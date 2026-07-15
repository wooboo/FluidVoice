import Foundation

@MainActor
final class RemoteStreamingCaptureService {
    struct Completion {
        let input: RemoteAPI.StreamStartInput
        let rawText: String
    }

    private struct Session {
        let ownerCredential: String
        let input: RemoteAPI.StreamStartInput
        let startedAt: TimeInterval
        var samples: [Float]
        var processedSampleCount: Int
        var transcript: String
        var preview: Preview?
    }

    private struct Preview {
        let id: UUID
        let throughSampleCount: Int
        let task: Task<String, Error>
    }

    private var sessions: [UUID: Session] = [:]
    private let asr: ASRService

    init(asr: ASRService) {
        self.asr = asr
    }

    func start(input: RemoteAPI.StreamStartInput, credential: String) async throws -> UUID {
        self.removeExpiredSessions()
        guard !self.asr.isRunning else { throw CaptureError.busy }
        guard self.sessions.isEmpty else { throw CaptureError.busy }
        let id = UUID()
        self.sessions[id] = Session(
            ownerCredential: credential,
            input: input,
            startedAt: ProcessInfo.processInfo.systemUptime,
            samples: [],
            processedSampleCount: 0,
            transcript: "",
            preview: nil
        )
        return id
    }

    func append(id: UUID, pcm16LE: Data, credential: String) async throws {
        guard pcm16LE.count.isMultiple(of: 2), pcm16LE.count <= Self.maximumChunkBytes else {
            throw CaptureError.invalidChunk
        }
        guard var session = self.sessions[id], session.ownerCredential == credential else {
            throw CaptureError.notFound
        }
        let addedSamples = Self.samples(fromPCM16LE: pcm16LE)
        guard session.samples.count + addedSamples.count <= Self.maximumSamples else {
            session.preview?.task.cancel()
            self.sessions[id] = nil
            throw CaptureError.tooLong
        }
        session.samples.append(contentsOf: addedSamples)
        self.sessions[id] = session
        if session.preview != nil {
            try await self.consumePreview(id: id, credential: credential)
        }
        self.schedulePreviewIfNeeded(id: id, credential: credential)
    }

    func finish(id: UUID, credential: String) async throws -> Completion {
        guard self.sessions[id]?.ownerCredential == credential,
              var session = self.sessions.removeValue(forKey: id)
        else {
            throw CaptureError.notFound
        }
        guard !session.samples.isEmpty else { throw CaptureError.empty }

        if let preview = session.preview {
            let previewText = try await preview.task.value
            session.transcript = Self.merging(session.transcript, with: previewText)
            session.processedSampleCount = preview.throughSampleCount
            session.preview = nil
        }

        let text: String
        if session.transcript.isEmpty {
            text = (try await self.asr.transcribeSamplesForAPI(session.samples)).text
        } else if session.processedSampleCount == session.samples.count {
            text = session.transcript
        } else {
            let windowStart = max(0, session.processedSampleCount - Self.overlapSamples)
            let tail = (try await self.asr.transcribeSamplesForAPI(Array(session.samples[windowStart...]))).text
            text = StreamingTranscriptMerger.mergeBestEffort(prefix: session.transcript, overlappingSuffix: tail)
        }
        return Completion(input: session.input, rawText: text)
    }

    func cancel(id: UUID, credential: String) {
        guard self.sessions[id]?.ownerCredential == credential else { return }
        self.sessions[id]?.preview?.task.cancel()
        self.sessions[id] = nil
    }

    private func consumePreview(id: UUID, credential: String) async throws {
        guard let preview = self.sessions[id]?.preview else { return }
        let text = try await preview.task.value
        guard var session = self.sessions[id],
              session.ownerCredential == credential
        else { throw CaptureError.notFound }
        guard session.preview?.id == preview.id else { return }
        session.transcript = Self.merging(session.transcript, with: text)
        session.processedSampleCount = preview.throughSampleCount
        session.preview = nil
        self.sessions[id] = session
    }

    private func schedulePreviewIfNeeded(id: UUID, credential: String) {
        guard var session = self.sessions[id],
              session.ownerCredential == credential,
              session.preview == nil,
              session.samples.count - session.processedSampleCount >= Self.segmentSamples
        else { return }
        let windowStart = max(0, session.processedSampleCount - Self.overlapSamples)
        let window = Array(session.samples[windowStart...])
        let previewID = UUID()
        session.preview = Preview(
            id: previewID,
            throughSampleCount: session.samples.count,
            task: Task { [asr] in
                try await asr.transcribeSamplesForAPI(window).text
            }
        )
        self.sessions[id] = session
    }

    private static func merging(_ prefix: String, with suffix: String) -> String {
        let trimmed = suffix.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prefix.isEmpty else { return trimmed }
        return StreamingTranscriptMerger.mergeBestEffort(prefix: prefix, overlappingSuffix: trimmed)
    }

    private func removeExpiredSessions() {
        let now = ProcessInfo.processInfo.systemUptime
        let expired = self.sessions.filter { now - $0.value.startedAt >= Self.maximumAgeSeconds }
        expired.values.forEach { $0.preview?.task.cancel() }
        self.sessions = self.sessions.filter { now - $0.value.startedAt < Self.maximumAgeSeconds }
    }

    private static func samples(fromPCM16LE data: Data) -> [Float] {
        var samples: [Float] = []
        samples.reserveCapacity(data.count / 2)
        data.withUnsafeBytes { bytes in
            for offset in stride(from: 0, to: bytes.count, by: 2) {
                let value = UInt16(bytes[offset]) | (UInt16(bytes[offset + 1]) << 8)
                samples.append(Float(Int16(bitPattern: value)) / 32_768)
            }
        }
        return samples
    }

    private static let maximumChunkBytes = 128 * 1024
    // Keep the retained PCM below the legacy endpoint's 25 MB request limit so
    // the Android client can always fall back to one final WAV upload.
    private static let maximumSamples = 16_000 * 60 * 12
    private static let segmentSamples = 16_000 * 8
    private static let overlapSamples = 16_000 * 4
    private static let maximumAgeSeconds: TimeInterval = 20 * 60

    enum CaptureError: LocalizedError {
        case busy
        case empty
        case invalidChunk
        case notFound
        case tooLong

        var errorDescription: String? {
            switch self {
            case .busy: return "Another remote streaming capture is active."
            case .empty: return "The streaming capture contains no audio."
            case .invalidChunk: return "The PCM audio chunk is invalid."
            case .notFound: return "The streaming capture was not found."
            case .tooLong: return "The streaming capture is too long."
            }
        }
    }
}

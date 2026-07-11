import Foundation

/// Smart Notes have their own enhancement toggle and must not inherit dictation prompt state.
enum SmartNoteAIPostProcessingGate {
    static func isConfigured() -> Bool {
        DictationAIPostProcessingGate.isProviderConfigured()
    }
}

@MainActor
final class SmartNoteCaptureService {
    static let shared = SmartNoteCaptureService()

    static let enhancementPrompt = """
    Turn the voice transcript into a useful structured note without inventing facts or adding advice.
    Remove fillers and false starts, preserve the speaker's meaning, and use concise Markdown in the body.
    Choose one short category and up to eight lowercase tags.

    Return only valid JSON with exactly this shape:
    {"title":"Short descriptive title","category":"Category","tags":["tag"],"body":"Markdown note body"}
    Do not wrap the JSON in a code fence.
    """

    private init() {}

    func capture(rawText: String, enhance: Bool) async throws -> RemoteAPI.SmartNoteCaptureResponse {
        var note = try SmartNotesStore.shared.capture(rawText: rawText)
        var enhancementError: String?

        if enhance {
            let providerConfigured = SmartNoteAIPostProcessingGate.isConfigured()
            DebugLogger.shared.info(
                "Remote Smart Notes enhancement requested=true providerConfigured=\(providerConfigured)",
                source: "SmartNoteCaptureService"
            )

            if providerConfigured {
                do {
                    let response = try await DictationPostProcessingService.shared.process(
                        rawText,
                        promptOverride: Self.enhancementPrompt
                    ).text
                    let enhancement = try SmartNoteEnhancement.parseAIResponse(response)
                    note = try SmartNotesStore.shared.apply(enhancement, to: note.id)
                } catch {
                    enhancementError = error.localizedDescription
                    DebugLogger.shared.error(
                        "Remote Smart Notes enrichment failed; raw note preserved: \(error.localizedDescription)",
                        source: "SmartNoteCaptureService"
                    )
                }
            } else {
                enhancementError = "The selected AI provider is not configured or verified."
                DebugLogger.shared.error(
                    "Remote Smart Notes enhancement skipped because the AI provider is unavailable; raw note preserved",
                    source: "SmartNoteCaptureService"
                )
            }
        } else {
            DebugLogger.shared.info(
                "Remote Smart Notes enhancement requested=false",
                source: "SmartNoteCaptureService"
            )
        }

        return .init(note: Self.response(for: note), rawText: rawText, enhancementError: enhancementError)
    }

    func notes(limit: Int = 100) -> [RemoteAPI.SmartNoteResponse] {
        SmartNotesStore.shared.reload()
        return SmartNotesStore.shared.notes.prefix(limit).map(Self.response)
    }

    func delete(id: UUID) throws {
        SmartNotesStore.shared.reload()
        guard let note = SmartNotesStore.shared.notes.first(where: { $0.id == id }) else {
            throw SmartNotesError.noteNotFound
        }
        try SmartNotesStore.shared.delete(note)
    }

    private static func response(for note: SmartNote) -> RemoteAPI.SmartNoteResponse {
        .init(
            id: note.id.uuidString.lowercased(),
            createdAt: Self.iso8601.string(from: note.createdAt),
            title: note.title,
            category: note.category,
            tags: note.tags,
            body: note.body,
            isAIEnhanced: note.isAIEnhanced
        )
    }

    private static let iso8601: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}

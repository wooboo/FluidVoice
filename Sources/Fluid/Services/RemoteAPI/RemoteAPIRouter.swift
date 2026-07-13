import Foundation

@MainActor
final class RemoteAPIRouter {
    typealias Dictate = (RemoteAPI.DictateInput) async throws -> RemoteAPI.DictateResponse
    typealias CaptureNote = (RemoteAPI.DictateInput) async throws -> RemoteAPI.SmartNoteCaptureResponse
    typealias ListNotes = () -> [RemoteAPI.SmartNoteResponse]
    typealias DeleteNote = (UUID) throws -> Void

    private let pairing: RemotePairingCoordinator
    private let dictate: Dictate
    private let captureNote: CaptureNote
    private let listNotes: ListNotes
    private let deleteNote: DeleteNote

    init(
        pairing: RemotePairingCoordinator,
        dictate: @escaping Dictate,
        captureNote: @escaping CaptureNote = { _ in throw SmartNotesError.emptyTranscript },
        listNotes: @escaping ListNotes = { [] },
        deleteNote: @escaping DeleteNote = { _ in throw SmartNotesError.noteNotFound }
    ) {
        self.pairing = pairing
        self.dictate = dictate
        self.captureNote = captureNote
        self.listNotes = listNotes
        self.deleteNote = deleteNote
    }

    func route(_ request: RemoteAPI.Request) async -> RemoteAPI.Response {
        switch (request.method, request.path) {
        case ("POST", "/remote/v1/pair"):
            return self.pair(request)
        case ("POST", "/remote/v1/preconnect"):
            return self.preconnect(request)
        case ("POST", "/remote/v1/dictate"):
            return await self.handleDictate(request)
        case ("GET", "/remote/v1/notes"):
            return self.handleListNotes(request)
        case ("POST", "/remote/v1/notes"):
            return await self.handleCaptureNote(request)
        case ("DELETE", let path) where path.hasPrefix(Self.notesPathPrefix):
            return self.handleDeleteNote(request, path: path)
        default:
            return RemoteAPI.error("Route not found.", status: 404)
        }
    }

    private func handleDeleteNote(_ request: RemoteAPI.Request, path: String) -> RemoteAPI.Response {
        guard let credential = Self.bearerCredential(from: request), self.pairing.authorizes(credential) else {
            return RemoteAPI.error("Unauthorized.", status: 401)
        }
        let idText = String(path.dropFirst(Self.notesPathPrefix.count))
        guard !idText.contains("/"), let noteID = UUID(uuidString: idText) else {
            return RemoteAPI.error("Invalid note ID.", status: 400)
        }

        do {
            try self.deleteNote(noteID)
            return RemoteAPI.Response(status: 204, headers: [:], body: Data())
        } catch SmartNotesError.noteNotFound {
            return RemoteAPI.error("Note not found.", status: 404)
        } catch {
            return RemoteAPI.error(error.localizedDescription, status: 400)
        }
    }

    private func handleListNotes(_ request: RemoteAPI.Request) -> RemoteAPI.Response {
        guard let credential = Self.bearerCredential(from: request), self.pairing.authorizes(credential) else {
            return RemoteAPI.error("Unauthorized.", status: 401)
        }
        return RemoteAPI.json(RemoteAPI.SmartNotesListResponse(notes: self.listNotes()))
    }

    private func handleCaptureNote(_ request: RemoteAPI.Request) async -> RemoteAPI.Response {
        let requestID = Self.requestID(from: request)
        guard let credential = Self.bearerCredential(from: request), self.pairing.authorizes(credential) else {
            Self.log(requestID, "note_auth_rejected")
            return RemoteAPI.error("Unauthorized.", status: 401)
        }
        guard !request.body.isEmpty else {
            return RemoteAPI.error("Missing audio body.", status: 400)
        }

        do {
            let wantsEnhancement = request.headers["x-fluidvoice-enhance"]?.lowercased() != "false"
            let response = try await self.captureNote(.init(
                audio: request.body,
                audioFileExtension: Self.audioFileExtension(from: request),
                wantsEnhancement: wantsEnhancement,
                requestID: requestID
            ))
            return RemoteAPI.json(response, status: 201)
        } catch {
            Self.log(requestID, "note_failed error=\(error.localizedDescription)")
            return RemoteAPI.error(error.localizedDescription, status: 400)
        }
    }

    private func preconnect(_ request: RemoteAPI.Request) -> RemoteAPI.Response {
        let requestID = Self.requestID(from: request)
        guard let credential = Self.bearerCredential(from: request), self.pairing.authorizes(credential) else {
            Self.log(requestID, "preconnect_auth_rejected")
            return RemoteAPI.error("Unauthorized.", status: 401)
        }
        Self.log(requestID, "preconnect_ready")
        return RemoteAPI.Response(status: 204, headers: [:], body: Data())
    }

    private func pair(_ request: RemoteAPI.Request) -> RemoteAPI.Response {
        guard request.headers["content-type"]?.lowercased().contains("application/json") == true,
              let payload = try? JSONDecoder().decode(RemoteAPI.PairRequest.self, from: request.body)
        else {
            return RemoteAPI.error("Invalid pairing request.", status: 400)
        }

        do {
            return RemoteAPI.json(try self.pairing.complete(payload), status: 201)
        } catch RemotePairingError.invalidOrExpiredSecret {
            return RemoteAPI.error("Pairing secret is invalid or expired.", status: 403)
        } catch {
            return RemoteAPI.error("Invalid pairing request.", status: 400)
        }
    }

    private func handleDictate(_ request: RemoteAPI.Request) async -> RemoteAPI.Response {
        let requestID = Self.requestID(from: request)
        guard let credential = Self.bearerCredential(from: request), self.pairing.authorizes(credential) else {
            Self.log(requestID, "auth_rejected")
            return RemoteAPI.error("Unauthorized.", status: 401)
        }
        guard !request.body.isEmpty else {
            Self.log(requestID, "empty_audio")
            return RemoteAPI.error("Missing audio body.", status: 400)
        }

        do {
            let wantsEnhancement = request.headers["x-fluidvoice-enhance"]?.lowercased() != "false"
            Self.log(requestID, "dispatch bytes=\(request.body.count) enhance=\(wantsEnhancement)")
            return RemoteAPI.json(try await self.dictate(.init(
                audio: request.body,
                audioFileExtension: Self.audioFileExtension(from: request),
                wantsEnhancement: wantsEnhancement,
                inputContext: wantsEnhancement ? Self.inputContext(from: request) : nil,
                requestID: requestID
            )))
        } catch {
            Self.log(requestID, "failed error=\(error.localizedDescription)")
            return RemoteAPI.error(error.localizedDescription, status: 400)
        }
    }

    private static func bearerCredential(from request: RemoteAPI.Request) -> String? {
        guard let authorization = request.headers["authorization"] else { return nil }
        let parts = authorization.split(separator: " ", maxSplits: 1).map(String.init)
        guard parts.count == 2, parts[0].caseInsensitiveCompare("Bearer") == .orderedSame else { return nil }
        return parts[1]
    }

    static func requestID(from request: RemoteAPI.Request) -> String {
        let candidate = request.headers["x-request-id"] ?? UUID().uuidString.lowercased()
        let allowed = candidate.filter { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }
        return allowed.isEmpty ? UUID().uuidString.lowercased() : String(allowed.prefix(64))
    }

    private static func log(_ requestID: String, _ message: String) {
        DebugLogger.shared.info("REMOTE_BENCH id=\(requestID) \(message)", source: "RemoteAPIBenchmark")
    }

    private static func audioFileExtension(from request: RemoteAPI.Request) -> String {
        let contentType = request.headers["content-type"]?.lowercased() ?? ""
        if contentType.contains("audio/mp4") || contentType.contains("audio/m4a") { return "m4a" }
        return "wav"
    }

    private static func inputContext(from request: RemoteAPI.Request) -> RemoteAPI.InputFieldContext? {
        guard let encoded = request.headers["x-fluidvoice-input-context"],
              encoded.utf8.count <= 2_048,
              let data = Data(base64Encoded: encoded),
              let decoded = try? JSONDecoder().decode(RemoteAPI.InputFieldContext.self, from: data)
        else { return nil }

        let label = Self.contextValue(decoded.label)
        let placeholder = Self.contextValue(decoded.placeholder)
        guard label != nil || placeholder != nil else { return nil }
        return RemoteAPI.InputFieldContext(label: label, placeholder: placeholder)
    }

    private static func contextValue(_ value: String?) -> String? {
        guard let value else { return nil }
        return String(value.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ").prefix(200))
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nonEmpty
    }

    private static let notesPathPrefix = "/remote/v1/notes/"
}

private extension String {
    var nonEmpty: String? { self.isEmpty ? nil : self }
}

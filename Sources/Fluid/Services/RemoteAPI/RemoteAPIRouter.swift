import Foundation

@MainActor
final class RemoteAPIRouter {
    typealias Dictate = (RemoteAPI.DictateInput) async throws -> RemoteAPI.DictateResponse

    private let pairing: RemotePairingCoordinator
    private let dictate: Dictate

    init(pairing: RemotePairingCoordinator, dictate: @escaping Dictate) {
        self.pairing = pairing
        self.dictate = dictate
    }

    func route(_ request: RemoteAPI.Request) async -> RemoteAPI.Response {
        switch (request.method, request.path) {
        case ("POST", "/remote/v1/pair"):
            return self.pair(request)
        case ("POST", "/remote/v1/dictate"):
            return await self.handleDictate(request)
        default:
            return RemoteAPI.error("Route not found.", status: 404)
        }
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
}

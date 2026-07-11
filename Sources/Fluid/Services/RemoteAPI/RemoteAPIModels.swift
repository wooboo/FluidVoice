import Foundation

enum RemoteAPI {
    static let defaultPort: UInt16 = 47_734
    static let maxRequestBytes = 25 * 1024 * 1024

    struct Request {
        let method: String
        let path: String
        let headers: [String: String]
        let body: Data
        let receivedAt: TimeInterval

        init(
            method: String,
            path: String,
            headers: [String: String] = [:],
            body: Data = Data(),
            receivedAt: TimeInterval = ProcessInfo.processInfo.systemUptime
        ) {
            self.method = method.uppercased()
            self.path = path
            self.headers = headers.reduce(into: [:]) { result, item in
                result[item.key.lowercased()] = item.value
            }
            self.body = body
            self.receivedAt = receivedAt
        }
    }

    struct Response {
        let status: Int
        let headers: [String: String]
        let body: Data
    }

    struct PairRequest: Codable {
        let deviceID: String
        let deviceName: String
        let pairingSecret: String
    }

    struct PairResponse: Codable {
        let credential: String
    }

    struct PairingPayload: Codable {
        let version: Int
        let baseURL: String
        let instanceID: String
        let pairingSecret: String
        let certificateSHA256: String
    }

    struct DictateResponse: Codable, Equatable {
        let rawText: String
        let finalText: String
        var enhancementError: String?

        init(rawText: String, finalText: String, enhancementError: String? = nil) {
            self.rawText = rawText
            self.finalText = finalText
            self.enhancementError = enhancementError
        }
    }

    struct DictateInput {
        let audio: Data
        let audioFileExtension: String
        let wantsEnhancement: Bool
        let requestID: String
    }

    struct SmartNoteResponse: Codable, Equatable {
        let id: String
        let createdAt: String
        let title: String
        let category: String?
        let tags: [String]
        let body: String
        let isAIEnhanced: Bool
    }

    struct SmartNotesListResponse: Codable, Equatable {
        let notes: [SmartNoteResponse]
    }

    struct SmartNoteCaptureResponse: Codable, Equatable {
        let note: SmartNoteResponse
        let rawText: String
        let enhancementError: String?
    }

    private struct ErrorResponse: Encodable {
        let error: String
    }

    static func json<T: Encodable>(_ value: T, status: Int = 200) -> Response {
        do {
            return Response(
                status: status,
                headers: ["Content-Type": "application/json; charset=utf-8"],
                body: try JSONEncoder().encode(value)
            )
        } catch {
            return self.error("Failed to encode response.", status: 500)
        }
    }

    static func error(_ message: String, status: Int) -> Response {
        let body = (try? JSONEncoder().encode(ErrorResponse(error: message))) ?? Data()
        return Response(status: status, headers: ["Content-Type": "application/json; charset=utf-8"], body: body)
    }
}

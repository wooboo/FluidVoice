import Combine
import Darwin
import Foundation
import Network
import Security

@MainActor
final class RemoteAPIServer: ObservableObject {
    static let shared = RemoteAPIServer()

    @Published private(set) var isRunning = false
    @Published private(set) var lastError: String?

    private let queue = DispatchQueue(label: "fluidvoice.remote-api", qos: .utility)
    private let store = RemoteDeviceKeychainStore()
    private lazy var pairing = RemotePairingCoordinator(store: self.store)
    private lazy var router = RemoteAPIRouter(
        pairing: self.pairing,
        dictate: Self.dictate,
        captureNote: Self.captureNote,
        listNotes: { SmartNoteCaptureService.shared.notes() },
        deleteNote: { try SmartNoteCaptureService.shared.delete(id: $0) }
    )
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: RemoteAPIConnectionHandler] = [:]
    private var tlsIdentity: RemoteTLSIdentity?
    private var retryTask: Task<Void, Never>?
    private let configuredPort: UInt16
    private let isEnabledProvider: () -> Bool
    private let retryDelayNanoseconds: UInt64
    private let maxRequestBytes: Int

    var isEnabled: Bool {
        get { self.isEnabledProvider() }
        set {
            UserDefaults.standard.set(newValue, forKey: "RemoteAPIEnabled")
            newValue ? self.start() : self.stop()
            self.objectWillChange.send()
        }
    }

    var port: UInt16 { self.configuredPort }

    private init() {
        self.configuredPort = RemoteAPI.defaultPort
        self.isEnabledProvider = { UserDefaults.standard.bool(forKey: "RemoteAPIEnabled") }
        self.retryDelayNanoseconds = 1_000_000_000
        self.maxRequestBytes = RemoteAPI.maxRequestBytes
    }

    init(
        port: UInt16,
        retryDelayNanoseconds: UInt64,
        maxRequestBytes: Int = RemoteAPI.maxRequestBytes,
        isEnabled: @escaping () -> Bool
    ) {
        self.configuredPort = port
        self.isEnabledProvider = isEnabled
        self.retryDelayNanoseconds = retryDelayNanoseconds
        self.maxRequestBytes = maxRequestBytes
    }

    func start() {
        guard self.isEnabled, self.listener == nil else { return }
        self.retryTask?.cancel()
        self.retryTask = nil
        do {
            let identity = try RemoteTLSIdentityStore.loadOrCreate()
            let tls = NWProtocolTLS.Options()
            guard let protocolIdentity = sec_identity_create(identity.identity) else {
                throw NSError(domain: "RemoteAPIServer", code: -1, userInfo: [NSLocalizedDescriptionKey: "Unable to configure TLS identity."])
            }
            sec_protocol_options_set_local_identity(tls.securityProtocolOptions, protocolIdentity)
            sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
            sec_protocol_options_set_max_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)

            let listener = try NWListener(
                using: NWParameters(tls: tls, tcp: NWProtocolTCP.Options()),
                on: NWEndpoint.Port(rawValue: self.port)!
            )
            listener.newConnectionHandler = { [weak self] connection in
                Task { @MainActor [weak self] in self?.accept(connection) }
            }
            listener.stateUpdateHandler = { [weak self, weak listener] state in
                Task { @MainActor [weak self, weak listener] in
                    guard let self, let listener, self.listener === listener else { return }
                    self.handle(state)
                }
            }
            self.tlsIdentity = identity
            self.listener = listener
            self.lastError = nil
            listener.start(queue: self.queue)
        } catch {
            self.lastError = error.localizedDescription
            DebugLogger.shared.error("Remote API failed to start: \(error.localizedDescription)", source: "RemoteAPIServer")
        }
    }

    func stop() {
        self.retryTask?.cancel()
        self.retryTask = nil
        self.pairing.cancel()
        self.connections.values.forEach { $0.cancel() }
        self.connections.removeAll()
        self.listener?.stateUpdateHandler = nil
        self.listener?.cancel()
        self.listener = nil
        self.isRunning = false
    }

    func beginPairing() throws -> RemoteAPI.PairingPayload {
        guard self.isRunning, let identity = self.tlsIdentity else {
            throw NSError(domain: "RemoteAPIServer", code: -2, userInfo: [NSLocalizedDescriptionKey: "Remote access is not running."])
        }
        guard let host = Self.localIPv4Address() else {
            throw NSError(domain: "RemoteAPIServer", code: -3, userInfo: [NSLocalizedDescriptionKey: "No local network address is available."])
        }
        let secret = self.pairing.begin()
        return RemoteAPI.PairingPayload(
            version: 1,
            baseURL: "https://\(host):\(self.port)",
            instanceID: Self.instanceID,
            pairingSecret: secret,
            certificateSHA256: identity.fingerprint
        )
    }

    func pairedDevices() -> [RemoteDevice] {
        (try? self.store.allDevices()) ?? []
    }

    func cancelPairing() {
        self.pairing.cancel()
    }

    func revoke(deviceID: String) throws {
        try self.store.revoke(deviceID: deviceID)
        self.objectWillChange.send()
    }

    private func accept(_ connection: NWConnection) {
        let handler = RemoteAPIConnectionHandler(
            connection: connection,
            router: self.router,
            maxRequestBytes: self.maxRequestBytes
        )
        let id = ObjectIdentifier(handler)
        handler.onClose = { [weak self] in
            Task { @MainActor [weak self] in self?.connections[id] = nil }
        }
        self.connections[id] = handler
        handler.start(on: self.queue)
    }

    private func handle(_ state: NWListener.State) {
        switch state {
        case .ready:
            self.isRunning = true
            self.lastError = nil
            DebugLogger.shared.info("Remote API listening on HTTPS port \(self.port)", source: "RemoteAPIServer")
        case let .waiting(error):
            self.recoverListener(after: error)
        case let .failed(error):
            self.recoverListener(after: error)
        case .cancelled:
            self.isRunning = false
        default:
            break
        }
    }

    private func recoverListener(after error: NWError) {
        self.lastError = error.localizedDescription
        self.isRunning = false
        self.connections.values.forEach { $0.cancel() }
        self.connections.removeAll()
        self.listener?.stateUpdateHandler = nil
        self.listener?.cancel()
        self.listener = nil

        guard self.isEnabled else { return }
        DebugLogger.shared.warning(
            "Remote API listener unavailable; retrying: \(error.localizedDescription)",
            source: "RemoteAPIServer"
        )
        self.scheduleRetry()
    }

    private func scheduleRetry() {
        self.retryTask?.cancel()
        self.retryTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await Task.sleep(nanoseconds: self.retryDelayNanoseconds)
            } catch {
                return
            }
            self.retryTask = nil
            self.start()
        }
    }

    private static func dictate(input: RemoteAPI.DictateInput) async throws -> RemoteAPI.DictateResponse {
        let startedAt = ProcessInfo.processInfo.systemUptime
        Self.log(input.requestID, "pipeline_start bytes=\(input.audio.count) format=\(input.audioFileExtension)")
        let samples = try LocalAPIAudioDecoder.samples(
            fromAudioData: input.audio,
            suggestedExtension: input.audioFileExtension
        )
        let decodedAt = ProcessInfo.processInfo.systemUptime
        Self.log(input.requestID, "decode_done samples=\(samples.count) elapsedMs=\(Self.milliseconds(from: startedAt, to: decodedAt))")
        let transcription = try await AppServices.shared.asr.transcribeSamplesForAPI(samples)
        let transcribedAt = ProcessInfo.processInfo.systemUptime
        let raw = transcription.text
        Self.log(
            input.requestID,
            "asr_done elapsedMs=\(Self.milliseconds(from: decodedAt, to: transcribedAt)) chars=\(raw.count)"
        )
        guard input.wantsEnhancement, DictationAIPostProcessingGate.isConfigured(for: .primary) else {
            Self.log(input.requestID, "pipeline_done enhancement=skipped totalMs=\(Self.milliseconds(from: startedAt))")
            return RemoteAPI.DictateResponse(rawText: raw, finalText: raw)
        }
        do {
            Self.log(input.requestID, "enhancement_start")
            let enhanced = try await DictationPostProcessingService.shared.process(
                raw,
                inputContext: input.inputContext
            ).text
            Self.log(
                input.requestID,
                "enhancement_done elapsedMs=\(Self.milliseconds(from: transcribedAt)) totalMs=\(Self.milliseconds(from: startedAt))"
            )
            return RemoteAPI.DictateResponse(rawText: raw, finalText: enhanced)
        } catch {
            Self.log(
                input.requestID,
                "enhancement_failed elapsedMs=\(Self.milliseconds(from: transcribedAt)) error=\(error.localizedDescription)"
            )
            return RemoteAPI.DictateResponse(
                rawText: raw,
                finalText: raw,
                enhancementError: error.localizedDescription
            )
        }
    }

    private static func captureNote(input: RemoteAPI.DictateInput) async throws -> RemoteAPI.SmartNoteCaptureResponse {
        let startedAt = ProcessInfo.processInfo.systemUptime
        Self.log(input.requestID, "note_pipeline_start bytes=\(input.audio.count) format=\(input.audioFileExtension)")
        let samples = try LocalAPIAudioDecoder.samples(
            fromAudioData: input.audio,
            suggestedExtension: input.audioFileExtension
        )
        let transcription = try await AppServices.shared.asr.transcribeSamplesForAPI(samples)
        Self.log(
            input.requestID,
            "note_asr_done elapsedMs=\(Self.milliseconds(from: startedAt)) chars=\(transcription.text.count)"
        )
        let response = try await SmartNoteCaptureService.shared.capture(
            rawText: transcription.text,
            enhance: input.wantsEnhancement
        )
        Self.log(
            input.requestID,
            "note_pipeline_done enhanced=\(response.note.isAIEnhanced) totalMs=\(Self.milliseconds(from: startedAt))"
        )
        return response
    }

    private static func milliseconds(from start: TimeInterval, to end: TimeInterval = ProcessInfo.processInfo.systemUptime) -> Int {
        Int(((end - start) * 1_000).rounded())
    }

    private static func log(_ requestID: String, _ message: String) {
        DebugLogger.shared.info("REMOTE_BENCH id=\(requestID) \(message)", source: "RemoteAPIBenchmark")
    }

    private static var instanceID: String {
        let key = "RemoteAPIInstanceID"
        if let existing = UserDefaults.standard.string(forKey: key) { return existing }
        let created = UUID().uuidString.lowercased()
        UserDefaults.standard.set(created, forKey: key)
        return created
    }

    private nonisolated static func localIPv4Address() -> String? {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let first = interfaces else { return nil }
        defer { freeifaddrs(interfaces) }

        var candidates: [(name: String, address: String)] = []
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let interface = pointer.pointee
            guard let socketAddress = interface.ifa_addr,
                  socketAddress.pointee.sa_family == UInt8(AF_INET),
                  interface.ifa_flags & UInt32(IFF_UP) != 0,
                  interface.ifa_flags & UInt32(IFF_LOOPBACK) == 0
            else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(
                socketAddress,
                socklen_t(socketAddress.pointee.sa_len),
                &host,
                socklen_t(host.count),
                nil,
                0,
                NI_NUMERICHOST
            ) == 0 else { continue }
            candidates.append((String(cString: interface.ifa_name), String(cString: host)))
        }
        let preferredNames = ["en0", "en1"]
        return candidates.min { lhs, rhs in
            (preferredNames.firstIndex(of: lhs.name) ?? preferredNames.count) <
                (preferredNames.firstIndex(of: rhs.name) ?? preferredNames.count)
        }?.address
    }
}

@MainActor
private final class RemoteAPIConnectionHandler {
    private let connection: NWConnection
    private let router: RemoteAPIRouter
    private let maxRequestBytes: Int
    private var buffer = Data()
    private var closed = false
    private var requestStartedAt: TimeInterval?
    private var activeRequestID: String?
    private var requestCount = 0
    private var idleTimeoutTask: Task<Void, Never>?
    var onClose: (() -> Void)?

    init(connection: NWConnection, router: RemoteAPIRouter, maxRequestBytes: Int) {
        self.connection = connection
        self.router = router
        self.maxRequestBytes = maxRequestBytes
    }

    func start(on queue: DispatchQueue) {
        self.connection.start(queue: queue)
        self.armIdleTimeout()
        self.receive()
    }

    func cancel() { self.close() }

    private func receive() {
        self.connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, complete, error in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if error != nil || complete { self.close(); return }
                if let data, !data.isEmpty {
                    self.idleTimeoutTask?.cancel()
                    if self.requestStartedAt == nil { self.requestStartedAt = ProcessInfo.processInfo.systemUptime }
                    self.buffer.append(data)
                }
                guard self.buffer.count <= self.maxRequestBytes else {
                    self.buffer = Data()
                    self.send(RemoteAPI.error("Request too large.", status: 413), closeAfterResponse: true)
                    return
                }
                switch self.parseRequest() {
                case let .request(request):
                    let requestID = RemoteAPIRouter.requestID(from: request)
                    self.activeRequestID = requestID
                    Self.log(
                        requestID,
                        "request_received bytes=\(request.body.count) uploadMs=\(Self.milliseconds(since: request.receivedAt)) connectionRequest=\(self.requestCount)"
                    )
                    self.send(await self.router.route(request))
                case let .failure(response):
                    self.buffer = Data()
                    self.send(response, closeAfterResponse: true)
                case .incomplete:
                    self.armIdleTimeout()
                    self.receive()
                }
            }
        }
    }

    private enum ParseResult {
        case incomplete
        case request(RemoteAPI.Request)
        case failure(RemoteAPI.Response)
    }

    private func parseRequest() -> ParseResult {
        guard let headerEnd = self.buffer.range(of: Data("\r\n\r\n".utf8)) else { return .incomplete }
        guard let text = String(data: self.buffer[..<headerEnd.lowerBound], encoding: .utf8) else {
            return .failure(RemoteAPI.error("Malformed request.", status: 400))
        }
        let lines = text.components(separatedBy: "\r\n")
        let requestLine = lines.first?.split(separator: " ").map(String.init) ?? []
        guard requestLine.count >= 2 else { return .failure(RemoteAPI.error("Malformed request.", status: 400)) }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let separator = line.firstIndex(of: ":") else { continue }
            headers[String(line[..<separator]).lowercased()] = line[line.index(after: separator)...]
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        let method = requestLine[0].uppercased()
        let length: Int
        if let rawLength = headers["content-length"], let parsedLength = Int(rawLength), parsedLength >= 0 {
            length = parsedLength
        } else if method == "GET" || method == "HEAD" || method == "DELETE" {
            length = 0
        } else {
            return .failure(RemoteAPI.error("Content-Length is required.", status: 411))
        }
        guard length <= self.maxRequestBytes else { return .failure(RemoteAPI.error("Request too large.", status: 413)) }
        let bodyStart = headerEnd.upperBound
        guard self.buffer.count >= bodyStart + length else { return .incomplete }
        let body = Data(self.buffer[bodyStart..<(bodyStart + length)])
        self.buffer.removeSubrange(..<(bodyStart + length))
        self.requestCount += 1
        return .request(RemoteAPI.Request(
            method: requestLine[0],
            path: requestLine[1],
            headers: headers,
            body: body,
            receivedAt: self.requestStartedAt ?? ProcessInfo.processInfo.systemUptime
        ))
    }

    private func send(_ response: RemoteAPI.Response, closeAfterResponse: Bool = false) {
        let requestStartedAt = self.requestStartedAt ?? ProcessInfo.processInfo.systemUptime
        let shouldKeepAlive = !closeAfterResponse && self.requestCount < 20
        if let requestID = self.activeRequestID {
            Self.log(
                requestID,
                "response_ready status=\(response.status) bytes=\(response.body.count) totalMs=\(Self.milliseconds(since: requestStartedAt)) keepAlive=\(shouldKeepAlive)"
            )
        }
        var headers = response.headers
        headers["Content-Length"] = String(response.body.count)
        headers["Connection"] = shouldKeepAlive ? "keep-alive" : "close"
        if shouldKeepAlive { headers["Keep-Alive"] = "timeout=30, max=20" }
        let reason = [200: "OK", 201: "Created", 204: "No Content", 400: "Bad Request", 401: "Unauthorized", 403: "Forbidden", 404: "Not Found", 411: "Length Required", 413: "Payload Too Large", 500: "Internal Server Error"][response.status] ?? "Response"
        var data = Data("HTTP/1.1 \(response.status) \(reason)\r\n".utf8)
        for (key, value) in headers.sorted(by: { $0.key < $1.key }) { data.append(Data("\(key): \(value)\r\n".utf8)) }
        data.append(Data("\r\n".utf8))
        data.append(response.body)
        self.connection.send(content: data, completion: .contentProcessed { [weak self] error in
            Task { @MainActor [weak self] in
                guard let self else { return }
                guard error == nil, shouldKeepAlive else { self.close(); return }
                self.activeRequestID = nil
                self.requestStartedAt = nil
                self.armIdleTimeout()
                self.receive()
            }
        })
    }

    private func close() {
        guard !self.closed else { return }
        self.closed = true
        self.idleTimeoutTask?.cancel()
        self.idleTimeoutTask = nil
        self.connection.cancel()
        self.onClose?()
    }

    private func armIdleTimeout() {
        self.idleTimeoutTask?.cancel()
        self.idleTimeoutTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 30_000_000_000)
            guard !Task.isCancelled else { return }
            self?.close()
        }
    }

    private static func milliseconds(since start: TimeInterval) -> Int {
        Int(((ProcessInfo.processInfo.systemUptime - start) * 1_000).rounded())
    }

    private static func log(_ requestID: String, _ message: String) {
        DebugLogger.shared.info("REMOTE_BENCH id=\(requestID) \(message)", source: "RemoteAPIBenchmark")
    }
}

import CoreImage.CIFilterBuiltins
import SwiftUI

struct RemoteAccessSettingsView: View {
    @ObservedObject private var server = RemoteAPIServer.shared
    @State private var pairingImage: NSImage?
    @State private var errorMessage: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Mobile Remote Access", systemImage: "iphone.and.arrow.forward")
                .font(.headline)

            Toggle("Allow paired phones on the local network", isOn: Binding(
                get: { self.server.isEnabled },
                set: { self.server.isEnabled = $0 }
            ))

            Text("Audio is sent directly to this Mac over pinned TLS 1.3. The existing local API remains loopback-only.")
                .font(.caption)
                .foregroundStyle(.secondary)

            if self.server.isEnabled {
                HStack {
                    Circle()
                        .fill(self.server.isRunning ? Color.green : Color.orange)
                        .frame(width: 8, height: 8)
                    Text(self.server.isRunning ? "Listening on port \(self.server.port)" : "Starting secure listener...")
                        .font(.caption)
                    Spacer()
                    Button("Pair Android device") { self.beginPairing() }
                        .disabled(!self.server.isRunning)
                }
            }

            if let error = self.errorMessage ?? self.server.lastError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            let devices = self.server.pairedDevices()
            if !devices.isEmpty {
                Divider()
                Text("Paired devices")
                    .font(.subheadline.weight(.semibold))
                ForEach(devices) { device in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(device.name)
                            Text("Paired \(device.pairedAt.formatted(date: .abbreviated, time: .shortened))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Revoke", role: .destructive) {
                            do { try self.server.revoke(deviceID: device.id) }
                            catch { self.errorMessage = error.localizedDescription }
                        }
                    }
                }
            }
        }
        .sheet(isPresented: Binding(
            get: { self.pairingImage != nil },
            set: {
                if !$0 {
                    self.server.cancelPairing()
                    self.pairingImage = nil
                }
            }
        )) {
            VStack(spacing: 18) {
                Text("Pair Android device")
                    .font(.title2.weight(.semibold))
                Text("Open FluidVoice Remote on Android and scan this code.")
                    .foregroundStyle(.secondary)
                if let image = self.pairingImage {
                    Image(nsImage: image)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 280, height: 280)
                }
                Text("This code can be used once. Close this window to cancel pairing.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("Cancel") {
                    self.server.cancelPairing()
                    self.pairingImage = nil
                }
            }
            .padding(28)
            .frame(width: 380)
        }
    }

    private func beginPairing() {
        do {
            let payload = try self.server.beginPairing()
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            let data = try encoder.encode(payload)
            guard let value = String(data: data, encoding: .utf8), let image = Self.qrImage(value) else {
                throw NSError(domain: "RemoteAccessSettings", code: -1, userInfo: [NSLocalizedDescriptionKey: "Could not generate pairing QR."])
            }
            self.pairingImage = image
            self.errorMessage = nil
        } catch {
            self.errorMessage = error.localizedDescription
        }
    }

    private static func qrImage(_ value: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(value.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)) else { return nil }
        let representation = NSCIImageRep(ciImage: output)
        let image = NSImage(size: representation.size)
        image.addRepresentation(representation)
        return image
    }
}

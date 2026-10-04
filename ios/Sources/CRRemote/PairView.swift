import SwiftUI
import AVFoundation

/// Scan the /pair QR, tap its link in Safari, or type broker + token.
struct PairView: View {
    @EnvironmentObject var model: RemoteModel
    @State private var broker = ""
    @State private var token = ""
    @State private var scanning = false
    @State private var checking = false
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Image(systemName: "play.rectangle").font(.system(size: 46)).foregroundStyle(C.orange)
                    .padding(.top, 40)
                Text("Pair CR Remote").font(.title2.bold())
                Text("On the PC, open **http://localhost:8774/pair** (or the extension's popup) and scan the QR. With Tailscale on, you can also open that page in Safari here and tap the button.")
                    .font(.callout).foregroundStyle(C.sub)

                Button { scanning = true } label: {
                    Label("Scan QR code", systemImage: "qrcode.viewfinder").bold()
                        .frame(maxWidth: .infinity).padding(.vertical, 14)
                        .background(C.orange, in: RoundedRectangle(cornerRadius: 12))
                        .foregroundStyle(.black)
                }

                Text("OR ENTER BY HAND").font(.caption2.bold()).foregroundStyle(C.sub).padding(.top, 8)
                field("Broker", "my-pc:8774", $broker)
                field("Token", "from ~/.config/cr-remote/token", $token, secure: true)
                if let error { Text(error).font(.footnote).foregroundStyle(C.error) }
                Button {
                    Task { await manual() }
                } label: {
                    HStack {
                        if checking { ProgressView() }
                        Text(checking ? "Checking…" : "Connect").bold()
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                    .background(C.variant, in: RoundedRectangle(cornerRadius: 12))
                }
                .disabled(broker.trimmed.isEmpty || token.trimmed.isEmpty || checking)
            }
            .padding(24)
        }
        .background(C.bg.ignoresSafeArea())
        .sheet(isPresented: $scanning) {
            QRScannerSheet { code in
                scanning = false
                if let p = Pairing.parse(code) {
                    model.pair(p)
                } else {
                    error = "That QR isn't a CR Remote pairing code."
                }
            }
        }
    }

    private func field(_ label: String, _ hint: String, _ text: Binding<String>, secure: Bool = false) -> some View {
        Group {
            if secure { SecureField(hint, text: text) } else { TextField(label, text: text, prompt: Text(hint)) }
        }
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .padding(12)
        .background(C.surface, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(C.outline))
    }

    private func manual() async {
        checking = true
        error = nil
        defer { checking = false }
        let p = Pairing(broker: Pairing.normalise(broker), token: token.trimmed)
        do {
            _ = try await Broker(pairing: p).hosts()
            model.pair(p)
        } catch {
            self.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }
}

// ── QR scanning ──────────────────────────────────────────────────────────────

private struct QRScannerSheet: View {
    let onCode: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var denied = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if denied {
                    VStack(spacing: 12) {
                        Text("Camera access is off").font(.headline)
                        Text("Allow it in Settings → CR Remote, or pair by hand.").foregroundStyle(C.sub)
                        Button("Open Settings") {
                            if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
                        }
                    }
                    .padding()
                } else {
                    QRScanner(onCode: onCode).ignoresSafeArea()
                    RoundedRectangle(cornerRadius: 16).stroke(C.orange, lineWidth: 3)
                        .frame(width: 240, height: 240)
                }
            }
            .navigationTitle("Scan pairing code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .task {
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: break
            case .notDetermined: denied = !(await AVCaptureDevice.requestAccess(for: .video))
            default: denied = true
            }
        }
    }
}

private struct QRScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerVC {
        let vc = ScannerVC()
        vc.onCode = onCode
        return vc
    }

    func updateUIViewController(_ vc: ScannerVC, context: Context) {}
}

final class ScannerVC: UIViewController, @preconcurrency AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var done = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let cam = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: cam),
              session.canAddInput(input) else { return }
        session.addInput(input)
        let out = AVCaptureMetadataOutput()
        guard session.canAddOutput(out) else { return }
        session.addOutput(out)
        out.setMetadataObjectsDelegate(self, queue: .main)
        out.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        preview = layer
        let s = session
        DispatchQueue.global(qos: .userInitiated).async { s.startRunning() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        let s = session
        DispatchQueue.global().async { s.stopRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !done,
              let code = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
        done = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onCode?(code)
    }
}

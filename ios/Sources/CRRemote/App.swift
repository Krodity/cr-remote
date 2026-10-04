import SwiftUI

enum C {
    static let orange = Color(red: 0xF4 / 255, green: 0x75 / 255, blue: 0x21 / 255)
    static let bg = Color(white: 0x0A / 255)
    static let surface = Color(white: 0x12 / 255)
    static let variant = Color(white: 0x1E / 255)
    static let text = Color(white: 0xEC / 255)
    static let sub = Color(white: 0xB4 / 255)
    static let outline = Color(white: 0x3A / 255)
    static let error = Color(red: 1, green: 0x8A / 255, blue: 0x80 / 255)
}

@main
struct CRRemoteApp: App {
    @StateObject private var model = RemoteModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            Group {
                if model.pairing.paired { MainView() } else { PairView() }
            }
            .environmentObject(model)
            .preferredColorScheme(.dark)
            .tint(C.orange)
            .onOpenURL { url in
                if let p = Pairing.parse(url.absoluteString) { model.pair(p) }
            }
        }
        .onChange(of: phase) { _, now in
            if now == .active { model.resume() }
        }
    }
}

struct MainView: View {
    @EnvironmentObject var model: RemoteModel
    @State private var confirmUnpair = false

    var body: some View {
        NavigationStack {
            BrowseView()
                .withChrome()
                .navigationTitle("Crunchyroll")
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) { hostMenu }
                }
                .navigationDestination(item: Binding(
                    get: { model.series }, set: { if $0 == nil { model.closeSeries() } })) { s in
                    SeriesView(series: s)
                }
        }
        .confirmationDialog("Forget this broker?", isPresented: $confirmUnpair, titleVisibility: .visible) {
            Button("Unpair", role: .destructive) { model.unpair() }
        }
    }

    /// Which PC's browser the remote drives.
    private var hostMenu: some View {
        Menu {
            Section("Browsers") {
                if model.hosts.isEmpty {
                    Text("No browser connected")
                }
                ForEach(model.hosts) { h in
                    Button {
                        model.selectHost(h.id)
                    } label: {
                        if model.pairing.host == h.id || (model.pairing.host.isEmpty && h.id == model.hosts.first?.id) {
                            Label(h.name, systemImage: "checkmark")
                        } else {
                            Text(h.name)
                        }
                    }
                }
            }
            Button { model.refreshHosts() } label: { Label("Refresh", systemImage: "arrow.clockwise") }
            Button(role: .destructive) { confirmUnpair = true } label: { Label("Unpair", systemImage: "link.badge.plus") }
        } label: {
            Image(systemName: model.hosts.isEmpty ? "desktopcomputer.trianglebadge.exclamationmark" : "desktopcomputer")
        }
    }
}

extension View {
    /// Error banner on top, player bar at the bottom. Applied per screen: a
    /// safeAreaInset on the NavigationStack itself is not passed down to
    /// pushed screens, so the episode list scrolled under the player bar.
    func withChrome() -> some View { modifier(Chrome()) }
}

private struct Chrome: ViewModifier {
    @EnvironmentObject var model: RemoteModel

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .top, spacing: 0) {
                if let e = model.error {
                    HStack(alignment: .top) {
                        Image(systemName: "exclamationmark.triangle.fill")
                        Text(e).frame(maxWidth: .infinity, alignment: .leading)
                        Button { model.error = nil } label: { Image(systemName: "xmark") }
                    }
                    .font(.footnote)
                    .foregroundStyle(.black)
                    .padding(10)
                    .background(C.error)
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) { PlayerBar() }
    }
}

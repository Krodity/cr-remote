import SwiftUI

/// The mini player pinned to the bottom: always a play/pause, expands to the
/// full transport.
struct PlayerBar: View {
    @EnvironmentObject var model: RemoteModel
    @State private var expanded = false
    @State private var scrub: Double?
    @State private var volume: Double?

    private var s: PlayerStatus { model.status }

    var body: some View {
        VStack(spacing: 0) {
            Rectangle().fill(C.outline).frame(height: 0.5)
            if s.hasVideo || model.busy {
                ProgressView(value: scrub ?? s.progress)
                    .progressViewStyle(.linear).tint(C.orange).scaleEffect(y: 0.6)
            }
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(subtitle).font(.caption).foregroundStyle(C.sub).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { withAnimation(.snappy) { expanded.toggle() } }

                if model.busy { ProgressView() }
                Button { model.control("toggle") } label: {
                    Image(systemName: s.playing ? "pause.fill" : "play.fill").font(.title2)
                }
                .disabled(!s.hasVideo)
                Button { withAnimation(.snappy) { expanded.toggle() } } label: {
                    Image(systemName: expanded ? "chevron.down" : "chevron.up")
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 10)

            if expanded { full }
        }
        .foregroundStyle(C.text)
        .background(.ultraThinMaterial)
    }

    private var title: String {
        if let t = model.nowPlaying?.title, model.nowPlaying?.id == s.episodeId || s.episodeId == nil {
            return [model.nowPlaying?.label ?? "", t].filter { !$0.isEmpty }.joined(separator: " · ")
        }
        if let t = s.pageTitle, !t.isEmpty { return t.replacingOccurrences(of: " - Crunchyroll", with: "") }
        return s.hasVideo ? "Playing" : "Nothing playing"
    }

    private var subtitle: String {
        if s.hasVideo { return "\(clock(scrub.map { $0 * s.duration } ?? s.position)) / \(clock(s.duration))" }
        return s.reason ?? (model.hosts.isEmpty ? "No browser connected" : "Pick an episode to play it on the PC")
    }

    private var full: some View {
        VStack(spacing: 14) {
            Slider(value: Binding(get: { scrub ?? s.progress }, set: { scrub = $0 }), in: 0...1) { editing in
                if !editing, let f = scrub {
                    model.seek(to: f)
                    scrub = nil
                }
            }
            .disabled(!s.hasVideo)

            HStack {
                ctrl("backward.end.fill", "Restart") { model.control("restart") }
                Spacer()
                ctrl("gobackward.10", "Back 10s") { model.control("back", 10) }
                Spacer()
                Button { model.control("toggle") } label: {
                    Image(systemName: s.playing ? "pause.fill" : "play.fill").font(.title)
                        .frame(width: 60, height: 60)
                        .background(C.orange, in: Circle()).foregroundStyle(.black)
                }
                .disabled(!s.hasVideo)
                Spacer()
                ctrl("goforward.10", "Forward 10s") { model.control("forward", 10) }
                Spacer()
                ctrl("forward.end.fill", "Next episode", enabled: true) { model.control("next") }
            }

            HStack {
                ctrl(s.muted ? "speaker.slash.fill" : "speaker.wave.2.fill", "Mute") { model.control("mute") }
                // One RPC on release, not one per drag tick.
                Slider(value: Binding(get: { volume ?? s.volume }, set: { volume = $0 }), in: 0...1) { editing in
                    if !editing, let v = volume {
                        model.control("volume", v)
                        volume = nil
                    }
                }
                .disabled(!s.hasVideo)
                ctrl(s.fullscreen ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right",
                     "Fullscreen") { model.control("fullscreen") }
                ctrl("hand.tap", "Dismiss 'still watching'", enabled: true) { model.control("dismiss") }
            }
        }
        .padding(.horizontal, 20).padding(.bottom, 14)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private func ctrl(_ icon: String, _ label: String, enabled: Bool? = nil,
                      _ action: @escaping () -> Void) -> some View {
        Button {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            action()
        } label: {
            Image(systemName: icon).font(.title3).frame(width: 40, height: 40)
        }
        .accessibilityLabel(label)
        .disabled(!(enabled ?? s.hasVideo))
    }
}

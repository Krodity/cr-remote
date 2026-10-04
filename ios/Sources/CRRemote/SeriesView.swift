import SwiftUI

struct SeriesView: View {
    @EnvironmentObject var model: RemoteModel
    let series: Series

    var body: some View {
        List {
            Section {
                HStack(alignment: .top, spacing: 14) {
                    Poster(url: series.poster)
                        .frame(width: 96, height: 144)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    VStack(alignment: .leading, spacing: 6) {
                        Text(series.title).font(.headline)
                        if let d = series.description, !d.isEmpty {
                            Text(d).font(.caption).foregroundStyle(C.sub).lineLimit(7)
                        }
                    }
                }
                .listRowBackground(C.bg)

                if model.seasons.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(model.seasons) { s in
                                let on = model.season?.id == s.id
                                Button { model.select(s) } label: {
                                    Text(s.label + (s.dubbed == true && s.subbed != true ? " · Dub" : ""))
                                        .font(.subheadline.weight(on ? .bold : .regular))
                                        .padding(.horizontal, 14).padding(.vertical, 7)
                                        .background(on ? C.orange : C.variant, in: Capsule())
                                        .foregroundStyle(on ? .black : C.text)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    .listRowBackground(C.bg)
                }
                if let t = model.season?.title, !t.isEmpty {
                    Text(t).font(.subheadline).foregroundStyle(C.sub).listRowBackground(C.bg)
                }
            }

            Section {
                if model.loadingSeries && model.episodes.isEmpty {
                    ProgressView().frame(maxWidth: .infinity).listRowBackground(C.bg)
                }
                ForEach(model.episodes) { ep in
                    Button { model.play(ep) } label: {
                        EpisodeRow(episode: ep, playhead: model.playheads[ep.id],
                                   current: model.status.episodeId == ep.id)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(C.bg)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(C.bg)
        .withChrome()
        .navigationTitle(series.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct EpisodeRow: View {
    let episode: Episode
    let playhead: Playhead?
    let current: Bool

    private var fraction: Double? {
        guard let p = playhead, let s = p.seconds, let d = episode.durationMs, d > 0 else { return nil }
        return p.finished == true ? 1 : min(s * 1000 / d, 1)
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            // The size lives on this ZStack, and the image and the progress bar
            // measure against it (the Compose build learnt this the hard way).
            ZStack(alignment: .bottomLeading) {
                Poster(url: episode.thumb)
                if let f = fraction {
                    GeometryReader { g in
                        Rectangle().fill(C.orange).frame(width: g.size.width * f, height: 3)
                            .frame(maxHeight: .infinity, alignment: .bottom)
                    }
                }
                if current {
                    Image(systemName: "play.fill").font(.caption).padding(5)
                        .background(C.orange, in: Circle()).foregroundStyle(.black)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .frame(width: 128, height: 72)
            .clipShape(RoundedRectangle(cornerRadius: 6))

            VStack(alignment: .leading, spacing: 3) {
                Text([episode.label, episode.title ?? ""].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.subheadline.weight(.semibold)).lineLimit(2)
                    .foregroundStyle(current ? C.orange : C.text)
                Text(meta).font(.caption).foregroundStyle(C.sub)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 2)
    }

    private var meta: String {
        var parts: [String] = []
        if let d = episode.durationMs { parts.append("\(Int(d / 60000)) min") }
        if playhead?.finished == true { parts.append("Watched") }
        else if let s = playhead?.seconds, s > 30 { parts.append("Resume \(clock(s))") }
        if episode.premium == true { parts.append("Premium") }
        return parts.joined(separator: " · ")
    }
}

func clock(_ seconds: Double) -> String {
    guard seconds.isFinite, seconds >= 0 else { return "0:00" }
    let s = Int(seconds)
    return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                     : String(format: "%d:%02d", s / 60, s % 60)
}

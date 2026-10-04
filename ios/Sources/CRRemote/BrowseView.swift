import SwiftUI

/// Search box over two home rails: Popular, and shows with a new episode.
struct BrowseView: View {
    @EnvironmentObject var model: RemoteModel

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                if model.searched || model.searching {
                    if model.searching {
                        ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
                    } else if model.results.isEmpty {
                        Text("Nothing found for “\(model.query)”").foregroundStyle(C.sub)
                            .frame(maxWidth: .infinity).padding(.top, 40)
                    } else {
                        grid(model.results)
                    }
                } else if model.hosts.isEmpty {
                    empty
                } else {
                    if !model.newEpisodes.isEmpty {
                        rail("New episodes", model.newEpisodes)
                    }
                    if !model.trending.isEmpty {
                        Text("Popular").font(.headline).padding(.horizontal)
                        grid(model.trending)
                    } else if model.loadingHome {
                        ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
                    }
                }
            }
            .padding(.vertical, 8)
        }
        .background(C.bg)
        .searchable(text: $model.query, placement: .navigationBarDrawer(displayMode: .always),
                    prompt: "Search Crunchyroll")
        .onSubmit(of: .search) { model.search() }
        .onChange(of: model.query) { _, _ in model.queryChanged() }
        .refreshable {
            model.refreshHosts()
            model.loadHome()
        }
    }

    private var empty: some View {
        VStack(spacing: 10) {
            Image(systemName: "desktopcomputer.trianglebadge.exclamationmark").font(.largeTitle)
                .foregroundStyle(C.sub)
            Text("No browser connected").font(.headline)
            Text("Open Chrome/Chromium with the CR Remote extension on a PC, and make sure it's signed in to Crunchyroll.")
                .font(.footnote).foregroundStyle(C.sub).multilineTextAlignment(.center)
            Button("Check again") { model.refreshHosts() }.padding(.top, 6)
        }
        .padding(30)
        .frame(maxWidth: .infinity)
    }

    private func rail(_ title: String, _ items: [Series]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline).padding(.horizontal)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) {
                    ForEach(items) { s in
                        Button { model.open(s) } label: { SeriesCard(series: s, width: 120) }
                            .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    private func grid(_ items: [Series]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 12)], spacing: 16) {
            ForEach(items) { s in
                Button { model.open(s) } label: { SeriesCard(series: s, width: nil) }
                    .buttonStyle(.plain)
            }
        }
        .padding(.horizontal)
    }
}

struct SeriesCard: View {
    let series: Series
    let width: CGFloat?

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            // The frame is a fixed 2:3 box and the image fills and is cropped
            // to it. Putting the aspect ratio on the image itself let each
            // poster lay out at its own size and spill across the columns.
            Color.clear
                .aspectRatio(2 / 3, contentMode: .fit)
                .frame(width: width)
                .overlay { Poster(url: series.poster) }
                .clipShape(RoundedRectangle(cornerRadius: 8))
            Text(series.title).font(.caption.weight(.semibold)).lineLimit(2)
                .frame(width: width, alignment: .leading)
            if let l = series.latest {
                Text(l).font(.caption2).foregroundStyle(C.orange).lineLimit(1)
                    .frame(width: width, alignment: .leading)
            }
        }
    }
}

struct Poster: View {
    let url: String?

    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { phase in
            switch phase {
            case .success(let img): img.resizable().scaledToFill()
            default: Rectangle().fill(C.variant)
                .overlay(Image(systemName: "play.rectangle").foregroundStyle(C.outline))
            }
        }
    }
}

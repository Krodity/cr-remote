import Foundation
import SwiftUI

@MainActor
final class RemoteModel: ObservableObject {
    @Published private(set) var pairing = Store.load()
    @Published private(set) var hosts: [HostInfo] = []
    @Published var error: String?

    @Published var query = ""
    @Published private(set) var searching = false
    @Published private(set) var searched = false
    @Published private(set) var results: [Series] = []
    @Published private(set) var trending: [Series] = []
    @Published private(set) var newEpisodes: [Series] = []
    @Published private(set) var loadingHome = false

    @Published private(set) var series: Series?
    @Published private(set) var seasons: [Season] = []
    @Published private(set) var season: Season?
    @Published private(set) var episodes: [Episode] = []
    @Published private(set) var playheads: [String: Playhead] = [:]
    @Published private(set) var loadingSeries = false

    @Published private(set) var status = PlayerStatus()
    @Published private(set) var nowPlaying: Episode?
    @Published private(set) var busy = false

    private var pollTask: Task<Void, Never>?
    private var seekGuardUntil = Date.distantPast

    private var broker: Broker { Broker(pairing: pairing) }

    init() {
        if pairing.paired { start() }
    }

    // ── pairing / hosts ──────────────────────────────────────────────────────
    func pair(_ p: Pairing) {
        var p = p
        p.host = ""
        Store.save(p)
        pairing = p
        error = nil
        start()
    }

    func unpair() {
        stop()
        Store.clear()
        pairing = Pairing()
        hosts = []
        trending = []
        newEpisodes = []
        results = []
        status = PlayerStatus()
    }

    func selectHost(_ id: String) {
        pairing.host = id
        Store.save(pairing)
        status = PlayerStatus()
        Task { await pollOnce() }
    }

    func start() {
        refreshHosts()
        startPolling()
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
    }

    func refreshHosts() {
        Task {
            do {
                hosts = try await broker.hosts()
                error = nil
                if !hosts.isEmpty && trending.isEmpty { loadHome() }
            } catch {
                fail(error)
            }
        }
    }

    // ── browsing ─────────────────────────────────────────────────────────────
    /// Both rails come from the same PC round-trip; load them together so the
    /// home screen never renders half-populated.
    func loadHome() {
        guard !loadingHome else { return }
        loadingHome = true
        Task {
            defer { loadingHome = false }
            do {
                let top = try await broker.trending()
                let fresh = (try? await broker.newEpisodes()) ?? []
                trending = top
                newEpisodes = fresh
            } catch {
                // Retried whenever a PC shows up; not worth an error banner.
            }
        }
    }

    func queryChanged() {
        // Clearing the box returns to the home rails, not a stale result set.
        if query.trimmed.isEmpty {
            searched = false
            results = []
        }
    }

    func search() {
        let q = query.trimmed
        guard !q.isEmpty else { return }
        searching = true
        error = nil
        Task {
            defer { searching = false; searched = true }
            do { results = try await broker.search(q) } catch { fail(error) }
        }
    }

    func open(_ s: Series) {
        series = s
        seasons = []
        season = nil
        episodes = []
        loadingSeries = true
        error = nil
        Task {
            defer { loadingSeries = false }
            do {
                let ss = try await broker.seasons(s.id)
                guard series?.id == s.id else { return }
                seasons = ss
                if let first = ss.first {
                    season = first
                    let eps = try await broker.episodes(first.id)
                    guard season?.id == first.id else { return }
                    episodes = eps
                    loadPlayheads(eps)
                }
            } catch {
                fail(error)
            }
        }
    }

    func select(_ s: Season) {
        season = s
        loadingSeries = true
        error = nil
        Task {
            defer { loadingSeries = false }
            do {
                let eps = try await broker.episodes(s.id)
                guard season?.id == s.id else { return }
                episodes = eps
                loadPlayheads(eps)
            } catch {
                fail(error)
            }
        }
    }

    /// Resume points are a nicety: a failure must not break the episode list.
    private func loadPlayheads(_ eps: [Episode]) {
        Task {
            if let p = try? await broker.playheads(eps.map(\.id)) {
                playheads.merge(p) { _, new in new }
            }
        }
    }

    func closeSeries() {
        series = nil
        seasons = []
        episodes = []
    }

    // ── playback ─────────────────────────────────────────────────────────────
    func play(_ ep: Episode) {
        busy = true
        nowPlaying = ep
        error = nil
        Task {
            do { try await broker.play(ep) } catch { fail(error) }
            busy = false
            // The page needs a moment before it reports a video.
            try? await Task.sleep(for: .milliseconds(2500))
            await pollOnce()
        }
    }

    func control(_ action: String, _ value: Double? = nil) {
        if ["seek", "forward", "back"].contains(action) {
            seekGuardUntil = Date().addingTimeInterval(1.2)
        }
        // Reflect play/pause at once; the next poll corrects a failure.
        switch action {
        case "play": optimistic(playing: true)
        case "pause": optimistic(playing: false)
        case "toggle": optimistic(playing: !status.playing)
        default: break
        }
        Task {
            do {
                let s = try await broker.control(action, value)
                if s.hasVideo { status = s }
                error = nil
            } catch {
                fail(error)
            }
        }
    }

    func seek(to fraction: Double) {
        guard status.duration > 0 else { return }
        status.position = status.duration * fraction
        control("seek", status.duration * fraction)
    }

    private func optimistic(playing: Bool) {
        status.playing = playing
        status.paused = !playing
    }

    private func startPolling() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.pollOnce()
                // Slow down when nothing is playing; there is nothing to animate.
                let playing = await MainActor.run { self?.status.hasVideo ?? false }
                try? await Task.sleep(for: .milliseconds(playing ? 1500 : 5000))
            }
        }
    }

    /// Called when the app returns to the foreground.
    func resume() {
        guard pairing.paired else { return }
        if pollTask == nil { startPolling() }
        Task { await pollOnce() }
        if hosts.isEmpty { refreshHosts() }
    }

    private func pollOnce() async {
        guard pairing.paired else { return }
        do {
            var s = try await broker.status()
            // Keep an optimistic scrub position until the page has caught up.
            if Date() < seekGuardUntil && s.hasVideo { s.position = status.position }
            status = s
            if error != nil && s.hasVideo { error = nil }
        } catch let e as BrokerError where !e.recoverable {
            fail(e)
        } catch {
            // A polling failure is background noise; don't blank the screen.
        }
    }

    private func fail(_ e: Error) {
        error = (e as? LocalizedError)?.errorDescription ?? e.localizedDescription
    }
}

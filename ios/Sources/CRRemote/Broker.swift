import Foundation
import Security

// ── wire models: field names mirror what the extension emits ─────────────────

struct Series: Decodable, Identifiable, Hashable {
    var id: String
    var title: String
    var slug: String?
    var description: String?
    var episodes: Int?
    var seasons: Int?
    var poster: String?
    /// Only on the "New episodes" rail, e.g. "E24 · Bloodbath in Laos".
    var latest: String?
}

struct Season: Decodable, Identifiable, Hashable {
    var id: String
    var number: Int?
    var title: String?
    var episodes: Int?
    var subbed: Bool?
    var dubbed: Bool?

    var label: String { number.map { "S\($0)" } ?? String((title ?? "?").prefix(8)) }
}

struct Episode: Decodable, Identifiable, Hashable {
    var id: String
    var number: Int?
    var season: Int?
    var title: String?
    var slug: String?
    var description: String?
    var durationMs: Double?
    var premium: Bool?
    var thumb: String?
    var url: String?

    var label: String { number.map { "E\($0)" } ?? "" }
}

/// How far into an episode this account already is.
struct Playhead: Decodable, Hashable {
    var seconds: Double?
    var finished: Bool?
}

/// Playback state as seen by the extension's content script.
struct PlayerStatus: Decodable, Equatable {
    var hasVideo: Bool = false
    var playing: Bool = false
    var paused: Bool = true
    var ended: Bool = false
    var position: Double = 0
    var duration: Double = 0
    var buffered: Double = 0
    var volume: Double = 1
    var muted: Bool = false
    var fullscreen: Bool = false
    var episodeId: String?
    var pageTitle: String?
    var url: String?
    var reason: String?

    var progress: Double { duration > 0 ? min(max(position / duration, 0), 1) : 0 }

    init() {}

    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        hasVideo = (try? c.decode(Bool.self, forKey: .hasVideo)) ?? false
        playing = (try? c.decode(Bool.self, forKey: .playing)) ?? false
        paused = (try? c.decode(Bool.self, forKey: .paused)) ?? !playing
        ended = (try? c.decode(Bool.self, forKey: .ended)) ?? false
        position = (try? c.decode(Double.self, forKey: .position)) ?? 0
        duration = (try? c.decode(Double.self, forKey: .duration)) ?? 0
        buffered = (try? c.decode(Double.self, forKey: .buffered)) ?? 0
        volume = (try? c.decode(Double.self, forKey: .volume)) ?? 1
        muted = (try? c.decode(Bool.self, forKey: .muted)) ?? false
        fullscreen = (try? c.decode(Bool.self, forKey: .fullscreen)) ?? false
        episodeId = try? c.decode(String.self, forKey: .episodeId)
        pageTitle = try? c.decode(String.self, forKey: .pageTitle)
        url = try? c.decode(String.self, forKey: .url)
        reason = try? c.decode(String.self, forKey: .reason)
    }

    // `duration` can arrive as NaN from a <video> before metadata loads, which
    // JSON encodes as null — hence the forgiving decoder above.
    enum CodingKeys: String, CodingKey {
        case hasVideo, playing, paused, ended, position, duration, buffered, volume, muted,
             fullscreen, episodeId, pageTitle, url, reason
    }
}

struct HostInfo: Decodable, Identifiable, Hashable {
    var id: String
    var name: String
    var connected_at: Double?
}

/// A broker call that failed, with a message already fit to show the user.
struct BrokerError: LocalizedError {
    let message: String
    var recoverable = true
    var errorDescription: String? { message }
}

// ── pairing ──────────────────────────────────────────────────────────────────

struct Pairing: Equatable {
    var broker = ""
    var token = ""
    var host = ""

    var paired: Bool { !broker.isEmpty && !token.isEmpty }

    /// The one place a pairing payload is parsed. The /pair page's button and
    /// its QR code carry the identical string, so the two routes can't drift:
    ///     crremote://pair?broker=host:8774&token=…
    static func parse(_ raw: String) -> Pairing? {
        guard let c = URLComponents(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              c.scheme == "crremote", c.host == "pair",
              let broker = c.queryItems?.first(where: { $0.name == "broker" })?.value?.trimmed, !broker.isEmpty,
              let token = c.queryItems?.first(where: { $0.name == "token" })?.value?.trimmed, !token.isEmpty
        else { return nil }
        return Pairing(broker: normalise(broker), token: token)
    }

    /// "hostname", "hostname:8774" or a full URL → always a base URL.
    static func normalise(_ raw: String) -> String {
        var s = raw.trimmed
        while s.hasSuffix("/") { s.removeLast() }
        if s.isEmpty { return s }
        if !s.hasPrefix("http://") && !s.hasPrefix("https://") { s = "http://" + s }
        let afterScheme = s.components(separatedBy: "://").last ?? ""
        // A bracketed IPv6 literal has colons of its own; only a port after ']' counts.
        let hostPart = afterScheme.hasPrefix("[") ? String(afterScheme.split(separator: "]").last ?? "") : afterScheme
        if !hostPart.contains(":") { s += ":8774" }
        return s
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

/// Broker + host in UserDefaults; the token, which drives a browser on the
/// PC, in the Keychain.
enum Store {
    private static var d: UserDefaults { .standard }
    private static let service = "uk.krodity.crremote"

    static func load() -> Pairing {
        Pairing(broker: d.string(forKey: "broker") ?? "", token: keychain("token") ?? "",
                host: d.string(forKey: "host") ?? "")
    }

    static func save(_ p: Pairing) {
        d.set(p.broker, forKey: "broker")
        d.set(p.host, forKey: "host")
        setKeychain("token", p.token)
    }

    static func clear() {
        d.removeObject(forKey: "broker")
        d.removeObject(forKey: "host")
        setKeychain("token", nil)
    }

    private static func q(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: key]
    }

    private static func keychain(_ key: String) -> String? {
        var query = q(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func setKeychain(_ key: String, _ value: String?) {
        SecItemDelete(q(key) as CFDictionary)
        guard let value else { return }
        var query = q(key)
        query[kSecValueData as String] = Data(value.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(query as CFDictionary, nil)
    }
}

// ── client ───────────────────────────────────────────────────────────────────

/// Thin client for cr-broker. Every catalogue lookup and player command is one
/// POST /api/rpc, which the broker forwards to the paired browser extension.
///
/// The broker's own failures and the extension's arrive differently (503/502
/// vs an `error` field), so both are flattened into one BrokerError.
struct Broker: Sendable {
    let pairing: Pairing

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 30
        return URLSession(configuration: c)
    }()

    private func host() -> String {
        URL(string: pairing.broker)?.host ?? pairing.broker
    }

    private func perform(_ req: URLRequest) async throws -> (Data, Int) {
        do {
            let (data, resp) = try await Self.session.data(for: req)
            return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
        } catch let e as URLError {
            switch e.code {
            case .cannotFindHost, .dnsLookupFailed:
                throw BrokerError(message: "Can't find \(host()) — is Tailscale on?")
            case .timedOut:
                throw BrokerError(message: "The PC didn't answer in time")
            case .cannotConnectToHost, .networkConnectionLost, .notConnectedToInternet:
                throw BrokerError(message: "Can't reach the broker on \(host()) — is Tailscale on?")
            default:
                throw BrokerError(message: e.localizedDescription)
            }
        }
    }

    private func rpc(_ method: String, _ params: [String: Any] = [:]) async throws -> [String: Any] {
        guard pairing.paired else { throw BrokerError(message: "Not paired with a broker yet", recoverable: false) }
        var req = URLRequest(url: URL(string: pairing.broker + "/api/rpc")!)
        req.httpMethod = "POST"
        // Player commands answer instantly; a cold search can take a few
        // seconds while the extension refreshes its Crunchyroll token.
        req.timeoutInterval = 30
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(pairing.token)", forHTTPHeaderField: "Authorization")
        var body: [String: Any] = ["method": method, "params": params]
        if !pairing.host.isEmpty { body["host"] = pairing.host }
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, code) = try await perform(req)
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw BrokerError(message: "Broker sent an unreadable reply (HTTP \(code))")
        }
        if let err = json["error"] as? String {
            throw BrokerError(message: err, recoverable: code != 401)
        }
        if code == 401 { throw BrokerError(message: "Pairing token rejected", recoverable: false) }
        guard (200..<300).contains(code) else { throw BrokerError(message: "Broker error (HTTP \(code))") }
        return json["result"] as? [String: Any] ?? [:]
    }

    private func decode<T: Decodable>(_ obj: Any?, as: T.Type) -> T? {
        guard let obj, JSONSerialization.isValidJSONObject(obj) || obj is [Any],
              let data = try? JSONSerialization.data(withJSONObject: obj) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    /// Decodes each element on its own, so one odd row can't blank a whole list.
    private func list<T: Decodable>(_ result: [String: Any], _ key: String) -> [T] {
        (result[key] as? [Any] ?? []).compactMap { decode($0, as: T.self) }
    }

    func hosts() async throws -> [HostInfo] {
        guard pairing.paired else { throw BrokerError(message: "Not paired with a broker yet", recoverable: false) }
        var req = URLRequest(url: URL(string: pairing.broker + "/api/hosts")!)
        req.timeoutInterval = 10
        req.setValue("Bearer \(pairing.token)", forHTTPHeaderField: "Authorization")
        let (data, code) = try await perform(req)
        if code == 401 { throw BrokerError(message: "Pairing token rejected", recoverable: false) }
        let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        return list(json ?? [:], "hosts")
    }

    func search(_ q: String) async throws -> [Series] { list(try await rpc("search", ["q": q]), "series") }

    /// The home rail: Crunchyroll's own "Popular" ordering.
    func trending(n: Int = 30) async throws -> [Series] {
        list(try await rpc("browse", ["sort": "popularity", "n": n]), "series")
    }

    /// Shows that just got a new episode, one row per show.
    func newEpisodes() async throws -> [Series] { list(try await rpc("recent"), "series") }

    func seasons(_ seriesId: String) async throws -> [Season] {
        list(try await rpc("seasons", ["seriesId": seriesId]), "seasons")
    }

    func episodes(_ seasonId: String) async throws -> [Episode] {
        list(try await rpc("episodes", ["seasonId": seasonId]), "episodes")
    }

    /// Resume positions for a batch of episodes, keyed by episode id.
    func playheads(_ ids: [String]) async throws -> [String: Playhead] {
        guard !ids.isEmpty else { return [:] }
        let r = try await rpc("playheads", ["ids": ids.joined(separator: ",")])
        let obj = r["playheads"] as? [String: Any] ?? [:]
        return obj.compactMapValues { decode($0, as: Playhead.self) }
    }

    func play(_ ep: Episode) async throws {
        var p: [String: Any] = ["id": ep.id]
        if let u = ep.url { p["url"] = u }
        if let s = ep.slug { p["slug"] = s }
        _ = try await rpc("play", p)
    }

    func status() async throws -> PlayerStatus {
        decode(try await rpc("status"), as: PlayerStatus.self) ?? PlayerStatus()
    }

    /// `value`: seconds for seek/forward/back, 0…1 for volume, else nil.
    func control(_ action: String, _ value: Double? = nil) async throws -> PlayerStatus {
        var p: [String: Any] = ["action": action]
        if let value { p["value"] = value }
        return decode(try await rpc("control", p), as: PlayerStatus.self) ?? PlayerStatus()
    }
}

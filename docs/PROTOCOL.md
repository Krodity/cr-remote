# Protocol reference

Two interfaces: the **HTTP API** the phone calls, and the **WebSocket RPC** the
broker uses to reach an extension. The phone never speaks the second one.

---

## HTTP API (phone → broker), port 8774

All endpoints except `/api/health` and `/pair` need the pairing token:

```
Authorization: Bearer <token>
```

(`?token=` on the query string also works, for `curl` convenience.)

### `GET /api/health`
Unauthenticated liveness probe.
```json
{"ok": true, "hosts": 1}
```

### `GET /api/hosts`
Connected browsers, most recent first. `status` is the last state each host pushed.
```json
{"hosts": [{"id": "1e5447a6", "name": "living room PC",
            "connected_at": 1789130363.6, "status": {"hasVideo": true, "playing": false}}]}
```

### `POST /api/rpc`
```json
{"method": "search", "params": {"q": "frieren"}, "host": "1e5447a6"}
```
`host` is optional — omit it to use the most recently connected browser.

Responses:
```json
{"host": "1e5447a6", "result": { ... }}
{"error": "human-readable message"}
```

| Status | Meaning |
|---|---|
| 200 | `result` holds the extension's reply |
| 401 | bad or missing token |
| 502 | the extension raised an error (message passed through) |
| 503 | no browser connected, or the named host is gone |

### `GET /api/rpc?method=…&…`
Same thing with query parameters, for shell use. Every parameter other than
`token`, `host` and `method` is passed through as a string param.

```bash
curl -s -H "Authorization: Bearer $TOK" \
     "http://localhost:8774/api/rpc?method=search&q=frieren"
```

### `GET /pair`
An HTML page with a `crremote://pair?broker=…&token=…` deep link. Unauthenticated
by design — reaching it already means you are on the private network.

---

## Methods

### Catalogue

| Method | Params | Returns |
|---|---|---|
| `ping` | — | `{ok, at}` |
| `search` | `q`, `n` (default 20) | `{series: [Series]}` |
| `browse` | `sort` (default `popularity`), `n` (30) | `{series: [Series]}` |
| `recent` | `n` (100) | `{series: [Series]}` — one row per show, newest episode first |
| `seasons` | `seriesId` | `{seasons: [Season]}` |
| `episodes` | `seasonId` | `{episodes: [Episode]}` |
| `playheads` | `ids` (array or comma string) | `{playheads: {id: {seconds, finished}}}` |
| `upNext` | `episodeId` | `{next: Episode \| null}` |

### Playback

| Method | Params | Notes |
|---|---|---|
| `play` | `url`, or `id` + `slug` | Reuses an existing Crunchyroll tab; refuses non-`/watch/` URLs |
| `status` | — | Current `PlayerStatus` |
| `control` | `action`, `value` | See below |

`control` actions:

| Action | `value` | Effect |
|---|---|---|
| `play` / `pause` / `toggle` | — | Transport |
| `seek` | seconds | Absolute position |
| `forward` / `back` | seconds (default 10) | Relative |
| `restart` | — | Back to 0:00 |
| `volume` | 0.0–1.0 | |
| `mute` | omit to toggle | |
| `rate` | e.g. `1.5` | Playback speed |
| `fullscreen` | — | Trusted `f` keypress → the player's own fullscreen |
| `next` | — | Resolves `upNext` and navigates |
| `dismiss` | — | Clicks *"Are you still watching?"* if present |

---

## Shapes

```jsonc
// Series
{"id","title","slug","description","episodes","seasons","poster",
 "latest"}   // only on `recent`, e.g. "E24 · Episode 24"

// Season
{"id","number","title","episodes","subbed","dubbed"}

// Episode
{"id","number","season","title","slug","description",
 "durationMs","premium","thumb","url"}

// PlayerStatus
{"hasVideo","playing","paused","ended","position","duration","buffered",
 "volume","muted","rate","fullscreen","episodeId","pageTitle","url",
 "reason"}   // set only when there is nothing to report, e.g. no tab open
```

---

## WebSocket RPC (broker → extension), port 8775

The extension connects **outbound**:

```
ws://<broker-host>:8775/host?token=<token>&name=<label>&id=<stable-id>
```

A bad token is closed with code `4401`.

Broker → extension:
```json
{"id": 7, "method": "search", "params": {"q": "frieren"}}
```

Extension → broker:
```json
{"id": 7, "result": { ... }}
{"id": 7, "error": "Not signed in to Crunchyroll in this browser"}
```

Unsolicited status push (every ~2s, so the phone's scrubber moves without polling):
```json
{"type": "status", "status": { ...PlayerStatus... }}
```

Calls time out after 25s (`CR_BROKER_RPC_TIMEOUT`). The broker pings every 20s,
which also keeps the MV3 service worker resident.

---

## Crunchyroll endpoints used

For anyone extending this. All are `https://www.crunchyroll.com`, all take
`locale=en-US`, all need `Authorization: Bearer <access_token>`.

| Purpose | Path |
|---|---|
| Token | `POST /auth/v1/token` — `grant_type=etp_rt_cookie`, Basic `noaihdevm_6iyg0a8l0q:`, cookies included |
| Account | `/accounts/v1/me` → **`account_id`** (a UUID) |
| Search | `/content/v2/discover/search?q=&n=&type=series` |
| Trending | `/content/v2/discover/browse?sort_by=popularity&type=series` |
| New episodes | `/content/v2/discover/browse?sort_by=newly_added&type=episode` |
| Seasons | `/content/v2/cms/series/{id}/seasons` |
| Episodes | `/content/v2/cms/seasons/{id}/episodes` |
| Resume points | `/content/v2/{account_id}/playheads?content_ids=a,b,c` |
| Up next | `/content/v2/discover/up_next/{episodeId}` |

Gotchas worth knowing:

- **`n` caps at 100.** `n=200` is a hard `HTTP 400`.
- **Playheads wants `account_id` (UUID), not `external_id`** — the latter 404s.
- **New episodes must use `type=episode`.** `type=series&sort_by=newly_added`
  means "new to the catalogue", a different thing.
- **Crunchyroll returns one row per dub**, so deduplicate by
  `episode_metadata.series_id`. 100 episodes collapse to roughly 16 shows.
- **No session gives `403`, not `401`.** A 401-only check never fires.

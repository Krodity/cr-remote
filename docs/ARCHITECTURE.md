# How cr-remote works

## The constraint that shapes everything

Crunchyroll's video is protected with **Widevine DRM**. A third-party app cannot
decrypt the stream — only a client with a licensed player (a real browser, or
Crunchyroll's own app) can play it.

So a "Crunchyroll remote" cannot be a video player. It has to be a *remote*: the
phone decides **what** to watch, and something that already has a licensed player
does the watching. That single fact explains every design decision below.

## The three pieces

```
┌──────────────┐   HTTP :8774    ┌───────────┐   WebSocket :8775   ┌──────────────────┐
│ Android app  │ ───────────────▶│ cr-broker │◀────────────────────│ browser extension│
│  (a remote)  │◀─────────────── │ (rendezvous)                    │  (any PC)        │
└──────────────┘   JSON-RPC      └───────────┘                     └────────┬─────────┘
                                                                            │
                                                          ┌─────────────────┴─────────┐
                                                          │ Crunchyroll API (cookies) │
                                                          │ the page's <video>        │
                                                          └───────────────────────────┘
```

### 1. The browser extension — the only part that knows about Crunchyroll

It runs inside a logged-in `crunchyroll.com` context. That matters enormously:
`fetch(..., { credentials: "include" })` against `/auth/v1/token` reuses **the
site's own `etp_rt` cookie**, which is exactly the grant the website performs for
itself.

The consequence: **no Crunchyroll credentials exist anywhere else in the system.**
Not on the phone, not in the broker, not on disk. If you sign out in the browser,
the remote stops working — which is the correct behaviour.

It does two jobs:

- **Catalogue lookups** (`search`, `browse`, `seasons`, `episodes`, `playheads`,
  `upNext`) — plain HTTPS calls with a bearer token it refreshes as needed.
- **Player control** — a content script talks directly to the page's `<video>`
  element: `play()`, `pause()`, `currentTime`, `volume`, `muted`.

### 2. cr-broker — a dumb rendezvous point

A phone cannot open a connection *into* a browser extension, and an extension
cannot listen on a port. They need somewhere to meet.

The broker is deliberately ignorant. It holds **no Crunchyroll credentials**,
knows nothing about episodes, and contains no Crunchyroll logic at all. It:

- accepts WebSocket connections from extensions and registers each as a *host*,
- accepts HTTP requests from phones and forwards one JSON-RPC call at a time to
  the host the phone names (or the most recently connected one),
- caches the last status each host pushed, so `/api/hosts` can show what is playing.

It binds **loopback and the Tailscale address only**. The bearer token is a
pairing secret, not a security boundary — the private network is the boundary.

### 3. The Android app — a thin client

Kotlin + Compose. It holds the broker URL and token, and turns every user action
into one `POST /api/rpc`. It has no Crunchyroll knowledge either; it renders
whatever the extension returns.

## Why a browser extension rather than automating the browser?

The earlier tooling on this machine (`~/bin/cr-goto`, `cr-fullscreen`) drives
Chromium over the DevTools Protocol plus `ydotool` keystrokes. That works, but it
is welded to one machine: it needs Chromium launched with
`--remote-debugging-port`, a Wayland compositor, a specific monitor, and a running
`ydotool` daemon.

An extension needs none of that. Install it on any PC — Windows, macOS, Linux —
and that machine becomes controllable. No launch flags, no input-injection daemon,
no window manager assumptions.

## Two things that are less obvious than they look

### Frames

The `<video>` is in the **top frame** of a `/watch/` page. But a watch page also
contains several `about:blank` subframes that *do* run the content script and
answer with a contentless `{hasVideo:false}`. A naive "send to the tab and take
the first reply" therefore picks garbage at random.

The service worker walks `chrome.webNavigation.getAllFrames()`, asks every frame,
and prefers the reply that actually knows the episode.

> An earlier version of this document claimed the `<video>` lived in a
> `static.crunchyroll.com` iframe. That was true of the old "vilos" player and is
> **not** true today — it was asserted without measuring. Frame-walking is still
> needed, just for the different reason above.

### Fullscreen needs a real user gesture

`video.requestFullscreen()` called from a content script fails with
`Permissions check failed`. Element fullscreen requires **transient user
activation**, and a message arriving from a phone is not a user gesture. A
synthetic `KeyboardEvent` does not help either — untrusted events do not grant
activation.

The fix is `chrome.debugger`: input injected through the debugger API **is
trusted**. The extension attaches to the tab and dispatches a real `f` keypress,
which drives Crunchyroll's own player fullscreen. The cost is a
*"started debugging this browser"* infobar while attached.

(There is a `chrome.windows.update({state:"fullscreen"})` fallback if the debugger
is unavailable, but it fullscreens the *browser window* — F11-like, and not what
you want.)

## Security model

| Concern | Position |
|---|---|
| Crunchyroll credentials | Never leave the browser. The extension reuses the site's own session cookie. |
| Transport | Plain HTTP/WS. Intended to run only on a private network (Tailscale). |
| Token | A pairing secret so a stray process on the same host cannot drive your browser. It is **not** a substitute for network isolation. |
| Binding | Loopback + the Tailscale address. Never `0.0.0.0`. |
| Navigation | `play` refuses any URL that is not `https://www.crunchyroll.com/watch/...`. |

**Do not expose the broker to the public internet.** Anyone who reaches it with
the token can drive your browser.

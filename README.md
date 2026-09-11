# cr-remote

An Android remote for Crunchyroll: search shows on the phone, pick an episode,
and drive playback on a PC.

```
Android app  ──HTTP :8774──>  cr-broker  <──WS :8775──  browser extension
  search / browse / transport    (aepc)      (any PC)   ├─ Crunchyroll API
                                                        └─ the <video> element
```

## Why it is built this way

Crunchyroll video is Widevine-DRM'd, so the phone cannot be the player — only
something with a licensed player can decode the stream. The app therefore drives
a real browser instead of replacing it.

The extension is the only component that talks to Crunchyroll. Because it runs
inside a logged-in crunchyroll.com context, it reuses the site's own `etp_rt`
cookie for the `/auth/v1/token` grant — so **no Crunchyroll credentials ever
touch the phone or the broker**.

The broker is deliberately dumb: it registers connected browsers, routes one
JSON-RPC call at a time, and knows nothing about Crunchyroll. It binds loopback
and the Tailscale address only.

## Install

**1. Broker (on aepc)**

```bash
systemctl --user enable --now cr-broker
curl -s localhost:8774/api/health        # {"ok": true, "hosts": 0}
```

The pairing token is generated on first run at `~/.config/cr-remote/token`.

**2. Extension (on every PC you want to control)**

Chrome/Chromium/Edge → `chrome://extensions` → enable Developer mode →
**Load unpacked** → select `extension/`.

Then click the extension icon and fill in:

| Field | Value |
|-------|-------|
| Broker | `ws://aepc:8775` |
| Pairing token | contents of `~/.config/cr-remote/token` |
| This PC's name | anything, e.g. `living room PC` |

A green badge means connected. The browser must be signed in to Crunchyroll.

**3. Android app**

```bash
adb install android/app/build/outputs/apk/debug/cr-remote-1.0.0-debug.apk
```

Then open **`http://aepc:8774/pair`** in the phone's browser and tap *Pair this
phone* — the deep link fills in the broker address and token, so the token never
has to be typed by hand. (Manual entry is still available in the app.)

## Using it

- Search a show → pick a season → tap an episode. The PC navigates and starts it.
- The bar at the bottom is the transport. Tap it to expand:
  scrubber, ±10s, restart, next episode, mute, fullscreen.
- **Dismiss** (the hand icon) clicks Crunchyroll's "Are you still watching?"
  modal, which otherwise stalls a binge.
- The monitor icon in the top bar picks which PC to drive when several browsers
  are connected. *Most recent* is the default and is almost always right.

## Notes and limits

- **Multiple PCs** can be connected at once; the app picks one. A PC drops off
  the list as soon as its browser closes.
- **MV3 service worker lifetime**: the websocket traffic (and the broker's 20s
  ping) keeps the extension's worker resident. A 1-minute alarm revives and
  reconnects it if Chrome kills it anyway.
- **Playback may be blocked** if the tab has never been interacted with — Chrome
  requires a user gesture before `video.play()`. The error says so; click the
  page once.
- The broker has no TLS and one shared token. That is deliberate: it is reachable
  only from the tailnet, exactly like the other services on this box.

## Layout

```
broker/cr_broker.py      the rendezvous service (stdlib + websockets)
extension/               MV3 extension: cr-api.js (Crunchyroll), background.js
                         (broker link + RPC), content.js (the <video>)
android/                 Kotlin + Compose app
```

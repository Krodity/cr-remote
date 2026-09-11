# Planned work

## QR pairing (next up)

Replace the "open a URL and tap" pairing flow with "point the phone at the
screen". The PC shows a QR code; the app scans it and is paired.

### What it encodes

Nothing new — the **same deep link** the `/pair` page already builds:

```
crremote://pair?broker=<host>:8774&token=<token>
```

So the app's existing `handlePairLink()` in `MainActivity.kt` already handles the
payload. The scanner just needs to feed it that string.

### PC side — where to draw it

Two candidates, and they are not exclusive:

1. **The broker's `/pair` page** (`PAIR_PAGE` in `broker/cr_broker.py`) — add the
   QR above the existing button, so the page works whether you tap or scan.
2. **The extension popup** (`extension/options.html`) — nicer, because you never
   have to find the broker URL first. The popup already knows the broker address
   and token.

### How to generate it — decide this first

| Option | Trade-off |
|---|---|
| **Vendor a small JS QR encoder** (MIT, ~5 KB) and render in the page | **Preferred.** Keeps the broker at "stdlib + websockets", works offline, and the same file serves both the `/pair` page and the extension popup. No CDN — the extension's CSP forbids remote scripts anyway. |
| Python `qrcode` (Arch: `extra/python-qrcode` 8.2-2) rendering inline SVG | Server-side and simple, but adds a dependency to a deliberately dependency-light broker. If taken, make it **optional** — fall back to the current button when the import fails. |

### Android side

`com.journeyapps:zxing-android-embedded` — **already present in the Gradle cache
and still listed in `gradle/libs.versions.toml` history**, so it resolves without
a download.

- Add `libs.zxing` back to `app/build.gradle.kts`.
- Add `<uses-permission android:name="android.permission.CAMERA" />`.
- Launch with `ScanContract` from the pairing screen; keep manual entry as the
  fallback for when the camera is refused.
- Feed the scanned string through the **same** path as the deep link so there is
  only one place that parses pairing payloads.

### Watch out for

- **The QR contains the live token.** Same exposure as the `/pair` page — fine on
  a private network, but it should not be screenshotted into a chat or a README.
  Worth a line of warning text under the code.
- Include a **"regenerate"** affordance if the token is ever rotated, so a stale
  QR is not left on screen.
- Dark theme: QR codes need real contrast. Draw on a white quiet-zone background
  regardless of theme rather than inverting.

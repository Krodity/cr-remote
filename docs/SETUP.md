# Setup

Three pieces to install: the **broker** (once, on one always-on machine), the
**extension** (on every PC you want to control), and the **app** (on every phone).

Throughout, replace `<broker-host>` with the hostname or IP of the machine running
the broker — e.g. a Tailscale MagicDNS name like `mypc`, or `100.x.y.z`.

---

## 0. Prerequisites

| Where | Needs |
|---|---|
| Broker machine | Python **3.11+**, the `websockets` package (`pip install websockets` or a distro package) |
| PC to control | Chrome / Chromium / Edge, signed in to Crunchyroll |
| Phone | Android **8.0+** (API 26) |
| All | A private network all three can reach — [Tailscale](https://tailscale.com) is the intended setup |

> The broker and the controlled PC are often the same machine. They don't have to be.

---

## 1. Broker

```bash
git clone <your-repo-url> cr-remote
cd cr-remote
./broker/install.sh
```

That installs a **user** systemd service (no root) and starts it. Verify:

```bash
systemctl --user status cr-broker
curl -s localhost:8774/api/health      # {"ok": true, "hosts": 0}
```

`hosts: 0` is expected until a browser connects.

### Let it run without you logged in

A user service stops when your session ends. To keep the broker up across
logouts and reboots:

```bash
sudo loginctl enable-linger $USER
```

### Your pairing token

Generated on first run:

```bash
cat ~/.config/cr-remote/token
```

Treat it like a password. It is the only thing standing between another process
on your network and control of your browser.

### Manual install (no systemd)

```bash
python3 broker/cr_broker.py
```

Environment overrides: `CR_BROKER_HTTP_PORT` (8774), `CR_BROKER_WS_PORT` (8775),
`CR_BROKER_CONF` (`~/.config/cr-remote`), `CR_BROKER_LOG` (`INFO`).

---

## 2. Browser extension

1. Open `chrome://extensions`
2. Turn on **Developer mode** (top right)
3. **Load unpacked** → select the `extension/` folder
4. Click the extension's icon and fill in:

   | Field | Value |
   |---|---|
   | Broker | `ws://<broker-host>:8775` |
   | Pairing token | contents of `~/.config/cr-remote/token` |
   | This PC's name | anything, e.g. `living room PC` |

5. Press **Save & connect**. The status line should read **Connected**.

Confirm the broker sees it:

```bash
curl -s -H "Authorization: Bearer $(cat ~/.config/cr-remote/token)" \
     http://localhost:8774/api/hosts
```

> **The browser must be signed in to Crunchyroll.** The extension has no
> credentials of its own — it borrows the site's session. If you are signed out
> you will get *"Not signed in to Crunchyroll in this browser"*.

### Adding it to an already-running browser

Loading an unpacked extension normally means a restart if the browser was started
with its own `--load-extension` flag. If the browser is already running with
`--remote-debugging-port`, you can install it live, without losing your tabs:

```js
// against http://127.0.0.1:9222/json/version → webSocketDebuggerUrl
{"id":1,"method":"Extensions.loadUnpacked","params":{"path":"/abs/path/to/extension"}}
```

This works on a plain `--remote-debugging-port=9222`; no special flag is needed.

---

## 3. Android app

Install the prebuilt APK:

```bash
adb install -r dist/cr-remote-1.0.0-debug.apk
```

or copy it to the phone and open it (you'll need "install from unknown sources").

> The APK is **debug-signed**. It is meant for sideloading on your own devices,
> not for distribution. Rebuilding produces a different signature, so uninstall
> before switching between someone else's build and your own.

### Pair it — the easy way

On the phone, open:

```
http://<broker-host>:8774/pair
```

Tap **Pair this phone**. The page hands the app a `crremote://pair?...` deep link
carrying both the broker address and the token, so you never type the token on a
phone keyboard.

### Pair it — by hand

Open the app and enter the broker host and token on the pairing screen.

### Pair it — over adb

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "crremote://pair?broker=<broker-host>%3A8774&token=$(cat ~/.config/cr-remote/token)"
```

Any number of phones can share one token.

---

## 4. Use it

1. Open a Crunchyroll tab in the controlled browser (any page will do).
2. On the phone: pick from **Trending now** / **New episodes**, or search.
3. Choose a season, tap an episode — the PC navigates and starts playing.
4. The bar at the bottom is the transport. Tap it to expand:
   scrubber, ±10s, restart, next episode, mute, fullscreen, and a **dismiss**
   button for Crunchyroll's *"Are you still watching?"* modal.
5. The monitor icon in the top bar chooses **which PC** to drive when several
   browsers are connected. *Most recent* is the default and is usually right.

---

## Building from source

```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/cr-remote-1.0.0-debug.apk
```

Needs JDK **21** (AGP 9 rejects newer JDKs) and an Android SDK with API 37.
Point Gradle at your SDK via `android/local.properties`:

```properties
sdk.dir=/path/to/android-sdk
```

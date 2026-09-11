# Troubleshooting

Ordered roughly by how often each one bites.

---

### The app says "no PC is connected"

The broker is up but no browser has registered.

```bash
curl -s -H "Authorization: Bearer $(cat ~/.config/cr-remote/token)" \
     http://localhost:8774/api/hosts
```

- Is the browser open, with the extension enabled?
- Click the extension icon — does it say **Connected**?
- Does the extension's broker field point at the right host, `ws://` not `http://`?
- Does its token match `~/.config/cr-remote/token` exactly?

### "Not signed in to Crunchyroll in this browser"

The extension has no credentials of its own. Open `crunchyroll.com` **in that
browser profile** and sign in.

Note Crunchyroll answers **`403`**, not `401`, when there is no session — so a
403 here means "signed out", not "forbidden".

### The phone can't reach the broker

**Do not test with `ping`.** ICMP through Tailscale's `tun0` is filtered for
non-root on Android, so ping reports 100% loss even when everything works. Use
HTTP:

```bash
adb shell curl -s -m 5 http://<broker-host>:8774/api/health
```

If that fails: check Tailscale is connected on both ends, and that the broker
actually bound the tailnet address (`systemctl --user status cr-broker` logs the
addresses it bound at startup).

### Fullscreen makes the *browser window* fullscreen instead of the video

The trusted-keypress path failed and it fell back to window fullscreen. Usually
because **another debugger client is attached to that tab** — DevTools open on
the Crunchyroll tab, or another automation tool. Close DevTools and retry.

The reply tells you which path ran:

```json
{"ok": true, "method": "keypress-f"}         // player fullscreen
{"ok": true, "method": "window-fullscreen"}  // fallback
```

### "the browser blocked playback — click the page once"

Chrome requires a user gesture before `video.play()` in a tab that has never been
interacted with. Click the page once; the tab is then trusted for the session.

### Playback jumps to an unexpected position after `restart`

Not a bug. Crunchyroll restores its **server-side playhead**. If the account has a
saved position for that episode, the player may seek back to it shortly after you
set `currentTime = 0`. Check the saved value with the `playheads` method.

### Status is empty or missing `episodeId`

The tab is not on a `/watch/` page. `status` returns
`{"hasVideo": false, "reason": "..."}` when there is nothing to report — that is
correct behaviour, not a failure.

### The extension stops responding after a while

MV3 service workers are evicted when idle. Websocket traffic and the broker's 20s
ping normally keep it alive, and a 1-minute alarm revives it otherwise. If it is
genuinely wedged, toggle the extension off and on in `chrome://extensions`.

### The extension disappears after restarting the browser

Extensions added live over CDP (`Extensions.loadUnpacked`) may not persist across
a browser restart. Load it the normal way — `chrome://extensions` → **Load
unpacked** — to make it stick.

### Systemd: broker dies at boot, works when started by hand

It binds the Tailscale address, which does not exist until Tailscale is up. The
unit has `ExecStartPre=tailscale status` plus `Restart=on-failure` to ride that
out. If it still loses the race, increase `RestartSec`.

If it stops when you log out, enable lingering:

```bash
sudo loginctl enable-linger $USER
```

---

## Build problems

### `The 'org.jetbrains.kotlin.android' plugin is no longer required since AGP 9.0`

AGP 9 has Kotlin built in. Remove the plugin; put the `kotlin { }` block
**outside** `android { }`.

### Gradle fails on a newer JDK

AGP 9.3 wants JDK **21**. A newer JDK on `PATH` breaks it:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

### `Unresolved reference 'rememberSaveable'`

It lives in `androidx.compose.runtime.saveable`, not the `androidx.compose.runtime.*`
wildcard. Watch out for the knock-on: when it fails to resolve, a `var expanded by
…` can silently bind to material3's internal `WideNavigationRailValue`, producing
errors that look unrelated.

`VolumeUp` / `VolumeOff` are in `Icons.AutoMirrored.Filled`.

### The bottom bar draws under the system navigation bar

With `enableEdgeToEdge()`, a Scaffold `bottomBar` extends behind the system bars.
Put `navigationBarsPadding()` on the bar's **content**, not on its `Surface` —
padding the Surface leaves a transparent gap with the page showing through.

---

## Debugging tips

Broker logs:

```bash
journalctl --user -u cr-broker -f
CR_BROKER_LOG=DEBUG python3 broker/cr_broker.py     # verbose, run by hand
```

Extension logs: `chrome://extensions` → **service worker** under cr-remote host.

Exercise any method straight from a shell:

```bash
TOK=$(cat ~/.config/cr-remote/token)
curl -s -H "Authorization: Bearer $TOK" "http://localhost:8774/api/rpc?method=status"
curl -s -H "Authorization: Bearer $TOK" "http://localhost:8774/api/rpc?method=control&action=pause"
```

### Never kill the broker with a `grep` pattern

```bash
# WRONG — the enclosing `bash -c` command line contains the pattern too,
# so this kills your own shell (exit 144). The [c] trick only hides grep.
ps -eo pid,args | grep '[c]r_broker.py' | awk '{print $1}' | xargs kill
```

Use the listening socket instead:

```bash
ss -ltnp | grep ':8774' | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2
# or simply
systemctl --user restart cr-broker
```

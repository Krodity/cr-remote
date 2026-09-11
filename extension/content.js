/* content.js — runs on Crunchyroll pages (including the player iframe) and is
 * the only thing that actually touches the <video>.
 *
 * Crunchyroll serves its player from static.crunchyroll.com inside an iframe,
 * so this script is injected into every frame and each frame answers only for
 * itself. The service worker walks the frame list and uses whichever frame
 * reports a real video element.
 */
(() => {
  if (window.__crRemoteInstalled) return;
  window.__crRemoteInstalled = true;

  const SEEK_STEP = 10;                       // matches the player's own arrow keys

  const video = () => {
    const vs = [...document.querySelectorAll("video")].filter(
      (v) => v.readyState > 0 || v.currentSrc || v.src
    );
    // The real episode is the longest video; CR sprinkles short preview clips around.
    return vs.sort((a, b) => (b.duration || 0) - (a.duration || 0))[0] || null;
  };

  /** Episode identity, read from whichever frame can see the address bar. */
  function meta() {
    const m = location.href.match(/\/watch\/([^/?#]+)/);
    if (!m) return {};
    const t = document.title.replace(/\s*[-|]\s*Crunchyroll\s*$/i, "").trim();
    return { episodeId: m[1], pageTitle: t, url: location.href };
  }

  function snapshot() {
    const v = video();
    if (!v) return { hasVideo: false, ...meta() };
    return {
      hasVideo: true,
      playing: !v.paused && !v.ended,
      paused: v.paused,
      ended: v.ended,
      position: Number.isFinite(v.currentTime) ? v.currentTime : 0,
      duration: Number.isFinite(v.duration) ? v.duration : 0,
      buffered: v.buffered.length ? v.buffered.end(v.buffered.length - 1) : 0,
      volume: v.volume,
      muted: v.muted,
      rate: v.playbackRate,
      fullscreen: !!document.fullscreenElement,
      ...meta(),
    };
  }

  /** Crunchyroll's "Are you still watching?" modal blocks playback until clicked.
   *  A synthetic .click() is not a user gesture, but it does satisfy this modal. */
  function dismissIdleModal() {
    const rx = /still watching|continue watching|keep watching|i'?m here/i;
    for (const el of document.querySelectorAll("button, [role=button], a")) {
      if (rx.test((el.textContent || "").trim()) && el.offsetParent !== null) {
        el.click();
        return true;
      }
    }
    return false;
  }

  async function control(action, value) {
    const v = video();
    if (!v) throw new Error("no video in this frame");
    switch (action) {
      case "play":
        dismissIdleModal();
        try {
          await v.play();
        } catch (e) {
          throw new Error(
            "the browser blocked playback — click the page once, then retry"
          );
        }
        break;
      case "pause":
        v.pause();
        break;
      case "toggle":
        if (v.paused) return control("play");
        v.pause();
        break;
      case "seek":                               // absolute, seconds
        v.currentTime = Math.max(0, Math.min(value ?? 0, v.duration || value || 0));
        break;
      case "forward":
        v.currentTime = Math.min(v.currentTime + (value || SEEK_STEP), v.duration || Infinity);
        break;
      case "back":
        v.currentTime = Math.max(v.currentTime - (value || SEEK_STEP), 0);
        break;
      case "restart":
        v.currentTime = 0;
        break;
      case "volume":
        v.volume = Math.max(0, Math.min(1, value ?? v.volume));
        break;
      case "mute":
        v.muted = value === null ? !v.muted : !!value;
        break;
      case "rate":
        v.playbackRate = value || 1;
        break;
      case "fullscreen": {
        // Must target the player container, not the raw <video>, or CR's own
        // controls end up outside the fullscreen element.
        const host = v.closest("#vilos-player, .video-player, div") || v;
        if (document.fullscreenElement) await document.exitFullscreen();
        else await host.requestFullscreen().catch(() => v.requestFullscreen());
        break;
      }
      case "dismiss":
        return { ok: true, dismissed: dismissIdleModal(), ...snapshot() };
      default:
        throw new Error(`unknown action ${action}`);
    }
    return { ok: true, ...snapshot() };
  }

  chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
    (async () => {
      try {
        if (msg?.cmd === "status") return reply(snapshot());
        if (msg?.cmd === "control") return reply(await control(msg.action, msg.value));
        reply({ error: "unknown cmd" });
      } catch (e) {
        reply({ error: e?.message || String(e) });
      }
    })();
    return true;                                 // keep the channel open for async
  });
})();

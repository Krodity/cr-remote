/* background.js — the cr-remote host.
 *
 * Keeps one websocket open to the broker and answers JSON-RPC calls from the
 * phone. Two kinds of call: Crunchyroll catalogue lookups (handled here, in a
 * logged-in context) and player commands (forwarded to the content script
 * running on the watch page).
 *
 * MV3 note: a service worker is normally torn down after ~30s idle. Websocket
 * traffic resets that timer, and the broker pings every 20s, so the socket
 * itself is what keeps this worker resident. The alarm below is the belt to
 * that braces -- it revives the worker and reconnects if Chrome kills us anyway.
 */
import * as CR from "./cr-api.js";

const DEFAULTS = { broker: "ws://localhost:8775", token: "", name: "" };
const RETRY_MIN = 2_000;
const RETRY_MAX = 60_000;

let ws = null;
let retry = RETRY_MIN;
let statusTimer = null;

/** Optional pre-seeded settings, so an install on the broker's own machine
 *  needs no copy-pasting. local-config.json sits beside this file, is
 *  git-ignored (it carries the token), and is read only while unpaired --
 *  a token typed into the popup always wins. */
async function seed() {
  try {
    const r = await fetch(chrome.runtime.getURL("local-config.json"));
    if (!r.ok) return false;
    const c = await r.json();
    if (!c.token) return false;
    await chrome.storage.local.set({
      broker: c.broker || DEFAULTS.broker, token: c.token,
      ...(c.name ? { name: c.name } : {}),
    });
    return true;
  } catch {
    return false;                          // no file: pair through the popup
  }
}

async function config() {
  let c = await chrome.storage.local.get(DEFAULTS);
  if (!c.token && (await seed())) c = await chrome.storage.local.get(DEFAULTS);
  if (!c.name) {
    const p = await chrome.runtime.getPlatformInfo();
    c.name = `${p.os} browser`;
  }
  return { ...DEFAULTS, ...c };
}

// Memoised: startup fires connect() from several events at once, and parallel
// first calls would each mint a different id -- one browser, three "PCs".
let hostIdP = null;
function hostId() {
  hostIdP ||= (async () => {
    const { hostId } = await chrome.storage.local.get("hostId");
    if (hostId) return hostId;
    const id = crypto.randomUUID().slice(0, 8);
    await chrome.storage.local.set({ hostId: id });
    return id;
  })();
  return hostIdP;
}

// ── talking to the watch page ────────────────────────────────────────────────
async function crTab() {
  // Prefer a tab actually on a /watch/ page; fall back to any Crunchyroll tab.
  const tabs = await chrome.tabs.query({ url: "https://www.crunchyroll.com/*" });
  return tabs.find((t) => t.url?.includes("/watch/")) || tabs[0] || null;
}

/** Crunchyroll's <video> lives in a static.crunchyroll.com iframe while the
 *  episode identity (the /watch/ URL) is only visible to the top frame, so we
 *  address frames individually and stitch the two halves together. */
async function frameIds(tabId) {
  try {
    const fr = await chrome.webNavigation.getAllFrames({ tabId });
    return (fr || []).map((f) => f.frameId);
  } catch {
    return [0];
  }
}

async function askFrame(tabId, frameId, msg) {
  try {
    return await chrome.tabs.sendMessage(tabId, msg, { frameId });
  } catch {
    // Content script absent (installed after the tab opened, or mid-navigation):
    // inject on demand, then try once more.
    try {
      await chrome.scripting.executeScript({
        target: { tabId, frameIds: [frameId] },
        files: ["content.js"],
      });
      return await chrome.tabs.sendMessage(tabId, msg, { frameId });
    } catch {
      return null;
    }
  }
}

/** Send to every frame, return the reply from the one holding the video. */
async function toPage(msg, { required = true } = {}) {
  const tab = await crTab();
  if (!tab) {
    if (!required) return null;
    throw new Error("no Crunchyroll tab is open in this browser");
  }
  const ids = await frameIds(tab.id);
  const replies = await Promise.all(ids.map((f) => askFrame(tab.id, f, msg)));
  const live = replies.filter(Boolean);

  const withVideo = live.find((r) => r.hasVideo || r.ok);
  if (withVideo) {
    // The player frame cannot see the address bar; borrow identity from the top.
    const top = live.find((r) => r.episodeId);
    return top ? { ...top, ...withVideo } : withVideo;
  }
  const errored = live.find((r) => r.error);
  if (errored && required) throw new Error(errored.error);
  // about:blank subframes answer with a contentless snapshot; the frame that
  // knows the episode is always the more useful answer.
  if (live.length) return live.find((r) => r.episodeId) || live[0];
  if (!required) return null;
  throw new Error("the Crunchyroll tab is not responding — reload it");
}

/**
 * Press a key as if the user did.
 *
 * A synthetic KeyboardEvent is untrusted, and Crunchyroll's own fullscreen
 * handler calls requestFullscreen(), which the browser refuses without a real
 * user gesture ("Permissions check failed"). Events injected through the
 * debugger API are trusted and do grant that gesture -- this is the same reason
 * ~/bin/cr-continue dispatches its click over CDP rather than calling .click().
 *
 * Costs a "started debugging this browser" infobar for the moment it is attached.
 */
async function trustedKey(tabId, { key, code, keyCode }) {
  const target = { tabId };
  await chrome.debugger.attach(target, "1.3");
  try {
    const base = {
      key,
      code,
      windowsVirtualKeyCode: keyCode,
      nativeVirtualKeyCode: keyCode,
    };
    await chrome.debugger.sendCommand(target, "Input.dispatchKeyEvent", {
      ...base, type: "keyDown", text: key,
    });
    await chrome.debugger.sendCommand(target, "Input.dispatchKeyEvent", {
      ...base, type: "keyUp",
    });
  } finally {
    await chrome.debugger.detach(target).catch(() => {});
  }
}

// ── RPC methods the phone can call ───────────────────────────────────────────
const METHODS = {
  ping: async () => ({ ok: true, at: Date.now() }),

  search: ({ q, n }) => CR.search(q, n ? Number(n) : 20),
  browse: ({ sort, n }) => CR.browse(sort || "popularity", n ? Number(n) : 30),
  recent: ({ n }) => CR.recent(n ? Number(n) : 100),
  seasons: ({ seriesId, id }) => CR.seasons(seriesId || id),
  episodes: ({ seasonId, id }) => CR.episodes(seasonId || id),
  upNext: ({ episodeId, id }) => CR.upNext(episodeId || id),
  playheads: ({ ids }) =>
    CR.playheads(Array.isArray(ids) ? ids : String(ids || "").split(",").filter(Boolean)),

  /** Open an episode. Reuses an existing Crunchyroll tab so we never pile up windows. */
  play: async ({ url, id, slug }) => {
    const target = url || `https://www.crunchyroll.com/watch/${id}/${slug || ""}`;
    if (!/^https:\/\/www\.crunchyroll\.com\/watch\//.test(target)) {
      throw new Error("refusing to navigate to a non-Crunchyroll URL");
    }
    const tab = await crTab();
    const t = tab
      ? await chrome.tabs.update(tab.id, { url: target, active: true })
      : await chrome.tabs.create({ url: target, active: true });
    await chrome.windows.update(t.windowId, { focused: true }).catch(() => {});
    return { ok: true, tabId: t.id, url: target };
  },

  /** Transport controls, executed against the page's own <video>. */
  control: async ({ action, value }) => {
    if (action === "fullscreen") {
      // Press "f", exactly as a viewer at the keyboard would: that drives
      // Crunchyroll's own player fullscreen, not the browser window's.
      const tab = await crTab();
      if (!tab) throw new Error("no Crunchyroll tab is open in this browser");
      try {
        await chrome.tabs.update(tab.id, { active: true });
        await trustedKey(tab.id, { key: "f", code: "KeyF", keyCode: 70 });
        return { ok: true, method: "keypress-f" };
      } catch (e) {
        // Debugger unavailable (another client attached, or policy). Last
        // resort: the browser window, which at least fills the screen.
        const win = await chrome.windows.get(tab.windowId);
        const state = win.state === "fullscreen" ? "normal" : "fullscreen";
        await chrome.windows.update(tab.windowId, { state });
        return {
          ok: true,
          method: "window-fullscreen",
          note: `could not press f (${e?.message || e}); fell back to window fullscreen`,
        };
      }
    }
    if (action === "next") {
      const tab = await crTab();
      const m = tab?.url?.match(/\/watch\/([^/?#]+)/);
      if (!m) throw new Error("no episode is open to advance from");
      const { next } = await CR.upNext(m[1]);
      if (!next) throw new Error("this is the last episode");
      await METHODS.play({ url: next.url });
      return { ok: true, playing: next };
    }
    return toPage({ cmd: "control", action, value: value === undefined ? null : Number(value) });
  },

  status: async () => {
    const s = await toPage({ cmd: "status" }, { required: false });
    return s || { playing: false, reason: "no Crunchyroll tab open" };
  },
};

// ── broker link ──────────────────────────────────────────────────────────────
async function dispatch(msg) {
  const fn = METHODS[msg.method];
  if (!fn) throw new Error(`unknown method ${msg.method}`);
  return (await fn(msg.params || {})) ?? { ok: true };
}

let connecting = false;

async function connect() {
  // One socket at a time: install, startup, the alarm and a settings change can
  // all ask for a connection within the same second.
  if (connecting || ws?.readyState === WebSocket.OPEN || ws?.readyState === WebSocket.CONNECTING) return;
  connecting = true;
  try {
    await dial();
  } finally {
    connecting = false;
  }
}

async function dial() {
  const cfg = await config();
  if (!cfg.token) {
    setBadge("!", "#b91c1c");
    return schedule();                   // not paired yet; options page will kick us
  }
  const url =
    `${cfg.broker.replace(/\/+$/, "")}/host` +
    `?token=${encodeURIComponent(cfg.token)}` +
    `&name=${encodeURIComponent(cfg.name)}` +
    `&id=${encodeURIComponent(await hostId())}`;

  try {
    ws = new WebSocket(url);
  } catch {
    return schedule();
  }

  ws.onopen = () => {
    retry = RETRY_MIN;
    setBadge("", "#16a34a");
    chrome.storage.local.set({ lastConnected: Date.now(), lastError: "" });
    startStatusPush();
  };

  ws.onmessage = async (ev) => {
    let msg;
    try {
      msg = JSON.parse(ev.data);
    } catch {
      return;
    }
    if (msg.type === "hello") {
      // The broker's own reachable address, for the popup's pairing QR.
      chrome.storage.local.set({ pairHost: msg.pairHost || "" });
      return;
    }
    try {
      ws.send(JSON.stringify({ id: msg.id, result: await dispatch(msg) }));
    } catch (e) {
      ws.send(JSON.stringify({ id: msg.id, error: e?.message || String(e) }));
    }
  };

  const sock = ws;
  ws.onclose = () => {
    if (ws !== sock) return;               // an older socket we already replaced
    stopStatusPush();
    setBadge("!", "#b91c1c");
    schedule();
  };
  ws.onerror = () => {
    chrome.storage.local.set({ lastError: `cannot reach broker at ${cfg.broker}` });
  };
}

function schedule() {
  retry = Math.min(retry * 2, RETRY_MAX);
  chrome.alarms.create("reconnect", { when: Date.now() + retry });
}

/** Push player status up so the phone's scrubber moves without polling. */
function startStatusPush() {
  stopStatusPush();
  statusTimer = setInterval(async () => {
    if (ws?.readyState !== WebSocket.OPEN) return;
    try {
      const status = await METHODS.status();
      ws.send(JSON.stringify({ type: "status", status }));
    } catch { /* page between navigations */ }
  }, 2000);
}

function stopStatusPush() {
  if (statusTimer) clearInterval(statusTimer);
  statusTimer = null;
}

function setBadge(text, color) {
  chrome.action.setBadgeText({ text });
  if (color) chrome.action.setBadgeBackgroundColor({ color });
}

chrome.alarms.onAlarm.addListener((a) => {
  if (a.name === "reconnect" && ws?.readyState !== WebSocket.OPEN) connect();
});
chrome.runtime.onStartup.addListener(connect);
chrome.runtime.onInstalled.addListener(connect);
chrome.storage.onChanged.addListener((ch) => {
  // Re-dial immediately when the options page saves new settings.
  if (ch.broker || ch.token || ch.name) {
    const old = ws;
    ws = null;
    try { old?.close(); } catch {}
    retry = RETRY_MIN;
    connect();
  }
});
// Keep a heartbeat alarm so a killed worker always comes back.
chrome.alarms.create("reconnect", { periodInMinutes: 1 });
connect();

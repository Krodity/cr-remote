/* cr-api.js — Crunchyroll Content API, called from the extension service worker.
 *
 * The extension runs in a logged-in crunchyroll.com context, so `credentials:
 * "include"` hands the site's own etp_rt cookie to the token endpoint. That is
 * why the phone never needs Crunchyroll credentials of its own: this is the
 * same grant the website performs for itself.
 */
const BASE = "https://www.crunchyroll.com";
// The public web client id the site's own JS uses (password half is empty).
const BASIC = "Basic " + btoa("noaihdevm_6iyg0a8l0q:");
const LOCALE = "en-US";

let token = null;        // { access, expires }

async function deviceId() {
  const { deviceId } = await chrome.storage.local.get("deviceId");
  if (deviceId) return deviceId;
  const id = crypto.randomUUID();
  await chrome.storage.local.set({ deviceId: id });
  return id;
}

/** Fetch (and cache) an access token, refreshing a minute before expiry. */
export async function auth() {
  if (token && token.expires > Date.now() + 60_000) return token.access;
  const body = new URLSearchParams({
    grant_type: "etp_rt_cookie",
    device_id: await deviceId(),
    device_type: "Chrome on Linux",
    device_name: "cr-remote",
  });
  const r = await fetch(`${BASE}/auth/v1/token`, {
    method: "POST",
    credentials: "include",
    headers: {
      Authorization: BASIC,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body,
  });
  if (!r.ok) {
    // 401 = token rejected, 403 = no etp_rt cookie at all. Both mean "sign in".
    throw new Error(
      r.status === 401 || r.status === 403
        ? "Not signed in to Crunchyroll in this browser — open crunchyroll.com and sign in"
        : `Crunchyroll auth failed (HTTP ${r.status})`
    );
  }
  const j = await r.json();
  token = { access: j.access_token, expires: Date.now() + (j.expires_in ?? 300) * 1000 };
  return token.access;
}

async function get(path) {
  const t = await auth();
  const sep = path.includes("?") ? "&" : "?";
  const r = await fetch(`${BASE}${path}${sep}locale=${LOCALE}`, {
    headers: { Authorization: "Bearer " + t },
  });
  if (r.status === 401 || r.status === 403) {   // token rejected mid-flight: retry once
    token = null;
    const t2 = await auth();
    const r2 = await fetch(`${BASE}${path}${sep}locale=${LOCALE}`, {
      headers: { Authorization: "Bearer " + t2 },
    });
    if (!r2.ok) throw new Error(`Crunchyroll API ${r2.status} on ${path}`);
    return r2.json();
  }
  if (!r.ok) throw new Error(`Crunchyroll API ${r.status} on ${path}`);
  return r.json();
}

/** Shape a series/episode panel down to what the phone actually renders. */
function poster(o) {
  const im = o.images || {};
  const set = (im.poster_wide || im.poster_tall || im.thumbnail || [])[0] || [];
  // Crunchyroll returns ascending sizes; take a mid one to spare mobile data.
  const pick = set[Math.min(2, Math.max(0, set.length - 1))] || set[0];
  return pick?.source || null;
}

export async function search(q, n = 20) {
  if (!q || !q.trim()) return { series: [] };
  const j = await get(
    `/content/v2/discover/search?q=${encodeURIComponent(q.trim())}&n=${n}&type=series`
  );
  const coll = (j.data || []).find((c) => c.type === "series") || { items: [] };
  return {
    series: (coll.items || []).map((s) => ({
      id: s.id,
      title: s.title,
      slug: s.slug_title,
      description: s.description || "",
      episodes: s.series_metadata?.episode_count ?? null,
      seasons: s.series_metadata?.season_count ?? null,
      poster: poster(s),
    })),
  };
}

export async function seasons(seriesId) {
  const j = await get(`/content/v2/cms/series/${seriesId}/seasons`);
  return {
    seasons: (j.data || []).map((s) => ({
      id: s.id,
      number: s.season_number,
      title: s.title,
      episodes: s.number_of_episodes ?? null,
      subbed: !!s.is_subbed,
      dubbed: !!s.is_dubbed,
    })),
  };
}

export async function episodes(seasonId) {
  const j = await get(`/content/v2/cms/seasons/${seasonId}/episodes`);
  return {
    episodes: (j.data || []).map((e) => ({
      id: e.id,
      number: e.episode_number ?? e.episode,
      season: e.season_number,
      title: e.title,
      slug: e.slug_title,
      description: e.description || "",
      durationMs: e.duration_ms ?? null,
      premium: !!e.is_premium_only,
      thumb: poster(e),
      url: `${BASE}/watch/${e.id}/${e.slug_title || ""}`,
    })),
  };
}

/** Resume points, so the phone can show "continue from 12:04". */
export async function playheads(episodeIds) {
  if (!episodeIds?.length) return { playheads: {} };
  const j = await get(
    `/content/v2/aepc/playheads?content_ids=${episodeIds.join(",")}`
  ).catch(() => ({ data: [] }));
  const out = {};
  for (const p of j.data || []) {
    out[p.content_id] = { seconds: p.playhead, finished: !!p.fully_watched };
  }
  return { playheads: out };
}

export async function upNext(episodeId) {
  const j = await get(`/content/v2/discover/up_next/${episodeId}`);
  const panel = (j.data || [])[0]?.panel;
  if (!panel) return { next: null };
  return {
    next: {
      id: panel.id,
      title: panel.title,
      number: panel.episode_metadata?.episode_number,
      season: panel.episode_metadata?.season_number,
      slug: panel.slug_title,
      url: `${BASE}/watch/${panel.id}/${panel.slug_title || ""}`,
    },
  };
}

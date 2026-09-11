const $ = (id) => document.getElementById(id);
const FIELDS = ["broker", "token", "name"];

(async () => {
  const c = await chrome.storage.local.get(["broker", "token", "name"]);
  $("broker").value = c.broker || "ws://aepc:8775";
  $("token").value = c.token || "";
  $("name").value = c.name || "";
  render();
})();

$("save").onclick = async () => {
  const vals = Object.fromEntries(FIELDS.map((f) => [f, $(f).value.trim()]));
  if (!/^wss?:\/\//.test(vals.broker)) {
    return flag("bad", "Broker must start with ws:// or wss://");
  }
  if (!vals.token) return flag("bad", "Pairing token is required");
  await chrome.storage.local.set(vals);
  flag("wait", "connecting…");
  setTimeout(render, 1200);
};

async function render() {
  const c = await chrome.storage.local.get(["lastConnected", "lastError", "token"]);
  if (!c.token) return flag("bad", "Not paired yet");
  if (c.lastError) return flag("bad", c.lastError);
  if (!c.lastConnected) return flag("wait", "connecting…");
  const age = Date.now() - c.lastConnected;
  flag(age < 120_000 ? "ok" : "wait",
       age < 120_000 ? "Connected" : `Last connected ${Math.round(age / 60000)} min ago`);
}

function flag(kind, msg) {
  $("msg").textContent = msg;
  document.querySelector(".dot").style.background =
    kind === "ok" ? "var(--ok)" : kind === "bad" ? "var(--bad)" : "var(--mut)";
}

chrome.storage.onChanged.addListener(render);

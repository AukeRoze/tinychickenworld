/*
 * Golden test page — renders the latest full-pipeline regression run against
 * the baseline: per golden job a metric table with delta colouring, the
 * fail/warn verdict, and the keyframe contact-sheets (latest vs baseline)
 * side by side for the human eye. Read-only: the bench runs on the host
 * (golden-test.bat) and publishes into {workdir}/golden/.
 */
import { api } from "/assets/js/api.js";

const host = document.getElementById("golden-host");

/** Metrics where LOWER is better (mirror of the bench's TOLERANCES). */
const LOWER_BETTER = new Set(["durDeltaPct", "loudnessLUFS_err", "edgeBarFrames",
                              "edgeBarMaxPx", "costEur"]);
const HIDDEN = new Set(["contactSheet", "sheetName", "error"]);

function fmt(v) {
  if (v === null || v === undefined) return "—";
  if (typeof v === "number") return Math.round(v * 100) / 100;
  return String(v);
}

function deltaClass(key, baseVal, curVal) {
  if (typeof baseVal !== "number" || typeof curVal !== "number") return "";
  const diff = curVal - baseVal;
  if (diff === 0) return "";
  const worse = LOWER_BETTER.has(key) ? diff > 0 : diff < 0;
  return worse ? "golden-worse" : "golden-better";
}

function card(title) {
  const c = document.createElement("div");
  c.className = "card";
  if (title) {
    const h = document.createElement("h3");
    h.className = "card-title";
    h.textContent = title;
    c.appendChild(h);
  }
  return c;
}

function verdictBanner(latest) {
  const b = document.createElement("div");
  b.className = "card";
  const fails = latest.fails || [];
  const warns = latest.warns || [];
  let icon = "✅", msg = "Geen regressies t.o.v. de baseline.";
  if (fails.length) { icon = "❌"; msg = `${fails.length} regressie(s) — niet shippen.`; }
  else if (warns.length) { icon = "⚠️"; msg = `${warns.length} kleine verslechtering(en) binnen tolerantie.`; }
  b.innerHTML = `<h3 class="card-title">${icon} Run ${latest.ts || "?"} (${latest.motion || "?"})</h3>
    <p class="sub">${msg}</p>`;
  for (const f of fails) {
    const p = document.createElement("p");
    p.className = "sub small golden-worse";
    p.textContent = "❌ " + f;
    b.appendChild(p);
  }
  for (const w of warns) {
    const p = document.createElement("p");
    p.className = "sub small";
    p.textContent = "⚠️ " + w;
    b.appendChild(p);
  }
  return b;
}

function jobCard(tag, cur, base) {
  const c = card(`🎬 ${tag}`);
  const t = document.createElement("table");
  t.className = "golden-table";
  t.innerHTML = "<thead><tr><th>metric</th><th>baseline</th><th>nu</th></tr></thead>";
  const tb = document.createElement("tbody");
  const keys = [...new Set([...Object.keys(base || {}), ...Object.keys(cur || {})])]
      .filter(k => !HIDDEN.has(k)).sort();
  for (const k of keys) {
    const tr = document.createElement("tr");
    const cls = deltaClass(k, base?.[k], cur?.[k]);
    tr.innerHTML = `<td class="mono">${k}</td><td>${fmt(base?.[k])}</td>
                    <td class="${cls}">${fmt(cur?.[k])}</td>`;
    tb.appendChild(tr);
  }
  t.appendChild(tb);
  c.appendChild(t);

  // Contact-sheets naast elkaar — de menselijke blik op visuele drift.
  const sheets = document.createElement("div");
  sheets.className = "golden-sheets";
  for (const [label, name] of [["baseline", `${tag}-sheet-baseline.jpg`],
                               ["nu", `${tag}-sheet.jpg`]]) {
    const fig = document.createElement("figure");
    const img = document.createElement("img");
    img.src = `/api/v1/golden/sheet/${name}?t=${Date.now()}`;
    img.alt = `${tag} keyframes (${label})`;
    img.loading = "lazy";
    img.onerror = () => fig.remove();
    const cap = document.createElement("figcaption");
    cap.className = "sub small";
    cap.textContent = label;
    fig.appendChild(img);
    fig.appendChild(cap);
    sheets.appendChild(fig);
  }
  c.appendChild(sheets);
  return c;
}

function injectStyles() {
  const s = document.createElement("style");
  s.textContent = `
    .golden-table { width:100%; border-collapse:collapse; margin:8px 0; }
    .golden-table th, .golden-table td { text-align:left; padding:4px 10px;
      border-bottom:1px solid var(--border, #ddd); font-size:13px; }
    .golden-better { color:#15803d; font-weight:600; }
    .golden-worse  { color:#b91c1c; font-weight:600; }
    .golden-sheets { display:flex; gap:12px; flex-wrap:wrap; margin-top:10px; }
    .golden-sheets figure { margin:0; flex:1 1 380px; }
    .golden-sheets img { width:100%; border-radius:8px; }
  `;
  document.head.appendChild(s);
}

async function load() {
  injectStyles();
  let data;
  try {
    data = await api.get("/api/v1/golden", { key: "golden" });
  } catch (e) {
    host.textContent = "Kon golden-resultaten niet laden.";
    return;
  }
  host.textContent = "";
  const latest = data.latest, baseline = data.baseline;
  if (!latest) {
    host.innerHTML = `<div class="card"><p class="sub">Nog geen golden run gepubliceerd.
      Draai <code>golden-test.bat --save-baseline</code> op een goede build —
      de resultaten verschijnen hier vanzelf.</p></div>`;
    return;
  }
  host.appendChild(verdictBanner(latest));
  const baseResults = (baseline && baseline.results) || {};
  for (const [tag, cur] of Object.entries(latest.results || {})) {
    host.appendChild(jobCard(tag, cur, baseResults[tag]));
  }
}

load();

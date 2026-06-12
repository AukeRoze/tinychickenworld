/*
 * Jobs list page for the new static dashboard (/ui/).
 *
 * Proves the target architecture end-to-end with no build step:
 *   JSON endpoint (GET /api/v1/videos) → api.js → render → DOM,
 * with a 5s poll that re-renders WITHOUT a full-page reload.
 *
 * XSS-safe by construction: every dynamic value goes in via textContent /
 * element properties, never innerHTML string concatenation.
 */
import api, { toast } from "/assets/js/api.js";

const POLL_MS = 5000;
const host = document.getElementById("jobs-host");
const statusLine = document.getElementById("status-line");

// ── Health-strip: OAuth-token + ref-dekking ──
// Eén rode balk bovenaan in plaats van een begraven ERROR-logregel: een dood
// YouTube-token of een castlid zonder referentiebeelden zie je hier vóórdat
// het een publicatie kost.
const healthHost = document.createElement("div");
healthHost.style.cssText = "margin:8px 0";
host.parentNode.insertBefore(healthHost, host);

async function loadHealth() {
  try {
    const h = await api.get("/api/v1/insights/health", { key: "health" });
    healthHost.replaceChildren();
    const issues = [];
    if (h.oauth && h.oauth.healthy === false) {
      issues.push("🔑 YouTube-token DOOD — uploads/captions falen. Fix: StoredCredential " +
          "verwijderen + OAuth-flow opnieuw. (" + (h.oauth.lastError || "") + ")");
    } else if (h.oauth && h.oauth.healthy == null) {
      issues.push("🔑 Tokenstatus onbekend (upload-service niet bereikbaar of nog niet gecheckt).");
    }
    if (h.refs && h.refs.missing && h.refs.missing.length) {
      issues.push("🖼 Geen referentiebeelden voor: " + h.refs.missing.join(", ") +
          " — rendert zonder pixel-anker. Fix via de Cast-pagina.");
    }
    if (!issues.length) return;   // alles groen → geen balk, geen ruis
    for (const msg of issues) {
      const bar = document.createElement("div");
      bar.style.cssText = "background:rgba(200,60,60,.12);border:1px solid rgba(200,60,60,.4);" +
          "border-radius:8px;padding:8px 12px;margin-bottom:6px;font-size:13px";
      bar.textContent = "⚠ " + msg;
      healthHost.appendChild(bar);
    }
  } catch (e) { /* health is informatief — stil falen */ }
}
loadHealth();
setInterval(loadHealth, 60_000);

/** Coarse status → CSS hook for a coloured dot. Keeps styling in CSS, not JS. */
function statusKind(status) {
  if (!status) return "muted";
  if (status === "FAILED") return "danger";
  if (status === "COMPLETED") return "success";
  if (status.endsWith("_REVIEW_PENDING") || status === "DISTRIBUTION_PENDING") return "warning";
  return "primary"; // in-flight
}

/** "ASSETS_GENERATING" → "Assets generating". */
function prettyStatus(status) {
  if (!status) return "—";
  const s = status.replace(/_/g, " ").toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

function fmtWhen(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return isNaN(d) ? iso : d.toLocaleString();
}

function buildTable(jobs) {
  const table = document.createElement("table");
  table.className = "joblist clickable-rows";

  const thead = document.createElement("thead");
  const htr = document.createElement("tr");
  // Kop-checkbox: selecteer/deselecteer alle GEFILTERDE rijen in één klik.
  const thSel = document.createElement("th");
  const selAll = document.createElement("input");
  selAll.type = "checkbox";
  selAll.title = "Selecteer alle zichtbare jobs (voor bulk-retry/approve)";
  selAll.checked = jobs.length > 0 && jobs.every((j) => selection.has(j.id));
  selAll.addEventListener("click", (e) => {
    e.stopPropagation();
    if (selAll.checked) jobs.forEach((j) => selection.add(j.id));
    else jobs.forEach((j) => selection.delete(j.id));
    applyFilter();
  });
  thSel.appendChild(selAll);
  htr.appendChild(thSel);
  ["", "Topic", "Status", "Created", ""].forEach((label) => {
    const th = document.createElement("th");
    th.textContent = label;
    htr.appendChild(th);
  });
  thead.appendChild(htr);
  table.appendChild(thead);

  const tbody = document.createElement("tbody");
  for (const j of jobs) {
    const tr = document.createElement("tr");
    tr.tabIndex = 0;
    const go = () => { location.href = "/ui/job.html?id=" + encodeURIComponent(j.id); };
    tr.addEventListener("click", go);
    tr.addEventListener("keydown", (e) => { if (e.key === "Enter") go(); });

    // Bulk-selectie checkbox — klik mag de rij-navigatie niet triggeren.
    const sel = document.createElement("td");
    const cb = document.createElement("input");
    cb.type = "checkbox";
    cb.checked = selection.has(j.id);
    cb.title = "Selecteer voor bulk-acties (retry / approve)";
    cb.addEventListener("click", (e) => {
      e.stopPropagation();
      if (cb.checked) selection.add(j.id); else selection.delete(j.id);
      renderBulkBar();
    });
    sel.addEventListener("click", (e) => e.stopPropagation());
    sel.appendChild(cb);
    tr.appendChild(sel);

    // Small recognisable image left of the topic (completed jobs). Prefer the
    // first scene still (always present after a render), fall back to a thumbnail
    // variant, else hide gracefully.
    const thumb = document.createElement("td");
    thumb.className = "thumb-cell";
    if (j.status === "COMPLETED") {
      const img = document.createElement("img");
      img.className = "list-thumb";
      img.loading = "lazy";
      img.alt = "";
      const sources = [
        `/review/images/${encodeURIComponent(j.id)}/file/1.png`,
        `/dashboard/${encodeURIComponent(j.id)}/thumbnail/0.png`,
      ];
      let si = 0;
      img.onerror = () => { si += 1; if (si < sources.length) img.src = sources[si]; else img.remove(); };
      img.src = sources[0];
      thumb.appendChild(img);
    }
    tr.appendChild(thumb);

    const topic = document.createElement("td");
    topic.textContent = j.topic || "(no topic)";
    tr.appendChild(topic);

    const status = document.createElement("td");
    const pill = document.createElement("span");
    pill.className = "pill pill--" + statusKind(j.status);
    pill.textContent = prettyStatus(j.status);
    status.appendChild(pill);
    tr.appendChild(status);

    const created = document.createElement("td");
    created.className = "small mono";
    created.textContent = fmtWhen(j.createdAt);
    tr.appendChild(created);

    // Delete — stops row navigation.
    const actions = document.createElement("td");
    const del = document.createElement("button");
    del.className = "btn sm reject";
    del.textContent = "🗑";
    del.title = "Permanently delete this job and its files";
    del.addEventListener("click", async (e) => {
      e.stopPropagation();
      if (!confirm(`Delete "${j.topic || j.id}" and its files? This cannot be undone.`)) return;
      del.disabled = true;
      try {
        await api.del(`/api/v1/videos/${j.id}`, { key: "del-" + j.id });
        toast("Job deleted", "info");
        refresh();
      } catch (err) { del.disabled = false; }
    });
    actions.appendChild(del);
    tr.appendChild(actions);

    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  return table;
}

let latestJobs = [];

// ── Bulk-selectie: ids van aangevinkte jobs (overleeft de 5s-poll, wordt bij
// elke refresh opgeschoond tegen de actuele lijst). ──
const selection = new Set();

const FILTERS = [
  { key: "todo", label: "To do", pred: (j) => j.status !== "COMPLETED" },
  { key: "review", label: "Needs review",
    pred: (j) => !!j.status && (j.status.endsWith("_REVIEW_PENDING") || j.status === "DISTRIBUTION_PENDING") },
  { key: "active", label: "In progress",
    pred: (j) => !!j.status && !j.status.endsWith("_REVIEW_PENDING") &&
                 !["COMPLETED", "FAILED", "DISTRIBUTION_PENDING"].includes(j.status) },
  { key: "failed", label: "Failed", pred: (j) => j.status === "FAILED" },
  { key: "completed", label: "Completed", pred: (j) => j.status === "COMPLETED" },
  { key: "all", label: "All", pred: () => true },
];
let activeFilter = "todo"; // default hides completed so the to-do list stays clear

// ── Verfijning bovenop de quick-filters: multi-status + formaat. Stand wordt
// in localStorage bewaard zodat de gekozen weergave een reload overleeft. ──
let statusSel = new Set();   // leeg = alle statussen
let formatSel = "all";       // all | landscape | vertical
const LS_FILTERS = "jobsFilters.v1";

function loadFilterState() {
  try {
    const s = JSON.parse(localStorage.getItem(LS_FILTERS) || "{}");
    if (s.activeFilter && FILTERS.some((f) => f.key === s.activeFilter)) activeFilter = s.activeFilter;
    if (Array.isArray(s.statusSel)) statusSel = new Set(s.statusSel.filter((x) => typeof x === "string"));
    if (s.formatSel === "landscape" || s.formatSel === "vertical") formatSel = s.formatSel;
    if (typeof s.q === "string" && searchEl) searchEl.value = s.q;
  } catch (e) { /* defect of geblokkeerd localStorage → defaults */ }
}

function saveFilterState() {
  try {
    localStorage.setItem(LS_FILTERS, JSON.stringify({
      activeFilter, statusSel: [...statusSel], formatSel,
      q: searchEl ? searchEl.value : "",
    }));
  } catch (e) { /* best-effort */ }
}

function renderFilters() {
  const fhost = document.getElementById("job-filters");
  if (!fhost) return;
  const pills = FILTERS.map((f) => {
    const n = latestJobs.filter(f.pred).length;
    const b = document.createElement("button");
    b.className = "filter-pill" + (f.key === activeFilter ? " active" : "");
    b.textContent = `${f.label} (${n})`;
    b.addEventListener("click", () => { activeFilter = f.key; applyFilter(); });
    return b;
  });
  fhost.replaceChildren(...pills);
}

/** Multi-status pills + formaat-keuze (alleen statussen die nu voorkomen,
 *  plus eerder aangevinkte zodat een filter nooit "onuitzetbaar" verdwijnt). */
function renderExtraFilters() {
  const fhost = document.getElementById("job-filters-extra");
  if (!fhost) return;
  const seen = new Set(latestJobs.map((j) => j.status).filter(Boolean));
  statusSel.forEach((s) => seen.add(s));
  const nodes = [];

  const lbl = document.createElement("span");
  lbl.className = "sub small";
  lbl.textContent = "Status:";
  nodes.push(lbl);
  for (const st of [...seen].sort()) {
    const n = latestJobs.filter((j) => j.status === st).length;
    const b = document.createElement("button");
    b.className = "filter-pill" + (statusSel.has(st) ? " active" : "");
    b.textContent = `${prettyStatus(st)} (${n})`;
    b.title = statusSel.size
        ? "Klik om deze status aan/uit te zetten (leeg = alle statussen)"
        : "Klik om alleen deze status te tonen (meerdere mogelijk)";
    b.addEventListener("click", () => {
      if (statusSel.has(st)) statusSel.delete(st); else statusSel.add(st);
      applyFilter();
    });
    nodes.push(b);
  }
  if (statusSel.size) {
    const clr = document.createElement("button");
    clr.className = "filter-pill";
    clr.textContent = "✕ status-filter wissen";
    clr.addEventListener("click", () => { statusSel.clear(); applyFilter(); });
    nodes.push(clr);
  }

  // Formaat-filter — alleen tonen als de backend het veld al meelevert.
  if (latestJobs.some((j) => "format" in j)) {
    const flbl = document.createElement("span");
    flbl.className = "sub small";
    flbl.style.marginLeft = "12px";
    flbl.textContent = "Formaat:";
    nodes.push(flbl);
    for (const [val, label] of [["all", "alle"], ["landscape", "landscape"], ["vertical", "vertical (Shorts)"]]) {
      const b = document.createElement("button");
      b.className = "filter-pill" + (formatSel === val ? " active" : "");
      b.textContent = label;
      b.addEventListener("click", () => { formatSel = val; applyFilter(); });
      nodes.push(b);
    }
  }
  fhost.replaceChildren(...nodes);
}

/** Eén bulk-POST per geselecteerde job, sequentieel, met één samenvattende
 *  toast — geen nieuwe backend, gewoon de bestaande endpoints n×. */
async function runBulk(label, ids, urlOf) {
  let ok = 0, fail = 0;
  for (const jid of ids) {
    try {
      await api.post(urlOf(jid), undefined, { key: "bulk-" + jid });
      ok += 1;
    } catch (e) {
      if (e.name === "AbortError") continue;
      fail += 1; // api.js heeft de individuele fout al getoast
    }
  }
  toast(`${label}: ${ok} gelukt${fail ? `, ${fail} mislukt` : ""}`,
        fail ? "error" : "info", 7000);
  selection.clear();
  refresh();
}

function renderBulkBar() {
  const bhost = document.getElementById("job-bulk-bar");
  if (!bhost) return;
  const byId = new Map(latestJobs.map((j) => [j.id, j]));
  const picked = [...selection].map((jid) => byId.get(jid)).filter(Boolean);
  if (!picked.length) { bhost.replaceChildren(); return; }

  const failed = picked.filter((j) => j.status === "FAILED");
  const inReview = picked.filter((j) => !!j.status &&
      (j.status.endsWith("_REVIEW_PENDING") || j.status === "DISTRIBUTION_PENDING"));

  const bar = document.createElement("div");
  bar.style.cssText = "display:flex;align-items:center;gap:8px;flex-wrap:wrap;" +
      "border:1px solid var(--border,#ddd);border-radius:8px;padding:6px 10px;margin:6px 0";
  const count = document.createElement("span");
  count.className = "small";
  count.textContent = `${picked.length} geselecteerd`;
  bar.appendChild(count);

  const mkBtn = (label, title, jobs, confirmMsg, urlOf, actLabel) => {
    const b = document.createElement("button");
    b.className = "btn sm";
    b.textContent = `${label} (${jobs.length})`;
    b.title = title;
    b.disabled = !jobs.length;
    b.addEventListener("click", async () => {
      if (!jobs.length || !confirm(confirmMsg.replace("{n}", String(jobs.length)))) return;
      b.disabled = true;
      const orig = b.textContent;
      b.textContent = "⏳ bezig…";
      try { await runBulk(actLabel, jobs.map((j) => j.id), urlOf); }
      finally { b.textContent = orig; }
    });
    return b;
  };

  bar.appendChild(mkBtn("↻ Retry failed", "Hervat elke geselecteerde FAILED-job vanaf de gefaalde stap (bestaand endpoint, n× aangeroepen).",
      failed, "{n} mislukte job(s) opnieuw proberen?",
      (jid) => `/api/v1/videos/${jid}/retry`, "Retry"));
  bar.appendChild(mkBtn("✔ Approve review", "Keurt elke geselecteerde job op een review-gate goed — de pipeline gaat per job één stap verder.",
      inReview, "{n} job(s) op een review-gate goedkeuren?\n\nElke approve laat die pipeline één gate verder lopen.",
      (jid) => `/api/v1/videos/${jid}/approve`, "Approve"));

  const clear = document.createElement("button");
  clear.className = "btn sm";
  clear.textContent = "✕ Wis selectie";
  clear.addEventListener("click", () => { selection.clear(); applyFilter(); });
  bar.appendChild(clear);

  bhost.replaceChildren(bar);
}

function applyFilter() {
  const f = FILTERS.find((x) => x.key === activeFilter) || FILTERS[FILTERS.length - 1];
  const el = document.getElementById("job-search");
  const q = el ? el.value.trim().toLowerCase() : "";
  const filtered = latestJobs.filter((j) => {
    if (!f.pred(j)) return false;
    if (statusSel.size && !statusSel.has(j.status)) return false;
    if (formatSel !== "all" && (j.format || "landscape") !== formatSel) return false;
    if (!q) return true;
    return (j.topic || "").toLowerCase().includes(q) || (j.status || "").toLowerCase().includes(q);
  });
  host.replaceChildren(buildTable(filtered));
  statusLine.textContent =
    `${filtered.length} job(s) · ${f.label}${q ? ' · "' + q + '"' : ""} — updated ${new Date().toLocaleTimeString()}`;
  renderFilters();
  renderExtraFilters();
  renderBulkBar();
  saveFilterState();
}

async function refresh() {
  try {
    const jobs = await api.get("/api/v1/videos", { key: "jobs-list" });
    latestJobs = Array.isArray(jobs) ? jobs : [];
    // Selectie opschonen: verwijderde jobs mogen niet blijven "hangen".
    const ids = new Set(latestJobs.map((j) => j.id));
    for (const jid of [...selection]) if (!ids.has(jid)) selection.delete(jid);
    applyFilter();
  } catch (e) {
    if (e.name === "AbortError") return; // superseded by a newer poll
    statusLine.textContent = "could not load jobs (see toast)";
  }
}

const searchEl = document.getElementById("job-search");
if (searchEl) searchEl.addEventListener("input", applyFilter);
loadFilterState();

refresh();
setInterval(refresh, POLL_MS);

// Intro/outro rebuild lives on the Brand page now (/ui/brand.html).

// New-job AI helpers: fill the form from /random-idea or /improve-brief.
(function () {
  const form = document.getElementById("new-job-form");
  if (!form) return;
  const FIELDS = ["topic", "brief", "hook", "lesson", "mood", "angle"];
  const rnd = document.getElementById("btn-random");
  const imp = document.getElementById("btn-improve");

  // "Verbeter brief" needs something to work from — the backend 400s on an empty
  // topic AND brief — so keep it disabled until one of them has content.
  function syncImprove() {
    if (!imp) return;
    const has = !!(
      (form.elements.topic && form.elements.topic.value.trim()) ||
      (form.elements.brief && form.elements.brief.value.trim())
    );
    imp.disabled = !has;
    imp.title = has
      ? "Upgrade your rough brief into a production-ready one"
      : "Fill in a topic or brief first";
  }

  function fill(data) {
    if (!data) return;
    console.debug("[new-job] idea/brief fields:", Object.keys(data), data);
    for (const k of FIELDS) {
      const el = form.elements[k];
      if (el && data[k] != null) el.value = data[k];
    }
    syncImprove();
  }

  // Populate the Series dropdown from the bible's predefined series.
  (async function loadSeries() {
    const sel = document.getElementById("series-select");
    if (!sel) return;
    try {
      const series = await api.get("/api/v1/series", { key: "series" });
      for (const s of (Array.isArray(series) ? series : [])) {
        const opt = document.createElement("option");
        opt.value = s.id;
        opt.textContent = s.name || s.id;
        if (s.description) opt.title = s.description;
        sel.appendChild(opt);
      }
    } catch (e) { /* leave just the "none" option */ }
  })();

  async function run(btn, work) {
    const old = btn.textContent;
    btn.disabled = true;
    btn.textContent = "…";
    try { fill(await work()); }
    catch (e) { /* api.js toasted */ }
    finally {
      btn.textContent = old;
      if (btn === imp) syncImprove(); else btn.disabled = false;
    }
  }

  if (rnd) rnd.addEventListener("click", () =>
    run(rnd, () => api.get("/api/v1/random-idea", { key: "idea" })));

  if (imp) imp.addEventListener("click", () => run(imp, () => {
    const body = {};
    for (const k of FIELDS) { const el = form.elements[k]; if (el) body[k] = el.value; }
    return api.post("/api/v1/improve-brief", body, { key: "improve" });
  }));

  // Re-evaluate as the user types, and set the initial (disabled) state.
  ["topic", "brief"].forEach((k) => form.elements[k] &&
    form.elements[k].addEventListener("input", syncImprove));
  syncImprove();
})();

// 🧠 Self-learning zichtbaar (backlog P2): toon op het create-form de
// performance-hint die elke nieuwe script-prompt meekrijgt. Zelfde bron als
// req.performanceHint in de pipeline: InsightsAggregator.performanceHint(),
// hier gelezen via GET /api/v1/analytics → hint. Eén keer per pageload —
// het is context, geen live-meter. Faalt de call, dan blijft het blok
// verborgen en werkt het formulier gewoon.
(async function loadPerformanceHint() {
  const el = document.getElementById("perf-hint");
  if (!el) return;
  try {
    const a = await api.get("/api/v1/analytics", { key: "perf-hint" });
    el.hidden = false;
    el.style.cssText = "background:rgba(212,160,23,.10);border:1px solid rgba(212,160,23,.45);" +
        "border-radius:8px;padding:8px 12px;font-size:13px";
    if (a && a.hint) {
      el.textContent = "🧠 Self-learning actief — nieuwe scripts krijgen deze bias mee: " + a.hint;
      el.title = "Exact deze tekst gaat als performanceHint mee naar de script-service bij elke nieuwe job.";
    } else {
      el.textContent = "🧠 Nog geen performance-hint — zodra geüploade video's analytics-data " +
          "hebben (de poller draait elke 6 uur), krijgen nieuwe scripts automatisch een bias " +
          "richting de best presterende moods en lessen.";
    }
  } catch (e) { /* hint is informatief — formulier werkt gewoon zonder */ }
})();

// New-job form → POST /api/v1/videos, then jump to the created job's detail.
const form = document.getElementById("new-job-form");
if (form) {
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const fd = new FormData(form);
    const body = {};
    for (const [k, v] of fd.entries()) {
      const val = typeof v === "string" ? v.trim() : v;
      if (val === "") continue; // omit blanks — backend treats everything but topic as optional
      body[k] = (k === "targetSeconds" || k === "episodeNumber") ? Number(val) : val;
    }
    const btn = form.querySelector("button[type=submit]");
    btn.disabled = true;
    try {
      const created = await api.post("/api/v1/videos", body, { key: "create-job" });
      if (created && created.id) {
        location.href = "/ui/job.html?id=" + encodeURIComponent(created.id);
      } else {
        refresh();
      }
    } catch (err) {
      /* api.js already toasted the failure */
    } finally {
      btn.disabled = false;
    }
  });
}

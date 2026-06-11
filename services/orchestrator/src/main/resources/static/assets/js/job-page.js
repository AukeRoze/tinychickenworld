/*
 * Job detail page for the new static dashboard (/ui/job.html?id=...).
 *
 * Consumes GET /api/v1/videos/{id} (VideoJobResponse) and wires the lifecycle
 * actions through api.js. After every action it re-fetches the job and does a
 * targeted re-render — no location.reload(). XSS-safe: textContent only.
 *
 * Scope note: this is the core detail + actions view. The rich per-scene
 * grid / QA board / cost cards are a later chunk that needs a fuller JSON DTO;
 * until then the "classic view" link opens the old server-rendered detail.
 */
import api, { toast } from "/assets/js/api.js";

const id = new URLSearchParams(location.search).get("id");
const jobHost = document.getElementById("job-host");
const actionsHost = document.getElementById("actions-host");
const statusLine = document.getElementById("status-line");
const topicEl = document.getElementById("job-topic");
const scenesHost = document.getElementById("scenes-host");
const reviewHost = document.getElementById("review-host");
const stepperHost = document.getElementById("stepper-host");
const gateHost = document.getElementById("gate-host");
const costHost = document.getElementById("cost-host");
const castStrip = document.getElementById("cast-strip");

// Compact cast reference at the top — main characters with avatar + name
// (full description on hover). Loaded once; clicking opens the Cast page.
let castLoaded = false;
async function loadCastStrip() {
  if (castLoaded || !castStrip) return;
  castLoaded = true;
  try {
    const cast = await api.get("/api/v1/brand/cast", { key: "cast" });
    const mains = (Array.isArray(cast) ? cast : []).filter((c) => !c.role || c.role === "main");
    if (!mains.length) return;
    const wrap = document.createElement("div");
    wrap.className = "cast-strip-inner";
    for (const c of mains) {
      const chip = document.createElement("a");
      chip.className = "cast-chip";
      chip.href = "/ui/cast.html";
      chip.title = c.description || "";
      const img = document.createElement("img");
      img.src = `/api/v1/brand/character/${encodeURIComponent(c.id)}.png`;
      img.alt = c.name;
      img.onerror = () => img.remove();
      chip.appendChild(img);
      const nm = document.createElement("span");
      nm.textContent = c.name;
      chip.appendChild(nm);
      wrap.appendChild(chip);
    }
    castStrip.replaceChildren(wrap);
  } catch (e) { castLoaded = false; }
}

const pdfLink = document.getElementById("script-pdf");
if (pdfLink && id) pdfLink.href = "/api/v1/videos/" + encodeURIComponent(id) + "/script.pdf";

function isReview(status) {
  return !!status && (status.endsWith("_REVIEW_PENDING") || status === "DISTRIBUTION_PENDING");
}
function isFailed(status) {
  return status === "FAILED";
}

/** A definition-list row, XSS-safe. Skips empty values. */
function field(dl, label, value, { mono = false, link = false } = {}) {
  if (value === null || value === undefined || value === "") return;
  const dt = document.createElement("dt");
  dt.textContent = label;
  const dd = document.createElement("dd");
  if (link) {
    const a = document.createElement("a");
    a.href = value;
    a.target = "_blank";
    a.rel = "noopener";
    a.textContent = value;
    dd.appendChild(a);
  } else {
    dd.textContent = value;
    if (mono) dd.className = "mono small";
  }
  dl.appendChild(dt);
  dl.appendChild(dd);
}

function statusKind(status) {
  if (!status) return "muted";
  if (status === "FAILED") return "danger";
  if (status === "COMPLETED") return "success";
  if (status.endsWith("_REVIEW_PENDING") || status === "DISTRIBUTION_PENDING") return "warning";
  return "primary";
}

// Pipeline stepper — mirrors the classic dashboard's phase model so you can see
// exactly which production stage a job is in.
const PHASES = ["Script", "Voice + Images", "Video (Veo)", "Assembly",
                "Thumbnail", "Planning", "Upload", "Distribution"];
const PHASE_OF = {
  PENDING: [0, "queued"], SCRIPT_GENERATING: [0, "active"], SCRIPT_REVIEW_PENDING: [0, "review"],
  ASSETS_GENERATING: [1, "active"], IMAGES_REVIEW_PENDING: [1, "review"], ASSETS_REVIEW_PENDING: [1, "review"],
  VEO_GENERATING: [2, "active"], VEO_REVIEW_PENDING: [2, "review"], ASSEMBLING: [3, "active"],
  THUMBNAIL_REVIEW_PENDING: [4, "review"], UPLOAD_REVIEW_PENDING: [5, "review"], UPLOADING: [6, "active"],
  DISTRIBUTION_PENDING: [7, "review"], COMPLETED: [7, "done"], FAILED: [-1, "failed"],
};

// ── Voortgangsmodel: status → [van%, tot%, typische duur (s), standaardtekst].
// Binnen een actieve fase vult de balk asymptotisch richting het fase-plafond
// (beweegt altijd, liegt nooit voorbij de fase). Review-fases pauzeren amber.
const PROGRESS = {
  PENDING:                  [0,   3,   45, "In de wachtrij…"],
  SCRIPT_GENERATING:        [3,  14,  240, "Script schrijven — structuur-, pacing- en comedy-gates…"],
  SCRIPT_REVIEW_PENDING:    [14, 14,    0, "⏸ Wacht op jouw script-review (Pass A van de checklist)"],
  ASSETS_GENERATING:        [15, 45,  600, "Stills + stemmen genereren, drift-QC re-rolls…"],
  IMAGES_REVIEW_PENDING:    [45, 45,    0, "⏸ Wacht op jouw beeld-review"],
  ASSETS_REVIEW_PENDING:    [45, 45,    0, "⏸ Wacht op jouw assets-review"],
  VEO_GENERATING:           [46, 73,  900, "Veo-clips renderen — minuten per scène…"],
  VEO_REVIEW_PENDING:       [73, 73,    0, "⏸ Wacht op jouw Veo-review"],
  ASSEMBLING:               [74, 88,  300, "Montage: scènes, muziek, intro/outro, gates en audit…"],
  THUMBNAIL_REVIEW_PENDING: [88, 88,    0, "⏸ Kies en keur de thumbnail"],
  UPLOAD_REVIEW_PENDING:    [90, 90,    0, "⏸ Klaar voor publicatie — plan in of publiceer direct"],
  UPLOADING:                [90, 98,  240, "Uploaden naar YouTube…"],
  DISTRIBUTION_PENDING:     [98, 98,    0, "⏸ Distributie-opties beschikbaar"],
  COMPLETED:                [100, 100,  0, "✅ Klaar"],
  FAILED:                   [0,   0,    0, "❌ Mislukt — zie de foutmelding; Retry hervat vanaf de gefaalde stap"],
};
let progressSeen = { status: null, since: Date.now() };
let lastJob = null;

function progressPct(status) {
  const p = PROGRESS[status];
  if (!p) return 0;
  const [from, to, typical] = p;
  if (to <= from || typical <= 0) return to;
  const elapsed = (Date.now() - progressSeen.since) / 1000;
  return from + (to - from) * (1 - Math.exp(-elapsed / typical));
}

function updateProgress() {
  if (!lastJob) return;
  const status = lastJob.status;
  const p = PROGRESS[status];
  const fill = document.getElementById("job-progress-fill");
  const label = document.getElementById("job-progress-label");
  if (!p || !fill || !label) return;
  if (status !== progressSeen.status) progressSeen = { status, since: Date.now() };
  const review = (status || "").endsWith("_PENDING") && status !== "PENDING";
  if (status === "FAILED") {
    fill.style.background = "#b91c1c";          // breedte bevriest, kleur vertelt het
    label.textContent = p[3];
    return;
  }
  const pct = progressPct(status);
  fill.style.width = pct.toFixed(1) + "%";
  fill.style.background = review
      ? "linear-gradient(90deg,#f59e0b,#fbbf24)"   // amber: wacht op de mens
      : "linear-gradient(90deg,#d4a017,#f0b010)";  // goud: machine aan het werk
  // De backend-step is de eerlijkste "wat doet hij nu"-tekst; val terug op
  // de fase-standaard. Review-fases tonen altijd de duidelijke pauze-tekst.
  const doing = (!review && lastJob.step) ? lastJob.step : p[3];
  label.textContent = Math.round(pct) + "% · " + doing;
}

function renderStepper(job) {
  lastJob = job;
  const [idx, state] = PHASE_OF[job.status] || [0, "queued"];
  const wrap = document.createElement("div");
  wrap.className = "stepper";
  PHASES.forEach((label, i) => {
    let cls;
    if (idx < 0) cls = "failed";
    else if (i < idx) cls = "done";
    else if (i === idx) cls = state;
    else cls = "upcoming";
    const step = document.createElement("div");
    step.className = "step step--" + cls;
    // Done/active/review steps are clickable → scroll to that stage's info.
    if (cls !== "upcoming" && cls !== "failed") {
      const target = i <= 2 ? "scenes-host" : "review-host";
      step.classList.add("step--clickable");
      step.title = "View this stage's details";
      step.addEventListener("click", () => {
        const el = document.getElementById(target);
        if (!el) return;
        el.scrollIntoView({ behavior: "smooth", block: "start" });
        el.classList.add("flash");
        setTimeout(() => el.classList.remove("flash"), 1200);
      });
    }
    const dot = document.createElement("div");
    dot.className = "step-dot";
    const lab = document.createElement("div");
    lab.className = "step-label";
    lab.textContent = label;
    step.appendChild(dot);
    step.appendChild(lab);
    wrap.appendChild(step);
  });
  // ── Voortgangsbalk onder de stepper: 0-100% met live "wat doet hij nu" ──
  const prog = document.createElement("div");
  prog.style.cssText = "margin:8px 2px 2px";
  const barWrap = document.createElement("div");
  barWrap.style.cssText = "height:10px;border-radius:6px;background:var(--border,#e5e5e5);overflow:hidden";
  const fill = document.createElement("div");
  fill.id = "job-progress-fill";
  fill.style.cssText = "height:100%;width:0%;border-radius:6px;transition:width .9s ease;" +
      "background:linear-gradient(90deg,#d4a017,#f0b010)";
  barWrap.appendChild(fill);
  const plabel = document.createElement("div");
  plabel.id = "job-progress-label";
  plabel.className = "sub small";
  plabel.style.cssText = "margin-top:4px";
  prog.appendChild(barWrap);
  prog.appendChild(plabel);
  stepperHost.replaceChildren(wrap, prog);
  updateProgress();
}

// Cost shown as a tidy euro line right under the pipeline steps.
function renderCost(cost) {
  if (!cost) { costHost.replaceChildren(); return; }
  const pct = cost.capEur ? (cost.estimateEur / cost.capEur) * 100 : 0;
  const kind = pct > 90 ? "danger" : pct > 70 ? "warning" : "success";
  const line = document.createElement("div");
  line.className = "cost-line";
  const badge = document.createElement("span");
  badge.className = "audit-score";
  badge.style.background = "var(--" + kind + ")";
  badge.textContent = "€" + Number(cost.estimateEur).toFixed(2);
  line.appendChild(badge);
  const cap = document.createElement("span");
  cap.className = "small";
  cap.style.color = "var(--muted)";
  cap.textContent = "of €" + Number(cost.capEur).toFixed(2) + " budget";
  line.appendChild(cap);
  line.appendChild(bar(cost.estimateEur, cost.capEur || 1, kind));
  costHost.replaceChildren(line);
}

// Constant, non-user-data SVG (safe to use as innerHTML).
const YT_LOGO =
  '<svg width="24" height="17" viewBox="0 0 24 17" aria-hidden="true">' +
  '<rect width="24" height="17" rx="4" fill="#FF0000"/>' +
  '<path d="M9.5 4.5v8l7-4z" fill="#fff"/></svg>';

// Prominent review-gate bar at the top whenever the job is awaiting a human.
function renderGate(job) {
  if (!isReview(job.status)) {
    gateHost.replaceChildren();
    delete gateHost.dataset.sig;
    return;
  }
  // STABLE WHILE REVIEWING: load() polls every 5s and used to rebuild this
  // card each tick — wiping the thumbnail-selection highlight, reloading the
  // variant images (new ?t= cache-buster = visible flicker) and clearing a
  // half-filled publish date. The gate's content only depends on the job
  // status, so once rendered for a status we leave the DOM alone until the
  // status actually changes.
  const sig = job.status + "|" + id;
  if (gateHost.dataset.sig === sig && gateHost.childElementCount > 0) return;
  gateHost.dataset.sig = sig;
  const card = document.createElement("div");
  card.className = "card gate-card";
  const head = document.createElement("div");
  head.className = "gate-head";
  head.textContent = "⏸ Awaiting your review — " + job.status.replace(/_/g, " ").toLowerCase();
  card.appendChild(head);
  // Thumbnail-gate: het hele werk van deze stap (kies de beste variant) hoort
  // IN de gate-kaart — niet een halve pagina scrollen naar de Media-sectie.
  if (job.status === "THUMBNAIL_REVIEW_PENDING") {
    const hint = document.createElement("p");
    hint.className = "sub";
    hint.textContent = "Deze stap gaat alléén over de thumbnail. Klik je favoriet " +
        "(keuze wordt direct opgeslagen) en keur daarna goed. Squint-test: zie je " +
        "op telefoonformaat in één seconde een gezicht + één visueel raadsel?";
    card.appendChild(hint);
    const grid = document.createElement("div");
    grid.className = "thumb-pick";
    card.appendChild(grid);
    api.get(`/api/v1/videos/${id}/review`, { key: "gate-thumbs" }).then(data => {
      const variants = (data.media && data.media.thumbnailVariants) || [];
      if (!variants.length) { grid.textContent = "Geen varianten gevonden."; return; }
      for (const tv of variants) {
        const img = document.createElement("img");
        img.src = `/dashboard/${encodeURIComponent(id)}/thumbnail/${tv}.png?t=` + Date.now();
        img.alt = `Thumbnail ${tv}`;
        img.title = `Kies thumbnail ${tv}`;
        img.style.cursor = "pointer";
        img.addEventListener("click", async () => {
          try {
            await api.post(`/api/v1/videos/${id}/thumbnail/${tv}`, undefined, { key: "gate-thumb" });
            grid.querySelectorAll("img").forEach(i =>
                i.style.cssText = "cursor:pointer;opacity:.55");
            img.style.cssText = "cursor:pointer;opacity:1;outline:3px solid #d4a017;" +
                "outline-offset:2px;border-radius:6px";
            toast(`Thumbnail ${tv} gekozen ✓ — klik nu Approve`, "info");
          } catch (e) { /* api.js toasted */ }
        });
        grid.appendChild(img);
      }
    }).catch(() => { grid.textContent = "Varianten laden mislukte."; });
  }

  if (job.status === "THUMBNAIL_REVIEW_PENDING") {
    card.appendChild(thumbRegenRow(id, card));
  }

  const row = document.createElement("div");
  row.className = "filter-row";

  if (job.status === "UPLOAD_REVIEW_PENDING") {
    // Publish gate: schedule via a date picker OR go out immediately —
    // the moment you'd expect the calendar, not a bare "approve".
    const dt = document.createElement("input");
    dt.type = "datetime-local";
    // Native input styling (NOT .btn — that appearance reset hides the
    // browser's calendar indicator, so no picker ever showed).
    dt.style.cssText = "padding:8px 10px;border:1px solid var(--border,#ccc);" +
        "border-radius:8px;background:var(--bg,#fff);color:inherit;font:inherit";
    dt.min = new Date(Date.now() - new Date().getTimezoneOffset() * 60000)
        .toISOString().slice(0, 16);                 // geen datums in het verleden
    dt.title = "Kies het publicatiemoment — YouTube houdt de video private tot dan (scheduled publish).";
    // Open de picker betrouwbaar bij klik (Chrome opent hem anders alleen
    // via het kleine kalender-icoon).
    dt.addEventListener("click", () => { try { dt.showPicker && dt.showPicker(); } catch (e) {} });
    row.appendChild(dt);
    row.appendChild(actionButton("📅 Plan in & approve", "plan", async () => {
      if (!dt.value) {
        toast("Kies eerst een datum/tijd (klik het veld voor de kalender)", "error");
        try { dt.showPicker && dt.showPicker(); } catch (e) {}
        throw new Error("no date");
      }
      await api.post(`/api/v1/videos/${id}/planning`,
        { plannedPublishAt: new Date(dt.value).toISOString() });
      return api.post(`/api/v1/videos/${id}/approve`);
    }));
    row.appendChild(actionButton("🚀 Publiceer direct", "publishnow", async () => {
      // Clear een eventueel eerder gezette planning. Best-effort: als het
      // planning-endpoint nog niet gedeployed is (404), publiceren we alsnog —
      // zonder geplande datum is direct publiceren toch al het gedrag.
      try {
        await api.post(`/api/v1/videos/${id}/planning`, { plannedPublishAt: null });
      } catch (e) {
        toast("Planning-endpoint niet bereikbaar (oude build?) — publiceer zonder wissen door", "info");
      }
      return api.post(`/api/v1/videos/${id}/approve`);
    }));
  } else {
    const label = job.status === "THUMBNAIL_REVIEW_PENDING"
        ? "✓ Thumbnail gekozen — doorgaan"
        : "✓ Approve & continue";
    row.appendChild(actionButton(label, "approve",
      () => api.post(`/api/v1/videos/${id}/approve`)));
  }
  row.appendChild(actionButton("✗ Reject", "reject", () => {
    const reason = prompt("Reject reason (optional):") || "";
    return api.post(`/api/v1/videos/${id}/reject?reason=${encodeURIComponent(reason)}`);
  }));
  card.appendChild(row);
  gateHost.replaceChildren(card);
}

function renderJob(job) {
  topicEl.textContent = job.topic || "(no topic)";
  renderStepper(job);
  renderGate(job);

  const card = document.createElement("div");
  card.className = "card";

  // Header: coloured status badge + step.
  const head = document.createElement("div");
  head.className = "job-head";
  const badge = document.createElement("span");
  badge.className = "status-badge";
  badge.style.background = "var(--" + statusKind(job.status) + ")";
  badge.textContent = job.status || "—";
  head.appendChild(badge);
  if (job.step) {
    const step = document.createElement("span");
    step.className = "small";
    step.style.color = "var(--muted)";
    step.textContent = job.step;
    head.appendChild(step);
  }
  card.appendChild(head);

  // Error, if any — prominent.
  if (job.error) {
    const err = document.createElement("div");
    err.className = "job-error";
    err.textContent = job.error;
    card.appendChild(err);
  }

  // Primary CTA: a proper YouTube button instead of a raw long URL.
  if (job.youtubeUrl) {
    const a = document.createElement("a");
    a.className = "yt-btn";
    a.href = job.youtubeUrl;
    a.target = "_blank";
    a.rel = "noopener";
    a.innerHTML = YT_LOGO;
    a.appendChild(document.createTextNode("Watch on YouTube"));
    card.appendChild(a);
  }

  // Verbose/technical fields tucked into a collapsed disclosure.
  const det = document.createElement("details");
  det.className = "tech-details";
  const sum = document.createElement("summary");
  sum.textContent = "Technical details";
  det.appendChild(sum);
  const dl = document.createElement("dl");
  dl.className = "kv";
  field(dl, "ID", job.id, { mono: true });
  field(dl, "Video path", job.videoPath, { mono: true });
  det.appendChild(dl);
  card.appendChild(det);

  jobHost.replaceChildren(card);
  renderActions(job);
}

/** Build one action button that POSTs (or DELETEs), then refreshes. */
/** Invoerveld + knop om de thumbnail opnieuw te genereren met een eigen
 *  aanwijzing (bv. "precies drie kuikens, geen extra kippen op de achtergrond").
 *  Genereert 3 nieuwe varianten (duurt enkele minuten, 1× thumbnail-kosten) en
 *  ververst daarna de variant-afbeeldingen binnen `root` (cache-bust). */
function thumbRegenRow(id, root) {
  const row = document.createElement("div");
  row.style.cssText = "display:flex;align-items:center;gap:8px;margin:8px 0;flex-wrap:wrap";
  const inp = document.createElement("input");
  inp.type = "text";
  inp.placeholder = "Aanwijzing, bv. 'precies drie kuikens, geen extra kippen'";
  inp.style.cssText = "flex:1;min-width:240px;padding:8px 10px;border:1px solid " +
      "var(--border,#ccc);border-radius:8px;background:var(--bg,#fff);color:inherit;font:inherit";
  inp.title = "Stuur de regeneratie met een vrije aanwijzing — wordt als verplichte " +
      "instructie aan de prompt van alle 3 varianten toegevoegd";
  const btn = document.createElement("button");
  btn.className = "btn sm";
  btn.textContent = "🔁 Regenereer thumbnails";
  btn.addEventListener("click", async () => {
    const hint = inp.value.trim();
    if (!hint) { toast("Typ eerst een aanwijzing (wat moet er anders?)", "error"); return; }
    btn.disabled = true;
    const old = btn.textContent;
    btn.textContent = "⏳ Genereren… (duurt enkele minuten)";
    try {
      await api.post(`/api/v1/videos/${id}/thumbnail/regenerate`, { hint }, { key: "thumb-regen" });
      // Verse PNG's afdwingen voor alle varianten in deze kaart.
      (root || document).querySelectorAll(".thumb-pick img").forEach(img => {
        img.src = img.src.split("?")[0] + "?t=" + Date.now();
      });
      toast("Thumbnails opnieuw gegenereerd ✓ — bekijk de 3 nieuwe varianten", "info");
    } catch (e) { /* api.js toasted */ }
    finally { btn.disabled = false; btn.textContent = old; }
  });
  row.appendChild(inp);
  row.appendChild(btn);
  return row;
}

function actionButton(label, cls, run) {
  const b = document.createElement("button");
  b.className = "btn " + cls;
  b.textContent = label;
  b.addEventListener("click", async () => {
    b.disabled = true;
    try {
      await run();
      await load();
    } catch (e) {
      /* api.js already toasted */
    } finally {
      b.disabled = false;
    }
  });
  return b;
}

/** An action button paired with an ⓘ info icon explaining what it does. */
function actionItem(label, cls, help, run) {
  const w = document.createElement("span");
  w.className = "action-item";
  w.appendChild(actionButton(label, cls, run));
  const info = document.createElement("span");
  info.className = "info-icon";
  info.textContent = "ⓘ";
  info.title = help;
  info.setAttribute("aria-label", help);
  w.appendChild(info);
  return w;
}

function renderActions(job) {
  const s = job.status;
  const items = [];

  // Approve / Reject live in the prominent gate bar at the top (renderGate).
  if (isFailed(s)) {
    items.push(actionItem("Retry", "",
      "Retry a failed job from the stage that failed — reuses everything that already succeeded (script, images, voice).",
      () => api.post(`/api/v1/videos/${id}/retry`)));
    items.push(actionItem("Retry upload only", "",
      "Retry just the YouTube upload step, skipping all earlier stages. Use when only the upload failed.",
      () => api.post(`/api/v1/videos/${id}/retry-upload`)));
  }
  // Re-assemble only makes sense once the scene images + voice exist (i.e. the
  // job has passed asset generation). Hidden on early stages where there's
  // nothing to assemble.
  const ASSETS_READY = new Set([
    "IMAGES_REVIEW_PENDING", "ASSETS_REVIEW_PENDING", "VEO_GENERATING", "VEO_REVIEW_PENDING",
    "ASSEMBLING", "THUMBNAIL_REVIEW_PENDING", "UPLOAD_REVIEW_PENDING", "UPLOADING",
    "DISTRIBUTION_PENDING", "COMPLETED",
  ]);
  if (ASSETS_READY.has(s) || (s === "FAILED" && job.videoPath)) {
    items.push(actionItem("Re-assemble", "",
      "Rebuild the final video from the EXISTING assets (script, scene images, voice) — nothing is regenerated. Use to apply assembly / outro / thumbnail changes at no extra generation cost.",
      () => api.post(`/api/v1/videos/${id}/reassemble`)));
  }
  items.push(actionItem("Clone", "",
    "Create a NEW job that copies this one's brief and settings — a fresh run from scratch. The original is left untouched.",
    () => api.post(`/api/v1/videos/${id}/clone`)));
  items.push(actionItem("Delete ⚠️", "reject",
    "Permanently delete this job: its database row AND its files on disk. Cannot be undone.",
    async () => {
      if (!confirm("Permanently delete this job and its files?")) throw new Error("cancelled");
      await api.del(`/api/v1/videos/${id}`);
      location.href = "/ui/index.html";
    }));

  actionsHost.replaceChildren(...items);
}

/** A coloured score bar (0..max) → returns a wrapper element. */
function bar(value, max, kind) {
  const track = document.createElement("div");
  track.className = "sbar";
  const fill = document.createElement("div");
  fill.className = "sbar-fill sbar-fill--" + kind;
  fill.style.width = Math.max(0, Math.min(100, (value / max) * 100)) + "%";
  track.appendChild(fill);
  return track;
}

function axisKind(score) { return score >= 8 ? "success" : score >= 6 ? "warning" : "danger"; }

function reviewCard(title) {
  const card = document.createElement("div");
  card.className = "card";
  if (title) {
    const h = document.createElement("h3");
    h.className = "card-title";
    h.textContent = title;
    card.appendChild(h);
  }
  return card;
}

/** A labelled input/textarea for inline editing. Returns { wrap, input }. */
function labeledInput(label, value, multiline) {
  const wrap = document.createElement("label");
  wrap.className = "meta-field";
  wrap.appendChild(document.createTextNode(label));
  const input = multiline ? document.createElement("textarea") : document.createElement("input");
  if (multiline) input.rows = 4;
  input.value = value || "";
  wrap.appendChild(input);
  return { wrap, input };
}

function renderReview(ctx) {
  const data = ctx.review || {};
  const wrap = document.createElement("div");
  let any = false;
  // Localization + distribution only make sense once the video is on YouTube
  // (Instagram even needs a public URL). Gate them on an actual upload.
  const uploaded = !!(data.planning && data.planning.youtubeUrl);

  // ── Media: master video + thumbnail variant picker ──
  const media = data.media;
  if (media && (media.hasVideo || (media.thumbnailVariants && media.thumbnailVariants.length))) {
    any = true;
    const card = reviewCard("Media");
    if (media.hasVideo) {
      const v = document.createElement("video");
      v.controls = true;
      v.preload = "metadata";
      v.className = "master-video";
      v.src = `/dashboard/${encodeURIComponent(id)}/master.mp4`;
      card.appendChild(v);
    }
    // Auto-derived vertical Short (hook + meest energieke moment, 9:16).
    // Gratis bijvangst van elke assembly — download en upload als Short.
    if (media.hasShort) {
      const row = document.createElement("div");
      row.style.cssText = "display:flex;align-items:center;gap:8px;margin:8px 0";
      const a = document.createElement("a");
      a.className = "btn sm";
      a.href = `/dashboard/${encodeURIComponent(id)}/short.mp4`;
      a.target = "_blank";
      a.rel = "noopener";
      a.textContent = "📱 Bekijk/download de auto-Short (9:16, ±22s)";
      a.title = "Automatisch afgeleid uit de hook + het luidste (= spannendste) moment. " +
          "Upload als YouTube Short voor extra ontdekking — voeg #Shorts toe aan de titel.";
      row.appendChild(a);
      card.appendChild(row);
    }
    // Productie-metrics: wat kostte deze aflevering en hoe ver rekte de render
    // op t.o.v. het script (duurdiscipline-gate, audit ep-3: +28% onopgemerkt).
    if (data.metrics) {
      const m = data.metrics;
      const bits = [];
      if (m.veoCostEur != null) bits.push(`Veo €${Number(m.veoCostEur).toFixed(2)} (${m.veoOk}/${m.veoTotal} clips)`);
      if (m.stretchFactor != null) bits.push(`duur: script ${m.scriptedSeconds}s → master ${m.masterSeconds}s (×${m.stretchFactor})`);
      if (bits.length) {
        const p = document.createElement("p");
        p.className = "sub small mono";
        p.textContent = "📊 " + bits.join(" · ");
        if (m.stretchFactor != null && Number(m.stretchFactor) > 1.3) {
          p.style.color = "#b8651f";
          p.textContent += " ⚠ boven de ×1.30-grens";
        }
        card.appendChild(p);
      }
    }
    // Background-music picker: the auto-pick is stored on the job and reused
    // by every Reassemble — this lets you swap it (e.g. a calm rain track
    // instead of sunny_adventure). Applies on the next Reassemble.
    {
      const row = document.createElement("div");
      row.style.cssText = "display:flex;align-items:center;gap:8px;margin:8px 0";
      const sel = document.createElement("select");
      sel.className = "btn";
      sel.title = "Achtergrondmuziek voor deze video — wordt toegepast bij de volgende Reassemble";
      sel.appendChild(new Option("🎵 Muziek wisselen…", ""));
      api.get("/api/v1/brand/music", { key: "job-music-list" }).then(tracks => {
        for (const t of tracks) {
          if (t.present) sel.appendChild(new Option(`${t.id} · ${t.mood}`, t.id));
        }
      }).catch(() => {});
      const apply = document.createElement("button");
      apply.className = "btn sm";
      apply.textContent = "Toepassen";
      apply.addEventListener("click", async () => {
        if (!sel.value) { toast("Kies eerst een track", "error"); return; }
        apply.disabled = true;
        try {
          await api.post(`/api/v1/videos/${id}/music`, { trackId: sel.value }, { key: "job-music" });
          toast(`Muziek → ${sel.value}. Draai Reassemble om toe te passen.`, "info");
        } catch (e) { /* api.js toasted */ }
        finally { apply.disabled = false; }
      });
      row.appendChild(sel);
      row.appendChild(apply);
      card.appendChild(row);
    }

    if (media.thumbnailVariants && media.thumbnailVariants.length) {
      const pick = document.createElement("div");
      pick.className = "thumb-pick";
      for (const tv of media.thumbnailVariants) {
        const img = document.createElement("img");
        img.src = `/dashboard/${encodeURIComponent(id)}/thumbnail/${tv}.png`;
        img.alt = `Thumbnail ${tv}`;
        img.title = `Select thumbnail ${tv}`;
        img.addEventListener("click", async () => {
          try {
            await api.post(`/api/v1/videos/${id}/thumbnail/${tv}`, undefined, { key: "thumb" });
            toast(`Thumbnail ${tv} selected`, "info");
          } catch (e) { /* toasted */ }
        });
        pick.appendChild(img);
      }
      card.appendChild(pick);
      card.appendChild(thumbRegenRow(id, card));
    }
    wrap.appendChild(card);
  }

  // ── Retention per scène (self-learning loop) ──
  // Filled by the AnalyticsPoller once YouTube retention data lands; shows
  // WHERE kids drop off, per scene — the editorial half of the analytics loop.
  const ret = data.retentionScenes;
  if (Array.isArray(ret) && ret.length) {
    any = true;
    const card = reviewCard("📉 Retentie per scène");
    if (data.storyArc || data.thumbnailLayout) {
      const meta = document.createElement("p");
      meta.className = "sub small";
      meta.textContent = [
        data.storyArc ? `arc: ${data.storyArc}` : null,
        data.thumbnailLayout ? `thumbnail: ${data.thumbnailLayout}` : null,
      ].filter(Boolean).join(" · ");
      card.appendChild(meta);
    }
    const maxDrop = Math.max(0.001, ...ret.map(r => r.drop || 0));
    const list = document.createElement("div");
    for (const r of ret) {
      const row = document.createElement("div");
      row.style.cssText = "display:flex;align-items:center;gap:8px;margin:2px 0;font-size:12px";
      const label = document.createElement("span");
      label.className = "mono";
      label.style.cssText = "width:170px;flex:none";
      label.textContent = `s${String(r.seq).padStart(2, "0")} ${r.phase || ""} (${r.startSec}-${r.endSec}s)`;
      const barWrap = document.createElement("div");
      barWrap.style.cssText = "flex:1;background:var(--border,#eee);border-radius:4px;height:12px;overflow:hidden";
      const bar = document.createElement("div");
      const watch = Math.max(0, Math.min(1, r.avgWatchRatio || 0));
      const isWorst = (r.drop || 0) >= maxDrop * 0.99 && (r.drop || 0) > 0.01;
      bar.style.cssText = `height:100%;width:${Math.round(watch * 100)}%;` +
          `background:${isWorst ? "#b91c1c" : "#15803d"};opacity:.75`;
      bar.title = `kijkratio ${(watch * 100).toFixed(0)}%, drop ${((r.drop || 0) * 100).toFixed(1)}%`;
      barWrap.appendChild(bar);
      const dropTxt = document.createElement("span");
      dropTxt.className = "mono";
      dropTxt.style.cssText = "width:60px;flex:none;text-align:right";
      dropTxt.textContent = r.drop > 0 ? `-${(r.drop * 100).toFixed(1)}%` : "·";
      if (isWorst) dropTxt.style.color = "#b91c1c";
      row.appendChild(label);
      row.appendChild(barWrap);
      row.appendChild(dropTxt);
      list.appendChild(row);
    }
    card.appendChild(list);
    const hint = document.createElement("p");
    hint.className = "sub small";
    hint.textContent = "Rood = grootste kijkersverlies. Vergelijk met je eigen wegklik-gok uit de creative review.";
    card.appendChild(hint);
    wrap.appendChild(card);
  }

  // ── QA board ──
  const qa = data.qaBoard;
  if (qa) {
    any = true;
    const card = reviewCard(null);
    const min = qa.publishMin ?? 80;
    const total = qa.total ?? 0;
    const head = document.createElement("div");
    const badge = document.createElement("span");
    badge.className = "audit-score";
    badge.textContent = `QA ${total}/100`;
    badge.style.background =
      "var(--" + (qa.publishable ? "success" : total >= min ? "warning" : "danger") + ")";
    head.appendChild(badge);
    if (qa.verdict) {
      const v = document.createElement("span");
      v.className = "small";
      v.style.marginLeft = "8px";
      v.textContent = qa.verdict;
      head.appendChild(v);
    }
    card.appendChild(head);
    if (Array.isArray(qa.axes)) {
      const list = document.createElement("div");
      list.className = "axis-list";
      for (const ax of qa.axes) {
        const row = document.createElement("div");
        row.className = "axis-row";
        const name = document.createElement("span");
        name.className = "axis-name";
        name.textContent = ax.name;
        const score = document.createElement("span");
        score.className = "axis-score small mono";
        score.textContent = `${ax.score}/10`;
        row.appendChild(name);
        row.appendChild(bar(ax.score, 10, axisKind(ax.score)));
        row.appendChild(score);
        list.appendChild(row);
      }
      card.appendChild(list);
    }
    // 🤖 Auto-Fix: one round of re-rolling the weak scenes, then pause for review.
    const fixRow = document.createElement("div");
    fixRow.className = "scene-acts";
    fixRow.style.marginTop = "10px";
    const fixBtn = sceneBtn(
      "🤖 Auto-Fix (1 ronde)",
      "Genereert de gemarkeerde zwakke scènes één keer opnieuw, hermonteert en auditeert opnieuw, en pauzeert dan voor je review. Kost beeldgeneratie-credits (begrensd); geen Veo, geen auto-upload.",
      async () => {
        const ok = confirm(
          "Auto-Fix genereert de zwakke scènes ÉÉN keer opnieuw (kost beeldgeneratie-credits, " +
          "begrensd door een cap), hermonteert en auditeert opnieuw, en pauzeert dan voor je review.\n\n" +
          "Geen Veo-render, geen automatische upload.\n\nDoorgaan?");
        if (!ok) return;
        try {
          await api.post(`/api/v1/videos/${id}/autofix?iterations=1`, undefined, { key: "autofix" });
          toast("Auto-Fix gestart — dit duurt even; de status ververst vanzelf.", "info");
        } catch (e) { /* api.js toasted */ }
      });
    if (!qa.publishable) fixBtn.classList.add("approve");   // highlight when below the bar
    fixRow.appendChild(fixBtn);
    card.appendChild(fixRow);
    wrap.appendChild(card);
  }

  // ── Cost lives under the stepper now (renderCost), not as a review card ──
  renderCost(data.cost);

  // ── Metadata (editable) — only once it has actually been generated ──
  const meta = data.metadata;
  if (meta && (meta.title || meta.description || meta.tags)) {
    any = true;
    const card = reviewCard("Metadata");

    // Keep the <h3> title; swap everything after it between view and edit.
    const replaceBody = (node) => {
      while (card.childNodes.length > 1) card.removeChild(card.lastChild);
      card.appendChild(node);
    };

    const showView = () => {
      const body = document.createElement("div");
      const dl = document.createElement("dl");
      dl.className = "kv";
      field(dl, "Title", meta.title);
      field(dl, "Description", meta.description);
      field(dl, "Tags", meta.tags);
      body.appendChild(dl);
      body.appendChild(sceneBtn("✎ Edit", "Edit title / description / tags inline", showEdit));
      replaceBody(body);
    };

    const showEdit = () => {
      const form = document.createElement("div");
      form.className = "meta-edit";
      const t = labeledInput("Title", meta.title, false);
      const d = labeledInput("Description", meta.description, true);
      const g = labeledInput("Tags (comma-separated)", meta.tags, false);
      form.appendChild(t.wrap);
      form.appendChild(d.wrap);
      form.appendChild(g.wrap);
      const row = document.createElement("div");
      row.className = "filter-row";
      const save = sceneBtn("Save", "Save metadata", async () => {
        meta.title = t.input.value;
        meta.description = d.input.value;
        meta.tags = g.input.value;
        await api.patch(`/api/v1/videos/${id}/metadata`,
          { title: meta.title, description: meta.description, tags: meta.tags });
        toast("Metadata saved", "info");
        showView();
      });
      save.classList.add("approve");
      row.appendChild(save);
      row.appendChild(sceneBtn("Cancel", "Discard changes", showView));
      form.appendChild(row);
      replaceBody(form);
    };

    showView();
    wrap.appendChild(card);
  }

  // ── Planning (read-only) ──
  const pl = data.planning;
  if (pl && (pl.seriesId || pl.episodeNumber != null || pl.plannedPublishAt || pl.youtubeUrl)) {
    any = true;
    const card = reviewCard("Planning");
    const dl = document.createElement("dl");
    dl.className = "kv";
    field(dl, "Series", pl.seriesId);
    field(dl, "Episode", pl.episodeNumber != null ? String(pl.episodeNumber) : "");
    field(dl, "Planned publish", pl.plannedPublishAt);
    field(dl, "YouTube", pl.youtubeUrl, { link: true });
    card.appendChild(dl);
    wrap.appendChild(card);
  }

  // ── Localization (only after upload) ──
  if (uploaded) {
    any = true;
    const card = reviewCard("Localization");
    const locs = Array.isArray(ctx.localizations) ? ctx.localizations : [];
    if (locs.length) {
      const dl = document.createElement("dl");
      dl.className = "kv";
      for (const l of locs) field(dl, l.name || l.language, l.status);
      card.appendChild(dl);
    } else {
      const p = document.createElement("div");
      p.className = "small";
      p.style.color = "var(--muted)";
      p.textContent = "no localizations yet";
      card.appendChild(p);
    }
    const langs = ctx.languages && ctx.languages.supported;
    if (Array.isArray(langs) && langs.length) {
      const row = document.createElement("div");
      row.className = "filter-row";
      const sel = document.createElement("select");
      for (const code of langs) {
        const opt = document.createElement("option");
        opt.value = code;
        opt.textContent = (ctx.languages.names && ctx.languages.names[code]) || code;
        sel.appendChild(opt);
      }
      row.appendChild(sel);
      row.appendChild(sceneBtn("Localize", "Generate a localized version", async () => {
        await api.post(`/api/v1/videos/${id}/localize/${encodeURIComponent(sel.value)}`);
        loadReview();
      }));
      card.appendChild(row);
    }
    wrap.appendChild(card);
  }

  // ── Distribution (only after upload) ──
  if (uploaded) {
    any = true;
    const card = reviewCard("Distribution");
    const row = document.createElement("div");
    row.className = "filter-row";
    for (const p of ["tiktok", "instagram", "facebook"]) {
      row.appendChild(sceneBtn("→ " + p, "Push this video to " + p,
        async () => { await api.post(`/api/v1/videos/${id}/distribute/${p}`); toast("Pushed to " + p, "info"); }));
    }
    card.appendChild(row);
    wrap.appendChild(card);
  }

  // When there's nothing meaningful to show yet, hide the whole Review section
  // (header + host) instead of an empty "no review data" block.
  const h2 = reviewHost.previousElementSibling;
  if (!any) {
    reviewHost.style.display = "none";
    if (h2 && h2.tagName === "H2") h2.style.display = "none";
    reviewHost.replaceChildren();
    return;
  }
  reviewHost.style.display = "";
  if (h2 && h2.tagName === "H2") h2.style.display = "";
  reviewHost.replaceChildren(wrap);
}

async function loadReview() {
  // Review payload is required; localizations + languages are best-effort.
  let review, localizations = [], languages = null;
  try {
    review = await api.get(`/api/v1/videos/${id}/review`, { key: "review-" + id });
  } catch (e) {
    if (e.name === "AbortError") return;
    reviewHost.textContent = "could not load review (see toast)";
    return;
  }
  try { localizations = await api.get(`/api/v1/videos/${id}/localizations`, { key: "locs-" + id }); } catch (e) {}
  try { languages = await api.get(`/api/v1/languages`, { key: "langs" }); } catch (e) {}
  renderReview({ review, localizations, languages });
}

/** Small async action button: disables itself while running. */
function sceneBtn(label, title, run) {
  const b = document.createElement("button");
  b.className = "btn sm";
  b.textContent = label;
  if (title) b.title = title;
  b.addEventListener("click", async () => {
    b.disabled = true;
    // Clip-generatie is een synchrone call van 1-3 min — laat zien dat hij loopt.
    const orig = b.textContent;
    b.textContent = "⏳ bezig…";
    try { await run(); } catch (e) { /* api.js toasted */ }
    finally { b.disabled = false; b.textContent = orig; }
  });
  return b;
}

/** A scene action button paired with an ⓘ info icon (hover = what it does). */
function sceneItem(label, help, run, extraClass) {
  const w = document.createElement("span");
  w.className = "action-item";
  const b = sceneBtn(label, help, run);
  if (extraClass) b.classList.add(extraClass);
  w.appendChild(b);
  const info = document.createElement("span");
  info.className = "info-icon";
  info.textContent = "ⓘ";
  info.title = help;
  info.setAttribute("aria-label", help);
  w.appendChild(info);
  return w;
}

/** Cache-bust a scene image so a freshly regenerated one shows immediately. */
function bust(img) {
  img.style.display = "";
  img.src = img.src.split("?")[0] + "?t=" + Date.now();
}

/** A single labelled still (start or end) with a graceful "no image yet" box.
 *  `version` (the still's mtime) is appended as ?v= so a regenerated image
 *  refreshes — and only when it actually changed (poll re-renders stay cached). */
function stillFrame(seq, label, version) {
  const frame = document.createElement("div");
  frame.className = "scene-img-frame";
  const img = document.createElement("img");
  img.loading = "lazy";
  img.alt = (label || "Scene") + " " + seq;
  const ph = document.createElement("div");
  ph.className = "scene-img-ph";
  ph.textContent = "no image yet";
  ph.style.display = "none";
  img.onload = () => { img.style.display = ""; ph.style.display = "none"; };
  img.onerror = () => { img.style.display = "none"; ph.style.display = "flex"; };
  img.src = `/review/images/${encodeURIComponent(id)}/file/${seq}.png`
          + (version ? `?v=${version}` : "");
  frame.appendChild(img);
  frame.appendChild(ph);
  if (label) {
    const cap = document.createElement("div");
    cap.className = "scene-img-cap";
    cap.textContent = label;
    frame.appendChild(cap);
  }
  return { frame, img };
}

/**
 * Scene image(s) for the right column. Normal scenes show one still. "Hero"
 * scenes that also have a directed end-still show TWO frames side by side —
 * "Start" → "Eind" — so it's clear Veo interpolates between them.
 * Returns { frame, img } where img is the START still (for cache-busting on regen).
 */
function sceneImage(s) {
  const seq = s.seq;
  const start = stillFrame(seq, s.hasEndStill ? "Start" : null, s.imageVersion);
  if (!s.hasEndStill || s.endStillSeq < 0) {
    return { frame: start.frame, img: start.img };
  }
  // Two-up: start → end, with an arrow between, so the directed motion reads.
  const wrap = document.createElement("div");
  wrap.className = "scene-img-pair";
  const arrow = document.createElement("div");
  arrow.className = "scene-img-arrow";
  arrow.textContent = "→";
  arrow.title = "Veo animates from the Start frame to the End frame (directed motion)";
  const end = stillFrame(s.endStillSeq, "Eind");
  wrap.appendChild(start.frame);
  wrap.appendChild(arrow);
  wrap.appendChild(end.frame);
  return { frame: wrap, img: start.img };
}

// One row per scene: script (dialogue + visual description) on the LEFT,
// the scene image (or placeholder) + per-scene actions on the RIGHT.
function renderScenes(scenes) {
  if (!scenes || scenes.length === 0) {
    scenesHost.textContent = "no scenes yet (script not generated)";
    return;
  }
  const list = document.createElement("div");
  list.className = "scene-rows";
  for (const s of scenes) {
    const seq = s.seq;
    const row = document.createElement("div");
    row.className = "scene-row" + (s.locked ? " locked" : "");

    // ✨ SILENT VISUAL BEAT — the one scene with no dialogue, carried entirely
    // by the image. Golden frame so the reviewer's eye lands here first:
    // this is the shot to judge hardest (acting beat, not scenery).
    const isSilentBeat = (!s.lines || s.lines.length === 0) && !s.narration;
    if (isSilentBeat) {
      row.style.cssText += "border:2px solid #d4a017;border-radius:10px;" +
          "box-shadow:0 0 12px rgba(212,160,23,.35);padding:10px;" +
          "background:linear-gradient(rgba(212,160,23,.06),rgba(212,160,23,.02))";
      const badge = document.createElement("div");
      badge.style.cssText = "color:#b8860b;font-weight:700;font-size:12px;margin-bottom:4px";
      badge.textContent = "✨ STILLE SCÈNE — het beeld ís de scène. Wees hier het strengst: " +
          "een acteer-beat (houding, blik, adem), geen plaatje van een plek.";
      row.appendChild(badge);
    }

    // LEFT — script
    const left = document.createElement("div");
    left.className = "scene-text";
    const head = document.createElement("div");
    head.className = "script-head";
    const bits = ["Scene " + seq];
    if (s.durationSeconds) bits.push(s.durationSeconds + "s");
    if (s.phase) bits.push(s.phase);
    if (s.hasClip) bits.push("🎬");
    if (s.locked) bits.push("🔒");
    head.textContent = bits.join(" · ");
    left.appendChild(head);
    for (const l of (s.lines || [])) {
      const line = document.createElement("div");
      line.className = "script-line";
      const sp = document.createElement("b");
      sp.textContent = (l.speaker || "?") + ": ";
      line.appendChild(sp);
      line.appendChild(document.createTextNode(l.text || ""));
      left.appendChild(line);
    }
    if (s.visualDesc) {
      const vd = document.createElement("div");
      vd.className = "script-vd small";
      vd.textContent = s.visualDesc;
      left.appendChild(vd);
    }
    if (!(s.lines && s.lines.length) && !s.visualDesc && s.narration) {
      const n = document.createElement("div");
      n.className = "script-line";
      n.textContent = s.narration;
      left.appendChild(n);
    }

    // RIGHT — image + per-scene actions
    const right = document.createElement("div");
    right.className = "scene-media";
    const { frame, img } = sceneImage(s);
    right.appendChild(frame);

    // Inline Veo-clip player (raw clip, no voice/music — those join at
    // assembly). Toggle so the page stays light with 25+ scenes.
    if (s.hasClip) {
      const clipBtn = document.createElement("button");
      clipBtn.className = "btn sm";
      clipBtn.textContent = "▶ Veo-clip";
      clipBtn.title = "Bekijk de ruwe Veo-clip van deze scène (zonder voice/muziek — die komen er bij montage bij).";
      let vid = null;
      clipBtn.addEventListener("click", () => {
        if (vid) { vid.remove(); vid = null; clipBtn.textContent = "▶ Veo-clip"; return; }
        vid = document.createElement("video");
        vid.controls = true;
        vid.autoplay = true;
        vid.preload = "metadata";
        vid.style.cssText = "width:100%;margin-top:6px;border-radius:8px";
        vid.src = `/dashboard/${encodeURIComponent(id)}/scene/${seq}/clip.mp4?t=` + Date.now();
        vid.addEventListener("play", () => {
          document.querySelectorAll("video").forEach(v => { if (v !== vid) v.pause(); });
        });
        vid.addEventListener("error", () => {
          const msg = document.createElement("div");
          msg.className = "sub small";
          msg.textContent = "clip niet gevonden";
          vid.replaceWith(msg);
          vid = null;
          clipBtn.textContent = "▶ Veo-clip";
        });
        right.appendChild(vid);
        clipBtn.textContent = "✕ clip sluiten";
      });
      right.appendChild(clipBtn);
    }

    const acts = document.createElement("div");
    acts.className = "scene-acts";
    const P = (path, body) =>
      api.post(`/api/v1/videos/${id}/scenes/${seq}/${path}`, body, { key: `${path}-${seq}` });

    acts.appendChild(sceneItem("↻ Regen",
      "Genereert het STARTBEELD van deze scène opnieuw uit de oorspronkelijke scripttekst. Goedkoop (alleen een beeld). De video zelf verandert pas na een re-roll/hermontage.",
      async () => { await P("regenerate"); loadScenes(); }));
    acts.appendChild(sceneItem("✎ Edit",
      "Pas de omschrijving van deze scène aan en genereer het startbeeld daaruit opnieuw. Alleen het beeld — de video verandert pas na een re-roll/hermontage.",
      () => {
        // Inline editor in the scene's text column — no prompt box.
        const editor = document.createElement("div");
        editor.className = "scene-edit";
        const ta = document.createElement("textarea");
        ta.rows = 5;
        ta.value = s.visualDesc || "";
        editor.appendChild(ta);
        const row = document.createElement("div");
        row.className = "scene-acts";
        const save = sceneBtn("Save + regen", "Sla de nieuwe omschrijving op en genereer het beeld opnieuw",
          async () => {
            const vd = ta.value.trim();
            if (!vd) return;
            await P("edit", { visualDesc: vd });
            loadScenes();
          });
        save.classList.add("approve");
        row.appendChild(save);
        row.appendChild(sceneBtn("Cancel", "Wijzigingen verwerpen", () => loadScenes()));
        editor.appendChild(row);
        left.replaceChildren(editor);
        ta.focus();
      }));
    acts.appendChild(sceneItem(
      s.hasEndStill ? "↻ Eindbeeld" : "＋ Eindbeeld",
      s.hasEndStill
        ? "Maakt het eindbeeld (Start → Eind) van deze hero-scène opnieuw aan. Alleen een beeld, geen Veo-kosten."
        : "Genereert een EINDBEELD zodat Veo van start → eind beweegt (geregisseerde beweging). Alleen een extra beeld, geen Veo-kosten.",
      async () => { await P("end-still"); loadScenes(); }));
    acts.appendChild(sceneItem("🔊 Re-voice",
      "Pas de dialoog aan en laat ALLEEN deze scène opnieuw inspreken (ElevenLabs). Beeld blijft ongewijzigd.",
      async () => {
        const dlg = prompt(`New dialogue for scene ${seq} (e.g. "pip: Hi!\\nmo: Look..."):`);
        if (dlg == null || dlg.trim() === "") return;
        await P("edit-dialogue", { dialogue: dlg.trim() });
      }));
    acts.appendChild(sceneItem(s.locked ? "🔓 Unlock" : "🔒 Lock",
      s.locked
        ? "Ontgrendel deze scène zodat QC / auto-fix 'm weer mag aanpassen."
        : "Vergrendel deze scène zodat QC / auto-fix 'm met rust laat.",
      async () => { await P(s.locked ? "unlock" : "lock"); loadScenes(); }));
    {
      // Per-scène motion-model (dropdown) + clip maken/vernieuwen. Toont nu
      // óók bij scènes ZONDER clip — zo upgrade je een Ken Burns-fallback
      // (bijv. na een cost-cap-afkapping) alsnog naar een echte clip.
      const modelSel = document.createElement("select");
      modelSel.className = "scene-model-sel";
      modelSel.title = "Veo-model voor deze re-roll";
      [["", "Model: Veo Fast (720p, ~€0,10/s)"],
       ["veo3_1_lite", "Model: Veo Lite (720p, ~€0,05/s)"],
       ["veo3_1", "Model: Veo Premium (1080p, ~€0,40/s)"],
       ["seedance2_fast", "Model: Seedance Fast (fal.ai, ~€0,10/s)"],
       ["seedance2", "Model: Seedance 2.0 (fal.ai, 1080p, ~€0,25/s)"]].forEach(([v, l]) => {
        const o = document.createElement("option");
        o.value = v; o.textContent = l;
        modelSel.appendChild(o);
      });
      acts.appendChild(modelSel);

      acts.appendChild(sceneItem(s.hasClip ? "🎬 Reroll clip" : "🎬 Maak clip",
        s.hasClip
          ? "Maakt ALLEEN de clip van deze scène opnieuw (≈1 clip-kost) vanaf het HUIDIGE startbeeld, en hermonteert. Kies links het model — Veo (Lite/Fast/Premium) of Seedance 2.0 via fal.ai. Ideaal om providers per scène te A/B'en."
          : "Deze scène heeft nog GEEN clip (Ken Burns-fallback, bijv. door de cost-cap). Genereert er alsnog één (≈1 clip-kost, ~€0,60 op Fast) vanaf het huidige startbeeld en hermonteert automatisch.",
        async () => {
          const m = modelSel.value;
          await api.post(
            `/api/v1/videos/${id}/scenes/${seq}/reroll-veo${m ? "?model=" + encodeURIComponent(m) : ""}`,
            undefined, { key: `reroll-veo-${seq}` });
          loadScenes();
        }));
      acts.appendChild(sceneItem("🆕 Nieuw beeld + clip",
        "Genereert een NIEUW startbeeld voor deze scène ÉN maakt daarvan een nieuwe Veo-clip (≈1 Veo-kost, model via de dropdown), en hermonteert de video (alle andere scènes blijven hergebruikt). Gebruik dit als het PLAATJE zelf matig is.",
        async () => {
          const m = modelSel.value;
          if (!confirm(
            "Nieuw startbeeld + nieuwe Veo-clip voor deze scène?\n\n" +
            "Model: " + (m === "veo3_1" ? "Premium 1080p (~€0,40/s)" : "Fast (720p)") + ".\n" +
            "Genereert een vers beeld, maakt daar een nieuwe Veo-clip van en hermonteert de video. " +
            "Alle andere scènes blijven ongewijzigd.\n\nDoorgaan?")) return;
          await P("regen-clip", { model: m });
          loadScenes();
          toast("Nieuw beeld gemaakt; de Veo-clip wordt opnieuw gerenderd + de video hermonteerd (even geduld).", "info");
        }, "approve"));
    }
    right.appendChild(acts);

    row.appendChild(left);
    row.appendChild(right);
    list.appendChild(row);
  }
  scenesHost.replaceChildren(list);
}

async function loadScenes() {
  try {
    const scenes = await api.get(`/api/v1/videos/${id}/scenes`, { key: "scenes-" + id });
    renderScenes(scenes);
  } catch (e) {
    if (e.name === "AbortError") return;
    scenesHost.textContent = "could not load scenes (see toast)";
  }
}

async function load() {
  if (!id) {
    statusLine.textContent = "no job id in URL";
    return;
  }
  try {
    const job = await api.get(`/api/v1/videos/${id}`, { key: "job-" + id });
    renderJob(job);
    statusLine.textContent = `updated ${new Date().toLocaleTimeString()}`;
  } catch (e) {
    if (e.name === "AbortError") return;
    statusLine.textContent = "could not load job (see toast)";
  }
  loadScenes();
  loadReview();
  loadCastStrip();
}

load();
// Light auto-refresh so status changes (e.g. a stage finishing) show up.
setInterval(load, 5000);
// 1s-ticker: animeer de voortgangsbalk tussen de refreshes door, zodat hij
// zichtbaar blijft bewegen binnen een lange fase (Veo kan minuten per scène
// duren). De 5s-load hierboven levert de verse status + step-tekst.
setInterval(updateProgress, 1000);

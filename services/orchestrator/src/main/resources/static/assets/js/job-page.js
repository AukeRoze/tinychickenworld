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
import api, { toast, esc } from "/assets/js/api.js";

const id = new URLSearchParams(location.search).get("id");
const jobHost = document.getElementById("job-host");
const actionsHost = document.getElementById("actions-host");
const statusLine = document.getElementById("status-line");
const topicEl = document.getElementById("job-topic");
const scenesHost = document.getElementById("scenes-host");
const montageHost = document.getElementById("montage-host");
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
const PHASES = ["Script", "Scenes", "Montage", "Assembly",
                "Thumbnail", "Planning", "Upload", "Distribution"];
const PHASE_OF = {
  PENDING: [0, "queued"], SCRIPT_GENERATING: [0, "active"], SCRIPT_REVIEW_PENDING: [0, "review"],
  ASSETS_GENERATING: [1, "active"], IMAGES_REVIEW_PENDING: [1, "review"], ASSETS_REVIEW_PENDING: [1, "review"],
  // Veo wordt niet gebruikt (clips komen uit Google Flow/Omni); mocht een
  // Veo-status toch voorkomen, dan valt 'ie onder de Montage-stap.
  VEO_GENERATING: [2, "active"], VEO_REVIEW_PENDING: [2, "review"],
  MONTAGE_REVIEW_PENDING: [2, "review"], ASSEMBLING: [3, "active"],
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
  MONTAGE_REVIEW_PENDING:   [74, 74,    0, "⏸ Montage — volgorde, knippen/trimmen, overgangen en achtergrondmuziek"],
  ASSEMBLING:               [75, 88,  300, "Assembleren: intro/outro, audio-mix, gates en audit…"],
  THUMBNAIL_REVIEW_PENDING: [88, 88,    0, "⏸ Kies en keur de thumbnail"],
  UPLOAD_REVIEW_PENDING:    [90, 90,    0, "⏸ Klaar voor publicatie — plan in of publiceer direct"],
  UPLOADING:                [90, 98,  240, "Uploaden naar YouTube…"],
  DISTRIBUTION_PENDING:     [98, 98,    0, "⏸ Distributie-opties beschikbaar"],
  COMPLETED:                [100, 100,  0, "✅ Klaar"],
  FAILED:                   [0,   0,    0, "❌ Mislukt — zie de foutmelding; Retry hervat vanaf de gefaalde stap"],
};
let progressSeen = { status: null, since: Date.now() };
let lastJob = null;

// ── Paneel-state die de 5s-re-render van de review-sectie moet overleven ──
// (loadReview herbouwt de kaarten elke poll; zonder dit verdwenen opgehaalde
// community-posts, de expander-stand en de taalkeuze elke 5 seconden.)
let communityPosts = null;   // laatst opgehaalde community-postideeën
let endScreenRecipe = null;  // laatst opgehaalde end-screen-recipe
let endScreenOpen = false;   // expander open/dicht
let lastLangChoice = "";     // gekozen taal in het vertalingen-panel
let costData = null;         // GET /api/v1/videos/{id}/cost (nieuw endpoint)
let costFailed = false;      // oudere build zonder /cost → niet elke 5s opnieuw proberen
let distRows = null;         // GET /api/v1/distribution (YouTube/Facebook-status)
let seriesNames = null;      // id → naam uit GET /api/v1/series (lazy, eenmalig)
let lastReview = null;       // laatst geladen /review-payload (voor de score-bolletjes op de stepper)

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
    // ELKE stap is selecteerbaar (Auke) — ook nog-niet-bereikte stappen: klik
    // opent de bijbehorende sectie en scrollt ernaartoe, zodat je vrij door de
    // stappen kunt navigeren i.p.v. alleen de actuele.
    {
      const sec = i === 2 ? ["step-montage", "montage-host"]
                : i < 2   ? ["step-script", "scenes-host"]
                :           ["step-review", "review-host"];
      step.classList.add("step--clickable");
      step.title = "Open deze stap";
      step.addEventListener("click", () => {
        const det = document.getElementById(sec[0]);
        const el = document.getElementById(sec[1]);
        if (det && det.tagName === "DETAILS") {
          det.style.display = "";   // eventueel verborgen sectie weer tonen
          det.open = true;          // en openklappen
        }
        document.querySelectorAll(".stepper .step--selected")
          .forEach(s => s.classList.remove("step--selected"));
        step.classList.add("step--selected");
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
    // Score-bolletje per stap (Auke 17 jun): klein gekleurd cijfer op de stappen
    // die een score hebben — Script = verhaal-criticus, Assembly = QA-board (0-100).
    // Pas zichtbaar zodra /review die score levert; ontbreekt 'ie, geen bolletje.
    let stepScore = null;
    if (lastReview) {
      if (i === 0 && lastReview.storyScore && lastReview.storyScore.criticScore != null) {
        stepScore = lastReview.storyScore.criticScore;
      } else if (i === 2 && lastReview.montageScore != null) {
        stepScore = lastReview.montageScore;          // Montage-stap (Auke)
      } else if (i === 3 && lastReview.qaBoardScore != null) {
        stepScore = lastReview.qaBoardScore;          // Assembly = QA-board
      }
    }
    if (stepScore != null) {
      const k = stepScore >= 80 ? "success" : stepScore >= 60 ? "warning" : "danger";
      const badge = document.createElement("span");
      badge.textContent = stepScore;
      badge.title = "Score voor deze stap: " + stepScore + "/100";
      badge.style.cssText =
        "display:inline-block;min-width:18px;height:18px;line-height:18px;border-radius:9px;" +
        "padding:0 4px;margin-top:3px;font-size:10px;font-weight:700;color:#fff;text-align:center;" +
        "background:var(--" + k + ",#888)";
      step.appendChild(badge);
    }
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

// Kosten-blok verwijderd (Auke): geen euro-balk meer onder de stappen.
function renderCost(_cost) {
  if (costHost) costHost.replaceChildren();
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
      // 🤖 Squint-test advies: de AI heeft de varianten al gerankt op
      // telefoonformaat-leesbaarheid; de winnaar is voorgeselecteerd als
      // default. Jij beslist — dit toont alleen WAAROM die voorstaat.
      const best = data.metrics && data.metrics.thumbnailBestVariant;
      if (best != null) {
        const advice = document.createElement("p");
        advice.className = "small";
        advice.style.color = "#b8860b";
        advice.textContent = `🤖 AI-advies (squint-test): variant ${best}` +
            (data.metrics.thumbnailScores ? ` — scores ${data.metrics.thumbnailScores}` : "") +
            " · is al voorgeselecteerd; klik een andere om te overrulen.";
        card.insertBefore(advice, grid);
      }
      for (const tv of variants) {
        const img = document.createElement("img");
        img.src = `/dashboard/${encodeURIComponent(id)}/thumbnail/${tv}.png?t=` + Date.now();
        img.alt = `Thumbnail ${tv}`;
        img.title = `Kies thumbnail ${tv}`;
        img.style.cursor = "pointer";
        if (best != null && tv === best) {
          img.style.cssText += "outline:3px solid #d4a017;outline-offset:2px;border-radius:6px";
        }
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

  // Montage-gate: korte uitleg dat de montage-controls (volgorde, knippen/trimmen,
  // overgangen en muziek) in het scènes-paneel staan; deze knop bouwt de master.
  if (job.status === "MONTAGE_REVIEW_PENDING") {
    const hint = document.createElement("p");
    hint.className = "sub";
    hint.textContent = "Montage-stap: zet de scènes op volgorde, knip/trim de in/uit-punten, "
        + "kies de overgangen en de achtergrondmuziek in het scènes-paneel hieronder. "
        + "Klaar? Klik ‘Assembleren’ om de master te bouwen.";
    card.appendChild(hint);
  }

  // Script-gate (#4): toon de VERHAAL-score zodat je vóór akkoord ziet hoe sterk
  // het verhaal is (en waar het zwak is) — niet alleen blind approve/reject.
  if (job.status === "SCRIPT_REVIEW_PENDING") {
    const sc = document.createElement("div");
    sc.className = "small";
    sc.style.cssText = "margin:4px 0 8px";
    sc.textContent = "📖 Verhaal-score laden…";
    card.appendChild(sc);
    api.get(`/api/v1/videos/${id}/review`, { key: "gate-story" }).then(data => {
      const s = data && data.storyScore;
      if (!s) { sc.remove(); return; }
      const parts = [];
      if (s.criticScore != null) parts.push("Verhaal-criticus " + s.criticScore + "/100");
      if (s.structureScore != null) parts.push("Structuur " + s.structureScore + "/100");
      if (s.comedy != null) parts.push("Humor " + s.comedy + "/10");
      if (s.emotionalImpact != null) parts.push("Emotie " + s.emotionalImpact + "/10");
      if (s.childPsychology != null) parts.push("Kind-veilig " + s.childPsychology + "/10");
      if (!parts.length) { sc.textContent = "📖 Verhaal-score nog niet berekend."; return; }
      // parts are numeric scores (safe); storyArc is LLM/server text → escape it.
      sc.innerHTML = "📖 <strong>Verhaal-score</strong> — " + esc(parts.join(" · ")) +
        (s.storyArc ? "  ·  arc: " + esc(s.storyArc) : "");
    }).catch(() => sc.remove());
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
        : job.status === "MONTAGE_REVIEW_PENDING"
        ? "🎬 Assembleren"
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
    // 📥 Flow-clips importeren: zet de in Google Flow gemaakte scène-clips
    // (bible/afleveringen/<aflevering>/scene-<nr>.mp4) als de beweging van elke
    // scène en hermonteer met de stemmen eroverheen. Geen Veo, geen kosten.
    items.push(actionItem("📥 Flow-clips importeren", "",
      "Zet je in Google Flow gemaakte clips (bible/afleveringen/<aflevering>/scene-<nr>.mp4) " +
      "als de beweging van elke scène en hermonteert met de stemmen eroverheen. Geen Veo, geen kosten. " +
      "Ontbreekt er een clip, dan meldt 'ie welke scène-nummers nog ontbreken.",
      async () => {
        const ep = (prompt("Welke aflevering? (mapnaam onder bible/afleveringen/)",
            String(job.episodeNumber || 1)) || "").trim();
        if (!ep) throw new Error("cancelled");
        const res = await api.post(
            `/api/v1/videos/${id}/import-clips?episode=${encodeURIComponent(ep)}`,
            undefined, { key: "import-clips" });
        const imported = (res && res.importedSeqs) || [];
        const missing = (res && res.missingSeqs) || [];
        if (missing.length) {
          toast(`${imported.length} clip(s) geïmporteerd. Ontbreekt nog: scène ${missing.join(", ")} `
              + `— upload die en probeer opnieuw.`, "error", 9000);
          throw new Error("missing clips");
        }
        toast(`${imported.length} clip(s) geïmporteerd — hermonteren…`, "info");
        return api.post(`/api/v1/videos/${id}/reassemble`, undefined, { key: "import-reassemble" });
      }));
  }
  // 🔁 Re-render beelden (nieuwe cast): na een character-redesign alle visuals
  // vers genereren op de huidige refs — script en stemmen blijven staan.
  // Alleen zichtbaar in de statussen waar de backend-guard het toestaat
  // (gepauzeerd op een review-gate, of klaar/mislukt — nooit mid-stage).
  const RERENDER_SAFE = new Set([
    "IMAGES_REVIEW_PENDING", "ASSETS_REVIEW_PENDING", "VEO_REVIEW_PENDING",
    "THUMBNAIL_REVIEW_PENDING", "UPLOAD_REVIEW_PENDING", "DISTRIBUTION_PENDING",
    "COMPLETED", "FAILED",
  ]);
  if (RERENDER_SAFE.has(s)) {
    items.push(actionItem("🔁 Re-render beelden (nieuwe cast)", "",
      "Genereert ALLE scène-beelden opnieuw met de huidige character-refs (na een redesign). " +
      "Stemmen en script blijven; de oude episode-canon, thumbnail en scène-locks worden " +
      "gewist zodat de nieuwe beelden op de nieuwe cast ankeren.",
      () => {
        const ok = confirm(
          "Genereert ALLE scène-beelden opnieuw met de huidige character-refs (na een redesign). " +
          "Script en stemmen blijven; beelden + eventuele Veo-clips worden opnieuw gemaakt — " +
          "dat kost echt geld (±€0,02-0,05 per scène-beeld, Veo-clips apart). " +
          "Gelockte scènes gaan óók mee.\n\nDoorgaan?");
        if (!ok) throw new Error("cancelled");
        return api.post(`/api/v1/videos/${id}/rerender-visuals`);
      }));
  }
  // 🎬 Alle scènes → Veo: genereer een echte Veo-clip voor ELKE scène die er nog
  // geen heeft (motionMode wordt veo). Bestaande clips blijven behouden; alleen
  // de ontbrekende worden gemaakt. Zelfde veilige statussen als re-render.
  if (RERENDER_SAFE.has(s)) {
    items.push(actionItem("🎬 Alle scènes → Veo (alles bewegend)", "",
      "Genereert een echte Veo-clip voor ELKE scène die er nog geen heeft, zodat de hele " +
      "aflevering beweegt (motionMode wordt 'veo'). Bestaande clips blijven behouden — alleen " +
      "de ontbrekende scènes worden gegenereerd. Daarna hermonteert de video automatisch. " +
      "Let op: dit kost echt Veo-geld per scène.",
      () => {
        const ok = confirm(
          "Genereert een echte Veo-clip voor ELKE scène die er nog geen heeft " +
          "(~€2-4 per scène; een aflevering van ~25 scènes kan €50-90 kosten). " +
          "Bestaande clips blijven behouden. Daarna hermonteert de video automatisch.\n\nDoorgaan?");
        if (!ok) throw new Error("cancelled");
        return api.post(`/api/v1/videos/${id}/all-clips`);
      }));
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
      // ⧉ Apart tabblad voor de volledige master (geen 5s-poll die 'm afkapt).
      const mNew = document.createElement("a");
      mNew.className = "btn sm";
      mNew.textContent = "⧉ open in nieuw venster";
      mNew.title = "Open de master-video in een apart tabblad — speelt volledig af, geen last van de 5s-refresh.";
      mNew.href = `/dashboard/${encodeURIComponent(id)}/master.mp4`;
      mNew.target = "_blank";
      mNew.rel = "noopener";
      card.appendChild(mNew);
    }
    // Auto-derived vertical Short (hook + meest energieke moment, 9:16) —
    // inline afspeelbaar naast de master, plus downloadknop voor de upload.
    if (media.hasShort) {
      const row = document.createElement("div");
      row.style.cssText = "display:flex;align-items:flex-start;gap:12px;margin:8px 0";
      const sv = document.createElement("video");
      sv.controls = true;
      sv.preload = "metadata";
      sv.style.cssText = "width:160px;aspect-ratio:9/16;border-radius:10px;background:#000";
      sv.src = `/dashboard/${encodeURIComponent(id)}/short.mp4`;
      row.appendChild(sv);
      const col = document.createElement("div");
      const a = document.createElement("a");
      a.className = "btn sm";
      a.href = `/dashboard/${encodeURIComponent(id)}/short.mp4`;
      a.download = "short.mp4";
      a.textContent = "⬇ Download Short";
      a.title = "Automatisch afgeleid uit de hook + het luidste (= spannendste) moment. " +
          "Upload als YouTube Short voor extra ontdekking — voeg #Shorts toe aan de titel.";
      col.appendChild(a);
      const note = document.createElement("p");
      note.className = "small";
      note.style.color = "var(--muted)";
      note.textContent = "📱 9:16, intro overgeslagen, captions ingebrand, tekst-hook in de eerste 3s.";
      col.appendChild(note);
      row.appendChild(col);
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
    // 🤖 Auto-Fix: one round of weak-scene rerolls + the QA-as-hefbomen
    // (thumbnail-regen, sound-/story-advies), then pause for review.
    const fixRow = document.createElement("div");
    fixRow.className = "scene-acts";
    fixRow.style.marginTop = "10px";
    const fixBtn = sceneBtn(
      "🤖 Auto-Fix (1 ronde)",
      "Genereert de gemarkeerde zwakke scènes één keer opnieuw (bij een lage Characters/Animation-as ook de zwakste hero-scènes), regenereert zo nodig de thumbnail (max 1×), noteert geluids-/verhaaladvies, hermonteert en auditeert opnieuw, en pauzeert dan voor je review. Kost beeldgeneratie-credits (begrensd); geen Veo, geen auto-upload.",
      async () => {
        const ok = confirm(
          "Auto-Fix genereert de zwakke scènes ÉÉN keer opnieuw (kost beeldgeneratie-credits, " +
          "begrensd door een cap), pakt ook zakkende QA-assen aan (thumbnail-regeneratie max 1×, " +
          "geluids-/verhaaladvies als notitie), hermonteert en auditeert opnieuw, en pauzeert dan " +
          "voor je review.\n\n" +
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

  // ── Cost line under the stepper (compact) ──
  renderCost(data.cost);

  // ── 💶 Kosten-paneel (backlog P2): schatting + cap + werkelijke Veo-kosten ──
  // Bron: het nieuwe read-only /cost-endpoint (met breakdown); valt op een
  // oudere build terug op review.cost (alleen schatting + cap). Toont NIETS
  // dat de backend niet echt meet.
  {
    const c = ctx.cost || null;
    const fb = data.cost || null;
    if (false) {   // kosten-blok verwijderd (Auke) — geen Kosten-kaart meer
      any = true;
      const card = reviewCard("💶 Kosten");
      const est = c && c.estimateEur != null ? c.estimateEur : (fb ? fb.estimateEur : null);
      const cap = c && c.capEur != null ? c.capEur : (fb ? fb.capEur : null);
      if (est != null && cap != null) {
        const pct = cap ? (est / cap) * 100 : 0;
        const kind = pct > 90 ? "danger" : pct > 70 ? "warning" : "success";
        const line = document.createElement("div");
        line.className = "cost-line";
        const badge = document.createElement("span");
        badge.className = "audit-score";
        badge.style.background = "var(--" + kind + ")";
        badge.textContent = "€" + Number(est).toFixed(2) + " geschat";
        line.appendChild(badge);
        const capEl = document.createElement("span");
        capEl.className = "small";
        capEl.style.color = "var(--muted)";
        capEl.textContent = "van €" + Number(cap).toFixed(2) + " per-video cap";
        line.appendChild(capEl);
        line.appendChild(bar(est, cap || 1, kind));
        card.appendChild(line);
      }
      if (c && Array.isArray(c.breakdown) && c.breakdown.length) {
        const ul = document.createElement("ul");
        ul.className = "small";
        ul.style.cssText = "margin:4px 0 8px;padding-left:18px;color:var(--muted)";
        for (const note of c.breakdown) {
          const li = document.createElement("li");
          li.textContent = note;
          ul.appendChild(li);
        }
        card.appendChild(ul);
      }
      const act = c && c.actual;
      if (act && act.veoCostEur != null) {
        const p = document.createElement("p");
        p.className = "small mono";
        p.textContent = `Werkelijk (Veo): €${Number(act.veoCostEur).toFixed(2)}` +
            (act.veoTotal != null ? ` · ${act.veoOk != null ? act.veoOk : "?"}/${act.veoTotal} clips gelukt` : "");
        card.appendChild(p);
      } else {
        const p = document.createElement("p");
        p.className = "sub small";
        p.textContent = "Nog geen werkelijke kostendata — die verschijnt zodra de Veo-render " +
            "gedraaid heeft. Voice/script-tokens worden niet per job bijgehouden; dit paneel " +
            "toont alleen wat de backend echt meet.";
        card.appendChild(p);
      }
      wrap.appendChild(card);
    }
  }

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

    // Eén opslag-route voor de metadata (dubbelklik-titel-edit én het
    // Edit-formulier): nieuwe gevalideerde POST, met terugval op de legacy PATCH.
    const saveMeta = async (body) => {
      let res;
      try {
        res = await api.post(`/api/v1/videos/${id}/metadata`, body, { key: "meta-save-" + id });
      } catch (e) {
        if (e.name === "AbortError" || !/HTTP (404|405)/.test(e.message || "")) throw e;
        res = null;
        await api.patch(`/api/v1/videos/${id}/metadata`, body, { key: "meta-save-" + id });
      }
      meta.title = res && res.title != null ? res.title : body.title;
      meta.description = res && res.description != null ? res.description : body.description;
      meta.tags = res && res.tags != null ? res.tags : body.tags;
      return res;
    };

    // Bewerken kan alleen vóór de upload — daarna staat de metadata op YouTube
    // en zou een lokale edit stilletjes afwijken (de backend geeft dan 409).
    const metaFrozen = !!(lastJob && (lastJob.status === "UPLOADING" ||
        lastJob.status === "COMPLETED" || lastJob.youtubeVideoId));

    const showView = () => {
      const body = document.createElement("div");
      const dl = document.createElement("dl");
      dl.className = "kv";
      // Title — dubbelklik om inline te hernoemen (alleen vóór de upload).
      const tdt = document.createElement("dt");
      tdt.textContent = "Title";
      const tdd = document.createElement("dd");
      tdd.textContent = meta.title || "";
      if (!metaFrozen) {
        tdd.title = "Dubbelklik om de titel te bewerken";
        tdd.style.cursor = "text";
        tdd.ondblclick = () => {
          const inp = document.createElement("input");
          inp.type = "text";
          inp.value = meta.title || "";
          inp.maxLength = 100;
          inp.style.cssText = "width:100%;padding:6px 8px;border:1px solid var(--border,#ccc);" +
              "border-radius:6px;background:var(--bg,#fff);color:inherit;font:inherit";
          tdd.replaceChildren(inp);
          inp.focus();
          inp.select();
          let done = false;
          const commit = async () => {
            if (done) return;
            done = true;
            const v = inp.value.trim();
            if (!v || v === (meta.title || "")) { showView(); return; }
            if (v.length > 100) {
              toast("Titel langer dan 100 tekens — YouTube weigert dat. Kort 'm in.", "error", 6000);
              showView();
              return;
            }
            try {
              const res = await saveMeta({ title: v, description: meta.description, tags: meta.tags });
              toast("Titel opgeslagen" + (res && res.title ? ` — “${res.title}”` : ` — “${v}”`), "info");
            } catch (e) { /* api.js toasted */ }
            showView();
          };
          inp.addEventListener("keydown", (e) => {
            if (e.key === "Enter") { e.preventDefault(); commit(); }
            else if (e.key === "Escape") { done = true; showView(); }
          });
          inp.addEventListener("blur", commit);
        };
      }
      dl.appendChild(tdt);
      dl.appendChild(tdd);
      field(dl, "Description", meta.description);
      field(dl, "Tags", meta.tags);
      body.appendChild(dl);
      if (metaFrozen) {
        const note = document.createElement("p");
        note.className = "sub small";
        note.textContent = "🔒 Metadata staat vast — de video is (bijna) op YouTube; pas het daar aan via YouTube Studio.";
        body.appendChild(note);
      } else {
        const row = document.createElement("div");
        row.className = "filter-row";
        row.appendChild(sceneBtn("✎ Edit", "Edit title / description / tags inline", showEdit));
        row.appendChild(sceneBtn("🔄 Regenereer",
          "Genereert titel/omschrijving/tags opnieuw uit het script (zelfde LLM + brand-gate als de pipeline). Overschrijft de huidige metadata.",
          async () => {
            if (!confirm("Metadata opnieuw genereren uit het script?\n\nDe huidige titel, omschrijving en tags worden overschreven.")) return;
            const res = await api.post(`/api/v1/videos/${id}/metadata/regenerate`,
                undefined, { key: "meta-regen-" + id });
            if (res) {
              meta.title = res.title || "";
              meta.description = res.description || "";
              meta.tags = res.tags || "";
            }
            toast("Metadata geregenereerd" + (res && res.title ? ` — “${res.title}”` : ""), "info", 6000);
            showView();
          }));
        body.appendChild(row);
      }
      replaceBody(body);
    };

    const showEdit = () => {
      const form = document.createElement("div");
      form.className = "meta-edit";
      const t = labeledInput("Title (max 100 tekens)", meta.title, false);
      const d = labeledInput("Description", meta.description, true);
      const g = labeledInput("Tags (comma-separated)", meta.tags, false);
      form.appendChild(t.wrap);
      form.appendChild(d.wrap);
      form.appendChild(g.wrap);
      const row = document.createElement("div");
      row.className = "filter-row";
      const save = sceneBtn("Save", "Save metadata (gevalideerd tegen de YouTube-limieten)", async () => {
        if (t.input.value.trim().length > 100) {
          toast("Titel is langer dan 100 tekens — YouTube weigert dat. Kort 'm in.", "error", 7000);
          return;
        }
        // Nieuwe gevalideerde route (POST); oudere backend zonder POST → val
        // terug op de legacy PATCH zodat opslaan blijft werken.
        const body = { title: t.input.value, description: d.input.value, tags: g.input.value };
        let res;
        try {
          res = await api.post(`/api/v1/videos/${id}/metadata`, body, { key: "meta-save-" + id });
        } catch (e) {
          if (e.name === "AbortError" || !/HTTP (404|405)/.test(e.message || "")) throw e;
          res = null;
          await api.patch(`/api/v1/videos/${id}/metadata`, body, { key: "meta-save-" + id });
        }
        meta.title = res && res.title != null ? res.title : body.title;
        meta.description = res && res.description != null ? res.description : body.description;
        meta.tags = res && res.tags != null ? res.tags : body.tags;
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

  // ── Planning — alleen zichtbaar vanaf de planning-stap (Auke): de gate
  // UPLOAD_REVIEW_PENDING en alles daarna (uploaden/distributie/klaar). Daarvoor
  // niet tonen zodat de review-sectie niet vol staat met nog-niet-relevante info.
  const pl = data.planning || {};
  const planningStep = !!(lastJob && (
      lastJob.status === "UPLOAD_REVIEW_PENDING" ||
      lastJob.status === "UPLOADING" ||
      lastJob.status === "DISTRIBUTION_PENDING" ||
      lastJob.status === "COMPLETED"));
  if (planningStep) {
    any = true;
    const card = reviewCard("Planning");
    const dl = document.createElement("dl");
    dl.className = "kv";
    field(dl, "Series", pl.seriesId);

    const epDt = document.createElement("dt");
    epDt.textContent = "Episode";
    const epDd = document.createElement("dd");
    const epSel = document.createElement("select");
    epSel.className = "btn sm";
    epSel.title = "Stel het afleveringnummer in";
    const ph = document.createElement("option");
    ph.value = "";
    ph.textContent = "Afl. —";
    epSel.appendChild(ph);
    for (let n = 1; n <= 20; n++) {
      const o = document.createElement("option");
      o.value = String(n);
      o.textContent = "Afl. " + n;
      if (pl.episodeNumber === n) o.selected = true;
      epSel.appendChild(o);
    }
    epSel.addEventListener("change", async () => {
      if (!epSel.value) return;
      epSel.disabled = true;
      try {
        await api.post(`/api/v1/videos/${id}/episode`,
            { episodeNumber: Number(epSel.value) }, { key: "ep-detail" });
        toast("Aflevering " + epSel.value + " ingesteld ✓", "info");
        loadReview();
      } catch (e) { epSel.disabled = false; }
    });
    epDd.appendChild(epSel);
    dl.appendChild(epDt);
    dl.appendChild(epDd);

    field(dl, "Planned publish", pl.plannedPublishAt);
    field(dl, "YouTube", pl.youtubeUrl, { link: true });
    card.appendChild(dl);
    wrap.appendChild(card);
  }

  // Distributie-gate (taak P2): pas zichtbaar zodra de master geüpload is —
  // status DISTRIBUTION_PENDING of COMPLETED.
  const distReady = !!(lastJob &&
      (lastJob.status === "DISTRIBUTION_PENDING" || lastJob.status === "COMPLETED"));

  // ── 🌍 Vertalingen (backlog P2) — per taal: status + vertaal-actie ──
  if (uploaded || distReady) {
    any = true;
    const card = reviewCard("🌍 Vertalingen");
    const locs = Array.isArray(ctx.localizations) ? ctx.localizations : [];
    if (locs.length) {
      const list = document.createElement("div");
      for (const l of locs) {
        const row = document.createElement("div");
        row.style.cssText = "display:flex;align-items:center;gap:8px;margin:3px 0;flex-wrap:wrap";
        const name = document.createElement("span");
        name.style.cssText = "min-width:120px";
        name.textContent = l.name || l.language || "?";
        row.appendChild(name);
        const st = (l.status || "").toUpperCase();
        const pill = document.createElement("span");
        pill.className = "pill pill--" +
            (st === "TRANSLATED" || st === "UPLOADED" ? "success"
             : st === "FAILED" ? "danger" : "muted");
        pill.textContent = l.status || "—";
        row.appendChild(pill);
        if (l.youtubeVideoId) {
          const a = document.createElement("a");
          a.className = "small";
          a.href = "https://www.youtube.com/watch?v=" + encodeURIComponent(l.youtubeVideoId);
          a.target = "_blank";
          a.rel = "noopener";
          a.textContent = "YouTube ↗";
          row.appendChild(a);
        }
        if (l.error) {
          const err = document.createElement("span");
          err.className = "small";
          err.style.color = "var(--danger,#b91c1c)";
          err.textContent = String(l.error).slice(0, 120);
          err.title = l.error;
          row.appendChild(err);
        }
        list.appendChild(row);
      }
      card.appendChild(list);
    } else {
      const p = document.createElement("p");
      p.className = "sub small";
      p.textContent = "Nog geen vertalingen — kies hieronder een taal en klik Vertaal.";
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
      if (lastLangChoice && langs.includes(lastLangChoice)) sel.value = lastLangChoice;
      sel.addEventListener("change", () => { lastLangChoice = sel.value; });
      row.appendChild(sel);
      row.appendChild(sceneBtn("🌍 Vertaal",
        "Vertaalt het script (en de metadata, als die er al is) naar de gekozen taal en bewaart het resultaat per taal. Bestaat de vertaling al, dan wordt ze ververst.",
        async () => {
          const res = await api.post(
            `/api/v1/videos/${id}/localize/${encodeURIComponent(sel.value)}`,
            undefined, { key: "localize" });
          toast(`Vertaling ${sel.value} klaar ✓` +
              (res && res.title ? ` — “${res.title}”` : ""), "info", 6000);
          loadReview();
        }));
      card.appendChild(row);
    } else {
      const p = document.createElement("p");
      p.className = "sub small";
      p.textContent = "Talenlijst niet beschikbaar (languages-endpoint niet bereikbaar) — vertalingen blijven wel zichtbaar.";
      card.appendChild(p);
    }
    wrap.appendChild(card);
  }

  // ── 📣 Distributie (backlog P2) — per platform: status + push-knop ──
  if (distReady) {
    any = true;
    wrap.appendChild(distributionCard(uploaded, pl && pl.seriesId ? pl.seriesId : null));
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

/** Statuschip voor het distributie-panel. on=true → groen vinkje.
 *  Optionele href (bijv. de Instagram-permalink, V25) maakt de chip een
 *  klikbare link naar de live post. */
function distChip(on, label, title, href) {
  const s = document.createElement(href ? "a" : "span");
  s.className = "pill pill--" + (on ? "success" : "muted");
  s.textContent = label + (on ? " ✓" : " —");
  if (title) s.title = title;
  if (href) {
    s.href = href;
    s.target = "_blank";
    s.rel = "noopener noreferrer";
  }
  return s;
}

/** Community-postideeën (copy-paste — de YouTube API kan ze niet plaatsen). */
function renderCommunityPosts(host) {
  host.replaceChildren();
  host.className = "";
  const data = communityPosts;
  if (!data) return;
  const posts = Array.isArray(data.posts) ? data.posts : [];
  if (!posts.length) {
    host.textContent = "Geen postideeën ontvangen (upload-service niet bereikbaar?).";
    host.className = "sub small";
    return;
  }
  for (const p of posts) {
    const box = document.createElement("div");
    box.style.cssText = "border:1px solid var(--border,#ddd);border-radius:8px;padding:8px 10px;margin:6px 0";
    const pre = document.createElement("pre");
    pre.style.cssText = "white-space:pre-wrap;margin:0 0 6px;font:inherit";
    pre.textContent = p.body || "";
    box.appendChild(pre);
    const foot = document.createElement("div");
    foot.style.cssText = "display:flex;align-items:center;gap:8px;flex-wrap:wrap";
    const copy = document.createElement("button");
    copy.className = "btn sm";
    copy.textContent = "📋 Kopieer";
    copy.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(p.body || "");
        toast("Post gekopieerd — plak in YouTube Studio → Community → Nieuw bericht", "info");
      } catch (e) {
        toast("Kopiëren mislukt — selecteer de tekst handmatig", "error");
      }
    });
    foot.appendChild(copy);
    if (p.scheduleHint) {
      const hint = document.createElement("span");
      hint.className = "small";
      hint.style.color = "var(--muted)";
      hint.textContent = p.scheduleHint;
      foot.appendChild(hint);
    }
    box.appendChild(foot);
    host.appendChild(box);
  }
  if (data.cadenceTip) {
    const tip = document.createElement("p");
    tip.className = "sub small";
    tip.textContent = "💡 " + data.cadenceTip;
    host.appendChild(tip);
  }
}

/** End-screen-recept: gestructureerd als de elements-lijst er is, anders JSON. */
function renderEndScreen(host) {
  host.replaceChildren();
  const r = endScreenRecipe;
  if (!r) return;
  if (Array.isArray(r.elements) && r.elements.length) {
    const ul = document.createElement("ul");
    ul.className = "small";
    ul.style.cssText = "margin:6px 0;padding-left:18px";
    for (const el of r.elements) {
      const li = document.createElement("li");
      const bits = [];
      if (el.type) bits.push(el.type);
      if (el.position) bits.push("positie: " + el.position);
      if (el.startSecondsBeforeEnd != null) bits.push(el.startSecondsBeforeEnd + "s voor het einde");
      li.textContent = bits.join(" · ") || JSON.stringify(el);
      ul.appendChild(li);
    }
    host.appendChild(ul);
    for (const [k, v] of Object.entries(r)) {
      if (k === "elements" || typeof v !== "string") continue;
      const p = document.createElement("p");
      p.className = "sub small";
      p.textContent = v;
      host.appendChild(p);
    }
  } else {
    const pre = document.createElement("pre");
    pre.className = "small mono";
    pre.style.cssText = "white-space:pre-wrap";
    pre.textContent = JSON.stringify(r, null, 2);
    host.appendChild(pre);
  }
  const note = document.createElement("p");
  note.className = "sub small";
  note.textContent = "Eenmalig instellen in YouTube Studio → Editor → End screen; YouTube onthoudt het voor volgende uploads.";
  host.appendChild(note);
}

/**
 * 📣 Distributie-kaart: per platform een statuschip + push-knop op de
 * bestaande MultiPlatform-endpoints (via de orchestrator-proxy
 * POST /api/v1/videos/{id}/distribute/{platform}). Status is eerlijk én
 * persistent: YouTube, Facebook, TikTok (publish-id) en Instagram (media-id)
 * worden na een geslaagde push op de job bewaard (V23) en komen mee in
 * GET /api/v1/videos/{id}; oudere backends zonder die velden vallen terug
 * op het distributie-overzicht.
 */
function distributionCard(uploaded, seriesId) {
  const card = reviewCard("📣 Distributie");
  const sub = document.createElement("p");
  sub.className = "sub small";
  sub.textContent = "Eén master → meerdere platformen. Een push uploadt de bestaande video — geen nieuwe render. Platforms zonder token geven '503 not configured'.";
  card.appendChild(sub);

  // Serie-playlist: na de upload voegt de pipeline de video automatisch toe
  // aan de playlist van de serie (Shorts niet). Naam lazy via /api/v1/series;
  // tot die er is (of bij een fout) tonen we gewoon de seriesId.
  if (seriesId) {
    const plLine = document.createElement("p");
    plLine.className = "sub small";
    plLine.title = "Automatisch toegevoegd na de YouTube-upload (verticale Shorts niet).";
    const setText = () => {
      plLine.textContent = "📃 In playlist: " + ((seriesNames && seriesNames[seriesId]) || seriesId);
    };
    setText();
    if (!seriesNames) {
      api.get("/api/v1/series", { key: "series-names" }).then((arr) => {
        seriesNames = {};
        for (const s of (Array.isArray(arr) ? arr : [])) seriesNames[s.id] = s.name || s.id;
        setText();
      }).catch(() => {});
    }
    card.appendChild(plLine);
  }

  // Statuschips.
  const row = Array.isArray(distRows) ? distRows.find((r) => r.id === id) : null;
  const chips = document.createElement("div");
  chips.className = "filter-row";
  chips.appendChild(distChip(uploaded, "YouTube",
      uploaded ? "Live op YouTube" : "Nog niet op YouTube"));
  const fbOn = !!((lastJob && (lastJob.facebookVideoId || lastJob.facebookUrl)) || (row && row.facebook));
  chips.appendChild(distChip(fbOn, "Facebook",
      fbOn ? "Gepost op de Facebook-pagina" : "Nog niet op Facebook"));
  // TikTok/Instagram: de push-respons (publish-id / media-id) wordt sinds V23
  // op de job bewaard — echte status in plaats van het oude eerlijke "?".
  // Op een oudere backend (veld ontbreekt in de job-respons én in het
  // overzicht) blijft het chipje grijs met een eerlijke uitleg.
  const tracked = lastJob && ("tiktokPublishId" in lastJob || "instagramMediaId" in lastJob);
  const tkOn = !!((lastJob && lastJob.tiktokPublishId) || (row && row.tiktok));
  const igOn = !!((lastJob && lastJob.instagramMediaId) || (row && row.instagram));
  const UNKNOWN = "Status onbekend — deze backend bewaart de pushstatus nog niet op de job.";
  chips.appendChild(distChip(tkOn, "TikTok",
      tkOn ? "Gepusht naar TikTok (publish-id: " + (lastJob && lastJob.tiktokPublishId || "?") + ")"
           : tracked ? "Nog niet naar TikTok gepusht" : UNKNOWN));
  // Instagram-permalink (V25): als de backend 'm heeft opgeslagen wordt de
  // chip een link naar de live Reel; zonder permalink blijft het een chip.
  const igUrl = (lastJob && lastJob.instagramUrl) || null;
  chips.appendChild(distChip(igOn, "Instagram",
      igOn ? "Gepubliceerd als Instagram Reel (media-id: " + (lastJob && lastJob.instagramMediaId || "?") + ")"
               + (igUrl ? " — klik om de Reel te openen" : "")
           : tracked ? "Nog niet naar Instagram gepusht" : UNKNOWN,
      igOn ? igUrl : null));
  card.appendChild(chips);

  // Push-knoppen met bevestiging + toast.
  const acts = document.createElement("div");
  acts.className = "filter-row";
  const push = (platform, label, help, confirmMsg) => {
    const b = sceneBtn(label, help, async () => {
      if (!confirm(confirmMsg)) return;
      const res = await api.post(`/api/v1/videos/${id}/distribute/${platform}`,
          undefined, { key: "dist-" + platform });
      const r = (res && res.result) || {};
      const ok = r.success !== false;
      toast(ok ? `Gepusht naar ${platform} ✓` + (r.url ? " · " + r.url : "")
               : `${platform}: push niet gelukt — zie de details in de melding`,
            ok ? "info" : "error", 8000);
      loadReview();
    });
    return b;
  };
  acts.appendChild(push("tiktok", "→ TikTok",
      "Upload de master als TikTok-video (vereist TIKTOK_ACCESS_TOKEN op de upload-service).",
      "Video naar TikTok pushen?\n\nDe bestaande master wordt geüpload met de YouTube-titel als caption."));
  const ig = push("instagram", "→ Instagram",
      "Publiceer als Instagram Reel via de publieke YouTube-URL.",
      "Video naar Instagram (Reels) pushen?\n\nGebruikt de publieke YouTube-URL als bron.");
  if (!uploaded) {
    ig.disabled = true;
    ig.title = "Instagram heeft een publieke URL nodig — upload eerst naar YouTube.";
  }
  acts.appendChild(ig);
  acts.appendChild(push("facebook", "→ Facebook",
      "Upload de master naar de Facebook-pagina; de post-ID en URL worden op de job bewaard.",
      "Video naar de Facebook-pagina pushen?\n\nDe bestaande master wordt geüpload met titel + beschrijving."));
  card.appendChild(acts);

  // (Facebook-URL: het overzicht zegt alleen dát hij er staat; de URL staat
  // wel op de job maar zit niet in /review — de push-toast toont 'm direct.)

  // 💬 Community-posts (copy-paste — YouTube's API is Studio-only).
  const ideasHost = document.createElement("div");
  const cpBtn = sceneBtn("💬 Community-postideeën",
    "Genereert copy-paste posts voor het YouTube Community-tabblad. De API kan ze niet plaatsen (Studio-only) — kopieer en plak ze zelf.",
    async () => {
      communityPosts = await api.get(`/api/v1/videos/${id}/distribute/community-posts`,
          { key: "cposts-" + id });
      renderCommunityPosts(ideasHost);
    });
  const cpRow = document.createElement("div");
  cpRow.className = "filter-row";
  cpRow.appendChild(cpBtn);
  card.appendChild(cpRow);
  if (communityPosts) renderCommunityPosts(ideasHost);
  card.appendChild(ideasHost);

  // 📺 End-screen-recept (expander, lazy geladen, stand overleeft de poll).
  const es = document.createElement("details");
  es.open = endScreenOpen;
  const esSum = document.createElement("summary");
  esSum.style.cursor = "pointer";
  esSum.className = "small";
  esSum.textContent = "📺 End-screen-recept (eenmalig instellen in YouTube Studio)";
  es.appendChild(esSum);
  const esBody = document.createElement("div");
  es.appendChild(esBody);
  if (endScreenRecipe) renderEndScreen(esBody);
  es.addEventListener("toggle", async () => {
    endScreenOpen = es.open;
    if (es.open && !endScreenRecipe) {
      try {
        endScreenRecipe = await api.get(
            `/api/v1/videos/${id}/distribute/end-screen-recipe`, { key: "esr-" + id });
        renderEndScreen(esBody);
      } catch (e) {
        esBody.className = "sub small";
        esBody.textContent = "Recept laden mislukte (upload-service niet bereikbaar?).";
      }
    }
  });
  card.appendChild(es);

  return card;
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
  // Kosten: nieuw read-only endpoint met breakdown + werkelijke Veo-kosten.
  // Eén mislukking (bv. oudere build zonder /cost) → niet elke 5s opnieuw
  // proberen (en dus geen toast-spam); de UI valt terug op review.cost.
  if (!costFailed) {
    try {
      costData = await api.get(`/api/v1/videos/${id}/cost`, { key: "cost-" + id });
    } catch (e) {
      if (e.name !== "AbortError") costFailed = true;
    }
  }
  // Distributie-status (YouTube/Facebook per video) — alleen relevant zodra
  // de master er is; best-effort.
  if (lastJob && (lastJob.status === "DISTRIBUTION_PENDING" || lastJob.status === "COMPLETED")) {
    try { distRows = await api.get("/api/v1/distribution", { key: "dist-rows" }); } catch (e) {}
  }
  lastReview = review;            // voedt de score-bolletjes op de stepper
  if (lastJob) renderStepper(lastJob);   // herteken met de verse scores
  renderReview({ review, localizations, languages, cost: costData });
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

/** Toon/verberg een laad-overlay (spinner + tekst) op een scène-beeldframe
 *  tijdens een regen, zodat het niet lijkt of er niks gebeurt. `frame` is het
 *  .scene-img-frame element; `label` de tekst onder de spinner. */
function setSceneBusy(frame, on, label) {
  if (!frame) return;
  const img = frame.querySelector("img");
  let ov = frame.querySelector(".scene-img-busy");
  if (on) {
    if (img) img.classList.add("dimmed");
    if (!ov) {
      ov = document.createElement("div");
      ov.className = "scene-img-busy";
      const sp = document.createElement("div");
      sp.className = "spin";
      const tx = document.createElement("div");
      tx.textContent = label || "Genereren…";
      ov.appendChild(sp);
      ov.appendChild(tx);
      frame.appendChild(ov);
    }
  } else {
    if (img) img.classList.remove("dimmed");
    if (ov) ov.remove();
  }
}

/** A single labelled still (start or end) with a graceful "no image yet" box.
 *  `version` (the still's mtime) is appended as ?v= so a regenerated image
 *  refreshes — and only when it actually changed (poll re-renders stay cached). */
function stillFrame(seq, label, version, hasClip) {
  const frame = document.createElement("div");
  frame.className = "scene-img-frame";
  const ph = document.createElement("div");
  ph.className = "scene-img-ph";
  ph.textContent = hasClip ? "clip laden…" : "no image yet";
  ph.style.display = "none";
  let img;
  if (hasClip) {
    // Frame UIT de geïmporteerde clip (Auke): toon een echt beeld uit het
    // filmpje i.p.v. de AI-gegenereerde still. Een <video> met media-fragment
    // #t=0.5 schildert het frame op ~0,5s als statische poster (geen autoplay,
    // geen JS-seek). preload=metadata houdt het licht voor alle scènes tegelijk.
    img = document.createElement("video");
    img.muted = true;
    img.playsInline = true;
    img.setAttribute("playsinline", "");
    img.preload = "metadata";
    img.alt = (label || "Scene") + " " + seq;
    img.onloadeddata = () => { img.style.display = ""; ph.style.display = "none"; };
    img.onerror = () => { img.style.display = "none"; ph.style.display = "flex"; };
    img.src = `/dashboard/${encodeURIComponent(id)}/scene/${seq}/clip.mp4#t=0.5`;
  } else {
    img = document.createElement("img");
    img.loading = "lazy";
    img.alt = (label || "Scene") + " " + seq;
    img.onload = () => { img.style.display = ""; ph.style.display = "none"; };
    img.onerror = () => { img.style.display = "none"; ph.style.display = "flex"; };
    img.src = `/review/images/${encodeURIComponent(id)}/file/${seq}.png`
            + (version ? `?v=${version}` : "");
  }
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
 * Scene image for the right column — a single start still. The old "Start →
 * Eind" two-up view is removed (2026-06-14): end frames are pipeline-wide off
 * (start→end interpolation caused character morphing), so every scene runs
 * start-only and there is no end still to show.
 * Returns { frame, img } where img is the START still (for cache-busting on regen).
 */
function sceneImage(s) {
  const start = stillFrame(s.seq, null, s.imageVersion, s.hasClip);
  return { frame: start.frame, img: start.img };
}

/** Eénmalige uitleg-box bovenaan de scènelijst: wat doen de per-scène-knoppen,
 *  en met name wat betekent 🔒 Lock. Uitklapbaar (<details>) en de open/dicht-
 *  stand overleeft de poll via localStorage, zodat hij niet steeds terugklapt. */
function sceneActionsHelp() {
  const d = document.createElement("details");
  d.className = "scene-help";
  let open = false;
  try { open = localStorage.getItem("sceneHelpOpen") === "1"; } catch (e) {}
  d.open = open;
  d.addEventListener("toggle", () => {
    try { localStorage.setItem("sceneHelpOpen", d.open ? "1" : "0"); } catch (e) {}
  });
  const sum = document.createElement("summary");
  sum.textContent = "ⓘ Wat doen de knoppen per scène? (en wat is 🔒 Lock?)";
  d.appendChild(sum);

  const body = document.createElement("div");
  body.className = "scene-help-body sub small";
  const rows = [
    ["↻ Regen", "Maakt het startbeeld opnieuw uit de scripttekst. Je kunt een correctie meegeven (bv. “geen tweede kip”). Alleen een beeld — goedkoop."],
    ["✎ Edit", "Pas de scène-omschrijving aan en genereer het beeld daaruit opnieuw."],
    ["🔊 Re-voice", "Pas de dialoog aan en laat alleen deze scène opnieuw inspreken. Beeld blijft."],
    ["🎬 Maak/Reroll clip", "Maakt de Veo-clip van deze scène (kost ±1 clip) en hermonteert. Kies links het model."],
    ["🔒 Lock / 🔓 Unlock", "DIT is de belangrijke: een Lock beschermt een scène tegen de AUTOMATISCHE systemen — de vision-QC en de Auto-Fix-lus laten een gelockte scène met rust en vervangen het beeld niet meer. Handig zodra een scène er precies goed uitziet. Jij kunt 'm met de knoppen hierboven nog wél handmatig aanpassen; de lock is een hek tegen de robot, niet tegen jou. Unlock geeft 'm weer vrij voor de automatische passes."],
  ];
  for (const [k, v] of rows) {
    const p = document.createElement("p");
    p.style.margin = "4px 0";
    const b = document.createElement("b");
    b.textContent = k + " — ";
    p.appendChild(b);
    p.appendChild(document.createTextNode(v));
    body.appendChild(p);
  }
  d.appendChild(body);
  return d;
}

/** Eén scène → leesbaar promptblok. `which`: "desc" (omschrijving + dialoog),
 *  "veo" (gecompileerde Veo-videoprompt), "image" (gecomponeerde image/still-
 *  prompt) of "both" (alles wat beschikbaar is). */
function scenePromptBlock(s, which) {
  const head = `=== Scene ${s.seq}` +
      (s.phase ? ` · ${s.phase}` : "") +
      (s.durationSeconds ? ` · ${s.durationSeconds}s` : "") + " ===";
  const parts = [head];
  if (which === "desc" || which === "both") {
    const desc = s.visualDesc || s.narration || "";
    if (desc) parts.push((which === "both" ? "[Description]\n" : "") + desc);
    const lines = (s.lines || [])
        .map(l => `${l.speaker || "?"}: ${l.text || ""}`).join("\n");
    if (lines) parts.push("[Dialogue]\n" + lines);
  }
  if ((which === "image" || which === "both") && s.imagePrompt) {
    parts.push((which === "both" ? "[AI Image Prompt]\n" : "") + s.imagePrompt);
  }
  if ((which === "veo" || which === "both") && s.veoPrompt) {
    parts.push((which === "both" ? "[AI Video Prompt]\n" : "") + s.veoPrompt);
  }
  return parts.join("\n");
}

/** Alle scènes samengevoegd tot één tekstblok (gescheiden door een lege regel). */
function buildPromptText(scenes, which) {
  return (scenes || []).map(s => scenePromptBlock(s, which)).join("\n\n");
}

/** Kopieer tekst naar het klembord (met fallback voor oudere browsers). */
async function copyText(text, okMsg) {
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text);
    } else {
      const ta = document.createElement("textarea");
      ta.value = text;
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      document.execCommand("copy");
      ta.remove();
    }
    toast(okMsg || "Gekopieerd ✓", "info");
  } catch (e) {
    toast("Kopiëren mislukt — selecteer de tekst en kopieer handmatig.", "error");
  }
}

/** Modal die ALLE scène-prompts toont (omschrijving + volledige Veo-prompt),
 *  met knoppen om alles in één keer te kopiëren. */
function openPromptsModal(scenes) {
  const overlay = document.createElement("div");
  overlay.style.cssText =
      "position:fixed;inset:0;background:rgba(0,0,0,.55);z-index:1000;" +
      "display:flex;align-items:center;justify-content:center;padding:24px";
  const close = () => overlay.remove();
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });

  const box = document.createElement("div");
  box.style.cssText =
      "background:var(--bg,#fff);color:inherit;border-radius:12px;max-width:900px;" +
      "width:100%;max-height:86vh;display:flex;flex-direction:column;overflow:hidden;" +
      "box-shadow:0 12px 48px rgba(0,0,0,.4)";

  const head = document.createElement("div");
  head.style.cssText =
      "display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:14px 16px;" +
      "border-bottom:1px solid var(--border,#e5e5e5)";
  const title = document.createElement("strong");
  title.textContent = `Alle scène-prompts (${scenes.length})`;
  title.style.marginRight = "auto";
  head.appendChild(title);

  const haveVeo = scenes.some(s => s.veoPrompt);
  const haveImage = scenes.some(s => s.imagePrompt);

  // Het tekstgebied dat de modal toont — wisselt met de weergavekeuze.
  const ta = document.createElement("textarea");
  ta.readOnly = true;
  ta.style.cssText =
      "flex:1;width:100%;border:0;resize:none;padding:14px 16px;font:12px/1.5 " +
      "ui-monospace,Menlo,Consolas,monospace;background:transparent;color:inherit;outline:none";

  let mode = (haveVeo || haveImage) ? "both" : "desc";
  const render = () => { ta.value = buildPromptText(scenes, mode); };

  const mkToggle = (label, m, help) => {
    const b = document.createElement("button");
    b.className = "btn sm";
    b.textContent = label;
    if (help) b.title = help;
    b.addEventListener("click", () => {
      mode = m;
      render();
      [...head.querySelectorAll("[data-mode]")].forEach(x =>
          x.classList.toggle("approve", x.dataset.mode === m));
    });
    b.dataset.mode = m;
    if (m === mode) b.classList.add("approve");
    return b;
  };
  head.appendChild(mkToggle("Omschrijvingen", "desc",
      "Toon per scène alleen de korte omschrijving + dialoog."));
  if (haveImage) {
    head.appendChild(mkToggle("Image-prompts", "image",
        "Toon per scène alleen de gecomponeerde image/still-prompt (om in je beeld-tool te plakken)."));
  }
  if (haveVeo) {
    head.appendChild(mkToggle("Veo-prompts", "veo",
        "Toon per scène alleen de volledige gecompileerde Veo-videoprompt."));
  }
  if (haveVeo || haveImage) {
    head.appendChild(mkToggle("Alles", "both",
        "Toon per scène de omschrijving + image-prompt + Veo-videoprompt."));
  }

  const copyBtn = document.createElement("button");
  copyBtn.className = "btn sm approve";
  copyBtn.textContent = "📋 Kopieer alles";
  copyBtn.title = "Kopieert de getoonde prompts (alle scènes) in één keer naar het klembord.";
  copyBtn.addEventListener("click", () =>
      copyText(ta.value, `Alle ${scenes.length} scène-prompts gekopieerd ✓`));
  head.appendChild(copyBtn);

  const x = document.createElement("button");
  x.className = "btn sm";
  x.textContent = "✕";
  x.title = "Sluiten";
  x.addEventListener("click", close);
  head.appendChild(x);

  render();
  box.appendChild(head);
  box.appendChild(ta);

  const foot = document.createElement("div");
  foot.className = "sub small";
  foot.style.cssText = "padding:8px 16px;border-top:1px solid var(--border,#e5e5e5)";
  foot.textContent = haveVeo
      ? "Tip: de Veo-prompt is de volledige gecompileerde prompt (camera, camera-move, " +
        "setting, performance + identity-locks) zoals de pipeline 'm naar Veo stuurt."
      : "Nog geen gecompileerde Veo-prompts beschikbaar (verschijnt zodra de scènes klaar zijn). " +
        "Hieronder de scène-omschrijvingen + dialoog.";
  box.appendChild(foot);

  overlay.appendChild(box);
  document.body.appendChild(overlay);
  // Esc sluit de modal.
  const onKey = (e) => { if (e.key === "Escape") { close(); document.removeEventListener("keydown", onKey); } };
  document.addEventListener("keydown", onKey);
}

/** Eén thumbnail-variant → leesbaar promptblok. `which`: "full" (volledige
 *  OpenAI-prompt), "anchor" (live Gemini-beschrijving) of "both". */
function thumbVariantBlock(v, which) {
  let head = `### Variant ${v.variant} — layout ${v.layout}`;
  head += v.overlayHeadline ? ` — overlaytekst: "${v.overlayHeadline}"` : " — (geen tekst, controle)";
  head += `\nMood: ${v.mood}\nFraming: ${v.framing}`;
  const parts = [];
  if ((which === "full" || which === "both") && v.openAiPrompt) {
    parts.push((which === "both" ? "[Volledige prompt]\n" : "") + v.openAiPrompt);
  }
  if ((which === "anchor" || which === "both") && v.anchorPrompt) {
    parts.push((which === "both" ? "[Live anchor-beschrijving (Gemini, ref-conditioned)]\n" : "") + v.anchorPrompt);
  }
  return head + "\n\n" + parts.join("\n\n");
}

function buildThumbPromptText(data, which) {
  return (data.variants || []).map(v => thumbVariantBlock(v, which)).join("\n\n———\n\n");
}

/** Modal met de thumbnail-prompt(s) per variant — zelfde stijl als de scène-
 *  promptmodal, kopieerbaar in één keer. */
function openThumbnailPromptModal(data) {
  const variants = (data && data.variants) || [];
  const overlay = document.createElement("div");
  overlay.style.cssText =
      "position:fixed;inset:0;background:rgba(0,0,0,.55);z-index:1000;" +
      "display:flex;align-items:center;justify-content:center;padding:24px";
  const close = () => overlay.remove();
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });

  const box = document.createElement("div");
  box.style.cssText =
      "background:var(--bg,#fff);color:inherit;border-radius:12px;max-width:900px;" +
      "width:100%;max-height:86vh;display:flex;flex-direction:column;overflow:hidden;" +
      "box-shadow:0 12px 48px rgba(0,0,0,.4)";

  const head = document.createElement("div");
  head.style.cssText =
      "display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:14px 16px;" +
      "border-bottom:1px solid var(--border,#e5e5e5)";
  const title = document.createElement("strong");
  title.textContent = `Thumbnail-prompt (${variants.length} varianten, ${data && data.castMode ? "cast" : "solo"})`;
  title.style.marginRight = "auto";
  head.appendChild(title);

  const ta = document.createElement("textarea");
  ta.readOnly = true;
  ta.style.cssText =
      "flex:1;width:100%;border:0;resize:none;padding:14px 16px;font:12px/1.5 " +
      "ui-monospace,Menlo,Consolas,monospace;background:transparent;color:inherit;outline:none";

  let mode = "full";
  const render = () => { ta.value = buildThumbPromptText(data, mode); };
  const mkToggle = (label, m, help) => {
    const b = document.createElement("button");
    b.className = "btn sm";
    b.textContent = label;
    if (help) b.title = help;
    b.dataset.mode = m;
    if (m === mode) b.classList.add("approve");
    b.addEventListener("click", () => {
      mode = m;
      render();
      [...head.querySelectorAll("[data-mode]")].forEach(x =>
          x.classList.toggle("approve", x.dataset.mode === m));
    });
    return b;
  };
  head.appendChild(mkToggle("Volledige prompt", "full",
      "De volledige, op zichzelf staande thumbnail-prompt per variant (zoals de OpenAI-fallback 'm voert)."));
  head.appendChild(mkToggle("Live (Gemini)", "anchor",
      "De beschrijving die de live anchor-route naar de image-service stuurt (ref-conditioned cast)."));
  head.appendChild(mkToggle("Alles", "both", "Beide: volledige prompt + live anchor-beschrijving."));

  const copyBtn = document.createElement("button");
  copyBtn.className = "btn sm approve";
  copyBtn.textContent = "📋 Kopieer alles";
  copyBtn.title = "Kopieert de getoonde thumbnail-prompt(s) in één keer naar het klembord.";
  copyBtn.addEventListener("click", () =>
      copyText(ta.value, `Thumbnail-prompt (${variants.length} varianten) gekopieerd ✓`));
  head.appendChild(copyBtn);

  const x = document.createElement("button");
  x.className = "btn sm";
  x.textContent = "✕";
  x.title = "Sluiten";
  x.addEventListener("click", close);
  head.appendChild(x);

  render();
  box.appendChild(head);
  box.appendChild(ta);

  const foot = document.createElement("div");
  foot.className = "sub small";
  foot.style.cssText = "padding:8px 16px;border-top:1px solid var(--border,#e5e5e5)";
  foot.textContent =
      "De live pipeline rendert de thumbnail via de cast-referenties (anchor-route); " +
      "de volledige prompt is de zelfstandige fallback. Beide komen 1-op-1 uit de thumbnail-service.";
  box.appendChild(foot);

  overlay.appendChild(box);
  document.body.appendChild(overlay);
  const onKey = (e) => { if (e.key === "Escape") { close(); document.removeEventListener("keydown", onKey); } };
  document.addEventListener("keydown", onKey);
}

/** Bouwt het lopende VERHAAL van de aflevering uit de scènes: per scène de
 *  narration (voice-over) + de gesproken dialoog, in volgorde. Visuele
 *  regie-omschrijvingen blijven eruit — dit is wat de kijker HOORT, zodat je het
 *  verhaal zelf kunt nalezen. */
function buildStoryText(scenes) {
  const out = [];
  (scenes || []).forEach(s => {
    const block = [`— Scène ${s.seq}${s.phase ? " · " + s.phase : ""} —`];
    // narration is echt een voice-over alleen als die afwijkt van de visualDesc
    // (de backend vult lege narration met de visualDesc voor weergave).
    const vo = (s.narration || "").trim();
    if (vo && vo !== (s.visualDesc || "").trim()) block.push("VO: " + vo);
    (s.lines || []).forEach(l => {
      if (l && l.text && l.text.trim()) {
        block.push((l.speaker ? l.speaker + ": " : "") + l.text.trim());
      }
    });
    if (block.length === 1) block.push("(stille beat — geen tekst)");
    out.push(block.join("\n"));
  });
  return out.join("\n\n");
}

/** Modal met het lopende verhaal (narration + dialoog), kopieerbaar in één keer. */
function openStoryModal(scenes) {
  const overlay = document.createElement("div");
  overlay.style.cssText =
      "position:fixed;inset:0;background:rgba(0,0,0,.55);z-index:1000;" +
      "display:flex;align-items:center;justify-content:center;padding:24px";
  const close = () => overlay.remove();
  overlay.addEventListener("click", (e) => { if (e.target === overlay) close(); });

  const box = document.createElement("div");
  box.style.cssText =
      "background:var(--bg,#fff);color:inherit;border-radius:12px;max-width:760px;" +
      "width:100%;max-height:86vh;display:flex;flex-direction:column;overflow:hidden;" +
      "box-shadow:0 12px 48px rgba(0,0,0,.4)";

  const head = document.createElement("div");
  head.style.cssText =
      "display:flex;align-items:center;gap:8px;flex-wrap:wrap;padding:14px 16px;" +
      "border-bottom:1px solid var(--border,#e5e5e5)";
  const title = document.createElement("strong");
  title.textContent = `Verhaal (${(scenes || []).length} scènes)`;
  title.style.marginRight = "auto";
  head.appendChild(title);

  const ta = document.createElement("textarea");
  ta.readOnly = true;
  ta.value = buildStoryText(scenes);
  ta.style.cssText =
      "flex:1;width:100%;border:0;resize:none;padding:14px 16px;font:13px/1.6 " +
      "ui-sans-serif,system-ui,sans-serif;background:transparent;color:inherit;outline:none";

  const copyBtn = document.createElement("button");
  copyBtn.className = "btn sm approve";
  copyBtn.textContent = "📋 Kopieer verhaal";
  copyBtn.title = "Kopieert het volledige verhaal (alle scènes) naar het klembord.";
  copyBtn.addEventListener("click", () =>
      copyText(ta.value, "Verhaal gekopieerd ✓"));
  head.appendChild(copyBtn);

  const x = document.createElement("button");
  x.className = "btn sm";
  x.textContent = "✕";
  x.title = "Sluiten";
  x.addEventListener("click", close);
  head.appendChild(x);

  box.appendChild(head);
  box.appendChild(ta);

  const foot = document.createElement("div");
  foot.className = "sub small";
  foot.style.cssText = "padding:8px 16px;border-top:1px solid var(--border,#e5e5e5)";
  foot.textContent = "Het lopende verhaal: voice-over (VO) + gesproken dialoog per scène, in volgorde.";
  box.appendChild(foot);

  overlay.appendChild(box);
  document.body.appendChild(overlay);
  const onKey = (e) => { if (e.key === "Escape") { close(); document.removeEventListener("keydown", onKey); } };
  document.addEventListener("keydown", onKey);
}

/** Toolbar bovenaan de scènelijst met de "alle prompts"-knoppen. */
function scenePromptsBar(scenes) {
  const bar = document.createElement("div");
  bar.className = "scene-acts";
  bar.style.cssText = "margin:4px 0 8px";
  bar.appendChild(sceneItem("📖 Verhaal",
      "Toont het lopende verhaal van de aflevering (voice-over + dialoog per scène) " +
      "in één venster, met een knop om alles te kopiëren — om het verhaal zelf na te lezen.",
      () => openStoryModal(scenes)));
  bar.appendChild(sceneItem("📋 Alle prompts",
      "Toont alle scène-prompts (omschrijving + volledige Veo-prompt) in één venster, " +
      "met een knop om ze in één keer te kopiëren.",
      () => openPromptsModal(scenes)));
  bar.appendChild(sceneItem("🖼️ Thumbnail-prompt",
      "Toont de thumbnail-prompt(s) per variant — dezelfde tekst die de pipeline naar het " +
      "beeldmodel stuurt — kopieerbaar, net als de scène-prompts.",
      async () => {
        try {
          const data = await api.get(`/api/v1/videos/${id}/thumbnail-prompt`, { key: "thumb-prompt" });
          openThumbnailPromptModal(data);
        } catch (e) {
          toast("Thumbnail-prompt ophalen mislukt — draait de thumbnail-service?", "error");
        }
      }));
  return bar;
}

// One row per scene: script (dialogue + visual description) on the LEFT,
// the scene image (or placeholder) + per-scene actions on the RIGHT.
// Slugify a scene goal into the file-/label-safe token used in the
// "scene-<seq>-<title>" naming: lowercase, accent-stripped, hyphen-separated,
// capped to 6 words so filenames stay sane. Empty goal → "".
function slugifyGoal(goal) {
  if (!goal) return "";
  return String(goal)
    .toLowerCase()
    .normalize("NFD")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .split("-").filter(Boolean).slice(0, 6).join("-");
}

// Read-only: surface the HERO OBJECTS the compiler injected into the Veo prompt
// (the "KEY OBJECT — NAME ( … ) / Current state: X" block). No backend call — we
// parse what is already present in s.veoPrompt. Returns [{name, state}].
function keyObjectsFromPrompt(veoPrompt) {
  const out = [];
  if (!veoPrompt) return out;
  const lines = String(veoPrompt).split("\n");
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^KEY OBJECT\s*[—-]\s*(.+?)\s*\(/);
    if (!m) continue;
    let state = "";
    for (let j = i + 1; j < Math.min(i + 4, lines.length); j++) {
      const sm = lines[j].match(/Current state:\s*([^.]+)/i);
      if (sm) { state = sm[1].trim(); break; }
    }
    out.push({ name: m[1].trim(), state });
  }
  return out;
}

// Compact overview line: which hero objects appear, and in how many scenes.
// Empty (no element) when the episode has none, so it never adds clutter.
function keyObjectsSummary(scenes) {
  const wrap = document.createElement("div");
  const counts = {};
  for (const s of (scenes || [])) {
    for (const o of keyObjectsFromPrompt(s.veoPrompt)) {
      counts[o.name] = (counts[o.name] || 0) + 1;
    }
  }
  const names = Object.keys(counts);
  if (!names.length) return wrap;
  wrap.className = "small";
  wrap.style.cssText = "margin:4px 0 8px;padding:6px 8px;border-left:3px solid #1f7a4d;" +
      "background:rgba(31,122,77,.06);border-radius:4px";
  wrap.textContent = "📦 Belangrijke objecten: " +
      names.map(n => n + " (" + counts[n] + " scène" + (counts[n] === 1 ? "" : "s") + ")").join(" · ");
  return wrap;
}

// 🎞 Film-rol — alle scènes als een horizontale strook miniaturen, met tussen
// elke twee scènes een +-knop om de overgang te kiezen. Read-only thumbnails;
// de overgang slaat op via /scenes/{seq}/transition (zichtbaar na Re-assemble).
const TRANSITION_OPTS = [
  ["", "(phase-default)"], ["cut", "cut (harde snit)"], ["fade", "crossfade"],
  ["fadeblack", "fade → zwart"], ["fadewhite", "fade → wit"], ["dissolve", "dissolve"],
  ["wipeleft", "wipe ←"], ["wiperight", "wipe →"], ["wipeup", "wipe ↑"], ["wipedown", "wipe ↓"],
  ["slideleft", "slide ←"], ["slideright", "slide →"], ["circleopen", "circle open"],
  ["circleclose", "circle close"], ["zoomin", "zoom in"], ["pixelize", "pixelize"], ["radial", "radial"]];

// Grootte van de filmrol-miniaturen (Auke: groter + scrubbaar). 16:9.
const REEL_W = 260, REEL_H = 146;
const REEL_CLIP = 10;       // Omni-clips zijn 10s
const REEL_MINLEN = 1;      // backend-minimum scènelengte

function reelFrame(s) {
  const f = document.createElement("div");
  f.style.cssText = `flex:0 0 auto;width:${REEL_W}px;text-align:center`;

  let media, video = null;
  if (s.hasClip) {
    video = document.createElement("video");
    video.muted = true;
    video.playsInline = true;
    video.setAttribute("playsinline", "");
    video.preload = "metadata";
    video.src = `/dashboard/${encodeURIComponent(id)}/scene/${s.seq}/clip.mp4#t=0.1`;
    media = video;
  } else {
    media = document.createElement("img");
    media.src = `/review/images/${encodeURIComponent(id)}/file/${s.seq}.png`
              + (s.imageVersion ? `?v=${s.imageVersion}` : "");
  }
  media.style.cssText = "width:100%;height:100%;object-fit:cover;display:block;background:#222";
  media.onerror = () => { media.style.visibility = "hidden"; };

  // Houder = de filmcel. Trim-handvatten + dim-overlays liggen hier ÓP het beeld;
  // de tijd-badge toont waar in de clip je staat tijdens slepen.
  const holder = document.createElement("div");
  holder.style.cssText = `position:relative;width:${REEL_W}px;height:${REEL_H}px;` +
      "border-radius:6px;overflow:hidden;border:2px solid rgba(255,255,255,.12);background:#222";
  const tBadge = document.createElement("div");
  tBadge.style.cssText = "position:absolute;left:50%;top:6px;transform:translateX(-50%);" +
      "background:rgba(0,0,0,.7);color:#fff;font:600 11px/1.5 ui-monospace,monospace;" +
      "padding:1px 6px;border-radius:4px;pointer-events:none;display:none;z-index:3";
  holder.append(media, tBadge);

  const cap = document.createElement("div");
  cap.className = "small";
  cap.style.cssText = `margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;width:${REEL_W}px`;
  const slug = slugifyGoal(s.goal);
  cap.textContent = "scene-" + s.seq + (slug ? "-" + slug : "");
  cap.title = cap.textContent;

  // Bij een clip: trim-handvatten op het beeld + readout/opslaan eronder.
  if (s.hasClip) {
    f.append(holder, cap, reelTrim(s, video, tBadge, holder));
  } else {
    f.append(holder, cap);
  }
  return f;
}

// ✂️ Inkorten ÓP de afbeelding: een handvat aan de LINKERkant (in-punt) en aan de
// RECHTERkant (uit-punt). Slepen scrubt live het video-frame (tijd-badge toont waar
// je staat) en dimt het weggesneden stuk. Onder het beeld de readout + "✓ opslaan".
function reelTrim(s, video, tBadge, holder) {
  const W = REEL_W, CLIP = REEL_CLIP, MINLEN = REEL_MINLEN;
  let inV = Math.min(CLIP - MINLEN, Math.max(0, +(s.trimStartSeconds || 0)));
  let outV = Math.max(inV + MINLEN, Math.min(CLIP, +(s.trimEndSeconds || CLIP)));
  const fmt = (v) => v.toFixed(1);
  const xOf = (t) => (t / CLIP) * W;
  const tOf = (x) => Math.max(0, Math.min(CLIP, (x / W) * CLIP));

  const dimCss = "position:absolute;top:0;bottom:0;background:rgba(0,0,0,.55);pointer-events:none;z-index:1";
  const leftDim = document.createElement("div");  leftDim.style.cssText = dimCss + ";left:0";
  const rightDim = document.createElement("div"); rightDim.style.cssText = dimCss + ";right:0";
  const handleCss = "position:absolute;top:0;bottom:0;width:12px;background:#f0b010;cursor:ew-resize;" +
      "z-index:2;touch-action:none;display:flex;align-items:center;justify-content:center;" +
      "box-shadow:0 0 0 1px rgba(0,0,0,.45);color:#000;font:700 11px/1 sans-serif";
  const leftH = document.createElement("div");  leftH.style.cssText = handleCss;  leftH.textContent = "⟨";
  const rightH = document.createElement("div"); rightH.style.cssText = handleCss; rightH.textContent = "⟩";
  leftH.title = "in-punt — sleep om in te korten en door de clip te scrubben";
  rightH.title = "uit-punt — sleep om in te korten en door de clip te scrubben";
  holder.append(leftDim, rightDim, leftH, rightH);

  const layout = () => {
    leftDim.style.width = xOf(inV) + "px";
    rightDim.style.width = (W - xOf(outV)) + "px";
    leftH.style.left = (xOf(inV) - 6) + "px";
    rightH.style.left = (xOf(outV) - 6) + "px";
  };

  // Live scrub (seek-coalescing: snel slepen verstopt de speler niet).
  let pending = null;
  const scrubTo = (t) => {
    if (!video) return;
    if (tBadge) { tBadge.style.display = "block"; tBadge.textContent = fmt(t) + "s"; }
    if (video.readyState < 1) {
      video.addEventListener("loadedmetadata", () => { try { video.currentTime = t; } catch (e) {} }, { once: true });
      return;
    }
    if (video.seeking) { pending = t; return; }
    try { video.currentTime = t; } catch (e) {}
  };
  if (video) video.addEventListener("seeked", () => {
    if (pending != null) { const t = pending; pending = null; try { video.currentTime = t; } catch (e) {} }
  });

  const read = document.createElement("div");
  read.className = "small";
  read.style.cssText = "white-space:nowrap;font-variant-numeric:tabular-nums;font-size:11px;margin-top:3px";
  const sync = () => { read.textContent = `✂ ${fmt(inV)}–${fmt(outV)}s (${fmt(outV - inV)}s)`; };

  const startDrag = (handle, which) => {
    handle.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      try { handle.setPointerCapture(e.pointerId); } catch (err) {}
      const rect = holder.getBoundingClientRect();
      const move = (ev) => {
        let t = tOf(ev.clientX - rect.left);
        if (which === "in")  inV = Math.max(0, Math.min(t, outV - MINLEN));
        else                 outV = Math.min(CLIP, Math.max(t, inV + MINLEN));
        layout(); scrubTo(which === "in" ? inV : outV); sync();
      };
      const up = () => {
        handle.removeEventListener("pointermove", move);
        handle.removeEventListener("pointerup", up);
        handle.removeEventListener("pointercancel", up);
      };
      handle.addEventListener("pointermove", move);
      handle.addEventListener("pointerup", up);
      handle.addEventListener("pointercancel", up);
    });
  };
  startDrag(leftH, "in");
  startDrag(rightH, "out");
  layout();
  sync();

  const box = document.createElement("div");
  box.style.cssText = `width:${W}px;display:flex;flex-direction:column;align-items:center;gap:2px`;
  const apply = sceneBtn("✓ Inkorten opslaan", "Inkorting opslaan (zichtbaar na Re-assemble)", async () => {
    const startSec = +inV.toFixed(1), endSec = +outV.toFixed(1);
    await api.post(`/api/v1/videos/${id}/scenes/${s.seq}/trim`,
      { startSec, endSec }, { key: `trim-${s.seq}` });
    toast(`Scène ${s.seq} ingekort naar ${fmt(inV)}-${fmt(outV)}s. Druk op Re-assemble om het toe te passen.`, "info");
    loadScenes();
  });
  apply.style.cssText += ";margin-top:2px";
  box.append(read, apply);
  return box;
}

// Nog-niet-opgeslagen overgangskeuzes (Auke: één save-knop i.p.v. per stuk).
// seq → {type, seconds}. Blijft over een poll heen bewaard (module-scope); de
// "Overgangen opslaan"-knop bovenaan de filmrol schrijft alles in één keer weg.
const pendingTransitions = new Map();
let transitionsSaveBtn = null;
let lastScenesArr = null;   // laatste scènes (voor een gerichte filmrol-herteken)
// Bumper-overgangen (intro → scène 1 en laatste scène → outro). De bumpers
// hebben geen scène-nummer, dus die overgangen leven los van pendingTransitions.
// Worden direct gepersisteerd (POST /bumper-transition) en renderen in de
// assemblage (Concatenator); de preview-speler simuleert ze mee.
const bumperTransitions = { intro: { type: "", seconds: 0.3 }, outro: { type: "", seconds: 0.3 } };

function updateTransitionsSaveBar() {
  if (!transitionsSaveBtn) return;
  const n = pendingTransitions.size;
  transitionsSaveBtn.textContent = n ? `💾 Overgangen opslaan (${n})` : "💾 Overgangen opslaan";
  transitionsSaveBtn.disabled = n === 0;
  transitionsSaveBtn.style.opacity = n ? "1" : ".5";
}

// Regel boven de filmrol: één overgang + duur voor de HELE film instellen, plus
// de ene save-knop die alle (globale én per-scène) keuzes wegschrijft.
function transitionsSaveBar(scenes) {
  const bar = document.createElement("div");
  bar.style.cssText = "margin:4px 0 2px;display:flex;align-items:center;gap:8px;flex-wrap:wrap";

  // ── Hele film: dezelfde overgang + duur op elke grens tussen scènes ──
  const allLbl = document.createElement("span");
  allLbl.className = "small";
  allLbl.style.fontWeight = "600";
  allLbl.textContent = "Hele film:";
  const allSel = document.createElement("select");
  allSel.className = "scene-model-sel";
  allSel.title = "Overgang voor alle scènes";
  for (const [v, l] of TRANSITION_OPTS) allSel.appendChild(new Option(l, v));
  const allSec = document.createElement("input");
  allSec.type = "number"; allSec.min = "0.05"; allSec.max = "1.5"; allSec.step = "0.05";
  allSec.value = "0.3"; allSec.style.width = "54px";
  allSec.title = "duur (s) voor alle overgangen";
  const allBtn = document.createElement("button");
  allBtn.className = "btn sm";
  allBtn.textContent = "Toepassen op alle scènes";
  allBtn.title = "Zet deze overgang + duur op elke grens tussen scènes; daarna opslaan met de knop hiernaast";
  allBtn.addEventListener("click", () => {
    const type = allSel.value;
    const seconds = parseFloat(allSec.value) || 0.3;
    const bnds = (scenes || []).slice(1);   // grenzen tussen scènes (bumpers hebben eigen +)
    for (const s of bnds) pendingTransitions.set(s.seq, { type, seconds });
    updateTransitionsSaveBar();
    renderMontageSection(lastScenesArr);     // herteken zodat elke + de keuze toont
    toast(`Overgang "${type || "(geen)"}" ${seconds}s op alle ${bnds.length} grenzen gezet — klik nu Opslaan.`, "info");
  });

  const spacer = document.createElement("span");
  spacer.style.cssText = "margin-left:auto";

  // ── Save-knop: schrijft alle onthouden overgangen ineens weg ──
  const btn = document.createElement("button");
  btn.className = "btn sm approve";
  btn.title = "Slaat alle gekozen overgangen in één keer op (zichtbaar na Re-assemble)";
  btn.addEventListener("click", async () => {
    if (!pendingTransitions.size) return;
    btn.disabled = true;
    const entries = [...pendingTransitions.entries()];
    try {
      for (const [seq, t] of entries) {
        await api.post(`/api/v1/videos/${id}/scenes/${seq}/transition`,
          t.type ? { type: t.type, seconds: t.seconds } : { type: "" },
          { key: `transition-${seq}` });
      }
      pendingTransitions.clear();
      toast(`${entries.length} overgang(en) opgeslagen. Druk op Re-assemble om toe te passen.`, "info");
      loadScenes();
    } catch (e) { btn.disabled = false; /* api.js toasted */ }
  });
  transitionsSaveBtn = btn;

  bar.append(allLbl, allSel, allSec, allBtn, spacer, btn);
  updateTransitionsSaveBar();
  return bar;
}

// De +-knop op de grens VÓÓR `scene` (de overgang ernaartoe). Klikken opent een
// picker (type + duur); "OK" onthoudt de keuze lokaal — opslaan doe je met de
// ene knop bovenaan de filmrol.
function transitionPlus(scene) {
  const seq = scene.seq;
  const cur = pendingTransitions.has(seq)
      ? { ...pendingTransitions.get(seq) }
      : { type: scene.transitionType || "", seconds: scene.transitionSeconds || 0.3 };
  const btn = document.createElement("button");
  btn.className = "btn sm";
  // Verticaal in het midden van de afbeeldingen.
  const midTop = Math.round(REEL_H / 2) - 13;
  btn.style.cssText = `flex:0 0 auto;align-self:flex-start;margin:${midTop}px 1px 0;min-width:28px`;
  const refreshLabel = () => {
    const pending = pendingTransitions.has(seq);
    btn.textContent = (cur.type ? "⇄" : "+") + (pending ? "•" : "");
    btn.title = cur.type
        ? `Overgang: ${cur.type}${cur.seconds ? " " + cur.seconds + "s" : ""}`
          + (pending ? " (nog niet opgeslagen)" : "") + " — klik om te wijzigen"
        : "Kies de overgang tussen deze twee scènes";
  };
  refreshLabel();
  btn.addEventListener("click", () => {
    const box = document.createElement("span");
    box.style.cssText = `display:inline-flex;align-items:center;gap:3px;align-self:flex-start;margin-top:${midTop - 4}px`;
    const sel = document.createElement("select");
    sel.className = "scene-model-sel";
    for (const [v, l] of TRANSITION_OPTS) {
      const o = document.createElement("option");
      o.value = v; o.textContent = l;
      if (v === cur.type) o.selected = true;
      sel.appendChild(o);
    }
    const sec = document.createElement("input");
    sec.type = "number"; sec.min = "0.05"; sec.max = "1.5"; sec.step = "0.05";
    sec.style.width = "54px"; sec.title = "duur (s)";
    sec.value = cur.seconds || 0.3;
    const ok = document.createElement("button");
    ok.className = "btn sm";
    ok.textContent = "OK";
    ok.title = "Onthoud deze overgang — opslaan met de knop bovenaan de filmrol";
    ok.addEventListener("click", () => {
      cur.type = sel.value;
      cur.seconds = parseFloat(sec.value) || 0.3;
      pendingTransitions.set(seq, { type: cur.type, seconds: cur.seconds });
      updateTransitionsSaveBar();
      box.replaceWith(btn);
      refreshLabel();
    });
    box.append(sel, sec, ok);
    btn.replaceWith(box);
  });
  return btn;
}

// Overgang-+ op een bumper-grens (intro → scène 1, of laatste scène → outro).
// Slaat op in bumperTransitions[position]; werkt in de preview-speler.
function bumperTransitionPlus(position) {
  const cur = bumperTransitions[position];
  const btn = document.createElement("button");
  btn.className = "btn sm";
  const midTop = Math.round(REEL_H / 2) - 13;
  btn.style.cssText = `flex:0 0 auto;align-self:flex-start;margin:${midTop}px 1px 0;min-width:28px`;
  const refresh = () => {
    btn.textContent = cur.type ? "⇄" : "+";
    btn.title = (position === "intro" ? "Overgang intro → scène 1" : "Overgang laatste scène → outro")
        + (cur.type ? `: ${cur.type} ${cur.seconds}s` : "");
  };
  refresh();
  btn.addEventListener("click", () => {
    const box = document.createElement("span");
    box.style.cssText = `display:inline-flex;align-items:center;gap:3px;align-self:flex-start;margin-top:${midTop - 4}px`;
    const sel = document.createElement("select");
    sel.className = "scene-model-sel";
    for (const [v, l] of TRANSITION_OPTS) {
      const o = document.createElement("option");
      o.value = v; o.textContent = l;
      if (v === cur.type) o.selected = true;
      sel.appendChild(o);
    }
    const sec = document.createElement("input");
    sec.type = "number"; sec.min = "0.05"; sec.max = "1.5"; sec.step = "0.05";
    sec.style.width = "54px"; sec.title = "duur (s)";
    sec.value = cur.seconds || 0.3;
    const ok = document.createElement("button");
    ok.className = "btn sm"; ok.textContent = "OK";
    ok.addEventListener("click", async () => {
      cur.type = sel.value; cur.seconds = parseFloat(sec.value) || 0.3;
      box.replaceWith(btn); refresh();
      // Persisteer op de job (wordt toegepast bij de (re)assemblage); de preview
      // gebruikt de lokale bumperTransitions-state.
      try {
        await api.post(`/api/v1/videos/${id}/bumper-transition`,
          { position, type: cur.type, seconds: cur.seconds }, { key: `bumper-${position}` });
        toast(`Overgang ${position === "intro" ? "intro → scène 1" : "laatste scène → outro"} opgeslagen. Druk op Re-assemble om toe te passen.`, "info");
      } catch (e) { /* api.js toasted */ }
    });
    box.append(sel, sec, ok);
    btn.replaceWith(box);
  });
  return btn;
}

// Vaste brand-bumper (intro/outro) als eerste/laatste frame in de filmrol —
// read-only context (geen trim/overgang). Toont een frame uit bible/intro.mp4
// resp. outro.mp4; ontbreekt de clip, dan een nette placeholder.
function bumperFrame(kind) {
  const isIntro = kind === "intro";
  const f = document.createElement("div");
  f.style.cssText = `flex:0 0 auto;width:${REEL_W}px;text-align:center`;
  const holder = document.createElement("div");
  holder.style.cssText = `position:relative;width:${REEL_W}px;height:${REEL_H}px;border-radius:6px;` +
      "overflow:hidden;border:2px solid rgba(240,176,16,.5);background:#1a1a1d";
  const v = document.createElement("video");
  v.muted = true; v.playsInline = true; v.setAttribute("playsinline", "");
  v.preload = "metadata";
  v.src = `/api/v1/${kind}/current.mp4#t=0.5`;
  v.style.cssText = "width:100%;height:100%;object-fit:cover;display:block;background:#1a1a1d";
  v.onerror = () => {
    v.style.display = "none";
    const ph = document.createElement("div");
    ph.textContent = (isIntro ? "Intro" : "Outro") + " — geen clip";
    ph.style.cssText = "position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:#888;font-size:12px";
    holder.appendChild(ph);
  };
  const tag = document.createElement("div");
  tag.textContent = isIntro ? "▶ INTRO" : "OUTRO ⏹";
  tag.style.cssText = "position:absolute;left:50%;bottom:6px;transform:translateX(-50%);" +
      "background:rgba(0,0,0,.72);color:#f0b010;font:700 11px/1.5 sans-serif;padding:1px 8px;border-radius:4px";
  holder.append(v, tag);
  const cap = document.createElement("div");
  cap.className = "small";
  cap.style.cssText = `margin-top:3px;width:${REEL_W}px;font-weight:600;color:var(--muted,#888)`;
  cap.textContent = isIntro ? "Intro (vast)" : "Outro (vast)";
  f.append(holder, cap);
  return f;
}

// ▶ Speel de hele film in een nieuw venster: intro → alle scènes (mét hun
// inkortingen) → outro, achter elkaar, met een GESIMULEERDE overgang tussen de
// clips (korte fade; "cut" = harde snit, "fadewhite" flitst wit, rest fade door
// zwart, met de ingestelde duur). De echte ffmpeg-overgangen + muziek komen pas
// bij het assembleren — dit is een benadering om de cut te kunnen beoordelen.
function playWholeFilm(scenes) {
  const transOf = (s) => pendingTransitions.has(s.seq)
      ? pendingTransitions.get(s.seq)
      : { type: s.transitionType || "", seconds: s.transitionSeconds || 0 };
  const playlist = [{ src: "/api/v1/intro/current.mp4", label: "Intro", trans: null }];
  (scenes || []).forEach((s, idx) => {
    const start = +(s.trimStartSeconds || 0);
    const end = s.trimEndSeconds != null && s.trimEndSeconds !== "" ? +s.trimEndSeconds : null;
    // Overgang NAAR deze scène: voor scène 1 de bumper-overgang (intro → scène 1),
    // daarna de gewone tussen-scène-overgang.
    const t = idx === 0 ? bumperTransitions.intro : transOf(s);
    playlist.push({
      src: `/dashboard/${encodeURIComponent(id)}/scene/${s.seq}/clip.mp4`,
      start, end, label: "Scène " + s.seq,
      trans: { type: t.type || "", seconds: +(t.seconds || 0.3) }
    });
  });
  playlist.push({
    src: "/api/v1/outro/current.mp4", label: "Outro",
    trans: { type: bumperTransitions.outro.type || "", seconds: +(bumperTransitions.outro.seconds || 0.3) }
  });

  const w = window.open("", "_blank");
  if (!w) { toast("Pop-up geblokkeerd — sta pop-ups toe voor deze pagina", "error"); return; }
  const html = '<!DOCTYPE html><html><head><meta charset="utf-8"><title>Hele film — preview</title>'
    + '<style>html,body{margin:0;height:100%;background:#000;color:#ccc;'
    + 'font:13px system-ui,sans-serif}body{display:flex;flex-direction:column}'
    + '.stage{position:relative;flex:1;min-height:0}'
    + 'video{position:absolute;inset:0;width:100%;height:100%;background:#000}'
    + '#fade{position:absolute;inset:0;background:#000;opacity:0;pointer-events:none}'
    + '.bar{padding:6px 12px;display:flex;gap:14px;align-items:center}'
    + '.note{color:#888}</style></head><body>'
    + '<div class="stage"><video id="v" controls autoplay playsinline></video><div id="fade"></div></div>'
    + '<div class="bar"><b id="lbl"></b>'
    + '<span class="note">Preview — volgorde, inkortingen &amp; <i>gesimuleerde</i> overgangen; echte overgangen + muziek pas na assembleren.</span></div>'
    + '<script>'
    + 'var PL=' + JSON.stringify(playlist) + ';'
    + 'var v=document.getElementById("v"),fade=document.getElementById("fade"),lbl=document.getElementById("lbl"),i=0;'
    + 'function fadeColor(t){return (t&&/white/.test(t.type))?"#fff":"#000";}'
    + 'function dur(t){return t&&t.type&&t.type!=="cut"?Math.max(0.1,Math.min(1.5,t.seconds||0.3)):0;}'
    // Fade het huidige beeld uit (kleur o.b.v. de overgang van het VOLGENDE segment), wissel, fade in.
    + 'function nx(){var nt=PL[i+1]?PL[i+1].trans:null;var d=dur(nt);'
    + 'if(d<=0){i++;load();return;}'
    + 'fade.style.background=fadeColor(nt);fade.style.transition="opacity "+(d/2)+"s";fade.style.opacity="1";'
    + 'setTimeout(function(){i++;load(true);setTimeout(function(){fade.style.opacity="0";},30);},d*500);}'
    + 'function load(fadingIn){if(i>=PL.length){lbl.textContent="Einde";try{v.pause();}catch(e){}v.removeAttribute("src");return;}'
    + 'var p=PL[i];lbl.textContent=(i+1)+"/"+PL.length+" \\u00b7 "+p.label;'
    + 'v.src=p.src;v.load();'
    + 'v.onloadedmetadata=function(){if(p.start){try{v.currentTime=p.start;}catch(e){}}v.play().catch(function(){});};'
    + 'v.ontimeupdate=function(){if(p.end!=null&&v.currentTime>=p.end){v.ontimeupdate=null;nx();}};'
    + 'v.onended=function(){nx();};v.onerror=function(){i++;load();};}'
    + 'load();'
    + '<\/script></body></html>';
  w.document.open(); w.document.write(html); w.document.close();
}

function filmReel(scenes) {
  const wrap = document.createElement("div");
  if (!scenes || !scenes.length) return wrap;
  const titleRow = document.createElement("div");
  titleRow.style.cssText = "display:flex;align-items:flex-start;gap:10px;margin:6px 0 2px";
  const title = document.createElement("div");
  title.className = "small";
  title.style.cssText = "font-weight:600;flex:1";
  title.textContent = "🎞 Film-rol — vaste intro/outro vooraan en achteraan; kort scènes in met de handvatten op het beeld (links = begin, rechts = eind), kies met de + tussen de beelden de overgang (een • = nog niet opgeslagen) en sla ze samen op met de knop hieronder";
  const playBtn = document.createElement("button");
  playBtn.className = "btn sm";
  playBtn.style.cssText = "flex:0 0 auto;white-space:nowrap";
  playBtn.textContent = "▶ Speel hele film ⧉";
  playBtn.title = "Speelt intro → alle scènes (met inkortingen) → outro achter elkaar af in een nieuw venster. Ruwe preview; overgangen en muziek komen er pas bij het assembleren in.";
  playBtn.addEventListener("click", () => playWholeFilm(scenes));
  titleRow.append(title, playBtn);
  const reel = document.createElement("div");
  reel.className = "film-reel";   // filmstrip-look (perforaties) staat in dashboard.css
  reel.appendChild(bumperFrame("intro"));            // vaste intro vooraan
  reel.appendChild(bumperTransitionPlus("intro"));   // intro → scène 1
  scenes.forEach((s, idx) => {
    if (idx > 0) reel.appendChild(transitionPlus(s));   // grens tussen scènes
    reel.appendChild(reelFrame(s));
  });
  reel.appendChild(bumperTransitionPlus("outro"));   // laatste scène → outro
  reel.appendChild(bumperFrame("outro"));            // vaste outro achteraan
  wrap.append(titleRow, transitionsSaveBar(scenes), reel);   // titel + play, hele-film-instelling + save, filmrol
  return wrap;
}

// 💬 Stabiele kleur per spreker — hetzelfde personage krijgt altijd dezelfde
// tint, zodat je in het gespreksblok in één oogopslag ziet wie aan het woord is.
const SPEAKER_COLORS = ["#2f7ad4", "#d9700f", "#1f7a4d", "#a23bb5",
  "#c0392b", "#0d8b8b", "#b8860b", "#6b6bd6"];
function speakerColor(name) {
  const n = (name || "?").trim().toLowerCase();
  let h = 0;
  for (let i = 0; i < n.length; i++) h = (h * 31 + n.charCodeAt(i)) >>> 0;
  return SPEAKER_COLORS[h % SPEAKER_COLORS.length];
}

// ── Montage-paneel (Auke): aparte montage-stap vóór de assemblage. Verschijnt
// alleen als de job bij de montage-gate staat (MONTAGE_REVIEW_PENDING). Bundelt
// de achtergrondmuziek-keuze (met preview) en de "Assembleren"-actie; de
// volgorde, het knippen/trimmen en de overgangen doe je per scène hieronder
// (film-rol + de in/uit-schuifjes en het +-overgangsmenu).
function montagePanel() {
  if (!lastJob || lastJob.status !== "MONTAGE_REVIEW_PENDING") return null;
  const panel = document.createElement("div");
  panel.className = "card";
  panel.style.cssText = "border:1px solid var(--warning,#f59e0b);border-radius:10px;" +
    "padding:12px 14px;margin:4px 0 12px;background:rgba(245,158,11,.06)";

  const h = document.createElement("div");
  h.style.cssText = "font-weight:700;font-size:15px;margin-bottom:4px";
  h.textContent = "🎬 Montage";
  panel.appendChild(h);

  const sub = document.createElement("div");
  sub.style.cssText = "font-size:12px;color:var(--muted,#888);margin-bottom:10px";
  sub.textContent = "Zet de scènes op volgorde, knip/trim de in- en uitpunten en kies de "
    + "overgangen (hieronder per scène). Kies hier de achtergrondmuziek en assembleer.";
  panel.appendChild(sub);

  // Achtergrondmuziek met preview — leest de job-scoped library (toont de
  // huidige keuze, speelt een fragment af vóór je kiest).
  const musicRow = document.createElement("div");
  musicRow.style.cssText = "display:flex;align-items:center;gap:8px;margin:6px 0;flex-wrap:wrap";
  const lbl = document.createElement("span");
  lbl.style.cssText = "font-size:13px";
  lbl.textContent = "🎵 Achtergrondmuziek:";
  const sel = document.createElement("select");
  sel.className = "btn";
  const audio = new Audio();
  const preview = document.createElement("button");
  preview.className = "btn sm";
  preview.textContent = "▶ Preview";
  preview.title = "Speel een fragment van de gekozen track";
  let playing = false;
  preview.addEventListener("click", () => {
    if (!sel.value) { toast("Kies eerst een track", "error"); return; }
    if (playing) { audio.pause(); audio.currentTime = 0; playing = false; preview.textContent = "▶ Preview"; return; }
    audio.src = `/dashboard/music/${encodeURIComponent(sel.value)}.mp3`;
    audio.play().then(() => { playing = true; preview.textContent = "⏹ Stop"; }).catch(() => {});
  });
  audio.addEventListener("ended", () => { playing = false; preview.textContent = "▶ Preview"; });
  const apply = document.createElement("button");
  apply.className = "btn sm";
  apply.textContent = "Toepassen";
  apply.addEventListener("click", async () => {
    if (!sel.value) { toast("Kies eerst een track", "error"); return; }
    apply.disabled = true;
    try {
      await api.post(`/api/v1/videos/${id}/music`, { trackId: sel.value }, { key: "montage-music" });
      toast(`Muziek → ${sel.value}`, "info");
    } catch (e) { /* api.js toasted */ }
    finally { apply.disabled = false; }
  });
  api.get(`/api/v1/videos/${id}/music`, { key: "montage-music-list" }).then(data => {
    sel.replaceChildren(new Option("— kies een track —", ""));
    for (const t of (data.tracks || [])) {
      const o = new Option(`${t.name} · ${t.mood}`, t.id);
      if (t.selected) o.selected = true;
      sel.appendChild(o);
    }
  }).catch(() => {});
  musicRow.append(lbl, sel, preview, apply);
  panel.appendChild(musicRow);

  // De definitieve "Assembleren" (= approve) zit in de review-gate bovenaan de
  // pagina; hier alleen een verwijzing zodat er één duidelijke knop is.
  const note = document.createElement("div");
  note.style.cssText = "font-size:12px;color:var(--muted,#888);margin-top:8px";
  note.textContent = "Klaar? Klik bovenaan ‘Assembleren’ in de review-balk om de master te bouwen.";
  panel.appendChild(note);
  return panel;
}

function renderScenes(scenes) {
  if (!scenes || scenes.length === 0) {
    scenesHost.textContent = "no scenes yet (script not generated)";
    renderMontageSection(scenes);
    return;
  }
  const list = document.createElement("div");
  list.className = "scene-rows";
  // Het montage-paneel staat niet meer hier maar in de Montage-sectie
  // (renderMontageSection), samen met de filmrol.
  list.appendChild(sceneActionsHelp());
  list.appendChild(scenePromptsBar(scenes));
  list.appendChild(keyObjectsSummary(scenes));
  // De filmrol staat niet meer hier maar in de eigen Video-sectie (zie
  // renderVideoReel) — onder de Video-stap.
  for (const s of scenes) {
    const seq = s.seq;
    const row = document.createElement("div");
    row.className = "scene-row" + (s.locked ? " locked" : "");

    // ✨ SILENT VISUAL BEAT — the one scene with no dialogue, carried entirely
    // by the image. Golden frame so the reviewer's eye lands here first.
    // Uses the backend's silentBeat flag: the old client-side check broke when
    // the API started filling empty narration with the visualDesc for display
    // (the reason the golden frame silently disappeared).
    const isSilentBeat = s.silentBeat === true ||
        ((!s.lines || s.lines.length === 0) && !s.narration);
    // 🌟 HERO SCENES (hook/climax) — the beats that carry retention; a gold
    // edge pulls review attention to them without shouting like the silent beat.
    const isHero = !isSilentBeat && /^(hook|climax)$/i.test(s.phase || "");
    if (isHero) {
      row.style.cssText += "border:2px solid rgba(212,160,23,.55);border-radius:10px;padding:10px";
    }
    // 😄 HUMOR-SCÈNE — de ene speciale lach-beat. Een duidelijke oranje rand +
    // lach-badge zodat de reviewer 'm meteen herkent (los van de gouden hero/
    // stille-scène-rand). Sluit hero/silent uit zodat de randen elkaar niet
    // overschrijven.
    const isHumor = !isSilentBeat && !isHero && /^humor$/i.test(s.phase || "");
    if (isHumor) {
      row.style.cssText += "border:2px solid #f5821f;border-radius:10px;padding:10px;" +
          "box-shadow:0 0 10px rgba(245,130,31,.28);" +
          "background:linear-gradient(rgba(245,130,31,.06),rgba(245,130,31,.02))";
      const badge = document.createElement("div");
      badge.style.cssText = "color:#d9700f;font-weight:700;font-size:12px;margin-bottom:4px";
      badge.textContent = "😄 HUMOR-SCÈNE — de lach-beat; hier moet de grap duidelijk landen.";
      row.appendChild(badge);
    }
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
    // Scene naming: scene-<seq>-<goal-slug> (this label IS the recommended Flow
    // clip filename — file the export as "<this>.mp4" and import picks it up).
    const slug = slugifyGoal(s.goal);
    const sceneName = "scene-" + seq + (slug ? "-" + slug : "");
    const bits = [sceneName];
    if (s.durationSeconds) bits.push(s.durationSeconds + "s");
    if (s.phase) bits.push(isHero ? "🌟 " + s.phase : isHumor ? "😄 " + s.phase : s.phase);
    if (s.hasClip) bits.push("🎬");
    if (s.locked) bits.push("🔒");
    head.textContent = bits.join(" · ");
    head.title = "Aanbevolen clip-bestandsnaam: " + sceneName + ".mp4";
    left.appendChild(head);
    // 📦 Read-only: hero objects in this scene + their state, parsed from the
    // compiled Veo prompt (the KEY OBJECT block). Shows which objects matter and
    // where the egg is in its life cycle, without any editing.
    const kobs = keyObjectsFromPrompt(s.veoPrompt);
    if (kobs.length) {
      const kb = document.createElement("div");
      kb.className = "small";
      kb.style.cssText = "margin:2px 0 4px;color:#1f7a4d;font-weight:600";
      kb.textContent = "📦 Belangrijk object: " +
          kobs.map(o => o.name + (o.state ? " — " + o.state : "")).join("; ");
      left.appendChild(kb);
    }
    // 🛡 QC findings for this scene (filled async by annotateQcFindings).
    const qcSlot = document.createElement("div");
    qcSlot.className = "small";
    qcSlot.dataset.qcSeq = String(seq);
    left.appendChild(qcSlot);
    // 💬 Gesprek — de dialoog van de scène als duidelijk leesbaar blok: per
    // regel een gekleurde balk + sprekernaam (stabiele kleur per personage, zie
    // speakerColor) zodat je meteen ziet wie wat zegt.
    {
      const lines = s.lines || [];
      if (lines.length) {
        const conv = document.createElement("div");
        conv.className = "scene-conv";
        conv.style.cssText = "margin:4px 0;padding:6px 8px;border-radius:8px;" +
            "background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.08)";
        const ch = document.createElement("div");
        ch.className = "small";
        ch.style.cssText = "font-weight:600;opacity:.7;margin-bottom:4px";
        ch.textContent = "💬 Gesprek (" + lines.length + " regel" + (lines.length === 1 ? "" : "s") + ")";
        conv.appendChild(ch);
        for (const l of lines) {
          const color = speakerColor(l.speaker);
          const bubble = document.createElement("div");
          bubble.className = "script-line";
          bubble.style.cssText = "display:flex;gap:6px;align-items:baseline;margin:3px 0;" +
              "padding-left:8px;border-left:3px solid " + color;
          const sp = document.createElement("span");
          sp.style.cssText = "font-weight:700;white-space:nowrap;color:" + color;
          sp.textContent = l.speaker || "?";
          const tx = document.createElement("span");
          tx.textContent = l.text || "";
          bubble.append(sp, tx);
          conv.appendChild(bubble);
        }
        left.appendChild(conv);
      }
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
    // Het START-beeldframe (eerste .scene-img-frame) krijgt de busy-overlay
    // tijdens een regen/edit/eindbeeld — zo zie je dat er iets gebeurt.
    const startFrame = frame.classList && frame.classList.contains("scene-img-frame")
        ? frame : frame.querySelector(".scene-img-frame");

    // Scène-clip — speelt de bewegende clip van DEZE scène af, ONGEACHT hoe die
    // gemaakt is (geïmporteerde Google Flow-clip óf Veo). Opent de ruwe mp4
    // (zonder voice/muziek) in een apart tabblad: de inline speler werd door de
    // 5s-poll afgekapt (gebruikerswens 2026-06-14), dus een los tabblad dat
    // volledig doorspeelt. De bron-resolutie zit in MediaController#sceneClip
    // (clipPath eerst, anders de schijf-conventie) — herkomst-onafhankelijk.
    if (s.hasClip) {
      const clipBtn = document.createElement("a");
      clipBtn.className = "btn sm";
      clipBtn.textContent = "▶ Speel clip ⧉";
      clipBtn.title = "Speelt de clip van deze scène af (zonder voice/muziek) in een apart "
          + "tabblad — ongeacht of die in Google Flow of via Veo is gemaakt.";
      clipBtn.href = `/dashboard/${encodeURIComponent(id)}/scene/${seq}/clip.mp4`;
      clipBtn.target = "_blank";
      clipBtn.rel = "noopener";
      right.appendChild(clipBtn);
    }

    // ⚠ QC-AFGEKEURDE clip — de clip-QC keurde deze Veo-clip af; hij is bewaard
    // (clip.rejected.mp4) zodat je zelf kunt oordelen of de QC terecht afkeurde,
    // en hem eventueel alsnog kunt gebruiken (override → hermontage). De scène
    // gebruikt nu de Ken Burns-still.
    if (s.hasRejectedClip) {
      const reason = s.qcRejectReason || "QC afgekeurd";
      const qcBox = document.createElement("div");
      qcBox.style.cssText = "margin-top:6px;padding:6px 8px;border:1px solid #c0392b;" +
          "border-radius:8px;background:rgba(192,57,43,.07)";
      const badge = document.createElement("div");
      badge.style.cssText = "color:#c0392b;font-weight:700;font-size:12px";
      badge.textContent = "⚠ QC afgekeurd — clip vervangen door still";
      badge.title = reason;
      qcBox.appendChild(badge);
      const why = document.createElement("div");
      why.className = "sub small";
      why.style.cssText = "margin-top:2px;white-space:pre-wrap";
      why.textContent = "Reden: " + reason;
      qcBox.appendChild(why);

      // ▶ Bekijk afgekeurde clip — opent ALTIJD in een apart tabblad
      // (gebruikerswens 2026-06-14): de inline speler werd door de 5s-poll
      // afgekapt, dus geen inline player meer — gewoon een link naar de ruwe mp4.
      const rejBtn = document.createElement("a");
      rejBtn.className = "btn sm";
      rejBtn.style.marginTop = "4px";
      rejBtn.textContent = "▶ Bekijk afgekeurde clip ⧉";
      rejBtn.title = "Opent de afgekeurde Veo-clip in een apart tabblad — speelt volledig af, geen last van de 5s-refresh.";
      rejBtn.href = `/dashboard/${encodeURIComponent(id)}/scene/${seq}/rejected-clip.mp4`;
      rejBtn.target = "_blank";
      rejBtn.rel = "noopener";
      qcBox.appendChild(rejBtn);

      // ✓ Toch gebruiken (override QC) → promoveert de clip terug + hermonteert.
      const ovBtn = document.createElement("button");
      ovBtn.className = "btn sm approve";
      ovBtn.style.cssText = "margin-top:4px;margin-left:6px";
      ovBtn.textContent = "✓ Toch gebruiken (override QC)";
      ovBtn.title = "Gebruik deze afgekeurde clip alsnog — promoveert 'm terug naar clip.mp4. Druk daarna zelf op Re-assemble.";
      ovBtn.addEventListener("click", async () => {
        if (!confirm(
            "De QC keurde deze clip af wegens:\n\n" + reason + "\n\n" +
            "Toch in de video gebruiken? De clip wordt opgeslagen; druk daarna zelf op Re-assemble (geen Veo-kosten).")) return;
        await api.post(
          `/api/v1/videos/${id}/scenes/${seq}/accept-rejected-clip`,
          undefined, { key: `accept-rejected-clip-${seq}` });
        toast("Afgekeurde clip in gebruik genomen — opgeslagen. Druk op Re-assemble om het in de video te zetten.", "info");
        loadScenes();
      });
      qcBox.appendChild(ovBtn);
      right.appendChild(qcBox);
    }

    const acts = document.createElement("div");
    acts.className = "scene-acts";
    const P = (path, body) =>
      api.post(`/api/v1/videos/${id}/scenes/${seq}/${path}`, body, { key: `${path}-${seq}` });

    acts.appendChild(sceneItem("↻ Regen",
      "Genereert het STARTBEELD van deze scène opnieuw uit de oorspronkelijke scripttekst. " +
      "Je kunt optioneel een correctie-aanwijzing meegeven (bv. \"geen tweede kip\", \"hoed iets kleiner\", " +
      "\"meer naar links\") — die stuurt de nieuwe prompt. Leeg laten = gewone re-roll. " +
      "Goedkoop (alleen een beeld). De video zelf verandert pas na een re-roll/hermontage.",
      async () => {
        // Pre-fill the prompt with the best feedback we already have for this
        // scene (QA/Critic/QC). Short, best-effort GET; failure → empty prompt
        // (the legacy blind re-roll). The user can overwrite, extend or clear it.
        let suggested = "";
        try {
          const r = await api.get(
            `/api/v1/videos/${id}/scenes/${seq}/regen-hint`,
            { key: `regen-hint-${seq}` });
          suggested = (r && r.hint) ? String(r.hint) : "";
        } catch (e) { /* leave empty — plain prompt, current behaviour */ }
        // Optionele correctie-hint: leeg/cancel = gewone blinde re-roll.
        const hint = (window.prompt(
          "Wat moet er anders aan dit beeld? (voorstel uit de kwaliteitsanalyse — pas aan of wis)\n\n" +
          "Bijv. \"geen tweede kip\", \"hoed iets kleiner\", \"meer naar links\".\n" +
          "Leeg laten voor een gewone regeneratie.",
          suggested) || "").trim();
        setSceneBusy(startFrame, true, "Nieuw beeld genereren…");
        try {
          await P("regenerate", hint ? { correctionHint: hint } : undefined);
        } finally {
          setSceneBusy(startFrame, false);
        }
        loadScenes();
      }));
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
            setSceneBusy(startFrame, true, "Nieuw beeld genereren…");
            try { await P("edit", { visualDesc: vd }); }
            finally { setSceneBusy(startFrame, false); }
            loadScenes();
          });
        save.classList.add("approve");
        row.appendChild(save);
        row.appendChild(sceneBtn("Cancel", "Wijzigingen verwerpen", () => loadScenes()));
        editor.appendChild(row);
        left.replaceChildren(editor);
        ta.focus();
      }));
    // Eindbeeld-knop verwijderd (2026-06-14): eindframes zijn pipeline-breed uit
    // (zie PipelineOrchestrator.endFrameEnabled) omdat de start→eind-interpolatie
    // karaktermorphing veroorzaakte. Veo draait overal start-only.
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
    // ✂️ Inkorten — kies een in- en uit-punt binnen de 10s-clip met twee schuifjes.
    // "Toepassen" slaat op via /trim; zichtbaar in de film na Re-assemble.
    {
      const CLIP = 10;                       // Omni-clips zijn 10s
      let inV = Math.min(CLIP - 2, Math.max(0, Math.round(s.trimStartSeconds || 0)));
      let outV = Math.max(inV + 2, Math.min(CLIP, Math.round(s.trimEndSeconds || CLIP)));
      const wrap = document.createElement("div");
      wrap.className = "scene-trim small";
      wrap.style.cssText = "display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin-top:4px";
      const lbl = document.createElement("span");
      lbl.textContent = "✂️ Inkorten:";
      const inR = document.createElement("input");
      inR.type = "range"; inR.min = "0"; inR.max = String(CLIP); inR.step = "1";
      inR.value = String(inV); inR.title = "in-punt"; inR.style.width = "90px";
      const outR = document.createElement("input");
      outR.type = "range"; outR.min = "0"; outR.max = String(CLIP); outR.step = "1";
      outR.value = String(outV); outR.title = "uit-punt"; outR.style.width = "90px";
      const read = document.createElement("span");
      read.style.cssText = "min-width:150px;font-variant-numeric:tabular-nums";
      const sync = () => { read.textContent = `in ${inV}s · uit ${outV}s · lengte ${outV - inV}s`; };
      inR.addEventListener("input", () => {
        inV = parseInt(inR.value, 10);
        if (inV > outV - 2) { outV = Math.min(CLIP, inV + 2); outR.value = String(outV); }
        sync();
      });
      outR.addEventListener("input", () => {
        outV = parseInt(outR.value, 10);
        if (outV < inV + 2) { inV = Math.max(0, outV - 2); inR.value = String(inV); }
        sync();
      });
      sync();
      const apply = sceneBtn("Toepassen", "Sla het in/uit-punt op (zichtbaar na Re-assemble)",
        async () => {
          await P("trim", { startSec: inV, endSec: outV });
          toast(`Scène ${seq} ingekort naar ${inV}-${outV}s. Druk op Re-assemble om het toe te passen.`, "info");
          loadScenes();
        });
      wrap.append(lbl, inR, read, outR, apply);
      acts.appendChild(wrap);
    }
    {
      // Per-scène motion-model (dropdown) + clip maken/vernieuwen. Toont nu
      // óók bij scènes ZONDER clip — zo upgrade je een Ken Burns-fallback
      // (bijv. na een cost-cap-afkapping) alsnog naar een echte clip.
      const modelSel = document.createElement("select");
      modelSel.className = "scene-model-sel";
      modelSel.title = "Model voor deze re-roll";
      [["googleflow_omni", "Model: GoogleFlow Omni (Flow — handmatige clips)"],
       ["", "Model: Veo Fast (720p, ~€0,10/s)"],
       ["veo3_1_lite", "Model: Veo Lite (720p, ~€0,05/s)"],
       ["veo3_1", "Model: Veo Premium (1080p, ~€0,40/s)"],
       ["seedance2_fast", "Model: Seedance Fast (fal.ai, ~€0,10/s)"],
       ["seedance2", "Model: Seedance 2.0 (fal.ai, 1080p, ~€0,25/s)"]].forEach(([v, l]) => {
        const o = document.createElement("option");
        o.value = v; o.textContent = l;
        modelSel.appendChild(o);
      });
      // GoogleFlow Omni is de default-keuze (zelfde als het create-formulier) —
      // direct geselecteerd zodat reroll/maak-clip standaard de Flow-workflow volgt.
      modelSel.value = "googleflow_omni";
      acts.appendChild(modelSel);

      acts.appendChild(sceneItem(s.hasClip ? "🎬 Reroll clip" : "🎬 Maak clip",
        s.hasClip
          ? "Maakt ALLEEN de clip van deze scène opnieuw (≈1 clip-kost) vanaf het HUIDIGE startbeeld. Hermonteert NIET automatisch — druk daarna zelf op Re-assemble (zo kun je eerst meerdere clips maken). Kies links het model — Veo (Lite/Fast/Premium) of Seedance 2.0 via fal.ai. Ideaal om providers per scène te A/B'en."
          : "Deze scène heeft nog GEEN clip (Ken Burns-fallback, bijv. door de cost-cap). Genereert er alsnog één (≈1 clip-kost, ~€0,60 op Fast) vanaf het huidige startbeeld. Hermonteert NIET automatisch — druk daarna zelf op Re-assemble.",
        async () => {
          const m = modelSel.value;
          await api.post(
            `/api/v1/videos/${id}/scenes/${seq}/reroll-veo${m ? "?model=" + encodeURIComponent(m) : ""}`,
            undefined, { key: `reroll-veo-${seq}` });
          loadScenes();
          toast("Clip gemaakt — de video is NIET hermonteerd. Druk op Re-assemble als je klaar bent met clips maken.", "info");
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
    // 🚨 Clip-QC afgekeurd → rode omranding + alert (de details staan al in de
    // QC-box rechts; dit maakt de scène in de lijst onmiskenbaar).
    if (s.hasRejectedClip) {
      markSceneFailed(row, "Clip afgekeurd door de clip-QC — vervangen door een still (zie details rechts).");
    }
    list.appendChild(row);
  }
  scenesHost.replaceChildren(list);
  renderMontageSection(scenes);
}

// De hele montage in één sectie (Auke): het montage-paneel (muziek + actie) bovenaan
// en daaronder de filmrol (clip-tijdlijn met inkorten + overgangen). Verhuisd uit
// het scènes-blok zodat alle montage-controls bij de Montage-stap zitten.
function renderMontageSection(scenes) {
  if (!montageHost) return;
  lastScenesArr = scenes;
  if (!scenes || !scenes.length) { montageHost.textContent = "—"; return; }
  const wrap = document.createDocumentFragment();
  const mp = montagePanel();
  if (mp) wrap.appendChild(mp);
  wrap.appendChild(filmReel(scenes));
  montageHost.replaceChildren(wrap);
}

// STABIEL TUSSEN DE POLLS: de 5s-refresh herbouwde elke tick álle scène-rijen,
// wat de <img>/<video>-miniaturen liet herladen (zichtbaar geflikker — vooral nu
// de preview een frame uit de clip is). We renderen alleen opnieuw als de scène-
// data écht veranderde (zelfde truc als de review-gate). Een geopend trim-/
// overgang-/muziek-control blijft zo ook staan tijdens een poll.
let lastScenesSig = null;
function scenesSignature(scenes) {
  const st = lastJob ? lastJob.status : "";
  return st + "|" + (scenes || []).map(s => [
    s.seq, s.hasClip ? 1 : 0, s.imageVersion || "", s.locked ? 1 : 0,
    s.trimStartSeconds || 0, s.trimEndSeconds || "", s.transitionType || "",
    s.transitionSeconds || "", s.hasRejectedClip ? 1 : 0, s.silentBeat ? 1 : 0
  ].join(",")).join(";");
}

async function loadScenes() {
  try {
    const scenes = await api.get(`/api/v1/videos/${id}/scenes`, { key: "scenes-" + id });
    const sig = scenesSignature(scenes);
    // Ongewijzigd én al getekend → niet opnieuw renderen (geen flikker).
    if (sig === lastScenesSig && scenesHost.childElementCount > 0) return;
    lastScenesSig = sig;
    renderScenes(scenes);
    annotateQcFindings();
  } catch (e) {
    if (e.name === "AbortError") return;
    scenesHost.textContent = "could not load scenes (see toast)";
  }
}

// 🛡 Per-scene QC badges: shows which scenes the vision-QC flagged and from
// which checker (scene-qc / clip-qc / auto-fix) — so the reviewer knows where
// the machine already looked and what it found.
async function annotateQcFindings() {
  try {
    const findings = await api.get(`/api/v1/videos/${id}/qc-findings`, { key: "qcf-" + id });
    if (!Array.isArray(findings) || !findings.length) return;
    const bySeq = new Map();
    for (const f of findings) {
      if (f.seq == null) continue;
      if (!bySeq.has(f.seq)) bySeq.set(f.seq, []);
      bySeq.get(f.seq).push(f);
    }
    for (const [seq, list] of bySeq) {
      const slot = scenesHost.querySelector(`[data-qc-seq="${seq}"]`);
      if (!slot) continue;
      slot.style.cssText = "color:#b8651f;margin:2px 0";
      const srcs = [...new Set(list.map(f => f.source))].join(", ");
      // INFORMATIEF LOG, GEEN ACTUELE AFKEURING: /qc-findings is de historie van
      // elke QC-vlag tijdens het verwerken — inclusief vlaggen die de auto-fix
      // daarna heeft opgelost. Daarom NIET rood markeren (dat liet bijna elke
      // scène als "gezakt" ogen, feedback 2026-06-13). De rode "niet door de
      // controle"-markering blijft voorbehouden aan een echt huidig probleem:
      // een door de clip-QC afgekeurde clip (s.hasRejectedClip, in renderScenes).
      slot.textContent = `🛡 QC-log: ${list.length} bevinding(en) tijdens verwerken [${srcs}] — ` +
          (list[0].issue || list[0].category || "");
      slot.title = "Historie van QC-vlaggen tijdens het verwerken (kan al opgelost zijn):\n"
          + list.map(f => `[${f.source}/${f.category}] ${f.issue}`).join("\n");
    }
  } catch (e) { /* badges zijn informatief — stil falen */ }
}

// 🚨 Mark a scene that did NOT pass control: a loud red outline + an alert
// banner pinned to the top of the scene row, so flagged scenes are impossible
// to miss. Idempotent and additive — safe to call from both the synchronous
// render (rejected clips) and the async QC-findings pass; a scene flagged by
// both gets one banner with both reasons listed.
function markSceneFailed(row, message) {
  if (!row) return;
  // Red outline wins over the hero/silent-beat gold frames.
  row.style.border = "2px solid #c0392b";
  row.style.borderRadius = "10px";
  row.style.padding = "10px";
  row.style.boxShadow = "0 0 12px rgba(192,57,43,.35)";
  row.style.background = "linear-gradient(rgba(192,57,43,.06),rgba(192,57,43,.02))";
  let alert = row.querySelector(".scene-fail-alert");
  if (!alert) {
    alert = document.createElement("div");
    alert.className = "scene-fail-alert";
    alert.setAttribute("role", "alert");
    alert.style.cssText = "padding:6px 10px;margin-bottom:6px;border:1px solid #c0392b;" +
        "border-radius:6px;background:rgba(192,57,43,.12);color:#c0392b;font-size:12px";
    const head = document.createElement("div");
    head.style.cssText = "font-weight:700;margin-bottom:2px";
    head.textContent = "🚨 Niet door de controle";
    alert.appendChild(head);
    row.insertBefore(alert, row.firstChild);
  }
  // Skip duplicate reason lines (the same pass can re-render on the 5s poll).
  const exists = [...alert.querySelectorAll(".scene-fail-line")]
      .some(el => el.dataset.msg === message);
  if (!exists) {
    const line = document.createElement("div");
    line.className = "scene-fail-line";
    line.dataset.msg = message;
    line.textContent = "• " + message;
    alert.appendChild(line);
  }
}

// Stap-focus: open de sectie die bij de huidige pipelinefase hoort, één keer
// per statuswisseling — daarna blijven handmatige open/dicht-keuzes van de
// gebruiker staan (de 5s-poll mag die niet overschrijven).
function applyStepFocus(status) {
  const script = document.getElementById("step-script");
  const montage = document.getElementById("step-montage");
  const review = document.getElementById("step-review");
  if (!script || !review) return;
  const sig = String(status || "");
  if (document.body.dataset.stepSig === sig) return;
  document.body.dataset.stepSig = sig;
  // De secties sluiten elkaar uit (Auke): in de scenes-fase de Montage-sectie
  // helemaal VERBERGEN (niet alleen inklappen) en omgekeerd. Een stap-klik haalt
  // een verborgen sectie weer tevoorschijn (zie renderStepper). Scenes → toon
  // alleen Scenes; Montage/Veo → alleen Montage; daarna → alleen Review.
  const scriptPhases = /^(PENDING|SCRIPT_|ASSETS_|IMAGES_)/;
  const montagePhases = /^(VEO_|MONTAGE_)/;
  const show = (el, on) => { if (!el) return; el.style.display = on ? "" : "none"; el.open = on; };
  if (scriptPhases.test(sig)) {
    show(script, true);  show(montage, false); show(review, false);
  } else if (montagePhases.test(sig)) {
    show(script, false); show(montage, true);  show(review, false);
  } else {
    show(script, false); show(montage, false); show(review, true);
  }
}

// Staat de gebruiker NU in een invoerveld? De 5s-poll herbouwt scène- en
// review-kaarten compleet (replaceChildren) en zou een open editor + getypte
// tekst wegblazen. Zolang een tekstveld/textarea/select/contenteditable focus
// heeft, slaan we de verversing van die kaarten over (de status-tijd updaten
// blijft prima). Zodra je het veld verlaat of submit, hervat de poll vanzelf.
function isUserEditing() {
  const el = document.activeElement;
  if (!el) return false;
  const tag = el.tagName;
  if (tag === "TEXTAREA" || tag === "SELECT") return true;
  if (tag === "INPUT") {
    const t = (el.type || "text").toLowerCase();
    // checkboxes/radio/buttons mogen de poll niet blokkeren — alleen tekstinvoer.
    return !["checkbox", "radio", "button", "submit", "range", "file"].includes(t);
  }
  return el.isContentEditable === true;
}

async function load() {
  if (!id) {
    statusLine.textContent = "no job id in URL";
    return;
  }
  // Bevries de verversing tijdens het typen — anders verdwijnt je tekst.
  if (isUserEditing()) {
    statusLine.textContent = "bezig met bewerken — verversen gepauzeerd";
    return;
  }
  try {
    const job = await api.get(`/api/v1/videos/${id}`, { key: "job-" + id });
    renderJob(job);
    applyStepFocus(job.status);
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

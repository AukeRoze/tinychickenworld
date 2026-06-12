/*
 * Brand page (/ui/brand.html) — preview the current intro & outro clips and
 * rebuild them. Streams from GET /api/v1/{intro,outro}/current.mp4; rebuild
 * polls /status and reloads the player (cache-busted) when it finishes.
 */
import api, { toast } from "/assets/js/api.js";

function wire(kind, videoId, btnId, statusId, recompositeBtnId) {
  const video = document.getElementById(videoId);
  const btn = document.getElementById(btnId);
  const st = document.getElementById(statusId);
  const recomp = recompositeBtnId ? document.getElementById(recompositeBtnId) : null;
  const modelSel = document.getElementById(`${kind}-model`);   // Veo model select (optional)

  async function refreshStatus() {
    try {
      const s = await api.get(`/api/v1/${kind}/status`, { key: kind + "-status" });
      if (st && s) st.textContent = s.status || "";
      if (recomp) recomp.disabled = !(s && s.hasClip);   // needs a cached clip
      return s;
    } catch (e) { return null; }
  }

  function pollUntilDone(activeBtn) {
    const poll = setInterval(async () => {
      const s = await refreshStatus();
      if (s && !s.running) {
        clearInterval(poll);
        if (activeBtn) activeBtn.disabled = false;
        video.src = `/api/v1/${kind}/current.mp4?t=` + Date.now();
        video.load();
        toast(`${kind} updated`, "info");
      }
    }, 3000);
  }

  btn.addEventListener("click", async () => {
    const model = modelSel && modelSel.value ? modelSel.value : "";
    const modelLabel = model
        ? modelSel.options[modelSel.selectedIndex].text
        : "auto (bible-routing)";
    const ok = confirm(
      `⚠️  Rebuild the ${kind.toUpperCase()}?\n\n` +
      `Veo model: ${modelLabel}\n\n` +
      `This REPLACES the current ${kind} clip that is added to EVERY video on the ` +
      `channel. It regenerates via Veo + assembly (costs time and Veo credits), and ` +
      `the existing ${kind} is overwritten — there is no undo.\n\n` +
      `Continue and rebuild the ${kind}?`);
    if (!ok) return;
    btn.disabled = true;
    try {
      const qs = model ? `?model=${encodeURIComponent(model)}` : "";
      await api.post(`/api/v1/${kind}/rebuild${qs}`, undefined, { key: kind + "-rebuild" });
      toast(`${kind} rebuild started`, "info");
      pollUntilDone(btn);
    } catch (e) {
      btn.disabled = false; /* api.js toasted */
    }
  });

  if (recomp) {
    recomp.addEventListener("click", async () => {
      const ok = confirm(
        `Re-composite the ${kind.toUpperCase()} title + sound onto the EXISTING clip?\n\n` +
        `No Veo cost — it just re-applies the title/SFX and overwrites ${kind}.mp4.`);
      if (!ok) return;
      recomp.disabled = true;
      try {
        await api.post(`/api/v1/${kind}/recomposite`, undefined, { key: kind + "-recomp" });
        toast(`${kind} re-composite started`, "info");
        pollUntilDone(recomp);
      } catch (e) {
        recomp.disabled = false; /* api.js toasted */
      }
    });
  }

  // Hide the player if there's no clip yet.
  video.addEventListener("error", () => { video.style.display = "none"; });
  refreshStatus();
}

wire("intro", "intro-video", "rebuild-intro", "intro-status", "recomposite-intro");
wire("outro", "outro-video", "rebuild-outro", "outro-status", "recomposite-outro");

// ── Bible hot-reload (stack-breed) ───────────────────────────────────────
// POST /api/v1/brand/bible/reload → per-service resultaat. Voor handmatige
// channel.yml-edits; Cast-edits triggeren de reload al automatisch.
(() => {
  const btn = document.getElementById("reload-bible");
  const st = document.getElementById("reload-bible-status");
  if (!btn) return;
  btn.addEventListener("click", async () => {
    btn.disabled = true;
    if (st) st.textContent = "herladen…";
    try {
      const r = await api.post("/api/v1/brand/bible/reload", undefined, { key: "bible-reload" });
      const entries = Object.entries(r || {});
      const failed = entries.filter(([, v]) => v !== true).map(([k, v]) => `${k}: ${v}`);
      if (failed.length === 0) {
        if (st) st.textContent = `✓ ${entries.length} services herladen`;
        toast("Bible herladen op alle services", "info");
      } else {
        if (st) st.textContent = `⚠ ${failed.join(" · ")}`;
        toast("Bible deels herladen — zie status", "warn");
      }
    } catch (e) {
      if (st) st.textContent = "mislukt";
      /* api.js toonde de fout al */
    } finally {
      btn.disabled = false;
    }
  });
})();

// ── Audio previews: music library + ambient loops ───────────────────────
// One row per track with a native <audio> player (preload=none so the page
// stays light; the file streams on first play). Missing files are flagged so
// a deleted-but-still-registered track is visible instead of silently broken.

const MOOD_ICON = { energetic: "⚡", thoughtful: "💭", calm: "🌙" };

function audioRow(host, label, src, missing) {
  const row = document.createElement("div");
  row.style.cssText = "display:flex;align-items:center;gap:10px;margin:6px 0";
  const name = document.createElement("span");
  name.className = "mono";
  name.style.cssText = "width:230px;flex:none";
  name.textContent = label;
  row.appendChild(name);
  if (missing) {
    const warn = document.createElement("span");
    warn.textContent = "⚠ bestand ontbreekt (wel geregistreerd)";
    warn.style.color = "#b91c1c";
    row.appendChild(warn);
  } else {
    const audio = document.createElement("audio");
    audio.controls = true;
    audio.preload = "none";
    audio.src = src;
    audio.style.cssText = "height:30px;flex:1;max-width:420px";
    // Pauzeer andere spelers zodra deze start — één track tegelijk.
    audio.addEventListener("play", () => {
      document.querySelectorAll("audio").forEach(a => { if (a !== audio) a.pause(); });
    });
    row.appendChild(audio);
  }
  host.appendChild(row);
}

async function loadAudio() {
  const musicHost = document.getElementById("music-host");
  const ambientHost = document.getElementById("ambient-host");
  if (!musicHost || !ambientHost) return;
  try {
    const tracks = await api.get("/api/v1/brand/music", { key: "brand-music" });
    musicHost.textContent = tracks.length ? "" : "Geen tracks geregistreerd.";
    const order = { energetic: 0, thoughtful: 1, calm: 2 };
    tracks.sort((a, b) => (order[a.mood] ?? 9) - (order[b.mood] ?? 9) || a.id.localeCompare(b.id));
    for (const t of tracks) {
      audioRow(musicHost, `${MOOD_ICON[t.mood] || "🎵"} ${t.id} · ${t.mood}`,
               `/api/v1/brand/music/${encodeURIComponent(t.id)}.mp3`, !t.present);
    }
  } catch (e) { musicHost.textContent = "Kon muziek niet laden."; }
  try {
    const loops = await api.get("/api/v1/brand/ambient", { key: "brand-ambient" });
    ambientHost.textContent = loops.length ? "" : "Nog geen ambient-loops (generate-ambient.py).";
    for (const id of loops) {
      audioRow(ambientHost, `🌿 ${id}`, `/api/v1/brand/ambient/${encodeURIComponent(id)}.mp3`, false);
    }
  } catch (e) { ambientHost.textContent = "Kon ambient niet laden."; }
}

loadAudio();

// ── 📺 YouTube branding (logo + banner uit de cast-refs) ─────────────────
// Checkbox-rij van de cast (GET /api/v1/brand/cast; default: main + sidekicks
// aan, baby uit; keuze persistent in localStorage "brandingCast.v1" — bewust
// niet in de bible), genereren via POST /api/v1/brand/branding/generate
// (kind: logo|banner, characters: [ids] → de provider ankert op ál hun refs),
// kandidaten-grid met approve per kind. Na een banner-approve verschijnt
// "⬆ Upload banner naar YouTube" (channelBanners.insert + channels.update via
// de upload-service). Het LOGO kan niet via de API: YouTube heeft geen
// endpoint voor de profielfoto — vandaar de ⬇ Download-link + Studio-uitleg.
(() => {
  const castHost = document.getElementById("branding-cast");
  const candHost = document.getElementById("branding-candidates");
  const approvedHost = document.getElementById("branding-approved");
  const stEl = document.getElementById("branding-status");
  const genLogoBtn = document.getElementById("branding-gen-logo");
  const genBannerBtn = document.getElementById("branding-gen-banner");
  if (!castHost || !candHost || !genLogoBtn || !genBannerBtn) return;

  const LS_KEY = "brandingCast.v1";
  const CANDIDATE_COUNT = 3;
  const fileUrl = (name) => `/api/v1/brand/branding/file?name=${encodeURIComponent(name)}`;

  function selectedIds() {
    return [...castHost.querySelectorAll("input[type=checkbox]:checked")].map(c => c.value);
  }

  function saveSelection() {
    try { localStorage.setItem(LS_KEY, JSON.stringify(selectedIds())); } catch (e) { /* private mode */ }
  }

  function loadSelection() {
    try {
      const raw = localStorage.getItem(LS_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }

  async function loadCast() {
    try {
      const cast = await api.get("/api/v1/brand/cast", { key: "branding-cast" });
      const stored = loadSelection();   // null = eerste bezoek → role-default
      castHost.replaceChildren();
      for (const c of cast) {
        const label = document.createElement("label");
        label.style.cssText = "display:inline-flex;align-items:center;gap:5px;margin-right:14px;cursor:pointer";
        const cb = document.createElement("input");
        cb.type = "checkbox";
        cb.value = c.id;
        // Default: hoofdcast aan (main + sidekicks), baby/bijrollen uit.
        cb.checked = stored ? stored.includes(c.id)
                            : (c.role === "main" || c.role === "sidekick");
        cb.addEventListener("change", saveSelection);
        label.appendChild(cb);
        const txt = document.createElement("span");
        txt.textContent = `${c.name || c.id} (${c.role || "?"})`;
        label.appendChild(txt);
        castHost.appendChild(label);
      }
      if (!cast.length) castHost.textContent = "Geen cast gevonden in de bible.";
    } catch (e) { castHost.textContent = "Kon de cast niet laden."; }
  }

  function approveLabel(kind) {
    return kind === "logo" ? "✓ Gebruik als avatar (800×800)"
                           : "✓ Gebruik als banner (2560×1440)";
  }

  function renderCandidates(list) {
    candHost.replaceChildren();
    if (!list.length) return;
    const grid = document.createElement("div");
    grid.style.cssText = "display:flex;flex-wrap:wrap;gap:12px";
    for (const cand of list) {
      const cell = document.createElement("div");
      cell.style.cssText = "display:flex;flex-direction:column;align-items:center;gap:4px";
      const img = document.createElement("img");
      img.src = fileUrl(cand.file);
      img.title = cand.file + " — klik voor volledig formaat";
      // Logo-kandidaten als cirkel tonen: zo zie je de YouTube-crop alvast.
      img.style.cssText = cand.kind === "logo"
        ? "width:120px;height:120px;object-fit:cover;border-radius:50%;border:1px solid var(--border,#ccc);cursor:zoom-in"
        : "width:300px;aspect-ratio:16/9;object-fit:cover;border-radius:8px;border:1px solid var(--border,#ccc);cursor:zoom-in";
      img.addEventListener("click", () => window.open(img.src, "_blank"));
      cell.appendChild(img);
      const cap = document.createElement("span");
      cap.className = "small";
      cap.style.color = "var(--muted)";
      cap.textContent = cand.kind === "logo" ? "logo (cirkel-preview)" : "banner";
      cell.appendChild(cap);
      const ok = document.createElement("button");
      ok.className = "btn approve sm";
      ok.style.cssText = "font-size:10px;padding:1px 6px";
      ok.textContent = approveLabel(cand.kind);
      ok.addEventListener("click", async () => {
        const msg = cand.kind === "logo"
          ? "Deze kandidaat als kanaal-avatar opslaan (800×800, gecentreerde vierkant-crop)?\n\n" +
            "Let op: YouTube heeft géén API voor de profielfoto — je krijgt een " +
            "downloadlink en uploadt hem eenmalig handmatig via Studio."
          : "Deze kandidaat als kanaal-banner opslaan (2560×1440)?\n\n" +
            "Dit vervangt óók bible/youtube_banner.jpg — de cast-canon-referentie " +
            "(oude versie → youtube_banner.previous.jpg). Daarna kun je hem naar " +
            "YouTube uploaden.";
        if (!confirm(msg)) return;
        ok.disabled = true;
        try {
          const resp = await api.post("/api/v1/brand/branding/approve",
              { file: cand.file, kind: cand.kind }, { key: "branding-approve" });
          toast(resp && resp.note ? resp.note : `${cand.kind} goedgekeurd`, "info", 9000);
          await refreshCandidates();
        } catch (e) { ok.disabled = false; /* api.js toonde de fout al */ }
      });
      cell.appendChild(ok);
      grid.appendChild(cell);
    }
    candHost.appendChild(grid);
    const rejectAll = document.createElement("button");
    rejectAll.className = "btn sm";
    rejectAll.style.marginTop = "8px";
    rejectAll.textContent = "🗑 Weiger alles";
    rejectAll.addEventListener("click", async () => {
      if (!confirm("Alle branding-kandidaten weigeren en verwijderen?")) return;
      try {
        await api.del("/api/v1/brand/branding/candidates", { key: "branding-discard" });
        toast("Kandidaten verwijderd", "info");
        await refreshCandidates();
      } catch (e) { /* api.js toonde de fout al */ }
    });
    candHost.appendChild(rejectAll);
  }

  function renderApproved(avatarPresent, bannerPresent) {
    approvedHost.replaceChildren();
    if (avatarPresent) {
      const dl = document.createElement("a");
      dl.className = "btn sm";
      dl.textContent = "⬇ Download avatar.png";
      dl.href = fileUrl("avatar.png");
      dl.download = "avatar.png";
      approvedHost.appendChild(dl);
      const note = document.createElement("span");
      note.className = "small";
      note.style.color = "var(--muted)";
      note.textContent = "Profielfoto kan niet via de API — eenmalig handmatig: " +
          "studio.youtube.com → Aanpassing → Branding.";
      approvedHost.appendChild(note);
    }
    if (bannerPresent) {
      const up = document.createElement("button");
      up.className = "btn sm";
      up.textContent = "⬆ Upload banner naar YouTube";
      up.addEventListener("click", async () => {
        if (!confirm("Banner naar YouTube uploaden?\n\nDit VERVANGT de live " +
            "channel-art van het kanaal — er is geen undo (behalve opnieuw uploaden).")) return;
        up.disabled = true;
        up.textContent = "⏳ Uploaden…";
        try {
          const resp = await api.post("/api/v1/brand/branding/upload-banner",
              undefined, { key: "branding-upload-banner" });
          toast(resp && resp.note ? resp.note : "Banner live op YouTube ✓", "info", 9000);
        } catch (e) { /* api.js toonde de fout al */ }
        finally {
          up.disabled = false;
          up.textContent = "⬆ Upload banner naar YouTube";
        }
      });
      approvedHost.appendChild(up);
    }
  }

  async function refreshCandidates() {
    try {
      const data = await api.get("/api/v1/brand/branding/candidates", { key: "branding-cands" });
      renderCandidates((data && data.candidates) || []);
      renderApproved(!!(data && data.avatarPresent), !!(data && data.bannerPresent));
    } catch (e) { /* informatief — stil falen */ }
  }

  function wireGenerate(btn, kind) {
    btn.addEventListener("click", async () => {
      const ids = selectedIds();
      if (!ids.length) { toast("Selecteer minstens één personage", "warn"); return; }
      const ok = confirm(
        `${CANDIDATE_COUNT} ${kind}-kandidaten genereren met: ${ids.join(", ")}?\n\n` +
        `Elke kandidaat is een betaalde image-call (~€0,05-0,10) en de vorige ` +
        `${kind}-kandidaten worden vervangen. Generatie duurt ±1-2 min.`);
      if (!ok) return;
      btn.disabled = true;
      const oldText = btn.textContent;
      btn.textContent = "⏳ Genereren… (±1-2 min)";
      if (stEl) stEl.textContent = `${kind} genereren…`;
      try {
        const resp = await api.post("/api/v1/brand/branding/generate",
            { kind, characters: ids, count: CANDIDATE_COUNT }, { key: `branding-gen-${kind}` });
        if (resp && Array.isArray(resp.errors) && resp.errors.length) {
          toast(`Deels gelukt — ${resp.errors.length} kandidaat(en) mislukt`, "warn", 8000);
        } else {
          toast(`${kind}-kandidaten klaar`, "info");
        }
        await refreshCandidates();
      } catch (e) { /* api.js toonde de fout al */ }
      finally {
        btn.disabled = false;
        btn.textContent = oldText;
        if (stEl) stEl.textContent = "";
      }
    });
  }

  wireGenerate(genLogoBtn, "logo");
  wireGenerate(genBannerBtn, "banner");
  loadCast();
  refreshCandidates();
})();

// ── 🐔 Overlay-logo (bible/logo.png — het transparante intro/outro-asset) ──
// Preview op een geblokte donkere achtergrond (transparantie + evt. crème-halo
// meteen zichtbaar), multipart-upload naar POST /api/v1/brand/branding/
// logo-overlay (raw fetch — api.post forceert JSON, zelfde reden als
// cast-page.js uploadImage), en twee snelkoppelingen die DEZELFDE
// /recomposite-endpoints + confirm/poll-patronen gebruiken als wire() bovenin
// — eigen knoppen, geen programmatisch geklik op de bestaande.
(() => {
  const img = document.getElementById("overlay-logo-preview");
  const fileInput = document.getElementById("overlay-logo-file");
  const upBtn = document.getElementById("overlay-logo-upload");
  const st = document.getElementById("overlay-logo-status");
  if (!img || !fileInput || !upBtn) return;

  // Cache-buster: BrandController servet logo.png zonder no-store, en na een
  // vervanging moet de preview het NIEUWE bestand tonen.
  const refreshPreview = () => { img.src = "/api/v1/brand/logo.png?t=" + Date.now(); };
  img.addEventListener("error", () => { img.style.display = "none"; });
  refreshPreview();

  upBtn.addEventListener("click", async () => {
    const file = fileInput.files && fileInput.files[0];
    if (!file) { toast("Kies eerst een PNG-bestand", "warn"); return; }
    const ok = confirm(
      "⚠️  Overlay-logo vervangen?\n\n" +
      "Dit vervangt bible/logo.png — het logo dat in ÉLKE video gebakken wordt " +
      "(intro-fly-in + outro). De oude versie gaat naar logo.previous.png.\n\n" +
      "Daarna intro én outro re-compositen (gratis, geen Veo) om het nieuwe " +
      "logo in de bumpers te bakken — knoppen hiernaast.");
    if (!ok) return;
    upBtn.disabled = true;
    if (st) st.textContent = "uploaden…";
    try {
      const fd = new FormData();
      fd.append("file", file);
      const res = await fetch("/api/v1/brand/branding/logo-overlay", { method: "POST", body: fd });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
      img.style.display = "";          // kan verborgen zijn na een eerdere 404
      refreshPreview();
      fileInput.value = "";
      if (data.warning) toast("⚠ " + data.warning, "warn", 12000);
      toast(data.note || "Overlay-logo vervangen ✓", "info", 9000);
      if (st) st.textContent = "✓ vervangen — nu intro + outro re-compositen";
    } catch (e) {
      toast("Logo-upload mislukt: " + (e.message || e), "error", 9000);
      if (st) st.textContent = "";
    } finally {
      upBtn.disabled = false;
    }
  });

  // Snelkoppeling: zelfde POST + status-poll als de ♻-knoppen in wire(); de
  // gedeelde dedupe-keys ({kind}-recomp / {kind}-status) voorkomen dat beide
  // knoppen tegelijk dubbel werk starten.
  function wireRecompShortcut(kind, btnId) {
    const btn = document.getElementById(btnId);
    if (!btn) return;
    btn.addEventListener("click", async () => {
      const ok = confirm(
        `${kind.toUpperCase()} re-compositen met het huidige overlay-logo?\n\n` +
        `Geen Veo-kosten — logo/titel/SFX worden opnieuw op de bestaande clip ` +
        `gezet en ${kind}.mp4 wordt overschreven.`);
      if (!ok) return;
      btn.disabled = true;
      try {
        await api.post(`/api/v1/${kind}/recomposite`, undefined, { key: kind + "-recomp" });
        toast(`${kind} re-composite gestart`, "info");
        const poll = setInterval(async () => {
          try {
            const s = await api.get(`/api/v1/${kind}/status`, { key: kind + "-status" });
            if (s && !s.running) {
              clearInterval(poll);
              btn.disabled = false;
              const video = document.getElementById(`${kind}-video`);
              if (video) {
                video.src = `/api/v1/${kind}/current.mp4?t=` + Date.now();
                video.load();
              }
              toast(`${kind} updated`, "info");
            }
          } catch (e) { /* poll best-effort (of door wire() afgebroken) — volgende tick */ }
        }, 3000);
      } catch (e) { btn.disabled = false; /* api.js toonde de fout al */ }
    });
  }
  wireRecompShortcut("intro", "overlay-recomp-intro");
  wireRecompShortcut("outro", "overlay-recomp-outro");
})();

// ── Scène-overgangen editor (bible assembly.transitions, hot-reload ≤1 min) ──
async function loadTransitions() {
  const tHost = document.getElementById("transitions-host");
  if (!tHost) return;
  try {
    const data = await api.get("/api/v1/brand/transitions", { key: "brand-trans" });
    const phases = data.phases || {};
    const types = data.validTypes || [];
    tHost.replaceChildren();
    if (!Object.keys(phases).length) {
      tHost.textContent = "Geen assembly.transitions-sectie in channel.yml — de ingebouwde defaults gelden.";
      return;
    }
    for (const [phase, cfg] of Object.entries(phases)) {
      const row = document.createElement("div");
      row.style.cssText = "display:flex;align-items:center;gap:8px;margin:4px 0";
      const lbl = document.createElement("span");
      lbl.className = "mono small";
      lbl.style.cssText = "width:110px;display:inline-block";
      lbl.textContent = phase;
      row.appendChild(lbl);
      const sel = document.createElement("select");
      sel.className = "btn sm";
      for (const t of types) sel.appendChild(new Option(t, t, false, t === cfg.type));
      row.appendChild(sel);
      const sec = document.createElement("input");
      sec.type = "number";
      sec.min = "0.05"; sec.max = "1.5"; sec.step = "0.05";
      sec.value = cfg.seconds || 0.2;
      sec.style.cssText = "width:80px;padding:4px 8px;border:1px solid var(--border,#ccc);" +
          "border-radius:8px;background:var(--bg,#fff);color:inherit;font:inherit";
      row.appendChild(sec);
      const save = document.createElement("button");
      save.className = "btn sm";
      save.textContent = "💾";
      save.title = "Opslaan — actief binnen ±1 min";
      save.addEventListener("click", async () => {
        save.disabled = true;
        try {
          await api.post("/api/v1/brand/transitions",
              { phase, type: sel.value, seconds: Number(sec.value) }, { key: "trans-save" });
          toast(`Overgang '${phase}' → ${sel.value} ${sec.value}s ✓`, "info");
        } catch (e) { /* api.js toasted */ }
        finally { save.disabled = false; }
      });
      row.appendChild(save);
      tHost.appendChild(row);
    }
  } catch (e) { tHost.textContent = "Kon overgangen niet laden."; }
}
loadTransitions();

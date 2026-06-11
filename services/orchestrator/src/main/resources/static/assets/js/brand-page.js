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

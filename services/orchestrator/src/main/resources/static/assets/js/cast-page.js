/*
 * Cast overview + editor (/ui/cast.html). Shows the channel's recurring
 * characters and lets you tweak the "fun" knobs from the UI: name, role,
 * accessory (look) and tic (signature motion), plus upload a new reference
 * image. Edits write to the bible (channel.yml + refs/{id}.png) via
 * /api/v1/brand/cast/* and show here immediately; new RENDERS pick them up
 * after a restart of the orchestrator + image-service (they cache the bible).
 *
 * Naast uploaden kan een referentie ook AI-GEGENEREERD worden ("🎨 Genereer
 * nieuwe referentie"): POST .../generate-ref maakt 2-3 kandidaten vanuit de
 * actuele bible-DNA (tekst-only, zonder het oude referentiebeeld als anchor),
 * POST .../ref/approve promoveert er één naar refs/{id}.png en ruimt de stale
 * multi-angle refs + serie-anchors op.
 *
 * Data: GET /api/v1/brand/cast ; images: /api/v1/brand/character/{id}.png.
 */
import api, { toast } from "/assets/js/api.js";

const host = document.getElementById("cast-host");

/** Raw multipart upload (api.post forces JSON, so use fetch directly here). */
async function uploadImage(id, file) {
  const fd = new FormData();
  fd.append("file", file);
  const res = await fetch(`/api/v1/brand/cast/${encodeURIComponent(id)}/image`, { method: "POST", body: fd });
  if (!res.ok) {
    const t = await res.text().catch(() => "");
    throw new Error(`${res.status} ${t.slice(0, 200)}`);
  }
  return res.json().catch(() => ({}));
}

/** Hoeveel AI-kandidaten één "🎨 Genereer"-klik aanvraagt (max 4 server-side). */
const CANDIDATE_COUNT = 3;

function refImgUrl(id, file) {
  return `/api/v1/brand/cast/${encodeURIComponent(id)}/ref?file=` +
      encodeURIComponent(file) + "&t=" + Date.now();
}

/**
 * "🎨 Genereer nieuwe referentie"-flow: knop → confirm met kosten-indicatie →
 * spinner → kandidaat-thumbnails met "✓ Gebruik als referentie" (promoveert
 * naar refs/{id}.png en ruimt stale refs op) en "🗑 Weiger alles". De bestaande
 * upload-flow in ✎ Bewerken blijft hier los van bestaan. Een pending batch van
 * een eerdere sessie wordt bij het renderen van de kaart teruggetoond.
 */
function renderGenerateRef(c, host, refreshCard) {
  host.replaceChildren();

  const btn = document.createElement("button");
  btn.className = "btn sm";
  btn.textContent = "🎨 Genereer nieuwe referentie";
  btn.title = "Genereert " + CANDIDATE_COUNT + " AI-kandidaten vanuit de actuele bible-DNA " +
      "(tekst bepaalt de vorm — het oude referentiebeeld gaat NIET mee)";
  host.appendChild(btn);

  const strip = document.createElement("div");
  strip.style.cssText = "display:flex;gap:8px;flex-wrap:wrap;margin-top:6px;align-items:flex-start";
  host.appendChild(strip);

  function showCandidates(cands) {
    strip.replaceChildren();
    if (!Array.isArray(cands) || cands.length === 0) return;
    const lbl = document.createElement("div");
    lbl.className = "small";
    lbl.style.cssText = "flex-basis:100%;font-weight:600";
    lbl.textContent = "Kandidaten (pipeline negeert ze tot je er één goedkeurt):";
    strip.appendChild(lbl);
    for (const cand of cands) {
      const cell = document.createElement("div");
      cell.style.cssText = "display:flex;flex-direction:column;align-items:center;gap:2px";
      const img = document.createElement("img");
      img.loading = "lazy";
      img.style.cssText = "width:110px;height:110px;object-fit:cover;border-radius:8px;cursor:zoom-in";
      img.src = refImgUrl(c.id, cand.file);
      img.title = cand.file + " — klik voor volledig formaat";
      img.addEventListener("click", () => window.open(img.src, "_blank"));
      cell.appendChild(img);
      const ok = document.createElement("button");
      ok.className = "btn approve sm";
      ok.style.cssText = "font-size:10px;padding:1px 6px";
      ok.textContent = "✓ Gebruik als referentie";
      ok.addEventListener("click", async () => {
        if (!confirm(`Deze kandidaat promoveren tot canonieke referentie van ${c.name}?\n\n` +
            `Dit vervangt refs/${c.id}.png (oude versie → .bak), verwijdert de overige ` +
            `kandidaten, leegt de multi-angle map refs/${c.id}/ en verwijdert de ` +
            `serie-anchors — die dragen allemaal nog de oude look.`)) return;
        ok.disabled = true;
        try {
          const resp = await api.post(
              `/api/v1/brand/cast/${encodeURIComponent(c.id)}/ref/approve`,
              { file: cand.file }, { key: `approve-ref-${c.id}` });
          const cl = (resp && resp.cleaned) || {};
          toast(`Referentie van ${c.name} vervangen — opgeruimd: ${cl.candidates ?? 0} ` +
              `kandidaten, ${cl.staleAngles ?? 0} oude hoek-refs, ` +
              `${cl.seriesAnchors ?? 0} serie-anchor(s)`, "info", 8000);
          refreshCard();   // kaart verversen: nieuwe canonieke ref + lege strip
        } catch (e) {
          ok.disabled = false;   // api.post toont de serverfout al als toast
        }
      });
      cell.appendChild(ok);
      strip.appendChild(cell);
    }
    const rejectAll = document.createElement("button");
    rejectAll.className = "btn sm";
    rejectAll.style.cssText = "font-size:10px;padding:1px 6px;align-self:center";
    rejectAll.textContent = "🗑 Weiger alles";
    rejectAll.addEventListener("click", async () => {
      if (!confirm("Alle kandidaten weigeren en verwijderen? De huidige referentie blijft staan.")) return;
      try {
        await api.del(`/api/v1/brand/cast/${encodeURIComponent(c.id)}/ref/candidates`,
            { key: `discard-ref-${c.id}` });
        toast("Kandidaten verwijderd", "info");
        strip.replaceChildren();
      } catch (e) { /* api.del toont de fout al */ }
    });
    strip.appendChild(rejectAll);
  }

  // Pending batch van een eerdere sessie terugtonen (best-effort).
  api.get(`/api/v1/brand/cast/${encodeURIComponent(c.id)}/ref/candidates`,
      { key: `ref-cands-${c.id}` })
    .then(showCandidates)
    .catch(() => { /* informatief — stil falen */ });

  btn.addEventListener("click", async () => {
    if (!confirm(`${CANDIDATE_COUNT} AI-kandidaten genereren voor ${c.name}?\n\n` +
        `Kosten: ~€0,05-0,10 per kandidaat (~€0,15-0,30 totaal). De huidige referentie ` +
        `blijft staan tot je een kandidaat goedkeurt.`)) return;
    btn.disabled = true;
    const oldText = btn.textContent;
    btn.textContent = "⏳ Genereren… (±1-2 min)";
    try {
      const resp = await api.post(
          `/api/v1/brand/cast/${encodeURIComponent(c.id)}/generate-ref`,
          { count: CANDIDATE_COUNT }, { key: `gen-ref-${c.id}` });
      if (resp && Array.isArray(resp.errors) && resp.errors.length) {
        toast(`Deels gelukt — ${resp.errors.length} kandidaat/kandidaten mislukt`, "info", 7000);
      }
      showCandidates(resp ? resp.candidates : []);
    } catch (e) {
      /* api.post toont de serverfout al als toast */
    } finally {
      btn.disabled = false;
      btn.textContent = oldText;
    }
  });
}

function castImg(id, cls) {
  const img = document.createElement("img");
  img.className = cls;
  img.alt = id;
  img.loading = "lazy";
  img.src = `/api/v1/brand/character/${encodeURIComponent(id)}.png?t=` + Date.now();
  img.onerror = () => { img.style.visibility = "hidden"; };
  return img;
}

function field(label, value, opts = {}) {
  const wrap = document.createElement("label");
  wrap.className = "field";
  wrap.textContent = label;
  const input = opts.area ? document.createElement("textarea") : document.createElement("input");
  if (opts.area) input.rows = 2;
  input.value = value || "";
  wrap.appendChild(input);
  return { wrap, input };
}

function renderCard(c, reload) {
  const card = document.createElement("div");
  card.className = "card cast-card";

  function showView() {
    card.replaceChildren();
    card.appendChild(castImg(c.id, "cast-img"));
    const body = document.createElement("div");
    body.className = "cast-body";
    const h = document.createElement("h3");
    h.className = "card-title";
    h.textContent = c.name + (c.role ? ` · ${c.role}` : "");
    body.appendChild(h);
    const meta = (label, value) => {
      if (!value) return;
      const el = document.createElement("div");
      el.className = "small"; el.style.color = "var(--muted)";
      const b = document.createElement("b");
      b.textContent = label + ": ";
      el.appendChild(b);
      el.appendChild(document.createTextNode(value));
      body.appendChild(el);
    };
    meta("Accessoire", c.accessory);
    meta("Tic", c.tic);
    meta("Signatuurgeluid", c.signatureSound);
    // The "eigenaardigheden" — personality is the main quirk text (drives the script).
    if (c.personality) {
      const lbl = document.createElement("div");
      lbl.className = "small"; lbl.style.fontWeight = "600"; lbl.style.marginTop = "6px";
      lbl.textContent = "Eigenaardigheden";
      body.appendChild(lbl);
      const p = document.createElement("p");
      p.className = "small"; p.style.margin = "2px 0 0";
      p.textContent = c.personality;
      body.appendChild(p);
    }
    const phrases = [c.catchphrasesOpener, c.catchphrasesCloser].filter(Boolean).join(" · ").replace(/\n/g, " · ");
    meta("Catchphrases", phrases);
    if (c.description) {
      const lbl = document.createElement("div");
      lbl.className = "small"; lbl.style.fontWeight = "600"; lbl.style.marginTop = "6px";
      lbl.textContent = "Uiterlijk";
      body.appendChild(lbl);
      const d = document.createElement("p");
      d.className = "small"; d.style.margin = "2px 0 0";
      d.textContent = c.description;
      body.appendChild(d);
    }
    // ── Referentiebeelden — het pixel-anker van Veo én de QC ──
    // Deze stills gaan letterlijk mee in elke Veo-call (asset references) en
    // zijn de ground truth waar de vision-QC tegen keurt. Beoordeel ze dus
    // als canon: klopt er één niet, keur hem af (🗑 = hernoemen naar
    // .rejected, omkeerbaar) en upload/genereer een betere.
    {
      const lbl = document.createElement("div");
      lbl.className = "small";
      lbl.style.cssText = "font-weight:600;margin-top:10px";
      lbl.textContent = "Referentiebeelden (anker voor Veo + QC)";
      body.appendChild(lbl);
      const strip = document.createElement("div");
      strip.style.cssText = "display:flex;gap:8px;flex-wrap:wrap;margin-top:6px";
      body.appendChild(strip);
      api.get(`/api/v1/brand/cast/${encodeURIComponent(c.id)}/refs`, { key: `refs-${c.id}` })
        .then(refs => {
          if (!refs || !refs.length) {
            const warn = document.createElement("div");
            warn.className = "small";
            warn.style.color = "#b8651f";
            warn.textContent = "⚠ Geen referentiebeelden — dit character rendert ZONDER " +
                "pixel-anker. Genereer en keur refs vóór de volgende aflevering.";
            strip.appendChild(warn);
            return;
          }
          for (const r of refs) {
            const cell = document.createElement("div");
            cell.style.cssText = "display:flex;flex-direction:column;align-items:center;gap:2px";
            const img = document.createElement("img");
            img.loading = "lazy";
            img.style.cssText = "width:84px;height:84px;object-fit:cover;border-radius:8px;" +
                (r.active ? "" : "opacity:.35;filter:grayscale(1);");
            img.src = `/api/v1/brand/cast/${encodeURIComponent(c.id)}/ref?file=` +
                encodeURIComponent(r.file) + "&t=" + Date.now();
            img.title = r.file + " · " + r.kind + (r.active ? "" : " (genegeerd)");
            cell.appendChild(img);
            const cap = document.createElement("div");
            cap.className = "small mono";
            cap.style.cssText = "font-size:10px;color:var(--muted)";
            cap.textContent = r.kind === "canonical" ? "★ canoniek" : r.kind;
            cell.appendChild(cap);
            if (r.active) {
              const del = document.createElement("button");
              del.className = "btn sm";
              del.style.cssText = "font-size:10px;padding:1px 6px";
              del.textContent = "🗑 afkeuren";
              del.title = "Hernoemt naar .rejected (omkeerbaar) — Veo en QC gebruiken hem dan niet meer";
              del.addEventListener("click", async () => {
                if (!confirm(`Referentie '${r.file}' afkeuren? Veo en QC gebruiken hem dan niet meer.`)) return;
                try {
                  await fetch(`/api/v1/brand/cast/${encodeURIComponent(c.id)}/ref?file=` +
                      encodeURIComponent(r.file), { method: "DELETE" });
                  toast(`Referentie ${r.file} afgekeurd`, "info");
                  showView();   // refresh the strip
                } catch (e) { toast("Afkeuren mislukte", "error"); }
              });
              cell.appendChild(del);
            }
            strip.appendChild(cell);
          }
        })
        .catch(() => { /* strip is informatief — stil falen */ });
    }

    // ── 🎨 AI-referentie genereren (naast de upload-flow in ✎ Bewerken) ──
    {
      const genHost = document.createElement("div");
      genHost.style.marginTop = "8px";
      body.appendChild(genHost);
      renderGenerateRef(c, genHost, showView);
    }

    const editBtn = document.createElement("button");
    editBtn.className = "btn sm";
    editBtn.textContent = "✎ Bewerken";
    editBtn.style.marginTop = "8px";
    editBtn.addEventListener("click", showEdit);
    body.appendChild(editBtn);
    card.appendChild(body);
  }

  function showEdit() {
    card.replaceChildren();
    card.appendChild(castImg(c.id, "cast-img"));
    const body = document.createElement("div");
    body.className = "cast-body form-grid";

    const name = field("Naam", c.name);
    const role = field("Rol", c.role);
    const acc = field("Accessoire (look)", c.accessory, { area: true });
    const tic = field("Tic (signatuurbeweging)", c.tic, { area: true });
    const pers = field("Eigenaardigheden (persoonlijkheid — stuurt het script)", c.personality, { area: true });
    pers.input.rows = 6;
    const desc = field("Uiterlijk (beschrijving)", c.description, { area: true });
    desc.input.rows = 5;
    const opener = field("Catchphrases — opener (één per regel)", c.catchphrasesOpener, { area: true });
    opener.input.rows = 3;
    const closer = field("Catchphrases — closer (één per regel)", c.catchphrasesCloser, { area: true });
    closer.input.rows = 3;
    body.append(name.wrap, role.wrap, acc.wrap, tic.wrap, pers.wrap, desc.wrap, opener.wrap, closer.wrap);

    const fileLabel = document.createElement("label");
    fileLabel.className = "field";
    fileLabel.textContent = "Nieuw referentieplaatje (PNG)";
    const file = document.createElement("input");
    file.type = "file";
    file.accept = "image/png";
    fileLabel.appendChild(file);
    body.appendChild(fileLabel);

    const note = document.createElement("p");
    note.className = "small"; note.style.color = "var(--muted)";
    note.textContent = "Let op: wijzigingen zijn hier meteen zichtbaar, maar nieuwe video-renders " +
      "pakken ze pas op na een herstart van orchestrator + image-service.";
    body.appendChild(note);

    const row = document.createElement("div");
    row.className = "filter-row";
    const save = document.createElement("button");
    save.className = "btn approve sm";
    save.textContent = "Opslaan";
    save.addEventListener("click", async () => {
      save.disabled = true;
      try {
        await api.post(`/api/v1/brand/cast/${encodeURIComponent(c.id)}`, {
          name: name.input.value.trim(),
          role: role.input.value.trim(),
          accessory: acc.input.value.trim(),
          tic: tic.input.value.trim(),
          personality: pers.input.value.trim(),
          description: desc.input.value.trim(),
          catchphrasesOpener: opener.input.value,
          catchphrasesCloser: closer.input.value,
        }, { key: "cast-edit-" + c.id });
        if (file.files && file.files[0]) {
          await uploadImage(c.id, file.files[0]);
        }
        toast("Character bijgewerkt. Herstart orchestrator + image-service voor nieuwe renders.", "info");
        reload();
      } catch (e) {
        toast("Opslaan mislukt: " + e.message, "error");
        save.disabled = false;
      }
    });
    const cancel = document.createElement("button");
    cancel.className = "btn sm";
    cancel.textContent = "Annuleren";
    cancel.addEventListener("click", showView);
    row.append(save, cancel);
    body.appendChild(row);
    card.appendChild(body);
  }

  showView();
  return card;
}

/**
 * Re-fetch the cast and re-render the grid in place — a targeted refresh
 * instead of location.reload(), so the page doesn't flash and scroll position
 * survives an edit.
 */
async function load() {
  let cast;
  try {
    cast = await api.get("/api/v1/brand/cast", { key: "cast" });
  } catch (e) {
    host.textContent = "Could not load cast (see toast).";
    return;
  }
  if (!Array.isArray(cast) || cast.length === 0) {
    host.textContent = "No characters defined in the bible (channel.yml → characters:).";
    return;
  }
  const grid = document.createElement("div");
  grid.className = "cast-grid";
  for (const c of cast) grid.appendChild(renderCard(c, load));
  host.replaceChildren(grid);
}

load();

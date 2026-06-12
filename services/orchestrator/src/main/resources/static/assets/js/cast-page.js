/*
 * Cast overview + editor (/ui/cast.html). Shows the channel's recurring
 * characters and lets you tweak the "fun" knobs from the UI: name, role,
 * accessory (look) and tic (signature motion), plus upload a new reference
 * image. Edits write to the bible (channel.yml + refs/{id}.png) via
 * /api/v1/brand/cast/* and show here immediately; new RENDERS pick them up
 * after a restart of the orchestrator + image-service (they cache the bible).
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

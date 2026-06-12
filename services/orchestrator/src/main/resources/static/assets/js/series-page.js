/*
 * Series manager (/ui/series.html) — add / edit / delete the channel's
 * predefined series. Series live in the bible (channel.yml → series:); this
 * page reads them via GET /api/v1/series and writes via POST/DELETE
 * /api/v1/series[/ {id}] (BibleEditor, comment-preserving + auto-revert).
 * Changes show here + in the New Job dropdown at once; new scripts pick them
 * up after a script-service restart.
 */
import api, { toast } from "/assets/js/api.js";

const host = document.getElementById("series-host");

/* Targeted refresh: re-fetch + re-render the list instead of location.reload(),
   so the page doesn't flash and scroll position survives an edit. */
function reload() { load(); }

function field(label, value, area) {
  const wrap = document.createElement("label");
  wrap.className = "field";
  wrap.textContent = label;
  const input = area ? document.createElement("textarea") : document.createElement("input");
  if (area) input.rows = 2;
  input.value = value || "";
  wrap.appendChild(input);
  return { wrap, input };
}

function addForm() {
  const card = document.createElement("div");
  card.className = "card form-grid";
  const h = document.createElement("h3");
  h.className = "card-title";
  h.textContent = "➕ Nieuwe serie";
  card.appendChild(h);

  const name = field("Naam", "");
  const id = field("Id (optioneel — anders afgeleid van de naam)", "");
  const desc = field("Omschrijving", "", true);
  card.append(name.wrap, id.wrap, desc.wrap);

  const row = document.createElement("div");
  row.className = "filter-row";
  const add = document.createElement("button");
  add.className = "btn approve sm";
  add.textContent = "Toevoegen";
  add.addEventListener("click", async () => {
    const nm = name.input.value.trim();
    if (!nm && !id.input.value.trim()) { toast("Geef minstens een naam op.", "error"); return; }
    add.disabled = true;
    try {
      await api.post("/api/v1/series", {
        id: id.input.value.trim() || nm,
        name: nm,
        description: desc.input.value.trim(),
      }, { key: "series-add" });
      toast("Serie toegevoegd. Herstart script-service voor nieuwe scripts.", "info");
      reload();
    } catch (e) {
      toast("Toevoegen mislukt: " + e.message, "error");
      add.disabled = false;
    }
  });
  row.appendChild(add);
  card.appendChild(row);
  return card;
}

function seriesCard(s) {
  const card = document.createElement("div");
  card.className = "card";

  function showView() {
    card.replaceChildren();
    const h = document.createElement("h3");
    h.className = "card-title";
    h.textContent = s.name || s.id;
    card.appendChild(h);
    const id = document.createElement("div");
    id.className = "small mono"; id.style.color = "var(--muted)";
    id.textContent = s.id;
    card.appendChild(id);
    if (s.description) {
      const d = document.createElement("p");
      d.className = "small"; d.style.marginBottom = "8px";
      d.textContent = s.description;
      card.appendChild(d);
    }
    const row = document.createElement("div");
    row.className = "filter-row";
    const edit = document.createElement("button");
    edit.className = "btn sm";
    edit.textContent = "✎ Bewerken";
    edit.addEventListener("click", showEdit);
    const del = document.createElement("button");
    del.className = "btn sm reject";
    del.textContent = "🗑 Verwijderen";
    del.addEventListener("click", async () => {
      if (!confirm(`Serie "${s.name || s.id}" verwijderen uit de bible?`)) return;
      del.disabled = true;
      try {
        await api.del(`/api/v1/series/${encodeURIComponent(s.id)}`, { key: "series-del-" + s.id });
        toast("Serie verwijderd.", "info");
        reload();
      } catch (e) { toast("Verwijderen mislukt: " + e.message, "error"); del.disabled = false; }
    });
    row.append(edit, del);
    card.appendChild(row);
  }

  function showEdit() {
    card.replaceChildren();
    card.classList.add("form-grid");
    const idLbl = document.createElement("div");
    idLbl.className = "small mono"; idLbl.style.color = "var(--muted)";
    idLbl.textContent = s.id;
    card.appendChild(idLbl);
    const name = field("Naam", s.name);
    const desc = field("Omschrijving", s.description, true);
    desc.input.rows = 3;
    card.append(name.wrap, desc.wrap);
    const row = document.createElement("div");
    row.className = "filter-row";
    const save = document.createElement("button");
    save.className = "btn approve sm";
    save.textContent = "Opslaan";
    save.addEventListener("click", async () => {
      save.disabled = true;
      try {
        await api.post(`/api/v1/series/${encodeURIComponent(s.id)}`, {
          name: name.input.value.trim(),
          description: desc.input.value.trim(),
        }, { key: "series-edit-" + s.id });
        toast("Serie bijgewerkt. Herstart script-service voor nieuwe scripts.", "info");
        reload();
      } catch (e) { toast("Opslaan mislukt: " + e.message, "error"); save.disabled = false; }
    });
    const cancel = document.createElement("button");
    cancel.className = "btn sm";
    cancel.textContent = "Annuleren";
    cancel.addEventListener("click", () => { card.classList.remove("form-grid"); showView(); });
    row.append(save, cancel);
    card.appendChild(row);
  }

  showView();
  return card;
}

async function load() {
  let series;
  try {
    series = await api.get("/api/v1/series", { key: "series" });
  } catch (e) {
    host.textContent = "Could not load series (see toast).";
    return;
  }
  const wrap = document.createElement("div");
  wrap.appendChild(addForm());
  if (Array.isArray(series)) {
    for (const s of series) wrap.appendChild(seriesCard(s));
  }
  host.replaceChildren(wrap);
}

load();

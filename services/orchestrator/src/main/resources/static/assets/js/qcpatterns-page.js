/*
 * QC Patterns (/ui/qcpatterns.html) — recurring vision-QC findings.
 * Data: GET /api/v1/qc-patterns.
 */
import api from "/assets/js/api.js";

const host = document.getElementById("qc-host");

function countKind(n) { return n >= 5 ? "danger" : n >= 3 ? "warning" : "success"; }

(async function () {
  let patterns;
  try {
    patterns = await api.get("/api/v1/qc-patterns", { key: "qc-patterns" });
  } catch (e) {
    host.textContent = "Could not load QC patterns (see toast).";
    return;
  }
  patterns = Array.isArray(patterns) ? patterns : [];
  if (!patterns.length) {
    host.textContent = "No QC findings recorded yet — they appear after auto-QC / Auto-Fix flags a scene image.";
    return;
  }

  const table = document.createElement("table");
  table.className = "joblist";
  const thead = document.createElement("thead");
  const htr = document.createElement("tr");
  ["Count", "Category", "Character", "Most recent example"].forEach((l) => {
    const th = document.createElement("th"); th.textContent = l; htr.appendChild(th);
  });
  thead.appendChild(htr);
  table.appendChild(thead);

  const tbody = document.createElement("tbody");
  for (const p of patterns) {
    const tr = document.createElement("tr");

    const count = document.createElement("td");
    const badge = document.createElement("span");
    badge.className = "pill pill--" + countKind(p.count);
    badge.textContent = p.count + "×";
    count.appendChild(badge);
    tr.appendChild(count);

    const cat = document.createElement("td");
    cat.className = "mono small";
    cat.textContent = p.category || "—";
    tr.appendChild(cat);

    const ch = document.createElement("td");
    ch.className = "mono small";
    ch.textContent = p.character || "—";
    tr.appendChild(ch);

    const ex = document.createElement("td");
    ex.className = "small";
    ex.textContent = p.lastExample || "";
    tr.appendChild(ex);

    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  host.replaceChildren(table);

  // ── Lerende studio (loop 2): bible-fix-voorstellen ──
  // Terugkerende patronen boven de drempel worden door Claude vertaald naar
  // één concreet bible-veldvoorstel. Jij beoordeelt; Toepassen schrijft via de
  // BibleEditor (met backup + validatie). PR-stijl: het systeem stelt voor,
  // de mens merget.
  const sugHead = document.createElement("h2");
  sugHead.textContent = "Voorstellen — bible-fixes uit deze patronen";
  host.appendChild(sugHead);
  const sugHost = document.createElement("div");
  sugHost.className = "sub small";
  sugHost.textContent = "Voorstellen laden…";
  host.appendChild(sugHost);

  function renderSuggestions(suggestions) {
    sugHost.replaceChildren();
    if (!suggestions || !suggestions.length) {
      sugHost.textContent = "Geen voorstellen — geen patroon heeft de drempel " +
          "bereikt, of de huidige bible dekt ze al af.";
      return;
    }
    for (const s of suggestions) {
      const card = document.createElement("div");
      card.className = "card";
      card.style.marginBottom = "10px";
      const h = document.createElement("h3");
      h.className = "card-title";
      h.textContent = `${s.characterId} · ${s.field}  (${s.patternCount}× ${s.patternCategory})`;
      card.appendChild(h);
      const why = document.createElement("p");
      why.className = "small";
      why.textContent = "💡 " + (s.rationale || "");
      card.appendChild(why);
      const diff = document.createElement("div");
      diff.className = "small mono";
      diff.style.cssText = "display:grid;grid-template-columns:1fr 1fr;gap:8px";
      const before = document.createElement("div");
      before.style.cssText = "background:rgba(200,60,60,.08);padding:6px;border-radius:6px;white-space:pre-wrap";
      before.textContent = "− " + (s.currentValue || "(leeg)");
      const after = document.createElement("div");
      after.style.cssText = "background:rgba(60,160,60,.10);padding:6px;border-radius:6px;white-space:pre-wrap";
      after.textContent = "+ " + s.proposedValue;
      diff.appendChild(before);
      diff.appendChild(after);
      card.appendChild(diff);
      const row = document.createElement("div");
      row.style.cssText = "margin-top:8px;display:flex;gap:8px";
      const apply = document.createElement("button");
      apply.className = "btn approve sm";
      apply.textContent = "✓ Toepassen";
      apply.addEventListener("click", async () => {
        if (!confirm(`Bible-veld '${s.field}' van ${s.characterId} vervangen door het voorstel?`)) return;
        apply.disabled = true;
        try {
          await api.post("/api/v1/insights/bible-suggestions/apply",
              { characterId: s.characterId, field: s.field, value: s.proposedValue },
              { key: "bible-apply" });
          card.style.opacity = ".45";
          apply.textContent = "✓ Toegepast — herstart services voor effect";
        } catch (e) { apply.disabled = false; }
      });
      row.appendChild(apply);
      card.appendChild(row);
      sugHost.appendChild(card);
    }
  }

  try {
    renderSuggestions(await api.get("/api/v1/insights/bible-suggestions", { key: "bible-sug" }));
  } catch (e) {
    sugHost.textContent = "Voorstellen konden niet geladen worden.";
  }
  const refreshBtn = document.createElement("button");
  refreshBtn.className = "btn sm";
  refreshBtn.style.marginTop = "6px";
  refreshBtn.textContent = "↻ Voorstellen vernieuwen (LLM-call)";
  refreshBtn.addEventListener("click", async () => {
    refreshBtn.disabled = true;
    sugHost.textContent = "Opnieuw genereren…";
    try {
      renderSuggestions(await api.get("/api/v1/insights/bible-suggestions?refresh=true",
          { key: "bible-sug-r" }));
    } catch (e) { sugHost.textContent = "Vernieuwen mislukte."; }
    finally { refreshBtn.disabled = false; }
  });
  host.appendChild(refreshBtn);
})();

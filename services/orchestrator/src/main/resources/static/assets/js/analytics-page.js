/*
 * Analytics (/ui/analytics.html) — KPI totals, top-performer insights and a
 * per-video metrics table. Data: GET /api/v1/analytics; "Poll now" triggers the
 * existing POST /api/v1/analytics/poll.
 */
import api, { toast } from "/assets/js/api.js";

const host = document.getElementById("analytics-host");

function fmtNum(n) {
  if (n == null) return "—";
  return Number(n).toLocaleString();
}

function kpi(label, value) {
  const c = document.createElement("div");
  c.className = "kpi";
  const l = document.createElement("div"); l.className = "kpi-label"; l.textContent = label;
  const v = document.createElement("div"); v.className = "kpi-value"; v.textContent = value;
  c.appendChild(l); c.appendChild(v);
  return c;
}

function insightCard(title, items) {
  const card = document.createElement("div");
  card.className = "card";
  const h = document.createElement("h3"); h.className = "card-title"; h.textContent = title;
  card.appendChild(h);
  if (!items || !items.length) {
    const p = document.createElement("div"); p.className = "small"; p.style.color = "var(--muted)";
    p.textContent = "not enough data yet";
    card.appendChild(p);
    return card;
  }
  const dl = document.createElement("dl"); dl.className = "kv";
  for (const i of items) {
    const dt = document.createElement("dt"); dt.textContent = i.value || "—";
    const dd = document.createElement("dd"); dd.className = "mono small";
    dd.textContent = `${fmtNum(i.avgViews)} avg · n=${i.n}`;
    dl.appendChild(dt); dl.appendChild(dd);
  }
  card.appendChild(dl);
  return card;
}

function render(data) {
  const wrap = document.createElement("div");

  if (data.hint) {
    const hint = document.createElement("div");
    hint.className = "card";
    hint.style.borderLeft = "4px solid var(--primary)";
    hint.textContent = "🧠 Self-learning active — new scripts aim for: " + data.hint;
    wrap.appendChild(hint);
  }

  const kpis = document.createElement("div");
  kpis.className = "kpi-row";
  kpis.appendChild(kpi("📹 Videos uploaded", String(data.uploaded ?? 0)));
  kpis.appendChild(kpi("👁 Total views", fmtNum(data.totalViews)));
  kpis.appendChild(kpi("📊 Tracked snapshots", String(data.snapshots ?? 0)));
  wrap.appendChild(kpis);

  const grid = document.createElement("div");
  grid.className = "insights-grid";
  grid.appendChild(insightCard("🌅 Top moods", data.topMoods));
  grid.appendChild(insightCard("📖 Top lessons", data.topLessons));
  grid.appendChild(insightCard("📚 Top series", data.topSeries));
  grid.appendChild(insightCard("🎬 Top motion modes", data.topMotionModes));
  wrap.appendChild(grid);

  const h2 = document.createElement("h2"); h2.textContent = "Per video"; wrap.appendChild(h2);
  const table = document.createElement("table");
  table.className = "joblist clickable-rows";
  const thead = document.createElement("thead");
  const htr = document.createElement("tr");
  ["Topic", "Views", "Likes", "Comments", "Series", "Updated"].forEach((l) => {
    const th = document.createElement("th"); th.textContent = l; htr.appendChild(th);
  });
  thead.appendChild(htr);
  table.appendChild(thead);
  const tbody = document.createElement("tbody");
  const vids = data.videos || [];
  if (!vids.length) {
    const tr = document.createElement("tr");
    const td = document.createElement("td"); td.colSpan = 6; td.className = "small";
    td.style.color = "var(--muted)"; td.textContent = "No uploaded videos yet.";
    tr.appendChild(td); tbody.appendChild(tr);
  }
  for (const v of vids) {
    const tr = document.createElement("tr");
    tr.tabIndex = 0;
    const go = () => { location.href = "/ui/job.html?id=" + encodeURIComponent(v.id); };
    tr.addEventListener("click", go);
    tr.addEventListener("keydown", (e) => { if (e.key === "Enter") go(); });
    const cell = (txt, mono) => { const td = document.createElement("td"); if (mono) td.className = "mono small"; td.textContent = txt; return td; };
    tr.appendChild(cell(v.topic || "(no topic)"));
    tr.appendChild(cell(fmtNum(v.views), true));
    tr.appendChild(cell(v.likes == null ? "—" : v.likes, true));
    tr.appendChild(cell(v.comments == null ? "—" : v.comments, true));
    tr.appendChild(cell(v.series || "—", true));
    tr.appendChild(cell(v.updated ? new Date(v.updated).toLocaleString() : "—", true));
    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  wrap.appendChild(table);

  host.replaceChildren(wrap);
}

async function load() {
  try {
    render(await api.get("/api/v1/analytics", { key: "analytics" }));
  } catch (e) {
    host.textContent = "Could not load analytics (see toast).";
  }
}

document.getElementById("poll-now").addEventListener("click", async (e) => {
  const b = e.currentTarget;
  b.disabled = true;
  try {
    await api.post("/api/v1/analytics/poll", undefined, { key: "poll" });
    toast("Polled — refreshing", "info");
    await load();
  } catch (err) { /* toasted */ } finally { b.disabled = false; }
});

load();

/*
 * Quality (/ui/quality.html) — channel-wide QA averages. Data: GET /api/v1/quality.
 */
import api from "/assets/js/api.js";

const host = document.getElementById("quality-host");

function axisKind(avg) { return avg >= 8 ? "success" : avg >= 6 ? "warning" : "danger"; }

function bar(value, max, kind) {
  const track = document.createElement("div");
  track.className = "sbar";
  const fill = document.createElement("div");
  fill.className = "sbar-fill sbar-fill--" + kind;
  fill.style.width = Math.max(0, Math.min(100, (value / max) * 100)) + "%";
  track.appendChild(fill);
  return track;
}

(async function () {
  let data;
  try {
    data = await api.get("/api/v1/quality", { key: "quality" });
  } catch (e) {
    host.textContent = "Could not load quality (see toast).";
    return;
  }
  if (!data || !data.count) {
    host.textContent = "No QA-board scores yet — they appear once videos are assembled + audited.";
    return;
  }
  const min = data.publishMin ?? 80;
  const wrap = document.createElement("div");

  // Summary card.
  const sum = document.createElement("div");
  sum.className = "card";
  const badge = document.createElement("span");
  badge.className = "audit-score";
  badge.style.background = "var(--" + (data.avg >= min ? "success" : data.avg >= 60 ? "warning" : "danger") + ")";
  badge.textContent = `Avg ${data.avg}/100`;
  sum.appendChild(badge);
  const meta = document.createElement("span");
  meta.className = "small";
  meta.style.marginLeft = "8px";
  meta.textContent = `over ${data.count} scored video(s) · publish ≥ ${min}`;
  sum.appendChild(meta);
  wrap.appendChild(sum);

  // Per-axis averages (weakest first).
  if (Array.isArray(data.axes) && data.axes.length) {
    const card = document.createElement("div");
    card.className = "card";
    const h = document.createElement("h3");
    h.className = "card-title";
    h.textContent = "Average per axis (weakest first)";
    card.appendChild(h);
    const list = document.createElement("div");
    list.className = "axis-list";
    for (const a of data.axes) {
      const row = document.createElement("div");
      row.className = "axis-row";
      const name = document.createElement("span");
      name.className = "axis-name";
      name.textContent = a.name;
      const score = document.createElement("span");
      score.className = "axis-score small mono";
      score.textContent = `${a.avg.toFixed(1)}/10`;
      row.appendChild(name);
      row.appendChild(bar(a.avg, 10, axisKind(a.avg)));
      row.appendChild(score);
      list.appendChild(row);
    }
    card.appendChild(list);
    wrap.appendChild(card);
  }

  // Below publish threshold.
  const card = document.createElement("div");
  card.className = "card";
  const h = document.createElement("h3");
  h.className = "card-title";
  h.textContent = `Below publish threshold (${data.below ? data.below.length : 0})`;
  card.appendChild(h);
  if (data.below && data.below.length) {
    const dl = document.createElement("dl");
    dl.className = "kv";
    for (const j of data.below) {
      const dt = document.createElement("dt");
      const a = document.createElement("a");
      a.href = "/ui/job.html?id=" + encodeURIComponent(j.id);
      a.textContent = j.topic || j.id;
      dt.appendChild(a);
      const dd = document.createElement("dd");
      dd.className = "mono small";
      dd.textContent = `${j.score}/100`;
      dl.appendChild(dt);
      dl.appendChild(dd);
    }
    card.appendChild(dl);
  } else {
    const p = document.createElement("div");
    p.className = "small";
    p.style.color = "var(--muted)";
    p.textContent = "Every scored video is at or above the publish threshold 👍";
    card.appendChild(p);
  }
  wrap.appendChild(card);

  host.replaceChildren(wrap);
})();

/*
 * Distribution overview (/ui/distribution.html) — per finished video, which
 * platforms it's live on. Data: GET /api/v1/distribution.
 */
import api from "/assets/js/api.js";

const host = document.getElementById("dist-host");
const sub = document.getElementById("dist-sub");

function chip(on, label) {
  const s = document.createElement("span");
  s.className = "pill pill--" + (on ? "success" : "muted");
  s.textContent = label + (on ? " ✓" : " —");
  return s;
}

(async function () {
  let rows;
  try {
    rows = await api.get("/api/v1/distribution", { key: "distribution" });
  } catch (e) {
    host.textContent = "Could not load distribution (see toast).";
    return;
  }
  rows = Array.isArray(rows) ? rows : [];
  const needCross = rows.filter((r) => r.youtube && !r.facebook).length;
  if (sub) sub.textContent =
    `${rows.length} finished video(s) · ${needCross} waiting to be cross-posted. Open a video → Distribution to push to TikTok / Instagram / Facebook.`;

  if (!rows.length) {
    host.textContent = "No finished videos yet.";
    return;
  }

  const table = document.createElement("table");
  table.className = "joblist clickable-rows";
  const thead = document.createElement("thead");
  const htr = document.createElement("tr");
  ["Topic", "YouTube", "Facebook", ""].forEach((l) => {
    const th = document.createElement("th"); th.textContent = l; htr.appendChild(th);
  });
  thead.appendChild(htr);
  table.appendChild(thead);

  const tbody = document.createElement("tbody");
  for (const r of rows) {
    const tr = document.createElement("tr");
    tr.tabIndex = 0;
    const go = () => { location.href = "/ui/job.html?id=" + encodeURIComponent(r.id); };
    tr.addEventListener("click", go);
    tr.addEventListener("keydown", (e) => { if (e.key === "Enter") go(); });

    const topic = document.createElement("td");
    topic.textContent = r.topic || "(untitled)";
    tr.appendChild(topic);

    const yt = document.createElement("td"); yt.appendChild(chip(r.youtube, "YouTube")); tr.appendChild(yt);
    const fb = document.createElement("td"); fb.appendChild(chip(r.facebook, "Facebook")); tr.appendChild(fb);

    const flag = document.createElement("td");
    if (r.youtube && !r.facebook) {
      const s = document.createElement("span");
      s.className = "pill pill--warning";
      s.textContent = "cross-post";
      flag.appendChild(s);
    }
    tr.appendChild(flag);

    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  host.replaceChildren(table);
})();

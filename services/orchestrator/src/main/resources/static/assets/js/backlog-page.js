/*
 * Backlog (/ui/backlog.html) — jobs with no planned publish date and not yet
 * completed, i.e. work that still needs scheduling. Data: GET /api/v1/videos.
 */
import api from "/assets/js/api.js";

const host = document.getElementById("backlog-host");
const sub = document.getElementById("backlog-sub");

function statusKind(s) {
  if (!s) return "muted";
  if (s === "FAILED") return "danger";
  if (s === "COMPLETED") return "success";
  if (s.endsWith("_REVIEW_PENDING") || s === "DISTRIBUTION_PENDING") return "warning";
  return "primary";
}
function prettyStatus(s) {
  if (!s) return "—";
  const t = s.replace(/_/g, " ").toLowerCase();
  return t.charAt(0).toUpperCase() + t.slice(1);
}
function fmtWhen(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return isNaN(d) ? iso : d.toLocaleDateString();
}

(async function () {
  let jobs;
  try {
    jobs = await api.get("/api/v1/videos", { key: "jobs-list" });
  } catch (e) {
    host.textContent = "Could not load jobs (see toast).";
    return;
  }
  const backlog = (Array.isArray(jobs) ? jobs : [])
    .filter((j) => !j.plannedPublishAt && j.status !== "COMPLETED");

  if (sub) sub.textContent = `${backlog.length} job(s) without a planned publish date.`;
  if (!backlog.length) {
    host.textContent = "Nothing in the backlog — every job is scheduled or done 👍";
    return;
  }

  const table = document.createElement("table");
  table.className = "joblist clickable-rows";
  const thead = document.createElement("thead");
  const htr = document.createElement("tr");
  ["Topic", "Status", "Series", "Created"].forEach((l) => {
    const th = document.createElement("th"); th.textContent = l; htr.appendChild(th);
  });
  thead.appendChild(htr);
  table.appendChild(thead);

  const tbody = document.createElement("tbody");
  for (const j of backlog) {
    const tr = document.createElement("tr");
    tr.tabIndex = 0;
    const go = () => { location.href = "/ui/job.html?id=" + encodeURIComponent(j.id); };
    tr.addEventListener("click", go);
    tr.addEventListener("keydown", (e) => { if (e.key === "Enter") go(); });

    const topic = document.createElement("td");
    topic.textContent = j.topic || "(no topic)";
    tr.appendChild(topic);

    const status = document.createElement("td");
    const pill = document.createElement("span");
    pill.className = "pill pill--" + statusKind(j.status);
    pill.textContent = prettyStatus(j.status);
    status.appendChild(pill);
    tr.appendChild(status);

    const series = document.createElement("td");
    series.className = "small mono";
    series.textContent = j.seriesId
      ? j.seriesId + (j.episodeNumber != null ? " · Ep " + j.episodeNumber : "")
      : "—";
    tr.appendChild(series);

    const created = document.createElement("td");
    created.className = "small mono";
    created.textContent = fmtWhen(j.createdAt);
    tr.appendChild(created);

    tbody.appendChild(tr);
  }
  table.appendChild(tbody);
  host.replaceChildren(table);
})();

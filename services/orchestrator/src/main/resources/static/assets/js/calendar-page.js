/*
 * Calendar (/ui/calendar.html) — month grid of jobs by their planned publish
 * date. Data: GET /api/v1/videos (plannedPublishAt). Prev/Today/Next navigate
 * months client-side. Click a chip to open the job.
 */
import api from "/assets/js/api.js";

const host = document.getElementById("cal-host");
const title = document.getElementById("cal-title");
const MONTHS = ["January", "February", "March", "April", "May", "June",
                "July", "August", "September", "October", "November", "December"];

let view = new Date();           // first of the shown month
view.setDate(1);
let jobs = [];

function chipKind(s) {
  if (s === "COMPLETED") return "success";
  if (s === "FAILED") return "danger";
  if (s && (s.endsWith("_REVIEW_PENDING") || s === "DISTRIBUTION_PENDING")) return "warning";
  return "primary";
}

function render() {
  const y = view.getFullYear();
  const m = view.getMonth();                 // 0-based
  title.textContent = `📅 ${MONTHS[m]} ${y}`;

  // Jobs planned in this month, keyed by day-of-month.
  const byDay = {};
  for (const j of jobs) {
    if (!j.plannedPublishAt) continue;
    const d = new Date(j.plannedPublishAt);
    if (isNaN(d) || d.getFullYear() !== y || d.getMonth() !== m) continue;
    (byDay[d.getDate()] ||= []).push(j);
  }

  const firstDow = (new Date(y, m, 1).getDay() + 6) % 7;  // 0 = Monday
  const daysInMonth = new Date(y, m + 1, 0).getDate();
  const today = new Date();

  const grid = document.createElement("div");
  grid.className = "cal-grid";
  for (const h of ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]) {
    const head = document.createElement("div");
    head.className = "cal-head";
    head.textContent = h;
    grid.appendChild(head);
  }
  for (let i = 0; i < firstDow; i++) {
    const e = document.createElement("div");
    e.className = "cal-cell empty";
    grid.appendChild(e);
  }
  for (let d = 1; d <= daysInMonth; d++) {
    const cell = document.createElement("div");
    const isToday = today.getFullYear() === y && today.getMonth() === m && today.getDate() === d;
    cell.className = "cal-cell" + (isToday ? " today" : "");
    const day = document.createElement("div");
    day.className = "cal-day";
    day.textContent = d;
    cell.appendChild(day);
    for (const j of (byDay[d] || [])) {
      const chip = document.createElement("a");
      chip.className = "cal-chip pill--" + chipKind(j.status);
      chip.href = "/ui/job.html?id=" + encodeURIComponent(j.id);
      chip.textContent = j.topic || "(untitled)";
      chip.title = j.topic || "";
      cell.appendChild(chip);
    }
    grid.appendChild(cell);
  }
  host.replaceChildren(grid);
}

document.getElementById("cal-prev").addEventListener("click", () => { view.setMonth(view.getMonth() - 1); render(); });
document.getElementById("cal-next").addEventListener("click", () => { view.setMonth(view.getMonth() + 1); render(); });
document.getElementById("cal-today").addEventListener("click", () => { view = new Date(); view.setDate(1); render(); });

(async function () {
  try {
    jobs = await api.get("/api/v1/videos", { key: "jobs-list" });
    if (!Array.isArray(jobs)) jobs = [];
  } catch (e) {
    host.textContent = "Could not load jobs (see toast).";
    return;
  }
  render();
})();

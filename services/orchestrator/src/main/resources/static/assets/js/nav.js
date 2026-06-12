/*
 * Shared top navigation for the new UI. Injected on every /ui page so the menu
 * is always present (and the current section is highlighted). Inserted right
 * after #topbar; falls back to the start of <body>.
 */
(function () {
  const ITEMS = [
    ["📹 Jobs", "/ui/index.html"],
    ["📚 Series", "/ui/series.html"],
    ["🐔 Cast", "/ui/cast.html"],
    ["🎬 Brand", "/ui/brand.html"],
    ["📅 Calendar", "/ui/calendar.html"],
    ["📋 Backlog", "/ui/backlog.html"],
    ["📊 Analytics", "/ui/analytics.html"],
    ["🏅 Quality", "/ui/quality.html"],
    ["🥇 Golden", "/ui/golden.html"],
    ["🌍 Distribution", "/ui/distribution.html"],
    ["🔎 QC Patterns", "/ui/qcpatterns.html"],
  ];

  function build() {
    const wrap = document.createElement("div");
    wrap.className = "ui-header";

    // Brand logo (links home). Hidden gracefully if the logo isn't available.
    const brand = document.createElement("a");
    brand.className = "brand-link";
    brand.href = "/ui/index.html";
    const img = document.createElement("img");
    img.className = "brand-logo";
    img.src = "/api/v1/brand/logo.png";
    img.alt = "Tiny Chicken World";
    img.onerror = () => brand.remove();
    brand.appendChild(img);
    wrap.appendChild(brand);

    const nav = document.createElement("nav");
    nav.className = "uinav";
    let cur = location.pathname.split("/").pop();
    if (!cur) cur = "index.html";           // "/ui/" → index
    for (const [label, href] of ITEMS) {
      const file = href.split("/").pop();
      const a = document.createElement("a");
      a.href = href;
      a.textContent = label;
      if (file === cur) a.classList.add("active");
      nav.appendChild(a);
    }

    // ── 📈 Poll analytics — rechtsboven op élke pagina ──────────────────
    // POST /api/v1/analytics/poll: haalt NU de nieuwste YouTube-cijfers op
    // (views + retentie-per-scène) en voedt de zelflerende lus. NB: de
    // server doet dit sowieso al automatisch elke 6 uur (AnalyticsPoller),
    // dus deze knop is "ik wil het nú zien" — bv. vlak na een publicatie.
    // Laatste handmatige poll wordt in localStorage getoond als geheugensteun.
    const pollBtn = document.createElement("button");
    pollBtn.type = "button";
    pollBtn.id = "nav-analytics-poll";
    // Stijl als nav-link, rechts uitgelijnd (zelfde truc als .uinav-classic).
    pollBtn.style.cssText = "margin-left:auto;padding:8px 12px;font-size:13px;" +
        "font-weight:500;color:var(--muted);background:none;border:none;" +
        "border-bottom:2px solid transparent;cursor:pointer;font-family:inherit";
    pollBtn.title = "Haalt nu de nieuwste YouTube-statistieken op (views, retentie per " +
        "scène) en voedt de zelflerende lus: retentie-per-fase → scriptprompt, " +
        "arc-weging, thumbnail-CTR. Gebeurt automatisch elke 6 uur — deze knop " +
        "is voor direct verversen, bijvoorbeeld vlak na een publicatie.";
    function pollLabel() {
      let last = null;
      try { last = localStorage.getItem("lastAnalyticsPoll"); } catch (e) {}
      return "📈 Poll analytics" + (last ? " · " + last : "");
    }
    pollBtn.textContent = pollLabel();
    pollBtn.addEventListener("click", function () {
      pollBtn.disabled = true;
      pollBtn.textContent = "⏳ analytics pollen…";
      fetch("/api/v1/analytics/poll", { method: "POST" })
        .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); })
        .then(function () {
          const t = new Date().toLocaleTimeString("nl-NL",
              { hour: "2-digit", minute: "2-digit" });
          try { localStorage.setItem("lastAnalyticsPoll", t); } catch (e) {}
          pollBtn.textContent = "✓ gepolld " + t;
          setTimeout(function () { pollBtn.textContent = pollLabel(); }, 4000);
        })
        .catch(function () {
          pollBtn.textContent = "⚠ poll mislukt";
          setTimeout(function () { pollBtn.textContent = pollLabel(); }, 4000);
        })
        .finally(function () { pollBtn.disabled = false; });
    });
    nav.appendChild(pollBtn);
    wrap.appendChild(nav);

    const topbar = document.getElementById("topbar");
    if (topbar) topbar.insertAdjacentElement("afterend", wrap);
    else document.body.insertBefore(wrap, document.body.firstChild);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", build);
  } else {
    build();
  }
})();

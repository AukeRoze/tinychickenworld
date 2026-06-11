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

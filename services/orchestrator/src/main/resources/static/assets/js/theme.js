/*
 * Light/dark theme for the new static UI: pre-paint bootstrap + a fixed
 * top-right toggle button.
 *
 * IMPORTANT: this file is loaded with a plain SYNCHRONOUS <script> tag in the
 * <head> of every /ui page (no defer/async/module). That is deliberate: the
 * bootstrap below must set data-theme BEFORE the body first paints, otherwise
 * dark mode flashes white (FOUC). Being a static asset it is cached after the
 * first page view, so this replaces the old per-page inline copy at no cost.
 * The toggle itself persists the choice (localStorage + a long-lived cookie,
 * matching the classic dashboard's behaviour).
 */
(function () {
  // ── Pre-paint bootstrap (was inline in each page's <head>) ──
  try {
    var saved = null;
    try { saved = localStorage.getItem("theme"); } catch (e) {}
    var sys = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
    document.documentElement.setAttribute("data-theme", saved || (sys ? "dark" : "light"));
  } catch (e) { /* never block rendering over a theme */ }

  function current() {
    return document.documentElement.getAttribute("data-theme") || "light";
  }

  const btn = document.createElement("button");
  btn.id = "theme-toggle";
  btn.type = "button";

  function apply(theme) {
    document.documentElement.setAttribute("data-theme", theme);
    try { localStorage.setItem("theme", theme); } catch (e) {}
    document.cookie = "theme=" + theme + ";path=/;max-age=31536000";
    btn.textContent = theme === "dark" ? "☀️" : "🌙";
    btn.title = theme === "dark" ? "Switch to light mode" : "Switch to dark mode";
    btn.setAttribute("aria-label", btn.title);
  }

  btn.addEventListener("click", () => apply(current() === "dark" ? "light" : "dark"));

  function mount() {
    (document.getElementById("topbar") || document.body).appendChild(btn);
    apply(current());
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", mount);
  } else {
    mount();
  }
})();

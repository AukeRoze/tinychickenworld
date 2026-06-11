/*
 * Light/dark theme toggle for the new static UI — a fixed top-right button.
 * The pre-paint bootstrap in each page already set data-theme to avoid a flash;
 * this just adds the toggle control and persists the choice (localStorage +
 * a long-lived cookie, matching the classic dashboard's behaviour).
 */
(function () {
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

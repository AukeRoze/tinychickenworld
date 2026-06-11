/*
 * Shared API client for the operator dashboard (Frontend-review action HIGH#2).
 *
 * One place for every call to the orchestrator REST API: uniform JSON parsing,
 * uniform error handling (a toast instead of a silent failure), and an
 * AbortController per call so rapid clicking can't race. Components should call
 * api.get/post/del — never fetch() directly.
 *
 * No build step, no framework: plain ES module, served as a static asset by
 * Spring from classpath:/static/.
 */

/** Fire a transient toast. Falls back to console if no #toast-host exists yet. */
export function toast(message, kind = "info", durationMs = 4000) {
  const host = document.getElementById("toast-host");
  if (!host) {
    (kind === "error" ? console.error : console.log)("[toast]", message);
    return;
  }
  const el = document.createElement("div");
  el.className = `toast toast--${kind}`;
  el.textContent = message;
  host.appendChild(el);
  setTimeout(() => el.remove(), durationMs);
}

const inflight = new Map(); // key -> AbortController, so a repeat call cancels the previous

async function request(method, url, body, { key } = {}) {
  // Cancel an earlier in-flight call with the same key (e.g. a fast double-click).
  const dedupeKey = key || `${method} ${url}`;
  if (inflight.has(dedupeKey)) inflight.get(dedupeKey).abort();
  const ctrl = new AbortController();
  inflight.set(dedupeKey, ctrl);

  const opts = { method, headers: {}, signal: ctrl.signal };
  if (body !== undefined) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }

  let res;
  try {
    res = await fetch(url, opts);
  } catch (e) {
    if (e.name === "AbortError") throw e; // superseded by a newer call — caller ignores
    toast(`Network error on ${method} ${url}`, "error");
    throw e;
  } finally {
    if (inflight.get(dedupeKey) === ctrl) inflight.delete(dedupeKey);
  }

  if (!res.ok) {
    const text = await res.text().catch(() => "");
    // Surface the SERVER's reason, not just the status line. The backend sends
    // ProblemDetail JSON ({detail: "..."}) — without this the operator only saw
    // "500 Internal Server Error" while the real cause (e.g. "clip niet
    // gegenereerd: QUOTA") was in the body.
    let reason = "";
    try {
      const j = JSON.parse(text);
      reason = j.detail || j.message || j.error || "";
    } catch (e) { /* non-JSON body */ }
    toast(reason
        ? `${res.status}: ${reason.slice(0, 220)}`
        : `${res.status} ${res.statusText} on ${method} ${url}`,
      "error", reason ? 9000 : undefined);
    throw new Error(`HTTP ${res.status} ${method} ${url}: ${(reason || text).slice(0, 200)}`);
  }

  // 204 No Content or empty body → null.
  if (res.status === 204) return null;
  const ct = res.headers.get("content-type") || "";
  if (ct.includes("application/json")) return res.json();
  return res.text();
}

export const api = {
  get: (url, opts) => request("GET", url, undefined, opts),
  post: (url, body, opts) => request("POST", url, body, opts),
  patch: (url, body, opts) => request("PATCH", url, body, opts),
  del: (url, opts) => request("DELETE", url, undefined, opts),
};

export default api;

#!/usr/bin/env node
/**
 * Flow clip generator — drives Google Flow (Omni) via Playwright, using YOUR
 * already-logged-in Chrome session.
 *
 *   • Costs NO Claude credits: you run this locally, Claude is not involved.
 *   • It DOES use your Flow credits (~15 per clip, your subscription's daily
 *     pool that refreshes each day). No automation can make generations free —
 *     Flow charges server-side per generation. The win here is: no manual
 *     clicking and no Claude usage.
 *
 * It reuses the approach that worked for episode 3:
 *   per scene → lock the cast via @-mentions (Character refs) → type a SHORT
 *   action prompt → click generate. Flow renders them concurrently.
 *
 * SETUP + TROUBLESHOOTING: see README.md
 * RUN:   node generate-clips.mjs ep4-puddle-jumpers.json
 */
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';

// ───────────────────────── CONFIG (override via env) ─────────────────────────
const CDP_URL     = process.env.FLOW_CDP     || 'http://localhost:9222';
const PROJECT_URL = process.env.FLOW_PROJECT || '';   // optional: open this project first
const PACING_MS   = Number(process.env.FLOW_PACING || 6000); // pause between scenes
const PICKER_WAIT = 1300;   // ms to let the @-mention picker open
const STEP_WAIT   = 650;    // ms between picker clicks

// Selectors — deliberately TEXT/ROLE based (most stable). If Flow's UI changed
// and a step fails, grab the right selector with:
//   npx playwright codegen https://labs.google/fx/tools/flow
// then set it below (or via the FLOW_SUBMIT env var for the generate button).
const SEL = {
  // the "What do you want to create?" input (a contenteditable in Flow)
  promptEditor: '[contenteditable="true"]',
  // the "Add to Prompt" button in the @-mention picker
  addToPrompt : 'button:has-text("Add to Prompt")',
  // a clickable row in the @-mention picker that is a CHARACTER ref (not an image):
  // it shows the name AND the word "Character".
  charRow     : (name) =>
    `:is([role="option"],li,button,div):has-text("${name}"):has-text("Character")`,
};

// The generate/submit button (the arrow at the right of the prompt bar) has no
// reliable text label, so we try several strategies in order.
const SUBMIT_SELECTORS = [
  process.env.FLOW_SUBMIT,                       // your override (recommended)
  'button[aria-label*="enerate" i]',
  'button[aria-label*="ubmit" i]',
  'button[type="submit"]',
].filter(Boolean);

// ─────────────────────────────────────────────────────────────────────────────
const file = process.argv[2];
if (!file) { console.error('Usage: node generate-clips.mjs <scenes.json> [sceneNumberOrName]'); process.exit(1); }
const only = process.argv[3];   // optioneel: maak alléén deze scène, bv. "4" of "scene-4"
let scenes = JSON.parse(readFileSync(file, 'utf8'));
if (only) {
  scenes = scenes.filter((s, i) =>
    s.name === only || s.name === `scene-${only}` || String(i + 1) === String(only));
  if (!scenes.length) { console.error(`Geen scène "${only}" in ${file}.`); process.exit(1); }
  console.log(`Alleen scène: ${only}`);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.connectOverCDP(CDP_URL).catch((e) => {
  console.error(`Could not connect to Chrome at ${CDP_URL}.`);
  console.error('Start Chrome with --remote-debugging-port=9222 (see README).');
  throw e;
});
const ctx = browser.contexts()[0];
if (!ctx) throw new Error('No browser context found.');
let page = ctx.pages().find((p) => p.url().includes('labs.google/fx/tools/flow')) || ctx.pages()[0];
if (PROJECT_URL) await page.goto(PROJECT_URL, { waitUntil: 'domcontentloaded' });
await page.bringToFront();
console.log(`Connected to Flow. ${scenes.length} scene(s) queued.\n`);

async function lockCharacter(name) {
  const ed = page.locator(SEL.promptEditor).last();
  await ed.click();
  await page.keyboard.type('@' + name, { delay: 30 });   // real keystrokes trigger the picker
  await sleep(PICKER_WAIT);

  // Helper: vind het element via een betrouwbare in-page-query (op tekst) en klik
  // er met een ECHTE muisklik op het midden (page.mouse). Zo combineren we
  // betrouwbaar vinden mét echte mouse-events — nodig omdat deze
  // styled-components hun selectie/handlers pas op echte events triggeren, en
  // omdat Playwright's eigen locator de geconcateneerde "PipCharacter"-tekst
  // soms niet als "visible" ziet.
  const realClick = async (sel, { includes, startsWith } = {}) => {
    const box = await page.evaluate((a) => {
      const norm = (s) => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
      const el = [...document.querySelectorAll(a.sel)].find((e) => {
        const t = norm(e.textContent);
        if (a.includes && !t.includes(a.includes)) return false;
        if (a.startsWith && !t.startsWith(a.startsWith)) return false;
        return true;
      });
      if (!el) return null;
      el.scrollIntoView({ block: 'center' });
      const r = el.getBoundingClientRect();
      return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height };
    }, { sel, includes: (includes || '').toLowerCase(), startsWith: (startsWith || '').toLowerCase() });
    if (!box || box.w === 0) return false;
    await page.mouse.click(box.x, box.y);
    return true;
  };

  // 1) Characters-tabblad.
  await realClick('[role="tab"]', { includes: 'characters' });
  await sleep(700);

  // 2) Character-optie. Op het Characters-tabblad heet de optie simpelweg de naam
  //    (bv. "Pip") — geen "Character"-subtitle (die staat alleen in de All-weergave).
  //    Er staan hier alleen characters, dus matchen op de naam is genoeg.
  const okOpt = await realClick('[role="option"]', { startsWith: name });
  if (!okOpt) {
    const opts = await page.locator('[role="option"]').evaluateAll((es) =>
      es.map((e) => (e.textContent || '').replace(/\s+/g, ' ').trim()).slice(0, 30));
    const pickerOpen = await page.locator('#add-menu-input').count();
    const tabs = await page.locator('[role="tab"]').evaluateAll((es) =>
      es.map((e) => `${(e.textContent || '').replace(/\s+/g, ' ').trim()}${e.getAttribute('aria-selected') === 'true' ? '*' : ''}`));
    console.log(`   [diag ${name}] picker-open=${pickerOpen} tabs=${JSON.stringify(tabs)} opties=${JSON.stringify(opts)}`);
    throw new Error(`@-picker: character-optie voor "${name}" niet gevonden`);
  }
  await sleep(STEP_WAIT);

  // 3) Onder het Characters-tabblad voegt de klik op de optie de chip al direct
  //    toe (de picker sluit meteen). Mocht er in een andere weergave toch nog een
  //    "Add to Prompt"-knop staan, klik 'm; zo niet, gewoon doorgaan (geen fout).
  await realClick('button', { includes: 'add to prompt' });
  await sleep(STEP_WAIT);
}

async function clickGenerate() {
  for (const sel of SUBMIT_SELECTORS) {
    const btn = page.locator(sel).last();
    if (await btn.count()) { await btn.click({ timeout: 3000 }).catch(() => {}); return true; }
  }
  // In-page: zoek de genereer-knop. Eerst op aria-label (generate/create/submit),
  // anders op een pijl/verzend-icoon. Let op: NIET op play_arrow (= "Play audio"
  // in de @-picker) of arrow_drop_down (= dropdowns).
  const clicked = await page.evaluate(() => {
    const btns = [...document.querySelectorAll('button')].filter((b) => !b.disabled);
    let btn = btns.find((b) => /generate|create|submit/i.test(b.getAttribute('aria-label') || ''));
    if (!btn) {
      const ico = /\b(arrow_forward|send|arrow_upward)\b/;
      btn = btns.filter((b) => { const i = b.querySelector('i'); return i && ico.test(i.textContent || ''); }).pop();
    }
    if (btn) { btn.click(); return true; }
    return false;
  });
  if (clicked) return true;
  // laatste redmiddel: veel chat-inputs versturen op Ctrl+Enter
  await page.locator(SEL.promptEditor).last().press('Control+Enter').catch(() => {});
  return false;
}

let ok = 0;
for (let i = 0; i < scenes.length; i++) {
  const s = scenes[i];
  const label = s.name || `scene-${i + 1}`;
  try {
    console.log(`[${i + 1}/${scenes.length}] ${label} — cast: ${(s.cast || []).join(', ')}`);
    for (const name of (s.cast || [])) await lockCharacter(name);
    const ed = page.locator(SEL.promptEditor).last();
    await ed.click();
    await page.keyboard.type(s.prompt, { delay: 1 });
    await sleep(400);
    await clickGenerate();
    console.log('   ▶ generation fired');
    ok++;
    await sleep(PACING_MS);
  } catch (e) {
    console.error(`   ✖ ${label} FAILED: ${e.message}`);
    console.error('     → fix the failing selector in SEL (use: npx playwright codegen) and re-run.');
    console.error('       Flow only charges credits when a generation actually fires, so a failed');
    console.error('       setup step here costs nothing.');
  }
}

console.log(`\nFinished: ${ok}/${scenes.length} fired. Check Flow for the rendering clips.`);
await browser.close(); // detaches from CDP; does NOT close your Chrome window

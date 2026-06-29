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

  // Click the CHARACTER result — NOT an image (e.g. "pip.png") and not a big
  // container. We find the small picker row whose text holds the exact name AND
  // the word "Character", and click it in-page (most reliable across UI tweaks).
  const ok = await page.evaluate((nm) => {
    const norm = (s) => (s || '').replace(/\s+/g, ' ').trim();
    const rows = [...document.querySelectorAll('div,li,button,[role="option"]')];
    const row = rows.find((e) => {
      const t = norm(e.textContent);
      return t.length < 45
        && /\bCharacter\b/.test(t)                                  // it's a character ref
        && new RegExp('(^|[^a-zA-Z])' + nm + '([^a-zA-Z]|$)').test(t) // exact name (not pip.png)
        && e.querySelectorAll('*').length < 14;                     // a row, not a container
    });
    if (row) { row.click(); return true; }
    return false;
  }, name);
  if (!ok) throw new Error(`@-picker: kon de Character-rij voor "${name}" niet vinden`);
  await sleep(STEP_WAIT);
  await page.locator(SEL.addToPrompt).first().click();
  await sleep(STEP_WAIT);
}

async function clickGenerate() {
  for (const sel of SUBMIT_SELECTORS) {
    const btn = page.locator(sel).last();
    if (await btn.count()) { await btn.click({ timeout: 3000 }).catch(() => {}); return true; }
  }
  // last resort: many chat inputs submit on Ctrl+Enter (harmless if it just adds a newline)
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

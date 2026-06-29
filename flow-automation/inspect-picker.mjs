#!/usr/bin/env node
/**
 * inspect-picker.mjs — leest de @-mention-picker van Flow uit, zodat we de juiste
 * selectors kunnen bepalen. Kost niets (alleen lezen).
 *
 * GEBRUIK:
 *   1) In je remote-debug-Chrome: open je Flow-project en typ "@Pip" in het
 *      promptveld, zodat de picker OPEN staat (met het linkermenu All/Images/
 *      Voices/Characters/Uploads zichtbaar).
 *   2) Draai:  node inspect-picker.mjs
 *   3) Plak de output terug in de chat.
 */
import { chromium } from 'playwright';

const CDP = process.env.FLOW_CDP || 'http://localhost:9222';
const browser = await chromium.connectOverCDP(CDP).catch(() => {
  console.error(`Geen Chrome op ${CDP} — start Chrome met --remote-debugging-port=9222.`);
  process.exit(1);
});
const ctx = browser.contexts()[0];
const page = ctx.pages().find((p) => p.url().includes('labs.google/fx/tools/flow')) || ctx.pages()[0];

const dump = await page.evaluate(() => {
  const norm = (s) => (s || '').replace(/\s+/g, ' ').trim();
  const desc = (e) => ({
    tag: e.tagName.toLowerCase(),
    role: e.getAttribute('role') || '',
    type: e.getAttribute('type') || '',
    aria: e.getAttribute('aria-label') || '',
    cls: (e.className && e.className.toString ? e.className.toString() : '').slice(0, 70),
    text: norm(e.textContent).slice(0, 50),
  });

  const add = [...document.querySelectorAll('button')].find((b) => /add to prompt/i.test(b.textContent || ''));
  if (!add) return { error: 'GEEN picker open — typ eerst "@Pip" in Flow zodat de picker zichtbaar is, dan opnieuw.' };

  let panel = add;
  for (let i = 0; i < 14 && panel; i++, panel = panel.parentElement) {
    const t = panel.textContent || '';
    if (/Characters/.test(t) && /Voices/.test(t)) break;
  }
  panel = panel || add;

  const wanted = ['All', 'Images', 'Voices', 'Characters', 'Uploads'];
  const sidebar = [...panel.querySelectorAll('*')]
    .filter((e) => e.children.length === 0 && wanted.includes(norm(e.textContent)))
    .map((e) => ({ label: norm(e.textContent), leaf: desc(e), clickable: desc(e.closest('button,[role="button"],[role="option"],li,a') || e) }));

  const results = [...panel.querySelectorAll('*')]
    .filter((e) => e.children.length <= 5 && /\b(Character|Image)\b/.test(norm(e.textContent)) && norm(e.textContent).length < 55)
    .slice(0, 12)
    .map((e) => ({ ...desc(e), clickable: desc(e.closest('button,[role="button"],[role="option"],li,a') || e) }));

  const html = panel.outerHTML.replace(/<svg[\s\S]*?<\/svg>/g, '<svg/>');
  return { sidebar, results, addBtn: desc(add), html: html.slice(0, 4500) };
});

console.log(JSON.stringify(dump, null, 2));
await browser.close();

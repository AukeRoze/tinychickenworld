#!/usr/bin/env node
/**
 * build-scenes.mjs — leest een aflevering uit de orchestrator en schrijft een
 * scenes-JSON die generate-clips.mjs direct kan draaien.
 *
 *   • Kost GEEN credits (alleen lezen uit de lokale orchestrator-API).
 *   • Per scène een KORTE actie-prompt, opgebouwd uit:
 *       Omgeving (location + licht + weer)  +  visualDesc (de actie)  +  dialoog
 *     De lange character-DNA laten we weg — de @-referenties in Flow regelen de look.
 *   • Cast per scène uit de Veo-roster ("1. Pip: ...  2. Mo: ...  3. Bo: ...").
 *
 * RUN:
 *   node build-scenes.mjs <jobId> [out.json]
 *   node build-scenes.mjs ce41a53c-2cd7-4dac-9e12-73ff8c105f39 ep3.json
 *
 * Daarna:
 *   node generate-clips.mjs ep3.json
 */
import { writeFileSync } from 'node:fs';

const API = process.env.ORCH || 'http://localhost:8080';
const id  = process.argv[2];
const out = process.argv[3] || `scenes-${id}.json`;
if (!id) { console.error('Usage: node build-scenes.mjs <jobId> [out.json]'); process.exit(1); }

const STYLE = 'Soft clean stylized 3D cartoon look, matte';
const TAIL  = 'One lively 10-second beat, lip-synced clear English, natural motion, no morphing or flicker, no on-screen text, only the listed characters in frame, never add any extra chickens, chicks or birds';

const clean   = (s) => (s || '').replace(/\s+/g, ' ').trim();
const stripEnd = (p) => p.replace(/[.\s]+$/, '');
const cap     = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : '');

// cast = de genummerde namen in de Veo-roster; valt terug op Pip/Mo/Bo.
function castOf(veo) {
  const names = [];
  const re = /\b\d+\.\s+([A-Z][a-zA-Z']+)\s*:/g;   // "1. Pip:", "2. Mo:", "3. Bo:"
  let m; while ((m = re.exec(veo || ''))) if (!names.includes(m[1])) names.push(m[1]);
  return names.length ? names : ['Pip', 'Mo', 'Bo'];
}

// Omgeving: liefst het environmentPrompt-veld; anders de "- Setting:"-regel uit de Veo-prompt.
function settingOf(s) {
  if (s.environmentPrompt) return clean(s.environmentPrompt);
  const m = /Setting:\s*([^\n]+)/.exec(s.veoPrompt || '');
  return m ? clean(m[1]) : '';
}

function dialogueOf(lines) {
  return (lines || [])
    .filter((l) => l && l.text && l.text.trim())
    .map((l) => `${cap(l.speaker)} says "${l.text.trim()}"`)
    .join('; ');
}

const scenes = await fetch(`${API}/api/v1/videos/${id}/scenes`)
  .then((r) => { if (!r.ok) throw new Error(`HTTP ${r.status}`); return r.json(); })
  .catch((e) => {
    console.error(`Kon scènes niet ophalen: ${e.message}`);
    console.error(`Draait de orchestrator op ${API}? (zet ORCH=... om te wijzigen)`);
    process.exit(1);
  });

if (!Array.isArray(scenes) || !scenes.length) {
  console.error('Geen scènes gevonden voor deze job (is het script al gegenereerd/geïmporteerd?).');
  process.exit(1);
}

// Korte, gerichte herinneringen per personage (de @-referenties brengen niet
// altijd elk kenmerk over). Worden alleen toegevoegd als het personage in de
// scène zit. Houd ze KORT — de prompt moet compact blijven.
const HINTS = {
  Pip: 'Pip is a pure white chick with a straw hat and a red bandana',
  Mo:  'Mo is a blue chick with a BRIGHT RED COMB on top of his head and a RED scarf (both always visible)',
  Bo:  'Bo is a tan chick with round glasses, a GREEN scarf, and a distinctly LONG upright neck (a tall exclamation-mark silhouette), never a short round neckless body',
};

const arr = scenes.map((s, i) => {
  const cast = castOf(s.veoPrompt);
  const hints = cast.map((n) => HINTS[n]).filter(Boolean).join('. ');
  const parts = [STYLE, settingOf(s), clean(s.visualDesc || s.narration), dialogueOf(s.lines), hints, TAIL]
    .filter(Boolean)
    .map(stripEnd);
  return {
    name: `scene-${s.seq || i + 1}`,
    cast,
    prompt: (parts.join('. ') + '.').replace(/\s+/g, ' ').trim(),
  };
});

writeFileSync(out, JSON.stringify(arr, null, 2));
console.log(`Wrote ${arr.length} scenes -> ${out}`);
console.log(`Cast scène 1: ${arr[0].cast.join(', ')}`);
console.log(`Voorbeeld prompt scène 1:\n  ${arr[0].prompt.slice(0, 200)}...`);

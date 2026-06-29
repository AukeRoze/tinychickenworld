#!/usr/bin/env node
/**
 * run.mjs — doet beide stappen in één commando:
 *   1) build-scenes.mjs  (leest de aflevering uit de orchestrator -> scenes JSON)
 *   2) generate-clips.mjs (genereert de clips in Flow)
 *
 * RUN:  node run.mjs <jobId>
 *       node run.mjs ce41a53c-2cd7-4dac-9e12-73ff8c105f39
 *
 * Geen Claude-credits. Flow-credits gelden (~15/clip, dagelijks ververst).
 */
import { spawnSync } from 'node:child_process';

const id = process.argv[2];
if (!id) { console.error('Usage: node run.mjs <jobId>'); process.exit(1); }
const tmp = `scenes-${id}.json`;

console.log(`\n=== 1/2 build-scenes (${id}) ===`);
let r = spawnSync(process.execPath, ['build-scenes.mjs', id, tmp], { stdio: 'inherit' });
if (r.status !== 0) { console.error('build-scenes faalde — gestopt.'); process.exit(r.status || 1); }

console.log(`\n=== 2/2 generate-clips (${tmp}) ===`);
r = spawnSync(process.execPath, ['generate-clips.mjs', tmp], { stdio: 'inherit' });
process.exit(r.status || 0);

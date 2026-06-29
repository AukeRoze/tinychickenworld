#!/usr/bin/env node
/**
 * server.mjs — kleine LOKALE agent zodat je de Flow-generatie vanuit de frontend
 * (orchestrator-UI) kunt aanroepen. Een browserpagina mag zelf geen Node/Chrome
 * starten, dus dit servertje doet dat: het frontend doet een fetch hierheen.
 *
 *   Start:  node server.mjs           (luistert op http://localhost:9223)
 *   Aanroep vanuit de frontend:
 *     fetch('http://localhost:9223/generate/' + jobId, { method: 'POST' })
 *
 * Het draait dan build-scenes + generate-clips voor dat jobId (op de achtergrond).
 * Alleen lokaal bereikbaar; CORS staat alleen de orchestrator-origin toe.
 *
 * Vereist (zoals altijd): Chrome met --remote-debugging-port=9222, ingelogd in Flow,
 * en de orchestrator draaiend. Flow-credits gelden; Claude-credits niet.
 */
import http from 'node:http';
import { spawn } from 'node:child_process';

const PORT   = Number(process.env.FLOW_AGENT_PORT || 9223);
const ORIGIN = process.env.FLOW_AGENT_ORIGIN || 'http://localhost:8080';

let running = null;          // simpele 1-tegelijk-lock
const state = { lastJob: null, status: 'idle', startedAt: null };

function runChain(id, seq) {
  const tmp = `scenes-${id}.json`;
  state.lastJob = id; state.status = 'building'; state.startedAt = new Date().toISOString();
  const p1 = spawn(process.execPath, ['build-scenes.mjs', id, tmp], { stdio: 'inherit' });
  running = p1;
  p1.on('exit', (code) => {
    if (code !== 0) { state.status = 'build-failed'; running = null; return; }
    state.status = 'generating';
    const args = seq ? [tmp, String(seq)] : [tmp];   // seq = alleen die scène
    const p2 = spawn(process.execPath, ['generate-clips.mjs', ...args], { stdio: 'inherit' });
    running = p2;
    p2.on('exit', (c2) => { state.status = c2 === 0 ? 'done' : 'generate-failed'; running = null; });
  });
}

const server = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', ORIGIN);
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  res.setHeader('Content-Type', 'application/json');
  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }

  if (req.url === '/status') { res.writeHead(200); return res.end(JSON.stringify(state)); }

  // /generate/<jobId>            -> hele aflevering
  // /generate/<jobId>/<seq>      -> alleen scène <seq> (bv. /generate/<id>/4)
  const m = /^\/generate\/([0-9a-fA-F-]{8,})(?:\/(\w+))?$/.exec(req.url || '');
  if (m) {
    if (running) { res.writeHead(409); return res.end(JSON.stringify({ error: 'busy', state })); }
    const id = m[1], seq = m[2] || null;
    runChain(id, seq);
    res.writeHead(202);
    return res.end(JSON.stringify({ started: true, jobId: id, scene: seq || 'all' }));
  }

  res.writeHead(404);
  res.end(JSON.stringify({ usage: 'POST /generate/<jobId>  ·  GET /status' }));
});

server.listen(PORT, () => {
  console.log(`Flow agent op http://localhost:${PORT}  (CORS: ${ORIGIN})`);
  console.log('Frontend: fetch(`http://localhost:' + PORT + '/generate/${jobId}`, {method:"POST"})');
});

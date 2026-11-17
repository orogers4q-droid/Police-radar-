import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ReportStore, REPORT_TYPES, ValidationError } from './store.js';
import { RateLimiter } from './ratelimit.js';

const ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)));
const PUBLIC_DIR = join(ROOT, 'public');
const MAX_RADIUS_M = 50_000;
const MAX_BODY_BYTES = 4_096;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
};

export function createServer({ store = new ReportStore(), limiter = new RateLimiter() } = {}) {
  const streams = new Set();
  store.onChange((event, report) => {
    const msg = `event: ${event}\ndata: ${JSON.stringify(report)}\n\n`;
    for (const res of streams) res.write(msg);
  });
  const pruneTimer = setInterval(() => store.prune(), 30_000);
  pruneTimer.unref();

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    try {
      if (url.pathname.startsWith('/api/')) {
        return await handleApi(req, res, url);
      }
      return await serveStatic(res, url.pathname);
    } catch (err) {
      if (err instanceof ValidationError) return json(res, 400, { error: err.message });
      console.error(err);
      return json(res, 500, { error: 'Internal error' });
    }
  });

  server.on('close', () => {
    clearInterval(pruneTimer);
    for (const res of streams) res.end();
  });

  async function handleApi(req, res, url) {
    const clientId = sanitizeClientId(req.headers['x-client-id']);
    const ip = req.socket.remoteAddress ?? 'unknown';

    if (req.method === 'GET' && url.pathname === '/api/types') {
      return json(res, 200, REPORT_TYPES);
    }

    if (req.method === 'GET' && url.pathname === '/api/reports') {
      const lat = parseFloat(url.searchParams.get('lat'));
      const lng = parseFloat(url.searchParams.get('lng'));
      const radius = Math.min(Number(url.searchParams.get('radius')) || 5_000, MAX_RADIUS_M);
      if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
        throw new ValidationError('lat and lng are required');
      }
      return json(res, 200, store.near({ lat, lng }, radius));
    }

    if (req.method === 'POST' && url.pathname === '/api/reports') {
      if (!limiter.allow(`report:${ip}`, 5, 10 * 60_000)) {
        return json(res, 429, { error: 'Too many reports. Try again in a few minutes.' });
      }
      const body = await readJson(req);
      const result = store.add({
        lat: parseFloat(body.lat),
        lng: parseFloat(body.lng),
        type: body.type,
        note: body.note ?? '',
        clientId,
      });
      return json(res, result.created ? 201 : 200, result);
    }

    const voteMatch = url.pathname.match(/^\/api\/reports\/([\w-]+)\/(confirm|dismiss)$/);
    if (req.method === 'POST' && voteMatch) {
      if (!limiter.allow(`vote:${ip}`, 30, 10 * 60_000)) {
        return json(res, 429, { error: 'Too many votes. Slow down.' });
      }
      const report = store.vote(voteMatch[1], voteMatch[2], clientId);
      if (!report) return json(res, 404, { error: 'Report not found or expired' });
      return json(res, 200, report);
    }

    if (req.method === 'GET' && url.pathname === '/api/stream') {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
      });
      res.write('retry: 5000\n\n');
      streams.add(res);
      const ping = setInterval(() => res.write(': ping\n\n'), 25_000);
      req.on('close', () => {
        clearInterval(ping);
        streams.delete(res);
      });
      return;
    }

    return json(res, 404, { error: 'Not found' });
  }

  return server;
}

async function serveStatic(res, pathname) {
  const rel = normalize(decodeURIComponent(pathname)).replace(/^(\.\.[/\\])+/, '');
  let file = join(PUBLIC_DIR, rel === '/' ? 'index.html' : rel);
  if (!file.startsWith(PUBLIC_DIR)) return json(res, 403, { error: 'Forbidden' });
  try {
    const data = await readFile(file);
    res.writeHead(200, {
      'Content-Type': MIME[extname(file)] ?? 'application/octet-stream',
      'Cache-Control': 'no-cache',
    });
    res.end(data);
  } catch {
    json(res, 404, { error: 'Not found' });
  }
}

function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}

function readJson(req) {
  return new Promise((resolveBody, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) {
        reject(new ValidationError('Request body too large'));
        req.destroy();
      } else chunks.push(c);
    });
    req.on('end', () => {
      try {
        resolveBody(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'));
      } catch {
        reject(new ValidationError('Invalid JSON'));
      }
    });
    req.on('error', reject);
  });
}

function sanitizeClientId(value) {
  return typeof value === 'string' && /^[\w-]{8,64}$/.test(value) ? value : null;
}

// Start the server when run directly (not when imported by tests).
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const port = Number(process.env.PORT) || 3000;
  const store = new ReportStore({ file: process.env.DATA_FILE ?? join(ROOT, 'data', 'reports.json') });
  createServer({ store }).listen(port, () => {
    console.log(`Police alert server running at http://localhost:${port}`);
  });
}

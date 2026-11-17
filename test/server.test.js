import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from '../server/index.js';

let server, base;
const headers = { 'Content-Type': 'application/json', 'X-Client-Id': 'test-client-1' };

before(async () => {
  server = createServer();
  await new Promise((r) => server.listen(0, r));
  base = `http://localhost:${server.address().port}`;
});
after(() => { server.closeAllConnections(); server.close(); });

test('serves the app shell', async () => {
  const res = await fetch(`${base}/`);
  assert.equal(res.status, 200);
  assert.match(await res.text(), /Patrol Alert/);
});

test('blocks path traversal', async () => {
  const res = await fetch(`${base}/..%2f..%2fpackage.json`);
  assert.notEqual(res.status, 200);
});

test('report, list, confirm and dismiss over HTTP', async () => {
  const post = await fetch(`${base}/api/reports`, {
    method: 'POST', headers, body: JSON.stringify({ lat: 51.5, lng: -0.12, type: 'checkpoint', note: 'A4 eastbound' }),
  });
  assert.equal(post.status, 201);
  const { report } = await post.json();

  const list = await (await fetch(`${base}/api/reports?lat=51.5&lng=-0.12&radius=1000`)).json();
  assert.equal(list.length, 1);
  assert.equal(list[0].note, 'A4 eastbound');

  const conf = await fetch(`${base}/api/reports/${report.id}/confirm`, {
    method: 'POST', headers: { ...headers, 'X-Client-Id': 'test-client-2' },
  });
  assert.equal((await conf.json()).confirms, 2);

  const missing = await fetch(`${base}/api/reports/nope/dismiss`, { method: 'POST', headers });
  assert.equal(missing.status, 404);
});

test('validates input', async () => {
  const noClient = await fetch(`${base}/api/reports`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ lat: 1, lng: 1, type: 'patrol' }),
  });
  assert.equal(noClient.status, 400);
  const badJson = await fetch(`${base}/api/reports`, { method: 'POST', headers, body: '{nope' });
  assert.equal(badJson.status, 400);
  const noCoords = await fetch(`${base}/api/reports`);
  assert.equal(noCoords.status, 400);
});

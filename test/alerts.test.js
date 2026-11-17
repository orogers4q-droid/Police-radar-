import { test } from 'node:test';
import assert from 'node:assert/strict';
import { reportsToAlert, bearing, formatDistance } from '../public/alerts.js';

const me = { lat: 40.0, lng: -75.0 };
const north = { id: 'n', lat: 40.005, lng: -75.0 }; // ~555 m north
const south = { id: 's', lat: 39.995, lng: -75.0 }; // ~555 m south
const nearSouth = { id: 'ns', lat: 39.999, lng: -75.0 }; // ~110 m south
const far = { id: 'f', lat: 40.05, lng: -75.0 }; // ~5.5 km

test('bearing points the right way', () => {
  assert.ok(Math.abs(bearing(me, north) - 0) < 1);
  assert.ok(Math.abs(bearing(me, south) - 180) < 1);
});

test('alerts within radius, nearest first, once per report', () => {
  const alerted = new Set();
  const reports = [north, far, nearSouth];
  const hits = reportsToAlert({ position: me, reports, radiusM: 1000, alerted });
  assert.deepEqual(hits.map((h) => h.report.id), ['ns', 'n']);
  hits.forEach((h) => alerted.add(h.report.id));
  assert.equal(reportsToAlert({ position: me, reports, radiusM: 1000, alerted }).length, 0);
});

test('when driving, ignores reports behind unless very close', () => {
  const hits = reportsToAlert({
    position: me, heading: 0, speed: 20, reports: [north, south, nearSouth], radiusM: 1000, alerted: new Set(),
  });
  assert.deepEqual(hits.map((h) => h.report.id), ['ns', 'n']);
});

test('heading is ignored when stationary', () => {
  const hits = reportsToAlert({
    position: me, heading: 0, speed: 0, reports: [south], radiusM: 1000, alerted: new Set(),
  });
  assert.equal(hits.length, 1);
});

test('formatDistance', () => {
  assert.equal(formatDistance(234), '230 m');
  assert.equal(formatDistance(1530), '1.5 km');
});

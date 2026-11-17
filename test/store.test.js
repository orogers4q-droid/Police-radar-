import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ReportStore, ValidationError } from '../server/store.js';

const MIN = 60_000;
const HERE = { lat: 40.7128, lng: -74.006 };
const FAR = { lat: 40.73, lng: -74.006 }; // ~2 km north

function makeStore() {
  let t = 1_000_000;
  const clock = { now: () => t, advance: (ms) => { t += ms; } };
  return { store: new ReportStore({ now: clock.now }), clock };
}

test('creates a report that expires after one hour', () => {
  const { store, clock } = makeStore();
  const { report, created } = store.add({ ...HERE, type: 'patrol', clientId: 'aaaaaaaa' });
  assert.equal(created, true);
  assert.equal(report.minutesLeft, 60);
  clock.advance(59 * MIN);
  assert.equal(store.near(HERE, 1000).length, 1);
  clock.advance(2 * MIN);
  assert.equal(store.near(HERE, 1000).length, 0);
});

test('a nearby report of the same type becomes a confirmation', () => {
  const { store } = makeStore();
  const a = store.add({ ...HERE, type: 'speed_trap', clientId: 'aaaaaaaa' });
  const b = store.add({ lat: HERE.lat + 0.0005, lng: HERE.lng, type: 'speed_trap', clientId: 'bbbbbbbb' });
  assert.equal(b.created, false);
  assert.equal(b.report.id, a.report.id);
  assert.equal(b.report.confirms, 2);
  // Different type at same spot is a separate pin.
  assert.equal(store.add({ ...HERE, type: 'checkpoint', clientId: 'bbbbbbbb' }).created, true);
});

test('confirmations extend lifetime, capped at 3h remaining', () => {
  const { store } = makeStore();
  const { report } = store.add({ ...HERE, type: 'patrol', clientId: 'c0000000' });
  for (let i = 1; i <= 20; i++) store.vote(report.id, 'confirm', `c000000${i}`.padEnd(8, '0'));
  assert.equal(store.get(report.id).minutesLeft, 180);
});

test('votes are idempotent per client and can be changed', () => {
  const { store } = makeStore();
  const { report } = store.add({ ...HERE, type: 'patrol', clientId: 'aaaaaaaa' });
  store.vote(report.id, 'confirm', 'bbbbbbbb');
  store.vote(report.id, 'confirm', 'bbbbbbbb');
  assert.equal(store.get(report.id).confirms, 2);
  store.vote(report.id, 'dismiss', 'bbbbbbbb');
  const r = store.get(report.id);
  assert.equal(r.confirms, 1);
  assert.equal(r.dismissals, 1);
});

test('enough dismissals remove a report', () => {
  const { store } = makeStore();
  const { report } = store.add({ ...HERE, type: 'patrol', clientId: 'aaaaaaaa' });
  store.vote(report.id, 'dismiss', 'bbbbbbbb');
  store.vote(report.id, 'dismiss', 'cccccccc');
  const res = store.vote(report.id, 'dismiss', 'dddddddd');
  assert.equal(res.removed, true);
  assert.equal(store.get(report.id), null);
});

test('near() filters by radius and sorts by distance', () => {
  const { store } = makeStore();
  store.add({ ...FAR, type: 'patrol', clientId: 'aaaaaaaa' });
  store.add({ ...HERE, type: 'checkpoint', clientId: 'aaaaaaaa' });
  assert.equal(store.near(HERE, 500).length, 1);
  const both = store.near(HERE, 5000);
  assert.deepEqual(both.map((r) => r.type), ['checkpoint', 'patrol']);
  assert.ok(both[1].distanceM > 1500 && both[1].distanceM < 2500);
});

test('rejects invalid input and hides voter identities', () => {
  const { store } = makeStore();
  assert.throws(() => store.add({ lat: 200, lng: 0, type: 'patrol', clientId: 'aaaaaaaa' }), ValidationError);
  assert.throws(() => store.add({ ...HERE, type: 'ufo', clientId: 'aaaaaaaa' }), ValidationError);
  assert.throws(() => store.add({ ...HERE, type: 'patrol', clientId: null }), ValidationError);
  const { report } = store.add({ ...HERE, type: 'patrol', clientId: 'aaaaaaaa' });
  assert.equal('voters' in report, false);
});

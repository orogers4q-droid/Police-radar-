import { readFileSync, writeFileSync, mkdirSync, renameSync } from 'node:fs';
import { dirname } from 'node:path';
import { randomUUID } from 'node:crypto';
import { distanceMeters, isValidCoord } from './geo.js';

export const REPORT_TYPES = {
  patrol: 'Patrol car',
  speed_trap: 'Speed trap',
  checkpoint: 'Checkpoint',
  traffic_stop: 'Traffic stop',
  hidden: 'Hidden / unmarked',
};

const MINUTE = 60_000;
export const DEFAULTS = {
  baseLifetimeMs: 60 * MINUTE, // a fresh report lives one hour
  confirmExtensionMs: 20 * MINUTE, // each confirmation adds time...
  maxRemainingMs: 3 * 60 * MINUTE, // ...but never more than 3h from now
  dedupeRadiusM: 150, // same type this close = confirmation, not a new pin
  dismissMargin: 2, // removed once dismissals >= confirmations + margin
};

/**
 * In-memory store of police sighting reports with optional JSON persistence.
 * `now` is injectable so lifetimes can be tested deterministically.
 */
export class ReportStore {
  constructor({ file = null, now = Date.now, options = {} } = {}) {
    this.file = file;
    this.now = now;
    this.opts = { ...DEFAULTS, ...options };
    this.reports = new Map();
    this.listeners = new Set();
    if (file) this.#load();
  }

  onChange(fn) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  #emit(event, report) {
    for (const fn of this.listeners) fn(event, publicView(report, this.now()));
  }

  /**
   * Create a report, or confirm an existing one of the same type nearby.
   * Returns { report, created }.
   */
  add({ lat, lng, type, note = '', clientId }) {
    if (!isValidCoord(lat, lng)) throw new ValidationError('Invalid coordinates');
    if (!(type in REPORT_TYPES)) throw new ValidationError('Unknown report type');
    if (!clientId) throw new ValidationError('Missing client id');
    this.prune();

    const near = this.#findNearbySameType({ lat, lng }, type);
    if (near) {
      this.vote(near.id, 'confirm', clientId);
      return { report: publicView(near, this.now()), created: false };
    }

    const t = this.now();
    const report = {
      id: randomUUID(),
      type,
      lat,
      lng,
      note: String(note).trim().slice(0, 140),
      createdAt: t,
      expiresAt: t + this.opts.baseLifetimeMs,
      lastConfirmedAt: t,
      confirms: 1,
      dismissals: 0,
      voters: { [clientId]: 'confirm' },
    };
    this.reports.set(report.id, report);
    this.#save();
    this.#emit('added', report);
    return { report: publicView(report, t), created: true };
  }

  /** Register a 'confirm' ("still there") or 'dismiss' ("not there") vote. */
  vote(id, kind, clientId) {
    if (kind !== 'confirm' && kind !== 'dismiss') throw new ValidationError('Invalid vote');
    if (!clientId) throw new ValidationError('Missing client id');
    this.prune();
    const report = this.reports.get(id);
    if (!report) return null;

    const t = this.now();
    const previous = report.voters[clientId];
    if (previous === kind) return publicView(report, t); // idempotent
    if (previous === 'confirm') report.confirms--;
    if (previous === 'dismiss') report.dismissals--;
    report.voters[clientId] = kind;

    if (kind === 'confirm') {
      report.confirms++;
      report.lastConfirmedAt = t;
      report.expiresAt = Math.min(
        report.expiresAt + this.opts.confirmExtensionMs,
        t + this.opts.maxRemainingMs,
      );
    } else {
      report.dismissals++;
    }

    if (report.dismissals >= report.confirms + this.opts.dismissMargin) {
      this.reports.delete(id);
      this.#save();
      this.#emit('removed', report);
      return { ...publicView(report, t), removed: true };
    }
    this.#save();
    this.#emit('updated', report);
    return publicView(report, t);
  }

  /** Active reports within `radiusM` of a point, nearest first. */
  near({ lat, lng }, radiusM) {
    this.prune();
    const t = this.now();
    const out = [];
    for (const r of this.reports.values()) {
      const d = distanceMeters({ lat, lng }, r);
      if (d <= radiusM) out.push({ ...publicView(r, t), distanceM: Math.round(d) });
    }
    return out.sort((a, b) => a.distanceM - b.distanceM);
  }

  get(id) {
    const r = this.reports.get(id);
    return r && r.expiresAt > this.now() ? publicView(r, this.now()) : null;
  }

  /** Drop expired reports. Returns how many were removed. */
  prune() {
    const t = this.now();
    let removed = 0;
    for (const [id, r] of this.reports) {
      if (r.expiresAt <= t) {
        this.reports.delete(id);
        this.#emit('removed', r);
        removed++;
      }
    }
    if (removed) this.#save();
    return removed;
  }

  #findNearbySameType(point, type) {
    let best = null;
    let bestD = Infinity;
    for (const r of this.reports.values()) {
      if (r.type !== type) continue;
      const d = distanceMeters(point, r);
      if (d <= this.opts.dedupeRadiusM && d < bestD) {
        best = r;
        bestD = d;
      }
    }
    return best;
  }

  #load() {
    try {
      const rows = JSON.parse(readFileSync(this.file, 'utf8'));
      for (const r of rows) this.reports.set(r.id, r);
    } catch (err) {
      if (err.code !== 'ENOENT') console.warn(`Could not load ${this.file}: ${err.message}`);
    }
  }

  #save() {
    if (!this.file) return;
    mkdirSync(dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    writeFileSync(tmp, JSON.stringify([...this.reports.values()]));
    renameSync(tmp, this.file);
  }
}

/** Strip voter identities before sending a report to clients. */
function publicView(r, now) {
  return {
    id: r.id,
    type: r.type,
    label: REPORT_TYPES[r.type],
    lat: r.lat,
    lng: r.lng,
    note: r.note,
    createdAt: r.createdAt,
    expiresAt: r.expiresAt,
    lastConfirmedAt: r.lastConfirmedAt,
    confirms: r.confirms,
    dismissals: r.dismissals,
    minutesLeft: Math.max(0, Math.round((r.expiresAt - now) / MINUTE)),
  };
}

export class ValidationError extends Error {}

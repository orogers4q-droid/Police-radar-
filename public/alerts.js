// Pure alerting logic, shared by the browser app and the tests.

const R = 6_371_000;
const rad = (d) => (d * Math.PI) / 180;
const deg = (r) => (r * 180) / Math.PI;

export function distanceMeters(a, b) {
  const dLat = rad(b.lat - a.lat);
  const dLng = rad(b.lng - a.lng);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Initial compass bearing (0–360°) from a to b. */
export function bearing(a, b) {
  const y = Math.sin(rad(b.lng - a.lng)) * Math.cos(rad(b.lat));
  const x =
    Math.cos(rad(a.lat)) * Math.sin(rad(b.lat)) -
    Math.sin(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.cos(rad(b.lng - a.lng));
  return (deg(Math.atan2(y, x)) + 360) % 360;
}

export function angleDiff(a, b) {
  const d = Math.abs(a - b) % 360;
  return d > 180 ? 360 - d : d;
}

/**
 * Decide which reports should trigger a new alert.
 *
 * - Only reports within `radiusM` count.
 * - When moving with a known heading, reports behind you (outside a ±70° cone)
 *   are ignored unless they are very close (`closeM`).
 * - Each report alerts once per `alerted` set (caller owns the set).
 */
export function reportsToAlert({ position, heading = null, speed = null, reports, radiusM, alerted, closeM = 250 }) {
  const moving = heading != null && Number.isFinite(heading) && (speed ?? 0) > 2; // > ~7 km/h
  const hits = [];
  for (const r of reports) {
    if (alerted.has(r.id)) continue;
    const d = distanceMeters(position, r);
    if (d > radiusM) continue;
    if (moving && d > closeM && angleDiff(heading, bearing(position, r)) > 70) continue;
    hits.push({ report: r, distanceM: Math.round(d) });
  }
  return hits.sort((a, b) => a.distanceM - b.distanceM);
}

export function formatDistance(m) {
  return m < 1000 ? `${Math.round(m / 10) * 10} m` : `${(m / 1000).toFixed(1)} km`;
}

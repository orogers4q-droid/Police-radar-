/** Simple sliding-window rate limiter keyed by an arbitrary string. */
export class RateLimiter {
  constructor({ now = Date.now } = {}) {
    this.now = now;
    this.hits = new Map();
  }

  allow(key, limit, windowMs) {
    const t = this.now();
    const recent = (this.hits.get(key) ?? []).filter((ts) => t - ts < windowMs);
    if (recent.length >= limit) {
      this.hits.set(key, recent);
      return false;
    }
    recent.push(t);
    this.hits.set(key, recent);
    if (this.hits.size > 10_000) this.#sweep(windowMs);
    return true;
  }

  #sweep(windowMs) {
    const t = this.now();
    for (const [k, list] of this.hits) {
      if (!list.some((ts) => t - ts < windowMs)) this.hits.delete(k);
    }
  }
}

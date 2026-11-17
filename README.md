# Patrol Alert

A crowdsourced police-sighting app for drivers. People report what they see (patrol car, speed trap, checkpoint, traffic stop, unmarked car), other drivers confirm or dismiss it, and everyone nearby gets warned as they approach.

It's a mobile-first Progressive Web App backed by a small Node.js server with **no runtime dependencies**.

## Features

- **Live map** of active reports around you (Leaflet + OpenStreetMap).
- **One-tap reporting** at your current GPS location, with an optional short note.
- **Proximity alerts**: banner, two-tone sound, vibration, optional spoken alert, and a system notification if the app is in the background.
- **Direction-aware**: while you're moving, reports behind you are ignored unless they are very close.
- **Driving mode** keeps the screen awake and follows your position.
- **Self-cleaning data**:
  - Reports expire after 60 minutes.
  - Each "still there" confirmation adds 20 minutes, up to 3 hours remaining.
  - A report is removed once dismissals reach confirmations + 2.
  - A new report of the same type within 150 m counts as a confirmation instead of a duplicate pin.
- **Real-time updates** pushed to every open app via Server-Sent Events.
- **Abuse limits**: 5 reports and 30 votes per IP per 10 minutes, one vote per device per report, and voter IDs are never sent to clients.
- **Installable and offline-capable** app shell (service worker + manifest).

## Run it

```bash
npm start          # http://localhost:3000
npm run dev        # restarts on file changes
npm test           # unit + HTTP tests
```

Environment variables:

| Variable    | Default             | Purpose                         |
|-------------|---------------------|---------------------------------|
| `PORT`      | `3000`              | HTTP port                       |
| `DATA_FILE` | `data/reports.json` | Where active reports persist    |

> Browsers only allow geolocation on `https://` or `localhost`. To test on a phone, put the server behind HTTPS (for example a reverse proxy or a tunnel).

## API

All write requests need an `X-Client-Id` header: a random per-device ID of 8–64 characters from `[A-Za-z0-9_-]`.

| Method | Path                          | Description |
|--------|-------------------------------|-------------|
| GET    | `/api/types`                  | Report types and their labels |
| GET    | `/api/reports?lat&lng&radius` | Active reports within `radius` meters (default 5000, max 50000), nearest first |
| POST   | `/api/reports`                | `{lat, lng, type, note?}` returns `201` when created, or `200` when merged into a nearby report |
| POST   | `/api/reports/:id/confirm`    | "Still there" |
| POST   | `/api/reports/:id/dismiss`    | "Not there" |
| GET    | `/api/stream`                 | Server-Sent Events: `added`, `updated`, `removed` |

## Project layout

```
server/
  index.js      HTTP server: API, SSE, static files
  store.js      Report store: lifetimes, votes, dedupe, JSON persistence
  ratelimit.js  Sliding-window rate limiter
  geo.js        Distance helpers
public/
  index.html, styles.css, app.js   The PWA
  alerts.js     Pure alert logic (shared with tests)
  sw.js, manifest.webmanifest, icon.svg
  vendor/leaflet/                  Bundled Leaflet 1.9.4 (BSD-2-Clause)
test/           node:test suites
```

## Responsible use

Reports are crowdsourced and may be wrong. Don't use the app while driving: set it up before you leave, and let the alerts come to you. Always drive safely and obey the law. Laws on sharing police locations vary by country, so check yours before you deploy this.

## Ideas for next steps

- Store reports in SQLite or Postgres with a geospatial index, and scope the SSE stream to each client's area.
- Native wrappers (Capacitor) for reliable background location alerts.
- Reputation scores so reports from reliable users carry more weight.
- Use OpenStreetMap road data to alert only for reports on your route.

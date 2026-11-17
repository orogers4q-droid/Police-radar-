import { reportsToAlert, distanceMeters, formatDistance } from './alerts.js';

const ICONS = {
  patrol: '🚓',
  speed_trap: '📡',
  checkpoint: '🚧',
  traffic_stop: '🚨',
  hidden: '🕵️',
};
const FETCH_RADIUS_M = 15_000;
const $ = (id) => document.getElementById(id);

// ---------- persisted settings & identity ----------
function load(key, fallback) {
  try {
    const v = localStorage.getItem(key);
    return v == null ? fallback : JSON.parse(v);
  } catch {
    return fallback;
  }
}
function save(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* private mode */ }
}

const clientId = load('clientId', null) ?? crypto.randomUUID();
save('clientId', clientId);
const settings = { radiusM: 1000, sound: true, vibrate: true, voice: false, ...load('settings', {}) };

// ---------- state ----------
const state = {
  position: null, // {lat, lng}
  heading: null,
  speed: null,
  reports: new Map(),
  markers: new Map(),
  alerted: new Set(),
  driving: false,
  watchId: null,
  wakeLock: null,
  lastFetchAt: null,
  selectedType: null,
};

// ---------- map ----------
const map = L.map('map', { zoomControl: false }).setView([39.5, -98.35], 4);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19,
  attribution: '&copy; OpenStreetMap contributors',
}).addTo(map);
L.control.zoom({ position: 'bottomright' }).addTo(map);

const meMarker = L.marker([0, 0], {
  icon: L.divIcon({ className: '', html: '<div class="me"></div>', iconSize: [18, 18] }),
  interactive: false,
});
const radiusCircle = L.circle([0, 0], { radius: settings.radiusM, color: '#3b82f6', weight: 1, fillOpacity: 0.05 });

// ---------- API ----------
async function api(path, opts = {}) {
  const res = await fetch(path, {
    ...opts,
    headers: { 'Content-Type': 'application/json', 'X-Client-Id': clientId, ...opts.headers },
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || `Request failed (${res.status})`);
  return body;
}

async function fetchReports() {
  if (!state.position) return;
  const { lat, lng } = state.position;
  try {
    const list = await api(`/api/reports?lat=${lat}&lng=${lng}&radius=${FETCH_RADIUS_M}`);
    const seen = new Set();
    for (const r of list) {
      seen.add(r.id);
      upsertReport(r);
    }
    for (const id of state.reports.keys()) if (!seen.has(id)) removeReport(id);
    state.lastFetchAt = { ...state.position };
    checkAlerts();
  } catch (err) {
    setStatus(`Offline: ${err.message}`);
  }
}

function connectStream() {
  const es = new EventSource('/api/stream');
  es.addEventListener('added', (e) => { upsertReport(JSON.parse(e.data)); checkAlerts(); });
  es.addEventListener('updated', (e) => { upsertReport(JSON.parse(e.data)); });
  es.addEventListener('removed', (e) => removeReport(JSON.parse(e.data).id));
}

// ---------- reports on the map ----------
function upsertReport(r) {
  state.reports.set(r.id, r);
  const stale = Date.now() - r.lastConfirmedAt > 30 * 60_000;
  const icon = L.divIcon({
    className: '',
    html: `<div class="marker${stale ? ' stale' : ''}">${ICONS[r.type] ?? '🚓'}</div>`,
    iconSize: [34, 34],
  });
  let m = state.markers.get(r.id);
  if (m) {
    m.setIcon(icon);
    m.setPopupContent(popupFor(r));
  } else {
    m = L.marker([r.lat, r.lng], { icon }).bindPopup(popupFor(r)).addTo(map);
    state.markers.set(r.id, m);
  }
  renderNearest();
}

function removeReport(id) {
  state.reports.delete(id);
  state.markers.get(id)?.remove();
  state.markers.delete(id);
  renderNearest();
}

function popupFor(r) {
  const el = document.createElement('div');
  el.className = 'popup';
  const ago = Math.max(1, Math.round((Date.now() - r.lastConfirmedAt) / 60_000));
  el.innerHTML = `
    <h3>${ICONS[r.type] ?? ''} ${escapeHtml(r.label)}</h3>
    <p>${r.note ? `“${escapeHtml(r.note)}”<br>` : ''}
      Confirmed ${ago} min ago · ${r.confirms}👍 ${r.dismissals}👎 · expires in ${r.minutesLeft} min</p>
    <div class="row">
      <button class="yes">Still there</button>
      <button class="no">Not there</button>
    </div>`;
  el.querySelector('.yes').onclick = () => vote(r.id, 'confirm');
  el.querySelector('.no').onclick = () => vote(r.id, 'dismiss');
  return el;
}

async function vote(id, kind) {
  try {
    const r = await api(`/api/reports/${id}/${kind}`, { method: 'POST' });
    if (r.removed) removeReport(id);
    else upsertReport(r);
    map.closePopup();
    toast(kind === 'confirm' ? 'Thanks for confirming!' : 'Thanks — noted.');
  } catch (err) {
    toast(err.message);
  }
}

function renderNearest() {
  const el = $('nearest');
  if (!state.position || state.reports.size === 0) {
    el.textContent = state.position ? 'No reports nearby. Drive safe!' : 'Waiting for location…';
    return;
  }
  let best = null;
  for (const r of state.reports.values()) {
    const d = distanceMeters(state.position, r);
    if (!best || d < best.d) best = { r, d };
  }
  el.innerHTML = `Nearest: <strong>${ICONS[best.r.type]} ${escapeHtml(best.r.label)}</strong> · ${formatDistance(best.d)} away · ${state.reports.size} active nearby`;
}

// ---------- location ----------
function onPosition(pos) {
  const first = !state.position;
  state.position = { lat: pos.coords.latitude, lng: pos.coords.longitude };
  state.heading = pos.coords.heading;
  state.speed = pos.coords.speed;
  meMarker.setLatLng(state.position);
  radiusCircle.setLatLng(state.position);
  if (first) {
    meMarker.addTo(map);
    radiusCircle.addTo(map);
    map.setView(state.position, 14);
    fetchReports();
  } else if (state.driving) {
    map.panTo(state.position, { animate: true });
  }
  // Refetch after moving a few km so the local set stays fresh.
  if (state.lastFetchAt && distanceMeters(state.lastFetchAt, state.position) > 3_000) fetchReports();
  const kmh = state.speed != null ? ` · ${Math.round(state.speed * 3.6)} km/h` : '';
  setStatus(`${state.driving ? 'Driving mode' : 'Located'} ±${Math.round(pos.coords.accuracy)} m${kmh}`);
  renderNearest();
  checkAlerts();
}

function onPositionError(err) {
  setStatus(err.code === err.PERMISSION_DENIED ? 'Location permission denied' : 'Location unavailable');
}

function startWatching() {
  if (!('geolocation' in navigator)) return setStatus('Geolocation not supported');
  if (state.watchId != null) navigator.geolocation.clearWatch(state.watchId);
  state.watchId = navigator.geolocation.watchPosition(onPosition, onPositionError, {
    enableHighAccuracy: true,
    maximumAge: 5_000,
    timeout: 20_000,
  });
}

// ---------- alerts ----------
function checkAlerts() {
  if (!state.position) return;
  const hits = reportsToAlert({
    position: state.position,
    heading: state.heading,
    speed: state.speed,
    reports: [...state.reports.values()],
    radiusM: settings.radiusM,
    alerted: state.alerted,
  });
  if (!hits.length) return;
  for (const h of hits) state.alerted.add(h.report.id);
  const { report, distanceM } = hits[0];
  const more = hits.length > 1 ? ` (+${hits.length - 1} more)` : '';
  showAlert(`${ICONS[report.type]} ${report.label} ahead`, `${formatDistance(distanceM)} away${more}${report.note ? ` · ${report.note}` : ''}`);
  if (settings.voice && 'speechSynthesis' in window) {
    speechSynthesis.speak(new SpeechSynthesisUtterance(`${report.label} reported, ${formatDistance(distanceM).replace('km', 'kilometers').replace(' m', ' meters')} ahead`));
  }
}

let alertTimer;
function showAlert(title, sub) {
  $('alertTitle').textContent = title;
  $('alertSub').textContent = sub;
  $('alert').classList.remove('hidden');
  clearTimeout(alertTimer);
  alertTimer = setTimeout(() => $('alert').classList.add('hidden'), 12_000);
  if (settings.vibrate && navigator.vibrate) navigator.vibrate([300, 150, 300, 150, 300]);
  if (settings.sound) beep();
  if (document.hidden && 'Notification' in window && Notification.permission === 'granted') {
    new Notification(title, { body: sub, icon: '/icon.svg', tag: 'patrol-alert' });
  }
}

let audioCtx;
function beep() {
  try {
    audioCtx ??= new AudioContext();
    const t = audioCtx.currentTime;
    // Two-tone siren-style chirp.
    [880, 660, 880, 660].forEach((freq, i) => {
      const osc = audioCtx.createOscillator();
      const gain = audioCtx.createGain();
      osc.type = 'square';
      osc.frequency.value = freq;
      gain.gain.setValueAtTime(0.15, t + i * 0.18);
      gain.gain.exponentialRampToValueAtTime(0.001, t + i * 0.18 + 0.16);
      osc.connect(gain).connect(audioCtx.destination);
      osc.start(t + i * 0.18);
      osc.stop(t + i * 0.18 + 0.17);
    });
  } catch { /* audio unavailable */ }
}

// ---------- driving mode ----------
async function toggleDriving() {
  state.driving = !state.driving;
  const btn = $('startBtn');
  btn.textContent = state.driving ? 'Driving mode on' : 'Start driving mode';
  btn.classList.toggle('active', state.driving);
  if (state.driving) {
    audioCtx ??= new AudioContext(); // unlock audio on a user gesture
    audioCtx.resume?.();
    if ('Notification' in window && Notification.permission === 'default') Notification.requestPermission();
    await acquireWakeLock();
    if (state.position) map.setView(state.position, 15);
  } else {
    state.wakeLock?.release?.();
    state.wakeLock = null;
  }
  setStatus(state.driving ? 'Driving mode' : 'Located');
}

// Keep the screen on in driving mode; the browser drops the lock when the tab is hidden.
async function acquireWakeLock() {
  try {
    state.wakeLock = (await navigator.wakeLock?.request('screen')) ?? null;
    state.wakeLock?.addEventListener('release', () => { state.wakeLock = null; });
  } catch { /* not supported or denied */ }
}

// ---------- report dialog ----------
async function initReportDialog() {
  const types = await api('/api/types').catch(() => ({}));
  const grid = $('typeGrid');
  for (const [key, label] of Object.entries(types)) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'type-btn';
    b.setAttribute('aria-pressed', 'false');
    b.innerHTML = `<span class="emoji">${ICONS[key] ?? '🚓'}</span>${escapeHtml(label)}`;
    b.onclick = () => {
      state.selectedType = key;
      grid.querySelectorAll('.type-btn').forEach((x) => x.setAttribute('aria-pressed', String(x === b)));
      $('submitReport').disabled = false;
    };
    grid.append(b);
  }

  $('reportBtn').onclick = () => {
    if (!state.position) return toast('Waiting for your location…');
    state.selectedType = null;
    $('noteInput').value = '';
    $('submitReport').disabled = true;
    grid.querySelectorAll('.type-btn').forEach((x) => x.setAttribute('aria-pressed', 'false'));
    $('reportDialog').showModal();
  };

  $('reportDialog').addEventListener('close', async () => {
    if ($('reportDialog').returnValue !== 'submit' || !state.selectedType) return;
    try {
      const { report, created } = await api('/api/reports', {
        method: 'POST',
        body: JSON.stringify({ ...state.position, type: state.selectedType, note: $('noteInput').value }),
      });
      state.alerted.add(report.id); // don't alert yourself
      upsertReport(report);
      toast(created ? 'Report posted. Thanks!' : 'Already reported here — counted as a confirmation.');
    } catch (err) {
      toast(err.message);
    }
  });
}

// ---------- settings dialog ----------
function initSettings() {
  const sync = () => {
    $('radiusLabel').textContent = formatDistance(settings.radiusM);
    radiusCircle.setRadius(settings.radiusM);
    save('settings', settings);
  };
  $('radiusInput').value = settings.radiusM;
  $('soundInput').checked = settings.sound;
  $('vibrateInput').checked = settings.vibrate;
  $('voiceInput').checked = settings.voice;
  $('radiusInput').oninput = (e) => { settings.radiusM = Number(e.target.value); sync(); };
  $('soundInput').onchange = (e) => { settings.sound = e.target.checked; sync(); };
  $('vibrateInput').onchange = (e) => { settings.vibrate = e.target.checked; sync(); };
  $('voiceInput').onchange = (e) => { settings.voice = e.target.checked; sync(); };
  $('settingsBtn').onclick = () => $('settingsDialog').showModal();
  sync();
}

// ---------- helpers ----------
function setStatus(text) { $('status').textContent = text; }

let toastTimer;
function toast(text) {
  const el = $('toast');
  el.textContent = text;
  el.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add('hidden'), 3_000);
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

// ---------- boot ----------
$('alertClose').onclick = () => $('alert').classList.add('hidden');
$('startBtn').onclick = toggleDriving;
document.addEventListener('visibilitychange', async () => {
  if (!document.hidden && state.driving && !state.wakeLock) {
    await acquireWakeLock();
  }
});
initSettings();
initReportDialog();
connectStream();
startWatching();
setInterval(fetchReports, 60_000);
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});

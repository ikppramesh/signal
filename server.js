#!/usr/bin/env node
// Serves the Signal page and exposes the Mac's Wi-Fi readings. No dependencies.
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { execFile, execFileSync, spawnSync } = require('child_process');
const devices = require('./devices');

const PORT = Number(process.env.PORT) || 8787;
const ROOT = __dirname;
const APP = path.join(ROOT, 'build', 'SignalHelper.app');
const BIN = path.join(APP, 'Contents', 'MacOS', 'SignalHelper');
const SRC = path.join(ROOT, 'helper', 'SignalHelper.swift');
const DATA = path.join(ROOT, 'build', 'wifi.json');
const ALIVE = DATA + '.alive';
const PAGE = path.join(ROOT, 'public', 'index.html');
const BLOB = Buffer.alloc(1024 * 1024, 'signal');

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const percentFor = (rssi) => Math.round(clamp(2 * (rssi + 100), 0, 100));

function buildHelper() {
  const stale = !fs.existsSync(BIN) || fs.statSync(BIN).mtimeMs < fs.statSync(SRC).mtimeMs;
  if (!stale) return;
  console.log('Building the Wi-Fi helper (first run only)...');
  fs.mkdirSync(path.dirname(BIN), { recursive: true });
  fs.copyFileSync(path.join(ROOT, 'helper', 'Info.plist'), path.join(APP, 'Contents', 'Info.plist'));
  const r = spawnSync('swiftc', ['-O', SRC, '-o', BIN, '-framework', 'CoreWLAN', '-framework', 'CoreLocation', '-framework', 'AppKit'], { stdio: 'inherit' });
  if (r.status !== 0) throw new Error('Could not compile the helper. Install Xcode command line tools: xcode-select --install');
  execFileSync('codesign', ['--force', '--sign', '-', APP]);
}

function touchAlive() { fs.writeFileSync(ALIVE, String(Date.now())); }
function stopHelper() { spawnSync('pkill', ['-f', BIN]); }

let helperStarted = 0;
function startHelper() {
  stopHelper();
  touchAlive();
  helperStarted = Date.now();
  execFile('open', ['-g', '-n', APP, '--args', DATA], (err) => {
    if (err) console.error('Could not start the helper:', err.message);
  });
}

// Router latency, sampled continuously so the page can grade the local network.
const router = { ip: null, ms: null, results: [] };

function findRouter() {
  try {
    const out = execFileSync('route', ['-n', 'get', 'default'], { encoding: 'utf8' });
    router.ip = (out.match(/gateway:\s*([\d.]+)/) || [])[1] || null;
  } catch { router.ip = null; }
}

function pingOnce(host) {
  return new Promise((resolve) => execFile('ping', ['-c', '1', '-t', '1', host], (err, stdout) => {
    const m = /time=([\d.]+)/.exec(stdout || '');
    resolve(m ? Number(m[1]) : null);
  }));
}

// Drop recorder: every sample is classified, and each unbroken run of one problem becomes an event.
const DROPS = path.join(ROOT, 'build', 'drops.json');
const WEAK_BELOW = 40;
let drops = [];
try { drops = JSON.parse(fs.readFileSync(DROPS, 'utf8')); } catch {}
let ongoing = null;
const fails = { router: 0, internet: 0 };

function readWifi() {
  try {
    const w = JSON.parse(fs.readFileSync(DATA, 'utf8'));
    return Date.now() / 1000 - w.time < 10 ? w : null;
  } catch { return null; }
}

function causeFor(w) {
  if (!w || !w.powerOn) return null;
  if (!w.connected) return 'wifi';
  if (fails.router >= 2) return 'router';
  if (fails.internet >= 2) return 'internet';
  if (percentFor(w.rssi) < WEAK_BELOW) return 'weak';
  return null;
}

function finishDrop(e) {
  if (e.end - e.start < 4000) return;
  drops.push(e);
  if (drops.length > 1000) drops = drops.slice(-1000);
  fs.writeFile(DROPS, JSON.stringify(drops), () => {});
}

function track(cause, w) {
  const now = Date.now();
  const percent = w && w.connected ? percentFor(w.rssi) : 0;
  if (ongoing && ongoing.cause === cause) {
    ongoing.end = now;
    ongoing.minPercent = Math.min(ongoing.minPercent, percent);
    return;
  }
  if (ongoing) finishDrop(ongoing);
  ongoing = cause ? { cause, start: now, end: now, minPercent: percent, ssid: (w && w.ssid) || null } : null;
}

async function sample() {
  findRouter();
  const [routerMs, inet] = await Promise.all([
    router.ip ? pingOnce(router.ip) : null,
    pingOnce('1.1.1.1').then((ms) => (ms != null ? ms : pingOnce('8.8.8.8'))),
  ]);
  router.ms = routerMs;
  if (router.ip) {
    router.results.push(routerMs != null);
    if (router.results.length > 20) router.results.shift();
  } else router.results = [];
  fails.router = routerMs == null ? fails.router + 1 : 0;
  fails.internet = inet == null ? fails.internet + 1 : 0;
  const w = readWifi();
  // Restart the helper if it has stopped writing, giving it time to come up first.
  if (!w && Date.now() - helperStarted > 20000) {
    console.log('Wi-Fi helper stopped responding. Restarting it.');
    startHelper();
  }
  track(causeFor(w), w);
  setTimeout(sample, 2000);
}

function dropReport() {
  const since = Date.now() - 24 * 3600 * 1000;
  const recent = drops.filter((e) => e.end >= since);
  if (ongoing && ongoing.end - ongoing.start >= 4000) recent.push({ ...ongoing, ongoing: true });
  const counts = { wifi: 0, weak: 0, router: 0, internet: 0 };
  const seconds = { wifi: 0, weak: 0, router: 0, internet: 0 };
  for (const e of recent) { counts[e.cause]++; seconds[e.cause] += Math.round((e.end - e.start) / 1000); }
  return { counts, seconds, events: recent.slice(-20).reverse() };
}

function healthFor(w) {
  const snr = w.rssi - w.noise;
  const snrScore = clamp((snr - 10) / 30, 0, 1);
  const pingScore = router.ms == null ? 0 : clamp(1 - (router.ms - 2) / 80, 0, 1);
  const n = router.results.length;
  const loss = n ? router.results.filter((ok) => !ok).length / n : 0;
  return { score: Math.round(100 * (snrScore + pingScore) / 2 * (1 - loss)), snr, loss: Math.round(loss * 100) };
}

// Picks out the connected access point, then keeps the strongest entry per network.
function nearbyFor(w) {
  const rows = (w.nearby || []).filter((n) => n.rssi < 0);
  let current = -1;
  if (w.connected) {
    let best = Infinity;
    rows.forEach((n, i) => {
      const same = w.ssid ? n.ssid === w.ssid : n.channel === w.channel;
      const gap = Math.abs(n.rssi - w.rssi);
      if (same && n.channel === w.channel && gap < best) { best = gap; current = i; }
    });
  }
  const seen = new Map();
  rows.forEach((n, i) => {
    if (i === current || (w.ssid && n.ssid === w.ssid)) return;
    const id = n.ssid ? `s:${n.ssid}` : `c:${n.channel}:${i}`;
    if (!seen.has(id) || seen.get(id).rssi < n.rssi) {
      seen.set(id, { id, ssid: n.ssid, rssi: n.rssi, percent: percentFor(n.rssi), channel: n.channel, band: n.band, security: n.security || '' });
    }
  });
  return [...seen.values()].sort((a, b) => b.rssi - a.rssi);
}

function isHost(req) {
  const addr = (req.socket.remoteAddress || '').replace('::ffff:', '');
  if (addr === '127.0.0.1' || addr === '::1') return true;
  return Object.values(os.networkInterfaces()).flat().some((i) => i.address === addr);
}

function status(req) {
  const viewer = isHost(req) ? 'host' : 'remote';
  let w;
  try { w = JSON.parse(fs.readFileSync(DATA, 'utf8')); } catch { return { viewer, helper: false }; }
  if (Date.now() / 1000 - w.time > 10) return { viewer, helper: false };
  const nearby = nearbyFor(w);
  const out = {
    viewer, helper: true, powerOn: w.powerOn, connected: w.connected, namesAvailable: w.namesAvailable,
    ssid: w.ssid, nearby, router: { ip: router.ip, ms: router.ms },
  };
  if (w.connected) {
    Object.assign(out, {
      rssi: w.rssi, noise: w.noise, percent: percentFor(w.rssi), txRate: Math.round(w.txRate),
      channel: w.channel, band: w.band, width: w.width, security: w.security || '', health: healthFor(w),
    });
  }
  return out;
}

function send(res, code, type, body) {
  res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(body);
}

const HOSTED_ORIGIN = 'https://ir-wifi-signal.vercel.app';

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://local');
  // Lets only the hosted page read Wi-Fi status from this Mac over localhost.
  if (req.headers.origin === HOSTED_ORIGIN && ['/api/status', '/api/drops', '/api/devices'].includes(url.pathname)) {
    res.setHeader('Access-Control-Allow-Origin', HOSTED_ORIGIN);
    res.setHeader('Access-Control-Allow-Private-Network', 'true');
    if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
  }
  if (req.method !== 'GET') return send(res, 405, 'text/plain', 'Method not allowed');
  if (url.pathname === '/') return fs.readFile(PAGE, (e, b) => (e ? send(res, 500, 'text/plain', 'Page missing') : send(res, 200, 'text/html; charset=utf-8', b)));
  if (url.pathname === '/api/devices') {
    return devices.report(url.searchParams.has('scan'))
      .then((r) => send(res, 200, 'application/json', JSON.stringify(r)))
      .catch(() => send(res, 500, 'text/plain', 'Scan failed'));
  }
  if (url.pathname === '/api/drops') return send(res, 200, 'application/json', JSON.stringify(dropReport()));
  if (url.pathname === '/api/status') return send(res, 200, 'application/json', JSON.stringify(status(req)));
  if (url.pathname === '/api/ping') return send(res, 200, 'text/plain', 'ok');
  if (url.pathname === '/api/blob') {
    const kb = clamp(Number(url.searchParams.get('kb')) || 256, 1, 1024);
    return send(res, 200, 'application/octet-stream', BLOB.subarray(0, kb * 1024));
  }
  send(res, 404, 'text/plain', 'Not found');
});

function lanAddress() {
  const list = Object.entries(os.networkInterfaces()).flatMap(([name, a]) => a.map((i) => ({ name, ...i })));
  const v4 = list.filter((i) => i.family === 'IPv4' && !i.internal);
  return (v4.find((i) => i.name === 'en0') || v4[0] || {}).address;
}

buildHelper();
startHelper();
setInterval(touchAlive, 5000).unref();
sample();
devices.start();
server.listen(PORT, '0.0.0.0', () => {
  console.log(`\nSignal is running.\n  On this Mac:  http://localhost:${PORT}`);
  const ip = lanAddress();
  if (ip) console.log(`  On a phone:   http://${ip}:${PORT}  (same Wi-Fi)`);
  console.log('\nPress Ctrl+C to stop.');
});

for (const s of ['SIGINT', 'SIGTERM']) process.on(s, () => { stopHelper(); process.exit(0); });

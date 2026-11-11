// Traffic tab data for the Mac: this Mac's upload and download per day, and the
// network-wide website log written by dns-monitor.js, with approximate locations.
const fs = require('fs');
const os = require('os');
const path = require('path');
const https = require('https');
const { execFile } = require('child_process');

const BUILD = path.join(__dirname, 'build');
const USAGE = path.join(BUILD, 'usage.json');
const DNS_LOG = path.join(BUILD, 'dns-log.jsonl');
const GEO = path.join(BUILD, 'geo.json');

const dayKey = (t = Date.now()) => {
  const d = new Date(t);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};
const readJson = (f, fallback) => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return fallback; } };

/* ---------- This Mac's data use, kept per day ---------- */
const usage = readJson(USAGE, { since: Date.now(), days: {} });
usage.hours = usage.hours || {};
const hourStart = (t = Date.now()) => { const d = new Date(t); d.setMinutes(0, 0, 0); return d.getTime(); };
let prev = null;

function counters() {
  return new Promise((resolve) => {
    const iface = Object.keys(os.networkInterfaces()).includes('en0') ? 'en0' : null;
    if (!iface) return resolve(null);
    execFile('netstat', ['-ib', '-I', iface], (err, out) => {
      const line = (out || '').split('\n').find((l) => /<Link#/.test(l));
      const cols = line ? line.trim().split(/\s+/) : [];
      const rx = Number(cols[6]), tx = Number(cols[9]);
      resolve(Number.isFinite(rx) && Number.isFinite(tx) ? { rx, tx } : null);
    });
  });
}

async function sampleUsage() {
  const c = await counters();
  if (!c) return;
  // Counters reset when the Mac restarts, so only positive steps are added.
  if (prev && c.rx >= prev.rx && c.tx >= prev.tx) {
    const day = usage.days[dayKey()] || (usage.days[dayKey()] = { down: 0, up: 0 });
    day.down += c.rx - prev.rx;
    day.up += c.tx - prev.tx;
    const h = hourStart();
    const hour = usage.hours[h] || (usage.hours[h] = { down: 0, up: 0 });
    hour.down += c.rx - prev.rx;
    hour.up += c.tx - prev.tx;
    const keep = h - 8 * 86400000;
    for (const k of Object.keys(usage.hours)) if (Number(k) < keep) delete usage.hours[k];
  }
  prev = c;
}

function saveUsage() { fs.writeFile(USAGE, JSON.stringify(usage), () => {}); }

function usageReport() {
  const keys = Object.keys(usage.days).sort();
  const total = keys.reduce((s, k) => ({ down: s.down + usage.days[k].down, up: s.up + usage.days[k].up }), { down: 0, up: 0 });
  const days = [];
  for (let i = 13; i >= 0; i--) {
    const k = dayKey(Date.now() - i * 86400000);
    days.push({ date: k, ...(usage.days[k] || { down: 0, up: 0 }) });
  }
  const hours = [];
  for (let i = 23; i >= 0; i--) {
    const t = hourStart() - i * 3600000;
    hours.push({ t, ...(usage.hours[t] || { down: 0, up: 0 }) });
  }
  return { scope: 'This Mac', hours, today: usage.days[dayKey()] || { down: 0, up: 0 }, lifetime: { ...total, since: usage.since }, days, apps: null };
}

/* ---------- Locations, looked up once per address and cached ---------- */
const geo = readJson(GEO, {});
const geoQueue = [];
function locate(ip) {
  if (!ip || geo[ip] || geoQueue.includes(ip) || /^(10|127|192\.168|172\.(1[6-9]|2\d|3[01]))\./.test(ip)) return;
  geoQueue.push(ip);
}
function geoWorker() {
  const ip = geoQueue.shift();
  if (!ip) return;
  https.get(`https://ipwho.is/${ip}?fields=success,country,country_code,city,connection`, { timeout: 5000 }, (res) => {
    let body = '';
    res.on('data', (d) => { body += d; });
    res.on('end', () => {
      try {
        const g = JSON.parse(body);
        if (g.success) {
          geo[ip] = { country: g.country, code: g.country_code, city: g.city, org: (g.connection || {}).org || '' };
          fs.writeFile(GEO, JSON.stringify(geo), () => {});
        }
      } catch {}
    });
  }).on('error', () => {});
}

/* ---------- Network-wide website log ---------- */
let logCache = { size: -1, entries: [] };
function readLog() {
  let size = 0;
  try { size = fs.statSync(DNS_LOG).size; } catch { return []; }
  if (size === logCache.size) return logCache.entries;
  const lines = fs.readFileSync(DNS_LOG, 'utf8').trim().split('\n');
  const entries = [];
  for (const l of lines.slice(-200000)) { try { entries.push(JSON.parse(l)); } catch {} }
  logCache = { size, entries };
  return entries;
}

// Collapses names like "r3---sn-abc.googlevideo.com" into the site people would recognise.
function siteOf(domain) {
  const parts = domain.split('.');
  const two = parts.slice(-2).join('.');
  const twoLevelTld = /^(co|com|net|org|gov|ac|edu)\.[a-z]{2}$/.test(two);
  return parts.slice(twoLevelTld ? -3 : -2).join('.');
}

function websitesReport(nameFor) {
  const entries = readLog();
  const active = entries.length && Date.now() - entries[entries.length - 1].t < 15 * 60000;
  const since = Date.now() - 7 * 86400000, todayKey = dayKey();
  const byClient = new Map();
  let today = 0;
  for (const e of entries) {
    if (e.t < since) continue;
    if (dayKey(e.t) === todayKey) today++;
    const c = byClient.get(e.client) || { key: e.client, sites: new Map(), total: 0 };
    const site = siteOf(e.domain);
    const s = c.sites.get(site) || { domain: site, count: 0, lastSeen: 0, ip: null, names: new Set() };
    s.count++;
    s.lastSeen = Math.max(s.lastSeen, e.t);
    s.names.add(e.domain);
    if (e.ips && e.ips[0]) s.ip = e.ips[0];
    c.sites.set(site, s);
    c.total++;
    byClient.set(e.client, c);
  }
  const groups = [...byClient.values()].sort((a, b) => b.total - a.total).map((c) => {
    const sites = [...c.sites.values()].sort((a, b) => b.count - a.count).slice(0, 60);
    sites.slice(0, 25).forEach((s) => locate(s.ip));
    return {
      key: c.key, title: nameFor(c.key) || c.key, sub: c.key, total: c.total,
      domains: sites.map((s) => ({ domain: s.domain, count: s.count, lastSeen: s.lastSeen, examples: [...s.names].slice(0, 3), ...(geo[s.ip] || {}) })),
    };
  });
  return {
    scope: 'Whole network', enabled: entries.length > 0, active, today, groups,
    setup: active ? null : [
      'In Terminal, in the Signal folder, run: sudo node dns-monitor.js',
      `Open your router at http://${defaultGateway() || '192.168.29.1'} and set its DNS server to this Mac (${lanIp()}).`,
      'Give this Mac a fixed address in the router, so the setting keeps working.',
      'Turn Wi-Fi off and on for each device, so it picks up the new DNS.',
    ],
  };
}

let gatewayIp = null;
function defaultGateway() { return gatewayIp; }
function lanIp() {
  const v4 = (os.networkInterfaces().en0 || []).find((i) => i.family === 'IPv4');
  return v4 ? v4.address : 'this Mac';
}

function start(getGateway) {
  fs.mkdirSync(BUILD, { recursive: true });
  setInterval(sampleUsage, 5000).unref();
  setInterval(saveUsage, 60000).unref();
  setInterval(geoWorker, 1500).unref();
  setInterval(() => { gatewayIp = getGateway(); }, 10000).unref();
  sampleUsage();
  process.on('exit', () => { try { fs.writeFileSync(USAGE, JSON.stringify(usage)); } catch {} });
}

function report(nameFor) {
  return { usage: usageReport(), websites: websitesReport(nameFor) };
}

module.exports = { start, report };

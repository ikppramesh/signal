// Finds devices on whatever network this Mac is joined to: a ping sweep, the ARP table,
// Bonjour (mDNS) and reverse DNS. Other devices' data use is invisible from here; only the router sees it.
const fs = require('fs');
const os = require('os');
const path = require('path');
const https = require('https');
const dns = require('dns').promises;
const { execFile, spawn } = require('child_process');

const BUILD = path.join(__dirname, 'build');
const MANUF = path.join(BUILD, 'manuf.txt');
const MEMORY = path.join(BUILD, 'devices.json');
const MANUF_URL = 'https://www.wireshark.org/download/automated/data/manuf';
const BONJOUR = [
  '_airplay._tcp', '_raop._tcp', '_companion-link._tcp', '_googlecast._tcp', '_androidtvremote2._tcp',
  '_amzn-wplay._tcp', '_ipp._tcp', '_ipps._tcp', '_printer._tcp', '_pdl-datastream._tcp', '_scanner._tcp',
  '_hap._tcp', '_homekit._tcp', '_spotify-connect._tcp', '_sonos._tcp', '_smb._tcp', '_afpovertcp._tcp',
  '_ssh._tcp', '_rdlink._tcp', '_matter._tcp',
];

const run = (cmd, args, timeout = 5000) => new Promise((resolve) =>
  execFile(cmd, args, { timeout, maxBuffer: 8 << 20 }, (err, stdout) => resolve(stdout || '')));
const withTimeout = (p, ms) => Promise.race([p, new Promise((r) => setTimeout(() => r(null), ms))]);

/* ---------- Manufacturer lookup ---------- */
let vendors = null;
function loadVendors() {
  if (!fs.existsSync(MANUF)) return downloadVendors();
  vendors = new Map();
  for (const line of fs.readFileSync(MANUF, 'utf8').split('\n')) {
    const m = /^([0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2})\s+(\S+)\s*(.*)$/.exec(line);
    if (m) vendors.set(m[1], (m[3] || m[2]).trim());
  }
}
function downloadVendors() {
  const tmp = MANUF + '.part';
  https.get(MANUF_URL, (res) => {
    if (res.statusCode !== 200) return res.resume();
    res.pipe(fs.createWriteStream(tmp)).on('finish', () => { fs.renameSync(tmp, MANUF); loadVendors(); });
  }).on('error', () => {});
}
const isPrivateMac = (mac) => /^.[26ae]/i.test(mac);
function vendorFor(mac) {
  if (!mac) return '';
  if (isPrivateMac(mac)) return 'Private address';
  return (vendors && vendors.get(mac.slice(0, 8).toUpperCase())) || '';
}

/* ---------- Network facts ---------- */
function localInterface() {
  const all = os.networkInterfaces();
  const pick = (name) => (all[name] || []).find((i) => i.family === 'IPv4' && !i.internal);
  const name = pick('en0') ? 'en0' : Object.keys(all).find((n) => pick(n));
  return name ? { name, ...pick(name) } : null;
}
function hostsFor(ip, netmask) {
  const toInt = (a) => a.split('.').reduce((n, o) => n * 256 + Number(o), 0);
  const toIp = (n) => [24, 16, 8, 0].map((s) => Math.floor(n / 2 ** s) % 256).join('.');
  let mask = toInt(netmask);
  // Bigger networks are trimmed to the /24 around this Mac so a scan stays quick.
  if (mask < toInt('255.255.255.0')) mask = toInt('255.255.255.0');
  const base = toInt(ip) - (toInt(ip) % (2 ** 32 - mask));
  const size = 2 ** 32 - mask;
  const out = [];
  for (let i = 1; i < size - 1; i++) out.push(toIp(base + i));
  return out;
}
async function gateway() {
  const out = await run('route', ['-n', 'get', 'default']);
  return (/gateway:\s*([\d.]+)/.exec(out) || [])[1] || null;
}
const padMac = (m) => m.split(':').map((p) => p.padStart(2, '0')).join(':').toLowerCase();
async function arpTable() {
  const table = new Map();
  for (const line of (await run('arp', ['-an'])).split('\n')) {
    const m = /\(([\d.]+)\) at ([0-9a-f:]+) /i.exec(line);
    if (m && !/^ff:ff/i.test(m[2])) table.set(m[1], padMac(m[2]));
  }
  return table;
}
async function sweep(hosts) {
  const alive = new Set();
  let next = 0;
  const worker = async () => {
    while (next < hosts.length) {
      const ip = hosts[next++];
      if (/time=/.test(await run('ping', ['-c', '1', '-t', '1', ip], 3000))) alive.add(ip);
    }
  };
  await Promise.all(Array.from({ length: 64 }, worker));
  return alive;
}

/* ---------- Bonjour ---------- */
const unescape = (s) => s.replace(/\\(\d{3})/g, (_, d) => String.fromCharCode(Number(d))).replace(/\\(.)/g, '$1');
function browse(type) {
  return new Promise((resolve) => {
    let text = '';
    const p = spawn('dns-sd', ['-Z', type, 'local.']);
    p.stdout.on('data', (d) => { text += d; });
    p.on('error', () => resolve([]));
    setTimeout(() => {
      p.kill();
      const found = new Map();
      for (const line of text.split('\n')) {
        const srv = /^(\S.*?)\.(_[^.]+\._(?:tcp|udp))\s+SRV\s+\d+\s+\d+\s+\d+\s+(\S+)/.exec(line);
        const txt = /^(\S.*?)\.(_[^.]+\._(?:tcp|udp))\s+TXT\s+(.*)$/.exec(line);
        const hit = srv || txt;
        if (!hit) continue;
        const name = unescape(hit[1]);
        const entry = found.get(name) || { name, type, txt: {} };
        if (srv) entry.host = srv[3].replace(/\.$/, '');
        if (txt) for (const [, kv] of hit[3].matchAll(/"([^"]*)"/g)) {
          const i = kv.indexOf('=');
          if (i > 0) entry.txt[kv.slice(0, i)] = kv.slice(i + 1);
        }
        found.set(name, entry);
      }
      resolve([...found.values()]);
    }, 3500);
  });
}
async function bonjour() {
  const byIp = new Map();
  const entries = (await Promise.all(BONJOUR.map(browse))).flat().filter((e) => e.host);
  await Promise.all(entries.map(async (e) => {
    const r = await withTimeout(dns.lookup(e.host, { family: 4 }).catch(() => null), 2500);
    if (!r) return;
    const d = byIp.get(r.address) || { names: [], services: new Set(), txt: {}, host: e.host };
    d.names.push(e.name);
    d.services.add(e.type);
    Object.assign(d.txt, e.txt);
    byIp.set(r.address, d);
  }));
  return byIp;
}

/* ---------- Classification ---------- */
function classify(d) {
  const model = d.model || '';
  const s = d.services;
  const text = `${d.name} ${model} ${d.vendor}`.toLowerCase();
  if (d.isRouter) return 'Router';
  if (/^ipad/i.test(model) || /\bipad\b/.test(text)) return 'Tablet';
  if (/^iphone/i.test(model) || /\biphone\b/.test(text)) return 'Phone';
  if (/^appletv/i.test(model) || /apple ?tv/.test(text)) return 'TV';
  if (/^(mac|imac|macbook)/i.test(model) || /macbook|imac|mac mini|mac studio/.test(text) || d.isSelf) return 'Computer';
  if (['_ipp._tcp', '_ipps._tcp', '_printer._tcp', '_pdl-datastream._tcp', '_scanner._tcp'].some((t) => s.has(t)) ||
      /\b(hp|epson|canon|brother|kyocera|xerox|lexmark|ricoh)\b/.test(text)) return 'Printer';
  if (['_googlecast._tcp', '_androidtvremote2._tcp', '_amzn-wplay._tcp', '_airplay._tcp'].some((t) => s.has(t)) ||
      /\b(tv|bravia|roku|fire ?tv|chromecast|tcl|hisense|vizio)\b/.test(text)) return /speaker|nest audio|home mini/.test(text) ? 'Speaker' : 'TV';
  if (['_sonos._tcp', '_spotify-connect._tcp', '_raop._tcp'].some((t) => s.has(t)) || /homepod|sonos|echo|speaker/.test(text)) return 'Speaker';
  if (['_hap._tcp', '_homekit._tcp', '_matter._tcp'].some((t) => s.has(t)) ||
      /espressif|tuya|shelly|philips|signify|xiaomi|wyze|ring|nest|camera/.test(text)) return 'Smart home';
  if (/samsung|oneplus|oppo|vivo|realme|motorola|huawei|honor|nothing|google|android/.test(text) || isPrivateMac(d.mac || '')) return 'Phone or tablet';
  if (/intel|dell|lenovo|asus|acer|microsoft|msi|raspberry|apple/.test(text)) return 'Computer';
  return 'Unknown';
}

/* ---------- Scan ---------- */
let memory = {};
try { memory = JSON.parse(fs.readFileSync(MEMORY, 'utf8')); } catch {}
let latest = null;
let scanning = null;

async function scan() {
  const iface = localInterface();
  if (!iface) return (latest = { time: Date.now(), network: null, devices: [] });
  const [gw, alive, mdns] = await Promise.all([gateway(), sweep(hostsFor(iface.address, iface.netmask)), bonjour()]);
  const arp = await arpTable();
  const ips = new Set([...alive, ...mdns.keys(), ...arp.keys(), iface.address]);
  if (gw) ips.add(gw);
  const subnet = new Set(hostsFor(iface.address, iface.netmask));
  const selfMac = iface.mac && iface.mac !== '02:00:00:00:00:00' ? iface.mac : null;
  const key = (gw && arp.get(gw)) || gw || 'unknown';
  const known = memory[key] || (memory[key] = {});
  const now = Date.now();

  const found = await Promise.all([...ips].filter((ip) => subnet.has(ip)).map(async (ip) => {
    const isSelf = ip === iface.address;
    const b = mdns.get(ip);
    const mac = isSelf ? selfMac : arp.get(ip) || null;
    const rdns = b ? null : await withTimeout(dns.reverse(ip).then((n) => n[0]).catch(() => null), 1500);
    const txt = b ? b.txt : {};
    const name = isSelf ? os.hostname().replace(/\.local$/, '')
      : (b && (txt.fn || b.names[0])) || (rdns && rdns.replace(/\.(local|lan|home)$/i, '')) || '';
    const d = {
      ip, mac, name, isSelf, isRouter: ip === gw,
      model: txt.model || txt.rpMd || txt.md || txt.am || txt.ty || txt.usb_MDL || '',
      vendor: vendorFor(mac), services: b ? b.services : new Set(),
      status: alive.has(ip) || b || isSelf ? 'online' : 'idle',
    };
    d.type = classify(d);
    if (d.isRouter && !d.name) d.name = 'Router';
    return d;
  }));

  for (const d of found) {
    const id = d.mac || `ip:${d.ip}`;
    const prev = known[id] || { firstSeen: now };
    known[id] = { ...prev, ip: d.ip, mac: d.mac, name: d.name || prev.name || '', type: d.type === 'Unknown' ? prev.type || 'Unknown' : d.type,
      model: d.model || prev.model || '', vendor: d.vendor || prev.vendor || '', isSelf: d.isSelf, isRouter: d.isRouter, lastSeen: now, status: d.status };
  }
  const week = now - 7 * 24 * 3600 * 1000;
  for (const [id, d] of Object.entries(known)) {
    if (d.lastSeen < week) delete known[id];
    else if (d.lastSeen < now) d.status = 'offline';
  }
  fs.writeFile(MEMORY, JSON.stringify(memory), () => {});

  const order = { Router: 0, Computer: 1 };
  const devices = Object.entries(known).map(([id, d]) => ({ id, ...d }))
    .sort((a, b) => (a.status === 'offline') - (b.status === 'offline') || (order[a.type] ?? 9) - (order[b.type] ?? 9) ||
      a.ip.split('.').map(Number)[3] - b.ip.split('.').map(Number)[3]);
  latest = { time: now, network: { gateway: gw, subnet: `${iface.address} / ${iface.netmask}`, macsVisible: arp.size > 0 }, devices };
  return latest;
}

function scanNow() {
  if (!scanning) scanning = scan().catch(() => latest).finally(() => { scanning = null; });
  return scanning;
}

/* ---------- This Mac's own traffic ---------- */
const usage = { down: 0, up: 0, prev: null };
async function sampleUsage() {
  const iface = localInterface();
  if (!iface) return;
  const line = (await run('netstat', ['-ib', '-I', iface.name])).split('\n').find((l) => /<Link#/.test(l));
  const cols = line ? line.trim().split(/\s+/) : [];
  const rx = Number(cols[6]), tx = Number(cols[9]), t = Date.now();
  if (usage.prev && rx >= usage.prev.rx && tx >= usage.prev.tx) {
    const secs = (t - usage.prev.t) / 1000;
    usage.down = ((rx - usage.prev.rx) * 8) / secs / 1e6;
    usage.up = ((tx - usage.prev.tx) * 8) / secs / 1e6;
  }
  if (Number.isFinite(rx)) usage.prev = { rx, tx, t };
}

function start() {
  fs.mkdirSync(BUILD, { recursive: true });
  loadVendors();
  scanNow();
  setInterval(scanNow, 120000).unref();
  setInterval(sampleUsage, 2000).unref();
}

async function report(fresh) {
  if (fresh && (!latest || Date.now() - latest.time > 10000)) await scanNow();
  else if (!latest) await scanNow();
  return { ...latest, self: { downMbps: +usage.down.toFixed(2), upMbps: +usage.up.toFixed(2) }, scanning: Boolean(scanning) };
}

module.exports = { start, report };

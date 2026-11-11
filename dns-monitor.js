#!/usr/bin/env node
// Network-wide website log. Point your router's DNS at this Mac and every device's
// lookups pass through here: each one is forwarded unchanged and its name is recorded.
// Run with sudo (port 53 is privileged); it drops back to your user right after binding.
const dgram = require('dgram');
const fs = require('fs');
const os = require('os');
const path = require('path');

const UPSTREAM = process.env.UPSTREAM_DNS || '1.1.1.1';
const LOG = path.join(__dirname, 'build', 'dns-log.jsonl');
const MAX_LOG_BYTES = 50 * 1024 * 1024;

function lanAddress() {
  const all = Object.entries(os.networkInterfaces()).flatMap(([name, a]) => a.map((i) => ({ name, ...i })));
  const v4 = all.filter((i) => i.family === 'IPv4' && !i.internal);
  return (v4.find((i) => i.name === 'en0') || v4[0] || {}).address;
}

// Reads a DNS name starting at offset, following compression pointers.
function readName(buf, offset) {
  const labels = [];
  let jumped = false, end = offset, guard = 0;
  while (offset < buf.length && guard++ < 128) {
    const len = buf[offset];
    if (len === 0) { if (!jumped) end = offset + 1; break; }
    if ((len & 0xc0) === 0xc0) {
      if (!jumped) end = offset + 2;
      offset = ((len & 0x3f) << 8) | buf[offset + 1];
      jumped = true;
      continue;
    }
    labels.push(buf.toString('latin1', offset + 1, offset + 1 + len));
    offset += len + 1;
  }
  return { name: labels.join('.').toLowerCase(), end };
}

function parseQuestion(buf) {
  if (buf.length < 12 || buf.readUInt16BE(4) < 1) return null;
  const { name, end } = readName(buf, 12);
  return { name, type: buf.readUInt16BE(end), end: end + 4 };
}

// Pulls IPv4 answers out of a response, for the location lookup later.
function parseAnswers(buf) {
  const ips = [];
  try {
    const q = parseQuestion(buf);
    let off = q.end;
    const count = buf.readUInt16BE(6);
    for (let i = 0; i < count && off < buf.length; i++) {
      off = readName(buf, off).end;
      const type = buf.readUInt16BE(off), len = buf.readUInt16BE(off + 8);
      if (type === 1 && len === 4) ips.push([...buf.subarray(off + 10, off + 14)].join('.'));
      off += 10 + len;
    }
  } catch {}
  return ips;
}

const TYPES = { 1: 'A', 28: 'AAAA', 5: 'CNAME', 15: 'MX', 16: 'TXT', 65: 'HTTPS', 12: 'PTR', 33: 'SRV' };
const pending = new Map();

function record(entry) {
  try {
    if (fs.existsSync(LOG) && fs.statSync(LOG).size > MAX_LOG_BYTES) fs.renameSync(LOG, LOG + '.old');
    fs.appendFileSync(LOG, JSON.stringify(entry) + '\n');
  } catch {}
}

const address = process.env.DNS_BIND || lanAddress();
const server = dgram.createSocket('udp4');
const upstream = dgram.createSocket('udp4');

server.on('message', (msg, rinfo) => {
  const q = parseQuestion(msg);
  if (!q) return;
  const id = msg.readUInt16BE(0);
  const key = `${id}:${q.name}`;
  pending.set(key, { rinfo, q, t: Date.now() });
  upstream.send(msg, 53, UPSTREAM);
});

upstream.on('message', (msg) => {
  const q = parseQuestion(msg);
  if (!q) return;
  const key = `${msg.readUInt16BE(0)}:${q.name}`;
  const p = pending.get(key);
  if (!p) return;
  pending.delete(key);
  server.send(msg, p.rinfo.port, p.rinfo.address);
  if (!p.q.name || p.q.name.endsWith('.arpa') || p.q.name.endsWith('.local')) return;
  record({ t: p.t, client: p.rinfo.address, domain: p.q.name, type: TYPES[p.q.type] || String(p.q.type), ips: parseAnswers(msg) });
});

// Forget queries the upstream never answered.
setInterval(() => {
  const old = Date.now() - 10000;
  for (const [k, p] of pending) if (p.t < old) pending.delete(k);
}, 10000).unref();

server.on('error', (e) => {
  console.error(e.code === 'EACCES' ? 'Port 53 needs admin rights. Run: sudo node dns-monitor.js' : `DNS server error: ${e.message}`);
  process.exit(1);
});

server.bind(53, address, () => {
  fs.mkdirSync(path.dirname(LOG), { recursive: true });
  // Give up root now that the port is bound, so the log belongs to you.
  if (process.getuid && process.getuid() === 0 && process.env.SUDO_UID) {
    process.setgid(Number(process.env.SUDO_GID));
    process.setuid(Number(process.env.SUDO_UID));
  }
  console.log(`\nWebsite log is running on ${address}:53, forwarding to ${UPSTREAM}.`);
  console.log(`Set your router's DNS server to ${address}, then reconnect your devices.`);
  console.log('Press Ctrl+C to stop. Devices will need the router DNS changed back if this Mac is off.\n');
});

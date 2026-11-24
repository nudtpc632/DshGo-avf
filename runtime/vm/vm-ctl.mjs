// dsh-Go AVF guest control channel.
// Listens on 127.0.0.1:13081; vsock-tcp-bridge forwards vsock:3081 to it
// (node:net cannot bind AF_VSOCK directly).
// Line protocol, one JSON request per connection, one JSON response:
//   {"cmd":"status"}   -> {"ok":true,"ready":bool,"token":str|null,"baseUrl":str|null}
//   {"cmd":"save"}     -> {"ok":true,"files":{relPath: base64}}   (DSH_HOME snapshot)
//   {"cmd":"restore","files":{relPath: base64}} -> {"ok":true,"restored":n}
// The ro rootfs keeps DSH_HOME on tmpfs; the host persists snapshots across VM
// restarts (virtmgr opens disk fds O_RDONLY, so a writable data disk is not an
// option on this device). Only regular files under DSH_HOME are exchanged,
// plus the per-launch phone bridge.json under the "@phone/" key prefix
// (whitelisted tmpfs dir; never part of save).
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';

const CTL_TCP_PORT = 13081;
const DSH_HOME = process.env.DSH_HOME || '/root/.dsh';
const PHONE_STATE = '/var/lib/dsh-app/phone';
const WEB_LOG = '/tmp/dsh-web.log';
const TOKEN_RE = /^dsh web: (http:\/\/127\.0\.0\.1:[0-9]+\/)\?token=([A-Za-z0-9_-]+)/m;
const MAX_FILES_BYTES = 64 * 1024 * 1024;

let token = null;
let baseUrl = null;

function scanToken() {
  let text;
  try { text = fs.readFileSync(WEB_LOG, 'utf8'); } catch { return; }
  const m = TOKEN_RE.exec(text);
  if (m) { baseUrl = m[1]; token = m[2]; }
}
scanToken();
try {
  fs.watch(WEB_LOG, () => scanToken());
} catch {
  // log file not created yet: poll until dsh web writes it
  const t = setInterval(() => {
    scanToken();
    if (token) { clearInterval(t); try { fs.watch(WEB_LOG, () => scanToken()); } catch {} }
  }, 500);
}

function walk(dir, rel, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const r = rel ? rel + '/' + e.name : e.name;
    if (e.isDirectory()) walk(path.join(dir, e.name), r, out);
    else if (e.isFile()) out[r] = fs.readFileSync(path.join(dir, e.name)).toString('base64');
  }
  return out;
}

function handle(req) {
  switch (req.cmd) {
    case 'status':
      scanToken();
      return { ok: true, ready: token !== null, token, baseUrl };
    case 'save':
      return { ok: true, files: walk(DSH_HOME, '', {}) };
    case 'restore': {
      const files = req.files || {};
      let total = 0;
      for (const b64 of Object.values(files)) total += Buffer.from(b64, 'base64').length;
      if (total > MAX_FILES_BYTES) return { ok: false, error: 'snapshot too large' };
      let n = 0;
      for (const [rel, b64] of Object.entries(files)) {
        if (rel.startsWith('/') || rel.split('/').includes('..')) {
          return { ok: false, error: 'bad path: ' + rel };
        }
        const phone = rel.startsWith('@phone/');
        if (phone && rel !== '@phone/bridge.json') {
          return { ok: false, error: 'bad path: ' + rel };
        }
        const dest = phone ? path.join(PHONE_STATE, rel.slice('@phone/'.length)) : path.join(DSH_HOME, rel);
        fs.mkdirSync(path.dirname(dest), { recursive: true });
        fs.writeFileSync(dest, Buffer.from(b64, 'base64'), { mode: 0o600 });
        n++;
      }
      return { ok: true, restored: n };
    }
    default:
      return { ok: false, error: 'unknown cmd' };
  }
}

const server = net.createServer({ allowHalfOpen: false }, (sock) => {
  let buf = '';
  sock.on('data', (d) => {
    buf += d.toString('utf8');
    const nl = buf.indexOf('\n');
    if (nl < 0) {
      if (buf.length > 1024 * 1024) sock.destroy();
      return;
    }
    let resp;
    try { resp = handle(JSON.parse(buf.slice(0, nl))); }
    catch (e) { resp = { ok: false, error: String(e && e.message || e) }; }
    sock.end(JSON.stringify(resp) + '\n');
  });
  sock.on('error', () => {});
});
server.listen(CTL_TCP_PORT, '127.0.0.1', () => {
  console.log(`vm-ctl: tcp:${CTL_TCP_PORT} (vsock:3081 via bridge)`);
});
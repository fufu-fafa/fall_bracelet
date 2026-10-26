// FallBracelet relay: the wearer's phone uploads events and heartbeats, caregivers' phones hold a
// live Server-Sent Events stream and get alarms the moment an event arrives. Replaces Firebase in
// the PRD with something that runs on a Raspberry Pi. No dependencies beyond Node 22.
//
//   node --disable-warning=ExperimentalWarning server.mjs      (PORT, DB_PATH env vars optional)
//
// Auth: every phone gets a random bearer token when it registers or links; only its SHA-256 is
// stored. Wearers make short-lived invite codes; a caregiver types or scans one to link.

import http from 'node:http';
import crypto from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';

const PORT = Number(process.env.PORT ?? 8787);
// Home-network only for now: requests from public addresses are refused. LAN_ONLY=0 lifts it
// once the relay sits behind HTTPS (e.g. a Cloudflare Tunnel).
const LAN_ONLY = process.env.LAN_ONLY !== '0';
const DB_PATH = process.env.DB_PATH ?? new URL('./relay.db', import.meta.url).pathname;
const OFFLINE_AFTER_MS = 15 * 60_000;   // PRD: caregivers told the wearer is offline after 15 min
const ESCALATE_AFTER_MS = 2 * 60_000;   // PRD: re-push an unacknowledged FALL/SOS after 2 min
const INVITE_TTL_MS = 15 * 60_000;
const REPLAY_WINDOW_MS = 24 * 3600_000; // what a reconnecting caregiver can catch up on
const KEEPALIVE_MS = 25_000;
const ALARM_TYPES = new Set(['FALL', 'SOS']);
const MAX_ESCALATIONS = 5;

// --- Storage ----------------------------------------------------------------------------------

const db = new DatabaseSync(DB_PATH);
db.exec(`
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS wearers (
    id INTEGER PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL, last_heartbeat INTEGER, offline INTEGER NOT NULL DEFAULT 0,
    status TEXT);
  CREATE TABLE IF NOT EXISTS caregivers (
    id INTEGER PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL);
  CREATE TABLE IF NOT EXISTS links (
    wearer_id INTEGER NOT NULL REFERENCES wearers(id) ON DELETE CASCADE,
    caregiver_id INTEGER NOT NULL REFERENCES caregivers(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL, PRIMARY KEY (wearer_id, caregiver_id));
  CREATE TABLE IF NOT EXISTS invites (
    code TEXT PRIMARY KEY, wearer_id INTEGER NOT NULL REFERENCES wearers(id) ON DELETE CASCADE,
    expires_at INTEGER NOT NULL);
  CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY, wearer_id INTEGER NOT NULL REFERENCES wearers(id) ON DELETE CASCADE,
    device_address TEXT NOT NULL, seq INTEGER NOT NULL, type TEXT NOT NULL, battery_pct INTEGER,
    occurred_at INTEGER NOT NULL, received_at INTEGER NOT NULL, impact_g REAL, alarms_24h INTEGER,
    cancelled_24h INTEGER, message TEXT NOT NULL, acked_by TEXT, acked_at INTEGER,
    escalations INTEGER NOT NULL DEFAULT 0,
    UNIQUE (wearer_id, device_address, seq));
  PRAGMA foreign_keys = ON;
`);

const now = () => Date.now();
const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');
const newToken = () => crypto.randomBytes(32).toString('base64url');

const q = {
  wearerByToken: db.prepare('SELECT * FROM wearers WHERE token_hash = ?'),
  caregiverByToken: db.prepare('SELECT * FROM caregivers WHERE token_hash = ?'),
  addWearer: db.prepare('INSERT INTO wearers (name, token_hash, created_at) VALUES (?, ?, ?)'),
  renameWearer: db.prepare('UPDATE wearers SET name = ? WHERE id = ?'),
  addCaregiver: db.prepare('INSERT INTO caregivers (name, token_hash, created_at) VALUES (?, ?, ?)'),
  addInvite: db.prepare('INSERT INTO invites (code, wearer_id, expires_at) VALUES (?, ?, ?)'),
  takeInvite: db.prepare('DELETE FROM invites WHERE code = ? AND expires_at > ? RETURNING wearer_id'),
  purgeInvites: db.prepare('DELETE FROM invites WHERE expires_at <= ?'),
  link: db.prepare('INSERT OR IGNORE INTO links (wearer_id, caregiver_id, created_at) VALUES (?, ?, ?)'),
  unlink: db.prepare('DELETE FROM links WHERE wearer_id = ? AND caregiver_id = ?'),
  caregiversOf: db.prepare(`SELECT c.id, c.name, l.created_at AS linkedAt FROM links l
    JOIN caregivers c ON c.id = l.caregiver_id WHERE l.wearer_id = ? ORDER BY l.created_at`),
  wearersOf: db.prepare(`SELECT w.* FROM links l JOIN wearers w ON w.id = l.wearer_id
    WHERE l.caregiver_id = ? ORDER BY w.name`),
  isLinked: db.prepare('SELECT 1 FROM links WHERE wearer_id = ? AND caregiver_id = ?'),
  insertEvent: db.prepare(`INSERT OR IGNORE INTO events (wearer_id, device_address, seq, type, battery_pct,
    occurred_at, received_at, impact_g, alarms_24h, cancelled_24h, message)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`),
  eventByKey: db.prepare('SELECT * FROM events WHERE wearer_id = ? AND device_address = ? AND seq = ?'),
  eventById: db.prepare('SELECT * FROM events WHERE id = ?'),
  eventsOf: db.prepare('SELECT * FROM events WHERE wearer_id = ? ORDER BY occurred_at DESC, id DESC LIMIT ?'),
  eventsSince: db.prepare(`SELECT e.* FROM events e JOIN links l ON l.wearer_id = e.wearer_id
    WHERE l.caregiver_id = ? AND e.id > ? AND e.received_at > ? ORDER BY e.id`),
  ack: db.prepare('UPDATE events SET acked_by = ?, acked_at = ? WHERE id = ? AND acked_at IS NULL'),
  acksFor: db.prepare(`SELECT id, seq, device_address, acked_by, acked_at FROM events
    WHERE wearer_id = ? AND acked_at > ? ORDER BY acked_at`),
  heartbeat: db.prepare('UPDATE wearers SET last_heartbeat = ?, offline = 0, status = ? WHERE id = ?'),
  stale: db.prepare('SELECT * FROM wearers WHERE offline = 0 AND last_heartbeat IS NOT NULL AND last_heartbeat < ?'),
  markOffline: db.prepare('UPDATE wearers SET offline = 1 WHERE id = ?'),
  unacked: db.prepare(`SELECT * FROM events WHERE type IN ('FALL', 'SOS') AND acked_at IS NULL
    AND escalations < ? AND received_at < ? - ? * (escalations + 1) AND received_at > ?`),
  escalate: db.prepare('UPDATE events SET escalations = escalations + 1 WHERE id = ?'),
};

// --- Live streams -----------------------------------------------------------------------------

/** caregiverId -> Set of open SSE responses. */
const streams = new Map();

function send(res, type, data, id) {
  res.write(`${id != null ? `id: ${id}\n` : ''}event: ${type}\ndata: ${JSON.stringify(data)}\n\n`);
}

/** Push to every caregiver linked to this wearer. */
function fanOut(wearerId, type, data, id) {
  for (const c of q.caregiversOf.all(wearerId)) {
    for (const res of streams.get(c.id) ?? []) send(res, type, data, id);
  }
}

const eventJson = (e) => ({
  id: e.id, wearerId: e.wearer_id, deviceAddress: e.device_address, seq: e.seq, type: e.type,
  batteryPct: e.battery_pct, occurredAt: e.occurred_at, receivedAt: e.received_at, impactG: e.impact_g,
  alarms24h: e.alarms_24h, cancelled24h: e.cancelled_24h, message: e.message,
  ackedBy: e.acked_by, ackedAt: e.acked_at, alarm: ALARM_TYPES.has(e.type), escalations: e.escalations,
});

const wearerJson = (w) => ({
  id: w.id, name: w.name, lastHeartbeat: w.last_heartbeat, offline: !!w.offline,
  status: w.status ? JSON.parse(w.status) : null,
});

// --- HTTP -------------------------------------------------------------------------------------

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

async function body(req) {
  let raw = '';
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 64_000) throw new HttpError(413, 'body too large');
  }
  if (!raw) return {};
  try { return JSON.parse(raw); } catch { throw new HttpError(400, 'invalid JSON'); }
}

function bearer(req) {
  const m = /^Bearer (.+)$/.exec(req.headers.authorization ?? '');
  return m ? sha(m[1]) : null;
}

function wearerAuth(req) {
  const h = bearer(req);
  const w = h && q.wearerByToken.get(h);
  if (!w) throw new HttpError(401, 'wearer token required');
  return w;
}

function caregiverAuth(req) {
  const h = bearer(req);
  const c = h && q.caregiverByToken.get(h);
  if (!c) throw new HttpError(401, 'caregiver token required');
  return c;
}

const str = (v, max = 80) => (typeof v === 'string' ? v.trim().slice(0, max) : '');
const int = (v) => (Number.isFinite(Number(v)) ? Math.trunc(Number(v)) : null);

function newInviteCode(wearerId, expiresAt) {
  // 6 digits, easy to read out loud; retried on the rare clash.
  for (;;) {
    const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
    try { q.addInvite.run(code, wearerId, expiresAt); return code; } catch { /* clash, try again */ }
  }
}

const routes = {
  'GET /v1/health': () => ({ ok: true, time: now() }),

  /** The wearer's phone registers once and keeps the token. */
  'POST /v1/wearers': async (req) => {
    const b = await body(req);
    const name = str(b.name) || 'Wearer';
    const token = newToken();
    const { lastInsertRowid } = q.addWearer.run(name, sha(token), now());
    return { wearerId: Number(lastInsertRowid), token };
  },

  'PUT /v1/wearers/me': async (req) => {
    const w = wearerAuth(req);
    const name = str((await body(req)).name);
    if (name) q.renameWearer.run(name, w.id);
    return { ok: true };
  },

  'POST /v1/invites': (req) => {
    const w = wearerAuth(req);
    q.purgeInvites.run(now());
    const expiresAt = now() + INVITE_TTL_MS;
    return { code: newInviteCode(w.id, expiresAt), expiresAt };
  },

  /** A caregiver links with an invite code; send an existing caregiver token to follow another wearer. */
  'POST /v1/link': async (req) => {
    const b = await body(req);
    const row = q.takeInvite.get(str(b.code, 12), now());
    if (!row) throw new HttpError(404, 'invite code not found or expired');
    let caregiver = bearer(req) && q.caregiverByToken.get(bearer(req));
    let token = null;
    if (!caregiver) {
      token = newToken();
      const { lastInsertRowid } = q.addCaregiver.run(str(b.name) || 'Caregiver', sha(token), now());
      caregiver = { id: Number(lastInsertRowid) };
    }
    q.link.run(row.wearer_id, caregiver.id, now());
    const w = db.prepare('SELECT * FROM wearers WHERE id = ?').get(row.wearer_id);
    return { caregiverId: caregiver.id, token, wearer: wearerJson(w) };
  },

  'GET /v1/caregivers': (req) => ({ caregivers: q.caregiversOf.all(wearerAuth(req).id) }),

  'DELETE /v1/caregivers/:id': (req, p) => {
    const w = wearerAuth(req);
    const id = int(p.id);
    q.unlink.run(w.id, id);
    for (const res of streams.get(id) ?? []) send(res, 'unlinked', { wearerId: w.id });
    return { ok: true };
  },

  /** Idempotent on (wearer, deviceAddress, seq): a retry after a lost response is harmless. */
  'POST /v1/events': async (req) => {
    const w = wearerAuth(req);
    const b = await body(req);
    const seq = int(b.seq), occurredAt = int(b.occurredAt);
    const device = str(b.deviceAddress, 32), type = str(b.type, 20);
    if (seq == null || occurredAt == null || !device || !type) throw new HttpError(400, 'seq, deviceAddress, type, occurredAt required');
    const r = q.insertEvent.run(w.id, device, seq, type, int(b.batteryPct), occurredAt, now(),
      Number(b.impactG) || 0, int(b.alarms24h), int(b.cancelled24h), str(b.message, 500));
    const e = q.eventByKey.get(w.id, device, seq);
    if (r.changes) fanOut(w.id, 'event', { ...eventJson(e), wearerName: w.name }, e.id);
    return { id: e.id, duplicate: !r.changes };
  },

  /** Latest STATUS every few minutes; the reply carries acknowledgements since `acksSince`. */
  'POST /v1/heartbeat': async (req) => {
    const w = wearerAuth(req);
    const b = await body(req);
    const status = JSON.stringify(b.status ?? {});
    q.heartbeat.run(now(), status, w.id);
    if (w.offline) fanOut(w.id, 'online', { wearerId: w.id, name: w.name });
    fanOut(w.id, 'status', { wearerId: w.id, status: JSON.parse(status), at: now() });
    return { acks: q.acksFor.all(w.id, int(b.acksSince) ?? 0), caregivers: q.caregiversOf.all(w.id).length };
  },

  'GET /v1/dashboard': (req) => {
    const c = caregiverAuth(req);
    return {
      wearers: q.wearersOf.all(c.id).map((w) => ({
        ...wearerJson(w),
        events: q.eventsOf.all(w.id, 100).map(eventJson),
      })),
    };
  },

  'POST /v1/events/:id/ack': (req, p) => {
    const c = caregiverAuth(req);
    const e = q.eventById.get(int(p.id));
    if (!e || !q.isLinked.get(e.wearer_id, c.id)) throw new HttpError(404, 'event not found');
    if (q.ack.run(c.name, now(), e.id).changes) {
      fanOut(e.wearer_id, 'ack', { eventId: e.id, wearerId: e.wearer_id, by: c.name, at: now() });
    }
    return { ok: true };
  },

  /** Live stream for a caregiver. Reconnect with Last-Event-ID to replay what was missed. */
  'GET /v1/stream': (req, p, res) => {
    const c = caregiverAuth(req);
    res.writeHead(200, {
      'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    res.write('retry: 5000\n\n');
    send(res, 'hello', { caregiverId: c.id, wearers: q.wearersOf.all(c.id).map(wearerJson) });
    const lastId = int(req.headers['last-event-id']);
    if (lastId != null) {
      const names = new Map(q.wearersOf.all(c.id).map((w) => [w.id, w.name]));
      for (const e of q.eventsSince.all(c.id, lastId, now() - REPLAY_WINDOW_MS)) {
        send(res, 'event', { ...eventJson(e), wearerName: names.get(e.wearer_id), replay: true }, e.id);
      }
    }
    if (!streams.has(c.id)) streams.set(c.id, new Set());
    streams.get(c.id).add(res);
    const keepalive = setInterval(() => res.write(': keepalive\n\n'), KEEPALIVE_MS);
    req.on('close', () => {
      clearInterval(keepalive);
      streams.get(c.id)?.delete(res);
    });
    return undefined; // response stays open
  },
};

function match(method, path) {
  for (const [key, handler] of Object.entries(routes)) {
    const [m, pattern] = key.split(' ');
    if (m !== method) continue;
    const names = [];
    const re = new RegExp('^' + pattern.replace(/:(\w+)/g, (_, n) => (names.push(n), '([^/]+)')) + '$');
    const hit = re.exec(path);
    if (hit) return [handler, Object.fromEntries(names.map((n, i) => [n, decodeURIComponent(hit[i + 1])]))];
  }
  return [null, {}];
}

/** Loopback, link-local and private (RFC 1918 / ULA) addresses: the same network as the Pi. */
function isLocal(addr = '') {
  const a = addr.replace(/^::ffff:/, '');
  if (/^(127\.|10\.|192\.168\.|169\.254\.)/.test(a)) return true;
  const m = /^172\.(\d+)\./.exec(a);
  if (m && Number(m[1]) >= 16 && Number(m[1]) <= 31) return true;
  return a === '::1' || /^f[cd]/i.test(a) || /^fe[89ab]/i.test(a);
}

const server = http.createServer(async (req, res) => {
  if (LAN_ONLY && !isLocal(req.socket.remoteAddress)) {
    res.writeHead(403, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'this relay only accepts phones on the home network' }));
    return;
  }
  const path = new URL(req.url, 'http://x').pathname;
  const [handler, params] = match(req.method, path);
  try {
    if (!handler) throw new HttpError(404, 'not found');
    const out = await handler(req, params, res);
    if (out !== undefined) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(out));
    }
  } catch (err) {
    const status = err.status ?? 500;
    if (status === 500) console.error(err);
    if (!res.headersSent) res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: err.message }));
  }
});

// --- Timers: offline wearers and unacknowledged alarms ----------------------------------------

setInterval(() => {
  for (const w of q.stale.all(now() - OFFLINE_AFTER_MS)) {
    q.markOffline.run(w.id);
    fanOut(w.id, 'offline', { wearerId: w.id, name: w.name, lastHeartbeat: w.last_heartbeat });
    console.log(`wearer ${w.id} offline`);
  }
  for (const e of q.unacked.all(MAX_ESCALATIONS, now(), ESCALATE_AFTER_MS, now() - REPLAY_WINDOW_MS)) {
    q.escalate.run(e.id);
    const w = db.prepare('SELECT name FROM wearers WHERE id = ?').get(e.wearer_id);
    fanOut(e.wearer_id, 'event', { ...eventJson(q.eventById.get(e.id)), wearerName: w?.name, escalated: true }, e.id);
    console.log(`event ${e.id} escalated`);
  }
}, 30_000).unref();

server.listen(PORT, () => console.log(`relay listening on :${PORT}, db ${DB_PATH}${LAN_ONLY ? ', home network only' : ''}`));

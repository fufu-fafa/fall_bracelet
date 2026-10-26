// End-to-end check of the relay against a throwaway database:
//   node --disable-warning=ExperimentalWarning test.mjs
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const PORT = 18787;
const base = `http://127.0.0.1:${PORT}`;
const server = spawn(process.execPath, ['--disable-warning=ExperimentalWarning', new URL('./server.mjs', import.meta.url).pathname], {
  env: { ...process.env, PORT: String(PORT), DB_PATH: join(tmpdir(), `relay-test-${process.pid}.db`) },
  stdio: ['ignore', 'pipe', 'inherit'],
});
await new Promise((ok) => server.stdout.once('data', ok));

async function api(method, path, token, body) {
  const r = await fetch(base + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: r.status, json: await r.json() };
}

/** Collects SSE messages from the caregiver stream. */
async function listen(token, lastEventId) {
  const ctrl = new AbortController();
  const r = await fetch(base + '/v1/stream', {
    headers: { Authorization: `Bearer ${token}`, ...(lastEventId ? { 'Last-Event-ID': String(lastEventId) } : {}) },
    signal: ctrl.signal,
  });
  const got = [];
  (async () => {
    let buf = '';
    try {
      for await (const chunk of r.body) {
        buf += Buffer.from(chunk).toString();
        let i;
        while ((i = buf.indexOf('\n\n')) >= 0) {
          const block = buf.slice(0, i); buf = buf.slice(i + 2);
          const type = /^event: (.+)$/m.exec(block)?.[1];
          const data = /^data: (.+)$/m.exec(block)?.[1];
          if (type) got.push({ type, data: JSON.parse(data) });
        }
      }
    } catch { /* aborted */ }
  })();
  return { got, close: () => ctrl.abort() };
}
const wait = (ms) => new Promise((ok) => setTimeout(ok, ms));
const fall = (seq) => ({ deviceAddress: 'F4:65:0B:EA:D1:BE', seq, type: 'FALL', batteryPct: 80, occurredAt: Date.now(), impactG: 3.9, message: 'FALL detected — test' });

try {
  const health = (await api('GET', '/v1/health')).json;
  assert.equal(health.service, 'fallbracelet-relay');
  assert.match(health.relayId, /^[0-9a-f]{16}$/);

  const w = (await api('POST', '/v1/wearers', null, { name: 'Grandma' })).json;
  assert.ok(w.token);

  assert.equal((await api('POST', '/v1/link', null, { code: '000000', name: 'X' })).status, 404, 'bad code refused');
  const invite = (await api('POST', '/v1/invites', w.token)).json;
  const c = (await api('POST', '/v1/link', null, { code: invite.code, name: 'Sara' })).json;
  assert.ok(c.token);
  assert.equal(c.wearer.name, 'Grandma');
  assert.equal((await api('POST', '/v1/link', null, { code: invite.code, name: 'Y' })).status, 404, 'invite is single use');
  assert.equal((await api('GET', '/v1/caregivers', w.token)).json.caregivers.length, 1);
  assert.equal((await api('GET', '/v1/dashboard', w.token)).status, 401, 'wearer token is not a caregiver token');

  const s = await listen(c.token);
  await wait(200);
  const first = (await api('POST', '/v1/events', w.token, fall(1))).json;
  assert.equal(first.duplicate, false);
  assert.equal((await api('POST', '/v1/events', w.token, fall(1))).json.duplicate, true, 'resend is a duplicate');
  await api('POST', '/v1/heartbeat', w.token, { status: { state: 'MONITORING', batteryPct: 80 } });
  await wait(300);
  const events = s.got.filter((m) => m.type === 'event');
  assert.equal(events.length, 1, 'caregiver alarmed once, not twice');
  assert.equal(events[0].data.alarm, true);
  assert.equal(events[0].data.wearerName, 'Grandma');
  assert.ok(s.got.some((m) => m.type === 'status'));

  await api('POST', `/v1/events/${first.id}/ack`, c.token);
  await wait(200);
  assert.ok(s.got.some((m) => m.type === 'ack' && m.data.by === 'Sara'));
  const hb = (await api('POST', '/v1/heartbeat', w.token, { status: {}, acksSince: 0 })).json;
  assert.equal(hb.acks[0].acked_by, 'Sara', 'wearer learns who saw it');
  s.close();

  // Missed while disconnected: replayed with Last-Event-ID.
  const second = (await api('POST', '/v1/events', w.token, { ...fall(2), type: 'SOS' })).json;
  const s2 = await listen(c.token, first.id);
  await wait(300);
  assert.ok(s2.got.some((m) => m.type === 'event' && m.data.id === second.id && m.data.replay), 'missed event replayed');
  s2.close();

  const dash = (await api('GET', '/v1/dashboard', c.token)).json;
  assert.equal(dash.wearers[0].events.length, 2);
  assert.equal(dash.wearers[0].events.find((e) => e.id === first.id).ackedBy, 'Sara');

  await api('DELETE', `/v1/caregivers/${c.caregiverId}`, w.token);
  assert.equal((await api('GET', '/v1/dashboard', c.token)).json.wearers.length, 0, 'unlinked caregiver sees nothing');

  console.log('relay tests passed');
} finally {
  server.kill();
}

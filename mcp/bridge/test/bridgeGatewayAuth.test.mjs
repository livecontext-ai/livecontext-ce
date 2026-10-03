/**
 * Unit tests of the bridge authentication front (lib/bridgeSecurity.mjs) and of the toolset /
 * identity decisions the execute handler takes from it (LC-001 / LC-022). The real server.mjs is
 * exercised over HTTP in bridgeServerBoot.test.mjs; this file covers the decisions that need a
 * handler (restricted vs unrestricted, which identity a run acts as) and the building blocks
 * (secret source, enforcement mode, replay cache, signature parity).
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, rmSync, mkdirSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import express from 'express';

import {
  gatewaySignedHeaders, resolveRestrictedToolset, verifyGatewaySignature, UNRESTRICTED_PROVIDER_ID,
} from '../lib/gatewayAuth.mjs';
import {
  installBridgeSecurity, createSecretSource, createReplayCache, enforcementMode, resolveRequestIdentity,
} from '../lib/bridgeSecurity.mjs';
import { bridgeSignature, BRIDGE_SIGNATURE_HEADER } from '../lib/bridgeSignature.mjs';

const SECRET = 'test-gateway-hmac-key-for-unit-tests';
const __dirname = dirname(fileURLToPath(import.meta.url));

/** Express app with the REAL security front and a handler that takes the server's decisions. */
function startApp({ mode = 'on', secret = SECRET } = {}) {
  const app = express();
  installBridgeSecurity(app, {
    secrets: { inboundSecret: () => secret, platformSecret: () => secret },
    mode, logger: { warn() {} },
  });
  app.post('/api/bridge/execute', (req, res) => {
    const identity = resolveRequestIdentity(req.gatewayAuth, req.body || {}, req.headers);
    res.json({
      restrictedToolset: resolveRestrictedToolset(req.gatewayAuth, req.body?.credentials),
      identity,
    });
  });
  return new Promise(done => {
    const server = app.listen(0, '127.0.0.1', () => {
      done({ url: `http://127.0.0.1:${server.address().port}/api/bridge/execute`, close: () => new Promise(d => server.close(d)) });
    });
  });
}

function signed({ providerId = 'bridge-client', userId = 'tenant-1', organizationId = '', organizationRole = '',
  body = {}, v2 = true } = {}) {
  const raw = JSON.stringify(body);
  const headers = {
    'Content-Type': 'application/json',
    'X-User-ID': userId,
    ...gatewaySignedHeaders({ secretKey: SECRET, providerId, userId, organizationId }),
  };
  if (organizationId) headers['X-Organization-ID'] = organizationId;
  if (organizationRole) headers['X-Organization-Role'] = organizationRole;
  if (v2) {
    headers[BRIDGE_SIGNATURE_HEADER] = bridgeSignature({
      secretKey: SECRET, method: 'POST', path: '/api/bridge/execute', headers,
      timestamp: headers['X-Gateway-Timestamp'], body: raw,
    });
  }
  return { headers, body: raw };
}

async function post(app, { headers, body }) {
  const res = await fetch(app.url, { method: 'POST', headers, body });
  return { status: res.status, json: await res.json() };
}

// ─── LC-022: restricted by default ────────────────────────────────────────

test('a signed run WITHOUT the unrestricted provider id is restricted, even if the body tries to widen', async () => {
  const app = await startApp();
  try {
    const r = await post(app, signed({ body: { credentials: { __restrictedToolset__: false } } }));
    assert.equal(r.status, 200);
    assert.equal(r.json.restrictedToolset, true);
  } finally { await app.close(); }
});

test('the unrestricted provider id (body-bound) unlocks host tools, unless the body asks to be restricted', async () => {
  const app = await startApp();
  try {
    const open = await post(app, signed({ providerId: UNRESTRICTED_PROVIDER_ID }));
    assert.equal(open.json.restrictedToolset, false);
    const tightened = await post(app, signed({ providerId: UNRESTRICTED_PROVIDER_ID, body: { credentials: { __restrictedToolset__: true } } }));
    assert.equal(tightened.json.restrictedToolset, true);
  } finally { await app.close(); }
});

test('a v1-only signature with the unrestricted provider id stays restricted (body not bound)', async () => {
  const app = await startApp();
  try {
    const r = await post(app, signed({ providerId: UNRESTRICTED_PROVIDER_ID, v2: false }));
    assert.equal(r.status, 200);
    assert.equal(r.json.restrictedToolset, true);
  } finally { await app.close(); }
});

test('enforcement off never grants the unrestricted toolset', async () => {
  const app = await startApp({ mode: 'off' });
  try {
    const r = await post(app, { headers: { 'Content-Type': 'application/json', 'X-Provider-ID': UNRESTRICTED_PROVIDER_ID }, body: '{}' });
    assert.equal(r.json.restrictedToolset, true);
  } finally { await app.close(); }
});

// ─── LC-001: which identity a run acts as ─────────────────────────────────

test('a verified run acts as the SIGNED identity; the organization role comes only from a signed header', async () => {
  const app = await startApp();
  try {
    const r = await post(app, signed({ organizationId: 'org_7', organizationRole: 'OWNER', body: { prompt: 'x' } }));
    assert.deepEqual(r.json.identity, { tenantId: 'tenant-1', organizationId: 'org_7', organizationRole: 'OWNER' });
    // v1 does not bind X-Organization-Role, so a v1-only request gets no role at all, and a body
    // organizationRole is never used for a verified request.
    const v1 = await post(app, signed({ organizationId: 'org_7', organizationRole: 'OWNER', v2: false, body: { organizationRole: 'OWNER' } }));
    assert.equal(v1.json.identity.organizationRole, '');
  } finally { await app.close(); }
});

test('a tampered X-Organization-Role invalidates the body-bound signature', async () => {
  const app = await startApp();
  try {
    const s = signed({ organizationId: 'org_7', organizationRole: 'MEMBER' });
    s.headers['X-Organization-Role'] = 'OWNER';
    const r = await post(app, s);
    assert.equal(r.status, 401);
    assert.equal(r.json.reason, 'bad_body_signature');
  } finally { await app.close(); }
});

test('resolveRequestIdentity keeps the legacy header-or-body resolution only for unverified requests', () => {
  assert.deepEqual(
    resolveRequestIdentity({ verified: false }, { tenantId: 't', organizationId: 'b', organizationRole: 'r' }, { 'x-organization-id': 'h' }),
    { tenantId: 't', organizationId: 'h', organizationRole: 'r' },
  );
  assert.deepEqual(
    resolveRequestIdentity({ verified: true, userId: 'u', organizationId: '', organizationRole: '' }, { tenantId: 'u', organizationRole: 'OWNER' }, {}),
    { tenantId: 'u', organizationId: '', organizationRole: '' },
  );
});

// ─── building blocks ──────────────────────────────────────────────────────

test('enforcementMode: explicit true/false, unset = auto', () => {
  assert.equal(enforcementMode({ BRIDGE_REQUIRE_GATEWAY_AUTH: 'true' }), 'on');
  assert.equal(enforcementMode({ BRIDGE_REQUIRE_GATEWAY_AUTH: 'FALSE' }), 'off');
  assert.equal(enforcementMode({}), 'auto');
  assert.equal(enforcementMode({ BRIDGE_REQUIRE_GATEWAY_AUTH: ' ' }), 'auto');
});

test('secret source: systemd credential wins over the env, the CE shared file is read lazily', () => {
  const dir = mkdtempSync(join(tmpdir(), 'bridge-secret-'));
  try {
    const creds = join(dir, 'creds');
    mkdirSync(creds);
    writeFileSync(join(creds, 'gateway-secret'), 'from-credential\n');
    const a = createSecretSource({ env: { CREDENTIALS_DIRECTORY: creds, GATEWAY_SECRET_KEY: 'from-env' } });
    assert.equal(a.platformSecret(), 'from-credential');
    assert.equal(a.platformSecretSource(), 'systemd-credential');

    const shared = join(dir, 'bridge-shared-secret');
    const b = createSecretSource({ env: { BRIDGE_SHARED_SECRET_FILE: shared } });
    assert.equal(b.inboundSecret(), '', 'file not there yet (bridge container starts first)');
    writeFileSync(shared, 'per-install\n');
    assert.equal(b.inboundSecret(), 'per-install', 'picked up once the app container wrote it');
    assert.equal(b.platformSecret(), '', 'the CE shared secret is never used for outbound signing');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('replay cache: a key is refused inside its window and forgotten after it', () => {
  let now = 1_000;
  const cache = createReplayCache({ ttlMs: 100, now: () => now });
  assert.equal(cache.checkAndRemember('a'), false);
  assert.equal(cache.checkAndRemember('a'), true);
  now += 101;
  assert.equal(cache.checkAndRemember('a'), false, 'expired entry is not a replay');
  const bounded = createReplayCache({ ttlMs: 10_000, maxEntries: 3, now: () => now });
  for (const k of ['1', '2', '3', '4', '5']) bounded.checkAndRemember(k);
  assert.ok(bounded.size() <= 4, 'the cache stays bounded');
});

test('v1 verification accepts exactly the Java signer output (shared golden fixture)', () => {
  const fixture = JSON.parse(readFileSync(resolve(__dirname, '..', '..', '..', 'shared', 'contracts', 'gateway-signature-fixtures.json'), 'utf8'));
  for (const c of fixture.cases) {
    const r = verifyGatewaySignature({
      secretKey: fixture.secretKey, signature: c.expectedSignature, providerId: c.providerId,
      timestamp: c.timestamp, userId: c.userId, organizationId: c.organizationId, nowMs: Number(c.timestamp),
    });
    assert.equal(r.ok, true, `${c.name}: ${r.reason}`);
  }
});

test('body-bound signature matches the golden fixture the Java BridgeRequestSignature is pinned to', () => {
  const fixture = JSON.parse(readFileSync(resolve(__dirname, '..', '..', '..', 'shared', 'contracts', 'bridge-signature-fixtures.json'), 'utf8'));
  assert.ok(fixture.cases.length >= 3);
  for (const c of fixture.cases) {
    assert.equal(bridgeSignature({ secretKey: fixture.secretKey, ...c }), c.expectedSignature, c.name);
  }
});

test('the provider id literal matches the Java twin', () => {
  assert.equal(UNRESTRICTED_PROVIDER_ID, 'bridge-unrestricted');
});

// ─── wiring into server.mjs (behaviour is in bridgeServerBoot.test.mjs) ───

test('server.mjs mounts the security front before any other body parser and takes the toolset from it', () => {
  const server = readFileSync(resolve(__dirname, '..', 'server.mjs'), 'utf8');
  assert.match(server, /installBridgeSecurity\(app, \{ secrets: SECRETS, mode: AUTH_MODE, bodyLimit: BODY_LIMIT \}\);/);
  assert.doesNotMatch(server, /app\.use\(express\.json/, 'the only JSON parser is the one inside the security front');
  assert.match(server, /const restrictedToolset = resolveRestrictedToolset\(req\.gatewayAuth, credentials\);/);
  assert.match(server, /AGENT_SHELL_ENABLED: restrictedToolset \? '' : \(process\.env\.AGENT_SHELL_ENABLED \|\| ''\)/);
});

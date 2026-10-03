// Tests for the gateway HMAC signer (lib/gatewayAuth.mjs).
//
// The parity block reads shared/contracts/gateway-signature-fixtures.json - the
// SAME fixture consumed by the Java twin GatewaySignatureParityTest. If the JS and
// Java HMAC implementations ever drift, one side fails against the shared golden.
//
// Run with: node --test mcp/bridge/lib/__tests__/gatewayAuth.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gatewaySignedHeaders, internalSignedHeaders } from '../gatewayAuth.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));

function locateFixture() {
  let here = __dirname;
  for (let i = 0; i < 6; i++) {
    const candidate = resolve(here, 'shared/contracts/gateway-signature-fixtures.json');
    if (existsSync(candidate)) return candidate;
    const parent = dirname(here);
    if (parent === here) break;
    here = parent;
  }
  throw new Error(`gateway-signature-fixtures.json not found from ${__dirname}`);
}

const fixture = JSON.parse(readFileSync(locateFixture(), 'utf8'));

test('cross-language parity: each fixture case reproduces the golden signature', () => {
  assert.ok(fixture.cases.length > 0, 'fixture has cases');
  for (const c of fixture.cases) {
    const headers = gatewaySignedHeaders({
      secretKey: fixture.secretKey,
      providerId: c.providerId,
      userId: c.userId,
      organizationId: c.organizationId,
      timestampMs: Number(c.timestamp),
    });
    assert.equal(headers['X-Gateway-Secret'], c.expectedSignature, `case "${c.name}" signature`);
    assert.equal(headers['X-Gateway-Timestamp'], String(c.timestamp), `case "${c.name}" timestamp echoed`);
    assert.equal(headers['X-Provider-ID'], c.providerId, `case "${c.name}" provider echoed`);
  }
});

test('signature changes when userId changes (binds the user)', () => {
  const base = { secretKey: 's3cr3t', providerId: 'p', organizationId: 'o', timestampMs: 1700000000000 };
  const a = gatewaySignedHeaders({ ...base, userId: '1' })['X-Gateway-Secret'];
  const b = gatewaySignedHeaders({ ...base, userId: '2' })['X-Gateway-Secret'];
  assert.notEqual(a, b);
});

test('signature changes when organizationId changes (binds the org)', () => {
  const base = { secretKey: 's3cr3t', providerId: 'p', userId: 'u', timestampMs: 1700000000000 };
  const a = gatewaySignedHeaders({ ...base, organizationId: 'orgA' })['X-Gateway-Secret'];
  const b = gatewaySignedHeaders({ ...base, organizationId: 'orgB' })['X-Gateway-Secret'];
  assert.notEqual(a, b);
});

test('signature is "gw_"-prefixed url-safe base64 with no padding', () => {
  const sig = gatewaySignedHeaders({ secretKey: 'k', providerId: 'p', userId: 'u', timestampMs: 1 })['X-Gateway-Secret'];
  assert.match(sig, /^gw_[A-Za-z0-9_-]+$/, 'url-safe alphabet, no + / or = padding');
});

test('empty secret → provider-id-only fallback (no signature headers)', () => {
  const h = gatewaySignedHeaders({ secretKey: '', providerId: 'internal-credit-client', userId: '42' });
  assert.deepEqual(h, { 'X-Provider-ID': 'internal-credit-client' });
  assert.equal(h['X-Gateway-Secret'], undefined);
  assert.equal(h['X-Gateway-Timestamp'], undefined);
});

test('null user/org coerce to empty string (match Java safeUser/safeOrg)', () => {
  const withNulls = gatewaySignedHeaders({ secretKey: 'k', providerId: 'p', userId: null, organizationId: null, timestampMs: 1700000000000 })['X-Gateway-Secret'];
  const withEmpties = gatewaySignedHeaders({ secretKey: 'k', providerId: 'p', userId: '', organizationId: '', timestampMs: 1700000000000 })['X-Gateway-Secret'];
  assert.equal(withNulls, withEmpties);
});

test('numeric and string userId of the same value sign identically (String coercion)', () => {
  const asNum = gatewaySignedHeaders({ secretKey: 'k', providerId: 'p', userId: 42, timestampMs: 1700000000000 })['X-Gateway-Secret'];
  const asStr = gatewaySignedHeaders({ secretKey: 'k', providerId: 'p', userId: '42', timestampMs: 1700000000000 })['X-Gateway-Secret'];
  assert.equal(asNum, asStr);
});

// --- internalSignedHeaders: the wiring guarantee (sent identity == signed identity) ---

test('internalSignedHeaders: with org, sends X-User-ID + X-Organization-ID and signs the SAME org', () => {
  const args = { secretKey: 'k', providerId: 'internal-credit-client', userId: '42', organizationId: 'org_7', timestampMs: 1700000000000 };
  const h = internalSignedHeaders({ ...args, extra: { Accept: 'application/json' } });
  assert.equal(h['X-User-ID'], '42');
  assert.equal(h['X-Organization-ID'], 'org_7');
  assert.equal(h['Accept'], 'application/json');
  // The signature MUST be the one computed over the org we actually send - proving
  // sent-identity and signed-identity cannot diverge.
  const expected = gatewaySignedHeaders(args)['X-Gateway-Secret'];
  assert.equal(h['X-Gateway-Secret'], expected);
});

test('internalSignedHeaders: empty org → no X-Organization-ID header, signature over org=""', () => {
  const args = { secretKey: 'k', providerId: 'internal-credit-client', userId: '42', organizationId: '', timestampMs: 1700000000000 };
  const h = internalSignedHeaders(args);
  assert.equal(h['X-User-ID'], '42');
  assert.equal('X-Organization-ID' in h, false, 'org header omitted when empty (filter reads missing as "")');
  const expected = gatewaySignedHeaders(args)['X-Gateway-Secret'];
  assert.equal(h['X-Gateway-Secret'], expected);
});

test('internalSignedHeaders: no secret → still sends X-User-ID + provider-id, no signature', () => {
  const h = internalSignedHeaders({ secretKey: '', providerId: 'internal-credit-client', userId: '42' });
  assert.equal(h['X-User-ID'], '42');
  assert.equal(h['X-Provider-ID'], 'internal-credit-client');
  assert.equal(h['X-Gateway-Secret'], undefined);
});

// --- v2 (CASA LC-035): parity with the Java GatewaySignatureV2 via the shared fixture ---

import { gatewaySignatureV2, withGatewaySignatureV2, GATEWAY_SIGNATURE_V2_HEADER } from '../gatewayAuth.mjs';

test('v2 cross-language parity: each v2Cases entry reproduces the golden signature', () => {
  assert.ok(fixture.v2Cases.length > 0, 'fixture has v2 cases');
  for (const c of fixture.v2Cases) {
    const sig = gatewaySignatureV2({ secretKey: fixture.secretKey, method: c.method, path: c.path, query: c.query, headers: c.headers, timestamp: c.timestamp });
    assert.equal(sig, c.expectedSignature, `case "${c.name}"`);
  }
});

test('v2: header lookup is case-insensitive (agent-cli sends X-User-Id)', () => {
  const base = { secretKey: 'k', method: 'POST', path: '/api/agent/cli/tool', timestamp: '1' };
  const a = gatewaySignatureV2({ ...base, headers: { 'X-Provider-ID': 'p', 'X-User-ID': '42' } });
  const b = gatewaySignatureV2({ ...base, headers: { 'x-provider-id': 'p', 'X-User-Id': '42' } });
  assert.equal(a, b);
});

test('withGatewaySignatureV2: signs the final headers, method and URL', () => {
  const headers = { ...gatewaySignedHeaders({ secretKey: 'k', providerId: 'p', userId: '42', timestampMs: 1700000000000 }), 'X-User-ID': '42', 'X-User-Roles': 'USER' };
  const out = withGatewaySignatureV2(headers, { secretKey: 'k', method: 'GET', url: 'http://auth:8083/api/credits/balance?x=1' });
  const expected = gatewaySignatureV2({ secretKey: 'k', method: 'GET', path: '/api/credits/balance', query: 'x=1', headers, timestamp: '1700000000000' });
  assert.equal(out[GATEWAY_SIGNATURE_V2_HEADER], expected);
  const otherPath = withGatewaySignatureV2(headers, { secretKey: 'k', method: 'GET', url: 'http://auth:8083/api/credits/other?x=1' });
  assert.notEqual(otherPath[GATEWAY_SIGNATURE_V2_HEADER], expected, 'path is bound');
});

test('withGatewaySignatureV2: no secret or no v1 timestamp -> no v2 header', () => {
  const unsigned = withGatewaySignatureV2({ 'X-User-ID': '1' }, { secretKey: 'k', method: 'GET', url: 'http://x/y' });
  assert.equal(unsigned[GATEWAY_SIGNATURE_V2_HEADER], undefined);
  const noSecret = withGatewaySignatureV2({ 'X-Gateway-Timestamp': '1', 'X-Provider-ID': 'p' }, { secretKey: '', method: 'GET', url: 'http://x/y' });
  assert.equal(noSecret[GATEWAY_SIGNATURE_V2_HEADER], undefined);
});

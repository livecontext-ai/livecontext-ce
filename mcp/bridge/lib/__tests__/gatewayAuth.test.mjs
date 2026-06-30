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

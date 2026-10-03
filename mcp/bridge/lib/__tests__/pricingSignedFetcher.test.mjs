// The bridge reads auth-service's pricing snapshot directly (no gateway hop). auth-service can
// require the gateway HMAC on its whole /api/internal/auth/ prefix, so the read must be signed
// exactly like the balance refresh: an unsigned read would be refused and the budget guards
// would fall back to the default rates.
//
// Run with: node --test mcp/bridge/lib/__tests__/pricingSignedFetcher.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { signedSnapshotFetcher, PRICING_SNAPSHOT_PROVIDER_ID, PricingCache } from '../pricing.js';
import {
  verifyGatewaySignature,
  gatewaySignatureV2,
  GATEWAY_SIGNATURE_V2_HEADER,
} from '../gatewayAuth.mjs';

const SECRET = 'pricing-snapshot-test-secret-0123456789';
const URL_ = 'http://auth:8083/api/internal/auth/pricing/snapshot';

function recordingFetch(body = { rates: [{ provider: 'openai', model: 'gpt-5', inputRate: 1, outputRate: 2 }] }) {
  const calls = [];
  const impl = async (url, init) => {
    calls.push({ url, headers: init.headers });
    return { ok: true, status: 200, json: async () => body };
  };
  return { calls, impl };
}

test('signs the snapshot read with v1 headers that auth-service verifies', async () => {
  const { calls, impl } = recordingFetch();
  await signedSnapshotFetcher(SECRET, impl)(URL_);

  const h = calls[0].headers;
  assert.equal(h['X-Provider-ID'], PRICING_SNAPSHOT_PROVIDER_ID);
  const verdict = verifyGatewaySignature({
    secretKey: SECRET,
    signature: h['X-Gateway-Secret'],
    providerId: h['X-Provider-ID'],
    timestamp: h['X-Gateway-Timestamp'],
  });
  assert.equal(verdict.ok, true, JSON.stringify(verdict));
  assert.equal(h.Accept, 'application/json');
});

test('adds the v2 signature over GET and the snapshot path', async () => {
  const { calls, impl } = recordingFetch();
  await signedSnapshotFetcher(SECRET, impl)(URL_);

  const h = calls[0].headers;
  const expected = gatewaySignatureV2({
    secretKey: SECRET,
    method: 'GET',
    path: '/api/internal/auth/pricing/snapshot',
    query: '',
    headers: h,
    timestamp: h['X-Gateway-Timestamp'],
  });
  assert.equal(h[GATEWAY_SIGNATURE_V2_HEADER], expected);
});

test('a blank secret sends the read unsigned (dev and CE, where nothing verifies it)', async () => {
  const { calls, impl } = recordingFetch();
  await signedSnapshotFetcher('', impl)(URL_);

  const h = calls[0].headers;
  assert.equal(h['X-Gateway-Secret'], undefined);
  assert.equal(h[GATEWAY_SIGNATURE_V2_HEADER], undefined);
});

test('a refused read (401) is a failed refresh, never an empty price list', async () => {
  const impl = async () => ({ ok: false, status: 401, json: async () => ({}) });
  const cache = new PricingCache({ snapshotUrl: URL_, fetcher: signedSnapshotFetcher(SECRET, impl) });

  await cache.refreshIfStale();

  assert.equal(cache.healthy, false);
  assert.match(String(cache.lastError), /401/);
});

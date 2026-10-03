#!/usr/bin/env node
// Post-deploy smoke check of the bridge authentication front (LC-001).
//
// Proves, against the RUNNING bridge, the three things a green /health cannot:
//   1. an unsigned call to /api/bridge/** is refused (401), i.e. enforcement is really on;
//   2. a call signed the way the Java clients sign (v1 gateway HMAC + body-bound
//      X-Bridge-Signature) with the secret this host holds is accepted (200) and body-bound;
//   3. a signed call whose body claims another tenant is refused (403).
// It uses POST /api/bridge/auth-check, a signed no-op that spawns nothing.
//
// A 401 on (2) means this host's secret differs from the one the app signs with; a 503 means no
// secret is configured. Either way every chat would come back empty, so the deploy must fail.
//
// Usage: node bridge-auth-smoke.mjs [--url http://127.0.0.1:8093]
// The secret is read like the bridge reads it: $CREDENTIALS_DIRECTORY/gateway-secret, else
// GATEWAY_SECRET_KEY. It is run by lc-bridge-smoke.service (same user, env file and credential as
// the bridge), which the deploy lanes start and which fails the deploy when this exits non-zero.

import { gatewaySignedHeaders } from '../lib/gatewayAuth.mjs';
import { bridgeSignature, BRIDGE_SIGNATURE_HEADER } from '../lib/bridgeSignature.mjs';
import { createSecretSource } from '../lib/bridgeSecurity.mjs';

const args = process.argv.slice(2);
const urlArg = args.indexOf('--url');
const BASE = (urlArg >= 0 ? args[urlArg + 1] : null) || process.env.BRIDGE_SMOKE_URL || 'http://127.0.0.1:8093';
const PATH = '/api/bridge/auth-check';

export function signedRequest({ secret, userId, bodyObject, now = Date.now() }) {
  const body = JSON.stringify(bodyObject);
  const headers = {
    'Content-Type': 'application/json',
    'X-User-ID': userId,
    ...gatewaySignedHeaders({ secretKey: secret, providerId: 'bridge-smoke', userId, timestampMs: now }),
  };
  headers[BRIDGE_SIGNATURE_HEADER] = bridgeSignature({
    secretKey: secret, method: 'POST', path: PATH, headers, timestamp: headers['X-Gateway-Timestamp'], body,
  });
  return { headers, body };
}

async function post(headers, body) {
  const res = await fetch(BASE + PATH, { method: 'POST', headers, body });
  let json = null;
  try { json = await res.json(); } catch { /* not JSON */ }
  return { status: res.status, json };
}

async function main() {
  const secret = createSecretSource().inboundSecret();
  if (!secret) {
    console.error('[bridge-smoke] FAIL: no secret on this host ($CREDENTIALS_DIRECTORY/gateway-secret or GATEWAY_SECRET_KEY)');
    return 1;
  }
  const failures = [];

  const unsigned = await post({ 'Content-Type': 'application/json' }, '{}');
  if (unsigned.status !== 401) failures.push(`unsigned call answered ${unsigned.status}, expected 401 (enforcement off?)`);

  const ok = signedRequest({ secret, userId: 'smoke', bodyObject: { tenantId: 'smoke' } });
  const signed = await post(ok.headers, ok.body);
  if (signed.status !== 200 || !signed.json?.bodyBound) {
    failures.push(`signed call answered ${signed.status} ${JSON.stringify(signed.json)}, expected 200 bodyBound `
      + '(401 = this host holds a different secret than the app; 503 = no secret configured)');
  }

  const swap = signedRequest({ secret, userId: 'smoke', bodyObject: { tenantId: 'someone-else' } });
  const mismatch = await post(swap.headers, swap.body);
  if (mismatch.status !== 403) failures.push(`identity-mismatch call answered ${mismatch.status}, expected 403`);

  if (failures.length) {
    for (const f of failures) console.error(`[bridge-smoke] FAIL: ${f}`);
    return 1;
  }
  console.log('[bridge-smoke] OK: unsigned 401, signed 200 (body-bound), identity mismatch 403');
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}` || process.argv[1]?.endsWith('bridge-auth-smoke.mjs')) {
  main().then((code) => process.exit(code), (err) => {
    console.error(`[bridge-smoke] FAIL: ${err.message}`);
    process.exit(1);
  });
}

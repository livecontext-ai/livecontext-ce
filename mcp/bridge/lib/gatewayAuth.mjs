// Gateway HMAC authentication headers - JS twin of the Java
// `GatewayAuthenticationFilter` / `CreditConsumptionClient.computeGatewaySignature`.
//
// Backend services protect their internal endpoints with a shared-secret HMAC:
// every non-public request must carry `X-Gateway-Secret` + `X-Gateway-Timestamp`
// + `X-Provider-ID`, or the filter rejects it 401. A Node process that calls a
// protected backend endpoint DIRECTLY (bypassing the gateway) - e.g. the bridge
// refreshing the live tenant balance from auth-service - must therefore sign the
// request itself, exactly the way the gateway / Java internal clients do.
//
// Signature MUST stay byte-identical to the Java side:
//   data = providerId + "|" + userId + "|" + orgId + "|" + timestamp
//   sig  = "gw_" + base64url-nopadding( HMAC_SHA256(secretKey, data) )
// Node's `digest('base64url')` is unpadded URL-safe Base64 - matches Java's
// `Base64.getUrlEncoder().withoutPadding()`. The parity is pinned by
// `shared/contracts/gateway-signature-fixtures.json` (consumed by the JS twin
// `gatewayAuth.test.mjs` AND the Java `GatewaySignatureParityTest`).
//
// IMPORTANT: the signature binds `userId` AND `orgId`, so the caller MUST send
// the SAME X-User-ID / X-Organization-ID header values it passes here, or the
// filter recomputes a different expected secret and rejects the request.

import { createHmac } from 'node:crypto';

const SIGNATURE_PREFIX = 'gw_';

/**
 * Build the gateway HMAC headers for a direct backend call.
 *
 * @param {object}  opts
 * @param {string}  opts.secretKey        shared HMAC secret (`gateway.filter.secret-key`,
 *                                         provisioned as the `GATEWAY_SECRET_KEY` env var).
 * @param {string}  opts.providerId       value echoed in `X-Provider-ID` and bound into the signature.
 * @param {string} [opts.userId='']       must equal the `X-User-ID` header the caller sends.
 * @param {string} [opts.organizationId=''] must equal the `X-Organization-ID` header the caller sends
 *                                         (empty string when no org header is sent).
 * @param {number} [opts.timestampMs]     epoch ms; defaults to now. The filter rejects a skew > 5 min.
 * @returns {Record<string,string>} headers to merge into the request. When `secretKey`
 *          is empty (dev/test where the backend filter is disabled), returns ONLY
 *          `X-Provider-ID` - mirrors the no-secret fallback so local runs don't break.
 */
export function gatewaySignedHeaders({ secretKey, providerId, userId = '', organizationId = '', timestampMs } = {}) {
  const safeProvider = providerId == null ? '' : String(providerId);
  if (!secretKey) {
    return { 'X-Provider-ID': safeProvider };
  }
  const timestamp = String(timestampMs == null ? Date.now() : timestampMs);
  const safeUser = userId == null ? '' : String(userId);
  const safeOrg = organizationId == null ? '' : String(organizationId);
  const data = `${safeProvider}|${safeUser}|${safeOrg}|${timestamp}`;
  const signature = SIGNATURE_PREFIX
    + createHmac('sha256', secretKey).update(data, 'utf8').digest('base64url');
  return {
    'X-Provider-ID': safeProvider,
    'X-Gateway-Timestamp': timestamp,
    'X-Gateway-Secret': signature,
  };
}

/**
 * Full header set for a direct internal backend call that must satisfy the gateway
 * filter: the gateway-signed headers PLUS the `X-User-ID` / `X-Organization-ID` the
 * signature is BOUND to. Building both from one set of inputs makes a "sign one
 * identity, send another" mismatch - which silently 401s and is invisible to a
 * signer-only test - structurally impossible. `X-Organization-ID` is sent only when
 * a non-empty org is supplied (and the signature is computed over that same value,
 * empty string included), matching how the filter reads a missing header as "".
 *
 * @param {object}  opts                  same as {@link gatewaySignedHeaders}, plus:
 * @param {Record<string,string>} [opts.extra={}] extra headers to merge (e.g. Accept).
 * @returns {Record<string,string>} headers ready to pass to fetch().
 */
export function internalSignedHeaders({ secretKey, providerId, userId = '', organizationId = '', timestampMs, extra = {} } = {}) {
  const safeOrg = organizationId == null ? '' : String(organizationId);
  const headers = {
    ...extra,
    'X-User-ID': userId == null ? '' : String(userId),
    ...gatewaySignedHeaders({ secretKey, providerId, userId, organizationId: safeOrg, timestampMs }),
  };
  if (safeOrg) headers['X-Organization-ID'] = safeOrg;
  return headers;
}

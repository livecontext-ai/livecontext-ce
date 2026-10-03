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

import { createHmac, timingSafeEqual } from 'node:crypto';

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
 * @param {number} [opts.timestampMs]     epoch ms; defaults to now. The filter rejects a v1 skew > 5 min
 *                                         in either direction (a v2 signature: > 60 s by default,
 *                                         gateway.signature.max-skew-seconds).
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

// --- Verification of INCOMING requests (LC-001) -----------------------------
//
// The bridge is also a callee: `POST /api/bridge/execute` spawns a CLI agent child process.
// It used to accept any caller that could reach its port. The verifier below is the mirror
// image of `gatewaySignedHeaders` (and of the Java `GatewayAuthenticationFilter`): same tuple,
// same prefix, same encoding. The Java bridge clients (conversation-service `BridgeClient`,
// agent-service `SubAgentBridgeClient`) sign with `InternalGatewaySigner.stamp`. The express
// middleware that applies it (plus the body-bound bridge signature, the replay cache and the
// identity binding) lives in lib/bridgeSecurity.mjs.

/**
 * Accepted clock skew for an incoming signature, in BOTH directions. Narrower than the Java
 * filter's 300s: every legitimate caller signs immediately before sending, so the real gap is
 * milliseconds plus inter-host clock drift. A far-future timestamp is rejected too (the Java
 * filter only rejects old ones), so a captured signature cannot be pre-dated to live forever.
 */
export const BRIDGE_MAX_SKEW_MS = 60_000;

/**
 * Provider id a caller must SIGN WITH to unlock the unrestricted toolset (native host tools,
 * source checkout, repo/shell MCP tools). The provider id is part of the signed tuple, so only
 * a holder of `GATEWAY_SECRET_KEY` can assert it, and no request BODY field can substitute for
 * it. Java twin: `BridgeDispatchSigning.UNRESTRICTED_PROVIDER_ID` (common-lib); the two literals
 * are pinned equal by tests on both sides.
 */
export const UNRESTRICTED_PROVIDER_ID = 'bridge-unrestricted';

/** Constant-time compare; a length mismatch still burns one comparison. */
function timingSafeEquals(a, b) {
  const left = Buffer.from(String(a), 'utf8');
  const right = Buffer.from(String(b), 'utf8');
  if (left.length !== right.length) {
    timingSafeEqual(left, left);
    return false;
  }
  return timingSafeEqual(left, right);
}

/**
 * Verify a gateway HMAC presented by an incoming request.
 *
 * @returns {{ok: boolean, reason: string}} `reason` is a stable machine token, never the
 *          expected signature.
 */
export function verifyGatewaySignature({
  secretKey,
  signature,
  providerId,
  timestamp,
  userId = '',
  organizationId = '',
  nowMs,
  maxSkewMs = BRIDGE_MAX_SKEW_MS,
} = {}) {
  if (!secretKey) return { ok: false, reason: 'no_secret_configured' };
  if (!signature || !timestamp || providerId == null || providerId === '') {
    return { ok: false, reason: 'missing_headers' };
  }
  if (!String(signature).startsWith(SIGNATURE_PREFIX)) return { ok: false, reason: 'bad_prefix' };
  if (!/^\d{1,16}$/.test(String(timestamp))) return { ok: false, reason: 'bad_timestamp' };
  const requestTime = Number(timestamp);
  const now = nowMs == null ? Date.now() : nowMs;
  if (Math.abs(now - requestTime) > maxSkewMs) return { ok: false, reason: 'stale_timestamp' };

  const expected = gatewaySignedHeaders({
    secretKey,
    providerId,
    userId,
    organizationId,
    timestampMs: String(timestamp),
  })['X-Gateway-Secret'];
  return timingSafeEquals(signature, expected)
    ? { ok: true, reason: 'ok' }
    : { ok: false, reason: 'bad_signature' };
}

/**
 * Decide whether a bridge run is RESTRICTED (platform MCP tools only, empty cwd, no repo/shell
 * MCP tools) - LC-022. Restricted is the default; the only way out is a verified signature with
 * {@link UNRESTRICTED_PROVIDER_ID}. A body claim (`credentials.__restrictedToolset__ === true`,
 * set by the model-execution-link dispatchers) can only TIGHTEN, never widen.
 *
 * @param {{unrestricted?: boolean}|undefined} gatewayAuth  `req.gatewayAuth` from the middleware
 * @param {object|undefined} credentials                    the request body's credentials map
 */
export function resolveRestrictedToolset(gatewayAuth, credentials) {
  const unrestrictedProven = gatewayAuth?.unrestricted === true;
  const bodyRestrictedClaim = !!(credentials && credentials.__restrictedToolset__ === true);
  return bodyRestrictedClaim || !unrestrictedProven;
}

// ---------------------------------------------------------------------------
// v2 signature (CASA LC-035). Kept in its own block, separate from the v1
// functions above. JS twin of the Java `GatewaySignatureV2`; the bytes are pinned
// by the `v2` section of `shared/contracts/gateway-signature-fixtures.json`.
//
// v2 binds the identity AND privilege headers, the HTTP method, the path and the
// query, and length-prefixes every field (`<byteLength>:<bytes>\n`) so no value
// can spill into its neighbour. It travels in `X-Gateway-Signature-V2` NEXT TO the
// v1 headers (same `X-Gateway-Timestamp`): an older backend ignores it, a newer
// one checks it.
// ---------------------------------------------------------------------------

export const GATEWAY_SIGNATURE_V2_HEADER = 'X-Gateway-Signature-V2';
const SIGNATURE_V2_PREFIX = 'gw2_';
const SIGNATURE_V2_DOMAIN = 'lc-gateway-v2';
export const GATEWAY_V2_SIGNED_HEADERS = Object.freeze([
  'X-Provider-ID',
  'X-User-ID',
  'X-Organization-ID',
  'X-Organization-Role',
  'X-User-Roles',
  'X-Api-Key-Scopes',
  'X-Share-Context',
]);

function hexValue(c) {
  if (c >= 0x30 && c <= 0x39) return c - 0x30;
  if (c >= 0x61 && c <= 0x66) return c - 0x61 + 10;
  if (c >= 0x41 && c <= 0x46) return c - 0x41 + 10;
  return -1;
}

/** Percent-decode to raw bytes; `+` and a malformed `%` are kept as-is (Java twin: percentDecode). */
function percentDecodeToBytes(s) {
  if (s == null || s === '') return Buffer.alloc(0);
  const input = Buffer.from(String(s), 'utf8');
  const out = [];
  for (let i = 0; i < input.length; i++) {
    const b = input[i];
    if (b === 0x25 && i + 2 < input.length && hexValue(input[i + 1]) >= 0 && hexValue(input[i + 2]) >= 0) {
      out.push((hexValue(input[i + 1]) << 4) | hexValue(input[i + 2]));
      i += 2;
    } else {
      out.push(b);
    }
  }
  return Buffer.from(out);
}

function lengthPrefixed(buf) {
  return Buffer.concat([Buffer.from(`${buf.length}:`, 'ascii'), buf, Buffer.from('\n', 'ascii')]);
}

/** Case-insensitive header lookup on a plain object; arrays are joined with "," like Java. */
function headerValue(headers, name) {
  const wanted = name.toLowerCase();
  for (const [key, value] of Object.entries(headers || {})) {
    if (key.toLowerCase() === wanted) {
      if (value == null) return '';
      return Array.isArray(value) ? value.map((v) => (v == null ? '' : String(v))).join(',') : String(value);
    }
  }
  return '';
}

/**
 * Compute the v2 signature of one request.
 *
 * @param {object} opts
 * @param {string} opts.secretKey  shared HMAC secret
 * @param {string} opts.method     HTTP method (any case)
 * @param {string} opts.path       path as sent, without query
 * @param {string} [opts.query]    query string without the leading `?`
 * @param {Record<string,string|string[]>} opts.headers  the headers that will be sent
 * @param {string} opts.timestamp  the `X-Gateway-Timestamp` value
 * @returns {string} `gw2_` + base64url-nopadding(HMAC-SHA256)
 */
export function gatewaySignatureV2({ secretKey, method, path, query = '', headers = {}, timestamp }) {
  const parts = [lengthPrefixed(Buffer.from(SIGNATURE_V2_DOMAIN, 'utf8'))];
  for (const name of GATEWAY_V2_SIGNED_HEADERS) {
    parts.push(lengthPrefixed(Buffer.from(headerValue(headers, name), 'utf8')));
  }
  parts.push(lengthPrefixed(Buffer.from(String(method || '').toUpperCase(), 'utf8')));
  parts.push(lengthPrefixed(percentDecodeToBytes(path)));
  parts.push(lengthPrefixed(percentDecodeToBytes(query)));
  parts.push(lengthPrefixed(Buffer.from(timestamp == null ? '' : String(timestamp), 'utf8')));
  return SIGNATURE_V2_PREFIX
    + createHmac('sha256', secretKey).update(Buffer.concat(parts)).digest('base64url');
}

/**
 * Return `headers` plus `X-Gateway-Signature-V2` for a request to `url` with `method`,
 * signing the headers exactly as passed. Call it LAST, on the final header set. A no-op
 * (returns a copy) when there is no secret or the headers carry no v1 timestamp /
 * provider id, mirroring the Java `InternalGatewaySigner.stampV2`.
 */
export function withGatewaySignatureV2(headers, { secretKey, method, url }) {
  const out = { ...(headers || {}) };
  const timestamp = headerValue(out, 'X-Gateway-Timestamp');
  if (!secretKey || !timestamp || !headerValue(out, 'X-Provider-ID')) return out;
  const parsed = new URL(url);
  out[GATEWAY_SIGNATURE_V2_HEADER] = gatewaySignatureV2({
    secretKey,
    method,
    path: parsed.pathname,
    query: parsed.search.startsWith('?') ? parsed.search.slice(1) : parsed.search,
    headers: out,
    timestamp,
  });
  return out;
}

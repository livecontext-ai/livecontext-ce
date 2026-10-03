// Body-bound request signature for calls INTO the agent bridge (LC-001 follow-up).
//
// The v1 gateway signature (`X-Gateway-Secret`, see gatewayAuth.mjs) binds only
// `providerId|userId|orgId|timestamp`: the method, the path, the organization role and, above
// all, the request BODY (prompt, tenant, credentials) are outside the MAC. On the bridge that body
// decides what an agent does, so a v1-only request could be captured and re-sent with another body
// inside its time window.
//
// This signature reuses the canonical encoding of the fleet v2 signature (CASA LC-035,
// `X-Gateway-Signature-V2`, Java `GatewaySignatureV2`): every field is length-prefixed
// (`<byteLength>:<bytes>\n`), the same seven identity/privilege headers are bound in the same
// order, the method is upper-cased and the path/query are signed percent-decoded. It differs in
// exactly two places, so a bridge signature can never be confused with a fleet one:
//   - the domain field is `lc-bridge-v2` (fleet: `lc-gateway-v2`);
//   - one extra LAST field: the lowercase hex SHA-256 of the raw request body bytes.
// It travels in `X-Bridge-Signature` (prefix `br2_`) next to the v1 headers and reuses
// `X-Gateway-Timestamp`. Java twin: common-lib `BridgeRequestSignature`; the bytes are pinned by
// shared/contracts/bridge-signature-fixtures.json, which both test suites check.

import { createHash, createHmac } from 'node:crypto';

export const BRIDGE_SIGNATURE_HEADER = 'X-Bridge-Signature';
const PREFIX = 'br2_';
const DOMAIN = 'lc-bridge-v2';

/** Same list and order as the fleet v2 signature. Changing it is a wire-format change. */
export const BRIDGE_SIGNED_HEADERS = Object.freeze([
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

/** Percent-decode to raw bytes; `+` and a malformed `%` are kept (same rule as the fleet v2). */
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

function field(buf) {
  return Buffer.concat([Buffer.from(`${buf.length}:`, 'ascii'), buf, Buffer.from('\n', 'ascii')]);
}

/** Case-insensitive lookup on a plain header object; arrays are joined with "," like Java. */
export function headerValue(headers, name) {
  const wanted = name.toLowerCase();
  for (const [key, value] of Object.entries(headers || {})) {
    if (key.toLowerCase() === wanted) {
      if (value == null) return '';
      return Array.isArray(value) ? value.map((v) => (v == null ? '' : String(v))).join(',') : String(value);
    }
  }
  return '';
}

/** Lowercase hex SHA-256 of the body bytes (empty body = hash of zero bytes). */
export function bodySha256Hex(body) {
  const bytes = body == null ? Buffer.alloc(0) : (Buffer.isBuffer(body) ? body : Buffer.from(String(body), 'utf8'));
  return createHash('sha256').update(bytes).digest('hex');
}

/**
 * Compute the bridge signature of one request.
 *
 * @param {object} opts
 * @param {string} opts.secretKey
 * @param {string} opts.method
 * @param {string} opts.path      path as sent, without query
 * @param {string} [opts.query]   query without the leading `?`
 * @param {Record<string,string|string[]>} opts.headers  headers as sent
 * @param {string} opts.timestamp the `X-Gateway-Timestamp` value
 * @param {Buffer|string} [opts.body] the exact raw body bytes sent
 */
export function bridgeSignature({ secretKey, method, path, query = '', headers = {}, timestamp, body }) {
  const parts = [field(Buffer.from(DOMAIN, 'utf8'))];
  for (const name of BRIDGE_SIGNED_HEADERS) {
    parts.push(field(Buffer.from(headerValue(headers, name), 'utf8')));
  }
  parts.push(field(Buffer.from(String(method || '').toUpperCase(), 'utf8')));
  parts.push(field(percentDecodeToBytes(path)));
  parts.push(field(percentDecodeToBytes(query)));
  parts.push(field(Buffer.from(timestamp == null ? '' : String(timestamp), 'utf8')));
  parts.push(field(Buffer.from(bodySha256Hex(body), 'ascii')));
  return PREFIX + createHmac('sha256', secretKey).update(Buffer.concat(parts)).digest('base64url');
}

export function isBridgeSignatureShape(value) {
  return typeof value === 'string' && value.startsWith(PREFIX);
}

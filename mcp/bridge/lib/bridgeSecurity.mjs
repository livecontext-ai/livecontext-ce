// Authentication front of the agent bridge (LC-001 / LC-022).
//
// `POST /api/bridge/execute` spawns a CLI agent child process. This module is the ONLY thing
// between a network caller and that spawn, and server.mjs mounts it through
// `installBridgeSecurity(app, ...)` so the tests exercise the very code production runs.
//
// Order of operations on every `/api/bridge/**` request:
//   1. pre-parse gate (BEFORE the JSON body is read, so an unauthenticated caller cannot make the
//      bridge buffer and parse up to BODY_LIMIT): the v1 gateway HMAC over the headers must verify
//      and the timestamp must be fresh. When BRIDGE_REQUIRE_SIGNATURE_V2 is on, the body-bound
//      `X-Bridge-Signature` must also be present.
//   2. JSON body parse, keeping the exact raw bytes.
//   3. post-parse gate: the body-bound signature (lib/bridgeSignature.mjs) is checked against the
//      raw bytes, the signature is recorded in a replay cache (a second use inside the window is
//      refused), and the identity in the BODY must equal the signed identity (403 otherwise).
//
// Outcome on `req.gatewayAuth`:
//   { verified, bodyBound, providerId, userId, organizationId, organizationRole, unrestricted }
// `organizationRole` is only ever taken from a SIGNED header (the body-bound signature binds
// X-Organization-Role); on a v1-only request it is '' because v1 does not bind it.
// `unrestricted` requires a body-bound signature made with UNRESTRICTED_PROVIDER_ID.

import express from 'express';
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { timingSafeEqual } from 'node:crypto';
import { verifyGatewaySignature, BRIDGE_MAX_SKEW_MS, UNRESTRICTED_PROVIDER_ID } from './gatewayAuth.mjs';
import { bridgeSignature, isBridgeSignatureShape, BRIDGE_SIGNATURE_HEADER } from './bridgeSignature.mjs';

/** Name of the systemd credential (LoadCredential=gateway-secret:...) holding the shared secret. */
export const GATEWAY_SECRET_CREDENTIAL = 'gateway-secret';

function readTrimmed(path, readFile) {
  try {
    return String(readFile(path, 'utf8')).trim();
  } catch {
    return '';
  }
}

/**
 * Where the bridge gets its secrets.
 *
 * - platformSecret(): the shared gateway secret used for OUTBOUND signing (balance refresh) and
 *   inbound verification. Read from the systemd credential `$CREDENTIALS_DIRECTORY/gateway-secret`
 *   when present (LC-053: then it is never in the process environment, so a spawned agent cannot
 *   recover it from /proc/<pid>/environ), else from GATEWAY_SECRET_KEY.
 * - inboundSecret(): platformSecret(), else the file named by BRIDGE_SHARED_SECRET_FILE. That file
 *   is how the CE compose stacks share a per-install secret between the app and bridge containers
 *   (written by ce-entrypoint.sh on the shared keys volume). It is read lazily and cached once
 *   non-empty, because the bridge container starts before the app container generates it.
 */
export function createSecretSource({ env = process.env, readFile = readFileSync, exists = existsSync } = {}) {
  let platform = null;
  let shared = '';
  return {
    platformSecret() {
      if (platform === null) {
        const dir = env.CREDENTIALS_DIRECTORY;
        const credPath = dir ? join(dir, GATEWAY_SECRET_CREDENTIAL) : null;
        const fromCredential = credPath && exists(credPath) ? readTrimmed(credPath, readFile) : '';
        platform = fromCredential || env.GATEWAY_SECRET_KEY || '';
      }
      return platform;
    },
    platformSecretSource() {
      const dir = env.CREDENTIALS_DIRECTORY;
      if (dir && exists(join(dir, GATEWAY_SECRET_CREDENTIAL))) return 'systemd-credential';
      if (env.GATEWAY_SECRET_KEY) return 'environment';
      return 'none';
    },
    inboundSecret() {
      const p = this.platformSecret();
      if (p) return p;
      if (!shared && env.BRIDGE_SHARED_SECRET_FILE && exists(env.BRIDGE_SHARED_SECRET_FILE)) {
        shared = readTrimmed(env.BRIDGE_SHARED_SECRET_FILE, readFile);
      }
      return shared;
    },
  };
}

/**
 * Enforcement policy from BRIDGE_REQUIRE_GATEWAY_AUTH:
 *   'true'  -> always enforce; no secret = 503 on every call (fail closed). Prod sets this.
 *   'false' -> never enforce (local dev only); never grants the unrestricted toolset.
 *   unset   -> enforce iff a secret is available at request time (CE secure-by-default).
 */
export function enforcementMode(env = process.env) {
  const raw = env.BRIDGE_REQUIRE_GATEWAY_AUTH;
  if (raw == null || String(raw).trim() === '') return 'auto';
  return String(raw).trim().toLowerCase() === 'false' ? 'off' : 'on';
}

/** Bounded seen-signature cache. */
export function createReplayCache({ ttlMs = 2 * BRIDGE_MAX_SKEW_MS + 1_000, maxEntries = 50_000, now = Date.now } = {}) {
  const seen = new Map(); // key -> expiry (insertion order == expiry order, ttl is constant)
  function prune(t) {
    for (const [key, expiry] of seen) {
      if (expiry > t && seen.size <= maxEntries) break;
      seen.delete(key);
    }
  }
  return {
    /** True if the key was already seen inside the window; otherwise records it and returns false. */
    checkAndRemember(key) {
      const t = now();
      prune(t);
      const expiry = seen.get(key);
      if (expiry !== undefined && expiry > t) return true;
      seen.set(key, t + ttlMs);
      return false;
    },
    size() { return seen.size; },
  };
}

function header(req, name) {
  const value = req.headers[name.toLowerCase()];
  return Array.isArray(value) ? value[0] : value;
}

function timingSafeEquals(a, b) {
  const left = Buffer.from(String(a), 'utf8');
  const right = Buffer.from(String(b), 'utf8');
  if (left.length !== right.length) {
    timingSafeEqual(left, left);
    return false;
  }
  return timingSafeEqual(left, right);
}

function reject(res, status, reason) {
  return res.status(status).json({ error: status === 403 ? 'forbidden' : 'unauthorized', reason });
}

/**
 * The identity a request may act as. Verified requests act ONLY as the signed identity; the
 * body fields are checked against it by the post-parse gate. Unverified requests (enforcement
 * off) keep the legacy header-or-body resolution.
 */
export function resolveRequestIdentity(gatewayAuth, body = {}, headers = {}) {
  if (gatewayAuth?.verified) {
    return {
      tenantId: gatewayAuth.userId || null,
      organizationId: gatewayAuth.organizationId || '',
      organizationRole: gatewayAuth.organizationRole || '',
    };
  }
  return {
    tenantId: body.tenantId ?? null,
    organizationId: headers['x-organization-id'] || body.organizationId || '',
    organizationRole: headers['x-organization-role'] || body.organizationRole || '',
  };
}

/**
 * Mount the authentication front on `app`. Must be called BEFORE any other body parser and
 * before the `/api/bridge/**` handlers.
 */
export function installBridgeSecurity(app, {
  secrets = createSecretSource(),
  mode = enforcementMode(),
  requireV2 = String(process.env.BRIDGE_REQUIRE_SIGNATURE_V2 || '').trim().toLowerCase() === 'true',
  bodyLimit = '100mb',
  maxSkewMs = BRIDGE_MAX_SKEW_MS,
  replayCache = createReplayCache({ ttlMs: 2 * maxSkewMs + 1_000 }),
  now = Date.now,
  logger = console,
} = {}) {
  let warnedOff = false;

  // 1. Pre-parse gate: nothing is read from the body of an unauthenticated request.
  app.use('/api/bridge', (req, res, next) => {
    const secret = secrets.inboundSecret();
    const enforce = mode === 'on' || (mode === 'auto' && !!secret);
    if (!enforce) {
      if (!warnedOff) {
        warnedOff = true;
        logger.warn?.(`[BRIDGE] SECURITY WARNING: /api/bridge/** is NOT authenticated (${mode === 'off'
          ? 'BRIDGE_REQUIRE_GATEWAY_AUTH=false' : 'no shared secret configured'}). Every run is RESTRICTED.`);
      }
      req.gatewayAuth = { verified: false, bodyBound: false, unrestricted: false };
      return next();
    }
    if (!secret) {
      logger.warn?.('[BRIDGE] AUTH refusing request: enforcement is on but no shared secret is configured.');
      return res.status(503).json({
        error: 'bridge_auth_misconfigured',
        message: 'Bridge gateway authentication is enabled but no shared secret is configured.',
      });
    }
    const providerId = header(req, 'x-provider-id');
    const userId = header(req, 'x-user-id') || '';
    const organizationId = header(req, 'x-organization-id') || '';
    const timestamp = header(req, 'x-gateway-timestamp');
    const v1Signature = header(req, 'x-gateway-secret');
    const result = verifyGatewaySignature({
      secretKey: secret, signature: v1Signature, providerId, timestamp,
      userId, organizationId, nowMs: now(), maxSkewMs,
    });
    if (!result.ok) {
      logger.warn?.(`[BRIDGE] AUTH rejected ${req.method} ${req.originalUrl}: ${result.reason}`);
      return reject(res, 401, result.reason);
    }
    const v2 = header(req, BRIDGE_SIGNATURE_HEADER);
    if (v2 != null && !isBridgeSignatureShape(v2)) return reject(res, 401, 'bad_body_signature');
    if (requireV2 && !v2) {
      logger.warn?.(`[BRIDGE] AUTH rejected ${req.method} ${req.originalUrl}: body signature required`);
      return reject(res, 401, 'body_signature_required');
    }
    req.bridgeAuthPending = { secret, providerId: String(providerId), userId, organizationId, timestamp, v1Signature, v2 };
    return next();
  });

  // 2. Body parse, keeping the exact bytes the signature covers.
  app.use(express.json({
    limit: bodyLimit,
    verify: (req, _res, buf) => { req.rawBody = buf; },
  }));

  // 3. Post-parse gate.
  app.use('/api/bridge', (req, res, next) => {
    const pending = req.bridgeAuthPending;
    if (!pending) return next(); // enforcement off
    delete req.bridgeAuthPending;
    const url = req.originalUrl || req.url;
    const q = url.indexOf('?');
    const path = q >= 0 ? url.slice(0, q) : url;
    const query = q >= 0 ? url.slice(q + 1) : '';

    let bodyBound = false;
    if (pending.v2) {
      const expected = bridgeSignature({
        secretKey: pending.secret, method: req.method, path, query,
        headers: req.headers, timestamp: pending.timestamp, body: req.rawBody,
      });
      if (!timingSafeEquals(pending.v2, expected)) {
        logger.warn?.(`[BRIDGE] AUTH rejected ${req.method} ${path}: bad_body_signature`);
        return reject(res, 401, 'bad_body_signature');
      }
      bodyBound = true;
    }

    if (replayCache.checkAndRemember(pending.v2 || pending.v1Signature)) {
      logger.warn?.(`[BRIDGE] AUTH rejected ${req.method} ${path}: replayed signature`);
      return reject(res, 401, 'replayed');
    }

    const auth = {
      verified: true,
      bodyBound,
      providerId: pending.providerId,
      userId: pending.userId,
      organizationId: pending.organizationId,
      organizationRole: bodyBound ? (header(req, 'x-organization-role') || '') : '',
      unrestricted: bodyBound && pending.providerId === UNRESTRICTED_PROVIDER_ID,
    };

    // The body may not claim an identity other than the signed one.
    const body = req.body && typeof req.body === 'object' ? req.body : {};
    if (body.tenantId != null && body.tenantId !== '' && String(body.tenantId) !== auth.userId) {
      logger.warn?.(`[BRIDGE] AUTH rejected ${req.method} ${path}: body tenantId differs from the signed X-User-ID`);
      return reject(res, 403, 'identity_mismatch');
    }
    if (body.organizationId != null && body.organizationId !== '' && String(body.organizationId) !== auth.organizationId) {
      logger.warn?.(`[BRIDGE] AUTH rejected ${req.method} ${path}: body organizationId differs from the signed X-Organization-ID`);
      return reject(res, 403, 'identity_mismatch');
    }
    req.gatewayAuth = auth;
    return next();
  });

  // Signed no-op used by the post-deploy smoke check: proves the deployed bridge and the caller
  // agree on the secret and the signature format without spawning anything.
  app.post('/api/bridge/auth-check', (req, res) => {
    res.json({
      ok: true,
      verified: !!req.gatewayAuth?.verified,
      bodyBound: !!req.gatewayAuth?.bodyBound,
      unrestricted: !!req.gatewayAuth?.unrestricted,
    });
  });
}

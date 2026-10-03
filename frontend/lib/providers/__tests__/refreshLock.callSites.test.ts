import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Every token refresh runs under the cross-tab refresh lock (audit B #11).
 *
 * Both sign-in systems rotate refresh tokens: a refresh token is spent on use, and Keycloak ends
 * the session's tokens in EVERY tab once one is presented past its single tolerated reuse. All
 * tabs share one stored session, so a refresh that skips the lock (`lib/auth/crossTabLock.ts`)
 * lets two tabs present the same token at once and signs the user out everywhere. The lock is
 * taken in exactly two places: `refreshCloudSession` in smart-providers.tsx (cloud OIDC, reached
 * through the provider's `refreshSession`) and
 * `withCrossTabRefreshLock` in embedded-auth-provider.tsx (CE embedded auth).
 *
 * A source-level guard rather than a render test because the rule is about the SET of refresh
 * call sites: the next `oidc.signinSilent()` written in a new effect or hook would be born
 * without the lock while every component test stayed green. The one refresh path not written
 * in this source, oidc-client-ts's own timer, is off (`automaticSilentRenew: false`, pinned by
 * app/__tests__/providers.oidcConfig.test.ts).
 */

const ROOT = process.cwd();
const SMART_PROVIDERS = path.join('lib', 'providers', 'smart-providers.tsx');
const EMBEDDED_PROVIDER = path.join('lib', 'providers', 'embedded-auth-provider.tsx');

/** Directories that hold no product source. */
const NOT_SOURCE = new Set([
  'node_modules', '.next', '.turbo', 'coverage', 'public', 'messages', 'e2e', 'scripts',
  '__tests__', '__mocks__',
]);

/** A call of an oidc-client-ts / react-oidc-context refresh entry point. */
const REFRESH_CALL = /(?<![\w$])(?:signinSilent|startSilentRenew|signinResourceOwnerCredentials)\s*\(/g;
/** The CE embedded refresh endpoint (a string, so matched on source with only comments removed). */
const EMBEDDED_REFRESH_ENDPOINT = /auth\/refresh['"`]/;

/** Replaces comments (and, when asked, string literals) with spaces, keeping every offset. */
function blank(source: string, strings: boolean): string {
  const out = source.split('');
  let i = 0;
  while (i < source.length) {
    const two = source.slice(i, i + 2);
    if (two === '//') {
      while (i < source.length && source[i] !== '\n') { out[i] = ' '; i += 1; }
      continue;
    }
    if (two === '/*') {
      while (i < source.length && source.slice(i, i + 2) !== '*/') { out[i] = ' '; i += 1; }
      for (let j = 0; j < 2 && i < source.length; j += 1, i += 1) out[i] = ' ';
      continue;
    }
    const quote = source[i];
    if (quote === "'" || quote === '"' || quote === '`') {
      const start = i;
      i += 1;
      while (i < source.length && source[i] !== quote) {
        if (source.charCodeAt(i) === 92) i += 1; // an escape: skip the pair
        i += 1;
      }
      i += 1;
      if (strings) for (let j = start; j < i && j < source.length; j += 1) out[j] = ' ';
      continue;
    }
    i += 1;
  }
  return out.join('');
}

function productSources(dir: string, found: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!NOT_SOURCE.has(entry.name) && !entry.name.startsWith('.')) {
        productSources(path.join(dir, entry.name), found);
      }
    } else if (/\.(?:ts|tsx|js|jsx|mjs)$/.test(entry.name) && !/\.(?:test|spec)\./.test(entry.name)) {
      found.push(path.join(dir, entry.name));
    }
  }
  return found;
}

function read(relative: string): string {
  return fs.readFileSync(path.join(ROOT, relative), 'utf8');
}

/** Offsets of every refresh entry-point call in code with comments and strings blanked out. */
function refreshCalls(code: string): number[] {
  return [...code.matchAll(REFRESH_CALL)].map((m) => m.index ?? -1);
}

/** The text of the `useCallback(...)` assigned to {@code name}, located in blanked code. */
function callbackBlock(code: string, name: string): { start: number; end: number } {
  const start = code.search(new RegExp(`const ${name} = useCallback\\(`));
  expect(start, `${name} is declared with useCallback`).toBeGreaterThanOrEqual(0);
  let depth = 0;
  for (let i = code.indexOf('(', start); i < code.length; i += 1) {
    if (code[i] === '(') depth += 1;
    else if (code[i] === ')') {
      depth -= 1;
      if (depth === 0) return { start, end: i };
    }
  }
  throw new Error(`unbalanced useCallback for ${name}`);
}

/** The `function name(...) { ... }` declared in blanked code (from the keyword to its closing brace). */
function functionBlock(code: string, name: string): { start: number; end: number } {
  const start = code.search(new RegExp(`function ${name}\\(`));
  expect(start, `${name} is declared as a function`).toBeGreaterThanOrEqual(0);
  let depth = 0;
  for (let i = code.indexOf('{', code.indexOf(')', start)); i < code.length; i += 1) {
    if (code[i] === '{') depth += 1;
    else if (code[i] === '}') {
      depth -= 1;
      if (depth === 0) return { start, end: i };
    }
  }
  throw new Error(`unbalanced function ${name}`);
}

function within(at: number, block: { start: number; end: number }): boolean {
  return at >= block.start && at <= block.end;
}

describe('token refresh call sites', () => {
  it('only the two auth providers start a refresh', () => {
    const offenders = productSources(ROOT)
      .map((file) => path.relative(ROOT, file))
      .filter((file) => file !== SMART_PROVIDERS && file !== EMBEDDED_PROVIDER)
      .filter((file) => {
        const source = read(file);
        return refreshCalls(blank(source, true)).length > 0
          || EMBEDDED_REFRESH_ENDPOINT.test(blank(source, false));
      });
    // A file listed here refreshes outside the cross-tab lock: route it through the provider's
    // refreshSession / signinSilent instead.
    expect(offenders).toEqual([]);
  });

  it('smart-providers calls signinSilent only in refreshSession (CE) and refreshCloudSession (cloud, under the lock)', () => {
    const code = blank(read(SMART_PROVIDERS), true);
    const session = callbackBlock(code, 'refreshSession');
    const cloud = functionBlock(code, 'refreshCloudSession');
    const calls = refreshCalls(code);

    const outside = calls.filter((at) => !within(at, session) && !within(at, cloud));
    expect(outside, 'a refresh outside refreshSession / refreshCloudSession bypasses the cross-tab lock').toEqual([]);

    // refreshSession: the CE branch (embedded-auth-provider's signinSilent takes its own lock,
    // checked below), and the cloud branch hands over to refreshCloudSession.
    const sessionBody = code.slice(session.start, session.end);
    const sessionCalls = calls.filter((at) => within(at, session)).map((at) => at - session.start);
    expect(sessionCalls).toHaveLength(1);
    expect(sessionBody.slice(0, sessionCalls[0])).toMatch(/if\s*\(\s*IS_EMBEDDED_AUTH\s*\)\s*return\s+oidcRef\.current\.$/);
    expect(sessionBody).toMatch(/return\s+refreshCloudSession\(\s*oidcUserManagerRef\.current\s*,\s*onLockGranted\s*\)/);

    // refreshCloudSession: one call, inside the OIDC refresh lock's callback, on the UserManager
    // itself. Never react-oidc-context's useAuth().signinSilent, which resolves null on every
    // failure (invalid_grant included), so the retry and the sign-out never ran (CASA round 8).
    const cloudBody = code.slice(cloud.start, cloud.end);
    const lockAt = cloudBody.search(/withCrossTabLock\s*(?:<[\s\S]*?>)?\s*\(\s*OIDC_REFRESH_LOCK_NAME\s*,\s*OIDC_REFRESH_LEASE_KEY\s*,/);
    expect(lockAt, 'refreshCloudSession takes the OIDC refresh lock').toBeGreaterThanOrEqual(0);
    const cloudCalls = calls.filter((at) => within(at, cloud)).map((at) => at - cloud.start);
    expect(cloudCalls).toHaveLength(1);
    expect(cloudCalls[0]).toBeGreaterThan(lockAt);
    expect(cloudBody.slice(0, cloudCalls[0])).toMatch(/return\s+userManager\.$/);
  });

  it('the cloud refresh is bounded: request timeout on signinSilent, hold bound on the lock', () => {
    // CASA round 5: a hung /token held the lock (and every waiting tab) ~100 s. Behaviour is
    // covered by lib/auth/crossTabLock.maxHold.test.ts and app/__tests__/providers.oidcRequestTimeout.test.ts;
    // this pins that refreshCloudSession actually passes both bounds.
    const code = blank(read(SMART_PROVIDERS), true);
    const cloud = functionBlock(code, 'refreshCloudSession');
    const body = code.slice(cloud.start, cloud.end + 1);
    const lockedCall = body.slice(body.search(/withCrossTabLock\s*(?:<[\s\S]*?>)?\s*\(/));
    expect(lockedCall).toMatch(
      /signinSilent\(\s*\{\s*silentRequestTimeoutInSeconds:\s*OIDC_REQUEST_TIMEOUT_SECONDS\s*\}\s*\)/,
    );
    expect(lockedCall).toMatch(/\}\s*,\s*\{\s*maxHoldMs:\s*OIDC_REFRESH_MAX_HOLD_MS\s*\}\s*\)\s*;?\s*\}$/);
  });

  it('every smart-providers refresh goes through refreshSession / refreshCloudSession', () => {
    const code = blank(read(SMART_PROVIDERS), true);
    // No signinSilent reference may escape the two (a `const s = oidc.signinSilent` alias included).
    const session = callbackBlock(code, 'refreshSession');
    const cloud = functionBlock(code, 'refreshCloudSession');
    const references = [...code.matchAll(/\bsigninSilent\b/g)]
      .map((m) => m.index ?? -1)
      .filter((at) => !within(at, session) && !within(at, cloud));
    expect(references).toEqual([]);
    // refreshCloudSession is called from refreshSession only.
    const cloudCalls = [...code.matchAll(/\brefreshCloudSession\s*\(/g)]
      .map((m) => m.index ?? -1)
      .filter((at) => at !== cloud.start + code.slice(cloud.start).search(/\brefreshCloudSession\s*\(/));
    expect(cloudCalls.every((at) => within(at, session))).toBe(true);
    expect(cloudCalls).toHaveLength(1);
  });

  it('embedded auth refreshes only through withCrossTabRefreshLock', () => {
    const source = read(EMBEDDED_PROVIDER);
    const code = blank(source, true);
    const withStrings = blank(source, false);

    // The lock helper is the shared cross-tab lock.
    const helper = withStrings.slice(withStrings.indexOf('function withCrossTabRefreshLock'));
    expect(helper.slice(0, helper.indexOf('\n}'))).toMatch(/return withCrossTabLock\(REFRESH_LOCK_NAME, REFRESH_LEASE_KEY, fn\)/);

    // The refresh endpoint is called from refreshOnce only...
    const refreshOnce = callbackBlock(code, 'refreshOnce');
    const endpointAt = withStrings.search(EMBEDDED_REFRESH_ENDPOINT);
    expect(endpointAt).toBeGreaterThan(refreshOnce.start);
    expect(endpointAt).toBeLessThan(refreshOnce.end);
    expect(withStrings.slice(endpointAt + 1).search(EMBEDDED_REFRESH_ENDPOINT)).toBe(-1);

    // ...and refreshOnce runs only inside the lock.
    const uses = [...code.matchAll(/\brefreshOnce\s*\(/g)].map((m) => m.index ?? -1);
    expect(uses.length).toBeGreaterThan(0);
    for (const at of uses) {
      expect(code.slice(Math.max(0, at - 60), at)).toMatch(/withCrossTabRefreshLock\(\s*\(\)\s*=>\s*$/);
    }

    // The provider's public signinSilent is the locked one.
    const signinSilent = callbackBlock(code, 'signinSilent');
    expect(code.slice(signinSilent.start, signinSilent.end)).toMatch(/withCrossTabRefreshLock\(/);
  });
});

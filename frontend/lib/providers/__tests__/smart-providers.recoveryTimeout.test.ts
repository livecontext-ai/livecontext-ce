import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { OIDC_REFRESH_MAX_HOLD_MS, withCrossTabLock } from '../../auth/crossTabLock';
import { AUTO_RECOVERY_TIMEOUT_MS, withTimeoutFromStart } from '../smart-providers';

/**
 * Regression (CASA round 4): the 15 s auto-recovery of an expired session raced `refreshSession()`
 * against a timer started at the CALL, and `refreshSession` first waits for the cross-tab refresh
 * lock. With several tabs restored at once (each waiting its turn) the wait ate the 15 s, the
 * recovery "timed out" while its refresh had not even started, and the tab was sent to the login
 * page although its session was still good. The clock now starts when the lock is granted.
 */

const TIMEOUT_MS = 15_000;

/** navigator.locks stand-in that grants the lock after `delayMs`. */
function delayedLocks(delayMs: number) {
  return {
    request: (_name: string, callback: () => Promise<unknown>) =>
      new Promise((resolve, reject) => {
        setTimeout(() => callback().then(resolve, reject), delayMs);
      }),
  };
}

describe('withTimeoutFromStart (auto-recovery timeout)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('does not count the wait for the cross-tab lock against the refresh', async () => {
    vi.stubGlobal('navigator', { locks: delayedLocks(20_000) }); // another tab holds it for 20 s
    const signinSilent = vi.fn(
      () => new Promise<string>((resolve) => setTimeout(() => resolve('token'), 1_000)),
    );

    const outcome = withTimeoutFromStart(
      (started) => withCrossTabLock('lc-oidc-refresh', 'lease', () => {
        started();
        return signinSilent();
      }),
      TIMEOUT_MS,
      'signinSilent timeout',
    );
    const settled = vi.fn();
    outcome.then(settled, settled);

    await vi.advanceTimersByTimeAsync(TIMEOUT_MS + 1);
    expect(settled).not.toHaveBeenCalled(); // still queued for the lock: no timeout yet

    await vi.advanceTimersByTimeAsync(20_000 + 1_000);
    await expect(outcome).resolves.toBe('token');
    expect(signinSilent).toHaveBeenCalledTimes(1);
  });

  it('times the refresh out 15 s after the lock is granted', async () => {
    vi.stubGlobal('navigator', { locks: delayedLocks(5_000) });
    const outcome = withTimeoutFromStart(
      (started) => withCrossTabLock('lc-oidc-refresh', 'lease', () => {
        started();
        return new Promise<string>(() => {}); // a refresh that never answers
      }),
      TIMEOUT_MS,
      'signinSilent timeout',
    );
    const settled = vi.fn();
    outcome.catch(settled);

    await vi.advanceTimersByTimeAsync(5_000 + TIMEOUT_MS - 1);
    expect(settled).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(2);
    expect(settled).toHaveBeenCalledWith(new Error('signinSilent timeout'));
  });

  it('passes the refresh result or error through and clears its timer', async () => {
    await expect(withTimeoutFromStart(async (started) => { started(); return 'ok'; }, TIMEOUT_MS, 'x'))
      .resolves.toBe('ok');
    await expect(withTimeoutFromStart(async (started) => { started(); throw new Error('refused'); }, TIMEOUT_MS, 'x'))
      .rejects.toThrow('refused');
    expect(vi.getTimerCount()).toBe(0);
  });

  it('rejects when the refresh throws before returning a promise', async () => {
    await expect(withTimeoutFromStart(() => { throw new Error('boom'); }, TIMEOUT_MS, 'x'))
      .rejects.toThrow('boom');
  });
});

describe('smart-providers auto-recovery wiring', () => {
  const source = fs.readFileSync(path.join(process.cwd(), 'lib', 'providers', 'smart-providers.tsx'), 'utf8');

  function recoveryEffect(): string {
    const start = source.indexOf('const autoRecoverAttemptedRef = useRef(false);');
    expect(start).toBeGreaterThanOrEqual(0);
    return source.slice(start, source.indexOf('}, [isLoading, isAuthenticated, refreshSession]);', start));
  }

  it('starts the recovery timeout from the lock grant, not from the call', () => {
    const recovery = recoveryEffect();
    expect(recovery).toMatch(/withTimeoutFromStart\(\s*\(started\)\s*=>/);
    expect(recovery).toMatch(/return refreshSession\(started\)/);
    expect(recovery).toMatch(/\},\s*AUTO_RECOVERY_TIMEOUT_MS,\s*'signinSilent timeout'\)/);
    expect(recovery).not.toMatch(/Promise\.race/);
  });

  it('regression (CASA round 8): the recovery clock is never shorter than the lock hold bound', () => {
    // Both start at the lock grant. At 15 s the recovery signed the user out while a refresh the
    // lock still allowed (discovery, then /token, each given 10 s) could have succeeded.
    expect(AUTO_RECOVERY_TIMEOUT_MS).toBeGreaterThanOrEqual(OIDC_REFRESH_MAX_HOLD_MS);
  });

  it('regression (CASA round 8): a slow network gets the same single retry as getAccessToken before the login page', () => {
    const recovery = recoveryEffect();
    expect(recovery).toMatch(/refreshWithTransientRetry\(\{[\s\S]*refresh:\s*\(\)\s*=>\s*withTimeoutFromStart\(/);
    const redirects = [...recovery.matchAll(/safeRedirectToLoginRef\.current\(/g)];
    expect(redirects).toHaveLength(1);
    expect(recovery).toMatch(/if \(decision\.kind === 'redirect'\) \{\s*await safeRedirectToLoginRef\.current\(/);
    expect(recovery).not.toMatch(/catch\s*[({]/);
  });

  it('refreshCloudSession signals the grant inside the lock, right before the UserManager refresh', () => {
    const start = source.indexOf('export function refreshCloudSession(');
    const body = source.slice(start, source.indexOf('{ maxHoldMs: OIDC_REFRESH_MAX_HOLD_MS });', start));
    expect(body).toMatch(
      /withCrossTabLock[\s\S]*?OIDC_REFRESH_LEASE_KEY,\s*\(\)\s*=>\s*\{\s*onLockGranted\?\.\(\);[\s\S]*?return userManager\.signinSilent\([^)]*\);/,
    );
  });
});

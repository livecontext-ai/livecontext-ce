// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CrossTabLockHoldTimeoutError,
  OIDC_REFRESH_LEASE_KEY,
  OIDC_REFRESH_LOCK_NAME,
  OIDC_REFRESH_MAX_HOLD_MS,
  withCrossTabLock,
} from './crossTabLock';

/**
 * Regression (CASA round 5): the cloud refresh holds the cross-tab lock while signinSilent runs,
 * and a /token call that never answered (no request timeout; a Cloudflare 524 only after ~100 s)
 * held it that long, so every other tab spun waiting for it. With `maxHoldMs` the lock is released
 * once the work has run that long, whether or not its promise ever settles, and the next tab runs.
 */

type Locks = { request: (name: string, cb: () => Promise<unknown>) => Promise<unknown> };

/** A Web Locks stand-in with real mutual exclusion per name: the lock is held until cb settles. */
function fakeWebLocks(): Locks {
  const tails = new Map<string, Promise<unknown>>();
  return {
    request(name, cb) {
      const previous = tails.get(name) ?? Promise.resolve();
      const run = previous.catch(() => undefined).then(() => cb());
      tails.set(name, run.catch(() => undefined));
      return run;
    },
  };
}

function setLocks(locks: Locks | undefined) {
  Object.defineProperty(navigator, 'locks', { value: locks, configurable: true });
}

const never = () => new Promise<string>(() => {});

describe('withCrossTabLock maxHoldMs (a hung refresh cannot hold every tab)', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    vi.useRealTimers();
    setLocks(undefined);
  });

  it('with Web Locks, releases the lock after a hung refresh times out and the other tab proceeds', async () => {
    vi.useFakeTimers();
    setLocks(fakeWebLocks());
    const otherTab = vi.fn(async () => 'token-b');

    const hung = withCrossTabLock(OIDC_REFRESH_LOCK_NAME, OIDC_REFRESH_LEASE_KEY, never,
      { maxHoldMs: OIDC_REFRESH_MAX_HOLD_MS });
    const hungOutcome = vi.fn();
    hung.catch(hungOutcome);
    const waiting = withCrossTabLock(OIDC_REFRESH_LOCK_NAME, OIDC_REFRESH_LEASE_KEY, otherTab,
      { maxHoldMs: OIDC_REFRESH_MAX_HOLD_MS });

    await vi.advanceTimersByTimeAsync(OIDC_REFRESH_MAX_HOLD_MS - 1);
    expect(otherTab, 'the other tab waits while the refresh may still answer').not.toHaveBeenCalled();
    expect(hungOutcome).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);
    expect(hungOutcome).toHaveBeenCalledWith(expect.any(CrossTabLockHoldTimeoutError));
    await expect(waiting).resolves.toBe('token-b');
    expect(otherTab).toHaveBeenCalledTimes(1);
  });

  it('without Web Locks (plain HTTP), the lease is released after a hung refresh times out', async () => {
    setLocks(undefined);
    const maxHoldMs = 150;
    const otherTab = vi.fn(async () => 'token-b');

    const hung = withCrossTabLock(OIDC_REFRESH_LOCK_NAME, OIDC_REFRESH_LEASE_KEY, never, { maxHoldMs });
    // Let the first call claim the lease before the second one looks at it.
    await new Promise((resolve) => setTimeout(resolve, 80));
    const waiting = withCrossTabLock(OIDC_REFRESH_LOCK_NAME, OIDC_REFRESH_LEASE_KEY, otherTab, { maxHoldMs });

    await expect(hung).rejects.toBeInstanceOf(CrossTabLockHoldTimeoutError);
    await expect(waiting).resolves.toBe('token-b');
    expect(otherTab).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem(OIDC_REFRESH_LEASE_KEY)).toBeNull();
  });

  it('a refresh that settles in time keeps its own result or error (no timeout left behind)', async () => {
    vi.useFakeTimers();
    setLocks(fakeWebLocks());

    await expect(withCrossTabLock(OIDC_REFRESH_LOCK_NAME, OIDC_REFRESH_LEASE_KEY,
      async () => 'token', { maxHoldMs: OIDC_REFRESH_MAX_HOLD_MS })).resolves.toBe('token');
    await expect(withCrossTabLock(OIDC_REFRESH_LOCK_NAME, OIDC_REFRESH_LEASE_KEY,
      async () => { throw new Error('invalid_grant'); }, { maxHoldMs: OIDC_REFRESH_MAX_HOLD_MS }))
      .rejects.toThrow('invalid_grant');
    expect(vi.getTimerCount(), 'the hold timer is cleared once the refresh settles').toBe(0);
  });

  it('without maxHoldMs the lock waits for the work however long it takes (CE embedded auth)', async () => {
    vi.useFakeTimers();
    setLocks(fakeWebLocks());
    const outcome = vi.fn();

    withCrossTabLock('ce-auth-refresh', 'ce_refresh_lease', never).then(outcome, outcome);
    await vi.advanceTimersByTimeAsync(10 * OIDC_REFRESH_MAX_HOLD_MS);
    expect(outcome).not.toHaveBeenCalled();
  });
});

// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OIDC_REFRESH_LEASE_KEY, OIDC_REFRESH_LOCK_NAME, withCrossTabLock } from './crossTabLock';

/**
 * Keycloak rotates refresh tokens and, past one tolerated reuse, ends the session's tokens in every
 * tab. Tabs share one stored session, so their refreshes must take turns: these tests pin that the
 * lock really serialises them, with Web Locks and with the localStorage lease fallback.
 */

type Locks = { request: (name: string, cb: () => Promise<unknown>) => Promise<unknown> };

/** A Web Locks stand-in with real mutual exclusion per name (what browsers give every tab). */
function fakeWebLocks(): Locks & { names: string[] } {
  const tails = new Map<string, Promise<unknown>>();
  const names: string[] = [];
  return {
    names,
    request(name, cb) {
      names.push(name);
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

/** Two "tabs" refreshing at once; records when each refresh starts and ends. */
async function twoConcurrentRefreshes() {
  const events: string[] = [];
  const refresh = (tab: string) => async () => {
    events.push(`${tab}:start`);
    await new Promise((resolve) => setTimeout(resolve, 20));
    events.push(`${tab}:end`);
    return tab;
  };
  const results = await Promise.all([
    withCrossTabLock(OIDC_REFRESH_LOCK_NAME, OIDC_REFRESH_LEASE_KEY, refresh('a')),
    withCrossTabLock(OIDC_REFRESH_LOCK_NAME, OIDC_REFRESH_LEASE_KEY, refresh('b')),
  ]);
  return { events, results };
}

describe('withCrossTabLock (refresh-token rotation across tabs)', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    setLocks(undefined);
    vi.restoreAllMocks();
  });

  it('with Web Locks, two refreshes started together run one after the other under the OIDC lock name', async () => {
    const locks = fakeWebLocks();
    setLocks(locks);

    const { events, results } = await twoConcurrentRefreshes();

    expect(results).toEqual(['a', 'b']);
    expect(events).toEqual(['a:start', 'a:end', 'b:start', 'b:end']);
    expect(locks.names).toEqual([OIDC_REFRESH_LOCK_NAME, OIDC_REFRESH_LOCK_NAME]);
    expect(localStorage.getItem(OIDC_REFRESH_LEASE_KEY), 'no lease when Web Locks exist').toBeNull();
  });

  it('without Web Locks (plain HTTP), the localStorage lease serialises them and is released after', async () => {
    setLocks(undefined);

    const { events, results } = await twoConcurrentRefreshes();

    expect(results.sort()).toEqual(['a', 'b']);
    // Whichever claimed the lease first runs to its end before the other starts.
    expect([events[0].split(':')[1], events[1].split(':')[1]]).toEqual(['start', 'end']);
    expect(events[0].split(':')[0]).toBe(events[1].split(':')[0]);
    expect(localStorage.getItem(OIDC_REFRESH_LEASE_KEY)).toBeNull();
  });

  it('falls back to the lease when the lock request is refused before it is granted', async () => {
    setLocks({ request: () => Promise.reject(new DOMException('not fully active', 'InvalidStateError')) });
    const refresh = vi.fn(async () => 'token');

    await expect(withCrossTabLock(OIDC_REFRESH_LOCK_NAME, OIDC_REFRESH_LEASE_KEY, refresh)).resolves.toBe('token');
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('a refresh that fails under the lock fails once: it is not run a second time through the lease', async () => {
    setLocks(fakeWebLocks());
    const refresh = vi.fn(async () => { throw new Error('invalid_grant'); });

    await expect(withCrossTabLock(OIDC_REFRESH_LOCK_NAME, OIDC_REFRESH_LEASE_KEY, refresh)).rejects.toThrow('invalid_grant');
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('the cloud lock is not the CE one (each session takes its own turns)', () => {
    expect(OIDC_REFRESH_LOCK_NAME).not.toBe('ce-auth-refresh');
    expect(OIDC_REFRESH_LEASE_KEY).not.toBe('ce_refresh_lease');
  });
});

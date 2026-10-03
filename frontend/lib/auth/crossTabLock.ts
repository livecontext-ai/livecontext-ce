/**
 * A lock shared by every tab of this origin, for token refreshes.
 *
 * Both sign-in systems rotate refresh tokens: a refresh token is spent when it is used, and
 * presenting a spent one is refused (Keycloak, past its one tolerated reuse, also ends the
 * session's tokens for every tab). All tabs share one stored session, so two tabs refreshing at the
 * same moment would present the same token. Running every refresh under this lock makes the tabs
 * take turns: the second one starts after the first stored its new token, and presents that.
 *
 * Used by the CE embedded auth (lib/providers/embedded-auth-provider.tsx) and the cloud OIDC
 * session (lib/providers/smart-providers.tsx), each under its own lock name and lease key.
 */

/**
 * The lease lasts this long unless its holder renews it. The holder renews it every
 * LEASE_RENEW_MS while its refresh runs, so a slow refresh keeps it; a tab that died stops renewing
 * and the lease frees itself. Long enough for a hidden tab, whose timers the browser may run only
 * once a minute.
 */
export const LEASE_TTL_MS = 90_000;
const LEASE_RENEW_MS = 10_000;
const LEASE_CONFIRM_MS = 50;
const LEASE_POLL_MS = 100;

/** The cloud OIDC session's refresh lock (Keycloak refresh tokens, smart-providers.tsx). */
export const OIDC_REFRESH_LOCK_NAME = 'lc-oidc-refresh';
export const OIDC_REFRESH_LEASE_KEY = 'lc_oidc_refresh_lease';
/**
 * oidc-client-ts has no request timeout unless one is set: a /token call that never answers (a
 * Cloudflare 524 arrives only after ~100 s) would hold the refresh lock, and every other tab
 * waiting for it, that long. The cloud OIDC settings (app/providers.tsx) and every refresh
 * (smart-providers.tsx) use this timeout.
 */
export const OIDC_REQUEST_TIMEOUT_SECONDS = 10;
/**
 * The most time a cloud refresh may hold the cross-tab lock, whether or not its promise settles
 * (the request timeout covers the wait for the response headers, not the body). Longer than one
 * refresh's requests (discovery, then /token) each given the request timeout, so in practice the
 * refresh fails on its own first; this only frees the lock when it does not.
 */
export const OIDC_REFRESH_MAX_HOLD_MS = 30_000;

/** The work run under the lock did not settle within its `maxHoldMs`; the lock was released. */
export class CrossTabLockHoldTimeoutError extends Error {
  constructor(maxHoldMs: number) {
    super(`cross-tab lock released: the work did not settle within ${maxHoldMs} ms`);
    this.name = 'CrossTabLockHoldTimeoutError';
  }
}

export interface CrossTabLockOptions {
  /**
   * When set, the lock is released (and the call rejects with CrossTabLockHoldTimeoutError) once
   * fn has run this long without settling, so a promise that never settles cannot hold every tab.
   */
  maxHoldMs?: number;
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/** Runs fn; without maxHoldMs as is, with it rejecting once fn has not settled after maxHoldMs. */
function boundedHold<T>(fn: () => Promise<T>, maxHoldMs: number | undefined): Promise<T> {
  if (maxHoldMs === undefined) return fn();
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new CrossTabLockHoldTimeoutError(maxHoldMs)), maxHoldMs);
    const settle = <V,>(done: (value: V) => void) => (value: V) => {
      clearTimeout(timer);
      done(value);
    };
    try {
      fn().then(settle(resolve), settle(reject));
    } catch (error) {
      settle(reject)(error);
    }
  });
}

function readLease(leaseKey: string): { owner: string; until: number } | null {
  try {
    return JSON.parse(localStorage.getItem(leaseKey) || 'null');
  } catch {
    return null;
  }
}

/**
 * Runs fn while holding a localStorage lease, the fallback for browsers without Web Locks:
 * navigator.locks exists only in a secure context, so every tab of a plain-HTTP LAN install
 * (http://server:3000) lands here. A tab writes its claim, waits for a concurrent writer's claim
 * to land, and proceeds only if its own survived. Best effort, not a true mutex: two tabs whose
 * writes take longer than LEASE_CONFIRM_MS to reach each other can both proceed, and fn then
 * relies on its re-check of what another tab stored. The holder renews the lease while fn runs;
 * a waiter waits for as long as the lease is alive and never runs without it (running beside a
 * live holder presents the refresh token it is spending).
 */
async function withStorageLease<T>(leaseKey: string, fn: () => Promise<T>): Promise<T> {
  const owner = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  for (;;) {
    const lease = readLease(leaseKey);
    if (!lease || lease.until <= Date.now()) {
      try {
        localStorage.setItem(leaseKey, JSON.stringify({ owner, until: Date.now() + LEASE_TTL_MS }));
      } catch {
        return fn();
      }
      await sleep(LEASE_CONFIRM_MS);
      if (readLease(leaseKey)?.owner === owner) {
        // Released when this page goes away too (reload, navigation, close): a lease left behind
        // makes the next page's refresh wait for it to run out.
        const release = () => {
          try {
            if (readLease(leaseKey)?.owner === owner) localStorage.removeItem(leaseKey);
          } catch {
            // Storage refused: the lease runs out on its own.
          }
        };
        if (typeof window !== 'undefined') window.addEventListener('pagehide', release);
        const renew = setInterval(() => {
          try {
            if (readLease(leaseKey)?.owner === owner) {
              localStorage.setItem(leaseKey, JSON.stringify({ owner, until: Date.now() + LEASE_TTL_MS }));
            }
          } catch {
            // Storage refused the write: the lease runs out on its own, as for a dead tab.
          }
        }, LEASE_RENEW_MS);
        try {
          return await fn();
        } finally {
          clearInterval(renew);
          if (typeof window !== 'undefined') window.removeEventListener('pagehide', release);
          release();
        }
      }
    }
    await sleep(LEASE_POLL_MS + Math.random() * LEASE_POLL_MS);
  }
}

/**
 * Runs fn while holding a lock shared by every tab of this origin: Web Locks when the browser has
 * them, the localStorage lease otherwise, and also when the lock request itself is refused
 * (a document that is not fully active, an opaque origin) rather than failing the refresh.
 * With `maxHoldMs` the lock is held at most that long once granted (see CrossTabLockOptions).
 */
export function withCrossTabLock<T>(
  lockName: string,
  leaseKey: string,
  fn: () => Promise<T>,
  options: CrossTabLockOptions = {},
): Promise<T> {
  const held = () => boundedHold(fn, options.maxHoldMs);
  const locks = typeof navigator !== 'undefined'
    ? (navigator as Navigator & { locks?: { request: (name: string, cb: () => Promise<unknown>) => Promise<unknown> } }).locks
    : undefined;
  if (!locks?.request) return withStorageLease(leaseKey, held);
  let granted = false;
  return (locks.request(lockName, () => {
    granted = true;
    return held();
  }) as Promise<T>).catch((error) => {
    if (granted) throw error;
    return withStorageLease(leaseKey, held);
  });
}

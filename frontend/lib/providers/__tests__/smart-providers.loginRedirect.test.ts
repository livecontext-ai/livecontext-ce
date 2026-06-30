import { describe, expect, it } from 'vitest';
import {
  decideLoginRedirect,
  recordLoginRedirect,
  signedInWithin,
} from '../smart-providers';

/**
 * Minimal mutable in-memory sessionStorage stand-in (the real `auth_redirect_log`
 * budget lives in sessionStorage). Only the methods the loop breaker touches.
 */
function memoryStorage(): Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> & { dump: () => Record<string, string> } {
  const values: Record<string, string> = {};
  return {
    getItem: (key: string) => (key in values ? values[key] : null),
    setItem: (key: string, value: string) => {
      values[key] = value;
    },
    removeItem: (key: string) => {
      delete values[key];
    },
    dump: () => ({ ...values }),
  };
}

function throwingStorage(): Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> {
  return {
    getItem: () => {
      throw new Error('storage blocked');
    },
    setItem: () => {
      throw new Error('storage blocked');
    },
    removeItem: () => {
      throw new Error('storage blocked');
    },
  };
}

describe('decideLoginRedirect - login-redirect loop breaker', () => {
  // REGRESSION: a logout-race that keeps re-authenticating against a live Keycloak
  // SSO cookie used to loop /app/ -> /login -> Keycloak -> /app/ forever, because
  // loginWithRedirect cleared `auth_redirect_log` on EVERY call so the cap never
  // tripped. The automatic path must now stop after the 3-in-60s budget.
  it('stops an automatic redirect once the 3-in-60s budget is exhausted', () => {
    const storage = memoryStorage();
    const t = 1_000_000;

    expect(decideLoginRedirect(false, storage, t)).toBe('redirect');
    expect(decideLoginRedirect(false, storage, t + 10)).toBe('redirect');
    expect(decideLoginRedirect(false, storage, t + 20)).toBe('redirect');
    // 4th automatic bounce inside the window -> breaker trips, caller shows the
    // Session-expired UI instead of redirecting again.
    expect(decideLoginRedirect(false, storage, t + 30)).toBe('stop');
    // The trip clears the budget (loginWithRedirect then calls markSessionExpired,
    // whose Session-expired UI halts the loop so no further automatic redirect
    // fires). The budget itself is therefore fresh again after a trip.
    expect(decideLoginRedirect(false, storage, t + 40)).toBe('redirect');
  });

  it('lets an explicit user sign-in reset the budget so the user can always recover', () => {
    const storage = memoryStorage();
    const t = 2_000_000;

    // Exhaust the automatic budget.
    decideLoginRedirect(false, storage, t);
    decideLoginRedirect(false, storage, t);
    decideLoginRedirect(false, storage, t);
    expect(decideLoginRedirect(false, storage, t)).toBe('stop');

    // The user clicks "Sign in" on the Session-expired card (resetLoopGuards).
    expect(decideLoginRedirect(true, storage, t)).toBe('redirect');

    // The budget is fresh again: the next automatic redirect is allowed.
    expect(decideLoginRedirect(false, storage, t)).toBe('redirect');
  });

  it('prunes redirects that fall outside the 60s window', () => {
    const storage = memoryStorage();
    const t = 3_000_000;

    decideLoginRedirect(false, storage, t);
    decideLoginRedirect(false, storage, t + 1_000);
    decideLoginRedirect(false, storage, t + 2_000);
    // 61s after the first three: all are stale and pruned, so a fresh redirect is allowed.
    expect(decideLoginRedirect(false, storage, t + 61_000)).toBe('redirect');
  });

  it('fails open (allows the redirect) when sessionStorage is unavailable', () => {
    expect(decideLoginRedirect(false, null, 4_000_000)).toBe('redirect');
    expect(decideLoginRedirect(false, throwingStorage(), 4_000_000)).toBe('redirect');
  });

  // REGRESSION (the decisive one): the reported loop re-authenticates silently against
  // a live Keycloak SSO cookie, so onSigninCallback runs every cycle - it re-stamps
  // `auth_signin_at` AND wipes `auth_redirect_log`. The wipe defeats the 3-in-60s
  // counter, so the breaker MUST also stop on the "just signed in" signal. Pre-fix
  // (counter only, cleared every callback) this would redirect forever.
  it('stops an automatic redirect right after a successful Keycloak signin, even when onSigninCallback cleared the redirect counter', () => {
    const storage = memoryStorage();
    const t = 9_000_000;
    // One loop cycle just completed: Keycloak silently re-authenticated -> onSigninCallback.
    storage.setItem('auth_signin_at', String(t)); // stamped by onSigninCallback
    storage.removeItem('auth_redirect_log');       // wiped by the same onSigninCallback
    // The forced re-expiry now fires an automatic redirect almost immediately.
    expect(decideLoginRedirect(false, storage, t + 100)).toBe('stop');
  });

  it('does not trip the just-signed-in gate when there is no recent signin (e.g. a clean logout cleared auth_signin_at)', () => {
    const storage = memoryStorage();
    // No auth_signin_at -> the first automatic redirect proceeds to the Keycloak login form.
    expect(decideLoginRedirect(false, storage, 10_000_000)).toBe('redirect');
  });

  it('does not trip the just-signed-in gate for a signin older than the 30s window', () => {
    const storage = memoryStorage();
    const t = 11_000_000;
    storage.setItem('auth_signin_at', String(t));
    // 31s later the signin is stale -> gate silent -> the counter path allows the redirect.
    expect(decideLoginRedirect(false, storage, t + 31_000)).toBe('redirect');
  });

  it('an explicit user sign-in redirects even right after a signin (the gate only applies to automatic redirects)', () => {
    const storage = memoryStorage();
    const t = 12_000_000;
    storage.setItem('auth_signin_at', String(t));
    expect(decideLoginRedirect(true, storage, t + 100)).toBe('redirect');
  });

  it('an explicit user sign-in always redirects, even with no storage', () => {
    expect(decideLoginRedirect(true, null, 5_000_000)).toBe('redirect');
    expect(decideLoginRedirect(true, throwingStorage(), 5_000_000)).toBe('redirect');
  });
});

describe('recordLoginRedirect - shared budget primitive', () => {
  it('persists into the shared `auth_redirect_log` key so safeRedirectToLogin and loginWithRedirect share one budget', () => {
    const storage = memoryStorage();

    expect(recordLoginRedirect(storage, 6_000_000)).toBe(true);
    // The key name is the contract both redirect paths read/write.
    expect(storage.dump()['auth_redirect_log']).toBe(JSON.stringify([6_000_000]));
  });

  it('returns false and clears the log once the cap is hit', () => {
    const storage = memoryStorage();
    const t = 7_000_000;

    expect(recordLoginRedirect(storage, t)).toBe(true);
    expect(recordLoginRedirect(storage, t)).toBe(true);
    expect(recordLoginRedirect(storage, t)).toBe(true);
    expect(recordLoginRedirect(storage, t)).toBe(false);
    // The log is reset when the cap trips, so a later explicit retry isn't poisoned.
    expect(storage.dump()['auth_redirect_log']).toBeUndefined();
  });

  it('fails open when storage is missing or throws', () => {
    expect(recordLoginRedirect(null, 8_000_000)).toBe(true);
    expect(recordLoginRedirect(throwingStorage(), 8_000_000)).toBe(true);
  });
});

describe('signedInWithin - just-signed-in loop signal', () => {
  it('is true within the 30s window and false at/after the boundary', () => {
    const storage = memoryStorage();
    storage.setItem('auth_signin_at', '1000');

    expect(signedInWithin(storage, 1000)).toBe(true); // same instant
    expect(signedInWithin(storage, 1000 + 29_999)).toBe(true); // just under 30s
    expect(signedInWithin(storage, 1000 + 30_000)).toBe(false); // exactly 30s -> excluded (< window)
    expect(signedInWithin(storage, 1000 + 31_000)).toBe(false);
  });

  it('is false with no recorded signin, on unparseable data, and when storage is unavailable', () => {
    expect(signedInWithin(memoryStorage(), 1000)).toBe(false);

    const bad = memoryStorage();
    bad.setItem('auth_signin_at', 'not-a-number');
    expect(signedInWithin(bad, 1000)).toBe(false);

    expect(signedInWithin(null, 1000)).toBe(false);
    expect(signedInWithin(throwingStorage(), 1000)).toBe(false);
  });
});

/**
 * @vitest-environment jsdom
 *
 * Regression test for the "Sign In required on every fresh tab" UX bug
 * (2026-05-11). react-oidc-context defaults to `sessionStorage` for the OIDC
 * userStore - sessionStorage is per-tab, so opening the app in a new tab finds
 * `oidc.user === null`, the auto-recovery effect skips (it gates on
 * `oidc.user.expired === true`), and the user has to click Sign In manually
 * even though their Keycloak SSO cookie is still valid.
 *
 * Fix: oidcConfig.userStore = new WebStorageStateStore({ store: localStorage }).
 *
 * This test pins:
 *   1. userStore is a localStorage-backed WebStorageStateStore (NOT sessionStorage)
 *   2. automaticSilentRenew stays false (the custom recovery effect is the contract)
 *
 * If either invariant is broken, the manual-Sign-In bug returns.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { WebStorageStateStore } from 'oidc-client-ts';
import { oidcConfig } from '../providers';
import {
  signedInWithin,
  LOGIN_SIGNIN_AT_KEY,
  LOGIN_REDIRECT_LOG_KEY,
} from '../../lib/providers/smart-providers';

describe('oidcConfig - regression for "Sign In required on fresh tab" bug', () => {
  it('uses a localStorage-backed WebStorageStateStore (NOT sessionStorage)', async () => {
    expect(oidcConfig.userStore).toBeInstanceOf(WebStorageStateStore);

    // Round-trip via the store and verify the write lands in localStorage,
    // not sessionStorage. We compare entry count (not key name) because
    // WebStorageStateStore prefixes keys internally - checking the count
    // keeps the test resilient to that prefix changing.
    const store = oidcConfig.userStore as WebStorageStateStore;
    const testKey = '__regression-userStore-probe__';
    const lsBefore = localStorage.length;
    const ssBefore = sessionStorage.length;
    await store.set(testKey, 'value');
    try {
      expect(localStorage.length).toBe(lsBefore + 1);
      expect(sessionStorage.length).toBe(ssBefore);
    } finally {
      await store.remove(testKey);
    }
  });

  it('keeps automaticSilentRenew=false (custom auto-recovery effect is the contract)', () => {
    expect(oidcConfig.automaticSilentRenew).toBe(false);
  });
});

/**
 * The login/logout redirect-loop breaker hinges on a PRODUCER/CONSUMER key contract
 * that spans two files: `onSigninCallback` here STAMPS `auth_signin_at` and CLEARS
 * `auth_redirect_log`, and `smart-providers` reads those exact keys (`signedInWithin`
 * / `recordLoginRedirect`). They share the exported constants; this test runs the real
 * producer and asserts the real consumer observes it, so a key rename on either side
 * fails CI instead of silently re-opening the loop in production.
 */
describe('oidcConfig.onSigninCallback - loop-breaker key contract', () => {
  afterEach(() => {
    sessionStorage.removeItem(LOGIN_SIGNIN_AT_KEY);
    sessionStorage.removeItem(LOGIN_REDIRECT_LOG_KEY);
  });

  it('stamps a signin the consumer sees and clears the redirect counter via the shared keys', () => {
    // A stale loop counter from earlier redirects must be wiped by the callback.
    sessionStorage.setItem(LOGIN_REDIRECT_LOG_KEY, JSON.stringify([1, 2, 3]));

    oidcConfig.onSigninCallback();

    // The consumer `signedInWithin` reads the same key the producer just stamped.
    expect(signedInWithin(sessionStorage, Date.now())).toBe(true);
    // ...and the redirect-loop counter was cleared through the shared key.
    expect(sessionStorage.getItem(LOGIN_REDIRECT_LOG_KEY)).toBeNull();
  });
});

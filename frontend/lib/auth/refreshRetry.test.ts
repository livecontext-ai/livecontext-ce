import { describe, expect, it } from 'vitest';
import { ErrorResponse, ErrorTimeout } from 'oidc-client-ts';
import { CrossTabLockHoldTimeoutError } from './crossTabLock';
import { isTransientRefreshError } from './refreshRetry';

/**
 * Regression (CASA round 7): the 10 s refresh request timeout and the cross-tab lock hold bound
 * made a slow, not dead, network reject the refresh, and getAccessToken sent every rejection to
 * the login page. This pins the classification only: which oidc-client-ts errors are a refusal by
 * the server (sign out) and which are a slow or dead network (retry). The retry itself is driven
 * through the real UserManager and a stubbed token endpoint in
 * lib/providers/__tests__/smart-providers.cloudRefresh.test.tsx (CASA round 8: mocking a rejected
 * refresh hid that the provider's refresh never rejected).
 */

const invalidGrant = () => new ErrorResponse({ error: 'invalid_grant', error_description: 'Token is not active' });

describe('isTransientRefreshError', () => {
  it('timeouts, network failures, 5xx, 408/429 and temporary OAuth errors are transient', () => {
    expect(isTransientRefreshError(new ErrorTimeout('Network timed out'))).toBe(true);
    expect(isTransientRefreshError(new CrossTabLockHoldTimeoutError(30_000))).toBe(true);
    expect(isTransientRefreshError(new TypeError('Failed to fetch'))).toBe(true);
    expect(isTransientRefreshError(new Error('Bad Gateway (502)'))).toBe(true);
    expect(isTransientRefreshError(new Error('Request Timeout (408)'))).toBe(true);
    expect(isTransientRefreshError(new Error('Too Many Requests (429)'))).toBe(true);
    expect(isTransientRefreshError(new Error('Invalid response Content-Type: text/html, from URL: x'))).toBe(true);
    expect(isTransientRefreshError(new ErrorResponse({ error: 'temporarily_unavailable' }))).toBe(true);
  });

  it('a refusal by the server is not: invalid_grant, other OAuth errors, other 4xx', () => {
    expect(isTransientRefreshError(invalidGrant())).toBe(false);
    expect(isTransientRefreshError(new ErrorResponse({ error: 'unauthorized_client' }))).toBe(false);
    expect(isTransientRefreshError(new Error('Bad Request (400): {"foo":1}'))).toBe(false);
    expect(isTransientRefreshError(new Error('Unauthorized (401)'))).toBe(false);
  });
});

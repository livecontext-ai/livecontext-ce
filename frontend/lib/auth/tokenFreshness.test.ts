import { describe, expect, it } from 'vitest';
import { TOKEN_REFRESH_LEEWAY_SECONDS, accessTokenAction, tokenNeedsRefresh } from './tokenFreshness';

describe('tokenNeedsRefresh (15-minute access tokens, CASA LC-014)', () => {
  const now = 1_800_000_000;

  it('keeps a token with more than the leeway left', () => {
    expect(tokenNeedsRefresh(now + 600, now)).toBe(false);
    expect(tokenNeedsRefresh(now + TOKEN_REFRESH_LEEWAY_SECONDS, now)).toBe(false);
  });

  it('refreshes a token that is NOT yet expired but inside the last 60 s (it would die in flight)', () => {
    expect(TOKEN_REFRESH_LEEWAY_SECONDS).toBe(60);
    expect(tokenNeedsRefresh(now + 59, now)).toBe(true);
    expect(tokenNeedsRefresh(now + 1, now)).toBe(true);
  });

  it('refreshes an expired token and a token of unknown expiry', () => {
    expect(tokenNeedsRefresh(now - 1, now)).toBe(true);
    expect(tokenNeedsRefresh(undefined, now)).toBe(true);
    expect(tokenNeedsRefresh(null, now)).toBe(true);
    expect(tokenNeedsRefresh(Number.NaN, now)).toBe(true);
  });
});

describe('accessTokenAction (the getAccessToken decision)', () => {
  const now = 1_800_000_000;

  it('returns the cached token only when it has more than 60 s left and nothing forces a refresh', () => {
    expect(accessTokenAction({ expires_at: now + 600 }, false, now)).toBe('cached');
  });

  it('refreshes near expiry instead of waiting for "expired" (the old rule sent dying tokens)', () => {
    expect(accessTokenAction({ expires_at: now + 30 }, false, now)).toBe('refresh');
  });

  it('refreshes after a 401 even when the cached token still looks valid', () => {
    expect(accessTokenAction({ expires_at: now + 600 }, true, now)).toBe('refresh');
  });

  it('does nothing without a session', () => {
    expect(accessTokenAction(null, true, now)).toBe('none');
    expect(accessTokenAction(undefined, false, now)).toBe('none');
  });
});

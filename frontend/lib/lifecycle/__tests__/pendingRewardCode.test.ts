// @vitest-environment jsdom
//
// A partner / creator code followed from a link must survive sign-up: it is read from the URL,
// remembered (first code wins, 30-day life), and a blocked store never breaks the page.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  MAX_AGE_MS,
  PENDING_REWARD_CODE_KEY,
  capturePendingRewardCode,
  clearPendingRewardCode,
  codeFromUrl,
  readPendingRewardCode,
} from '../pendingRewardCode';

function at(href: string): Window {
  window.history.replaceState({}, '', href);
  return window;
}

beforeEach(() => localStorage.clear());
afterEach(() => vi.restoreAllMocks());

describe('codeFromUrl', () => {
  it('reads ?lc_ref= on any page, upper-cased', () => {
    expect(codeFromUrl(new URL('https://x.ai/fr?lc_ref=techdox&utm_source=yt'))).toBe('TECHDOX');
  });

  it('ignores the conventional ?ref= that directories put on their links (Product Hunt, AI lists)', () => {
    expect(codeFromUrl(new URL('https://x.ai/?ref=producthunt'))).toBeNull();
  });

  it('reads ?code= only on the /redeem landing (a ?code= elsewhere is some other feature)', () => {
    expect(codeFromUrl(new URL('https://x.ai/redeem?code=LC-ABCD2345'))).toBe('LC-ABCD2345');
    expect(codeFromUrl(new URL('https://x.ai/app/oauth/callback?code=abc123'))).toBeNull();
  });

  it('rejects anything that is not a plausible code', () => {
    expect(codeFromUrl(new URL('https://x.ai/?lc_ref=a'))).toBeNull();
    expect(codeFromUrl(new URL('https://x.ai/?lc_ref=%3Cscript%3E'))).toBeNull();
    expect(codeFromUrl(new URL('https://x.ai/'))).toBeNull();
  });
});

describe('capture / read / clear', () => {
  it('remembers the code from the landing URL and reads it back after sign-in', () => {
    capturePendingRewardCode(at('/?lc_ref=TECHDOX'), 1_000);

    expect(readPendingRewardCode(window, 2_000)).toBe('TECHDOX');
  });

  it('first code wins: a second link never replaces a code still waiting', () => {
    capturePendingRewardCode(at('/?lc_ref=FIRST'), 1_000);
    const kept = capturePendingRewardCode(at('/?lc_ref=SECOND'), 2_000);

    expect(kept).toBe('FIRST');
    expect(readPendingRewardCode(window, 3_000)).toBe('FIRST');
  });

  it('an explicit /redeem?code= visit replaces a waiting code (the page promises THAT code)', () => {
    capturePendingRewardCode(at('/?lc_ref=FIRST'), 1_000);
    capturePendingRewardCode(at('/redeem?code=CHOSEN'), 2_000);

    expect(readPendingRewardCode(window, 3_000)).toBe('CHOSEN');
  });

  it('an expired code is forgotten, and a new link can then be remembered', () => {
    capturePendingRewardCode(at('/?lc_ref=OLD'), 0);

    expect(readPendingRewardCode(window, MAX_AGE_MS)).toBeNull();
    expect(localStorage.getItem(PENDING_REWARD_CODE_KEY)).toBeNull();
    capturePendingRewardCode(at('/?lc_ref=NEW'), MAX_AGE_MS + 1);
    expect(readPendingRewardCode(window, MAX_AGE_MS + 2)).toBe('NEW');
  });

  it('clear forgets the code', () => {
    capturePendingRewardCode(at('/?lc_ref=TECHDOX'));
    clearPendingRewardCode(window);

    expect(readPendingRewardCode(window)).toBeNull();
  });

  it('a page without a code stores nothing', () => {
    expect(capturePendingRewardCode(at('/pricing'))).toBeNull();
    expect(localStorage.getItem(PENDING_REWARD_CODE_KEY)).toBeNull();
  });

  it('a blocked store never throws (private mode, site data disabled)', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });

    expect(capturePendingRewardCode(at('/?lc_ref=TECHDOX'))).toBe('TECHDOX');
    expect(readPendingRewardCode(window)).toBeNull();
    expect(() => clearPendingRewardCode(window)).not.toThrow();
  });

  it('a corrupted stored value reads as no code', () => {
    localStorage.setItem(PENDING_REWARD_CODE_KEY, '{not json');

    expect(readPendingRewardCode(window)).toBeNull();
  });
});

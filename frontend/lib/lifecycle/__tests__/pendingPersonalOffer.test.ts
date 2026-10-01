// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  capturePendingPersonalOffer,
  clearPendingPersonalOffer,
  personalOfferCodeFromUrl,
  readPendingPersonalOffer,
} from '../pendingPersonalOffer';
import { readPendingRewardCode } from '../pendingRewardCode';

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  window.history.replaceState({}, '', '/');
});
afterEach(() => vi.restoreAllMocks());

describe('personal offer capture', () => {
  it('captures lc_offer without claiming or replacing a partner attribution', () => {
    window.history.replaceState({}, '', '/fr/app/settings/pricing?lc_offer=abC123&lc_ref=TECHDOX&billingCycle=monthly');

    expect(capturePendingPersonalOffer(window)).toBe('ABC123');
    expect(readPendingPersonalOffer(window)).toBe('ABC123');
    expect(readPendingRewardCode(window)).toBeNull();
    expect(window.location.search).toBe('?lc_ref=TECHDOX&billingCycle=monthly');
  });

  it('does not interpret lc_ref as a personal offer', () => {
    expect(personalOfferCodeFromUrl(new URL('https://example.test/?lc_ref=TECHDOX'))).toBeNull();
  });

  it('leaves the URL available when session storage is blocked', () => {
    window.history.replaceState({}, '', '/fr/app/settings/pricing?lc_offer=ABC123');
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });

    expect(capturePendingPersonalOffer(window)).toBe('ABC123');
    expect(window.location.search).toBe('?lc_offer=ABC123');
  });

  it('forgets an expired pending code', () => {
    window.history.replaceState({}, '', '/fr/app/settings/pricing?lc_offer=ABC123');
    capturePendingPersonalOffer(window, 1000);

    expect(readPendingPersonalOffer(window, 1000 + 4 * 24 * 60 * 60 * 1000)).toBeNull();
  });

  it('clears only the personal offer slot', () => {
    window.history.replaceState({}, '', '/fr/app/settings/pricing?lc_offer=ABC123');
    capturePendingPersonalOffer(window);
    clearPendingPersonalOffer(window);

    expect(readPendingPersonalOffer(window)).toBeNull();
  });
});

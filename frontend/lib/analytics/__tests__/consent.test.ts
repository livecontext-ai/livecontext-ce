// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { isAnalyticsConsentGranted } from '../consent';

const KEY = 'lc.cookieConsent';

describe('isAnalyticsConsentGranted', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('is true only for an explicit accepted choice at the current version', () => {
    localStorage.setItem(KEY, JSON.stringify({ status: 'accepted', version: 1, ts: 1 }));
    expect(isAnalyticsConsentGranted()).toBe(true);
  });

  it('is false when the user rejected', () => {
    localStorage.setItem(KEY, JSON.stringify({ status: 'rejected', version: 1, ts: 1 }));
    expect(isAnalyticsConsentGranted()).toBe(false);
  });

  it('is false when no choice was made yet', () => {
    expect(isAnalyticsConsentGranted()).toBe(false);
  });

  it('is false when the stored consent version is stale (re-consent required)', () => {
    localStorage.setItem(KEY, JSON.stringify({ status: 'accepted', version: 0, ts: 1 }));
    expect(isAnalyticsConsentGranted()).toBe(false);
  });

  it('is false when the stored value is malformed JSON', () => {
    localStorage.setItem(KEY, 'not-json{');
    expect(isAnalyticsConsentGranted()).toBe(false);
  });
});

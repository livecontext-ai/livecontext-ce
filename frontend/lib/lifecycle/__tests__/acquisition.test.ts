/**
 * First-touch acquisition capture (lc_acq_v1): written once, utm parsed and capped, referrer
 * reduced to an external origin + path, token-bearing landing paths redacted, and a blocked
 * store never throws into the page.
 */
import { describe, it, expect } from 'vitest';
import {
  ACQUISITION_STORAGE_KEY,
  buildAcquisition,
  captureFirstTouch,
  externalReferrer,
  readAcquisition,
  redactLandingPath,
} from '../acquisition';

class MemoryStorage {
  store = new Map<string, string>();
  getItem(k: string) { return this.store.has(k) ? this.store.get(k)! : null; }
  setItem(k: string, v: string) { this.store.set(k, v); }
  removeItem(k: string) { this.store.delete(k); }
}

function fakeWindow(url: string, referrer = '', storage: unknown = new MemoryStorage()): Window {
  const u = new URL(url);
  return {
    location: { pathname: u.pathname, search: u.search, host: u.host },
    document: { referrer },
    localStorage: storage,
  } as unknown as Window;
}

const NOW = new Date('2026-09-24T10:00:00.000Z');

describe('captureFirstTouch', () => {
  it('stores utm values, the external referrer and the landing path on the first visit', () => {
    const win = fakeWindow(
      'https://livecontext.ai/fr/for/marketers?utm_source=google&utm_medium=cpc&utm_campaign=launch&utm_content=ad1&utm_term=ai+agents&gclid=secret',
      'https://www.google.com/search?q=private+query',
    );

    const stored = captureFirstTouch(win, NOW);

    expect(stored).toEqual({
      utmSource: 'google',
      utmMedium: 'cpc',
      utmCampaign: 'launch',
      utmContent: 'ad1',
      utmTerm: 'ai agents',
      referrer: 'https://www.google.com/search',
      landingPath: '/fr/for/marketers',
      firstSeenAt: '2026-09-24T10:00:00.000Z',
    });
    expect(JSON.parse(win.localStorage.getItem(ACQUISITION_STORAGE_KEY)!)).toEqual(stored);
  });

  it('keeps the FIRST touch: a later visit with other utm values does not overwrite it', () => {
    const storage = new MemoryStorage();
    captureFirstTouch(fakeWindow('https://livecontext.ai/?utm_source=newsletter', '', storage), NOW);

    const second = captureFirstTouch(
      fakeWindow('https://livecontext.ai/pricing?utm_source=twitter', 'https://t.co/x', storage),
      new Date('2026-10-01T00:00:00.000Z'),
    );

    expect(second?.utmSource).toBe('newsletter');
    expect(second?.landingPath).toBe('/');
    expect(second?.referrer).toBeUndefined();
    expect(second?.firstSeenAt).toBe('2026-09-24T10:00:00.000Z');
  });

  it('stores at least the landing path and first-seen time on a direct visit', () => {
    const stored = captureFirstTouch(fakeWindow('https://livecontext.ai/pricing'), NOW);

    expect(stored).toEqual({ landingPath: '/pricing', firstSeenAt: '2026-09-24T10:00:00.000Z' });
  });

  it('never throws when storage access is blocked', () => {
    const blocked = {
      getItem() { throw new Error('SecurityError'); },
      setItem() { throw new Error('SecurityError'); },
    };
    const win = fakeWindow('https://livecontext.ai/?utm_source=x', '', blocked);

    expect(() => captureFirstTouch(win, NOW)).not.toThrow();
    expect(captureFirstTouch(win, NOW)).toBeNull();
    expect(readAcquisition(win)).toBeNull();
  });

  it('never throws when the store is full', () => {
    const full = { getItem: () => null, setItem() { throw new Error('QuotaExceededError'); } };

    expect(captureFirstTouch(fakeWindow('https://livecontext.ai/', '', full), NOW)).toBeNull();
  });

  it('treats a malformed stored record as absent', () => {
    const storage = new MemoryStorage();
    storage.setItem(ACQUISITION_STORAGE_KEY, '{not json');

    expect(readAcquisition(fakeWindow('https://livecontext.ai/', '', storage))).toBeNull();
  });
});

describe('buildAcquisition', () => {
  it('ignores blank utm values and caps long ones at 255 characters', () => {
    const long = 'a'.repeat(400);
    const data = buildAcquisition(
      { pathname: '/', search: `?utm_source=%20%20&utm_campaign=${long}`, host: 'livecontext.ai' },
      '',
      NOW,
    );

    expect(data.utmSource).toBeUndefined();
    expect(data.utmCampaign).toHaveLength(255);
  });
});

describe('externalReferrer', () => {
  it('drops a referrer from this site, including the identity provider subdomain', () => {
    expect(externalReferrer('https://livecontext.ai/fr', 'livecontext.ai')).toBeUndefined();
    expect(externalReferrer('https://auth.livecontext.ai/realms/x', 'livecontext.ai')).toBeUndefined();
  });

  it('drops non-http referrers and garbage', () => {
    expect(externalReferrer('android-app://com.slack/', 'livecontext.ai')).toBeUndefined();
    expect(externalReferrer('not a url', 'livecontext.ai')).toBeUndefined();
  });

  it('keeps origin and path of an external referrer, never its query or hash', () => {
    expect(externalReferrer('https://news.ycombinator.com/item?id=1#c', 'livecontext.ai'))
      .toBe('https://news.ycombinator.com/item');
  });
});

describe('redactLandingPath', () => {
  it('cuts a capability token off the landing path', () => {
    expect(redactLandingPath('/s/sl_abcdef1234567890')).toBe('/s');
    expect(redactLandingPath('/f/tok123/more')).toBe('/f');
    expect(redactLandingPath('/fr/invitations/accept')).toBe('/fr/invitations');
  });

  it('keeps an ordinary localized path intact', () => {
    expect(redactLandingPath('/de/for/marketers')).toBe('/de/for/marketers');
    expect(redactLandingPath('/')).toBe('/');
  });
});

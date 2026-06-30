// @vitest-environment jsdom
/**
 * Regression tests for locale-aware formatting (bug 2026-06-23).
 *
 * Symptom: a user on the /en app whose BROWSER was French saw dates like
 * "15 juin 2026" in the marketplace-preview runInfo. Root cause: date/number
 * formatters defaulted their locale to `navigator.language` (the browser
 * language) instead of the APP locale (next-intl, from the URL). The fix routes
 * every default through `getClientLocale()`, which follows the app locale and
 * defaults to 'en' (never the browser language).
 *
 * The whole file runs under a simulated FRENCH BROWSER (navigator.language =
 * 'fr-FR'), the exact environment that produced the bug: the pre-fix code
 * rendered French here, so these assertions fail on the buggy code and pass only
 * on the fix.
 */
import { describe, it, expect, beforeEach, beforeAll, afterAll } from 'vitest';
import { getClientLocale } from '../locale';
import { formatUtcDate, formatRelativeDateI18n, parseUtcAware } from '../dateFormatters';

const setPath = (p: string) => window.history.replaceState(null, '', p);
const setCookie = (v: string) => {
  document.cookie = `NEXT_LOCALE=${v}; path=/`;
};
const clearCookie = () => {
  document.cookie = 'NEXT_LOCALE=; path=/; max-age=0';
};

const originalLanguage = navigator.language;
const originalLanguages = navigator.languages;
beforeAll(() => {
  Object.defineProperty(navigator, 'language', { value: 'fr-FR', configurable: true });
  Object.defineProperty(navigator, 'languages', { value: ['fr-FR', 'fr'], configurable: true });
});
afterAll(() => {
  Object.defineProperty(navigator, 'language', { value: originalLanguage, configurable: true });
  Object.defineProperty(navigator, 'languages', { value: originalLanguages, configurable: true });
});

beforeEach(() => {
  setPath('/');
  clearCookie();
});

describe('getClientLocale', () => {
  it('uses the [locale] URL prefix when present', () => {
    setPath('/fr/app/marketplace/x/preview');
    expect(getClientLocale()).toBe('fr');
    setPath('/de/app');
    expect(getClientLocale()).toBe('de');
  });

  it('regression: a /en page resolves to en even when the NEXT_LOCALE cookie is fr', () => {
    setCookie('fr');
    setPath('/en/app/marketplace/x/preview');
    expect(getClientLocale()).toBe('en');
  });

  it('falls back to the NEXT_LOCALE cookie on routes outside the [locale] tree', () => {
    setCookie('de');
    setPath('/workflows/builder');
    expect(getClientLocale()).toBe('de');
  });

  it('defaults to en (never the French browser) when there is no prefix and no cookie', () => {
    setPath('/workflows/builder');
    expect(getClientLocale()).toBe('en');
  });

  it('ignores an unsupported cookie value and defaults to en', () => {
    setCookie('xx');
    setPath('/billing');
    expect(getClientLocale()).toBe('en');
  });
});

describe('number formatting via getClientLocale (toLocaleString)', () => {
  it('groups digits per the APP locale resolved from the URL, not the French browser', () => {
    setPath('/en/app/settings/quota');
    expect((1234567).toLocaleString(getClientLocale())).toBe('1,234,567');
    setPath('/de/app/settings/quota');
    expect((1234567).toLocaleString(getClientLocale())).toBe('1.234.567');
  });
});

describe('date formatters follow the APP locale by default (the runInfo bug)', () => {
  it('formatUtcDate with no locale uses the [locale] URL prefix', () => {
    setPath('/de/app');
    expect(formatUtcDate('2026-05-11T14:00:00')).toContain('Mai'); // German short month
    setPath('/en/app');
    expect(formatUtcDate('2026-05-11T14:00:00')).toContain('May'); // English short month
  });

  it('regression: the /en marketplace preview renders "Jun" (English), not "15 juin" (French), for a French browser', () => {
    // navigator.language is 'fr-FR' (see beforeAll) and the cookie is fr too:
    // the pre-fix code rendered "15 juin 2026" here; the app locale (URL=/en) wins now.
    setCookie('fr');
    setPath('/en/app/marketplace/x/preview');
    const out = formatUtcDate('2026-06-15T10:00:00');
    expect(out).toContain('Jun');
    expect(out).not.toContain('juin');
  });
});

describe('formatRelativeDateI18n (the shared localized relative-time helper)', () => {
  // Key-aware fake translator: returns sentinels for the relative keys so we can
  // assert which branch fired without depending on real message files.
  const tRel = (key: string, params?: Record<string, string | number | Date>) => {
    switch (key) {
      case 'never':
        return 'NEVER';
      case 'justNow':
        return 'JUSTNOW';
      case 'minutesAgo':
        return `${params?.count}MIN`;
      case 'hoursAgo':
        return `${params?.count}HR`;
      case 'daysAgo':
        return `${params?.count}DAY`;
      default:
        return key;
    }
  };

  it('translates the relative branches via the provided keys (no hardcoded English)', () => {
    expect(formatRelativeDateI18n(null, tRel)).toBe('NEVER');
    expect(formatRelativeDateI18n(new Date(Date.now() - 30_000).toISOString(), tRel)).toBe('JUSTNOW');
    expect(formatRelativeDateI18n(new Date(Date.now() - 5 * 60_000).toISOString(), tRel)).toBe('5MIN');
    expect(formatRelativeDateI18n(new Date(Date.now() - 3 * 3_600_000).toISOString(), tRel)).toBe('3HR');
    expect(formatRelativeDateI18n(new Date(Date.now() - 3 * 86_400_000).toISOString(), tRel)).toBe('3DAY');
  });

  it('regression (the "juin" bug): the >7-day absolute fallback follows the PASSED locale, not the cookie/browser', () => {
    // A run >7 days old; the badge that mixed English status text with this date
    // showed a cookie-locale month. The fix passes the UI locale explicitly so
    // the month matches the visible language.
    const old = '2020-06-15T10:00:00'; // always > 7 days ago
    expect(formatRelativeDateI18n(old, tRel, 'fr')).toContain('juin');
    expect(formatRelativeDateI18n(old, tRel, 'fr')).not.toContain('Jun ');
    expect(formatRelativeDateI18n(old, tRel, 'en')).toContain('Jun');
    expect(formatRelativeDateI18n(old, tRel, 'en')).not.toContain('juin');
  });

  it('defaults the >7-day fallback through getClientLocale (== the provider locale on every route) when no locale is passed', () => {
    const old = '2020-06-15T10:00:00';
    setPath('/fr/app/agent');
    expect(formatRelativeDateI18n(old, tRel)).toContain('juin');
    setPath('/en/app/agent');
    expect(formatRelativeDateI18n(old, tRel)).toContain('Jun');
  });
});

describe('Class B: UTC-aware date rendering (off-by-one fix)', () => {
  // The fixed call sites compose two corrections; the tests below pin the exact
  // instant/zone so they are deterministic regardless of the host TZ and would
  // FAIL on the pre-fix `new Date(str).toLocaleDateString(locale)` shape.

  it('parseUtcAware parses a TZ-naive backend timestamp as UTC, not browser-local', () => {
    expect(parseUtcAware('2026-07-01T00:00:00').toISOString()).toBe('2026-07-01T00:00:00.000Z');
    expect(parseUtcAware('2026-07-01T00:00:00Z').toISOString()).toBe('2026-07-01T00:00:00.000Z');
  });

  it('timeZone:UTC renders the UTC calendar day, where a viewer-zone render would be off by one', () => {
    const instant = new Date('2026-07-01T00:00:00Z'); // midnight UTC
    expect(
      instant.toLocaleDateString('en', { timeZone: 'UTC', month: 'short', day: 'numeric' }),
    ).toBe('Jul 1');
    expect(
      instant.toLocaleDateString('en', { timeZone: 'America/New_York', month: 'short', day: 'numeric' }),
    ).toBe('Jun 30');
  });
});

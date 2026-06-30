/**
 * Regression tests for the locale path helpers.
 *
 * Bug (2026-06-12): the helpers (and several inline copies) hardcoded
 * (en|fr|es), so de/pt/zh users had the locale prefix left in the pathname -
 * useCurrentView then failed to match any /app/* route and the whole view
 * detection (breadcrumb, sidebar highlight, EmptyCanvasChat gating) broke.
 * The old prefix regex also lacked a boundary, so '/enterprise' was parsed
 * as locale 'en' + '/terprise'.
 */
import { describe, expect, it } from 'vitest';
import { removeLocalePrefix, parseLocalePath, buildLocalePath } from '../locale';
import { locales } from '@/i18n/routing';

describe('removeLocalePrefix', () => {
  it('strips EVERY locale of the routing config (regression: de/pt/zh were not stripped)', () => {
    for (const locale of locales) {
      expect(removeLocalePrefix(`/${locale}/app/workflow`)).toBe('/app/workflow');
    }
  });

  it('regression: does not mistake a path starting with a locale string for a locale prefix', () => {
    expect(removeLocalePrefix('/enterprise')).toBe('/enterprise');
    expect(removeLocalePrefix('/free-trial')).toBe('/free-trial');
  });

  it('leaves unprefixed paths untouched and handles null', () => {
    expect(removeLocalePrefix('/app/workflow')).toBe('/app/workflow');
    expect(removeLocalePrefix(null)).toBe('');
  });
});

describe('parseLocalePath', () => {
  it('extracts EVERY routing locale (regression: de/pt/zh fell through to no-locale)', () => {
    for (const locale of locales) {
      expect(parseLocalePath(`/${locale}/app/workflow`)).toEqual({ locale, normalized: '/app/workflow' });
    }
  });

  it('returns no locale for unprefixed and lookalike paths', () => {
    expect(parseLocalePath('/app/workflow')).toEqual({ locale: '', normalized: '/app/workflow' });
    expect(parseLocalePath('/enterprise')).toEqual({ locale: '', normalized: '/enterprise' });
  });

  it('normalizes a bare locale path to /', () => {
    expect(parseLocalePath('/de')).toEqual({ locale: 'de', normalized: '/' });
  });
});

describe('buildLocalePath', () => {
  it('prefixes non-default locales and leaves en/empty unprefixed', () => {
    expect(buildLocalePath('de', '/app/workflow')).toBe('/de/app/workflow');
    expect(buildLocalePath('en', '/app/workflow')).toBe('/app/workflow');
    expect(buildLocalePath('', '/app/workflow')).toBe('/app/workflow');
  });
});

/**
 * Regression tests for the locale resolver used by route trees OUTSIDE the
 * `[locale]` segment (e.g. /workflows, /billing, the share/embed token routes).
 *
 * Bug 2026-06-24: those routes resolved the next-intl provider locale from the
 * `Accept-Language` HEADER (the BROWSER language) when no NEXT_LOCALE cookie was
 * set, while the date/number formatters (getClientLocale) only ever read the
 * cookie or defaulted to 'en'. A French-browser user with no cookie therefore
 * got French UI TEXT next to English dates - and the rest of the app (the
 * [locale] tree, which the middleware sends to /en by default) stayed English.
 *
 * The fix makes this resolver mirror getClientLocale and the [locale] default:
 * NEXT_LOCALE cookie if valid, else 'en'. It must NEVER consult Accept-Language.
 *
 * One header IS read: the language a localized public page's URL names (/fr/partners), which
 * the proxy sets itself in PAGE_LOCALE_HEADER. It wins over the cookie, so one URL is one language.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

// `server-only` throws outside a React Server Component; stub it for the unit test.
vi.mock('server-only', () => ({}));

const cookieGet = vi.fn();
const headerGet = vi.fn();
vi.mock('next/headers', () => ({
  cookies: async () => ({ get: cookieGet }),
  // Only the proxy's page-language header may be read - never Accept-Language (asserted below).
  headers: async () => ({ get: headerGet }),
}));

import { resolveRequestLocale } from '../resolveRequestLocale';
import { PAGE_LOCALE_HEADER } from '@/lib/seo/siteUrl';

describe('resolveRequestLocale', () => {
  beforeEach(() => {
    cookieGet.mockReset();
    headerGet.mockReset();
  });

  it('returns the NEXT_LOCALE cookie when it is a known locale', async () => {
    cookieGet.mockReturnValue({ value: 'de' });
    expect(await resolveRequestLocale()).toBe('de');
  });

  it('defaults to en when there is no cookie - never the browser Accept-Language', async () => {
    cookieGet.mockReturnValue(undefined);
    // A French browser, and no page language set by the proxy.
    headerGet.mockImplementation((name: string) => (name.toLowerCase() === 'accept-language' ? 'fr-FR,fr;q=0.9' : null));
    expect(await resolveRequestLocale()).toBe('en');
    // Accept-Language must not even be consulted: the divergence root was reading it.
    expect(headerGet.mock.calls.map(([name]) => name)).toEqual([PAGE_LOCALE_HEADER]);
  });

  it("a localized public page's URL language (set by the proxy) wins over the cookie", async () => {
    headerGet.mockImplementation((name: string) => (name === PAGE_LOCALE_HEADER ? 'fr' : null));
    cookieGet.mockReturnValue({ value: 'de' });
    expect(await resolveRequestLocale()).toBe('fr');
  });

  it('an unknown page language is ignored, and the cookie decides', async () => {
    headerGet.mockImplementation((name: string) => (name === PAGE_LOCALE_HEADER ? 'xx' : null));
    cookieGet.mockReturnValue({ value: 'de' });
    expect(await resolveRequestLocale()).toBe('de');
  });

  it('ignores an unsupported cookie value and defaults to en', async () => {
    cookieGet.mockReturnValue({ value: 'xx' });
    expect(await resolveRequestLocale()).toBe('en');
  });
});

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
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

// `server-only` throws outside a React Server Component; stub it for the unit test.
vi.mock('server-only', () => ({}));

const cookieGet = vi.fn();
const headerGet = vi.fn();
vi.mock('next/headers', () => ({
  cookies: async () => ({ get: cookieGet }),
  // Present but must remain UNUSED - asserted below via headerGet not being called.
  headers: async () => ({ get: headerGet }),
}));

import { resolveRequestLocale } from '../resolveRequestLocale';

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
    headerGet.mockReturnValue('fr-FR,fr;q=0.9'); // a French browser
    expect(await resolveRequestLocale()).toBe('en');
    // The header must not even be consulted: the divergence root was reading it.
    expect(headerGet).not.toHaveBeenCalled();
  });

  it('ignores an unsupported cookie value and defaults to en', async () => {
    cookieGet.mockReturnValue({ value: 'xx' });
    expect(await resolveRequestLocale()).toBe('en');
  });
});

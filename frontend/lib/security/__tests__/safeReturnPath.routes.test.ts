import { describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/edition', () => ({ IS_CE: false }));
// next-intl's ESM middleware build does not resolve under vitest; no path here reaches it.
vi.mock('next-intl/middleware', () => ({ default: () => () => undefined }));

import {
  GATEWAY_REWRITE_SEGMENTS,
  LOCALE_REQUIRED_PREFIXES,
  PUBLIC_ROUTE_SEGMENTS,
} from '@/proxy';
import { isSafeReturnPath } from '@/lib/security/safeReturnPath';

/**
 * The deny-list holds STRING prefixes (`/api*`, `/ws*`, `/form*`, `/chat*`, ...), which would
 * silently swallow a future page whose name starts with the same letters. These cases are derived
 * from the middleware's own route lists, so adding such a page fails here instead of sending its
 * visitors to the fallback after sign-in. The other direction too: every next.config gateway
 * rewrite must stay refused.
 */
const LOCALE = 'en';

describe('safeReturnPath against the real route lists of proxy.ts', () => {
  it.each(PUBLIC_ROUTE_SEGMENTS.map((segment) => [segment]))(
    'keeps every page under the public route /%s, bare, nested and locale-prefixed',
    (segment) => {
      expect(isSafeReturnPath(`/${segment}`)).toBe(true);
      expect(isSafeReturnPath(`/${segment}/x`)).toBe(true);
      expect(isSafeReturnPath(`/${LOCALE}/${segment}/x`)).toBe(true);
    },
  );

  it.each(LOCALE_REQUIRED_PREFIXES.map((prefix) => [prefix]))(
    'keeps every page under the locale-required area %s, with and without a locale',
    (prefix) => {
      expect(isSafeReturnPath(prefix)).toBe(true);
      expect(isSafeReturnPath(`${prefix}/x`)).toBe(true);
      expect(isSafeReturnPath(`/${LOCALE}${prefix}`)).toBe(true);
      expect(isSafeReturnPath(`/${LOCALE}${prefix}/x`)).toBe(true);
    },
  );

  it.each(GATEWAY_REWRITE_SEGMENTS.map((segment) => [segment]))(
    'refuses the gateway rewrite /%s, bare, nested and locale-prefixed',
    (segment) => {
      expect(isSafeReturnPath(`/${segment}`)).toBe(false);
      expect(isSafeReturnPath(`/${segment}/x`)).toBe(false);
      expect(isSafeReturnPath(`/${LOCALE}/${segment}/x`)).toBe(false);
    },
  );

  it('keeps the bare /app/public (a locale redirect to an app page) and refuses its subtree', () => {
    expect(isSafeReturnPath('/app/public')).toBe(true);
    expect(isSafeReturnPath(`/${LOCALE}/app/public`)).toBe(true);
    expect(isSafeReturnPath(`/${LOCALE}/app/public/x`)).toBe(true);
    expect(isSafeReturnPath('/app/public/x')).toBe(false);
  });
});

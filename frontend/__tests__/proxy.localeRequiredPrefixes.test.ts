/**
 * Two guards for the same bug, one behavioural and one structural.
 *
 * `/forgot-password` and `/reset-password` shipped as real pages under
 * `app/[locale]/` without being added to `LOCALE_REQUIRED_PREFIXES`, so the
 * proxy stripped the locale, the one-segment path matched no static route and
 * was not a public index segment, and every entry point answered a hard 404:
 * the "Forgot password?" link on the login page, and the link inside the reset
 * e-mail itself.
 *
 * Nothing caught it. The backend suite was green, `tsc` was clean, the page's
 * own unit tests passed, and `proxy.unknown-route.test.ts` passed too, because
 * it hand-lists the routes it exercises. A route nobody thought to list is a
 * route nobody tests, which is why the structural half below reads the
 * directories off DISK instead of trusting a second hand-written list.
 */
import { describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';
import fs from 'node:fs';
import path from 'node:path';

const editionMock = vi.hoisted(() => ({ IS_CE: false }));
vi.mock('@/lib/edition', () => editionMock);

// next-intl's ESM middleware build does not resolve under the vitest node
// environment, and no case here reaches it. Same shim as the sibling suite.
vi.mock('next-intl/middleware', () => ({
  default: () => () => undefined,
}));

import { LOCALE_REQUIRED_PREFIXES, proxy } from '@/proxy';

const LOCALE_DIR = path.resolve(__dirname, '..', 'app', '[locale]');

/**
 * Directories under `app/[locale]/` that are NOT prefixed routes:
 *  - `_landing` is a private folder (leading underscore, Next ignores it),
 *  - `[...notFound]` is the catch-all, which must never be listed.
 * The landing is `app/[locale]/page.tsx`, a file, so it never appears here, and
 * it is deliberately reachable without a locale.
 */
const NOT_A_PREFIXED_ROUTE = new Set(['_landing', '[...notFound]']);

function routeDirectoriesOnDisk(): string[] {
  return fs
    .readdirSync(LOCALE_DIR, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .filter((name) => !name.startsWith('_') && !NOT_A_PREFIXED_ROUTE.has(name))
    .sort();
}

/**
 * Resolves what the browser ACTUALLY ends up on, following one redirect hop.
 *
 * This has to follow the hop, and the first version of this test did not, which
 * made it pass on the broken proxy. A missing prefix does not produce a rewrite
 * to `_not-found` directly: it produces a 307 that strips the locale, and only
 * the request for THAT path is rewritten to `_not-found`. Reading just the
 * first response therefore sees no rewrite at all and looks healthy.
 */
function finalTarget(pathname: string): string | null {
  const response = proxy(new NextRequest(`https://livecontext.ai${pathname}`)) as Response;
  const rewrite = response?.headers.get('x-middleware-rewrite');
  if (rewrite) return rewrite;

  const location = response?.headers.get('location');
  if (location && response.status >= 300 && response.status < 400) {
    const hop = proxy(new NextRequest(new URL(location, 'https://livecontext.ai').toString())) as Response;
    return hop?.headers.get('x-middleware-rewrite') ?? `redirected:${location}`;
  }
  return null;
}

describe('the reset pages are reachable through the proxy', () => {
  it.each([
    ['/en/forgot-password', 'the link beside the password field on the login page'],
    ['/en/reset-password', 'the page the e-mailed link opens'],
    ['/en/reset-password?token=a-real-token', 'the e-mailed link, with its token'],
    ['/fr/forgot-password', 'a non-default locale'],
  ])('%s is not sent to _not-found (%s)', (pathname) => {
    expect(finalTarget(pathname)).not.toBe('https://livecontext.ai/_not-found');
  });

  it('the e-mailed link carries NO locale, and the redirect that adds one keeps the token', () => {
    // PasswordResetMailer builds `<frontendUrl>/reset-password?token=...` with no
    // locale, so this exact shape is what lands in someone's inbox. It has to
    // survive the hop that adds the locale: drop the query string there and every
    // link in every reset e-mail opens a form with no token.
    const response = proxy(
      new NextRequest('https://livecontext.ai/reset-password?token=a-real-token'),
    ) as Response;

    expect(response.status).toBe(307);
    const location = response.headers.get('location');
    expect(location).toContain('/en/reset-password');
    expect(location).toContain('token=a-real-token');
    expect(finalTarget('/reset-password?token=a-real-token'))
      .not.toBe('https://livecontext.ai/_not-found');
  });

  it('CONTROL: an unknown one-segment path still IS sent to _not-found', () => {
    // Without this, the assertions above would pass on a proxy that stopped
    // rewriting anything at all.
    expect(finalTarget('/ru')).toBe('https://livecontext.ai/_not-found');
    expect(finalTarget('/forgotten-password')).toBe('https://livecontext.ai/_not-found');
  });
});

describe('LOCALE_REQUIRED_PREFIXES covers every [locale] route', () => {
  it('reads the route directories, so the check below cannot be vacuous', () => {
    const found = routeDirectoriesOnDisk();
    expect(found.length).toBeGreaterThanOrEqual(8);
    expect(found).toContain('login');
    expect(found).toContain('forgot-password');
  });

  // Persona routes use as-needed locale routing; proxy.personas.test.ts covers
  // their dedicated intlMiddleware branch, including bare English URLs.
  it.each(routeDirectoriesOnDisk().filter((directory) => directory !== 'for'))('/%s keeps its locale prefix', (directory) => {
    expect(LOCALE_REQUIRED_PREFIXES).toContain(`/${directory}`);
  });

  it('lists nothing that no longer exists on disk', () => {
    const onDisk = new Set(routeDirectoriesOnDisk().map((directory) => `/${directory}`));
    expect(LOCALE_REQUIRED_PREFIXES.filter((prefix) => !onDisk.has(prefix))).toEqual([]);
  });
});

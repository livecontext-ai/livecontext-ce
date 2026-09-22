/**
 * Regression: the blog was removed (routes, content and assets deleted), but
 * deleting the routes is NOT enough to make its URLs disappear.
 *
 * `/blog` is a ONE-segment path, so with no route of its own it matches the
 * `[locale]` segment and Next serves the prerendered LANDING page with HTTP
 * 200. Measured on a production build: `/blog` answered 200 with the full
 * landing (~140 KB), exactly like any unknown path such as `/zzz`. That is a
 * soft 404: the crawler keeps the indexed URL and, worse, indexes a duplicate
 * of the landing under /blog.
 *
 * A tombstone page route does not fix it: the one written for this (an
 * optional catch-all calling `notFound()` with no other input) was itself
 * measured answering 200. That is that page's behaviour, not `notFound()`'s in
 * general - `/marketplace/<unknown>` calls it and does answer 404 - but it is
 * why the middleware owns the status here.
 *
 * What these tests CANNOT catch: `/_not-found` is Next's own route name. If a
 * Next upgrade renames it the rewrite lands nowhere and `/blog` goes back to a
 * 200 landing with every assertion below still green. The status itself is only
 * observable against a running build.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NextRequest } from 'next/server';
import { existsSync } from 'node:fs';
import path from 'node:path';

const editionMock = vi.hoisted(() => ({ IS_CE: false }));
vi.mock('@/lib/edition', () => editionMock);

// next-intl's ESM middleware build does not resolve under the vitest node
// environment; the branches under test return before reaching it (it only
// handles the bare landing root).
vi.mock('next-intl/middleware', () => ({
  default: () => () => undefined,
}));

import { proxy, config } from '@/proxy';
import { routing } from '@/i18n/routing';

function request(path: string): NextRequest {
  return new NextRequest(`https://livecontext.ai${path}`);
}

// A middleware rewrite carries the destination in this header, and the response
// takes that destination's status: `/_not-found` is Next's not-found route, so
// the visitor gets the app's 404 page with a real 404 and the URL untouched.
function rewriteTarget(response: Response | undefined): string | null {
  return response?.headers.get('x-middleware-rewrite') ?? null;
}

describe('proxy: the removed blog answers 404', () => {
  beforeEach(() => {
    editionMock.IS_CE = false;
  });

  it('sends the blog index to the not-found route', () => {
    expect(rewriteTarget(proxy(request('/blog')) as Response))
      .toBe('https://livecontext.ai/_not-found');
  });

  it('sends every article path there too', () => {
    for (const path of [
      '/blog/the-niche-data-advantage',
      '/blog/small-data-sharp-decisions', // a slug that used to 301 to the above
    ]) {
      expect(rewriteTarget(proxy(request(path)) as Response), path)
        .toBe('https://livecontext.ai/_not-found');
    }
  });

  it('covers the asset URLs, which an extension check used to wave through', () => {
    // These files existed (public/blog) and are deleted, so they are indexed
    // image URLs now answering from the app. They carry an extension, and both
    // the static short-circuit inside the middleware and `config.matcher` skip
    // dotted paths - so an earlier cut of this fix left exactly these URLs
    // answering 200 with an HTML body, the soft 404 it exists to remove.
    for (const path of [
      '/blog/the-niche-data-advantage.jpg',
      '/blog/ai-agent-audit-trail-run.png',
      '/blog/authors/camille-r.jpg',
      '/fr/blog/authors/camille-r.jpg',
    ]) {
      expect(rewriteTarget(proxy(request(path)) as Response), path)
        .toBe('https://livecontext.ai/_not-found');
    }
  });

  it('covers every locale prefix, not just the bare path', () => {
    // A locale-prefixed blog URL must 404 on its own. Handled by the middleware
    // it answers 404 directly; left to the generic locale-strip redirect below
    // it would answer 307 to a path that then answers 200 with the landing.
    for (const path of ['/en/blog', '/fr/blog', '/de/blog/ai-agent-audit-trail', '/zh/blog']) {
      expect(rewriteTarget(proxy(request(path)) as Response), path)
        .toBe('https://livecontext.ai/_not-found');
    }
  });

  it('does not swallow a path that merely starts with the same letters', () => {
    // `/blogger` is not the blog, and this pins that the blog branch does not
    // claim it. It is not a route either, so since the unknown-route branch was
    // added (see proxy.unknown-route.test.ts) it does end at the same 404. What
    // separates the two is the `noindex` header, which only the later branch
    // sets: with it present, `/blogger` was handled generically, not as a blog
    // URL. `/blog` itself must NOT carry it, or this assertion proves nothing.
    const blogger = proxy(request('/blogger')) as Response;
    expect(rewriteTarget(blogger)).toBe('https://livecontext.ai/_not-found');
    expect(blogger.headers.get('x-robots-tag')).toBe('noindex');
    expect((proxy(request('/blog')) as Response).headers.get('x-robots-tag')).toBeNull();
  });

  it('answers the same on a self-hosted build', () => {
    // The branch runs before any edition check, so CE must not keep a live blog
    // URL: nothing about the removal is cloud-specific. The landing assertion
    // is what proves the mocked edition actually reached the module under test
    // - without it this case would pass even if the mock did nothing.
    editionMock.IS_CE = true;
    expect((proxy(request('/')) as Response).headers.get('location'))
      .toBe('https://livecontext.ai/app/chat');
    expect(rewriteTarget(proxy(request('/blog')) as Response))
      .toBe('https://livecontext.ai/_not-found');
    expect(rewriteTarget(proxy(request('/blog/authors/camille-r.jpg')) as Response))
      .toBe('https://livecontext.ai/_not-found');
  });

  it('does not claim the name on the docs subdomain', () => {
    // There the whole path space belongs to the docs (`/agents` renders
    // `/docs/agents`), so `/blog` must stay routable as a docs page name.
    // The Host header is what the proxy reads; NextRequest does not derive one
    // from the URL, so it has to be set for this to be the docs host at all.
    const response = proxy(new NextRequest('https://docs.livecontext.ai/blog', {
      headers: { host: 'docs.livecontext.ai' },
    })) as Response;
    expect(rewriteTarget(response)).toBe('https://docs.livecontext.ai/docs/blog');
  });

  it('leaves the other public marketing surfaces alone', () => {
    for (const path of ['/about', '/changelog', '/compare/n8n-alternative', '/marketplace']) {
      expect(rewriteTarget(proxy(request(path)) as Response), path).toBeNull();
    }
  });
});

describe('proxy: the rewrite target', () => {
  it('names a route that only exists while app/not-found.tsx does', () => {
    // `/_not-found` is generated by Next FROM that file. Delete it and the
    // rewrite lands nowhere, silently taking /blog back to a 200 landing.
    expect(existsSync(path.resolve(__dirname, '..', 'app', 'not-found.tsx'))).toBe(true);
  });
});

describe('proxy matcher: the blog entries the catch-all cannot express', () => {
  it('lists the blog explicitly, since the catch-all skips any dotted path', () => {
    expect(config.matcher).toContain('/blog/:path*');
  });

  it('keeps those entries free of a dot exclusion, or the assets stop matching', () => {
    // The bug this closes was the catch-all's `(?!....*\..*)`: it skips every
    // path with an extension, which is every blog image. Narrowing the blog
    // entries the same way would restore it while the literal above still
    // passes, so pin the property, not just the spelling.
    for (const entry of config.matcher.filter((m) => m.includes('/blog'))) {
      expect(entry, entry).not.toContain('.');
    }
  });

  it('names every locale, so no translation silently keeps a live blog URL', () => {
    const entry = config.matcher.find((m) => m.includes('/blog/:path*') && m.includes(':locale'));
    expect(entry, 'no locale-prefixed blog matcher entry').toBeDefined();
    const alternation = /:locale\(([^)]+)\)/.exec(entry as string)?.[1].split('|') ?? [];
    expect([...alternation].sort()).toEqual([...routing.locales].sort());
  });
});

/**
 * Regression: an unknown ONE-segment path answered HTTP 200, not 404.
 *
 * `[locale]` is a dynamic segment, so Next hands it every one-segment path no
 * static route claims. `/ru` therefore rendered the LANDING route with
 * `locale='ru'`. The locale layout does call `notFound()` for an unsupported
 * locale, so the BODY was right - but the landing is SSG with `revalidate`
 * (`● /[locale]` in the build output), so that body was written into the
 * prerender cache and served with 200 from then on. Measured in production:
 * `/ru` answered `200`, `x-nextjs-prerender: 1`, `x-nextjs-cache: STALE`, and
 * carried the landing's own <title> because the page's metadata is generated
 * whether or not the layout bails.
 *
 * The cost was not theoretical. Search Console showed Googlebot walking the
 * language codes it knows (`/ru`, `/ro`, `/no`, `/nl`, `/ja`, `/da`, `/tr`,
 * `/it`, `/pl`, `/sv`, `/es`, `/en_GB`, `/ko`, `/fi`), collecting a 200 each
 * time, and filing them under "crawled, currently not indexed" or "excluded by
 * noindex": about a quarter of the domain's unindexed pages, against 153
 * indexed ones.
 *
 * DEPTH IS TWO DIFFERENT QUESTIONS, both measured on a production build rather
 * than reasoned about, because reasoning got it wrong twice here:
 *   - `/about/team`, `/models/x`: already a real 404. The known static segment
 *     resolves, nothing matches under it, the root not-found answers.
 *   - `/v1/weather` (a URL Search Console actually reported): NOT a 404. Its
 *     first segment is taken as the locale, so it renders
 *     `app/[locale]/[...notFound]/page.tsx`, a client component that draws a
 *     404 and never calls `notFound()`, so the status stays 200.
 * So the first segment decides at every depth, but against different lists:
 * an index PAGE at depth 1, a route FOLDER deeper.
 *
 * Getting the gateway prefixes wrong breaks live URLs. The `rewrites()` in
 * `next.config.mjs` serve `/share/*`, `/c/*`, `/form/*` and `/chat/*` from the
 * gateway and run AFTER middleware, so a rewrite here takes public share links,
 * public forms and the public chat offline. Measured on a production build at
 * one point in this change's history: all four answered 404, and the front end
 * renders that as "invalid or expired" rather than as a fault.
 *
 * What these tests CANNOT catch, the same blind spot the retired-blog suite
 * documents: `/_not-found` is Next's own route name, and the 404 STATUS is only
 * observable against a running build.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NextRequest } from 'next/server';
import { readdirSync, existsSync } from 'node:fs';
import path from 'node:path';

const editionMock = vi.hoisted(() => ({ IS_CE: false }));
vi.mock('@/lib/edition', () => editionMock);

// next-intl's ESM middleware build does not resolve under the vitest node
// environment. Only the bare landing root reaches it, and no case here does.
vi.mock('next-intl/middleware', () => ({
  default: () => () => undefined,
}));

import {
  proxy,
  PUBLIC_INDEX_SEGMENTS,
  PUBLIC_ROUTE_SEGMENTS,
  GATEWAY_REWRITE_SEGMENTS,
  config,
} from '@/proxy';
import { isServedFilePath } from '@/lib/seo/servedFiles';
import { routing } from '@/i18n/routing';
import nextConfig from '@/next.config.mjs';

const APP_DIR = path.resolve(__dirname, '..', 'app');

function request(pathname: string): NextRequest {
  return new NextRequest(`https://livecontext.ai${pathname}`);
}

function rewriteTarget(response: Response | undefined): string | null {
  return response?.headers.get('x-middleware-rewrite') ?? null;
}

function isNotFound(pathname: string): boolean {
  return rewriteTarget(proxy(request(pathname)) as Response)
    === 'https://livecontext.ai/_not-found';
}

describe('proxy: an unknown one-segment path answers 404 instead of the landing', () => {
  beforeEach(() => {
    editionMock.IS_CE = false;
  });

  it('404s the language codes Googlebot actually probed', () => {
    // The exact set Search Console reported, minus the six the app supports.
    for (const code of [
      'ru', 'ro', 'no', 'nl', 'ja', 'da', 'tr', 'it', 'pl', 'sv', 'ko', 'fi', 'en_GB',
    ]) {
      expect(isNotFound(`/${code}`), `/${code}`).toBe(true);
    }
  });

  it('404s any other one-segment path that is not an index page', () => {
    // `billing`, `workflows`, `f`, `s`, `u` and `w` ARE folders under app/, but
    // none has a page of its own, so the bare form was a soft 404 too. Listing
    // route FOLDERS rather than index PAGES is the mistake this pins.
    for (const pathname of [
      '/nawak', '/zzz', '/billing', '/workflows', '/f', '/s', '/u', '/w', '/legal',
    ]) {
      expect(isNotFound(pathname), pathname).toBe(true);
    }
  });

  it('marks the 404 noindex as well as answering 404', () => {
    const response = proxy(request('/ru')) as Response;
    expect(response.headers.get('x-robots-tag')).toBe('noindex');
  });

  it('answers the same on a self-hosted build', () => {
    // Nothing about an unknown path is cloud-specific, and CE is where the
    // blast radius of a mistake here is widest (it has no reverse proxy in
    // front, so it relies on every next.config rewrite). The landing assertion
    // proves the mocked edition actually reached the module under test.
    editionMock.IS_CE = true;
    expect((proxy(request('/')) as Response).headers.get('location'))
      .toBe('https://livecontext.ai/app/chat');
    expect(isNotFound('/ru')).toBe(true);
    expect(isNotFound('/share/tok')).toBe(false);
    expect(isNotFound('/form/tok/config')).toBe(false);
  });
});

describe('proxy: the branch must not swallow paths served by a rewrite', () => {
  beforeEach(() => {
    editionMock.IS_CE = false;
  });

  /**
   * `next.config.mjs` `rewrites()` returns a plain array, so these are
   * afterFiles rewrites: Next evaluates them AFTER middleware. A middleware
   * rewrite replaces the pathname, so the rewrite for the original path never
   * runs and the URL is dead. These are the real callers, all of which build
   * their URL relative to the page origin.
   */
  it('leaves the public share, conversation, form and chat paths alone', () => {
    for (const pathname of [
      '/share/tok',              // app/s/[token]/components/ShareResolver.tsx
      '/c/tok',                  // SharedConversation.tsx
      '/c/tok/messages',         // SharedConversation.tsx
      '/form/tok',               // app/f/[token]/components/PublicForm.tsx
      '/form/tok/config',        // PublicForm.tsx
      '/chat/tok/config',        // app/s/[token]/components/PublicChat.tsx
      '/chat/tok/message',       // PublicChat.tsx
      '/app/public/anything',    // already had its own early return
    ]) {
      expect(isNotFound(pathname), pathname).toBe(false);
    }
  });

  it('covers every rewrite declared in next.config.mjs', async () => {
    // Derived, not typed out: adding a rewrite whose path this branch swallows
    // would take that URL offline, and the symptom is a 404 that reads like an
    // expired token rather than a routing bug.
    const rewrites = await (nextConfig as { rewrites: () => Promise<Array<{ source: string }>> })
      .rewrites();
    expect(rewrites.length).toBeGreaterThan(0);

    for (const { source } of rewrites) {
      // A concrete URL the rule would serve.
      expect(isNotFound(source.replace(':path*', 'probe')), source).toBe(false);

      // `:path*` also matches ZERO segments, so the bare prefix is a live URL
      // for that rule and must survive too. An exact source has no bare form:
      // no rewrite serves `/api` on its own, and it is not a page either, so
      // the branch is right to 404 it (see the matcher case below).
      if (source.endsWith(':path*')) {
        const bare = source.replace('/:path*', '');
        expect(isNotFound(bare), bare).toBe(false);
      }
    }
  });
});

describe('proxy: everything that must keep working', () => {
  beforeEach(() => {
    editionMock.IS_CE = false;
  });

  it('leaves every real public route untouched', () => {
    for (const pathname of [
      '/about', '/changelog', '/compare', '/compare/n8n-alternative', '/contact',
      '/docs', '/docs/workflows', '/integrations', '/integrations/slack',
      '/legal/terms', '/local-mcp', '/marketplace', '/marketplace/reconcile',
      '/models', '/redeem', '/status', '/f/tok', '/s/tok', '/u/someone',
      '/w/embed/tok', '/workflows/builder', '/billing/success',
    ]) {
      expect(isNotFound(pathname), pathname).toBe(false);
    }
  });

  it('leaves a deep path under a KNOWN segment alone, since Next 404s it itself', () => {
    // Measured on a production build: these already answer 404 without this
    // branch. Claiming them here would gain nothing.
    for (const pathname of ['/about/team', '/models/x', '/status/x', '/marketplace/zzz']) {
      expect(isNotFound(pathname), pathname).toBe(false);
    }
  });

  it('404s a deep path under an UNKNOWN segment, which Next does not', () => {
    // `/v1/weather` is from the Search Console report. Its first segment is
    // taken as the locale, so it lands on the client-component catch-all that
    // draws a 404 body and leaves the status at 200. Measured: 200 before.
    for (const pathname of ['/v1/weather', '/nawak/deeper', '/ru/anything']) {
      expect(isNotFound(pathname), pathname).toBe(true);
    }
  });

  it('leaves the landing alone, bare and under every supported locale', () => {
    expect(isNotFound('/')).toBe(false);
    for (const locale of routing.locales) {
      expect(isNotFound(`/${locale}`), `/${locale}`).toBe(false);
    }
  });

  it('leaves the authenticated app alone', () => {
    // These live under `[locale]`, so they are reached with the prefix already
    // added. A 404 here would sign every logged-in user out of their own app.
    for (const pathname of [
      '/en/app/chat', '/fr/app/settings/overview', '/en/login', '/en/register',
      '/en/onboarding', '/en/ce-setup', '/en/invitations/accept', '/en/auth/callback',
    ]) {
      expect(isNotFound(pathname), pathname).toBe(false);
    }
  });

  it('sends a locale-prefixed unknown path to the bare path first, then 404s it', () => {
    // `/fr/ru` is stripped to `/ru` by the branch above this one, and the
    // redirect target is what then answers 404. Two hops, and pinned so nobody
    // reads the 307 as "handled".
    const response = proxy(request('/fr/ru')) as Response;
    expect(response.headers.get('location')).toBe('https://livecontext.ai/ru');
    expect(isNotFound('/ru')).toBe(true);
  });

  it('does not claim the whole path space of the docs subdomain', () => {
    // There every clean path is a docs page name, resolved before this branch.
    const response = proxy(new NextRequest('https://docs.livecontext.ai/nawak', {
      headers: { host: 'docs.livecontext.ai' },
    })) as Response;
    expect(rewriteTarget(response)).toBe('https://docs.livecontext.ai/docs/nawak');
  });

  it('does not touch the API proxy or static assets', () => {
    for (const pathname of ['/api/proxy/workflows', '/_next/static/chunk.js', '/og-image.jpg']) {
      expect(isNotFound(pathname), pathname).toBe(false);
    }
  });

  it('404s the bare /api and /icons the matcher used to hide', () => {
    // The branch can only act on what `config.matcher` lets through, and the
    // exclusions are PREFIX matches: `api` also excluded `/api` and `/apiary`,
    // which are one-segment paths and so answered 200 with the landing. `/api`
    // is crawlable, because robots.txt disallows `/api/` with the slash.
    for (const pathname of ['/api', '/icons', '/apiary']) {
      expect(isNotFound(pathname), pathname).toBe(true);
    }
  });
});

describe('proxy: the matcher lets the branch see what it must', () => {
  /**
   * `config.matcher` is applied by Next, not by this module, so the branch
   * above is unreachable for anything the matcher excludes. These pin the
   * exclusions as REGEX text, which is the only form available in-process.
   */
  const catchAll = (config.matcher as string[]).find((entry) => entry.includes('?!')) as string;

  it('excludes the asset prefixes only WITH their slash', () => {
    expect(catchAll).toContain('api/');
    expect(catchAll).toContain('icons/');
    // The slashless forms are what hid `/api` and `/icons` themselves.
    expect(catchAll).not.toMatch(/\?!api\|/);
    expect(catchAll).not.toMatch(/\|icons\|/);
  });

  it('still excludes the paths that are a real file on every request', () => {
    const source = catchAll.slice('/('.length, -')'.length);
    const matcher = new RegExp(`^/(${source})$`);
    for (const pathname of ['/api/workflows', '/icons/mark.svg', '/_next/static/x', '/_next/image']) {
      expect(matcher.test(pathname), pathname).toBe(false);
    }
    for (const pathname of ['/api', '/icons', '/apiary', '/ru', '/nawak']) {
      expect(matcher.test(pathname), pathname).toBe(true);
    }
  });

  it('now lets DOTTED paths through, which is what the soft-404 fix depends on', () => {
    // The matcher used to exclude every path containing a dot, so
    // `/indexnow.txt` and `/wp-login.php` never reached the middleware and
    // answered 200 with the landing. Deciding them needs the list of files this
    // site serves, so they have to arrive first.
    const source = catchAll.slice('/('.length, -')'.length);
    const matcher = new RegExp(`^/(${source})$`);
    for (const pathname of ['/indexnow.txt', '/wp-login.php', '/og-image.jpg', '/sitemap.xml']) {
      expect(matcher.test(pathname), pathname).toBe(true);
    }
  });
});

describe('proxy: a dotted path is a file we serve, or a 404', () => {
  beforeEach(() => {
    editionMock.IS_CE = false;
  });

  function isPassedThrough(pathname: string): boolean {
    const response = proxy(request(pathname)) as Response;
    return rewriteTarget(response) === null && response?.headers.get('x-middleware-next') === '1';
  }

  it('404s an invented file, which used to answer 200 with the landing page', () => {
    // Measured in production before this branch existed: each of these returned
    // HTTP 200 with an HTML body, which a search console files as Soft 404 and
    // which made "the verification file is installed" unanswerable.
    for (const pathname of [
      '/indexnow.txt', '/BingSiteAuth.xml', '/sitemap_index.xml', '/wp-login.php', '/index.php',
    ]) {
      expect(isNotFound(pathname), pathname).toBe(true);
    }
  });

  it('serves the files that exist, so nothing visible breaks', () => {
    for (const pathname of [
      '/og-image.jpg', '/llms.txt', '/liveContext-logo.svg',
      '/landing/screenshots/builder.png', '/videos/automate-client-invoicing.webp',
    ]) {
      expect(isPassedThrough(pathname), pathname).toBe(true);
    }
  });

  it('serves the metadata routes, apex and per section', () => {
    // `/videos/sitemap.xml` is the trap: a rule anchored at the root would 404
    // the section sitemaps, and robots.txt advertises them.
    for (const pathname of [
      '/robots.txt', '/sitemap.xml',
      '/videos/sitemap.xml', '/marketplace/sitemap.xml', '/integrations/sitemap.xml',
    ]) {
      expect(isPassedThrough(pathname), pathname).toBe(true);
    }
  });

  it('still 404s the retired blog assets, which the dotted branch must not rescue', () => {
    // The blog branch runs ABOVE the dotted one on purpose. If the order ever
    // flipped, `/blog/<post>.jpg` would fall to the catch-all again.
    expect(isNotFound('/blog/small-data-sharp-decisions.jpg')).toBe(true);
    expect(isNotFound('/blog/authors/someone.jpg')).toBe(true);
  });

  it('does NOT mistake a dot inside a token or a slug for a file extension', () => {
    // The hazard this file's header records: the `/share`, `/chat`, `/c` and
    // `/form` rewrites run AFTER middleware, so a branch that answers first
    // takes public share links, public forms and the public chat offline. Every
    // one of these is a page, not a file, and none of them is in `public/`.
    for (const pathname of [
      '/share/abc.def', '/chat/room.1', '/c/conv.id', '/form/x.y',
      '/s/tok.en', '/f/tok.en', '/w/embed/tok.en',
      '/marketplace/some.app', '/integrations/some.api', '/videos/some.film',
      '/app/public/tok/render.html',
    ]) {
      expect(isNotFound(pathname), pathname).toBe(false);
      expect(isPassedThrough(pathname), pathname).toBe(true);
    }
  });

  it('still 404s a dotted path whose first segment names nothing', () => {
    // The other half of the rule above: deferring to `isKnownRoute` must not
    // become "any path with a dot is fine".
    for (const pathname of ['/wp-content/sitemap.xml', '/wp-admin/robots.txt', '/nawak/x.png']) {
      expect(isNotFound(pathname), pathname).toBe(true);
    }
  });

  it('lets a dotted path through on the LOCALE-required tree, which neither segment list covers', () => {
    // Regression: `isKnownRoute` only knows the non-`[locale]` tree, so asking
    // it alone 404'd every dotted path under /app, /auth, /login, /onboarding
    // and the rest - bare and locale-prefixed. `/app/u/<handle>` is a real
    // linked route and `lib/marketplace/publicProfiles.ts` documents the handle
    // charset as allowing a dot.
    //
    // What this asserts is precisely "the middleware does not 404 it", which is
    // what this change is responsible for. It does NOT assert the page renders:
    // the BARE `/app/u/j.doe` was already a soft 404 before any of this (its
    // dotless twin gets a locale-strip redirect that a dotted path never had),
    // and fixing that is a different change.
    for (const pathname of [
      '/app/u/j.doe', '/en/app/u/j.doe', '/fr/app/u/j.doe',
      '/en/app/settings/mcp/some.api', '/en/app/tables/1/a.b',
      '/en/reset-password/a.b', '/en/auth/callback/x.y', '/en/invitations/accept/a.b',
    ]) {
      expect(isNotFound(pathname), pathname).toBe(false);
    }
  });

  it('still 404s a dotted path whose first segment is on NEITHER tree', () => {
    // The other half: deferring to two lists must not become "anything goes".
    for (const pathname of ['/appfoo/x.y', '/en/appfoo/x.y', '/logins/x.y', '/en/nawak/x.y']) {
      expect(isNotFound(pathname), pathname).toBe(true);
    }
  });

  it('strips the locale before deciding, so an invented name cannot hide behind one', () => {
    // Measured on a production build: `/en/marketplace/wp-login.php` answered
    // 200 with the landing page, because the first segment after the locale is
    // a real route and the branch stopped there. Its dotless twin was already
    // fixed, by the locale-strip redirect this now applies too.
    for (const pathname of ['/en/marketplace/wp-login.php', '/fr/docs/wp-login.php', '/de/videos/x.php']) {
      const response = proxy(request(pathname)) as Response;
      const location = response?.headers.get('location') ?? '';
      expect(location, pathname).toBe(`https://livecontext.ai${pathname.slice(3)}`);
    }
  });

  it('does NOT redirect a dotted path that needs its locale, nor one that names nothing', () => {
    // The redirect is only for a real route wearing a locale it does not need.
    // A locale-required path must keep its prefix, and an invented first
    // segment must 404 outright rather than be redirected to another 404.
    expect((proxy(request('/en/app/u/j.doe')) as Response)?.headers.get('location')).toBeNull();
    expect((proxy(request('/en/nawak/x.y')) as Response)?.headers.get('location')).toBeNull();
    expect(isNotFound('/en/nawak/x.y')).toBe(true);
  });

  it('marks the dotted 404 noindex, which is what removes it from the index fastest', () => {
    // The 404 status does the work eventually; the header covers the window
    // before a crawler recrawls. The existing noindex test uses `/ru`, which
    // takes the other branch, so this one could be dropped unnoticed.
    const response = proxy(request('/wp-login.php')) as Response;
    expect(response.headers.get('x-middleware-rewrite')).toBe('https://livecontext.ai/_not-found');
    expect(response.headers.get('X-Robots-Tag')).toBe('noindex');
  });

  it('gives the framework prefixes their separator, in THIS file and not only in the module', () => {
    // Measured on a production build: `/__nextjsfoo.php` answered 200 while the
    // module-level test for the same string passed, because the short-circuit
    // above runs first and had its own copy of the prefix without the `_`.
    // A guard duplicated in two places has to be asserted in both.
    expect(isNotFound('/__nextjsfoo.php')).toBe(true);
    expect(isNotFound('/_nextfoo.js')).toBe(true);
    // Both directions: without a positive case the whole `__nextjs_` clause
    // could be deleted with every test still green.
    expect(isPassedThrough('/__nextjs_original-stack-frame')).toBe(true);
    expect(isPassedThrough('/_next/static/chunks/main.js')).toBe(true);
  });

  it('lets the middleware see a name that merely STARTS like an excluded file', () => {
    // `favicon.ico` sat in the matcher as an unanchored prefix, so
    // `/favicon.ico.php` never reached the middleware and answered 200. The
    // entry was also redundant: the file is named in PUBLIC_ROOT_FILES.
    const source = (config.matcher as string[]).find((entry) => entry.includes('?!')) as string;
    const matcher = new RegExp(`^/(${source.slice('/('.length, -')'.length)})$`);
    expect(matcher.test('/favicon.ico.php')).toBe(true);
    expect(isNotFound('/favicon.ico.php')).toBe(true);
    expect(isPassedThrough('/favicon.ico')).toBe(true);
  });
});

describe('proxy: the allow list matches the index pages on disk', () => {
  /**
   * The allow list is what makes the branch possible, and it is the one thing
   * about it that can rot: add an index page, forget this list, and the new
   * page answers 404 in production while every other test stays green.
   * Deriving the truth from `app/` turns that into a CI failure.
   *
   * A folder WITHOUT its own `page.tsx` must NOT be listed: `/billing` and
   * `/workflows` are folders, not pages, and listing them is what left them
   * answering 200 with the landing.
   */
  // `[locale]` is the dynamic segment this branch exists to fence off, and
  // `api` is served by an earlier branch that never reaches either list.
  const NOT_A_PUBLIC_SEGMENT = ['[locale]', 'api', '__tests__', 'node_modules'];

  function topLevelFolders(): string[] {
    return readdirSync(APP_DIR, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      // A folder whose NAME carries a dot is a file route (`robots.txt`), and a
      // dotted path never reaches `isKnownRoute`: the branch above decides it
      // against `lib/seo/servedFiles.ts`. Asserting it into these lists would
      // pin it to a guard that cannot affect it. The test below covers it
      // against the guard that can.
      .filter((name) => !name.includes('.'))
      .filter((name) => !NOT_A_PUBLIC_SEGMENT.includes(name));
  }

  /** The dotted route folders, which the lists above deliberately skip. */
  function dottedRouteFoldersOnDisk(): string[] {
    return readdirSync(APP_DIR, { withFileTypes: true })
      .filter((entry) => entry.isDirectory() && entry.name.includes('.'))
      .map((entry) => entry.name);
  }

  /**
   * An INDEX segment is a folder that answers its own bare URL. Both `page.*`
   * and `route.*` do that, and both must count: accepting only `page.*` here
   * while `isRouteFolder` below accepts either would let a future top-level
   * `route.ts` land in the route list and not the index list, so its bare URL
   * would 404 with both parity tests still green.
   */
  function servesItsOwnUrl(dir: string): boolean {
    return ['page', 'route']
      .some((base) => ['tsx', 'ts', 'jsx', 'js']
        .some((ext) => existsSync(path.join(dir, `${base}.${ext}`))));
  }

  function isRouteFolder(dir: string): boolean {
    return readdirSync(dir, { withFileTypes: true }).some((entry) => {
      if (entry.isFile()) return /^(page|route)\.(t|j)sx?$/.test(entry.name);
      if (NOT_A_PUBLIC_SEGMENT.includes(entry.name)) return false;
      return isRouteFolder(path.join(dir, entry.name));
    });
  }

  function indexPageFoldersOnDisk(): string[] {
    return topLevelFolders()
      .filter((name) => servesItsOwnUrl(path.join(APP_DIR, name)))
      .sort();
  }

  function routeFoldersOnDisk(): string[] {
    return topLevelFolders()
      .filter((name) => isRouteFolder(path.join(APP_DIR, name)))
      .sort();
  }

  it('names every index page in app/, and nothing that is not one', () => {
    expect([...PUBLIC_INDEX_SEGMENTS].sort()).toEqual(indexPageFoldersOnDisk());
  });

  it('names every route folder in app/, and nothing that is not one', () => {
    expect([...PUBLIC_ROUTE_SEGMENTS].sort()).toEqual(routeFoldersOnDisk());
  });

  it('declares every DOTTED route folder to the served-files guard instead', () => {
    // `app/robots.txt/route.ts` answers a path the segment lists can never see.
    // Adding another one and forgetting `servedFiles.ts` would ship a route
    // that 404s before it renders, with every other test still green.
    for (const name of dottedRouteFoldersOnDisk()) {
      expect(isServedFilePath(`/${name}`), `/${name} is a route but would 404`).toBe(true);
    }
  });

  it('keeps the index list a subset of the route list', () => {
    // A page is always a route. If this ever fails one of the two derivations
    // above is wrong, and the branch would answer differently per depth for
    // the same segment.
    for (const segment of PUBLIC_INDEX_SEGMENTS) {
      expect(PUBLIC_ROUTE_SEGMENTS, segment).toContain(segment);
    }
  });

  it('keeps the gateway prefixes disjoint from the app routes', () => {
    // If a folder ever takes one of these names the rewrite would be shadowed
    // by a real page, and this list would be hiding that rather than the
    // reverse.
    for (const segment of GATEWAY_REWRITE_SEGMENTS) {
      expect(routeFoldersOnDisk(), segment).not.toContain(segment);
    }
  });

  it('rewrites to a route that only exists while app/not-found.tsx does', () => {
    // `/_not-found` is generated by Next FROM that file. Delete it and the
    // rewrite lands nowhere, silently taking every unknown path back to a 200.
    expect(existsSync(path.join(APP_DIR, 'not-found.tsx'))).toBe(true);
  });
});

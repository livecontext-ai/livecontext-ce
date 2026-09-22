import createMiddleware from 'next-intl/middleware';
import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';
import { routing } from './i18n/routing';
import { IS_CE } from './lib/edition';
import { isDocsHost, resolveDocsRoute } from './lib/docs/docsHostRewrite';
import { isJwtShapedToken } from './lib/utils/jwtShape';
import { isServedFilePath } from './lib/seo/servedFiles';

const intlMiddleware = createMiddleware(routing);
const GATEWAY_URL = process.env.NEXT_PUBLIC_SPRING_BASE_URL || 'http://localhost:8080';
const API_PROXY_CORS_HEADERS = {
  'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS, PATCH',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Requested-With, X-Active-Organization-ID',
  'Access-Control-Allow-Credentials': 'true',
} as const;

/**
 * Paths whose pages live under `/[locale]/...` only. They must keep the locale
 * prefix; otherwise routes like `/invitations/accept?token=...` cannot resolve
 * because the page exists under `[locale]`.
 *
 * MISSING AN ENTRY IS A HARD 404, not a degraded page: the locale is stripped by
 * a 307, the one-segment path matches no static route and is not in
 * PUBLIC_INDEX_SEGMENTS, so isKnownRoute rejects it. Adding a directory under
 * `app/[locale]/` without adding it here therefore ships a route nobody can
 * reach. `proxy.localeRequiredPrefixes.test.ts` derives the real list from disk
 * and fails on exactly that omission, because it is invisible to every test
 * that hand-lists the routes it checks.
 */
export const LOCALE_REQUIRED_PREFIXES = [
  '/app',
  '/auth',
  '/onboarding',
  '/login',
  '/register',
  '/ce-setup',
  '/invitations',
  '/forgot-password',
  '/reset-password',
] as const;

function requiresLocale(path: string): boolean {
  return LOCALE_REQUIRED_PREFIXES.some(
    (prefix) => path === prefix || path.startsWith(prefix + '/'),
  );
}

/**
 * Every ONE-segment path that is a real page, outside the `[locale]` tree: the
 * folders under `app/` that have a `page.tsx` of their own.
 *
 * `[locale]` is a DYNAMIC segment, so Next hands it any one-segment path no
 * static route claims: `/ru` renders the landing route with `locale='ru'`. The
 * locale layout does call `notFound()`, so the BODY is right, but the landing
 * is SSG with `revalidate` (`● /[locale]` in the build output), so that 404
 * body is written into the prerender cache and served with HTTP 200 ever after
 * (measured in production: `x-nextjs-prerender: 1`, `x-nextjs-cache: STALE`,
 * and the landing's own <title>, because the page's metadata is generated
 * whether or not the layout bails).
 *
 * The cost was not theoretical: Search Console showed Googlebot walking the
 * language codes it knows (`/ru`, `/ja`, `/da`, `/pl`, `/sv`, `/tr`, `/it`,
 * `/nl`, `/no`, `/ko`, `/fi`, `/es`, `/en_GB`), collecting a 200 each time and
 * filing them under "crawled, currently not indexed": about a quarter of the
 * domain's unindexed pages, against 153 indexed ones.
 *
 * DEPTH IS TWO DIFFERENT QUESTIONS, and both had to be measured on a real
 * build rather than reasoned about:
 *
 *  - `/<known>/<unknown>` (`/about/team`, `/models/x`) already answers a real
 *    404. Next resolves the known static segment, finds nothing under it, and
 *    falls to the root not-found, which is dynamic.
 *  - `/<unknown>/<more>` (`/v1/weather`, one of the URLs Search Console
 *    reported) does NOT. The first segment is taken as the locale, so it
 *    renders `app/[locale]/[...notFound]/page.tsx` - a plain client component
 *    that DRAWS a 404 and never calls `notFound()`, so the status stays 200.
 *
 * So the first segment decides at every depth, and the two lists differ: at
 * depth 1 the segment must name an index PAGE, deeper it need only name a
 * route FOLDER (`/billing` is not a page, `/billing/success` is).
 *
 * GATEWAY_REWRITE_SEGMENTS is not optional. The `rewrites()` in
 * `next.config.mjs` serve `/share/*`, `/chat/*`, `/c/*` and `/form/*` from the
 * gateway and run AFTER this middleware, so a rewrite here replaces the
 * pathname and they never reach their own rewrite. Leaving them out took
 * public share links, public forms and the public chat to 404 on a production
 * build, with the front end reporting "invalid or expired" rather than a
 * routing fault. That is the same hazard the `/app/public/` early return below
 * already exists for.
 *
 * Rewriting here rather than fixing the page is deliberate, and matches the
 * retired-blog branch above: a route that calls `notFound()` answers 404 only
 * when it renders dynamically, which the landing must not do (it is the most
 * requested page on the domain and is served from the CDN). The middleware
 * runs before the prerender cache is consulted, so it is the one place that
 * can turn these into a real 404 without making the landing dynamic.
 *
 * A DENY list is impossible (the bad set is unbounded), so this is an allow
 * list, and an allow list rots: a new index page nobody adds here would answer
 * 404. `__tests__/proxy.unknown-route.test.ts` derives both this list and
 * GATEWAY_REWRITE_SEGMENTS from disk and fails when they drift, so the rot is
 * a CI failure rather than a page that silently disappears.
 */
export const PUBLIC_INDEX_SEGMENTS = [
  'about',
  'changelog',
  'compare',
  'contact',
  'docs',
  'integrations',
  'local-mcp',
  'marketplace',
  'models',
  'redeem',
  'status',
  'videos',
] as const;

/**
 * Every route folder under `app/` outside `[locale]`, index page or not. A
 * superset of PUBLIC_INDEX_SEGMENTS: these names are real at depth 2 and
 * deeper (`/billing/success`, `/s/<token>`, `/workflows/builder`) even when
 * the bare form is not a page. `api` is absent because the branch above
 * answers every `/api/` path before this one.
 */
export const PUBLIC_ROUTE_SEGMENTS = [
  ...PUBLIC_INDEX_SEGMENTS,
  'billing',
  'f',
  'legal',
  's',
  'u',
  'w',
  'workflows',
] as const;

/**
 * First segments of the `rewrites()` in `next.config.mjs`, which serve public
 * gateway paths from the page origin. Their `:path*` also matches ZERO
 * segments, so the bare `/share` form has to survive this branch too.
 */
export const GATEWAY_REWRITE_SEGMENTS = ['share', 'chat', 'c', 'form'] as const;

function isKnownRoute(pathWithoutLocale: string): boolean {
  if (pathWithoutLocale === '/') return true;

  const segments = pathWithoutLocale.split('/').filter(Boolean);
  const first = segments[0] ?? '';

  // Served by a next.config rewrite at every depth, bare form included.
  if ((GATEWAY_REWRITE_SEGMENTS as readonly string[]).includes(first)) return true;

  const known = segments.length === 1 ? PUBLIC_INDEX_SEGMENTS : PUBLIC_ROUTE_SEGMENTS;
  return (known as readonly string[]).includes(first);
}

function getPathLocale(pathname: string): string | null {
  return routing.locales.find(
    (locale) => pathname === `/${locale}` || pathname.startsWith(`/${locale}/`),
  ) ?? null;
}

/**
 * Paths of the removed blog: the index, every article, and `/blog/...` assets
 * (the article images and author avatars that lived in `public/blog`). Strips
 * the locale prefix itself, so `/fr/blog` and `/de/blog/<slug>` are covered.
 */
function isRetiredBlogPath(pathname: string): boolean {
  const locale = getPathLocale(pathname);
  const path = locale ? pathname.slice(locale.length + 1) || '/' : pathname;
  return path === '/blog' || path.startsWith('/blog/');
}

function isApiProxyPath(pathname: string): boolean {
  return pathname === '/api/proxy' || pathname.startsWith('/api/proxy/');
}

function getApiProxyCorsHeaders(request: NextRequest): Headers {
  const headers = new Headers(API_PROXY_CORS_HEADERS);
  const origin = request.headers.get('origin');

  if (origin) {
    headers.set('Access-Control-Allow-Origin', origin);
    headers.set('Vary', 'Origin');
  } else {
    headers.set('Access-Control-Allow-Origin', '*');
  }

  return headers;
}

function withApiProxyCors(request: NextRequest, response: NextResponse): NextResponse {
  getApiProxyCorsHeaders(request).forEach((value, key) => {
    response.headers.set(key, value);
  });
  return response;
}

/**
 * True for React Server Component (flight) and router-prefetch requests.
 * Next.js differentiates these from document requests by HEADERS only (see
 * the `Vary: rsc, next-router-...` it emits), and they share the page URL.
 */
function isRscRequest(request: NextRequest): boolean {
  return Boolean(
    request.headers.get('rsc')
    || request.headers.get('next-router-prefetch')
    || request.headers.get('next-router-segment-prefetch'),
  );
}

export function proxy(request: NextRequest) {
  const response = routeRequest(request);

  // Flight/prefetch responses must NEVER be stored by shared caches (see the
  // headers() block in next.config.mjs - the PRIMARY guard, since middleware
  // cannot override the Cache-Control of a prerendered response). This
  // covers what next.config cannot: responses the middleware itself issues
  // for RSC-headered requests (redirects, API-proxy rewrites).
  if (response && isRscRequest(request)) {
    response.headers.set('cache-control', 'private, no-store');
  }

  return response;
}

function routeRequest(request: NextRequest) {
  const { pathname } = request.nextUrl;

  if (isApiProxyPath(pathname) && request.method === 'OPTIONS') {
    return new NextResponse(null, {
      status: 204,
      headers: getApiProxyCorsHeaders(request),
    });
  }

  // The ONLY `/api/proxy/*` path that reaches a route handler. Everything else is rewritten
  // below, so `app/api/proxy/[...path]/route.ts` serves this path and nothing else: this
  // middleware IS the API proxy. Fix proxy behaviour here, not there (see that file's header).
  if (pathname === '/api/proxy/external-proxy') {
    return NextResponse.next();
  }

  if (isApiProxyPath(pathname)) {
    const proxiedPath = pathname.replace(/^\/api\/proxy\/?/, '');
    const targetUrl = new URL(`/api/${proxiedPath}`, GATEWAY_URL);
    targetUrl.search = request.nextUrl.search;

    const headers = new Headers(request.headers);
    const authHeader = headers.get('authorization');
    // Only promote a `token` query param to a bearer when it is a JWT access token
    // (the <img>/window.open fallback). An opaque/UUID RESOURCE token must reach the
    // gateway in the query: the unauthenticated invitation-accept lookup, email verify
    // and password reset all read it from ?token=. (Stripping it here is what made
    // /organizations/invitations/info?token= always return valid:false.)
    if (!authHeader && targetUrl.searchParams.has('token') && isJwtShapedToken(targetUrl.searchParams.get('token'))) {
      const token = targetUrl.searchParams.get('token');
      if (token) {
        headers.set('authorization', `Bearer ${token}`);
      }
      targetUrl.searchParams.delete('token');
    }

    return withApiProxyCors(request, NextResponse.rewrite(targetUrl, {
      request: {
        headers,
      },
    }));
  }

  if (pathname.startsWith('/api/')) {
    return NextResponse.next();
  }

  // The blog was removed (routes, content and assets deleted). Its URLs are
  // indexed and shared, so they must answer a real 404 instead of falling
  // through to the app's routes: `/blog` is a ONE-segment path, so it matches
  // the `[locale]` segment and gets served the prerendered LANDING page with
  // HTTP 200, while a deeper path renders the catch-all 404 body, also with a
  // 200. Both are soft 404s: the crawler keeps the URL indexed, and `/blog`
  // would be indexed as a duplicate of the landing. Rewriting to Next's
  // not-found route answers the app's 404 page with a real 404 status and
  // leaves the requested URL in the address bar.
  //
  // A tombstone page route was tried first and dropped. The one that was
  // written - an optional catch-all at `app/blog/[[...slug]]` whose render
  // called `notFound()` with no other input - was measured answering 200 on a
  // production build. That is a property of THAT page, not of `notFound()` in
  // general: routes that call it after awaiting their data
  // (`/marketplace/<unknown>`) do answer 404. Getting a page route to work
  // would have meant finding a shape that does set the status AND duplicating
  // it under `[locale]` for the six locale prefixes; one middleware branch
  // covers every one of those paths, assets included.
  //
  // ABOVE the static short-circuit below on purpose. The blog's images live at
  // `/blog/<post>.jpg` and `/blog/authors/<author>.jpg`; every one carries an
  // extension, so ordering this after the `pathname.includes('.')` check would
  // wave exactly those URLs through to the same soft 404 this exists to remove
  // (measured: they answered 200 with an HTML body). `config.matcher` lists
  // them explicitly for the same reason: its catch-all excludes dotted paths.
  // Not on the docs subdomain: there the whole path space is the docs' own
  // (`docs.livecontext.ai/agents` renders `/docs/agents`), so claiming `/blog`
  // site-wide would quietly reserve that name from a docs page that may exist
  // one day. The docs router below runs too late to make that call itself: this
  // branch has to sit above the static short-circuit, and it does not.
  if (!isDocsHost(request.headers.get('host')) && isRetiredBlogPath(pathname)) {
    return NextResponse.rewrite(new URL('/_not-found', request.url));
  }

  // The framework's own endpoints. `/_next/` is already excluded by the
  // matcher, so what this is really here for is `/__nextjs_*`, which carries no
  // dot and would therefore never reach the dotted branch below. Both prefixes
  // need their separator: without it `/__nextjsfoo.php` passes, which is a 200
  // soft 404 the module-level test cannot see, because this check shadows it.
  if (pathname.startsWith('/_next/') || pathname.startsWith('/__nextjs_')) {
    return NextResponse.next();
  }

  // `/app/public/*` renders a public share/form surface from under the app
  // tree, whose first segment is deliberately absent from both route lists.
  // ABOVE the dotted branch, because a token there can carry a dot
  // (`/app/public/<id>/render.html`).
  if (pathname.startsWith('/app/public/')) {
    return NextResponse.next();
  }

  // A dotted path is either something this site serves or a name nobody
  // published. It used to be waved through unconditionally, which left the
  // whole invented class answering 200 with the landing page: `/indexnow.txt`,
  // `/sitemap_index.xml`, `/wp-login.php`. Same soft 404 the unknown-route
  // branch below exists to remove, and the reason a verification file could not
  // be told from a missing one.
  //
  // THREE questions, and all three must be asked. `isServedFilePath` answers
  // "is this a file we serve" (`lib/seo/servedFiles.ts`). `isKnownRoute` and
  // `requiresLocale` between them answer "does the first segment name something
  // that renders", across BOTH route trees, which is what keeps a dot INSIDE A
  // TOKEN or a slug from being mistaken for a file extension.
  //
  // `requiresLocale` is not optional, and leaving it out was measured: the two
  // segment lists cover only the non-`[locale]` tree, so without it every
  // dotted path under `/app`, `/auth`, `/login`, `/onboarding` and the rest
  // answers 404 - `/en/app/u/j.doe` among them, and `publicProfiles.ts`
  // documents the handle charset as allowing a dot. Getting this class wrong is
  // the hazard the note on GATEWAY_REWRITE_SEGMENTS records as having already
  // taken public share links, public forms and the public chat offline once.
  //
  // Residual, knowingly: a MISSING file under a real asset directory
  // (`/videos/typo.webp`) still answers 200 from the catch-all. Closing that
  // needs a filesystem check, which middleware cannot do; a crawler reaches
  // those paths only through a broken `<img>`, never on its own. The asset
  // directories that are NOT route names are excluded in `config.matcher`, so
  // they never pay for this branch at all.
  // A known route returns `next()` rather than falling through to the branches
  // below, which is what EVERY dotted path used to do. Keeping that identical
  // is deliberate: this change is meant to move the invented names from 200 to
  // 404 and nothing else, and falling through would newly subject a dotted path
  // to the locale-strip redirect and the `/chat/c/` rewrite. Those would
  // arguably be more consistent, and they are not this change's to make.
  if (pathname.includes('.')) {
    const dottedLocale = getPathLocale(pathname);
    const dottedPath = dottedLocale ? pathname.slice(dottedLocale.length + 1) || '/' : pathname;

    // A locale prefix in front of a REAL route is stripped here, which is the
    // same 307 the branch far below gives the dotless twin. Without it,
    // `/en/marketplace/wp-login.php` answered `next()` and rendered the
    // prerendered landing with a 404 body and a 200 status: an invented name
    // wearing a locale prefix walked straight through the guard.
    // A path whose first segment names nothing is still 404'd outright below,
    // rather than redirected to another 404.
    if (dottedLocale && !requiresLocale(dottedPath) && isKnownRoute(dottedPath)) {
      const stripped = new URL(dottedPath, request.url);
      stripped.search = request.nextUrl.search;
      return NextResponse.redirect(stripped);
    }

    if (isServedFilePath(pathname) || isKnownRoute(dottedPath) || requiresLocale(dottedPath)) {
      return NextResponse.next();
    }
    return NextResponse.rewrite(new URL('/_not-found', request.url), {
      headers: { 'X-Robots-Tag': 'noindex' },
    });
  }

  // Documentation subdomain. The docs are the home of docs.livecontext.ai (clean
  // paths), backed by the /docs routes. API / _next / asset paths were handled
  // above, and this is a no-op for every non-docs host except the apex /docs/*
  // redirect. See resolveDocsRoute for the full mapping.
  const docsRoute = resolveDocsRoute(request.headers.get('host'), pathname);
  if (docsRoute) {
    if (docsRoute.kind === 'redirect') {
      const dest = new URL(docsRoute.url, request.url);
      dest.search = request.nextUrl.search;
      return NextResponse.redirect(dest, 308);
    }
    const docsUrl = request.nextUrl.clone();
    docsUrl.pathname = docsRoute.pathname;
    return NextResponse.rewrite(docsUrl);
  }

  const locale = getPathLocale(pathname);
  const pathnameWithoutLocale = locale ? pathname.slice(locale.length + 1) || '/' : pathname;

  if (IS_CE && pathnameWithoutLocale === '/') {
    const target = locale ? `/${locale}/app/chat` : '/app/chat';
    const newUrl = new URL(target, request.url);
    newUrl.search = request.nextUrl.search;
    return NextResponse.redirect(newUrl, {
      status: 308,
      headers: { 'X-Robots-Tag': 'noindex, nofollow' },
    });
  }

  if (pathnameWithoutLocale === '/chat' || pathnameWithoutLocale.startsWith('/chat/c/')) {
    if (pathnameWithoutLocale.startsWith('/chat/c/')) {
      const conversationId = pathnameWithoutLocale.replace('/chat/c/', '');
      const newPath = locale ? `/${locale}/app/c/${conversationId}` : `/app/c/${conversationId}`;
      const newUrl = new URL(newPath, request.url);
      newUrl.search = request.nextUrl.search;
      return NextResponse.redirect(newUrl);
    }

    const newPath = locale ? `/${locale}/app/chat` : '/app/chat';
    const newUrl = new URL(newPath, request.url);
    newUrl.search = request.nextUrl.search;
    return NextResponse.redirect(newUrl);
  }

  // `/pricing` has no page of its own: pricing is a section of the landing page
  // (cloud) and a settings tab (`/app/settings/pricing`). External links and
  // typed URLs were 404ing - send them to the right surface per edition.
  if (pathnameWithoutLocale === '/pricing') {
    const target = IS_CE
      ? (locale ? `/${locale}/app/settings/pricing` : '/app/settings/pricing')
      : (locale ? `/${locale}#pricing` : '/#pricing');
    const newUrl = new URL(target, request.url);
    newUrl.search = request.nextUrl.search;
    return NextResponse.redirect(newUrl);
  }

  if (pathnameWithoutLocale.startsWith('/dashboard')) {
    const settingsPath = pathnameWithoutLocale.replace('/dashboard', '/app/settings');
    const newPath = locale ? `/${locale}${settingsPath}` : settingsPath;
    const newUrl = new URL(newPath, request.url);
    newUrl.search = request.nextUrl.search;
    return NextResponse.redirect(newUrl);
  }

  if (pathnameWithoutLocale === '/app') {
    const newPath = locale ? `/${locale}/app/chat` : '/app/chat';
    const newUrl = new URL(newPath, request.url);
    newUrl.search = request.nextUrl.search;
    return NextResponse.redirect(newUrl);
  }

  if (pathnameWithoutLocale === '/app/settings') {
    const newPath = locale ? `/${locale}/app/settings/overview` : '/app/settings/overview';
    const newUrl = new URL(newPath, request.url);
    newUrl.search = request.nextUrl.search;
    return NextResponse.redirect(newUrl);
  }

  if (!locale && requiresLocale(pathname)) {
    const newUrl = new URL(`/en${pathname}`, request.url);
    newUrl.search = request.nextUrl.search;
    return NextResponse.redirect(newUrl);
  }

  // Translated persona landings keep their locale and rewrite bare English URLs.
  if (pathnameWithoutLocale.startsWith('/for/')) {
    return intlMiddleware(request);
  }

  if (locale && pathnameWithoutLocale !== '/' && !requiresLocale(pathnameWithoutLocale)) {
    const newUrl = new URL(pathnameWithoutLocale, request.url);
    newUrl.search = request.nextUrl.search;
    return NextResponse.redirect(newUrl);
  }

  // Locale-required pages already live under [locale]. Let them through once a
  // locale is present; otherwise next-intl's `as-needed` redirect strips the
  // locale and the add-locale branch above puts it back, creating a loop.
  if (locale && requiresLocale(pathnameWithoutLocale)) {
    return NextResponse.next();
  }

  // The first segment decides, at EVERY depth, against two lists: it must name
  // an index page when it is the whole path, and a route folder when there is
  // more after it. Anything else is not a page, and gets a real 404 instead of
  // the 200 the dynamic `[locale]` segment would answer with. See
  // PUBLIC_INDEX_SEGMENTS for the two failure modes this covers and what keeps
  // the lists honest.
  if (!isKnownRoute(pathnameWithoutLocale)) {
    return NextResponse.rewrite(new URL('/_not-found', request.url), {
      // The 404 status is what removes these from the index; this is the belt
      // for the braces, and it also covers the window before Google recrawls.
      headers: { 'X-Robots-Tag': 'noindex' },
    });
  }

  if (pathnameWithoutLocale !== '/') {
    return NextResponse.next();
  }

  return intlMiddleware(request);
}

export const config = {
  matcher: [
    '/api/proxy/:path*',
    // The removed blog, including its asset URLs (`/blog/<post>.jpg`). The
    // catch-all below now covers these too, since it no longer excludes dotted
    // paths, so these entries are redundant rather than load-bearing. They stay
    // because they are free and they state the intent for the one set of URLs
    // that is known to be indexed and must keep answering 404. A matcher must
    // be statically analyzable, so the locales are spelled out; the alternation
    // must list every locale in `routing.locales` (a test pins that).
    '/blog/:path*',
    '/:locale(en|fr|es|de|pt|zh)/blog/:path*',
    // `api/` and `icons/` carry their slash on purpose: the alternation matches
    // a PREFIX, so the slashless form also excluded the bare `/api` and
    // `/icons`, plus anything merely STARTING with those letters (`/apiary`,
    // `/iconsfoo`). Those are one-segment paths, so they reached the ISR
    // landing and answered 200, the same soft 404 the unknown-route branch
    // exists to remove.
    //
    // `/icons` and `/apiary` are the ones this fixes on the cloud host.
    // `/api` is a CE and local-dev fix only: the prod ingress routes `path:
    // /api, pathType: Prefix` to the gateway, and Ingress prefix matching is
    // element-wise, so there the bare form never reaches Next at all.
    //
    // With the slash, every real `/api/...` and `/icons/...` request is still
    // excluded and never pays for the middleware. `/api/proxy/*` is unaffected
    // either way: it has its own matcher entry above.
    //
    // Dotted paths now REACH the middleware. They used to be excluded here by
    // a `.*\..*` alternative, which is what left `/index.php`, `/wp-login.php`
    // and `/indexnow.txt` answering 200 with the landing page. Deciding them
    // needs to know which files this site actually serves, which is a list, not
    // a regex: `lib/seo/servedFiles.ts` holds it, a test derives it from
    // `public/` on disk, and the dotted branch in `routeRequest` applies it.
    //
    // `_next/` (all of it, not just static + image) and `favicon.ico` stay
    // excluded, and so do the four `public/` directories whose names are NOT
    // also route names, so they never pay for the middleware at all.
    // `videos/` and `changelog/` are deliberately absent from that list: they
    // ARE routes, so they must reach the branches above. `api/` keeps its
    // trailing slash for the reason above.
    //
    // Excluding them costs the same residual the dotted branch documents: a
    // name that does NOT exist under one of them (`/landing/typo.png`,
    // `/icons/nope.svg`) never reaches the middleware and falls to the
    // catch-all with a 200. Same trade, and the same reason it is acceptable -
    // nothing links there, so only a broken `<img>` reaches it.
    //
    // `favicon.ico` is deliberately NOT in that list. An alternative here is an
    // unanchored PREFIX, so it also excluded `/favicon.ico.php`, which then
    // answered 200 with the landing page: the same hole the `api/` and `icons/`
    // slashes exist to close, on the one entry that has no slash to give it.
    // The file is already named in `PUBLIC_ROOT_FILES`, so dropping it from the
    // matcher costs one middleware call per uncached favicon and nothing else.
    '/((?!api/|_next/|icons/|avatars/|examples/|landing/).*)',
  ],
};

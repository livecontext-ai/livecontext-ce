import createMiddleware from 'next-intl/middleware';
import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';
import { routing } from './i18n/routing';
import { IS_CE } from './lib/edition';
import { enforceSecureLocaleCookie } from './lib/security/localeCookie';
import { isDocsHost, resolveDocsRoute } from './lib/docs/docsHostRewrite';
import { isJwtShapedToken } from './lib/utils/jwtShape';
import { isServedFilePath } from './lib/seo/servedFiles';
import { LOCALIZED_PUBLIC_PATHS, PAGE_LOCALE_HEADER } from './lib/seo/siteUrl';
// Shared with next.config.mjs (LC-027): the ONE place that knows the nonce-CSP path list and
// builds its header set, so the app-shell (`/app`, `/ce-setup`) exclusion here and the
// exclusion baked into securityHeaderRules() can never drift apart. See that module's header.
import { isNonceCspPath, nonceDocumentHeaders } from './lib/security/securityHeaders.mjs';

const intlMiddleware = createMiddleware(routing);
const GATEWAY_URL = process.env.NEXT_PUBLIC_SPRING_BASE_URL || 'http://localhost:8080';
const API_PROXY_CORS_HEADERS = {
  'Access-Control-Allow-Methods': 'GET, POST, PUT, DELETE, OPTIONS, PATCH',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Requested-With, X-Active-Organization-ID',
} as const;

/**
 * Browser origins allowed to call `/api/proxy/*` CROSS-origin (LC-033).
 *
 * The app calls this proxy same-origin, which needs no CORS header at all, and nothing in the
 * product calls it from another origin (the widget, share pages and docs host are all served by
 * this same app). So the default is an EMPTY allow-list: no CORS header is emitted. An operator
 * who genuinely needs a cross-origin caller lists it, exactly, in
 * `API_PROXY_CORS_ALLOWED_ORIGINS` (comma-separated).
 *
 * This used to reflect ANY `Origin` back (and send `*` when there was none) together with
 * `Access-Control-Allow-Credentials: true`. Credentials are no longer sent at all: auth is the
 * `Authorization` Bearer header, never an ambient cookie. A wildcard and the opaque `null`
 * origin (sandboxed interface iframes) are refused even if configured.
 */
export function parseApiProxyAllowedOrigins(raw: string | undefined): Set<string> {
  const origins = new Set<string>();
  for (const entry of (raw ?? '').split(',')) {
    const origin = entry.trim();
    if (!origin || origin.includes('*') || origin.toLowerCase() === 'null') continue;
    origins.add(origin);
  }
  return origins;
}

/**
 * Paths whose pages live under `/[locale]/...` only. They must keep the locale
 * prefix; otherwise routes like `/invitations/accept?token=...` cannot resolve
 * because the page exists under `[locale]`.
 *
 * MISSING AN ENTRY IS A HARD 404, not a degraded page: the locale is stripped by
 * a redirect, the one-segment path matches no static route and is not in
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
  // The interface-rendering shell route (LC-027 CASA E3): a route handler, not a page, but it
  // is a REAL one-segment route (app/interface-frame/route.ts) that must not 404 here. See
  // lib/security/securityHeaders.mjs's INTERFACE_FRAME_PATH for the header side of this route.
  'interface-frame',
  'integrations',
  'local-mcp',
  'marketplace',
  'models',
  'partners',
  'redeem',
  // Vulnerability disclosure policy, the Policy target of /.well-known/security.txt.
  'security',
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
  'offer',
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

/** The NEXT_LOCALE cookie when it names a locale this site serves, null otherwise. */
function cookieLocale(request: NextRequest): string | null {
  const value = request.cookies.get('NEXT_LOCALE')?.value;
  return value && (routing.locales as readonly string[]).includes(value) ? value : null;
}

function getPathLocale(pathname: string): string | null {
  return routing.locales.find(
    (locale) => pathname === `/${locale}` || pathname.startsWith(`/${locale}/`),
  ) ?? null;
}

/**
 * A permanent (308) redirect that browsers keep for one day only.
 *
 * Search engines read the 308 as "this URL moved for good", which is what
 * consolidates the old URL into the new one. A bare 308 is also cached by
 * browsers with no expiry, and two of the branches using it depend on route
 * lists that have changed before (a page later localized, a prefix missing
 * from LOCALE_REQUIRED_PREFIXES): a day's cache means fixing the list reaches
 * every returning visitor by the next day instead of never.
 */
function permanentRedirect(url: URL): NextResponse {
  return NextResponse.redirect(url, {
    status: 308,
    headers: { 'Cache-Control': 'public, max-age=86400' },
  });
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
  const allowed = parseApiProxyAllowedOrigins(process.env.API_PROXY_CORS_ALLOWED_ORIGINS);
  if (allowed.size === 0) {
    return new Headers();
  }
  // The answer depends on Origin once an allow-list exists, so shared caches must key on it.
  const headers = new Headers({ Vary: 'Origin' });
  const origin = request.headers.get('origin');
  if (origin && allowed.has(origin)) {
    Object.entries(API_PROXY_CORS_HEADERS).forEach(([key, value]) => headers.set(key, value));
    headers.set('Access-Control-Allow-Origin', origin);
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

/**
 * A CSP nonce is a one-time, per-REQUEST secret: reusing it (or a constant) defeats the point
 * (an attacker who ever observes one value could replay it forever). `crypto.randomUUID()` is
 * available in the Edge runtime middleware executes in; base64-encoding it matches the value
 * shape CSP expects and Next's own CSP guide. Exported for the header-generation unit test.
 */
export function generateCspNonce(): string {
  return Buffer.from(crypto.randomUUID()).toString('base64');
}

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const nonce = isNonceCspPath(pathname) ? generateCspNonce() : null;
  const nonceHeaderRules = nonce
    ? nonceDocumentHeaders({
        nonce,
        edition: process.env.NEXT_PUBLIC_APP_EDITION,
        serviceUrls: [process.env.NEXT_PUBLIC_KEYCLOAK_URL, process.env.NEXT_PUBLIC_GATEWAY_WS_URL],
      })
    : null;

  // The nonce class (`/app/*`, `/ce-setup/*`) needs its CSP on the REQUEST too, not only the
  // response: Next's SSR reads the request's own Content-Security-Policy header to find the
  // nonce and auto-apply it to its own hydration/flight scripts (see Next's CSP guide and
  // securityHeaders.mjs's module header). A plain response-header mutation after the fact is
  // too late - by then the page has already rendered without it, so this has to reach the
  // `renderPage()` call sites inside routeRequest that actually render these paths (see
  // routeRequest's `nonceRequestHeaders` parameter) rather than being bolted on here.
  let nonceRequestHeaders: Headers | undefined;
  if (nonceHeaderRules) {
    nonceRequestHeaders = new Headers(request.headers);
    nonceRequestHeaders.set('x-nonce', nonce as string);
    for (const { key, value } of nonceHeaderRules) {
      nonceRequestHeaders.set(key, value);
    }
  }

  const response = routeRequest(request, nonceRequestHeaders);

  if (nonceHeaderRules && response) {
    for (const { key, value } of nonceHeaderRules) {
      response.headers.set(key, value);
    }
  }

  // Flight/prefetch responses must NEVER be stored by shared caches (see the
  // headers() block in next.config.mjs - the PRIMARY guard, since middleware
  // cannot override the Cache-Control of a prerendered response). This
  // covers what next.config cannot: responses the middleware itself issues
  // for RSC-headered requests (redirects, API-proxy rewrites).
  if (response && isRscRequest(request)) {
    response.headers.set('cache-control', 'private, no-store');
  }

  // next-intl's middleware sets NEXT_LOCALE without `Secure`; add it on HTTPS (CASA DAST).
  if (response) {
    enforceSecureLocaleCookie(request, response);
  }

  return response;
}

function routeRequest(request: NextRequest, nonceRequestHeaders?: Headers) {
  const { pathname } = request.nextUrl;
  // Every branch that lets a DOCUMENT render goes through here, so a nonce-class path carries its
  // nonce to Next's SSR whichever branch serves it (`/en/app/...`, the public `/s/<token>` and
  // `/f/<token>` links, and a dotted token like `/s/abc.def` or `/en/app/u/j.doe`). Without it the
  // response would carry the nonce CSP while the page rendered un-nonced: hydration blocked.
  // `nonceRequestHeaders` is undefined outside the nonce class, so this is a plain next() there.
  const renderPage = () =>
    NextResponse.next(nonceRequestHeaders ? { request: { headers: nonceRequestHeaders } } : undefined);

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

    // Every incoming header is forwarded, and two of them are load-bearing: Cloudflare fronts
    // livecontext.ai and sets `CF-IPCountry` / `CF-Connecting-IP`, which auth-service reads
    // (signup country for the lifecycle e-mails, abuse-only signup IP). Never rebuild this
    // from an allow-list without keeping those two; pinned by proxy.cloudflareHeaders.test.ts.
    const headers = new Headers(request.headers);
    // The browser's CORS decision for this proxy is made HERE (getApiProxyCorsHeaders, LC-033),
    // so the browser Origin is not forwarded. Upstream, the backend sees a call from this server
    // to a different host and would run its own CORS check against the page origin: the CE
    // monolith only allows APP_PUBLIC_URL/APP_BASE_URL, so an install reached on any other address
    // (default port 8870, a LAN IP, a reverse-proxy domain) got "403 Invalid CORS request" on every
    // browser POST, since browsers send Origin on every non-GET request, same-origin included.
    headers.delete('origin');
    // LC-044: a session JWT is NOT accepted as a `?token=` query parameter. This proxy used to
    // promote a JWT-shaped `token` to an `Authorization: Bearer` header (an <img>/window.open
    // fallback). No shipped caller uses it any more (file URLs are fetched with the header and
    // handed over as blob:/data: URLs), and it let a full-session credential travel in a URL,
    // into access logs, history and Referer headers. An opaque RESOURCE token (invitation
    // lookup, email verification, password reset) is forwarded untouched in the query: the
    // backend itself reads it from `?token=`. A JWT-shaped one is still STRIPPED (never
    // promoted): forwarding it would carry the session credential into the gateway and
    // upstream access logs. The dead copy in `app/api/proxy/[...path]/route.ts` matches.
    if (isJwtShapedToken(targetUrl.searchParams.get('token'))) {
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
    // same 308 the branch far below gives the dotless twin. Without it,
    // `/en/marketplace/wp-login.php` answered `next()` and rendered the
    // prerendered landing with a 404 body and a 200 status: an invented name
    // wearing a locale prefix walked straight through the guard.
    // A path whose first segment names nothing is still 404'd outright below,
    // rather than redirected to another 404.
    if (dottedLocale && !requiresLocale(dottedPath) && isKnownRoute(dottedPath)) {
      const stripped = new URL(dottedPath, request.url);
      stripped.search = request.nextUrl.search;
      return permanentRedirect(stripped);
    }

    if (isServedFilePath(pathname) || isKnownRoute(dottedPath) || requiresLocale(dottedPath)) {
      return renderPage();
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
    // Permanent: a 307 tells search engines to keep this URL indexed in case a
    // page comes back to it.
    return permanentRedirect(newUrl);
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

  // A personal offer's link (the email a free account receives once its credits run out, and
  // every one already sent) points at the pricing page with the offer's code: it opens the offer's
  // own full-screen page instead, in the email's language (that page lives outside the [locale]
  // tree and reads the NEXT_LOCALE cookie). Cloud only: a self-hosted install has no such offer.
  if (!IS_CE && pathnameWithoutLocale === '/app/settings/pricing' && request.nextUrl.searchParams.has('lc_offer')) {
    const newUrl = new URL('/offer/personal', request.url);
    newUrl.search = request.nextUrl.search;
    const response = NextResponse.redirect(newUrl);
    if (locale) response.cookies.set('NEXT_LOCALE', locale, { path: '/', maxAge: 31_536_000, sameSite: 'lax' });
    return response;
  }

  // Settings > Agents & Chat rendered a second copy of the agent & Orbi defaults editor,
  // which now lives only on the Agents page "Settings" tab. Old links and bookmarks land
  // there, in their own locale (a page-level redirect() would lose it to the /en fallback).
  if (pathnameWithoutLocale === '/app/settings/agents') {
    const newPath = locale ? `/${locale}/app/agent` : '/app/agent';
    const newUrl = new URL(newPath, request.url);
    newUrl.search = '?view=settings';
    return NextResponse.redirect(newUrl);
  }

  if (pathnameWithoutLocale === '/app/settings') {
    const newPath = locale ? `/${locale}/app/settings/overview` : '/app/settings/overview';
    const newUrl = new URL(newPath, request.url);
    newUrl.search = request.nextUrl.search;
    return NextResponse.redirect(newUrl);
  }

  if (!locale && requiresLocale(pathname)) {
    // The language the visitor chose (the NEXT_LOCALE cookie), English otherwise: an unprefixed
    // link from a public page ("Open the app", a pricing link) must not drop a French reader
    // into the English app.
    const newUrl = new URL(`/${cookieLocale(request) ?? routing.defaultLocale}${pathname}`, request.url);
    newUrl.search = request.nextUrl.search;
    return NextResponse.redirect(newUrl);
  }

  // A public page with one URL per language outside the [locale] tree (LOCALIZED_PUBLIC_PATHS):
  // English at the bare path, the others prefixed. The prefixed URL is rewritten onto the page
  // with its language in PAGE_LOCALE_HEADER, so one URL always serves one language, whatever the
  // cookie says: what a crawler reads is what a visitor reads.
  if (!IS_CE && (LOCALIZED_PUBLIC_PATHS as readonly string[]).includes(pathnameWithoutLocale)) {
    if (locale === routing.defaultLocale) {
      const newUrl = new URL(pathnameWithoutLocale, request.url);
      newUrl.search = request.nextUrl.search;
      return permanentRedirect(newUrl);
    }
    const chosen = cookieLocale(request);
    if (!locale && chosen && chosen !== routing.defaultLocale) {
      // The bare URL is English: a visitor who chose another language goes to theirs.
      const newUrl = new URL(`/${chosen}${pathnameWithoutLocale}`, request.url);
      newUrl.search = request.nextUrl.search;
      // It depends on the cookie: no shared cache may store it for the next visitor.
      return NextResponse.redirect(newUrl, {
        headers: { 'Cache-Control': 'private, no-store', Vary: 'Cookie' },
      });
    }
    // Set here, never taken from the request: on these URLs a client cannot name another
    // language. (Elsewhere a request carrying the header only changes its own sender's view.)
    const headers = new Headers(nonceRequestHeaders ?? request.headers);
    headers.set(PAGE_LOCALE_HEADER, locale ?? routing.defaultLocale);
    if (!locale) return NextResponse.next({ request: { headers } });
    const target = new URL(pathnameWithoutLocale, request.url);
    target.search = request.nextUrl.search;
    const response = NextResponse.rewrite(target, { request: { headers } });
    // Opening a page in a language is choosing it, as a prefixed [locale] page does.
    response.cookies.set('NEXT_LOCALE', locale, { path: '/', maxAge: 31_536_000, sameSite: 'lax' });
    return response;
  }

  // Translated persona landings keep their locale and rewrite bare English URLs.
  if (pathnameWithoutLocale.startsWith('/for/')) {
    return intlMiddleware(request);
  }

  if (locale && pathnameWithoutLocale !== '/' && !requiresLocale(pathnameWithoutLocale)) {
    const newUrl = new URL(pathnameWithoutLocale, request.url);
    newUrl.search = request.nextUrl.search;
    // Permanent (308): these pages exist at ONE URL, the bare one. A temporary
    // redirect kept `/fr/about` and friends alive in the index as separate URLs.
    return permanentRedirect(newUrl);
  }

  // Locale-required pages already live under [locale]. Let them through once a
  // locale is present; otherwise next-intl's `as-needed` redirect strips the
  // locale and the add-locale branch above puts it back, creating a loop.
  //
  // This is where the locale-prefixed nonce-class pages render: `/app/*`, `/ce-setup/*` and the
  // auth/onboarding areas (`/login`, `/register`, `/onboarding`, `/forgot-password`,
  // `/reset-password`, `/invitations`, `/auth`) are ALL of LOCALE_REQUIRED_PREFIXES, and every one
  // of them takes no other branch above once it carries a dotless path. The public `/s` and `/f`
  // links render through the known-route branch below, dotted paths through the dotted branch
  // above - all three use renderPage(), which carries the per-request CSP nonce so Next's SSR can
  // find and auto-apply it (see proxy() and lib/security/securityHeaders.mjs NONCE_CSP_PREFIXES).
  if (locale && requiresLocale(pathnameWithoutLocale)) {
    return renderPage();
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
    return renderPage();
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

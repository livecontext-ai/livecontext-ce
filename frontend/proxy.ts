import createMiddleware from 'next-intl/middleware';
import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';
import { routing } from './i18n/routing';
import { IS_CE } from './lib/edition';
import { resolveDocsRoute } from './lib/docs/docsHostRewrite';
import { isJwtShapedToken } from './lib/utils/jwtShape';

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
 */
const LOCALE_REQUIRED_PREFIXES = [
  '/app',
  '/auth',
  '/onboarding',
  '/login',
  '/register',
  '/ce-setup',
  '/invitations',
] as const;

function requiresLocale(path: string): boolean {
  return LOCALE_REQUIRED_PREFIXES.some(
    (prefix) => path === prefix || path.startsWith(prefix + '/'),
  );
}

function getPathLocale(pathname: string): string | null {
  return routing.locales.find(
    (locale) => pathname === `/${locale}` || pathname.startsWith(`/${locale}/`),
  ) ?? null;
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

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  if (isApiProxyPath(pathname) && request.method === 'OPTIONS') {
    return new NextResponse(null, {
      status: 204,
      headers: getApiProxyCorsHeaders(request),
    });
  }

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

  if (
    pathname.startsWith('/_next/')
    || pathname.startsWith('/favicon.ico')
    || pathname.startsWith('/icons/')
    || pathname.includes('.')
  ) {
    return NextResponse.next();
  }

  if (pathname.startsWith('/app/public/')) {
    return NextResponse.next();
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

  if (pathnameWithoutLocale !== '/') {
    return NextResponse.next();
  }

  return intlMiddleware(request);
}

export const config = {
  matcher: [
    '/api/proxy/:path*',
    '/((?!api|_next/static|_next/image|favicon.ico|icons|.*\\..*).*)',
  ],
};

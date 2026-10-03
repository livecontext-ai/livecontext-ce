import type { NextRequest, NextResponse } from 'next/server';

/** next-intl's locale cookie (routing.localeCookie keeps the default name). */
export const LOCALE_COOKIE_NAME = 'NEXT_LOCALE';

/**
 * True when the browser reached this request over HTTPS. TLS ends at the edge (Cloudflare,
 * Traefik, a CE reverse proxy), so the request URL Next sees is usually `http:`: the edge's
 * `X-Forwarded-Proto` is what tells (in cloud prod Traefik trusts Cloudflare's, which says
 * `https`). Same rule for cloud and CE builds: a cloud build served over plain HTTP (the cloud
 * e2e compose, a localhost run) must not get a `Secure` cookie either.
 */
export function isSecureRequest(request: Pick<NextRequest, 'headers' | 'nextUrl'>): boolean {
  const forwarded = request.headers.get('x-forwarded-proto')?.split(',')[0]?.trim().toLowerCase();
  return forwarded === 'https' || request.nextUrl.protocol === 'https:';
}

/**
 * Adds `Secure` to the `NEXT_LOCALE` cookie next-intl's middleware sets, when the request came
 * over HTTPS (CASA DAST finding: the cookie was sent without it). SameSite=lax and the other
 * attributes are kept as next-intl set them. Never added on a plain-HTTP request (CE install,
 * cloud e2e, localhost): a browser drops a `Secure` cookie set over HTTP, which would silently
 * lose the language choice.
 */
export function enforceSecureLocaleCookie(
  request: Pick<NextRequest, 'headers' | 'nextUrl'>,
  response: NextResponse,
): void {
  const cookie = response.cookies.get(LOCALE_COOKIE_NAME);
  if (!cookie || cookie.secure || !isSecureRequest(request)) return;
  response.cookies.set({ ...cookie, secure: true });
}

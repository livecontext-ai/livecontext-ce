import { describe, it, expect } from 'vitest';
import { NextRequest, NextResponse } from 'next/server';
import { enforceSecureLocaleCookie, isSecureRequest, LOCALE_COOKIE_NAME } from '@/lib/security/localeCookie';

/**
 * Regression (CASA DAST): next-intl's middleware set `NEXT_LOCALE` without `Secure` on the
 * HTTPS site. The flag is added per request, and never on a plain-HTTP request (a browser would
 * drop the cookie and lose the language choice). Round 4: cloud builds were forced Secure even
 * over plain HTTP (cloud e2e compose, localhost); they now follow the effective protocol too.
 */
function request(url: string, headers: Record<string, string> = {}) {
  return new NextRequest(url, { headers });
}

function responseWithLocaleCookie() {
  const response = NextResponse.next();
  response.cookies.set(LOCALE_COOKIE_NAME, 'fr', { path: '/', sameSite: 'lax' });
  return response;
}

describe('isSecureRequest', () => {
  it('trusts the edge X-Forwarded-Proto (TLS ends before Next)', () => {
    expect(isSecureRequest(request('http://frontend:3000/fr', { 'x-forwarded-proto': 'https' }))).toBe(true);
    expect(isSecureRequest(request('http://frontend:3000/fr', { 'x-forwarded-proto': 'HTTPS, http' }))).toBe(true);
  });

  it('reads the request URL scheme when no edge header is present', () => {
    expect(isSecureRequest(request('https://livecontext.ai/fr'))).toBe(true);
    expect(isSecureRequest(request('http://192.168.1.10:8870/fr'))).toBe(false);
  });

  it('is false for a plain-HTTP request whatever the edition', () => {
    expect(isSecureRequest(request('http://localhost:3000/fr'))).toBe(false);
    expect(isSecureRequest(request('http://frontend:3000/fr', { 'x-forwarded-proto': 'http' }))).toBe(false);
  });
});

describe('enforceSecureLocaleCookie', () => {
  it('adds Secure and keeps SameSite=lax on an HTTPS request', () => {
    const response = responseWithLocaleCookie();
    enforceSecureLocaleCookie(request('http://frontend:3000/fr', { 'x-forwarded-proto': 'https' }), response);

    const header = response.headers.get('set-cookie') ?? '';
    expect(header).toMatch(/NEXT_LOCALE=fr/);
    expect(header).toMatch(/Secure/i);
    expect(header).toMatch(/SameSite=lax/i);
    expect(header).toMatch(/Path=\//i);
  });

  it('adds Secure in cloud prod, where Traefik forwards the https of Cloudflare', () => {
    const response = responseWithLocaleCookie();
    enforceSecureLocaleCookie(
      request('http://livecontext-frontend:3000/fr', { 'x-forwarded-proto': 'https' }),
      response,
    );
    expect(response.headers.get('set-cookie')).toMatch(/Secure/i);
  });

  it('leaves the cookie without Secure on a plain-HTTP CE install', () => {
    const response = responseWithLocaleCookie();
    enforceSecureLocaleCookie(request('http://192.168.1.10:8870/fr'), response);
    expect(response.headers.get('set-cookie')).not.toMatch(/Secure/i);
  });

  it('leaves the cookie without Secure on a cloud build served over plain HTTP', () => {
    // Cloud e2e compose / localhost: a Secure cookie set over HTTP is dropped by the browser.
    const response = responseWithLocaleCookie();
    enforceSecureLocaleCookie(request('http://localhost:3000/fr'), response);
    expect(response.headers.get('set-cookie')).not.toMatch(/Secure/i);
  });

  it('sets nothing when the response carries no locale cookie', () => {
    const response = NextResponse.next();
    enforceSecureLocaleCookie(request('https://livecontext.ai/fr'), response);
    expect(response.headers.get('set-cookie')).toBeNull();
  });
});

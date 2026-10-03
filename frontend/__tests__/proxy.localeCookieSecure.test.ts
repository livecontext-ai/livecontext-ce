/**
 * Regression (CASA round 4): the proxy forced `Secure` on next-intl's NEXT_LOCALE cookie for every
 * cloud build (`alwaysSecure = !IS_CE`), even when the page was served over plain HTTP (the cloud
 * e2e compose, a localhost run), where a browser drops a Secure cookie and the language choice is
 * lost. `Secure` now follows the effective protocol (X-Forwarded-Proto, else the request URL) on
 * both editions; cloud prod stays Secure because Traefik forwards Cloudflare's `https`.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NextRequest, NextResponse } from 'next/server';

const editionMock = vi.hoisted(() => ({ IS_CE: false }));
vi.mock('@/lib/edition', () => editionMock);

// Stands in for next-intl's middleware: it answers with the locale cookie it sets.
vi.mock('next-intl/middleware', () => ({
  default: () => () => {
    const response = NextResponse.next();
    response.cookies.set('NEXT_LOCALE', 'fr', { path: '/', sameSite: 'lax' });
    return response;
  },
}));

import { proxy } from '@/proxy';

function localeCookieHeader(url: string, headers: Record<string, string> = {}): string {
  const response = proxy(new NextRequest(url, { headers })) as Response;
  const header = response.headers.get('set-cookie') ?? '';
  expect(header).toMatch(/NEXT_LOCALE=fr/);
  return header;
}

describe.each([
  ['cloud', false],
  ['CE', true],
])('NEXT_LOCALE Secure flag on a %s build', (_edition, isCe) => {
  beforeEach(() => {
    editionMock.IS_CE = isCe;
  });

  it('is not Secure over plain HTTP (localhost, e2e compose)', () => {
    expect(localeCookieHeader('http://localhost:3000/for/developers')).not.toMatch(/Secure/i);
  });

  it('is not Secure when the edge says http', () => {
    expect(localeCookieHeader('http://frontend:3000/for/developers', { 'x-forwarded-proto': 'http' })).not.toMatch(/Secure/i);
  });

  it('is Secure behind an HTTPS edge', () => {
    expect(localeCookieHeader('http://frontend:3000/for/developers', { 'x-forwarded-proto': 'https' })).toMatch(/Secure/i);
  });

  it('is Secure on an https request URL', () => {
    expect(localeCookieHeader('https://livecontext.ai/for/developers')).toMatch(/Secure/i);
  });
});

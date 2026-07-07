/**
 * Regression: Next.js differentiates RSC/flight and prefetch requests from
 * document requests by HEADERS only, while static routes send
 * `Cache-Control: s-maxage=31536000` on BOTH. Cloudflare ignores `Vary: rsc`,
 * so a single client-side navigation cached a `text/x-component` flight
 * payload under the page URL and served it as the DOCUMENT to every visitor
 * and crawler (this actually happened to /fr in prod). The middleware must
 * force `private, no-store` on every RSC-headered response.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NextRequest } from 'next/server';

const editionMock = vi.hoisted(() => ({ IS_CE: false }));
vi.mock('@/lib/edition', () => editionMock);

// next-intl's ESM middleware build does not resolve under the vitest node
// environment; the paths under test never reach it.
vi.mock('next-intl/middleware', () => ({
  default: () => () => undefined,
}));

import { proxy } from '@/proxy';

function request(path: string, headers: Record<string, string> = {}): NextRequest {
  return new NextRequest(`https://livecontext.ai${path}`, { headers });
}

describe('proxy RSC cache poisoning guard', () => {
  beforeEach(() => {
    editionMock.IS_CE = false;
  });

  it.each([
    ['rsc', '1'],
    ['next-router-prefetch', '1'],
    ['next-router-segment-prefetch', '/_tree'],
  ])('forces no-store when the %s header is present', (header, value) => {
    const response = proxy(request('/about', { [header]: value })) as Response;
    expect(response.headers.get('cache-control')).toBe('private, no-store');
  });

  it('applies the guard on redirects too (locale-stripping redirect)', () => {
    const response = proxy(request('/fr/about', { rsc: '1' })) as Response;
    expect(response.headers.get('location')).toBe('https://livecontext.ai/about');
    expect(response.headers.get('cache-control')).toBe('private, no-store');
  });

  it('leaves document requests untouched (no forced cache-control)', () => {
    const response = proxy(request('/about')) as Response;
    expect(response.headers.get('cache-control')).toBeNull();
  });
});

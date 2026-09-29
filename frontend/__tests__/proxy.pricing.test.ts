/**
 * Regression: `/pricing` used to 404 - no page exists at that path (pricing is
 * a landing-page section on cloud and a settings tab in-app). The middleware
 * must redirect it to the right surface per edition.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NextRequest } from 'next/server';

const editionMock = vi.hoisted(() => ({ IS_CE: false }));
vi.mock('@/lib/edition', () => editionMock);

// next-intl's ESM middleware build does not resolve under the vitest node
// environment; the /pricing branches under test never reach it (it only
// handles the bare landing root).
vi.mock('next-intl/middleware', () => ({
  default: () => () => undefined,
}));

import { proxy } from '@/proxy';

function request(path: string): NextRequest {
  return new NextRequest(`https://livecontext.ai${path}`);
}

function redirectLocation(response: Response | undefined): string | null {
  if (!response) return null;
  return response.headers.get('location');
}

describe('proxy /pricing redirect', () => {
  beforeEach(() => {
    editionMock.IS_CE = false;
  });

  it('cloud: /pricing redirects to the landing pricing section', () => {
    const response = proxy(request('/pricing')) as Response;
    expect(redirectLocation(response)).toBe('https://livecontext.ai/#pricing');
  });

  it.each([
    ['cloud', false, '/pricing'],
    ['cloud', false, '/fr/pricing'],
    ['CE', true, '/pricing'],
    ['CE', true, '/fr/pricing'],
  ])('%s: %s is a permanent redirect that browsers keep for a day only', (_edition, isCe, path) => {
    // Permanent, because a 307 told search engines to keep the dead URL indexed.
    // Bounded, because a bare 308 is cached by browsers forever.
    editionMock.IS_CE = isCe;
    const response = proxy(request(path)) as Response;
    expect(response.status).toBe(308);
    expect(response.headers.get('cache-control')).toBe('public, max-age=86400');
  });

  it('cloud: localized /fr/pricing keeps the locale on the landing redirect', () => {
    const response = proxy(request('/fr/pricing')) as Response;
    expect(redirectLocation(response)).toBe('https://livecontext.ai/fr#pricing');
  });

  it('CE: /pricing redirects to the in-app pricing settings tab', () => {
    editionMock.IS_CE = true;
    const response = proxy(request('/pricing')) as Response;
    expect(redirectLocation(response)).toBe(
      'https://livecontext.ai/app/settings/pricing',
    );
  });

  it('CE: localized /fr/pricing keeps the locale on the in-app redirect', () => {
    editionMock.IS_CE = true;
    const response = proxy(request('/fr/pricing')) as Response;
    expect(redirectLocation(response)).toBe(
      'https://livecontext.ai/fr/app/settings/pricing',
    );
  });

  it('preserves the query string through the redirect', () => {
    const response = proxy(request('/pricing?plan=team')) as Response;
    expect(redirectLocation(response)).toBe(
      'https://livecontext.ai/?plan=team#pricing',
    );
  });
});

/**
 * /partners has one URL per language, outside the [locale] tree: English at the bare path, the
 * others prefixed (/fr/partners...), so search engines index each language. The prefixed URL is
 * rewritten onto the page with its language in a request header; one URL always serves one
 * language, whatever the cookie. And an unprefixed app link keeps the visitor's language.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NextRequest } from 'next/server';

const editionMock = vi.hoisted(() => ({ IS_CE: false }));
vi.mock('@/lib/edition', () => editionMock);
vi.mock('next-intl/middleware', () => ({
  default: () => () => undefined,
}));

import { proxy } from '@/proxy';
import { PAGE_LOCALE_HEADER } from '@/lib/seo/siteUrl';

function request(path: string, init?: { cookie?: string; headers?: Record<string, string> }): NextRequest {
  const headers = new Headers(init?.headers);
  if (init?.cookie) headers.set('cookie', init.cookie);
  return new NextRequest(`https://livecontext.ai${path}`, { headers });
}

/** The page language the proxy hands to the page (a request-header override on next/rewrite). */
const pageLocale = (response: Response) => response.headers.get(`x-middleware-request-${PAGE_LOCALE_HEADER}`);

describe('proxy: /partners in every language', () => {
  beforeEach(() => {
    editionMock.IS_CE = false;
  });

  it.each(['fr', 'de', 'es', 'pt', 'zh'])('/%s/partners renders the page in that language, at that URL', (locale) => {
    const response = proxy(request(`/${locale}/partners?apply=1`)) as Response;

    expect(response.headers.get('x-middleware-rewrite')).toBe('https://livecontext.ai/partners?apply=1');
    expect(pageLocale(response)).toBe(locale);
    // Opening a page in a language is choosing it, as a prefixed [locale] page does.
    expect(response.headers.get('set-cookie')).toMatch(new RegExp(`NEXT_LOCALE=${locale};`));
  });

  it('regression: the bare URL is English for a crawler (no cookie), not whatever the last visitor chose', () => {
    const response = proxy(request('/partners')) as Response;

    expect(response.headers.get('location')).toBeNull();
    expect(pageLocale(response)).toBe('en');
  });

  it('a visitor who chose another language is sent from the bare URL to theirs, query kept, never from a shared cache', () => {
    const response = proxy(request('/partners?apply=1', { cookie: 'NEXT_LOCALE=de' })) as Response;

    expect(response.status).toBe(307);
    expect(response.headers.get('location')).toBe('https://livecontext.ai/de/partners?apply=1');
    expect(response.headers.get('cache-control')).toBe('private, no-store');
    expect(response.headers.get('vary')).toContain('Cookie');
  });

  it('regression: a prefixed URL is never redirected by the cookie, whatever it says (that would loop)', () => {
    for (const cookie of ['NEXT_LOCALE=fr', 'NEXT_LOCALE=de', 'NEXT_LOCALE=en']) {
      const response = proxy(request('/fr/partners', { cookie })) as Response;
      expect(response.headers.get('location'), cookie).toBeNull();
      expect(response.headers.get('x-middleware-rewrite'), cookie).toBe('https://livecontext.ai/partners');
      expect(pageLocale(response), cookie).toBe('fr');
    }
  });

  it('English or an unknown cookie stays on the bare URL', () => {
    for (const cookie of ['NEXT_LOCALE=en', 'NEXT_LOCALE=xx']) {
      const response = proxy(request('/partners', { cookie })) as Response;
      expect(response.headers.get('location'), cookie).toBeNull();
      expect(pageLocale(response), cookie).toBe('en');
    }
  });

  it('/en/partners is the bare URL: a permanent redirect, never a second English copy', () => {
    const response = proxy(request('/en/partners?x=1')) as Response;

    expect(response.status).toBe(308);
    expect(response.headers.get('location')).toBe('https://livecontext.ai/partners?x=1');
  });

  it('a client cannot name another language for a URL: the header it sends is replaced', () => {
    const bare = proxy(request('/partners', { headers: { [PAGE_LOCALE_HEADER]: 'fr' } })) as Response;
    const prefixed = proxy(request('/de/partners', { headers: { [PAGE_LOCALE_HEADER]: 'fr' } })) as Response;

    expect(pageLocale(bare)).toBe('en');
    expect(pageLocale(prefixed)).toBe('de');
  });

  it('self-hosted: no partner program, the prefixed URL is not given a language of its own', () => {
    editionMock.IS_CE = true;
    const response = proxy(request('/fr/partners')) as Response;

    expect(response.headers.get('x-middleware-rewrite')).toBeNull();
    expect(pageLocale(response)).toBeNull();
  });
});

describe('proxy: an unprefixed app link keeps the visitor\'s language', () => {
  beforeEach(() => {
    editionMock.IS_CE = false;
  });

  it('regression: a French reader is sent to the French app, not /en', () => {
    const response = proxy(request('/app/settings/pricing?creditTierIndex=5', { cookie: 'NEXT_LOCALE=fr' })) as Response;

    expect(response.status).toBe(307);
    expect(response.headers.get('location')).toBe('https://livecontext.ai/fr/app/settings/pricing?creditTierIndex=5');
  });

  it('without a cookie, or with one the site does not serve, English as before', () => {
    expect((proxy(request('/app/chat')) as Response).headers.get('location')).toBe('https://livecontext.ai/en/app/chat');
    expect((proxy(request('/app/chat', { cookie: 'NEXT_LOCALE=xx' })) as Response).headers.get('location'))
      .toBe('https://livecontext.ai/en/app/chat');
  });
});

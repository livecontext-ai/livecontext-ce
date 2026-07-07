import { describe, it, expect, vi, beforeEach } from 'vitest';
import { DOCS_PAGES } from '../docs/_nav';

const SITE = 'https://livecontext.ai';

describe('sitemap - cloud edition', () => {
  beforeEach(() => vi.resetModules());

  it('emits one entry per live docs page, with the Overview at a higher priority', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    const { default: sitemap } = await import('../sitemap');
    const entries = sitemap();
    const urls = entries.map((e) => e.url);

    // Docs live on the subdomain at clean paths; every IA page is in the sitemap.
    const DOCS = 'https://docs.livecontext.ai';
    for (const page of DOCS_PAGES) {
      expect(urls).toContain(`${DOCS}${page.href === '/' ? '' : page.href}`);
    }
    // Overview is prioritised above its sub-pages.
    expect(entries.find((e) => e.url === DOCS)?.priority).toBe(0.6);
    expect(entries.find((e) => e.url === `${DOCS}/agents`)?.priority).toBe(0.5);
    // The apex landing root is still emitted alongside the docs.
    expect(urls).toContain(SITE);
  });

  it('emits the landing page ONCE at the apex (locale duplicates canonicalize there, not sitemap entries)', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    const { default: sitemap } = await import('../sitemap');
    const { routing } = await import('@/i18n/routing');
    const urls = sitemap().map((e) => e.url);

    expect(urls).toContain(SITE);
    // The landing serves identical English content on every locale URL, so
    // listing /fr, /es, ... would advertise duplicates that canonicalize away.
    for (const locale of routing.locales) {
      expect(urls).not.toContain(`${SITE}/${locale}`);
    }
  });

  it('emits the /compare hub and one entry per comparison page', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    const { default: sitemap } = await import('../sitemap');
    const { COMPARISONS } = await import('../compare/_lib/comparisons');
    const entries = sitemap();
    const urls = entries.map((e) => e.url);

    expect(urls).toContain(`${SITE}/compare`);
    for (const comparison of COMPARISONS) {
      expect(urls).toContain(`${SITE}/compare/${comparison.slug}`);
    }
    // Comparison pages are a primary SEO surface: above sub-pages, below the landing.
    expect(entries.find((e) => e.url === `${SITE}/compare/n8n-alternative`)?.priority).toBe(0.8);
  });
});

describe('sitemap - community edition', () => {
  beforeEach(() => vi.resetModules());

  it('is empty on a self-hosted edition (never indexed)', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: true }));
    const { default: sitemap } = await import('../sitemap');
    expect(sitemap()).toEqual([]);
  });
});

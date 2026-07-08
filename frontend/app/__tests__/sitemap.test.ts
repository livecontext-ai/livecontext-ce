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

  it('emits the blog index and one entry per post, each with a full hreflang cluster', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    const { default: sitemap } = await import('../sitemap');
    const { getAllPosts } = await import('@/lib/blog/posts');
    const entries = sitemap();
    const urls = entries.map((e) => e.url);

    // The index plus every post (enumerated from the registry) is present.
    expect(urls).toContain(`${SITE}/blog`);
    const posts = getAllPosts();
    for (const post of posts) {
      expect(urls).toContain(`${SITE}/blog/${post.slug}`);
    }

    // Each blog entry carries a reciprocal hreflang cluster (x-default + en + 5 locales).
    const indexEntry = entries.find((e) => e.url === `${SITE}/blog`);
    expect(Object.keys(indexEntry?.alternates?.languages ?? {}).sort()).toEqual([
      'de', 'en', 'es', 'fr', 'pt', 'x-default', 'zh',
    ]);
    expect(indexEntry?.alternates?.languages?.fr).toBe(`${SITE}/fr/blog`);
    expect(indexEntry?.alternates?.languages?.['x-default']).toBe(`${SITE}/blog`);

    // An article's alternates point at the localized article URLs.
    const articleEntry = entries.find((e) => e.url === `${SITE}/blog/${posts[0].slug}`);
    expect(articleEntry?.alternates?.languages?.de).toBe(`${SITE}/de/blog/${posts[0].slug}`);
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

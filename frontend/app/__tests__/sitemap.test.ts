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
});

describe('sitemap - community edition', () => {
  beforeEach(() => vi.resetModules());

  it('is empty on a self-hosted edition (never indexed)', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: true }));
    const { default: sitemap } = await import('../sitemap');
    expect(sitemap()).toEqual([]);
  });
});

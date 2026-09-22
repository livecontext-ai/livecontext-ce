import { describe, it, expect, vi, beforeEach } from 'vitest';
import { PERSONA_KEYS } from '@/components/landing/personas/personas';
import { DOCS_PAGES } from '../docs/_nav';

const SITE = 'https://livecontext.ai';

/** A marketplace listing shaped like the public read path returns it. */
function listing(overrides: Record<string, unknown> = {}) {
  return {
    id: 'pub-1',
    publicSlug: 'invoice-bot',
    title: 'Invoice Bot',
    description: 'x'.repeat(200),
    publisherName: 'John Doe',
    publisherHandle: 'john-doe',
    publisherAvatarUrl: null,
    categorySlug: 'automation',
    categoryName: 'Automation',
    averageRating: 4.5,
    reviewCount: 12,
    useCount: 42,
    publishedAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-02T10:00:00Z',
    publicationType: 'WORKFLOW',
    ...overrides,
  };
}

/**
 * Stub the marketplace read path. The sitemap must never reach the network in a
 * unit test, and every test that does not care about listings gets an empty
 * catalog so the in-repo sections stay isolated.
 */
function mockMarketplace(publications: unknown[] = [], truncated = false) {
  vi.doMock('@/lib/marketplace/publicPublications', () => ({
    fetchAllPublicPublications: vi.fn().mockResolvedValue({ publications, truncated }),
  }));
}

/** An integration shaped like the public read path returns it. */
function integration(overrides: Record<string, unknown> = {}) {
  return {
    slug: 'slack',
    name: 'Slack',
    description: 'Post messages, manage channels and read conversations.',
    iconSlug: 'slack',
    iconUrl: null,
    toolCount: 45,
    authType: 'oauth2',
    ...overrides,
  };
}

/**
 * Stub the integration read path, for the same reason as the marketplace one.
 * Declared by every test rather than chained onto `mockMarketplace`: `vi.doMock`
 * keeps the FIRST registration for a path, so a helper registering the empty
 * default would win over a test asking for its own integrations, and that test
 * would pass on nothing.
 */
/** A film as the public library read hands it back. */
function videoRow(overrides: Record<string, unknown> = {}) {
  return {
    slug: 'automate-x',
    title: 'Automate X, end to end',
    tagline: 'A five minute film about X.',
    youtubeId: 'lG-wfKo2NOo',
    durationSeconds: 318,
    publishedAt: '2026-09-10T15:57:19Z',
    // Absolute, like the library serves them.
    poster: 'https://livecontext.ai/videos/automate-x.webp',
    shareImage: 'https://livecontext.ai/videos/automate-x.jpg',
    posterAlt: 'The X screen',
    problem: [],
    answer: [],
    highlights: [],
    chapters: [],
    transcript: [],
    marketplaceSlug: 'x-app',
    marketplaceTitle: 'X App',
    ...overrides,
  };
}

/**
 * Stub the film-library read, for the same reason as the two above: the sitemap
 * must never reach the network in a unit test.
 */
function mockVideos(videos: unknown[] = []) {
  vi.doMock('../videos/_lib/publicVideos', () => ({
    fetchPublishedVideos: async () => videos,
    fetchPublishedVideosOrEmpty: async () => videos,
    fetchVideo: async () => null,
  }));
}

function mockIntegrations(integrations: unknown[] = [], truncated = false) {
  vi.doMock('@/lib/integrations/publicIntegrations', () => ({
    fetchAllIntegrations: vi.fn().mockResolvedValue({
      integrations,
      totalElements: integrations.length,
      truncated,
    }),
  }));
}

describe('sitemap - cloud edition', () => {
  beforeEach(() => vi.resetModules());

  it('lists every persona page in six languages with reciprocal alternates', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations();
    mockVideos();
    const { default: sitemap } = await import('../sitemap');
    const entries = (await sitemap()).filter((entry) => entry.url.includes('/for/'));
    // Read from the list itself: adding a persona is a product decision that belongs in
    // one place, and a hardcoded count here only ever says "someone added a page".
    expect(entries).toHaveLength(PERSONA_KEYS.length * 6);
    for (const persona of PERSONA_KEYS) {
      const english = entries.find((entry) => entry.url === `${SITE}/for/${persona}`);
      expect(english?.alternates?.languages?.fr).toBe(`${SITE}/fr/for/${persona}`);
      expect(english?.alternates?.languages?.['x-default']).toBe(`${SITE}/for/${persona}`);
      for (const locale of ['fr', 'de', 'es', 'pt', 'zh']) {
        expect(entries.find((entry) => entry.url === `${SITE}/${locale}/for/${persona}`)?.alternates).toEqual(english?.alternates);
      }
    }
  });

  it('emits one entry per live docs page, with the Overview at a higher priority', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations();
    mockVideos();
    const { default: sitemap } = await import('../sitemap');
    const entries = await sitemap();
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

  it('emits the landing once per locale, now that each one is a translated page', async () => {
    // It used to emit the apex ALONE, and that was right while the landing was hardcoded
    // English everywhere: /fr was a duplicate that canonicalized to /, so listing it
    // advertised a URL the page asked the crawler to drop. Each locale canonicalizes to
    // itself now, and an unlisted translation is one Google has to find on its own.
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations();
    mockVideos();
    const { default: sitemap } = await import('../sitemap');
    const { routing } = await import('@/i18n/routing');
    const entries = await sitemap();
    const urls = entries.map((e) => e.url);

    // The apex, unprefixed and with no trailing slash: the exact string the page's own
    // canonical resolves to.
    expect(urls).toContain(SITE);
    for (const locale of routing.locales.filter((value) => value !== 'en')) {
      expect(urls).toContain(`${SITE}/${locale}`);
    }
    // English is the unprefixed URL: a /en entry would be a second address for one page.
    expect(urls).not.toContain(`${SITE}/en`);
  });

  it('gives every landing entry the reciprocal hreflang cluster', async () => {
    // A sitemap alternate that is not mirrored on the page, or between locales, is ignored
    // by Google rather than half-applied, so all six must carry the same complete map.
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations();
    mockVideos();
    const { default: sitemap } = await import('../sitemap');
    const { routing } = await import('@/i18n/routing');
    const entries = await sitemap();
    const landing = entries.filter((entry) => entry.url === SITE || routing.locales.some((locale) => entry.url === `${SITE}/${locale}`));

    expect(landing).toHaveLength(routing.locales.length);
    for (const entry of landing) {
      expect(Object.keys(entry.alternates?.languages ?? {}).sort()).toEqual([...routing.locales, 'x-default'].sort());
      expect(entry.alternates?.languages?.en).toBe(SITE);
      expect(entry.alternates?.languages?.['x-default']).toBe(SITE);
      expect(entry.priority).toBe(1.0);
    }
  });

  it('emits the /compare hub and one entry per comparison page', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations();
    mockVideos();
    const { default: sitemap } = await import('../sitemap');
    const { COMPARISONS } = await import('../compare/_lib/comparisons');
    const entries = await sitemap();
    const urls = entries.map((e) => e.url);

    expect(urls).toContain(`${SITE}/compare`);
    for (const comparison of COMPARISONS) {
      expect(urls).toContain(`${SITE}/compare/${comparison.slug}`);
    }
    // Comparison pages are a primary SEO surface: above sub-pages, below the landing.
    expect(entries.find((e) => e.url === `${SITE}/compare/n8n-alternative`)?.priority).toBe(0.8);
  });

  it('advertises no blog URL, the section having been deleted', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations();
    mockVideos();
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    // The blog routes, their content registry and their assets are gone, so
    // every /blog URL now 404s. One listed here would be a dead entry.
    // Assert the walk produced a real sitemap first: "contains no /blog" is
    // vacuously true of an empty list, so without this the case could pass
    // while asserting nothing.
    expect(urls.length).toBeGreaterThan(10);
    expect(urls.some((url) => url.includes('/blog'))).toBe(false);
  });

});

describe('sitemap - marketplace listings', () => {
  beforeEach(() => vi.resetModules());

  it('emits the marketplace hub plus one entry per indexable listing', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([listing(), listing({ id: 'pub-2', publicSlug: 'expense-sorter' })]);
    mockIntegrations();
    mockVideos();
    const { default: sitemap } = await import('../sitemap');
    const entries = await sitemap();
    const urls = entries.map((e) => e.url);

    expect(urls).toContain(`${SITE}/marketplace`);
    expect(urls).toContain(`${SITE}/marketplace/invoice-bot`);
    expect(urls).toContain(`${SITE}/marketplace/expense-sorter`);
  });

  it('uses the listing updatedAt as lastModified so crawlers see real freshness', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([listing()]);
    mockIntegrations();
    mockVideos();
    const { default: sitemap } = await import('../sitemap');
    const entry = (await sitemap()).find((e) => e.url === `${SITE}/marketplace/invoice-bot`);

    expect(entry?.lastModified).toEqual(new Date('2026-07-02T10:00:00Z'));
  });

  it('omits a listing whose description is too thin to index', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([listing({ publicSlug: 'thin-app', description: 'too short' })]);
    mockIntegrations();
    mockVideos();
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    // The sitemap and the page's robots meta read the SAME predicate. Listing a
    // noindex URL here would advertise a page that then refuses indexing.
    expect(urls).not.toContain(`${SITE}/marketplace/thin-app`);
  });

  it('omits a listing that has no slug yet', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([listing({ publicSlug: null })]);
    mockIntegrations();
    mockVideos();
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    expect(urls.some((url) => url.startsWith(`${SITE}/marketplace/`))).toBe(false);
  });

  it('still emits the in-repo sections when the marketplace read fails', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace([], true);
    mockIntegrations();
    mockVideos();
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    // A gateway blip must not empty the sitemap of the landing and docs.
    expect(urls).toContain(SITE);
    expect(urls).toContain('https://docs.livecontext.ai');
    expect(urls).toContain(`${SITE}/marketplace`);
  });
});

describe('sitemap - integrations', () => {
  beforeEach(() => vi.resetModules());

  it('emits the directory plus one entry per indexable integration', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations([integration(), integration({ slug: 'github', name: 'GitHub' })]);
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    expect(urls).toContain(`${SITE}/integrations`);
    expect(urls).toContain(`${SITE}/integrations/slack`);
    expect(urls).toContain(`${SITE}/integrations/github`);
  });

  it('omits an integration too thin to index', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations([integration({ slug: 'tiny', toolCount: 1, description: 'An API.' })]);
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    // The sitemap and the page's robots meta read the SAME predicate. Listing a
    // noindex URL here would advertise a page that then refuses indexing.
    expect(urls).not.toContain(`${SITE}/integrations/tiny`);
    // The directory itself stays, whatever the catalog holds.
    expect(urls).toContain(`${SITE}/integrations`);
  });

  it('omits an integration whose slug is not the shape the page accepts', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations([integration({ slug: 'Not A Slug' }), integration({ slug: 'github' })]);
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    // `fetchIntegration` rejects a malformed slug locally, before any gateway call,
    // so advertising one here would be a sitemap entry whose own page 404s.
    expect(urls).toContain(`${SITE}/integrations/github`);
    expect(urls.some((url) => url.includes('Not A Slug'))).toBe(false);
    expect(urls.filter((url) => url.startsWith(`${SITE}/integrations/`))).toHaveLength(1);
  });

  it('still emits the in-repo sections when the catalog read fails', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations([], true);
    const { default: sitemap } = await import('../sitemap');
    const urls = (await sitemap()).map((e) => e.url);

    expect(urls).toContain(SITE);
    expect(urls).toContain(`${SITE}/integrations`);
  });
});

describe('sitemap - product films', () => {
  beforeEach(() => vi.resetModules());

  async function sitemapWith(videos: unknown[]) {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockMarketplace();
    mockIntegrations();
    mockVideos(videos);
    const { default: sitemap } = await import('../sitemap');
    return sitemap();
  }

  it('emits the library and one entry per published film', async () => {
    const entries = await sitemapWith([videoRow(), videoRow({ slug: 'automate-y' })]);
    const urls = entries.map((e) => e.url);

    expect(urls).toContain(`${SITE}/videos`);
    expect(urls).toContain(`${SITE}/videos/automate-x`);
    expect(urls).toContain(`${SITE}/videos/automate-y`);
  });

  it('advertises nothing when the library could not be read', async () => {
    // The read degrades to an empty list, so the section loses itself rather
    // than failing the whole sitemap.
    const entries = await sitemapWith([]);
    const urls = entries.map((e) => e.url);

    expect(urls).toContain(`${SITE}/videos`);
    expect(urls.some((url) => url.startsWith(`${SITE}/videos/`))).toBe(false);
  });

  it('carries the video block Google reads, for EVERY film', async () => {
    const films = [videoRow(), videoRow({ slug: 'automate-y', youtubeId: 'kX9pQ2mL7bT' })];
    const entries = await sitemapWith(films);

    for (const film of films) {
      const entry = entries.find((e) => e.url === `${SITE}/videos/${film.slug}`);
      expect(entry, film.slug).toBeDefined();
      expect(entry!.videos, film.slug).toHaveLength(1);
      const video = entry!.videos![0];
      expect(video.title).toBe(film.title);
      // Absolute, on our domain, and the JPEG: a relative thumbnail_loc is
      // dropped and not every consumer of this block reads WebP.
      expect(video.thumbnail_loc).toBe(film.shareImage);
      expect(video.thumbnail_loc.startsWith(`${SITE}/`)).toBe(true);
      expect(video.thumbnail_loc.endsWith('.jpg')).toBe(true);
      // The full string, not `toContain(id)`, which passes on a null id.
      expect(video.player_loc).toBe(`https://www.youtube.com/embed/${film.youtubeId}`);
      expect(video.duration).toBe(film.durationSeconds);
      expect(video.publication_date).toBe(film.publishedAt);
      // Google drops a video entry that claims neither, so they are not decoration.
      expect(video.family_friendly).toBe('yes');
      expect(video.live).toBe('no');
      // The film's own date, not the crawl's.
      expect(new Date(entry!.lastModified as Date).toISOString())
        .toBe(new Date(film.publishedAt).toISOString());
    }
  });
});

describe('sitemap - community edition', () => {
  beforeEach(() => vi.resetModules());

  it('is empty on a self-hosted edition (never indexed)', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: true }));
    mockMarketplace([listing()]);
    mockIntegrations();
    mockVideos();
    const { default: sitemap } = await import('../sitemap');

    // Even with a full catalog available, a self-hosted install advertises
    // nothing: robots.ts already disallows everything there.
    expect(await sitemap()).toEqual([]);
  });
});

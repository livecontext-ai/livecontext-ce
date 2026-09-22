import { describe, it, expect, vi, beforeEach } from 'vitest';
import { existsSync, readdirSync } from 'fs';
import { join } from 'path';
import { SITEMAP_PATHS, sitemapUrls } from '@/lib/seo/sitemaps';

const SITE = 'https://livecontext.ai';
const APP_DIR = join(__dirname, '..');

function listing(slug: string) {
  return {
    id: `pub-${slug}`,
    publicSlug: slug,
    title: slug,
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
  };
}

function integration(slug: string) {
  return {
    slug,
    name: slug,
    description: 'Post messages, manage channels and read conversations.',
    iconSlug: slug,
    iconUrl: null,
    toolCount: 45,
    authType: 'oauth2',
  };
}

function film(slug: string) {
  return {
    slug,
    title: `Automate ${slug}`,
    tagline: 'A five minute film.',
    youtubeId: 'lG-wfKo2NOo',
    durationSeconds: 318,
    publishedAt: '2026-09-10T15:57:19Z',
    poster: `${SITE}/videos/${slug}.webp`,
    shareImage: `${SITE}/videos/${slug}.jpg`,
    posterAlt: 'A screen',
    problem: [],
    answer: [],
    highlights: [],
    chapters: [],
    transcript: [],
    marketplaceSlug: 'x-app',
    marketplaceTitle: 'X App',
  };
}

/** One catalog, served to both the complete sitemap and the section files. */
function mockCatalog() {
  vi.doMock('@/lib/marketplace/publicPublications', () => ({
    fetchAllPublicPublications: vi.fn().mockResolvedValue({
      publications: [listing('invoice-bot'), listing('candidate-screening')],
      truncated: false,
    }),
  }));
  vi.doMock('@/lib/integrations/publicIntegrations', () => ({
    fetchAllIntegrations: vi.fn().mockResolvedValue({
      integrations: [integration('slack'), integration('notion')],
      truncated: false,
    }),
  }));
  vi.doMock('@/app/videos/_lib/publicVideos', () => ({
    fetchPublishedVideosOrEmpty: vi.fn().mockResolvedValue([
      film('automate-client-invoicing'),
      film('automate-candidate-screening'),
    ]),
  }));
}

describe('section sitemaps - published as files AND inside the complete one', () => {
  beforeEach(() => vi.resetModules());

  // The whole reason the sections exist as separate files is per-section
  // reporting. Two hand-written copies of the same list would drift within a
  // release; this asserts they are the same list, entry for entry.
  it.each([
    ['marketplace', () => import('../marketplace/sitemap')],
    ['integrations', () => import('../integrations/sitemap')],
    ['videos', () => import('../videos/sitemap')],
    ['compare', () => import('../compare/sitemap')],
    ['personas', () => import('../for/sitemap')],
  ])('the %s section appears in the complete sitemap with every field identical', async (_name, load) => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockCatalog();

    const { default: section } = await load();
    const { default: complete } = await import('../sitemap');

    const sectionEntries = await section();
    const byUrl = new Map((await complete()).map((entry) => [entry.url, entry]));

    expect(sectionEntries.length).toBeGreaterThan(1);
    for (const entry of sectionEntries) {
      const twin = byUrl.get(entry.url);
      expect(twin, `${entry.url} is in the section file but not in /sitemap.xml`).toBeDefined();
      // Not just the URL: a section that advertised a different priority,
      // changefreq or video block than the complete sitemap would be two
      // contradictory claims about the same page.
      expect(twin).toEqual(entry);
    }
  });

  it('uses source dates only and does not make unchanged pages look freshly edited', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockCatalog();
    const { default: complete } = await import('../sitemap');
    const entries = await complete();
    const dated = entries.filter((entry) => entry.lastModified !== undefined);
    expect(dated.map((entry) => entry.url).sort()).toEqual([
      SITE + '/marketplace/invoice-bot',
      SITE + '/marketplace/candidate-screening',
      SITE + '/videos/automate-client-invoicing',
      SITE + '/videos/automate-candidate-screening',
    ].sort());
    expect(entries.find((entry) => entry.url === SITE + '/marketplace/invoice-bot')?.lastModified)
      .toEqual(new Date('2026-07-02T10:00:00Z'));
    expect(entries.find((entry) => entry.url === SITE + '/videos/automate-client-invoicing')?.lastModified)
      .toEqual(new Date('2026-09-10T15:57:19Z'));
  });

  it('the film entries carry the video block, in the section file as well', async () => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    mockCatalog();

    const { default: videos } = await import('../videos/sitemap');
    const entries = await videos();
    const film = entries.find((entry) => entry.url.endsWith('/automate-client-invoicing'));

    // Without this block the page is a page; with it, it is eligible for video
    // results. It is the only reason this section is worth splitting out.
    expect(film?.videos?.[0]).toMatchObject({
      player_loc: 'https://www.youtube.com/embed/lG-wfKo2NOo',
      thumbnail_loc: `${SITE}/videos/automate-client-invoicing.jpg`,
      duration: 318,
      family_friendly: 'yes',
      live: 'no',
    });
  });

  it.each([
    ['marketplace', () => import('../marketplace/sitemap')],
    ['integrations', () => import('../integrations/sitemap')],
    ['videos', () => import('../videos/sitemap')],
    ['compare', () => import('../compare/sitemap')],
    ['personas', () => import('../for/sitemap')],
  ])('the %s section is empty on a self-hosted edition', async (_name, load) => {
    vi.doMock('@/lib/edition', () => ({ IS_CE: true }));
    mockCatalog();

    const { default: section } = await load();
    expect(await section()).toEqual([]);
  });

  it.each([
    ['marketplace', '@/lib/marketplace/publicPublications', 'fetchAllPublicPublications', 'publications',
      () => import('../marketplace/sitemap')],
    ['integration', '@/lib/integrations/publicIntegrations', 'fetchAllIntegrations', 'integrations',
      () => import('../integrations/sitemap')],
  ])('warns when the %s walk stopped early, instead of shipping a partial list silently', async (
    name, modulePath, fn, key, load,
  ) => {
    // A truncated walk is a sitemap that quietly claims the catalog is smaller
    // than it is. It still has to SHIP (a partial sitemap beats none), so the
    // warning is the only signal that it happened.
    // Registers its OWN mocks rather than layering onto `mockCatalog()`:
    // `vi.doMock` keeps the FIRST registration for a path, so the helper's
    // non-truncated default would win and this test would pass on nothing.
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    vi.doMock(modulePath, () => ({ [fn]: vi.fn().mockResolvedValue({ [key]: [], truncated: true }) }));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const { default: section } = await load();
    const entries = await section();

    expect(warn).toHaveBeenCalledOnce();
    expect(String(warn.mock.calls[0][0])).toContain(name);
    expect(String(warn.mock.calls[0][0])).toContain('incomplete');
    // The index page still ships: losing the section entirely is worse.
    expect(entries).toHaveLength(1);
    warn.mockRestore();
  });

  it('still advertises /videos when the film library cannot be read at all', () => {
    // The film section has no `truncated` flag: `fetchPublishedVideosOrEmpty`
    // swallows an unreachable gateway and returns nothing. That must cost the
    // FILMS and not the library page, which is a real page either way.
    vi.doMock('@/lib/edition', () => ({ IS_CE: false }));
    vi.doMock('@/app/videos/_lib/publicVideos', () => ({
      fetchPublishedVideosOrEmpty: vi.fn().mockResolvedValue([]),
    }));

    return import('../videos/sitemap').then(async ({ default: videos }) => {
      const entries = await videos();
      expect(entries).toHaveLength(1);
      expect(entries[0].url).toBe(`${SITE}/videos`);
      // And it must not claim a video block it has no film for.
      expect(entries[0].videos).toBeUndefined();
    });
  });
});

/** Every `sitemap.ts` under `app/`, as a declarable path: `app/sitemap.ts` is `/sitemap.xml`. */
function sitemapRoutesOnDisk(dir = APP_DIR, prefix = ''): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isFile() && /^sitemap\.(t|j)sx?$/.test(entry.name)) {
      found.push(`${prefix}/sitemap.xml`);
    } else if (entry.isDirectory() && !entry.name.startsWith('_') && entry.name !== '__tests__') {
      found.push(...sitemapRoutesOnDisk(join(dir, entry.name), `${prefix}/${entry.name}`));
    }
  }
  return found;
}

describe('the declared sitemap list', () => {
  // robots.txt advertises these paths. A path with no route behind it is a 404
  // handed to every search engine, and nothing else would report it.
  it('names only sitemaps that have a route', () => {
    const missing = SITEMAP_PATHS.filter((path) => {
      const route = path === '/sitemap.xml'
        ? join(APP_DIR, 'sitemap.ts')
        : join(APP_DIR, path.replace('/sitemap.xml', ''), 'sitemap.ts');
      return !existsSync(route);
    });

    expect(missing, `declared in lib/seo/sitemaps.ts with no route behind them: ${missing.join(', ')}`)
      .toEqual([]);
  });

  it('names EVERY sitemap route on disk, so a new section cannot go unadvertised', () => {
    // The reverse of the check above, and the one that actually rots: adding
    // `app/models/sitemap.ts` without touching the list leaves a sitemap that
    // builds, serves, and is advertised to nobody.
    expect([...SITEMAP_PATHS].sort()).toEqual(sitemapRoutesOnDisk().sort());
  });

  it('gates every sitemap route on the edition and renders it per request', async () => {
    // Each route re-declares these two lines. A section that forgot the CE gate
    // would advertise livecontext.ai URLs from a self-hosted install; one that
    // forgot `force-dynamic` would be frozen at build with an EMPTY catalog,
    // which is the failure `app/sitemap.ts` documents as measured in production.
    for (const [name, load] of [
      ['/sitemap.xml', () => import('../sitemap')],
      ['/marketplace/sitemap.xml', () => import('../marketplace/sitemap')],
      ['/integrations/sitemap.xml', () => import('../integrations/sitemap')],
      ['/videos/sitemap.xml', () => import('../videos/sitemap')],
      ['/compare/sitemap.xml', () => import('../compare/sitemap')],
      ['/for/sitemap.xml', () => import('../for/sitemap')],
    ] as const) {
      vi.resetModules();
      vi.doMock('@/lib/edition', () => ({ IS_CE: true }));
      mockCatalog();
      const mod = await load();
      expect((mod as { dynamic?: string }).dynamic, name).toBe('force-dynamic');
      expect(await mod.default(), name).toEqual([]);
    }
  });

  it('builds absolute URLs on the site origin', () => {
    for (const url of sitemapUrls(SITE)) {
      expect(url.startsWith(`${SITE}/`)).toBe(true);
      expect(url.endsWith('/sitemap.xml')).toBe(true);
    }
  });
});

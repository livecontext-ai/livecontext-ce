import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The read the whole section stands on, and the guard in front of it.
 *
 * <p>Rows here are written by whoever edits the library, so this mapper is the
 * last thing between a cell and a public page: it refuses a row that could not
 * make a page, and it refuses it AGAIN after the backend already did, because
 * the two are deployed separately and the page must not depend on which version
 * of the other one is running.
 */
vi.mock('server-only', () => ({}));
vi.mock('@/lib/marketplace/publicPublications', () => ({
  gatewayBaseUrl: () => 'http://gateway.test',
}));

import {
  VideoLibraryUnavailableError,
  fetchPublishedVideos,
  fetchPublishedVideosOrEmpty,
  fetchVideo,
  fetchVideoForMarketplaceSlug,
} from '../publicVideos';

const fetchMock = vi.fn();

function film(overrides: Record<string, unknown> = {}) {
  return {
    slug: 'automate-x',
    series: 'LiveContext product films',
    title: 'Automate X, end to end',
    tagline: 'A five minute film about X.',
    youtubeId: 'lG-wfKo2NOo',
    durationSeconds: 318,
    publishedAt: '2026-09-10T15:57:19Z',
    posterUrl: 'https://livecontext.ai/videos/automate-x.webp',
    shareImageUrl: 'https://livecontext.ai/videos/automate-x.jpg',
    posterAlt: 'The X screen',
    problem: ['The problem.'],
    answer: ['The answer.'],
    highlights: ['A claim'],
    chapters: [{ start: 0, end: 15.4, title: 'The problem' }],
    transcript: [{ t: 0.15, text: 'You posted the role.' }],
    marketplaceSlug: 'x-app',
    marketplaceTitle: 'X App',
    ...overrides,
  };
}

/** What the gateway answers. */
function serving(videos: unknown[], status = 200) {
  fetchMock.mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    json: async () => ({ videos, count: videos.length, configured: true }),
  });
}

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock);
  fetchMock.mockReset();
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('reading the library', () => {
  it('maps a film into the shape the pages render', async () => {
    serving([film()]);

    const [video] = await fetchPublishedVideos();

    expect(video.slug).toBe('automate-x');
    expect(video.youtubeId).toBe('lG-wfKo2NOo');
    expect(video.durationSeconds).toBe(318);
    expect(video.poster).toBe('https://livecontext.ai/videos/automate-x.webp');
    expect(video.shareImage).toBe('https://livecontext.ai/videos/automate-x.jpg');
    expect(video.chapters).toEqual([{ start: 0, end: 15.4, title: 'The problem' }]);
    expect(video.transcript).toEqual([{ t: 0.15, text: 'You posted the role.' }]);
    expect(video.marketplaceSlug).toBe('x-app');
  });

  it('asks the anonymous endpoint, with no credentials and a bounded wait', async () => {
    serving([film()]);

    await fetchPublishedVideos();

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('http://gateway.test/api/public/videos');
    expect(init.headers).toEqual({ Accept: 'application/json' });
    // A gateway that accepts the connection and never answers is the case a
    // try/catch cannot see.
    expect(init.signal).toBeDefined();
    expect(init.next.revalidate).toBe(3600);
    expect(JSON.stringify(init)).not.toMatch(/authorization|cookie/i);
  });

  it('serves a stale copy for no longer than the window the caller asks for', async () => {
    serving([film()]);

    await fetchPublishedVideos({ revalidateSeconds: 600 });

    expect(fetchMock.mock.calls[0][1].next.revalidate).toBe(600);
  });
});

describe('rows that cannot make a page', () => {
  it('drops a film whose id is a URL instead of an id', async () => {
    // The id is pasted straight after `/embed/` on four surfaces at once, so a
    // URL there renders `.../embed/https://youtu.be/ID` everywhere and nothing
    // looks different enough to notice.
    serving([film({ youtubeId: 'https://youtu.be/lG-wfKo2NOo' })]);

    expect(await fetchPublishedVideos()).toEqual([]);
  });

  it('drops a film with no id, no title or no slug', async () => {
    serving([
      film({ slug: 'no-id', youtubeId: '' }),
      film({ slug: 'no-title', title: '   ' }),
      film({ slug: '' }),
      film(),
    ]);

    expect((await fetchPublishedVideos()).map((video) => video.slug)).toEqual(['automate-x']);
  });

  it('drops a film whose slug would not be a url', async () => {
    serving([film({ slug: '../../etc/passwd' })]);

    expect(await fetchPublishedVideos()).toEqual([]);
  });

  it('keeps the film when one of its lists is the wrong shape', async () => {
    serving([film({ chapters: 'not a list', highlights: null, transcript: [{ t: 1 }] })]);

    const [video] = await fetchPublishedVideos();

    expect(video.chapters).toEqual([]);
    expect(video.highlights).toEqual([]);
    expect(video.transcript).toEqual([]);
    expect(video.title).toBe('Automate X, end to end');
  });

  it('drops a chapter or a line with nothing to say', async () => {
    serving([film({
      chapters: [{ start: 0, end: 10, title: '' }, { start: 10, end: 20, title: 'Real' }],
      transcript: [{ t: 0, text: '  ' }, { t: 1, text: 'Real.' }],
    })]);

    const [video] = await fetchPublishedVideos();

    expect(video.chapters).toEqual([{ start: 10, end: 20, title: 'Real' }]);
    expect(video.transcript).toEqual([{ t: 1, text: 'Real.' }]);
  });
});

describe('when the library cannot be read', () => {
  it('throws, so the film page renders an error a crawler retries', async () => {
    // A 404 here would invite a search engine to drop a page that still exists.
    fetchMock.mockRejectedValue(new Error('gateway unreachable'));

    await expect(fetchPublishedVideos()).rejects.toBeInstanceOf(VideoLibraryUnavailableError);
  });

  it('throws on a gateway error status too, not just on a dead connection', async () => {
    serving([], 503);

    await expect(fetchPublishedVideos()).rejects.toBeInstanceOf(VideoLibraryUnavailableError);
  });

  it('degrades to no films for the surfaces that must lose themselves instead', async () => {
    // The index and the sitemap: an empty directory is survivable, a 500 is not.
    fetchMock.mockRejectedValue(new Error('gateway unreachable'));

    expect(await fetchPublishedVideosOrEmpty()).toEqual([]);
  });
});

describe('the film for a marketplace listing', () => {
  it('finds the film that names that listing', async () => {
    serving([film({ slug: 'other', marketplaceSlug: 'other-app' }), film()]);

    expect((await fetchVideoForMarketplaceSlug('x-app'))?.slug).toBe('automate-x');
  });

  it('returns nothing for a listing no film demonstrates', async () => {
    serving([film()]);

    expect(await fetchVideoForMarketplaceSlug('unfilmed-app')).toBeNull();
    expect(await fetchVideoForMarketplaceSlug('')).toBeNull();
  });

  it('costs the section and not the listing page when the library is down', async () => {
    // This feeds a section of someone else's page: it must degrade, never throw.
    fetchMock.mockRejectedValue(new Error('gateway unreachable'));

    expect(await fetchVideoForMarketplaceSlug('x-app')).toBeNull();
  });
});

describe('one film', () => {
  it('returns the published film at that slug', async () => {
    serving([film(), film({ slug: 'automate-y' })]);

    expect((await fetchVideo('automate-y'))?.slug).toBe('automate-y');
  });

  it('returns null for a slug the library does not publish', async () => {
    serving([film()]);

    expect(await fetchVideo('no-such-film')).toBeNull();
  });

  it('throws rather than returning null when it could not ask', async () => {
    fetchMock.mockRejectedValue(new Error('gateway unreachable'));

    await expect(fetchVideo('automate-x')).rejects.toBeInstanceOf(VideoLibraryUnavailableError);
  });
});

import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchReleases, mapReleases, releasesApiUrl } from '../githubReleases';

const release = (over: Record<string, unknown> = {}) => ({
  tag_name: 'v0.1.5',
  name: 'LiveContext CE v0.1.5',
  published_at: '2026-07-02T12:30:00Z',
  html_url: 'https://github.com/livecontext-ai/livecontext-ce/releases/tag/v0.1.5',
  body: '## What is new\n\n- something',
  draft: false,
  prerelease: false,
  ...over,
});

describe('releasesApiUrl', () => {
  it('derives the API endpoint from the canonical public-repo constant', () => {
    expect(releasesApiUrl()).toBe(
      'https://api.github.com/repos/livecontext-ai/livecontext-ce/releases?per_page=25',
    );
  });
});

describe('mapReleases', () => {
  it('maps a published release to the view model', () => {
    const [mapped] = mapReleases([release()]);
    expect(mapped).toEqual({
      tag: 'v0.1.5',
      title: 'LiveContext CE v0.1.5',
      publishedAt: '2026-07-02T12:30:00Z',
      body: '## What is new\n\n- something',
      htmlUrl: 'https://github.com/livecontext-ai/livecontext-ce/releases/tag/v0.1.5',
    });
  });

  it('drops drafts and prereleases (only published versions belong in the changelog)', () => {
    const mapped = mapReleases([
      release({ draft: true, tag_name: 'v9.9.9-draft' }),
      release({ prerelease: true, tag_name: 'v9.9.9-rc1' }),
      release(),
    ]);
    expect(mapped.map((r) => r.tag)).toEqual(['v0.1.5']);
  });

  it('sorts newest-first regardless of API ordering', () => {
    const mapped = mapReleases([
      release({ tag_name: 'v0.1.3', published_at: '2026-06-30T10:00:00Z' }),
      release({ tag_name: 'v0.1.5', published_at: '2026-07-02T12:30:00Z' }),
      release({ tag_name: 'v0.1.4', published_at: '2026-07-01T09:00:00Z' }),
    ]);
    expect(mapped.map((r) => r.tag)).toEqual(['v0.1.5', 'v0.1.4', 'v0.1.3']);
  });

  it('falls back to the tag when the release has no name and floors a missing body to empty', () => {
    const [mapped] = mapReleases([release({ name: '', body: undefined })]);
    expect(mapped.title).toBe('v0.1.5');
    expect(mapped.body).toBe('');
  });

  it('skips malformed entries and tolerates a non-array payload', () => {
    expect(mapReleases({ message: 'API rate limit exceeded' })).toEqual([]);
    expect(mapReleases(null)).toEqual([]);
    expect(
      mapReleases([null, 42, release({ tag_name: undefined }), release({ html_url: undefined }), release()]),
    ).toHaveLength(1);
  });
});

describe('fetchReleases', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('returns the mapped releases on a 200 response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => [release()],
    }));

    const releases = await fetchReleases(60);

    expect(releases).toHaveLength(1);
    expect(releases[0].tag).toBe('v0.1.5');
  });

  it('degrades to an empty list on a non-200 response (rate limit must not break the landing)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403 }));

    expect(await fetchReleases(60)).toEqual([]);
  });

  it('degrades to an empty list when the network call throws', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')));

    expect(await fetchReleases(60)).toEqual([]);
  });
});

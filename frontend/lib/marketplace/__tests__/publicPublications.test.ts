import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// `server-only` throws outside a React Server Component; stub it for the unit
// test (same approach as i18n/__tests__/resolveRequestLocale.test.ts).
vi.mock('server-only', () => ({}));

import {
  fetchAllPublicPublications,
  PUBLIC_MARKETPLACE_REVALIDATE_SECONDS,
  fetchPublicationBySlug,
  fetchShowcaseRender,
  fetchPublicationReviews,
  fetchVerifiedPublisherHandles,
  gatewayBaseUrl,
  mapPublication,
  mapPublications,
} from '../publicPublications';

const ORIGINAL_ENV = { ...process.env };

function restoreEnv() {
  process.env = { ...ORIGINAL_ENV };
}

/** Minimal backend row: everything the mapper treats as required. */
function rawRow(overrides: Record<string, unknown> = {}) {
  return {
    id: '0189d3c2-7f4a-4c11-9b3e-2a5d6e7f8a9b',
    title: 'Invoice Bot',
    description: 'Chases unpaid invoices.',
    publicSlug: 'invoice-bot',
    publisherName: 'John Doe',
    publisherId: '42',
    publisherHandle: 'john-doe',
    publisherAvatarUrl: 'avatar-uuid',
    category: { slug: 'automation', name: 'Automation', color: '#6366f1' },
    averageRating: 4.5,
    reviewCount: 12,
    useCount: 42,
    publishedAt: '2026-07-01T10:00:00Z',
    updatedAt: '2026-07-02T10:00:00Z',
    publicationType: 'WORKFLOW',
    displayMode: 'APPLICATION',
    creditsPerUse: 3,
    hasShowcase: true,
    nodeIcons: [{ isMcp: true, iconSlug: 'xai' }],
    agentCount: 1,
    interfaceCount: 2,
    workflowCount: 3,
    skillCount: 4,
    datasourceCount: 5,
    planSnapshot: { cores: [] },
    ...overrides,
  };
}

describe('gatewayBaseUrl', () => {
  afterEach(restoreEnv);

  it('prefers the runtime-injected GATEWAY_SERVICE_URL', () => {
    // NEXT_PUBLIC_* is inlined at build time; the non-public var is injected
    // into the pod at runtime and must win so a service-DNS change needs no
    // rebuild.
    process.env.GATEWAY_SERVICE_URL = 'http://in-cluster-gateway:8080';
    process.env.NEXT_PUBLIC_SPRING_BASE_URL = 'http://baked-at-build:8080';

    expect(gatewayBaseUrl()).toBe('http://in-cluster-gateway:8080');
  });

  it('falls back to NEXT_PUBLIC_SPRING_BASE_URL when the runtime var is absent', () => {
    delete process.env.GATEWAY_SERVICE_URL;
    process.env.NEXT_PUBLIC_SPRING_BASE_URL = 'http://baked-at-build:8080';

    expect(gatewayBaseUrl()).toBe('http://baked-at-build:8080');
  });

  it('falls back to localhost when neither is set', () => {
    delete process.env.GATEWAY_SERVICE_URL;
    delete process.env.NEXT_PUBLIC_SPRING_BASE_URL;

    expect(gatewayBaseUrl()).toBe('http://localhost:8080');
  });

  it('ignores an empty variable rather than producing a URL with no host', () => {
    process.env.GATEWAY_SERVICE_URL = '';
    process.env.NEXT_PUBLIC_SPRING_BASE_URL = 'http://baked-at-build:8080';

    expect(gatewayBaseUrl()).toBe('http://baked-at-build:8080');
  });
});

describe('mapPublication', () => {
  it('maps a complete row', () => {
    const item = mapPublication(rawRow());

    expect(item).toEqual({
      id: '0189d3c2-7f4a-4c11-9b3e-2a5d6e7f8a9b',
      publicSlug: 'invoice-bot',
      title: 'Invoice Bot',
      description: 'Chases unpaid invoices.',
      publisherName: 'John Doe',
      publisherId: '42',
      publisherHandle: 'john-doe',
      publisherAvatarUrl: 'avatar-uuid',
      categorySlug: 'automation',
      categoryName: 'Automation',
      averageRating: 4.5,
      reviewCount: 12,
      useCount: 42,
      publishedAt: '2026-07-01T10:00:00Z',
      updatedAt: '2026-07-02T10:00:00Z',
      publicationType: 'WORKFLOW',
      categoryColor: '#6366f1',
      displayMode: 'APPLICATION',
      creditsPerUse: 3,
      hasShowcase: true,
      nodeIcons: [{ isMcp: true, iconSlug: 'xai' }],
      agentCount: 1,
      interfaceCount: 2,
      workflowCount: 3,
      skillCount: 4,
      datasourceCount: 5,
      planSnapshot: { cores: [] },
    });
  });

  it('drops a row with no id: it cannot be addressed by a page', () => {
    expect(mapPublication(rawRow({ id: undefined }))).toBeNull();
  });

  it('drops a row with no title: an indexed page needs one', () => {
    expect(mapPublication(rawRow({ title: '' }))).toBeNull();
  });

  it('keeps a row whose slug is still null (predates the backfill)', () => {
    // Such a row is reachable by UUID and must not vanish from the listing.
    const item = mapPublication(rawRow({ publicSlug: null }));

    expect(item?.publicSlug).toBeNull();
    expect(item?.id).toBeTruthy();
  });

  it('treats a missing publisher handle as absent, never as the string "null"', () => {
    // The handle goes into a /u/{handle} URL, so a stringified null would
    // produce a link to /u/null.
    const item = mapPublication(rawRow({ publisherHandle: null }));

    expect(item?.publisherHandle).toBeNull();
  });

  it('survives a row with no category object', () => {
    const item = mapPublication(rawRow({ category: null }));

    expect(item?.categorySlug).toBeNull();
    expect(item?.categoryName).toBeNull();
  });

  it('coerces non-numeric metrics to 0 rather than emitting NaN into the markup', () => {
    const item = mapPublication(rawRow({ averageRating: null, reviewCount: 'x', useCount: undefined }));

    expect(item?.averageRating).toBe(0);
    expect(item?.reviewCount).toBe(0);
    expect(item?.useCount).toBe(0);
  });

  it('defaults an absent publicationType to WORKFLOW', () => {
    expect(mapPublication(rawRow({ publicationType: undefined }))?.publicationType).toBe('WORKFLOW');
  });

  it.each([null, undefined, 'a string', 42, []])('returns null for a non-object payload (%s)', (input) => {
    expect(mapPublication(input)).toBeNull();
  });
});

describe('mapPublications', () => {
  it('maps the publications array', () => {
    const items = mapPublications({ publications: [rawRow(), rawRow({ id: 'other-id' })] });

    expect(items).toHaveLength(2);
  });

  it('skips unusable rows but keeps the rest', () => {
    const items = mapPublications({ publications: [rawRow(), { title: 'no id' }, rawRow({ id: 'x' })] });

    expect(items).toHaveLength(2);
  });

  it.each([
    ['a non-array publications field', { publications: 'nope' }],
    ['a missing publications field', { count: 0 }],
    ['a non-object payload', 'nope'],
    ['null', null],
  ])('returns an empty list for %s', (_label, payload) => {
    // A backend shape change must degrade to an empty page, not a 500 on a
    // public URL.
    expect(mapPublications(payload)).toEqual([]);
  });
});

describe('the shared gateway read', () => {
  beforeEach(() => {
    process.env.GATEWAY_SERVICE_URL = 'http://gw:8080';
  });
  afterEach(() => {
    restoreEnv();
    vi.unstubAllGlobals();
  });

  it('calls the gateway directly, with ISR caching and no credentials', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ publications: [rawRow()] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const { publications } = await fetchAllPublicPublications();

    expect(publications).toHaveLength(1);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('http://gw:8080/api/publications/marketplace?page=0&size=100');
    // Never through the Next proxy: that would be an HTTP hop to ourselves.
    expect(url).not.toContain('/api/proxy');
    expect(init.next).toEqual({ revalidate: PUBLIC_MARKETPLACE_REVALIDATE_SECONDS });
    // These endpoints are anonymous by design; sending credentials from a
    // shared server process would be both useless and a cross-request hazard.
    expect(init.headers).toEqual({ Accept: 'application/json' });
    expect(init.credentials).toBeUndefined();
  });

  it('forwards the caller revalidate window and page size', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ publications: [] }) });
    vi.stubGlobal('fetch', fetchMock);

    await fetchAllPublicPublications({ pageSize: 10, revalidateSeconds: 60 });

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toContain('page=0&size=10');
    expect(init.next).toEqual({ revalidate: 60 });
  });

  it('arms an abort signal, so a gateway that never answers cannot hang the render', async () => {
    // A page that awaits this read is on the critical path of a public page and,
    // for the landing, of the BUILD. A refused connection is caught by the
    // try/catch; a socket that simply never answers is not, and that is the
    // failure that would stall a render indefinitely. What is asserted here is
    // that the signal is ARMED - a real elapsed timeout is the platform's job,
    // and the abort it raises is covered by the rejection case below, which is
    // the same code path.
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ publications: [] }) });
    vi.stubGlobal('fetch', fetchMock);

    await fetchAllPublicPublications();

    const [, init] = fetchMock.mock.calls[0];
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it('reports nothing readable when the read is aborted', async () => {
    // What the timeout above actually produces. Distinct from a 503: an abort
    // never yields a Response at all, so it takes the catch, not `!res.ok`.
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(
      Object.assign(new Error('The operation was aborted'), { name: 'TimeoutError' }),
    ));

    await expect(fetchAllPublicPublications()).resolves.toEqual({ publications: [], truncated: true });
  });

  it('reports nothing readable when the gateway refuses the connection', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));

    await expect(fetchAllPublicPublications()).resolves.toEqual({ publications: [], truncated: true });
  });

  it('returns nothing readable on a non-200 response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503, json: async () => ({}) }));

    await expect(fetchAllPublicPublications()).resolves.toEqual({ publications: [], truncated: true });
  });

  it('returns nothing readable when the body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => {
        throw new SyntaxError('Unexpected token <');
      },
    }));

    await expect(fetchAllPublicPublications()).resolves.toEqual({ publications: [], truncated: true });
  });
});

describe('fetchAllPublicPublications', () => {
  beforeEach(() => {
    process.env.GATEWAY_SERVICE_URL = 'http://gw:8080';
  });
  afterEach(() => {
    restoreEnv();
    vi.unstubAllGlobals();
  });

  it('walks pages until a short page ends the catalog', async () => {
    const full = Array.from({ length: 100 }, (_, i) => rawRow({ id: `pub-${i}` }));
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ publications: full }) })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ publications: [rawRow({ id: 'last' })] }) });
    vi.stubGlobal('fetch', fetchMock);

    const { publications, truncated } = await fetchAllPublicPublications();

    expect(publications).toHaveLength(101);
    expect(truncated).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('stops after a single short page without a second request', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ publications: [rawRow()] }) });
    vi.stubGlobal('fetch', fetchMock);

    const { publications, truncated } = await fetchAllPublicPublications();

    expect(publications).toHaveLength(1);
    expect(truncated).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('reports truncation when the page cap is reached', async () => {
    // Every page comes back full, so the catalog never signals its end.
    const full = Array.from({ length: 10 }, (_, i) => rawRow({ id: `pub-${i}` }));
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ publications: full }) }));

    const { publications, truncated } = await fetchAllPublicPublications({ pageSize: 10, maxPages: 3 });

    // Truncation MUST be visible: a partial sitemap that looks complete hides
    // pages from search engines with no signal at all.
    expect(publications).toHaveLength(30);
    expect(truncated).toBe(true);
  });

  it('reports truncation and keeps what it has when a page read fails', async () => {
    const full = Array.from({ length: 100 }, (_, i) => rawRow({ id: `pub-${i}` }));
    vi.stubGlobal('fetch', vi
      .fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ publications: full }) })
      .mockResolvedValueOnce({ ok: false, status: 502, json: async () => ({}) }));

    const { publications, truncated } = await fetchAllPublicPublications();

    // Continuing past a failed page would leave a hole in the middle of the
    // catalog while still reporting success.
    expect(publications).toHaveLength(100);
    expect(truncated).toBe(true);
  });

  it('returns an empty, truncated result when the very first page fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));

    await expect(fetchAllPublicPublications()).resolves.toEqual({ publications: [], truncated: true });
  });
});

describe('fetchPublicationBySlug', () => {
  beforeEach(() => {
    process.env.GATEWAY_SERVICE_URL = 'http://gw:8080';
  });
  afterEach(() => {
    restoreEnv();
    vi.unstubAllGlobals();
  });

  it('requests the by-slug endpoint and maps the detail payload', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => rawRow() });
    vi.stubGlobal('fetch', fetchMock);

    const item = await fetchPublicationBySlug('invoice-bot');

    expect(item?.title).toBe('Invoice Bot');
    expect(fetchMock.mock.calls[0][0]).toBe('http://gw:8080/api/publications/by-slug/invoice-bot');
  });

  it.each([
    ['a path traversal attempt', '../../internal/secrets'],
    ['an uppercase slug', 'Invoice-Bot'],
    ['a slug with a slash', 'invoice/bot'],
    ['a slug with a query string', 'invoice-bot?x=1'],
    ['a double hyphen', 'invoice--bot'],
    ['a leading hyphen', '-invoice-bot'],
    ['a trailing hyphen', 'invoice-bot-'],
    ['an over-long slug', `${'a'.repeat(121)}`],
    ['an empty slug', ''],
    ['a whitespace slug', '   '],
  ])('rejects %s locally, without a gateway request', async (_label, slug) => {
    // /marketplace/{slug} is a dynamic route: every URL a scanner invents would
    // otherwise become one gateway call from the SSR pod, all sharing a single
    // anonymous rate-limit bucket.
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPublicationBySlug(slug)).resolves.toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('accepts a well-formed slug at the length ceiling', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => rawRow() });
    vi.stubGlobal('fetch', fetchMock);

    await fetchPublicationBySlug('a'.repeat(120));

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('returns null for a 404 (unknown slug and non-public publication alike)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404, json: async () => ({}) }));

    await expect(fetchPublicationBySlug('nope')).resolves.toBeNull();
  });

  it.each(['', '   '])('returns null for a blank slug without calling the gateway (%s)', async (slug) => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchPublicationBySlug(slug)).resolves.toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('mapPublication - fields the public pages draw with', () => {
  it('keeps the plan, so a listing can draw its workflow', () => {
    // The whole point of carrying it: the crawlable page cannot call the
    // authenticated builder endpoints, so the published plan is its only source.
    const item = mapPublication(rawRow({ planSnapshot: { cores: [{ id: 'core:a' }] } }));

    expect(item?.planSnapshot).toEqual({ cores: [{ id: 'core:a' }] });
  });

  it('reads hasShowcase strictly, so a truthy string does not promise a preview', () => {
    // A page that believes a preview exists renders a frame for one and then
    // has nothing to put in it.
    expect(mapPublication(rawRow({ hasShowcase: 'yes' }))?.hasShowcase).toBe(false);
    expect(mapPublication(rawRow({ hasShowcase: undefined }))?.hasShowcase).toBe(false);
    expect(mapPublication(rawRow({ hasShowcase: true }))?.hasShowcase).toBe(true);
  });

  it('drops malformed icon entries instead of losing the whole icon row', () => {
    const item = mapPublication(rawRow({ nodeIcons: [{ iconSlug: 'xai' }, null, 'nope', 7] }));

    expect(item?.nodeIcons).toEqual([{ iconSlug: 'xai' }]);
  });

  it('degrades a non-array nodeIcons to an empty row', () => {
    expect(mapPublication(rawRow({ nodeIcons: 'xai' }))?.nodeIcons).toEqual([]);
  });

  it('defaults every count to zero on a row that predates them', () => {
    const item = mapPublication(rawRow({
      agentCount: undefined,
      interfaceCount: undefined,
      workflowCount: undefined,
      skillCount: undefined,
      datasourceCount: undefined,
      creditsPerUse: undefined,
    }));

    expect(item).toMatchObject({
      agentCount: 0,
      interfaceCount: 0,
      workflowCount: 0,
      skillCount: 0,
      datasourceCount: 0,
      creditsPerUse: 0,
    });
  });

  it('reads the category accent colour from the nested category object', () => {
    expect(mapPublication(rawRow())?.categoryColor).toBe('#6366f1');
    expect(mapPublication(rawRow({ category: {} }))?.categoryColor).toBeNull();
  });
});

describe('fetchShowcaseRender', () => {
  beforeEach(() => {
    process.env.GATEWAY_SERVICE_URL = 'http://gw:8080';
  });
  afterEach(() => {
    restoreEnv();
    vi.unstubAllGlobals();
  });

  const render = (overrides: Record<string, unknown> = {}) => ({
    htmlTemplate: '<div id="app"></div>',
    cssTemplate: 'body{margin:0}',
    jsTemplate: 'render()',
    format: 'vertical',
    items: [{ data: { title: 'Volcano' } }],
    ...overrides,
  });

  it('reads the anonymous showcase endpoint of that publication', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => render() });
    vi.stubGlobal('fetch', fetchMock);

    const result = await fetchShowcaseRender('pub-1');

    expect(fetchMock.mock.calls[0][0]).toBe(
      'http://gw:8080/api/publications/by-id/pub-1/showcase-render',
    );
    expect(result?.htmlTemplate).toBe('<div id="app"></div>');
    expect(result?.format).toBe('vertical');
  });

  it('returns null when the render carries no HTML', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => render({ htmlTemplate: '' }) }));

    // A blank shell renders as an empty white box, which reads as a broken app.
    await expect(fetchShowcaseRender('pub-1')).resolves.toBeNull();
  });

  it('returns null rather than throwing when the gateway fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));

    // A listing page must not go down because one showcase is unavailable.
    await expect(fetchShowcaseRender('pub-1')).resolves.toBeNull();
  });

  it('returns null on a 404 (publication without a showcase)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404, json: async () => ({}) }));

    await expect(fetchShowcaseRender('pub-1')).resolves.toBeNull();
  });

  it('does not call the gateway at all for a blank publication id', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchShowcaseRender('')).resolves.toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('drops malformed items so one bad epoch cannot break the preview', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => render({ items: [null, { data: { a: 1 } }, 'nope'] }),
    }));

    const result = await fetchShowcaseRender('pub-1');

    expect(result?.items).toEqual([{ data: { a: 1 } }]);
  });

  it('yields an empty item list when items is not an array', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => render({ items: null }) }));

    const result = await fetchShowcaseRender('pub-1');

    // The interface still renders, just with unresolved placeholders.
    expect(result?.items).toEqual([]);
    expect(result?.htmlTemplate).toBe('<div id="app"></div>');
  });
});

describe('fetchPublicationReviews', () => {
  beforeEach(() => {
    process.env.GATEWAY_SERVICE_URL = 'http://gw:8080';
  });
  afterEach(() => {
    restoreEnv();
    vi.unstubAllGlobals();
  });

  const reviewRow = (overrides: Record<string, unknown> = {}) => ({
    id: 'rev-1',
    reviewerName: 'John Doe',
    rating: 5,
    comment: 'Saved me a week.',
    createdAt: '2026-07-01T10:00:00Z',
    replyCount: 2,
    ...overrides,
  });

  const okWith = (body: unknown) =>
    vi.fn().mockResolvedValue({ ok: true, json: async () => body });

  it('reads the anonymous alias, not the authenticated reviews route', async () => {
    const fetchMock = okWith({ reviews: [reviewRow()], totalElements: 7 });
    vi.stubGlobal('fetch', fetchMock);

    const page = await fetchPublicationReviews('pub-1');

    // The bare /publications/{id}/reviews path sits behind the gateway's JWT
    // filter and answers 401 to a visitor; only the /by-id/ prefix is public.
    expect(fetchMock.mock.calls[0][0]).toContain('/api/publications/by-id/pub-1/reviews');
    expect(page.reviews).toHaveLength(1);
    expect(page.totalElements).toBe(7);
  });

  it('asks only for reviews that carry words', async () => {
    const fetchMock = okWith({ reviews: [], totalElements: 0 });
    vi.stubGlobal('fetch', fetchMock);

    await fetchPublicationReviews('pub-1');

    // A bare star with no comment is already summarised by the average in the
    // header; a page of empty rows gives a reader nothing.
    expect(fetchMock.mock.calls[0][0]).toContain('onlyWithComment=true');
  });

  it('never reads a reviewer id, even if the payload carries one', async () => {
    vi.stubGlobal('fetch', okWith({
      reviews: [reviewRow({ reviewerId: '42' })],
      totalElements: 1,
    }));

    const page = await fetchPublicationReviews('pub-1');

    // The server strips it; not mapping it is the second half of that, so a
    // field re-added upstream cannot reach a crawlable page by accident.
    expect(page.reviews[0]).not.toHaveProperty('reviewerId');
  });

  it('keeps a comment left without a rating', async () => {
    vi.stubGlobal('fetch', okWith({ reviews: [reviewRow({ rating: null })], totalElements: 1 }));

    const page = await fetchPublicationReviews('pub-1');

    expect(page.reviews[0].rating).toBeNull();
    expect(page.reviews[0].comment).toBe('Saved me a week.');
  });

  it('drops a row with no id rather than rendering an unkeyed entry', async () => {
    vi.stubGlobal('fetch', okWith({
      reviews: [reviewRow(), reviewRow({ id: null }), 'nope'],
      totalElements: 2,
    }));

    const page = await fetchPublicationReviews('pub-1');

    expect(page.reviews).toHaveLength(1);
  });

  it('degrades to an empty page when the gateway fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));

    // Reviews are secondary content: a listing must still render without them.
    await expect(fetchPublicationReviews('pub-1')).resolves.toEqual({
      reviews: [],
      totalElements: 0,
    });
  });

  it('degrades to an empty page on a 404 (publication not publicly readable)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404, json: async () => ({}) }));

    await expect(fetchPublicationReviews('pub-1')).resolves.toEqual({
      reviews: [],
      totalElements: 0,
    });
  });

  it('does not call the gateway for a blank publication id', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    await fetchPublicationReviews('');

    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe('fetchVerifiedPublisherHandles', () => {
  beforeEach(() => {
    process.env.GATEWAY_SERVICE_URL = 'http://gw:8080';
  });
  afterEach(() => {
    restoreEnv();
    vi.unstubAllGlobals();
  });

  it('asks about each DISTINCT author once, lowercased, and answers a set', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ verified: ['ada'] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const verified = await fetchVerifiedPublisherHandles(['Ada', 'ada', 'linus', null, '  ']);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0])
        .toBe('http://gw:8080/api/users/public/verified-handles?handles=ada,linus');
    expect(verified).toEqual(new Set(['ada']));
  });

  it('lowercases what comes back, so the caller can compare either casing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ verified: ['Ada'] }),
    }));

    expect(await fetchVerifiedPublisherHandles(['ada'])).toEqual(new Set(['ada']));
  });

  it('splits above the backend batch cap rather than sending one oversized query', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ verified: [] }) });
    vi.stubGlobal('fetch', fetchMock);

    await fetchVerifiedPublisherHandles(Array.from({ length: 150 }, (_, i) => `user${i}`));

    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('answers empty without fetching when there is no author to ask about', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    expect(await fetchVerifiedPublisherHandles([null, undefined, ''])).toEqual(new Set());
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('degrades to no badges when the gateway fails - a public page must still render', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));

    expect(await fetchVerifiedPublisherHandles(['ada'])).toEqual(new Set());
  });

  it('ignores a payload of the wrong shape instead of throwing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ verified: 'ada' }),
    }));

    expect(await fetchVerifiedPublisherHandles(['ada'])).toEqual(new Set());
  });

  it('percent-encodes each handle, so a crafted value cannot forge query parameters', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ verified: [] }) });
    vi.stubGlobal('fetch', fetchMock);

    await fetchVerifiedPublisherHandles(['ada&ids=1']);

    expect(fetchMock.mock.calls[0][0]).toContain('ada%26ids%3D1');
  });
});

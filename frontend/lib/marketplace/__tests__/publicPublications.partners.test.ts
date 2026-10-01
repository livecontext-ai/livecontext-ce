import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('server-only', () => ({}));
vi.mock('@/lib/edition', () => ({ IS_MANAGED_CLOUD: true, IS_CE: false }));

import { fetchPublisherBadgeHandles, fetchVerifiedPublisherHandles } from '../publicPublications';

const ORIGINAL_ENV = { ...process.env };

/** The partner half of the handle-keyed badge lookup the public marketplace pages use. */
describe('fetchPublisherBadgeHandles', () => {
  beforeEach(() => {
    process.env.GATEWAY_SERVICE_URL = 'http://gw:8080';
  });
  afterEach(() => {
    process.env = { ...ORIGINAL_ENV };
    vi.unstubAllGlobals();
  });

  it('answers both badges from ONE request per chunk, lowercased', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ verified: ['Ada'], partners: ['Linus'] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    const badges = await fetchPublisherBadgeHandles(['ada', 'linus']);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(badges.verified).toEqual(new Set(['ada']));
    expect(badges.partners).toEqual(new Set(['linus']));
  });

  it('an older backend with no `partners` list yields no partner, and the verified half is intact', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ verified: ['ada'] }) }));

    const badges = await fetchPublisherBadgeHandles(['ada']);

    expect(badges.partners).toEqual(new Set());
    expect(badges.verified).toEqual(new Set(['ada']));
  });

  it('a malformed partners list is ignored, never thrown', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ partners: 'ada' }) }));

    expect((await fetchPublisherBadgeHandles(['ada'])).partners).toEqual(new Set());
  });

  it('the verified-only wrapper returns exactly the verified half', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ verified: ['ada'], partners: ['linus'] }),
    }));

    expect(await fetchVerifiedPublisherHandles(['ada', 'linus'])).toEqual(new Set(['ada']));
  });
});

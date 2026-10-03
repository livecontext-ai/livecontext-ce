import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('server-only', () => ({}));
let mockIsCe = false;
vi.mock('@/lib/edition', () => ({
  get IS_CE() { return mockIsCe; },
  get IS_MANAGED_CLOUD() { return !mockIsCe; },
}));

import { fetchPartnerOffer, mapPartnerOffer } from '../publicPartnerOffer';

const RAW = {
  token: 'Abc23XyZ9k', code: 'NORTHWIND', credits: 8000, plan_code: 'TEAM', credit_tier_index: 3, billing_cycle: 'yearly',
  partner: { name: 'Northwind Studio', handle: 'northwind', avatar_url: 'https://cdn.example/a.webp', tier: 'gold', verified: true, bio: 'x' },
};

describe('mapPartnerOffer', () => {
  it('keeps what the client reads: the plan, credits, cycle, the code and the partner as their profile shows them', () => {
    expect(mapPartnerOffer(RAW)).toEqual({
      token: 'Abc23XyZ9k', code: 'NORTHWIND', credits: 8000, plan: 'team', creditTier: 3, cycle: 'yearly',
      partner: { name: 'Northwind Studio', handle: 'northwind', avatarUrl: 'https://cdn.example/a.webp', tier: 'gold', verified: true },
      // An offer without apps (or a backend that sends none) has an empty list.
      apps: [],
      appsPlan: null,
    });
  });

  it('the plan the apps need is one the price list sells, or none', () => {
    expect(mapPartnerOffer({ ...RAW, apps_plan: 'PRO' })?.appsPlan).toBe('pro');
    expect(mapPartnerOffer({ ...RAW, apps_plan: 'TEAM' })?.appsPlan).toBe('team');
    expect(mapPartnerOffer({ ...RAW, apps_plan: 'ENTERPRISE' })?.appsPlan).toBeNull();
    expect(mapPartnerOffer({ ...RAW, apps_plan: null })?.appsPlan).toBeNull();
  });

  it('keeps each offered app as the marketplace lists it: title, description, publisher, integrations', () => {
    const offer = mapPartnerOffer({ ...RAW, apps: [{
      id: 'p-1', title: 'Suivi des prospects', description: 'Leads', publisherId: 42, publisherName: 'Northwind Studio',
      nodeIcons: [{ iconSlug: 'hubspot', isMcp: true }, { nodeKind: 'entry' }, { bogus: 1 }, 'x'],
      showcaseRunId: 'run-1', showcaseInterfaceId: 'if-1',
    }] });

    expect(offer?.apps).toEqual([{
      id: 'p-1', title: 'Suivi des prospects', description: 'Leads', publisherId: '42', publisherName: 'Northwind Studio',
      nodeIcons: [
        { nodeId: undefined, nodeKind: undefined, iconSlug: 'hubspot', avatarUrl: undefined, isMcp: true },
        { nodeId: undefined, nodeKind: 'entry', iconSlug: undefined, avatarUrl: undefined, isMcp: false },
      ],
      // The showcase run the card draws the app's screen from.
      showcaseRunId: 'run-1', showcaseInterfaceId: 'if-1', visibility: 'PUBLIC',
    }]);
  });

  it('an app missing what the card needs (id, title, publisher) is left out, the others are kept', () => {
    const ok = { id: 'p-2', title: 'Support', publisherId: '42' };
    const offer = mapPartnerOffer({ ...RAW, apps: [ok, { title: 'No id', publisherId: '42' }, { id: 'p-3', publisherId: '42' }, { id: 'p-4', title: 'No publisher' }, null] });

    expect(offer?.apps.map((a) => a.id)).toEqual(['p-2']);
    expect(offer?.apps[0]).toMatchObject({ description: null, publisherName: null, nodeIcons: [], showcaseRunId: null, showcaseInterfaceId: null });
  });

  it('a private profile (no partner) keeps the offer, without naming anyone', () => {
    expect(mapPartnerOffer({ ...RAW, partner: null })?.partner).toBeNull();
    expect(mapPartnerOffer({ ...RAW, partner: { name: '' } })?.partner).toBeNull();
  });

  it('regression: a backend avatar path is served through the app proxy, as every other avatar is', () => {
    expect(mapPartnerOffer({ ...RAW, partner: { name: 'Ann', avatar_url: '/api/users/42/avatar' } })?.partner?.avatarUrl)
      .toBe('/api/proxy/users/42/avatar');
  });

  it('a partner without handle, avatar or a known tier keeps their name only', () => {
    expect(mapPartnerOffer({ ...RAW, partner: { name: 'Ann', handle: '', avatar_url: null, tier: 'diamond' } })?.partner)
      .toEqual({ name: 'Ann', handle: null, avatarUrl: null, tier: null, verified: false });
  });

  it('anything off the price list reads as no offer, never as a page promising what nobody sells', () => {
    for (const bad of [
      { plan_code: 'ENTERPRISE' }, { plan_code: 'FREE' }, { credit_tier_index: -1 }, { credit_tier_index: 1.5 },
      { credit_tier_index: 999 }, { billing_cycle: 'weekly' }, { credits: -5 }, { credits: 'many' },
      { code: '' }, { token: 'abc' }, { token: '../../x' },
    ]) {
      expect(mapPartnerOffer({ ...RAW, ...bad }), JSON.stringify(bad)).toBeNull();
    }
    expect(mapPartnerOffer(null)).toBeNull();
    expect(mapPartnerOffer('offer')).toBeNull();
  });
});

describe('fetchPartnerOffer', () => {
  const ORIGINAL_ENV = { ...process.env };
  const fetchMock = vi.fn();

  beforeEach(() => {
    mockIsCe = false;
    process.env.GATEWAY_SERVICE_URL = 'http://gateway:8080';
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => {
    process.env = { ...ORIGINAL_ENV };
    vi.unstubAllGlobals();
  });

  it('reads the public endpoint for the token, never from a cache', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => RAW });

    const read = await fetchPartnerOffer('Abc23XyZ9k');

    expect(read.status === 'ok' && read.offer.plan).toBe('team');
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/api\/public\/partner-program\/offers\/Abc23XyZ9k$/);
    expect(init.cache).toBe('no-store');
  });

  it('only the endpoint 404 means the offer is gone', async () => {
    fetchMock.mockResolvedValueOnce({ ok: false, status: 404, json: async () => ({ error: 'unknown_offer' }) });

    expect(await fetchPartnerOffer('Abc23XyZ9k')).toEqual({ status: 'gone' });
  });

  it('regression: a failed read (5xx, timeout, unreadable answer) is an error, never a dead offer', async () => {
    fetchMock.mockResolvedValueOnce({ ok: false, status: 502, json: async () => ({}) });
    expect(await fetchPartnerOffer('Abc23XyZ9k')).toEqual({ status: 'error' });

    fetchMock.mockRejectedValueOnce(new Error('timeout'));
    expect(await fetchPartnerOffer('Abc23XyZ9k')).toEqual({ status: 'error' });

    fetchMock.mockResolvedValueOnce({ ok: true, json: async () => ({ token: 'Abc23XyZ9k' }) });
    expect(await fetchPartnerOffer('Abc23XyZ9k')).toEqual({ status: 'error' });
  });

  it('regression: a 404 that is not the endpoint answer (a route missing during a deploy) is an error, not a dead offer', async () => {
    fetchMock.mockResolvedValueOnce({ ok: false, status: 404, json: async () => ({ status: 404, error: 'Not Found', path: '/api/x' }) });
    expect(await fetchPartnerOffer('Abc23XyZ9k')).toEqual({ status: 'error' });

    fetchMock.mockResolvedValueOnce({ ok: false, status: 404, json: async () => { throw new Error('html'); } });
    expect(await fetchPartnerOffer('Abc23XyZ9k')).toEqual({ status: 'error' });
  });

  it('a malformed token is refused before any request', async () => {
    expect(await fetchPartnerOffer('../admin')).toEqual({ status: 'gone' });
    expect(await fetchPartnerOffer('abc')).toEqual({ status: 'gone' });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('a self-hosted build has no partner program: no offer, no request', async () => {
    mockIsCe = true;

    expect(await fetchPartnerOffer('Abc23XyZ9k')).toEqual({ status: 'gone' });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

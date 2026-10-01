import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('server-only', () => ({}));
let mockIsCe = false;
vi.mock('@/lib/edition', () => ({
  get IS_CE() { return mockIsCe; },
  get IS_MANAGED_CLOUD() { return !mockIsCe; },
}));

import { fetchPartnerTerms, mapPartnerTerms } from '../publicPartnerTerms';

const ORIGINAL_ENV = { ...process.env };
const TIERS = [
  { tier: 'silver', commission_percent: 30, threshold_minor: 0 },
  { tier: 'gold', commission_percent: 40, threshold_minor: 500000 },
  { tier: 'platinum', commission_percent: 50, threshold_minor: 2500000 },
];
const TERMS = {
  commission_percent: 30, commission_months: 12, hold_days: 14, audience_credits: 10000,
  tiers: TIERS, tier_currency: 'usd', tier_settle_days: 60, founder_until: '2027-01-01T00:00:00Z', founder_open: true,
};

describe('mapPartnerTerms', () => {
  it('keeps a complete payload', () => {
    expect(mapPartnerTerms(TERMS)).toEqual(TERMS);
  });

  it('an older payload without tiers keeps its figures, with no tier and no founder offer', () => {
    const base = { commission_percent: 50, commission_months: 12, hold_days: 14, audience_credits: 10000 };
    expect(mapPartnerTerms(base)).toEqual({
      ...base, tiers: [], tier_currency: 'usd', tier_settle_days: null, founder_until: null, founder_open: false,
    });
  });

  it('tiers without the rule that moves a partner between them (no settle window) are not shown', () => {
    expect(mapPartnerTerms({ ...TERMS, tier_settle_days: undefined })?.tiers).toEqual([]);
  });

  it('puts the tiers in Silver, Gold, Platinum order whatever order they arrive in', () => {
    expect(mapPartnerTerms({ ...TERMS, tiers: [TIERS[2], TIERS[0], TIERS[1]] })?.tiers.map((t) => t.tier))
      .toEqual(['silver', 'gold', 'platinum']);
  });

  it('an incomplete or malformed ladder is dropped whole, never shown partially', () => {
    expect(mapPartnerTerms({ ...TERMS, tiers: [TIERS[0], TIERS[1]] })?.tiers).toEqual([]);
    expect(mapPartnerTerms({ ...TERMS, tiers: [TIERS[0], TIERS[1], { ...TIERS[2], tier: 'diamond' }] })?.tiers).toEqual([]);
    expect(mapPartnerTerms({ ...TERMS, tiers: [TIERS[0], TIERS[1], { ...TIERS[2], commission_percent: '50' }] })?.tiers).toEqual([]);
    expect(mapPartnerTerms({ ...TERMS, tiers: 'x' })?.tiers).toEqual([]);
  });

  it('opens the founder offer only on an explicit true with a real deadline', () => {
    expect(mapPartnerTerms({ ...TERMS, founder_open: 'true' })?.founder_open).toBe(false);
    expect(mapPartnerTerms({ ...TERMS, founder_until: 'soon' })).toMatchObject({ founder_until: null, founder_open: false });
    expect(mapPartnerTerms({ ...TERMS, founder_until: null })?.founder_open).toBe(false);
  });

  it('refuses a payload with any figure missing or malformed, so the page never shows a guess', () => {
    expect(mapPartnerTerms({ ...TERMS, commission_percent: '50' })).toBeNull();
    expect(mapPartnerTerms({ ...TERMS, hold_days: undefined })).toBeNull();
    expect(mapPartnerTerms({ ...TERMS, commission_months: -1 })).toBeNull();
    expect(mapPartnerTerms({ ...TERMS, audience_credits: Number.NaN })).toBeNull();
    expect(mapPartnerTerms(null)).toBeNull();
    expect(mapPartnerTerms('x')).toBeNull();
  });
});

describe('fetchPartnerTerms', () => {
  beforeEach(() => {
    mockIsCe = false;
    process.env.GATEWAY_SERVICE_URL = 'http://gw:8080';
  });
  afterEach(() => {
    process.env = { ...ORIGINAL_ENV };
    vi.unstubAllGlobals();
  });

  it('reads the anonymous terms endpoint on the gateway', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => TERMS });
    vi.stubGlobal('fetch', fetchMock);

    expect(await fetchPartnerTerms()).toEqual(TERMS);
    expect(fetchMock.mock.calls[0][0]).toBe('http://gw:8080/api/public/partner-program/terms');
  });

  it('answers null on an error status, a thrown fetch or a bad body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: async () => TERMS }));
    expect(await fetchPartnerTerms()).toBeNull();

    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('ECONNREFUSED')));
    expect(await fetchPartnerTerms()).toBeNull();

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ nope: 1 }) }));
    expect(await fetchPartnerTerms()).toBeNull();
  });

  it('never calls the gateway on a self-hosted build', async () => {
    mockIsCe = true;
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    expect(await fetchPartnerTerms()).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

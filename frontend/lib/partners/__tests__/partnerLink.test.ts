// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest';
import {
  highestRecommendableTier,
  partnerLink,
  partnerRecommendationFromSearch,
  partnerRecommendedLink,
} from '../partnerLink';
import { buildPersonalOfferSignInReturn } from '@/lib/lifecycle/personalOfferJourney';
import { CREDIT_TIERS, DEFAULT_MAX_TIER_INDEX } from '@/lib/billing/pricing-constants';

const ORIGIN = 'https://livecontext.ai';
const tierOf = (credits: number) => CREDIT_TIERS.indexOf(credits);

describe('partnerLink', () => {
  it('is the home page with the code, encoded', () => {
    expect(partnerLink(ORIGIN, 'NORTH WIND')).toBe('https://livecontext.ai/?lc_ref=NORTH%20WIND');
  });
});

describe('partnerRecommendedLink', () => {
  it('regression: a partner tier above the default range keeps the full range, even when the visitor picked a lower one', () => {
    const rec = { plan: 'pro' as const, creditTier: DEFAULT_MAX_TIER_INDEX + 1, cycle: 'monthly' as const };
    const url = new URL(partnerRecommendedLink(ORIGIN, 'NORTHWIND', rec, { ...rec, creditTier: 3 }));

    expect(url.searchParams.get('creditTierIndex')).toBe('3');
    // The banner names the partner's tier: the page must be able to show it.
    expect(url.searchParams.get('tiers')).toBe('full');
  });

  it('no tier above the default range on either side: the default range', () => {
    const rec = { plan: 'pro' as const, creditTier: 5, cycle: 'monthly' as const };

    expect(new URL(partnerRecommendedLink(ORIGIN, 'NORTHWIND', rec, { ...rec, creditTier: 2 })).searchParams.get('tiers')).toBeNull();
  });

  it("opens on the visitor's own selection while lc_rec keeps the partner's choice", () => {
    const rec = { plan: 'team' as const, creditTier: 3, cycle: 'yearly' as const };
    const url = new URL(partnerRecommendedLink(ORIGIN, 'NORTHWIND', rec, { plan: 'pro', creditTier: DEFAULT_MAX_TIER_INDEX + 1, cycle: 'monthly' }));

    expect(url.searchParams.get('planCode')).toBe('PRO');
    expect(url.searchParams.get('creditTierIndex')).toBe(String(DEFAULT_MAX_TIER_INDEX + 1));
    expect(url.searchParams.get('billingCycle')).toBe('monthly');
    // The tier shown is the selection's, so the page must show the full range for it.
    expect(url.searchParams.get('tiers')).toBe('full');
    expect(url.searchParams.get('lc_rec')).toBe('team.3.yearly');
    // ...which the pricing page reads back as the partner's recommendation, not the selection.
    expect(partnerRecommendationFromSearch(url.searchParams)).toEqual({ ...rec, code: 'NORTHWIND' });
  });

  it('opens the pricing page preset to the recommended plan, with the code and the recommendation itself', () => {
    const url = new URL(partnerRecommendedLink(ORIGIN, 'NORTHWIND', { plan: 'pro', creditTier: tierOf(250_000), cycle: 'monthly' }));

    expect(url.origin + url.pathname).toBe('https://livecontext.ai/app/settings/pricing');
    expect(Object.fromEntries(url.searchParams)).toEqual({
      pricingMode: 'subscription', planCode: 'PRO', creditTierIndex: String(tierOf(250_000)), billingCycle: 'monthly',
      lc_ref: 'NORTHWIND', lc_rec: `pro.${tierOf(250_000)}.monthly`,
    });
  });

  it('a tier above the default range asks the page to show it (otherwise it would be clamped away)', () => {
    const url = new URL(partnerRecommendedLink(ORIGIN, 'NORTHWIND', { plan: 'team', creditTier: DEFAULT_MAX_TIER_INDEX + 1, cycle: 'yearly' }));

    expect(url.searchParams.get('tiers')).toBe('full');
    expect(url.searchParams.get('billingCycle')).toBe('yearly');
  });

  it('round-trips: the page reads back exactly what the partner chose', () => {
    const rec = { plan: 'team' as const, creditTier: tierOf(1_000_000), cycle: 'yearly' as const };
    const url = new URL(partnerRecommendedLink(ORIGIN, 'northwind', rec));

    expect(partnerRecommendationFromSearch(url.searchParams)).toEqual({ ...rec, code: 'NORTHWIND' });
  });
});

describe('the recommendation survives the sign-in round trip, the visitor\'s own choice does not replace it', () => {
  afterEach(() => window.history.replaceState({}, '', '/'));

  it('regression: a visitor who picked Team 1M before signing in still reads Pro 250K as the partner\'s recommendation', () => {
    const link = new URL(partnerRecommendedLink(ORIGIN, 'NORTHWIND', { plan: 'pro', creditTier: tierOf(250_000), cycle: 'monthly' }));
    window.history.replaceState({}, '', `/en${link.pathname}${link.search}`);

    // What the pricing page sends to sign-in when the visitor clicks Team with 1M credits, yearly.
    const back = buildPersonalOfferSignInReturn(window, { planCode: 'TEAM', creditTierIndex: tierOf(1_000_000), billingCycle: 'yearly' });
    const params = new URL(back, ORIGIN).searchParams;

    // The page's selection follows the visitor...
    expect(params.get('planCode')).toBe('TEAM');
    // ...the recommendation and the code do not.
    expect(partnerRecommendationFromSearch(params)).toEqual({ code: 'NORTHWIND', plan: 'pro', creditTier: tierOf(250_000), cycle: 'monthly' });
  });
});

describe('partnerRecommendationFromSearch', () => {
  const read = (query: string) => partnerRecommendationFromSearch(new URLSearchParams(query));
  const base = 'lc_ref=NORTHWIND&lc_rec=pro.5.monthly&planCode=TEAM&creditTierIndex=7&billingCycle=yearly';

  it('reads lc_rec, never the page\'s own selection', () => {
    expect(read(base)).toEqual({ code: 'NORTHWIND', plan: 'pro', creditTier: 5, cycle: 'monthly' });
  });

  it('says nothing without a recommendation: a plain pricing link is not a partner recommendation', () => {
    expect(read('lc_ref=NORTHWIND&planCode=PRO&creditTierIndex=5')).toBeNull();
    expect(read(base.replace('lc_rec=pro.5.monthly', 'lc_rec=1'))).toBeNull();
  });

  it('says nothing for an incoherent link: no code, a malformed code, an unknown plan or cycle, a tier the plan does not offer', () => {
    expect(read(base.replace('lc_ref=NORTHWIND&', ''))).toBeNull();
    expect(read(base.replace('NORTHWIND', 'x!'))).toBeNull();
    expect(read(base.replace('pro.5', 'enterprise.5'))).toBeNull();
    expect(read(base.replace('pro.5.monthly', 'pro.5.weekly'))).toBeNull();
    expect(read(base.replace('pro.5.monthly', 'pro.42.monthly'))).toBeNull();
    expect(read(base.replace('pro.5.monthly', 'pro.-1.monthly'))).toBeNull();
    expect(read(base.replace('pro.5.monthly', 'pro.1e1.monthly'))).toBeNull();
    expect(read(base.replace('pro.5.monthly', 'pro.5.monthly.extra'))).toBeNull();
    // Starter stops at its credit cap.
    expect(read(base.replace('pro.5', `starter.${highestRecommendableTier('starter') + 1}`))).toBeNull();
  });
});

describe('highestRecommendableTier', () => {
  it('Starter stops at its credit cap, Pro and Team go to the top of the price list', () => {
    expect(CREDIT_TIERS[highestRecommendableTier('starter')]).toBe(100_000);
    expect(highestRecommendableTier('pro')).toBe(CREDIT_TIERS.length - 1);
    expect(highestRecommendableTier('team')).toBe(CREDIT_TIERS.length - 1);
  });
});

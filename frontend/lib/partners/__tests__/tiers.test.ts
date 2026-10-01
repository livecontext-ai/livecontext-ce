import { describe, expect, it } from 'vitest';
import {
  clientsForAYear,
  creditTierOf,
  daysUntil,
  EXAMPLE_CLIENT_PLAN,
  maxCreditTier,
  monthlyBill,
  estimateMonths,
  estimateYearOne,
  isTierKey,
  maxTierPercent,
  monthlyCommission,
  niceCeiling,
  tierForRevenue,
  wholeMoney,
  type PartnerTierTerm,
} from '../tiers';

const TIERS: PartnerTierTerm[] = [
  { tier: 'silver', commission_percent: 30, threshold_minor: 0 },
  { tier: 'gold', commission_percent: 40, threshold_minor: 500_000 },
  { tier: 'platinum', commission_percent: 50, threshold_minor: 2_500_000 },
];

describe('tierForRevenue', () => {
  it('matches the backend: thresholds are inclusive', () => {
    expect(tierForRevenue(TIERS, 0)).toBe('silver');
    expect(tierForRevenue(TIERS, 499_999)).toBe('silver');
    expect(tierForRevenue(TIERS, 500_000)).toBe('gold');
    expect(tierForRevenue(TIERS, 2_499_999)).toBe('gold');
    expect(tierForRevenue(TIERS, 2_500_000)).toBe('platinum');
  });
});

describe('maxTierPercent / isTierKey', () => {
  it('the top rate is the highest tier, none without tiers', () => {
    expect(maxTierPercent(TIERS)).toBe(50);
    expect(maxTierPercent([])).toBeNull();
  });

  it('only the three tier names are tiers', () => {
    expect(['silver', 'gold', 'platinum'].every(isTierKey)).toBe(true);
    expect(isTierKey('diamond')).toBe(false);
    expect(isTierKey(undefined)).toBe(false);
  });
});

describe('estimateYearOne', () => {
  it('a small book stays Silver all year: 10 clients x $40 = $120 a month, $1,440 a year', () => {
    const e = estimateYearOne({ tiers: TIERS, clients: 10, monthlySpend: 40 });

    expect(e.months).toHaveLength(12);
    expect(e.months.every((m) => m.tier === 'silver')).toBe(true);
    expect(e.months[0].commission).toBe(120);
    expect(e.total).toBe(1440);
    expect(e.upgrades).toEqual([]);
  });

  it('like the backend, an invoice counts toward a tier only once it is 60 days old: two months of lag', () => {
    // $1,000 a month: at the start of month 7 the invoices of months 1 to 5 are 60+ days old
    // ($5,000 settled), so month 7 is the first Gold month.
    const e = estimateYearOne({ tiers: TIERS, clients: 25, monthlySpend: 40, settleDays: 60 });

    expect(e.months[5].tier).toBe('silver');
    expect(e.months[6].tier).toBe('gold');
    expect(e.months[6].commission).toBe(400);
    expect(e.upgrades).toEqual([{ tier: 'gold', month: 7 }]);
  });

  it('a shorter settle window moves the upgrade earlier: 30 days is one month of lag', () => {
    const e = estimateYearOne({ tiers: TIERS, clients: 25, monthlySpend: 40, settleDays: 30 });

    expect(e.upgrades).toEqual([{ tier: 'gold', month: 6 }]);
  });

  it('the default settle window is the backend default (60 days)', () => {
    expect(estimateYearOne({ tiers: TIERS, clients: 25, monthlySpend: 40 }).upgrades)
      .toEqual(estimateYearOne({ tiers: TIERS, clients: 25, monthlySpend: 40, settleDays: 60 }).upgrades);
  });

  it('climbs both steps in one year and reports each', () => {
    // $2,500 a month, two months of lag: Gold from month 4 ($5,000 settled), Platinum from month 12 ($25,000).
    const e = estimateYearOne({ tiers: TIERS, clients: 50, monthlySpend: 50 });

    expect(e.upgrades).toEqual([{ tier: 'gold', month: 4 }, { tier: 'platinum', month: 12 }]);
    expect(e.months[11].commission).toBe(1250);
  });

  it('a founder earns Platinum from the first month, and never "upgrades"', () => {
    const e = estimateYearOne({ tiers: TIERS, clients: 10, monthlySpend: 40, founder: true });

    expect(e.months.every((m) => m.tier === 'platinum')).toBe(true);
    expect(e.total).toBe(2400);
    expect(e.upgrades).toEqual([]);
  });

  it('nothing in, nothing out: no clients or a negative input earns zero', () => {
    expect(estimateYearOne({ tiers: TIERS, clients: 0, monthlySpend: 40 }).total).toBe(0);
    expect(estimateYearOne({ tiers: TIERS, clients: -3, monthlySpend: 40 }).total).toBe(0);
  });
});

describe('daysUntil', () => {
  const now = new Date('2026-12-30T12:00:00Z');

  it('counts started days, and 0 once the deadline has passed', () => {
    expect(daysUntil('2027-01-01T00:00:00Z', now)).toBe(2);
    expect(daysUntil('2026-12-30T12:00:01Z', now)).toBe(1);
    expect(daysUntil('2026-12-30T12:00:00Z', now)).toBe(0);
    expect(daysUntil('2026-01-01T00:00:00Z', now)).toBe(0);
  });

  it('no deadline or a bad one is 0, never NaN', () => {
    expect(daysUntil(null, now)).toBe(0);
    expect(daysUntil('not a date', now)).toBe(0);
  });
});

describe('estimateMonths (a client base that grows)', () => {
  const GROWING = [4, 8, 12, 16, 20, 24, 28, 32, 37, 42, 46, 50];

  it('a constant base gives exactly the fixed-clients estimate', () => {
    const fixed = estimateYearOne({ tiers: TIERS, clients: 10, monthlySpend: 60 });
    expect(estimateMonths({ tiers: TIERS, clientsByMonth: Array(12).fill(10), monthlySpend: 60 })).toEqual(fixed);
  });

  it('climbs on the settled revenue of the months behind it: Gold in month 8 once months 1 to 6 reach $5,000', () => {
    // Months 1-6: 84 client-months x $60 = $5,040, counted from month 8 (60 days later). Platinum
    // needs $25,000 and the year only brings 319 client-months ($19,140): never reached.
    const e = estimateMonths({ tiers: TIERS, clientsByMonth: GROWING, monthlySpend: 60, settleDays: 60 });

    expect(e.months[6]).toEqual({ month: 7, tier: 'silver', commission: 504 }); // 28 clients x $60 x 30%
    expect(e.months[7]).toEqual({ month: 8, tier: 'gold', commission: 768 }); // 32 clients x $60 x 40%
    expect(e.months[11].commission).toBe(1200);
    expect(e.upgrades).toEqual([{ tier: 'gold', month: 8 }]);
    expect(e.total).toBeCloseTo(112 * 60 * 0.3 + 207 * 60 * 0.4, 6);
  });

  it('a founder earns the Platinum rate on every month of the growth', () => {
    const e = estimateMonths({ tiers: TIERS, clientsByMonth: GROWING, monthlySpend: 60, founder: true });

    expect(e.months.every((m) => m.tier === 'platinum')).toBe(true);
    expect(e.months[11].commission).toBe(1500);
    expect(e.total).toBe(319 * 60 * 0.5);
  });
});

describe('money helpers of the page', () => {
  it('monthlyCommission: clients x bill x rate, never negative', () => {
    expect(monthlyCommission(25, 60, 50)).toBe(750);
    expect(monthlyCommission(-1, 60, 50)).toBe(0);
  });

  it('clientsForAYear turns a threshold into clients paying for a year, rounded up', () => {
    expect(clientsForAYear(500_000, 60)).toBe(7); // $5,000 / $720 = 6.9
    expect(clientsForAYear(2_500_000, 60)).toBe(35); // $25,000 / $720 = 34.7
    expect(clientsForAYear(0, 60)).toBe(1);
    expect(clientsForAYear(500_000, 0)).toBe(0);
  });

  it('by default counts clients on the example plan, at its real price ($209 a month)', () => {
    expect(clientsForAYear(500_000)).toBe(2); // $5,000 / $2,508 = 1.99
    expect(clientsForAYear(2_500_000)).toBe(10); // $25,000 / $2,508 = 9.97
  });
});

describe('client bills on the real price list', () => {
  it('a bill is the plan price plus the price of its credits, on monthly billing', () => {
    expect(monthlyBill({ plan: 'pro', creditTier: creditTierOf(250_000) })).toBe(24 + 185);
    expect(monthlyBill({ plan: 'team', creditTier: creditTierOf(1_000_000) })).toBe(49 + 825);
    expect(monthlyBill({ plan: 'team', creditTier: creditTierOf(5_000_000) })).toBe(49 + 4_000);
    expect(monthlyBill({ plan: 'starter', creditTier: 0 })).toBe(10);
  });

  it('the example plan is Pro with 250,000 credits: $209 a month', () => {
    expect(EXAMPLE_CLIENT_PLAN.plan).toBe('pro');
    expect(monthlyBill(EXAMPLE_CLIENT_PLAN)).toBe(209);
  });

  it('Starter stops at its credit cap (100K), the other plans reach the top tier', () => {
    expect(maxCreditTier('starter')).toBe(creditTierOf(100_000));
    // Capped at what /pricing shows by default (1M): every bill the calculator prints can be checked there.
    expect(maxCreditTier('pro')).toBe(creditTierOf(1_000_000));
    expect(maxCreditTier('team')).toBe(creditTierOf(1_000_000));
  });

  it('creditTierOf refuses an amount that is not a tier, instead of pricing a wrong one', () => {
    expect(() => creditTierOf(123_456)).toThrow(/not a credit tier/);
  });
});

describe('chart and money formatting', () => {

  it('niceCeiling rounds an axis top up to a round figure, never below the value', () => {
    expect(niceCeiling(480)).toBe(500);
    expect(niceCeiling(500)).toBe(500);
    expect(niceCeiling(1500)).toBe(1500);
    expect(niceCeiling(1501)).toBe(2000);
    expect(niceCeiling(180)).toBe(200);
    expect(niceCeiling(7)).toBe(8);
    expect(niceCeiling(0)).toBe(1);
  });

  it('wholeMoney writes whole amounts in the locale, and survives an unknown currency', () => {
    expect(wholeMoney(1800, 'usd', 'en')).toBe('$1,800');
    // French writes the currency out ("1 800 $US"), so a visitor never mistakes it for another dollar.
    expect(wholeMoney(1800, 'usd', 'fr')).toMatch(/^1\s800\s\$US$/);
    expect(wholeMoney(12, 'not-a-currency', 'en')).toBe('12 NOT-A-CURRENCY');
  });
});

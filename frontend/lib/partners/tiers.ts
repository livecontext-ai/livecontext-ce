/**
 * Partner tiers (Silver, Gold, Platinum): the pure logic the /partners page, the earnings
 * calculator and the partner dashboard share. The rates and thresholds always come from the
 * backend terms (`PartnerProgramTerms.tiers`), never from copy, so the page cannot promise a
 * rate the program does not pay. The client bills the examples run on come from the real price
 * list (`pricing-constants`), never from a figure picked for the page.
 */
import { calcPrice, CREDIT_TIERS, resolveMaxTierIndex, STARTER_MAX_CREDITS } from '@/lib/billing/pricing-constants';

export type PartnerTierKey = 'silver' | 'gold' | 'platinum';

export const TIER_ORDER: readonly PartnerTierKey[] = ['silver', 'gold', 'platinum'];

/** One tier as the backend states it: its rate and the settled revenue (minor units) that reaches it. */
export interface PartnerTierTerm {
  tier: PartnerTierKey;
  commission_percent: number;
  threshold_minor: number;
}

export function isTierKey(value: unknown): value is PartnerTierKey {
  return typeof value === 'string' && (TIER_ORDER as readonly string[]).includes(value);
}

/** The highest rate on offer, for "up to N%". */
export function maxTierPercent(tiers: readonly PartnerTierTerm[]): number | null {
  if (tiers.length === 0) return null;
  return Math.max(...tiers.map((t) => t.commission_percent));
}

export function tierTerm(tiers: readonly PartnerTierTerm[], tier: PartnerTierKey): PartnerTierTerm | undefined {
  return tiers.find((t) => t.tier === tier);
}

/** The tier a settled revenue reaches on its own (thresholds are inclusive, like the backend). */
export function tierForRevenue(tiers: readonly PartnerTierTerm[], revenueMinor: number): PartnerTierKey {
  let reached: PartnerTierKey = 'silver';
  for (const key of TIER_ORDER) {
    const term = tierTerm(tiers, key);
    if (term && revenueMinor >= term.threshold_minor) reached = key;
  }
  return reached;
}

export interface MonthEstimate {
  month: number;
  tier: PartnerTierKey;
  /** Commission earned that month, in major units (dollars). */
  commission: number;
}

export interface YearOneEstimate {
  months: MonthEstimate[];
  /** Sum of the monthly commissions, major units. */
  total: number;
  /** Each tier reached AFTER the first month, with the first month it applied. */
  upgrades: { tier: PartnerTierKey; month: number }[];
}

/**
 * A partner's first year, month by month: `clients` paying `monthlySpend` (major units, excl.
 * tax) every month from month 1. A month's invoices earn the rate of the tier reached on the
 * revenue already SETTLED at its start: like the backend, an invoice counts toward a tier only
 * once it is `settleDays` old, so a month counts the invoices of the months at least that many
 * days (rounded up to whole months, at least one) before it. A founder is Platinum from day
 * one. An estimate for the page, not the ledger: the backend computes the real commissions.
 */
export function estimateYearOne({
  tiers,
  clients,
  monthlySpend,
  founder = false,
  months = 12,
  settleDays = 60,
}: {
  tiers: readonly PartnerTierTerm[];
  clients: number;
  monthlySpend: number;
  founder?: boolean;
  months?: number;
  settleDays?: number;
}): YearOneEstimate {
  return estimateMonths({
    tiers,
    clientsByMonth: Array.from({ length: months }, () => clients),
    monthlySpend,
    founder,
    settleDays,
  });
}

/**
 * The same estimate for a client base that changes month by month: `clientsByMonth[i]` clients
 * pay `monthlySpend` in month i + 1. The settled revenue at the start of a month is the sum of
 * the months at least `settleDays` behind it, so a partner who grows slowly climbs later than
 * one who starts big.
 */
export function estimateMonths({
  tiers,
  clientsByMonth,
  monthlySpend,
  founder = false,
  settleDays = 60,
}: {
  tiers: readonly PartnerTierTerm[];
  clientsByMonth: readonly number[];
  monthlySpend: number;
  founder?: boolean;
  settleDays?: number;
}): YearOneEstimate {
  const revenue = clientsByMonth.map((c) => Math.max(0, c) * Math.max(0, monthlySpend));
  const lagMonths = Math.max(1, Math.ceil(Math.max(0, settleDays) / 30));
  const out: MonthEstimate[] = [];
  const upgrades: { tier: PartnerTierKey; month: number }[] = [];
  let total = 0;
  let settled = 0;
  for (let month = 1; month <= revenue.length; month++) {
    // The month that has just become old enough to count joins the settled revenue.
    if (month - lagMonths >= 1) settled += revenue[month - lagMonths - 1];
    const tier = founder ? 'platinum' : tierForRevenue(tiers, Math.round(settled * 100));
    const percent = tierTerm(tiers, tier)?.commission_percent ?? 0;
    const commission = (revenue[month - 1] * percent) / 100;
    if (out.length > 0 && tier !== out[out.length - 1].tier) upgrades.push({ tier, month });
    out.push({ month, tier, commission });
    total += commission;
  }
  return { months: out, total, upgrades };
}

/**
 * The smallest "round" value at or above `value` (1, 1.5, 2, 2.5, 3, 4, 5, 6, 8 times a power of
 * ten), for the top of a money axis: $480 reads against $500, not against $480.
 */
export function niceCeiling(value: number): number {
  if (!(value > 0)) return 1;
  const power = 10 ** Math.floor(Math.log10(value));
  const step = [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10].find((s) => s * power >= value - 1e-9) ?? 10;
  return step * power;
}

/** Whole days from `now` until `until` (0 once passed), for "N days left". */
export function daysUntil(until: string | Date | null | undefined, now: Date = new Date()): number {
  if (!until) return 0;
  const end = typeof until === 'string' ? new Date(until) : until;
  const ms = end.getTime() - now.getTime();
  if (!Number.isFinite(ms) || ms <= 0) return 0;
  return Math.ceil(ms / 86_400_000);
}

/**
 * The metal of each tier, as a CSS gradient and a text colour that reads on it. Shared by the
 * tier cards, the tier chip and the dashboard, so a tier looks the same everywhere.
 */
export const TIER_STYLE: Record<PartnerTierKey, { gradient: string; ink: string; ring: string }> = {
  silver: {
    gradient: 'linear-gradient(135deg, #eef1f5 0%, #c3cad4 55%, #9aa4b1 100%)',
    ink: '#2b313a',
    ring: 'rgba(154, 164, 177, 0.55)',
  },
  gold: {
    gradient: 'linear-gradient(135deg, #fbe3a4 0%, #f2b640 50%, #c98a12 100%)',
    ink: '#3a2600',
    ring: 'rgba(217, 154, 30, 0.6)',
  },
  platinum: {
    gradient: 'linear-gradient(135deg, #fbfcfe 0%, #d9def0 40%, #a9b4d6 75%, #8b93c2 100%)',
    ink: '#1f2340',
    ring: 'rgba(139, 147, 194, 0.6)',
  },
};

export type PlanKey = 'starter' | 'pro' | 'team';

export const PLAN_KEYS: readonly PlanKey[] = ['starter', 'pro', 'team'];

/** A client's subscription: a plan and its monthly credits, as an index into CREDIT_TIERS. */
export interface ClientPlan {
  plan: PlanKey;
  creditTier: number;
}

/**
 * What a client on this plan pays per month, excluding tax: the real price list (plan price plus
 * the price of its credits), on monthly billing. The same formula the pricing page bills with.
 */
export function monthlyBill({ plan, creditTier }: ClientPlan): number {
  return calcPrice(plan, 'monthly', creditTier);
}

/**
 * The highest credit tier a plan can take here: Starter stops at its credit cap, the others at
 * the highest tier the pricing page shows by default, so every bill can be checked there.
 */
export function maxCreditTier(plan: PlanKey): number {
  if (plan !== 'starter') return resolveMaxTierIndex(false);
  let max = 0;
  CREDIT_TIERS.forEach((credits, i) => { if (credits <= STARTER_MAX_CREDITS) max = i; });
  return max;
}

/** The credit tier of a monthly credit amount (an exact entry of CREDIT_TIERS). */
export function creditTierOf(credits: number): number {
  const tier = CREDIT_TIERS.indexOf(credits);
  if (tier < 0) throw new Error(`${credits} credits is not a credit tier`);
  return tier;
}

/** The client the page's examples are built on: Pro with 250,000 credits a month. */
export const EXAMPLE_CLIENT_PLAN: ClientPlan = { plan: 'pro', creditTier: creditTierOf(250_000) };

/** Commission a partner earns per month on `clients` paying `bill` each, at `percent`. */
export function monthlyCommission(clients: number, bill: number, percent: number): number {
  return (Math.max(0, clients) * Math.max(0, bill) * Math.max(0, percent)) / 100;
}

/**
 * How many clients paying `bill` a month for a year reach a revenue threshold (minor units):
 * "$5,000 = about 2 clients on Pro with 250K credits for a year". Rounded up, at least 1.
 */
export function clientsForAYear(thresholdMinor: number, bill: number = monthlyBill(EXAMPLE_CLIENT_PLAN)): number {
  if (bill <= 0) return 0;
  return Math.max(1, Math.ceil(thresholdMinor / 100 / (bill * 12)));
}

/** A whole amount of money in the locale ("$5,000"), from MAJOR units. */
export function wholeMoney(major: number, currency: string, locale: string): string {
  try {
    return major.toLocaleString(locale, { style: 'currency', currency: currency.toUpperCase(), maximumFractionDigits: 0 });
  } catch {
    return `${Math.round(major).toLocaleString(locale)} ${currency.toUpperCase()}`;
  }
}

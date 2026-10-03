import { PARTNER_LINK_PARAM } from '@/lib/lifecycle/pendingRewardCode';
import { CREDIT_TIERS, DEFAULT_MAX_TIER_INDEX } from '@/lib/billing/pricing-constants';
import { maxCreditTier, PLAN_KEYS, type PlanKey } from '@/lib/partners/tiers';

/**
 * The link a partner shares: the site's home with their code. Built on the public site's origin
 * (SITE_URL) by the partner's dashboard and the admin report alike, so both copy the same link
 * whatever address the page is open on (a preview, a staging host).
 */
export function partnerLink(origin: string, code: string): string {
  return `${origin}/?${PARTNER_LINK_PARAM}=${encodeURIComponent(code)}`;
}

/** The short link of a partner's offer (V559): a full-screen page with the plan they chose for one client. */
export function partnerOfferLink(origin: string, token: string): string {
  return `${origin}/offer/${encodeURIComponent(token)}`;
}

/**
 * The partner's plan recommendation, in its own parameter: `pro.5.monthly` (plan, credit tier,
 * cycle). Kept apart from the page's own `planCode` / `creditTierIndex` / `billingCycle`, which
 * follow what the visitor selects and are rewritten by the sign-in round trip: read from them, a
 * visitor's own choice would come back from sign-in labelled as the partner's.
 */
export const PARTNER_RECOMMENDATION_PARAM = 'lc_rec';

/** A plan a partner recommends to one client: the plan, its monthly credits, the billing cycle. */
export interface PartnerRecommendation {
  plan: PlanKey;
  /** Index into CREDIT_TIERS. */
  creditTier: number;
  cycle: 'monthly' | 'yearly';
}

/**
 * The highest credit tier a partner can recommend on a plan: Starter stops at its credit cap,
 * the others go up to the top of the price list (the tiers above the default range included,
 * since a partner may well have a client that large).
 */
export function highestRecommendableTier(plan: PlanKey): number {
  return plan === 'starter' ? maxCreditTier('starter') : CREDIT_TIERS.length - 1;
}

function tierFitsPlan(plan: PlanKey, creditTier: number): boolean {
  return Number.isInteger(creditTier) && creditTier >= 0 && creditTier <= highestRecommendableTier(plan);
}

/**
 * The link a partner sends one client: the pricing page with the recommended plan, credits and
 * cycle already chosen, and the partner's code. The pricing page presets itself from
 * `planCode` / `creditTierIndex` / `billingCycle`, the code is captured like any partner link and
 * applied once the account exists, and `lc_rec` keeps the recommendation itself, so the page can
 * still say which plan the partner chose after the visitor changed the selection. A tier above the
 * default range asks the page to show it (`tiers=full`).
 *
 * @param preset the selection to open on when it differs from the recommendation (a client who
 *   changed it on the partner's offer page): `lc_rec` still carries the partner's own choice
 */
export function partnerRecommendedLink(
  origin: string,
  code: string,
  rec: PartnerRecommendation,
  preset: PartnerRecommendation = rec,
): string {
  const params = new URLSearchParams({
    pricingMode: 'subscription',
    planCode: preset.plan.toUpperCase(),
    creditTierIndex: String(preset.creditTier),
    billingCycle: preset.cycle,
  });
  // Either tier above the default range needs it shown: the selection's to open on, the
  // recommendation's for the banner that names it.
  if (Math.max(rec.creditTier, preset.creditTier) > DEFAULT_MAX_TIER_INDEX) params.set('tiers', 'full');
  params.set(PARTNER_LINK_PARAM, code);
  params.set(PARTNER_RECOMMENDATION_PARAM, `${rec.plan}.${rec.creditTier}.${rec.cycle}`);
  return `${origin}/app/settings/pricing?${params.toString()}`;
}

/**
 * The partner's recommendation a pricing URL carries (from `lc_rec` only, never from the page's
 * selection), or null when it carries none or an incoherent one (no code, an unknown plan, a
 * credit tier the plan does not offer, an odd cycle): the page then says nothing rather than
 * vouch for a plan nobody recommended.
 */
export function partnerRecommendationFromSearch(search: URLSearchParams): (PartnerRecommendation & { code: string }) | null {
  const [planRaw, tierRaw, cycleRaw, ...rest] = (search.get(PARTNER_RECOMMENDATION_PARAM) ?? '').split('.');
  if (rest.length > 0) return null;
  const code = (search.get(PARTNER_LINK_PARAM) ?? '').trim().toUpperCase();
  if (!/^[A-Z0-9][A-Z0-9_-]{2,63}$/.test(code)) return null;
  const plan = (planRaw ?? '').toLowerCase() as PlanKey;
  if (!(PLAN_KEYS as readonly string[]).includes(plan)) return null;
  if (!/^\d+$/.test(tierRaw ?? '')) return null;
  const creditTier = Number(tierRaw);
  if (!tierFitsPlan(plan, creditTier)) return null;
  if (cycleRaw !== 'monthly' && cycleRaw !== 'yearly') return null;
  return { code, plan, creditTier, cycle: cycleRaw };
}

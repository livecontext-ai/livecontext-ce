/**
 * Single source of truth for pricing constants and calculation logic.
 * Used by: pricing page, insufficient credits modal, billing tests.
 *
 * Mirrors CreditTierConstants.java; keep both in sync.
 *
 * Pricing curve revised 2026-05-27:
 * - Pro/Starter credit packs are degressive down to $0.70 / 1k credits.
 * - Team has explicit premium pack costs down to $0.80 / 1k credits.
 * - PAYG stays separate at $1.25 / 1k credits.
 * - Yearly discount applies to the base plan only, not to credits.
 */

/**
 * Public GitHub repository for the self-hosted Community Edition.
 * Single source of truth for the "Self-hosted" deployment CTA across every pricing
 * surface (landing, settings, insufficient-credits modal). The CE tree is published
 * by `export-ce.sh --push <giturl>`; update this constant to the final public repo URL.
 */
export const SELF_HOSTED_GITHUB_URL = 'https://github.com/livecontext-ai/livecontext-ce';

/**
 * Canonical credits→USD "list" scale: 1 credit = $0.001 USD. Defined backend-side
 * (ModelPricingService / migration V80) - calculateCost divides provider USD/1M-token
 * rates by 1000, so a stored credit amount IS the dollar cost × 1000. CE shows spend in
 * dollars (see lib/format-cost.ts), so credit-denominated ledger amounts - the local CE
 * ledger AND the cloud-linked relay mirror - are multiplied by this to render real dollars.
 *
 * NOTE: this is the list scale, NOT the per-pack purchase price (those vary $0.0008-$0.00125
 * per credit, see CREDIT_COSTS/CREDIT_TIERS), and the cloud LLM billing multiplier (×1.8) is
 * already baked into the stored credit amount - so the displayed $ is the billed value,
 * margin included (the deliberate, balance-reconciling choice over stripping the ×1.8).
 */
export const CREDIT_LIST_USD = 0.001;

/**
 * PAYG one-time top-up purchase price: $1.25 per 1,000 credits (see the header note).
 * Used to render the referral reward's dollar value from its configured credit amount.
 */
export const PAYG_USD_PER_1K = 1.25;

export const CREDIT_TIERS = [5_000, 10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000, 5_000_000, 10_000_000];
export const CREDIT_COSTS = [0, 10, 22, 42, 80, 185, 365, 720, 3_500, 7_000];
export const TEAM_CREDIT_COSTS = [0, 15, 30, 55, 100, 230, 430, 825, 4_000, 8_000];

export const BASE_PRICES: Record<string, number> = {
  starter: 10,
  pro: 24,
  team: 49,
};

export const STARTER_MAX_CREDITS = 100_000;

/**
 * Highest tier index shown on the slider by default (index 7 = 1,000,000 credits).
 * The two tiers above (5M, 10M) carry intimidating prices for a casual visitor, so
 * they are hidden behind the `?tiers=full` unlock (see resolveMaxTierIndex). The
 * underlying CREDIT_TIERS array stays 10-long - the cap is display-only and the
 * backend (CreditTierConstants.java) keeps accepting indices 0-9.
 */
export const DEFAULT_MAX_TIER_INDEX = 7;

/**
 * Resolve the slider's max selectable index.
 * Full range (last index, 10M) when the hidden tiers are explicitly unlocked OR when
 * the user's current subscription already sits on a hidden tier - otherwise an existing
 * 5M/10M customer would be silently clamped down to 1M and could downgrade by accident.
 */
export function resolveMaxTierIndex(fullTiersUnlocked: boolean, subscriptionTierIndex = 0): number {
  if (fullTiersUnlocked || subscriptionTierIndex > DEFAULT_MAX_TIER_INDEX) {
    return CREDIT_TIERS.length - 1;
  }
  return DEFAULT_MAX_TIER_INDEX;
}

/**
 * Clamp a selected tier index into [0, maxTierIndex]. Used to keep the selected index in
 * sync with the slider cap: when the cap shrinks (e.g. the hidden tiers get re-hidden while
 * the user is parked on 5M/10M), the index must follow so the price/checkout never reflect a
 * tier the slider no longer shows.
 */
export function clampTierIndex(tierIndex: number, maxTierIndex: number): number {
  return Math.min(Math.max(tierIndex, 0), maxTierIndex);
}

/**
 * Ordered feature-label keys per plan card. Each key maps to a single i18n string at
 * `pricing.planCards.features.<key>` (shared keys are translated once). The sentinel
 * 'creditsDynamic' is rendered in the component with the live slider amount.
 *
 * Lists are authored as explicit supersets: every tier visibly includes everything the
 * tier below it offers, so Enterprise never appears to have fewer features than Team.
 * The coherence is enforced by a unit test via CAPABILITY_KEYS.
 */
export const PLAN_FEATURE_KEYS: Record<string, string[]> = {
  free: ['creditsFree', 'users1', 'workspaces1', 'concurrent1', 'storage100mb', 'logs7', 'supportCommunity'],
  starter: ['creditsDynamic', 'users1', 'workspaces1', 'concurrent5', 'storage1gb', 'logs30', 'versioning', 'apiAccess', 'analyticsBasic', 'supportEmail'],
  pro: ['creditsDynamic', 'users1', 'workspaces3', 'concurrent20', 'storage10gb', 'logs30', 'versioning', 'apiAccess', 'priorityExecution', 'executionSearch', 'analyticsDetailed', 'supportPriority'],
  team: ['creditsDynamic', 'users25', 'workspaces10', 'concurrent50', 'storage100gb', 'logs60', 'versioning', 'apiAccess', 'priorityExecution', 'executionSearch', 'sso', 'rbac', 'auditLogs', 'sharedTemplates', 'centralizedBilling', 'analyticsTeam', 'supportSla'],
  enterprise: ['creditsCustom', 'usersUnlimited', 'workspacesUnlimited', 'concurrentUnlimited', 'storage1tb', 'logsCustom', 'versioning', 'apiAccess', 'priorityExecution', 'executionSearch', 'sso', 'rbac', 'auditLogs', 'sharedTemplates', 'centralizedBilling', 'dedicatedInstance', 'compliance', 'overageProtection', 'analyticsAdvanced', 'sla999', 'accountManager', 'onboarding'],
};

/**
 * Purely-additive capability keys (excludes value-scaled dimensions such as
 * credits/users/concurrent/storage/logs and the replaced support/analytics dims).
 * Used by the coherence test: each tier must include every capability of the tier below.
 */
export const CAPABILITY_KEYS = [
  'versioning', 'apiAccess', 'priorityExecution', 'executionSearch',
  'sso', 'rbac', 'auditLogs', 'sharedTemplates', 'centralizedBilling',
  'dedicatedInstance', 'compliance', 'overageProtection', 'accountManager', 'onboarding',
];

export function getCreditCost(planId: string, creditTierIndex: number): number {
  return planId === 'team'
    ? TEAM_CREDIT_COSTS[creditTierIndex]
    : CREDIT_COSTS[creditTierIndex];
}

export function calcPrice(planId: string, cycle: 'monthly' | 'yearly', creditTierIndex: number): number {
  const base = BASE_PRICES[planId];
  if (base === undefined) return 0;
  const creditCost = getCreditCost(planId, creditTierIndex);
  const basePrice = cycle === 'yearly' ? Math.round(base * 0.8) : base;
  return basePrice + creditCost;
}

export function formatTierLabel(tier: number): string {
  if (tier >= 1_000_000) return `${tier / 1_000_000}M`;
  if (tier >= 1_000) return `${tier / 1_000}K`;
  return String(tier);
}

/**
 * The side-by-side plan comparison, DERIVED from `PLAN_FEATURE_KEYS`.
 *
 * <p><b>Why derived and not written out.</b> `pricing-constants.ts` already
 * declares what each plan includes, as an ordered list of feature keys, and the
 * plan cards on the settings page and on the landing both render it. A matrix
 * typed out by hand beside it would be a second answer to the same question,
 * free to drift the day someone adds a feature to a card and forgets the table -
 * and a comparison that disagrees with the card the reader just left is worse
 * than no comparison at all. So this file only REARRANGES those lists: it turns
 * "what does Pro include" into "what does this row say across the five plans".
 *
 * <p><b>Two kinds of row, and the difference matters.</b> A SCALE row is a
 * dimension every plan answers with a different value (storage, users, log
 * retention): the cell names which key that plan carries, and the renderer shows
 * its short value. A FLAG row is a capability a plan either has or has not: the
 * cell is a boolean and the renderer shows a check or a dash. Scale rows are
 * declared here, in `DIMENSIONS`; everything else becomes a flag row on its own,
 * which is what keeps the matrix TOTAL.
 *
 * <p><b>Totality is the invariant.</b> Every key that appears in any plan's list
 * appears in exactly one row: a key claimed by no dimension and named in no
 * section still lands in the last section rather than vanishing. That is the
 * property `plan-comparison.test.ts` pins, and it is what makes adding a feature
 * to `PLAN_FEATURE_KEYS` enough - the comparison picks it up with no edit here.
 */

import { PLAN_FEATURE_KEYS } from './pricing-constants';

/** The columns, cheapest first. Same ids as `PLAN_FEATURE_KEYS` / `BASE_PRICES`. */
export const COMPARISON_PLAN_IDS = ['free', 'starter', 'pro', 'team', 'enterprise'] as const;

export type ComparisonPlanId = (typeof COMPARISON_PLAN_IDS)[number];

/**
 * A dimension every plan scales along. The key lists are ordered cheapest-first
 * for readability only; membership is what selects a plan's cell.
 */
const DIMENSIONS: ReadonlyArray<{ id: string; keys: readonly string[]; fallback?: string }> = [
  { id: 'credits', keys: ['creditsFree', 'creditsDynamic', 'creditsCustom'] },
  // Paid plans hold no separate pot because their credits already fund agents;
  // the fallback says so instead of rendering a "not included" cross.
  { id: 'aiCredits', keys: ['aiCreditsFree'], fallback: 'aiCreditsIncluded' },
  { id: 'nodes', keys: ['nodesCore', 'nodesPublishing', 'nodesAll'] },
  { id: 'concurrent', keys: ['concurrent1', 'concurrent5', 'concurrent20', 'concurrent50', 'concurrentUnlimited'] },
  { id: 'storage', keys: ['storage100mb', 'storage1gb', 'storage10gb', 'storage100gb', 'storage1tb'] },
  { id: 'logs', keys: ['logs7', 'logs30', 'logs90', 'logsCustom'] },
  { id: 'users', keys: ['users1', 'users25', 'usersUnlimited'] },
  { id: 'workspaces', keys: ['workspaces1', 'workspaces3', 'workspaces10', 'workspacesUnlimited'] },
  { id: 'variables', keys: ['variables3', 'variables25', 'variables100', 'variables500', 'variablesUnlimited'] },
  { id: 'analytics', keys: ['analyticsBasic', 'analyticsDetailed', 'analyticsTeam', 'analyticsAdvanced'] },
  { id: 'support', keys: ['supportCommunity', 'supportEmail', 'supportPriority', 'supportSla'] },
];

/**
 * Reading order. `dimensions` names scale rows by dimension id, `flags` names
 * capability rows by feature key; both render in the order written here.
 *
 * A flag key named in no section is not dropped - see `buildPlanComparison`.
 */
const SECTIONS: ReadonlyArray<{ id: string; dimensions: readonly string[]; flags: readonly string[] }> = [
  {
    id: 'usage',
    dimensions: ['credits', 'aiCredits', 'nodes', 'concurrent', 'storage', 'logs'],
    flags: ['priorityExecution', 'overageProtection'],
  },
  {
    id: 'building',
    dimensions: ['variables'],
    flags: ['versioning', 'apiAccess', 'executionSearch', 'cePlatformCreds', 'vectorSearch', 'browserAgent'],
  },
  {
    id: 'collaboration',
    dimensions: ['users', 'workspaces'],
    flags: ['sharedTemplates', 'centralizedBilling'],
  },
  {
    id: 'security',
    dimensions: [],
    flags: ['sso', 'rbac', 'auditLogs', 'compliance', 'dedicatedInstance'],
  },
  {
    id: 'support',
    dimensions: ['analytics', 'support'],
    flags: ['sla999', 'accountManager', 'onboarding'],
  },
];

/** A dimension: each cell names the feature key that plan carries, or null. */
export interface ScaleRow {
  kind: 'scale';
  /** Dimension id. Labelled by `pricing.compare.dimensions.<id>`. */
  id: string;
  /** planId -> the feature key this plan carries for the dimension, or null. */
  cells: Record<ComparisonPlanId, string | null>;
  /**
   * What a plan with NO key for this dimension should read, as
   * `pricing.compare.values.<fallbackValueKey>`. Absent = the cell keeps the
   * default "not included" cross.
   *
   * <p>Exists because a missing cell does not always mean "you don't get this".
   * The AI allowance is the case: only Free has a separate pot, and the paid plans
   * have none precisely because their normal credits already fund agents. A cross
   * there would tell a paying visitor they cannot run an agent, which is the
   * opposite of the truth.
   */
  fallbackValueKey?: string;
}

/** A capability: each cell says whether the plan includes it. */
export interface FlagRow {
  kind: 'flag';
  /** Feature key. Labelled by the card's own `pricing.planCards.features.<id>`. */
  id: string;
  cells: Record<ComparisonPlanId, boolean>;
}

export type ComparisonRow = ScaleRow | FlagRow;

export interface ComparisonSection {
  /** Titled by `pricing.compare.sections.<id>`. */
  id: string;
  rows: ComparisonRow[];
}

function keysOf(planId: ComparisonPlanId): string[] {
  return PLAN_FEATURE_KEYS[planId] ?? [];
}

function scaleRow(dimensionId: string, keys: readonly string[], fallbackValueKey?: string): ScaleRow {
  const cells = {} as Record<ComparisonPlanId, string | null>;
  for (const planId of COMPARISON_PLAN_IDS) {
    const owned = keysOf(planId);
    cells[planId] = keys.find((key) => owned.includes(key)) ?? null;
  }
  return { kind: 'scale', id: dimensionId, cells, fallbackValueKey };
}

function flagRow(featureKey: string): FlagRow {
  const cells = {} as Record<ComparisonPlanId, boolean>;
  for (const planId of COMPARISON_PLAN_IDS) {
    cells[planId] = keysOf(planId).includes(featureKey);
  }
  return { kind: 'flag', id: featureKey, cells };
}

/** Every feature key any plan carries, in cheapest-plan-first order of appearance. */
export function allFeatureKeys(): string[] {
  const seen: string[] = [];
  for (const planId of COMPARISON_PLAN_IDS) {
    for (const key of keysOf(planId)) {
      if (!seen.includes(key)) seen.push(key);
    }
  }
  return seen;
}

/** The keys any dimension claims, whether or not a plan currently carries them. */
function claimedByDimension(): Set<string> {
  return new Set(DIMENSIONS.flatMap((d) => d.keys));
}

/**
 * The matrix: sections, each with its rows, each row with one cell per plan.
 *
 * A feature key claimed by no dimension and named in no section is appended to
 * the LAST section as a flag row, so a key added to `PLAN_FEATURE_KEYS` alone is
 * always visible somewhere - never silently absent from the comparison.
 */
export function buildPlanComparison(): ComparisonSection[] {
  const dimensionKeys = claimedByDimension();
  const placedFlags = new Set(SECTIONS.flatMap((s) => s.flags));

  const sections: ComparisonSection[] = SECTIONS.map((section) => {
    const rows: ComparisonRow[] = [];
    for (const dimensionId of section.dimensions) {
      const dimension = DIMENSIONS.find((d) => d.id === dimensionId);
      // A section naming a dimension that no longer exists renders one row fewer
      // rather than throwing inside a dialog the reader opened to compare prices.
      if (dimension) rows.push(scaleRow(dimension.id, dimension.keys, dimension.fallback));
    }
    for (const key of section.flags) rows.push(flagRow(key));
    return { id: section.id, rows };
  });

  const unplaced = allFeatureKeys().filter((key) => !dimensionKeys.has(key) && !placedFlags.has(key));
  if (unplaced.length > 0) {
    const last = sections[sections.length - 1];
    for (const key of unplaced) last.rows.push(flagRow(key));
  }

  return sections;
}

/**
 * The column a backend plan code sits in, or null when none does.
 *
 * Every ENTERPRISE SKU maps to the one Enterprise column: the suffix is the
 * product name, and new SKUs ship far more often than new tiers (same reasoning
 * as `planTier.userRank`). CE and "no subscription" have no column: a
 * self-hosted install compares plans without one of them being "current", and
 * `__NONE__` is what the cloud sends for a connected account that has none.
 */
export function resolveComparisonPlanId(planCode: string | null | undefined): ComparisonPlanId | null {
  const code = (planCode ?? '').trim().toUpperCase();
  if (!code || code === '__NONE__' || code === 'CE') return null;
  if (code.startsWith('ENTERPRISE')) return 'enterprise';
  const match = COMPARISON_PLAN_IDS.find((id) => id.toUpperCase() === code);
  return match ?? null;
}

/**
 * The cheapest column that satisfies a requirement, for a comparison opened from
 * a gate ("this node needs STARTER"). Unknown or FREE requirements emphasise
 * nothing: there is no upgrade to point at.
 */
export function resolveRequiredPlanId(requiredPlanCode: string | null | undefined): ComparisonPlanId | null {
  const resolved = resolveComparisonPlanId(requiredPlanCode);
  return resolved && resolved !== 'free' ? resolved : null;
}

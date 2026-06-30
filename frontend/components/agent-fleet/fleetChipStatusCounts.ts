/**
 * Resolve the status-count badge for a fleet resource chip - the single source of
 * truth shared by useSingleAgentFleet and useAgentFleetState (the two hooks built
 * this inline and DRIFTED into the same bug: stamping the whole FAMILY aggregate
 * onto every resource leaf, which the leaf-summing rollup then multiplied by the
 * resource count - e.g. 31 interfaces × 36 calls = 1116).
 *
 * The fix lives here: resource-family chips (table/interface/workflow/skill/
 * application) read their count from the PER-RESOURCE breakdown keyed by the
 * leaf's own id, so each leaf shows its own usage and the category/aggregator -
 * which sum their leaves unchanged - show the honest total (sum of the parts).
 * tool/web_search chips keep their per-tool-name aggregate; model chips keep the
 * per-model aggregate with BUDGET_EXHAUSTED carved out.
 */

/** Resource families tracked per specific resource id, NOT by the family tool name. */
export const RESOURCE_FAMILY_TYPES = new Set(['skill', 'interface', 'workflow', 'table', 'application']);

export interface FleetChipStatsLookups {
  /** Per-tool aggregate, keyed by normalized tool name - for tool / web_search chips. */
  toolStats?: Map<string, { successCount: number; failureCount: number }>;
  /** Per-model aggregate, keyed by model name - for the model chip. */
  modelStats?: Map<string, { successCount: number; failureCount: number; budgetExhaustedCount?: number }>;
  /** Per-RESOURCE aggregate, keyed by the resource id - for resource-family chips. */
  resourceStats?: Map<string, { successCount: number; failureCount: number }>;
  /** The agent's model name - needed to look up the model chip's stats. */
  modelName?: string;
}

/** Normalize a tool label to the key shape the per-tool stats map is built with. */
function normalizeToolKey(label: string): string {
  return label.toLowerCase().replace(/[\s-]/g, '_');
}

export function resolveFleetChipStatusCounts(
  res: { type: string; id: string | number; label: string; toolName?: string },
  lookups: FleetChipStatsLookups,
): Record<string, number> | undefined {
  if (res.type === 'tool' || res.type === 'web_search') {
    const stat = lookups.toolStats?.get(normalizeToolKey(res.toolName || res.label));
    return stat ? { COMPLETED: stat.successCount, FAILED: stat.failureCount } : undefined;
  }

  if (res.type === 'model') {
    const stat = lookups.modelStats?.get(lookups.modelName || '');
    if (stat && (stat.successCount || stat.failureCount)) {
      // BUDGET_EXHAUSTED is a SUBSET of failureCount - surfaced separately so the
      // chip can render an amber "throttled by credits" indicator; NodeStatusBadge
      // subtracts the subset from FAILED so the two never double-count.
      return { COMPLETED: stat.successCount, FAILED: stat.failureCount, BUDGET_EXHAUSTED: stat.budgetExhaustedCount || 0 };
    }
    return undefined;
  }

  if (RESOURCE_FAMILY_TYPES.has(res.type)) {
    const stat = lookups.resourceStats?.get(String(res.id));
    return stat ? { COMPLETED: stat.successCount, FAILED: stat.failureCount } : undefined;
  }

  return undefined;
}

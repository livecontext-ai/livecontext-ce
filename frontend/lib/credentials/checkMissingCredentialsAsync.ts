/**
 * Imperative one-shot helper that runs the full missing-credential check
 * against a workflow plan. Single source of truth shared by:
 *
 *  - {@code useMissingCredentials} (TanStack Query reactive form, used by
 *    {@code PublicationInfoPanel}).
 *  - {@code ApplicationLayout.initializeApplication} pre-flight gate (needs
 *    a synchronous-feeling control flow inside a {@code useCallback} where
 *    sub-hooks can't fire).
 *
 * <p>Both call paths must produce identical results - historically the gate
 * and the panel diverged silently when the matching strategy or scrub
 * sentinel changed in only one place. Centralising here removes that drift.
 */
import { orchestratorApi, type Credential } from '@/lib/api/orchestrator';
import { ToolDataService } from '@/app/workflows/builder/services/workflowPlanImporter/ToolDataService';
import {
  extractMissingCredentialsFromPlan,
  type MissingCredentialsResult,
  type ToolDataLike,
  type WorkflowPlanLike,
} from './missingCredentials';

/**
 * Walk the plan to find every (apiSlug/toolSlug) pair the helper will need
 * to consult - covers MCP steps and agent.tools[] references.
 */
function collectToolIds(plan: WorkflowPlanLike): string[] {
  const ids = new Set<string>();
  for (const mcp of plan.mcps ?? []) {
    if (typeof mcp?.id === 'string' && mcp.id) ids.add(mcp.id);
  }
  for (const agent of plan.agents ?? []) {
    const tools = (agent?.tools ?? []) as unknown[];
    for (const ref of tools) {
      if (typeof ref === 'string' && ref) ids.add(ref);
    }
  }
  return Array.from(ids);
}

/**
 * Map the batch result (keyed by tool slug) back to a Map keyed by the
 * caller's tool ids, so the extractor can look up by the id it already
 * has. Mirrors PlanParserService.extractToolSlug.
 */
function buildToolDataMap(
  toolIds: string[],
  batch: Map<string, { toolData?: unknown }>
): Map<string, ToolDataLike> {
  const out = new Map<string, ToolDataLike>();
  for (const id of toolIds) {
    const slug = id.includes('/') ? id.split('/').pop()! : id;
    const entry = batch.get(slug) ?? batch.get(id);
    if (entry?.toolData) {
      out.set(id, entry.toolData as ToolDataLike);
    }
  }
  return out;
}

export interface CheckMissingCredentialsAsyncOptions {
  /** When true, swallow individual fetch failures and proceed with empty data
   *  for the failed source. Default: true (the gate prefers a permissive
   *  fail-open over blocking the user when the network is flaky). */
  failOpen?: boolean;
}

/**
 * One-shot version of the missing-credentials check. Runs the user-cred
 * fetch and the tool-batch fetch in parallel, then delegates to the pure
 * extractor.
 */
export async function checkMissingCredentialsAsync(
  plan: WorkflowPlanLike,
  options: CheckMissingCredentialsAsyncOptions = {}
): Promise<MissingCredentialsResult> {
  const { failOpen = true } = options;

  const toolIds = collectToolIds(plan);

  const userCredsPromise = failOpen
    ? orchestratorApi.getAllCredentials().catch(() => [] as Credential[])
    : orchestratorApi.getAllCredentials();

  const toolBatchPromise =
    toolIds.length === 0
      ? Promise.resolve(new Map())
      : failOpen
        ? ToolDataService.fetchToolsBatch(toolIds).catch(() => new Map())
        : ToolDataService.fetchToolsBatch(toolIds);

  const [userCreds, batch] = await Promise.all([userCredsPromise, toolBatchPromise]);

  const toolDataMap = buildToolDataMap(toolIds, batch);
  return extractMissingCredentialsFromPlan(plan, toolDataMap, userCreds);
}

/**
 * Exported for the reactive hook variant which prefers a custom TanStack
 * Query layout over a single Promise.
 */
export const __testing = {
  collectToolIds,
  buildToolDataMap,
};

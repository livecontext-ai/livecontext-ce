'use client';

/**
 * Detects which credentials a (typically acquired) workflow needs that the
 * current user has not yet connected.
 *
 * Two consumers today:
 *  - {@code SetupRequiredState} (pre-flight gate in {@code ApplicationLayout})
 *  - {@code PublicationInfoPanel} Setup block on {@code /app/applications/[id]}
 *
 * Both render the same data, so the hook is the single source of truth.
 *
 * <p>Plan source priority:
 * <ol>
 *   <li>{@code planSnapshot} prop (fast path - already in
 *       {@code WorkflowPublication.planSnapshot}, no extra round-trip).</li>
 *   <li>{@code workflowService.getWorkflow(workflowId).plan} (fallback when
 *       no snapshot - e.g. older publications, custom workflows).</li>
 * </ol>
 *
 * <p>Tool data: required to know the {@code iconSlug} and credential
 * requirement list of MCP/agent steps. Fetched in batch via
 * {@code ToolDataService.fetchToolsBatch} which has a session-scoped cache -
 * the second call for the same set of tool ids is free.
 *
 * <p>User credentials: shared TanStack Query cache key
 * {@code ['user-credentials']} - same key as {@code ValidationContext} and
 * {@code CredentialSection}, so the cache is reused across the app.
 *
 * <p>Reactivity: {@code refetch} invalidates the user-credentials cache so
 * the hook re-evaluates after a wizard completion. The plan + tool data
 * caches are stable for the session.
 */
import * as React from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { orchestratorApi, type Credential } from '@/lib/api/orchestrator';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import { ToolDataService } from '@/app/workflows/builder/services/workflowPlanImporter/ToolDataService';
import {
  extractMissingCredentialsFromPlan,
  type ManualMissingCredential,
  type ToolDataLike,
  type WizardableMissingCredential,
  type WorkflowPlanLike,
} from '@/lib/credentials/missingCredentials';

const USER_CREDS_QUERY_KEY = ['user-credentials'] as const;

interface UseMissingCredentialsOptions {
  /** Workflow id to scan. Hook is disabled when undefined. */
  workflowId?: string;
  /**
   * Optional pre-fetched plan (e.g. {@code WorkflowPublication.planSnapshot}).
   * When present we skip the {@code getWorkflow} round-trip on first paint.
   * Falls back to fetching when the snapshot is null (older publications).
   */
  planSnapshot?: WorkflowPlanLike | null;
  /**
   * Disable entirely (e.g. anonymous marketplace preview). When false the hook
   * fires no queries and returns the empty state.
   */
  enabled?: boolean;
}

export interface UseMissingCredentialsResult {
  wizardable: WizardableMissingCredential[];
  manual: ManualMissingCredential[];
  /** Total wizardable + manual count - drives the "Setup required (N)" badge. */
  count: number;
  isLoading: boolean;
  error: Error | null;
  /** Re-runs the user-creds query so the wizard's onComplete reflects immediately. */
  refetch: () => Promise<void>;
}

const EMPTY_RESULT: UseMissingCredentialsResult = {
  wizardable: [],
  manual: [],
  count: 0,
  isLoading: false,
  error: null,
  refetch: async () => {},
};

export function useMissingCredentials(
  opts: UseMissingCredentialsOptions
): UseMissingCredentialsResult {
  const { workflowId, planSnapshot, enabled = true } = opts;
  const isActive = enabled && !!workflowId;
  const queryClient = useQueryClient();

  // 1) User credentials (shared cache with ValidationContext / CredentialSection).
  const userCredsQuery = useQuery<Credential[]>({
    queryKey: USER_CREDS_QUERY_KEY,
    queryFn: () => orchestratorApi.getAllCredentials(),
    enabled: isActive,
    staleTime: 30_000,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });

  // 2) Plan - prefer the prop snapshot, fall back to network.
  // Cache forever for this session (a freshly acquired workflow's plan does
  // not change while the panel is open).
  const planQuery = useQuery<WorkflowPlanLike | null>({
    queryKey: ['workflow-plan-for-cred-check', workflowId],
    queryFn: async () => {
      if (planSnapshot) return planSnapshot;
      if (!workflowId) return null;
      const wf = await workflowService.getWorkflow(workflowId);
      return (wf?.plan as WorkflowPlanLike | undefined) ?? null;
    },
    enabled: isActive,
    staleTime: Infinity,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });

  // 3) Tool data batch - derived from the plan's MCP + agent steps. Same
  // session-scoped cache the workflow builder uses, so opening the builder
  // afterwards is free.
  const toolIds = React.useMemo(() => {
    const plan = planQuery.data;
    if (!plan) return [];
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
  }, [planQuery.data]);

  const toolDataQuery = useQuery<Map<string, ToolDataLike>>({
    queryKey: ['tool-data-for-cred-check', toolIds.slice().sort().join('|')],
    queryFn: async () => {
      if (toolIds.length === 0) return new Map();
      const batch = await ToolDataService.fetchToolsBatch(toolIds);
      // Re-map by the original tool id (the batch keys by tool slug - the
      // last segment after the optional `apiSlug/` prefix; mirrors
      // PlanParserService.extractToolSlug).
      const out = new Map<string, ToolDataLike>();
      for (const id of toolIds) {
        const slug = id.includes('/') ? id.split('/').pop()! : id;
        const entry = batch.get(slug) ?? batch.get(id);
        if (entry?.toolData) {
          out.set(id, entry.toolData as ToolDataLike);
        }
      }
      return out;
    },
    enabled: isActive && toolIds.length > 0,
    staleTime: Infinity,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });

  // 4) Compute the result. Memoize so React.useMemo dependents don't churn
  // unless one of the underlying caches actually changed.
  const computed = React.useMemo(() => {
    if (!isActive) return { wizardable: [], manual: [] };
    if (!planQuery.data) return { wizardable: [], manual: [] };
    return extractMissingCredentialsFromPlan(
      planQuery.data,
      // toolIds === 0 path: pass an empty map so cores + triggers still scan.
      toolDataQuery.data ?? new Map(),
      userCredsQuery.data ?? []
    );
  }, [isActive, planQuery.data, toolDataQuery.data, userCredsQuery.data]);

  const refetch = React.useCallback(async () => {
    // Wizard completion = new user creds. Invalidate so the next render
    // re-evaluates the gate. Plan + tool data don't need invalidation.
    await queryClient.invalidateQueries({ queryKey: USER_CREDS_QUERY_KEY });
  }, [queryClient]);

  if (!isActive) return EMPTY_RESULT;

  const isLoading =
    userCredsQuery.isPending ||
    planQuery.isPending ||
    (toolIds.length > 0 && toolDataQuery.isPending);
  const error =
    (planQuery.error as Error | null) ??
    (userCredsQuery.error as Error | null) ??
    (toolDataQuery.error as Error | null) ??
    null;

  return {
    wizardable: computed.wizardable,
    manual: computed.manual,
    count: computed.wizardable.length + computed.manual.length,
    isLoading,
    error,
    refetch,
  };
}

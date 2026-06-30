import { useEffect, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { orchestratorApi } from '@/lib/api';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';
import { useStepCompletionInvalidation } from './useStepCompletionInvalidation';

export interface AggregatedStepData {
  status: string;
  alias: string;
  toolId: string;
  startTime: string | null;
  endTime: string | null;
  statusCounts?: {
    completed?: number;
    failed?: number;
    skipped?: number;
    running?: number;
  };
}

export function useAggregatedSteps(rawRunId: string | undefined) {
  // "latest" is a placeholder - skip until resolved to a real run ID
  const runId = rawRunId === 'latest' ? undefined : rawRunId;
  const queryClient = useQueryClient();
  const prevRunIdRef = useRef<string | undefined>(undefined);

  // Invalidate cache when runId changes (new run or step-by-step execution)
  useEffect(() => {
    if (runId && runId !== prevRunIdRef.current) {
      queryClient.invalidateQueries({
        queryKey: ['aggregated-steps'],
      });
      prevRunIdRef.current = runId;
    }
  }, [runId, queryClient]);

  // Invalidate the aggregated-steps cache when any step of this run completes.
  // Throttled via the shared listener (parity with useStepData / useRunData) so
  // a busy run's stepExecutionCompleted stream cannot fan out into a refetch
  // storm. No stepAlias filter: the canvas aggregation spans every alias.
  useStepCompletionInvalidation({
    runId,
    onInvalidate: () => {
      queryClient.invalidateQueries({
        queryKey: ['aggregated-steps', runId],
      });
    },
  });

  // In publication preview the auth'd /aggregated-steps endpoint is forbidden
  // (cross-tenant leak risk). The showcase clone's aggregated step list is
  // already shipped inside /run-state, so the canvas hydrates from there and
  // this hook returns an empty list - callers tolerate it as "no aggregation
  // data, fall back to flat view".
  const inPreview = !!getActivePublicPreview();

  // Fetch aggregated steps using React Query
  const {
    data: aggregatedSteps = [],
    isLoading: loading,
    error: queryError,
  } = useQuery({
    queryKey: ['aggregated-steps', runId],
    queryFn: async () => {
      if (!runId) return [];
      const data = await orchestratorApi.getAggregatedSteps(runId);
      return data as AggregatedStepData[];
    },
    enabled: !!runId && !inPreview,
    staleTime: 3000, // 3 seconds - quick refresh for execution data
    refetchOnMount: true,
    refetchOnWindowFocus: false,
  });

  const error = queryError instanceof Error ? queryError.message : null;

  return { aggregatedSteps, loading, error };
}

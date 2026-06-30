import { useEffect, useMemo, useRef } from 'react';
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { orchestratorApi } from '@/lib/api';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';
import { useStepCompletionInvalidation } from './useStepCompletionInvalidation';

// Page size for the /steps/paged calls behind useStepData. Matches the backend
// cap (WorkflowRunQueryController.safeSize=500). The hook switches to
// useInfiniteQuery so a single page never exceeds this size - pages stack as
// the consumer (StepDataTable) scrolls past the loaded set via the
// LoadOlderSentinel pattern. Daily Email Digest routinely has 3490 rows per
// stepAlias, so a single fixed-size fetch silently truncated the table to
// the latest 100 rows before this hook was paged (audit 2026-05-13).
const PAGE_SIZE = 500;

export interface WorkflowStepData {
  id: number;
  workflowRunId: string;
  runId: string;
  stepAlias: string;
  toolId: string;
  status: string;
  startTime: string | null;
  endTime: string | null;
  httpStatus: number | null;
  outputStorageId: string | null;
  iteration: number | null;
  itemIndex: number | null;
  epoch: number | null;
  spawn: number | null;
  errorMessage: string | null;
  inputData: Record<string, any> | null;
  metadata: Record<string, any> | null;
  tenantId: string;
  // Node type identification
  nodeType: string | null;
  normalizedKey: string | null;
  // Decision node fields
  conditionExpression: string | null;
  conditionResult: boolean | null;
  selectedBranch: string | null;
  // Loop node fields
  loopId: string | null;
  loopIteration: number | null;
  loopExitReason: string | null;
  // Merge node fields
  mergeStrategy: string | null;
  mergeReceivedBranches: string[] | null;
  mergeSkippedBranches: string[] | null;
  // Skip tracking fields
  skipReason: string | null;
  skipSourceNode: string | null;
  // Item tracking fields
  triggerId: string | null;
  itemId: string | null;
  itemNumber: number | null;
}

export function useStepData(runId: string | undefined, stepAlias: string | undefined) {
  const queryClient = useQueryClient();
  const prevRunIdRef = useRef<string | undefined>(undefined);
  // Inspector is disabled in publication preview (see useRunData).
  const inPreview = !!getActivePublicPreview();

  // Invalidate cache when runId changes (new run or step-by-step execution)
  useEffect(() => {
    if (runId && runId !== prevRunIdRef.current) {
      queryClient.invalidateQueries({
        queryKey: ['step-data'],
      });
      prevRunIdRef.current = runId;
    }
  }, [runId, queryClient]);

  // Get workflow run UUID from public runId (cached & shared with useRunData)
  const { data: workflowRunId } = useQuery({
    queryKey: ['workflow-run', runId],
    queryFn: async () => {
      if (!runId) return null;
      const run = await orchestratorApi.getRun(runId);
      return run.id as string;
    },
    enabled: !!runId && !inPreview,
    staleTime: 5 * 60 * 1000, // 5 min - run UUID never changes
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });

  // Invalidate this alias's step-data cache when a matching step completes.
  // Throttled via the shared listener so a busy run's stepExecutionCompleted
  // stream cannot fan out into a /steps/paged 429 storm (see
  // useStepCompletionInvalidation). exact: false for symmetry with useRunData
  // and future-proofing if the queryKey ever gains a suffix (page, filter).
  useStepCompletionInvalidation({
    runId,
    stepAlias,
    enabled: !!stepAlias,
    onInvalidate: () => {
      queryClient.invalidateQueries({
        queryKey: ['step-data', workflowRunId, stepAlias],
        exact: false,
      });
    },
  });

  // Fetch step data filtered by stepAlias via the paginated backend endpoint.
  // Uses useInfiniteQuery so workflows with >PAGE_SIZE rows per alias (e.g. the
  // Daily Email Digest's parse_headers alias has 3490 rows across 136 epochs)
  // surface fully: the StepDataTable's LoadOlderSentinel triggers fetchNextPage
  // when it scrolls into view. Before this switch, the hook fetched one fixed
  // page and the table silently truncated to the latest rows.
  const {
    data: infiniteData,
    isLoading: loading,
    error: queryError,
    hasNextPage,
    fetchNextPage,
    isFetchingNextPage,
  } = useInfiniteQuery({
    queryKey: ['step-data', workflowRunId, stepAlias],
    queryFn: async ({ pageParam }) => {
      if (!workflowRunId || !stepAlias) {
        return { content: [] as WorkflowStepData[], totalElements: 0, totalPages: 0, page: 0, size: PAGE_SIZE };
      }
      const result = await orchestratorApi.getRunStepsPaged(
        workflowRunId,
        stepAlias,
        pageParam as number,
        PAGE_SIZE,
      );
      return result;
    },
    enabled: !!workflowRunId && !!stepAlias && !inPreview,
    initialPageParam: 0,
    getNextPageParam: (lastPage, allPages) => {
      if (!lastPage) return undefined;
      // Stop when we've fetched all pages the backend knows about - guards
      // against an off-by-one if the last page is shorter than PAGE_SIZE.
      const totalPages = typeof lastPage.totalPages === 'number' ? lastPage.totalPages : 0;
      const nextPageIndex = allPages.length;
      if (nextPageIndex >= totalPages) return undefined;
      // Defense in depth: also stop if the last page returned less than
      // PAGE_SIZE rows (the backend doesn't have more).
      const lastContent = Array.isArray(lastPage.content) ? lastPage.content.length : 0;
      if (lastContent < PAGE_SIZE) return undefined;
      return nextPageIndex;
    },
    staleTime: 15000, // 15 seconds - prevents refetch storms during parallel execution
    refetchOnMount: true, // refetch stale data when inspector opens (invalidateQueries marks stale)
    refetchOnWindowFocus: false,
  });

  // Flatten paged content into a single ASC array (backend returns each page
  // in DB-order which is already chronological by id).
  const stepData = useMemo<WorkflowStepData[]>(() => {
    if (!infiniteData?.pages) return [];
    return infiniteData.pages.flatMap(
      page => (page?.content || []) as unknown as WorkflowStepData[],
    );
  }, [infiniteData]);

  const totalElements = infiniteData?.pages?.[0]?.totalElements ?? stepData.length;

  const error = queryError instanceof Error ? queryError.message : null;

  return {
    stepData,
    loading,
    error,
    // Progressive-load hooks consumed by StepDataTable's LoadOlderSentinel.
    // Older inspector surfaces that ignored these (and only read stepData)
    // continue to work unchanged because the flat array reflects loaded pages.
    hasNextPage,
    fetchNextPage,
    isFetchingNextPage,
    totalElements,
  };
}

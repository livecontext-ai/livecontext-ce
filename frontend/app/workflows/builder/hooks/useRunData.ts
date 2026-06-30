/**
 * useRunData - Unified hook for fetching step input/output data
 *
 * Loads ALL items (up to size=100) for the step alias in a single paginated
 * call, then navigates and filters them client-side. Status filtering and
 * navigation share the same items array so "Item N / N" stays consistent with
 * the visible filter scope.
 */

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { orchestratorApi } from '@/lib/api';
import { useStepCompletionInvalidation } from './useStepCompletionInvalidation';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';
import { mapBackendStatusToStatusType, type StatusType } from '@/components/ui/StatusBadge';
import { useApprovalReviewTarget } from '../services/approvalReviewStore';
import type { WorkflowStepData } from './useStepData';
import type { StepOutputSkeleton } from '@/lib/api/orchestrator/execution.service';

export type RunDataType = 'input' | 'output';

export interface UseRunDataOptions {
  workflowId: string | undefined;
  runId: string | undefined;
  stepAlias: string | undefined;
  dataType: RunDataType;
  enabled?: boolean;
  /**
   * Optional canonical {@link StatusType} to keep only matching rows in
   * navigation. Backend statuses are mapped via
   * {@link mapBackendStatusToStatusType} before comparison.
   */
  statusFilter?: StatusType | null;
}

export interface RunDataResult {
  // Items data
  items: WorkflowStepData[];
  totalItems: number;
  isLoading: boolean;
  error: string | null;

  // Current item navigation
  currentIndex: number;
  currentItem: WorkflowStepData | null;
  hasNext: boolean;
  hasPrev: boolean;
  goToNext: () => void;
  goToPrev: () => void;
  goToIndex: (index: number) => void;

  // Skeleton for current item
  skeleton: StepOutputSkeleton | null;
  isLoadingSkeleton: boolean;

  // Lazy loading helpers
  getValueAtPath: (path: string) => Promise<string | null>;
  getObjectAtPath: (path: string) => Promise<any | null>;

  // Manual refetch
  refetch: () => void;

  /** Canonical {@link StatusType}s present in the unfiltered set, for the filter UI. */
  availableStatuses: StatusType[];
}

// Per-page size; matches the backend safeSize cap raised to 500 on 2026-05-13.
// useInfiniteQuery stacks older pages as the user navigates back via the ◄
// arrow - the input/output/params columns surface the full run history (Daily
// Email Digest: 3490 rows per alias) instead of the previous silent 100-row
// truncation.
const PAGE_SIZE = 500;

// Trigger a background fetch of the next (older) page when the user is within
// LOOKAHEAD items of the oldest LOADED item - the arrow navigator never bottoms
// out at the loaded boundary while older rows still exist on the backend.
const LOOKAHEAD = 50;

export function useRunData({
  workflowId,
  runId,
  stepAlias,
  dataType,
  enabled = true,
  statusFilter,
}: UseRunDataOptions): RunDataResult {
  const [currentIndex, setCurrentIndex] = useState(0);
  const queryClient = useQueryClient();
  const prevRunIdRef = useRef<string | undefined>(undefined);
  const { viewingEpoch } = useWorkflowMode();

  // Invalidate all caches when runId changes (new run or step-by-step execution)
  useEffect(() => {
    if (runId && runId !== prevRunIdRef.current) {
      queryClient.invalidateQueries({
        queryKey: ['run-step-data'],
      });
      queryClient.invalidateQueries({
        queryKey: ['step-skeleton'],
      });
      prevRunIdRef.current = runId;
    }
  }, [runId, queryClient]);

  // Inspector is disabled in publication preview: the showcase clone is read
  // only and the auth'd /api/runs/{id} endpoint is forbidden cross-tenant.
  const inPreview = !!getActivePublicPreview();

  // Step 1: Get workflow run UUID from public runId (cached & shared)
  const { data: workflowRunId } = useQuery({
    queryKey: ['workflow-run', runId],
    queryFn: async () => {
      if (!runId) return null;
      const run = await orchestratorApi.getRun(runId);
      return run.id as string;
    },
    enabled: !!runId && enabled && !inPreview,
    staleTime: 5 * 60 * 1000, // 5 min - run UUID never changes
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });

  // Invalidate this alias's run-step-data + skeleton caches when a matching step
  // completes. Throttled via the shared listener so the Input/Output/Param
  // columns (three useRunData mounts for the selected node) cannot fan out into
  // a /steps/paged 429 storm on a busy run (see useStepCompletionInvalidation).
  useStepCompletionInvalidation({
    runId,
    stepAlias,
    enabled: !!stepAlias,
    onInvalidate: () => {
      queryClient.invalidateQueries({
        queryKey: ['run-step-data', workflowRunId, stepAlias, dataType],
        exact: false,
      });
      queryClient.invalidateQueries({
        queryKey: ['step-skeleton', workflowId, runId],
        exact: false,
      });
    },
  });

  // Step 2: Fetch steps as an infinite stream. Backend sorts DESC by id and
  // pages are PAGE_SIZE rows each. As the user navigates back through history,
  // the lookahead effect below silently fetches the next (older) page. The
  // navigator stays on the same logical item across page boundaries because
  // we track currentItemId (see post-merge effect) - same pattern as
  // useRunOutputData.
  const {
    data: infiniteData,
    isLoading,
    error,
    refetch: refetchItems,
    hasNextPage,
    fetchNextPage,
    isFetchingNextPage,
  } = useInfiniteQuery({
    queryKey: ['run-step-data', workflowRunId, stepAlias, dataType, viewingEpoch],
    queryFn: async ({ pageParam }) => {
      if (!workflowRunId || !stepAlias) {
        return { content: [] as WorkflowStepData[], totalElements: 0, totalPages: 0, page: 0, size: PAGE_SIZE };
      }
      return orchestratorApi.getRunStepsPaged(
        workflowRunId,
        stepAlias,
        pageParam as number,
        PAGE_SIZE,
        viewingEpoch,
      );
    },
    enabled: !!workflowRunId && !!stepAlias && enabled && !inPreview,
    initialPageParam: 0,
    getNextPageParam: (lastPage, allPages) => {
      if (!lastPage) return undefined;
      const totalPages = typeof lastPage.totalPages === 'number' ? lastPage.totalPages : 0;
      const nextPageIndex = allPages.length;
      if (nextPageIndex >= totalPages) return undefined;
      const lastContent = Array.isArray(lastPage.content) ? lastPage.content.length : 0;
      if (lastContent < PAGE_SIZE) return undefined;
      return nextPageIndex;
    },
    staleTime: 15000,
    refetchOnMount: true,
    refetchOnWindowFocus: false,
  });

  const rawItems = useMemo<WorkflowStepData[]>(() => {
    if (!infiniteData?.pages) return [];
    return infiniteData.pages.flatMap(
      page => (page?.content || []) as unknown as WorkflowStepData[],
    );
  }, [infiniteData]);

  const totalElementsFromBackend = infiniteData?.pages?.[0]?.totalElements;

  const allItems = useMemo(() => {
    return [...rawItems].reverse();
  }, [rawItems]);

  const availableStatuses = useMemo<StatusType[]>(() => {
    const set = new Set<StatusType>();
    for (const it of allItems) {
      set.add(mapBackendStatusToStatusType(it.status));
    }
    return Array.from(set);
  }, [allItems]);

  const items = useMemo(() => {
    if (!statusFilter) return allItems;
    return allItems.filter(
      (it) => mapBackendStatusToStatusType(it.status) === statusFilter,
    );
  }, [allItems, statusFilter]);

  // totalItems exposes the true backend count when no client-side filter is
  // active (the user expects "N / 3490" not "N / 500"). When statusFilter
  // narrows the visible set client-side, totalItems reflects the filtered
  // count - otherwise the navigator label would lie ("3 of 3490" when only 3
  // failed items exist).
  const totalItems = useMemo(() => {
    if (!statusFilter && typeof totalElementsFromBackend === 'number' && totalElementsFromBackend > items.length) {
      return totalElementsFromBackend;
    }
    return items.length;
  }, [items.length, statusFilter, totalElementsFromBackend]);

  // Stable per-item tracking - see useRunOutputData for the same pattern.
  // currentItemIdRef is updated synchronously inside navigateToIndex below
  // (NOT via a downstream useEffect on currentItem.id) so the seed effect never
  // reads a stale id and clobbers the user's navigation. followTipRef captures
  // the user's INTENT: true while they sit on the newest row (auto-follow fresh
  // executions), false once they navigate into history (stay pinned to that row
  // across page merges).
  const currentItemIdRef = useRef<number | null>(null);
  const prevKeyRef = useRef<string>('');
  const followTipRef = useRef(true);

  useEffect(() => {
    const key = `${workflowRunId ?? ''}|${stepAlias ?? ''}|${viewingEpoch ?? ''}|${statusFilter ?? ''}`;
    const keyChanged = key !== prevKeyRef.current;
    const isInitialSeed = items.length > 0 && currentItemIdRef.current == null;

    // New view (run / step / epoch / filter) or first data → land on the tip
    // (newest) and start in follow-the-tip mode.
    if (keyChanged || isInitialSeed) {
      if (items.length > 0) {
        currentItemIdRef.current = items[items.length - 1]?.id ?? null;
        setCurrentIndex(items.length - 1);
      } else {
        currentItemIdRef.current = null;
        setCurrentIndex(0);
      }
      followTipRef.current = true;
      prevKeyRef.current = key;
      return;
    }

    // Same view, the loaded set changed (a re-run added/slid a row at the tip,
    // or an older page merged in at the front).
    if (followTipRef.current) {
      // The user is parked on the latest result. Always track the current tip so
      // a fresh execution's data replaces the stale one - even when the loaded
      // window keeps the SAME length (>1 page loaded: the new row pushes the
      // oldest off the loaded tail, so items.length is unchanged but the tip id
      // changed). currentItem.id then changes → the lazy content fetch re-runs.
      // This is the reported "content stuck on the old epoch" fix.
      if (items.length > 0) {
        currentItemIdRef.current = items[items.length - 1]?.id ?? null;
        if (currentIndex !== items.length - 1) setCurrentIndex(items.length - 1);
      }
      return;
    }

    // The user navigated into history - keep them anchored to the SAME logical
    // row across an older-page merge by re-locating its id.
    if (currentItemIdRef.current != null) {
      const newIdx = items.findIndex(it => it.id === currentItemIdRef.current);
      if (newIdx >= 0 && newIdx !== currentIndex) {
        setCurrentIndex(newIdx);
        return;
      }
    }

    // Fallback: the tracked row is no longer loaded and the index is now out of
    // bounds - clamp to the newest loaded row.
    if (items.length > 0 && currentIndex >= items.length) {
      currentItemIdRef.current = items[items.length - 1]?.id ?? null;
      setCurrentIndex(items.length - 1);
    }
  }, [workflowRunId, stepAlias, viewingEpoch, statusFilter, items, items.length, currentIndex]);

  const currentItem = items[currentIndex] || null;

  // Lookahead prefetch - see useRunOutputData. Skipped when statusFilter is
  // active (page would need client-side re-filter); gated on currentIndex>0
  // so the useState(0) default before seed never fires a phantom fetch.
  useEffect(() => {
    if (statusFilter || !hasNextPage || isFetchingNextPage) return;
    if (currentItemIdRef.current == null) return;
    if (currentIndex > 0 && currentIndex <= LOOKAHEAD) {
      void fetchNextPage();
    }
  }, [currentIndex, hasNextPage, isFetchingNextPage, fetchNextPage, statusFilter]);

  // Step 3: Fetch skeleton for current item only
  const {
    data: skeleton,
    isLoading: isLoadingSkeleton,
    refetch: refetchSkeleton,
  } = useQuery({
    queryKey: ['step-skeleton', workflowId, runId, currentItem?.id, dataType],
    queryFn: async () => {
      if (!workflowId || !runId || !currentItem?.id) return null;
      if (dataType === 'output') {
        return orchestratorApi.execution.getStepOutputSkeleton(
          workflowId,
          runId,
          currentItem.id
        );
      }
      return null;
    },
    enabled: !!workflowId && !!runId && !!currentItem?.id && enabled,
    staleTime: 5 * 60 * 1000, // 5 minutes - skeleton structure doesn't change
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });

  // Navigation handlers
  // hasNext (=newer) bounded by the LOADED set's top end. hasPrev (=older)
  // extends past the loaded boundary when more pages exist on the backend;
  // goToPrev triggers a fetchNextPage in that case and the post-merge effect
  // keeps the user on the same item id until the new page renders.
  const hasNext = currentIndex < items.length - 1;
  const hasPrev = currentIndex > 0 || (!statusFilter && hasNextPage);

  // Sync id-tracking helper - see useRunOutputData.navigateToIndex.
  const navigateToIndex = useCallback(
    (index: number) => {
      const target = items[index];
      if (target?.id != null) currentItemIdRef.current = target.id;
      // Follow-the-tip is ON only while the user sits on the newest row.
      // Navigating away pins them to that row; navigating back to the tip
      // re-enables following so a later re-run advances them automatically.
      followTipRef.current = index === items.length - 1;
      setCurrentIndex(index);
    },
    [items],
  );

  const goToNext = useCallback(() => {
    if (currentIndex < items.length - 1) {
      navigateToIndex(currentIndex + 1);
    }
  }, [currentIndex, items.length, navigateToIndex]);

  const goToPrev = useCallback(() => {
    if (currentIndex > 0) {
      navigateToIndex(currentIndex - 1);
      return;
    }
    if (!statusFilter && hasNextPage && !isFetchingNextPage) {
      void fetchNextPage();
    }
  }, [currentIndex, hasNextPage, isFetchingNextPage, fetchNextPage, statusFilter, navigateToIndex]);

  const goToIndex = useCallback(
    (index: number) => {
      if (index >= 0 && index < items.length) {
        navigateToIndex(index);
      }
    },
    [items.length, navigateToIndex],
  );

  // Approval-review targeting: when UserApprovalNode (or the ApprovalReviewBar
  // auto-advance) requests a specific (epoch, itemIndex), every mounted
  // navigator jumps to the matching row so the Input/Params/Output columns all
  // show the same item. Matching rules:
  //  - epoch axis wildcards when either side lacks it;
  //  - itemIndex axis treats a missing row index as 0 (single-row pre-split
  //    parents match an item-0 target, but NOT item-N targets);
  //  - latest spawn wins - items are sorted oldest→newest, so scan from the end.
  // The jump is one-shot per (requestId, navigator data key): the applied
  // requestId is reset whenever the navigator's data key changes, because the
  // inspector panel is NOT remounted per node - without the reset, a
  // pseudo-match against the previously inspected node's rows would consume
  // the request before the approval node's own rows ever load.
  // Declared after the seed/follow-tip effect so the targeted jump takes
  // precedence within the same commit.
  const reviewTarget = useApprovalReviewTarget();
  const appliedReviewRequestRef = useRef(0);
  const reviewDataKey = `${workflowRunId ?? ''}|${stepAlias ?? ''}`;
  const reviewDataKeyRef = useRef(reviewDataKey);
  if (reviewDataKeyRef.current !== reviewDataKey) {
    reviewDataKeyRef.current = reviewDataKey;
    appliedReviewRequestRef.current = 0;
  }
  useEffect(() => {
    if (!reviewTarget) return;
    if (appliedReviewRequestRef.current === reviewTarget.requestId) return;
    if (items.length === 0) return;
    for (let i = items.length - 1; i >= 0; i--) {
      const it = items[i];
      const epochOk =
        reviewTarget.epoch == null || it.epoch == null || it.epoch === reviewTarget.epoch;
      const itemOk =
        reviewTarget.itemIndex == null || (it.itemIndex ?? 0) === reviewTarget.itemIndex;
      if (epochOk && itemOk) {
        appliedReviewRequestRef.current = reviewTarget.requestId;
        navigateToIndex(i);
        // The jump often lands on the tip (latest spawn). Force follow-the-tip
        // OFF so a row appended afterwards (re-run spawn, another epoch firing)
        // cannot silently drift the navigator off the item under review.
        followTipRef.current = false;
        return;
      }
    }
    // No matching row loaded yet - leave the request unapplied so the next
    // items refresh (WS step-completion invalidation) can land it.
  }, [reviewTarget, items, navigateToIndex]);

  // Manual refetch for both items and skeleton
  const refetch = useCallback(() => {
    refetchItems();
    refetchSkeleton();
  }, [refetchItems, refetchSkeleton]);

  // Lazy loading helpers - prefix with dataType (input/output)
  // For dataType='input', read directly from step record's inputData field (JSONB column)
  // instead of blob storage which only contains 'output' data.
  const getValueAtPath = useCallback(
    async (path: string): Promise<string | null> => {
      if (!workflowId || !runId || !currentItem?.id) return null;

      // Input data is stored inline in the step record, not in blob storage
      if (dataType === 'input') {
        const inputData = currentItem.inputData;
        if (!inputData) return null;
        if (!path) return JSON.stringify(inputData);
        const value = getNestedValue(inputData, path);
        return value !== undefined ? String(value) : null;
      }

      const fullPath = path ? `${dataType}.${path}` : dataType;
      return orchestratorApi.execution.getStepOutputValueAtPath(
        workflowId,
        runId,
        currentItem.id,
        fullPath
      );
    },
    [workflowId, runId, currentItem?.id, currentItem?.inputData, dataType]
  );

  const getObjectAtPath = useCallback(
    async (path: string): Promise<any | null> => {
      if (!workflowId || !runId || !currentItem?.id) {
        return null;
      }

      // Input data is stored inline in the step record, not in blob storage
      if (dataType === 'input') {
        const inputData = currentItem.inputData;
        if (!inputData) return null;
        if (!path) return inputData;
        return getNestedValue(inputData, path) ?? null;
      }

      const fullPath = path ? `${dataType}.${path}` : dataType;
      return orchestratorApi.execution.getStepOutputObjectAtPath(
        workflowId,
        runId,
        currentItem.id,
        fullPath
      );
    },
    [workflowId, runId, currentItem?.id, currentItem?.inputData, dataType]
  );

  return {
    items,
    totalItems,
    isLoading,
    error: error instanceof Error ? error.message : null,

    currentIndex,
    currentItem,
    hasNext,
    hasPrev,
    goToNext,
    goToPrev,
    goToIndex,

    skeleton,
    isLoadingSkeleton,

    getValueAtPath,
    getObjectAtPath,

    refetch,

    availableStatuses,
  };
}

/**
 * Navigate a nested object by dot-separated path.
 * e.g. getNestedValue({ a: { b: 42 } }, "a.b") => 42
 */
function getNestedValue(obj: Record<string, any>, path: string): any {
  const parts = path.split('.');
  let current: any = obj;
  for (const part of parts) {
    if (current == null || typeof current !== 'object') return undefined;
    current = current[part];
  }
  return current;
}

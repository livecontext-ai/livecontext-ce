/**
 * useRunOutputData - Hook for managing step output data with item navigation
 *
 * Features:
 * - Fetches step data for a specific node (multiple items from datasource/forEach)
 * - Manages current item index for navigation (prev/next)
 * - Lazy loads skeleton for current item only (not all items)
 * - Provides methods for lazy loading values at specific paths
 */

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { orchestratorApi } from '@/lib/api';
import { mapBackendStatusToStatusType, type StatusType } from '@/components/ui/StatusBadge';
import { useStepData, type WorkflowStepData } from './useStepData';
import type { StepOutputSkeleton } from '@/lib/api/orchestrator/execution.service';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';

// Per-page size for the /steps/paged calls. Matches backend safeSize=500 cap.
// useInfiniteQuery streams older pages on demand so the navigator can step
// through workflows with thousands of items per stepAlias (Daily Email Digest:
// 3490 rows on `parse_headers`) - before this switch the navigator silently
// truncated to the latest page of 100 items.
const PAGE_SIZE = 500;

// When the user navigates within LOOKAHEAD items of the oldest LOADED item,
// silently prefetch the next (older) page so the navigator never reaches a
// "no more rows" state at the LOADED boundary while older rows still exist
// on the server. The user perceives a seamless tape across the full set.
const LOOKAHEAD = 50;

export interface UseRunOutputDataOptions {
  workflowId: string | undefined;
  runId: string | undefined;
  stepAlias: string | undefined;
  epoch?: number | null;
  enabled?: boolean;
  /**
   * When true, group rows by (epoch, itemIndex) and keep only the row with
   * the highest spawn - mirrors {@code InterfaceRenderService} semantics so
   * the navigator scrolls a clean (epoch, itemIndex) axis instead of
   * exposing every retry/replay. Default: false (legacy behavior).
   */
  dedupeMaxSpawn?: boolean;
  /**
   * Optional canonical {@link StatusType} to keep only matching rows in
   * navigation. Backend statuses are mapped via
   * {@link mapBackendStatusToStatusType} before comparison.
   */
  statusFilter?: StatusType | null;
}

export interface RunOutputDataResult {
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

  /** Canonical {@link StatusType}s present in the unfiltered set, for the filter UI. */
  availableStatuses: StatusType[];
}

/**
 * Group rows by (epoch, itemIndex) keeping only the row with the highest spawn,
 * then return them sorted (epoch ASC, itemIndex ASC) so index 0 = oldest and
 * index N-1 = newest. Mirrors {@code InterfaceRenderService} dedup semantics.
 *
 * Exported for unit testing; internal callers use the {@code dedupeMaxSpawn}
 * option on {@link useRunOutputData}.
 */
export function dedupeMaxSpawnByEpochItem(rows: WorkflowStepData[]): WorkflowStepData[] {
  const map = new Map<string, WorkflowStepData>();
  for (const item of rows) {
    const key = `${item.epoch ?? 0}:${item.itemIndex ?? 0}`;
    const existing = map.get(key);
    if (!existing || (item.spawn ?? 0) >= (existing.spawn ?? 0)) {
      map.set(key, item);
    }
  }
  return Array.from(map.values()).sort((a, b) => {
    const ea = a.epoch ?? 0;
    const eb = b.epoch ?? 0;
    if (ea !== eb) return ea - eb;
    return (a.itemIndex ?? 0) - (b.itemIndex ?? 0);
  });
}

export function useRunOutputData({
  workflowId,
  runId,
  stepAlias,
  epoch,
  enabled = true,
  dedupeMaxSpawn = false,
  statusFilter,
}: UseRunOutputDataOptions): RunOutputDataResult {
  const [currentIndex, setCurrentIndex] = useState(0);
  // Inspector is disabled in publication preview (see useRunData).
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

  // Step 2: Fetch steps filtered server-side by stepAlias + epoch + statusFilter.
  // Backend sorts DESC by id (most recent first) and pages are 500 rows each.
  // useInfiniteQuery stacks older pages as the consumer scrolls back through
  // history; the navigator stays on the same logical item across page boundaries
  // because we track currentItemId rather than a raw index (see currentIndex
  // memo below). Reverse client-side so index 0 = oldest LOADED and N-1 = most
  // recent, which maps naturally to the ItemNavigator label (N/N = latest).
  const {
    data: infiniteData,
    isLoading,
    error,
    hasNextPage,
    fetchNextPage,
    isFetchingNextPage,
  } = useInfiniteQuery({
    queryKey: ['run-output-data', workflowRunId, stepAlias, epoch, statusFilter ?? null],
    queryFn: async ({ pageParam }) => {
      if (!workflowRunId || !stepAlias) {
        return { content: [] as WorkflowStepData[], totalElements: 0, totalPages: 0, page: 0, size: PAGE_SIZE };
      }
      return orchestratorApi.getRunStepsPaged(
        workflowRunId,
        stepAlias,
        pageParam as number,
        PAGE_SIZE,
        epoch,
        statusFilter ?? null,
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
    staleTime: 30000,
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });

  const rawItems = useMemo<WorkflowStepData[]>(() => {
    if (!infiniteData?.pages) return [];
    return infiniteData.pages.flatMap(
      page => (page?.content || []) as unknown as WorkflowStepData[],
    );
  }, [infiniteData]);

  const totalElementsFromBackend = infiniteData?.pages?.[0]?.totalElements;

  // Items already match statusFilter (filtered server-side); just orient them.
  const items = useMemo(() => {
    const reversed = [...rawItems].reverse();
    return dedupeMaxSpawn ? dedupeMaxSpawnByEpochItem(reversed) : reversed;
  }, [rawItems, dedupeMaxSpawn]);

  // Reuse {@link useStepData} (size=200, unfiltered, shared cache key
  // `step-data` already invalidated on stepExecutionCompleted events) to
  // populate the filter dropdown - otherwise selecting "completed" would
  // shrink availableStatuses to just "completed" and the user could not
  // switch back to "failed". This is per-CLAUDE.md "vérifier l'existant":
  // useStepData already loads the exact set we need, no point firing a
  // second query.
  const { stepData: unfilteredStepData } = useStepData(
    enabled && !inPreview ? runId : undefined,
    enabled && !inPreview ? stepAlias : undefined,
  );

  const availableStatuses = useMemo<StatusType[]>(() => {
    const set = new Set<StatusType>();
    for (const it of unfilteredStepData) {
      set.add(mapBackendStatusToStatusType(it.status));
    }
    return Array.from(set);
  }, [unfilteredStepData]);

  // Stable per-item tracking: when a new (older) page lands, items get prepended
  // (older rows). currentIndex would otherwise point to a different logical
  // item - track currentItemId and recompute currentIndex from it after a
  // page-merge. The ref is updated synchronously inside goToNext/goToPrev/
  // goToIndex (NOT via a downstream useEffect) so the post-merge effect below
  // never reads a stale id and clobbers the user's navigation.
  const currentItemIdRef = useRef<number | null>(null);

  // Current item
  const currentItem = useMemo(() => {
    return items[currentIndex] || null;
  }, [items, currentIndex]);

  // followTipRef captures the user's INTENT: true while they sit on the newest
  // row (auto-follow fresh executions), false once they navigate into history
  // (stay pinned to that row across page merges).
  const prevKeyRef = useRef<string>('');
  const followTipRef = useRef(true);

  useEffect(() => {
    const key = `${workflowRunId ?? ''}|${stepAlias ?? ''}|${epoch ?? ''}|${statusFilter ?? ''}`;
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
  }, [workflowRunId, stepAlias, epoch, statusFilter, items, items.length, currentIndex]);

  // Lookahead prefetch: as soon as the user is within LOOKAHEAD of the oldest
  // LOADED item, silently fetch the next (older) page in the background so the
  // navigator never bottoms out at the loaded boundary while the server still
  // has older rows. Guarded by:
  //   - isFetchingNextPage so a per-render-fire loop is impossible when
  //     TanStack returns a new fetchNextPage reference between re-renders;
  //   - currentIndex > 0 so the initial mount (useState(0) before the seed
  //     effect runs) never triggers a phantom fetch - the seed effect lands
  //     currentIndex at items.length-1 (newest) before the user could ever
  //     have navigated into the LOOKAHEAD window.
  useEffect(() => {
    if (!hasNextPage || isFetchingNextPage) return;
    if (currentItemIdRef.current == null) return;
    if (currentIndex > 0 && currentIndex <= LOOKAHEAD) {
      void fetchNextPage();
    }
  }, [currentIndex, hasNextPage, isFetchingNextPage, fetchNextPage]);

  // Step 3: Fetch skeleton for current item only
  const {
    data: skeleton,
    isLoading: isLoadingSkeleton,
  } = useQuery({
    queryKey: ['step-output-skeleton', workflowId, runId, currentItem?.id],
    queryFn: async () => {
      if (!workflowId || !runId || !currentItem?.id) return null;
      return orchestratorApi.execution.getStepOutputSkeleton(
        workflowId,
        runId,
        currentItem.id
      );
    },
    enabled: !!workflowId && !!runId && !!currentItem?.id && enabled && !inPreview,
    staleTime: 60000, // 1 minute
    refetchOnMount: false,
    refetchOnWindowFocus: false,
  });

  // Navigation handlers
  // hasNext (=newer) is bounded by the LOADED set's top end - page 0 always
  // holds the newest 500 rows so there is never "newer" beyond what we already
  // have. hasPrev (=older) extends past the loaded boundary when more pages
  // exist on the backend; the goToPrev handler triggers a fetchNextPage in
  // that case and the post-merge useEffect keeps the user on the same item id
  // until the new page renders.
  const hasNext = currentIndex < items.length - 1;
  const hasPrev = currentIndex > 0 || hasNextPage;

  // Helper: update the tracked id synchronously alongside setCurrentIndex so
  // the post-merge effect always sees the right anchor. Doing it inside a
  // downstream useEffect on currentItem?.id ran AFTER the post-merge effect on
  // the same commit cycle, causing the post-merge effect to read a STALE id
  // and immediately revert the user's navigation.
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
    if (hasNext) {
      navigateToIndex(currentIndex + 1);
    }
  }, [hasNext, currentIndex, navigateToIndex]);

  const goToPrev = useCallback(() => {
    if (currentIndex > 0) {
      navigateToIndex(currentIndex - 1);
      return;
    }
    // At the oldest LOADED boundary - fetch next page; the new (older) rows
    // prepend after reverse so the user can navigate further back once they
    // land. We don't move currentIndex here; the user clicks ◄ again after the
    // page arrives.
    if (hasNextPage && !isFetchingNextPage) {
      void fetchNextPage();
    }
  }, [currentIndex, hasNextPage, isFetchingNextPage, fetchNextPage, navigateToIndex]);

  const goToIndex = useCallback(
    (index: number) => {
      if (index >= 0 && index < items.length) {
        navigateToIndex(index);
      }
    },
    [items.length, navigateToIndex],
  );

  // Lazy loading helpers - Always prefix with "output" to get only output data
  const getValueAtPath = useCallback(
    async (path: string): Promise<string | null> => {
      if (!workflowId || !runId || !currentItem?.id) return null;
      // Prefix with "output" - if path is empty, just use "output"
      const fullPath = path ? `output.${path}` : 'output';
      return orchestratorApi.execution.getStepOutputValueAtPath(
        workflowId,
        runId,
        currentItem.id,
        fullPath
      );
    },
    [workflowId, runId, currentItem?.id]
  );

  const getObjectAtPath = useCallback(
    async (path: string): Promise<any | null> => {
      if (!workflowId || !runId || !currentItem?.id) return null;
      // Prefix with "output" - if path is empty, just use "output"
      const fullPath = path ? `output.${path}` : 'output';
      return orchestratorApi.execution.getStepOutputObjectAtPath(
        workflowId,
        runId,
        currentItem.id,
        fullPath
      );
    },
    [workflowId, runId, currentItem?.id]
  );

  // totalItems exposes the true backend count so the navigator label can read
  // "current / 3490" rather than "current / 500" after the first page lands.
  // Falls back to items.length when totalElements isn't yet known (initial
  // boot, no pages fetched) or when client-side dedupeMaxSpawn has compressed
  // the loaded set below totalElements - the consumer relies on this to drive
  // the right-pad of the "N / total" label.
  const totalItems = (() => {
    if (typeof totalElementsFromBackend === 'number' && totalElementsFromBackend > items.length) {
      return totalElementsFromBackend;
    }
    return items.length;
  })();

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

    availableStatuses,
  };
}

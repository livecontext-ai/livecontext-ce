'use client';

import { useState, useCallback, useEffect, useRef } from 'react';
// Locale-aware router (next-intl) - drag-drop promote/review redirects must preserve the locale.
import { useRouter } from '@/i18n/navigation';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import { executionService } from '@/lib/api/orchestrator/execution.service';
import { versionService } from '@/lib/api/orchestrator/version.service';
import { useOrgScopedReset } from '@/lib/hooks/useOrgScopedReset';
import type { WorkflowBoardCard, WorkflowBoardColumn } from '@/lib/api/orchestrator/types';

const COLUMN_KEYS: WorkflowBoardColumn[] = ['draft', 'production', 'needsReview', 'paused'];
const PAGE_SIZE = 20;

interface ColumnState {
  items: WorkflowBoardCard[];
  totalCount: number;
  page: number;
  loading: boolean;
  hasMore: boolean;
}

const EMPTY_COLUMN: ColumnState = { items: [], totalCount: 0, page: 0, loading: false, hasMore: false };

const EMPTY_COLUMNS: Record<WorkflowBoardColumn, ColumnState> = {
  draft: { ...EMPTY_COLUMN },
  production: { ...EMPTY_COLUMN },
  needsReview: { ...EMPTY_COLUMN },
  paused: { ...EMPTY_COLUMN },
};

/** Columns that cannot receive drops. */
const DROP_FORBIDDEN: Set<WorkflowBoardColumn> = new Set(['needsReview']);

export type BoardErrorCode = 'loadFailed' | 'moveFailed';

export interface UseWorkflowBoardReturn {
  columns: Record<WorkflowBoardColumn, ColumnState>;
  totalCount: number;
  initialLoading: boolean;
  errorCode: BoardErrorCode | null;
  refresh: () => void;
  loadMore: (column: WorkflowBoardColumn) => Promise<void>;
  dismissError: () => void;
  moveCard: (card: WorkflowBoardCard, targetColumn: WorkflowBoardColumn) => Promise<void>;
  canDrop: (card: WorkflowBoardCard, targetColumn: WorkflowBoardColumn) => boolean;
  pinRequest: WorkflowBoardCard | null;
  closePinRequest: () => void;
  confirmPin: (version: number) => Promise<void>;
}

/** Which resource the board lists. Workflows (default) or APPLICATION-type workflows (apps). */
export type WorkflowBoardSource = 'workflow' | 'application';

export function useWorkflowBoard(source: WorkflowBoardSource = 'workflow'): UseWorkflowBoardReturn {
  const router = useRouter();
  const [columns, setColumns] = useState<Record<WorkflowBoardColumn, ColumnState>>(EMPTY_COLUMNS);
  const [initialLoading, setInitialLoading] = useState(true);
  const [errorCode, setErrorCode] = useState<BoardErrorCode | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [pinRequest, setPinRequest] = useState<WorkflowBoardCard | null>(null);
  const movingRef = useRef(false);
  // Track in-flight loadMore per column so we don't fire duplicate requests if the
  // IntersectionObserver fires twice while the first response is still pending.
  const inFlightRef = useRef<Set<WorkflowBoardColumn>>(new Set());

  const refresh = useCallback(() => setRefreshKey((k) => k + 1), []);
  const dismissError = useCallback(() => setErrorCode(null), []);

  // Phase 3 (2026-05-18) - clear the 4-column workflow card buckets on
  // workspace switch and trigger a fresh load. Without this, the previous
  // workspace's cards remain on the board until the next manual refresh.
  useOrgScopedReset(() => {
    setColumns(EMPTY_COLUMNS);
    setErrorCode(null);
    setPinRequest(null);
    inFlightRef.current.clear();
    setRefreshKey((k) => k + 1);
  });

  const fetchColumnPage = useCallback(async (col: WorkflowBoardColumn, page: number) => {
    return source === 'application'
      ? workflowService.getApplicationBoardColumn(col, { page, size: PAGE_SIZE })
      : workflowService.getWorkflowBoardColumn(col, { page, size: PAGE_SIZE });
  }, [source]);

  // Load page 0 of every column on mount/refresh in parallel.
  useEffect(() => {
    let cancelled = false;
    setInitialLoading(true);
    setErrorCode(null);
    inFlightRef.current.clear();

    Promise.all(COLUMN_KEYS.map((col) => fetchColumnPage(col, 0)))
      .then((results) => {
        if (cancelled) return;
        const next: Record<WorkflowBoardColumn, ColumnState> = { ...EMPTY_COLUMNS };
        for (let i = 0; i < COLUMN_KEYS.length; i++) {
          const col = COLUMN_KEYS[i];
          const r = results[i];
          next[col] = {
            items: r.items,
            totalCount: r.totalCount,
            page: 0,
            loading: false,
            hasMore: r.items.length < r.totalCount,
          };
        }
        setColumns(next);
        setInitialLoading(false);
      })
      .catch(() => {
        if (cancelled) return;
        setErrorCode('loadFailed');
        setInitialLoading(false);
      });

    return () => { cancelled = true; };
  }, [refreshKey, fetchColumnPage]);

  const loadMore = useCallback(async (column: WorkflowBoardColumn) => {
    const state = columns[column];
    if (!state || !state.hasMore || state.loading) return;
    if (inFlightRef.current.has(column)) return;

    inFlightRef.current.add(column);
    setColumns((prev) => ({ ...prev, [column]: { ...prev[column], loading: true } }));
    try {
      const nextPage = state.page + 1;
      const result = await fetchColumnPage(column, nextPage);
      setColumns((prev) => {
        const existing = prev[column].items;
        // Dedup by workflowId in case items shifted between pages.
        const seen = new Set(existing.map((c) => c.workflowId));
        const merged = [...existing, ...result.items.filter((c) => !seen.has(c.workflowId))];
        return {
          ...prev,
          [column]: {
            items: merged,
            totalCount: result.totalCount,
            page: nextPage,
            loading: false,
            hasMore: merged.length < result.totalCount,
          },
        };
      });
    } catch {
      setErrorCode('loadFailed');
      setColumns((prev) => ({ ...prev, [column]: { ...prev[column], loading: false } }));
    } finally {
      inFlightRef.current.delete(column);
    }
  }, [columns, fetchColumnPage]);

  const canDrop = useCallback((card: WorkflowBoardCard, target: WorkflowBoardColumn): boolean => {
    if (card.column === target) return false;
    if (DROP_FORBIDDEN.has(target)) return false;
    if (card.column === 'draft') return target === 'production';
    if (card.column === 'needsReview' && target === 'production') return false;
    return true;
  }, []);

  const moveCard = useCallback(async (card: WorkflowBoardCard, target: WorkflowBoardColumn) => {
    if (!canDrop(card, target)) return;
    if (movingRef.current) return;

    if (card.column === 'draft' && target === 'production') {
      setPinRequest(card);
      return;
    }

    movingRef.current = true;
    try {
      if (target === 'draft') {
        await versionService.pinVersion(card.workflowId, null);
      } else if (target === 'production') {
        if (card.column === 'paused' && card.productionRunId) {
          await executionService.reactivateWorkflow(card.productionRunId);
        }
      } else if (target === 'paused') {
        if (card.productionRunId) {
          await executionService.cancelWorkflow(card.productionRunId);
        }
      }
      refresh();
    } catch {
      setErrorCode('moveFailed');
    } finally {
      movingRef.current = false;
    }
  }, [canDrop, refresh]);

  const closePinRequest = useCallback(() => setPinRequest(null), []);

  const confirmPin = useCallback(async (version: number) => {
    if (!pinRequest) return;
    try {
      const result = await versionService.pinVersion(pinRequest.workflowId, version);
      const workflowId = pinRequest.workflowId;
      const sourcePublicationId = pinRequest.sourcePublicationId;
      setPinRequest(null);
      // Applications watch their production run on the application surface, not the workflow
      // run URL (the app card never points at the builder - see WorkflowBoardCard).
      if (sourcePublicationId) {
        router.push(`/app/applications/${sourcePublicationId}`);
        return;
      }
      // Redirect to the production run so the user can watch scheduled fires live.
      // Without this they stay on the board / builder edit URL which doesn't subscribe
      // to the WS channel (see WorkflowModeContext) - schedule ticks are invisible
      // until the user manually opens the run from the run history. Fall back to
      // refresh() if the pin succeeded but no production run was resolvable yet.
      if (result.productionRunIdPublic) {
        // Canonical route is /app/workflow/{id}/run/{runId} (singular `workflow`)
        // - see WorkflowRunsHistoryPanel.handleRunClick / WorkflowBoardCard /
        // WorkflowModeToggle for the same pattern. Plural /workflows/.../run/...
        // does NOT exist as a Next.js page and would 404.
        router.push(`/app/workflow/${workflowId}/run/${result.productionRunIdPublic}`);
      } else {
        refresh();
      }
    } catch {
      setErrorCode('moveFailed');
    }
  }, [pinRequest, refresh, router]);

  const totalCount =
    columns.draft.totalCount +
    columns.production.totalCount +
    columns.needsReview.totalCount +
    columns.paused.totalCount;

  return {
    columns,
    totalCount,
    initialLoading,
    errorCode,
    refresh,
    loadMore,
    dismissError,
    moveCard,
    canDrop,
    pinRequest,
    closePinRequest,
    confirmPin,
  };
}

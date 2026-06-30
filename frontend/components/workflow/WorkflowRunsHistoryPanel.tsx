'use client';

import React, { useState, useEffect, useCallback } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { X, History, RefreshCw, XCircle, StepForward, Pin, Calendar } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { StatusBadge } from '@/components/ui/StatusBadge';
import { orchestratorApi, WorkflowRun } from '@/lib/api/orchestrator';
import { cn } from '@/lib/utils';
import { useTranslations } from 'next-intl';
import { formatRelativeDateI18n, formatDuration, formatDurationFromTimes, formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { getRunDisplayStatus } from '@/lib/utils/runStatusUtils';

interface WorkflowRunsHistoryPanelProps {
  isOpen: boolean;
  onClose: () => void;
  workflowId?: string;
  currentRunId?: string;
}

const mapRunStatus = (status: string): 'pending' | 'running' | 'completed' | 'failed' | 'cancelled' | 'skipped' => {
  const statusMap: Record<string, 'pending' | 'running' | 'completed' | 'failed' | 'cancelled' | 'skipped'> = {
    PENDING: 'pending',
    RUNNING: 'running',
    COMPLETED: 'completed',
    FAILED: 'failed',
    CANCELLED: 'cancelled',
    SKIPPED: 'skipped',
  };
  return statusMap[status] || 'pending';
};

const formatRunDuration = (durationMs: number | undefined, startedAt: string | undefined, endedAt: string | undefined, completedAt: string | undefined) => {
  if (durationMs) {
    return formatDuration(durationMs);
  }
  const endTime = endedAt || completedAt;
  return formatDurationFromTimes(startedAt, endTime);
};

export function WorkflowRunsHistoryPanel({
  isOpen,
  onClose,
  workflowId,
  currentRunId,
}: WorkflowRunsHistoryPanelProps) {
  const t = useTranslations();
  const router = useRouter();
  const [runs, setRuns] = useState<WorkflowRun[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const [pinnedVersion, setPinnedVersion] = useState<number | null>(null);
  const [pinnedRun, setPinnedRun] = useState<WorkflowRun | null>(null);
  const observerTarget = React.useRef<HTMLDivElement>(null);
  const offsetRef = React.useRef(0);

  const LIMIT = 15;

  // Fetch pinnedVersion + pinnedRun when panel opens
  useEffect(() => {
    if (!isOpen || !workflowId) return;
    orchestratorApi.listVersions(workflowId)
      .then((data) => setPinnedVersion(data.pinnedVersion ?? null))
      .catch(() => {});
    orchestratorApi.getPinnedWorkflowRun(workflowId)
      .then((run) => setPinnedRun(run))
      .catch(() => setPinnedRun(null));
  }, [isOpen, workflowId]);

  // Listen for pin/unpin changes
  useEffect(() => {
    const handler = (e: Event) => {
      const newPinned = (e as CustomEvent).detail?.pinnedVersion ?? null;
      setPinnedVersion(newPinned);
      // Re-fetch pinned run when pin changes
      if (workflowId && newPinned != null) {
        orchestratorApi.getPinnedWorkflowRun(workflowId)
          .then((run) => setPinnedRun(run))
          .catch(() => setPinnedRun(null));
      } else {
        setPinnedRun(null);
      }
    };
    window.addEventListener('workflowPinnedVersionChange', handler);
    return () => window.removeEventListener('workflowPinnedVersionChange', handler);
  }, [workflowId]);

  const fetchRuns = useCallback(async (reset: boolean = false) => {
    if (!workflowId) return;

    const currentOffset = reset ? 0 : offsetRef.current;

    try {
      if (reset) {
        setLoading(true);
        setRuns([]);
        offsetRef.current = 0;
        setHasMore(true);
      } else {
        setLoadingMore(true);
      }

      setError(null);
      const data = await orchestratorApi.getWorkflowRuns(workflowId, LIMIT, currentOffset);
      console.log('[WorkflowRunsHistoryPanel] Runs data:', data, 'offset:', currentOffset);

      if (reset) {
        setRuns(data || []);
      } else {
        setRuns(prev => [...prev, ...(data || [])]);
      }

      // If we got less than LIMIT, no more data
      const hasMoreData = data && data.length === LIMIT;
      console.log('[InfiniteScroll] Fetch complete:', {
        returned: data?.length,
        limit: LIMIT,
        hasMore: hasMoreData,
        nextOffset: currentOffset + LIMIT,
      });
      setHasMore(hasMoreData);
      offsetRef.current = currentOffset + LIMIT;
    } catch (err) {
      console.error('Error fetching runs:', err);
      setError(t('runs.loadError'));
    } finally {
      setLoading(false);
      setLoadingMore(false);
    }
  }, [workflowId]);

  useEffect(() => {
    if (isOpen && workflowId) {
      fetchRuns(true); // Reset on open
    }
  }, [isOpen, workflowId]);

  // Infinite scroll observer
  useEffect(() => {
    if (!isOpen || !hasMore || loadingMore) {
      console.log('[InfiniteScroll] Observer skipped:', { isOpen, hasMore, loadingMore });
      return;
    }

    console.log('[InfiniteScroll] Setting up observer');

    const observer = new IntersectionObserver(
      (entries) => {
        console.log('[InfiniteScroll] Intersection detected:', {
          isIntersecting: entries[0].isIntersecting,
          hasMore,
          loadingMore,
          runsCount: runs.length,
        });

        if (entries[0].isIntersecting && hasMore && !loadingMore) {
          console.log('[InfiniteScroll] Triggering fetchRuns');
          fetchRuns(false);
        }
      },
      { threshold: 0.1 }
    );

    const currentTarget = observerTarget.current;
    if (currentTarget) {
      console.log('[InfiniteScroll] Observing target');
      observer.observe(currentTarget);
    } else {
      console.warn('[InfiniteScroll] No target to observe');
    }

    return () => {
      if (currentTarget) {
        observer.unobserve(currentTarget);
      }
    };
  }, [isOpen, hasMore, loadingMore, fetchRuns, runs.length]);

  const handleRunClick = (run: WorkflowRun) => {
    if (run.runId !== currentRunId) {
      router.push(`/app/workflow/${workflowId}/run/${run.runId}`);
      onClose();
    }
  };

  if (!isOpen) return null;

  return (
    <>
      {/* Close button - positioned outside the panel (desktop only) */}
      <Button
        onClick={onClose}
        variant="secondary"
        size="sm"
        className="hidden sm:flex absolute top-0 -left-10 h-8 w-8 p-0 rounded-full z-[100] bg-[var(--bg-primary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] shadow-none"
      >
        <X className="h-4 w-4" />
      </Button>

      {/* Main panel - same style as NodeCreatorPanel */}
      <div
        data-runs-history-panel
        className="w-[min(340px,calc(100vw-48px))] max-h-[800px] rounded-[32px] bg-white/80 dark:bg-gray-800/80 backdrop-blur flex flex-col pointer-events-auto overflow-hidden relative z-[100]"
      >
        {/* Mobile close button - inside panel */}
        <div className="sm:hidden flex justify-end px-3 pt-3 pb-0 flex-shrink-0">
          <Button onClick={onClose} variant="ghost" size="sm" className="h-7 w-7 p-0 rounded-full">
            <X className="h-4 w-4" />
          </Button>
        </div>

        {/* Header */}
        <div className="flex items-center justify-between px-4 sm:px-6 py-4 border-b border-gray-200 dark:border-gray-700 flex-shrink-0">
          <div className="flex items-center gap-2">
            <History className="w-5 h-5 text-theme-primary" />
            <h2 className="text-sm font-semibold text-theme-primary">{t('runs.title')}</h2>
          </div>
          <Button
            variant="ghost"
            size="icon"
            onClick={() => fetchRuns(true)}
            disabled={loading}
            className="w-7 h-7"
            title={t('actions.refresh')}
          >
            <RefreshCw className={cn("w-4 h-4", loading && "animate-spin")} />
          </Button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-4 py-4">
        {error ? (
          <div className="flex flex-col items-center justify-center py-12 text-center text-theme-secondary">
            <XCircle className="w-12 h-12 mb-4 opacity-50" />
            <p className="text-sm mb-4">{error}</p>
            <Button variant="outline" size="sm" onClick={() => fetchRuns()}>
              {t('errors.retry')}
            </Button>
          </div>
        ) : runs.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-center text-theme-secondary">
            <History className="w-12 h-12 mb-4 opacity-50" />
            <p className="text-sm">{t('runs.noRuns')}</p>
            <p className="text-xs mt-2 opacity-70">{t('runs.runWorkflow')}</p>
          </div>
        ) : (
          <div className="space-y-1">
            {/* Sort: pinned (production) run first, then rest in chronological order */}
            {(() => {
              // Build display list: pinned run at #1, then remaining runs (excluding duplicate)
              const displayRuns = (() => {
                if (!pinnedRun) return runs;
                const rest = runs.filter(r => r.id !== pinnedRun.id);
                return [pinnedRun, ...rest];
              })();

              return displayRuns.map((run) => {
              const isCurrentRun = currentRunId && (currentRunId === run.id || currentRunId === run.runId);
              const displayStatus = getRunDisplayStatus(run.status, run.metadata);
              const status = mapRunStatus(displayStatus);

              return (
                <div
                  key={run.id}
                  onClick={() => handleRunClick(run)}
                  className={cn(
                    "px-3 py-2.5 transition-all duration-200 group cursor-pointer rounded-lg",
                    isCurrentRun
                      ? "bg-[var(--accent-primary)]/5 ring-1 ring-[var(--accent-primary)]/20 shadow-sm"
                      : "hover:bg-gray-100 dark:hover:bg-gray-700/50"
                  )}
                >
                  <div className="flex items-center justify-between gap-3 mb-1">
                    <div className="flex items-center gap-2">
                      <StatusBadge status={status} variant="noBackground" />
                      {run.executionMode === 'step_by_step' && (
                        <div className="flex items-center justify-center w-5 h-5 rounded-full bg-purple-100 dark:bg-purple-900/30" title={t('runs.stepByStepMode')}>
                          <StepForward className="w-3 h-3 text-purple-700 dark:text-purple-300" />
                        </div>
                      )}
                      {run.planVersion != null && (
                        <>
                          <span className="text-xs text-theme-secondary opacity-50">·</span>
                          <span className="flex items-center gap-0.5 text-xs font-medium text-theme-secondary">
                            {t('runs.version', { version: run.planVersion })}
                            {/* Match WorkflowModeToggle (commit 94f7b4080): key off the
                                pinned VERSION, not the trusted-run id. getPinnedWorkflowRun
                                returns null for CANCELLED/FAILED prod runs (TRUSTED_STATUSES
                                excludes them), so `run.id === latestPinnedRunId` silently
                                drops the pin badge on terminal runs at the pinned version. */}
                            {pinnedVersion != null && run.planVersion === pinnedVersion && (
                              <Pin className="w-2.5 h-2.5 text-amber-500 dark:text-amber-400" />
                            )}
                          </span>
                        </>
                      )}
                      {run.currentEpoch != null && run.currentEpoch > 0 && (
                        <>
                          <span className="text-xs text-theme-secondary opacity-50">·</span>
                          <span className="flex items-center gap-1 text-xs font-medium text-theme-secondary tabular-nums">
                            <Calendar className="w-3 h-3" />
                            {run.currentEpoch}
                          </span>
                        </>
                      )}
                      {run.totalNodes !== undefined && (
                        <>
                          <span className="text-xs text-theme-secondary opacity-50">·</span>
                          <span className="text-xs text-theme-secondary">
                            {run.totalNodes} {run.totalNodes === 1 ? t('runs.node') : t('runs.nodes')}
                          </span>
                        </>
                      )}
                    </div>
                    {(() => {
                      // Reusable triggers reuse one run across many fires:
                      // run.startedAt = run birth (often days old), lastFireAt =
                      // most recent epoch's started_at (the "last execution"
                      // the user expects in this panel). Fall back to startedAt
                      // for single-shot runs that never fired an epoch.
                      const display = run.lastFireAt ?? run.startedAt;
                      // Tooltip surfaces both anchors so the relative number
                      // never contradicts the absolute date the user inspects:
                      // for a multi-epoch run "1h ago" comes with "Run started:
                      // 3d ago • Last fire: 1h ago".
                      // Guard getClientLocale() for SSR (panel is 'use client' but
                      // first render still happens server-side before hydration -
                      // navigator is undefined there).
                      const locale = getClientLocale();
                      const tooltipParts: string[] = [];
                      if (run.startedAt) {
                        tooltipParts.push(`${t('runs.runStarted')}: ${formatUtcDateTime(run.startedAt, { locale })}`);
                      }
                      if (run.lastFireAt && run.lastFireAt !== run.startedAt) {
                        tooltipParts.push(`${t('runs.lastFire')}: ${formatUtcDateTime(run.lastFireAt, { locale })}`);
                      }
                      const title = tooltipParts.length > 0 ? tooltipParts.join(' • ') : undefined;
                      return (
                        <span className="text-xs text-theme-secondary" title={title}>
                          {display
                            ? formatRelativeDateI18n(display, (key, params) => t(`runs.${key}`, params))
                            : '-'}
                        </span>
                      );
                    })()}
                  </div>

                  <div className="flex items-center justify-between text-xs text-theme-secondary">
                    <span className="font-mono truncate">{run.runId}</span>
                    {(run.durationMs || (run.startedAt && (run.endedAt || run.completedAt))) && (
                      <span className="ml-2 flex-shrink-0">
                        {formatRunDuration(run.durationMs, run.startedAt, run.endedAt, run.completedAt)}
                      </span>
                    )}
                  </div>
                </div>
              );
            });
            })()}

            {/* Loading more indicator */}
            {loadingMore && (
              <div className="flex items-center justify-center py-4">
                <RefreshCw className="w-4 h-4 animate-spin text-theme-secondary" />
              </div>
            )}

            {/* Intersection observer target - visible for debugging */}
            {hasMore && !loadingMore && (
              <div
                ref={observerTarget}
                className="h-8 flex items-center justify-center border-t border-dashed border-gray-300 dark:border-gray-600"
              >
                <span className="text-xs text-theme-secondary opacity-50">
                  {t('runs.scrollToLoadMore')}
                </span>
              </div>
            )}

            {/* No more runs indicator */}
            {!hasMore && runs.length > 0 && (
              <div className="text-center py-3 text-xs text-theme-secondary opacity-50">
                {t('runs.noMoreRuns', { defaultValue: 'No more runs' })}
              </div>
            )}
          </div>
        )}
        </div>
      </div>
    </>
  );
}

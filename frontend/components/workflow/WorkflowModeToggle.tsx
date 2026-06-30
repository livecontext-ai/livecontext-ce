'use client';

import { useRef, useEffect, useState, useCallback, useMemo, memo } from 'react';
import { createPortal } from 'react-dom';
// Aliased: `List` is also a lucide-react icon used for the list-view toggle button.
import { List as VirtualList, useListRef, type RowComponentProps } from 'react-window';
import { Edit3, Play, History, Square, StepForward, ChevronDown, ChevronUp, List, GanttChart, Calendar, CheckCircle2, XCircle, Loader2, CircleSlash, PauseCircle, Eye, Pin } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useTranslations, useLocale } from 'next-intl';
import { useRouter, usePathname } from 'next/navigation';
import type { Node } from 'reactflow';
import { orchestratorApi, type WorkflowRun } from '@/lib/api';
import { formatUtcTime, formatUtcDateTime, parseUtcAware, formatRelativeDateI18n } from '@/lib/utils/dateFormatters';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';
import { useToast } from '@/components/Toast';
import ToastContainer from '@/components/ToastContainer';
import { getRunDisplayStatus, getStatusClasses, getRunStatusLabel } from '@/lib/utils/runStatusUtils';
import { getIconSlug, NodeIcon } from '@/app/workflows/builder/components/nodes/shared';
import { nodeMatchesStep } from '@/app/workflows/builder/services/nodeMatcher';
import { findNodeClassById } from '@/app/workflows/builder/nodes/nodeClasses';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { VIEWING_EPOCH_EVENT, shouldAdoptEpochEvent, type EpochEventDetail } from '@/lib/workflow/epochEventScope';
import { isEmbeddedWorkflowCanvas } from '@/lib/workflow/canvasEmbedding';
import { StepRowActions } from '@/components/workflow/StepRowActions';
import { computeRunInfoPanelWidths } from '@/components/workflow/runInfoPanelWidth';


interface EpochTimestamp {
  epoch: number;
  startedAt: string;
  endedAt: string | null;
}

interface WorkflowModeToggleProps {
  mode: 'edit' | 'run';
  onModeChange?: (mode: 'edit' | 'run') => void;
  workflowId?: string;
  /** Hide the edit/run mode toggle (for application mode, always in run) */
  hideToggle?: boolean;
  /** Show a "Read only" badge instead of the toggle */
  showReadOnlyBadge?: boolean;
  // Run info props
  currentRunInfo?: (WorkflowRun & {
    completedCount?: number;
    failedCount?: number;
    runningCount?: number;
    skippedCount?: number;
    executionTotal?: number;
  }) | null;
  isStepByStep?: boolean;
  isRunsHistoryOpen?: boolean;
  onOpenRunsHistory?: () => void;
  onStop?: () => void;
  onCancel?: () => void;
  onReactivate?: () => void;
  canvasNodesRef?: React.RefObject<Node<BuilderNodeData>[]>;
  /** Current epoch number (0 = no fire, 1+ = epoch count) */
  currentEpoch?: number;
  /** Epoch timestamps for the epoch selector */
  epochTimestamps?: EpochTimestamp[];
  /** Callback when user selects an epoch to view on the canvas (null = back to live) */
  onViewEpoch?: (epoch: number | null) => void;
  /** Pinned (production) version of the workflow, null if unpinned */
  pinnedVersion?: number | null;
  /** Run ID of the latest run matching the pinned version (only this run gets the pin badge) */
  latestPinnedRunId?: string | null;
  /** Aggregated steps from WebSocket (includes timing). When available, skips REST polling. */
  streamedSteps?: Array<{
    alias: string;
    toolId: string;
    status: string;
    startTime: string | null;
    endTime: string | null;
    executionTimeMs?: number;
    totalExecutionTimeMs?: number;
    statusCounts?: { completed?: number; failed?: number; skipped?: number; running?: number; awaitingSignal?: number };
  }>;
  /** When settings panel is open, hide run info & history button */
  isSettingsOpen?: boolean;
}

/** Terminal run statuses - a trigger cannot be fired into one of these (the
 *  backend dispatcher rejects it), so the run-info popover hides the play. */
const TERMINAL_RUN_STATUSES = new Set([
  'COMPLETED', 'FAILED', 'PARTIAL_SUCCESS', 'CANCELLED', 'TIMEOUT', 'SKIPPED',
]);

export function WorkflowModeToggle({
  mode,
  onModeChange,
  workflowId,
  hideToggle = false,
  showReadOnlyBadge = false,
  currentRunInfo,
  isStepByStep = false,
  isRunsHistoryOpen = false,
  onOpenRunsHistory,
  onStop,
  onCancel,
  onReactivate,
  canvasNodesRef,
  currentEpoch = 0,
  epochTimestamps = [],
  onViewEpoch,
  streamedSteps,
  pinnedVersion,
  latestPinnedRunId,
  isSettingsOpen = false,
}: WorkflowModeToggleProps) {
  const t = useTranslations();
  const locale = useLocale();
  // Relative time, fully localized: the `just now`/`Nm ago` literals come from
  // the shared `runs.*` i18n keys (not hardcoded English) and the >7-day
  // absolute fallback follows the APP locale, so the whole badge stays in one
  // language instead of mixing English text with a cookie-locale month.
  const formatRel = useCallback(
    (d: string | Date | null | undefined) =>
      formatRelativeDateI18n(d, (k, p) => t(`runs.${k}`, p), locale),
    [t, locale],
  );
  const router = useRouter();
  const pathname = usePathname();
  const { toasts, addToast, removeToast } = useToast();
  // Canvas run identity - used to scope the cross-tree viewingEpochChanged event
  // so side-panel app tabs for OTHER runs don't move this canvas's epoch.
  const { setViewingEpoch, setRunId, runId: canvasRunId } = useWorkflowMode();
  const [sliderStyle, setSliderStyle] = useState({ left: 0, width: 0, opacity: 0 });
  const [isStepsExpanded, setIsStepsExpanded] = useState(true);
  const [stepsView, setStepsView] = useState<'list' | 'waterfall'>('list');
  /** Status filter for steps: null = all, string = filter by that status */
  const [statusFilter, setStatusFilter] = useState<string | null>(null);
  /** null = "All epochs" (default), number = specific epoch */
  const [selectedEpoch, setSelectedEpoch] = useState<number | null>(null);

  // Sync selectedEpoch from cross-tree viewingEpochChanged events
  // (e.g. ApplicationTabContent epoch selector in SidePanel)
  const isLocalEpochDispatchRef = useRef(false);

  // Stable callback for the memoized EpochSelector. Without useCallback the
  // arrow recreates on every parent render, defeating the EpochSelector memo()
  // wrapper and forcing react-window to re-render every visible row on every
  // SSE push. setSelectedEpoch is identity-stable; onViewEpoch is captured
  // through the deps list and re-bound only when the parent prop changes.
  const handleSelectEpoch = useCallback((epoch: number | null) => {
    setSelectedEpoch(epoch);
    isLocalEpochDispatchRef.current = true;
    onViewEpoch?.(epoch);
    Promise.resolve().then(() => { isLocalEpochDispatchRef.current = false; });
  }, [onViewEpoch]);
  useEffect(() => {
    const handler = (e: Event) => {
      if (isLocalEpochDispatchRef.current) return;
      const detail = (e as CustomEvent).detail as EpochEventDetail | undefined;
      // Ignore epoch changes broadcast for a DIFFERENT run (e.g. a sibling app
      // tab in the side panel) so this canvas only follows its own run's epoch.
      if (!shouldAdoptEpochEvent(detail?.runId, canvasRunId)) return;
      setSelectedEpoch(detail?.epoch ?? null);
    };
    window.addEventListener(VIEWING_EPOCH_EVENT, handler);
    return () => window.removeEventListener(VIEWING_EPOCH_EVENT, handler);
  }, [canvasRunId]);
  /** Cancel confirmation modal (for WAITING_TRIGGER → terminal CANCELLED) */
  const [cancelConfirm, setCancelConfirm] = useState(false);
  const [mounted, setMounted] = useState(false);
  type StepEntry = {
    alias: string;
    toolId: string;
    status: string;
    startTime: string | null;
    endTime: string | null;
    executionTimeMs?: number;
    totalExecutionTimeMs?: number;
    statusCounts?: { completed?: number; failed?: number; skipped?: number; running?: number; awaitingSignal?: number };
  };
  /** Per-epoch REST-fetched steps (only used when selectedEpoch != null) */
  const [epochFetchedSteps, setEpochFetchedSteps] = useState<StepEntry[]>([]);
  const [stepsLoading, setStepsLoading] = useState(false);
  const editButtonRef = useRef<HTMLButtonElement>(null);
  const runButtonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  /** Invisible probe element to measure the real available container width
   *  (accounts for SidePanel, sidebar, etc. - not just window.innerWidth). */
  const probeRef = useRef<HTMLDivElement>(null);
  /** Available width of the canvas container (updated via ResizeObserver). */
  const [containerWidth, setContainerWidth] = useState(
    typeof window !== 'undefined' ? window.innerWidth : 1200,
  );

  const isRunMode = mode === 'run';
  const showRunInfo = isRunMode && currentRunInfo && !isRunsHistoryOpen;
  // A trigger can be fired from the run-info popover only while the run is
  // non-terminal (mirrors the canvas, which hides the trigger play once nothing
  // is ready, and the dispatcher, which rejects firing into a terminal run).
  const runStatusUpper = (currentRunInfo?.status || '').toUpperCase();
  const isRunActive = !!runStatusUpper && !TERMINAL_RUN_STATUSES.has(runStatusUpper);

  useEffect(() => {
    const activeButtonRef = mode === 'edit' ? editButtonRef : runButtonRef;
    if (activeButtonRef.current) {
      const { offsetLeft, offsetWidth } = activeButtonRef.current;
      setSliderStyle({
        left: offsetLeft,
        width: offsetWidth,
        opacity: 1,
      });
    }
  }, [mode]);

  useEffect(() => { setMounted(true); return () => setMounted(false); }, []);

  // Measure the real available container width via ResizeObserver on the
  // invisible probe element. This correctly handles SidePanel open/close,
  // sidebar collapse, and window resize - unlike window.innerWidth.
  useEffect(() => {
    const el = probeRef.current?.parentElement;
    if (!el) return;
    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        setContainerWidth(entry.contentRect.width);
      }
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // Derive isWideEnough from containerWidth - center the toggle only when
  // there is enough room so it won't collide with the RunInfo panel.
  const isWideEnough = containerWidth >= (showRunInfo ? 900 : 640);

  // Derive aggregatedSteps: for "all epochs" use streamedSteps directly (no state),
  // for per-epoch view use REST-fetched epochFetchedSteps.
  const aggregatedSteps: StepEntry[] = useMemo(() => {
    if (selectedEpoch != null) return epochFetchedSteps;
    return streamedSteps ?? [];
  }, [selectedEpoch, streamedSteps, epochFetchedSteps]);

  // Loading: for all-epoch view, loading until streamedSteps is defined
  const isStepsLoading = selectedEpoch == null ? streamedSteps === undefined : stepsLoading;

  // REST fetch only for per-epoch views (epoch selector) - WebSocket data is all-epochs.
  // Marketplace anonymous preview: route through the public /aggregated-steps
  // endpoint when getActivePublicPreview() matches this runId, so the RunInfo
  // panel populates its step list for anonymous visitors exactly like
  // authenticated owners.
  //
  // MF-3 (audit 2026-05-09): generation counter to discard out-of-order responses.
  // Without this, rapid epoch clicks A→B fire parallel fetches; if A resolves
  // after B, A's data overwrites B's data while selectedEpoch=B → stale.
  const fetchGenerationRef = useRef(0);
  const fetchEpochSteps = useCallback(async (epoch: number) => {
    const runId = currentRunInfo?.runId || currentRunInfo?.id;
    if (!runId) return;
    const myGeneration = ++fetchGenerationRef.current;
    try {
      setStepsLoading(true);
      const publicCtx = getActivePublicPreview();
      const data = publicCtx && publicCtx.showcaseRunId === runId
        ? await publicationService.getShowcaseAggregatedSteps(publicCtx.publicationId, epoch, publicCtx.remote)
        : await orchestratorApi.getEpochAggregatedSteps(runId, epoch);
      // Discard out-of-order responses: only the latest fetch wins.
      if (myGeneration !== fetchGenerationRef.current) return;
      if (Array.isArray(data)) {
        setEpochFetchedSteps(data);
      }
    } catch (err) {
      console.error('[WorkflowModeToggle] Failed to fetch epoch steps:', err);
    } finally {
      // Only the latest in-flight clears the loading state - earlier ones bail.
      if (myGeneration === fetchGenerationRef.current) {
        setStepsLoading(false);
      }
    }
  }, [currentRunInfo?.runId, currentRunInfo?.id]);

  // When user selects a specific epoch, fetch from REST (per-epoch data).
  // MF-1 (audit 2026-05-09): gate the SYNCHRONOUS clear on selectedEpoch CHANGE,
  // not on every effect re-run (e.g. isStepsExpanded toggle while parked on an
  // epoch shouldn't blank the panel).
  const prevSelectedEpochRef = useRef<number | null | undefined>(undefined);
  useEffect(() => {
    if (selectedEpoch != null && isStepsExpanded) {
      const epochChanged = prevSelectedEpochRef.current !== selectedEpoch;
      if (epochChanged) {
        // Clear stale per-epoch data SYNCHRONOUSLY before the new fetch resolves.
        // Without this, the panel shows the previous epoch's counts (or aggregate
        // counts from a prior session via stale closure) until the fetch completes.
        setEpochFetchedSteps([]);
      }
      fetchEpochSteps(selectedEpoch);
    }
    prevSelectedEpochRef.current = selectedEpoch;
  }, [selectedEpoch, isStepsExpanded, fetchEpochSteps]);


  // Reset UI state when run changes (data derives from streamedSteps automatically)
  useEffect(() => {
    setIsStepsExpanded(true);
    setSelectedEpoch(null);
    setEpochFetchedSteps([]);
    // MF-4 (audit 2026-05-09): reset the gate ref to undefined so a fresh
    // selection on the new run fires the pre-clear correctly even if the
    // user picks the same epoch number that was selected on the prior run.
    prevSelectedEpochRef.current = undefined;
  }, [currentRunInfo?.runId, currentRunInfo?.id]);

  // Reset selected epoch (local + context viewingEpoch) when toggling between edit/run
  // so the view defaults to "all" instead of carrying over the previously pinned epoch.
  const isEmbedded = isEmbeddedWorkflowCanvas(pathname, workflowId);
  useEffect(() => {
    setSelectedEpoch(null);
    setViewingEpoch(null);
    // MF-4: same gate-ref reset on edit/run mode toggle.
    prevSelectedEpochRef.current = undefined;
  }, [mode, setViewingEpoch]);

  // Find the matching canvas node for a step alias (for icon rendering)
  const findNodeForStep = useCallback((stepAlias: string): Node<BuilderNodeData> | undefined => {
    const nodes = canvasNodesRef?.current;
    if (!nodes?.length) return undefined;
    return nodes.find((n) => nodeMatchesStep(n, { stepAlias, id: stepAlias }));
  }, [canvasNodesRef]);

  // Filter steps by selected status
  const filteredSteps = useMemo(() => {
    if (!statusFilter) return aggregatedSteps;
    // Map filter key to statusCounts field (awaiting_signal → awaitingSignal)
    const countsKey = statusFilter === 'awaiting_signal' ? 'awaitingSignal' : statusFilter;
    return aggregatedSteps.filter(s => {
      // Match on step's own status
      if (s.status === statusFilter) return true;
      // Also match if the step has non-zero count for that status
      if (s.statusCounts && (s.statusCounts as Record<string, number | undefined>)[countsKey]! > 0) return true;
      return false;
    });
  }, [aggregatedSteps, statusFilter]);

  const handleModeClick = async (newMode: 'edit' | 'run') => {
    if (!workflowId) return;

    // Allow re-clicking Run mode to load latest run (refresh)
    if (newMode === mode && newMode === 'edit') return;

    if (newMode === 'run') {
      // Show the most recent run regardless of version (debug/observation tool).
      try {
        const latestRun = await orchestratorApi.getLatestWorkflowRun(workflowId);
        if (latestRun) {
          const actualRunId = latestRun.runId || (latestRun as any).id;
          console.log('[WorkflowModeToggle] Latest run found:', actualRunId);
          if (isEmbedded) {
            setRunId(actualRunId);
          } else {
            router.push(`/app/workflow/${workflowId}/run/${actualRunId}`);
          }
          onModeChange?.(newMode);
        } else {
          console.log('[WorkflowModeToggle] No runs found for this workflow');
          addToast({
            type: 'info',
            title: t('workflow.mode.noRunsTitle'),
            message: t('workflow.mode.noRunsMessage'),
          });
        }
      } catch (error) {
        console.error('[WorkflowModeToggle] Failed to load run:', error);
      }
    } else {
      if (isEmbedded) {
        setRunId(null);
      } else {
        router.push(`/app/workflow/${workflowId}`);
      }
      onModeChange?.(newMode);
    }
  };

  // Width bounds for the RunInfo panel - see computeRunInfoPanelWidths. The mode
  // toggle is hidden in the embedded application side panel, so reserve room for
  // it only when shown, and never exceed the container (a narrow side panel used
  // to push the panel + its epoch-selector rows off-screen, out of reach).
  const { minWidth: runInfoMinWidth, maxWidth: runInfoMaxWidth } = computeRunInfoPanelWidths(
    containerWidth,
    !hideToggle && !showReadOnlyBadge,
  );

  return (
    <>
      {/* Invisible probe - spans the full container width so ResizeObserver
          can measure the real available space (not window.innerWidth). */}
      <div ref={probeRef} className="absolute inset-x-0 top-0 h-0 pointer-events-none" aria-hidden />

      {/* Mode Toggle - Centered (hidden in application/preview mode) */}
      {!hideToggle && !showReadOnlyBadge && (
        <div className={`absolute top-4 z-[40] ${
          isWideEnough ? 'left-1/2 -translate-x-1/2' : 'left-4'
        }`}>
          <div className="relative inline-flex items-center gap-0.5 p-1 bg-white dark:bg-gray-800 rounded-full">
            {/* Animated background slider */}
            <div
              className="absolute top-1 bottom-1 rounded-full bg-theme-secondary transition-all duration-300 ease-out"
              style={{
                left: `${sliderStyle.left}px`,
                width: `${sliderStyle.width}px`,
                opacity: sliderStyle.opacity,
              }}
            />

            {/* Edit Mode Button */}
            <button
              ref={editButtonRef}
              onClick={() => handleModeClick('edit')}
              title={t('workflow.mode.edit')}
              className={`
                relative z-10 flex items-center justify-center w-7 h-7 rounded-full
                transition-all duration-200 focus-visible:outline-none focus-visible:ring-2
                focus-visible:ring-theme-tertiary outline-none
                ${mode === 'edit'
                  ? 'text-gray-900 dark:text-gray-100'
                  : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-300 hover:bg-gray-100/50 dark:hover:bg-gray-700/50'
                }
              `}
            >
              <Edit3 className={`w-3.5 h-3.5 transition-colors duration-200 ${mode === 'edit' ? 'text-gray-900 dark:text-gray-100' : 'text-current'}`} />
            </button>

            {/* Run Mode Button */}
            <button
              ref={runButtonRef}
              onClick={() => handleModeClick('run')}
              title={t('workflow.mode.run')}
              className={`
                relative z-10 flex items-center justify-center w-7 h-7 rounded-full
                transition-all duration-200 focus-visible:outline-none focus-visible:ring-2
                focus-visible:ring-theme-tertiary outline-none
                ${mode === 'run'
                  ? 'text-gray-900 dark:text-gray-100'
                  : 'text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-300 hover:bg-gray-100/50 dark:hover:bg-gray-700/50'
                }
              `}
            >
              <Play className={`w-3.5 h-3.5 transition-colors duration-200 ${mode === 'run' ? 'text-gray-900 dark:text-gray-100' : 'text-current'}`} />
            </button>
          </div>
        </div>
      )}

      {/* Read-only badge (shown in preview mode instead of the toggle) */}
      {showReadOnlyBadge && (
        <div className={`absolute top-4 z-[40] ${
          isWideEnough ? 'left-1/2 -translate-x-1/2' : 'left-4'
        }`}>
          <div className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-white dark:bg-gray-800 rounded-full text-sm font-medium text-gray-500 dark:text-gray-400">
            <Eye className="w-4 h-4" />
            <span>{t('workflow.mode.readOnly')}</span>
          </div>
        </div>
      )}

      {/* Run Info & History - Right side */}
      {isRunMode && !isRunsHistoryOpen && !isSettingsOpen && (
        <div className="absolute top-4 right-2 sm:right-4 bottom-4 z-[40] flex items-start gap-2 sm:gap-3 pointer-events-none" style={{ maxWidth: runInfoMaxWidth }}>
          {/* Run Info */}
          {showRunInfo && (
            <div ref={panelRef} data-run-info-panel className={`pointer-events-auto bg-white dark:bg-gray-800 transition-all flex flex-col ${isStepsExpanded ? 'min-w-0 w-fit' : 'w-fit max-w-full'}`} style={{ borderRadius: isStepsExpanded ? '1rem' : '9999px', minWidth: isStepsExpanded ? runInfoMinWidth : undefined, maxWidth: isStepsExpanded ? runInfoMaxWidth : undefined, maxHeight: isStepsExpanded ? 'min(100%, 70vh)' : undefined }}>
              <div
                className="flex items-center gap-2 px-3 sm:px-4 py-2 cursor-pointer flex-shrink-0 overflow-hidden"
                onClick={() => setIsStepsExpanded(prev => !prev)}
              >
                {/* Stop/Cancel/Reactivate button - leftmost */}
                {(() => {
                  const rawStatus = currentRunInfo.status?.toUpperCase();
                  const isStoppable = rawStatus === 'RUNNING' || rawStatus === 'PAUSED';
                  const isCancellable = rawStatus === 'WAITING_TRIGGER';
                  // Every terminal status is reactivatable. Before commit a04f13449 the
                  // backend silently re-cycled FAILED runs on the next trigger fire, which
                  // produced the 73-epoch loop on run_<id> (prod OOM
                  // 2026-05-07 12:40 UTC). With the dispatcher gates now rejecting all
                  // terminals, the user must explicitly reactivate to re-arm - the button
                  // has to be visible for every terminal status, not just CANCELLED.
                  const isReactivatable = !!rawStatus && TERMINAL_RUN_STATUSES.has(rawStatus) && !!onReactivate;
                  if (!(isStoppable && onStop) && !(isCancellable && onCancel) && !isReactivatable) return null;

                  if (isReactivatable) {
                    return (
                      <>
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            onReactivate?.();
                          }}
                          className="flex items-center justify-center w-5 h-5 rounded-full bg-green-100 dark:bg-green-900/30 text-green-600 dark:text-green-400 hover:bg-green-200 dark:hover:bg-green-900/50 transition-colors"
                          title={t('workflow.reactivateRun.title')}
                        >
                          <Play className="w-2.5 h-2.5" />
                        </button>
                        <span className="text-xs text-gray-400 dark:text-gray-500">·</span>
                      </>
                    );
                  }

                  return (
                    <>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          if (isCancellable) {
                            setCancelConfirm(true);
                          } else {
                            onStop?.();
                          }
                        }}
                        className="flex items-center justify-center w-5 h-5 rounded-full bg-red-100 dark:bg-red-900/30 text-red-600 dark:text-red-400 hover:bg-red-200 dark:hover:bg-red-900/50 transition-colors"
                        title={isCancellable ? t('workflow.cancelRun.title') : t('workflow.mode.stopWorkflow')}
                      >
                        <Square className="w-2.5 h-2.5" />
                      </button>
                      <span className="text-xs text-gray-400 dark:text-gray-500">·</span>
                    </>
                  );
                })()}

                {/* Status badge */}
                {(() => {
                  const displaySt = getRunDisplayStatus(currentRunInfo.status, currentRunInfo.metadata);
                  return (
                    <div className={`flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${getStatusClasses(displaySt)}`}>
                      {getRunStatusLabel(displaySt, (k) => t(k))}
                    </div>
                  );
                })()}

                {/* Version badge + pinned indicator - hidden on small screens when collapsed */}
                {currentRunInfo.planVersion != null && (
                  <span className={`flex items-center gap-0.5 ${!isStepsExpanded ? 'hidden sm:flex' : 'flex'}`}>
                    <span className="text-xs text-gray-400 dark:text-gray-500">·</span>
                    <span className="flex items-center gap-0.5 text-xs font-medium text-gray-600 dark:text-gray-300 whitespace-nowrap">
                      v{currentRunInfo.planVersion}
                      {pinnedVersion != null && currentRunInfo.planVersion === pinnedVersion && (
                        <Pin className="w-2.5 h-2.5 text-amber-500 dark:text-amber-400" />
                      )}
                    </span>
                  </span>
                )}

                {/* Epoch indicator - right after version */}
                {currentEpoch > 0 && (
                  <span className={`flex items-center gap-0.5 ${!isStepsExpanded ? 'hidden sm:flex' : 'flex'}`}>
                    <span className="text-xs text-gray-400 dark:text-gray-500">·</span>
                    <span className="flex items-center gap-1 text-xs font-medium text-gray-600 dark:text-gray-300 tabular-nums whitespace-nowrap">
                      <Calendar className="w-3 h-3" />
                      {selectedEpoch != null ? selectedEpoch : (epochTimestamps.length > 0 ? epochTimestamps.length : currentEpoch)}
                    </span>
                  </span>
                )}

                {/* Started at - hidden on small screens when collapsed */}
                {currentRunInfo.startedAt && (
                  <span className={`flex items-center gap-0.5 ${!isStepsExpanded ? 'hidden md:flex' : 'flex'}`}>
                    <span className="text-xs text-gray-400 dark:text-gray-500">·</span>
                    <span className="text-xs text-gray-500 dark:text-gray-400 whitespace-nowrap">
                      {formatRel(currentRunInfo.startedAt)}
                    </span>
                  </span>
                )}

                {/* Step by step badge - icon only on mobile */}
                {isStepByStep && (
                  <>
                    <span className="text-xs text-gray-400 dark:text-gray-500">·</span>
                    <div className="flex items-center gap-1.5 px-2 py-0.5 bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-300 rounded-full">
                      <StepForward className="w-3 h-3" />
                      {isWideEnough && <span className="text-xs font-medium whitespace-nowrap">{t('workflow.mode.stepByStep')}</span>}
                    </div>
                  </>
                )}

                {/* Chevron toggle */}
                {isStepsExpanded ? (
                  <ChevronUp className="w-3.5 h-3.5 text-gray-400 dark:text-gray-500 ml-auto" />
                ) : (
                  <ChevronDown className="w-3.5 h-3.5 text-gray-400 dark:text-gray-500 ml-auto" />
                )}
              </div>

              {/* Expanded steps panel - grows to fill available space like NodeCreatorPanel */}
              {isStepsExpanded && (
                <TooltipProvider delayDuration={150}>
                <div className="overflow-y-auto flex-1 min-h-0">
                  {/* Toolbar: status filter (left) + view toggle (right) */}
                  <div className="flex items-center justify-between px-3 pt-1.5 pb-0.5">
                    {/* Status filter pills */}
                    <div className="inline-flex items-center gap-0.5">
                      {([
                        { key: null, icon: null, label: t('workflow.runSteps.filterAll'), color: 'text-gray-500 dark:text-gray-400' },
                        { key: 'completed', icon: <CheckCircle2 className="w-3 h-3" />, label: '', color: 'text-emerald-600 dark:text-emerald-400' },
                        { key: 'failed', icon: <XCircle className="w-3 h-3" />, label: '', color: 'text-red-500 dark:text-red-400' },
                        { key: 'running', icon: <Loader2 className="w-3 h-3" />, label: '', color: 'text-blue-500 dark:text-blue-400' },
                        { key: 'awaiting_signal', icon: <PauseCircle className="w-3 h-3" />, label: '', color: 'text-amber-500 dark:text-amber-400' },
                        { key: 'skipped', icon: <CircleSlash className="w-3 h-3" />, label: '', color: 'text-gray-400 dark:text-gray-500' },
                      ] as const).map((f) => {
                        const isActive = statusFilter === f.key;
                        return (
                          <button
                            key={f.key ?? 'all'}
                            type="button"
                            onClick={(e) => { e.stopPropagation(); setStatusFilter(f.key); }}
                            className={`p-1 rounded transition-colors ${
                              isActive
                                ? `bg-gray-100 dark:bg-gray-700/50 ${f.color}`
                                : 'text-gray-300 dark:text-gray-600 hover:text-gray-500 dark:hover:text-gray-400'
                            }`}
                            title={f.key ? f.key.charAt(0).toUpperCase() + f.key.slice(1) : t('workflow.runSteps.filterAll')}
                          >
                            {f.icon ?? <span className="text-[10px] font-bold leading-[12px] w-3 h-3 flex items-center justify-center">{t('workflow.runSteps.filterAll')}</span>}
                          </button>
                        );
                      })}
                    </div>

                    {/* View toggle: list vs waterfall */}
                    <div className="inline-flex items-center bg-gray-100 dark:bg-gray-700/50 rounded-md p-0.5">
                      <button
                        type="button"
                        onClick={(e) => { e.stopPropagation(); setStepsView('list'); }}
                        className={`p-1 rounded transition-colors ${stepsView === 'list' ? 'bg-white dark:bg-gray-600 shadow-sm' : 'text-gray-400 dark:text-gray-500 hover:text-gray-600 dark:hover:text-gray-300'}`}
                        title={t('workflow.runSteps.listView')}
                      >
                        <List className="w-3 h-3" />
                      </button>
                      <button
                        type="button"
                        onClick={(e) => { e.stopPropagation(); setStepsView('waterfall'); }}
                        className={`p-1 rounded transition-colors ${stepsView === 'waterfall' ? 'bg-white dark:bg-gray-600 shadow-sm' : 'text-gray-400 dark:text-gray-500 hover:text-gray-600 dark:hover:text-gray-300'}`}
                        title={t('workflow.runSteps.gaugeView')}
                      >
                        <GanttChart className="w-3 h-3" />
                      </button>
                    </div>
                  </div>

                  {/* Epoch selector - hidden when filter yields no steps */}
                  {epochTimestamps.length > 0 && (!statusFilter || filteredSteps.length > 0) && (
                    <EpochSelector
                      epochTimestamps={epochTimestamps}
                      selectedEpoch={selectedEpoch}
                      onSelectEpoch={handleSelectEpoch}
                      viewMode={stepsView}
                    />
                  )}

                  {isStepsLoading ? (
                    <div className="p-3 space-y-2">
                      {Array.from({ length: 4 }).map((_, i) => (
                        <div key={i} className="flex items-center gap-2 px-2 py-1.5">
                          <div className="h-4 w-4 bg-gray-200 dark:bg-gray-700 rounded-full animate-pulse" />
                          <div className="flex-1">
                            <div className="h-3 bg-gray-200 dark:bg-gray-700 rounded animate-pulse w-3/4" />
                          </div>
                          <div className="h-3 w-12 bg-gray-200 dark:bg-gray-700 rounded animate-pulse" />
                        </div>
                      ))}
                    </div>
                  ) : filteredSteps.length === 0 ? (
                    <div className="p-4 text-center text-xs text-gray-400 dark:text-gray-500">
                      {statusFilter ? t('workflow.runSteps.noStepsYet') : t('workflow.runSteps.noStepsYet')}
                    </div>
                  ) : (
                    <>
                      {stepsView === 'list' ? (
                        /* ── List view ── */
                        <div className="py-0.5">
                          {filteredSteps.map((step) => {
                            const matchedNode = findNodeForStep(step.alias);
                            const matchedData = matchedNode?.data;
                            const nodeClass = matchedData ? findNodeClassById(matchedData.id || '') : null;
                            const stepLabel = matchedData?.label || step.alias;
                            return (
                            <Tooltip key={step.alias} delayDuration={150}>
                            <TooltipTrigger asChild>
                            <div
                              className="flex items-center gap-1.5 px-3 py-1 cursor-pointer hover:bg-gray-100/60 dark:hover:bg-gray-700/40 transition-colors"
                              onClick={(e) => {
                                e.stopPropagation();
                                window.dispatchEvent(new CustomEvent('workflowFocusNode', {
                                  detail: { stepAlias: step.alias },
                                }));
                              }}
                            >
                              {/* Icon - w-6 to match waterfall/epoch columns */}
                              <div className="w-6 shrink-0 flex items-center justify-center">
                                {matchedData ? (
                                  <NodeIcon
                                    iconSlug={getIconSlug(matchedData)}
                                    nodeId={matchedData.id || ''}
                                    nodeKind={matchedData.kind}
                                    nodeFamily={nodeClass?.family}
                                    avatarUrl={(matchedData as any)?.agentAvatarUrl}
                                    size="xs"
                                  />
                                ) : (
                                  <div className="h-4 w-4 rounded-full bg-gray-100 dark:bg-gray-700" />
                                )}
                              </div>
                              <span className="flex-1 min-w-0 text-xs font-medium text-gray-900 dark:text-gray-100 truncate">{matchedData?.label || step.alias}</span>
                              {/* Status counts from multi-epoch execution */}
                              {(step.statusCounts?.completed ?? 0) > 0 && (
                                <span className="text-[10px] text-emerald-600 dark:text-emerald-400 flex-shrink-0">✓{step.statusCounts!.completed}</span>
                              )}
                              {(step.statusCounts?.failed ?? 0) > 0 && (
                                <span className="text-[10px] text-red-600 dark:text-red-400 flex-shrink-0">✗{step.statusCounts!.failed}</span>
                              )}
                              {(step.statusCounts?.skipped ?? 0) > 0 && (
                                <span className="text-[10px] text-gray-500 dark:text-gray-400 flex-shrink-0">⊘{step.statusCounts!.skipped}</span>
                              )}
                              {(step.statusCounts?.running ?? 0) > 0 && (
                                <span className="text-[10px] text-blue-600 dark:text-blue-400 flex-shrink-0">⟳{step.statusCounts!.running}</span>
                              )}
                              {(step.statusCounts?.awaitingSignal ?? 0) > 0 && (
                                <span className="inline-flex items-center gap-px text-[10px] text-amber-500 dark:text-amber-400 flex-shrink-0">
                                  <PauseCircle className="w-2.5 h-2.5" />{step.statusCounts!.awaitingSignal}
                                </span>
                              )}
                              {/* Live status indicators (when no terminal counts yet) */}
                              {step.status === 'running' && !(step.statusCounts?.running) && (
                                <Loader2 className="w-3 h-3 text-blue-500 dark:text-blue-400 animate-spin flex-shrink-0" />
                              )}
                              {step.status === 'awaiting_signal' && !(step.statusCounts?.awaitingSignal) && (
                                <PauseCircle className="w-3 h-3 text-amber-500 dark:text-amber-400 flex-shrink-0" />
                              )}
                              {step.startTime && (
                                <span className="text-[10px] text-gray-500 dark:text-gray-400 flex-shrink-0 whitespace-nowrap">
                                  {formatRel(step.startTime)}
                                  {(() => {
                                    // When viewing all epochs, show cumulative totalExecutionTimeMs
                                    const useTotal = selectedEpoch == null && step.totalExecutionTimeMs != null;
                                    const hasBackendTiming = useTotal || step.executionTimeMs != null;
                                    const ms = useTotal
                                      ? step.totalExecutionTimeMs!
                                      : step.executionTimeMs != null
                                        ? step.executionTimeMs
                                        : step.endTime
                                          ? Math.max(0, parseUtcAware(step.endTime).getTime() - parseUtcAware(step.startTime!).getTime())
                                          : 0;
                                    return (hasBackendTiming || ms > 0) ? ` · ${formatCompactDuration(ms)}` : '';
                                  })()}
                                </span>
                              )}
                            </div>
                            </TooltipTrigger>
                            <TooltipContent
                              side="left"
                              sideOffset={8}
                              align="center"
                              className="px-3 py-2.5"
                            >
                              <StepTooltipContent
                                step={step}
                                label={stepLabel}
                                showCumulative={selectedEpoch == null}
                              />
                              {matchedNode && (
                                <StepRowActions
                                  step={step}
                                  matchedNode={matchedNode}
                                  workflowId={workflowId}
                                  isStepByStep={isStepByStep}
                                  isRunActive={isRunActive}
                                />
                              )}
                            </TooltipContent>
                            </Tooltip>
                            );
                          })}
                        </div>
                      ) : (
                        /* ── Duration gauge view ── */
                        <WaterfallView
                          steps={filteredSteps}
                          findNodeForStep={findNodeForStep}
                          showCumulative={selectedEpoch == null}
                          workflowId={workflowId}
                          isStepByStep={isStepByStep}
                          isRunActive={isRunActive}
                        />
                      )}
                    </>
                  )}
                </div>
                </TooltipProvider>
              )}
            </div>
          )}

          {/* History button */}
          {onOpenRunsHistory && (
            <Button
              onClick={onOpenRunsHistory}
              className="w-11 h-11 rounded-full p-0 shadow-none pointer-events-auto"
              title={t('runs.title')}
            >
              <History className="w-[22px] h-[22px]" />
            </Button>
          )}
        </div>
      )}

      {/* Cancel confirmation modal (WAITING_TRIGGER → terminal CANCELLED) */}
      {mounted && cancelConfirm && createPortal(
        <div
          className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[9999] flex items-center justify-center p-4"
          onClick={() => setCancelConfirm(false)}
        >
          <div
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="cancel-run-dialog-title"
            aria-describedby="cancel-run-dialog-description"
            className="max-w-sm w-full bg-theme-primary rounded-3xl shadow-2xl p-8 animate-in fade-in-0 zoom-in-95 duration-300 border border-theme"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="text-center mb-6">
              <div className="w-14 h-14 bg-red-100 dark:bg-red-900/30 rounded-full flex items-center justify-center mx-auto mb-4">
                <XCircle className="w-7 h-7 text-red-600 dark:text-red-400" />
              </div>
              <h3 id="cancel-run-dialog-title" className="text-lg font-semibold text-theme-primary">
                {t('workflow.cancelRun.title')}
              </h3>
              <p id="cancel-run-dialog-description" className="text-sm text-theme-secondary mt-2">
                {t('workflow.cancelRun.description')}
              </p>
              {pinnedVersion != null && (
                <div className="mt-3 p-3 bg-red-50 dark:bg-red-900/20 rounded-xl border border-red-200 dark:border-red-800">
                  <p className="text-xs text-red-600 dark:text-red-400">
                    {t('workflow.cancelRun.warning')}
                  </p>
                </div>
              )}
            </div>
            <div className="flex gap-3">
              <Button variant="outline" onClick={() => setCancelConfirm(false)} className="flex-1">
                {t('workflow.cancelRun.keep')}
              </Button>
              <Button
                onClick={() => {
                  setCancelConfirm(false);
                  onCancel?.();
                }}
                className="flex-1 bg-red-600 hover:bg-red-700 text-white"
              >
                {t('workflow.cancelRun.confirm')}
              </Button>
            </div>
          </div>
        </div>,
        document.body
      )}
      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </>
  );
}

// ── Waterfall helpers ──

function formatCompactDuration(ms: number): string {
  if (ms < 1000) return '<1s';
  const sec = ms / 1000;
  if (sec < 10) return `${sec.toFixed(1)}s`;
  if (sec < 60) return `${Math.round(sec)}s`;
  const minutes = Math.floor(sec / 60);
  const remSec = Math.round(sec % 60);
  if (minutes < 60) return `${minutes}m${String(remSec).padStart(2, '0')}s`;
  const hours = Math.floor(minutes / 60);
  const remainMin = minutes % 60;
  return `${hours}h${String(remainMin).padStart(2, '0')}m`;
}

function getBarColor(status: string): string {
  switch (status) {
    case 'error':
    case 'failed':
    case 'partial_success':
      return 'bg-red-400/80 dark:bg-red-500/70';
    case 'running':
    case 'pending':
      return 'bg-blue-500 animate-pulse';
    case 'skipped':
      return 'bg-gray-300 dark:bg-gray-600';
    case 'awaiting_signal':
      return 'bg-amber-400/80 dark:bg-amber-500/70';
    default:
      return 'bg-emerald-500/80 dark:bg-emerald-500/70';
  }
}

/**
 * Derive the effective display status from statusCounts (multi-epoch aggregate).
 * Priority: failed > running > awaiting_signal > completed > skipped > raw status.
 */
function deriveEffectiveStatus(
  rawStatus: string,
  statusCounts?: { completed?: number; failed?: number; skipped?: number; running?: number; awaitingSignal?: number }
): string {
  if (!statusCounts) return rawStatus;
  const { completed = 0, failed = 0, running = 0, awaitingSignal = 0, skipped = 0 } = statusCounts;
  // If any execution is still running, show running
  if (running > 0) return 'running';
  // If any is awaiting signal, show that
  if (awaitingSignal > 0) return 'awaiting_signal';
  // If any failed, show failed (even if some completed)
  if (failed > 0) return completed > 0 ? 'partial_success' : 'failed';
  // If any completed, show completed
  if (completed > 0) return 'completed';
  // If only skipped, show skipped
  if (skipped > 0) return 'skipped';
  // Fallback to raw status
  return rawStatus;
}

type StepTooltipStep = {
  alias: string;
  status: string;
  startTime: string | null;
  endTime: string | null;
  executionTimeMs?: number;
  totalExecutionTimeMs?: number;
  statusCounts?: { completed?: number; failed?: number; skipped?: number; running?: number; awaitingSignal?: number };
};

/**
 * Reusable hover-tooltip body for a step row (used in both list and waterfall views).
 * Surfaces precise UTC timestamps, duration (cumulative-aware), and the per-status
 * execution breakdown that the compact row only shows as inline ✓/✗/⊘/⟳ glyphs.
 */
function StepTooltipContent({ step, label, showCumulative }: { step: StepTooltipStep; label: string; showCumulative: boolean }) {
  const t = useTranslations();
  const effectiveStatus = deriveEffectiveStatus(step.status, step.statusCounts);
  const isRunning = effectiveStatus === 'running';
  const isAwaiting = effectiveStatus === 'awaiting_signal';
  const useTotal = showCumulative && step.totalExecutionTimeMs != null;
  const durationMs = useTotal
    ? step.totalExecutionTimeMs!
    : step.executionTimeMs != null
      ? step.executionTimeMs
      : step.startTime
        ? Math.max(0, (step.endTime ? parseUtcAware(step.endTime).getTime() : Date.now()) - parseUtcAware(step.startTime).getTime())
        : 0;
  const hasAnyCount = !!step.statusCounts && (
    (step.statusCounts.completed ?? 0) +
    (step.statusCounts.failed ?? 0) +
    (step.statusCounts.skipped ?? 0) +
    (step.statusCounts.running ?? 0) +
    (step.statusCounts.awaitingSignal ?? 0)
  ) > 0;

  const statusLabel = (() => {
    switch (effectiveStatus) {
      case 'running': return t('workflow.runSteps.stepTooltip.running');
      case 'completed': return t('workflow.runSteps.stepTooltip.completed');
      case 'failed': return t('workflow.runSteps.stepTooltip.failed');
      case 'partial_success': return t('workflow.runSteps.stepTooltip.partialSuccess');
      case 'skipped': return t('workflow.runSteps.stepTooltip.skipped');
      case 'awaiting_signal': return t('workflow.runSteps.stepTooltip.awaitingSignal');
      case 'pending': return t('workflow.runSteps.stepTooltip.pending');
      default: return effectiveStatus;
    }
  })();
  const statusCls = (() => {
    switch (effectiveStatus) {
      case 'running': return 'text-blue-500 dark:text-blue-400';
      case 'completed': return 'text-emerald-600 dark:text-emerald-400';
      case 'failed': return 'text-red-500 dark:text-red-400';
      case 'partial_success': return 'text-amber-600 dark:text-amber-400';
      case 'skipped': return 'text-gray-500 dark:text-gray-400';
      case 'awaiting_signal': return 'text-amber-500 dark:text-amber-400';
      default: return 'text-gray-500 dark:text-gray-400';
    }
  })();

  return (
    <div className="flex flex-col gap-2 text-xs min-w-[260px] max-w-[320px]">
      {/* Header: label + status badge */}
      <div className="flex items-start justify-between gap-3 border-b border-gray-100 dark:border-gray-700 pb-1.5">
        <span className="font-semibold text-gray-900 dark:text-gray-100 break-words flex-1 min-w-0">
          {label}
        </span>
        <span className={`inline-flex items-center gap-1 font-medium shrink-0 ${statusCls}`}>
          {isRunning && (
            <span className="relative flex h-1.5 w-1.5">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75" />
              <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-blue-500" />
            </span>
          )}
          {isAwaiting && <PauseCircle className="w-3 h-3" />}
          {statusLabel}
        </span>
      </div>

      {/* Started */}
      <div className="flex items-center justify-between gap-3">
        <span className="text-gray-500 dark:text-gray-400">
          {t('workflow.runSteps.stepTooltip.started')}
        </span>
        <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
          {step.startTime
            ? formatUtcDateTime(step.startTime, { withSeconds: true })
            : '-'}
        </span>
      </div>

      {/* Ended */}
      <div className="flex items-center justify-between gap-3">
        <span className="text-gray-500 dark:text-gray-400">
          {t('workflow.runSteps.stepTooltip.ended')}
        </span>
        <span
          className={`font-medium tabular-nums ${
            isRunning || isAwaiting
              ? 'text-blue-500 dark:text-blue-400'
              : 'text-gray-900 dark:text-gray-100'
          }`}
        >
          {step.endTime
            ? formatUtcDateTime(step.endTime, { withSeconds: true })
            : isRunning || isAwaiting
              ? t('workflow.runSteps.stepTooltip.stillRunning')
              : '-'}
        </span>
      </div>

      {/* Duration */}
      <div className="flex items-center justify-between gap-3 border-t border-gray-100 dark:border-gray-700 pt-1.5">
        <span className="text-gray-500 dark:text-gray-400 inline-flex items-baseline gap-1">
          {t('workflow.runSteps.stepTooltip.duration')}
          {useTotal && (
            <span className="text-[10px] text-gray-400 dark:text-gray-500">
              ({t('workflow.runSteps.stepTooltip.cumulative')})
            </span>
          )}
        </span>
        <span
          className={`font-medium tabular-nums ${
            isRunning ? 'text-blue-500 dark:text-blue-400' : 'text-gray-900 dark:text-gray-100'
          }`}
        >
          {step.startTime && durationMs >= 0 ? formatCompactDuration(durationMs) : '-'}
        </span>
      </div>

      {/* Per-status execution breakdown */}
      {hasAnyCount && (
        <div className="flex flex-col gap-1 border-t border-gray-100 dark:border-gray-700 pt-1.5">
          <span className="text-[10px] uppercase tracking-wide text-gray-400 dark:text-gray-500 font-medium">
            {t('workflow.runSteps.stepTooltip.executions')}
          </span>
          <div className="grid grid-cols-1 gap-y-0.5">
            {(step.statusCounts!.completed ?? 0) > 0 && (
              <div className="flex items-center justify-between gap-2">
                <span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400">
                  <CheckCircle2 className="w-3 h-3" />
                  {t('workflow.runSteps.stepTooltip.completed')}
                </span>
                <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
                  {step.statusCounts!.completed}
                </span>
              </div>
            )}
            {(step.statusCounts!.failed ?? 0) > 0 && (
              <div className="flex items-center justify-between gap-2">
                <span className="inline-flex items-center gap-1 text-red-500 dark:text-red-400">
                  <XCircle className="w-3 h-3" />
                  {t('workflow.runSteps.stepTooltip.failed')}
                </span>
                <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
                  {step.statusCounts!.failed}
                </span>
              </div>
            )}
            {(step.statusCounts!.skipped ?? 0) > 0 && (
              <div className="flex items-center justify-between gap-2">
                <span className="inline-flex items-center gap-1 text-gray-500 dark:text-gray-400">
                  <CircleSlash className="w-3 h-3" />
                  {t('workflow.runSteps.stepTooltip.skipped')}
                </span>
                <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
                  {step.statusCounts!.skipped}
                </span>
              </div>
            )}
            {(step.statusCounts!.running ?? 0) > 0 && (
              <div className="flex items-center justify-between gap-2">
                <span className="inline-flex items-center gap-1 text-blue-500 dark:text-blue-400">
                  <Loader2 className="w-3 h-3 animate-spin" />
                  {t('workflow.runSteps.stepTooltip.running')}
                </span>
                <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
                  {step.statusCounts!.running}
                </span>
              </div>
            )}
            {(step.statusCounts!.awaitingSignal ?? 0) > 0 && (
              <div className="flex items-center justify-between gap-2">
                <span className="inline-flex items-center gap-1 text-amber-500 dark:text-amber-400">
                  <PauseCircle className="w-3 h-3" />
                  {t('workflow.runSteps.stepTooltip.awaitingSignal')}
                </span>
                <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
                  {step.statusCounts!.awaitingSignal}
                </span>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

interface WaterfallViewProps {
  steps: Array<{
    alias: string;
    toolId: string;
    status: string;
    startTime: string | null;
    endTime: string | null;
    executionTimeMs?: number;
    totalExecutionTimeMs?: number;
    statusCounts?: { completed?: number; failed?: number; skipped?: number; running?: number; awaitingSignal?: number };
  }>;
  findNodeForStep: (alias: string) => Node<BuilderNodeData> | undefined;
  /** Whether viewing all epochs (cumulative) vs a specific epoch */
  showCumulative?: boolean;
  /** Contextual-action props forwarded to StepRowActions (same as list view). */
  workflowId?: string;
  isStepByStep?: boolean;
  isRunActive?: boolean;
}

// ── Epoch Selector ──

interface EpochSelectorProps {
  epochTimestamps: EpochTimestamp[];
  selectedEpoch: number | null;
  onSelectEpoch: (epoch: number | null) => void;
  viewMode: 'list' | 'waterfall';
}

function formatTime(isoString: string): string {
  return formatUtcTime(isoString, { withSeconds: true });
}

// Row height kept fixed so FixedSizeList can virtualize without measuring.
// 30px = px-3 py-1.5 text-xs button - verified in DOM inspector before refactor.
const EPOCH_ROW_HEIGHT = 30;
const EPOCH_LIST_MAX_HEIGHT = 120;

interface EpochRowProps {
  entries: EpochTimestamp[];
  durations: number[];
  maxDuration: number;
  selectedEpoch: number | null;
  onSelectEpoch: (epoch: number | null) => void;
  viewMode: 'list' | 'waterfall';
}

// Row component defined at module scope so its identity is stable across renders
// (List re-renders rows when rowProps changes). All per-row data flows via
// `rowProps`, never closure capture, so re-renders only fire when rowProps changes.
function EpochRow({ index, style, entries, durations, maxDuration, selectedEpoch, onSelectEpoch, viewMode }: RowComponentProps<EpochRowProps>) {
  const t = useTranslations();
  const entry = entries[index];
  const isSelected = selectedEpoch === entry.epoch;
  const isRunning = entry.startedAt != null && entry.endedAt == null;
  const duration = durations[index];
  const barPct = maxDuration > 0 ? Math.max(5, (duration / maxDuration) * 100) : 5;

  const epochButton = (
    <button
      type="button"
      style={style}
      onClick={(e) => { e.stopPropagation(); onSelectEpoch(entry.epoch); }}
      className={`w-full flex items-center gap-1.5 px-3 py-1.5 text-xs transition-colors ${
        isSelected
          ? 'border-l-2 border-gray-900 dark:border-gray-100 bg-gray-50 dark:bg-white/[0.04] font-semibold text-gray-900 dark:text-gray-100'
          : 'border-l-2 border-transparent hover:bg-gray-50/80 dark:hover:bg-white/[0.03]'
      }`}
    >
      <div className={`w-6 shrink-0 text-center tabular-nums ${
        isSelected ? 'font-bold text-gray-900 dark:text-gray-100' : 'font-medium text-gray-500 dark:text-gray-400'
      }`}>
        {entry.epoch}
      </div>
      {/* Running indicator - slot is always reserved (same width + gap whether or
          not the epoch is running) so the gauge / "HH:mm → HH:mm" / duration to
          the right never shift horizontally as a run transitions to completed. */}
      <span className="relative flex h-1.5 w-1.5 shrink-0">
        {isRunning && (
          <>
            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75" />
            <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-blue-500" />
          </>
        )}
      </span>

      {viewMode === 'waterfall' ? (
        <>
          <span className="w-[80px] min-w-[80px] shrink-0" />
          <div className="flex-1 h-[3px] rounded-full bg-gray-100 dark:bg-white/[0.06] overflow-hidden min-w-0">
            <div
              className={`h-full rounded-full transition-all ${
                isRunning ? 'bg-blue-500 animate-pulse' : isSelected ? 'bg-emerald-500' : 'bg-emerald-500/70'
              }`}
              style={{ width: `${barPct}%` }}
            />
          </div>
          <span className={`min-w-[32px] text-right text-[10px] tabular-nums shrink-0 ${
            isRunning ? 'text-blue-500 dark:text-blue-400' : 'text-gray-500 dark:text-gray-400'
          }`}>
            {entry.startedAt ? formatCompactDuration(duration) : ''}
          </span>
        </>
      ) : (
        <>
          {/* Three fixed-width slots (start | → | end) inside a flex-1 centered
              wrapper. The end-time slot keeps its width when it renders "..."
              so the live "HH:mm:ss → ..." → "HH:mm:ss → HH:mm:ss" transition
              never shifts the block, and the whole group stays visually
              centered within the row. */}
          <span className={`flex-1 inline-flex items-center justify-center gap-1 text-[10px] tabular-nums whitespace-nowrap ${
            isRunning ? 'text-blue-500 dark:text-blue-400' : 'text-gray-500 dark:text-gray-400'
          }`}>
            {entry.startedAt ? (
              <>
                <span className="w-[76px] text-right">{formatTime(entry.startedAt)}</span>
                <span aria-hidden="true">→</span>
                <span className="w-[76px] text-left">{entry.endedAt ? formatTime(entry.endedAt) : '...'}</span>
              </>
            ) : (
              <span>-</span>
            )}
          </span>
          {/* Fixed width (48px) sized for the worst case "59m59s" / "99h59m"
              so the ticking duration on a running epoch doesn't expand the
              column and squeeze the flex-1 timestamps span to its left. */}
          <span className={`w-12 text-right text-[10px] tabular-nums font-medium shrink-0 ${
            isRunning ? 'text-blue-500 dark:text-blue-400' : 'text-gray-500 dark:text-gray-400'
          }`}>
            {entry.startedAt ? formatCompactDuration(duration) : ''}
          </span>
        </>
      )}
    </button>
  );

  return (
    <Tooltip delayDuration={150}>
      <TooltipTrigger asChild>{epochButton}</TooltipTrigger>
      <TooltipContent
        side="left"
        sideOffset={8}
        align="center"
        className="px-3 py-2.5 min-w-[240px]"
      >
        <div className="flex flex-col gap-2 text-xs">
          {/* Header: epoch number + status */}
          <div className="flex items-center justify-between gap-3 border-b border-gray-100 dark:border-gray-700 pb-1.5">
            <span className="font-semibold text-gray-900 dark:text-gray-100 tabular-nums">
              {t('workflow.runSteps.epochTooltip.epoch', { epoch: entry.epoch })}
            </span>
            <span className="inline-flex items-center gap-1">
              {isRunning ? (
                <>
                  <span className="relative flex h-1.5 w-1.5">
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75" />
                    <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-blue-500" />
                  </span>
                  <span className="font-medium text-blue-500 dark:text-blue-400">
                    {t('workflow.runSteps.epochTooltip.running')}
                  </span>
                </>
              ) : entry.endedAt ? (
                <span className="font-medium text-emerald-600 dark:text-emerald-400">
                  {t('workflow.runSteps.epochTooltip.completed')}
                </span>
              ) : (
                <span className="font-medium text-gray-500 dark:text-gray-400">
                  {t('workflow.runSteps.epochTooltip.pending')}
                </span>
              )}
            </span>
          </div>

          {/* Started */}
          <div className="flex items-center justify-between gap-3">
            <span className="text-gray-500 dark:text-gray-400">
              {t('workflow.runSteps.epochTooltip.started')}
            </span>
            <span className="font-medium text-gray-900 dark:text-gray-100 tabular-nums">
              {entry.startedAt
                ? formatUtcDateTime(entry.startedAt, { withSeconds: true })
                : '-'}
            </span>
          </div>

          {/* Ended */}
          <div className="flex items-center justify-between gap-3">
            <span className="text-gray-500 dark:text-gray-400">
              {t('workflow.runSteps.epochTooltip.ended')}
            </span>
            <span
              className={`font-medium tabular-nums ${
                isRunning
                  ? 'text-blue-500 dark:text-blue-400'
                  : 'text-gray-900 dark:text-gray-100'
              }`}
            >
              {entry.endedAt
                ? formatUtcDateTime(entry.endedAt, { withSeconds: true })
                : isRunning
                  ? t('workflow.runSteps.epochTooltip.stillRunning')
                  : '-'}
            </span>
          </div>

          {/* Duration */}
          <div className="flex items-center justify-between gap-3 border-t border-gray-100 dark:border-gray-700 pt-1.5">
            <span className="text-gray-500 dark:text-gray-400">
              {t('workflow.runSteps.epochTooltip.duration')}
            </span>
            <span
              className={`font-medium tabular-nums ${
                isRunning
                  ? 'text-blue-500 dark:text-blue-400'
                  : 'text-gray-900 dark:text-gray-100'
              }`}
            >
              {entry.startedAt ? formatCompactDuration(duration) : '-'}
            </span>
          </div>
        </div>
      </TooltipContent>
    </Tooltip>
  );
}

const EpochSelector = memo(function EpochSelector({ epochTimestamps, selectedEpoch, onSelectEpoch, viewMode }: EpochSelectorProps) {
  const t = useTranslations();

  // Sort once per epochTimestamps reference change. Show all epochs regardless of
  // status filter - the filter already applies to the step list. Hiding epochs
  // based on active/ended was too aggressive: an active epoch can contain
  // completed nodes, and a finished epoch can be relevant when filtering by any status.
  const sorted = useMemo(
    () => [...epochTimestamps].sort((a, b) => b.epoch - a.epoch),
    [epochTimestamps]
  );

  // Conditional 1s ticker: only runs when at least one epoch is still in-flight.
  // Closed-only timelines pay zero wake-up cost, and the heavy WS-event re-render
  // cadence stops driving duration recomputation (the previous code re-derived
  // every duration on every parent render because `Date.now()` was read inline).
  const hasRunningEpoch = useMemo(
    () => sorted.some(e => e.startedAt != null && e.endedAt == null),
    [sorted]
  );
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (!hasRunningEpoch) return;
    const id = setInterval(() => setTick(prev => prev + 1), 1000);
    return () => clearInterval(id);
  }, [hasRunningEpoch]);

  const durations = useMemo(() => {
    return sorted.map((entry) => {
      if (!entry.startedAt) return 0;
      const start = parseUtcAware(entry.startedAt).getTime();
      if (isNaN(start)) return 0;
      const end = entry.endedAt ? parseUtcAware(entry.endedAt).getTime() : Date.now();
      return isNaN(end) ? 0 : Math.max(0, end - start);
    });
    // `tick` is a deliberate dep: forces recompute every second when a running
    // epoch exists, so the bar / label tick forward without leaning on SSE cadence.
  }, [sorted, tick]);

  const maxDuration = useMemo(
    () => durations.reduce((m, d) => (d > m ? d : m), 1),
    [durations]
  );

  // Stable rowProps reference - List re-renders rows when this object's identity
  // changes; memoizing means SSE pushes that don't actually alter epoch data are no-ops.
  const rowProps = useMemo<EpochRowProps>(() => ({
    entries: sorted,
    durations,
    maxDuration,
    selectedEpoch,
    onSelectEpoch,
    viewMode,
  }), [sorted, durations, maxDuration, selectedEpoch, onSelectEpoch, viewMode]);

  // Auto-scroll to the selected epoch when selection changes. Sorted newest-first,
  // so the index lines up with position in `sorted`.
  const listRef = useListRef(null);
  useEffect(() => {
    if (selectedEpoch == null) return;
    const idx = sorted.findIndex(e => e.epoch === selectedEpoch);
    if (idx >= 0) listRef.current?.scrollToRow({ index: idx, align: 'smart' });
  }, [selectedEpoch, sorted]);

  // Height shrinks to fit when there are fewer epochs than the cap - matches
  // the original `max-h-[120px]` visual behaviour for small lists.
  const listHeight = Math.min(sorted.length * EPOCH_ROW_HEIGHT, EPOCH_LIST_MAX_HEIGHT);

  return (
    <div className="pt-1.5 pb-1.5">
      {/* "All epochs" button */}
      <button
        type="button"
        onClick={(e) => { e.stopPropagation(); onSelectEpoch(null); }}
        className={`w-full flex items-center gap-1.5 px-3 py-1.5 text-xs transition-colors ${
          selectedEpoch === null
            ? 'border-l-2 border-gray-900 dark:border-gray-100 bg-gray-50 dark:bg-white/[0.04] font-semibold text-gray-900 dark:text-gray-100'
            : 'border-l-2 border-transparent text-gray-500 dark:text-gray-400 hover:bg-gray-50/80 dark:hover:bg-white/[0.03]'
        }`}
      >
        <div className="w-6 shrink-0 flex items-center justify-center">
          <Calendar className="w-3.5 h-3.5" />
        </div>
        <span className="w-[80px] min-w-[80px] shrink-0 truncate text-left">{t('workflow.runSteps.allEpochs')}</span>
      </button>

      {/* Virtualized epoch rows */}
      {sorted.length > 0 && (
        <div className="mt-1">
          <VirtualList
            listRef={listRef}
            rowCount={sorted.length}
            rowHeight={EPOCH_ROW_HEIGHT}
            rowComponent={EpochRow}
            rowProps={rowProps}
            overscanCount={5}
            style={{ height: listHeight }}
            className="scrollbar-thin"
          />
        </div>
      )}

      {/* Separator */}
      <div className="border-t border-slate-200 dark:border-slate-700 mt-2 mx-3" />
    </div>
  );
});

function WaterfallView({ steps, findNodeForStep, showCumulative, workflowId, isStepByStep, isRunActive }: WaterfallViewProps) {
  const entries = steps
    .map(s => {
      // When viewing all epochs, prefer cumulative totalExecutionTimeMs
      const preferTotal = showCumulative && s.totalExecutionTimeMs != null;
      const durationMs = preferTotal
        ? s.totalExecutionTimeMs!
        : s.executionTimeMs != null
        ? s.executionTimeMs
        : s.startTime
          ? Math.max(0, (s.endTime ? parseUtcAware(s.endTime).getTime() : Date.now()) - parseUtcAware(s.startTime).getTime())
          : 0;
      const matchedNode = findNodeForStep(s.alias);
      const matchedData = matchedNode?.data;
      const nodeClass = matchedData ? findNodeClassById(matchedData.id || '') : null;
      const label = matchedData?.label || s.alias.replace(/^(mcp|core|agent|trigger|table|interface):/, '');
      // Derive effective status from statusCounts when available (matches list view behavior)
      const effectiveStatus = deriveEffectiveStatus(s.status, s.statusCounts);
      return { alias: s.alias, label, status: effectiveStatus, durationMs, matchedData, matchedNode, nodeClass, statusCounts: s.statusCounts };
    });

  if (entries.length === 0) return null;

  const maxDuration = Math.max(...entries.map(e => e.durationMs), 1);

  return (
    <div className="py-0.5">
      {entries.map(entry => {
        const barPct = Math.max(5, (entry.durationMs / maxDuration) * 100);
        const hasBackendTiming = entry.durationMs != null;
        const sourceStep = steps.find(s => s.alias === entry.alias)!;
        return (
          <Tooltip key={entry.alias} delayDuration={150}>
          <TooltipTrigger asChild>
          <div
            className="flex items-center gap-1.5 px-3 py-1 cursor-pointer hover:bg-gray-100/60 dark:hover:bg-gray-700/40 transition-colors"
            onClick={(e) => {
              e.stopPropagation();
              window.dispatchEvent(new CustomEvent('workflowFocusNode', {
                detail: { stepAlias: entry.alias },
              }));
            }}
          >
            {/* Icon - w-6 to match epoch number column */}
            <div className="w-6 shrink-0 flex items-center justify-center">
              {entry.matchedData ? (
                <NodeIcon
                  iconSlug={getIconSlug(entry.matchedData)}
                  nodeId={entry.matchedData.id || ''}
                  nodeKind={entry.matchedData.kind}
                  nodeFamily={entry.nodeClass?.family}
                  avatarUrl={(entry.matchedData as any)?.agentAvatarUrl}
                  size="xs"
                />
              ) : (
                <div className="h-4 w-4 rounded-full bg-gray-100 dark:bg-gray-700" />
              )}
            </div>
            {/* Label - w-[80px] to match epoch label column */}
            <span className="w-[80px] min-w-[80px] text-xs font-medium text-gray-900 dark:text-gray-100 truncate shrink-0">{entry.label}</span>
            {/* Status counts - compact indicators like list view */}
            {entry.statusCounts && (
              <div className="flex items-center gap-0.5 shrink-0">
                {(entry.statusCounts.completed ?? 0) > 0 && (
                  <span className="text-[10px] text-emerald-600 dark:text-emerald-400">✓{entry.statusCounts.completed}</span>
                )}
                {(entry.statusCounts.failed ?? 0) > 0 && (
                  <span className="text-[10px] text-red-600 dark:text-red-400">✗{entry.statusCounts.failed}</span>
                )}
                {(entry.statusCounts.skipped ?? 0) > 0 && (
                  <span className="text-[10px] text-gray-500 dark:text-gray-400">⊘{entry.statusCounts.skipped}</span>
                )}
              </div>
            )}
            {/* Gauge */}
            <div className="flex-1 h-[3px] rounded-full bg-gray-100 dark:bg-white/[0.06] overflow-hidden min-w-0">
              <div
                className={`h-full rounded-full ${getBarColor(entry.status)}`}
                style={{ width: `${barPct}%` }}
              />
            </div>
            {/* Duration */}
            <span className="min-w-[32px] text-right text-[10px] tabular-nums text-gray-500 dark:text-gray-400 shrink-0">
              {(hasBackendTiming || entry.durationMs > 0) ? formatCompactDuration(entry.durationMs) : ''}
            </span>
          </div>
          </TooltipTrigger>
          <TooltipContent
            side="left"
            sideOffset={8}
            align="center"
            className="px-3 py-2.5"
          >
            <StepTooltipContent
              step={sourceStep}
              label={entry.label}
              showCumulative={!!showCumulative}
            />
            {entry.matchedNode && (
              <StepRowActions
                step={{ alias: entry.alias }}
                matchedNode={entry.matchedNode}
                workflowId={workflowId}
                isStepByStep={!!isStepByStep}
                isRunActive={!!isRunActive}
              />
            )}
          </TooltipContent>
          </Tooltip>
        );
      })}
    </div>
  );
}

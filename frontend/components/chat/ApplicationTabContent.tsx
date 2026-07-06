'use client';

import * as React from 'react';
import { createPortal } from 'react-dom';
import { AlertCircle, X, Lock, StepForward, Grip, Calendar, Play, FormInput, MessageCircle, Webhook, ChevronLeft, ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useInterfaceRender, useInterfaceById } from '@/app/workflows/builder/hooks/useInterfaces';
import { useRun } from '@/contexts/WorkflowRunContext';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { InterfaceIframe } from '@/app/workflows/builder/components/interface/InterfaceIframe';
import { InterfaceToolbar } from '@/app/workflows/builder/components/interface/InterfaceToolbar';
import type { RenderMode } from '@/app/workflows/builder/utils/interfaceHtmlUtils';
import { mergeTriggerDataIntoResolved } from '@/app/workflows/builder/utils/interfaceHtmlUtils';
import { SAFE_CENTERING_CSS } from '@/app/workflows/builder/utils/safeCenteringCss';
import LoadingSpinner from '@/components/LoadingSpinner';
import { parseUtcAware } from '@/lib/utils/dateFormatters';
import { useTranslations } from 'next-intl';
import { useSharedInterfacePage } from '@/lib/stores/interface-pagination-store';

import { computeIsAwaitingSignal, isCurrentInterfaceItemPending } from './interfaceAwaitingSignal';
import { orchestratorApi } from '@/lib/api';
import { workflowService } from '@/lib/api/orchestrator/workflow.service';
import { executionService } from '@/lib/api/orchestrator/execution.service';
import { triggerKey } from '@/app/workflows/builder/utils/labelNormalizer';
import { TriggerPanel, type TriggerPanelConfig } from '@/app/workflows/builder/components/TriggerPanel';
import { formatUtcTime } from '@/lib/utils/dateFormatters';
import { RunningBorder } from './RunningBorder';
import { VIEWING_EPOCH_EVENT, shouldAdoptEpochEvent, type EpochEventDetail } from '@/lib/workflow/epochEventScope';

export interface ApplicationConfig {
  interfaceId: string;
  label: string;
  actionMapping: Record<string, string>;
  /** The workflow node ID (e.g., "interface:my_form") for signal resolution */
  nodeId?: string;
}

interface ApplicationTabContentProps {
  config: ApplicationConfig;
  runId: string | null;
  workflowId?: string;
  onAction: (triggerRef: string, data: Record<string, unknown>) => void;
  /** Carousel dot indicators injected into the InterfaceToolbar */
  carouselControls?: React.ReactNode;
  /** Controlled fullscreen state (lifted from parent carousel) */
  isExpanded?: boolean;
  onExpandedChange?: (expanded: boolean) => void;
  /** Controlled toolbar open state (lifted from parent carousel) */
  toolbarOpen?: boolean;
  onToolbarOpenChange?: (open: boolean) => void;
  /** Controlled viewingEpoch (lifted from parent carousel so it survives tab switches) */
  viewingEpoch?: number | null;
  onViewingEpochChange?: (epoch: number | null) => void;
  /**
   * Marketplace-preview mode. Anonymous visitors and authenticated browsing
   * users hitting `/app/marketplace/{publicationId}/preview` MUST NOT be
   * able to fire any workflow action against the publisher's tenant. When
   * true, three behavior gates fire:
   *
   *   1. The Launch (trigger) button is hidden from the floating toolbar.
   *   2. The Continue button (which routes through useWorkflowEventBridge
   *      to an authed interfaceService.fireInterfaceAction) is hidden too.
   *   3. The floating pill toolbar is portalled to document.body so it
   *      escapes the preview shell's `overflow-hidden` clipping (root
   *      cause of the original user-reported bug - clicking the grip
   *      3-dots opened a wide toolbar that was visually hidden by the
   *      shell's overflow rule). Position is computed from the preview
   *      container's bounding rect (tracked via ResizeObserver +
   *      scroll/resize listeners) so the toolbar lands at the bottom-
   *      center of the PANEL - not the viewport, which would dock it
   *      under the app shell on the marketplace page.
   *
   * The publisher viewing their own preview must see exactly what an
   * anonymous visitor sees - see PublicationPreviewShell. Same code path,
   * same gates, regardless of auth state.
   */
  previewMode?: boolean;
}

type ResolvedVariablePagination = {
  name: string;
  page: number;
  totalPages: number;
};

function toFiniteNonNegativeInteger(value: unknown): number | null {
  const numberValue = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(numberValue) || numberValue < 0) return null;
  return Math.floor(numberValue);
}

function isExplicitFalse(value: unknown): boolean {
  return value === false || value === 'false';
}

export function ApplicationTabContent({ config, runId, workflowId, onAction, carouselControls, isExpanded: controlledExpanded, onExpandedChange, toolbarOpen: controlledToolbarOpen, onToolbarOpenChange, viewingEpoch: controlledViewingEpoch, onViewingEpochChange, previewMode = false }: ApplicationTabContentProps) {
  const t = useTranslations('marketplace');
  const tActions = useTranslations('actions');
  const tCanvas = useTranslations('workflowBuilder.canvas');
  const tRun = useTranslations('runMode');
  const openControlsLabel = tCanvas('openApplicationControls');
  const [isDragging, setIsDragging] = React.useState(false);
  const [actionError, setActionError] = React.useState<string | null>(null);
  const [showPreviewToast, setShowPreviewToast] = React.useState(false);
  const previewToastTimeoutRef = React.useRef<ReturnType<typeof setTimeout> | null>(null);

  // Launchable triggers: chat/form/webhook (rendered as tabs in TriggerPanel)
  // + first manual (fired inline).
  // chat/form/webhook configs come from one of two sources (live wins):
  //   1. Canvas live cache via workflowPanelTriggerDataChange - dispatched
  //      by WorkflowBuilder when mounted (richer: live form-node data).
  //   2. Plan-derived fallback via workflowService.getWorkflow - used when
  //      no canvas is mounted (visualize-card popup in agent chat,
  //      /s/<token> share pages without a builder shell). Closes the
  //      previous gap where panelConfigs was [] in those contexts.
  // Manual triggers are detected from the same plan fetch (the canvas-side
  // dispatch filters them out at WorkflowBuilder.tsx:740-742).
  type LaunchableState = {
    panelConfigs: TriggerPanelConfig[];   // chat/form/webhook (multi-tab)
    firstManual: { id: string; label: string } | null;
    // First schedule trigger. With a runId it fires through the run-scoped
    // trigger endpoint; without one it falls back to the workflow-scoped
    // execute-now endpoint used by the schedule inspector.
    firstSchedule: { id: string; label: string } | null;
  };
  const [launchable, setLaunchable] = React.useState<LaunchableState>({
    panelConfigs: [],
    firstManual: null,
    firstSchedule: null,
  });

  // Two sources for chat/form/webhook configs:
  //  1. Live cache via `workflowPanelTriggerDataChange` - dispatched by the
  //     canvas (WorkflowBuilder) when mounted. Richer (live form-node data,
  //     backend-availability filtering). Wins when present.
  //  2. Plan-derived fallback - built from `wf.plan.triggers` directly. Used
  //     when the canvas isn't mounted (visualize-popup card in agent chat,
  //     /s/<token> share pages without canvas) so the Launch button still
  //     surfaces every trigger.
  // panelConfigs in launchable = liveConfigs.length > 0 ? liveConfigs : planConfigs.
  const [liveConfigs, setLiveConfigs] = React.useState<TriggerPanelConfig[]>([]);
  const [planConfigs, setPlanConfigs] = React.useState<TriggerPanelConfig[]>([]);

  React.useEffect(() => {
    if (!workflowId) {
      setLiveConfigs([]);
      setPlanConfigs([]);
      setLaunchable(prev => ({ ...prev, firstManual: null }));
      return;
    }
    let cancelled = false;
    // Seed liveConfigs from cache - but ONLY when the cache is for THIS
    // workflowId. The cache is a single global slot; a user navigating from
    // app A (which fills the cache) to a visualize-card popup of app B would
    // otherwise inherit A's trigger tabs against B's runId.
    const cached = (window as any).__applicationTriggerData;
    if (cached?.workflowId === workflowId && Array.isArray(cached?.configs)) {
      setLiveConfigs(cached.configs);
    } else {
      setLiveConfigs([]);
    }
    // Reset planConfigs while the new workflow's plan fetch is in flight -
    // otherwise the panelConfigs render would briefly show app A's tabs
    // against app B's runId between workflowId-change and fetch-resolve.
    setPlanConfigs([]);
    const handler = (event: CustomEvent) => {
      if (event.detail?.workflowId && event.detail.workflowId !== workflowId) return;
      const configs = event.detail?.configs;
      setLiveConfigs(Array.isArray(configs) ? configs : []);
    };
    window.addEventListener('workflowPanelTriggerDataChange', handler as EventListener);
    // Plan fetch - drives BOTH manual detection AND the plan-derived
    // chat/form/webhook fallback (so visualize-popup / share pages without
    // a canvas mounted still get launchable triggers).
    (async () => {
      try {
        const wf = await workflowService.getWorkflow(workflowId);
        const triggers = (wf.plan as { triggers?: Array<{ id: string; label?: string; type?: string; params?: Record<string, unknown> }> } | undefined)?.triggers;
        if (cancelled) return;
        if (!Array.isArray(triggers)) {
          setPlanConfigs([]);
          setLaunchable(prev => ({ ...prev, firstManual: null, firstSchedule: null }));
          return;
        }
        const manual = triggers.find(t => (t.type || '').toLowerCase() === 'manual');
        const schedule = triggers.find(t => (t.type || '').toLowerCase() === 'schedule');
        setLaunchable(prev => ({
          ...prev,
          firstManual: manual
            ? { id: triggerKey(manual.label) || manual.id, label: manual.label || 'Manual' }
            : null,
          firstSchedule: schedule
            ? { id: triggerKey(schedule.label) || schedule.id, label: schedule.label || 'Schedule' }
            : null,
        }));
        setPlanConfigs(buildPanelConfigsFromPlan(triggers));
      } catch {
        if (!cancelled) {
          setPlanConfigs([]);
          setLaunchable(prev => ({ ...prev, firstManual: null, firstSchedule: null }));
        }
      }
    })();
    return () => {
      cancelled = true;
      window.removeEventListener('workflowPanelTriggerDataChange', handler as EventListener);
    };
  }, [workflowId]);

  // Sync liveConfigs / planConfigs into launchable.panelConfigs (live wins).
  React.useEffect(() => {
    setLaunchable(prev => ({
      ...prev,
      panelConfigs: liveConfigs.length > 0 ? liveConfigs : planConfigs,
    }));
  }, [liveConfigs, planConfigs]);

  // Has any launchable trigger? (panel configs OR manual OR schedule)
  const hasPanelTriggers = launchable.panelConfigs.length > 0;
  const hasAnyLaunchable = hasPanelTriggers || !!launchable.firstManual || !!launchable.firstSchedule;

  const [isLaunching, setIsLaunching] = React.useState(false);
  // handleLaunchTrigger is declared AFTER useRun() further down so it can
  // close over runContext without TDZ. See the named callback below.
  // Pagination state keyed by runId - all interfaces share the same epoch
  const [currentPage, setCurrentPage] = useSharedInterfacePage(runId ?? null);
  // Per-variable pagination state for SQL-level JSONB array slicing
  const [variablePages, setVariablePages] = React.useState<Record<string, number>>({});

  // Expanded (fullscreen) state - use controlled props if provided, otherwise local state
  const [localExpanded, setLocalExpanded] = React.useState(false);
  const isExpanded = controlledExpanded ?? localExpanded;

  // Ref to always have current expanded value (avoids stale closures in callbacks)
  const expandedRef = React.useRef(isExpanded);
  expandedRef.current = isExpanded;

  const setIsExpanded = React.useCallback((value: boolean | ((prev: boolean) => boolean)) => {
    const next = typeof value === 'function' ? value(expandedRef.current) : value;
    if (onExpandedChange) {
      onExpandedChange(next);
    } else {
      setLocalExpanded(next);
    }
  }, [onExpandedChange]);

  // Disable iframe pointer events while dragging on React Flow canvas
  React.useEffect(() => {
    const handleDragStart = () => setIsDragging(true);
    const handleDragEnd = () => setIsDragging(false);

    window.addEventListener('mousedown', handleDragStart);
    window.addEventListener('mouseup', handleDragEnd);
    return () => {
      window.removeEventListener('mousedown', handleDragStart);
      window.removeEventListener('mouseup', handleDragEnd);
    };
  }, []);

  const { isRunMode, isPreviewOnly } = useWorkflowMode();

  // viewingEpoch: use controlled props if provided (lifted to carousel), otherwise local state.
  const [localViewingEpoch, setLocalViewingEpoch] = React.useState<number | null>(null);
  const viewingEpoch = controlledViewingEpoch !== undefined ? controlledViewingEpoch : localViewingEpoch;

  // Cross-tree viewingEpoch sync: canvas RunInfo ↔ SidePanel Application tab.
  // Scoped by runId so two apps mounted at once (keepMounted side-panel tabs)
  // keep independent epoch selection instead of slaving to each other.
  React.useEffect(() => {
    const handler = (event: CustomEvent<EpochEventDetail>) => {
      const detail = event.detail;
      if (!shouldAdoptEpochEvent(detail?.runId, runId)) return;
      if (onViewingEpochChange) {
        onViewingEpochChange(detail.epoch);
      } else {
        setLocalViewingEpoch(detail.epoch);
      }
    };
    window.addEventListener(VIEWING_EPOCH_EVENT, handler as EventListener);
    return () => window.removeEventListener(VIEWING_EPOCH_EVENT, handler as EventListener);
  }, [onViewingEpochChange, runId]);

  // Epoch selector: set locally/parent AND broadcast to canvas so RunInfo stays in sync.
  // Scope the broadcast to THIS run so sibling app tabs (other runs) ignore it.
  const handleViewEpoch = React.useCallback((epoch: number | null) => {
    if (onViewingEpochChange) {
      onViewingEpochChange(epoch);
    } else {
      setLocalViewingEpoch(epoch);
    }
    const detail: EpochEventDetail = { epoch, runId };
    window.dispatchEvent(new CustomEvent(VIEWING_EPOCH_EVENT, { detail }));
  }, [onViewingEpochChange, runId]);
  const [runState, runContext] = useRun(isRunMode ? runId || undefined : undefined);

  // Multi-trigger panel state: when there are chat/form/webhook triggers,
  // the Launch button opens TriggerPanel (multi-tab UI) instead of trying
  // to navigate the side panel. Works in any context (auth'd, visualize
  // popup, /s/<token> share page) because TriggerPanel renders as a
  // floating self-contained panel - no WorkflowPanelContent dependency.
  const [isTriggerPanelOpen, setIsTriggerPanelOpen] = React.useState(false);

  // chat/form/webhook → open TriggerPanel (multi-tab) so the user can pick
  //   any trigger, fill its input, and fire. onExecuteTrigger routes through
  //   runContext.executeStep so the WS subscription stays warm.
  // manual (and no chat/form/webhook) → fire inline (no panel needed).
  // mixed (manual + chat/form/webhook) → opens the panel; manual stays
  //   addressable via the inline branch only when no panel triggers exist.
  const handleLaunchTrigger = React.useCallback(async () => {
    if (hasPanelTriggers) {
      setIsTriggerPanelOpen(true);
      return;
    }
    if (launchable.firstManual && runId && !isLaunching) {
      setIsLaunching(true);
      try {
        if (runContext?.executeStep) {
          await runContext.executeStep(runId, launchable.firstManual.id, undefined, 'manual');
        } else {
          await executionService.triggerManual(runId);
        }
      } catch (e) {
        console.error('[ApplicationTabContent] manual fire failed', e);
      } finally {
        setIsLaunching(false);
      }
      return;
    }
    if (launchable.firstSchedule && !isLaunching) {
      setIsLaunching(true);
      try {
        if (runId) {
          if (runContext?.executeStep) {
            await runContext.executeStep(runId, launchable.firstSchedule.id, undefined, 'schedule');
          } else {
            await executionService.triggerSpecific(runId, launchable.firstSchedule.id, 'schedule');
          }
        } else if (workflowId) {
          await executionService.scheduleExecuteNow(workflowId, launchable.firstSchedule.id);
        }
      } catch (e) {
        console.error('[ApplicationTabContent] schedule force-fire failed', e);
      } finally {
        setIsLaunching(false);
      }
    }
  }, [hasPanelTriggers, launchable.firstManual, launchable.firstSchedule, runId, workflowId, isLaunching, runContext]);

  // Bridge TriggerPanel's onExecuteTrigger to the run-manager so chat/form/
  // webhook fires keep WS subscription warm + the UI updates live.
  // Returns readySteps (TriggerPanel uses it to refresh its disable state).
  const handlePanelExecuteTrigger = React.useCallback(async (
    triggerId: string,
    triggerType: 'chat' | 'form' | 'webhook',
    payload: Record<string, unknown>,
  ): Promise<string[] | undefined> => {
    if (!runId) return undefined;
    if (runContext?.executeStep) {
      // Returns StepExecutionResult; readySteps is on the response shape.
      const result = await runContext.executeStep(runId, triggerId, payload, triggerType);
      return result?.readySteps;
    }
    // Fallback raw API (no live updates).
    try {
      const response = await orchestratorApi.triggerSpecific(runId, triggerId, triggerType, payload);
      return response?.readySteps as string[] | undefined;
    } catch (e) {
      console.error('[ApplicationTabContent] panel trigger fire failed', e);
      return undefined;
    }
  }, [runId, runContext]);

  const { data: interfaceDetails } = useInterfaceById(config.interfaceId);

  // Epoch list from run state (needed before render hook)
  const epochTimestamps = runState?.epochTimestamps ?? [];
  const totalEpochs = epochTimestamps.length;

  // Default to the latest epoch on first load (not "All").
  const initializedRef = React.useRef(false);
  React.useEffect(() => {
    if (initializedRef.current || totalEpochs === 0) return;
    if (viewingEpoch != null) { initializedRef.current = true; return; }
    const latest = Math.max(...epochTimestamps.map((e: any) => e.epoch ?? 0));
    if (latest > 0) {
      handleViewEpoch(latest);
      initializedRef.current = true;
    }
  }, [totalEpochs, epochTimestamps, viewingEpoch, handleViewEpoch]);

  const { data: renderData, isLoading, isFetching, isPlaceholderData, refetch } = useInterfaceRender(
    config.interfaceId,
    runId,
    currentPage,
    1,
    viewingEpoch ?? undefined,
    variablePages
  );

  // Reset pagination when viewingEpoch changes.
  //
  // Backend sort in InterfaceRenderService.render(): epoch DESC, itemIndex DESC.
  // → page 0 is ALWAYS the most recent epoch (not the oldest).
  //
  // Rules:
  //   • In "All" (viewingEpoch == null) mode we want the interface to render
  //     the freshest epoch's content by default - that's page 0 given the
  //     DESC sort. Status counts still accumulate across ALL epochs.
  //   • When switching to a specific epoch, reset to page 0 (within-epoch
  //     pagination starts at the top, highest itemIndex).
  //   • When switching BACK from a specific epoch to All, re-pin to page 0
  //     (= latest epoch).
  //   • We do NOT auto-scroll when new epochs arrive while the user stays in
  //     All - respect their current position.
  const prevEpochRef = React.useRef<number | null>(viewingEpoch);
  const allModeInitialPinRef = React.useRef(false);
  React.useEffect(() => {
    const epochChanged = prevEpochRef.current !== viewingEpoch;
    prevEpochRef.current = viewingEpoch;

    if (viewingEpoch == null) {
      // All mode. Pin to page 0 (latest epoch) once per entry into this mode.
      if (!allModeInitialPinRef.current || epochChanged) {
        if (totalEpochs > 0) {
          setCurrentPage(0);
          setVariablePages({});
          allModeInitialPinRef.current = true;
        }
      }
    } else {
      // Specific epoch - reset the All-mode pin so next switch back re-pins.
      allModeInitialPinRef.current = false;
      if (epochChanged) {
        setCurrentPage(0);
        setVariablePages({});
      }
    }
  }, [viewingEpoch, setCurrentPage, totalEpochs]);

  // Debounced refetch when execution state changes.
  // Uses executionTotal (monotonically increasing sum of all per-node statusCounts)
  // instead of resolvedStepCount (Set-based, unreliable across epoch resets).
  const executionTotal = runState?.executionTotal ?? 0;

  const execTotalRef = React.useRef(executionTotal);
  React.useEffect(() => {
    if (!isRunMode) return;

    if (executionTotal === 0 || executionTotal === execTotalRef.current) return;

    execTotalRef.current = executionTotal;
    const timeoutId = setTimeout(() => {
      refetch();
    }, 2000);
    return () => clearTimeout(timeoutId);
  }, [isRunMode, executionTotal, refetch]);

  // Show loading overlay only during actual fetches where displayed content is stale placeholder.
  // Don't show during debounce wait (old content is still valid) or initial load with no content yet
  // (InterfaceIframe handles its own fade-in).
  const isTransitioning = isFetching && isPlaceholderData;

  const htmlTemplate = renderData?.htmlTemplate || '';
  const items = renderData?.items || [];

  const itemData = React.useMemo(() => {
    if (items.length === 0) return undefined;
    return items[0]?.data as Record<string, unknown> | undefined;
  }, [items]);

  const triggerData = React.useMemo(() => {
    if (items.length === 0) return undefined;
    return items[0]?.triggerData as Record<string, Record<string, unknown>> | undefined;
  }, [items]);

  // Merge trigger data into resolved data so {{trigger:name.output.field}} works in templates
  const resolvedData = React.useMemo(() => {
    return mergeTriggerDataIntoResolved(itemData, triggerData);
  }, [itemData, triggerData]);

  // When the run has no data yet, render in "edit" mode so `{{xxx|default}}`
  // pipe defaults show instead of raw `{{…}}` placeholders.
  const hasResolvedData = !!resolvedData && Object.keys(resolvedData).length > 0;
  const renderMode: RenderMode = hasResolvedData ? 'run' : 'edit';

  // Center iframe body content - see SAFE_CENTERING_CSS for rationale on the
  // `safe center` keyword (centers small interfaces but lets tall dashboards
  // scroll from the top instead of clipping above the viewport).
  const centeringCss = SAFE_CENTERING_CSS;

  // Auto-navigate to the latest epoch ONLY when a new one genuinely appears
  // mid-session (e.g. the user fires a trigger and a new epoch closes while
  // they're watching). On the initial mount / refresh we default to "All
  // epochs" (viewingEpoch == null) so RunInfo shows cumulative statusCounts
  // - otherwise the user lands on one specific epoch and sees counts = 1 on
  // a node that actually ran across multiple epochs.
  const prevEpochCountRef = React.useRef(0);
  const prevRunIdRef2 = React.useRef(runId);
  const initialSyncDoneRef = React.useRef(false);
  React.useEffect(() => {
    // Reset on run change so we re-prime the baseline for the new run.
    if (prevRunIdRef2.current !== runId) {
      prevRunIdRef2.current = runId;
      prevEpochCountRef.current = 0;
      initialSyncDoneRef.current = false;
    }
    if (totalEpochs === 0) return;

    // First time we see epochs for this run: prime the baseline, but DO NOT
    // force viewingEpoch to the latest. A null viewingEpoch means "All" and
    // must be preserved on refresh; a non-null viewingEpoch was lifted from
    // a previous carousel tab and is equally left untouched.
    if (!initialSyncDoneRef.current) {
      initialSyncDoneRef.current = true;
      prevEpochCountRef.current = totalEpochs;
      return;
    }

    // Post-initial-sync: a new epoch closed. Auto-jump only when the user is
    // already pinned to a specific epoch (they were watching live epoch N -
    // carry them to N+1). If they chose "All", respect that choice.
    const newEpochAppeared = totalEpochs > prevEpochCountRef.current;
    if (newEpochAppeared) {
      prevEpochCountRef.current = totalEpochs;
      if (viewingEpoch != null) {
        const latestEpoch = Math.max(...epochTimestamps.map((e: any) => e.epoch));
        handleViewEpoch(latestEpoch);
      }
    }
  }, [runId, totalEpochs, epochTimestamps, viewingEpoch, handleViewEpoch]);

  // Pending interface signals for this node (split-aware: one signal per itemId).
  const interfacePendingSignals = React.useMemo(() => {
    if (!runState?.pendingSignals || !config.nodeId) return [];
    return runState.pendingSignals.filter(
      s => s.nodeId === config.nodeId && s.signalType === 'INTERFACE_SIGNAL'
    );
  }, [runState?.pendingSignals, config.nodeId]);
  const pendingSignalCount = interfacePendingSignals.length;

  // Render pagination is meaningful whenever the backend exposes multiple
  // rendered pages. In All-epochs mode this browses the ordered page set
  // returned by InterfaceRenderService; choosing an epoch narrows that set.
  const effectiveTotalPages = renderData?.pagination?.totalPages ?? 0;
  const effectiveSpawnPages = effectiveTotalPages;
  const hasSpawnPagination = effectiveSpawnPages > 1;

  // ── Run-context semantics for the pagination counter ──
  // The render API tags every item with its {epoch, spawn, itemIndex} triple.
  // When the user is pinned to ONE epoch, each page IS one item of that epoch
  // → replace the bare "{page+1} / {totalPages}" counter with "Item X/Y"
  // (1-based, same convention as the Files browser). In "All epochs" mode the
  // pages span epochs so the bare counter stays (an item-number there would
  // look stuck across epochs of one item each).
  const currentItemTriple = items[0];
  const itemPageLabel = viewingEpoch != null && hasSpawnPagination && currentItemTriple
    ? tRun('itemOfTotal', { number: (currentItemTriple.itemIndex ?? 0) + 1, total: effectiveSpawnPages })
    : undefined;
  // Re-execution badge: spawn > 0 means the displayed item is a re-run within
  // the same epoch. Shown 1-based ("Re-execution 2" = second execution) and
  // ONLY for re-runs so first executions stay clean.
  const reExecutionBadge = currentItemTriple && (currentItemTriple.spawn ?? 0) > 0
    ? tRun('reExecutionBadge', { number: currentItemTriple.spawn + 1 })
    : undefined;

  React.useEffect(() => {
    if (effectiveSpawnPages <= 0) return;
    if (currentPage < 0) {
      setCurrentPage(0);
    } else if (currentPage >= effectiveSpawnPages) {
      setCurrentPage(effectiveSpawnPages - 1);
    }
  }, [currentPage, effectiveSpawnPages, setCurrentPage]);

  const handlePrevious = React.useCallback(() => {
    if (currentPage > 0) setCurrentPage(prev => prev - 1);
  }, [currentPage, setCurrentPage]);

  const handleNext = React.useCallback(() => {
    if (currentPage < effectiveSpawnPages - 1) {
      setCurrentPage(prev => prev + 1);
    }
  }, [currentPage, effectiveSpawnPages, setCurrentPage]);

  // Handle pagination actions from iframe bridge script (__pagination:prev/next)
  const handleIframePagination = React.useCallback((direction: 'prev' | 'next') => {
    if (direction === 'prev') handlePrevious();
    else if (direction === 'next') handleNext();
  }, [handlePrevious, handleNext]);

  // Handle per-variable pagination from iframe (variable-pagination postMessage or __varpage: triggers)
  const handleVariablePagination = React.useCallback((variableName: string, page: number) => {
    setVariablePages(prev => {
      if (prev[variableName] === page) return prev;
      return { ...prev, [variableName]: page };
    });
  }, []);

  const variablePaginationItems = React.useMemo<ResolvedVariablePagination[]>(() => {
    if (!resolvedData) return [];

    return Object.keys(resolvedData)
      .filter(key => key.endsWith('__totalPages'))
      .map(key => {
        const name = key.slice(0, -'__totalPages'.length);
        if (isExplicitFalse(resolvedData[`${name}__paginationSupported`])) return null;
        const totalPages = toFiniteNonNegativeInteger(resolvedData[key]);
        if (totalPages == null || totalPages <= 1) return null;
        const resolvedPage = toFiniteNonNegativeInteger(resolvedData[`${name}__page`]);
        const requestedPage = toFiniteNonNegativeInteger(variablePages[name]);
        return {
          name,
          page: resolvedPage ?? requestedPage ?? 0,
          totalPages,
        };
      })
      .filter((item): item is ResolvedVariablePagination => item != null)
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [resolvedData, variablePages]);

  const [activeVariablePageName, setActiveVariablePageName] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (variablePaginationItems.length === 0) {
      if (activeVariablePageName != null) setActiveVariablePageName(null);
      return;
    }
    if (!activeVariablePageName || !variablePaginationItems.some(item => item.name === activeVariablePageName)) {
      setActiveVariablePageName(variablePaginationItems[0].name);
    }
  }, [activeVariablePageName, variablePaginationItems]);

  const activeVariablePage = React.useMemo(() => {
    return variablePaginationItems.find(item => item.name === activeVariablePageName)
      ?? variablePaginationItems[0]
      ?? null;
  }, [activeVariablePageName, variablePaginationItems]);

  React.useEffect(() => {
    if (variablePaginationItems.length === 0) return;

    setVariablePages(prev => {
      let next = prev;
      let changed = false;

      for (const item of variablePaginationItems) {
        if (item.page < item.totalPages) continue;
        if (prev[item.name] === item.totalPages - 1) continue;
        if (!changed) next = { ...prev };
        next[item.name] = item.totalPages - 1;
        changed = true;
      }

      return changed ? next : prev;
    });
  }, [variablePaginationItems]);

  const handleVariablePrevious = React.useCallback(() => {
    if (!activeVariablePage || activeVariablePage.page <= 0) return;
    handleVariablePagination(activeVariablePage.name, activeVariablePage.page - 1);
  }, [activeVariablePage, handleVariablePagination]);

  const handleVariableNext = React.useCallback(() => {
    if (!activeVariablePage || activeVariablePage.page >= activeVariablePage.totalPages - 1) return;
    handleVariablePagination(activeVariablePage.name, activeVariablePage.page + 1);
  }, [activeVariablePage, handleVariablePagination]);

  // Fullscreen toggle
  const handleToggleExpanded = React.useCallback(() => {
    setIsExpanded(prev => !prev);
  }, [setIsExpanded]);

  // Listen for openInterfaceFullscreen events from canvas nodes
  React.useEffect(() => {
    const handleExternalFullscreen = (event: CustomEvent<{ interfaceId: string }>) => {
      if (event.detail.interfaceId === config.interfaceId) {
        setIsExpanded(true);
      }
    };
    window.addEventListener('openInterfaceFullscreen', handleExternalFullscreen as EventListener);
    return () => {
      window.removeEventListener('openInterfaceFullscreen', handleExternalFullscreen as EventListener);
    };
  }, [config.interfaceId, setIsExpanded]);

  // Escape to exit expanded mode, arrow keys for pagination
  React.useEffect(() => {
    if (!isExpanded) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setIsExpanded(false);
      } else if (e.key === 'ArrowLeft') {
        handlePrevious();
      } else if (e.key === 'ArrowRight') {
        handleNext();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isExpanded, handlePrevious, handleNext, setIsExpanded]);

  // Prevent body scroll when expanded
  React.useEffect(() => {
    if (isExpanded) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => { document.body.style.overflow = ''; };
  }, [isExpanded]);

  const hasActions = hasSpawnPagination || variablePaginationItems.length > 0 || totalEpochs > 1 || !!htmlTemplate;

  // Show preview-mode toast with auto-dismiss
  const showPreviewBlockedToast = React.useCallback(() => {
    if (previewToastTimeoutRef.current) clearTimeout(previewToastTimeoutRef.current);
    setShowPreviewToast(true);
    previewToastTimeoutRef.current = setTimeout(() => setShowPreviewToast(false), 4000);
  }, []);

  // Cleanup timeout on unmount
  React.useEffect(() => {
    return () => { if (previewToastTimeoutRef.current) clearTimeout(previewToastTimeoutRef.current); };
  }, []);

  // Block trigger re-submissions while workflow is running (backend rejects with 409 anyway)
  const isRunning = runState?.runStatus === 'running';
  const actionInFlightRef = React.useRef(false);

  // Wrap onAction to catch errors and show them to the user (for iframe bridge script)
  // Uses actionInFlightRef to prevent duplicate trigger calls (bridge script can fire twice)
  // isRunning guard only affects trigger actions (safeOnAction); __continue and __pagination
  // use separate callbacks (handleContinue, handleIframePagination) and are never blocked.
  // Navigate actions (:navigate suffix) are pure frontend tab switches - always allowed through.
  const safeOnAction = React.useCallback(async (triggerRef: string, data: Record<string, unknown>) => {
    const isNavigateAction = triggerRef.endsWith(':navigate');
    if (isPreviewOnly && !isNavigateAction) {
      showPreviewBlockedToast();
      return;
    }
    // Navigate actions bypass isRunning and actionInFlight guards (no API call)
    if (!isNavigateAction && (isRunning || actionInFlightRef.current)) {
      return;
    }
    if (!isNavigateAction) {
      actionInFlightRef.current = true;
    }
    setActionError(null);
    try {
      await onAction(triggerRef, data);
      // After trigger fires, stay on current viewingEpoch. The auto-navigate effect
      // will switch to the new epoch once it appears in epochTimestamps.
    } catch (error: any) {
      const message = error?.message || error?.toString() || 'Action failed';
      setActionError(message);
    } finally {
      if (!isNavigateAction) {
        actionInFlightRef.current = false;
      }
    }
  }, [onAction, isPreviewOnly, isRunning, showPreviewBlockedToast]);

  // Handle __continue: resolve the interface signal so the workflow continues past this interface.
  // Use the *rendered* item's itemIndex (from the API response) rather than the pagination
  // page number. In split context, itemIndex directly maps to the signal's itemId on the
  // backend, ensuring the correct per-item signal is resolved. currentPage is a pagination
  // cursor (DESC-sorted) and does NOT equal the item's split index.
  const currentItemIndex = items[0]?.itemIndex ?? 0;

  // Check if the currently displayed item still has a pending signal (guards against
  // clicking Continue on an already-resolved item after stale render data).
  const isCurrentItemPending = React.useMemo(() => {
    const fallbackAwaitingSinglePage = effectiveTotalPages <= 1
      && computeIsAwaitingSignal(config.nodeId, runState, config.actionMapping);
    return isCurrentInterfaceItemPending(interfacePendingSignals, currentItemIndex, fallbackAwaitingSinglePage);
  }, [
    interfacePendingSignals,
    currentItemIndex,
    effectiveTotalPages,
    config.nodeId,
    runState?.awaitingSignalSteps,
    runState?.runningSteps,
    config.actionMapping,
  ]);

  const handleContinue = React.useCallback((actionKey: string, data: Record<string, unknown>) => {
    if (!runId || !config.nodeId) return;
    window.dispatchEvent(new CustomEvent('workflowInterfaceContinue', {
      detail: { runId, nodeId: config.nodeId, actionKey, data, itemIndex: currentItemIndex },
    }));
  }, [runId, config.nodeId, currentItemIndex]);

  // Detect if the interface node is awaiting signal.
  // Only BLOCKING interfaces (actionMapping contains "__continue") ever yield
  // AWAITING_SIGNAL - non-blocking interfaces complete via SUCCESS and the
  // Continue button would be a no-op (backend fallback, but workflow has
  // already moved past this node). Logic extracted to a pure helper for unit
  // testing - see interfaceAwaitingSignal.ts and its test.
  const isAwaitingSignal = React.useMemo(() => {
    return computeIsAwaitingSignal(config.nodeId, runState, config.actionMapping);
  }, [config.nodeId, runState?.awaitingSignalSteps, runState?.runningSteps, config.actionMapping]);

  const [isContinuing, setIsContinuing] = React.useState(false);

  const handleDefaultContinue = React.useCallback(() => {
    if (!runId || !config.nodeId || isContinuing) return;
    // Guard: don't send Continue for an already-resolved item (stale render data race)
    if (!isCurrentItemPending) return;
    setIsContinuing(true);
    handleContinue('__continue', {});
  }, [runId, config.nodeId, isContinuing, isCurrentItemPending, handleContinue]);

  // Reset loading when awaiting state clears OR when execution progresses
  // (covers parallel epochs where isAwaitingSignal stays true because the
  // next epoch's signal is already registered before the current one resolves).
  React.useEffect(() => {
    if (!isAwaitingSignal) setIsContinuing(false);
  }, [isAwaitingSignal]);

  React.useEffect(() => {
    if (isContinuing) setIsContinuing(false);
  }, [executionTotal]); // eslint-disable-line react-hooks/exhaustive-deps

  // Safety valve: reset isContinuing after 10s if neither isAwaitingSignal nor
  // executionTotal changed (guards against silent backend errors where the event
  // bridge catches the error but never propagates it back to this component).
  React.useEffect(() => {
    if (!isContinuing) return;
    const timeout = setTimeout(() => setIsContinuing(false), 10_000);
    return () => clearTimeout(timeout);
  }, [isContinuing]);

  // ── Toolbar collapsed/expanded toggle - use controlled props if provided, otherwise local state ──
  const [localToolbarOpen, setLocalToolbarOpen] = React.useState(false);
  const toolbarOpen = controlledToolbarOpen ?? localToolbarOpen;
  const setToolbarOpen = React.useCallback((open: boolean) => {
    if (onToolbarOpenChange) {
      onToolbarOpenChange(open);
    } else {
      setLocalToolbarOpen(open);
    }
  }, [onToolbarOpenChange]);

  // ── Epoch selector: badge with popup (same data as RunInfo EpochSelector) ──
  const [epochDropdownOpen, setEpochDropdownOpen] = React.useState(false);
  const epochDropdownRef = React.useRef<HTMLDivElement>(null);

  // Close dropdown on click outside
  React.useEffect(() => {
    if (!epochDropdownOpen) return;
    const handler = (e: MouseEvent) => {
      if (epochDropdownRef.current && !epochDropdownRef.current.contains(e.target as Node)) {
        setEpochDropdownOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [epochDropdownOpen]);

  // Current display epoch: viewingEpoch if set, otherwise latest epoch
  const currentDisplayEpoch = React.useMemo(() => {
    if (viewingEpoch != null) return viewingEpoch;
    if (epochTimestamps.length > 0) return Math.max(...epochTimestamps.map((e: any) => e.epoch ?? 1));
    return 1;
  }, [viewingEpoch, epochTimestamps]);

  // Sorted epochs (newest first) + durations (same as RunInfo EpochSelector)
  const sortedEpochs = React.useMemo(() => {
    const sorted = [...epochTimestamps].sort((a: any, b: any) => b.epoch - a.epoch);
    return sorted.map((entry: any) => {
      const startMs = entry.startedAt ? parseUtcAware(entry.startedAt).getTime() : 0;
      const endMs = entry.endedAt ? parseUtcAware(entry.endedAt).getTime() : Date.now();
      const duration = startMs ? Math.max(0, endMs - startMs) : 0;
      const isRunning = entry.startedAt != null && entry.endedAt == null;
      return { ...entry, duration, isRunning, startMs };
    });
  }, [epochTimestamps]);

  const maxDuration = React.useMemo(() => Math.max(...sortedEpochs.map(e => e.duration), 1), [sortedEpochs]);

  // ── Shared toolbar extraControls (launch + epoch selector + continue) ──
  const toolbarExtraControls = React.useMemo(() => {
    // Launch button - opens the multi-trigger panel (TriggerPanel) when there
    // are chat/form/webhook triggers (works in any context: app page, agent
    // visualize popup, /s/<token> share). For workflows whose only trigger is
    // manual, the click fires inline (no panel needed). Hidden if no
    // launchable trigger exists.
    // Style mirrors the Continue button (Button size="sm" h-8 black-on-white
    // pill) so the toolbar's two action buttons read as peer controls.
    // Label/icon strategy:
    //   • exactly one panel trigger → show that trigger's label + per-type
    //     icon (the click feels deterministic).
    //   • two or more panel triggers → generic "Launch" + Play icon - the
    //     button doesn't claim a specific trigger because the panel auto-
    //     selects the first tab but the user can switch.
    //   • zero panel triggers + manual → the manual label + Play icon (inline
    //     fire, no panel).
    const panelCount = launchable.panelConfigs.length;
    const onlyPanelCfg = panelCount === 1 ? launchable.panelConfigs[0] : null;
    // Precedence ladder for label + type + icon: panel triggers (the
    // explicit chat/form/webhook author intent) > manual > schedule. The
    // schedule fallback is the "force fire cron now" affordance for
    // workflows whose only trigger is a periodic schedule.
    const buttonLabel = onlyPanelCfg?.triggerLabel
      || (panelCount > 1 ? tActions('launch') : null)
      || launchable.firstManual?.label
      || launchable.firstSchedule?.label
      || tActions('launch');
    const buttonType: 'chat' | 'form' | 'webhook' | 'manual' | 'multi' | 'schedule' | null =
      onlyPanelCfg?.type
      ?? (panelCount > 1 ? 'multi' : null)
      ?? (launchable.firstManual ? 'manual' : null)
      ?? (launchable.firstSchedule ? 'schedule' : null);
    const TriggerIcon = buttonType === 'form' ? FormInput
      : buttonType === 'chat' ? MessageCircle
      : buttonType === 'webhook' ? Webhook
      : buttonType === 'manual' ? Play
      : buttonType === 'multi' ? Play
      : buttonType === 'schedule' ? Calendar
      : null;
    // Hide the Launch button entirely in preview mode - anonymous visitors
    // browsing a marketplace publication MUST NOT be able to fire the
    // publisher's workflow (the backend would 403, but surfacing the button
    // is a confusing affordance). The publisher viewing their own preview
    // sees exactly what the visitor sees by contract - same gate applies.
    const launchButton = !previewMode && hasAnyLaunchable && TriggerIcon ? (
      <Button
        key="launch"
        onClick={handleLaunchTrigger}
        // Disabled iff we're already firing OR we'd have nothing to fire:
        // - panel triggers always render the panel (no runId required)
        // - manual needs runId (it resumes a WAITING_TRIGGER run)
        // - schedule needs only workflowId (spawns a fresh run server-side)
        disabled={isLaunching || (!hasPanelTriggers && !runId && !launchable.firstSchedule)}
        size="sm"
        className="h-8 px-3 rounded-full shadow-none border-0 gap-1.5 bg-black dark:bg-white text-white dark:text-black hover:bg-gray-800 dark:hover:bg-gray-200 focus-visible:ring-2 focus-visible:ring-theme-tertiary disabled:opacity-50"
        title={tActions('launchTrigger', { label: buttonLabel })}
      >
        {isLaunching ? <LoadingSpinner size="sm" /> : <TriggerIcon className="h-3.5 w-3.5" />}
        <span className="text-xs font-medium">{buttonLabel}</span>
      </Button>
    ) : null;

    const epochSelector = totalEpochs > 0 && runId ? (
      <div key="epoch" ref={epochDropdownRef} className="relative flex items-center">
        {/* Epoch badge button */}
        <button
          type="button"
          onClick={() => totalEpochs > 1 ? setEpochDropdownOpen(prev => !prev) : undefined}
          className={`h-7 flex items-center gap-1.5 px-2.5 rounded-full text-xs transition-colors ${
            epochDropdownOpen
              ? 'bg-gray-900 dark:bg-gray-100 text-white dark:text-gray-900'
              : 'text-[var(--text-secondary)] hover:bg-gray-200 dark:hover:bg-gray-700 hover:text-gray-900 dark:hover:text-gray-100'
          }`}
          data-testid="application-epoch-selector"
          title={`Epoch ${currentDisplayEpoch}`}
        >
          <Calendar className="h-3 w-3" />
          <span className="font-medium tabular-nums">{currentDisplayEpoch}</span>
        </button>

        {/* Epoch dropdown popup (same layout as RunInfo EpochSelector) */}
        {epochDropdownOpen && totalEpochs > 1 && (
          <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 z-50 rounded-xl bg-white/95 dark:bg-gray-800/95 backdrop-blur shadow-xl border border-slate-200 dark:border-slate-700 py-1.5 min-w-[260px] max-h-[240px] overflow-y-auto">
            {sortedEpochs.map((entry) => {
              const isSelected = entry.epoch === currentDisplayEpoch;
              const barPct = maxDuration > 0 ? Math.max(5, (entry.duration / maxDuration) * 100) : 5;

              return (
                <button
                  key={entry.epoch}
                  type="button"
                  onClick={() => {
                    handleViewEpoch(entry.epoch);
                    setEpochDropdownOpen(false);
                  }}
                  data-testid={`application-epoch-option-${entry.epoch}`}
                  className={`w-full flex items-center gap-1.5 px-3 py-1.5 text-xs transition-colors ${
                    isSelected
                      ? 'border-l-2 border-gray-900 dark:border-gray-100 bg-gray-50 dark:bg-white/[0.04] font-semibold text-gray-900 dark:text-gray-100'
                      : 'border-l-2 border-transparent hover:bg-gray-50/80 dark:hover:bg-white/[0.03]'
                  }`}
                >
                  {/* Epoch number */}
                  <div className={`w-5 shrink-0 text-center tabular-nums ${
                    isSelected ? 'font-bold text-gray-900 dark:text-gray-100' : 'font-medium text-gray-500 dark:text-gray-400'
                  }`}>
                    {entry.epoch}
                  </div>
                  {entry.isRunning && (
                    <span className="relative flex h-1.5 w-1.5 shrink-0">
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75" />
                      <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-blue-500" />
                    </span>
                  )}
                  {/* Time range */}
                  <span className={`flex-1 text-center text-[10px] tabular-nums whitespace-nowrap ${
                    entry.isRunning ? 'text-blue-500 dark:text-blue-400' : 'text-gray-500 dark:text-gray-400'
                  }`}>
                    {entry.startedAt ? `${formatEpochTime(entry.startedAt)} → ${entry.endedAt ? formatEpochTime(entry.endedAt) : '...'}` : '-'}
                  </span>
                  {/* Duration bar */}
                  <div className="w-12 h-[3px] rounded-full bg-gray-100 dark:bg-white/[0.06] overflow-hidden shrink-0">
                    <div
                      className={`h-full rounded-full transition-all ${
                        entry.isRunning ? 'bg-blue-500 animate-pulse' : isSelected ? 'bg-emerald-500' : 'bg-emerald-500/70'
                      }`}
                      style={{ width: `${barPct}%` }}
                    />
                  </div>
                  {/* Duration text */}
                  <span className={`min-w-[32px] text-right text-[10px] tabular-nums font-medium shrink-0 ${
                    entry.isRunning ? 'text-blue-500 dark:text-blue-400' : 'text-gray-500 dark:text-gray-400'
                  }`}>
                    {entry.startedAt ? formatEpochDuration(entry.duration) : ''}
                  </span>
                </button>
              );
            })}
          </div>
        )}
      </div>
    ) : null;

    // Same risk class as launchButton: Continue dispatches
    // `workflowInterfaceContinue` which routes through
    // `useWorkflowEventBridge` → authed `interfaceService.fireInterfaceAction`
    // against the publisher's tenant. The event bridge IS mounted on the
    // preview page (WorkflowRunCanvas runs inside ApplicationDetailView even
    // in preview), so leaving the button visible is a confusing 403-pending
    // affordance for visitors. Hide it for consistency with launchButton.
    // In split context, the Continue button resolves ONLY the currently displayed
    // item (not all pending). Label communicates per-item scope; tooltip changes
    // when the current item is already resolved so the user understands the
    // disabled state.
    const continueLabel = t('continueWorkflow');
    const isItemResolved = !isCurrentItemPending && !isContinuing;
    // Tooltip context: WHICH epoch/item will be continued. Epoch is the raw
    // epoch number (matches the epoch selector); item is 1-based.
    const continueContext = currentItemTriple
      ? `${tRun('epochBadge', { number: currentItemTriple.epoch })} · ${tRun('itemBadge', { number: (currentItemTriple.itemIndex ?? 0) + 1 })}`
      : null;
    const continueBaseTitle = pendingSignalCount > 1
      ? t('continueItemRemaining', { count: pendingSignalCount })
      : continueLabel;
    const continueTitle = isItemResolved
      ? t('itemAlreadyResolved')
      : continueContext
        ? `${continueBaseTitle} - ${continueContext}`
        : continueBaseTitle;
    const continueButton = !previewMode && isAwaitingSignal && runId && config.nodeId ? (
      <Button
        key="continue"
        onClick={handleDefaultContinue}
        disabled={isContinuing || !isCurrentItemPending}
        size="sm"
        className="h-8 px-3 rounded-full shadow-none border-0 gap-1.5 bg-black dark:bg-white text-white dark:text-black hover:bg-gray-800 dark:hover:bg-gray-200 focus-visible:ring-2 focus-visible:ring-theme-tertiary disabled:opacity-50"
        title={continueTitle}
      >
        {isContinuing ? <LoadingSpinner size="sm" /> : <StepForward className="h-3.5 w-3.5" />}
        <span className="text-xs font-medium">{continueLabel}</span>
        {pendingSignalCount > 1 && (
          <span className="inline-flex items-center justify-center h-4 min-w-[16px] px-1 rounded-full bg-white/20 dark:bg-black/20 text-[10px] font-semibold tabular-nums">
            {pendingSignalCount}
          </span>
        )}
      </Button>
    ) : null;

    const variablePaginationControl = activeVariablePage ? (
      <div key="variable-pagination" className="flex items-center gap-1" data-testid="application-variable-pagination">
        {variablePaginationItems.length > 1 ? (
          <select
            value={activeVariablePage.name}
            onChange={(event) => setActiveVariablePageName(event.target.value)}
            aria-label={tCanvas('variablePageSource')}
            title={tCanvas('variablePageSource')}
            data-testid="application-variable-page-select"
            className="h-7 max-w-[92px] rounded-full bg-transparent px-2 text-xs font-medium text-[var(--text-secondary)] outline-none hover:bg-gray-200 dark:hover:bg-gray-700 hover:text-gray-900 dark:hover:text-gray-100"
          >
            {variablePaginationItems.map(item => (
              <option key={item.name} value={item.name}>{item.name}</option>
            ))}
          </select>
        ) : (
          <span
            className="max-w-[92px] truncate px-2 text-xs font-medium text-[var(--text-secondary)]"
            title={activeVariablePage.name}
          >
            {activeVariablePage.name}
          </span>
        )}
        <button
          type="button"
          onClick={handleVariablePrevious}
          disabled={activeVariablePage.page <= 0}
          aria-label={tCanvas('previousVariablePage')}
          title={tCanvas('previousVariablePage')}
          data-testid="application-variable-page-previous"
          className="w-7 h-7 p-0 rounded-full transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-[var(--text-secondary)]"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
        </button>
        <span
          className="text-xs text-[var(--text-secondary)] font-medium min-w-[42px] text-center tabular-nums"
          data-testid="application-variable-page-label"
        >
          {activeVariablePage.page + 1} / {activeVariablePage.totalPages}
        </span>
        <button
          type="button"
          onClick={handleVariableNext}
          disabled={activeVariablePage.page >= activeVariablePage.totalPages - 1}
          aria-label={tCanvas('nextVariablePage')}
          title={tCanvas('nextVariablePage')}
          data-testid="application-variable-page-next"
          className="w-7 h-7 p-0 rounded-full transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-[var(--text-secondary)]"
        >
          <ChevronRight className="h-3.5 w-3.5" />
        </button>
      </div>
    ) : null;

    if (!variablePaginationControl && !launchButton && !epochSelector && !continueButton) return undefined;
    return <>{variablePaginationControl}{launchButton}{epochSelector}{continueButton}</>;
  }, [totalEpochs, epochTimestamps, sortedEpochs, maxDuration, viewingEpoch, currentDisplayEpoch, epochDropdownOpen, handleViewEpoch, runId, isAwaitingSignal, config.nodeId, isContinuing, isCurrentItemPending, handleDefaultContinue, t, tRun, currentItemTriple, pendingSignalCount, launchable, hasPanelTriggers, hasAnyLaunchable, handleLaunchTrigger, isLaunching, tActions, previewMode, activeVariablePage, variablePaginationItems, handleVariablePrevious, handleVariableNext, tCanvas]);

  // ── Shared iframe content ──
  // The application stays visible at all times. While its workflow run is
  // executing (runStatus === 'running'), a pulsing blue border is overlaid on
  // top to signal "this app's current epoch is running" - see RunningBorder.
  // Rendered here (inside the shared content) so every surface that shows an
  // application gets the same border in both panel and fullscreen modes.
  const iframeContent = (
    <div className="relative w-full h-full">
      {!htmlTemplate ? (
        <div className="flex items-center justify-center h-full text-slate-400 dark:text-slate-500 text-sm">
          No template configured
        </div>
      ) : (
        <InterfaceIframe
          htmlTemplate={htmlTemplate}
          mode={renderMode}
          resolvedData={hasResolvedData ? resolvedData : undefined}
          customCss={centeringCss + (renderData?.cssTemplate || interfaceDetails?.cssTemplate || '')}
          jsTemplate={(renderData as any)?.jsTemplate || (interfaceDetails as any)?.jsTemplate || undefined}
          className="w-full h-full"
          style={{ height: '100%' }}
          sandbox="allow-same-origin allow-scripts allow-forms"
          actionMapping={config.actionMapping}
          triggerData={triggerData}
          onAction={safeOnAction}
          onPagination={handleIframePagination}
          onContinue={handleContinue}
          onVariablePagination={handleVariablePagination}
          fileUploadContext={workflowId && runId ? { workflowId, runId } : undefined}
        />
      )}
      {/* Smooth loading overlay during transitions (epoch change, pagination, execution refetch) */}
      <div
        className="absolute inset-0 bg-white/60 dark:bg-slate-900/60 flex items-center justify-center pointer-events-none"
        style={{
          opacity: isTransitioning ? 1 : 0,
          transition: 'opacity 200ms ease-in-out',
        }}
      >
        <LoadingSpinner size="sm" />
      </div>
      {/* Pulsing blue "running" border - overlays the app (still visible
          underneath) while the run executes. Renders nothing otherwise. */}
      <RunningBorder running={isRunning} label={tActions('running')} />
    </div>
  );

  // Element ref (useState pattern, not useRef) for the application
  // container. The TriggerPanel reads its bounding rect to center its
  // dropdown on the iframe rather than the viewport - without this, the
  // panel sits at viewport-center which is OFF the visible application area
  // in side-panel layouts (workflow inspector, marketplace shell). We use
  // useState + callback ref instead of useRef so the parent re-renders
  // once the DOM node attaches, propagating the element down to TriggerPanel
  // (a plain useRef.current would be `null` at the time the JSX is built
  // and the TriggerPanel would never see the anchor).
  const [appContainerEl, setAppContainerEl] = React.useState<HTMLDivElement | null>(null);

  // Track the preview container's viewport rect so the portalled floating
  // toolbar (previewMode branch below) lands at the bottom-center of the
  // preview panel - NOT the bottom-center of the viewport. The portal escapes
  // the marketplace shell's overflow-hidden clipping, but `fixed`
  // positioning would otherwise dock the toolbar to the page bottom which is
  // visibly wrong on the marketplace page (header + sidebar take ~250px on
  // the left). Recompute on resize / scroll so the toolbar follows panel
  // movement (e.g. inspector pane open/close, mobile rotation).
  const [containerRect, setContainerRect] = React.useState<DOMRect | null>(null);
  React.useLayoutEffect(() => {
    if (!previewMode || !appContainerEl) {
      setContainerRect(null);
      return;
    }
    const update = () => setContainerRect(appContainerEl.getBoundingClientRect());
    update();
    const ro = new ResizeObserver(update);
    ro.observe(appContainerEl);
    // Capture phase catches scroll on any ancestor (the marketplace shell
    // may scroll independently of window).
    window.addEventListener('scroll', update, true);
    window.addEventListener('resize', update);
    return () => {
      ro.disconnect();
      window.removeEventListener('scroll', update, true);
      window.removeEventListener('resize', update);
    };
  }, [previewMode, appContainerEl]);

  // ── Expanded (fullscreen) mode - portal overlay ──
  if (isExpanded) {
    return createPortal(
      <div ref={setAppContainerEl} className="fixed inset-0 z-[9999] flex flex-col bg-white dark:bg-slate-900 group/expanded">
        {/* Top bar: close button - always visible in fullscreen */}
        <div className="fixed top-4 right-4 z-[10000]">
          <Button
            onClick={handleToggleExpanded}
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0 rounded-full shadow-none border-0 bg-white/40 dark:bg-gray-800/40 backdrop-blur-sm hover:bg-white/90 dark:hover:bg-gray-800/90 opacity-50 hover:opacity-100 transition-all duration-200"
            title="Close (Escape)"
          >
            <X className="h-4 w-4" />
          </Button>
        </div>

        {/* Content - full viewport iframe (the pulsing running border is
            rendered inside iframeContent so it traces this area while executing) */}
        <div className="flex-1 w-full h-full overflow-y-auto relative">
          {iframeContent}
        </div>

        {/* Floating pill toolbar - bottom center (collapsed/expanded toggle) */}
        {(hasActions || carouselControls) && (
          <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-[10000]">
            {toolbarOpen ? (
              <InterfaceToolbar
                currentPage={currentPage}
                totalPages={activeVariablePage ? 0 : effectiveSpawnPages}
                pageLabel={activeVariablePage ? undefined : itemPageLabel}
                pageBadge={reExecutionBadge}
                onPrevious={handlePrevious}
                onNext={handleNext}
                onFullscreen={handleToggleExpanded}
                isFullscreen
                variant="light"
                leadingControls={carouselControls}
                extraControls={toolbarExtraControls}
                onClose={() => setToolbarOpen(false)}
              />
            ) : (
              <button
                type="button"
                onClick={() => setToolbarOpen(true)}
                aria-label={openControlsLabel}
                title={openControlsLabel}
                data-testid="application-controls-toggle"
                className="h-8 w-8 rounded-full flex items-center justify-center bg-white/40 dark:bg-gray-800/40 backdrop-blur-sm text-gray-400 dark:text-gray-500 hover:bg-white/90 dark:hover:bg-gray-800/90 hover:text-gray-700 dark:hover:text-gray-200 opacity-50 hover:opacity-100 transition-all duration-200"
              >
                <Grip className="h-3.5 w-3.5" />
              </button>
            )}
          </div>
        )}

        {/* Preview mode toast (fullscreen) */}
        {showPreviewToast && (
          <div className="fixed inset-x-0 top-16 z-[10001] flex justify-center pointer-events-none animate-in fade-in slide-in-from-top-2 duration-300">
            <div className="pointer-events-auto flex items-start gap-3 max-w-sm w-full mx-4 px-4 py-3 rounded-xl bg-amber-50 dark:bg-amber-950/60 border border-amber-200 dark:border-amber-800 shadow-lg">
              <Lock className="h-5 w-5 text-amber-600 dark:text-amber-400 flex-shrink-0 mt-0.5" />
              <div className="flex-1 min-w-0">
                <p className="text-sm font-semibold text-amber-900 dark:text-amber-200">
                  {t('previewModeTitle')}
                </p>
                <p className="text-xs text-amber-700 dark:text-amber-400 mt-0.5">
                  {t('previewModeMessage')}
                </p>
              </div>
              <button
                type="button"
                onClick={() => setShowPreviewToast(false)}
                className="pointer-events-auto flex-shrink-0 text-amber-400 hover:text-amber-600 dark:hover:text-amber-300"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            </div>
          </div>
        )}
        {/* Multi-trigger panel (fullscreen mode mirror - see normal-mode return). */}
        {runId && (
          <TriggerPanel
            isOpen={isTriggerPanelOpen}
            onClose={() => setIsTriggerPanelOpen(false)}
            runId={runId}
            workflowId={workflowId}
            triggerConfigs={launchable.panelConfigs}
            onExecuteTrigger={handlePanelExecuteTrigger}
            onTriggerSuccess={() => setIsTriggerPanelOpen(false)}
            anchorElement={appContainerEl}
          />
        )}
      </div>,
      document.body,
    );
  }

  // ── Normal (panel) mode ──
  return (
    <div ref={setAppContainerEl} className="flex-1 flex flex-col min-h-0 relative">
      {/* Interface iframe - full bleed, no padding. While the run is executing,
          iframeContent overlays a pulsing blue border (app stays visible). */}
      <div
        className="flex-1 min-h-0 overflow-hidden"
        style={isDragging ? { pointerEvents: 'none' } : undefined}
      >
        <div className="w-full h-full relative">
          {iframeContent}
        </div>
      </div>

      {/* Error banner */}
      {actionError && (
        <div className="flex-shrink-0 w-full px-2">
          <div className="flex items-start gap-2 px-3 py-2 bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-800 rounded-md text-sm text-red-700 dark:text-red-400">
            <AlertCircle className="h-4 w-4 flex-shrink-0 mt-0.5" />
            <span className="flex-1 break-words">{actionError}</span>
            <button
              type="button"
              onClick={() => setActionError(null)}
              className="flex-shrink-0 text-red-400 hover:text-red-600 dark:hover:text-red-300"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>
      )}

      {/* Floating pill toolbar: collapsed/expanded toggle.
          Preview mode portals the block to document.body so it escapes
          the marketplace shell's `overflow-hidden` clipping (the root
          bug). Position is COMPUTED from the panel's bounding rect so
          the toolbar lands at the bottom-center of the preview PANEL,
          not the viewport - a viewport-centered toolbar would dock under
          the app shell's left sidebar on the marketplace page (~250px
          inset). Same z-index as the fullscreen toolbar so it floats
          above any publisher-supplied iframe content.
          Non-preview surfaces (workflow side-panel) keep the in-
          container `absolute bottom-4` positioning that respects the
          side-panel layout.
          Fullscreen (onFullscreen) IS available in preview: the expanded
          view is its own `fixed inset-0` portal to document.body, so it
          overlays the whole viewport (escaping the marketplace shell) and
          `isExpanded` toggles through the carousel-lifted state. */}
      {(hasActions || carouselControls) && (() => {
        const toolbarBlock = toolbarOpen ? (
          <InterfaceToolbar
            currentPage={currentPage}
            totalPages={activeVariablePage ? 0 : effectiveSpawnPages}
            pageLabel={activeVariablePage ? undefined : itemPageLabel}
            pageBadge={reExecutionBadge}
            onPrevious={handlePrevious}
            onNext={handleNext}
            onFullscreen={htmlTemplate ? handleToggleExpanded : undefined}
            variant="light"
            leadingControls={carouselControls}
            extraControls={toolbarExtraControls}
            onClose={() => setToolbarOpen(false)}
          />
        ) : (
          <button
            type="button"
            onClick={() => setToolbarOpen(true)}
            aria-label={openControlsLabel}
            title={openControlsLabel}
            data-testid="application-controls-toggle"
            className="h-8 w-8 rounded-full flex items-center justify-center bg-white/40 dark:bg-gray-800/40 backdrop-blur-sm text-gray-400 dark:text-gray-500 hover:bg-white/90 dark:hover:bg-gray-800/90 hover:text-gray-700 dark:hover:text-gray-200 opacity-50 hover:opacity-100 transition-all duration-200"
          >
            <Grip className="h-3.5 w-3.5" />
          </button>
        );
        if (previewMode) {
          // Wait for the container rect before mounting - without it the
          // portal would flash at the top-left corner for one frame.
          if (!containerRect) return null;
          // Translate "bottom-4 left-1/2" of the container into fixed-position
          // coordinates so the toolbar lands at the same visual spot it would
          // have if we hadn't portalled it. `bottom` is computed from the
          // viewport bottom so the toolbar tracks the container as it scrolls.
          const TOOLBAR_INSET_PX = 16; // matches the old `bottom-4` Tailwind utility
          return createPortal(
            <div
              style={{
                position: 'fixed',
                bottom: Math.max(0, window.innerHeight - containerRect.bottom + TOOLBAR_INSET_PX),
                left: containerRect.left + containerRect.width / 2,
                transform: 'translateX(-50%)',
                zIndex: 10000,
              }}
            >
              {toolbarBlock}
            </div>,
            document.body,
          );
        }
        return (
          <div className="absolute bottom-4 left-1/2 -translate-x-1/2 z-30">
            {toolbarBlock}
          </div>
        );
      })()}

      {/* Preview mode toast - shown when user tries to trigger an action */}
      {showPreviewToast && (
        <div className="absolute inset-x-0 top-4 z-50 flex justify-center pointer-events-none animate-in fade-in slide-in-from-top-2 duration-300">
          <div className="pointer-events-auto flex items-start gap-3 max-w-sm w-full mx-4 px-4 py-3 rounded-xl bg-amber-50 dark:bg-amber-950/60 border border-amber-200 dark:border-amber-800 shadow-lg">
            <Lock className="h-5 w-5 text-amber-600 dark:text-amber-400 flex-shrink-0 mt-0.5" />
            <div className="flex-1 min-w-0">
              <p className="text-sm font-semibold text-amber-900 dark:text-amber-200">
                {t('previewModeTitle')}
              </p>
              <p className="text-xs text-amber-700 dark:text-amber-400 mt-0.5">
                {t('previewModeMessage')}
              </p>
            </div>
            <button
              type="button"
              onClick={() => setShowPreviewToast(false)}
              className="pointer-events-auto flex-shrink-0 text-amber-400 hover:text-amber-600 dark:hover:text-amber-300"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>
      )}
      {/* Multi-trigger panel - opens from the toolbar Launch button. Mounts
          self-contained (floating, draggable) so it works anywhere this
          component renders: /app/applications/<id>, the visualize-card popup
          panel, and /s/<token> share pages (no WorkflowPanelContent
          dependency). chat/form/webhook tabs are auto-built from
          launchable.panelConfigs (sourced from the canvas dispatch). */}
      {runId && (
        <TriggerPanel
          isOpen={isTriggerPanelOpen}
          onClose={() => setIsTriggerPanelOpen(false)}
          runId={runId}
          workflowId={workflowId}
          triggerConfigs={launchable.panelConfigs}
          onExecuteTrigger={handlePanelExecuteTrigger}
          onTriggerSuccess={() => setIsTriggerPanelOpen(false)}
          anchorElement={appContainerEl}
        />
      )}
    </div>
  );
}

// ── Plan-derived TriggerPanelConfig builder ──
//
// Mirrors the canvas-side WorkflowBuilder.triggerPanelConfigs builder, but
// uses raw plan triggers only (no live node data, no backend-availability
// filter). This is the fallback used when no canvas is mounted: visualize
// popup card in agent chat, /s/<token> share pages. Live canvas dispatch
// (when present) takes precedence over this.
export function buildPanelConfigsFromPlan(
  planTriggers: Array<{ id: string; label?: string; type?: string; params?: Record<string, unknown> }>,
): TriggerPanelConfig[] {
  const out: TriggerPanelConfig[] = [];
  for (const t of planTriggers) {
    const type = (t.type || '').toLowerCase();
    if (type !== 'chat' && type !== 'form' && type !== 'webhook') continue;
    const triggerId = triggerKey(t.label || '') || `trigger:${t.id}`;
    const triggerLabel = t.label || (type === 'chat' ? 'Chat' : type === 'webhook' ? 'Webhook' : 'Form');
    const params = (t.params ?? {}) as Record<string, unknown>;

    if (type === 'chat') {
      out.push({ triggerId, triggerLabel, type: 'chat' });
      continue;
    }
    if (type === 'webhook') {
      const headers = params.headers;
      const body = params.body;
      out.push({
        triggerId,
        triggerLabel,
        type: 'webhook',
        webhookMethod: (params.method as 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE') || 'POST',
        webhookUrlPreview: (params.url as string) || (params.path as string) || '',
        webhookDefaultHeaders: typeof headers === 'string'
          ? headers
          : headers
            ? JSON.stringify(headers, null, 2)
            : '{\n  "Content-Type": "application/json"\n}',
        webhookDefaultBody: typeof body === 'string'
          ? body
          : body
            ? JSON.stringify(body, null, 2)
            : '{\n  \n}',
      });
      continue;
    }
    // form
    const rawFields = Array.isArray(params.fields) ? (params.fields as Array<Record<string, unknown>>) : [];
    out.push({
      triggerId,
      triggerLabel,
      type: 'form',
      formTitle: (params.formTitle as string) || triggerLabel,
      formDescription: (params.formDescription as string) || '',
      submitButtonText: (params.submitButtonText as string) || 'Submit',
      fields: rawFields.map((f, idx) => ({
        id: (f.id as string) || `field-${idx}`,
        name: (f.name as string) || (f.id as string) || `field-${idx}`,
        label: (f.label as string) || (f.name as string) || `Field ${idx + 1}`,
        type: (f.type as string) || 'text',
        placeholder: (f.placeholder as string) || '',
        required: !!f.required,
        options: (f.options as Array<{ label: string; value: string }>) || [],
        accept: (f.accept as string) || '',
      })),
    });
  }
  return out;
}

// ── Helpers (same as WorkflowModeToggle EpochSelector) ──

function formatEpochTime(isoString: string): string {
  return formatUtcTime(isoString, { withSeconds: true });
}

function formatEpochDuration(ms: number): string {
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

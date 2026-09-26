'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { Node } from 'reactflow';
import { useTranslations, useLocale } from 'next-intl';
import { CheckCircle2, XCircle, Loader2, CircleSlash, PauseCircle, List, GanttChart, Coins } from 'lucide-react';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { formatCostCompact } from '@/lib/format-cost';
import { orchestratorApi } from '@/lib/api';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';
import { parseUtcAware, formatRelativeDateI18n } from '@/lib/utils/dateFormatters';
import { getIconSlug, NodeIcon, nodeIconRadiusClass } from '@/app/workflows/builder/components/nodes/shared';
import { nodeMatchesStep } from '@/app/workflows/builder/services/nodeMatcher';
import { findNodeClassById } from '@/app/workflows/builder/nodes/nodeClasses';
import { getCanvasEdges, getCanvasNodes, subscribeCanvasNodes } from '@/app/workflows/builder/services/canvasNodesStore';
import { nodeRegistry } from '@/app/workflows/builder/registry/nodeRegistry';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { StepRowActions } from '@/components/workflow/StepRowActions';
import { EpochSelector } from './EpochSelector';
import { StepTooltipContent } from './StepTooltipContent';
import { WaterfallView } from './WaterfallView';
import { formatCompactDuration, stepDisplayDurationMs, type EpochTimestamp, type StepEntry } from './runFormatting';
import { computeDagOrder, sortByDagOrder } from '@/lib/workflow/dagStepOrder';
import { runCostGaugeState } from '@/components/budget/budgetPeriod';
import { BudgetChip } from '@/components/budget/BudgetChip';

export interface RunStepsPanelProps {
  /** The run being inspected (needs runId/id, costCredits, budgetCredits, costByEpoch). */
  currentRunInfo: {
    runId?: string;
    id?: string;
    /** Raw run status - tells an OPEN epoch apart from a merely unclosed one. */
    status?: string;
    costCredits?: number | null;
    budgetCredits?: number | null;
    costByEpoch?: Record<string, number>;
    /** Production spend in the current budget period - the figure the cap is
     *  compared against. Null for an editor run, which never counts against it. */
    periodSpentCredits?: number | null;
    budgetPeriodResetsAt?: string | null;
    /** monthly | weekly | cumulative - names the period above. */
    budgetPeriodMode?: string | null;
  } | null;
  /** Aggregated steps streamed over WebSocket (all epochs). undefined = still loading. */
  streamedSteps?: StepEntry[];
  epochTimestamps: EpochTimestamp[];
  /** null = "All epochs"; a number = that epoch only (REST-fetched). */
  selectedEpoch: number | null;
  onSelectEpoch: (epoch: number | null) => void;
  workflowId?: string;
  isStepByStep?: boolean;
  isRunActive?: boolean;
}

/**
 * The expanded run body: status filter + view toggle, epoch selector, the step
 * list (list or waterfall) and the run cost.
 *
 * Shared by the canvas run bar and the side-panel Run tab so both stay visually
 * identical - only the surrounding chrome differs.
 */
export function RunStepsPanel({
  currentRunInfo,
  streamedSteps,
  epochTimestamps,
  selectedEpoch,
  onSelectEpoch,
  workflowId,
  isStepByStep = false,
  isRunActive = false,
}: RunStepsPanelProps) {
  const t = useTranslations();
  // The filter pills are icons only: their tooltip is the status NAME, which has to
  // be translated like every other status label (it was rendering a raw
  // 'Awaiting_signal' inherited from the pre-move code).
  const tStatus = useTranslations('status');
  const locale = useLocale();
  const formatRel = useCallback(
    (d: string | Date | null | undefined) =>
      formatRelativeDateI18n(d, (k, p) => t(`runs.${k}`, p), locale),
    [t, locale],
  );

  const [stepsView, setStepsView] = useState<'list' | 'waterfall'>('list');
  /** Status filter for steps: null = all, string = filter by that status */
  const [statusFilter, setStatusFilter] = useState<string | null>(null);
  /** Per-epoch REST-fetched steps (only used when selectedEpoch != null) */
  const [epochFetchedSteps, setEpochFetchedSteps] = useState<StepEntry[]>([]);
  const [stepsLoading, setStepsLoading] = useState(false);

  const runId = currentRunInfo?.runId || currentRunInfo?.id;

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
  // endpoint when getActivePublicPreview() matches this runId, so the panel
  // populates its step list for anonymous visitors exactly like authenticated owners.
  //
  // Generation counter to discard out-of-order responses: without it, rapid epoch
  // clicks A→B fire parallel fetches; if A resolves after B, A's data overwrites
  // B's data while selectedEpoch=B → stale.
  const fetchGenerationRef = useRef(0);
  const fetchEpochSteps = useCallback(async (epoch: number) => {
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
        setEpochFetchedSteps(data as StepEntry[]);
      }
    } catch (err) {
      console.error('[RunStepsPanel] Failed to fetch epoch steps:', err);
    } finally {
      // Only the latest in-flight clears the loading state - earlier ones bail.
      if (myGeneration === fetchGenerationRef.current) {
        setStepsLoading(false);
      }
    }
  }, [runId]);

  // When the user selects a specific epoch, fetch from REST (per-epoch data).
  // The SYNCHRONOUS clear is gated on an actual epoch CHANGE so an unrelated
  // re-render while parked on an epoch doesn't blank the panel.
  const prevSelectedEpochRef = useRef<number | null | undefined>(undefined);
  useEffect(() => {
    if (selectedEpoch != null) {
      const epochChanged = prevSelectedEpochRef.current !== selectedEpoch;
      if (epochChanged) {
        // Clear stale per-epoch data SYNCHRONOUSLY before the new fetch resolves.
        // Without this, the panel shows the previous epoch's counts until the
        // fetch completes.
        setEpochFetchedSteps([]);
      }
      fetchEpochSteps(selectedEpoch);
    }
    prevSelectedEpochRef.current = selectedEpoch;
  }, [selectedEpoch, fetchEpochSteps]);

  // Re-fetch the selected epoch as the live run pushes new step data.
  // `streamedSteps` only carries the all-epochs aggregate, so a per-epoch view
  // would otherwise freeze on the snapshot taken when the epoch was selected.
  //
  // THROTTLED, not debounced: a live run pushes step batches several times a
  // second, and a plain debounce is reset by every push - on the very run this
  // exists for, the trailing edge never arrives and the panel freezes on the
  // snapshot taken when the epoch was selected. The delay shrinks with the time
  // already elapsed, so a continuous stream still refreshes about once a second
  // while a quiet run costs one call.
  //
  // `selectedEpoch` is a dependency so switching epochs CANCELS the pending
  // refresh: the timer closes over the epoch it was scheduled for, and firing it
  // after a switch loaded the previous epoch's rows under the new epoch's header
  // (the generation guard cannot help - the stale call is the newest one).
  const lastStreamRefetchRef = useRef(0);
  useEffect(() => {
    if (selectedEpoch == null || streamedSteps === undefined) return;
    const epoch = selectedEpoch;
    const delay = Math.max(0, 800 - (Date.now() - lastStreamRefetchRef.current));
    const timer = setTimeout(() => {
      lastStreamRefetchRef.current = Date.now();
      fetchEpochSteps(epoch);
    }, delay);
    return () => clearTimeout(timer);
  }, [streamedSteps, selectedEpoch, fetchEpochSteps]);

  // Reset per-epoch data when the run changes.
  useEffect(() => {
    setEpochFetchedSteps([]);
    prevSelectedEpochRef.current = undefined;
  }, [runId]);

  // ── Canvas graph subscription ──
  //
  // Read from the module-level canvas store: this panel lives in the side panel,
  // a different React tree from the builder, so there is no ref to thread down.
  // Scoped by workflowId - with a sub-workflow canvas also mounted, the unscoped
  // read would label these steps with the other canvas's nodes.
  //
  // Subscribed to the store rather than to a "node created" window event: the
  // panel can mount BEFORE the canvas publishes (opened straight on the Run tab),
  // where no creation event is ever coming and the list would stay empty for
  // good, and a rename or a deletion has to reach it too.
  const [canvasNodesTick, setCanvasNodesTick] = useState(0);
  useEffect(() => subscribeCanvasNodes(() => setCanvasNodesTick(prev => prev + 1)), []);

  // Reading order for the step list: triggers first, each branch's terminal node
  // last, the way the DAG executes. Rebuilt only when the canvas publishes, not
  // per step and not per render - the step list re-renders on every WebSocket
  // push while the graph itself barely changes.
  const dagOrder = useMemo(
    () => computeDagOrder(getCanvasNodes(workflowId), getCanvasEdges(workflowId)),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- canvasNodesTick is the store's change signal
    [canvasNodesTick, workflowId],
  );

  // Resolve every step's canvas node ONCE per data change (icon, label and DAG
  // rank all need it). The previous per-call `nodes.find` ran once per step per
  // render in the list AND again in the waterfall, i.e. O(steps x nodes) on
  // every push; this is a single pass that both views then read by key.
  const nodeByStepAlias = useMemo(() => {
    const nodes = getCanvasNodes(workflowId);
    const map = new Map<string, Node<BuilderNodeData> | undefined>();
    if (!nodes.length) return map;
    for (const step of aggregatedSteps) {
      if (map.has(step.alias)) continue;
      map.set(step.alias, nodes.find((n) => nodeMatchesStep(n, { stepAlias: step.alias, id: step.alias })));
    }
    return map;
    // eslint-disable-next-line react-hooks/exhaustive-deps -- canvasNodesTick is the store's change signal
  }, [aggregatedSteps, canvasNodesTick, workflowId]);

  const findNodeForStep = useCallback((stepAlias: string): Node<BuilderNodeData> | undefined => {
    if (nodeByStepAlias.has(stepAlias)) return nodeByStepAlias.get(stepAlias);
    // A caller outside `aggregatedSteps` (never happens today, but the prop is
    // public) still gets an answer rather than a missing icon.
    return getCanvasNodes(workflowId).find((n) => nodeMatchesStep(n, { stepAlias, id: stepAlias }));
  }, [nodeByStepAlias, workflowId]);

  // ── Nodes to show when the run has produced NO step yet ──
  // A workflow that has never fired still has a shape: listing its nodes (greyed,
  // no counts, no timing) is far more useful than an empty "no steps yet" box -
  // the user sees what WILL run and can still click through to a node.
  //
  // Ordered by the same DAG rank as the executed steps, so the preview reads as
  // the run it is standing in for instead of flipping order the moment the first
  // step lands.
  const previewNodes = useMemo(() => {
    if (aggregatedSteps.length > 0) return [];
    const nodes = getCanvasNodes(workflowId)
      // Notes are annotations, never executed - they are not steps-to-be.
      .filter((n) => !nodeRegistry.isNoteNode(n));
    return sortByDagOrder(nodes, dagOrder, (n) => n.id)
      .map((n) => ({
        node: n,
        label: n.data?.label || n.data?.id || n.id,
        nodeClass: n.data ? findNodeClassById(n.data.id || '') : null,
      }));
    // eslint-disable-next-line react-hooks/exhaustive-deps -- canvasNodesTick is the store's change signal
  }, [aggregatedSteps.length, canvasNodesTick, workflowId, dagOrder]);

  // Filter steps by selected status, then put them in DAG reading order.
  //
  // The aggregate arrives in execution order (WebSocket push order for "all
  // epochs", row order for a single one), which scatters as soon as branches run
  // in parallel or an epoch re-fires only part of the graph. Sorting here covers
  // BOTH views - the list and the waterfall both render `filteredSteps`.
  //
  // Steps the canvas cannot resolve (node deleted since the run) keep their
  // incoming order at the end: never dropped, never interleaved at random.
  const filteredSteps = useMemo(() => {
    const matching = !statusFilter
      ? aggregatedSteps
      : (() => {
          // Map filter key to statusCounts field (awaiting_signal → awaitingSignal)
          const countsKey = statusFilter === 'awaiting_signal' ? 'awaitingSignal' : statusFilter;
          return aggregatedSteps.filter(s => {
            if (s.status === statusFilter) return true;
            if (s.statusCounts && (s.statusCounts as Record<string, number | undefined>)[countsKey]! > 0) return true;
            return false;
          });
        })();
    return sortByDagOrder(matching, dagOrder, (s) => nodeByStepAlias.get(s.alias)?.id);
  }, [aggregatedSteps, statusFilter, dagOrder, nodeByStepAlias]);

  return (
    <>
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
                  title={f.key ? tStatus(f.key) : t('workflow.runSteps.filterAll')}
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
            onSelectEpoch={onSelectEpoch}
            viewMode={stepsView}
            runStatus={currentRunInfo?.status}
          />
        )}

        {isStepsLoading ? (
          /* Skeleton rows mirror the REAL step row geometry exactly - same
             `px-3 py-1 gap-1.5`, same `w-6` icon column, and placeholder bars
             sized to the text they stand in for (`text-sm` label, `text-xs`
             trailing meta). Anything smaller made the list visibly shrink and
             re-flow the moment the real steps landed. */
          <div className="py-0.5" aria-busy="true">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="flex items-center gap-1.5 px-3 py-1">
                <div className="w-6 shrink-0 flex items-center justify-center">
                  <div className={`h-4 w-4 bg-gray-200 dark:bg-gray-700 ${nodeIconRadiusClass('xs')} animate-pulse`} />
                </div>
                <div className="flex-1 min-w-0 flex items-center h-5">
                  <div
                    className="h-3.5 bg-gray-200 dark:bg-gray-700 rounded animate-pulse"
                    style={{ width: `${[70, 45, 60, 38][i % 4]}%` }}
                  />
                </div>
                <div className="flex items-center h-5 flex-shrink-0">
                  <div className="h-3 w-20 bg-gray-200 dark:bg-gray-700 rounded animate-pulse" />
                </div>
              </div>
            ))}
          </div>
        ) : filteredSteps.length === 0 && !statusFilter && previewNodes.length > 0 ? (
          /* Nothing has run yet: show the workflow's nodes as they will appear
             once they execute - same row, no counts, no timing. */
          <div className="py-0.5" data-run-steps-preview>
            {previewNodes.map(({ node, label, nodeClass }) => (
              <div
                key={node.id}
                className="flex items-center gap-1.5 px-3 py-1 cursor-pointer hover:bg-gray-100/60 dark:hover:bg-gray-700/40 transition-colors"
                onClick={(e) => {
                  e.stopPropagation();
                  window.dispatchEvent(new CustomEvent('workflowFocusNode', {
                    detail: { stepAlias: node.id },
                  }));
                }}
              >
                <div className="w-6 shrink-0 flex items-center justify-center opacity-60">
                  {node.data ? (
                    <NodeIcon
                      iconSlug={getIconSlug(node.data)}
                      nodeId={node.data.id || ''}
                      nodeKind={node.data.kind}
                      nodeFamily={nodeClass?.family}
                      avatarUrl={(node.data as any)?.agentAvatarUrl}
                      size="xs"
                    />
                  ) : (
                    <div className={`h-4 w-4 ${nodeIconRadiusClass('xs')} bg-gray-100 dark:bg-gray-700`} />
                  )}
                </div>
                <span className="flex-1 min-w-0 text-sm font-medium text-gray-500 dark:text-gray-400 truncate">{label}</span>
                <span className="text-xs text-gray-400 dark:text-gray-500 whitespace-nowrap flex-shrink-0">
                  {t('workflow.runSteps.notRunYet')}
                </span>
              </div>
            ))}
          </div>
        ) : filteredSteps.length === 0 ? (
          <div className="p-4 text-center text-xs text-gray-400 dark:text-gray-500">
            {t('workflow.runSteps.noStepsYet')}
          </div>
        ) : stepsView === 'list' ? (
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
                      // Row identity, in render order: what the DAG ordering is
                      // asserted on end-to-end (the visible label is the node's,
                      // which several nodes may share).
                      data-run-step-row={step.alias}
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
                          <div className={`h-4 w-4 ${nodeIconRadiusClass('xs')} bg-gray-100 dark:bg-gray-700`} />
                        )}
                      </div>
                      <span className="flex-1 min-w-0 text-sm font-medium text-gray-900 dark:text-gray-100 truncate">{stepLabel}</span>
                      {/* Status counts from multi-epoch execution */}
                      {(step.statusCounts?.completed ?? 0) > 0 && (
                        <span className="text-xs text-emerald-600 dark:text-emerald-400 flex-shrink-0">✓{step.statusCounts!.completed}</span>
                      )}
                      {(step.statusCounts?.failed ?? 0) > 0 && (
                        <span className="text-xs text-red-600 dark:text-red-400 flex-shrink-0">✗{step.statusCounts!.failed}</span>
                      )}
                      {(step.statusCounts?.skipped ?? 0) > 0 && (
                        <span className="text-xs text-gray-500 dark:text-gray-400 flex-shrink-0">⊘{step.statusCounts!.skipped}</span>
                      )}
                      {(step.statusCounts?.running ?? 0) > 0 && (
                        <span className="inline-flex items-center gap-1 text-xs text-blue-600 dark:text-blue-400 flex-shrink-0">
                          {/* Same blue pulse as the epoch row (not a spinning refresh icon) */}
                          <span className="relative flex h-1.5 w-1.5">
                            <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75" />
                            <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-blue-500" />
                          </span>
                          {step.statusCounts!.running}
                        </span>
                      )}
                      {(step.statusCounts?.awaitingSignal ?? 0) > 0 && (
                        <span className="inline-flex items-center gap-px text-xs text-amber-500 dark:text-amber-400 flex-shrink-0">
                          <PauseCircle className="w-2.5 h-2.5" />{step.statusCounts!.awaitingSignal}
                        </span>
                      )}
                      {/* Live status indicators (when no terminal counts yet) */}
                      {step.status === 'running' && !(step.statusCounts?.running) && (
                        <span className="relative flex h-1.5 w-1.5 flex-shrink-0" aria-label="running">
                          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75" />
                          <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-blue-500" />
                        </span>
                      )}
                      {step.status === 'awaiting_signal' && !(step.statusCounts?.awaitingSignal) && (
                        <PauseCircle className="w-3 h-3 text-amber-500 dark:text-amber-400 flex-shrink-0" />
                      )}
                      {step.startTime && (
                        <span className="text-xs text-gray-500 dark:text-gray-400 flex-shrink-0 whitespace-nowrap">
                          {formatRel(step.startTime)}
                          {(() => {
                            // Same figure as the waterfall gauge and the tooltip, by construction.
                            const ms = stepDisplayDurationMs(step, selectedEpoch == null);
                            return ms != null ? ` · ${formatCompactDuration(ms)}` : '';
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
      </div>
      </TooltipProvider>

      {/* Cost - a gray token icon then the price, bottom-right. Same size +
          colour as the node status-count/duration text above (text-[10px]
          gray); the icon stays gray even when the price turns red. Compact
          K/M rounding. Epoch-aware: a selected epoch shows that epoch's cost,
          "All" shows the run total; over the run budget it turns red. */}
      {currentRunInfo?.costCredits != null && (() => {
        const total = currentRunInfo.costCredits ?? 0;
        const budget = currentRunInfo.budgetCredits ?? null;
        const viewingEpoch = selectedEpoch != null;
        const shown = viewingEpoch
          ? (currentRunInfo.costByEpoch?.[String(selectedEpoch)] ?? 0)
          : total;
        // The cap is compared against the PERIOD spend, never this run's
        // lifetime cost: a pinned workflow keeps one run for months, so its
        // total passes the cap long before the period does. Painting the
        // total red against the cap would announce a stop that has not
        // happened. An editor run sends no period figure (it does not count
        // against the cap), so it simply shows its cost with no gauge.
        const periodSpent = currentRunInfo.periodSpentCredits ?? null;
        // The gauge draws itself and owns its own colour; the bar only decides
        // WHETHER it belongs here (a cap exists, and we are not looking at a
        // single epoch, where a period total next to an epoch cost would invite
        // a comparison between two unrelated things).
        const { showGauge } = runCostGaugeState({
          budgetCredits: budget,
          periodSpentCredits: periodSpent,
          viewingEpoch,
        });
        return (
          <div className="flex-shrink-0 flex justify-end px-3 py-1.5">
            <span className="inline-flex items-center gap-1 text-xs tabular-nums whitespace-nowrap">
              <Coins className="h-3 w-3 flex-shrink-0 text-gray-400 dark:text-gray-500" aria-hidden />
              <span className="text-gray-500 dark:text-gray-400" title={t('budget.runCostTitle')}>
                {formatCostCompact(shown)}
              </span>
              {showGauge && (
                <>
                  <span className="text-gray-400 dark:text-gray-500" aria-hidden="true">·</span>
                  <BudgetChip
                    spent={periodSpent as number}
                    cap={budget as number}
                    periodMode={currentRunInfo.budgetPeriodMode}
                    resetsAt={currentRunInfo.budgetPeriodResetsAt}
                    // The coin two spans to the left already labels this
                    // cluster as money; a second one between the run cost and
                    // the period spend would read as a different unit.
                    showIcon={false}
                  />
                </>
              )}
            </span>
          </div>
        );
      })()}
    </>
  );
}

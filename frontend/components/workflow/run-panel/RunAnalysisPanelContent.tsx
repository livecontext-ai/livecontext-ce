'use client';

import { memo, useCallback, useEffect, useMemo, useState } from 'react';
import type { Node } from 'reactflow';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import {
  AlertTriangle, ArrowLeft, CheckCircle2, CircleSlash, Crosshair, Loader2, PauseCircle, Play, RefreshCw,
  TrendingUp, XCircle,
} from 'lucide-react';
import { Bar, CartesianGrid, Cell, ComposedChart, Line, ResponsiveContainer, Tooltip as ChartTooltip, XAxis, YAxis } from 'recharts';
import { orchestratorApi } from '@/lib/api';
import type { RunAnalysisEpoch, RunAnalysisNodeCell } from '@/lib/api/orchestrator/types';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import { formatCostCompact } from '@/lib/format-cost';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';
import { getIconSlug, NodeIcon, nodeIconRadiusClass } from '@/app/workflows/builder/components/nodes/shared';
import { nodeMatchesStep } from '@/app/workflows/builder/services/nodeMatcher';
import { findNodeClassById } from '@/app/workflows/builder/nodes/nodeClasses';
import { getCanvasEdges, getCanvasNodes, subscribeCanvasNodes } from '@/app/workflows/builder/services/canvasNodesStore';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import { computeDagOrder, sortByDagOrder } from '@/lib/workflow/dagStepOrder';
import { RunSummaryBar } from './RunSummaryBar';
import { getCachedRunPanelData, subscribeRunPanelData, type RunPanelData } from './runPanelBus';
import { formatCompactDuration } from './runFormatting';
import { markEpochPickedByUser } from './useDefaultEpochSelection';
import {
  buildNodeRows, cellStatus, compareEpochs, computeInsights, computeKpis, defaultComparison, epochOutcome,
  heatLevel, referenceFor, type CellStatus, type NodeRow,
} from './runAnalysis';

/** Epoch windows the user can pick; the server caps at 200. */
const WINDOWS = [30, 60, 200] as const;
const DEFAULT_WINDOW = 60;

const CELL_CLASS: Record<CellStatus, string> = {
  ok: 'bg-emerald-500/80',
  failed: 'bg-red-500/80',
  partial: 'bg-orange-400',
  skipped: 'bg-gray-300 dark:bg-gray-600',
  running: 'bg-blue-500 animate-pulse',
  waiting: 'bg-amber-300 dark:bg-amber-500/60',
  none: 'bg-gray-100 dark:bg-white/[0.05]',
};
/** One hue for duration, so a slow cell never reads as a failed (red) one. */
const HEAT_CLASS = [
  'bg-indigo-100 dark:bg-indigo-950',
  'bg-indigo-200 dark:bg-indigo-800',
  'bg-indigo-400 dark:bg-indigo-600',
  'bg-indigo-600 dark:bg-indigo-400',
  'bg-indigo-800 dark:bg-indigo-200',
] as const;
const BAR_COLOR: Record<string, string> = { COMPLETED: '#10b981', FAILED: '#ef4444', RUNNING: '#3b82f6' };
const BAR_COLOR_OTHER = '#9ca3af';
const COST_COLOR = '#8b5cf6';

export interface RunAnalysisPanelContentProps {
  workflowId: string;
  runId: string;
  surfaceId?: string;
  /** Back to the Run tab (the header's left arrow). */
  onBack: () => void;
}

/**
 * The Analysis tab of the workflow panel: how the run's recent epochs went, at a glance.
 *
 * KPIs, a duration/cost bar per epoch, a nodes x epochs grid (by status or by duration) and a
 * side-by-side comparison of two epochs. All of it comes from ONE call
 * (`orchestratorApi.getRunAnalysis`) and is refetched whenever the run gains an epoch.
 */
export function RunAnalysisPanelContent({ workflowId, runId, surfaceId, onBack }: RunAnalysisPanelContentProps) {
  const t = useTranslations();
  const ta = useTranslations('workflow.runAnalysis');
  const auth = useAuthGuard();
  const { setViewingEpoch } = useWorkflowMode();

  // Run identity + epoch count from the canvas bus: the same snapshot the Run tab renders.
  const [panel, setPanel] = useState<RunPanelData>(() => getCachedRunPanelData(workflowId, surfaceId));
  useEffect(() => {
    setPanel(getCachedRunPanelData(workflowId, surfaceId));
    return subscribeRunPanelData(workflowId, setPanel, surfaceId);
  }, [workflowId, surfaceId]);
  const runStatus: string | null = panel.runInfo?.status ?? null;
  const epochCount = panel.epochTimestamps?.length ?? 0;
  // The newest epoch closing does not change the count: its close time and the run status do.
  const lastEpochEndedAt = panel.epochTimestamps?.reduce<string | null>(
    (latest, e) => (e.endedAt && (!latest || e.endedAt > latest) ? e.endedAt : latest), null) ?? null;
  // Executing right now (not merely alive: a schedule parked on WAITING_TRIGGER changes nothing).
  const runExecuting = ['RUNNING', 'PENDING'].includes((runStatus ?? '').toUpperCase());

  const [limit, setLimit] = useState<number>(DEFAULT_WINDOW);
  const query = useQuery({
    // A new fire, an epoch closing or the run changing status refetches; a quiet run costs nothing.
    queryKey: ['run-analysis', runId, limit, epochCount, lastEpochEndedAt, runStatus],
    queryFn: () => orchestratorApi.getRunAnalysis(runId, limit),
    enabled: auth.isReady && auth.isAuthenticated && !!runId,
    placeholderData: keepPreviousData,
    staleTime: 10_000,
    // While the run executes, node cells move without any of the above changing.
    refetchInterval: runExecuting ? 5_000 : false,
  });
  const epochs = useMemo(() => query.data?.epochs ?? [], [query.data]);

  // ── Canvas nodes: labels, icons, DAG order ──
  // The canvas republishes its nodes on every live status change; only a change of what THIS tab
  // reads (ids, labels, edges) may re-render it, or a running workflow redraws the chart and the
  // whole grid several times a second.
  const [canvasNodes, setCanvasNodes] = useState(() => getCanvasNodes(workflowId));
  useEffect(() => {
    let signature = canvasSignature(workflowId);
    setCanvasNodes(getCanvasNodes(workflowId));
    return subscribeCanvasNodes(() => {
      const next = canvasSignature(workflowId);
      if (next === signature) return;
      signature = next;
      setCanvasNodes(getCanvasNodes(workflowId));
    });
  }, [workflowId]);
  const nodeFor = useCallback(
    (alias: string): Node<BuilderNodeData> | undefined =>
      canvasNodes.find(n => nodeMatchesStep(n, { stepAlias: alias, id: alias })),
    [canvasNodes],
  );
  const rows = useMemo(() => {
    const dagOrder = computeDagOrder(canvasNodes, getCanvasEdges(workflowId));
    return sortByDagOrder(buildNodeRows(epochs, runStatus), dagOrder, row => nodeFor(row.alias)?.id);
  }, [epochs, canvasNodes, nodeFor, workflowId, runStatus]);
  const labelFor = useCallback(
    (alias: string) => nodeFor(alias)?.data?.label || alias.replace(/^(mcp|core|agent|trigger|table|interface):/, ''),
    [nodeFor],
  );

  // Stable across polls that change nothing, so the comparison table's memo holds.
  const aliasOrder = useMemo(() => rows.map(r => r.alias), [rows]);
  const kpis = useMemo(() => computeKpis(epochs, query.data?.totalEpochs ?? 0, runStatus), [epochs, query.data, runStatus]);
  const insights = useMemo(() => computeInsights(epochs, rows, runStatus), [epochs, rows, runStatus]);

  // ── Comparison: follows the latest failure until the user picks an epoch ──
  const [pickedTarget, setPickedTarget] = useState<number | null>(null);
  const comparison = useMemo(() => {
    if (pickedTarget != null && epochs.some(e => e.epoch === pickedTarget)) {
      const reference = referenceFor(epochs, pickedTarget, runStatus);
      return reference == null ? null : { reference, target: pickedTarget };
    }
    return defaultComparison(epochs, runStatus);
  }, [epochs, pickedTarget, runStatus]);

  const [colorBy, setColorBy] = useState<'status' | 'duration'>('status');

  const showOnCanvas = useCallback((epoch: number) => {
    markEpochPickedByUser(runId, epoch);
    setViewingEpoch(epoch);
    onBack();
  }, [runId, setViewingEpoch, onBack]);

  const focusNode = useCallback((alias: string) => {
    window.dispatchEvent(new CustomEvent('workflowFocusNode', { detail: { stepAlias: alias } }));
  }, []);

  const epochLabel = (epoch: number) => ta('compare.epoch', { epoch });

  return (
    <div className="flex h-full min-h-0 flex-col bg-theme-primary" data-run-analysis>
      <header className="flex-shrink-0 border-b border-theme">
        <RunSummaryBar
          currentRunInfo={panel.runInfo ?? { runId }}
          epochCount={epochCount}
          size="panel"
          leading={(
            <button
              type="button"
              onClick={onBack}
              data-run-analysis-back
              className="flex h-6 min-w-0 flex-shrink-0 items-center gap-1.5 rounded-lg border border-theme px-1.5 text-sm font-medium text-theme-secondary transition-colors hover:bg-theme-secondary hover:text-theme-primary"
              title={t('workflow.logs.backToRun')}
              aria-label={t('workflow.logs.backToRun')}
            >
              <ArrowLeft className="h-3.5 w-3.5 flex-shrink-0" />
              <Play className="h-3.5 w-3.5 flex-shrink-0" />
              <span className="truncate">{t('sidePanel.runTab')}</span>
            </button>
          )}
          trailing={(
            <button
              type="button"
              onClick={() => query.refetch()}
              title={ta('refresh')}
              aria-label={ta('refresh')}
              className="flex h-7 flex-shrink-0 items-center rounded-lg px-1.5 text-theme-secondary transition-colors hover:bg-theme-secondary hover:text-theme-primary"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${query.isFetching ? 'animate-spin' : ''}`} />
            </button>
          )}
        />
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {query.isLoading && !query.data ? (
          <div className="flex h-40 items-center justify-center gap-2 text-sm text-theme-secondary">
            <Loader2 className="h-4 w-4 animate-spin" /> {ta('loading')}
          </div>
        ) : query.isError && !query.data ? (
          <div className="flex flex-col items-center gap-2 p-6 text-center" role="alert">
            <p className="text-sm text-red-600 dark:text-red-400">{ta('error')}</p>
            <button type="button" onClick={() => query.refetch()} className="rounded-lg border border-theme px-2 py-1 text-sm hover:bg-theme-secondary">
              {ta('retry')}
            </button>
          </div>
        ) : epochs.length === 0 ? (
          <p className="p-6 text-center text-sm text-theme-secondary" data-run-analysis-empty>{ta('empty')}</p>
        ) : (
          <div className="flex flex-col gap-5 px-3 py-3 sm:px-4">
            {/* Window */}
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="inline-flex items-center rounded-md bg-gray-100 p-0.5 dark:bg-gray-700/50" role="group" aria-label={ta('window')}>
                {WINDOWS.map(w => (
                  <button
                    key={w}
                    type="button"
                    aria-pressed={limit === w}
                    onClick={() => setLimit(w)}
                    className={`rounded px-2 py-0.5 text-sm font-medium transition-colors ${
                      limit === w
                        ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-600 dark:text-gray-100'
                        : 'text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200'
                    }`}
                  >
                    {ta('windowOption', { count: w })}
                  </button>
                ))}
              </div>
              <span className="text-sm text-theme-secondary tabular-nums">
                {ta('windowOf', { shown: kpis.shown, total: kpis.total })}
              </span>
            </div>

            {/* KPIs */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-px overflow-hidden rounded-lg border border-theme bg-[var(--border-color)]" data-run-analysis-kpis>
              <Kpi label={ta('kpi.epochs')} value={String(kpis.shown)} />
              <Kpi
                label={ta('kpi.successRate')}
                value={kpis.successRate != null ? `${kpis.successRate}%` : '-'}
                sub={kpis.successRate != null ? ta('kpi.failedCount', { count: kpis.failed }) : ta('kpi.noSettled')}
                tone={kpis.successRate == null ? undefined : kpis.successRate >= 90 ? 'good' : 'bad'}
              />
              <Kpi
                label={ta('kpi.avgDuration')}
                value={kpis.avgDurationMs != null ? formatCompactDuration(kpis.avgDurationMs) : '-'}
                sub={kpis.maxDurationMs != null ? ta('kpi.maxDuration', { value: formatCompactDuration(kpis.maxDurationMs) }) : undefined}
              />
              <Kpi
                label={ta('kpi.credits')}
                value={kpis.totalCost != null ? formatCostCompact(kpis.totalCost) : '-'}
                sub={kpis.avgCost != null ? ta('kpi.perEpoch', { value: formatCostCompact(kpis.avgCost) }) : ta('kpi.noCost')}
              />
            </div>

            {/* Duration / cost per epoch */}
            <section className="flex flex-col gap-2">
              <h3 className="text-sm font-semibold text-theme-primary">{ta('chart.title')}</h3>
              <EpochChart
                epochs={epochs}
                runStatus={runStatus}
                selected={comparison?.target ?? null}
                onPick={setPickedTarget}
                labels={{ duration: ta('chart.duration'), cost: ta('chart.cost'), epoch: epochLabel }}
              />
            </section>

            {/* Nodes x epochs */}
            <section className="flex flex-col gap-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h3 className="text-sm font-semibold text-theme-primary">{ta('grid.title')}</h3>
                <div className="inline-flex items-center rounded-md bg-gray-100 p-0.5 dark:bg-gray-700/50" role="group" aria-label={ta('grid.colorBy')}>
                  {(['status', 'duration'] as const).map(mode => (
                    <button
                      key={mode}
                      type="button"
                      aria-pressed={colorBy === mode}
                      onClick={() => setColorBy(mode)}
                      className={`rounded px-2 py-0.5 text-sm font-medium transition-colors ${
                        colorBy === mode
                          ? 'bg-white text-gray-900 shadow-sm dark:bg-gray-600 dark:text-gray-100'
                          : 'text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200'
                      }`}
                    >
                      {mode === 'status' ? ta('grid.byStatus') : ta('grid.byDuration')}
                    </button>
                  ))}
                </div>
              </div>

              <GridSection
                epochs={epochs}
                rows={rows}
                colorBy={colorBy}
                selected={comparison}
                nodeFor={nodeFor}
                labelFor={labelFor}
                epochLabel={epochLabel}
                onPick={setPickedTarget}
                onFocusNode={focusNode}
              />

              <div className="flex flex-col gap-1.5 rounded-lg bg-theme-secondary px-2.5 py-2 text-sm text-theme-secondary" data-run-analysis-insights>
                {insights.length === 0 ? (
                  <span className="inline-flex items-center gap-1.5">
                    <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" /> {ta('insight.none')}
                  </span>
                ) : insights.map(insight => insight.kind === 'epochFailures' ? (
                  // Failed epochs with no failed node row (a trigger or engine failure): the reason
                  // is only in the logs, so say where to look.
                  <span key="epoch-failures" className="flex items-start gap-1.5" data-insight="epoch-failures">
                    <AlertTriangle className="mt-0.5 h-3.5 w-3.5 flex-shrink-0 text-amber-500" />
                    {ta('insight.epochFailures', { count: insight.count })}
                  </span>
                ) : insight.kind === 'failing' ? (
                  <span key="failing" className="flex items-start gap-1.5" data-insight="failing">
                    <AlertTriangle className="mt-0.5 h-3.5 w-3.5 flex-shrink-0 text-amber-500" />
                    <span className="min-w-0">
                      {ta('insight.failing', { node: labelFor(insight.alias), count: insight.count, total: insight.total })}
                      {insight.lastError && (
                        <span className="block truncate text-sm text-theme-muted" title={insight.lastError}>
                          {ta('insight.lastError', { error: insight.lastError })}
                        </span>
                      )}
                    </span>
                  </span>
                ) : (
                  <span key="slower" className="flex items-start gap-1.5" data-insight="slower">
                    <TrendingUp className="mt-0.5 h-3.5 w-3.5 flex-shrink-0 text-indigo-500" />
                    {ta('insight.slower', {
                      node: labelFor(insight.alias),
                      epoch: insight.sinceEpoch,
                      before: formatCompactDuration(insight.beforeMs),
                      after: formatCompactDuration(insight.afterMs),
                    })}
                  </span>
                ))}
              </div>
            </section>

            {/* Compare two epochs */}
            <section className="flex flex-col gap-2" data-run-analysis-compare>
              <h3 className="text-sm font-semibold text-theme-primary">{ta('compare.title')}</h3>
              {comparison ? (
                <ComparisonTable
                  reference={epochs.find(e => e.epoch === comparison.reference)!}
                  target={epochs.find(e => e.epoch === comparison.target)!}
                  aliasOrder={aliasOrder}
                  runStatus={runStatus}
                  nodeFor={nodeFor}
                  labelFor={labelFor}
                  onShowOnCanvas={showOnCanvas}
                />
              ) : (
                <p className="text-sm text-theme-secondary">{ta('compare.needTwo')}</p>
              )}
              <p className="text-sm text-theme-muted">{ta('compare.hint')}</p>
            </section>
          </div>
        )}
      </div>
    </div>
  );
}

/** What the Analysis tab reads from the canvas: node identities and labels, and the edges (DAG order). */
function canvasSignature(workflowId: string): string {
  const nodes = getCanvasNodes(workflowId).map(n => `${n.id}|${n.data?.id ?? ''}|${n.data?.label ?? ''}`);
  const edges = getCanvasEdges(workflowId).map(e => `${e.source}>${e.target}`);
  return nodes.join('\n') + '#' + edges.join('\n');
}

function Kpi({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone?: 'good' | 'bad' }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5 bg-theme-primary px-2.5 py-2">
      <span className="truncate text-sm text-theme-muted">{label}</span>
      <span className={`truncate text-base font-semibold tabular-nums ${
        tone === 'good' ? 'text-emerald-600 dark:text-emerald-400' : tone === 'bad' ? 'text-red-600 dark:text-red-400' : 'text-theme-primary'
      }`}>
        {value}
      </span>
      {sub && <span className="truncate text-sm text-theme-muted">{sub}</span>}
    </div>
  );
}

function NodeLabel({ alias, node, label }: { alias: string; node?: Node<BuilderNodeData>; label: string }) {
  const data = node?.data;
  return (
    <span className="flex min-w-0 items-center gap-1.5" title={alias}>
      <span className="flex w-4 flex-shrink-0 justify-center">
        {data ? (
          <NodeIcon
            iconSlug={getIconSlug(data)}
            nodeId={data.id || ''}
            nodeKind={data.kind}
            nodeFamily={findNodeClassById(data.id || '')?.family}
            avatarUrl={(data as { agentAvatarUrl?: string }).agentAvatarUrl}
            size="xs"
          />
        ) : (
          <span className={`h-4 w-4 ${nodeIconRadiusClass('xs')} bg-gray-100 dark:bg-gray-700`} />
        )}
      </span>
      <span className="truncate">{label}</span>
    </span>
  );
}

function EpochChart({
  epochs, runStatus, selected, onPick, labels,
}: {
  epochs: RunAnalysisEpoch[];
  runStatus: string | null;
  selected: number | null;
  onPick: (epoch: number) => void;
  labels: { duration: string; cost: string; epoch: (epoch: number) => string };
}) {
  const data = useMemo(() => epochs.map(e => ({
    epoch: e.epoch,
    // null, not 0: an untimed or running epoch draws no bar, and its tooltip says "-".
    durationMs: e.workDurationMs ?? null,
    cost: e.costCredits ?? null,
    outcome: epochOutcome(e, runStatus),
  })), [epochs, runStatus]);
  const hasCost = data.some(d => d.cost != null);

  return (
    <div className="h-40 w-full" data-run-analysis-chart>
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart data={data} margin={{ top: 4, right: hasCost ? 4 : 8, left: 0, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" strokeOpacity={0.2} vertical={false} />
          <XAxis dataKey="epoch" tick={{ fontSize: '0.75rem' }} tickLine={false} tickFormatter={(v: number) => `#${v}`} minTickGap={16} />
          <YAxis yAxisId="d" tick={{ fontSize: '0.75rem' }} tickLine={false} axisLine={false} width={40} tickFormatter={(v: number) => formatCompactDuration(v)} />
          {hasCost && <YAxis yAxisId="c" orientation="right" hide />}
          <ChartTooltip
            cursor={{ fillOpacity: 0.08 }}
            contentStyle={{
              backgroundColor: 'var(--bg-secondary)',
              border: '1px solid var(--border-color)',
              borderRadius: '0.5rem',
              fontSize: '0.875rem',
              color: 'var(--text-primary)',
            }}
            itemStyle={{ color: 'var(--text-primary)' }}
            labelStyle={{ color: 'var(--text-primary)' }}
            labelFormatter={(v) => labels.epoch(Number(v))}
            formatter={(value, name) => {
              if (value == null) return ['-', name === 'cost' ? labels.cost : labels.duration];
              return name === 'cost'
                ? [formatCostCompact(value as number), labels.cost]
                : [formatCompactDuration(value as number), labels.duration];
            }}
          />
          <Bar
            yAxisId="d"
            dataKey="durationMs"
            name="duration"
            radius={[2, 2, 0, 0]}
            maxBarSize={14}
            cursor="pointer"
            onClick={(entry: { epoch?: number; payload?: { epoch?: number } }) => {
              const epoch = entry?.payload?.epoch ?? entry?.epoch;
              if (epoch != null) onPick(epoch);
            }}
          >
            {data.map(d => (
              <Cell
                key={d.epoch}
                fill={BAR_COLOR[d.outcome ?? ''] ?? BAR_COLOR_OTHER}
                fillOpacity={selected == null || selected === d.epoch ? 0.9 : 0.55}
                stroke={selected === d.epoch ? 'var(--text-primary)' : undefined}
                strokeWidth={selected === d.epoch ? 1.5 : 0}
              />
            ))}
          </Bar>
          {hasCost && (
            <Line yAxisId="c" type="monotone" dataKey="cost" name="cost" stroke={COST_COLOR} strokeWidth={1.5} dot={false} connectNulls isAnimationActive={false} />
          )}
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}

/**
 * The grid, its legend and the hovered-cell line. Owns the hover state, and the grid below it is
 * memoized on props that do not change on hover, so a mouse move re-renders the one-line detail
 * only: never the chart, the rest of the panel, nor the grid (a 200-epoch grid is thousands of cells).
 */
function GridSection({
  epochs, rows, colorBy, selected, nodeFor, labelFor, epochLabel, onPick, onFocusNode,
}: {
  epochs: RunAnalysisEpoch[];
  rows: NodeRow[];
  colorBy: 'status' | 'duration';
  selected: { reference: number; target: number } | null;
  nodeFor: (alias: string) => Node<BuilderNodeData> | undefined;
  labelFor: (alias: string) => string;
  epochLabel: (epoch: number) => string;
  onPick: (epoch: number) => void;
  onFocusNode: (alias: string) => void;
}) {
  const [hovered, setHovered] = useState<{ alias: string; epoch: number } | null>(null);
  return (
    <>
      <NodeGrid
        epochs={epochs}
        rows={rows}
        colorBy={colorBy}
        selected={selected}
        nodeFor={nodeFor}
        labelFor={labelFor}
        onPick={onPick}
        onHover={setHovered}
        onFocusNode={onFocusNode}
      />
      <GridLegend colorBy={colorBy} />
      <HoverDetail hovered={hovered} epochs={epochs} labelFor={labelFor} epochLabel={epochLabel} />
    </>
  );
}

interface NodeGridProps {
  epochs: RunAnalysisEpoch[];
  rows: NodeRow[];
  colorBy: 'status' | 'duration';
  selected: { reference: number; target: number } | null;
  nodeFor: (alias: string) => Node<BuilderNodeData> | undefined;
  labelFor: (alias: string) => string;
  onPick: (epoch: number) => void;
  onHover: (cell: { alias: string; epoch: number } | null) => void;
  onFocusNode: (alias: string) => void;
}

/** Every prop is stable across a hover (state setters, memoized data, callbacks), so memo holds. */
const NodeGrid = memo(function NodeGrid({
  epochs, rows, colorBy, selected, nodeFor, labelFor, onPick, onHover, onFocusNode,
}: NodeGridProps) {
  const lastIndex = epochs.length - 1;
  return (
    <div className="overflow-x-auto pb-1 scrollbar-thin" onMouseLeave={() => onHover(null)} data-run-analysis-grid>
      <div
        className="grid w-max items-center gap-x-0.5 gap-y-1"
        // One column per epoch: the count is data, so the template cannot be a static class.
        style={{ gridTemplateColumns: `minmax(5.5rem, 10rem) repeat(${epochs.length}, 0.75rem) auto` }}
      >
        <span />
        {epochs.map((e, i) => (
          <span key={e.epoch} className="h-4 overflow-visible whitespace-nowrap text-xs leading-4 text-theme-muted tabular-nums">
            {i % 10 === 0 || i === lastIndex ? e.epoch : ''}
          </span>
        ))}
        <span />
        {rows.map(row => (
          <GridRow
            key={row.alias}
            row={row}
            epochs={epochs}
            colorBy={colorBy}
            selected={selected}
            node={nodeFor(row.alias)}
            label={labelFor(row.alias)}
            onPick={onPick}
            onHover={onHover}
            onFocusNode={onFocusNode}
          />
        ))}
      </div>
    </div>
  );
});

function GridRow({
  row, epochs, colorBy, selected, node, label, onPick, onHover, onFocusNode,
}: {
  row: NodeRow;
  epochs: RunAnalysisEpoch[];
  colorBy: 'status' | 'duration';
  selected: { reference: number; target: number } | null;
  node?: Node<BuilderNodeData>;
  label: string;
  onPick: (epoch: number) => void;
  onHover: (cell: { alias: string; epoch: number } | null) => void;
  onFocusNode: (alias: string) => void;
}) {
  const ta = useTranslations('workflow.runAnalysis');
  const failuresText = [
    row.failures ? ta('grid.failures', { count: row.failures }) : null,
    row.partials ? ta('grid.partials', { count: row.partials }) : null,
  ].filter(Boolean).join(' · ') || null;
  const averageText = row.avgElapsedMs != null ? ta('grid.average', { value: formatCompactDuration(row.avgElapsedMs) }) : null;
  return (
    <>
      <button
        type="button"
        onClick={() => onFocusNode(row.alias)}
        className="sticky left-0 z-[1] flex h-4 min-w-0 items-center bg-theme-primary pr-2 text-left text-sm font-medium text-theme-primary hover:underline"
        data-run-analysis-row={row.alias}
      >
        <NodeLabel alias={row.alias} node={node} label={label} />
      </button>
      {epochs.map(e => {
        const cell = row.cells.get(e.epoch);
        const status = cellStatus(cell);
        const level = colorBy === 'duration' && status === 'ok' ? heatLevel(cell?.elapsedMs, row.maxElapsedMs) : null;
        const cls = level != null ? HEAT_CLASS[level] : CELL_CLASS[status];
        const ring = selected?.target === e.epoch
          ? 'ring-2 ring-gray-900 dark:ring-gray-100 ring-offset-1 ring-offset-[var(--bg-primary)]'
          : selected?.reference === e.epoch
            ? 'ring-1 ring-gray-400 dark:ring-gray-500'
            : '';
        return (
          <button
            key={e.epoch}
            type="button"
            className={`h-4 w-3 rounded-sm ${cls} ${ring} focus-visible:outline focus-visible:outline-2 focus-visible:outline-blue-500`}
            aria-label={`${label}, ${ta('compare.epoch', { epoch: e.epoch })}, ${ta(`legend.${status}`)}`}
            data-cell-status={status}
            onMouseEnter={() => onHover({ alias: row.alias, epoch: e.epoch })}
            onFocus={() => onHover({ alias: row.alias, epoch: e.epoch })}
            onClick={() => onPick(e.epoch)}
          />
        );
      })}
      <span className="whitespace-nowrap pl-2 text-sm text-theme-muted tabular-nums">
        {failuresText && <span className="font-semibold text-red-600 dark:text-red-400">{failuresText}</span>}
        {failuresText && averageText && ' · '}
        {averageText}
      </span>
    </>
  );
}

function GridLegend({ colorBy }: { colorBy: 'status' | 'duration' }) {
  const ta = useTranslations('workflow.runAnalysis');
  // Every colour the grid can show in this mode, and nothing else.
  const items: CellStatus[] = colorBy === 'status'
    ? ['ok', 'failed', 'partial', 'skipped', 'running', 'waiting', 'none']
    : ['failed', 'partial', 'skipped', 'running', 'waiting', 'none'];
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-theme-muted">
      {colorBy === 'duration' && (
        <span className="inline-flex items-center gap-1">
          {ta('legend.fast')}
          {HEAT_CLASS.map(c => <span key={c} className={`inline-block h-2.5 w-2.5 rounded-sm ${c}`} />)}
          {ta('legend.slow')}
        </span>
      )}
      {items.map(s => (
        <span key={s} className="inline-flex items-center gap-1">
          <span className={`inline-block h-2.5 w-2.5 rounded-sm ${CELL_CLASS[s]}`} />
          {ta(`legend.${s}`)}
        </span>
      ))}
    </div>
  );
}

function HoverDetail({
  hovered, epochs, labelFor, epochLabel,
}: {
  hovered: { alias: string; epoch: number } | null;
  epochs: RunAnalysisEpoch[];
  labelFor: (alias: string) => string;
  epochLabel: (epoch: number) => string;
}) {
  const ta = useTranslations('workflow.runAnalysis');
  // Reserved height: the line appearing on hover must not push the sections below around.
  if (!hovered) return <div className="h-5" aria-hidden="true" />;
  const cell = epochs.find(e => e.epoch === hovered.epoch)?.nodes.find(c => c.alias === hovered.alias);
  const status = cellStatus(cell);
  // Executions that RAN: skipped items are not executions.
  const counts = cell?.statusCounts ?? {};
  const executions = (counts.completed ?? 0) + (counts.failed ?? 0) + (counts.running ?? 0) + (counts.awaitingSignal ?? 0);
  const parts = [
    labelFor(hovered.alias),
    epochLabel(hovered.epoch),
    ta(`legend.${status}`),
    cell?.elapsedMs != null ? formatCompactDuration(cell.elapsedMs) : null,
    executions > 1 ? ta('grid.items', { count: executions }) : null,
    executions > 1 && cell && cell.executionTimeMs > (cell.elapsedMs ?? 0) + 1000
      ? ta('grid.totalWork', { value: formatCompactDuration(cell.executionTimeMs) })
      : null,
  ].filter(Boolean);
  return (
    <div className="flex h-5 min-w-0 items-center gap-1 text-sm text-theme-secondary" aria-live="polite" data-run-analysis-hover>
      <span className="truncate">{parts.join(' · ')}</span>
      {cell?.errorMessage && <span className="truncate text-red-600 dark:text-red-400" title={cell.errorMessage}>· {cell.errorMessage}</span>}
    </div>
  );
}

function StatusIcon({ status }: { status: CellStatus }) {
  switch (status) {
    case 'ok': return <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" />;
    case 'failed': return <XCircle className="h-3.5 w-3.5 text-red-500" />;
    case 'partial': return <AlertTriangle className="h-3.5 w-3.5 text-amber-500" />;
    case 'skipped': return <CircleSlash className="h-3.5 w-3.5 text-gray-400" />;
    case 'waiting': return <PauseCircle className="h-3.5 w-3.5 text-amber-500" />;
    case 'running': return <Loader2 className="h-3.5 w-3.5 animate-spin text-blue-500" />;
    default: return <span className="inline-block h-3.5 w-3.5" />;
  }
}

function CompareCell({ cell }: { cell?: RunAnalysisNodeCell }) {
  const ta = useTranslations('workflow.runAnalysis');
  const status = cellStatus(cell);
  return (
    <div className="flex min-w-0 flex-col">
      <span className="inline-flex items-center gap-1 tabular-nums">
        <StatusIcon status={status} />
        {status === 'none'
          ? <span className="text-theme-muted">{ta('compare.notRun')}</span>
          : cell?.elapsedMs != null ? formatCompactDuration(cell.elapsedMs) : ta(`legend.${status}`)}
      </span>
      {cell?.errorMessage && status !== 'ok' && (
        <span className="line-clamp-2 break-words text-sm text-red-600 dark:text-red-400" title={cell.errorMessage}>
          {cell.errorMessage}
        </span>
      )}
    </div>
  );
}

function ComparisonTable({
  reference, target, aliasOrder, runStatus, nodeFor, labelFor, onShowOnCanvas,
}: {
  reference: RunAnalysisEpoch;
  target: RunAnalysisEpoch;
  aliasOrder: string[];
  runStatus: string | null;
  nodeFor: (alias: string) => Node<BuilderNodeData> | undefined;
  labelFor: (alias: string) => string;
  onShowOnCanvas: (epoch: number) => void;
}) {
  const ta = useTranslations('workflow.runAnalysis');
  const rows = useMemo(() => compareEpochs(reference, target, aliasOrder), [reference, target, aliasOrder]);
  const header = (epoch: RunAnalysisEpoch, isReference: boolean) => {
    const outcome = epochOutcome(epoch, runStatus);
    return (
      <div className="flex min-w-0 items-center gap-1">
        {outcome === 'COMPLETED' ? <CheckCircle2 className="h-3.5 w-3.5 flex-shrink-0 text-emerald-500" />
          : outcome === 'FAILED' ? <XCircle className="h-3.5 w-3.5 flex-shrink-0 text-red-500" /> : null}
        <span className="truncate">
          {ta('compare.epoch', { epoch: epoch.epoch })}
          {isReference && <span className="font-normal text-theme-muted"> ({ta('compare.reference')})</span>}
        </span>
        <button
          type="button"
          onClick={() => onShowOnCanvas(epoch.epoch)}
          title={ta('compare.showOnCanvas', { epoch: epoch.epoch })}
          aria-label={ta('compare.showOnCanvas', { epoch: epoch.epoch })}
          className="flex-shrink-0 rounded p-0.5 text-theme-secondary hover:bg-theme-secondary hover:text-theme-primary"
          data-show-epoch-on-canvas={epoch.epoch}
        >
          <Crosshair className="h-3.5 w-3.5" />
        </button>
      </div>
    );
  };
  return (
    <div className="overflow-x-auto rounded-lg border border-theme">
      <table className="w-full min-w-80 text-sm">
        <thead>
          <tr className="bg-theme-secondary text-left text-sm font-semibold text-theme-muted">
            <th className="px-2.5 py-1.5">{ta('compare.node')}</th>
            <th className="px-2.5 py-1.5">{header(reference, true)}</th>
            <th className="px-2.5 py-1.5">{header(target, false)}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map(row => (
            <tr
              key={row.alias}
              data-compare-row={row.alias}
              data-differs={row.differs || undefined}
              className={`border-t border-theme align-top ${row.differs ? 'bg-red-50/70 dark:bg-red-950/20' : ''}`}
            >
              <td className="max-w-40 px-2.5 py-1.5 font-medium text-theme-primary">
                <NodeLabel alias={row.alias} node={nodeFor(row.alias)} label={labelFor(row.alias)} />
              </td>
              <td className="px-2.5 py-1.5"><CompareCell cell={row.reference} /></td>
              <td className="px-2.5 py-1.5"><CompareCell cell={row.target} /></td>
            </tr>
          ))}
          <tr className="border-t border-theme text-theme-secondary">
            <td className="px-2.5 py-1.5 font-medium">{ta('compare.total')}</td>
            {[reference, target].map(e => (
              <td key={e.epoch} className="px-2.5 py-1.5 tabular-nums">
                {e.workDurationMs != null ? formatCompactDuration(e.workDurationMs) : '-'}
                {e.costCredits != null && ` · ${formatCostCompact(e.costCredits)}`}
              </td>
            ))}
          </tr>
        </tbody>
      </table>
    </div>
  );
}

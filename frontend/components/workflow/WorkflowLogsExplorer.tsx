'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocale, useTranslations } from 'next-intl';
import { ArrowDownToLine, ArrowUpFromLine, ChevronRight, FileText, Layers, Loader2, PanelLeftClose, PanelLeftOpen, RefreshCw } from 'lucide-react';
import { orchestratorApi } from '@/lib/api';
import type { AggregatedStepTiming } from '@/lib/api/orchestrator/types';
import { useAuthGuard } from '@/hooks/useAuthGuard';
import type { BreadcrumbItem } from '@/components/ui/breadcrumb';
import { StatusBadge, mapBackendStatusToStatusType } from '@/components/ui/StatusBadge';
import { formatUtcDateTime } from '@/lib/utils/dateFormatters';
import { computeDagOrder, sortByDagOrder } from '@/lib/workflow/dagStepOrder';
import { getCanvasEdges, getCanvasNodes, subscribeCanvasNodes } from '@/app/workflows/builder/services/canvasNodesStore';
import { nodeMatchesStep } from '@/app/workflows/builder/services/nodeMatcher';
import { normalizeId } from '@/app/workflows/builder/services/idMatcherUtils';
import { getIconSlug, NodeIcon } from '@/app/workflows/builder/components/nodes/shared';
import { findNodeClassById } from '@/app/workflows/builder/nodes/nodeClasses';
import { useStepData } from '@/app/workflows/builder/hooks/useStepData';
import { useStepCompletionInvalidation } from '@/app/workflows/builder/hooks/useStepCompletionInvalidation';
import { JsonValueTree } from '@/app/workflows/builder/components/inspector/outputs/JsonDataTree';
import { WorkflowStepTable } from './WorkflowStepTable';
import { CopyButton } from '@/app/workflows/builder/components/inspector/shared/CopyButton';
import { getPickedEpoch } from './run-panel/useDefaultEpochSelection';

interface WorkflowLogsExplorerProps {
  workflowId: string;
  runId: string;
  initialStepAlias?: string;
  view?: 'simple' | 'table';
  onBreadcrumbChange: (items: BreadcrumbItem[]) => void;
}

const selectClass = 'h-8 min-w-0 rounded-lg border border-theme bg-theme-primary px-2 text-sm text-theme-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent-primary)]';

/** Both presentations share the same epoch and node; the table explores all matching calls. */
export function WorkflowLogsExplorer(props: WorkflowLogsExplorerProps) {
  // A run change must not reuse a previous run's selection, even outside the panel host.
  return <LogsExplorer key={`${props.workflowId}:${props.runId}`} {...props} />;
}

function LogsExplorer({ workflowId, runId, initialStepAlias, onBreadcrumbChange, view = 'simple' }: WorkflowLogsExplorerProps) {
  const t = useTranslations('workflow.logs');
  const locale = useLocale();
  const auth = useAuthGuard();
  const enabled = auth.isReady && auth.isAuthenticated;
  const queryClient = useQueryClient();
  const tableView = view === 'table';
  const [tablePath, setTablePath] = useState('');
  const [tableRevision, setTableRevision] = useState(0);
  const [nodesHidden, setNodesHidden] = useState(false);
  const [showRoot, setShowRoot] = useState(false);
  const [epoch, setEpoch] = useState<number | null | undefined>(() => getPickedEpoch(runId));
  // The table lists every call of a node, so it always opens on all epochs; the simple view keeps its own epoch.
  const [tableEpoch, setTableEpoch] = useState<number | null>(null);
  useEffect(() => {
    // Reset on leaving, so the table is already on all epochs when it opens again (no refetch of a stale epoch).
    if (!tableView) setTableEpoch(null);
  }, [tableView]);
  const viewEpoch = tableView ? tableEpoch : epoch;
  const [requestedAlias, setRequestedAlias] = useState(initialStepAlias);
  const [direction, setDirection] = useState<'input' | 'output'>('output');
  const [passageId, setPassageId] = useState<number | null>(null);
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(() => new Set());
  const [canvasRevision, setCanvasRevision] = useState(0);
  useEffect(() => subscribeCanvasNodes(() => setCanvasRevision(value => value + 1)), []);
  useEffect(() => {
    setRequestedAlias(initialStepAlias);
    setShowRoot(false);
    setTablePath('');
  }, [initialStepAlias]);

  const stateQuery = useQuery({
    queryKey: ['workflow-logs', workflowId, runId, 'state'],
    queryFn: () => orchestratorApi.getRunState(runId),
    enabled,
    staleTime: 15000,
  });
  const epochs = useMemo(() => [...(Array.isArray(stateQuery.data?.epochTimestamps) ? stateQuery.data.epochTimestamps : [])].sort((a, b) => b.epoch - a.epoch), [stateQuery.data]);
  useEffect(() => {
    if (epoch === undefined && stateQuery.isSuccess) {
      // Pin once. A new trigger fire must not take the reader away from their data.
      setEpoch(epochs[0]?.epoch ?? null);
    }
  }, [epoch, epochs, stateQuery.isSuccess]);

  const nodesQuery = useQuery<AggregatedStepTiming[]>({
    queryKey: ['workflow-logs', workflowId, runId, 'nodes', viewEpoch],
    queryFn: () => orchestratorApi.getEpochAggregatedSteps(runId, viewEpoch ?? undefined),
    enabled: enabled && viewEpoch !== undefined,
    staleTime: 15000,
    // Switching view or epoch keeps the current node list on screen instead of a full-body spinner.
    placeholderData: keepPreviousData,
  });
  const nodes = useMemo(() => {
    const canvas = getCanvasNodes(workflowId);
    const entries = (Array.isArray(nodesQuery.data) ? nodesQuery.data : []).map(step => ({
      ...step,
      node: canvas.find(node => nodeMatchesStep(node, { stepAlias: step.alias, id: step.alias })),
    }));
    return sortByDagOrder(entries, computeDagOrder(canvas, getCanvasEdges(workflowId)), entry => entry.node?.id);
  }, [nodesQuery.data, workflowId, canvasRevision]);
  const selectedNode = showRoot ? undefined : nodes.find(entry => entry.alias === requestedAlias)
    ?? nodes.find(entry => requestedAlias && normalizeId(entry.alias) === normalizeId(requestedAlias))
    ?? nodes.find(entry => requestedAlias && entry.node && nodeMatchesStep(entry.node, { stepAlias: requestedAlias, id: requestedAlias }))
    ?? (requestedAlias ? undefined : nodes[0]);
  const alias = selectedNode?.alias;
  useEffect(() => {
    if (!requestedAlias && alias) setRequestedAlias(alias);
  }, [requestedAlias, alias]);
  const label = selectedNode?.node?.data.label || alias;
  const steps = useStepData(runId, alias, { epoch: epoch ?? null, enabled: enabled && !tableView && !!alias && epoch !== undefined });
  const passage = steps.stepData.find(step => step.id === passageId) ?? steps.stepData[0];
  useEffect(() => {
    if (passage && passageId !== passage.id) setPassageId(passage.id);
  }, [passage, passageId]);
  const payloadQuery = useQuery({
    queryKey: ['workflow-logs', workflowId, runId, 'output', passage?.id],
    queryFn: () => orchestratorApi.execution.getStepOutputObjectAtPath(workflowId, runId, passage!.id, 'output', { throwOnError: true }),
    enabled: enabled && !tableView && !!passage?.outputStorageId && direction === 'output',
    staleTime: 15000,
  });
  const payload = direction === 'input' ? passage?.inputData : passage?.outputStorageId ? payloadQuery.data : null;
  useEffect(() => {
    const parts = tablePath ? tablePath.split('.') : [];
    onBreadcrumbChange([
      { label: t('root'), ...(!showRoot ? { onClick: () => setShowRoot(true), alwaysClickable: true } : {}) },
      ...(label ? [
        { label, ...(tableView && tablePath ? { onClick: () => setTablePath('') } : {}) },
        ...(tableView ? parts.map((part, index) => ({
          label: part,
          ...(index < parts.length - 1 ? { onClick: () => setTablePath(parts.slice(0, index + 1).join('.')) } : {}),
        })) : [{ label: t(direction) }]),
      ] : []),
    ]);
  }, [label, direction, showRoot, tableView, tablePath, t, onBreadcrumbChange]);
  useStepCompletionInvalidation({
    runId,
    enabled,
    onInvalidate: () => { void queryClient.invalidateQueries({ queryKey: ['workflow-logs', workflowId, runId] }); },
  });
  function chooseNode(value: string) {
    setShowRoot(false);
    setRequestedAlias(value);
    setTablePath('');
    setPassageId(null);
    setExpandedPaths(new Set());
  }
  function chooseEpoch(value: string) {
    const next = value === 'all' ? null : Number(value);
    setTablePath('');
    if (tableView) {
      setTableEpoch(next);
      return;
    }
    setEpoch(next);
    setPassageId(null);
  }
  const busy = !enabled || stateQuery.isLoading || viewEpoch === undefined || nodesQuery.isLoading;
  const payloadBusy = steps.loading || (direction === 'output' && payloadQuery.isLoading);
  const error = stateQuery.isError || nodesQuery.isError || (!tableView && !!alias && !!steps.error);
  const outputError = direction === 'output' && !!passage?.outputStorageId && payloadQuery.isError;
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['workflow-logs', workflowId, runId] });
    if (tableView) setTableRevision(value => value + 1);
    else void steps.refetch();
  };

  const showNodesControl = (nodesHidden && <button type="button" onClick={() => setNodesHidden(false)} aria-label={t('showNodes')} title={t('showNodes')} aria-expanded={false} className="shrink-0 rounded-lg p-2 text-theme-secondary hover:bg-theme-secondary"><PanelLeftOpen className="h-3.5 w-3.5" /></button>);

  return (
    <section data-testid="workflow-logs-explorer" className="@container flex h-full min-h-0 flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <label className="flex min-w-0 items-center gap-2 text-sm text-theme-secondary">
          <Layers className="h-3.5 w-3.5 shrink-0" />
          <span className="sr-only">{t('epoch')}</span>
          <select aria-label={t('epoch')} value={viewEpoch ?? 'all'} onChange={event => chooseEpoch(event.target.value)} disabled={!stateQuery.isSuccess} className={`${selectClass} max-w-64`}>
            <option value="all">{t('allEpochs')}</option>
            {viewEpoch != null && !epochs.some(item => item.epoch === viewEpoch) && <option value={viewEpoch}>{t('epochNumber', { number: viewEpoch })}</option>}
            {epochs.map(item => <option key={item.epoch} value={item.epoch}>{t('epochNumber', { number: item.epoch })} · {formatUtcDateTime(item.startedAt, { locale })}</option>)}
          </select>
        </label>

      </div>

      {error ? (
        <div role="alert" className="flex flex-1 flex-col items-center justify-center gap-3 rounded-xl border border-theme p-6 text-center text-sm text-theme-secondary">
          <FileText className="h-6 w-6 text-red-500" />
          <p>{t('loadDataError')}</p>
          <button type="button" className="rounded-lg border border-theme px-3 py-2 text-theme-primary hover:bg-theme-secondary" onClick={refresh}>{t('retry')}</button>
        </div>
      ) : busy ? (
        <div role="status" className="flex flex-1 items-center justify-center gap-2 text-sm text-theme-secondary"><Loader2 className="h-4 w-4 animate-spin" />{t('loading')}</div>
      ) : nodes.length === 0 ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-theme p-6 text-center text-sm text-theme-secondary"><Layers className="h-8 w-8 opacity-40" /><p>{t('emptyEpoch')}</p><button type="button" onClick={refresh} className="underline">{t('refresh')}</button></div>
      ) : (
        <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-xl border border-theme @min-[38rem]:flex-row">
          <aside className={`hidden w-52 shrink-0 flex-col border-r border-theme bg-theme-secondary/50 ${!nodesHidden && !showRoot ? '@min-[38rem]:flex' : ''}`}>
            <div data-testid="workflow-logs-nodes-header" className="flex h-11 items-center gap-2 border-b border-theme px-3 text-sm font-medium text-theme-secondary">
              <span className="flex-1">{t('nodes')}</span><span className="rounded-md bg-theme-tertiary px-1.5 text-xs">{nodes.length.toLocaleString(locale)}</span>
              <button type="button" onClick={() => setNodesHidden(true)} aria-label={t('hideNodes')} title={t('hideNodes')} aria-expanded className="rounded-md p-1.5 hover:bg-theme-tertiary"><PanelLeftClose className="h-3.5 w-3.5" /></button>
            </div>
            <nav aria-label={t('nodes')} className="min-h-0 flex-1 space-y-1 overflow-y-auto p-2">
              {nodes.map(entry => (
                <button key={entry.alias} type="button" aria-current={entry.alias === alias ? 'true' : undefined} onClick={() => chooseNode(entry.alias)} className={`flex w-full items-center gap-2 rounded-lg border p-2.5 text-left text-sm transition-colors ${entry.alias === alias ? 'border-[var(--accent-primary)]/30 bg-[var(--accent-primary)]/10 text-theme-primary' : 'border-transparent text-theme-secondary hover:bg-theme-tertiary'}`}>
                  {entry.node ? <NodeIcon iconSlug={getIconSlug(entry.node.data)} nodeId={entry.node.data.id || ''} nodeKind={entry.node.data.kind} nodeFamily={findNodeClassById(entry.node.data.id || '')?.family} size="xs" /> : <FileText className="h-4 w-4 shrink-0" />}
                  <span className="min-w-0 flex-1"><span className="block truncate font-medium" title={entry.node?.data.label || entry.alias}>{entry.node?.data.label || entry.alias}</span><span className="mt-1 block"><StatusBadge status={mapBackendStatusToStatusType(entry.status)} variant="noBackground" /></span></span>
                  {entry.alias === alias && <ChevronRight className="h-3.5 w-3.5 shrink-0 text-[var(--accent-primary)]" />}
                </button>
              ))}
            </nav>
          </aside>
          <div className={`border-b border-theme bg-theme-secondary p-2 @min-[38rem]:hidden ${nodesHidden || showRoot ? 'hidden' : ''}`}>
            <div className="mb-2 flex items-center gap-2 text-sm text-theme-secondary">
              <span className="flex-1">{t('nodes')}</span><span className="text-xs">{nodes.length.toLocaleString(locale)}</span>
              <button type="button" onClick={() => setNodesHidden(true)} aria-label={t('hideNodes')} title={t('hideNodes')} aria-expanded className="rounded-md p-1.5 hover:bg-theme-tertiary"><PanelLeftClose className="h-3.5 w-3.5" /></button>
            </div>
            <select aria-label={t('node')} value={alias ?? ''} onChange={event => chooseNode(event.target.value)} className={`${selectClass} w-full`}>
              {!alias && <option value="">{t('chooseNode')}</option>}
              {nodes.map(entry => <option key={entry.alias} value={entry.alias}>{entry.node?.data.label || entry.alias}</option>)}
            </select>
          </div>
          <main className="flex min-h-0 min-w-0 flex-1 flex-col">
            {showRoot ? (
              <div data-testid="workflow-logs-root" className="min-h-0 overflow-auto p-4">
                <div className="mb-4 flex items-center justify-between text-sm text-theme-secondary"><h3 className="text-base font-semibold text-theme-primary">{t('nodes')}</h3><span>{nodes.length.toLocaleString(locale)}</span></div>
                <nav aria-label={t('nodes')} className="grid gap-2 @min-[38rem]:grid-cols-2">
                  {nodes.map(entry => (
                    <button key={entry.alias} type="button" onClick={() => chooseNode(entry.alias)} className="flex min-w-0 items-center gap-3 rounded-xl border border-theme p-3 text-left text-sm hover:bg-[var(--bg-secondary)]">
                      {entry.node ? <NodeIcon iconSlug={getIconSlug(entry.node.data)} nodeId={entry.node.data.id || ''} nodeKind={entry.node.data.kind} nodeFamily={findNodeClassById(entry.node.data.id || '')?.family} size="xs" /> : <FileText className="h-4 w-4 shrink-0" />}
                      <span className="min-w-0 flex-1"><span className="block truncate font-medium text-theme-primary">{entry.node?.data.label || entry.alias}</span><StatusBadge status={mapBackendStatusToStatusType(entry.status)} variant="noBackground" /></span>
                      <ChevronRight className="h-3.5 w-3.5 shrink-0 text-theme-secondary" />
                    </button>
                  ))}
                </nav>
              </div>
            ) : !alias ? <div className="flex items-center gap-2 p-4 text-sm text-theme-secondary">{showNodesControl}<p>{t('nodeUnavailable')}</p></div> : (
              <>
                <div className="flex flex-wrap items-center justify-between gap-2 border-b border-theme px-4 py-3">
                  {showNodesControl}
                  <div className="min-w-0 flex-1"><h3 className="truncate text-base font-semibold text-theme-primary" title={label}>{label}</h3><div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-theme-secondary">{(tableView ? selectedNode : passage) && <StatusBadge status={mapBackendStatusToStatusType((tableView ? selectedNode : passage)!.status)} variant="noBackground" />}{!tableView && passage?.startTime && <span>{formatUtcDateTime(passage.startTime, { locale, withSeconds: true })}</span>}</div></div>
                  <button type="button" onClick={refresh} aria-label={t('refresh')} title={t('refresh')} className="shrink-0 self-start rounded-lg p-2 text-theme-secondary hover:bg-theme-secondary"><RefreshCw className={`h-3.5 w-3.5 ${nodesQuery.isFetching || steps.isFetchingNextPage ? 'animate-spin' : ''}`} /></button>
                </div>
                {tableView ? (
                  <div data-testid="workflow-logs-table" className="min-h-0 flex-1 p-3">
                    <WorkflowStepTable key={`${alias}:${tableEpoch}`} workflowId={workflowId} runId={runId} stepAlias={alias} epoch={tableEpoch} jsonPath={tablePath} onNavigate={setTablePath} refreshVersion={tableRevision} />
                  </div>
                ) : <>
                <div className="flex flex-wrap items-center justify-between gap-2 border-b border-theme px-3 py-2">
                  <div role="group" aria-label={t('direction')} className="inline-flex rounded-lg bg-theme-secondary p-1">
                    {(['input', 'output'] as const).map(value => <button key={value} type="button" aria-pressed={direction === value} onClick={() => setDirection(value)} className={`flex items-center gap-1.5 rounded-md px-2 py-1.5 text-sm ${direction === value ? 'bg-theme-primary font-medium text-theme-primary shadow-sm' : 'text-theme-secondary hover:text-theme-primary'}`}>{value === 'input' ? <ArrowDownToLine className="h-3.5 w-3.5" /> : <ArrowUpFromLine className="h-3.5 w-3.5" />}{t(value)}</button>)}
                  </div>
                  {passage && steps.totalElements > 1 && <select aria-label={t('passage')} value={passage.id} onChange={event => setPassageId(Number(event.target.value))} className={`${selectClass} max-w-full`}>
                    {steps.stepData.map((step, index) => <option key={step.id} value={step.id}>{t('passageNumber', { number: steps.totalElements - index })}{epoch === null && step.epoch != null ? ` · ${t('epochNumber', { number: step.epoch })}` : ''}</option>)}
                  </select>}
                </div>
                <div className="min-h-0 flex-1 overflow-auto p-4" data-testid="workflow-logs-simple">
                  {passage?.errorMessage && <div role="alert" className="mb-4 whitespace-pre-wrap break-words rounded-lg border border-red-500/20 bg-red-500/5 p-3 text-sm text-red-600 dark:text-red-400">{passage.errorMessage}</div>}
                  {outputError ? <div role="alert" className="space-y-3 py-8 text-center text-sm text-theme-secondary"><p>{t('loadDataError')}</p><button type="button" onClick={() => void payloadQuery.refetch()} className="rounded-lg border border-theme px-3 py-2 text-theme-primary hover:bg-theme-secondary">{t('retry')}</button></div> : payloadBusy ? <div role="status" className="flex items-center gap-2 py-8 text-sm text-theme-secondary"><Loader2 className="h-4 w-4 animate-spin" />{t('loading')}</div> : !passage || payload == null ? <p className="py-8 text-center text-sm text-theme-secondary">{t('emptyData')}</p> : (
                    <>
                      <div className="mb-3 flex items-center justify-between text-sm text-theme-secondary"><span>{t(direction === 'input' ? 'inputData' : 'outputData')}</span><CopyButton value={payload} title={t('copy')} /></div>
                      {<JsonValueTree data={payload} path={[]} expandedPaths={expandedPaths} onToggleExpand={path => setExpandedPaths(previous => { const next = new Set(previous); if (next.has(path)) next.delete(path); else next.add(path); return next; })} />}
                    </>
                  )}
                </div>
                </>}
                {!tableView && steps.hasNextPage && <footer className="flex items-center justify-end border-t border-theme bg-theme-secondary/40 px-3 py-2">
                  <button type="button" disabled={steps.isFetchingNextPage} onClick={() => void steps.fetchNextPage()} className="text-sm text-theme-secondary underline disabled:opacity-50">{t('loadMore')}</button>
                </footer>}
              </>
            )}
          </main>
        </div>
      )}
    </section>
  );
}

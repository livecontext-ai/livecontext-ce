'use client';

import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { useRouter } from '@/i18n/navigation';
import { useToast } from './Toast';
import ToastContainer from './ToastContainer';
import { StatusBadge, mapBackendStatusToStatusType } from './ui/StatusBadge';
import { orchestratorApi } from '@/lib/api/orchestrator';
import { formatRelativeDate } from '@/lib/utils/dateFormatters';
import { getCanvasEdges, getCanvasNodes, subscribeCanvasNodes } from '@/app/workflows/builder/services/canvasNodesStore';
import { nodeMatchesStep } from '@/app/workflows/builder/services/nodeMatcher';
import { getIconSlug, NodeIcon, nodeIconRadiusClass } from '@/app/workflows/builder/components/nodes/shared';
import { findNodeClassById } from '@/app/workflows/builder/nodes/nodeClasses';
import { computeDagOrder, sortByDagOrder } from '@/lib/workflow/dagStepOrder';
import { useTranslations } from 'next-intl';

const orchestratorUrl = '/api/proxy';

// Types
export interface WorkflowStep {
  id?: number | string; // Can be number for individual steps or string (alias) for aggregated
  workflowRunId?: string;
  runId?: string;
  stepAlias: string;
  toolId: string;
  status: string;
  startTime: string;
  endTime?: string | null;
  httpStatus?: number;
  outputStorageId?: string;
  iteration?: number;
  itemIndex?: number;
  epoch?: number;
  spawn?: number;
  errorMessage?: string;
  inputData?: Record<string, any>;
  metadata?: Record<string, any>;
  statusCounts?: {
    completed?: number;
    failed?: number;
    skipped?: number;
    running?: number;
  };
}

interface StepTableProps {
  workflowId: string;
  runId: string;
  className?: string;
  onStepClick?: (step: WorkflowStep) => void;
  /**
   * The aggregated steps this table just loaded, handed to the parent so it does not have to
   * fetch them a second time. The aggregation is the single most expensive read of the Logs
   * modal on a run with many epochs, and the modal used to issue it twice on every open: once
   * here, once in the parent to resolve the step it was asked to pre-select.
   */
  onStepsLoaded?: (steps: WorkflowStep[]) => void;
  onAddAnalyzeBadges?: (ids: string[], type: 'data' | 'workflow') => void; // Callback to add badges directly
  onAnalyzeClick?: () => void; // Callback to close modal after analyze
}

export default function StepTable({
  workflowId,
  runId,
  className = '',
  onStepClick,
  onStepsLoaded,
}: StepTableProps) {
  const t = useTranslations();
  const router = useRouter();
  const { toasts, addToast, removeToast } = useToast();

  const [steps, setSteps] = useState<WorkflowStep[]>([]);
  const [loading, setLoading] = useState(true);
  // Held in a ref, and deliberately NOT a dependency of fetchSteps: the load effect below keys on
  // that callback's identity, so an inline parent lambda would re-arm it on every parent render
  // and the table would refetch in a loop.
  const onStepsLoadedRef = useRef(onStepsLoaded);
  onStepsLoadedRef.current = onStepsLoaded;
  const [loadFailed, setLoadFailed] = useState(false);

  // Chargement des steps agrégés
  // Note: Uses orchestratorApi which goes through Gateway (not direct localhost calls)
  const fetchSteps = useCallback(async () => {
    // Deliberately does NOT report to onStepsLoaded, unlike every other exit below: the load effect
    // already returns on a falsy runId before calling this, so the branch is unreachable and a
    // report here would be dead code that reads as covered. Wire a new caller (a retry button, say)
    // and this stops being true - report from it then.
    if (!runId) {
      setSteps([]);
      setLoading(false);
      return;
    }

    try {
      setLoading(true);
      setLoadFailed(false);

      // Use orchestratorApi to fetch aggregated steps
      const aggregatedData = await orchestratorApi.getAggregatedSteps(runId);

      // Gérer le cas où la réponse est vide ou null
      if (!aggregatedData || !Array.isArray(aggregatedData)) {
        console.warn('Empty or invalid aggregated steps response:', aggregatedData);
        setSteps([]);
        onStepsLoadedRef.current?.([]);
        return;
      }

      // Transformer les données agrégées en format WorkflowStep
      const transformedSteps: WorkflowStep[] = aggregatedData.map((step: any) => ({
        id: step.alias, // Utiliser l'alias comme ID pour les steps agrégés
        stepAlias: step.alias,
        toolId: step.toolId,
        status: step.status,
        startTime: step.startTime || new Date().toISOString(),
        endTime: step.endTime || undefined,
        statusCounts: step.statusCounts,
        runId: runId,
      }));

      setSteps(transformedSteps);
      onStepsLoadedRef.current?.(transformedSteps);
    } catch (err) {
      console.error('Error fetching aggregated steps:', err);
      setLoadFailed(true);
      setSteps([]);
      // The parent is told about the failure too, exactly as its own (now deleted) request did:
      // leaving it on the previous attempt's rows would let a stale step be auto-selected behind
      // a table that is showing "Failed to load steps".
      onStepsLoadedRef.current?.([]);
      // Utiliser addToast seulement en cas d'erreur réelle
      if (err instanceof Error && !err.message.includes('404')) {
        addToast({
          type: 'error',
          title: 'Error Loading Steps',
          message: 'Failed to load steps'
        });
      }
    } finally {
      setLoading(false);
    }
  }, [runId, addToast]);

  // Gestion du clic sur un step
  const handleStepClick = (step: WorkflowStep) => {
    if (onStepClick) {
      onStepClick(step);
    } else {
      // Pour les steps agrégés, utiliser l'alias au lieu de l'ID
      const stepIdentifier = step.id || step.stepAlias;
      router.push(`/app/workflow/${workflowId}/run/${runId}/step/${stepIdentifier}`);
    }
  };

  // Ref pour éviter les appels multiples
  const fetchingRef = useRef(false);
  const lastRunIdRef = useRef<string | null>(null);

  // Chargement initial
  useEffect(() => {
    // Réinitialiser si le runId change
    if (lastRunIdRef.current !== runId) {
      fetchingRef.current = false;
    }

    // Éviter les appels multiples pour le même runId
    if (!runId || fetchingRef.current) {
      return;
    }

    fetchingRef.current = true;
    lastRunIdRef.current = runId;
    
    fetchSteps().finally(() => {
      fetchingRef.current = false;
    });
  }, [runId, fetchSteps]);


  // ── DAG reading order ──
  //
  // The aggregate endpoint returns rows in execution order, which scatters as
  // soon as branches run in parallel or an epoch re-fires part of the graph.
  // Order by the canvas graph instead, exactly like the run panel's step list:
  // trigger first, each branch contiguous, terminal nodes last, and the SAME
  // order every time the modal is reopened on the same plan.
  //
  // The canvas store is invisible to React, so subscribe rather than read once:
  // this table can be mounted before a rename or a deletion reaches it.
  const [canvasNodesTick, setCanvasNodesTick] = useState(0);
  useEffect(() => subscribeCanvasNodes(() => setCanvasNodesTick(prev => prev + 1)), []);

  // Unscoped reads, as this table has always done for its icons: the modal is
  // also opened from surfaces where the canvas publishes under a different id
  // (the application view), and a scoped read there returns nothing. Nodes and
  // edges are taken in the same pass so they always describe one graph.
  const dagOrder = useMemo(
    () => computeDagOrder(getCanvasNodes(), getCanvasEdges()),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- canvasNodesTick is the store's change signal
    [canvasNodesTick],
  );

  // Resolve each step's canvas node ONCE instead of scanning the node array
  // inside every row's render, which is what the alias cell used to do.
  const nodeByStepAlias = useMemo(() => {
    const nodes = getCanvasNodes();
    const map = new Map<string, ReturnType<typeof getCanvasNodes>[number] | undefined>();
    if (!nodes.length) return map;
    for (const s of steps) {
      if (map.has(s.stepAlias)) continue;
      map.set(s.stepAlias, nodes.find((n) => nodeMatchesStep(n, { stepAlias: s.stepAlias, id: s.stepAlias })));
    }
    return map;
    // eslint-disable-next-line react-hooks/exhaustive-deps -- canvasNodesTick is the store's change signal
  }, [steps, canvasNodesTick]);

  // Steps whose node the canvas cannot resolve keep their incoming order at the
  // end: a row is never dropped just because its node was deleted since the run.
  const orderedSteps = useMemo(
    () => sortByDagOrder(steps, dagOrder, (s) => nodeByStepAlias.get(s.stepAlias)?.id),
    [steps, dagOrder, nodeByStepAlias],
  );

  return (
    <div className={`space-y-4 w-full h-full flex flex-col ${className}`}>
      {/* Table des steps */}
      <div className="flex-1 min-h-0 w-full overflow-hidden flex flex-col">
        {!loading && loadFailed ? (
          <div
            data-testid="workflow-logs-steps-error"
            role="alert"
            className="flex h-full items-center justify-center py-8 text-center text-sm text-red-600 dark:text-red-400"
          >
            <p>{t('workflow.logs.loadStepsError')}</p>
          </div>
        ) : !loading && steps.length === 0 ? (
          <div className="text-center py-8 text-theme-secondary h-full flex items-center justify-center">
            <p>No steps found</p>
          </div>
        ) : (
          <div className="w-full min-h-0 overflow-x-auto overflow-y-auto border border-theme rounded-xl" style={{ flex: '0 1 auto' }}>
            <table className="w-full text-sm" style={{ tableLayout: 'auto' }}>
              <thead className="bg-theme-secondary border-b border-theme sticky top-0 z-20">
                <tr>
                  <th className="px-3 py-3 text-left font-medium text-theme-primary w-20 min-w-[80px]">Status</th>
                  <th className="px-3 py-3 text-left font-medium text-theme-primary w-32 min-w-[128px]">Counts</th>
                  <th className="px-3 py-3 text-left font-medium text-theme-primary min-w-[200px]">Step Alias</th>
                  <th className="px-3 py-3 text-left font-medium text-theme-primary min-w-[200px]">Tool ID</th>
                  <th className="px-3 py-3 text-left font-medium text-theme-primary w-40 min-w-[160px]">Start Time</th>
                  <th className="px-3 py-3 text-left font-medium text-theme-primary w-40 min-w-[160px]">End Time</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-theme">
                {loading ? (
                  // Skeleton loading rows
                  Array.from({ length: 5 }).map((_, skeletonIndex) => (
                    <tr key={`skeleton-${skeletonIndex}`} className="border border-transparent h-14">
                      <td className="px-3 py-2 w-20 min-w-[80px]">
                        <div className="h-6 bg-theme-tertiary rounded animate-pulse w-16" style={{
                          animationDelay: `${skeletonIndex * 50 + 10}ms`
                        }}></div>
                      </td>
                      <td className="px-3 py-2 w-32 min-w-[128px]">
                        <div className="h-4 bg-theme-tertiary rounded animate-pulse w-16" style={{
                          animationDelay: `${skeletonIndex * 50 + 20}ms`
                        }}></div>
                      </td>
                      <td className="px-3 py-2 min-w-[200px]">
                        <div className="h-4 bg-theme-tertiary rounded animate-pulse w-full max-w-[80%]" style={{
                          animationDelay: `${skeletonIndex * 50 + 30}ms`
                        }}></div>
                      </td>
                      <td className="px-3 py-2 min-w-[200px]">
                        <div className="h-4 bg-theme-tertiary rounded animate-pulse w-full max-w-[60%]" style={{
                          animationDelay: `${skeletonIndex * 50 + 40}ms`
                        }}></div>
                      </td>
                      <td className="px-3 py-2 w-40 min-w-[160px]">
                        <div className="h-4 bg-theme-tertiary rounded animate-pulse w-24" style={{
                          animationDelay: `${skeletonIndex * 50 + 50}ms`
                        }}></div>
                      </td>
                      <td className="px-3 py-2 w-40 min-w-[160px]">
                        <div className="h-4 bg-theme-tertiary rounded animate-pulse w-24" style={{
                          animationDelay: `${skeletonIndex * 50 + 60}ms`
                        }}></div>
                      </td>
                    </tr>
                  ))
                ) : (
                  orderedSteps.map((s) => (
                    <tr
                      key={s.id}
                      // Row identity, in render order: what the DAG ordering is
                      // asserted on end-to-end (the visible label is the node's,
                      // which several nodes may share).
                      data-step-row={s.stepAlias}
                      className="border border-transparent cursor-pointer transition-colors hover-row-datasource h-14 text-sm"
                      onClick={() => handleStepClick(s)}
                    >
                      <td className="px-3 py-2 w-20 min-w-[80px]">
                        <StatusBadge status={mapBackendStatusToStatusType(s.status)} variant="noBackground" />
                      </td>
                      <td className="px-3 py-2 w-32 min-w-[128px]">
                        {s.statusCounts ? (
                          <div className="flex flex-row items-center gap-2">
                            {s.statusCounts.completed !== undefined && s.statusCounts.completed > 0 && (
                              <span className="text-emerald-700 dark:text-emerald-300">✓ {s.statusCounts.completed}</span>
                            )}
                            {s.statusCounts.failed !== undefined && s.statusCounts.failed > 0 && (
                              <span className="text-red-700 dark:text-red-300">✗ {s.statusCounts.failed}</span>
                            )}
                            {s.statusCounts.skipped !== undefined && s.statusCounts.skipped > 0 && (
                              <span className="text-slate-700 dark:text-gray-300">⊘ {s.statusCounts.skipped}</span>
                            )}
                            {s.statusCounts.running !== undefined && s.statusCounts.running > 0 && (
                              <span className="text-blue-700 dark:text-blue-300">⟳ {s.statusCounts.running}</span>
                            )}
                          </div>
                        ) : (
                          <span className="text-theme-primary">-</span>
                        )}
                      </td>
                      <td className="px-3 py-2 min-w-[200px]">
                        {(() => {
                          const d = nodeByStepAlias.get(s.stepAlias)?.data;
                          const nc = d ? findNodeClassById(d.id || '') : null;
                          return (
                            <div className="flex items-center gap-2">
                              {d ? (
                                <NodeIcon
                                  iconSlug={getIconSlug(d)}
                                  nodeId={d.id || ''}
                                  nodeKind={d.kind}
                                  nodeFamily={nc?.family}
                                  avatarUrl={(d as any)?.agentAvatarUrl}
                                  size="xs"
                                />
                              ) : (
                                <div className={`h-5 w-5 ${nodeIconRadiusClass('xs')} bg-gray-100 dark:bg-gray-700 flex-shrink-0`} />
                              )}
                              <span className="text-theme-primary">{d?.label || s.stepAlias}</span>
                            </div>
                          );
                        })()}
                      </td>
                      <td className="px-3 py-2 min-w-[200px]">
                        <span className="text-theme-primary">{s.toolId}</span>
                      </td>
                      <td className="px-3 py-2 w-40 min-w-[160px]">
                        <span className="text-theme-primary truncate block">
                          {formatRelativeDate(s.startTime)}
                        </span>
                      </td>
                      <td className="px-3 py-2 w-40 min-w-[160px]">
                        <span className="text-theme-primary truncate block">
                          {s.endTime ? formatRelativeDate(s.endTime) : '-'}
                        </span>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </div>
  );
}


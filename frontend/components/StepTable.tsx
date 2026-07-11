'use client';

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useRouter } from '@/i18n/navigation';
import { Button } from '@/components/ui/button';
import { Trash2, Sparkles } from 'lucide-react';
import { useToast } from './Toast';
import ToastContainer from './ToastContainer';
import { StatusBadge, mapBackendStatusToStatusType } from './ui/StatusBadge';
import { orchestratorApi } from '@/lib/api/orchestrator';
import { apiClient } from '@/lib/api/api-client';
import { formatRelativeDate } from '@/lib/utils/dateFormatters';
import { getCanvasNodes } from '@/app/workflows/builder/services/canvasNodesStore';
import { nodeMatchesStep } from '@/app/workflows/builder/services/nodeMatcher';
import { getIconSlug, NodeIcon } from '@/app/workflows/builder/components/nodes/shared';
import { findNodeClassById } from '@/app/workflows/builder/nodes/nodeClasses';

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
  onAddAnalyzeBadges?: (ids: string[], type: 'data' | 'workflow') => void; // Callback to add badges directly
  onAnalyzeClick?: () => void; // Callback to close modal after analyze
}

export default function StepTable({
  workflowId,
  runId,
  className = '',
  onStepClick,
  onAddAnalyzeBadges,
  onAnalyzeClick
}: StepTableProps) {
  const router = useRouter();
  const { toasts, addToast, removeToast } = useToast();

  const [steps, setSteps] = useState<WorkflowStep[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedSteps, setSelectedSteps] = useState<Set<string | number>>(new Set());
  const [showDeleteStepsModal, setShowDeleteStepsModal] = useState(false);

  // Chargement des steps agrégés
  // Note: Uses orchestratorApi which goes through Gateway (not direct localhost calls)
  const fetchSteps = useCallback(async () => {
    if (!runId) {
      setSteps([]);
      setLoading(false);
      return;
    }

    try {
      setLoading(true);
      setError(null);

      // Use orchestratorApi to fetch aggregated steps
      const aggregatedData = await orchestratorApi.getAggregatedSteps(runId);

      // Gérer le cas où la réponse est vide ou null
      if (!aggregatedData || !Array.isArray(aggregatedData)) {
        console.warn('Empty or invalid aggregated steps response:', aggregatedData);
        setSteps([]);
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
    } catch (err) {
      console.error('Error fetching aggregated steps:', err);
      setError('Failed to load steps');
      setSteps([]);
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

  // Suppression multiple de steps
  const deleteSelectedSteps = () => {
    if (selectedSteps.size === 0) return;
    setShowDeleteStepsModal(true);
  };

  // Confirmation de suppression des steps
  const confirmDeleteSteps = async () => {
    if (selectedSteps.size === 0) return;

    const count = selectedSteps.size;
    const idsToDelete = Array.from(selectedSteps);

    try {
      // Audit 2026-05-17 round-3 - route through apiClient so OIDC token AND
      // X-Active-Organization-ID are attached. Prior raw fetch had neither,
      // making the endpoint a no-op on the auth filter side AND a cross-org
      // delete vector if the auth filter was ever bypassed.
      const deletePromises = idsToDelete.map(id =>
        apiClient.delete<void>(`/api/workflows/steps/${id}`)
          .then(() => ({ ok: true }))
          .catch(() => ({ ok: false }))
      );

      const results = await Promise.all(deletePromises);
      const failed = results.filter(r => !r.ok);

      if (failed.length > 0) {
        throw new Error(`Failed to delete ${failed.length} step(s)`);
      }

      setSteps(prev => prev.filter(s => s.id && !selectedSteps.has(s.id)));
      setSelectedSteps(new Set());
      setShowDeleteStepsModal(false);
      
      addToast({
        type: 'success',
        title: 'Steps Deleted Successfully',
        message: `${count} step(s) have been deleted successfully`
      });
    } catch (err) {
      console.error('Error deleting selected steps:', err);
      addToast({
        type: 'error',
        title: 'Error Deleting Steps',
        message: 'Failed to delete selected steps'
      });
    }
  };

  // Gestion de la sélection des steps
  const toggleStepSelection = (id: string | number) => {
    setSelectedSteps(prev => {
      const newSet = new Set(prev);
      if (newSet.has(id)) {
        newSet.delete(id);
      } else {
        newSet.add(id);
      }
      return newSet;
    });
  };

  const clearStepSelection = () => {
    setSelectedSteps(new Set());
  };

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

  // Fonction pour sélectionner tous les steps
  const selectAllSteps = useCallback(() => {
    setSelectedSteps(new Set(steps.map(s => s.id)));
  }, [steps]);

  return (
    <div className={`space-y-4 w-full h-full flex flex-col ${className}`}>
      {/* Actions contextuelles */}
      {selectedSteps.size > 0 && (
        <div className="flex items-center gap-2 mb-4 flex-shrink-0">
          {onAddAnalyzeBadges && (
            <Button 
              variant="default"
              size="sm"
              onClick={() => {
                const selectedIds = Array.from(selectedSteps);
                onAddAnalyzeBadges(selectedIds.map(id => id.toString()), 'workflow');
                // Close modal if onAnalyzeClick is provided
                if (onAnalyzeClick) {
                  onAnalyzeClick();
                }
              }}
            >
              <Sparkles className="h-4 w-4 mr-1.5" />
              Analyze Step
            </Button>
          )}
          <Button 
            variant="destructive"
            size="sm"
            onClick={deleteSelectedSteps}
          >
            <Trash2 className="h-4 w-4 mr-1.5" />
            Delete ({selectedSteps.size})
          </Button>
          <Button 
            variant="ghost"
            size="sm"
            onClick={clearStepSelection}
          >
            Clear selection
          </Button>
        </div>
      )}

      {/* Table des steps */}
      <div className="flex-1 min-h-0 w-full overflow-hidden flex flex-col">
        {!loading && steps.length === 0 ? (
          <div className="text-center py-8 text-theme-secondary h-full flex items-center justify-center">
            <p>No steps found</p>
          </div>
        ) : (
          <div className="w-full min-h-0 overflow-x-auto overflow-y-auto border border-theme rounded-xl" style={{ flex: '0 1 auto' }}>
            <table className="w-full text-sm" style={{ tableLayout: 'auto' }}>
              <thead className="bg-theme-secondary border-b border-theme sticky top-0 z-20">
                <tr>
                  <th className="px-3 py-3 text-center font-medium text-theme-primary w-12 sticky left-0 z-30 bg-theme-secondary">
                    {!loading && (
                      <div className="flex items-center justify-center">
                        <input
                          type="checkbox"
                          checked={steps.length > 0 && steps.every(s => selectedSteps.has(s.id))}
                          onChange={steps.every(s => selectedSteps.has(s.id)) ? clearStepSelection : selectAllSteps}
                          className="rounded border-theme"
                          onClick={(e) => e.stopPropagation()}
                        />
                      </div>
                    )}
                  </th>
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
                      <td className="px-3 py-2 w-12 min-w-[48px] sticky left-0 z-10">
                        <div className="h-4 bg-theme-tertiary rounded animate-pulse w-4" style={{
                          animationDelay: `${skeletonIndex * 50}ms`
                        }}></div>
                      </td>
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
                  steps.map((s) => (
                    <tr
                      key={s.id}
                      className="border border-transparent cursor-pointer transition-colors hover-row-datasource h-14 text-sm"
                      onClick={() => handleStepClick(s)}
                    >
                      <td className="px-3 py-2 w-12 min-w-[48px] sticky left-0 z-10 bg-theme-primary text-center">
                        <div className="flex items-center justify-center">
                          <input
                            type="checkbox"
                            checked={selectedSteps.has(s.id)}
                            onChange={() => toggleStepSelection(s.id)}
                            onClick={(e) => e.stopPropagation()}
                            className="rounded border-theme"
                          />
                        </div>
                      </td>
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
                          const nodes = getCanvasNodes();
                          const matched = nodes.find((n) => nodeMatchesStep(n, { stepAlias: s.stepAlias, id: s.stepAlias }));
                          const d = matched?.data;
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
                                <div className="h-5 w-5 rounded-full bg-gray-100 dark:bg-gray-700 flex-shrink-0" />
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

      {/* Modale de confirmation de suppression */}
      {showDeleteStepsModal && (
        <div className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center p-4">
          <div className="bg-theme-primary rounded-lg p-6 shadow-lg max-w-md w-full max-h-[90vh] overflow-y-auto">
            <h3 className="text-lg font-semibold text-theme-primary mb-4">Confirm Deletion</h3>
            <p className="text-sm text-theme-secondary mb-4">
              Are you sure you want to delete {selectedSteps.size} step(s)? This action cannot be undone.
            </p>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setShowDeleteStepsModal(false)}>
                Cancel
              </Button>
              <Button variant="destructive" onClick={confirmDeleteSteps}>
                Delete
              </Button>
            </div>
          </div>
        </div>
      )}

      <ToastContainer toasts={toasts} onRemoveToast={removeToast} />
    </div>
  );
}


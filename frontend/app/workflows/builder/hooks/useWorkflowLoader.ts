'use client';

import * as React from 'react';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { orchestratorApi, type EdgeState, type StepState } from '@/lib/api';
import type { Agent } from '@/lib/api/orchestrator/types';
import { normalizeLabel } from '../utils/labelNormalizer';
import { getActivePublicPreview } from '@/contexts/PublicationSnapshotContext';
import { WorkflowPlanImporter } from '../services/workflowPlanImporter/WorkflowPlanImporter';
import {
  updateNodesFromBatchSteps,
  updateDecisionNodesFromPredecessors,
  type BatchStepData,
  type BatchEdgeData,
} from '../services/statusUpdater';
import {
  updateEdgesFromBatch,
  updateLoopInternalEdges,
} from '../services/edgeStatusService';
import { nodeRegistry } from '../registry/nodeRegistry';

/**
 * Global set of runIds that were just executed from the current plan.
 * When navigating to these runs, we skip reloading the plan since it's already in memory.
 * This prevents the flash/reload when clicking "Run" button.
 */
const justExecutedRunIds = new Set<string>();

/**
 * Mark a runId as "just executed" - useWorkflowLoader will skip reloading for this run.
 * Called by useWorkflowExecution before navigation.
 */
export function markRunAsJustExecuted(runId: string): void {
  justExecutedRunIds.add(runId);
  // Auto-clear after 10 seconds (enough time for navigation to complete)
  setTimeout(() => {
    justExecutedRunIds.delete(runId);
  }, 10000);
}

/**
 * Check if a runId was just executed (should skip reload).
 */
function wasJustExecuted(runId: string): boolean {
  return justExecutedRunIds.has(runId);
}

/**
 * Clear a runId from the just-executed set (called after using the flag).
 */
function clearJustExecuted(runId: string): void {
  justExecutedRunIds.delete(runId);
}

/**
 * Converts an EdgeState from the API to BatchEdgeData format (counts).
 * In batch mode, the API now returns actual counts (completedCount, skippedCount).
 * Falls back to deriving counts from status string for backwards compatibility.
 */
export function convertEdgeStateToBatchEdge(edgeState: EdgeState): BatchEdgeData {
  const status = (edgeState.status || '').toUpperCase();

  // Use actual counts from API if available (batch mode)
  const completedCount = edgeState.completedCount || 0;
  const skippedCount = edgeState.skippedCount || 0;

  // If we have actual counts, use them
  if (completedCount > 0 || skippedCount > 0) {
    return {
      from: edgeState.from,
      to: edgeState.to,
      running: 0,
      completed: completedCount,
      skipped: skippedCount,
      statusCounts: {
        ...(completedCount > 0 && { COMPLETED: completedCount }),
        ...(skippedCount > 0 && { SKIPPED: skippedCount }),
      },
    };
  }

  // Fallback: derive from status string (single-item mode or legacy)
  const isSkipped = status === 'SKIPPED';
  const isCompleted = status === 'COMPLETED' || status === 'SUCCESS';
  const isRunning = status === 'RUNNING' || status === 'IN_PROGRESS';

  return {
    from: edgeState.from,
    to: edgeState.to,
    running: isRunning ? 1 : 0,
    completed: isCompleted ? 1 : 0,
    skipped: isSkipped ? 1 : 0,
    statusCounts: {
      ...(isSkipped && { SKIPPED: 1 }),
      ...(isCompleted && { COMPLETED: 1 }),
      ...(isRunning && { RUNNING: 1 }),
    },
  };
}

/**
 * Converts StepState from the API to BatchStepData format for node status updates.
 * Uses stepAlias for matching (same as streaming format) and includes status and statusCounts for display.
 */
export function convertStepStateToBatchStep(stepState: StepState): BatchStepData {
  const status = (stepState.status || '').toLowerCase();

  // Use stepAlias if available (matches streaming format), otherwise fall back to stepId
  const alias = stepState.stepAlias || stepState.stepId;

  // Map statusCounts from API format to streaming format if available
  // API returns: { running, success, failure, skipped, processed, total }
  // Streaming format: { running, success, failure, skipped, processed, total } (same keys)
  const statusCounts = stepState.statusCounts;

  return {
    normalizedStepId: stepState.stepId,
    stepAlias: alias,
    id: stepState.stepId,
    originalStepId: stepState.stepAlias, // Also provide alias as originalStepId for matching
    status: status,
    uiStatus: status,
    backendStatus: stepState.status,
    statusCounts: statusCounts,
  };
}

export interface PendingSnapshot {
  steps: BatchStepData[];
  edges: BatchEdgeData[];
  loops?: any[];
}

export interface UseWorkflowLoaderConfig {
  workflowId?: string;
  runId?: string;
  /** Enriched plan (e.g. publication planSnapshot) to use instead of fetching from run */
  planOverride?: any;
  setNodes: React.Dispatch<React.SetStateAction<Node<BuilderNodeData>[]>>;
  setEdges: React.Dispatch<React.SetStateAction<Edge[]>>;
  nodesRef: React.MutableRefObject<Node<BuilderNodeData>[]>;
  edgesRef: React.MutableRefObject<Edge[]>;
}

export interface UseWorkflowLoaderResult {
  isLoadingWorkflow: boolean;
  workflowLoaded: boolean;
  workflowLoadedRef: React.MutableRefObject<boolean>;
  pendingSnapshotRef: React.MutableRefObject<PendingSnapshot | null>;
  applyPendingSnapshot: (
    nodes: Node<BuilderNodeData>[],
    edges: Edge[],
    snapshot: PendingSnapshot
  ) => { updatedNodes: Node<BuilderNodeData>[]; updatedEdges: Edge[] };
  /** Full workflow response from getWorkflow (null if cross-tenant/marketplace or not yet loaded) */
  workflowData: Record<string, any> | null;
  /**
   * True when the plan fetch failed (workflow/run unreachable). Without this the
   * builder used to render a silent empty canvas with a permanently disabled
   * Save button (workflowLoaded never turns true so dirty-tracking never arms).
   */
  loadError: boolean;
  /** Re-attempt the failed load (clears loadError and re-runs the fetch). */
  retryLoad: () => void;
}

/**
 * Hook for loading workflow plans and managing pending snapshots.
 * Handles loading workflow from backend and applying pending streaming updates.
 *
 * IMPORTANT: Uses sourceKey pattern to properly reload when switching between
 * edit mode (workflow plan) and run mode (run plan).
 */
export function useWorkflowLoader(config: UseWorkflowLoaderConfig): UseWorkflowLoaderResult {
  const { workflowId, runId, planOverride, setNodes, setEdges, nodesRef, edgesRef } = config;

  const [isLoadingWorkflow, setIsLoadingWorkflow] = React.useState(false);
  const [workflowLoaded, setWorkflowLoaded] = React.useState(false);
  const [workflowData, setWorkflowData] = React.useState<Record<string, any> | null>(null);
  const [loadError, setLoadError] = React.useState(false);
  // Bumping this re-runs the load effect after a failure (its other deps don't change).
  const [loadAttempt, setLoadAttempt] = React.useState(0);
  const retryLoad = React.useCallback(() => {
    setLoadError(false);
    setLoadAttempt((n) => n + 1);
  }, []);
  const workflowLoadedRef = React.useRef(workflowLoaded);
  const pendingSnapshotRef = React.useRef<PendingSnapshot | null>(null);

  // Source key identifies which plan we're loading (workflow vs run)
  // This ensures proper reload when switching between edit and run modes
  const sourceKey = runId ? `run-${runId}` : `workflow-${workflowId}`;
  const loadedSourceKeyRef = React.useRef<string | null>(null);

  // Keep ref in sync
  React.useEffect(() => {
    workflowLoadedRef.current = workflowLoaded;
  }, [workflowLoaded]);

  // Reset workflowLoaded when source changes (mode switch)
  // BUT: Skip reload if this is a run we just executed (plan already in memory)
  React.useEffect(() => {
    if (loadedSourceKeyRef.current && loadedSourceKeyRef.current !== sourceKey) {
      // Check if this is a run we just executed - if so, skip reload
      if (runId && wasJustExecuted(runId)) {
        console.log('[WorkflowLoader] Skipping reload - run was just executed, plan already in memory:', runId);
        // Update the source key without reloading
        loadedSourceKeyRef.current = sourceKey;
        // Clear the flag since we've used it
        clearJustExecuted(runId);
        // Mark as loaded (plan is already in memory from edit mode)
        setWorkflowLoaded(true);
        workflowLoadedRef.current = true;
        return;
      }

      console.log('[WorkflowLoader] Source changed, resetting for reload:', {
        previous: loadedSourceKeyRef.current,
        current: sourceKey,
      });
      setWorkflowLoaded(false);
      workflowLoadedRef.current = false;
      pendingSnapshotRef.current = null;
    }
  }, [sourceKey, runId]);

  /**
   * Process loop events from a pending snapshot to update node iterations.
   */
  const processLoopEvents = React.useCallback((
    nodes: Node<BuilderNodeData>[],
    loops: any[]
  ): Node<BuilderNodeData>[] => {
    if (!loops || !Array.isArray(loops) || loops.length === 0) {
      return nodes;
    }

    let updatedNodes = nodes;

    loops.forEach((loopInfo: any) => {
      if (!loopInfo.payload) {
        console.warn('[WorkflowLoader] Loop event missing payload:', loopInfo);
        return;
      }

      const payload = loopInfo.payload;

      // Extract loop label for matching (use centralized normalizeLabel)
      const loopLabel = normalizeLabel(payload.loopLabel || '') || '';
      const loopNodeIdRaw = (payload.loopNodeId || loopInfo.loopId || '').toLowerCase();

      // Remove core: prefix if present
      let loopId = loopNodeIdRaw.replace(/^core:/, '');

      // Try to match gateway pattern (both old __gateway and new gateway formats)
      const gatewayMatch = loopId.match(/^while_step_(?:_)?gateway_to_while_(.+)$/);
      if (gatewayMatch) {
        loopId = gatewayMatch[1];
      }

      // Extract iteration values
      const currentIteration = payload.currentIteration ?? payload.maxIterationSeen ?? payload.completedIterations ?? payload.iteration ?? 0;
      const maxIteration = payload.maxIterations ?? 50;

      // Strategy 1: Match by label (most reliable) - use centralized normalizeLabel
      let loopNode = updatedNodes.find(n => {
        if (!nodeRegistry.isLoopNode(n)) return false;
        const nodeData = n.data as any;
        const nodeLabelNormalized = normalizeLabel(nodeData?.label || '') || '';
        if (loopLabel && nodeLabelNormalized === loopLabel) return true;
        if (loopId && nodeLabelNormalized === loopId) return true;
        return false;
      });

      // Strategy 2: Match by ID
      if (!loopNode) {
        loopNode = updatedNodes.find(n => n.id === loopId && nodeRegistry.isLoopNode(n));
      }

      // Strategy 3: Match by partial ID
      if (!loopNode) {
        const loopIdNumber = loopId.replace(/^while-/, '');
        loopNode = updatedNodes.find(n => {
          if (!nodeRegistry.isLoopNode(n)) return false;
          const nodeIdLower = n.id.toLowerCase();
          return nodeIdLower.includes(loopIdNumber) || nodeIdLower.endsWith(loopId);
        });
      }

      if (loopNode) {
        updatedNodes = updatedNodes.map(node => {
          if (node.id === loopNode!.id && nodeRegistry.isLoopNode(node)) {
            return {
              ...node,
              data: {
                ...node.data,
                currentIteration: currentIteration,
                maxIterations: maxIteration,
                totalIterations: payload.totalIterations,
                completedItems: payload.completedItems,
              },
            };
          }
          return node;
        });

        console.log(`[WorkflowLoader] Applied loop iteration: loopNode=${loopNode.id}, label=${loopLabel}, iteration=${currentIteration}/${maxIteration}`);
      } else {
        console.warn(`[WorkflowLoader] LoopNode not found for core:`, {
          loopId,
          loopLabel,
          availableLoopNodes: updatedNodes
            .filter(n => nodeRegistry.isLoopNode(n))
            .map(n => ({ id: n.id, label: (n.data as any).label }))
        });
      }
    });

    return updatedNodes;
  }, []);

  /**
   * Apply a pending snapshot to nodes and edges.
   */
  const applyPendingSnapshot = React.useCallback((
    nodes: Node<BuilderNodeData>[],
    edges: Edge[],
    snapshot: PendingSnapshot
  ): { updatedNodes: Node<BuilderNodeData>[]; updatedEdges: Edge[] } => {
    const { steps: batchSteps, edges: batchEdges, loops: pendingLoops } = snapshot;

    // Update nodes
    let updatedNodes = nodes;
    if (batchSteps.length > 0) {
      updatedNodes = updateNodesFromBatchSteps(updatedNodes, batchSteps);
    }

    // Update edges
    let updatedEdges = edges;
    if (batchEdges.length > 0) {
      updatedEdges = updateEdgesFromBatch(updatedEdges, batchEdges, updatedNodes);
      updatedEdges = updateLoopInternalEdges(updatedEdges, batchEdges, updatedNodes);
    }

    // Infer decision node status from predecessor nodes/edges
    updatedNodes = updateDecisionNodesFromPredecessors(updatedNodes, updatedEdges);

    // Process loops if any
    if (pendingLoops && pendingLoops.length > 0) {
      console.log('[WorkflowLoader] Processing loops from pending snapshot:', pendingLoops.length);
      updatedNodes = processLoopEvents(updatedNodes, pendingLoops);
    }

    return { updatedNodes, updatedEdges };
  }, [processLoopEvents]);

  // Load workflow plan when workflowId is provided
  React.useEffect(() => {
    if (!workflowId || workflowLoaded) return;

    const loadWorkflow = async () => {
      const tLoadStart = performance.now();
      console.log('[RUN-MOUNT]', tLoadStart.toFixed(0), 'useWorkflowLoader.loadWorkflow START', { workflowId, runId });
      try {
        setIsLoadingWorkflow(true);
        setLoadError(false);

        // In run mode, load plan from the run; in edit mode, load from workflow
        let plan, schedule;
        let resolvedRunId = runId;

        // Resolve "latest" to actual run ID
        if (runId === 'latest' && workflowId) {
          try {
            const latestRun = await orchestratorApi.getLatestWorkflowRun(workflowId);
            if (latestRun?.runId) {
              resolvedRunId = latestRun.runId;
              console.log('[WorkflowLoader] Resolved "latest" to actual runId:', resolvedRunId);
            } else {
              // No runs exist yet - fall back to loading plan from workflow (like edit mode)
              resolvedRunId = undefined;
              console.log('[WorkflowLoader] No runs found for "latest", falling back to workflow plan');
            }
          } catch {
            resolvedRunId = undefined;
            console.log('[WorkflowLoader] Failed to resolve "latest", falling back to workflow plan');
          }
        }

        if (resolvedRunId) {
          // Run mode: use planOverride if provided (e.g. enriched publication planSnapshot),
          // otherwise load plan from the specific run
          if (planOverride) {
            console.log('[WorkflowLoader] Using planOverride (enriched planSnapshot) instead of run plan');
            plan = planOverride;
          } else {
            try {
              const runData = await orchestratorApi.getRun(resolvedRunId);
              plan = (runData as any).plan;
            } catch (err) {
              console.error('Failed to load run:', err);
              setLoadError(true);
              setIsLoadingWorkflow(false);
              return;
            }
          }
          // Workflow metadata is optional - don't block if inaccessible (e.g. marketplace preview cross-tenant).
          // In a publication preview we MUST NOT fetch live tenant data: the publisher viewing their own
          // preview would otherwise see their post-publication metadata instead of the frozen snapshot.
          const previewCtx = getActivePublicPreview();
          if (previewCtx) {
            setWorkflowData(null);
          } else {
            try {
              const workflow = await orchestratorApi.getWorkflow(workflowId);
              schedule = (workflow as any).schedule;
              setWorkflowData(workflow as Record<string, any>);
            } catch {
              // Expected for cross-tenant preview (marketplace) - metadata is non-critical
              setWorkflowData(null);
            }
          }
        } else {
          // Edit mode: load plan from workflow
          try {
            const workflow = await orchestratorApi.getWorkflow(workflowId);
            console.log('[WorkflowLoader] Edit mode - full workflow response:', workflow);
            plan = workflow.plan;
            schedule = (workflow as any).schedule;
            setWorkflowData(workflow as Record<string, any>);
            console.log('[WorkflowLoader] Edit mode - extracted schedule:', schedule);
            // Schedule lives in a dedicated column, never in the plan JSON
          } catch (err) {
            console.error('Failed to load workflow:', err);
            setLoadError(true);
            setIsLoadingWorkflow(false);
            return;
          }
        }

        // Check if plan has valid structure (at minimum: triggers, mcps, edges arrays)
        const isValidPlan = plan &&
          Array.isArray(plan.triggers) &&
          Array.isArray(plan.mcps) &&
          Array.isArray(plan.edges);

        console.log('[AppDebug] useWorkflowLoader plan validation', {
          workflowId, resolvedRunId,
          planPresent: !!plan,
          isValidPlan,
          shape: plan ? {
            triggers: Array.isArray(plan.triggers) ? plan.triggers.length : 'NOT_ARRAY',
            mcps: Array.isArray(plan.mcps) ? plan.mcps.length : 'NOT_ARRAY',
            edges: Array.isArray(plan.edges) ? plan.edges.length : 'NOT_ARRAY',
            interfaces: Array.isArray(plan.interfaces) ? plan.interfaces.length : 'NOT_ARRAY',
          } : null,
          plan_override_used: !!planOverride,
        });

        if (!isValidPlan) {
          console.log('[AppDebug] useWorkflowLoader SKIPPING import - plan is not valid (no nodes will be set)', { workflowId });
        }
        if (isValidPlan) {
          // Convert plan to JSON string for import
          const planJson = JSON.stringify(plan);

          // Import the plan into the builder
          const importResult = await WorkflowPlanImporter.importPlan(planJson, []);
          console.log('[AppDebug] useWorkflowLoader importPlan done', {
            workflowId,
            success: importResult.success,
            nodesCount: importResult.nodes.length,
            interfaceNodeCount: importResult.nodes.filter(n => (n.data as any)?.interfaceData).length,
          });

          if (importResult.success) {
            console.log('[WorkflowLoader] Setting initial edges from import:', {
              count: importResult.edges.length,
              decisionToLoopEdges: importResult.edges.filter(e => e.target?.startsWith('while-')).map(e => ({
                id: e.id,
                source: e.source,
                target: e.target,
                sourceHandle: e.sourceHandle,
                targetHandle: e.targetHandle,
              })),
            });

            let finalNodes = importResult.nodes;
            let finalEdges = importResult.edges;

            // Resolve agent avatars for nodes with agentConfigId.
            // In a publication preview the live agents endpoint is auth'd and
            // would leak the publisher's tenant agents - fall back to the
            // _snapshot_agent_avatarUrl already enriched onto the planSnapshot
            // by the publish step (see PublicationSnapshotContext.getAgentSnapshot).
            const agentNodeIds = finalNodes.filter(n => (n.data as any).agentConfigId && !(n.data as any).agentAvatarUrl);
            if (agentNodeIds.length > 0 && !getActivePublicPreview()) {
              try {
                const agents: Agent[] = await orchestratorApi.getAgents();
                const agentMap = new Map(agents.map(a => [a.id, a]));
                finalNodes = finalNodes.map(n => {
                  const configId = (n.data as any).agentConfigId;
                  if (configId && !((n.data as any).agentAvatarUrl)) {
                    const agent = agentMap.get(configId);
                    if (agent?.avatarUrl) {
                      return { ...n, data: { ...n.data, agentAvatarUrl: agent.avatarUrl } };
                    }
                  }
                  return n;
                });
              } catch (err) {
                console.warn('[WorkflowLoader] Failed to resolve agent avatars:', err);
              }
            }

            // Update refs immediately
            nodesRef.current = importResult.nodes;
            edgesRef.current = importResult.edges;
            workflowLoadedRef.current = true;

            // Run state hydration is owned by WorkflowRunManager:
            //   useRun(runId) → manager.initialize() → fetchRunStatePossiblyPublic → store
            //   useRunStateProcessing subscriber applies batchSteps/batchEdges to React Flow
            // Loader stays plan-only - eliminates the duplicate /state GET that caused
            // the 14s mount load (run_<id>, 2026-05-03).

            // Apply pending snapshot if available
            if (pendingSnapshotRef.current) {
              console.log('[WorkflowLoader] Applying pending snapshot after workflow load', {
                steps: pendingSnapshotRef.current.steps.length,
                edges: pendingSnapshotRef.current.edges.length,
                loopsCount: pendingSnapshotRef.current.loops?.length || 0,
              });

              const { updatedNodes, updatedEdges } = applyPendingSnapshot(
                finalNodes,
                finalEdges,
                pendingSnapshotRef.current
              );

              finalNodes = updatedNodes;
              finalEdges = updatedEdges;

              console.log('[WorkflowLoader] Applied pending snapshot');
              pendingSnapshotRef.current = null;
            }

            console.log('[RUN-MOUNT]', performance.now().toFixed(0), 'useWorkflowLoader setNodes/setEdges (plan-only paint)', { nodes: finalNodes.length, edges: finalEdges.length });
            setNodes(finalNodes);
            setEdges(finalEdges);
            nodesRef.current = finalNodes;
            edgesRef.current = finalEdges;
            setWorkflowLoaded(true);
            loadedSourceKeyRef.current = sourceKey;
            console.log('[RUN-MOUNT]', performance.now().toFixed(0), `useWorkflowLoader DONE (${(performance.now() - tLoadStart).toFixed(0)}ms total)`, { sourceKey });

            // Dispatch schedule to ChatHeader for UI pre-fill
            console.log('[WorkflowLoader] Schedule before dispatch:', schedule);
            if (schedule) {
              console.log('[WorkflowLoader] Dispatching workflowViewScheduleLoaded event with schedule:', schedule);
              window.dispatchEvent(new CustomEvent('workflowViewScheduleLoaded', {
                detail: { workflowId, schedule }
              }));
            } else {
              console.log('[WorkflowLoader] No schedule to dispatch');
            }
          } else {
            // Same silent-dead-builder class as a failed fetch: without loadError
            // the canvas stays empty and Save never arms (workflowLoaded false).
            console.error('Failed to import workflow plan:', importResult.error);
            setLoadError(true);
          }
        } else {
          // No valid plan - workflow is new or empty, mark as loaded with empty canvas
          console.log('[WorkflowLoader] No valid plan found, loading empty canvas');
          nodesRef.current = [];
          edgesRef.current = [];
          workflowLoadedRef.current = true;
          setNodes([]);
          setEdges([]);
          setWorkflowLoaded(true);
          loadedSourceKeyRef.current = sourceKey;
        }
      } catch (error) {
        // Catch-all for the same dead-builder class (unexpected throw mid-load):
        // surface the error UI instead of a silent empty canvas with dead Save.
        console.error('Error loading workflow:', error);
        setLoadError(true);
      } finally {
        setIsLoadingWorkflow(false);
      }
    };

    loadWorkflow();
  }, [workflowId, runId, planOverride, workflowLoaded, sourceKey, loadAttempt, setNodes, setEdges, nodesRef, edgesRef, applyPendingSnapshot]);

  // Apply pending snapshot when workflow becomes ready (after load or when nodes/edges are populated)
  React.useEffect(() => {
    if (workflowLoaded && nodesRef.current.length > 0 && edgesRef.current.length > 0 && pendingSnapshotRef.current) {
      console.log('[WorkflowLoader] Workflow is ready, applying pending snapshot', {
        nodesCount: nodesRef.current.length,
        edgesCount: edgesRef.current.length,
        stepsCount: pendingSnapshotRef.current.steps.length,
        batchEdgesCount: pendingSnapshotRef.current.edges.length,
      });

      const { updatedNodes, updatedEdges } = applyPendingSnapshot(
        nodesRef.current,
        edgesRef.current,
        pendingSnapshotRef.current
      );

      setNodes(updatedNodes);
      setEdges(updatedEdges);
      nodesRef.current = updatedNodes;
      edgesRef.current = updatedEdges;

      console.log('[WorkflowLoader] Applied pending snapshot after workflow ready');
      pendingSnapshotRef.current = null;
    }
  }, [workflowLoaded, setNodes, setEdges, nodesRef, edgesRef, applyPendingSnapshot]);

  // Listen for version restore events to re-import plan without page reload
  React.useEffect(() => {
    const handlePlanRestore = async (event: CustomEvent) => {
      const plan = event.detail?.plan;
      if (!plan) return;

      try {
        const planJson = JSON.stringify(plan);
        const importResult = await WorkflowPlanImporter.importPlan(planJson, []);

        if (importResult.success) {
          let finalNodes = importResult.nodes;

          // Resolve agent avatars for nodes with agentConfigId - skip in
          // publication preview (live tenant data leak risk; planSnapshot
          // already carries _snapshot_agent_avatarUrl).
          const needsAvatar = finalNodes.filter(n => (n.data as any).agentConfigId && !(n.data as any).agentAvatarUrl);
          if (needsAvatar.length > 0 && !getActivePublicPreview()) {
            try {
              const agents: Agent[] = await orchestratorApi.getAgents();
              const agentMap = new Map(agents.map(a => [a.id, a]));
              finalNodes = finalNodes.map(n => {
                const configId = (n.data as any).agentConfigId;
                if (configId && !((n.data as any).agentAvatarUrl)) {
                  const agent = agentMap.get(configId);
                  if (agent?.avatarUrl) {
                    return { ...n, data: { ...n.data, agentAvatarUrl: agent.avatarUrl } };
                  }
                }
                return n;
              });
            } catch (err) {
              console.warn('[WorkflowLoader] Failed to resolve agent avatars on restore:', err);
            }
          }

          setNodes(finalNodes);
          setEdges(importResult.edges);
          nodesRef.current = finalNodes;
          edgesRef.current = importResult.edges;
          console.log('[WorkflowLoader] Plan restored from version history');
        }
      } catch (err) {
        console.error('[WorkflowLoader] Failed to restore plan:', err);
      }
    };

    window.addEventListener('workflowPlanRestore', handlePlanRestore as EventListener);
    return () => {
      window.removeEventListener('workflowPlanRestore', handlePlanRestore as EventListener);
    };
  }, [setNodes, setEdges, nodesRef, edgesRef]);

  return {
    isLoadingWorkflow,
    workflowLoaded,
    workflowLoadedRef,
    pendingSnapshotRef,
    applyPendingSnapshot,
    workflowData,
    loadError,
    retryLoad,
  };
}

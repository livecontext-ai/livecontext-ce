'use client';

import * as React from 'react';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
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
import { nodesHaveChanged, edgesHaveChanged } from '../utils/graphCompare';
import { normalizeLabel, coreKey, mcpKey } from '../utils/labelNormalizer';
import { nodeRegistry } from '../registry/nodeRegistry';
import { streamDebug } from '@/contexts/workflow-run/streamingDebug';

interface RunState {
  batchSteps?: BatchStepData[];
  batchEdges?: BatchEdgeData[];
  loops?: Array<{ loopId: string; payload: any }>;
  decisionEvaluations?: Array<{
    coreId: string;
    selectedBranch: string;
    skippedBranches?: string[];
  }>;
  workflowStatus?: { status: string; durationMs?: number };
  [key: string]: any;
}

interface UseRunStateProcessingOptions {
  runState: RunState | null;
  workflowLoaded: boolean;
  /**
   * Flips false→true once the canvas nodes are committed. The paint effects
   * below bail on an empty {@code nodesRef} and key off refs + {@code
   * workflowLoaded} only - so a pinned/async-loaded graph that flips
   * {@code workflowLoaded} true BEFORE its nodes are committed leaves the
   * status badges/edge counts unpainted until a remount. Including this reactive
   * flag re-fires the paint the moment the nodes exist. (Flips once per load.)
   */
  nodesReady?: boolean;
  nodesRef: React.MutableRefObject<Node<BuilderNodeData>[]>;
  edgesRef: React.MutableRefObject<Edge[]>;
  setNodes: (nodes: Node<BuilderNodeData>[] | ((prev: Node<BuilderNodeData>[]) => Node<BuilderNodeData>[])) => void;
  setEdges: (edges: Edge[] | ((prev: Edge[]) => Edge[])) => void;
  setWorkflowStatus: (status: 'cancelled' | 'running' | 'paused' | 'completed' | 'failed') => void;
  workflowId?: string;
  effectiveRunId?: string;
  /** When true, suppress live SSE updates (user is viewing a historical epoch) */
  isViewingHistoricalEpoch?: boolean;
}

/**
 * Hook to process run state updates from streaming/context
 * Handles:
 * - batchSteps processing -> node status updates
 * - batchEdges processing -> edge status updates
 * - loops processing -> loop iteration updates
 * - decisionEvaluations processing -> decision node status updates
 * - workflowStatus processing -> workflow completion handling
 */
export function useRunStateProcessing({
  runState,
  workflowLoaded,
  nodesReady,
  nodesRef,
  edgesRef,
  setNodes,
  setEdges,
  setWorkflowStatus,
  workflowId,
  effectiveRunId,
  isViewingHistoricalEpoch,
}: UseRunStateProcessingOptions): void {
  // Process batchSteps from context and update ReactFlow nodes
  React.useEffect(() => {
    if (isViewingHistoricalEpoch) return;
    if (!runState?.batchSteps || runState.batchSteps.length === 0) {
      return;
    }
    if (!workflowLoaded || nodesRef.current.length === 0) {

      return;
    }

    console.log('[RUN-MOUNT]', performance.now().toFixed(0), 'useRunStateProcessing batchSteps subscriber FIRES', { count: runState.batchSteps.length });
    const batchSteps = runState.batchSteps as BatchStepData[];

    // DEBUG: trace core steps received by useRunStateProcessing
    const coreInBatch = batchSteps.filter((s: any) => {
      const id = (s as any).stepId || s.normalizedStepId || s.id || '';
      return id.startsWith('core:');
    });
    streamDebug.log('RunStateProcessing', '🔍 batchSteps received:', {
      total: batchSteps.length,
      coreSteps: coreInBatch.map((s: any) => ({
        id: (s as any).stepId || s.normalizedStepId || s.id,
        status: s.status,
        statusCounts: s.statusCounts,
      })),
    });

    // Update nodes from batch steps using functional setter to always read
    // the latest positions (avoids stale ref overwriting drag positions).
    setNodes((currentNodes: Node<BuilderNodeData>[]) => {
      const updatedNodes = updateNodesFromBatchSteps(currentNodes, batchSteps);
      const hasChanges = nodesHaveChanged(currentNodes, updatedNodes);

      if (hasChanges) {
        nodesRef.current = updatedNodes as any;

        // Dispatch event for completed/failed steps to trigger data refetch
        const terminalSteps = batchSteps.filter((step: BatchStepData) => {
          const status = (step.status || '').toLowerCase();
          return status === 'completed' || status === 'failed';
        });

        if (terminalSteps.length > 0) {
          window.dispatchEvent(new CustomEvent('stepExecutionCompleted', {
            detail: {
              workflowId,
              runId: effectiveRunId,
              steps: terminalSteps.map((s: BatchStepData) => ({
                stepAlias: s.stepAlias || s.normalizedStepId,
                status: s.status,
              })),
            }
          }));
        }

        return updatedNodes as any;
      }
      return currentNodes;
    });
  }, [runState?.batchSteps, workflowLoaded, nodesReady, setNodes, nodesRef, workflowId, effectiveRunId, isViewingHistoricalEpoch]);

  // Process batchEdges from context and update ReactFlow edges
  React.useEffect(() => {
    if (isViewingHistoricalEpoch) return;
    if (!runState?.batchEdges || runState.batchEdges.length === 0) {
      return;
    }
    if (!workflowLoaded || edgesRef.current.length === 0) {
      streamDebug.warn('RunStateProcessing', 'batchEdges skipped:', {
        workflowLoaded,
        edgesCount: edgesRef.current.length,
        batchEdgesCount: runState.batchEdges.length,
      });
      return;
    }

    console.log('[RUN-MOUNT]', performance.now().toFixed(0), 'useRunStateProcessing batchEdges subscriber FIRES', { count: runState.batchEdges.length });
    streamDebug.log('RunStateProcessing', 'Processing batchEdges:', runState.batchEdges.length);

    const batchEdges = runState.batchEdges as BatchEdgeData[];

    // Update edges from batch - use functional setters to read latest state.
    // Loop internal edges (e.g. body→iterate→exit) need a second pass that
    // reads the loop-node ports to colour the iteration spokes; the loader
    // used to do this inline (now removed) so we replicate it here.
    setEdges((currentEdges: Edge[]) => {
      let updatedEdges = updateEdgesFromBatch(currentEdges, batchEdges, nodesRef.current);
      updatedEdges = updateLoopInternalEdges(updatedEdges, batchEdges, nodesRef.current);
      const hasEdgeChanges = edgesHaveChanged(currentEdges, updatedEdges);

      if (hasEdgeChanges) {
        streamDebug.log('RunStateProcessing', 'Edges updated from batchEdges (incl. loop internals)');
        edgesRef.current = updatedEdges;
        return updatedEdges;
      }
      return currentEdges;
    });

    // Update decision nodes from predecessors
    setNodes((currentNodes: Node<BuilderNodeData>[]) => {
      const updatedNodes = updateDecisionNodesFromPredecessors(currentNodes, edgesRef.current);
      const hasNodeChanges = nodesHaveChanged(currentNodes, updatedNodes);

      if (hasNodeChanges) {
        streamDebug.log('RunStateProcessing', 'Nodes updated (decision)');
        nodesRef.current = updatedNodes as any;
        return updatedNodes as any;
      }
      return currentNodes;
    });
  }, [runState?.batchEdges, workflowLoaded, nodesReady, setNodes, setEdges, nodesRef, edgesRef, isViewingHistoricalEpoch]);

  // Process decisionEvaluations from context and update decision nodes
  React.useEffect(() => {
    if (isViewingHistoricalEpoch) return;
    if (!runState?.decisionEvaluations || runState.decisionEvaluations.length === 0) return;
    if (!workflowLoaded || nodesRef.current.length === 0) return;

    streamDebug.log('RunStateProcessing', 'Processing decisionEvaluations:', runState.decisionEvaluations.length);

    // Process the latest decision evaluation
    const latestEvaluation = runState.decisionEvaluations[runState.decisionEvaluations.length - 1];
    const { coreId, selectedBranch, skippedBranches } = latestEvaluation;

    // Update decision node status + mark skipped branches in one functional setter
    setNodes((currentNodes: Node<BuilderNodeData>[]) => {
      let result = currentNodes.map((node) => {
        const nodeLabelNormalized = normalizeLabel(node.data?.label || '') || '';
        const isDecisionNode =
          nodeRegistry.isDecisionLikeNode(node) &&
          (node.id === coreId ||
            node.id.includes(coreId) ||
            coreId === coreKey(node.data?.label || '') ||
            coreId.includes(nodeLabelNormalized));

        if (isDecisionNode) {
          return {
            ...node,
            data: {
              ...node.data,
              status: 'completed' as const,
              selectedBranch,
            },
          };
        }
        return node;
      });

      // Mark skipped branches
      if (skippedBranches && skippedBranches.length > 0) {
        result = result.map((node) => {
          const nodeLabelNormalized = normalizeLabel(node.data?.label || '') || '';
          const isSkippedNode = skippedBranches.some((branch: string) =>
            node.id === branch ||
            branch === mcpKey(node.data?.label || '') ||
            branch.includes(nodeLabelNormalized)
          );

          if (isSkippedNode && node.data?.status !== 'skipped') {
            return {
              ...node,
              data: {
                ...node.data,
                status: 'skipped' as const,
              },
            };
          }
          return node;
        });
      }

      nodesRef.current = result as any;
      return result as any;
    });

    // Update skipped edges
    if (skippedBranches && skippedBranches.length > 0) {
      setEdges((currentEdges: Edge[]) => {
        const updatedEdges = currentEdges.map((edge) => {
          const targetLabelNormalized = normalizeLabel(edge.data?.targetLabel || '') || '';
          const isSkippedEdge = skippedBranches.some((branch: string) =>
            edge.target === branch ||
            edge.target.includes(branch) ||
            (targetLabelNormalized && branch.includes(targetLabelNormalized))
          );

          if (isSkippedEdge) {
            return {
              ...edge,
              data: {
                ...edge.data,
                status: 'skipped' as const,
              },
            };
          }
          return edge;
        });
        edgesRef.current = updatedEdges;
        return updatedEdges;
      });
    }
  }, [runState?.decisionEvaluations, workflowLoaded, nodesReady, setNodes, setEdges, nodesRef, edgesRef, isViewingHistoricalEpoch]);

  // Handle workflow status from context (NOT suppressed - workflow completion should always be processed)
  React.useEffect(() => {
    if (!runState?.workflowStatus) return;

    const status = runState.workflowStatus.status;

    if (status === 'running') {
      setWorkflowStatus('running');
    } else if (status === 'paused') {
      setWorkflowStatus('paused');
    } else if (status === 'completed') {
      setWorkflowStatus('completed');
    } else if (status === 'failed') {
      setWorkflowStatus('failed');
    } else if (status === 'cancelled' || status === 'stopped') {
      setWorkflowStatus('cancelled');
    }

    // Dispatch completion event for finished workflows
    if (['completed', 'failed', 'cancelled', 'stopped'].includes(status)) {
      const durationMs = runState.workflowStatus.durationMs || 0;
      window.dispatchEvent(new CustomEvent('workflowExecutionCompleted', {
        detail: {
          workflowId,
          runId: effectiveRunId,
          status: status === 'failed' ? 'failed' : status,
          durationMs,
        }
      }));
      streamDebug.log('RunStateProcessing', `Dispatched workflowExecutionCompleted: ${status} (${durationMs}ms)`);
    }
  }, [runState?.workflowStatus, workflowId, effectiveRunId, setWorkflowStatus]);
}

/**
 * Run State Synchronization Service
 *
 * Handles synchronization of workflow run state from the REST API to React Flow nodes/edges.
 * This is used in step-by-step mode where streaming is disabled.
 *
 * Single Responsibility: Transform backend run state into React Flow visual state
 */

import type { Node, Edge } from 'reactflow';
import type { WorkflowRunState, StepState, EdgeState } from '@/lib/api/orchestrator';
import type { BuilderNodeData, DerivedNodeStatus } from '../types';
import {
  updateNodesFromBatchSteps,
  updateDecisionNodesFromPredecessors,
  type BatchStepData,
  type BatchEdgeData,
} from './statusUpdater';
import {
  updateEdgesFromBatch,
} from './edgeStatusService';
import { convertStepStateToBatchStep, convertEdgeStateToBatchEdge } from '../hooks/useWorkflowLoader';
import { nodeRegistry } from '../registry/nodeRegistry';
import { normalizeStatusCounts } from '../utils/statusCounts';

// ============================================================================
// Types
// ============================================================================

interface SyncResult {
  nodes: Node<BuilderNodeData>[];
  edges: Edge[];
  hasChanges: boolean;
}

// ============================================================================
// Status Derivation
// ============================================================================

/**
 * Normalizes the step status to lowercase.
 * Backend now derives status from statusCounts, so we just use it directly.
 */
function normalizeStepStatus(status: string): string {
  return status?.toLowerCase() || 'pending';
}

// ============================================================================
// Main Sync Function
// ============================================================================

/**
 * Synchronizes the workflow run state from the REST API to React Flow nodes/edges.
 * This is the single entry point for state synchronization in step-by-step mode.
 */
export function syncRunStateToReactFlow(
  state: WorkflowRunState,
  currentNodes: Node<BuilderNodeData>[],
  currentEdges: Edge[]
): SyncResult {
  let updatedNodes = currentNodes;
  let updatedEdges = currentEdges;

  // Update nodes from steps
  if (state.steps && state.steps.length > 0) {
    const batchSteps: BatchStepData[] = state.steps.map(stepState => {
      const base = convertStepStateToBatchStep(stepState);
      const status = normalizeStepStatus(stepState.status);
      return {
        ...base,
        status,
        uiStatus: status,
      };
    });
    updatedNodes = updateNodesFromBatchSteps(updatedNodes, batchSteps);
  }

  // Update edges
  if (state.edges && state.edges.length > 0) {
    const batchEdges = state.edges.map(convertEdgeStateToBatchEdge);
    updatedEdges = updateEdgesFromBatch(updatedEdges, batchEdges, updatedNodes);
  }

  // Update decision nodes from predecessors
  updatedNodes = updateDecisionNodesFromPredecessors(updatedNodes, updatedEdges);

  // Check if there are actual changes
  const hasChanges = updatedNodes !== currentNodes || updatedEdges !== currentEdges;

  return { nodes: updatedNodes, edges: updatedEdges, hasChanges };
}

// ============================================================================
// Status Counts Sync (Lightweight - for polling)
// ============================================================================

/**
 * Response format from the optimized getStatusCounts endpoint.
 */
export interface StatusCountsResponse {
  runId: string;
  status: string;
  epoch: number;
  nodes: Record<string, Record<string, number>>;
  edges: Record<string, Record<string, number>>;
  updatedAt: string;
}

/**
 * Derives node status from status counts.
 */
function deriveStatusFromCounts(counts: Record<string, number>): DerivedNodeStatus | undefined {
  // Priority: running > failed > skipped > completed
  if (counts.running > 0) return 'running';
  if (counts.failed > 0 || counts.error > 0) return 'failed';
  if (counts.skipped > 0 && !counts.completed) return 'skipped';
  if (counts.completed > 0) return 'completed';
  return undefined;
}

/**
 * Normalizes a label for matching with backend step alias.
 * Backend uses lowercase with underscores.
 */
function normalizeForMatch(label: string): string {
  return label
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '');
}

/**
 * Applies status counts from the lightweight polling endpoint to React Flow nodes/edges.
 * This is optimized for webhook polling where we don't need full step data.
 */
export function applyStatusCountsToReactFlow(
  statusCounts: StatusCountsResponse,
  currentNodes: Node<BuilderNodeData>[],
  currentEdges: Edge[]
): SyncResult {
  let hasNodeChanges = false;
  let hasEdgeChanges = false;

  // Update nodes with status counts
  const updatedNodes = currentNodes.map(node => {
    // Try to find matching counts by normalized label
    const nodeLabel = normalizeForMatch(node.data.label || '');
    const nodeCounts = statusCounts.nodes[nodeLabel];

    if (!nodeCounts) {
      return node;
    }

    // Derive status from counts (tolerant of lowercase + uppercase via statusCounts.ts:191).
    const derivedStatus = deriveStatusFromCounts(nodeCounts);

    // Normalize to canonical UPPERCASE keys (COMPLETED/SKIPPED/…) before writing
    // to node.data.statusCounts. The polling endpoint /runs/{id}/status-counts
    // returns lowercase keys verbatim from WorkflowEpochService.buildNodeCountsFromRows
    // (line 289: `row.status().toLowerCase()`). NodeStatusBadge and several other
    // consumers read uppercase canonical keys - without this normalization, switching
    // to the "All" tab silently drops the SKIPPED badge on nodes like classify.
    // Mirrors the same normalization in statusUpdater.ts:150 used by the SSE path.
    const normalizedNodeCounts = normalizeStatusCounts(nodeCounts) ?? nodeCounts;

    // Check if anything changed
    const currentCounts = node.data.statusCounts;
    const countsChanged = JSON.stringify(currentCounts) !== JSON.stringify(normalizedNodeCounts);
    const statusChanged = derivedStatus && derivedStatus !== node.data.status;

    if (!countsChanged && !statusChanged) {
      return node;
    }

    hasNodeChanges = true;
    return {
      ...node,
      data: {
        ...node.data,
        status: derivedStatus || node.data.status,
        statusCounts: normalizedNodeCounts,
      },
    };
  });

  // Update edges with status counts
  const updatedEdges = currentEdges.map(edge => {
    // Edge ID format in response: "trigger:webhook->agent:xxx" or "mcp:a->mcp:b"
    // We need to find matching edges
    const sourceNode = currentNodes.find(n => n.id === edge.source);
    const targetNode = currentNodes.find(n => n.id === edge.target);

    if (!sourceNode || !targetNode) {
      return edge;
    }

    const sourceLabel = normalizeForMatch(sourceNode.data.label || '');
    const targetLabel = normalizeForMatch(targetNode.data.label || '');

    // Determine source/target prefixes based on node types
    const sourceType = sourceNode.type;
    const targetType = targetNode.type;

    // Map React Flow node types to backend prefixes using nodeRegistry
    const getSourcePrefixes = (type: string | undefined, node?: Node<BuilderNodeData>): string[] => {
      if (!node) return ['mcp', 'agent'];
      return nodeRegistry.getPrefixesForNode(node);
    };

    const getTargetPrefixes = (type: string | undefined, node?: Node<BuilderNodeData>): string[] => {
      if (!node) return ['mcp', 'agent'];
      return nodeRegistry.getPrefixesForNode(node);
    };

    const sourcePrefixes = getSourcePrefixes(sourceType, sourceNode);
    const targetPrefixes = getTargetPrefixes(targetType, targetNode);

    // Generate all possible edge key combinations
    const edgeKeys: string[] = [];
    for (const sp of sourcePrefixes) {
      for (const tp of targetPrefixes) {
        edgeKeys.push(`${sp}:${sourceLabel}->${tp}:${targetLabel}`);
      }
    }
    // Also try without prefixes
    edgeKeys.push(`${sourceLabel}->${targetLabel}`);

    let edgeCounts: Record<string, number> | undefined;
    for (const key of edgeKeys) {
      if (statusCounts.edges[key]) {
        edgeCounts = statusCounts.edges[key];
        break;
      }
    }

    if (!edgeCounts) {
      return edge;
    }

    // Derive edge status from counts
    const edgeStatus = deriveStatusFromCounts(edgeCounts);

    // Same UPPERCASE normalization as the node path - keeps edge badges
    // consistent across SSE + polling write sources.
    const normalizedEdgeCounts = normalizeStatusCounts(edgeCounts) ?? edgeCounts;

    // Check if anything changed
    const currentCounts = (edge.data as any)?.statusCounts;
    const currentStatus = (edge.data as any)?.status;
    if (JSON.stringify(currentCounts) === JSON.stringify(normalizedEdgeCounts) && currentStatus === edgeStatus) {
      return edge;
    }

    hasEdgeChanges = true;
    return {
      ...edge,
      data: {
        ...edge.data,
        status: edgeStatus || (edge.data as any)?.status,
        statusCounts: normalizedEdgeCounts,
      },
    };
  });

  return {
    nodes: hasNodeChanges ? updatedNodes : currentNodes,
    edges: hasEdgeChanges ? updatedEdges : currentEdges,
    hasChanges: hasNodeChanges || hasEdgeChanges,
  };
}

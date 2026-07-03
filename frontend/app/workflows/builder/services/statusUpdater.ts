/**
 * Status Updater Service
 * Updates node/edge status and statusCounts from streaming data.
 *
 * Responsibilities:
 * - Update nodes from batch step events
 * - Update edges from batch edge events
 * - Handle interface nodes (frontend-only)
 * - Handle decision nodes
 */

import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData, DerivedNodeStatus, StatusCounts } from '../types';
import { normalizeStatusCounts, deriveStatusFromCounts } from '../utils/statusCounts';
import { nodeMatchesStep } from './nodeMatcher';
import { edgeMatchesBatchEdge } from './edgeMatcher';
import { normalizeLabel } from '../utils/labelNormalizer';
import { BatchEdgeData } from './edgeStatusService';
import { nodeRegistry } from '../registry/nodeRegistry';
import { streamDebug } from '@/contexts/workflow-run/streamingDebug';

// ============================================================================
// Types
// ============================================================================

export interface BatchStepData {
  normalizedStepId?: string;
  stepAlias?: string;
  originalStepId?: string;
  id?: string;
  uiStatus?: string;
  status?: string;
  backendStatus?: string;
  statusCounts?: Record<string, number>;
  message?: string;
  executionTime?: number;
  timestamp?: number;
  triggerId?: string;
  tenantId?: string;
  output?: {
    selectedBranch?: string;
    skippedBranches?: string[];
    [key: string]: unknown;
  };
  // Browser-agent live-view coordinates forwarded by StepEventBuilder
  // for agent:browser_agent steps. snake_case mirrors the wire format
  // (StateUtils.STEP_FIELDS_TO_KEEP allowlists these so they survive
  // the sanitize pass). Mapped into camelCase `lastBrowser*` fields on
  // the node data in updateNodeFromStep so BrowserLiveCdpPanel can
  // open the WS bridge. `run_id` is preferred over the legacy `runId`
  // on the wire, but we accept both for compat.
  runId?: string;
  run_id?: string;
  node_id?: string;
  // Control node id (tool-call id) when the browser session is hosted by a
  // GENERIC agent node - the event's nodeId addresses the HOST builder node.
  control_node_id?: string;
  session_id?: string;
  cdp_token?: string;
  cdp_ws_url?: string;
  step_index?: number;
  last_action?: string;
  cost_usd?: number;
  // Last URL the browser-agent landed on - surfaced in the node header so users
  // can see where the run is without opening the live-view WS.
  current_url?: string;
}

// Re-export BatchEdgeData from edgeStatusService for compatibility
export type { BatchEdgeData };

/**
 * Unified node event from streaming system
 */
export interface UnifiedNodeEvent {
  nodeId: string;
  nodeType: 'TRIGGER' | 'STEP' | 'DECISION' | 'LOOP_CONTROLLER' | 'MERGE';
  status: string;
  statusCounts?: Record<string, number>;
  timestamp?: number;
  decisionData?: {
    condition?: string;
    branches?: Record<string, { selected: number; skipped: number }>;
  };
  loopData?: {
    condition?: string;
    maxIterations?: number;
    iterationCounts?: Record<string, number>;
  };
  mergeData?: {
    strategy?: string;
    branches?: Record<string, { selected: number; skipped: number }>;
  };
}

/**
 * Unified edge state from streaming system
 */
export interface UnifiedEdgeState {
  edgeId: string;
  from: string;
  to: string;
  edgeType: 'SIMPLE' | 'IF_BRANCH' | 'ELSEIF_BRANCH' | 'ELSE_BRANCH' | 'POST_ACTION' | 'LOOP_ELSE' | 'LOOP_BODY' | 'LOOP_EXIT';
  status: string;
  statusCounts?: Record<string, number>;
  condition?: string;
  timestamp?: number;
}

// ============================================================================
// Utilities
// ============================================================================

function statusCountsEqual(
  a: Record<string, number> | undefined,
  b: Record<string, number> | undefined
): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  const keysA = Object.keys(a);
  const keysB = Object.keys(b);
  if (keysA.length !== keysB.length) return false;
  for (const key of keysA) {
    if (a[key] !== b[key]) return false;
  }
  return true;
}

/**
 * Returns true if statusCounts contain terminal results (completed or failed > 0).
 * Used to override "pending" status from backend when counts show the node has executed.
 */
function hasTerminalCounts(counts: Record<string, number>): boolean {
  return (counts.COMPLETED ?? 0) > 0 || (counts.FAILED ?? 0) > 0;
}

function toNodeStatus(raw?: string): DerivedNodeStatus {
  if (!raw) return 'pending';
  const v = raw.toLowerCase();
  if (['success', 'completed', 'complete', 'successful', 'done'].includes(v)) return 'completed';
  if (['failed', 'error', 'errored', 'failure'].includes(v)) return 'failed';
  if (['partial_success', 'partial-success'].includes(v)) return 'partial_success';
  if (['skipped', 'skip'].includes(v)) return 'skipped';
  if (['running', 'in_progress', 'processing', 'starting', 'started'].includes(v)) return 'running';
  if (['awaiting_signal', 'awaiting-signal', 'awaiting'].includes(v)) return 'awaiting_signal';
  return 'pending';
}

function updateNodeFromStep(
  node: Node<BuilderNodeData>,
  step: BatchStepData
): Node<BuilderNodeData> {
  const normalizedCounts = normalizeStatusCounts(step.statusCounts);
  const rawStatus = step.uiStatus || step.status || step.backendStatus;

  // Use new statusCounts if available, otherwise preserve existing
  const finalStatusCounts = normalizedCounts ?? node.data.statusCounts;

  // Status comes from the backend (authoritative). The backend already handles
  // priority logic (e.g. awaiting_signal overrides NodeCounts-derived status).
  // Only fall back to deriveStatusFromCounts when no raw status is available.
  // Exception: when backend says "pending" but statusCounts have terminal data
  // (e.g. waiting_trigger with closed epochs), derive from counts instead.
  let nodeStatus: DerivedNodeStatus;
  if (rawStatus) {
    const mapped = toNodeStatus(rawStatus);
    if (mapped === 'pending' && finalStatusCounts && hasTerminalCounts(finalStatusCounts)) {
      // Backend says pending but counts have terminal data (e.g. waiting_trigger with closed epochs)
      nodeStatus = deriveStatusFromCounts(finalStatusCounts);
    } else if ((mapped === 'failed' || mapped === 'completed') && finalStatusCounts && hasTerminalCounts(finalStatusCounts)) {
      // Backend says failed/completed but counts may show mixed results across epochs.
      // Let deriveStatusFromCounts decide (it returns partial_success when both completed > 0 and failed > 0).
      nodeStatus = deriveStatusFromCounts(finalStatusCounts);
    } else {
      nodeStatus = mapped;
    }
  } else {
    nodeStatus = finalStatusCounts ? deriveStatusFromCounts(finalStatusCounts) : 'pending';
  }

  // Browser-agent live-view fields. These arrive in snake_case on the
  // wire (StepEventBuilder forwards them from the node's raw output) and
  // we re-emit as camelCase `lastBrowser*` so BrowserAgentNode can hand
  // them to BrowserLiveCdpPanel without further transformation. Only
  // present on agent:browser_agent step events; no-ops elsewhere.
  const browserFields: Partial<BuilderNodeData> = {};
  if (typeof step.session_id === 'string' && step.session_id.length > 0) {
    browserFields.lastBrowserSessionId = step.session_id;
  }
  if (typeof step.cdp_token === 'string' && step.cdp_token.length > 0) {
    browserFields.lastBrowserCdpToken = step.cdp_token;
  }
  if (typeof step.cdp_ws_url === 'string' && step.cdp_ws_url.length > 0) {
    browserFields.lastBrowserCdpWsUrl = step.cdp_ws_url;
  }
  // run_id (snake) is the wire format; runId (camel) accepted as legacy.
  const wireRunId = (typeof step.run_id === 'string' && step.run_id.length > 0)
    ? step.run_id
    : (typeof step.runId === 'string' && step.runId.length > 0)
      ? step.runId
      : undefined;
  if (wireRunId) {
    browserFields.lastBrowserRunId = wireRunId;
  }
  // Control node id (tool-call id) for a browser session hosted by a
  // GENERIC agent node - the event's own nodeId is the HOST builder node
  // for display matching, while REST control stays keyed by this id.
  if (typeof step.control_node_id === 'string' && step.control_node_id.length > 0) {
    browserFields.lastBrowserNodeId = step.control_node_id;
  }
  if (typeof step.step_index === 'number') {
    browserFields.lastBrowserStepIndex = step.step_index;
  }
  if (typeof step.last_action === 'string' && step.last_action.length > 0) {
    browserFields.lastBrowserAction = step.last_action;
  }
  if (typeof step.cost_usd === 'number') {
    browserFields.lastBrowserCostUsd = step.cost_usd;
  }
  if (typeof step.current_url === 'string' && step.current_url.length > 0) {
    browserFields.lastBrowserCurrentUrl = step.current_url;
  }

  return {
    ...node,
    data: {
      ...node.data,
      status: nodeStatus,
      statusCounts: finalStatusCounts,
      ...browserFields,
    },
  };
}

// ============================================================================
// Node Updates
// ============================================================================

/**
 * Updates all nodes from batch steps.
 * Priority: Loop children first, then regular nodes.
 */
export function updateNodesFromBatchSteps(
  nodes: Node<BuilderNodeData>[],
  batchSteps: BatchStepData[]
): Node<BuilderNodeData>[] {
  if (batchSteps.length === 0) return nodes;

  // Pre-process streaming steps to populate stepAlias and normalizedStepId from available fields.
  // SseSnapshotService sends {id, label, status, statusCounts} but nodeMatchesStep()
  // relies on stepAlias/normalizedStepId for matching - without this, agent/guardrail/classify/crud
  // nodes fail to match because their node.data.id is static (e.g. "ai-agent").
  const processedSteps: BatchStepData[] = batchSteps.map(step => {
    if (step.stepAlias && step.normalizedStepId) return step;
    const rawStep = step as Record<string, unknown>;
    return {
      ...step,
      normalizedStepId: step.normalizedStepId || step.id,
      stepAlias: step.stepAlias || (rawStep.label as string) || step.id,
    };
  });

  // Index core steps (decision, switch, loop, split, download_file, http_request, transform, wait) - all use core: prefix
  const controlSteps = processedSteps.filter(s => {
    // Check all possible fields that might contain the step ID
    const stepId = (s as any).stepId || s.normalizedStepId || s.id || '';
    if (s.stepAlias?.startsWith('core:') ||
        s.normalizedStepId?.startsWith('core:') ||
        s.id?.startsWith('core:') ||
        stepId.startsWith('core:')) {
      return true;
    }
    // Check if this step's alias matches any control flow node label
    const stepAlias = s.stepAlias || (s as any).stepId || s.normalizedStepId || s.id || '';
    const normalizedAlias = normalizeLabel(stepAlias.replace(/^core:/, ''));
    return nodes.some(n => {
      const isControlNode = nodeRegistry.isCoreNode(n);
      return isControlNode && normalizeLabel(n.data.label) === normalizedAlias;
    });
  });
  const decisionSteps = controlSteps;

  // Helper to check if a node is a core node (uses core: prefix in streaming events)
  // Delegates to nodeRegistry for centralized node type detection
  const isCoreNode = (n: Node<BuilderNodeData>): boolean => {
    return nodeRegistry.isCoreNode(n);
  };

  return nodes.map(node => {
    // Handle core nodes (decision, switch, download_file, http_request, transform, wait)
    // All these use core: prefix in streaming events
    if (isCoreNode(node)) {
      const nodeLabel = normalizeLabel(node.data.label);
      const matchingStep = decisionSteps.find(step => {
        // Check stepId first (streaming format), then fallback to other fields
        const stepId = (step as any).stepId || step.normalizedStepId || step.id || '';
        const stepAlias = step.stepAlias || stepId;
        const stepLabel = normalizeLabel(stepAlias.replace(/^core:/, ''));
        return stepLabel === nodeLabel;
      });

      if (matchingStep) {
        const normalizedCounts = normalizeStatusCounts(matchingStep.statusCounts);

        // Use new statusCounts if available, otherwise preserve existing
        const finalStatusCounts = normalizedCounts ?? node.data.statusCounts;

        // Status comes from the backend (authoritative), same as updateNodeFromStep.
        // Exception: when backend says "pending" but statusCounts have terminal data,
        // derive from counts (e.g. waiting_trigger with closed epochs).
        const coreRawStatus = matchingStep.uiStatus || matchingStep.status;
        let nodeStatus: DerivedNodeStatus;
        if (coreRawStatus) {
          const mapped = toNodeStatus(coreRawStatus);
          if (mapped === 'pending' && finalStatusCounts && hasTerminalCounts(finalStatusCounts)) {
            nodeStatus = deriveStatusFromCounts(finalStatusCounts);
          } else {
            nodeStatus = mapped;
          }
        } else {
          nodeStatus = finalStatusCounts ? deriveStatusFromCounts(finalStatusCounts) : 'pending';
        }

        streamDebug.log('statusUpdater', `🔍 core node matched: label=${nodeLabel}, status=${nodeStatus}, counts=`, finalStatusCounts);

        return {
          ...node,
          data: {
            ...node.data,
            status: nodeStatus,
            statusCounts: finalStatusCounts,
          },
        };
      }
      // DEBUG: log unmatched core nodes that had statusCounts
      if (node.data.statusCounts) {
        streamDebug.warn('statusUpdater', `⚠️ core node NOT matched, will LOSE statusCounts: label=${nodeLabel}`, {
          existingCounts: node.data.statusCounts,
          availableSteps: decisionSteps.map(s => (s as any).stepId || s.normalizedStepId || s.id),
        });
      }
      return node;
    }

    // Handle regular nodes (agent, guardrail, classify, crud, mcp, trigger)
    const matchingStep = processedSteps.find(step => nodeMatchesStep(node, step));
    return matchingStep ? updateNodeFromStep(node, matchingStep) : node;
  });
}

// ============================================================================
// Edge Updates
// ============================================================================

function updateEdgeFromBatchEdge(
  edge: Edge,
  batchEdge: BatchEdgeData,
  sourceNode: Node<BuilderNodeData>,
  targetNode: Node<BuilderNodeData>
): Edge {
  const normalizedCounts = normalizeStatusCounts(batchEdge.statusCounts);
  const running = batchEdge.running ?? normalizedCounts?.RUNNING ?? 0;
  const completed = batchEdge.completed ?? normalizedCounts?.COMPLETED ?? 0;
  const skipped = batchEdge.skipped ?? normalizedCounts?.SKIPPED ?? 0;

  let edgeStatus: DerivedNodeStatus = 'pending';
  if (running > 0) edgeStatus = 'running';
  else if (completed > 0) edgeStatus = 'completed';
  else if (skipped > 0) edgeStatus = 'skipped';

  return {
    ...edge,
    // Preserve existing statusCounts if new ones are undefined (streaming events may not have them)
    data: { ...edge.data, status: edgeStatus, statusCounts: normalizedCounts ?? edge.data?.statusCounts },
  };
}

/**
 * Updates edges from batch edge data.
 * Note: Loop internal edges are handled by edgeStatusService.updateLoopInternalEdges.
 */
export function updateEdgesFromBatchEdges(
  edges: Edge[],
  batchEdges: BatchEdgeData[],
  nodes: Node<BuilderNodeData>[]
): Edge[] {
  if (batchEdges.length === 0) return edges;

  const nodeMap = new Map(nodes.map(n => [n.id, n]));

  return edges.map(edge => {
    const sourceNode = nodeMap.get(edge.source);
    const targetNode = nodeMap.get(edge.target);
    if (!sourceNode || !targetNode) return edge;

    // Find matching batch edge
    const matchingBatchEdge = batchEdges.find(be =>
      edgeMatchesBatchEdge(edge, be, sourceNode, targetNode, nodes)
    );

    return matchingBatchEdge
      ? updateEdgeFromBatchEdge(edge, matchingBatchEdge, sourceNode, targetNode)
      : edge;
  });
}

/**
 * Updates edges based on source node status (step-by-step mode fallback).
 */
export function updateEdgesFromNodes(
  edges: Edge[],
  nodes: Node<BuilderNodeData>[]
): Edge[] {
  const nodeMap = new Map(nodes.map(n => [n.id, n]));

  return edges.map(edge => {
    const sourceNode = nodeMap.get(edge.source);
    if (!sourceNode) return edge;

    const sourceStatus = sourceNode.data.status;
    if (!sourceStatus || sourceStatus === 'pending') return edge;

    const sourceStatusCounts = sourceNode.data.statusCounts;
    if (
      edge.data?.status === sourceStatus &&
      statusCountsEqual(edge.data?.statusCounts, sourceStatusCounts)
    ) {
      return edge;
    }

    return {
      ...edge,
      data: { ...edge.data, status: sourceStatus, statusCounts: sourceStatusCounts },
    };
  });
}

/**
 * Updates edges entering decision nodes (fallback when streaming doesn't send edge data).
 */
export function updateDecisionNodeEntryEdges(
  edges: Edge[],
  nodes: Node<BuilderNodeData>[]
): Edge[] {
  const nodeMap = new Map(nodes.map(n => [n.id, n]));

  return edges.map(edge => {
    const sourceNode = nodeMap.get(edge.source);
    const targetNode = nodeMap.get(edge.target);
    if (!sourceNode || !targetNode) return edge;
    if (!nodeRegistry.isDecisionLikeNode(targetNode)) return edge;

    // Skip if edge already has statusCounts from backend
    if (edge.data?.statusCounts && Object.keys(edge.data.statusCounts).length > 0) {
      return edge;
    }

    // Copy status from source node
    const sourceStatus = sourceNode.data.status || 'pending';
    const sourceStatusCounts = sourceNode.data.statusCounts;

    if (sourceStatus === 'pending') return edge;
    if (
      edge.data?.status === sourceStatus &&
      statusCountsEqual(edge.data?.statusCounts, sourceStatusCounts)
    ) {
      return edge;
    }

    return {
      ...edge,
      data: { ...edge.data, status: sourceStatus, statusCounts: sourceStatusCounts },
    };
  });
}

/**
 * Updates edges from trigger nodes to decision nodes.
 */
export function updateEdgesFromTriggerNodes(
  edges: Edge[],
  nodes: Node<BuilderNodeData>[]
): Edge[] {
  const nodeMap = new Map(nodes.map(n => [n.id, n]));

  return edges.map(edge => {
    const sourceNode = nodeMap.get(edge.source);
    const targetNode = nodeMap.get(edge.target);
    if (!sourceNode || !targetNode) return edge;

    const isTrigger = sourceNode.data.kind === 'entry';
    if (!isTrigger || !nodeRegistry.isDecisionLikeNode(targetNode)) return edge;

    // Skip if edge already has statusCounts from backend
    if (edge.data?.statusCounts && Object.keys(edge.data.statusCounts).length > 0) {
      return edge;
    }

    const triggerStatus = sourceNode.data.status || 'pending';
    const triggerStatusCounts = sourceNode.data.statusCounts;

    if (
      edge.data?.status === triggerStatus &&
      statusCountsEqual(edge.data?.statusCounts, triggerStatusCounts)
    ) {
      return edge;
    }

    return {
      ...edge,
      data: { ...edge.data, status: triggerStatus, statusCounts: triggerStatusCounts },
    };
  });
}

// ============================================================================
// Decision Node Updates
// ============================================================================

/**
 * Updates decision nodes based on outgoing edges (fallback).
 */
export function updateDecisionNodesFromEdges(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
  batchEdges: BatchEdgeData[]
): Node<BuilderNodeData>[] {
  if (batchEdges.length === 0) return nodes;

  return nodes.map(node => {
    if (!nodeRegistry.isDecisionLikeNode(node)) return node;

    // Skip if already has statusCounts
    if (node.data.statusCounts && Object.keys(node.data.statusCounts).length > 0) {
      return node;
    }

    // Skip if already has non-pending status
    if (node.data.status && node.data.status !== 'pending') {
      return node;
    }

    const outgoingEdges = edges.filter(e => e.source === node.id);
    if (outgoingEdges.length === 0) return node;

    // Check if any outgoing edge has activity
    const hasActivity = outgoingEdges.some(e =>
      (e.data?.status && e.data.status !== 'pending') ||
      (e.data?.statusCounts && Object.keys(e.data.statusCounts).length > 0)
    );

    if (hasActivity) {
      const edgeStatuses = outgoingEdges
        .map(e => e.data?.status)
        .filter((s): s is DerivedNodeStatus => !!s && s !== 'pending');

      let decisionStatus: DerivedNodeStatus = 'pending';
      if (edgeStatuses.includes('running')) decisionStatus = 'running';
      else if (edgeStatuses.includes('completed') || edgeStatuses.includes('failed')) {
        decisionStatus = 'completed';
      } else if (edgeStatuses.every(s => s === 'skipped')) {
        decisionStatus = 'skipped';
      }

      if (decisionStatus !== 'pending' && node.data.status !== decisionStatus) {
        return { ...node, data: { ...node.data, status: decisionStatus } };
      }
    }

    return node;
  });
}

/**
 * Updates decision nodes from their predecessor nodes.
 */
export function updateDecisionNodesFromPredecessors(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[]
): Node<BuilderNodeData>[] {
  const nodeMap = new Map(nodes.map(n => [n.id, n]));

  return nodes.map(node => {
    if (!nodeRegistry.isDecisionLikeNode(node)) return node;

    // Decision nodes get their statusCounts from the backend (aggregated from child steps)
    // This function only propagates status from predecessors if the decision hasn't been executed yet
    if (node.data.status && node.data.status !== 'pending') {
      // Decision already has a status from backend, preserve it and statusCounts
      return node;
    }

    const incomingEdges = edges.filter(e => e.target === node.id);
    if (incomingEdges.length === 0) return node;

    // CRITICAL: Preserve existing statusCounts from backend (set by updateNodesFromBatchSteps)
    // Don't overwrite with edge/source counts which may be incomplete or missing
    const existingStatusCounts = node.data.statusCounts;

    for (const incomingEdge of incomingEdges) {
      // Check edge status first
      if (incomingEdge.data?.status && incomingEdge.data.status !== 'pending') {
        return {
          ...node,
          data: {
            ...node.data,
            status: incomingEdge.data.status as DerivedNodeStatus,
            statusCounts: existingStatusCounts || incomingEdge.data.statusCounts,
          },
        };
      }

      // Check source node status
      const sourceNode = nodeMap.get(incomingEdge.source);
      if (sourceNode?.data.status && sourceNode.data.status !== 'pending') {
        return {
          ...node,
          data: {
            ...node.data,
            status: sourceNode.data.status,
            statusCounts: existingStatusCounts || sourceNode.data.statusCounts,
          },
        };
      }
    }

    // No status to propagate, return node as-is (preserving existing statusCounts)
    return node;
  });
}

// ============================================================================
// Converters (for compatibility with different streaming formats)
// ============================================================================

export function convertNodeEventToStepData(node: UnifiedNodeEvent | Record<string, unknown>): BatchStepData {
  const unifiedNode = node as UnifiedNodeEvent;
  const recordNode = node as Record<string, unknown>;

  const nodeId = unifiedNode.nodeId || (recordNode.normalizedStepId as string) || (recordNode.id as string) || '';
  const parts = nodeId.split(':');
  const alias = (recordNode.stepAlias as string) || (parts.length > 1 ? parts.slice(1).join(':') : nodeId);

  return {
    normalizedStepId: nodeId,
    stepAlias: alias,
    id: nodeId,
    status: unifiedNode.status || (recordNode.status as string),
    uiStatus: recordNode.uiStatus as string,
    backendStatus: (recordNode.backendStatus as string) || unifiedNode.status || (recordNode.status as string),
    statusCounts: unifiedNode.statusCounts || (recordNode.statusCounts as Record<string, number> | undefined),
    message: recordNode.message as string,
    executionTime: recordNode.executionTime as number,
    timestamp: unifiedNode.timestamp || (recordNode.timestamp as number),
  };
}

export function convertEdgeStateToEdgeData(edge: UnifiedEdgeState | Record<string, unknown>): BatchEdgeData {
  const unifiedEdge = edge as UnifiedEdgeState;
  const recordEdge = edge as Record<string, unknown>;

  const statusCounts = unifiedEdge.statusCounts || (recordEdge.statusCounts as Record<string, number> | undefined);
  const success = statusCounts?.COMPLETED ?? statusCounts?.completed ?? statusCounts?.SUCCESS ?? statusCounts?.success ?? (recordEdge.completed as number) ?? 0;
  const skipped = statusCounts?.SKIPPED ?? statusCounts?.skipped ?? (recordEdge.skipped as number) ?? 0;
  const running = statusCounts?.RUNNING ?? statusCounts?.running ?? (recordEdge.running as number) ?? 0;

  return {
    id: unifiedEdge.edgeId || (recordEdge.id as string),
    from: unifiedEdge.from || (recordEdge.from as string),
    to: unifiedEdge.to || (recordEdge.to as string),
    running,
    completed: success,
    skipped,
    statusCounts,
  };
}

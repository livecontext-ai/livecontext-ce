/**
 * Edge Status Service
 * Updates edge statuses from streaming batch data using normalized label-based matching.
 *
 * Principle: Direct label matching - no fallbacks, no guessing.
 * Backend sends: mcp:a->mcp:b format for internal edges
 *               source_key->target_key for regular edges
 */

import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData, NodeStatus } from '../types';
import { normalizeLabel, extractCoreLabelWithoutPort, extractAgentLabelWithoutPort, extractPortFromRef } from '../utils/labelNormalizer';
import { normalizeStatusCounts } from '../utils/statusCounts';
import { nodeRegistry } from '../registry/nodeRegistry';

// ============================================================================
// Types
// ============================================================================

export interface BatchEdgeData {
  id?: string;
  from?: string;
  to?: string;
  running?: number;
  completed?: number;
  skipped?: number;
  statusCounts?: Record<string, number>;
}

// ============================================================================
// Core Utilities - Delegates to nodeRegistry
// ============================================================================

/**
 * Checks if a node is a Wait node based on its data.id or kind.
 */
function isWaitNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isWaitNode(node);
}

/**
 * Checks if a node is a Transform node based on its data.id or kind.
 */
function isTransformNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isTransformNode(node);
}

/**
 * Checks if a node is a Download File node based on its data.id or kind.
 */
function isDownloadFileNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isDownloadFileNode(node);
}

/**
 * Computes the backend key for a node based on its type and label.
 * Delegates to nodeRegistry for centralized backend key mapping.
 */
export function computeNodeBackendKey(node: Node<BuilderNodeData>): string | null {
  const label = normalizeLabel(node.data.label);
  if (!label) return null;

  return nodeRegistry.computeBackendKey(node, label);
}

/**
 * Cleans batch edge reference by stripping # suffix, ports, and normalizing.
 * "core:WhileA#then:..." -> "core:whilea"
 * "core:check:if" -> "core:check" (strips core port)
 * "agent:ai_sorter:category_0" -> "agent:ai_sorter" (strips agent port)
 */
function cleanBatchRef(ref: string | undefined): string {
  if (!ref) return '';
  const cleanRef = ref.split('#')[0];

  // For core: refs, strip port suffixes (if, else, case_0, branch_0, etc.)
  if (cleanRef.startsWith('core:')) {
    const label = extractCoreLabelWithoutPort(cleanRef);
    return label ? `core:${label}` : cleanRef.toLowerCase();
  }

  // For agent: refs, strip port suffixes (category_0, pass, fail, etc.)
  if (cleanRef.startsWith('agent:')) {
    const label = extractAgentLabelWithoutPort(cleanRef);
    return label ? `agent:${label}` : cleanRef.toLowerCase();
  }

  const colonIndex = cleanRef.indexOf(':');
  if (colonIndex === -1) return cleanRef.toLowerCase();

  const type = cleanRef.substring(0, colonIndex).toLowerCase();
  const label = cleanRef.substring(colonIndex + 1);
  const normalizedLabel = normalizeLabel(label);
  return normalizedLabel ? `${type}:${normalizedLabel}` : cleanRef.toLowerCase();
}

/**
 * Extracts branch type from batch edge's 'to' field.
 */
function extractBranchFromBatchEdge(batchEdge: BatchEdgeData): string | null {
  const to = batchEdge.to || '';
  const hashIndex = to.indexOf('#');
  if (hashIndex === -1) return null;

  const suffix = to.substring(hashIndex + 1);
  if (suffix.startsWith('then:')) return 'then';
  if (suffix.startsWith('elsif:')) return 'elsif';
  if (suffix.startsWith('else:')) return 'else';
  return null;
}

/**
 * Extracts branch type from edge's source handle.
 * Delegates to nodeRegistry for centralized branch type extraction.
 */
function extractBranchFromEdge(edge: Edge, sourceNode: Node<BuilderNodeData>): string | null {
  return nodeRegistry.extractBranchType(edge.sourceHandle, sourceNode);
}

/**
 * Derives edge status from batch edge data.
 */
function deriveEdgeStatus(batchEdge: BatchEdgeData): NodeStatus {
  const running = batchEdge.running ?? 0;
  const completed = batchEdge.completed ?? 0;
  const skipped = batchEdge.skipped ?? 0;

  if (running > 0) return 'running';
  if (completed > 0) return 'completed';
  if (skipped > 0) return 'skipped';
  return 'pending';
}

/**
 * Creates normalized status counts from batch edge.
 */
function createEdgeStatusCounts(batchEdge: BatchEdgeData): Record<string, number> | undefined {
  if (batchEdge.statusCounts) {
    return normalizeStatusCounts(batchEdge.statusCounts);
  }

  const counts: Record<string, number> = {};
  if (batchEdge.running && batchEdge.running > 0) counts.RUNNING = batchEdge.running;
  if (batchEdge.completed && batchEdge.completed > 0) counts.COMPLETED = batchEdge.completed;
  if (batchEdge.skipped && batchEdge.skipped > 0) counts.SKIPPED = batchEdge.skipped;

  return Object.keys(counts).length > 0 ? counts : undefined;
}

/**
 * Extracts the backend port from a frontend edge's sourceHandle based on node type.
 * Returns the port name that matches backend edge refs (e.g., "category_0", "if", "case_0").
 */
function extractPortFromEdgeHandle(edge: Edge, sourceNode: Node<BuilderNodeData>): string | null {
  const sourceHandle = edge.sourceHandle;
  if (!sourceHandle) return null;

  // Classify node: map category ID to category_N port
  if (nodeRegistry.isClassifyNode(sourceNode)) {
    const categories = ((sourceNode.data as any).classifyCategories as any[]) || [];
    for (let i = 0; i < categories.length; i++) {
      const category = categories[i];
      if (category.id === sourceHandle || sourceHandle.includes(category.id)) {
        return `category_${i}`;
      }
    }
    return null;
  }

  // Guardrail node: pass/fail
  if (nodeRegistry.isGuardrailNode(sourceNode)) {
    const handle = sourceHandle.toLowerCase();
    if (handle.includes('pass')) return 'pass';
    if (handle.includes('fail')) return 'fail';
    return null;
  }

  // Decision node: if/else/elseif_N
  if (nodeRegistry.isDecisionNode(sourceNode)) {
    const handle = sourceHandle.toLowerCase();
    if (handle === 'then' || handle.includes('-if')) return 'if';
    if (handle.startsWith('elsif') || handle.includes('-elseif')) {
      const match = handle.match(/elseif[-_]?(\d+)/i);
      if (match) return `elseif_${match[1]}`;
      return 'elseif_0';
    }
    if (handle === 'else' || handle.includes('-else')) return 'else';
    return null;
  }

  // Switch node: case_N/default
  if (nodeRegistry.isSwitchNode(sourceNode)) {
    const handle = sourceHandle.toLowerCase();
    if (handle.includes('default')) return 'default';
    const match = handle.match(/case[-_](\d+)/);
    if (match) return `case_${match[1]}`;
    return null;
  }

  // Option node: choice_N
  if (nodeRegistry.isOptionNode(sourceNode)) {
    const handle = sourceHandle.toLowerCase();
    const match = handle.match(/choice[-_](\d+)/);
    if (match) return `choice_${match[1]}`;
    return null;
  }

  // Fork node: branch_N
  if (nodeRegistry.isForkNode(sourceNode)) {
    const handle = sourceHandle.toLowerCase();
    const match = handle.match(/branch[-_](\d+)/);
    if (match) return `branch_${match[1]}`;
    return null;
  }

  // Approval node: approved/rejected/timeout
  // sourceHandle is the approvalOutput ID (e.g., "user-approval-xxx-path-0")
  // Backend port is the label lowercased (e.g., "approved", "rejected", "timeout")
  if (nodeRegistry.isUserApprovalNode(sourceNode)) {
    const outputs = ((sourceNode.data as any).approvalOutputs as any[]) || [];
    for (const output of outputs) {
      if (output.id === sourceHandle || sourceHandle.includes(output.id)) {
        return output.label?.toLowerCase() || null;
      }
    }
    return null;
  }

  // While group node: body/exit
  if (nodeRegistry.isWhileGroupNode(sourceNode)) {
    if (sourceHandle.endsWith('-body')) return 'body';
    if (sourceHandle.endsWith('-exit')) return 'exit';
    return null;
  }

  return null;
}

// ============================================================================
// Main Functions
// ============================================================================

/**
 * Indexes batch edges by from->to key for O(1) lookup.
 */
function indexBatchEdges(batchEdges: BatchEdgeData[]): Map<string, BatchEdgeData[]> {
  const index = new Map<string, BatchEdgeData[]>();

  for (const be of batchEdges) {
    const fromKey = cleanBatchRef(be.from);
    const toKey = cleanBatchRef(be.to);
    if (!fromKey || !toKey) continue;

    const key = `${fromKey}->${toKey}`;
    const existing = index.get(key) || [];
    existing.push(be);
    index.set(key, existing);
  }

  return index;
}

/**
 * Updates regular edges (non-loop-internal) from batch edge data.
 */
export function updateEdgesFromBatch(
  edges: Edge[],
  batchEdges: BatchEdgeData[],
  nodes: Node<BuilderNodeData>[]
): Edge[] {
  if (batchEdges.length === 0) return edges;

  const nodeMap = new Map(nodes.map(n => [n.id, n]));
  const batchEdgeIndex = indexBatchEdges(batchEdges);

  return edges.map(edge => {
    const sourceNode = nodeMap.get(edge.source);
    const targetNode = nodeMap.get(edge.target);
    if (!sourceNode || !targetNode) return edge;

    const sourceKey = computeNodeBackendKey(sourceNode);
    const targetKey = computeNodeBackendKey(targetNode);
    if (!sourceKey || !targetKey) return edge;

    const lookupKey = `${sourceKey}->${targetKey}`;
    const matchingBatchEdges = batchEdgeIndex.get(lookupKey);
    if (!matchingBatchEdges || matchingBatchEdges.length === 0) return edge;

    // For branching nodes, match by port (category_0, if, case_0, etc.)
    let matchingBatchEdge: BatchEdgeData | null = null;

    if (nodeRegistry.isBranchingNode(sourceNode)) {
      // Port-based matching: extract port from both batch edge and frontend edge
      const edgePort = extractPortFromEdgeHandle(edge, sourceNode);
      for (const be of matchingBatchEdges) {
        const batchPort = extractPortFromRef(be.from);
        if (edgePort && batchPort) {
          if (edgePort === batchPort) {
            matchingBatchEdge = be;
            break;
          }
        } else if (!edgePort && !batchPort) {
          // Neither has a port - legacy match
          matchingBatchEdge = be;
          break;
        }
      }

      // Fallback: legacy branch matching (for backward compatibility with # suffix format)
      if (!matchingBatchEdge) {
        const edgeBranch = extractBranchFromEdge(edge, sourceNode);
        for (const be of matchingBatchEdges) {
          const batchBranch = extractBranchFromBatchEdge(be);
          if (edgeBranch && batchBranch && edgeBranch === batchBranch) {
            matchingBatchEdge = be;
            break;
          }
        }
      }

      // Final fallback: take last match if only one batch edge and no port info
      if (!matchingBatchEdge && matchingBatchEdges.length === 1) {
        matchingBatchEdge = matchingBatchEdges[0];
      }
    } else {
      // Non-branching: take last match (most recent)
      matchingBatchEdge = matchingBatchEdges[matchingBatchEdges.length - 1];
    }

    if (!matchingBatchEdge) return edge;

    let newStatus = deriveEdgeStatus(matchingBatchEdge);
    const newStatusCounts = createEdgeStatusCounts(matchingBatchEdge);

    // Edge coherence: a FAILED source node's outgoing edges are persisted as
    // `skipped` in StateSnapshot (that skipped count is load-bearing for merge
    // convergence and MUST stay skipped there - see EdgeStatusEmitter). But a
    // grey "skipped" edge is visually identical to a branch a SUCCEEDING node
    // simply didn't take (e.g. a guardrail that passed greys its fail-edge), so
    // a failed node looked indistinguishable from a passing one. When the source
    // node itself failed, render its skipped outgoing edges as `failed` (red) to
    // match the red node. This only affects edge colour, never convergence.
    if (newStatus === 'skipped' && sourceNode.data?.status === 'failed') {
      newStatus = 'failed';
    }

    // Skip if unchanged
    if (
      edge.data?.status === newStatus &&
      JSON.stringify(edge.data?.statusCounts) === JSON.stringify(newStatusCounts)
    ) {
      return edge;
    }

    return {
      ...edge,
      data: { ...edge.data, status: newStatus, statusCounts: newStatusCounts },
    };
  });
}

/**
 * Updates While group edges (body/exit/loop-back) from batch edge data.
 *
 * While edges use port-based handles (while-{id}-body, while-{id}-exit, while-{id}-loop-back)
 * that map to backend ports (core:label:body, core:label:exit, core:label:iterate).
 * Since whileGroupNode has hasPorts=false, updateEdgesFromBatch handles them via
 * the non-branching path. This function adds port-aware matching for disambiguation
 * when multiple While edges share the same source→target key.
 */
export function updateLoopInternalEdges(
  edges: Edge[],
  batchEdges: BatchEdgeData[],
  nodes: Node<BuilderNodeData>[]
): Edge[] {
  // Find While group nodes
  const whileNodes = nodes.filter(n => nodeRegistry.isWhileGroupNode(n));
  if (whileNodes.length === 0) return edges;

  const nodeMap = new Map(nodes.map(n => [n.id, n]));

  // Collect backend keys for While nodes
  const whileKeySet = new Set<string>();
  for (const wn of whileNodes) {
    const key = computeNodeBackendKey(wn);
    if (key) whileKeySet.add(key);
  }

  // Filter batch edges that involve While nodes with ports (body/exit/iterate)
  const whileBatchEdges: BatchEdgeData[] = [];
  for (const be of batchEdges) {
    const fromPort = extractPortFromRef(be.from);
    const toPort = extractPortFromRef(be.to);

    if (fromPort && be.from?.startsWith('core:')) {
      const coreLabel = extractCoreLabelWithoutPort(be.from);
      if (coreLabel && whileKeySet.has(`core:${coreLabel}`)) {
        whileBatchEdges.push(be);
        continue;
      }
    }
    if (toPort && be.to?.startsWith('core:')) {
      const coreLabel = extractCoreLabelWithoutPort(be.to);
      if (coreLabel && whileKeySet.has(`core:${coreLabel}`)) {
        whileBatchEdges.push(be);
      }
    }
  }

  if (whileBatchEdges.length === 0) return edges;

  return edges.map(edge => {
    const sourceNode = nodeMap.get(edge.source);
    const targetNode = nodeMap.get(edge.target);
    if (!sourceNode || !targetNode) return edge;

    // While as source: body/exit edges
    if (nodeRegistry.isWhileGroupNode(sourceNode) && edge.sourceHandle) {
      let port: string | null = null;
      if (edge.sourceHandle.endsWith('-body')) port = 'body';
      else if (edge.sourceHandle.endsWith('-exit')) port = 'exit';

      if (port) {
        const whileKey = computeNodeBackendKey(sourceNode);
        const targetKey = computeNodeBackendKey(targetNode);
        if (!whileKey || !targetKey) return edge;

        const match = whileBatchEdges.find(be => {
          const batchFromPort = extractPortFromRef(be.from);
          return batchFromPort === port &&
                 cleanBatchRef(be.from) === whileKey &&
                 cleanBatchRef(be.to) === targetKey;
        });

        if (match) {
          const newStatus = deriveEdgeStatus(match);
          const newStatusCounts = createEdgeStatusCounts(match);
          if (edge.data?.status === newStatus &&
              JSON.stringify(edge.data?.statusCounts) === JSON.stringify(newStatusCounts)) {
            return edge;
          }
          return {
            ...edge,
            data: { ...edge.data, status: newStatus, statusCounts: newStatusCounts },
          };
        }
      }
    }

    // While as target: loop-back edge (iterate port)
    if (nodeRegistry.isWhileGroupNode(targetNode) && edge.targetHandle?.endsWith('-loop-back')) {
      const sourceKey = computeNodeBackendKey(sourceNode);
      const whileKey = computeNodeBackendKey(targetNode);
      if (!sourceKey || !whileKey) return edge;

      const match = whileBatchEdges.find(be => {
        const batchToPort = extractPortFromRef(be.to);
        return batchToPort === 'iterate' &&
               cleanBatchRef(be.from) === sourceKey &&
               cleanBatchRef(be.to) === whileKey;
      });

      if (match) {
        const newStatus = deriveEdgeStatus(match);
        const newStatusCounts = createEdgeStatusCounts(match);
        if (edge.data?.status === newStatus &&
            JSON.stringify(edge.data?.statusCounts) === JSON.stringify(newStatusCounts)) {
          return edge;
        }
        return {
          ...edge,
          data: { ...edge.data, status: newStatus, statusCounts: newStatusCounts },
        };
      }
    }

    return edge;
  });
}


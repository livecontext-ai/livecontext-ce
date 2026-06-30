/**
 * ValidationCache - Caches computed data for validation performance
 *
 * This cache is built once per validation run and shared across all rules.
 * It prevents redundant computations like node categorization and label normalization.
 */

import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import type { ValidationCache, WorkflowNodeType } from './types';
import { categorizeNodes, getNodeType } from './nodeUtils';
import { normalizeLabel } from '../../../utils/labelNormalizer';
import { nodeRegistry } from '../../../registry/nodeRegistry';

/**
 * Builds the validation cache from nodes and edges
 */
export function buildValidationCache(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[]
): ValidationCache {
  // Categorize nodes by type
  const nodesByType = categorizeNodes(nodes);

  // Build node lookup by ID
  const nodeById = new Map<string, Node<BuilderNodeData>>();
  for (const node of nodes) {
    nodeById.set(node.id, node);
  }

  // Build label map for duplicate detection
  const labelMap = new Map<string, Array<{ node: Node<BuilderNodeData>; nodeType: WorkflowNodeType }>>();
  for (const node of nodes) {
    const label = node.data?.label;
    if (!label || label.trim() === '') continue;

    const normalized = normalizeLabel(label);
    if (!normalized) continue;

    const nodeType = getNodeType(node);
    const existing = labelMap.get(normalized) || [];
    existing.push({ node, nodeType });
    labelMap.set(normalized, existing);
  }

  // Build edge lookup maps
  const edgesBySource = new Map<string, Edge[]>();
  const edgesByTarget = new Map<string, Edge[]>();
  for (const edge of edges) {
    // By source
    const sourceEdges = edgesBySource.get(edge.source) || [];
    sourceEdges.push(edge);
    edgesBySource.set(edge.source, sourceEdges);

    // By target
    const targetEdges = edgesByTarget.get(edge.target) || [];
    targetEdges.push(edge);
    edgesByTarget.set(edge.target, targetEdges);
  }

  return {
    nodesByType,
    nodeById,
    labelMap,
    edgesBySource,
    edgesByTarget,
  };
}

/**
 * Computes reachable nodes from triggers using BFS
 * This is cached and only computed if needed by connectivity validation
 */
export function computeReachableNodes(cache: ValidationCache): Set<string> {
  if (cache.reachableNodes) {
    return cache.reachableNodes;
  }

  const reachable = new Set<string>();
  const queue: string[] = [];

  // Start from all triggers
  for (const trigger of cache.nodesByType.triggers) {
    queue.push(trigger.id);
    reachable.add(trigger.id);
  }

  // BFS traversal
  while (queue.length > 0) {
    const nodeId = queue.shift()!;
    const outgoingEdges = cache.edgesBySource.get(nodeId) || [];

    for (const edge of outgoingEdges) {
      if (!reachable.has(edge.target)) {
        reachable.add(edge.target);
        queue.push(edge.target);
      }
    }
  }

  cache.reachableNodes = reachable;
  return reachable;
}

/**
 * Detects cycles in the workflow graph using iterative DFS
 * Returns a map of nodeId -> cycle path (if node is part of a cycle)
 *
 * Note: Self-loops on loop nodes are intentional and not reported as cycles.
 * Loop nodes use self-referential edges to represent iteration.
 */
export function detectCycles(cache: ValidationCache): Map<string, string[]> {
  if (cache.cycles) {
    return cache.cycles;
  }

  const cycles = new Map<string, string[]>();
  const visited = new Set<string>();
  const recursionStack = new Set<string>();
  const pathStack: string[] = [];

  // Get loop node IDs to exclude their self-loops from cycle detection
  const loopNodeIds = new Set(cache.nodesByType.cores
    .filter((node) => nodeRegistry.isLoopNode(node))
    .map((node) => node.id));

  function dfs(nodeId: string): void {
    visited.add(nodeId);
    recursionStack.add(nodeId);
    pathStack.push(nodeId);

    const outgoingEdges = cache.edgesBySource.get(nodeId) || [];
    for (const edge of outgoingEdges) {
      const targetId = edge.target;

      // Skip back-edges - these are intentional loop edges, not cycles
      if (edge.data?.isBackEdge) {
        continue;
      }

      // Skip edges to loop-back handles on while/loop nodes (intentional iteration)
      if (loopNodeIds.has(targetId) && edge.targetHandle?.endsWith('-loop-back')) {
        continue;
      }

      // Skip self-loops on loop nodes - these are intentional for iteration
      if (targetId === nodeId && loopNodeIds.has(nodeId)) {
        continue;
      }

      if (!visited.has(targetId)) {
        dfs(targetId);
      } else if (recursionStack.has(targetId)) {
        // Found a cycle - extract the cycle path
        const cycleStartIndex = pathStack.indexOf(targetId);
        const cyclePath = [...pathStack.slice(cycleStartIndex), targetId];

        // Only report cycles with more than one unique node
        // (self-loops on non-loop nodes are still reported)
        const uniqueNodes = new Set(cyclePath);
        if (uniqueNodes.size > 1 || !loopNodeIds.has(targetId)) {
          // Mark all nodes in the cycle
          for (const cycleNodeId of cyclePath) {
            if (!cycles.has(cycleNodeId)) {
              cycles.set(cycleNodeId, cyclePath);
            }
          }
        }
      }
    }

    pathStack.pop();
    recursionStack.delete(nodeId);
  }

  // Start DFS from all nodes (not just triggers, to catch disconnected cycles)
  for (const nodeId of cache.nodeById.keys()) {
    if (!visited.has(nodeId)) {
      dfs(nodeId);
    }
  }

  cache.cycles = cycles;
  return cycles;
}

/**
 * Computes reachable nodes PER trigger (for DAG independence validation)
 * Returns a map: triggerId -> Set of reachable nodeIds
 */
export function computeReachableNodesPerTrigger(
  cache: ValidationCache
): Map<string, Set<string>> {
  const result = new Map<string, Set<string>>();

  for (const trigger of cache.nodesByType.triggers) {
    const reachable = new Set<string>();
    const queue: string[] = [];

    // BFS from this trigger
    const outgoing = cache.edgesBySource.get(trigger.id) || [];
    for (const edge of outgoing) {
      if (!reachable.has(edge.target)) {
        reachable.add(edge.target);
        queue.push(edge.target);
      }
    }

    while (queue.length > 0) {
      const nodeId = queue.shift()!;
      const edges = cache.edgesBySource.get(nodeId) || [];
      for (const edge of edges) {
        if (!reachable.has(edge.target)) {
          reachable.add(edge.target);
          queue.push(edge.target);
        }
      }
    }

    result.set(trigger.id, reachable);
  }

  return result;
}

/**
 * Calculates workflow complexity score (aligned with backend)
 */
export function calculateComplexityScore(cache: ValidationCache, edges: Edge[]): number {
  let score = 0;

  // 2 points per mcp
  score += cache.nodesByType.mcps.length * 2;

  // 5 points per agent (more complex than steps)
  score += cache.nodesByType.agents.length * 5;

  // 1 point per edge
  score += edges.length;

  // 3 points per trigger
  score += cache.nodesByType.triggers.length * 3;

  // 10 points per control node (decision/loop)
  score += cache.nodesByType.cores.length * 10;

  // TODO: Add merge node detection when supported
  // TODO: Add graph level calculation when supported

  return score;
}

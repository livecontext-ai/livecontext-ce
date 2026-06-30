/**
 * Pure graph-mutation helpers shared by the canvas context menu and keyboard
 * shortcuts. Kept side-effect free (no React, no Date.now) so the call sites
 * stay testable and deterministic - callers pass a `seed` for new IDs.
 */
import type { Edge, Node, XYPosition } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { stripRuntimeProps } from './nodeDataUtils';

/**
 * Forward-reachable descendant node IDs of `rootId` (excludes `rootId` itself).
 * Back-edges (`edge.data.isBackEdge`) are skipped so loop-backs don't drag the
 * whole graph into the selection; a `visited` set guards any other cycle.
 */
export function computeDownstreamNodeIds(rootId: string, edges: Edge[]): string[] {
  const adjacency = new Map<string, string[]>();
  for (const edge of edges) {
    if (edge.data?.isBackEdge) continue;
    const list = adjacency.get(edge.source);
    if (list) list.push(edge.target);
    else adjacency.set(edge.source, [edge.target]);
  }

  const visited = new Set<string>([rootId]);
  const queue: string[] = [rootId];
  const result: string[] = [];
  while (queue.length > 0) {
    const current = queue.shift()!;
    for (const next of adjacency.get(current) ?? []) {
      if (!visited.has(next)) {
        visited.add(next);
        result.push(next);
        queue.push(next);
      }
    }
  }
  return result;
}

/** True when the node is the source or target of at least one edge. */
export function nodeHasConnections(nodeId: string, edges: Edge[]): boolean {
  return edges.some((edge) => edge.source === nodeId || edge.target === nodeId);
}

/** Removes every edge whose source OR target is in `nodeIds`. */
export function removeEdgesTouchingNodes(edges: Edge[], nodeIds: Set<string>): Edge[] {
  return edges.filter((edge) => !nodeIds.has(edge.source) && !nodeIds.has(edge.target));
}

/**
 * A port handle can embed its owner node ID (e.g. `split-<id>-exit`, `<id>-if`).
 * When a node is cloned to a new ID, rewrite those handles so the copied edges
 * keep pointing at the copied node's ports rather than the original's.
 */
function remapHandle(handle: string | null | undefined, oldId: string, newId: string): string | null | undefined {
  if (!handle) return handle;
  return handle.split(oldId).join(newId);
}

export interface CloneOptions {
  /**
   * Collision-free seed for the generated IDs (callers pass `Date.now()` at the
   * call site so this helper stays deterministic in tests).
   */
  seed: string | number;
  /**
   * Flow-coordinate target for the cloned group's top-left corner. When given,
   * the whole group is translated so its bounding box starts there (paste at
   * cursor). When omitted, `offset` is applied instead (in-place duplicate).
   */
  position?: XYPosition | null;
  /** Fixed translation used when `position` is not provided. Defaults to +40,+40. */
  offset?: XYPosition;
}

export interface CloneResult {
  nodes: Node<BuilderNodeData>[];
  edges: Edge[];
  newIds: string[];
}

/**
 * Clones a set of nodes (and only the edges fully internal to that set) with
 * fresh IDs, stripped runtime props, and a translated position. Used by both
 * paste (position-based) and duplicate (offset-based). Entry-interface flags are
 * cleared on the copies - only one entry interface is allowed per workflow.
 */
export function cloneNodesForPaste(
  sourceNodes: Node<BuilderNodeData>[],
  sourceEdges: Edge[],
  options: CloneOptions,
): CloneResult {
  if (sourceNodes.length === 0) return { nodes: [], edges: [], newIds: [] };

  const offset = options.offset ?? { x: 40, y: 40 };
  const minX = Math.min(...sourceNodes.map((node) => node.position?.x ?? 0));
  const minY = Math.min(...sourceNodes.map((node) => node.position?.y ?? 0));
  const deltaX = options.position ? options.position.x - minX : offset.x;
  const deltaY = options.position ? options.position.y - minY : offset.y;

  const idMap = new Map<string, string>();
  const newNodes: Node<BuilderNodeData>[] = sourceNodes.map((node, index) => {
    const baseId = node.data?.id ?? node.id;
    const newId = `${baseId}-${options.seed}-${index}`;
    idMap.set(node.id, newId);

    const cleanedData = stripRuntimeProps(node.data) as BuilderNodeData;
    const interfaceData = (cleanedData as unknown as Record<string, unknown>).interfaceData as
      | { isEntryInterface?: boolean; [key: string]: unknown }
      | undefined;
    const data: BuilderNodeData = {
      ...cleanedData,
      id: newId,
      ...(interfaceData?.isEntryInterface
        ? { interfaceData: { ...interfaceData, isEntryInterface: false } }
        : {}),
    };

    return {
      ...node,
      id: newId,
      position: {
        x: (node.position?.x ?? 0) + deltaX,
        y: (node.position?.y ?? 0) + deltaY,
      },
      selected: true,
      data,
    };
  });

  const newEdges: Edge[] = [];
  sourceEdges.forEach((edge, index) => {
    const newSource = idMap.get(edge.source);
    const newTarget = idMap.get(edge.target);
    if (!newSource || !newTarget) return; // skip edges leaving the copied set
    newEdges.push({
      ...edge,
      id: `edge-${newSource}-${newTarget}-${options.seed}-${index}`,
      source: newSource,
      target: newTarget,
      sourceHandle: remapHandle(edge.sourceHandle, edge.source, newSource),
      targetHandle: remapHandle(edge.targetHandle, edge.target, newTarget),
      selected: false,
    });
  });

  return { nodes: newNodes, edges: newEdges, newIds: newNodes.map((node) => node.id) };
}

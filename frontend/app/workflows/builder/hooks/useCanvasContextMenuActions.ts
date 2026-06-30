'use client';

import * as React from 'react';
import type { Edge, Node, XYPosition } from 'reactflow';
import type { BuilderNodeData, PaletteDragItem, PaletteItem } from '../types';
import { getPaletteItemDataFromId } from '../nodes/nodeClasses';
import { nodeClipboard } from '../services/nodeClipboard';
import {
  cloneNodesForPaste,
  computeDownstreamNodeIds,
  removeEdgesTouchingNodes,
} from '../utils/graphMutations';

/**
 * The full set of canvas operations exposed to the context menu and the
 * keyboard shortcuts. Each operates on the RAW graph state (not the decorated
 * `preparedNodes`), so it stays free of the runtime callbacks `usePreparedGraph`
 * injects.
 */
export interface CanvasContextMenuActions {
  copyNode: (nodeId: string) => void;
  copySelection: () => void;
  paste: (position?: XYPosition | null) => void;
  duplicateNode: (nodeId: string) => void;
  duplicateSelection: () => void;
  selectDownstream: (nodeId: string) => void;
  disconnectNode: (nodeId: string) => void;
  addNoteNear: (nodeId: string) => void;
  deleteNode: (nodeId: string) => void;
  deleteSelection: () => void;
  selectAll: () => void;
}

interface UseCanvasContextMenuActionsParams {
  nodes: Node<BuilderNodeData>[];
  edges: Edge[];
  selectedNodeIds: string[];
  setNodes: React.Dispatch<React.SetStateAction<Node<BuilderNodeData>[]>>;
  setEdges: React.Dispatch<React.SetStateAction<Edge[]>>;
  setSelectedNodeIds: React.Dispatch<React.SetStateAction<string[]>>;
  onCreateNode: (item: PaletteDragItem | PaletteItem, position: XYPosition, options?: { parentId?: string }) => void;
}

let pasteSeed = 0;
/** Monotonic seed so clones never collide even within the same millisecond. */
function nextSeed(): string {
  pasteSeed += 1;
  return `${Date.now()}-${pasteSeed}`;
}

/**
 * Builds the canvas context-menu / shortcut actions. Reads graph state through
 * refs so the returned handlers stay referentially stable (they are consumed as
 * deps of the canvas render path, where churn can trigger React #185 cascades).
 */
export function useCanvasContextMenuActions({
  nodes,
  edges,
  selectedNodeIds,
  setNodes,
  setEdges,
  setSelectedNodeIds,
  onCreateNode,
}: UseCanvasContextMenuActionsParams): CanvasContextMenuActions {
  const nodesRef = React.useRef(nodes);
  const edgesRef = React.useRef(edges);
  const selectedRef = React.useRef(selectedNodeIds);
  const onCreateNodeRef = React.useRef(onCreateNode);
  // Keep the latest graph state on refs (updated after commit) so the returned
  // handlers stay referentially stable - they are read only from user-triggered
  // callbacks, which always run after the effect has synced these.
  React.useEffect(() => {
    nodesRef.current = nodes;
    edgesRef.current = edges;
    selectedRef.current = selectedNodeIds;
    onCreateNodeRef.current = onCreateNode;
  });

  /** The nodes a per-node action applies to: the whole selection when the node
   *  is part of a multi-selection, otherwise just that node. */
  const resolveTargets = React.useCallback((nodeId: string): string[] => {
    const selected = selectedRef.current;
    return selected.length > 1 && selected.includes(nodeId) ? selected : [nodeId];
  }, []);

  const copyIds = React.useCallback((ids: string[]) => {
    if (ids.length === 0) return;
    const idSet = new Set(ids);
    const pickedNodes = nodesRef.current.filter((node) => idSet.has(node.id));
    if (pickedNodes.length === 0) return;
    const internalEdges = edgesRef.current.filter((edge) => idSet.has(edge.source) && idSet.has(edge.target));
    nodeClipboard.set({ nodes: pickedNodes, edges: internalEdges });
  }, []);

  const cloneIntoCanvas = React.useCallback(
    (
      sourceNodes: Node<BuilderNodeData>[],
      sourceEdges: Edge[],
      opts: { position?: XYPosition | null },
    ) => {
      const { nodes: cloned, edges: clonedEdges, newIds } = cloneNodesForPaste(sourceNodes, sourceEdges, {
        seed: nextSeed(),
        position: opts.position,
      });
      if (cloned.length === 0) return;
      setNodes((prev) => [...prev, ...cloned]);
      if (clonedEdges.length > 0) setEdges((prev) => [...prev, ...clonedEdges]);
      setSelectedNodeIds(newIds);
    },
    [setNodes, setEdges, setSelectedNodeIds],
  );

  const duplicateIds = React.useCallback(
    (ids: string[]) => {
      if (ids.length === 0) return;
      const idSet = new Set(ids);
      const pickedNodes = nodesRef.current.filter((node) => idSet.has(node.id));
      if (pickedNodes.length === 0) return;
      const internalEdges = edgesRef.current.filter((edge) => idSet.has(edge.source) && idSet.has(edge.target));
      cloneIntoCanvas(pickedNodes, internalEdges, { position: null });
    },
    [cloneIntoCanvas],
  );

  const deleteIds = React.useCallback(
    (ids: string[]) => {
      if (ids.length === 0) return;
      const idSet = new Set(ids);
      setNodes((prev) => prev.filter((node) => !idSet.has(node.id)));
      setEdges((prev) => removeEdgesTouchingNodes(prev, idSet));
      setSelectedNodeIds((prev) => prev.filter((id) => !idSet.has(id)));
    },
    [setNodes, setEdges, setSelectedNodeIds],
  );

  return React.useMemo<CanvasContextMenuActions>(
    () => ({
      copyNode: (nodeId) => copyIds(resolveTargets(nodeId)),
      copySelection: () => copyIds(selectedRef.current),
      paste: (position) => {
        const payload = nodeClipboard.get();
        if (!payload) return;
        cloneIntoCanvas(payload.nodes, payload.edges, { position: position ?? null });
      },
      duplicateNode: (nodeId) => duplicateIds(resolveTargets(nodeId)),
      duplicateSelection: () => duplicateIds(selectedRef.current),
      selectDownstream: (nodeId) => {
        const downstream = computeDownstreamNodeIds(nodeId, edgesRef.current);
        setSelectedNodeIds([nodeId, ...downstream]);
      },
      disconnectNode: (nodeId) => {
        const idSet = new Set(resolveTargets(nodeId));
        setEdges((prev) => removeEdgesTouchingNodes(prev, idSet));
      },
      addNoteNear: (nodeId) => {
        const node = nodesRef.current.find((candidate) => candidate.id === nodeId);
        const item = getPaletteItemDataFromId('note');
        if (!item) return;
        const position: XYPosition = node
          ? { x: node.position?.x ?? 0, y: (node.position?.y ?? 0) + (node.height ?? 80) + 40 }
          : { x: 0, y: 0 };
        onCreateNodeRef.current(item, position);
      },
      deleteNode: (nodeId) => deleteIds(resolveTargets(nodeId)),
      deleteSelection: () => deleteIds(selectedRef.current),
      selectAll: () => setSelectedNodeIds(nodesRef.current.map((node) => node.id)),
    }),
    [copyIds, cloneIntoCanvas, duplicateIds, deleteIds, resolveTargets, setEdges, setSelectedNodeIds],
  );
}

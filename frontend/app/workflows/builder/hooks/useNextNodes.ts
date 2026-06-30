import * as React from 'react';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { nodeRegistry } from '../registry/nodeRegistry';

interface UseNextNodesOptions {
  currentNode?: Node<BuilderNodeData> | null;
  allNodes: Node<BuilderNodeData>[];
  edges: Edge[];
  selectedLoopChild?: { loopId: string; childId: string } | null;
}

/**
 * Find all node IDs reachable from a While node's body handle via BFS.
 * Stops at the While node itself to avoid infinite traversal.
 */
function getWhileBodyNodeIds(whileNode: Node<BuilderNodeData>, edges: Edge[]): Set<string> {
  const bodyEdge = edges.find(
    (e) => e.source === whileNode.id && e.sourceHandle?.endsWith('-body'),
  );
  if (!bodyEdge) return new Set();

  const visited = new Set<string>();
  const queue = [bodyEdge.target];
  while (queue.length > 0) {
    const current = queue.shift()!;
    if (visited.has(current) || current === whileNode.id) continue;
    visited.add(current);
    for (const edge of edges) {
      if (edge.source === current && !visited.has(edge.target)) {
        queue.push(edge.target);
      }
    }
  }
  return visited;
}

/**
 * Hook to calculate the next nodes from the current node
 * Handles loop children, while body nodes, virtual nodes, and edge-based navigation
 */
export function useNextNodes({
  currentNode,
  allNodes,
  edges,
  selectedLoopChild,
}: UseNextNodesOptions): Node<BuilderNodeData>[] {
  return React.useMemo(() => {
    if (!currentNode) return [];

    // Find if current node is a loop child
    let loopNode: Node<BuilderNodeData> | undefined;
    let currentIndex = -1;
    let loopChildren: any[] = [];

    if (selectedLoopChild && currentNode.id === selectedLoopChild.childId) {
      // Case 1: selectedLoopChild is defined and matches
      loopNode = allNodes.find(n => n.id === selectedLoopChild.loopId);
    } else {
      // Case 2: Search in all loopNodes if currentNode is a child
      loopNode = allNodes.find(n =>
        nodeRegistry.isLoopNode(n) &&
        Array.isArray(n.data.loopChildren) &&
        n.data.loopChildren.some((child: any) => child.id === currentNode.id)
      );
    }

    if (loopNode && Array.isArray(loopNode.data.loopChildren)) {
      loopChildren = loopNode.data.loopChildren;
      currentIndex = loopChildren.findIndex((c: any) => c.id === currentNode.id);
    }

    // If it's a loop child, check if it's the last node
    if (loopNode && currentIndex >= 0) {
      const isLastChild = currentIndex === loopChildren.length - 1;

      if (isLastChild) {
        // Last child: show nodes after the loop AND the first child (loop back)
        const targets = edges.filter(e => e.source === loopNode.id).map(e => e.target);
        const nodesAfterLoop = targets
          .map(t => allNodes.find(n => n.id === t))
          .filter((n): n is Node<BuilderNodeData> => !!n);

        const result: Node<BuilderNodeData>[] = [...nodesAfterLoop];

        // Add first child to show loop back
        const firstChild = loopChildren[0];
        if (firstChild) {
          const firstChildNode: Node<BuilderNodeData> = {
            id: firstChild.id,
            type: firstChild.nodeType ?? 'flowNode',
            position: { x: 0, y: 0 },
            data: {
              ...firstChild,
              _loopId: loopNode.id,
            } as BuilderNodeData,
          };
          result.push(firstChildNode);
        }

        return result;
      } else {
        // Not last: show next child in loop
        const nextChild = loopChildren[currentIndex + 1];
        if (nextChild) {
          return [{
            id: nextChild.id,
            type: nextChild.nodeType ?? 'flowNode',
            position: { x: 0, y: 0 },
            data: {
              ...nextChild,
              _loopId: loopNode.id,
            } as BuilderNodeData,
          } as Node<BuilderNodeData>];
        }
      }
    }

    // Check if current node is inside a While body
    for (const whileNode of allNodes) {
      if (!nodeRegistry.isWhileGroupNode(whileNode)) continue;

      const bodyNodeIds = getWhileBodyNodeIds(whileNode, edges);
      if (!bodyNodeIds.has(currentNode.id)) continue;

      // Current node IS in this While body
      // Check if it's the last node (connects back to While loop-back handle)
      const loopBackEdge = edges.find(
        (e) => e.source === currentNode.id && e.targetHandle?.endsWith('-loop-back'),
      );

      if (loopBackEdge) {
        // Last body node: show exit targets AND the first body node (loop back)
        const result: Node<BuilderNodeData>[] = [];

        // Exit targets (nodes connected from While exit handle)
        const exitEdges = edges.filter(
          (e) => e.source === whileNode.id && e.sourceHandle?.endsWith('-exit'),
        );
        for (const exitEdge of exitEdges) {
          const targetNode = allNodes.find((n) => n.id === exitEdge.target);
          if (targetNode) result.push(targetNode);
        }

        // First body node (loop back) - mark with _loopId and _isIterationInput
        const bodyEdge = edges.find(
          (e) => e.source === whileNode.id && e.sourceHandle?.endsWith('-body'),
        );
        if (bodyEdge) {
          const firstBodyNode = allNodes.find((n) => n.id === bodyEdge.target);
          if (firstBodyNode) {
            result.push({
              ...firstBodyNode,
              data: {
                ...firstBodyNode.data,
                _loopId: whileNode.id,
                _isIterationInput: true,
              } as BuilderNodeData,
            });
          }
        }

        return result;
      }

      // Not the last body node: use standard edge-based navigation
      break;
    }

    // Check if this external node is connected to a while loop (old container)
    const edgesToLoops = edges.filter(e => e.source === currentNode.id);
    for (const edge of edgesToLoops) {
      let targetLoopNode: Node<BuilderNodeData> | undefined;

      // Method 1: Check if target is a loopNode directly
      targetLoopNode = allNodes.find(n => n.id === edge.target && nodeRegistry.isLoopNode(n));

      // Method 2: Check targetHandle for loop entry
      if (!targetLoopNode && edge.targetHandle?.startsWith('loop-') && edge.targetHandle.endsWith('-entry')) {
        const loopId = edge.targetHandle.replace('loop-', '').replace('-entry', '');
        targetLoopNode = allNodes.find(n => n.id === loopId && nodeRegistry.isLoopNode(n));
      }

      // If we found a loop with children, return the first child
      if (targetLoopNode && Array.isArray(targetLoopNode.data.loopChildren) && targetLoopNode.data.loopChildren.length > 0) {
        const firstChild = targetLoopNode.data.loopChildren[0];
        const virtualNode: Node<BuilderNodeData> = {
          id: firstChild.id,
          type: firstChild.nodeType ?? 'flowNode',
          position: { x: 0, y: 0 },
          data: {
            ...firstChild,
            _loopId: targetLoopNode.id,
          } as BuilderNodeData & { _loopId?: string },
        };
        return [virtualNode];
      }
    }

    // Check if this node connects to a While entry (show first body node as next)
    for (const edge of edgesToLoops) {
      if (edge.targetHandle?.endsWith('-entry')) {
        const whileNode = allNodes.find(
          (n) => n.id === edge.target && nodeRegistry.isWhileGroupNode(n),
        );
        if (whileNode) {
          const bodyEdge = edges.find(
            (e) => e.source === whileNode.id && e.sourceHandle?.endsWith('-body'),
          );
          if (bodyEdge) {
            const firstBodyNode = allNodes.find((n) => n.id === bodyEdge.target);
            if (firstBodyNode) {
              return [{
                ...firstBodyNode,
                data: {
                  ...firstBodyNode.data,
                  _loopId: whileNode.id,
                } as BuilderNodeData,
              }];
            }
          }
        }
      }
    }

    // Normal behavior: use edges from current node
    const targets = edges.filter(e => e.source === currentNode.id).map(e => e.target);
    const uniqueTargets = Array.from(new Set(targets));
    return uniqueTargets
      .map(t => allNodes.find(n => n.id === t))
      .filter((n): n is Node<BuilderNodeData> => !!n);
  }, [currentNode, edges, allNodes, selectedLoopChild]);
}

/**
 * Get loop ID from node data
 */
export function getLoopIdFromNode(node: Node<BuilderNodeData> | undefined): string | undefined {
  if (!node) return undefined;
  const loopId = node.data?._loopId;
  return typeof loopId === 'string' ? loopId : undefined;
}

/**
 * Check if a node is an iteration node (first child of a loop or first body node of a While)
 * These nodes show a RefreshCcw icon instead of ArrowRight for navigation.
 */
export function useIsIterationNode(allNodes: Node<BuilderNodeData>[], edges?: Edge[]) {
  return React.useCallback((node: Node<BuilderNodeData>): boolean => {
    if (!node) return false;

    // Check _loopId marker (set by useNextNodes when navigating loop-back)
    const loopId = node.data?._loopId;
    if (loopId) {
      // Old loop container: check if first child
      const loopNode = allNodes.find(n => n.id === loopId && nodeRegistry.isLoopNode(n));
      if (loopNode && Array.isArray(loopNode.data.loopChildren)) {
        const loopChildren = loopNode.data.loopChildren;
        if (loopChildren.length > 0 && loopChildren[0].id === node.id) return true;
      }

      // While node: check if first body node
      const whileNode = allNodes.find(n => n.id === loopId && nodeRegistry.isWhileGroupNode(n));
      if (whileNode && edges) {
        const bodyEdge = edges.find(
          (e) => e.source === whileNode.id && e.sourceHandle?.endsWith('-body'),
        );
        if (bodyEdge && bodyEdge.target === node.id) return true;
      }

      return true; // Has _loopId = it's a loop iteration node
    }

    return false;
  }, [allNodes, edges]);
}

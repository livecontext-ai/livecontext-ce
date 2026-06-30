'use client';

/**
 * ParentNodesDataPreview - Shows execution output data from ancestor nodes in run mode
 *
 * Displays ancestor nodes organized by distance (parents, grandparents, etc.)
 * Each node shows its OWN output data (no passthrough).
 * Supports drag and drop to create expressions.
 */

import * as React from 'react';
import { ArrowLeft, ChevronRight, Layers } from 'lucide-react';
import clsx from 'clsx';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import { RunDataPreview } from './RunDataPreview';
import { normalizeLabel } from '../../../utils/labelNormalizer';
import { nodeRegistry } from '../../../registry/nodeRegistry';
import { NodeIcon, getIconSlug } from '../../nodes/shared';

interface ParentNodesDataPreviewProps {
  parentNodes: Node<BuilderNodeData>[];
  allNodes: Node<BuilderNodeData>[];
  edges: Edge[];
  workflowId: string;
  runId: string;
  onSelectNode?: (nodeId: string, loopId?: string) => void;
  /** Enable drag and drop in step-by-step mode */
  isDraggable?: boolean;
  /**
   * When true, force-expand the collapsed ancestor groups (Grandparents and
   * beyond) so their data previews mount - used during approval review to
   * reveal the upstream context of the item under review.
   */
  autoExpandAncestors?: boolean;
  /**
   * Bumped on each new approval-review request; re-triggers the auto-expand so
   * a fresh navigation re-opens groups the user may have manually collapsed.
   */
  reviewRequestId?: number;
}

interface AncestorGroup {
  distance: number;
  nodes: Node<BuilderNodeData>[];
}

/**
 * Get the drag prefix for a node based on its type
 * Returns the correct namespace: trigger:, mcp:, agent:, core:
 * Uses nodeRegistry for centralized type detection.
 */
function getDragPrefixForNode(node: Node<BuilderNodeData>): string {
  const nodeLabel = node.data?.label || node.id;
  const normalizedLabel = normalizeLabel(nodeLabel) || node.id;

  // Trigger nodes - use nodeRegistry
  if (nodeRegistry.isTrigger(node)) {
    return `trigger:${normalizedLabel}.output`;
  }

  // Agent nodes (agent, guardrail, classify) - use nodeRegistry
  if (nodeRegistry.isAgentNode(node) || nodeRegistry.isGuardrailNode(node) || nodeRegistry.isClassifyNode(node)) {
    return `agent:${normalizedLabel}.output`;
  }

  // Core nodes - use nodeRegistry.isControlNode for all control flow nodes
  if (nodeRegistry.isControlNode(node)) {
    return `core:${normalizedLabel}.output`;
  }

  // FindNode and other CRUD nodes use table: prefix
  if (nodeRegistry.isFindNode(node) || nodeRegistry.isCrudNode(node)) {
    return `table:${normalizedLabel}.output`;
  }

  // Default to mcp: for tool nodes and others
  return `mcp:${normalizedLabel}.output`;
}

/**
 * Compute all ancestor nodes organized by distance from the current node
 */
function computeAncestorsByDistance(
  startNodes: Node<BuilderNodeData>[],
  allNodes: Node<BuilderNodeData>[],
  edges: Edge[]
): AncestorGroup[] {
  const visited = new Set<string>();
  const ancestorsByDistance: Map<number, Set<string>> = new Map();

  // Helper to get direct parents of a node
  const getDirectParents = (nodeId: string): string[] => {
    const parentIds: string[] = [];

    edges.forEach(e => {
      if (e.target === nodeId) {
        const sourceNode = allNodes.find(n => n.id === e.source);
        if (sourceNode && (nodeRegistry.isLoopNode(sourceNode) || nodeRegistry.isSplitNode(sourceNode) || nodeRegistry.isFindNode(sourceNode))) {
          // For loop exit, add the loop node and potentially its children
          parentIds.push(e.source);
          if (Array.isArray(sourceNode.data.loopChildren) && sourceNode.data.loopChildren.length > 0) {
            sourceNode.data.loopChildren.forEach((child: any) => {
              if (!parentIds.includes(child.id)) {
                parentIds.push(child.id);
              }
            });
          }
        } else {
          parentIds.push(e.source);
        }
      }
    });

    return parentIds;
  };

  // BFS to find all ancestors with their distances
  const queue: { id: string; distance: number }[] = [];

  // Start with direct parents (distance 1)
  startNodes.forEach(node => {
    if (!visited.has(node.id)) {
      visited.add(node.id);
      ancestorsByDistance.set(1, ancestorsByDistance.get(1) || new Set());
      ancestorsByDistance.get(1)!.add(node.id);

      // Get parents of this node for distance 2+
      const parents = getDirectParents(node.id);
      parents.forEach(pId => {
        if (!visited.has(pId)) {
          visited.add(pId);
          queue.push({ id: pId, distance: 2 });
        }
      });
    }
  });

  // Continue BFS for grandparents and beyond
  while (queue.length > 0) {
    const { id, distance } = queue.shift()!;

    if (!ancestorsByDistance.has(distance)) {
      ancestorsByDistance.set(distance, new Set());
    }
    ancestorsByDistance.get(distance)!.add(id);

    // Get parents of this node
    const parents = getDirectParents(id);
    parents.forEach(pId => {
      if (!visited.has(pId)) {
        visited.add(pId);
        queue.push({ id: pId, distance: distance + 1 });
      }
    });
  }

  // Helper to find a node by ID (including loop children)
  const findNodeById = (id: string): Node<BuilderNodeData> | null => {
    let node = allNodes.find(n => n.id === id);
    if (node) return node;

    // Check loop children
    for (const potentialLoop of allNodes) {
      if ((nodeRegistry.isLoopNode(potentialLoop) || nodeRegistry.isSplitNode(potentialLoop) || nodeRegistry.isFindNode(potentialLoop)) &&
          Array.isArray(potentialLoop.data.loopChildren)) {
        const loopChild = potentialLoop.data.loopChildren.find((c: any) => c.id === id);
        if (loopChild) {
          return {
            id: loopChild.id,
            type: loopChild.nodeType ?? 'flowNode',
            position: { x: 0, y: 0 },
            data: {
              ...loopChild,
              _loopId: potentialLoop.id,
            } as BuilderNodeData,
          };
        }
      }
    }

    return null;
  };

  // Convert to array of groups
  const groups: AncestorGroup[] = [];
  const sortedDistances = Array.from(ancestorsByDistance.keys()).sort((a, b) => a - b);

  for (const distance of sortedDistances) {
    const nodeIds = ancestorsByDistance.get(distance)!;
    const nodes = Array.from(nodeIds)
      .map(id => findNodeById(id))
      .filter((n): n is Node<BuilderNodeData> => !!n);

    if (nodes.length > 0) {
      groups.push({ distance, nodes });
    }
  }

  return groups;
}

export function ParentNodesDataPreview({
  parentNodes,
  allNodes,
  edges,
  workflowId,
  runId,
  onSelectNode,
  isDraggable = false,
  autoExpandAncestors = false,
  reviewRequestId,
}: ParentNodesDataPreviewProps) {
  // Compute ancestors by distance
  const ancestorGroups = React.useMemo(() => {
    if (parentNodes.length === 0) return [];
    return computeAncestorsByDistance(parentNodes, allNodes, edges);
  }, [parentNodes, allNodes, edges]);

  if (ancestorGroups.length === 0) {
    return (
      <div className="py-4 text-center">
        <p className="text-sm text-slate-500">No parent nodes</p>
      </div>
    );
  }

  const getLoopIdFromNode = (node: Node<BuilderNodeData>): string | undefined => {
    return (node.data as any)?._loopId || (node.data as any)?._splitId;
  };

  return (
    <div className="space-y-4">
      {ancestorGroups.map(group => (
        <AncestorDistanceGroup
          key={`distance-${group.distance}`}
          distance={group.distance}
          nodes={group.nodes}
          workflowId={workflowId}
          runId={runId}
          onSelectNode={onSelectNode}
          getLoopIdFromNode={getLoopIdFromNode}
          isDraggable={isDraggable}
          defaultExpanded={group.distance === 1}
          forceExpanded={autoExpandAncestors}
          reviewRequestId={reviewRequestId}
        />
      ))}
    </div>
  );
}

/**
 * Collapsible section for ancestor nodes at a specific distance
 */
function AncestorDistanceGroup({
  distance,
  nodes,
  workflowId,
  runId,
  onSelectNode,
  getLoopIdFromNode,
  isDraggable,
  defaultExpanded,
  forceExpanded = false,
  reviewRequestId,
}: {
  distance: number;
  nodes: Node<BuilderNodeData>[];
  workflowId: string;
  runId: string;
  onSelectNode?: (nodeId: string, loopId?: string) => void;
  getLoopIdFromNode: (node: Node<BuilderNodeData>) => string | undefined;
  isDraggable: boolean;
  defaultExpanded: boolean;
  forceExpanded?: boolean;
  reviewRequestId?: number;
}) {
  const [isExpanded, setIsExpanded] = React.useState(defaultExpanded);

  // Force-open on each new approval-review request (keyed on reviewRequestId so
  // navigating to another pending approval re-opens groups the user collapsed).
  // Only expands - never auto-collapses, so manual control is preserved between
  // navigations.
  React.useEffect(() => {
    if (forceExpanded) setIsExpanded(true);
  }, [forceExpanded, reviewRequestId]);

  // Generate label based on distance
  const getDistanceLabel = (dist: number): string => {
    if (dist === 1) return 'Direct Parents';
    if (dist === 2) return 'Grandparents';
    if (dist === 3) return 'Great-grandparents';
    return `${dist} steps back`;
  };

  // For direct parents (distance 1), just render the nodes without header
  if (distance === 1) {
    return (
      <div className="space-y-3">
        {nodes.map(node => (
          <NodeDataPreview
            key={node.id}
            node={node}
            workflowId={workflowId}
            runId={runId}
            onSelectNode={onSelectNode}
            getLoopIdFromNode={getLoopIdFromNode}
            isDraggable={isDraggable}
          />
        ))}
      </div>
    );
  }

  return (
    <div className="border border-slate-200 dark:border-slate-700 rounded-lg overflow-hidden">
      <button
        onClick={() => setIsExpanded(!isExpanded)}
        className={clsx(
          "w-full flex items-center gap-2 px-3 py-2 text-left transition-colors",
          "bg-slate-50 dark:bg-slate-800/50 hover:bg-slate-100 dark:hover:bg-slate-800",
          "text-sm font-medium text-slate-600 dark:text-slate-400"
        )}
      >
        <ChevronRight
          className={clsx(
            "h-4 w-4 transition-transform",
            isExpanded && "rotate-90"
          )}
        />
        <Layers className="h-3.5 w-3.5 text-slate-400" />
        <span className="flex-1">{getDistanceLabel(distance)}</span>
        <span className="text-xs text-slate-400 bg-slate-200 dark:bg-slate-700 px-1.5 py-0.5 rounded">
          {nodes.length}
        </span>
      </button>

      {isExpanded && (
        <div className="p-3 space-y-3 bg-white dark:bg-slate-900">
          {nodes.map(node => (
            <NodeDataPreview
              key={node.id}
              node={node}
              workflowId={workflowId}
              runId={runId}
              onSelectNode={onSelectNode}
              getLoopIdFromNode={getLoopIdFromNode}
              isDraggable={isDraggable}
            />
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * Preview of a single node's output data
 */
function NodeDataPreview({
  node,
  workflowId,
  runId,
  onSelectNode,
  getLoopIdFromNode,
  isDraggable,
}: {
  node: Node<BuilderNodeData>;
  workflowId: string;
  runId: string;
  onSelectNode?: (nodeId: string, loopId?: string) => void;
  getLoopIdFromNode: (node: Node<BuilderNodeData>) => string | undefined;
  isDraggable: boolean;
}) {
  const stepAlias = node.data?.label;
  const dragPrefix = getDragPrefixForNode(node);

  return (
    <div className="space-y-2">
      {/* Navigation button */}
      <div className="text-sm text-[var(--text-primary)] flex justify-start">
        <button
          onClick={(e) => {
            e.stopPropagation();
            e.preventDefault();
            const loopId = getLoopIdFromNode(node);
            onSelectNode?.(node.id, loopId);
          }}
          className="inline-flex items-center gap-1.5 px-2 py-1.5 rounded-lg text-sm transition-colors text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
          title="Go to node"
        >
          <ArrowLeft className="h-3 w-3 flex-shrink-0" />
          <NodeIcon nodeId={node.data?.id || node.id} iconSlug={getIconSlug(node.data)} nodeKind={node.data?.kind as any} avatarUrl={(node.data as any)?.agentAvatarUrl} size="xs" />
          <span className="truncate max-w-[220px] text-sm">{node.data?.label || node.id}</span>
        </button>
      </div>

      {/* Node's own output data */}
      <RunDataPreview
        workflowId={workflowId}
        runId={runId}
        stepAlias={stepAlias}
        dataType="output"
        isDraggable={isDraggable}
        dragPrefix={dragPrefix}
      />
    </div>
  );
}

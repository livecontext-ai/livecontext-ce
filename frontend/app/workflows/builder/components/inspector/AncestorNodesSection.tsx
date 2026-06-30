import * as React from 'react';
import clsx from 'clsx';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { SourceNodeInspector } from './SourceNodeInspector';
import { SourceDataSourceInspector } from './SourceDataSourceInspector';
import { SourceCoreNodeInspector } from './SourceCoreNodeInspector';
import { ChevronRight, Layers } from 'lucide-react';
import { EmptyState } from '../shared/EmptyState';
import { nodeRegistry } from '../../registry/nodeRegistry';
import { useWorkflowMode } from '@/contexts/WorkflowModeContext';

interface AncestorNodesSectionProps {
    currentNode: Node<BuilderNodeData> | null;
    allNodes: Node<BuilderNodeData>[];
    edges: Edge[];
    onSelectNode?: (nodeId: string, loopId?: string) => void;
    selectedLoopChild?: { loopId: string; childId: string } | null;
    isRunMode?: boolean;
    /** When true, only show direct parents (distance 1), not grandparents or beyond */
    directParentsOnly?: boolean;
}

interface AncestorGroup {
    distance: number;
    nodes: Node<BuilderNodeData>[];
}

/**
 * Computes all ancestor nodes of a given node, organized by distance.
 * Distance 1 = direct parents, Distance 2 = grandparents, etc.
 */
function computeAncestorsByDistance(
    nodeId: string,
    allNodes: Node<BuilderNodeData>[],
    edges: Edge[],
    loopNode?: Node<BuilderNodeData> | null,
    loopChildren?: any[],
    currentChildIndex?: number
): AncestorGroup[] {
    const visited = new Set<string>();
    const ancestorsByDistance: Map<number, Set<string>> = new Map();

    // Helper to get direct parents of a node
    const getDirectParents = (nId: string): string[] => {
        const parentIds: string[] = [];

        // Check if this node is inside a loop (current context)
        if (loopNode && loopChildren && currentChildIndex !== undefined) {
            const childIndex = loopChildren.findIndex(c => c.id === nId);
            if (childIndex !== -1) {
                if (childIndex === 0) {
                    // First child in loop: 
                    // 1. Parents are nodes connected to the loop
                    edges.forEach(e => {
                        if (e.target === loopNode.id) {
                            parentIds.push(e.source);
                        }
                    });
                    // If no parents, use the loop node itself
                    if (parentIds.length === 0) {
                        parentIds.push(loopNode.id);
                    }
                    // 2. Also add ALL loop children as sources from previous iteration
                    // (they provide outputs from the last iteration)
                    loopChildren.forEach((child: any) => {
                        // Add with special marker for iteration
                        if (!parentIds.includes(child.id)) {
                            parentIds.push(child.id);
                        }
                    });
                } else {
                    // Other children: parent is the previous child in current iteration
                    parentIds.push(loopChildren[childIndex - 1].id);
                    // Also add ALL loop children as sources from previous iteration
                    loopChildren.forEach((child: any) => {
                        if (!parentIds.includes(child.id)) {
                            parentIds.push(child.id);
                        }
                    });
                }
                return parentIds;
            }
        }

        // Check if this is a loop/split child from ANY loop/split in the workflow
        // This handles the case where we're traversing ancestors of a node connected to a loop's output
        for (const potentialLoop of allNodes) {
            if ((nodeRegistry.isSplitNode(potentialLoop)) && Array.isArray(potentialLoop.data.loopChildren)) {
                const childIndex = potentialLoop.data.loopChildren.findIndex((c: any) => c.id === nId);
                if (childIndex !== -1) {
                    // Found this node as a child of a loop/split
                    // The "grandparents" are the parents of the loop/split itself
                    edges.forEach(e => {
                        if (e.target === potentialLoop.id) {
                            parentIds.push(e.source);
                        }
                    });
                    return parentIds;
                }
            }
        }

        // Normal case: use edges
        edges.forEach(e => {
            if (e.target === nId) {
                // Handle loop/split exit: if source is a loop/split, use ALL its children (last iteration outputs)
                const sourceNode = allNodes.find(n => n.id === e.source);
                if (sourceNode && nodeRegistry.isSplitNode(sourceNode)) {
                    const isLoopExit = e.sourceHandle?.endsWith('-exit') || !e.sourceHandle;
                    // Always add the source node itself as a parent
                    parentIds.push(e.source);
                    if (isLoopExit && Array.isArray(sourceNode.data.loopChildren) && sourceNode.data.loopChildren.length > 0) {
                        // Exit edge: also add all children (last iteration outputs)
                        sourceNode.data.loopChildren.forEach((child: any) => {
                            parentIds.push(child.id);
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

    // Start with direct parents of the current node
    const directParents = getDirectParents(nodeId);
    directParents.forEach(pId => {
        if (!visited.has(pId)) {
            visited.add(pId);
            queue.push({ id: pId, distance: 1 });
        }
    });

    while (queue.length > 0) {
        const { id, distance } = queue.shift()!;

        // Add to distance group
        if (!ancestorsByDistance.has(distance)) {
            ancestorsByDistance.set(distance, new Set());
        }
        ancestorsByDistance.get(distance)!.add(id);

        // Get parents of this node and continue traversal
        // Each node (including decision, switch, fork, wait) now has its OWN outputs
        // So we traverse through them normally to show their parents as grandparents
        const parents = getDirectParents(id);
        parents.forEach(pId => {
            if (!visited.has(pId)) {
                visited.add(pId);
                queue.push({ id: pId, distance: distance + 1 });
            }
        });
    }

    // Convert to array of groups, filtering out nodes that are sources of decision nodes
    const groups: AncestorGroup[] = [];
    const sortedDistances = Array.from(ancestorsByDistance.keys()).sort((a, b) => a - b);

    // Helper function to find a node by ID, checking both allNodes and all loop children
    const findNodeById = (id: string): Node<BuilderNodeData> | null => {
        // First check allNodes
        let node = allNodes.find(n => n.id === id);
        if (node) return node;

        // Check if it's a loop child from the current loop context
        if (loopNode && loopChildren) {
            const loopChild = loopChildren.find(c => c.id === id);
            if (loopChild) {
                return {
                    id: loopChild.id,
                    type: loopChild.nodeType ?? 'flowNode',
                    position: { x: 0, y: 0 },
                    data: {
                        ...loopChild,
                        _loopId: loopNode.id,
                        _isIterationInput: true, // Mark as iteration input (previous iteration)
                    } as BuilderNodeData,
                };
            }
        }

        // Check ALL loop/split nodes for their children
        for (const potentialLoop of allNodes) {
            if ((nodeRegistry.isSplitNode(potentialLoop)) && Array.isArray(potentialLoop.data.loopChildren)) {
                const loopChild = potentialLoop.data.loopChildren.find((c: any) => c.id === id);
                if (loopChild) {
                    return {
                        id: loopChild.id,
                        type: loopChild.nodeType ?? 'flowNode',
                        position: { x: 0, y: 0 },
                        data: {
                            ...loopChild,
                            _loopId: potentialLoop.id,
                            _splitId: (nodeRegistry.isSplitNode(potentialLoop)) ? potentialLoop.id : undefined,
                            _isFromLoopOutput: true, // Mark that this comes from a loop/split/find's output
                        } as BuilderNodeData,
                    };
                }
            }
        }

        return null;
    };

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

/**
 * Renders a single ancestor node based on its type
 */
function renderAncestorNode(
    node: Node<BuilderNodeData>,
    allNodes: Node<BuilderNodeData>[],
    edges: Edge[],
    onSelectNode?: (nodeId: string, loopId?: string) => void,
    isRunMode: boolean = false,
    isDraggable: boolean = true
) {
    const loopId = (node.data as any)?._loopId;
    const isIterationInput = !!(node.data as any)?._isIterationInput;

    // Aggregate nodes have dynamic output (configured fields as arrays + count)
    // Route them to SourceNodeInspector which has dedicated aggregate handling
    if (nodeRegistry.isAggregateNode(node)) {
        return (
            <SourceNodeInspector
                key={node.id}
                node={node}
                allNodes={allNodes}
                edges={edges}
                onNavigateToNode={(nodeId) => onSelectNode?.(nodeId, loopId)}
                isDraggable={isDraggable}
                isRunMode={isRunMode}
            />
        );
    }

    // Check if source is a core node (decision, switch, fork, wait, loop, split, merge, transform, download_file)
    // or a CRUD node (table: prefix - create-row, read-row, update-row, delete-row, find-row)
    // These nodes have their OWN outputs - use SourceCoreNodeInspector
    // Using nodeRegistry for centralized detection
    const isCoreNode = nodeRegistry.isCoreNode(node) || nodeRegistry.isCrudNode(node);

    if (isCoreNode) {
        return (
            <SourceCoreNodeInspector
                key={node.id}
                node={node}
                onNavigateToNode={(nodeId) => onSelectNode?.(nodeId, loopId)}
                isDraggable={isDraggable}
                isRunMode={isRunMode}
            />
        );
    }

    // Check if source is a datasource/tables trigger node
    const sourceDataSourceData = (node.data as any)?.dataSourceData;
    const isSourceDataSource = node.data?.id?.startsWith('tables-trigger-') || !!sourceDataSourceData;

    if (isSourceDataSource) {
        return (
            <SourceDataSourceInspector
                key={node.id}
                node={node}
                onNavigateToNode={(nodeId) => onSelectNode?.(nodeId, loopId)}
                isDraggable={isDraggable}
                isRunMode={isRunMode}
            />
        );
    }

    // Default: regular node (tool, agent, etc.)
    return (
        <SourceNodeInspector
            key={node.id}
            node={node}
            allNodes={allNodes}
            edges={edges}
            onNavigateToNode={(nodeId) => onSelectNode?.(nodeId, loopId)}
            isDraggable={isDraggable}
            useIterationArrow={isIterationInput}
            isRunMode={isRunMode}
        />
    );
}

/**
 * Collapsible section for ancestor nodes at a specific distance
 */
function AncestorDistanceGroup({
    distance,
    nodes,
    allNodes,
    edges,
    onSelectNode,
    isRunMode,
    isDraggable,
    defaultExpanded,
}: {
    distance: number;
    nodes: Node<BuilderNodeData>[];
    allNodes: Node<BuilderNodeData>[];
    edges: Edge[];
    onSelectNode?: (nodeId: string, loopId?: string) => void;
    isRunMode: boolean;
    isDraggable: boolean;
    defaultExpanded: boolean;
}) {
    const [isExpanded, setIsExpanded] = React.useState(defaultExpanded);

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
            <div className="space-y-2">
                {nodes.map(node => renderAncestorNode(node, allNodes, edges, onSelectNode, isRunMode, isDraggable))}
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
                <div className="p-3 space-y-2 bg-white dark:bg-slate-900">
                    {nodes.map(node => renderAncestorNode(node, allNodes, edges, onSelectNode, isRunMode, isDraggable))}
                </div>
            )}
        </div>
    );
}

/**
 * Main component that displays all ancestor nodes organized by distance
 */
export const AncestorNodesSection = ({
    currentNode,
    allNodes,
    edges,
    onSelectNode,
    selectedLoopChild,
    isRunMode = false,
    directParentsOnly = false,
}: AncestorNodesSectionProps) => {
    const { isPreviewOnly } = useWorkflowMode();
    const isDraggable = !isPreviewOnly;
    // Check if we're editing a loop child
    const isLoopChild = !!selectedLoopChild && currentNode?.id === selectedLoopChild.childId;
    const loopNode = isLoopChild ? allNodes.find(n => n.id === selectedLoopChild.loopId) : null;
    const loopChildren = loopNode && Array.isArray(loopNode.data.loopChildren) ? loopNode.data.loopChildren : undefined;
    const currentChildIndex = loopChildren ? loopChildren.findIndex(c => c.id === currentNode?.id) : undefined;

    // Check if current node is an interface - interfaces should only see direct parents
    const isInterfaceNode = currentNode ? nodeRegistry.isInterfaceNode(currentNode) : false;
    const showOnlyDirectParents = directParentsOnly || isInterfaceNode;

    // Compute all ancestors by distance
    const ancestorGroups = React.useMemo(() => {
        if (!currentNode) return [];
        const allGroups = computeAncestorsByDistance(
            currentNode.id,
            allNodes,
            edges,
            loopNode,
            loopChildren,
            currentChildIndex
        );

        // For interfaces or when directParentsOnly is set, only show distance 1 (direct parents)
        if (showOnlyDirectParents) {
            return allGroups.filter(group => group.distance === 1);
        }

        return allGroups;
    }, [currentNode, allNodes, edges, loopNode, loopChildren, currentChildIndex, showOnlyDirectParents]);

    if (ancestorGroups.length === 0) {
        return <EmptyState message="No source nodes connected" />;
    }

    return (
        <div className="space-y-3">
            {ancestorGroups.map(group => (
                <AncestorDistanceGroup
                    key={`distance-${group.distance}`}
                    distance={group.distance}
                    nodes={group.nodes}
                    allNodes={allNodes}
                    edges={edges}
                    onSelectNode={onSelectNode}
                    isRunMode={isRunMode}
                    isDraggable={isDraggable}
                    defaultExpanded={group.distance === 1} // Only direct parents are expanded by default
                />
            ))}
        </div>
    );
};

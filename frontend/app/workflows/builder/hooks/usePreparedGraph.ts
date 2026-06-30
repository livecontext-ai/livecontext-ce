import * as React from 'react';
import { Edge, Node, Connection, XYPosition } from 'reactflow';
import { BuilderNodeData, PaletteDragItem } from '../types';
import { normalizeLabel } from '../utils/labelNormalizer';
import { ConnectionType } from '../components/ConnectionTypeSelector';
import { useValidationOptional } from '../contexts/ValidationContext';
import { nodeRegistry } from '../registry/nodeRegistry';

/**
 * Prepares nodes and edges for rendering in React Flow.
 *
 * This hook adds runtime props (callbacks, computed state) to nodes for interactivity.
 * These runtime props are stripped before persistence by handleNodeUpdate.
 *
 * IMPORTANT: When adding new runtime props here, also add them to
 * ../utils/nodeDataUtils.ts RUNTIME_PROP_KEYS to ensure they are not persisted.
 */
export function usePreparedGraph(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
  selectedNodeIds: string[],
  reactFlowConnectionType: ConnectionType,
  callbacks: {
    handleDeleteNode: (nodeId: string) => void;
    handleDuplicateNode: (nodeId: string) => void;
    handleTogglePreview?: (nodeId: string) => void;
    handleNodeUpdate: (data: BuilderNodeData) => void;
    previewModeNodes?: Set<string>;
    onCreateNode?: (item: PaletteDragItem, position: XYPosition, options?: { parentId?: string }) => void;
    onConnect?: (connection: Connection) => void;
  },
) {
  // Use optional validation context (may be null if provider not yet mounted)
  const validationContext = useValidationOptional();

  // Convert validation state to legacy validationIssues format for node enrichment
  const validationIssues = React.useMemo(() => {
    if (!validationContext?.state?.fullResult) return {};

    const issues: Record<string, string[]> = {};

    // Convert issuesByElement to Record<string, string[]>
    Object.entries(validationContext.state.fullResult.issuesByElement).forEach(([key, issueList]) => {
      issues[key] = issueList
        .filter(issue => issue.severity === 'error')
        .map(issue => issue.message);
    });

    return issues;
  }, [validationContext?.state?.fullResult]);
  const preparedNodes = React.useMemo(
    () => {
      const transformedNodes = nodes.map((node) => {
        // selectedNodeIds is the single source of truth for selection.
        // Do NOT use node.selected - it may be stale and updating it triggers render loops.
        const isSelected = selectedNodeIds.includes(node.id);

        const currentZIndex = isSelected ? 20 : nodeRegistry.isNoteNode(node) ? 0 : nodeRegistry.isWhileGroupNode(node) ? 0 : 10;
        const currentZIndexInStyle = node.style?.zIndex;
        const needsStyleUpdate = currentZIndex !== currentZIndexInStyle;
        const updatedStyle = needsStyleUpdate
          ? (node.style ? { ...node.style, zIndex: currentZIndex } : { zIndex: currentZIndex })
          : node.style;

        // Helper function to get validation issues for any node type
        // Uses only XXX:label format (no fallback)
        const getNodeValidationIssues = (
          nodeType: 'mcp' | 'trigger' | 'core',
          nodeLabel?: string,
          nodeId?: string,
          dataId?: string
        ) => {
          const issues: string[] = [];
          const seen = new Set<string>();

          // Prefer normalized label key (existing behavior)
          if (nodeLabel) {
            const normalizedLabel = normalizeLabel(nodeLabel || '');
            if (normalizedLabel) {
              (validationIssues?.[`${nodeType}:${normalizedLabel}`] || []).forEach((msg) => {
                if (!seen.has(msg)) {
                  seen.add(msg);
                  issues.push(msg);
                }
              });
            }
          }

          // Fallback to node ids to catch rules that use nodeId (e.g., InterfaceValidationRule)
          const fallbackIds = [
            nodeId,
            dataId,
          ].filter(Boolean) as string[];

          for (const id of fallbackIds) {
            (validationIssues?.[`${nodeType}:${id}`] || []).forEach((msg) => {
              if (!seen.has(msg)) {
                seen.add(msg);
                issues.push(msg);
              }
            });
          }

          return issues;
        };

        if (nodeRegistry.isNoteNode(node)) {
          return {
            ...node,
            selected: isSelected,
            style: updatedStyle,
            data: {
              ...node.data,
              onNoteUpdate: (updates: any) => {
                callbacks.handleNodeUpdate({ ...node.data, ...updates });
              },
              onDeleteNode: () => callbacks.handleDeleteNode(node.id),
              onDuplicateNode: () => callbacks.handleDuplicateNode(node.id),
              onTogglePreview: callbacks.handleTogglePreview ? () => callbacks.handleTogglePreview?.(node.id) : undefined,
              isPreviewMode: callbacks.previewModeNodes?.has(node.id) || false,
            },
          };
        }
        if (nodeRegistry.isDecisionNode(node)) {
          const nodeValidationIssues = getNodeValidationIssues('core', node.data.label, node.id, node.data.id);
          return {
            ...node,
            selected: isSelected,
            style: updatedStyle,
            data: {
              ...node.data,
              onDeleteNode: () => callbacks.handleDeleteNode(node.id),
              onDuplicateNode: () => callbacks.handleDuplicateNode(node.id),
              onTogglePreview: callbacks.handleTogglePreview ? () => callbacks.handleTogglePreview?.(node.id) : undefined,
              isPreviewMode: callbacks.previewModeNodes?.has(node.id) || false,
              validationIssues: nodeValidationIssues.length > 0 ? nodeValidationIssues : undefined,
            },
          };
        }
        // Get validation issues for this node (flowNode - step or trigger)
        // Determine node type: trigger if kind === 'entry', otherwise step
        const isTrigger = nodeRegistry.isTrigger(node);
        const nodeType: 'mcp' | 'trigger' = isTrigger ? 'trigger' : 'mcp';
        const nodeValidationIssues = getNodeValidationIssues(nodeType, node.data.label, node.id, node.data.id);

        // Check if this is a non-connectable node (generic MCP/API, generic trigger, or generic table)
        // Generic tables (unspecified datasource) are collections like APIs, so they are not connectable
        // But specified tables (with dataSourceId and tableName) are connectable
        // Similar to API nodes: a table is connectable only when it has dataSourceData with dataSourceId AND tableName
        const nodeId = node.data.id || node.id || '';
        const nodeKind = node.data.kind;
        const dataSourceData = (node.data as any)?.dataSourceData;
        const hasDataSourceData = dataSourceData !== undefined;
        // A table is generic if:
        // 1. It's the generic "Tables" collection node (no dataSourceData at all, and ID is 'tables' or starts with 'tables-trigger-')
        // 2. It has dataSourceData but is missing dataSourceId or tableName
        // This is similar to API nodes: an API node is generic if it has apiData but no toolData
        const isTableGeneric = (!hasDataSourceData && (nodeId === 'tables' || nodeId === 'tables-trigger' || nodeId.startsWith('tables-trigger-'))) ||
          (hasDataSourceData && (!dataSourceData?.dataSourceId || !dataSourceData?.tableName));
        const isTriggerGeneric = (nodeId === 'triggers' || nodeId.startsWith('triggers-')) &&
          !nodeId.startsWith('webhook-trigger-') &&
          !nodeId.startsWith('schedule-trigger-') &&
          !nodeId.startsWith('manual-trigger-') &&
          !nodeId.startsWith('tables-trigger-');
        const isGenericEntryTrigger = nodeKind === 'entry' &&
          !nodeId.startsWith('webhook-trigger-') &&
          !nodeId.startsWith('schedule-trigger-') &&
          !nodeId.startsWith('manual-trigger-') &&
          !nodeId.startsWith('tables-trigger-') &&
          !nodeId.includes('-trigger-');
        const isApiNode = node.data.apiData && !node.data.toolData;
        const isMcpGeneric = (nodeId.startsWith('mcp-') || nodeId.startsWith('api-')) && !node.data.toolData;
        const isNonConnectable = isTriggerGeneric || isGenericEntryTrigger || isApiNode || isMcpGeneric || isTableGeneric;

        return {
          ...node,
          selected: isSelected,
          style: updatedStyle,
          connectable: !isNonConnectable, // Mark as non-connectable if it's a generic node
          data: {
            ...node.data,
            onDeleteNode: () => callbacks.handleDeleteNode(node.id),
            onDuplicateNode: () => callbacks.handleDuplicateNode(node.id),
            onTogglePreview: callbacks.handleTogglePreview ? () => callbacks.handleTogglePreview?.(node.id) : undefined,
            onNodeUpdate: (updatedData: BuilderNodeData) => callbacks.handleNodeUpdate(updatedData),
            isPreviewMode: callbacks.previewModeNodes?.has(node.id) || (node.data as any)?.interfaceData?.showPreview !== false,
            validationIssues: nodeValidationIssues.length > 0 ? nodeValidationIssues : undefined,
            onCreateNode: callbacks.onCreateNode,
            onConnect: callbacks.onConnect,
          },
        };
      });

      return transformedNodes;
    },
    [
      nodes,
      selectedNodeIds,
      callbacks.handleNodeUpdate,
      callbacks.handleDeleteNode,
      callbacks.handleDuplicateNode,
      callbacks.handleTogglePreview,
      callbacks.previewModeNodes,
      validationIssues,
    ],
  );

  // Order edges so selected ones are on top
  const preparedEdges = React.useMemo(() => {
    // Detect all loop body edges via DAG traversal from While nodes
    const whileBodyEdgeIds = new Set<string>();

    const adjForward = new Map<string, { targetId: string; edgeId: string }[]>();
    for (const edge of edges) {
      const list = adjForward.get(edge.source) || [];
      list.push({ targetId: edge.target, edgeId: edge.id });
      adjForward.set(edge.source, list);
    }

    for (const node of nodes) {
      if (!nodeRegistry.isWhileGroupNode(node)) continue;
      const whileId = node.id;

      const bodyEdge = edges.find(
        (e) => e.source === whileId && e.sourceHandle?.endsWith('-body'),
      );
      if (!bodyEdge) continue;

      // BFS forward from body target, stopping at While node
      const loopBodyNodes = new Set<string>();
      const queue = [bodyEdge.target];
      const visited = new Set<string>();

      while (queue.length > 0) {
        const current = queue.shift()!;
        if (visited.has(current)) continue;
        if (current === whileId) continue;
        visited.add(current);
        loopBodyNodes.add(current);

        for (const { targetId } of (adjForward.get(current) || [])) {
          if (!visited.has(targetId)) queue.push(targetId);
        }
      }

      // Mark: body entry edge + edges between body nodes + edges back to While
      whileBodyEdgeIds.add(bodyEdge.id);
      for (const edge of edges) {
        if (
          loopBodyNodes.has(edge.source) &&
          (loopBodyNodes.has(edge.target) || edge.target === whileId)
        ) {
          whileBodyEdgeIds.add(edge.id);
        }
      }
    }

    // Deduplicate edges and apply current connection type
    const seenEdgeIds = new Set<string>();
    const updatedEdges = edges.filter((e) => {
      if (seenEdgeIds.has(e.id)) return false;
      seenEdgeIds.add(e.id);
      return true;
    }).map(edge => {
      const isWhileBody = whileBodyEdgeIds.has(edge.id);
      const isLoopBack = !!edge.targetHandle?.endsWith('-loop-back');
      return {
        ...edge,
        data: {
          ...edge.data,
          connectionType: reactFlowConnectionType,
          ...(isWhileBody ? { isWhileBodyEdge: true } : {}),
          ...(isLoopBack ? { isLoopBackEdge: true } : {}),
        },
      };
    });

    const edgesWithSelection = updatedEdges.map((edge) => {
      if (selectedNodeIds.length > 0) {
        const isConnectedToSelected = selectedNodeIds.includes(edge.source) ||
          selectedNodeIds.includes(edge.target);
        return {
          ...edge,
          selected: isConnectedToSelected,
        };
      } else {
        return edge;
      }
    });

    const selectedEdges = edgesWithSelection.filter((edge) => edge.selected);
    const unselectedEdges = edgesWithSelection.filter((edge) => !edge.selected);
    const result = [...unselectedEdges, ...selectedEdges];

    return result;
  }, [edges, nodes, selectedNodeIds, reactFlowConnectionType]);

  const selectedNode = React.useMemo(() => {
    if (selectedNodeIds.length === 0) {
      return null;
    }
    return preparedNodes.find((node) => node.id === selectedNodeIds[0]) ?? null;
  }, [preparedNodes, selectedNodeIds]);

  return { preparedNodes, preparedEdges, selectedNode };
}

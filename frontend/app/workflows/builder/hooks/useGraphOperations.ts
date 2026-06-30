import * as React from 'react';
import {
  Connection,
  Edge,
  MarkerType,
  Node,
  XYPosition,
  addEdge,
} from 'reactflow';
import { BuilderNodeData, PaletteDragItem, PaletteItem, createDefaultDecisionConditions, createDefaultSwitchCases, createDefaultClassifyCategories, createDefaultGuardrailRules, createDefaultOptionChoices, createDefaultApprovalOutputs } from '../types';
import type { ConnectionType } from '../components/ConnectionTypeSelector';
import { stripRuntimeProps } from '../utils/nodeDataUtils';
import { nodeRegistry } from '../registry/nodeRegistry';

/**
 * Check if targetId is an ancestor of sourceId in the graph.
 * If true, an edge from source to target would be a back-edge.
 * Uses BFS from targetId following forward edges to see if sourceId is reachable.
 */
function isAncestor(targetId: string, sourceId: string, edges: Edge[]): boolean {
  // Build adjacency list (forward edges only)
  const successors = new Map<string, Set<string>>();
  for (const edge of edges) {
    if (edge.data?.isBackEdge) continue; // skip existing back-edges
    const sources = successors.get(edge.source) || new Set<string>();
    sources.add(edge.target);
    successors.set(edge.source, sources);
  }

  // BFS from target to see if we can reach source
  const visited = new Set<string>();
  const queue = [targetId];
  visited.add(targetId);

  while (queue.length > 0) {
    const current = queue.shift()!;
    if (current === sourceId) return true;
    const succs = successors.get(current);
    if (succs) {
      for (const next of succs) {
        if (!visited.has(next)) {
          visited.add(next);
          queue.push(next);
        }
      }
    }
  }
  return false;
}

// Simple deep equality for plain data objects (no functions/cycles expected here)
const deepEqual = (a: unknown, b: unknown) => {
  try {
    return JSON.stringify(a) === JSON.stringify(b);
  } catch {
    return false;
  }
};

export function useGraphOperations(
  nodes: Node<BuilderNodeData>[],
  setNodes: React.Dispatch<React.SetStateAction<Node<BuilderNodeData>[]>>,
  edges: Edge[],
  setEdges: React.Dispatch<React.SetStateAction<Edge[]>>,
  setSelectedNodeIds: React.Dispatch<React.SetStateAction<string[]>>,
  reactFlowConnectionType: ConnectionType,
  setHoveredEdgeId: React.Dispatch<React.SetStateAction<string | null>>,
  // Legacy loop params (unused, kept for call-site compatibility until callers are cleaned)
  _selectedLoopChild?: any,
  _setSelectedLoopChild?: any,
) {
  const handleDeleteEdge = React.useCallback(
    (edgeId: string) => {
      setEdges((eds) => eds.filter((edge) => edge.id !== edgeId));
      setHoveredEdgeId((current) => (current === edgeId ? null : current));
    },
    [setEdges, setHoveredEdgeId]
  );

  const handleDeleteNode = React.useCallback(
    (nodeId: string) => {
      setNodes((nds) => nds.filter((node) => node.id !== nodeId));
      setEdges((eds) =>
        eds.filter((edge) => edge.source !== nodeId && edge.target !== nodeId)
      );
      setSelectedNodeIds((prev) => prev.filter((id) => id !== nodeId));
    },
    [setNodes, setEdges, setSelectedNodeIds]
  );

  // Use refs to avoid recreating handleDuplicateNode/handleConnect when nodes/edges change -
  // these callbacks are deps of usePreparedGraph, so instability causes render storms
  // (every node drag changes nodes ref → callback ref changes → preparedNodes recomputes →
  // ReactFlow re-renders → cascade that can trip React error #185 in production).
  const nodesRef = React.useRef(nodes);
  nodesRef.current = nodes;
  const edgesRef = React.useRef(edges);
  edgesRef.current = edges;

  const handleDuplicateNode = React.useCallback(
    (nodeId: string) => {
      const nodeToDuplicate = nodesRef.current.find((node) => node.id === nodeId);
      if (!nodeToDuplicate) return;

      const newId = `${nodeToDuplicate.data.id}-${Date.now()}`;
      const newPosition = {
        x: (nodeToDuplicate.position?.x ?? 0) + 100,
        y: (nodeToDuplicate.position?.y ?? 0) + 100,
      };

      // Clear isEntryInterface on duplicate - only one entry interface allowed
      const sourceInterfaceData = (nodeToDuplicate.data as any).interfaceData;
      const cleanedInterfaceData = sourceInterfaceData?.isEntryInterface
        ? { ...sourceInterfaceData, isEntryInterface: false }
        : sourceInterfaceData;

      const duplicatedNode: Node<BuilderNodeData> = {
        ...nodeToDuplicate,
        id: newId,
        position: newPosition,
        data: {
          ...nodeToDuplicate.data,
          id: newId,
          label: `${nodeToDuplicate.data.label} (copy)`,
          ...(cleanedInterfaceData && { interfaceData: cleanedInterfaceData }),
        },
      };

      setNodes((nds) => [...nds, duplicatedNode]);
      setSelectedNodeIds([newId]);
    },
    [setNodes, setSelectedNodeIds]
  );

  const handleConnect = React.useCallback(
    (connection: Connection) => {
      let correctedConnection = { ...connection };

      // Helper to check if a node is an AI Agent
      const isAiAgentNode = (node: Node | undefined): boolean => {
        if (!node?.data) return false;
        const nodeData = node.data as any;
        const nodeId = nodeData.id || '';
        return nodeId === 'ai-agent' || nodeId === 'agent' ||
               nodeId.startsWith('ai-agent-') || nodeId.startsWith('agent-') ||
               nodeData.label?.toLowerCase().includes('agent');
      };

      // Helper to check if a node is a Merge node - uses nodeRegistry
      const isMergeNode = (node: Node | undefined): boolean => {
        if (!node) return false;
        return nodeRegistry.isMergeNode(node as Node<BuilderNodeData>);
      };

      const sourceNode = nodesRef.current.find((n) => n.id === connection.source);
      const targetNode = nodesRef.current.find((n) => n.id === connection.target);

      // AI Agent tool connection normalization:
      // When connecting to/from AI Agent's bottom handles (source-bottom-1/2),
      // the AI Agent should always be the source, regardless of drag direction
      const sourceIsAiAgent = isAiAgentNode(sourceNode);
      const targetIsAiAgent = isAiAgentNode(targetNode);
      const isBottomHandle = (handle: string | null | undefined) =>
        handle === 'source-bottom-tools' || handle === 'source-bottom-1' || handle === 'source-bottom-2';

      // If target is AI Agent and connection involves bottom handles, swap direction
      if (targetIsAiAgent && (isBottomHandle(connection.targetHandle) || isBottomHandle(connection.sourceHandle))) {
        // Swap source and target so AI Agent is always the source
        correctedConnection = {
          source: connection.target,
          target: connection.source,
          sourceHandle: connection.targetHandle || 'source-bottom-tools',
          targetHandle: connection.sourceHandle || 'source-top',
        };
      }
      // If source is AI Agent with bottom handle connecting to another node's top, keep as is
      else if (sourceIsAiAgent && isBottomHandle(connection.sourceHandle)) {
        // Ensure target handle is set properly
        if (!correctedConnection.targetHandle) {
          correctedConnection.targetHandle = 'source-top';
        }
      }

      // Interface nodes use standard left/right handles (same as other DAG nodes).
      // No special swap logic needed - interfaces can be both source and target.

      const finalSourceNode = nodesRef.current.find((n) => n.id === correctedConnection.source);

      // Split loop special handling
      if (finalSourceNode && nodeRegistry.isSplitNode(finalSourceNode)) {
        if (!correctedConnection.sourceHandle || correctedConnection.sourceHandle === 'source-right') {
          correctedConnection.sourceHandle = `split-${correctedConnection.source}-exit`;
        }
      }

      const finalTargetNode = nodesRef.current.find((n) => n.id === correctedConnection.target);

      // Merge node special handling - if no target handle specified, use first unused input
      if (isMergeNode(finalTargetNode) && !correctedConnection.targetHandle) {
        const mergeInputs = (finalTargetNode.data as any)?.mergeInputs;
        if (mergeInputs && mergeInputs.length > 0) {
          // Find the first input handle not already connected
          const usedHandles = new Set(
            edgesRef.current
              .filter(e => e.target === correctedConnection.target)
              .map(e => e.targetHandle)
          );
          const firstUnused = mergeInputs.find((input: any) => !usedHandles.has(input.id));
          correctedConnection.targetHandle = firstUnused?.id || mergeInputs[0].id;
        } else {
          correctedConnection.targetHandle = 'input_1';
        }
      }

      setEdges((eds) => {
        // If connecting from a split exit, remove any existing exit edge first
        if (correctedConnection.sourceHandle?.endsWith('-exit')) {
          const sourceNode = nodesRef.current.find((n) => n.id === correctedConnection.source);
          if (sourceNode && nodeRegistry.isSplitNode(sourceNode)) {
            eds = eds.filter(
              (edge) => !(edge.source === correctedConnection.source &&
                         edge.sourceHandle?.endsWith('-exit'))
            );
          }
        }

        // If connecting to a merge node input handle, remove any existing edge to that handle
        // Each merge input handle is exclusive (only one connection allowed)
        const targetNode = nodesRef.current.find((n) => n.id === correctedConnection.target);
        if (isMergeNode(targetNode) && correctedConnection.targetHandle) {
          eds = eds.filter(
            (edge) => !(edge.target === correctedConnection.target &&
                       edge.targetHandle === correctedConnection.targetHandle)
          );
        }

        // Auto-detect back-edges: if target is an ancestor of source, this is a back-edge
        // Skip for while loop-back handles - those are handled by usePreparedGraph as while body edges
        const isWhileLoopBack = correctedConnection.targetHandle?.endsWith('-loop-back') === true;
        const detectedBackEdge = !isWhileLoopBack && correctedConnection.target && correctedConnection.source
          ? isAncestor(correctedConnection.target, correctedConnection.source, eds)
          : false;

        const edgeData: Record<string, any> = {
          connectionType: reactFlowConnectionType,
        };
        if (detectedBackEdge) {
          edgeData.isBackEdge = true;
          edgeData.backEdgeCondition = '';
          edgeData.backEdgeMaxIterations = 10;
        }

        return addEdge(
          {
            ...correctedConnection,
            id: `edge-${correctedConnection.source}-${correctedConnection.target}-${Date.now()}`,
            type: 'builderEdge',
            data: edgeData,
            markerEnd: {
              type: MarkerType.ArrowClosed,
              color: detectedBackEdge ? '#f59e0b' : '#94a3b8',
            },
          },
          eds,
        );
      });
    },
    [setEdges, reactFlowConnectionType]
  );

  const handleCreateNode = React.useCallback(
    (item: PaletteDragItem | PaletteItem, position: XYPosition, options?: { parentId?: string }) => {
      const id = `${item.id}-${Date.now()}`;

      const decisionConditions =
        item.nodeType === 'decisionNode' ? createDefaultDecisionConditions(id) : undefined;
      const switchCases =
        item.nodeType === 'switchNode' ? createDefaultSwitchCases(id) : undefined;
      const classifyCategories =
        item.nodeType === 'classifyNode' ? createDefaultClassifyCategories(id) : undefined;
      const guardrailRules =
        item.nodeType === 'guardrailNode' ? createDefaultGuardrailRules(id) : undefined;
      const optionChoices =
        item.nodeType === 'optionNode' ? createDefaultOptionChoices(id) : undefined;
      const approvalOutputs =
        item.nodeType === 'userApprovalNode' ? createDefaultApprovalOutputs(id) : undefined;

      // WhileGroup node defaults
      const whileCondition = item.nodeType === 'whileGroupNode' ? '' : undefined;
      const maxIterations = item.nodeType === 'whileGroupNode' ? 10 : undefined;

      let nodeStyle: any = undefined;
      if (item.nodeType === 'noteNode') {
        nodeStyle = { width: 250, minHeight: 100 };
      } else if (item.kind === 'data_input') {
        nodeStyle = { width: 220 };
      } else if (item.nodeType === 'interfaceNode') {
        // Virtual viewport renders the interface at 1280×800 - without an explicit
        // node size, the layout box grows to fit that (CSS transform only scales
        // the visual rendering, not the layout). Match useInterfacePreviewSize defaults.
        nodeStyle = { width: 400, height: 250 };
      }

      const defaultNoteColor = '#fef3c7';
      const defaultNoteBorder = '#fbbf24';
      const defaultNoteText = '#92400e';

      const isInterfacePreview = item.nodeType === 'interfaceNode';
      const newNode: Node<BuilderNodeData> = {
        id,
        type: item.nodeType,
        position,
        selected: true,
        style: nodeStyle,
        ...(isInterfacePreview ? { width: 400, height: 250 } : {}),
        data: {
          id,
          label: item.label,
          description: item.description,
          kind: item.kind,
          badge: item.badge,
          decisionConditions,
          switchCases,
          classifyCategories,
          guardrailRules,
          optionChoices,
          approvalOutputs,
          noteText: item.nodeType === 'noteNode' ? 'Double-click to edit...' : undefined,
          noteColor: item.nodeType === 'noteNode' ? defaultNoteColor : undefined,
          noteBorderColor: item.nodeType === 'noteNode' ? defaultNoteBorder : undefined,
          noteTextColor: item.nodeType === 'noteNode' ? defaultNoteText : undefined,
          noteWidth: item.nodeType === 'noteNode' ? 250 : undefined,
          noteHeight: item.nodeType === 'noteNode' ? 100 : undefined,
          dataInputWidth: item.kind === 'data_input' ? 220 : undefined,
          dataInputHeight: undefined,
          ...((item as any).apiData ? { apiData: (item as any).apiData } : {}),
          ...((item as any).toolData ? { toolData: (item as any).toolData } : {}),
          ...((item as any).dataSourceData ? { dataSourceData: (item as any).dataSourceData } : {}),
          ...((item as any).interfaceData ? { interfaceData: (item as any).interfaceData } : {}),
          ...((item as any).workflowData ? { workflowData: (item as any).workflowData } : {}),
          ...((item as any).subWorkflowId ? { subWorkflowId: (item as any).subWorkflowId } : {}),
          ...((item as any).standaloneWebhookId ? { standaloneWebhookId: (item as any).standaloneWebhookId } : {}),
          ...((item as any).standaloneWebhookUrl ? { standaloneWebhookUrl: (item as any).standaloneWebhookUrl } : {}),
          ...((item as any).standaloneWebhookToken ? { standaloneWebhookToken: (item as any).standaloneWebhookToken } : {}),
          ...((item as any).standaloneScheduleId ? { standaloneScheduleId: (item as any).standaloneScheduleId } : {}),
          ...((item as any).standaloneChatEndpointId ? { standaloneChatEndpointId: (item as any).standaloneChatEndpointId } : {}),
          ...((item as any).standaloneChatUrl ? { standaloneChatUrl: (item as any).standaloneChatUrl } : {}),
          ...((item as any).standaloneChatToken ? { standaloneChatToken: (item as any).standaloneChatToken } : {}),
          ...((item as any).standaloneFormEndpointId ? { standaloneFormEndpointId: (item as any).standaloneFormEndpointId } : {}),
          ...((item as any).standaloneFormUrl ? { standaloneFormUrl: (item as any).standaloneFormUrl } : {}),
          ...((item as any).standaloneFormToken ? { standaloneFormToken: (item as any).standaloneFormToken } : {}),
          ...((item as any).agentConfigId ? { agentConfigId: (item as any).agentConfigId } : {}),
          ...((item as any).agentConfigName ? { agentConfigName: (item as any).agentConfigName } : {}),
          ...((item as any).agentAvatarUrl ? { agentAvatarUrl: (item as any).agentAvatarUrl } : {}),
          ...((item as any).withMemory !== undefined ? { withMemory: (item as any).withMemory } : {}),
          ...((item as any).provider ? { provider: (item as any).provider } : {}),
          ...(whileCondition !== undefined ? { whileCondition } : {}),
          ...(maxIterations !== undefined ? { maxIterations } : {}),
        },
      };

      if (item.nodeType === 'interfaceNode') {
        console.log('[InterfaceDrop] creating node', {
          id: newNode.id,
          type: newNode.type,
          position: newNode.position,
          style: newNode.style,
          interfaceData: (newNode.data as any).interfaceData,
        });
      }

      setNodes((nds) => {
        const updatedNodes = nds.map((node) => ({
          ...node,
          selected: false,
        }));
        return [...updatedNodes, newNode];
      });
      setSelectedNodeIds([id]);
    },
    [setNodes, setSelectedNodeIds]
  );

  const handleNodeUpdate = React.useCallback(
    (updatedData: BuilderNodeData) => {
      // Strip runtime props (callbacks, computed state) before persisting
      // See nodeDataUtils.ts for the single source of truth
      const persisted = stripRuntimeProps(updatedData);

      setNodes((nds) => {
        // If updated node sets isEntryInterface=true, deselect entry on all other interface nodes
        const isSettingEntry = (persisted as any).interfaceData?.isEntryInterface === true;

        let didChange = false;
        const nextNodes = nds.map((node) => {
          // Find node by matching updatedData.id with either:
          // 1. node.id (React Flow ID) - for normal updates (PRIORITY: this is the most reliable)
          // 2. node.data.id (data ID) - for updates where data.id was changed (e.g., Core nodes converting from 'core-123' to 'if-else-123')
          // 3. _matchNodeId - explicit React Flow ID passed when data.id changes (e.g., trigger type navigation)
          const matchesById = node.id === updatedData.id ||
                            node.data.id === updatedData.id ||
                            (updatedData as any)._matchNodeId === node.id;

          // Deselect entry on OTHER interface nodes when current sets entry=true
          if (!matchesById && isSettingEntry && nodeRegistry.isInterfaceNode(node)) {
            const iData = (node.data as any)?.interfaceData;
            if (iData?.isEntryInterface) {
              didChange = true;
              return { ...node, data: { ...node.data, interfaceData: { ...iData, isEntryInterface: false } } };
            }
          }

          if (matchesById) {
            if (nodeRegistry.isNoteNode(node)) {
              const updatedStyle: any = {
                ...node.style,
              };
              if (updatedData.noteWidth || updatedData.noteHeight) {
                updatedStyle.width = updatedData.noteWidth || node.style?.width;
                updatedStyle.minHeight = updatedData.noteHeight || node.style?.minHeight;
              }
              didChange = true;
              return {
                ...node,
                data: persisted,
                style: updatedStyle,
              };
            }

            // Sync node.style for resizable node types (NodeResizer writes to style, data persists dimensions)
            {
              const updatedStyle: any = { ...node.style };
              let styleChanged = false;

              // DataInput
              if (updatedData.dataInputWidth || updatedData.dataInputHeight) {
                if (updatedData.dataInputWidth) updatedStyle.width = updatedData.dataInputWidth;
                if (updatedData.dataInputHeight) updatedStyle.height = updatedData.dataInputHeight;
                styleChanged = true;
              }

              // DownloadFile
              if ((updatedData as any).downloadFileWidth || (updatedData as any).downloadFileHeight) {
                if ((updatedData as any).downloadFileWidth) updatedStyle.width = (updatedData as any).downloadFileWidth;
                if ((updatedData as any).downloadFileHeight) updatedStyle.height = (updatedData as any).downloadFileHeight;
                styleChanged = true;
              }

              // Interface preview
              const iData = (updatedData as any).interfaceData;
              if (iData?.previewWidth || iData?.previewHeight) {
                if (iData.previewWidth) updatedStyle.width = iData.previewWidth;
                if (iData.previewHeight) updatedStyle.height = iData.previewHeight;
                styleChanged = true;
              }

              if (styleChanged) {
                didChange = true;
                return {
                  ...node,
                  data: persisted,
                  style: updatedStyle,
                };
              }
            }

            // Update node type based on nodeType in data (for if-else -> decisionNode)
            const nodeType = (updatedData as any).nodeType;
            const newType = nodeType === 'decisionNode' ? 'decisionNode' :
                           node.type; // Keep current type if not specified

            const cleanedCurrent = { ...node.data };
            delete (cleanedCurrent as any).onDeleteNode;
            delete (cleanedCurrent as any).onDuplicateNode;
            delete (cleanedCurrent as any).onNoteUpdate;

            if (deepEqual(cleanedCurrent, persisted) && node.type === newType) {
              return node;
            }

            didChange = true;

            return {
              ...node,
              type: newType,
              data: persisted
            };
          }
          return node;
        });
        return didChange ? nextNodes : nds;
      });
    },
    [setNodes]
  );

  return {
    handleDeleteEdge,
    handleDeleteNode,
    handleDuplicateNode,
    handleConnect,
    handleCreateNode,
    handleNodeUpdate,
  };
}


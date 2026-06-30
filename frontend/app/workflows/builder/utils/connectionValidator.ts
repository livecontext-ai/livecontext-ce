import type { Node, Edge, Connection } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { nodeRegistry } from '../registry/nodeRegistry';

interface NodeData extends BuilderNodeData {
  dataSourceData?: any;
  toolData?: any;
  apiData?: any;
}

/**
 * Check if a node ID is a trigger node based on ID or kind
 */
function isTriggerNodeId(nodeId: string, nodeKind?: string): boolean {
  return nodeKind === 'entry' ||
    nodeId === 'webhook-trigger' || nodeId.startsWith('webhook-trigger-') ||
    nodeId === 'schedule-trigger' || nodeId.startsWith('schedule-trigger-') ||
    nodeId === 'manual-trigger' || nodeId.startsWith('manual-trigger-') ||
    nodeId === 'chat-trigger' || nodeId.startsWith('chat-trigger-') ||
    nodeId === 'tables-trigger' || nodeId.startsWith('tables-trigger-') ||
    nodeId === 'triggers' || nodeId.startsWith('triggers-');
}

/**
 * Check if a node is a generic table (unspecified datasource)
 */
function isGenericTable(nodeId: string, dataSourceData?: any): boolean {
  return (nodeId === 'tables-trigger' || nodeId === 'tables' || nodeId.startsWith('tables-trigger-')) &&
    (!dataSourceData || !dataSourceData.dataSourceId || !dataSourceData.tableName);
}

/**
 * Check if a node is a generic trigger placeholder
 */
function isGenericTrigger(nodeId: string): boolean {
  return (nodeId === 'triggers' || nodeId.startsWith('triggers-')) &&
    !(nodeId === 'webhook-trigger' || nodeId.startsWith('webhook-trigger-')) &&
    !(nodeId === 'schedule-trigger' || nodeId.startsWith('schedule-trigger-')) &&
    !(nodeId === 'manual-trigger' || nodeId.startsWith('manual-trigger-')) &&
    !(nodeId === 'chat-trigger' || nodeId.startsWith('chat-trigger-')) &&
    !nodeId.startsWith('tables-trigger-');
}

/**
 * Check if a node is an AI agent
 */
function isAiAgentNode(nodeId: string, label?: string): boolean {
  return nodeId === 'ai-agent' || nodeId === 'agent' ||
    nodeId.startsWith('ai-agent-') || nodeId.startsWith('agent-') ||
    label?.toLowerCase().includes('agent') || false;
}

/**
 * Check if a node is a core node (control flow nodes)
 */
function isCoreNodeId(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isCoreNode(node);
}

/**
 * True when `(nodeId, sourceHandle)` is a NAMED output port of a branching or
 * while node that ALREADY has an outgoing edge - i.e. a second edge from it
 * would be rejected by {@link validateConnection} ("one port = one target").
 *
 * `isValidConnection` can only block the drag silently, so the canvas uses this
 * on connect-end to decide whether to surface a "this port is taken - add a
 * Fork to fan out" hint. Port-less nodes (trigger, plain step) are NOT flagged:
 * their multiple outgoing edges are a legitimate implicit fork.
 */
export function isWiredOutputPort(
  nodeId: string | null | undefined,
  sourceHandle: string | null | undefined,
  nodes: Node<BuilderNodeData>[],
  edges: Edge[]
): boolean {
  if (!nodeId || !sourceHandle) return false;
  const node = nodes.find((n) => n.id === nodeId);
  if (!node) return false;
  const isPortedNode =
    nodeRegistry.isBranchingNode(node as Node<BuilderNodeData>) ||
    nodeRegistry.isWhileGroupNode(node as Node<BuilderNodeData>);
  if (!isPortedNode) return false;
  return edges.some((e) => e.source === nodeId && e.sourceHandle === sourceHandle);
}

/**
 * Validates a connection between two nodes in the workflow builder
 */
export function validateConnection(
  connection: Connection,
  nodes: Node<BuilderNodeData>[],
  edges: Edge[]
): boolean {
  // Prevent self-connections (node connecting to itself)
  if (connection.source === connection.target) {
    return false;
  }

  // Allow all connections, except those pointing to loop step handles
  if (connection.sourceHandle && connection.sourceHandle.includes('-source') &&
    connection.sourceHandle.startsWith('loop-') &&
    !connection.sourceHandle.endsWith('-exit')) {
    return false;
  }
  if (connection.targetHandle && connection.targetHandle.includes('-target') &&
    connection.targetHandle.startsWith('loop-') &&
    !connection.targetHandle.endsWith('-entry')) {
    return false;
  }

  const sourceNode = nodes.find((n) => n.id === connection.source);
  const targetNode = nodes.find((n) => n.id === connection.target);

  // Prevent multiple connections from a while node's source handles - one per handle
  if (connection.sourceHandle && sourceNode && nodeRegistry.isWhileGroupNode(sourceNode as Node<BuilderNodeData>)) {
    const existingEdge = edges.find(
      (edge) => edge.source === connection.source &&
        edge.sourceHandle === connection.sourceHandle
    );
    if (existingEdge) {
      return false;
    }
  }

  // Prevent multiple entries to a while node - only one entry and one loop-back allowed
  if (targetNode && nodeRegistry.isWhileGroupNode(targetNode as Node<BuilderNodeData>)) {
    const targetHandle = connection.targetHandle;
    if (targetHandle) {
      const existingEdge = edges.find(
        (edge) => edge.target === connection.target &&
          edge.targetHandle === targetHandle
      );
      if (existingEdge) {
        return false;
      }
    }
  }

  // Prevent multiple entries to a decision/switch/classify/option node - only one entry is allowed
  // Use nodeRegistry for decision-like node detection
  if (targetNode && nodeRegistry.requiresSingleEntry(targetNode as Node<BuilderNodeData>)) {
    const existingEntryEdge = edges.find(
      (edge) => edge.target === connection.target
    );
    if (existingEntryEdge) {
      return false;
    }
  }

  // Prevent multiple connections from a decision/switch/classify/option node branch
  // Use nodeRegistry for branching node detection
  if (connection.sourceHandle && sourceNode) {
    if (nodeRegistry.isBranchingNode(sourceNode as Node<BuilderNodeData>)) {
      const existingBranchEdge = edges.find(
        (edge) => edge.source === connection.source &&
          edge.sourceHandle === connection.sourceHandle
      );
      if (existingBranchEdge) {
        return false;
      }
    }
  }

  // Interface nodes use standard left/right handles - no special handle constraints.

  // Triggers CAN have multiple outgoing connections (implicit fork).
  // The backend handles this via parallel execution of all branches.

  // Validate source node - prevent connections from non-connectable nodes
  if (sourceNode) {
    const sourceData = sourceNode.data as NodeData;
    const sourceId = sourceData.id || '';
    const sourceKind = sourceData.kind;
    const sourceDataSourceData = sourceData.dataSourceData;

    const isValidTrigger = sourceKind === 'entry' && sourceDataSourceData;
    const isSourceGenericTable = isGenericTable(sourceId, sourceDataSourceData);
    const isSourceTriggerGeneric = isGenericTrigger(sourceId);
    const isSourceGenericEntryTrigger = sourceKind === 'entry' &&
      !sourceDataSourceData &&
      !(sourceId === 'webhook-trigger' || sourceId.startsWith('webhook-trigger-')) &&
      !(sourceId === 'schedule-trigger' || sourceId.startsWith('schedule-trigger-')) &&
      !(sourceId === 'manual-trigger' || sourceId.startsWith('manual-trigger-')) &&
      !(sourceId === 'chat-trigger' || sourceId.startsWith('chat-trigger-')) &&
      !(sourceId === 'form-trigger' || sourceId.startsWith('form-trigger-')) &&
      !sourceId.startsWith('tables-trigger-') &&
      !sourceId.includes('-trigger-');
    const isSourceApiNode = sourceData.apiData && !sourceData.toolData;
    const isSourceMcpGeneric = (sourceId.startsWith('mcp-') || sourceId.startsWith('api-')) && !sourceData.toolData;

    if (!isValidTrigger && (isSourceTriggerGeneric || isSourceGenericEntryTrigger ||
      isSourceApiNode || isSourceMcpGeneric || isSourceGenericTable)) {
      return false;
    }
  }

  // Validate target node - prevent connections to non-connectable nodes
  if (targetNode) {
    const targetData = targetNode.data as NodeData;
    const targetId = targetData.id || '';
    const targetKind = targetData.kind;
    const targetDataSourceData = targetData.dataSourceData;

    const isTargetGenericTable = isGenericTable(targetId, targetDataSourceData);
    const isTargetTriggerGeneric = isGenericTrigger(targetId);
    const isTargetGenericEntryTrigger = targetKind === 'entry' &&
      !(targetId === 'webhook-trigger' || targetId.startsWith('webhook-trigger-')) &&
      !(targetId === 'schedule-trigger' || targetId.startsWith('schedule-trigger-')) &&
      !(targetId === 'manual-trigger' || targetId.startsWith('manual-trigger-')) &&
      !(targetId === 'chat-trigger' || targetId.startsWith('chat-trigger-')) &&
      !(targetId === 'form-trigger' || targetId.startsWith('form-trigger-')) &&
      !targetId.startsWith('tables-trigger-') &&
      !targetId.includes('-trigger-');
    const isTargetApiNode = targetData.apiData && !targetData.toolData;
    const isTargetMcpGeneric = (targetId.startsWith('mcp-') || targetId.startsWith('api-')) && !targetData.toolData;

    if (isTargetTriggerGeneric || isTargetGenericEntryTrigger ||
      isTargetApiNode || isTargetMcpGeneric || isTargetGenericTable) {
      return false;
    }
  }

  return true;
}

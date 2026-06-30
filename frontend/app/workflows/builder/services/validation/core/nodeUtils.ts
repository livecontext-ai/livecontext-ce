/**
 * Centralized node type utilities
 * Uses nodeRegistry as the single source of truth for node type detection.
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import type { WorkflowNodeType } from './types';
import { nodeRegistry } from '../../../registry/nodeRegistry';

/**
 * Determines if a node is a trigger node
 */
export function isTriggerNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isTrigger(node);
}

/**
 * Determines if a node is a control node (decision, switch, option, loop, split, aggregate, exit, response, merge, fork)
 */
export function isControlNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isControlNode(node);
}

/**
 * Determines if a node is an interface node
 */
export function isInterfaceNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isInterfaceNode(node);
}

/**
 * Determines if a node is an agent node
 */
export function isAgentNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isAgentNode(node);
}

/**
 * Determines if a node is a CRUD/table node
 */
export function isCrudNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isCrudNode(node);
}

/**
 * Determines if a node is a step node (MCP tool call, not table/interface/agent/control/trigger)
 */
export function isStepNode(node: Node<BuilderNodeData>): boolean {
  return (
    !isControlNode(node) &&
    !isTriggerNode(node) &&
    !isInterfaceNode(node) &&
    !isAgentNode(node) &&
    !isCrudNode(node) &&
    !isNoteNode(node) &&
    node.data.toolData !== undefined
  );
}

/**
 * Determines if a node is a note node (non-executable)
 */
export function isNoteNode(node: Node<BuilderNodeData>): boolean {
  return nodeRegistry.isNoteNode(node);
}

/**
 * Gets the workflow node type for a node
 */
export function getNodeType(node: Node<BuilderNodeData>): WorkflowNodeType {
  if (isTriggerNode(node)) return 'trigger';
  if (isControlNode(node)) return 'core';
  if (isAgentNode(node)) return 'agent';
  if (isCrudNode(node)) return 'table';
  if (isInterfaceNode(node)) return 'interface';
  if (isNoteNode(node)) return 'note';
  return 'mcp';
}

/** Valid node key prefixes */
export const VALID_PREFIXES: WorkflowNodeType[] = ['trigger', 'mcp', 'core', 'agent', 'table', 'interface', 'note'];

/**
 * Gets the element key for a node (used for validation issues)
 */
export function getElementKey(node: Node<BuilderNodeData>, normalizedLabel?: string | null): string {
  const nodeType = getNodeType(node);
  const identifier = normalizedLabel || node.id;
  return `${nodeType}:${identifier}`;
}

/**
 * Categorizes all nodes by type
 */
export function categorizeNodes(nodes: Node<BuilderNodeData>[]): {
  triggers: Node<BuilderNodeData>[];
  mcps: Node<BuilderNodeData>[];
  agents: Node<BuilderNodeData>[];
  cores: Node<BuilderNodeData>[];
  interfaceNodes: Node<BuilderNodeData>[];
  tableNodes: Node<BuilderNodeData>[];
} {
  const triggers: Node<BuilderNodeData>[] = [];
  const mcps: Node<BuilderNodeData>[] = [];
  const agents: Node<BuilderNodeData>[] = [];
  const cores: Node<BuilderNodeData>[] = [];
  const interfaceNodes: Node<BuilderNodeData>[] = [];
  const tableNodes: Node<BuilderNodeData>[] = [];

  for (const node of nodes) {
    if (isNoteNode(node)) continue;

    if (isTriggerNode(node)) {
      triggers.push(node);
    } else if (isControlNode(node)) {
      cores.push(node);
    } else if (isInterfaceNode(node)) {
      interfaceNodes.push(node);
    } else if (isAgentNode(node)) {
      agents.push(node);
    } else if (isCrudNode(node)) {
      tableNodes.push(node);
    } else if (node.data.toolData !== undefined) {
      mcps.push(node);
    }
  }

  return { triggers, mcps, agents, cores, interfaceNodes, tableNodes };
}

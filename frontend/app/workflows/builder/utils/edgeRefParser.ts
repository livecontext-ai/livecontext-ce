/**
 * EdgeRefParser - Parser for V2 edge reference format
 *
 * === PREFIX SYSTEM (7 categories) ===
 *
 * | Prefix      | Category  | Applies To                                              |
 * |-------------|-----------|--------------------------------------------------------|
 * | trigger:    | Entry     | All triggers (webhook, chat, schedule, etc.)            |
 * | mcp:        | MCP       | Tools (MCP tool calls)                                  |
 * | table:      | Table     | CRUD operations (database tables)                       |
 * | agent:      | AI        | Agent, Guardrail, Classify                              |
 * | core:       | Core      | Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork, Download File, HTTP Request, Data Input, User Approval |
 * | note:       | Note      | Notes                                                   |
 * | interface:  | Interface | Interfaces                                              |
 *
 * Formats supported:
 * - "trigger:label" → { nodeType: "trigger", nodeLabel: "label", port: undefined }
 * - "mcp:label" → { nodeType: "mcp", nodeLabel: "label", port: undefined }
 * - "table:label" → { nodeType: "table", nodeLabel: "label", port: undefined }
 * - "agent:label" → { nodeType: "agent", nodeLabel: "label", port: undefined }
 * - "core:label" → { nodeType: "core", nodeLabel: "label", port: undefined }
 * - "core:label:if" → { nodeType: "core", nodeLabel: "label", port: "if" } (decision)
 * - "core:label:else" → { nodeType: "core", nodeLabel: "label", port: "else" } (decision)
 * - "core:label:elseif_0" → { nodeType: "core", nodeLabel: "label", port: "elseif_0" } (decision)
 * - "core:label:case_0" → { nodeType: "core", nodeLabel: "label", port: "case_0" } (switch)
 * - "core:label:default" → { nodeType: "core", nodeLabel: "label", port: "default" } (switch)
 * - "core:label:body" → { nodeType: "core", nodeLabel: "label", port: "body" } (loop)
 * - "core:label:iterate" → { nodeType: "core", nodeLabel: "label", port: "iterate" } (loop)
 * - "core:label:exit" → { nodeType: "core", nodeLabel: "label", port: "exit" } (loop)
 * - "core:label:branch_0" → { nodeType: "core", nodeLabel: "label", port: "branch_0" } (fork)
 * - "core:label:branch_1" → { nodeType: "core", nodeLabel: "label", port: "branch_1" } (fork)
 * - "agent:label:category_0" → { nodeType: "agent", nodeLabel: "label", port: "category_0" } (classify)
 * - "agent:label:category_1" → { nodeType: "agent", nodeLabel: "label", port: "category_1" } (classify)
 * - "note:label" → { nodeType: "note", nodeLabel: "label", port: undefined }
 * - "interface:label" → { nodeType: "interface", nodeLabel: "label", port: undefined }
 */

export type NodeType =
  | 'trigger'
  | 'mcp'
  | 'table'
  | 'agent'
  | 'core'
  | 'note'
  | 'interface';

export type DecisionPort = 'if' | 'else' | `elseif_${number}`;
export type SwitchPort = `case_${number}` | 'default';
export type LoopPort = 'body' | 'iterate' | 'exit';
export type ForkPort = `branch_${number}`;
export type ClassifyPort = `category_${number}`;

export type EdgePort = DecisionPort | SwitchPort | LoopPort | ForkPort | ClassifyPort;

export interface EdgeRef {
  nodeType: NodeType;
  nodeLabel: string;
  port?: string;
}

const VALID_NODE_TYPES: Set<string> = new Set([
  'trigger',
  'mcp',
  'table',
  'agent',
  'core',
  'note',
  'interface',
]);

const NODES_WITH_PORTS: Set<string> = new Set(['core', 'agent']);

/**
 * Parse an edge reference string into its components.
 *
 * @param ref - Edge reference string (e.g., "core:check:if", "mcp:fetch_data")
 * @returns Parsed EdgeRef object
 * @throws Error if ref is invalid
 */
export function parseEdgeRef(ref: string): EdgeRef {
  if (!ref || typeof ref !== 'string') {
    throw new Error(`Invalid edge ref: ${ref}`);
  }

  const parts = ref.split(':');

  if (parts.length < 2) {
    throw new Error(`Invalid edge ref format (expected at least 2 parts): ${ref}`);
  }

  const nodeType = parts[0] as NodeType;

  if (!VALID_NODE_TYPES.has(nodeType)) {
    throw new Error(`Invalid node type: ${nodeType} in ref: ${ref}`);
  }

  // For nodes with ports (decision, switch, loop), the format is type:label:port
  // For other nodes, any additional parts are part of the label
  if (NODES_WITH_PORTS.has(nodeType) && parts.length >= 3) {
    // Last part is the port
    const port = parts[parts.length - 1];
    // Everything in between is the label
    const nodeLabel = parts.slice(1, -1).join(':');

    return {
      nodeType,
      nodeLabel,
      port,
    };
  }

  // For nodes without ports or when no port is specified
  const nodeLabel = parts.slice(1).join(':');

  return {
    nodeType,
    nodeLabel,
    port: undefined,
  };
}

/**
 * Build an edge reference string from components.
 *
 * @param nodeType - Type of node
 * @param nodeLabel - Normalized label of node
 * @param port - Optional port (for decision, switch, loop)
 * @returns Edge reference string
 */
export function buildEdgeRef(
  nodeType: NodeType,
  nodeLabel: string,
  port?: string
): string {
  if (!VALID_NODE_TYPES.has(nodeType)) {
    throw new Error(`Invalid node type: ${nodeType}`);
  }

  if (!nodeLabel) {
    throw new Error('Node label is required');
  }

  if (port) {
    if (!NODES_WITH_PORTS.has(nodeType)) {
      throw new Error(`Node type ${nodeType} does not support ports`);
    }
    return `${nodeType}:${nodeLabel}:${port}`;
  }

  return `${nodeType}:${nodeLabel}`;
}

/**
 * Check if a reference string has a port.
 */
export function hasPort(ref: string): boolean {
  const parsed = parseEdgeRef(ref);
  return parsed.port !== undefined;
}

/**
 * Get the node key (without port) from a reference.
 * "core:check:if" → "core:check"
 * "mcp:fetch" → "mcp:fetch"
 */
export function getNodeKey(ref: string): string {
  const parsed = parseEdgeRef(ref);
  return `${parsed.nodeType}:${parsed.nodeLabel}`;
}

/**
 * Get just the port from a reference, or undefined if none.
 */
export function getPort(ref: string): string | undefined {
  const parsed = parseEdgeRef(ref);
  return parsed.port;
}

/**
 * Check if a reference is for a specific node type.
 */
export function isNodeType(ref: string, nodeType: NodeType): boolean {
  const parsed = parseEdgeRef(ref);
  return parsed.nodeType === nodeType;
}

/**
 * Check if a reference points to a decision branch (has :if, :else, :elseif_N port).
 */
export function isDecisionBranch(ref: string): boolean {
  const parsed = parseEdgeRef(ref);
  if (parsed.nodeType !== 'core' || !parsed.port) {
    return false;
  }
  return (
    parsed.port === 'if' ||
    parsed.port === 'else' ||
    parsed.port.startsWith('elseif_')
  );
}

/**
 * Check if a reference points to a switch case (has :case_N or :default port).
 */
export function isSwitchCase(ref: string): boolean {
  const parsed = parseEdgeRef(ref);
  if (parsed.nodeType !== 'core' || !parsed.port) {
    return false;
  }
  return parsed.port === 'default' || parsed.port.startsWith('case_');
}

/**
 * Check if a reference points to a loop port (has :body, :iterate, or :exit).
 */
export function isLoopPort(ref: string): boolean {
  const parsed = parseEdgeRef(ref);
  if (parsed.nodeType !== 'core' || !parsed.port) {
    return false;
  }
  return (
    parsed.port === 'body' ||
    parsed.port === 'iterate' ||
    parsed.port === 'exit'
  );
}

/**
 * Check if a reference points to a fork branch (has :branch_N port).
 */
export function isForkBranch(ref: string): boolean {
  const parsed = parseEdgeRef(ref);
  if (parsed.nodeType !== 'core' || !parsed.port) {
    return false;
  }
  return parsed.port.startsWith('branch_');
}

/**
 * Extract the branch index from a fork port.
 * "branch_0" → { type: "branch", index: 0 }
 */
export function parseForkPort(
  port: string
): { type: 'branch'; index: number } | null {
  const branchMatch = port.match(/^branch_(\d+)$/);
  if (branchMatch) {
    return { type: 'branch', index: parseInt(branchMatch[1], 10) };
  }
  return null;
}

/**
 * Build a fork port string.
 */
export function buildForkPort(index: number): string {
  return `branch_${index}`;
}

/**
 * Extract the condition type from a decision port.
 * "if" → { type: "if", index: undefined }
 * "else" → { type: "else", index: undefined }
 * "elseif_0" → { type: "elseif", index: 0 }
 */
export function parseDecisionPort(
  port: string
): { type: 'if' | 'else' | 'elseif'; index?: number } | null {
  if (port === 'if') {
    return { type: 'if' };
  }
  if (port === 'else') {
    return { type: 'else' };
  }
  const elseifMatch = port.match(/^elseif_(\d+)$/);
  if (elseifMatch) {
    return { type: 'elseif', index: parseInt(elseifMatch[1], 10) };
  }
  return null;
}

/**
 * Extract the case index from a switch port.
 * "case_0" → { type: "case", index: 0 }
 * "default" → { type: "default", index: undefined }
 */
export function parseSwitchPort(
  port: string
): { type: 'case' | 'default'; index?: number } | null {
  if (port === 'default') {
    return { type: 'default' };
  }
  const caseMatch = port.match(/^case_(\d+)$/);
  if (caseMatch) {
    return { type: 'case', index: parseInt(caseMatch[1], 10) };
  }
  return null;
}

/**
 * Build a decision port string.
 */
export function buildDecisionPort(
  type: 'if' | 'else' | 'elseif',
  index?: number
): string {
  if (type === 'elseif' && index !== undefined) {
    return `elseif_${index}`;
  }
  return type;
}

/**
 * Build a switch port string.
 */
export function buildSwitchPort(type: 'case' | 'default', index?: number): string {
  if (type === 'case' && index !== undefined) {
    return `case_${index}`;
  }
  return 'default';
}

/**
 * Check if a reference points to a classify category (has :category_N port).
 */
export function isClassifyCategory(ref: string): boolean {
  const parsed = parseEdgeRef(ref);
  if (parsed.nodeType !== 'agent' || !parsed.port) {
    return false;
  }
  return parsed.port.startsWith('category_');
}

/**
 * Extract the category index from a classify port.
 * "category_0" → { type: "category", index: 0 }
 */
export function parseClassifyPort(
  port: string
): { type: 'category'; index: number } | null {
  const categoryMatch = port.match(/^category_(\d+)$/);
  if (categoryMatch) {
    return { type: 'category', index: parseInt(categoryMatch[1], 10) };
  }
  return null;
}

/**
 * Build a classify category port string.
 */
export function buildClassifyPort(index: number): string {
  return `category_${index}`;
}

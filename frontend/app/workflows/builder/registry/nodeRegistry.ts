/**
 * Central Node Registry - Single source of truth for all workflow node types.
 *
 * This registry eliminates hardcoded node type checks scattered across 40+ files.
 * Instead of: `if (node.type === 'decisionNode' || node.type === 'switchNode')`
 * Use: `if (nodeRegistry.isBranchingNode(node))`
 *
 * @example
 * // Check if node is a control node
 * import { nodeRegistry } from '@/workflows/builder/registry/nodeRegistry';
 * if (nodeRegistry.isControlNode(node)) { ... }
 *
 * // Get prefix for streaming matching
 * const prefix = nodeRegistry.getPrefix(node.type); // 'core', 'mcp', 'trigger', etc.
 *
 * // Check if node has ports (branching)
 * if (nodeRegistry.hasPorts(node.type)) { ... }
 */

import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../types';

// ==================== Node Definition Types ====================

export type NodePrefix = 'trigger' | 'mcp' | 'core' | 'agent' | 'table' | 'interface' | 'note';

export type NodeKind =
  | 'entry'      // Triggers
  | 'action'     // MCP steps
  | 'exit'       // Exit node
  | 'response'   // Respond to Chat
  | 'decision'   // Decision (if/else)
  | 'switch'     // Switch (case)
  | 'option'     // Option (user choice)
  | 'approval'   // User Approval
  | 'condition'  // backward compat
  | 'loop'       // While Loop
  | 'split'      // Split (parallel iteration)
  | 'aggregate'  // Aggregate (collect items)
  | 'fork'       // Fork (parallel branches)
  | 'merge'      // Merge (join branches)
  | 'parallel'   // backward compat
  | 'transform'  // Transform
  | 'wait'       // Wait
  | 'download_file' // Download File
  | 'public_link' // Public Link (expiring signed URL for a stored file)
  | 'media'      // Media (probe/mux/mix/extract audio-video via ffmpeg sidecar)
  | 'http_request'  // HTTP Request
  | 'data_input'    // Data Input
  | 'filter'     // Filter
  | 'sort'       // Sort
  | 'limit'      // Limit
  | 'remove_duplicates' // Remove Duplicates
  | 'summarize'  // Summarize/Pivot
  | 'date_time'  // Date/Time
  | 'crypto_jwt' // Crypto/JWT
  | 'xml'        // XML Parse/Build
  | 'compression' // Compression/Decompression
  | 'rss'        // RSS Feed
  | 'convert_to_file'  // Convert JSON to file (CSV, XLSX, JSON, TXT)
  | 'extract_from_file' // Extract data from file (CSV, XLSX, JSON)
  | 'compare_datasets' // Compare two datasets (diff)
  | 'sub_workflow'  // Execute another workflow as a function
  | 'respond_to_webhook' // Control HTTP response to webhook caller
  | 'send_email' // Send email via SMTP
  | 'email_inbox' // Read a mailbox + act on messages via IMAP
  | 'set'        // Set / Edit Fields
  | 'html_extract' // HTML Extract via CSS selectors (jsoup)
  | 'task'       // Task CRUD (create, get, update, delete, list agent tasks)
  | 'code'       // Execute user code via Piston
  | 'stop_on_error' // Terminal: fail workflow with error
  | 'ssh'        // SSH command execution
  | 'sftp'       // SFTP file operations
  | 'database'   // SQL query execution
  | 'output'     // Response
  | 'agent'      // Agent
  | 'guardrail'  // Guardrail
  | 'classify'   // Classify
  | 'browser_agent' // Browser-driving agent (agent:browser_agent)
  | 'crud'       // CRUD operations
  | 'find'       // Find operations
  | 'data'       // backward compat
  | 'interface'  // UI interfaces
  | 'note';      // Notes

export interface NodeDefinition {
  /** React Flow node type (e.g., 'decisionNode', 'flowNode') */
  type: string;
  /** Backend prefix for streaming events (e.g., 'core', 'mcp') */
  prefix: NodePrefix;
  /** Node kind for categorization */
  kind: NodeKind;
  /** Whether this node has output ports (branching) */
  hasPorts: boolean;
  /** Port naming pattern (e.g., 'choice_' for option, 'case_' for switch) */
  portPattern?: string;
  /** Whether only one input edge is allowed */
  singleEntry: boolean;
  /** Whether this is a terminal node (no output edges) */
  terminal: boolean;
  /** Human-readable name */
  label: string;
  /** Category for grouping */
  category: 'trigger' | 'control' | 'action' | 'agent' | 'data' | 'utility' | 'other';
}

// ==================== Node Definitions ====================

const NODE_DEFINITIONS: Record<string, NodeDefinition> = {
  // === Triggers ===
  triggerNode: {
    type: 'triggerNode',
    prefix: 'trigger',
    kind: 'entry',
    hasPorts: false,
    singleEntry: false, // Triggers have no input
    terminal: false,
    label: 'Trigger',
    category: 'trigger',
  },

  // === Control Flow - Branching ===
  decisionNode: {
    type: 'decisionNode',
    prefix: 'core',
    kind: 'decision',
    hasPorts: true,
    portPattern: 'if|else|elseif_',
    singleEntry: true,
    terminal: false,
    label: 'Decision',
    category: 'control',
  },
  switchNode: {
    type: 'switchNode',
    prefix: 'core',
    kind: 'switch',
    hasPorts: true,
    portPattern: 'case_|default',
    singleEntry: true,
    terminal: false,
    label: 'Switch',
    category: 'control',
  },
  optionNode: {
    type: 'optionNode',
    prefix: 'core',
    kind: 'option',
    hasPorts: true,
    portPattern: 'choice_',
    singleEntry: true,
    terminal: false,
    label: 'Option',
    category: 'control',
  },
  userApprovalNode: {
    type: 'userApprovalNode',
    prefix: 'core',
    kind: 'approval',
    hasPorts: true,
    portPattern: 'approved|rejected|timeout',
    singleEntry: true,
    terminal: false,
    label: 'User Approval',
    category: 'control',
  },

  // === Control Flow - Loops ===
  splitNode: {
    type: 'splitNode',
    prefix: 'core',
    kind: 'split',
    hasPorts: false, // Split uses internal spawning, not ports
    singleEntry: true,
    terminal: false,
    label: 'Split',
    category: 'control',
  },

  // === Control Flow - Parallel ===
  forkNode: {
    type: 'forkNode',
    prefix: 'core',
    kind: 'fork',
    hasPorts: true,
    portPattern: 'branch_',
    singleEntry: true,
    terminal: false,
    label: 'Fork',
    category: 'control',
  },
  mergeNode: {
    type: 'mergeNode',
    prefix: 'core',
    kind: 'merge',
    hasPorts: false,
    singleEntry: false, // Merge accepts multiple inputs
    terminal: false,
    label: 'Merge',
    category: 'control',
  },

  // === Utility Nodes ===
  transformNode: {
    type: 'flowNode', // Transform uses flowNode with kind='transform'
    prefix: 'core',
    kind: 'transform',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Transform',
    category: 'utility',
  },
  waitNode: {
    type: 'flowNode', // Wait uses flowNode with kind='wait'
    prefix: 'core',
    kind: 'wait',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Wait',
    category: 'utility',
  },
  downloadFileNode: {
    type: 'flowNode', // Download uses flowNode with kind='download_file'
    prefix: 'core',
    kind: 'download_file',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Download File',
    category: 'utility',
  },
  publicLinkNode: {
    type: 'flowNode', // Public Link uses flowNode with kind='public_link'
    prefix: 'core',
    kind: 'public_link',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Public Link',
    category: 'utility',
  },
  mediaNode: {
    type: 'flowNode', // Media uses flowNode with kind='media'
    prefix: 'core',
    kind: 'media',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Media',
    category: 'utility',
  },
  httpRequestNode: {
    type: 'flowNode', // HTTP Request uses flowNode with kind='http_request'
    prefix: 'core',
    kind: 'http_request',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'HTTP Request',
    category: 'utility',
  },
  dataInputNode: {
    type: 'flowNode', // Data Input uses flowNode with kind='data_input'
    prefix: 'core',
    kind: 'data_input',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Data Input',
    category: 'utility',
  },
  exitNode: {
    type: 'exitNode',
    prefix: 'core',
    kind: 'exit',
    hasPorts: false,
    singleEntry: false,
    terminal: true, // No output edges
    label: 'Exit',
    category: 'utility',
  },
  responseNode: {
    type: 'responseNode',
    prefix: 'core',
    kind: 'response',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Respond to Chat',
    category: 'utility',
  },
  aggregateNode: {
    type: 'aggregateNode',
    prefix: 'core',
    kind: 'aggregate',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Aggregate',
    category: 'utility',
  },

  // === Data Manipulation Nodes ===
  filterNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'filter',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Filter',
    category: 'utility',
  },
  sortNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'sort',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Sort',
    category: 'utility',
  },
  limitNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'limit',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Limit',
    category: 'utility',
  },
  removeDuplicatesNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'remove_duplicates',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Remove Duplicates',
    category: 'utility',
  },
  summarizeNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'summarize',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Summarize',
    category: 'utility',
  },
  dateTimeNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'date_time',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Date/Time',
    category: 'utility',
  },
  cryptoJwtNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'crypto_jwt',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Crypto/JWT',
    category: 'utility',
  },
  xmlNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'xml',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'XML',
    category: 'utility',
  },
  compressionNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'compression',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Compression',
    category: 'utility',
  },
  rssNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'rss',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'RSS',
    category: 'utility',
  },
  convertToFileNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'convert_to_file',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Convert to File',
    category: 'utility',
  },
  extractFromFileNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'extract_from_file',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Extract from File',
    category: 'utility',
  },
  compareDatasetsNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'compare_datasets',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Compare Datasets',
    category: 'utility',
  },

  setNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'set',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Set / Edit Fields',
    category: 'utility',
  },
  htmlExtractNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'html_extract',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'HTML Extract',
    category: 'utility',
  },
  taskNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'task',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Task',
    category: 'utility',
  },

  subWorkflowNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'sub_workflow',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Sub-Workflow',
    category: 'utility',
  },

  respondToWebhookNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'respond_to_webhook',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Respond to Webhook',
    category: 'utility',
  },

  sendEmailNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'send_email',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Send Email',
    category: 'utility',
  },

  emailInboxNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'email_inbox',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Email Inbox',
    category: 'utility',
  },

  codeNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'code',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Code',
    category: 'utility',
  },

  stopOnErrorNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'stop_on_error',
    hasPorts: false,
    singleEntry: false,
    terminal: true,
    label: 'Stop on Error',
    category: 'utility',
  },

  sshNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'ssh',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'SSH',
    category: 'utility',
  },

  sftpNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'sftp',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'SFTP',
    category: 'utility',
  },

  databaseNode: {
    type: 'flowNode',
    prefix: 'core',
    kind: 'database',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Database',
    category: 'utility',
  },

  // === Action Nodes ===
  flowNode: {
    type: 'flowNode',
    prefix: 'mcp',
    kind: 'action',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Step',
    category: 'action',
  },

  // === Agent Nodes ===
  agentNode: {
    type: 'agentNode',
    prefix: 'agent',
    kind: 'agent',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Agent',
    category: 'agent',
  },
  guardrailNode: {
    type: 'guardrailNode',
    prefix: 'agent',
    kind: 'guardrail',
    hasPorts: true,
    portPattern: 'pass|fail',
    singleEntry: true,
    terminal: false,
    label: 'Guardrail',
    category: 'agent',
  },
  classifyNode: {
    type: 'classifyNode',
    prefix: 'agent',
    kind: 'classify',
    hasPorts: true,
    portPattern: 'category_',
    singleEntry: true,
    terminal: false,
    label: 'Classify',
    category: 'agent',
  },
  browserAgentNode: {
    type: 'browserAgentNode',
    prefix: 'agent',
    kind: 'browser_agent',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Browser Agent',
    category: 'agent',
  },

  // === Data Nodes ===
  crudNode: {
    type: 'crudNode',
    prefix: 'table',
    kind: 'crud',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'CRUD',
    category: 'data',
  },
  findNode: {
    type: 'crudNode', // FindNode uses crudNode type with crudOperation='find-row'
    prefix: 'table',
    kind: 'find',
    hasPorts: false, // Like Split, uses internal spawning, not ports
    singleEntry: false,
    terminal: false,
    label: 'Find',
    category: 'data',
  },

  // === Interface Nodes ===
  interfaceNode: {
    type: 'interfaceNode',
    prefix: 'interface',
    kind: 'interface',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Interface',
    category: 'other',
  },

  // === Note Nodes ===
  noteNode: {
    type: 'noteNode',
    prefix: 'note',
    kind: 'note',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Note',
    category: 'other',
  },

  // === While Group Container ===
  whileGroupNode: {
    type: 'whileGroupNode',
    prefix: 'core',
    kind: 'loop',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'While',
    category: 'control',
  },

  // === Workflow Container Nodes ===
  workflowNode: {
    type: 'workflowNode',
    prefix: 'mcp',
    kind: 'action',
    hasPorts: false,
    singleEntry: false,
    terminal: false,
    label: 'Workflow',
    category: 'other',
  },
};

// ==================== Helper Types ====================

type NodeType = keyof typeof NODE_DEFINITIONS;

// ==================== Node Registry Class ====================

class NodeRegistry {
  private definitions = NODE_DEFINITIONS;
  /** kind → prefix lookup built once from definitions (single source of truth) */
  private kindToPrefix: Map<string, NodePrefix>;

  constructor() {
    // Build kind → prefix map. When a kind appears in multiple definitions
    // (e.g. 'action' in both exitNode/core and flowNode/mcp), the generic
    // flowNode entry wins because exit/response nodes override their kind
    // at render time to 'exit'/'response'.
    this.kindToPrefix = new Map();
    for (const def of Object.values(this.definitions)) {
      if (def.kind) {
        this.kindToPrefix.set(def.kind, def.prefix);
      }
    }
  }

  /**
   * Get the backend prefix for a given node kind.
   * Single source of truth - no hardcoded lists needed anywhere else.
   */
  getPrefixForKind = (kind: string): NodePrefix | null => {
    return this.kindToPrefix.get(kind) ?? null;
  };

  // === Basic Getters ===

  /**
   * Get definition for a node type.
   */
  getDefinition(nodeType: string): NodeDefinition | undefined {
    return this.definitions[nodeType];
  }

  /**
   * Get all node definitions.
   */
  getAllDefinitions(): Record<string, NodeDefinition> {
    return { ...this.definitions };
  }

  /**
   * Get backend prefix for a node type.
   */
  getPrefix(nodeType: string): NodePrefix | null {
    return this.definitions[nodeType]?.prefix ?? null;
  }

  /**
   * Get prefixes for streaming event matching.
   * Handles flowNode specially based on kind.
   */
  getPrefixesForNode(node: Node<BuilderNodeData>): NodePrefix[] {
    const kind = (node.data as any)?.kind;

    // For flowNode, resolve prefix from kind (single source of truth)
    if (node.type === 'flowNode') {
      const kindPrefix = kind ? this.kindToPrefix.get(kind) : null;
      if (kindPrefix) return [kindPrefix];
      return ['mcp', 'agent'];
    }

    const def = this.definitions[node.type || ''];
    if (def) {
      // Agent nodes can match both 'agent' and 'mcp' prefixes
      if (def.prefix === 'agent') {
        return ['agent', 'mcp'];
      }
      return [def.prefix];
    }

    return ['mcp', 'agent']; // Default fallback
  }

  // === Category Checks ===

  /**
   * Check if node is a trigger.
   */
  isTrigger(node: Node<BuilderNodeData>): boolean {
    return node.type === 'triggerNode' || node.data?.kind === 'entry';
  }

  /**
   * Check if node is a control flow node (decision, loop, fork, etc.).
   */
  isControlNode(node: Node<BuilderNodeData>): boolean {
    const type = node.type || '';
    const kind = (node.data as any)?.kind;

    // Check by type first
    const controlTypes = [
      'decisionNode', 'switchNode', 'optionNode', 'userApprovalNode',
      'splitNode',
      'forkNode', 'mergeNode',
      'exitNode', 'responseNode', 'aggregateNode',
      'whileGroupNode',
    ];
    if (controlTypes.includes(type)) return true;

    // Check by kind for flowNode-based control nodes
    const controlKinds = ['transform', 'wait', 'download_file', 'public_link', 'media', 'http_request', 'data_input', 'filter', 'sort', 'limit', 'remove_duplicates', 'summarize', 'date_time', 'crypto_jwt', 'xml', 'compression', 'rss', 'convert_to_file', 'extract_from_file', 'compare_datasets', 'set', 'html_extract', 'sub_workflow', 'respond_to_webhook', 'send_email', 'email_inbox', 'code', 'task', 'stop_on_error', 'ssh', 'sftp', 'database', 'output', 'exit'];
    if (controlKinds.includes(kind)) return true;

    // Check by is*Node() for nodes created from palette with generic kind: 'action'
    if (kind === 'action' && type === 'flowNode') {
      if (this.isCodeNode(node) || this.isSendEmailNode(node) || this.isEmailInboxNode(node) || this.isRespondToWebhookNode(node) ||
          this.isSubWorkflowNode(node) || this.isRssNode(node) || this.isFilterNode(node) ||
          this.isSortNode(node) || this.isLimitNode(node) || this.isRemoveDuplicatesNode(node) ||
          this.isSummarizeNode(node) || this.isCompareDatasetsNode(node) ||
          this.isConvertToFileNode(node) || this.isExtractFromFileNode(node) ||
          this.isCompressionNode(node) || this.isXmlNode(node) ||
          this.isCryptoJwtNode(node) || this.isDateTimeNode(node) ||
          this.isSetNode(node) || this.isHtmlExtractNode(node) || this.isTaskNode(node) ||
          this.isStopOnErrorNode(node) || this.isSshNode(node) || this.isSftpNode(node) || this.isDatabaseNode(node)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Check if node is a branching node (has multiple output ports).
   */
  isBranchingNode(node: Node<BuilderNodeData>): boolean {
    const type = node.type || '';
    return this.definitions[type]?.hasPorts === true;
  }

  /**
   * Check if node is a decision-like node (decision, switch, option).
   * These are branching nodes where only ONE branch executes.
   */
  isDecisionLikeNode(node: Node<BuilderNodeData>): boolean {
    const type = node.type || '';
    const kind = (node.data as any)?.kind;
    return type === 'decisionNode' ||
           type === 'switchNode' ||
           type === 'optionNode' ||
           type === 'userApprovalNode' ||
           kind === 'decision' ||
           kind === 'switch' ||
           kind === 'option' ||
           kind === 'approval' ||
           kind === 'condition'; // backward compat
  }

  /**
   * Check if node requires single entry (only one input edge allowed).
   */
  requiresSingleEntry(node: Node<BuilderNodeData>): boolean {
    const type = node.type || '';
    return this.definitions[type]?.singleEntry === true;
  }

  /**
   * Check if node is terminal (no output edges allowed).
   */
  isTerminal(node: Node<BuilderNodeData>): boolean {
    const type = node.type || '';
    return this.definitions[type]?.terminal === true;
  }

  /**
   * Check if node is a core node (uses 'core:' prefix in streaming events).
   */
  isCoreNode(node: Node<BuilderNodeData>): boolean {
    const type = node.type || '';
    const kind = (node.data as any)?.kind;

    // Check by type
    const coreTypes = [
      'decisionNode', 'switchNode', 'optionNode', 'userApprovalNode',
      'splitNode',
      'forkNode', 'mergeNode',
      'exitNode', 'responseNode', 'aggregateNode',
      'whileGroupNode',
    ];
    if (coreTypes.includes(type)) return true;

    if (this.kindToPrefix.get(kind) === 'core') return true;

    return false;
  }

  /**
   * Check if node is an agent node.
   */
  isAgentNode(node: Node<BuilderNodeData>): boolean {
    const type = node.type || '';
    const kind = (node.data as any)?.kind;
    return type === 'agentNode' ||
           type === 'guardrailNode' ||
           type === 'classifyNode' ||
           type === 'browserAgentNode' ||
           (node.data as any)?.agentData != null ||
           kind === 'reasoning' ||
           kind === 'guardrail' ||
           kind === 'classify' ||
           kind === 'browser_agent';
  }

  /**
   * Check if node is the "AI Agent" node specifically - the reasoning agent
   * that uses the entity-reference panel. Excludes classify, guardrail, and
   * browser_agent which each have their own dedicated forms.
   *
   * Exact-name matching only - never matches on label substrings.
   */
  isAiAgentNode(node: Node<BuilderNodeData>): boolean {
    const type = node.type || '';
    const kind = (node.data as any)?.kind;
    const nodeDataId = node.data?.id || '';
    const agentType = (node.data as any)?.agentType;
    const isCanonical =
      type === 'agentNode' ||
      kind === 'reasoning' ||
      nodeDataId === 'ai-agent' ||
      nodeDataId === 'agent' ||
      nodeDataId.startsWith('agent-') ||
      agentType === 'agent';
    if (!isCanonical) return false;
    return !this.isClassifyNode(node) &&
           !this.isGuardrailNode(node) &&
           !this.isBrowserAgentNode(node);
  }

  /**
   * Check if node is a Browser Agent node (agent:browser_agent).
   */
  isBrowserAgentNode(node: Node<BuilderNodeData>): boolean {
    const type = node.type || '';
    const kind = (node.data as any)?.kind;
    const nodeDataId = node.data?.id || '';
    return type === 'browserAgentNode' ||
           kind === 'browser_agent' ||
           nodeDataId === 'browser_agent' ||
           nodeDataId === 'browser-agent' ||
           nodeDataId.startsWith('browser_agent-') ||
           nodeDataId.startsWith('browser-agent-');
  }

  // === Port Handling ===

  /**
   * Check if node has ports.
   */
  hasPorts(nodeType: string): boolean {
    return this.definitions[nodeType]?.hasPorts === true;
  }

  /**
   * Get port pattern for a node type.
   */
  getPortPattern(nodeType: string): string | undefined {
    return this.definitions[nodeType]?.portPattern;
  }

  /**
   * Check if a handle ID matches a branching port pattern.
   */
  isBranchHandle(handleId: string, nodeType: string): boolean {
    const pattern = this.getPortPattern(nodeType);
    if (!pattern) return false;

    const patterns = pattern.split('|');
    return patterns.some(p => {
      if (p.endsWith('_')) {
        // Pattern like 'choice_' matches 'choice_0', 'choice_1', etc.
        return handleId.includes(p) || handleId.includes(p.replace('_', '-'));
      }
      // Exact match patterns like 'if', 'else', 'default'
      return handleId.includes(`-${p}`) || handleId === p;
    });
  }

  // === Specific Node Type Checks ===

  isDecisionNode(node: Node<BuilderNodeData>): boolean {
    return node.type === 'decisionNode';
  }

  isSwitchNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    return node.type === 'switchNode' ||
           nodeDataId === 'switch' ||
           nodeDataId.startsWith('switch-');
  }

  isOptionNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    return node.type === 'optionNode' ||
           nodeDataId === 'option' ||
           nodeDataId.startsWith('option-');
  }

  isUserApprovalNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    return node.type === 'userApprovalNode' ||
           nodeDataId === 'user-approval' ||
           nodeDataId.startsWith('user-approval-');
  }

  /**
   * Returns true for whileGroupNode (the loop node type using back-edges).
   */
  isLoopNode(node: Node<BuilderNodeData>): boolean {
    return this.isWhileGroupNode(node);
  }

  isSplitNode(node: Node<BuilderNodeData>): boolean {
    return node.type === 'splitNode';
  }

  isForkNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    return node.type === 'forkNode' ||
           nodeDataId === 'fork' ||
           nodeDataId.startsWith('fork-');
  }

  isMergeNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    return node.type === 'mergeNode' ||
           (node.data as any)?.kind === 'merge' ||
           nodeDataId === 'merge' ||
           nodeDataId.startsWith('merge-');
  }

  isTransformNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'transform' ||
           nodeDataId === 'transform' ||
           nodeDataId.startsWith('transform-');
  }

  isWaitNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'wait' ||
           nodeDataId === 'wait' ||
           nodeDataId.startsWith('wait-');
  }

  isDownloadFileNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'download_file' ||
           nodeDataId === 'download_file' ||
           nodeDataId.startsWith('download-file-') ||
           nodeDataId.startsWith('download_file-');
  }

  isPublicLinkNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'public_link' ||
           nodeDataId === 'public_link' ||
           nodeDataId.startsWith('public-link-') ||
           nodeDataId.startsWith('public_link-');
  }

  isMediaNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'media' ||
           nodeDataId === 'media' ||
           nodeDataId.startsWith('media-');
  }

  isHttpRequestNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'http_request' ||
           nodeDataId === 'http-request' ||
           nodeDataId.startsWith('http-request-') ||
           nodeDataId.startsWith('http_request-');
  }

  isDataInputNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'data_input' ||
           nodeDataId === 'data_input' ||
           nodeDataId.startsWith('data_input-') ||
           nodeDataId.startsWith('data-input-');
  }

  isExitNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    return node.type === 'exitNode' ||
           nodeDataId === 'exit' ||
           nodeDataId.startsWith('exit-');
  }

  isResponseNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    return node.type === 'responseNode' ||
           nodeDataId === 'response' ||
           nodeDataId.startsWith('response-');
  }

  isAggregateNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    return node.type === 'aggregateNode' ||
           nodeDataId === 'aggregate' ||
           nodeDataId.startsWith('aggregate-');
  }

  isCrudNode(node: Node<BuilderNodeData>): boolean {
    if (node.type === 'crudNode') return true;
    const crudOperation = (node.data as any)?.dataSourceData?.crudOperation;
    if (!crudOperation) return false;
    // Only valid CRUD operations - not 'triggers' or other values
    const validCrudOperations = ['create-row', 'read-row', 'update-row', 'delete-row', 'create-column', 'find-row'];
    return validCrudOperations.includes(crudOperation);
  }

  /**
   * Check if node is a Find node (CRUD find-row operation).
   * Also matches legacy 'find-' prefix from older plan imports where
   * 'crud-find' produced node IDs starting with 'find-' instead of 'find-row-'.
   */
  isFindNode(node: Node<BuilderNodeData>): boolean {
    const crudOperation = (node.data as any)?.dataSourceData?.crudOperation;
    if (crudOperation === 'find-row') return true;
    const nodeDataId = node.data?.id || '';
    if (nodeDataId === 'find-row' || nodeDataId.startsWith('find-row-')) return true;
    // Legacy: plan imports used to generate IDs like 'find-<timestamp>' from 'crud-find' type
    const kind = (node.data as any)?.kind;
    if (kind === 'find' && nodeDataId.startsWith('find-')) return true;
    return false;
  }

  /**
   * Check if node is a read-row node (CRUD read-row / Get Rows operation).
   */
  isReadRowNode(node: Node<BuilderNodeData>): boolean {
    const crudOperation = (node.data as any)?.dataSourceData?.crudOperation;
    if (crudOperation === 'read-row') return true;
    const nodeDataId = node.data?.id || '';
    return nodeDataId === 'read-row' || nodeDataId.startsWith('read-row-');
  }

  /**
   * Check if node behaves like a split (parallel per item).
   * Only includes SplitNode (core). FindNode and ReadRow are now simple collection nodes.
   */
  isSplitLikeNode(node: Node<BuilderNodeData>): boolean {
    return this.isSplitNode(node) || this.isFindNode(node) || this.isReadRowNode(node);
  }

  isNoteNode(node: Node<BuilderNodeData>): boolean {
    return node.type === 'noteNode';
  }

  isInterfaceNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const interfaceData = (node.data as any)?.interfaceData;
    return node.type === 'interfaceNode' ||
           interfaceData?.interfaceId != null ||
           node.id.startsWith('interface-') ||
           nodeDataId.startsWith('interface-');
  }

  isClassifyNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    return node.type === 'classifyNode' ||
           nodeDataId === 'classify' ||
           nodeDataId.startsWith('classify-');
  }

  isGuardrailNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    return node.type === 'guardrailNode' ||
           nodeDataId === 'guardrail' ||
           nodeDataId.startsWith('guardrail-');
  }

  isWhileGroupNode(node: Node<BuilderNodeData>): boolean {
    return node.type === 'whileGroupNode';
  }

  isFilterNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'filter' ||
           nodeDataId === 'filter' ||
           nodeDataId.startsWith('filter-');
  }

  isSortNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'sort' ||
           nodeDataId === 'sort' ||
           nodeDataId.startsWith('sort-');
  }

  isLimitNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'limit' ||
           nodeDataId === 'limit' ||
           nodeDataId.startsWith('limit-');
  }

  isRemoveDuplicatesNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'remove_duplicates' ||
           nodeDataId === 'remove_duplicates' ||
           nodeDataId === 'remove-duplicates' ||
           nodeDataId.startsWith('remove_duplicates-') ||
           nodeDataId.startsWith('remove-duplicates-');
  }

  isSummarizeNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'summarize' ||
           nodeDataId === 'summarize' ||
           nodeDataId.startsWith('summarize-');
  }

  isDateTimeNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'date_time' ||
           nodeDataId === 'date_time' ||
           nodeDataId === 'date-time' ||
           nodeDataId.startsWith('date_time-') ||
           nodeDataId.startsWith('date-time-');
  }

  isCryptoJwtNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'crypto_jwt' ||
           nodeDataId === 'crypto_jwt' ||
           nodeDataId === 'crypto-jwt' ||
           nodeDataId.startsWith('crypto_jwt-') ||
           nodeDataId.startsWith('crypto-jwt-');
  }

  isXmlNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'xml' ||
           nodeDataId === 'xml' ||
           nodeDataId.startsWith('xml-');
  }

  isCompressionNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'compression' ||
           nodeDataId === 'compression' ||
           nodeDataId.startsWith('compression-');
  }

  isRssNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'rss' ||
           nodeDataId === 'rss' ||
           nodeDataId.startsWith('rss-');
  }

  isConvertToFileNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'convert_to_file' ||
           nodeDataId === 'convert_to_file' ||
           nodeDataId.startsWith('convert_to_file-') ||
           nodeDataId.startsWith('convert-to-file-');
  }

  isExtractFromFileNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'extract_from_file' ||
           nodeDataId === 'extract_from_file' ||
           nodeDataId.startsWith('extract_from_file-') ||
           nodeDataId.startsWith('extract-from-file-');
  }

  isCompareDatasetsNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'compare_datasets' ||
           nodeDataId === 'compare_datasets' ||
           nodeDataId.startsWith('compare_datasets-') ||
           nodeDataId.startsWith('compare-datasets-');
  }

  isSetNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'set' ||
           nodeDataId === 'set' ||
           nodeDataId.startsWith('set-');
  }

  isHtmlExtractNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'html_extract' ||
           nodeDataId === 'html_extract' ||
           nodeDataId.startsWith('html_extract-') ||
           nodeDataId.startsWith('html-extract-');
  }

  isTaskNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'task' ||
           nodeDataId === 'task' ||
           nodeDataId.startsWith('task-');
  }

  isSubWorkflowNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'sub_workflow' ||
           nodeDataId === 'sub_workflow' ||
           nodeDataId.startsWith('sub_workflow-') ||
           nodeDataId.startsWith('sub-workflow-');
  }

  isRespondToWebhookNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'respond_to_webhook' ||
           nodeDataId === 'respond_to_webhook' ||
           nodeDataId.startsWith('respond_to_webhook-') ||
           nodeDataId.startsWith('respond-to-webhook-');
  }

  isSendEmailNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'send_email' ||
           nodeDataId === 'send_email' ||
           nodeDataId.startsWith('send_email-') ||
           nodeDataId.startsWith('send-email-');
  }

  isEmailInboxNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'email_inbox' ||
           nodeDataId === 'email_inbox' ||
           nodeDataId.startsWith('email_inbox-') ||
           nodeDataId.startsWith('email-inbox-');
  }

  isCodeNode(node: Node<BuilderNodeData>): boolean {
    const nodeDataId = node.data?.id || '';
    const kind = (node.data as any)?.kind;
    return kind === 'code' ||
           nodeDataId === 'code' ||
           nodeDataId.startsWith('code-') ||
           nodeDataId.startsWith('script-') ||
           nodeDataId.startsWith('run-code-');
  }

  isStopOnErrorNode(node: { data?: { kind?: string } }): boolean {
    return (node?.data as any)?.kind === 'stop_on_error';
  }

  isSshNode(node: { data?: { kind?: string } }): boolean {
    return (node?.data as any)?.kind === 'ssh';
  }

  isSftpNode(node: { data?: { kind?: string } }): boolean {
    return (node?.data as any)?.kind === 'sftp';
  }

  isDatabaseNode(node: { data?: { kind?: string } }): boolean {
    return (node?.data as any)?.kind === 'database';
  }

  isWorkflowNode(node: Node<BuilderNodeData>): boolean {
    return node.type === 'workflowNode';
  }

  isFlowNode(node: Node<BuilderNodeData>): boolean {
    return node.type === 'flowNode';
  }

  // === Backend Key Building ===

  /**
   * Compute backend key for streaming matching.
   * Returns key like 'core:label', 'mcp:label', 'trigger:label'.
   */
  computeBackendKey(node: Node<BuilderNodeData>, normalizedLabel: string): string | null {
    const type = node.type || '';
    const kind = (node.data as any)?.kind;

    // Trigger
    if (this.isTrigger(node)) {
      return `trigger:${normalizedLabel}`;
    }

    // Agent nodes
    if (this.isAgentNode(node)) {
      return `agent:${normalizedLabel}`;
    }

    // Core nodes (control flow + utilities)
    if (this.isCoreNode(node)) {
      return `core:${normalizedLabel}`;
    }

    // CRUD/Table nodes
    if (this.isCrudNode(node)) {
      return `table:${normalizedLabel}`;
    }

    // Interface nodes
    if (this.isInterfaceNode(node)) {
      return `interface:${normalizedLabel}`;
    }

    // Default: MCP step
    if (type === 'flowNode' || !type) {
      return `mcp:${normalizedLabel}`;
    }

    // Note nodes (no backend key needed, but avoid null)
    if (this.isNoteNode(node)) {
      return `note:${normalizedLabel}`;
    }

    return null;
  }

  // === Edge Branch Detection ===

  /**
   * Extract branch type from edge handle for decision-like nodes.
   * Returns 'then', 'elsif', 'else', or null.
   */
  extractBranchType(handleId: string | null | undefined, sourceNode: Node<BuilderNodeData>): 'then' | 'elsif' | 'else' | null {
    if (!handleId) return null;
    if (!this.isDecisionLikeNode(sourceNode)) return null;

    const handle = handleId.toLowerCase();

    // Decision node patterns
    if (handle === 'then' || handle.includes('-if')) return 'then';
    if (handle.startsWith('elsif') || handle.includes('-elseif')) return 'elsif';
    if (handle === 'else' || handle.includes('-else')) return 'else';

    // Switch node patterns
    if (handle.includes('switch-case-0') || handle.includes('case-1')) return 'then';
    if (handle.includes('switch-case-') || handle.includes('case-')) return 'elsif';
    if (handle.includes('switch-default') || handle === 'default') return 'else';

    // Option node patterns
    if (handle.includes('choice_0') || handle.includes('choice-0')) return 'then';
    if (handle.includes('choice_') || handle.includes('choice-')) return 'elsif';

    return null;
  }
}

// ==================== Singleton Export ====================

export const nodeRegistry = new NodeRegistry();

// ==================== Convenience Exports ====================

// Re-export commonly used methods for easier imports
export const {
  isTrigger,
  isControlNode,
  isBranchingNode,
  isDecisionLikeNode,
  isCoreNode,
  isAgentNode,
  isAiAgentNode,
  isDecisionNode,
  isSwitchNode,
  isOptionNode,
  isUserApprovalNode,
  isLoopNode,
  isSplitNode,
  isForkNode,
  isMergeNode,
  isTransformNode,
  isWaitNode,
  isDownloadFileNode,
  isPublicLinkNode,
  isMediaNode,
  isHttpRequestNode,
  isDataInputNode,
  isExitNode,
  isResponseNode,
  isAggregateNode,
  isCrudNode,
  isFindNode,
  isSplitLikeNode,
  isNoteNode,
  isInterfaceNode,
  isClassifyNode,
  isGuardrailNode,
  isBrowserAgentNode,
  isWhileGroupNode,
  isFilterNode,
  isSortNode,
  isLimitNode,
  isRemoveDuplicatesNode,
  isSummarizeNode,
  isDateTimeNode,
  isCryptoJwtNode,
  isXmlNode,
  isCompressionNode,
  isRssNode,
  isConvertToFileNode,
  isExtractFromFileNode,
  isCompareDatasetsNode,
  isSetNode,
  isHtmlExtractNode,
  isTaskNode,
  isSubWorkflowNode,
  isRespondToWebhookNode,
  isSendEmailNode,
  isEmailInboxNode,
  isCodeNode,
  isStopOnErrorNode,
  isSshNode,
  isSftpNode,
  isDatabaseNode,
  isWorkflowNode,
  isFlowNode,
  requiresSingleEntry,
  isTerminal,
  hasPorts,
  getPrefix,
  getPrefixForKind,
  getPrefixesForNode,
  computeBackendKey,
  extractBranchType,
} = nodeRegistry;

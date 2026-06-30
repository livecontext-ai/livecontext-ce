/**
 * Core types for the Inspector Panel architecture.
 *
 * This file defines the contracts that all forms and outputs must follow.
 * The registry pattern allows adding new node types without modifying routing logic.
 */

import { ReactNode } from 'react';
import { Node } from 'reactflow';
import { BuilderNodeData, ConditionRow, SwitchCaseRow } from '../../../types';
import type { Connection } from '../useInspectorConnections';
import { nodeRegistry } from '../../../registry/nodeRegistry';

// =============================================================================
// FORM TYPES
// =============================================================================

/**
 * Common props injected into all form components via useFormCommonProps hook.
 * This eliminates prop drilling and ensures consistency.
 */
export interface InspectorFormProps {
  // Core
  node: Node<BuilderNodeData>;
  isRunMode: boolean;
  onUpdate: (data: Partial<BuilderNodeData>) => void;

  // Expression helpers (from useInspectorExpressions)
  getExpression: (field: string) => string;
  setExpression: (field: string, value: string) => void;
  getToolParamExpression: (paramName: string) => string;
  setToolParamExpression: (paramName: string, value: string) => void;

  // Connection helpers (bundled, no more 8 props drilling)
  connectionProps: ConnectionPropsBundle;

  // Validation
  errors: Record<string, string>;
  findUnknownVariables: (expressions: Record<string, string>) => string[];

  // Optional params
  showOptionalParams?: boolean;
  setShowOptionalParams?: (show: boolean) => void;

  // Webhook tokens map for multi-DAG support (triggerId -> token)
  webhookTokens?: Record<string, string>;

  // Decision/Switch state (for cores)
  currentConditions?: ConditionRow[];
  getConditionExpression?: (conditionId: string) => string;
  handleConditionExpressionChange?: (conditionId: string, value: string) => void;
  getConditionHandleId?: (condition: ConditionRow, index: number) => string;
  handleAddCondition?: (type: 'elseif' | 'else', afterIndex: number) => void;
  handleDeleteCondition?: (conditionId: string) => void;
  handleRenameCondition?: (conditionId: string, newLabel: string) => void;

  // Switch state
  currentCases?: SwitchCaseRow[];
  switchExpression?: string;
  getCaseHandleId?: (caseRow: SwitchCaseRow, index: number) => string;
  getCaseValue?: (caseId: string) => string;
  handleCaseValueChange?: (caseId: string, value: string) => void;
  handleSwitchExpressionChange?: (value: string) => void;
  handleAddCase?: (afterIndex: number) => void;
  handleDeleteCase?: (caseId: string) => void;
  handleRenameCase?: (caseId: string, newLabel: string) => void;

  // Tables trigger state
  columns?: any[];
  isLoadingColumns?: boolean;
  getColumnExpression?: (field: string) => string;
  handleColumnExpressionChange?: (field: string, value: string) => void;
  getColumnLabel?: (field: string) => string;
  handleColumnLabelChange?: (field: string, label: string) => void;
  handleDeleteColumn?: (field: string) => void;
  handleAddColumn?: () => void;

  // Graph data for AI Agent
  allNodes?: Node<BuilderNodeData>[];
  edges?: any[];
}

/**
 * Connection props bundle - replaces 8 individual props.
 */
export interface ConnectionPropsBundle {
  connections: Connection[];
  draggingFromHandle: string | null;
  hoveredTargetHandle: string | null;
  handleHandleClick: (handleId: string, nodeId: string) => void;
  handleHandleMouseDown: (e: React.MouseEvent, handleId: string, nodeId: string) => void;
  handleHandleMouseUp: (handleId: string, nodeId: string) => void;
  handleSetHandleRef: (handleId: string, element: HTMLElement | null) => void;
}

/**
 * Form definition for the registry.
 * Each node type registers its form component and metadata.
 */
export interface FormDefinition {
  /** The form component to render */
  component: React.ComponentType<InspectorFormProps>;

  /** Display name for the form (used in headers) */
  displayName?: string;

  /** Whether this form uses expression fields */
  hasExpressions?: boolean;

  /** Whether this form has optional/advanced parameters */
  hasOptionalParams?: boolean;

  /** Custom validation function */
  validate?: (node: Node<BuilderNodeData>) => Record<string, string>;
}

// =============================================================================
// OUTPUT TYPES
// =============================================================================

/**
 * Props for output components.
 */
export interface InspectorOutputProps {
  node: Node<BuilderNodeData>;
  data: any;
  isLoading: boolean;
  error?: string;
}

/**
 * Output definition for the registry.
 * Defines how to extract and render output data for each node type.
 */
export interface OutputDefinition {
  /**
   * Extract data from execution state.
   * Returns the data to display, or null if not available.
   */
  extractData: (node: Node<BuilderNodeData>, executionData: ExecutionData) => any;

  /**
   * Custom render function. If not provided, LazyStructureTree is used.
   */
  renderContent?: (data: any, node: Node<BuilderNodeData>) => ReactNode;

  /** Message to show when no data is available */
  emptyMessage?: string;

  /** Title for the output section */
  title?: string;
}

/**
 * Execution data available for output extraction.
 */
export interface ExecutionData {
  stepResults: Record<string, StepResult>;
  loopData?: Record<string, LoopData>;
  decisionData?: Record<string, DecisionData>;
}

export interface StepResult {
  status: 'pending' | 'running' | 'completed' | 'failed' | 'skipped';
  output?: any;
  error?: string;
  duration?: number;
}

export interface LoopData {
  currentIteration: number;
  totalIterations: number;
  processedItems: any[];
}

export interface DecisionData {
  selectedBranch: string;
  evaluations: Array<{
    condition: string;
    result: boolean;
  }>;
  skippedBranches: string[];
}

// =============================================================================
// NODE TYPE DETECTION
// =============================================================================

/**
 * All supported node types for the registry.
 * Categories: triggers, agents, cores, tools, tables, notes, interface
 */
export type InspectorNodeType =
  // Triggers
  | 'manual-trigger'
  | 'chat-trigger'
  | 'webhook-trigger'
  | 'schedule-trigger'
  | 'form-trigger'
  | 'tables-trigger'
  | 'workflows-trigger'
  | 'error-trigger'
  // Agents (AI)
  | 'agent'
  | 'summarize'
  | 'guardrail'
  | 'classify'
  | 'browser_agent'
  // Cores (control flow)
  | 'decision'
  | 'switch'
  | 'loop'
  | 'while-group'
  | 'split'
  | 'aggregate'
  | 'transform'
  | 'merge'
  | 'wait'
  | 'fork'
  | 'download_file'
  | 'http_request'
  | 'data_input'
  | 'response'
  | 'exit'
  | 'filter'
  | 'sort'
  | 'limit'
  | 'remove_duplicates'
  | 'summarize_data'
  | 'date_time'
  | 'crypto_jwt'
  | 'xml'
  | 'compression'
  | 'rss'
  | 'convert_to_file'
  | 'extract_from_file'
  | 'compare_datasets'
  | 'set'
  | 'html_extract'
  | 'task'
  | 'sub_workflow'
  | 'respond_to_webhook'
  | 'send_email'
  | 'email_inbox'
  | 'code'
  | 'stop_on_error'
  | 'ssh'
  | 'sftp'
  | 'database'
  // Tools
  | 'tool'
  // Tables (CRUD)
  | 'create-row'
  | 'read-row'
  | 'update-row'
  | 'delete-row'
  | 'find-row'
  | 'list-rows'
  // Notes (separate category)
  | 'note'
  // Interface
  | 'interface'
  // Unknown
  | 'unknown';

/**
 * Detect the inspector node type from a node.
 */
export function detectNodeType(node: Node<BuilderNodeData> | null): InspectorNodeType {
  if (!node?.data) return 'unknown';

  const { id, kind } = node.data;
  const nodeType = node.type;

  // Triggers (by id prefix)
  if (id === 'manual-trigger' || id?.startsWith('manual-trigger-')) return 'manual-trigger';
  if (id === 'chat-trigger' || id?.startsWith('chat-trigger-')) return 'chat-trigger';
  if (id?.startsWith('webhook-trigger')) return 'webhook-trigger';
  if (id?.startsWith('schedule-trigger')) return 'schedule-trigger';
  if (id?.startsWith('form-trigger')) return 'form-trigger';
  if (id?.startsWith('tables-trigger')) return 'tables-trigger';
  if (id?.startsWith('workflows-trigger')) return 'workflows-trigger';
  if (id?.startsWith('error-trigger')) return 'error-trigger';

  // AI nodes - check specific types BEFORE generic isAgentNode (which includes all AI types)
  if (nodeRegistry.isGuardrailNode(node)) return 'guardrail';
  if (nodeRegistry.isClassifyNode(node)) return 'classify';
  if (id === 'ai-summarize' || id?.startsWith('ai-summarize-')) return 'summarize';
  if (nodeRegistry.isAgentNode(node)) return 'agent';

  // WhileGroup - check before other cores
  if (nodeRegistry.isWhileGroupNode(node)) return 'while-group';

  // Cores - use nodeRegistry for type detection
  if (nodeRegistry.isDecisionNode(node)) return 'decision';
  if (nodeRegistry.isSwitchNode(node)) return 'switch';
  if (nodeRegistry.isLoopNode(node)) return 'loop';
  if (nodeRegistry.isSplitNode(node)) return 'split';
  if (nodeRegistry.isAggregateNode(node)) return 'aggregate';
  if (nodeRegistry.isTransformNode(node)) return 'transform';
  if (nodeRegistry.isMergeNode(node)) return 'merge';
  if (nodeRegistry.isWaitNode(node)) return 'wait';
  if (nodeRegistry.isForkNode(node)) return 'fork';
  if (nodeRegistry.isDownloadFileNode(node)) return 'download_file';
  if (nodeRegistry.isHttpRequestNode(node)) return 'http_request';
  if (nodeRegistry.isDataInputNode(node)) return 'data_input';
  if (nodeRegistry.isResponseNode(node)) return 'response';
  if (nodeRegistry.isExitNode(node)) return 'exit';
  if (nodeRegistry.isFilterNode(node)) return 'filter';
  if (nodeRegistry.isSortNode(node)) return 'sort';
  if (nodeRegistry.isLimitNode(node)) return 'limit';
  if (nodeRegistry.isRemoveDuplicatesNode(node)) return 'remove_duplicates';
  if (nodeRegistry.isSummarizeNode(node)) return 'summarize_data';
  if (nodeRegistry.isDateTimeNode(node)) return 'date_time';
  if (nodeRegistry.isCryptoJwtNode(node)) return 'crypto_jwt';
  if (nodeRegistry.isXmlNode(node)) return 'xml';
  if (nodeRegistry.isCompressionNode(node)) return 'compression';
  if (nodeRegistry.isRssNode(node)) return 'rss';
  if (nodeRegistry.isConvertToFileNode(node)) return 'convert_to_file';
  if (nodeRegistry.isExtractFromFileNode(node)) return 'extract_from_file';
  if (nodeRegistry.isCompareDatasetsNode(node)) return 'compare_datasets';
  if (nodeRegistry.isSetNode(node)) return 'set';
  if (nodeRegistry.isHtmlExtractNode(node)) return 'html_extract';
  if (nodeRegistry.isTaskNode(node)) return 'task';
  if (nodeRegistry.isSubWorkflowNode(node)) return 'sub_workflow';
  if (nodeRegistry.isRespondToWebhookNode(node)) return 'respond_to_webhook';
  if (nodeRegistry.isSendEmailNode(node)) return 'send_email';
  if (nodeRegistry.isEmailInboxNode(node)) return 'email_inbox';
  if (nodeRegistry.isCodeNode(node)) return 'code';
  if (nodeRegistry.isStopOnErrorNode(node)) return 'stop_on_error';
  if (nodeRegistry.isSshNode(node)) return 'ssh';
  if (nodeRegistry.isSftpNode(node)) return 'sftp';
  if (nodeRegistry.isDatabaseNode(node)) return 'database';

  // Notes - use nodeRegistry for type detection
  if (nodeRegistry.isNoteNode(node)) return 'note';

  // Interface
  if (nodeRegistry.isInterfaceNode(node)) return 'interface';

  // Tables (CRUD) - check by id prefix or crudOperation
  const crudOperation = (node.data as any)?.dataSourceData?.crudOperation;
  if (id?.startsWith('create-row') || crudOperation === 'create-row') return 'create-row';
  if (id?.startsWith('read-row') || crudOperation === 'read-row') return 'read-row';
  if (id?.startsWith('update-row') || crudOperation === 'update-row') return 'update-row';
  if (id?.startsWith('delete-row') || crudOperation === 'delete-row') return 'delete-row';
  if (id?.startsWith('find-row') || id?.startsWith('find-') || crudOperation === 'find-row') return 'find-row';
  if (id?.startsWith('list-rows') || crudOperation === 'list-rows') return 'list-rows';

  // Tools (MCP/API)
  if (kind === 'mcp' || kind === 'tool' || id?.startsWith('mcp-') || id?.startsWith('api-')) return 'tool';

  return 'unknown';
}

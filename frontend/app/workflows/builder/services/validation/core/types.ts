/**
 * Core types for the workflow validation system
 * Aligned with backend validation rules from orchestrator-service
 */

import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../../types';
import type { Credential } from '@/lib/api/orchestrator';
import type { FeatureCapabilities } from '@/lib/api/orchestrator/workflow.service';

// ============================================
// Node Type Utilities
// ============================================

export type WorkflowNodeType = 'trigger' | 'mcp' | 'core' | 'agent' | 'table' | 'interface' | 'note';
export type ElementType = WorkflowNodeType | 'edge';
export type Severity = 'error' | 'warning';
export type ValidationSource = 'frontend' | 'backend';

// ============================================
// Validation Issue
// ============================================

export interface ValidationIssue {
  /** Unique key identifying the element (e.g., "mcp:my_label", "trigger:my_trigger") */
  elementKey: string;
  /** Type of element */
  elementType: ElementType;
  /** Error/warning message */
  message: string;
  /** Severity level */
  severity: Severity;
  /** Source of the validation */
  source: ValidationSource;
  /** Rule that generated this issue */
  ruleName: string;
  /** Additional context data */
  context?: Record<string, unknown>;
}

// ============================================
// Validation Rule Result
// ============================================

export interface ValidationRuleResult {
  /** Rule name for identification */
  ruleName: string;
  /** List of validation issues found */
  issues: ValidationIssue[];
  /** Whether this rule found any critical errors */
  hasErrors: boolean;
  /** Whether this rule found any warnings */
  hasWarnings: boolean;
  /** Execution time in ms (for performance monitoring) */
  executionTimeMs?: number;
}

// ============================================
// Workflow Validation Result
// ============================================

export interface WorkflowValidationResult {
  /** Whether the workflow is valid (no errors) */
  isValid: boolean;
  /** All validation issues grouped by element key */
  issuesByElement: Record<string, ValidationIssue[]>;
  /** Global validation errors not tied to a specific element */
  globalIssues: ValidationIssue[];
  /** Total count of errors */
  errorCount: number;
  /** Total count of warnings */
  warningCount: number;
  /** Details by rule */
  ruleResults: ValidationRuleResult[];
  /** Workflow complexity score (aligned with backend) */
  complexityScore: number;
  /** Validation timestamp */
  timestamp: number;
}

/**
 * Legacy-compatible validation result
 * Used for backward compatibility with existing code
 */
export interface LegacyWorkflowValidationResult {
  isValid: boolean;
  issuesByElement: Record<string, string[]>;
  globalErrors: string[];
  errorCount: number;
  warningCount: number;
  ruleResults: ValidationRuleResult[];
}

// ============================================
// Validation Context
// ============================================

export interface BackendValidationError {
  type?: string;
  message: string;
  path?: string;
  context?: Record<string, unknown>;
}

export interface ValidationContext {
  /** All nodes in the workflow */
  nodes: Node<BuilderNodeData>[];
  /** All edges in the workflow */
  edges: Edge[];
  /** Backend validation errors (if available) */
  backendErrors?: BackendValidationError[];
  /** Cached computed data (for performance) */
  cache: ValidationCache;
  /**
   * Credentials configured by the current user. Let rules that check "is this
   * node connected?" see the same data the inspector uses so a service node
   * isn't flagged missing-credential just because `toolData.selectedCredentialId`
   * hasn't been auto-persisted yet (only happens when the user opens the node).
   */
  userCredentials?: Credential[];
  /**
   * Availability of the deployment's optional components (screenshot/PDF
   * renderer, browser agent). Absent = unknown (loading / fetch error): rules
   * must then emit NO availability warning rather than a possibly-false one.
   */
  featureCapabilities?: FeatureCapabilities;
}

// ============================================
// Validation Cache (Performance Optimization)
// ============================================

export interface ValidationCache {
  /** Nodes grouped by type */
  nodesByType: {
    triggers: Node<BuilderNodeData>[];
    mcps: Node<BuilderNodeData>[];
    agents: Node<BuilderNodeData>[];
    cores: Node<BuilderNodeData>[];
    interfaceNodes: Node<BuilderNodeData>[];
    tableNodes: Node<BuilderNodeData>[];
  };
  /** Node lookup by ID */
  nodeById: Map<string, Node<BuilderNodeData>>;
  /** Normalized labels map: normalizedLabel -> nodes */
  labelMap: Map<string, Array<{ node: Node<BuilderNodeData>; nodeType: WorkflowNodeType }>>;
  /** Edge lookup maps */
  edgesBySource: Map<string, Edge[]>;
  edgesByTarget: Map<string, Edge[]>;
  /** Reachable nodes from triggers (for connectivity) */
  reachableNodes?: Set<string>;
  /** Detected cycles (nodeId -> cycle path) */
  cycles?: Map<string, string[]>;
}

// ============================================
// Validation Rule Interface
// ============================================

/**
 * Rule names aligned with backend orchestrator-service
 */
export type ValidationRuleName =
  | 'BackendError'
  | 'GraphStructure'
  | 'LabelValidation'
  | 'NodeConfiguration'
  | 'MockConfiguration'
  | 'CrudValidation'
  | 'CredentialValidation'
  | 'InterfaceValidation'
  | 'CycleDetection'
  | 'BackEdge'
  | 'EdgeValidation'
  | 'InputReference'
  | 'GraphConnectivity';

export interface ValidationRule {
  /** Name of the validation rule (must be unique, aligned with backend) */
  readonly ruleName: ValidationRuleName;

  /** Whether this rule is critical (workflow cannot start if it has errors) */
  readonly isCritical: boolean;

  /** Priority of the rule (lower number = higher priority) */
  readonly priority: number;

  /**
   * Validates the workflow and returns issues found
   * @param context Validation context with nodes, edges, cache, and backend errors
   * @returns Validation rule result with issues found
   */
  validate(context: ValidationContext): ValidationRuleResult;

  /**
   * Optional: Check if this rule should run based on context
   * Useful for skipping expensive rules when not needed
   */
  shouldRun?(context: ValidationContext): boolean;

  /**
   * Optional: Get affected element keys for incremental validation
   * Returns element keys that this rule validates
   */
  getAffectedElements?(context: ValidationContext): string[];
}

// ============================================
// Incremental Validation Types
// ============================================

export interface NodeChangeEvent {
  type: 'add' | 'update' | 'remove';
  nodeId: string;
  node?: Node<BuilderNodeData>;
  previousNode?: Node<BuilderNodeData>;
}

export interface EdgeChangeEvent {
  type: 'add' | 'update' | 'remove';
  edgeId: string;
  edge?: Edge;
  previousEdge?: Edge;
}

export interface ValidationChangeEvent {
  nodes: NodeChangeEvent[];
  edges: EdgeChangeEvent[];
}

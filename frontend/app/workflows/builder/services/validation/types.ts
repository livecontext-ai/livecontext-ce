/**
 * Types and interfaces for workflow validation system
 * Provides a unified structure for validation rules and results
 */

import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../types';

/**
 * Represents a validation issue for a specific element (node, edge, etc.)
 */
export interface ValidationIssue {
  /** Unique key identifying the element (e.g., "mcp:my_label", "trigger:my_trigger") */
  elementKey: string;
  /** Type of element: mcp, trigger, or core */
  elementType: 'trigger' | 'mcp' | 'core' | 'agent' | 'table' | 'interface' | 'note' | 'edge';
  /** Error message */
  message: string;
  /** Severity level */
  severity: 'error' | 'warning';
  /** Source of the validation (frontend or backend) */
  source: 'frontend' | 'backend';
  /** Additional context data */
  context?: Record<string, unknown>;
}

/**
 * Result of a validation rule execution
 */
export interface ValidationRuleResult {
  /** Rule name for identification */
  ruleName: string;
  /** List of validation issues found */
  issues: ValidationIssue[];
  /** Whether this rule found any critical errors */
  hasErrors: boolean;
  /** Whether this rule found any warnings */
  hasWarnings: boolean;
}

/**
 * Complete validation result for a workflow
 */
export interface WorkflowValidationResult {
  /** Whether the workflow is valid (no errors) */
  isValid: boolean;
  /** All validation issues grouped by element key */
  issuesByElement: Record<string, string[]>;
  /** Global validation errors not tied to a specific element */
  globalErrors: string[];
  /** Total count of errors */
  errorCount: number;
  /** Total count of warnings */
  warningCount: number;
  /** Details by rule */
  ruleResults: ValidationRuleResult[];
}

/**
 * Context passed to validation rules
 */
export interface ValidationContext {
  /** All nodes in the workflow */
  nodes: Node<BuilderNodeData>[];
  /** All edges in the workflow */
  edges: Edge[];
  /** Backend validation errors (if available) */
  backendErrors?: Array<{
    elementKey?: string;
    message: string;
    context?: Record<string, unknown>;
  }>;
}

/**
 * Interface that all validation rules must implement
 */
export interface ValidationRule {
  /**
   * Name of the validation rule (must be unique)
   */
  readonly ruleName: string;

  /**
   * Validates the workflow and returns issues found
   * @param context Validation context containing nodes, edges, and backend errors
   * @returns Validation rule result with issues found
   */
  validate(context: ValidationContext): ValidationRuleResult;

  /**
   * Whether this rule is critical (workflow cannot start if it has errors)
   * @default true
   */
  readonly isCritical?: boolean;

  /**
   * Priority of the rule (lower number = higher priority)
   * Rules with higher priority are executed first
   * @default 100
   */
  readonly priority?: number;
}


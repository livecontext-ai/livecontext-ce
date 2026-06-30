/**
 * Central export point for the validation system
 *
 * This file provides a single entry point for all validation-related functionality.
 * Import from here to use the validation system.
 *
 * Migration Notes:
 * - WorkflowValidationService is deprecated, use WorkflowValidator instead
 * - Old rules in ./rules are deprecated, use ./rules-v2 instead
 * - New system includes caching and better performance
 */

// New validation system (recommended)
export { WorkflowValidator } from './WorkflowValidator';
export {
  getValidationRules,
  getValidationRule,
  getValidationRuleNames,
} from './rules-v2';

// Export core types
export type {
  ValidationRule,
  ValidationRuleResult,
  ValidationIssue,
  WorkflowValidationResult,
  LegacyWorkflowValidationResult,
  ValidationContext,
  ValidationCache,
  BackendValidationError,
  ElementType,
  Severity,
  ValidationSource,
} from './core/types';

// Export utilities for advanced usage
export { buildValidationCache, calculateComplexityScore } from './core/ValidationCache';
export {
  getNodeType,
  getElementKey,
  isTriggerNode,
  isControlNode,
  isInterfaceNode,
  isCrudNode,
  isStepNode,
  isNoteNode,
  categorizeNodes,
} from './core/nodeUtils';

// Re-export individual rules for testing/extension
export {
  BaseValidationRule,
  BackendErrorRule,
  GraphStructureRule,
  LabelValidationRule,
  NodeConfigurationRule,
  CrudValidationRule,
  CredentialValidationRule,
  InterfaceValidationRule,
  CycleDetectionRule,
  BackEdgeValidationRule,
  EdgeValidationRule,
  InputReferenceRule,
  GraphConnectivityRule,
} from './rules-v2';

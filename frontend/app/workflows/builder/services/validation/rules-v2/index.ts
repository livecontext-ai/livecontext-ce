/**
 * Centralized validation rules export
 *
 * Rules are organized by validation concern:
 *
 * | Rule                    | Priority | Validations                                          |
 * |-------------------------|----------|------------------------------------------------------|
 * | BackendErrorRule        | 1        | Backend validation errors pass-through                |
 * | GraphStructureRule      | 5        | #1 trigger exists, #2 DAG independence, #5 self-loop |
 * | LabelValidationRule     | 7        | #6 missing label, #7 duplicate, #8 prefix            |
 * | NodeConfigurationRule   | 8        | #9 params, #10 tool ID, #11-13 decision/loop/switch  |
 * | CrudValidationRule      | 9        | #14-15 datasource validation                         |
 * | CredentialValidationRule| 10       | #16 missing credential                               |
 * | InterfaceValidationRule | 11       | #20 missing ID/HTML, #21 unmapped vars               |
 * | CycleDetectionRule      | 12       | #4 cycle detection                                   |
 * | BackEdgeValidationRule  | 13       | Back-edge condition/target validation                 |
 * | EdgeValidationRule      | 14       | #17 edge refs, #18 trigger incoming, #19 merge       |
 * | InputReferenceRule      | 15       | #22 template reference validation                    |
 * | GraphConnectivityRule   | 20       | #3 unreachable nodes                                 |
 */

import type { ValidationRule } from '../core/types';

// Import all rules
import { BackendErrorRule } from './BackendErrorRule';
import { GraphStructureRule } from './GraphStructureRule';
import { LabelValidationRule } from './LabelValidationRule';
import { NodeConfigurationRule } from './NodeConfigurationRule';
import { MockConfigurationRule } from './MockConfigurationRule';
import { CrudValidationRule } from './CrudValidationRule';
import { CredentialValidationRule } from './CredentialValidationRule';
import { InterfaceValidationRule } from './InterfaceValidationRule';
import { CycleDetectionRule } from './CycleDetectionRule';
import { BackEdgeValidationRule } from './BackEdgeValidationRule';
import { EdgeValidationRule } from './EdgeValidationRule';
import { InputReferenceRule } from './InputReferenceRule';
import { GraphConnectivityRule } from './GraphConnectivityRule';

/**
 * All validation rules instances
 * Pre-instantiated for performance (rules are stateless)
 */
const RULE_INSTANCES: ValidationRule[] = [
  new BackendErrorRule(),
  new GraphStructureRule(),
  new LabelValidationRule(),
  new NodeConfigurationRule(),
  new MockConfigurationRule(),
  new CrudValidationRule(),
  new CredentialValidationRule(),
  new InterfaceValidationRule(),
  new CycleDetectionRule(),
  new BackEdgeValidationRule(),
  new EdgeValidationRule(),
  new InputReferenceRule(),
  new GraphConnectivityRule(),
];

/**
 * Get all validation rules sorted by priority
 */
export function getValidationRules(): ValidationRule[] {
  return [...RULE_INSTANCES].sort((a, b) => a.priority - b.priority);
}

/**
 * Get a specific rule by name
 */
export function getValidationRule(ruleName: string): ValidationRule | undefined {
  return RULE_INSTANCES.find((rule) => rule.ruleName === ruleName);
}

/**
 * Get rule names for debugging
 */
export function getValidationRuleNames(): string[] {
  return RULE_INSTANCES.map((rule) => rule.ruleName);
}

// Re-export rule classes for direct use
export { BackendErrorRule } from './BackendErrorRule';
export { GraphStructureRule } from './GraphStructureRule';
export { LabelValidationRule } from './LabelValidationRule';
export { NodeConfigurationRule } from './NodeConfigurationRule';
export { MockConfigurationRule } from './MockConfigurationRule';
export { CrudValidationRule } from './CrudValidationRule';
export { CredentialValidationRule } from './CredentialValidationRule';
export { InterfaceValidationRule } from './InterfaceValidationRule';
export { CycleDetectionRule } from './CycleDetectionRule';
export { BackEdgeValidationRule } from './BackEdgeValidationRule';
export { EdgeValidationRule } from './EdgeValidationRule';
export { InputReferenceRule } from './InputReferenceRule';
export { GraphConnectivityRule } from './GraphConnectivityRule';
export { BaseValidationRule } from './BaseValidationRule';

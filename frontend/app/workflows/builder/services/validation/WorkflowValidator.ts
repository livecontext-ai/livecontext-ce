/**
 * WorkflowValidator - Centralized workflow validation service
 *
 * This service orchestrates all validation rules and provides:
 * - Full validation with caching
 * - Incremental validation for performance
 * - Consistent result formatting
 *
 * Architecture:
 * - Validation rules are modular and follow the ValidationRule interface
 * - Rules are executed in priority order
 * - Results are aggregated and formatted consistently
 * - Cache is built once per validation and shared across rules
 */

import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import type { Credential } from '@/lib/api/orchestrator';
import type {
  ValidationContext,
  ValidationRule,
  ValidationRuleResult,
  WorkflowValidationResult,
  LegacyWorkflowValidationResult,
  ValidationIssue,
  BackendValidationError,
} from './core/types';
import { buildValidationCache, calculateComplexityScore } from './core/ValidationCache';
import { getValidationRules } from './rules-v2';

/**
 * Centralized workflow validation service
 */
export class WorkflowValidator {
  private static rules: ValidationRule[] | null = null;
  private static lastValidationResult: WorkflowValidationResult | null = null;
  private static lastValidationKey: string | null = null;

  /**
   * Get all validation rules (lazy initialization, sorted by priority)
   */
  private static getRules(): ValidationRule[] {
    if (!this.rules) {
      this.rules = getValidationRules();
    }
    return this.rules;
  }

  /**
   * Generate a cache key for validation results
   * Includes node labels, parameters, conditions, and all data that affects validation
   * This ensures validation re-runs when any relevant node data changes
   */
  private static generateCacheKey(
    nodes: Node<BuilderNodeData>[],
    edges: Edge[],
    backendErrors?: BackendValidationError[],
    userCredentials?: Credential[]
  ): string {
    // Include all data that affects validation in the cache key
    const nodeSignatures = nodes
      .map((n) => {
        const data = n.data;
        const label = data?.label || '';
        const conditions = data?.decisionConditions?.length || 0;
        const loopCondition = data?.loopCondition || '';
        // Include paramExpressions to detect when parameters change
        const paramExprs = JSON.stringify(data?.paramExpressions || {});
        // Include toolData presence
        const toolId = data?.toolData?.toolId || data?.toolData?.toolSlug || '';
        // Include credential selection (prevents stale "credential not connected" warnings).
        // Concatenate all credential fields (not || chain) to avoid collisions if a node
        // ever has multiple credential-like fields.
        const td = data?.toolData as any;
        const credentialId = [
          td?.selectedCredentialId,
          td?.credentialSource,
          td?.platformCredentialId,
          (data as any)?.smtpCredentialId,
          (data as any)?.sshCredentialId,
          (data as any)?.sftpCredentialId,
          (data as any)?.dbCredentialId,
        ].filter(Boolean).join(',') || '';
        // Include dataSource column expressions
        const dsExprs = JSON.stringify((data as any)?.dataSourceData?.columnExpressions || {});
        // Include interface template
        const interfaceTemplate = (data as any)?.interfaceData?.editorExpression || '';
        // Include validation issues (to clear when backend errors are resolved)
        const validationIssues = JSON.stringify((data as any)?.validationIssues || []);
        // Include decision condition expressions
        const decisionExprs = JSON.stringify(
          (data?.decisionConditions || []).map((c: any) => c.expression || '')
        );

        return `${n.id}|${label}|${conditions}|${loopCondition}|${paramExprs}|${toolId}|${credentialId}|${dsExprs}|${interfaceTemplate}|${validationIssues}|${decisionExprs}`;
      })
      .sort()
      .join('||');

    // Include full edge info (source, target, handles)
    const edgeSignatures = edges
      .map((e) => `${e.source}-${e.sourceHandle || ''}-${e.target}-${e.targetHandle || ''}`)
      .sort()
      .join(',');

    const errorSignature = JSON.stringify(backendErrors || []);

    // Include user credential identity so CredentialValidationRule re-runs when
    // the user adds/removes a credential (otherwise the warning would stick
    // until the next node edit).
    const credentialSignature = (userCredentials || [])
      .map((c) => `${c.id}:${c.integration || ''}`)
      .sort()
      .join(',');

    // Create a hash of the combined signatures
    const combined = `${nodeSignatures}###${edgeSignatures}###${errorSignature}###${credentialSignature}`;
    let hash = 0;
    for (let i = 0; i < combined.length; i++) {
      const char = combined.charCodeAt(i);
      hash = ((hash << 5) - hash) + char;
      hash = hash & hash;
    }

    return `${nodes.length}-${edges.length}-${hash}`;
  }

  /**
   * Validates a workflow using all registered validation rules
   *
   * @param nodes All nodes in the workflow
   * @param edges All edges in the workflow
   * @param backendErrors Optional backend validation errors to process
   * @param forceRevalidate Skip cache and force full revalidation
   * @returns Complete validation result with all issues
   */
  static validate(
    nodes: Node<BuilderNodeData>[],
    edges: Edge[],
    backendErrors?: BackendValidationError[],
    forceRevalidate = false,
    userCredentials?: Credential[]
  ): WorkflowValidationResult {
    const startTime = performance.now();

    // Check cache (unless forced)
    const cacheKey = this.generateCacheKey(nodes, edges, backendErrors, userCredentials);
    if (!forceRevalidate && this.lastValidationKey === cacheKey && this.lastValidationResult) {
      return this.lastValidationResult;
    }

    // Build validation cache
    const cache = buildValidationCache(nodes, edges);

    // Build context
    const context: ValidationContext = {
      nodes,
      edges,
      backendErrors,
      cache,
      userCredentials,
    };

    // Execute all validation rules
    const rules = this.getRules();
    const ruleResults: ValidationRuleResult[] = [];
    const allIssues: ValidationIssue[] = [];

    for (const rule of rules) {
      try {
        // Check if rule should run
        if (rule.shouldRun && !rule.shouldRun(context)) {
          continue;
        }

        const ruleStartTime = performance.now();
        const result = rule.validate(context);
        result.executionTimeMs = performance.now() - ruleStartTime;

        ruleResults.push(result);
        allIssues.push(...result.issues);
      } catch (error) {
        console.error(`[WorkflowValidator] Error executing rule ${rule.ruleName}:`, error);
        // Continue with other rules even if one fails
      }
    }

    // Aggregate issues by element key (deduplicated)
    const issuesByElement: Record<string, ValidationIssue[]> = {};
    const globalIssues: ValidationIssue[] = [];
    const seenIssueKeys = new Set<string>();

    for (const issue of allIssues) {
      // Create unique key for deduplication
      const issueKey = `${issue.elementKey}:${issue.message}`;
      if (seenIssueKeys.has(issueKey)) {
        continue;
      }
      seenIssueKeys.add(issueKey);

      if (issue.elementKey === 'global' || !issue.elementKey) {
        globalIssues.push(issue);
      } else {
        if (!issuesByElement[issue.elementKey]) {
          issuesByElement[issue.elementKey] = [];
        }
        issuesByElement[issue.elementKey].push(issue);
      }
    }

    // Count errors and warnings
    const errorCount = allIssues.filter((issue) => issue.severity === 'error').length;
    const warningCount = allIssues.filter((issue) => issue.severity === 'warning').length;

    // Calculate complexity score
    const complexityScore = calculateComplexityScore(cache, edges);

    // Build result
    const result: WorkflowValidationResult = {
      isValid: errorCount === 0,
      issuesByElement,
      globalIssues,
      errorCount,
      warningCount,
      ruleResults,
      complexityScore,
      timestamp: Date.now(),
    };

    // Cache result
    this.lastValidationKey = cacheKey;
    this.lastValidationResult = result;

    const totalTime = performance.now() - startTime;
    if (totalTime > 100) {
      console.warn(`[WorkflowValidator] Validation took ${totalTime.toFixed(2)}ms`);
    }

    return result;
  }

  /**
   * Quick validation check (returns only isValid)
   */
  static isValid(
    nodes: Node<BuilderNodeData>[],
    edges: Edge[],
    backendErrors?: BackendValidationError[]
  ): boolean {
    return this.validate(nodes, edges, backendErrors).isValid;
  }

  /**
   * Get validation issues for a specific element
   *
   * @param elementKey Element key (e.g., "mcp:my_label", "trigger:my_trigger")
   * @param nodes All nodes in the workflow
   * @param edges All edges in the workflow
   * @param backendErrors Optional backend validation errors
   * @returns Array of issues for the element
   */
  static getElementIssues(
    elementKey: string,
    nodes: Node<BuilderNodeData>[],
    edges: Edge[],
    backendErrors?: BackendValidationError[]
  ): ValidationIssue[] {
    const result = this.validate(nodes, edges, backendErrors);
    return result.issuesByElement[elementKey] || [];
  }

  /**
   * Get error messages for a specific element (string array for backward compatibility)
   */
  static getElementErrors(
    elementKey: string,
    nodes: Node<BuilderNodeData>[],
    edges: Edge[],
    backendErrors?: BackendValidationError[]
  ): string[] {
    const issues = this.getElementIssues(elementKey, nodes, edges, backendErrors);
    return issues.filter((i) => i.severity === 'error').map((i) => i.message);
  }

  /**
   * Clear the validation cache
   * Call this when you know the workflow has changed significantly
   */
  static clearCache(): void {
    this.lastValidationKey = null;
    this.lastValidationResult = null;
  }

  /**
   * Get all validation rules (for debugging/inspection)
   */
  static getValidationRules(): ValidationRule[] {
    return [...this.getRules()];
  }

  /**
   * Get rule names (for debugging/inspection)
   */
  static getRuleNames(): string[] {
    return this.getRules().map((rule) => rule.ruleName);
  }

  /**
   * Convert validation result to legacy format
   * For backward compatibility with existing code that expects string[] issues
   */
  static toLegacyResult(result: WorkflowValidationResult): LegacyWorkflowValidationResult {
    const issuesByElement: Record<string, string[]> = {};

    for (const [key, issues] of Object.entries(result.issuesByElement)) {
      issuesByElement[key] = issues.map((issue) => issue.message);
    }

    const globalErrors = result.globalIssues.map((issue) => issue.message);

    return {
      isValid: result.isValid,
      issuesByElement,
      globalErrors,
      errorCount: result.errorCount,
      warningCount: result.warningCount,
      ruleResults: result.ruleResults,
    };
  }

  /**
   * Validates a workflow and returns legacy-compatible result
   * @deprecated Use validate() for new code
   */
  static validateLegacy(
    nodes: Node<BuilderNodeData>[],
    edges: Edge[],
    backendErrors?: BackendValidationError[],
    forceRevalidate = false
  ): LegacyWorkflowValidationResult {
    const result = this.validate(nodes, edges, backendErrors, forceRevalidate);
    return this.toLegacyResult(result);
  }
}

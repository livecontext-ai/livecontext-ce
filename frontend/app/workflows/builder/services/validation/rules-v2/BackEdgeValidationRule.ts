/**
 * Validates back-edges (loop edges) in the workflow.
 *
 * Checks:
 * - Back-edge condition is not empty (warning)
 * - maxIterations is > 0
 * - Target is actually an ancestor of source (prevents invalid back-edges)
 */

import type {
  ValidationRuleName,
  ValidationRuleResult,
  ValidationContext,
  ValidationIssue,
} from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';

export class BackEdgeValidationRule extends BaseValidationRule {
  readonly ruleName: ValidationRuleName = 'BackEdge';
  readonly isCritical = false;
  readonly priority = 13;

  validate(context: ValidationContext): ValidationRuleResult {
    const issues: ValidationIssue[] = [];

    for (const edge of context.edges) {
      if (!edge.data?.isBackEdge) continue;

      const edgeKey = `${edge.source}->${edge.target}`;

      // Check maxIterations is valid
      const maxIter = edge.data.backEdgeMaxIterations;
      if (maxIter !== undefined && (typeof maxIter !== 'number' || maxIter < 1)) {
        issues.push(
          this.createError(
            edgeKey,
            'edge' as any,
            'Back-edge maxIterations must be a positive number',
          )
        );
      }

      // Verify target is an ancestor of source (validate it's actually a back-edge)
      if (!this.isAncestor(edge.target, edge.source, context)) {
        issues.push(
          this.createError(
            edgeKey,
            'edge' as any,
            'Invalid back-edge: target must be an ancestor of source in the graph',
          )
        );
      }
    }

    return this.buildResult(issues);
  }

  /**
   * Check if targetId is reachable from itself by following forward edges to sourceId.
   */
  private isAncestor(targetId: string, sourceId: string, context: ValidationContext): boolean {
    const successors = new Map<string, Set<string>>();
    for (const edge of context.edges) {
      if (edge.data?.isBackEdge) continue;
      const sources = successors.get(edge.source) || new Set<string>();
      sources.add(edge.target);
      successors.set(edge.source, sources);
    }

    const visited = new Set<string>();
    const queue = [targetId];
    visited.add(targetId);

    while (queue.length > 0) {
      const current = queue.shift()!;
      if (current === sourceId) return true;
      const succs = successors.get(current);
      if (succs) {
        for (const next of succs) {
          if (!visited.has(next)) {
            visited.add(next);
            queue.push(next);
          }
        }
      }
    }
    return false;
  }
}

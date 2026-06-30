/**
 * GraphStructureRule - Validates fundamental workflow graph structure
 *
 * Validations:
 * #1  At least one trigger (error)
 * #2  Multiple triggers sharing the same DAG - ALLOWED (auto-grouped via dag_group)
 * #5  Self-loop on non-loop nodes (error)
 */

import type { ValidationContext, ValidationIssue } from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';
import { normalizeLabel } from '../../../utils/labelNormalizer';
import { getNodeType } from '../core/nodeUtils';
import { nodeRegistry } from '../../../registry/nodeRegistry';

export class GraphStructureRule extends BaseValidationRule {
  readonly ruleName = 'GraphStructure' as const;
  readonly isCritical = true;
  readonly priority = 5;

  validate(context: ValidationContext): ReturnType<typeof this.buildResult> {
    const issues: ValidationIssue[] = [];
    const { edges, cache } = context;
    const { triggers } = cache.nodesByType;

    // #1: At least one trigger
    if (triggers.length === 0) {
      issues.push(
        this.createGlobalError('Workflow must have at least one trigger', {
          rule: 'no_triggers',
        })
      );
    }

    // #2: Multiple triggers sharing the same DAG (descendants overlap)
    // Triggers that share downstream nodes are auto-grouped via dag_group
    // in the plan generator. This is allowed and handled by the backend
    // execution engine (multi-trigger DAG support).
    // We only warn (not error) so the user is aware of the shared topology.

    // #5: Self-loop on non-loop nodes
    for (const edge of edges) {
      if (edge.source === edge.target) {
        const node = cache.nodeById.get(edge.source);
        if (node && !nodeRegistry.isLoopNode(node)) {
          const label = node.data?.label;
          const norm = label ? normalizeLabel(label) : null;
          const elType = getNodeType(node);
          const elementKey = norm ? `${elType}:${norm}` : `${elType}:${node.id}`;

          issues.push(
            this.createError(
              elementKey,
              elType,
              `Node "${label || node.id}" cannot connect to itself`,
              {
                rule: 'self_loop',
                nodeId: node.id,
              }
            )
          );
        }
      }
    }

    return this.buildResult(issues);
  }
}

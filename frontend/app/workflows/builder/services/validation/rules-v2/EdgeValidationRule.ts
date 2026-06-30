/**
 * EdgeValidationRule - Validates edges and edge-related constraints
 *
 * Validations:
 * #17  Edge source/target node does not exist (error)
 * #18  Trigger with incoming edges (error)
 * #19  Merge node with < 2 incoming edges (warning)
 */

import type { ValidationContext, ValidationIssue } from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';
import { normalizeLabel, coreKey } from '../../../utils/labelNormalizer';
import { isTriggerNode } from '../core/nodeUtils';
import { nodeRegistry } from '../../../registry/nodeRegistry';

export class EdgeValidationRule extends BaseValidationRule {
  readonly ruleName = 'EdgeValidation' as const;
  readonly isCritical = true;
  readonly priority = 14;

  validate(context: ValidationContext): ReturnType<typeof this.buildResult> {
    const issues: ValidationIssue[] = [];
    const { edges, cache } = context;
    const { nodeById } = cache;

    // #17: Edge source/target node exists
    for (const edge of edges) {
      const elementKey = `edge:${edge.id}`;

      if (!nodeById.has(edge.source)) {
        issues.push(
          this.createError(elementKey, 'edge', `Edge source node "${edge.source}" does not exist`, {
            rule: 'edge_invalid_source',
            edgeId: edge.id,
            sourceId: edge.source,
          })
        );
      }

      if (!nodeById.has(edge.target)) {
        issues.push(
          this.createError(elementKey, 'edge', `Edge target node "${edge.target}" does not exist`, {
            rule: 'edge_invalid_target',
            edgeId: edge.id,
            targetId: edge.target,
          })
        );
      }
    }

    // #18: Trigger with incoming edges
    for (const trigger of cache.nodesByType.triggers) {
      const incoming = cache.edgesByTarget.get(trigger.id) || [];
      if (incoming.length > 0) {
        const label = trigger.data?.label;
        const norm = label ? normalizeLabel(label) : null;
        const elementKey = norm ? `trigger:${norm}` : `trigger:${trigger.id}`;

        issues.push(
          this.createError(
            elementKey,
            'trigger',
            `Trigger "${label || trigger.id}" cannot have incoming edges. Triggers are entry points only.`,
            {
              rule: 'trigger_has_incoming',
              nodeId: trigger.id,
              incomingCount: incoming.length,
            }
          )
        );
      }
    }

    // #19: Merge node with < 2 incoming edges
    for (const node of cache.nodesByType.cores) {
      if (nodeRegistry.isMergeNode(node)) {
        const incoming = cache.edgesByTarget.get(node.id) || [];
        if (incoming.length < 2) {
          const label = node.data?.label;
          const elementKey = coreKey(label) ?? `core:${node.id}`;

          issues.push(
            this.createWarning(
              elementKey,
              'core',
              `Merge node "${label || node.id}" has only ${incoming.length} incoming edge(s). Merge nodes typically join 2 or more branches.`,
              {
                rule: 'merge_few_incoming',
                nodeId: node.id,
                incomingCount: incoming.length,
              }
            )
          );
        }
      }
    }

    return this.buildResult(issues);
  }
}

/**
 * GraphConnectivityRule - Ensures proper graph connectivity
 *
 * Validations:
 * #3  Node not reachable from any trigger (warning) - all node types
 */

import type { ValidationContext, ValidationIssue } from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';
import { computeReachableNodes } from '../core/ValidationCache';
import { normalizeLabel } from '../../../utils/labelNormalizer';
import { getNodeType, isNoteNode, isTriggerNode } from '../core/nodeUtils';

export class GraphConnectivityRule extends BaseValidationRule {
  readonly ruleName = 'GraphConnectivity' as const;
  readonly isCritical = true;
  readonly priority = 20;

  validate(context: ValidationContext): ReturnType<typeof this.buildResult> {
    const issues: ValidationIssue[] = [];
    const { edges, nodes, cache } = context;

    // Skip connectivity checks if no edges
    if (edges.length === 0) {
      return this.buildResult(issues);
    }

    // Compute reachable nodes from triggers
    const reachable = computeReachableNodes(cache);

    // Check ALL non-trigger, non-note nodes for reachability
    for (const node of nodes) {
      if (isTriggerNode(node) || isNoteNode(node)) continue;
      if (reachable.has(node.id)) continue;

      const label = node.data?.label;
      const nodeType = getNodeType(node);
      const norm = label ? normalizeLabel(label) : null;
      const elementKey = norm ? `${nodeType}:${norm}` : `${nodeType}:${node.id}`;

      issues.push(
        this.createWarning(
          elementKey,
          nodeType,
          `Node "${label || node.id}" is not reachable from any trigger`,
          {
            rule: 'unreachable_node',
            nodeId: node.id,
            nodeLabel: label,
            nodeType,
          }
        )
      );
    }

    return this.buildResult(issues);
  }
}

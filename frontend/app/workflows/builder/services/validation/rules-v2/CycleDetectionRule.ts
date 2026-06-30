/**
 * CycleDetectionRule - Detects directed cycles in the workflow graph
 *
 * Aligned with backend: CycleDetectionValidationRule.java
 *
 * Validates:
 * - No cycles exist in the workflow graph
 * - Reports all nodes involved in cycles
 */

import type { ValidationContext, ValidationIssue } from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';
import { detectCycles } from '../core/ValidationCache';
import { normalizeLabel } from '../../../utils/labelNormalizer';
import { getNodeType } from '../core/nodeUtils';

export class CycleDetectionRule extends BaseValidationRule {
  readonly ruleName = 'CycleDetection' as const;
  readonly isCritical = true;
  readonly priority = 11;

  validate(context: ValidationContext): ReturnType<typeof this.buildResult> {
    const issues: ValidationIssue[] = [];
    const { cache } = context;

    // Detect cycles using cached computation
    const cycles = detectCycles(cache);

    // Create issues for each node involved in a cycle
    cycles.forEach((cyclePath, nodeId) => {
      const node = cache.nodeById.get(nodeId);
      if (!node) return;

      const label = node.data?.label;
      const normalizedLabelValue = label ? normalizeLabel(label) : null;
      const nodeType = getNodeType(node);
      const elementKey = normalizedLabelValue
        ? `${nodeType}:${normalizedLabelValue}`
        : `${nodeType}:${nodeId}`;

      // Build human-readable cycle path
      const cyclePathLabels = cyclePath.map((id) => {
        const n = cache.nodeById.get(id);
        return n?.data?.label || id;
      });

      issues.push(
        this.createError(
          elementKey,
          nodeType,
          `Node is part of a cycle: ${cyclePathLabels.join(' → ')}`,
          {
            rule: 'cycle_detected',
            nodeId,
            nodeLabel: label,
            cyclePath,
            cyclePathLabels,
          }
        )
      );
    });

    return this.buildResult(issues);
  }
}

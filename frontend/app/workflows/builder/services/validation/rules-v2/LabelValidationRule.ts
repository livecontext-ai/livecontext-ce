/**
 * LabelValidationRule - Validates labels across all node types
 *
 * Validations:
 * #6  Label missing on any node (error)
 * #7  Duplicate labels after normalization (error)
 * #8  Unrecognized node key prefix (error)
 */

import type { ValidationContext, ValidationIssue } from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';
import { normalizeLabel } from '../../../utils/labelNormalizer';
import { getNodeType, isNoteNode, VALID_PREFIXES } from '../core/nodeUtils';

export class LabelValidationRule extends BaseValidationRule {
  readonly ruleName = 'LabelValidation' as const;
  readonly isCritical = true;
  readonly priority = 7;

  validate(context: ValidationContext): ReturnType<typeof this.buildResult> {
    const issues: ValidationIssue[] = [];
    const { nodes, cache } = context;

    // #6: Label missing (all node types except notes)
    for (const node of nodes) {
      if (isNoteNode(node)) continue;

      const label = node.data?.label;
      const nodeType = getNodeType(node);
      const elementKey = label
        ? `${nodeType}:${normalizeLabel(label) || node.id}`
        : `${nodeType}:${node.id}`;

      if (!label || label.trim() === '') {
        issues.push(
          this.createError(elementKey, nodeType, `${nodeType} node label is required`, {
            rule: 'missing_label',
            nodeId: node.id,
            nodeType,
          })
        );
      }

      // #8: Unrecognized prefix
      if (!VALID_PREFIXES.includes(nodeType)) {
        issues.push(
          this.createError(
            elementKey,
            nodeType,
            `Unrecognized node prefix "${nodeType}". Must be one of: ${VALID_PREFIXES.join(', ')}`,
            {
              rule: 'invalid_prefix',
              nodeId: node.id,
              detectedPrefix: nodeType,
            }
          )
        );
      }
    }

    // #7: Duplicate labels (after normalization, across all types)
    cache.labelMap.forEach((nodesWithLabel, normalizedLabelValue) => {
      if (nodesWithLabel.length > 1) {
        const nodeDescriptions = nodesWithLabel.map(({ node, nodeType }) => {
          return `${nodeType} "${node.data?.label}"`;
        });

        const originalLabel = nodesWithLabel[0].node.data?.label || normalizedLabelValue;
        const errorMessage = `Duplicate label "${originalLabel}" found in: ${nodeDescriptions.join(', ')}`;

        nodesWithLabel.forEach(({ node, nodeType }) => {
          const elementKey = `${nodeType}:${normalizedLabelValue}`;
          issues.push(
            this.createError(elementKey, nodeType, errorMessage, {
              rule: 'duplicate_label',
              nodeId: node.id,
              normalizedLabel: normalizedLabelValue,
              originalLabel: node.data?.label,
              conflictingNodes: nodesWithLabel.map((n) => ({
                nodeId: n.node.id,
                nodeType: n.nodeType,
                label: n.node.data?.label,
              })),
            })
          );
        });
      }
    });

    return this.buildResult(issues);
  }
}

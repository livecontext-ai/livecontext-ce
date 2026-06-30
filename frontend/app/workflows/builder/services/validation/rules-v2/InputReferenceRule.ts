/**
 * InputReferenceRule - Validates input template references
 *
 * Aligned with backend: InputReferenceValidationRule.java
 *
 * Validates:
 * - Input templates reference existing sources using unified pattern: {{type:label.output.field}}
 * - Data input references are valid (format: data.N or data.N.field)
 * - Step output references are valid
 */

import type { ValidationContext, ValidationIssue, ElementType } from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';
import { normalizeLabel } from '../../../utils/labelNormalizer';
import { getNodeType } from '../core/nodeUtils';

// Regex to match template references: {{reference}}.
// Mirrors backend TemplateEngine.EXPRESSION_PATTERN - accepts SpEL string literals.
const TEMPLATE_REFERENCE_REGEX = /\{\{((?:'(?:[^'\\]|\\.)*'|[^}|])+?)(?:\|[^}]*)?\}\}/g;

// Regex to validate data references: data.N or data.N.field
const DATA_REFERENCE_REGEX = /^data\.(\d+)(?:\.(.+))?$/;

// Regex to validate unified pattern: type:label.output.field
// Captures: [1]=type (trigger|mcp|core|agent), [2]=label, [3]=rest (output.field...)
const UNIFIED_PATTERN_REGEX = /^(trigger|mcp|core|agent):([a-z_][a-z0-9_]*)\.(.+)$/;

export class InputReferenceRule extends BaseValidationRule {
  readonly ruleName = 'InputReference' as const;
  readonly isCritical = true;
  readonly priority = 14;

  validate(context: ValidationContext): ReturnType<typeof this.buildResult> {
    const issues: ValidationIssue[] = [];
    const { nodes, cache } = context;

    // Build set of valid mcp labels for reference validation
    const validStepLabels = new Set<string>();
    cache.nodesByType.mcps.forEach((step) => {
      const label = step.data?.label;
      if (label) {
        const normalized = normalizeLabel(label);
        if (normalized) {
          validStepLabels.add(normalized);
        }
      }
    });

    // Validate each node's parameters for template references
    nodes.forEach((node) => {
      const nodeType = getNodeType(node);
      const label = node.data?.label;
      const normalizedLabelValue = label ? normalizeLabel(label) : null;
      const elementKey = normalizedLabelValue
        ? `${nodeType}:${normalizedLabelValue}`
        : `${nodeType}:${node.id}`;

      // Check tool parameters
      const toolData = node.data?.toolData;
      if (toolData?.parameters) {
        this.validateParameters(
          toolData.parameters as unknown as Record<string, unknown>,
          elementKey,
          nodeType,
          issues,
          validStepLabels
        );
      }

      // Check interface data
      const interfaceData = (node.data as unknown as Record<string, unknown>)?.interfaceData as Record<string, unknown> | undefined;
      if (interfaceData?.parameters) {
        this.validateParameters(
          interfaceData.parameters as Record<string, unknown>,
          elementKey,
          nodeType,
          issues,
          validStepLabels
        );
      }
    });

    return this.buildResult(issues);
  }

  private validateParameters(
    parameters: Record<string, unknown>,
    elementKey: string,
    nodeType: ElementType,
    issues: ValidationIssue[],
    validStepLabels: Set<string>
  ): void {
    Object.entries(parameters).forEach(([paramName, paramValue]) => {
      if (typeof paramValue !== 'string') return;

      // Find all template references
      const references = [...paramValue.matchAll(TEMPLATE_REFERENCE_REGEX)];

      references.forEach((match) => {
        const reference = match[1].trim();

        // Skip trigger references with unified pattern (trigger:label.output.field)
        // These are always valid if trigger exists
        if (reference.startsWith('trigger:')) {
          return;
        }

        // Skip core references with unified pattern (core:label.output.field)
        // E.g., core:split.output.current_item (runtime context in body nodes), core:split.output.items (persisted)
        if (reference.startsWith('core:')) {
          return;
        }

        // Skip agent references with unified pattern (agent:label.output.field)
        if (reference.startsWith('agent:')) {
          return;
        }

        // Validate data references
        const dataMatch = reference.match(DATA_REFERENCE_REGEX);
        if (dataMatch) {
          // Data references like data.0, data.1.field are valid by format
          // Backend validates if data input actually exists
          return;
        }

        // Validate mcp step output references using unified pattern
        // Format: mcp:label.output.field
        const unifiedMatch = reference.match(UNIFIED_PATTERN_REGEX);
        if (unifiedMatch) {
          const [, refType, stepLabel] = unifiedMatch;

          // Only validate mcp references (others are already handled above)
          if (refType === 'mcp') {
            const normalizedStepLabel = normalizeLabel(stepLabel);

            if (normalizedStepLabel && !validStepLabels.has(normalizedStepLabel)) {
              issues.push(
                this.createError(
                  elementKey,
                  nodeType,
                  `Invalid reference "{{${reference}}}" - mcp step "${stepLabel}" not found`,
                  {
                    rule: 'invalid_step_reference',
                    paramName,
                    reference,
                    referencedStep: stepLabel,
                  }
                )
              );
            }
          }
          return;
        }

        // Legacy format validation (for backwards compatibility): label.output.field
        // This handles old-style references that may still exist
        const parts = reference.split('.');
        if (parts.length >= 2 && parts[1] === 'output') {
          const stepLabel = parts[0];
          const normalizedStepLabel = normalizeLabel(stepLabel);

          if (normalizedStepLabel && !validStepLabels.has(normalizedStepLabel)) {
            issues.push(
              this.createError(
                elementKey,
                nodeType,
                `Invalid reference "{{${reference}}}" - step "${stepLabel}" not found. Consider using unified pattern: {{mcp:${normalizedStepLabel}.output.field}}`,
                {
                  rule: 'invalid_step_reference',
                  paramName,
                  reference,
                  referencedStep: stepLabel,
                }
              )
            );
          }
        }
      });
    });
  }
}

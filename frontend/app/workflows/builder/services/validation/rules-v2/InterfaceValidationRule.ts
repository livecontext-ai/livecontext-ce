/**
 * InterfaceValidationRule - Validates interface nodes
 *
 * Validates:
 * - Unsaved HTML template changes
 * - Interface attachment to tables (error)
 * - Missing interface ID
 * - Unmapped template variables (warning)
 */

import type { ValidationContext, ValidationIssue } from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';
import { normalizeLabel } from '../../../utils/labelNormalizer';

export class InterfaceValidationRule extends BaseValidationRule {
  readonly ruleName = 'InterfaceValidation' as const;
  readonly isCritical = true;
  readonly priority = 9;

  validate(context: ValidationContext): ReturnType<typeof this.buildResult> {
    const issues: ValidationIssue[] = [];
    const { cache } = context;
    const { interfaceNodes } = cache.nodesByType;

    interfaceNodes.forEach((node) => {
      const label = node.data?.label;
      const normalizedLabelValue = label ? normalizeLabel(label) : null;
      const elementKey = normalizedLabelValue ? `interface:${normalizedLabelValue}` : `interface:${node.id}`;

      const interfaceData = (node.data as unknown as Record<string, unknown>)?.interfaceData as Record<string, unknown> | undefined;
      if (!interfaceData) return;

      // Check: Unsaved HTML template changes (correct field name: hasUnsavedInterfaceChanges)
      const hasUnsavedChanges = (node.data as unknown as Record<string, unknown>)?.hasUnsavedInterfaceChanges as boolean | undefined;
      if (hasUnsavedChanges) {
        issues.push(
          this.createWarning(elementKey, 'interface', 'Interface has unsaved HTML template changes', {
            rule: 'interface_unsaved_changes',
            nodeId: node.id,
            interfaceId: interfaceData.interfaceId,
          })
        );
      }

      // Check: Interface attached to table
      const isAttachedToTable = interfaceData.isAttachedToTable as boolean | undefined;
      if (isAttachedToTable) {
        issues.push(
          this.createError(
            elementKey,
            'mcp',
            'Interface is attached to a table and cannot be used in workflow',
            {
              rule: 'interface_attached_to_table',
              nodeId: node.id,
              interfaceId: interfaceData.interfaceId,
            }
          )
        );
      }

      // Check: Missing interface ID
      const interfaceId = interfaceData.interfaceId as string | undefined;
      if (!interfaceId) {
        issues.push(
          this.createError(elementKey, 'interface', 'Interface node requires an interface ID', {
            rule: 'interface_missing_id',
            nodeId: node.id,
          })
        );
      }

      // Check: Unmapped template variables
      const templateVariables = interfaceData.templateVariables as string[] | undefined;
      const variableMapping = interfaceData.variableMapping as Record<string, string> | undefined;
      if (templateVariables && templateVariables.length > 0) {
        const mappedVars = variableMapping ? Object.keys(variableMapping) : [];
        const unmappedVars = templateVariables.filter(
          (v) => !mappedVars.includes(v) || !variableMapping?.[v]
        );
        if (unmappedVars.length > 0) {
          issues.push(
            this.createWarning(
              elementKey,
              'mcp',
              `Interface has ${unmappedVars.length} unmapped variable(s): ${unmappedVars.join(', ')}. Map them to workflow data in the inspector.`,
              {
                rule: 'interface_unmapped_variables',
                nodeId: node.id,
                interfaceId: interfaceData.interfaceId,
                unmappedVariables: unmappedVars,
              }
            )
          );
        }
      }
    });

    return this.buildResult(issues);
  }

  shouldRun(context: ValidationContext): boolean {
    return context.cache.nodesByType.interfaceNodes.length > 0;
  }
}

/**
 * CrudValidationRule - Validates CRUD/table nodes
 *
 * Validations:
 * #14  CRUD node without dataSourceId (error)
 * #15  CRUD node with invalid/empty dataSourceId (error)
 * #16  Mapped column with empty expression (error)
 * #17  No columns mapped (warning)
 */

import type { ValidationContext, ValidationIssue } from '../core/types';
import { BaseValidationRule } from './BaseValidationRule';
import { normalizeLabel } from '../../../utils/labelNormalizer';

export class CrudValidationRule extends BaseValidationRule {
  readonly ruleName = 'CrudValidation' as const;
  readonly isCritical = true;
  readonly priority = 9;

  validate(context: ValidationContext): ReturnType<typeof this.buildResult> {
    const issues: ValidationIssue[] = [];
    const { cache } = context;
    const { tableNodes } = cache.nodesByType;

    for (const node of tableNodes) {
      const label = node.data?.label;
      const norm = label ? normalizeLabel(label) : null;
      const elementKey = norm ? `table:${norm}` : `table:${node.id}`;

      const dsData = (node.data as any)?.dataSourceData;
      const dataSourceId = dsData?.dataSourceId;

      // #14: Missing dataSourceId
      if (!dataSourceId) {
        issues.push(
          this.createError(elementKey, 'table', 'CRUD node requires a data source (table)', {
            rule: 'crud_missing_datasource',
            nodeId: node.id,
          })
        );
        continue;
      }

      // #15: Invalid dataSourceId (empty string or whitespace)
      if (typeof dataSourceId === 'string' && dataSourceId.trim() === '') {
        issues.push(
          this.createError(elementKey, 'table', 'CRUD node has an invalid data source ID', {
            rule: 'crud_invalid_datasource',
            nodeId: node.id,
          })
        );
        continue;
      }

      // #16/#17: Column expression validation
      const columnExpressions = dsData?.columnExpressions || {};
      const columnFields = Object.keys(columnExpressions);
      const nonEmptyMappings = columnFields.filter(
        (key) => typeof columnExpressions[key] === 'string' && columnExpressions[key].trim() !== ''
      );

      for (const columnField of columnFields) {
        const expression = columnExpressions[columnField];
        if (!expression || (typeof expression === 'string' && expression.trim() === '')) {
          issues.push(
            this.createError(elementKey, 'table', `Mapped column "${columnField}" has an empty expression`, {
              rule: 'crud_empty_column_expression',
              nodeId: node.id,
              column: columnField,
            })
          );
        }
      }

      if (columnFields.length > 0 && nonEmptyMappings.length === 0) {
        issues.push(
          this.createWarning(elementKey, 'table', 'No columns are mapped for this data source', {
            rule: 'crud_no_columns_mapped',
            nodeId: node.id,
          })
        );
      }
    }

    return this.buildResult(issues);
  }

  shouldRun(context: ValidationContext): boolean {
    return context.cache.nodesByType.tableNodes.length > 0;
  }
}

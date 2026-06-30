import { describe, it, expect, beforeEach } from 'vitest';
import { CrudValidationRule } from '../rules-v2/CrudValidationRule';
import {
  makeCrudNode,
  makeTriggerNode,
  makeStepNode,
  buildContext,
  resetNodeCounter,
  resetEdgeCounter,
} from './test-helpers';

describe('CrudValidationRule', () => {
  let rule: CrudValidationRule;

  beforeEach(() => {
    rule = new CrudValidationRule();
    resetNodeCounter();
    resetEdgeCounter();
  });

  it('should have correct metadata', () => {
    expect(rule.ruleName).toBe('CrudValidation');
    expect(rule.isCritical).toBe(true);
    expect(rule.priority).toBe(9);
  });

  // ===================== #14: Missing dataSourceId =====================

  describe('#14 - CRUD without dataSourceId', () => {
    it('should error when CRUD node has no dataSourceId', () => {
      const crud = makeCrudNode('My Table');
      const ctx = buildContext([crud], []);
      const result = rule.validate(ctx);

      const dsIssues = result.issues.filter((i) => i.context?.rule === 'crud_missing_datasource');
      expect(dsIssues).toHaveLength(1);
      expect(dsIssues[0].severity).toBe('error');
      expect(dsIssues[0].elementType).toBe('table');
      expect(dsIssues[0].elementKey).toContain('table:');
    });

    it('should pass when CRUD node has valid dataSourceId', () => {
      const crud = makeCrudNode('My Table', { dataSourceId: 'ds-123' });
      const ctx = buildContext([crud], []);
      const result = rule.validate(ctx);

      const dsIssues = result.issues.filter((i) => i.context?.rule === 'crud_missing_datasource');
      expect(dsIssues).toHaveLength(0);
    });
  });

  // ===================== #15: Invalid dataSourceId =====================

  describe('#15 - CRUD with invalid dataSourceId', () => {
    it('should error when dataSourceId is empty string', () => {
      const crud = makeCrudNode('My Table', { dataSourceId: '' });
      const ctx = buildContext([crud], []);
      const result = rule.validate(ctx);

      // Empty string triggers the missing check (falsy)
      expect(result.hasErrors).toBe(true);
    });

    it('should error when dataSourceId is whitespace only', () => {
      const crud = makeCrudNode('My Table', { dataSourceId: '   ' });
      const ctx = buildContext([crud], []);
      const result = rule.validate(ctx);

      const invalidIssues = result.issues.filter((i) => i.context?.rule === 'crud_invalid_datasource');
      expect(invalidIssues).toHaveLength(1);
    });
  });

  // ===================== shouldRun =====================

  describe('shouldRun', () => {
    it('should not run when no CRUD nodes exist', () => {
      const trigger = makeTriggerNode('Start');
      const step = makeStepNode('Step');
      const ctx = buildContext([trigger, step], []);

      expect(rule.shouldRun!(ctx)).toBe(false);
    });

    it('should run when CRUD nodes exist', () => {
      const crud = makeCrudNode('Table', { dataSourceId: 'ds-1' });
      const ctx = buildContext([crud], []);

      expect(rule.shouldRun!(ctx)).toBe(true);
    });
  });

  // ===================== #16: Empty column expression =====================

  describe('#16 - Mapped column with empty expression', () => {
    it('should error when a mapped column has an empty expression', () => {
      const crud = makeCrudNode('My Table', {
        dataSourceId: 'ds-1',
        columnExpressions: { name: 'John', email: '' },
      });
      const ctx = buildContext([crud], []);
      const result = rule.validate(ctx);

      const colIssues = result.issues.filter((i) => i.context?.rule === 'crud_empty_column_expression');
      expect(colIssues).toHaveLength(1);
      expect(colIssues[0].severity).toBe('error');
      expect(colIssues[0].message).toContain('email');
    });

    it('should error for multiple empty column expressions', () => {
      const crud = makeCrudNode('My Table', {
        dataSourceId: 'ds-1',
        columnExpressions: { name: '', email: '', age: '25' },
      });
      const ctx = buildContext([crud], []);
      const result = rule.validate(ctx);

      const colIssues = result.issues.filter((i) => i.context?.rule === 'crud_empty_column_expression');
      expect(colIssues).toHaveLength(2);
    });

    it('should pass when all columns have non-empty expressions', () => {
      const crud = makeCrudNode('My Table', {
        dataSourceId: 'ds-1',
        columnExpressions: { name: 'John', email: 'john@test.com' },
      });
      const ctx = buildContext([crud], []);
      const result = rule.validate(ctx);

      const colIssues = result.issues.filter((i) => i.context?.rule === 'crud_empty_column_expression');
      expect(colIssues).toHaveLength(0);
    });

    it('should error for whitespace-only column expressions', () => {
      const crud = makeCrudNode('My Table', {
        dataSourceId: 'ds-1',
        columnExpressions: { name: '   ' },
      });
      const ctx = buildContext([crud], []);
      const result = rule.validate(ctx);

      const colIssues = result.issues.filter((i) => i.context?.rule === 'crud_empty_column_expression');
      expect(colIssues).toHaveLength(1);
    });
  });

  // ===================== #17: No columns mapped =====================

  describe('#17 - No columns mapped', () => {
    it('should warn when columns exist but all are empty', () => {
      const crud = makeCrudNode('My Table', {
        dataSourceId: 'ds-1',
        columnExpressions: { name: '', email: '' },
      });
      const ctx = buildContext([crud], []);
      const result = rule.validate(ctx);

      const noMapIssues = result.issues.filter((i) => i.context?.rule === 'crud_no_columns_mapped');
      expect(noMapIssues).toHaveLength(1);
      expect(noMapIssues[0].severity).toBe('warning');
    });

    it('should not warn when at least one column has a value', () => {
      const crud = makeCrudNode('My Table', {
        dataSourceId: 'ds-1',
        columnExpressions: { name: 'John', email: '' },
      });
      const ctx = buildContext([crud], []);
      const result = rule.validate(ctx);

      const noMapIssues = result.issues.filter((i) => i.context?.rule === 'crud_no_columns_mapped');
      expect(noMapIssues).toHaveLength(0);
    });

    it('should not warn when no columns are defined at all', () => {
      const crud = makeCrudNode('My Table', { dataSourceId: 'ds-1' });
      const ctx = buildContext([crud], []);
      const result = rule.validate(ctx);

      const noMapIssues = result.issues.filter((i) => i.context?.rule === 'crud_no_columns_mapped');
      expect(noMapIssues).toHaveLength(0);
    });
  });

  // ===================== Multiple CRUD nodes =====================

  describe('multiple CRUD nodes', () => {
    it('should validate each CRUD node independently', () => {
      const valid = makeCrudNode('Valid Table', { id: 'crud-1', dataSourceId: 'ds-1' });
      const invalid = makeCrudNode('Invalid Table', { id: 'crud-2' });
      const ctx = buildContext([valid, invalid], []);
      const result = rule.validate(ctx);

      const dsIssues = result.issues.filter((i) => i.context?.rule === 'crud_missing_datasource');
      expect(dsIssues).toHaveLength(1); // only the invalid one
    });
  });
});

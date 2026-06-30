import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData, ConditionRow, SwitchCaseRow } from '../../types';
import { NodeValidationService } from '../NodeValidationService';

// =========================================================================
// Local test helpers (not shared)
// =========================================================================

function makeToolNode(opts: {
  id?: string;
  label?: string;
  parameters?: Array<{
    name: string;
    isRequired?: boolean;
    required?: boolean;
    defaultValue?: string;
  }>;
  paramExpressions?: Record<string, string>;
  toolSlug?: string;
  noToolData?: boolean;
}): Node<BuilderNodeData> {
  const id = opts.id || 'tool-1';
  return {
    id,
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: {
      id,
      label: opts.label || 'Tool Node',
      kind: 'action',
      badge: 'Step',
      toolData: opts.noToolData
        ? undefined
        : {
            toolId: 'tool-id-1',
            toolSlug: opts.toolSlug,
            apiName: 'TestAPI',
            method: 'POST',
            parameters: opts.parameters,
          },
      paramExpressions: opts.paramExpressions,
    },
  };
}

function makeDecisionTestNode(opts: {
  id?: string;
  label?: string;
  conditions: ConditionRow[];
}): Node<BuilderNodeData> {
  const id = opts.id || 'decision-1';
  return {
    id,
    type: 'decisionNode',
    position: { x: 0, y: 0 },
    data: {
      id,
      label: opts.label || 'Decision',
      kind: 'decision',
      badge: 'Decision',
      decisionConditions: opts.conditions,
    },
  };
}

function makeLoopTestNode(opts: {
  id?: string;
  label?: string;
  loopCondition?: string;
}): Node<BuilderNodeData> {
  const id = opts.id || 'loop-1';
  return {
    id,
    type: 'whileGroupNode',
    position: { x: 0, y: 0 },
    data: {
      id,
      label: opts.label || 'Loop',
      kind: 'loop',
      badge: 'Loop',
      loopCondition: opts.loopCondition,
    },
  };
}

function makeSplitTestNode(opts: {
  id?: string;
  label?: string;
  list?: string;
}): Node<BuilderNodeData> {
  const id = opts.id || 'split-1';
  return {
    id,
    type: 'splitNode',
    position: { x: 0, y: 0 },
    data: {
      id,
      label: opts.label || 'Split',
      kind: 'split',
      badge: 'Split',
      list: opts.list,
    },
  };
}

function makeSwitchTestNode(opts: {
  id?: string;
  label?: string;
  switchExpression?: string;
  switchCases?: SwitchCaseRow[];
}): Node<BuilderNodeData> {
  const id = opts.id || 'switch-1';
  return {
    id,
    type: 'switchNode',
    position: { x: 0, y: 0 },
    data: {
      id,
      label: opts.label || 'Switch',
      kind: 'switch',
      badge: 'Switch',
      switchExpression: opts.switchExpression,
      switchCases: opts.switchCases,
    } as any,
  };
}

function makeDataSourceTestNode(opts: {
  id?: string;
  label?: string;
  dataSourceId?: number;
  columnExpressions?: Record<string, string>;
}): Node<BuilderNodeData> {
  const id = opts.id || 'ds-1';
  return {
    id,
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: {
      id,
      label: opts.label || 'DataSource',
      kind: 'action',
      badge: 'DataSource',
      dataSourceData: {
        dataSourceId: opts.dataSourceId ?? 1,
        dataSourceName: 'TestDS',
        columnExpressions: opts.columnExpressions,
      },
    },
  };
}

function makeInterfaceTestNode(opts: {
  id?: string;
  label?: string;
  interfaceId?: string;
  editorExpression?: string;
}): Node<BuilderNodeData> {
  const id = opts.id || 'iface-1';
  return {
    id,
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: {
      id,
      label: opts.label || 'Interface',
      kind: 'interface',
      badge: 'Interface',
      interfaceData: {
        interfaceId: opts.interfaceId,
        editorExpression: opts.editorExpression,
      },
    } as any,
  };
}

// =========================================================================
// Tests
// =========================================================================

describe('NodeValidationService', () => {
  // ====================================================================
  // Null / undefined node
  // ====================================================================

  describe('null / undefined node', () => {
    it('should return isValid:true for null node', () => {
      const result = NodeValidationService.validateNode(null);
      expect(result.isValid).toBe(true);
      expect(result.errors).toHaveLength(0);
      expect(result.errorCount).toBe(0);
      expect(result.warningCount).toBe(0);
    });

    it('should return isValid:true for undefined node (cast)', () => {
      const result = NodeValidationService.validateNode(undefined as any);
      expect(result.isValid).toBe(true);
      expect(result.errors).toHaveLength(0);
    });
  });

  // ====================================================================
  // MCP required params (isRequired / required)
  // ====================================================================

  describe('MCP required params', () => {
    it('should error when a required param (isRequired) has no expression', () => {
      const node = makeToolNode({
        parameters: [{ name: 'apiKey', isRequired: true }],
        paramExpressions: {},
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errorCount).toBe(1);
      expect(result.errors[0].message).toContain('apiKey');
      expect(result.errors[0].severity).toBe('error');
    });

    it('should error when a required param (required) has no expression', () => {
      const node = makeToolNode({
        parameters: [{ name: 'token', required: true }],
        paramExpressions: {},
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errorCount).toBe(1);
      expect(result.errors[0].message).toContain('token');
    });

    it('should error when a required param expression is empty string', () => {
      const node = makeToolNode({
        parameters: [{ name: 'body', isRequired: true }],
        paramExpressions: { body: '' },
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errorCount).toBe(1);
    });

    it('should error when a required param expression is whitespace', () => {
      const node = makeToolNode({
        parameters: [{ name: 'query', isRequired: true }],
        paramExpressions: { query: '   ' },
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errorCount).toBe(1);
    });

    it('should pass when required param has a valid expression', () => {
      const node = makeToolNode({
        parameters: [{ name: 'apiKey', isRequired: true }],
        paramExpressions: { apiKey: '#{secret}' },
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
      expect(result.errorCount).toBe(0);
    });

    it('should error for multiple missing required params', () => {
      const node = makeToolNode({
        parameters: [
          { name: 'a', isRequired: true },
          { name: 'b', required: true },
          { name: 'c', isRequired: true },
        ],
        paramExpressions: { a: 'val' },
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errorCount).toBe(2);
    });

    it('should error when paramExpressions is undefined and params are required', () => {
      const node = makeToolNode({
        parameters: [{ name: 'x', isRequired: true }],
        // no paramExpressions
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errorCount).toBe(1);
    });

    it('should pass when all required params are filled', () => {
      const node = makeToolNode({
        parameters: [
          { name: 'a', isRequired: true },
          { name: 'b', required: true },
        ],
        paramExpressions: { a: 'val_a', b: 'val_b' },
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
      expect(result.errorCount).toBe(0);
    });

    it('should treat param with neither isRequired nor required as optional', () => {
      const node = makeToolNode({
        parameters: [{ name: 'opt' }],
        paramExpressions: {},
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
      expect(result.errorCount).toBe(0);
    });
  });

  // ====================================================================
  // MCP optional params with defaults
  // ====================================================================

  describe('MCP optional params with defaults', () => {
    it('should warn when optional param has defaultValue but no expression', () => {
      const node = makeToolNode({
        parameters: [{ name: 'limit', defaultValue: '10' }],
        paramExpressions: {},
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true); // warnings don't block
      expect(result.warningCount).toBe(1);
      expect(result.errors[0].severity).toBe('warning');
    });

    it('should warn when optional param has defaultValue and expression is empty', () => {
      const node = makeToolNode({
        parameters: [{ name: 'limit', defaultValue: '10' }],
        paramExpressions: { limit: '' },
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.warningCount).toBe(1);
    });

    it('should warn when optional param has defaultValue and expression is whitespace', () => {
      const node = makeToolNode({
        parameters: [{ name: 'limit', defaultValue: '10' }],
        paramExpressions: { limit: '   ' },
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.warningCount).toBe(1);
    });

    it('should NOT warn when optional param without defaultValue is empty', () => {
      const node = makeToolNode({
        parameters: [{ name: 'opt' }],
        paramExpressions: {},
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.warningCount).toBe(0);
    });

    it('should NOT warn when optional param with defaultValue has expression', () => {
      const node = makeToolNode({
        parameters: [{ name: 'limit', defaultValue: '10' }],
        paramExpressions: { limit: '20' },
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.warningCount).toBe(0);
    });
  });

  // ====================================================================
  // MCP mixed required + optional
  // ====================================================================

  describe('MCP mixed required + optional', () => {
    it('should produce errors for required and warnings for optional with defaults', () => {
      const node = makeToolNode({
        parameters: [
          { name: 'apiKey', isRequired: true },
          { name: 'limit', defaultValue: '10' },
        ],
        paramExpressions: {},
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errorCount).toBe(1);
      expect(result.warningCount).toBe(1);
    });

    it('should be valid when only optional defaults are not set (warnings only)', () => {
      const node = makeToolNode({
        parameters: [
          { name: 'apiKey', isRequired: true },
          { name: 'limit', defaultValue: '10' },
        ],
        paramExpressions: { apiKey: 'my-key' },
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
      expect(result.warningCount).toBe(1);
    });
  });

  // ====================================================================
  // MCP params loading state
  // ====================================================================

  describe('MCP params loading state', () => {
    it('should skip validation when parameters is undefined', () => {
      const node = makeToolNode({
        parameters: undefined as any,
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
      expect(result.errors).toHaveLength(0);
    });

    it('should skip validation when parameters is not an array', () => {
      const node = makeToolNode({
        parameters: 'loading' as any,
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
      expect(result.errors).toHaveLength(0);
    });

    it('should skip validation when parameters is empty and toolSlug exists', () => {
      const node = makeToolNode({
        parameters: [],
        toolSlug: 'some-api-tool',
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
      expect(result.errors).toHaveLength(0);
    });

    it('should NOT skip when parameters is empty and no toolSlug', () => {
      const node = makeToolNode({
        parameters: [],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
      expect(result.errors).toHaveLength(0);
    });

    it('should use external toolParameters when provided', () => {
      const node = makeToolNode({
        parameters: [], // internal empty
      });
      const externalParams = [{ name: 'key', isRequired: true }];
      const result = NodeValidationService.validateNode(node, externalParams);
      expect(result.isValid).toBe(false);
      expect(result.errorCount).toBe(1);
    });
  });

  // ====================================================================
  // MCP no toolData
  // ====================================================================

  describe('MCP no toolData', () => {
    it('should skip tool validation when toolData is undefined', () => {
      const node = makeToolNode({ noToolData: true });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
      expect(result.errors).toHaveLength(0);
    });

    it('should skip tool validation when no toolData or apiData', () => {
      const node: Node<BuilderNodeData> = {
        id: 'bare-1',
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'bare-1',
          label: 'Bare',
          kind: 'action',
          badge: 'Step',
        },
      };
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
    });
  });

  // ====================================================================
  // DataSource node
  // ====================================================================

  describe('DataSource node', () => {
    it('should error when a mapped column has an empty expression', () => {
      const node = makeDataSourceTestNode({
        columnExpressions: { name: '', age: 'expr' },
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errorCount).toBe(1);
      expect(result.errors[0].message).toContain('name');
    });

    it('should error for multiple empty column expressions', () => {
      const node = makeDataSourceTestNode({
        columnExpressions: { name: '', email: '  ', city: 'Paris' },
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.errorCount).toBe(2);
    });

    it('should warn when no columns are mapped', () => {
      const node = makeDataSourceTestNode({
        columnExpressions: {},
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
      expect(result.warningCount).toBe(1);
    });

    it('should warn when all column expressions are empty', () => {
      const node = makeDataSourceTestNode({
        columnExpressions: { name: '', email: '' },
      });
      const result = NodeValidationService.validateNode(node);
      // errors for each empty column + warning for no non-empty mappings
      expect(result.errorCount).toBe(2);
      expect(result.warningCount).toBe(1);
    });

    it('should pass when all mapped columns have expressions', () => {
      const node = makeDataSourceTestNode({
        columnExpressions: { name: 'John', age: '30' },
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
      expect(result.errorCount).toBe(0);
      expect(result.warningCount).toBe(0);
    });

    it('should skip datasource validation when no dataSourceId', () => {
      const node: Node<BuilderNodeData> = {
        id: 'ds-no-id',
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'ds-no-id',
          label: 'DS',
          kind: 'action',
          badge: 'DS',
          dataSourceData: {
            dataSourceId: 0 as any, // falsy
            dataSourceName: 'Test',
            columnExpressions: { col: '' },
          },
        },
      };
      const result = NodeValidationService.validateNode(node);
      // dataSourceId is falsy so the inner check for `dataSourceData.dataSourceId` is skipped
      expect(result.errorCount).toBe(0);
    });
  });

  // ====================================================================
  // Loop node
  // ====================================================================

  describe('Loop node', () => {
    it('should error when loopCondition is missing', () => {
      const node = makeLoopTestNode({});
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errorCount).toBe(1);
      expect(result.errors[0].message).toContain('Loop condition');
    });

    it('should error when loopCondition is empty string', () => {
      const node = makeLoopTestNode({ loopCondition: '' });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
    });

    it('should error when loopCondition is whitespace', () => {
      const node = makeLoopTestNode({ loopCondition: '   ' });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
    });

    it('should pass when loopCondition is present', () => {
      const node = makeLoopTestNode({ loopCondition: 'x < 10' });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
    });

    it('should not trigger split validation for loop nodes', () => {
      const node = makeLoopTestNode({ loopCondition: 'true' });
      // Loop node shouldn't get "List expression is missing" error
      const result = NodeValidationService.validateNode(node);
      expect(result.errors.every(e => !e.message.includes('List expression'))).toBe(true);
    });
  });

  // ====================================================================
  // Split node
  // ====================================================================

  describe('Split node', () => {
    it('should error when list expression is missing', () => {
      const node = makeSplitTestNode({});
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errorCount).toBe(1);
      expect(result.errors[0].message).toContain('List expression');
    });

    it('should error when list expression is empty', () => {
      const node = makeSplitTestNode({ list: '' });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
    });

    it('should error when list expression is whitespace', () => {
      const node = makeSplitTestNode({ list: '   ' });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
    });

    it('should pass when list expression is present', () => {
      const node = makeSplitTestNode({ list: '#{items}' });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
    });
  });

  // ====================================================================
  // Decision node
  // ====================================================================

  describe('Decision node', () => {
    it('should error when IF condition has empty expression', () => {
      const node = makeDecisionTestNode({
        conditions: [
          { id: 'c1', type: 'if', label: 'IF', expression: '' },
          { id: 'c2', type: 'else', label: 'ELSE' },
        ],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errorCount).toBe(1);
    });

    it('should error when IF condition has no expression', () => {
      const node = makeDecisionTestNode({
        conditions: [
          { id: 'c1', type: 'if', label: 'IF' },
          { id: 'c2', type: 'else', label: 'ELSE' },
        ],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
    });

    it('should error when ELSEIF condition has empty expression', () => {
      const node = makeDecisionTestNode({
        conditions: [
          { id: 'c1', type: 'if', label: 'IF', expression: 'x > 0' },
          { id: 'c2', type: 'elseif', label: 'ELSEIF', expression: '' },
          { id: 'c3', type: 'else', label: 'ELSE' },
        ],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errorCount).toBe(1);
    });

    it('should NOT error for ELSE branch (no expression needed)', () => {
      const node = makeDecisionTestNode({
        conditions: [
          { id: 'c1', type: 'if', label: 'IF', expression: 'x > 0' },
          { id: 'c2', type: 'else', label: 'ELSE' },
        ],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
    });

    it('should error for multiple empty IF/ELSEIF conditions', () => {
      const node = makeDecisionTestNode({
        conditions: [
          { id: 'c1', type: 'if', label: 'IF', expression: '' },
          { id: 'c2', type: 'elseif', label: 'ELSEIF 1', expression: '   ' },
          { id: 'c3', type: 'elseif', label: 'ELSEIF 2', expression: '' },
          { id: 'c4', type: 'else', label: 'ELSE' },
        ],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.errorCount).toBe(3);
    });

    it('should pass when all IF/ELSEIF conditions have expressions', () => {
      const node = makeDecisionTestNode({
        conditions: [
          { id: 'c1', type: 'if', label: 'IF', expression: 'x > 0' },
          { id: 'c2', type: 'elseif', label: 'ELSEIF', expression: 'x < 0' },
          { id: 'c3', type: 'else', label: 'ELSE' },
        ],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
    });

    it('should pass with empty decisionConditions array', () => {
      const node = makeDecisionTestNode({ conditions: [] });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
    });

    it('should use condition label in error message', () => {
      const node = makeDecisionTestNode({
        conditions: [
          { id: 'c1', type: 'if', label: 'CheckAge', expression: '' },
        ],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.errors[0].message).toContain('CheckAge');
    });
  });

  // ====================================================================
  // Switch node
  // ====================================================================

  describe('Switch node', () => {
    it('should error when switchExpression is missing', () => {
      const node = makeSwitchTestNode({
        switchCases: [
          { id: 's1', type: 'case', label: 'Case 1', value: 'A' },
          { id: 's2', type: 'default', label: 'Default' },
        ],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errors.some(e => e.message.includes('Switch expression'))).toBe(true);
    });

    it('should error when switchExpression is empty string', () => {
      const node = makeSwitchTestNode({
        switchExpression: '',
        switchCases: [{ id: 's1', type: 'case', label: 'A', value: 'A' }],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.errors.some(e => e.message.includes('Switch expression'))).toBe(true);
    });

    it('should error when switchExpression is whitespace', () => {
      const node = makeSwitchTestNode({
        switchExpression: '   ',
        switchCases: [{ id: 's1', type: 'case', label: 'A', value: 'A' }],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.errors.some(e => e.message.includes('Switch expression'))).toBe(true);
    });

    it('should error when switchCases is empty', () => {
      const node = makeSwitchTestNode({
        switchExpression: '#{status}',
        switchCases: [],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errors.some(e => e.message.includes('at least one case'))).toBe(true);
    });

    it('should error when switchCases has only default (no case branches)', () => {
      const node = makeSwitchTestNode({
        switchExpression: '#{status}',
        switchCases: [{ id: 's1', type: 'default', label: 'Default' }],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errors.some(e => e.message.includes('not just default'))).toBe(true);
    });

    it('should error when a case branch has empty value', () => {
      const node = makeSwitchTestNode({
        switchExpression: '#{status}',
        switchCases: [
          { id: 's1', type: 'case', label: 'CaseA', value: '' },
          { id: 's2', type: 'default', label: 'Default' },
        ],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errors.some(e => e.message.includes('CaseA'))).toBe(true);
    });

    it('should error when a case branch has whitespace value', () => {
      const node = makeSwitchTestNode({
        switchExpression: '#{status}',
        switchCases: [
          { id: 's1', type: 'case', label: 'CaseA', value: '   ' },
          { id: 's2', type: 'default', label: 'Default' },
        ],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.errors.some(e => e.message.includes('CaseA'))).toBe(true);
    });

    it('should error for multiple empty case values', () => {
      const node = makeSwitchTestNode({
        switchExpression: '#{status}',
        switchCases: [
          { id: 's1', type: 'case', label: 'A', value: '' },
          { id: 's2', type: 'case', label: 'B', value: '' },
          { id: 's3', type: 'default', label: 'Default' },
        ],
      });
      const result = NodeValidationService.validateNode(node);
      const caseErrors = result.errors.filter(e => e.message.includes('missing a value'));
      expect(caseErrors).toHaveLength(2);
    });

    it('should pass when switch is fully configured', () => {
      const node = makeSwitchTestNode({
        switchExpression: '#{status}',
        switchCases: [
          { id: 's1', type: 'case', label: 'Active', value: 'active' },
          { id: 's2', type: 'case', label: 'Inactive', value: 'inactive' },
          { id: 's3', type: 'default', label: 'Default' },
        ],
      });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
    });

    it('should produce both expression and case errors when both are missing', () => {
      const node = makeSwitchTestNode({
        switchExpression: '',
        switchCases: [],
      });
      const result = NodeValidationService.validateNode(node);
      // expression error + at least one case error
      expect(result.errorCount).toBeGreaterThanOrEqual(2);
    });
  });

  // ====================================================================
  // Interface node
  // ====================================================================

  describe('Interface node', () => {
    it('should error when no template and no interfaceId (kind=interface)', () => {
      const node = makeInterfaceTestNode({ editorExpression: '' });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
      expect(result.errors.some(e => e.message.includes('HTML template'))).toBe(true);
    });

    it('should error when template is undefined and no interfaceId', () => {
      const node = makeInterfaceTestNode({});
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
    });

    it('should error when template is whitespace and no interfaceId', () => {
      const node = makeInterfaceTestNode({ editorExpression: '   ' });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(false);
    });

    it('should skip validation when interfaceId exists in interfaceData', () => {
      const node = makeInterfaceTestNode({
        interfaceId: 'iface-uuid-123',
        editorExpression: '',
      });
      const result = NodeValidationService.validateNode(node);
      // interfaceId exists, so no error even if template is empty
      expect(result.isValid).toBe(true);
    });

    it('should skip validation when node id starts with interface-', () => {
      const node: Node<BuilderNodeData> = {
        id: 'interface-abc123',
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'interface-abc123',
          label: 'My Interface',
          kind: 'interface',
          badge: 'Interface',
          interfaceData: {
            editorExpression: '',
          },
        } as any,
      };
      const result = NodeValidationService.validateNode(node);
      // interface-abc123 implies an interfaceId
      expect(result.isValid).toBe(true);
    });

    it('should pass when template is present', () => {
      const node = makeInterfaceTestNode({ editorExpression: '<div>Hello</div>' });
      const result = NodeValidationService.validateNode(node);
      expect(result.isValid).toBe(true);
    });

    it('should detect interface via data.id starting with interface-', () => {
      const node: Node<BuilderNodeData> = {
        id: 'some-rf-id',
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'interface-xyz',
          label: 'My Interface',
          kind: 'action', // not 'interface' kind
          badge: 'Step',
          interfaceData: {
            editorExpression: '',
          },
        } as any,
      };
      const result = NodeValidationService.validateNode(node);
      // data.id starts with interface-, which also extracts interfaceId
      expect(result.isValid).toBe(true);
    });
  });

  // ====================================================================
  // Backend errors integration
  // ====================================================================

  describe('Backend errors integration', () => {
    it('should include backend errors passed as parameter', () => {
      const node = makeToolNode({
        parameters: [],
        noToolData: true,
      });
      const result = NodeValidationService.validateNode(node, undefined, [
        'Backend error 1',
        'Backend error 2',
      ]);
      expect(result.isValid).toBe(false);
      expect(result.errorCount).toBe(2);
      expect(result.errors[0].source).toBe('backend');
      expect(result.errors[1].source).toBe('backend');
    });

    it('should include validationIssues from node data', () => {
      const node: Node<BuilderNodeData> = {
        id: 'n1',
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'n1',
          label: 'Node',
          kind: 'action',
          badge: 'Step',
          validationIssues: ['Data issue A'],
        } as any,
      };
      const result = NodeValidationService.validateNode(node);
      expect(result.errorCount).toBe(1);
      expect(result.errors[0].message).toBe('Data issue A');
    });

    it('should prefer backendErrors param over validationIssues in data', () => {
      const node: Node<BuilderNodeData> = {
        id: 'n1',
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'n1',
          label: 'Node',
          kind: 'action',
          badge: 'Step',
          validationIssues: ['From data'],
        } as any,
      };
      const result = NodeValidationService.validateNode(node, undefined, ['From param']);
      expect(result.errorCount).toBe(1);
      expect(result.errors[0].message).toBe('From param');
    });

    it('should handle non-array validationIssues gracefully', () => {
      const node: Node<BuilderNodeData> = {
        id: 'n1',
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'n1',
          label: 'Node',
          kind: 'action',
          badge: 'Step',
          validationIssues: 'not an array',
        } as any,
      };
      const result = NodeValidationService.validateNode(node);
      // non-array should be treated as empty
      expect(result.errorCount).toBe(0);
    });

    it('should merge backend + frontend errors', () => {
      const node = makeToolNode({
        parameters: [{ name: 'key', isRequired: true }],
        paramExpressions: {},
      });
      const result = NodeValidationService.validateNode(node, undefined, ['Backend issue']);
      expect(result.errorCount).toBe(2); // 1 backend + 1 frontend
      expect(result.errors.some(e => e.source === 'backend')).toBe(true);
      expect(result.errors.some(e => e.source === 'frontend')).toBe(true);
    });

    it('should mark backend errors as type general', () => {
      const node = makeToolNode({ noToolData: true });
      const result = NodeValidationService.validateNode(node, undefined, ['Some backend error']);
      expect(result.errors[0].type).toBe('general');
    });
  });

  // ====================================================================
  // Deduplication
  // ====================================================================

  describe('Deduplication', () => {
    it('should deduplicate errors with same severity and message', () => {
      const node: Node<BuilderNodeData> = {
        id: 'n1',
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'n1',
          label: 'Node',
          kind: 'action',
          badge: 'Step',
          toolData: {
            toolId: 'tool-1',
            apiName: 'API',
            method: 'GET',
            parameters: [{ name: 'key', isRequired: true }],
          },
          validationIssues: ['Required parameter "key" is missing'],
        } as any,
      };
      const result = NodeValidationService.validateNode(node);
      // Backend and frontend produce same message; should be deduplicated
      const keyErrors = result.errors.filter(e => e.message.includes('key'));
      expect(keyErrors).toHaveLength(1);
    });

    it('should NOT deduplicate errors with different messages', () => {
      const node: Node<BuilderNodeData> = {
        id: 'n1',
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'n1',
          label: 'Node',
          kind: 'action',
          badge: 'Step',
          validationIssues: ['Error A', 'Error B'],
        } as any,
      };
      const result = NodeValidationService.validateNode(node);
      expect(result.errorCount).toBe(2);
    });

    it('should NOT deduplicate errors with different severities but same message', () => {
      // This is a tricky edge case - in practice it doesn't happen naturally,
      // but the dedup key is `${severity}:${message}` so different severities are kept
      const node: Node<BuilderNodeData> = {
        id: 'n1',
        type: 'flowNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'n1',
          label: 'Node',
          kind: 'action',
          badge: 'Step',
          toolData: {
            toolId: 'tool-1',
            apiName: 'API',
            method: 'GET',
            parameters: [{ name: 'limit', defaultValue: '10' }],
          },
          // Backend says it's an error; frontend says it's a warning
          validationIssues: [
            'Optional parameter "limit" has a default value but is not set',
          ],
        } as any,
      };
      const result = NodeValidationService.validateNode(node);
      // Backend produces error; frontend produces warning; different severity = not deduped
      const limitIssues = result.errors.filter(e => e.message.includes('limit'));
      expect(limitIssues).toHaveLength(2);
    });

    it('should deduplicate when source differs but severity+message match', () => {
      const node = makeToolNode({
        parameters: [{ name: 'q', isRequired: true }],
        paramExpressions: {},
      });
      // Provide the exact same message as what frontend generates
      const result = NodeValidationService.validateNode(node, undefined, [
        'Required parameter "q" is missing',
      ]);
      const qErrors = result.errors.filter(e => e.message.includes('"q"'));
      expect(qErrors).toHaveLength(1);
    });
  });

  // ====================================================================
  // getErrorSummary
  // ====================================================================

  describe('getErrorSummary', () => {
    it('should return empty string when valid with no warnings', () => {
      const result = NodeValidationService.validateNode(null);
      const summary = NodeValidationService.getErrorSummary(result);
      expect(summary).toBe('');
    });

    it('should return singular error text', () => {
      const summary = NodeValidationService.getErrorSummary({
        isValid: false,
        errors: [],
        errorCount: 1,
        warningCount: 0,
      });
      expect(summary).toBe('1 error');
    });

    it('should return plural errors text', () => {
      const summary = NodeValidationService.getErrorSummary({
        isValid: false,
        errors: [],
        errorCount: 3,
        warningCount: 0,
      });
      expect(summary).toBe('3 errors');
    });

    it('should return singular warning text', () => {
      const summary = NodeValidationService.getErrorSummary({
        isValid: true,
        errors: [],
        errorCount: 0,
        warningCount: 1,
      });
      expect(summary).toBe('1 warning');
    });

    it('should return plural warnings text', () => {
      const summary = NodeValidationService.getErrorSummary({
        isValid: true,
        errors: [],
        errorCount: 0,
        warningCount: 5,
      });
      expect(summary).toBe('5 warnings');
    });

    it('should combine errors and warnings', () => {
      const summary = NodeValidationService.getErrorSummary({
        isValid: false,
        errors: [],
        errorCount: 2,
        warningCount: 3,
      });
      expect(summary).toBe('2 errors, 3 warnings');
    });

    it('should combine singular error and singular warning', () => {
      const summary = NodeValidationService.getErrorSummary({
        isValid: false,
        errors: [],
        errorCount: 1,
        warningCount: 1,
      });
      expect(summary).toBe('1 error, 1 warning');
    });
  });

  // ====================================================================
  // validateAllNodes (delegates to InputValidationService)
  // ====================================================================

  describe('validateAllNodes', () => {
    it('should return valid for empty node list', () => {
      const result = NodeValidationService.validateAllNodes([]);
      expect(result.isValid).toBe(true);
      expect(result.errors).toHaveLength(0);
    });

    it('should detect missing required params across multiple nodes', () => {
      const node1 = makeToolNode({
        id: 'n1',
        parameters: [{ name: 'a', isRequired: true }],
        paramExpressions: {},
      });
      const node2 = makeToolNode({
        id: 'n2',
        parameters: [{ name: 'b', required: true }],
        paramExpressions: {},
      });
      const result = NodeValidationService.validateAllNodes([node1, node2]);
      expect(result.isValid).toBe(false);
      expect(result.errors.length).toBe(2);
    });

    it('should skip decision nodes', () => {
      const decision = makeDecisionTestNode({
        conditions: [{ id: 'c1', type: 'if', label: 'IF', expression: '' }],
      });
      const result = NodeValidationService.validateAllNodes([decision]);
      expect(result.isValid).toBe(true);
      expect(result.errors).toHaveLength(0);
    });

    it('should skip loop nodes', () => {
      const loop = makeLoopTestNode({ loopCondition: '' });
      const result = NodeValidationService.validateAllNodes([loop]);
      expect(result.isValid).toBe(true);
    });

    it('should skip note nodes', () => {
      const note: Node<BuilderNodeData> = {
        id: 'note-1',
        type: 'noteNode',
        position: { x: 0, y: 0 },
        data: {
          id: 'note-1',
          label: 'Note',
          kind: 'output' as any,
          badge: 'Note',
          noteText: 'A note',
        },
      };
      const result = NodeValidationService.validateAllNodes([note]);
      expect(result.isValid).toBe(true);
    });

    it('should NOT skip split nodes', () => {
      // Split nodes have toolData in practice; this test verifies they aren't skipped
      const split = makeSplitTestNode({});
      // splitNode doesn't have toolData, so InputValidationService won't find params to validate
      // but the node itself is not skipped from the iteration
      const result = NodeValidationService.validateAllNodes([split]);
      expect(result.isValid).toBe(true);
    });

    it('should detect warnings for optional params with defaults', () => {
      const node = makeToolNode({
        parameters: [{ name: 'limit', defaultValue: '10' }],
        paramExpressions: {},
      });
      const result = NodeValidationService.validateAllNodes([node]);
      expect(result.isValid).toBe(true);
      expect(result.warnings.length).toBe(1);
    });

    it('should include nodeId and nodeLabel in error details', () => {
      const node = makeToolNode({
        id: 'my-node-123',
        label: 'My API Call',
        parameters: [{ name: 'key', isRequired: true }],
        paramExpressions: {},
      });
      const result = NodeValidationService.validateAllNodes([node]);
      expect(result.errors[0].nodeId).toBe('my-node-123');
      expect(result.errors[0].nodeLabel).toBe('My API Call');
    });
  });
});

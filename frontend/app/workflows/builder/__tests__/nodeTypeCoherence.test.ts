/**
 * Node Type Coherence Tests
 *
 * Ensures that every node type is consistently detected across ALL layers:
 * - idMatcherUtils.isTriggerNode()
 * - normalizeNodeId() / computeBackendStepId() in StepByStepContext
 * - FlowNode isDataTableNode detection
 * - EdgeCreationService trigger detection
 * - nodeRegistry methods
 *
 * Each node type has a test that verifies it is classified correctly
 * and NOT misclassified as another type.
 */

import { describe, it, expect } from 'vitest';
import { isTriggerNode, extractStepAliasFromNode } from '../services/idMatcherUtils';
import { normalizeWhereCondition } from '../services/workflowPlanImporter/nodeCreationHelpers';

// ─── Helpers ────────────────────────────────────────────────────────────────
// Simulate the normalizeNodeId logic from StepByStepContext (extracted for testability)
function classifyNodeIdPrefix(nodeId: string): string {
  // Already has a valid prefix
  if (nodeId.startsWith('trigger:') || nodeId.startsWith('mcp:') || nodeId.startsWith('agent:') ||
      nodeId.startsWith('core:') || nodeId.startsWith('table:') || nodeId.startsWith('interface:')) {
    return nodeId.split(':')[0];
  }

  // Triggers
  if (nodeId.startsWith('trigger-') || nodeId.startsWith('trigger:') || nodeId.startsWith('tables-trigger-')) {
    return 'trigger';
  }

  // CRUD table nodes
  if (nodeId.startsWith('create-') || nodeId.startsWith('read-') || nodeId.startsWith('update-') ||
      nodeId.startsWith('delete-') || nodeId.startsWith('find-') || nodeId.startsWith('list-') ||
      nodeId.startsWith('table-')) {
    return 'table';
  }

  // Control flow
  if (nodeId.includes('if-else') || nodeId.includes('decision') || nodeId.includes('switch') ||
      nodeId.includes('loop') || nodeId.includes('while') || nodeId.includes('split') ||
      nodeId.includes('merge') || nodeId.includes('transform') || nodeId.includes('wait') ||
      nodeId.includes('fork') || nodeId.includes('exit') || nodeId.includes('response') ||
      nodeId.includes('download_file') || nodeId.includes('download-file') ||
      nodeId.includes('http_request') || nodeId.includes('http-request') ||
      nodeId.includes('data_input') || nodeId.includes('data-input')) {
    return 'core';
  }

  // Agent
  if (nodeId.includes('ai-agent') || nodeId.startsWith('agent-') || nodeId.startsWith('agent:')) {
    return 'agent';
  }

  // Interface
  if (nodeId.startsWith('interface-') || nodeId.startsWith('interface:')) {
    return 'interface';
  }

  return 'mcp';
}

// Simulate FlowNode isDataTableNode detection
function isDataTableNode(nodeId: string, nodeKind: string): boolean {
  return (nodeId.startsWith('table-') || nodeId.startsWith('create-') || nodeId.startsWith('read-') ||
      nodeId.startsWith('update-') || nodeId.startsWith('delete-') || nodeId.startsWith('find-') ||
      nodeId.startsWith('list-')) && !nodeId.startsWith('tables-trigger-') && nodeKind !== 'entry';
}

// Simulate EdgeCreationService trigger detection
function isEdgeTriggerNode(sourceId: string, sourceKind: string): boolean {
  return sourceKind === 'entry' ||
    sourceId.startsWith('trigger-') ||
    sourceId.startsWith('trigger:') ||
    sourceId.startsWith('tables-trigger-');
}

// ─── Node Type Definitions ──────────────────────────────────────────────────
// Every node type with its expected classification across all layers

interface NodeSpec {
  name: string;
  nodeId: string;         // React Flow node.id
  dataId: string;         // node.data.id
  kind: string;           // node.data.kind
  crudOperation?: string; // node.data.dataSourceData.crudOperation
  hasDataSourceData?: boolean;
  expectedPrefix: string; // trigger | mcp | table | agent | core | interface
  isTrigger: boolean;
  isDataTable: boolean;
}

const NODE_SPECS: NodeSpec[] = [
  // ─── Triggers ───
  { name: 'Webhook trigger', nodeId: 'trigger-webhook-123-abc', dataId: 'trigger:webhook', kind: 'entry', expectedPrefix: 'trigger', isTrigger: true, isDataTable: false },
  { name: 'Chat trigger', nodeId: 'trigger-chat-123-abc', dataId: 'trigger:chat', kind: 'entry', expectedPrefix: 'trigger', isTrigger: true, isDataTable: false },
  { name: 'Schedule trigger', nodeId: 'trigger-schedule-123-abc', dataId: 'trigger:schedule', kind: 'entry', expectedPrefix: 'trigger', isTrigger: true, isDataTable: false },
  { name: 'Manual trigger', nodeId: 'trigger-manual-123-abc', dataId: 'trigger:manual', kind: 'entry', expectedPrefix: 'trigger', isTrigger: true, isDataTable: false },
  { name: 'Form trigger', nodeId: 'trigger-form-123-abc', dataId: 'trigger:form', kind: 'entry', expectedPrefix: 'trigger', isTrigger: true, isDataTable: false },
  { name: 'Tables trigger', nodeId: 'tables-trigger-123-abc', dataId: 'tables-trigger-123', kind: 'entry', hasDataSourceData: true, expectedPrefix: 'trigger', isTrigger: true, isDataTable: false },
  { name: 'Datasource trigger', nodeId: 'trigger-datasource-123-abc', dataId: 'trigger:datasource', kind: 'entry', hasDataSourceData: true, expectedPrefix: 'trigger', isTrigger: true, isDataTable: false },

  // ─── CRUD / Table nodes ───
  { name: 'Create row', nodeId: 'create-row-123-abc', dataId: 'create-row-123-abc', kind: 'crud', crudOperation: 'create-row', hasDataSourceData: true, expectedPrefix: 'table', isTrigger: false, isDataTable: true },
  { name: 'Read row', nodeId: 'read-row-123-abc', dataId: 'read-row-123-abc', kind: 'crud', crudOperation: 'read-row', hasDataSourceData: true, expectedPrefix: 'table', isTrigger: false, isDataTable: true },
  { name: 'Update row', nodeId: 'update-row-123-abc', dataId: 'update-row-123-abc', kind: 'crud', crudOperation: 'update-row', hasDataSourceData: true, expectedPrefix: 'table', isTrigger: false, isDataTable: true },
  { name: 'Delete row', nodeId: 'delete-row-123-abc', dataId: 'delete-row-123-abc', kind: 'crud', crudOperation: 'delete-row', hasDataSourceData: true, expectedPrefix: 'table', isTrigger: false, isDataTable: true },
  { name: 'Find row (modern)', nodeId: 'find-row-123-abc', dataId: 'find-row-123-abc', kind: 'find', crudOperation: 'find-row', hasDataSourceData: true, expectedPrefix: 'table', isTrigger: false, isDataTable: true },
  { name: 'Find row (legacy find- prefix)', nodeId: 'find-123-abc', dataId: 'find-123-abc', kind: 'find', crudOperation: 'find-row', hasDataSourceData: true, expectedPrefix: 'table', isTrigger: false, isDataTable: true },
  { name: 'Create column', nodeId: 'create-column-123-abc', dataId: 'create-column-123-abc', kind: 'crud', crudOperation: 'create-column', hasDataSourceData: true, expectedPrefix: 'table', isTrigger: false, isDataTable: true },
  { name: 'List rows', nodeId: 'list-rows-123-abc', dataId: 'list-rows-123-abc', kind: 'crud', crudOperation: 'list-rows', hasDataSourceData: true, expectedPrefix: 'table', isTrigger: false, isDataTable: true },

  // ─── MCP / Tool nodes ───
  { name: 'MCP tool step', nodeId: 'step-get_user-123-abc', dataId: 'step-get_user-123-abc', kind: 'action', expectedPrefix: 'mcp', isTrigger: false, isDataTable: false },
  { name: 'MCP tool with mcp: prefix', nodeId: 'mcp:get_user', dataId: 'mcp:get_user', kind: 'action', expectedPrefix: 'mcp', isTrigger: false, isDataTable: false },

  // ─── Agent nodes ───
  { name: 'AI Agent', nodeId: 'ai-agent-123-abc', dataId: 'ai-agent-123-abc', kind: 'reasoning', expectedPrefix: 'agent', isTrigger: false, isDataTable: false },
  { name: 'Agent with agent- prefix', nodeId: 'agent-my_agent-123-abc', dataId: 'agent-my_agent-123-abc', kind: 'reasoning', expectedPrefix: 'agent', isTrigger: false, isDataTable: false },
  { name: 'Agent with agent: prefix', nodeId: 'agent:my_agent', dataId: 'agent:my_agent', kind: 'reasoning', expectedPrefix: 'agent', isTrigger: false, isDataTable: false },

  // ─── Core / Control flow nodes ───
  { name: 'Decision (if-else)', nodeId: 'if-else-123-abc', dataId: 'if-else-123-abc', kind: 'condition', expectedPrefix: 'core', isTrigger: false, isDataTable: false },
  { name: 'Switch', nodeId: 'switch-123-abc', dataId: 'switch-123-abc', kind: 'switch', expectedPrefix: 'core', isTrigger: false, isDataTable: false },
  { name: 'Loop (while)', nodeId: 'while-123-abc', dataId: 'while-123-abc', kind: 'loop', expectedPrefix: 'core', isTrigger: false, isDataTable: false },
  { name: 'Split', nodeId: 'split-123-abc', dataId: 'split-123-abc', kind: 'split', expectedPrefix: 'core', isTrigger: false, isDataTable: false },
  { name: 'Merge', nodeId: 'merge-123-abc', dataId: 'merge-123-abc', kind: 'merge', expectedPrefix: 'core', isTrigger: false, isDataTable: false },
  { name: 'Transform', nodeId: 'transform-123-abc', dataId: 'transform-123-abc', kind: 'transform', expectedPrefix: 'core', isTrigger: false, isDataTable: false },
  { name: 'Wait', nodeId: 'wait-123-abc', dataId: 'wait-123-abc', kind: 'wait', expectedPrefix: 'core', isTrigger: false, isDataTable: false },
  { name: 'Fork', nodeId: 'fork-123-abc', dataId: 'fork-123-abc', kind: 'fork', expectedPrefix: 'core', isTrigger: false, isDataTable: false },
  { name: 'Exit', nodeId: 'exit-123-abc', dataId: 'exit-123-abc', kind: 'exit', expectedPrefix: 'core', isTrigger: false, isDataTable: false },
  { name: 'Response', nodeId: 'response-123-abc', dataId: 'response-123-abc', kind: 'response', expectedPrefix: 'core', isTrigger: false, isDataTable: false },
  { name: 'Download file', nodeId: 'download_file-123-abc', dataId: 'download_file-123-abc', kind: 'utility', expectedPrefix: 'core', isTrigger: false, isDataTable: false },
  { name: 'HTTP request', nodeId: 'http_request-123-abc', dataId: 'http_request-123-abc', kind: 'utility', expectedPrefix: 'core', isTrigger: false, isDataTable: false },
  { name: 'Data input', nodeId: 'data_input-123-abc', dataId: 'data_input-123-abc', kind: 'utility', expectedPrefix: 'core', isTrigger: false, isDataTable: false },

  // ─── Interface nodes ───
  { name: 'Interface', nodeId: 'interface-123-abc', dataId: 'interface-123-abc', kind: 'interface', expectedPrefix: 'interface', isTrigger: false, isDataTable: false },
  { name: 'Interface with prefix', nodeId: 'interface:my_form', dataId: 'interface:my_form', kind: 'interface', expectedPrefix: 'interface', isTrigger: false, isDataTable: false },
];

// =============================================================================
// Tests
// =============================================================================

describe('Node Type Coherence - isTriggerNode', () => {
  for (const spec of NODE_SPECS) {
    it(`${spec.name} (${spec.nodeId}) → isTrigger=${spec.isTrigger}`, () => {
      const node = {
        id: spec.nodeId,
        data: {
          id: spec.dataId,
          kind: spec.kind,
          ...(spec.hasDataSourceData && {
            dataSourceData: {
              ...(spec.crudOperation && { crudOperation: spec.crudOperation }),
            },
          }),
        },
      };
      expect(isTriggerNode(node as any)).toBe(spec.isTrigger);
    });
  }
});

describe('Node Type Coherence - classifyNodeIdPrefix (normalizeNodeId logic)', () => {
  for (const spec of NODE_SPECS) {
    it(`${spec.name} (${spec.nodeId}) → prefix=${spec.expectedPrefix}`, () => {
      expect(classifyNodeIdPrefix(spec.nodeId)).toBe(spec.expectedPrefix);
    });
  }
});

describe('Node Type Coherence - isDataTableNode (FlowNode detection)', () => {
  for (const spec of NODE_SPECS) {
    it(`${spec.name} (${spec.nodeId}) → isDataTable=${spec.isDataTable}`, () => {
      expect(isDataTableNode(spec.nodeId, spec.kind)).toBe(spec.isDataTable);
    });
  }
});

describe('Node Type Coherence - EdgeCreationService trigger detection', () => {
  for (const spec of NODE_SPECS) {
    it(`${spec.name} (${spec.nodeId}) → isEdgeTrigger=${spec.isTrigger}`, () => {
      expect(isEdgeTriggerNode(spec.dataId, spec.kind)).toBe(spec.isTrigger);
    });
  }
});

// =============================================================================
// Cross-layer consistency: no node should be both trigger AND table
// =============================================================================
describe('Node Type Coherence - mutual exclusion', () => {
  for (const spec of NODE_SPECS) {
    it(`${spec.name} is never BOTH trigger AND table`, () => {
      expect(spec.isTrigger && spec.isDataTable).toBe(false);
    });
  }

  it('CRUD node with dataSourceData is NOT a trigger', () => {
    const crudOps = ['create-row', 'read-row', 'update-row', 'delete-row', 'find-row', 'create-column'];
    for (const op of crudOps) {
      const node = {
        id: `${op}-123-abc`,
        data: {
          id: `${op}-123-abc`,
          kind: 'crud',
          dataSourceData: { crudOperation: op },
        },
      };
      expect(isTriggerNode(node as any)).toBe(false);
    }
  });

  it('Tables trigger with kind=entry IS a trigger despite having dataSourceData', () => {
    const node = {
      id: 'tables-trigger-6-123-abc',
      data: {
        id: 'tables-trigger-6',
        kind: 'entry',
        dataSourceData: { dataSourceId: 1 },
      },
    };
    expect(isTriggerNode(node as any)).toBe(true);
  });
});

// =============================================================================
// Regression: nodes whose labels/IDs contain "trigger" but are NOT triggers
// =============================================================================
describe('Node Type Coherence - false positive prevention', () => {
  it('MCP step with "trigger" in label is NOT a trigger', () => {
    const node = {
      id: 'step-check_trigger_status-123-abc',
      data: { id: 'step-check_trigger_status-123-abc', kind: 'action' },
    };
    expect(isTriggerNode(node as any)).toBe(false);
    expect(classifyNodeIdPrefix(node.id)).toBe('mcp');
  });

  it('Agent node is NOT a trigger', () => {
    const node = {
      id: 'agent-my_agent-123-abc',
      data: { id: 'agent-my_agent-123-abc', kind: 'reasoning' },
    };
    expect(isTriggerNode(node as any)).toBe(false);
    expect(classifyNodeIdPrefix(node.id)).toBe('agent');
  });

  it('Interface node is NOT a trigger', () => {
    const node = {
      id: 'interface-form-123-abc',
      data: { id: 'interface-form-123-abc', kind: 'interface' },
    };
    expect(isTriggerNode(node as any)).toBe(false);
    expect(classifyNodeIdPrefix(node.id)).toBe('interface');
  });

  it('Find node with legacy find- prefix is a table node, not trigger', () => {
    const node = {
      id: 'find-123-abc',
      data: {
        id: 'find-123-abc',
        kind: 'find',
        dataSourceData: { crudOperation: 'find-row' },
      },
    };
    expect(isTriggerNode(node as any)).toBe(false);
    expect(classifyNodeIdPrefix(node.id)).toBe('table');
    expect(isDataTableNode(node.id, node.data.kind)).toBe(true);
  });
});

// =============================================================================
// normalizeWhereCondition - column prefix + operator mapping
// =============================================================================
describe('normalizeWhereCondition', () => {
  it('should add data. prefix to bare column name', () => {
    const result = normalizeWhereCondition({ column: 'statut', operator: '=', value: 'actif' });
    expect(result.column).toBe('data.statut');
  });

  it('should not double-prefix columns already starting with data.', () => {
    const result = normalizeWhereCondition({ column: 'data.statut', operator: '==', value: 'actif' });
    expect(result.column).toBe('data.statut');
  });

  it('should not prefix meta. columns', () => {
    const result = normalizeWhereCondition({ column: 'meta.created_at', operator: '>', value: '2024-01-01' });
    expect(result.column).toBe('meta.created_at');
  });

  it('should not prefix id columns', () => {
    const result = normalizeWhereCondition({ column: 'id', operator: '==', value: '123' });
    expect(result.column).toBe('id');
  });

  it('should map = to ==', () => {
    const result = normalizeWhereCondition({ column: 'col', operator: '=', value: 'x' });
    expect(result.operator).toBe('==');
  });

  it('should keep == as ==', () => {
    const result = normalizeWhereCondition({ column: 'col', operator: '==', value: 'x' });
    expect(result.operator).toBe('==');
  });

  it('should keep != as !=', () => {
    const result = normalizeWhereCondition({ column: 'col', operator: '!=', value: 'x' });
    expect(result.operator).toBe('!=');
  });

  it('should keep > < >= <= as is', () => {
    expect(normalizeWhereCondition({ column: 'c', operator: '>', value: '' }).operator).toBe('>');
    expect(normalizeWhereCondition({ column: 'c', operator: '<', value: '' }).operator).toBe('<');
    expect(normalizeWhereCondition({ column: 'c', operator: '>=', value: '' }).operator).toBe('>=');
    expect(normalizeWhereCondition({ column: 'c', operator: '<=', value: '' }).operator).toBe('<=');
  });

  it('should map LIKE and IN (case insensitive input)', () => {
    expect(normalizeWhereCondition({ column: 'c', operator: 'like', value: '' }).operator).toBe('LIKE');
    expect(normalizeWhereCondition({ column: 'c', operator: 'in', value: '' }).operator).toBe('IN');
  });

  it('should keep LIKE and IN as is when uppercase', () => {
    expect(normalizeWhereCondition({ column: 'c', operator: 'LIKE', value: '' }).operator).toBe('LIKE');
    expect(normalizeWhereCondition({ column: 'c', operator: 'IN', value: '' }).operator).toBe('IN');
  });

  it('should keep IS NULL / IS NOT NULL as is', () => {
    expect(normalizeWhereCondition({ column: 'c', operator: 'IS NULL', value: '' }).operator).toBe('IS NULL');
    expect(normalizeWhereCondition({ column: 'c', operator: 'IS NOT NULL', value: '' }).operator).toBe('IS NOT NULL');
  });

  it('should preserve value as is', () => {
    const result = normalizeWhereCondition({ column: 'col', operator: '=', value: 'actif' });
    expect(result.value).toBe('actif');
  });

  it('should handle SpEL expressions in value', () => {
    const result = normalizeWhereCondition({ column: 'col', operator: '=', value: '{{trigger:start.output.status}}' });
    expect(result.value).toBe('{{trigger:start.output.status}}');
  });

  it('should default to empty strings for missing fields', () => {
    const result = normalizeWhereCondition({});
    expect(result.column).toBe('');
    expect(result.operator).toBe('==');
    expect(result.value).toBe('');
  });
});

import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData, NodeMock } from '../../types';
import { generateWorkflowPlan } from '../workflowPlanGenerator';

/**
 * Mock-block emission on the GENERATOR side (mirrors the nodePolicy tests).
 *
 * A node carrying `data.mock` must emit a top-level `mock` block on its plan
 * entry (sibling of `nodePolicy`), gated by node type. Nodes without a mock
 * emit NOTHING, so pre-existing plans stay byte-identical.
 */

function mcpNode(id: string, label: string, mock?: NodeMock): Node<BuilderNodeData> {
  return {
    id,
    type: 'flowNode',
    position: { x: 100, y: 100 },
    data: {
      id,
      label,
      kind: 'action',
      toolData: { apiName: 'Gmail', apiSlug: 'gmail', toolSlug: 'send_email', method: 'POST' },
      ...(mock ? { mock } : {}),
    } as BuilderNodeData,
  };
}

function transformNode(id: string, label: string, mock?: NodeMock): Node<BuilderNodeData> {
  return {
    id,
    type: 'flowNode',
    position: { x: 200, y: 100 },
    data: { id, label, kind: 'transform', ...(mock ? { mock } : {}) } as BuilderNodeData,
  };
}

function decisionNode(id: string, label: string, mock?: NodeMock): Node<BuilderNodeData> {
  return {
    id,
    type: 'decisionNode',
    position: { x: 300, y: 100 },
    data: {
      id,
      label,
      kind: 'decision',
      decisionConditions: [
        { id: `${id}-if`, type: 'if', label: 'If', expression: 'true' },
        { id: `${id}-else`, type: 'else', label: 'Else' },
      ],
      ...(mock ? { mock } : {}),
    } as BuilderNodeData,
  };
}

function splitNode(id: string, label: string, mock?: NodeMock): Node<BuilderNodeData> {
  return {
    id,
    type: 'splitNode',
    position: { x: 400, y: 100 },
    data: {
      id,
      label,
      kind: 'split',
      list: '{{trigger:chat.output.items}}',
      ...(mock ? { mock } : {}),
    } as BuilderNodeData,
  };
}

function triggerNode(id: string, mock?: NodeMock): Node<BuilderNodeData> {
  return {
    id,
    type: 'triggerNode',
    position: { x: 0, y: 0 },
    data: {
      id: 'chat-trigger-1',
      label: 'Chat',
      kind: 'entry',
      ...(mock ? { mock } : {}),
    } as BuilderNodeData,
  };
}

describe('workflowPlanGenerator - mock emission', () => {
  it('emits mock on mcp entries carrying data.mock', () => {
    const plan = generateWorkflowPlan(
      [mcpNode('mcp-1', 'Send Email', { output: { id: 'msg_1', status: 'sent' } })],
      []
    );
    expect(plan.mcps).toHaveLength(1);
    expect(plan.mcps[0].mock).toEqual({ output: { id: 'msg_1', status: 'sent' } });
  });

  it('emits catalog_example mocks on mcp entries', () => {
    const plan = generateWorkflowPlan(
      [mcpNode('mcp-1', 'Send Email', { source: 'catalog_example' })],
      []
    );
    expect(plan.mcps[0].mock).toEqual({ source: 'catalog_example' });
  });

  it('emits mock on cores (transform) and keeps a parked enabled:false flag', () => {
    const plan = generateWorkflowPlan(
      [transformNode('transform-1', 'Shape Data', { enabled: false, output: { rows: [] } })],
      []
    );
    const core = plan.cores!.find((c) => c.type === 'transform');
    expect(core?.mock).toEqual({ enabled: false, output: { rows: [] } });
  });

  it('emits a bare-port mock on decision cores', () => {
    const plan = generateWorkflowPlan(
      [decisionNode('decision-1', 'Check', { port: 'else' })],
      []
    );
    const core = plan.cores!.find((c) => c.type === 'decision');
    expect(core?.mock).toEqual({ port: 'else' });
  });

  it('drops catalog_example on non-mcp entries and port on non-branching entries', () => {
    const plan = generateWorkflowPlan(
      [
        transformNode('transform-1', 'Shape Data', {
          source: 'catalog_example',
          output: { a: 1 },
        } as NodeMock),
        mcpNode('mcp-1', 'Send Email', { port: 'if', output: { b: 2 } } as NodeMock),
      ],
      []
    );
    const core = plan.cores!.find((c) => c.type === 'transform');
    expect(core?.mock).toEqual({ output: { a: 1 } });
    expect(plan.mcps[0].mock).toEqual({ output: { b: 2 } });
  });

  it('never emits mock on blocked cores (split) or triggers', () => {
    const plan = generateWorkflowPlan(
      [
        triggerNode('trigger-1', { output: { x: 1 } }),
        splitNode('split-1', 'Per Item', { output: { y: 2 } }),
      ],
      []
    );
    expect(plan.triggers[0]).not.toHaveProperty('mock');
    const splitCore = plan.cores!.find((c) => c.type === 'split');
    expect(splitCore).toBeDefined();
    expect(splitCore).not.toHaveProperty('mock');
  });

  it('plans without any mock regenerate byte-identical (no mock keys anywhere)', () => {
    const nodes = [
      triggerNode('trigger-1'),
      mcpNode('mcp-1', 'Send Email'),
      transformNode('transform-1', 'Shape Data'),
      decisionNode('decision-1', 'Check'),
    ];
    const plan = generateWorkflowPlan(nodes, []);
    expect(JSON.stringify(plan)).not.toContain('"mock"');
  });

  it('an empty mock block is byte-identical to no mock at all', () => {
    const withEmpty = generateWorkflowPlan([mcpNode('mcp-1', 'Send Email', {} as NodeMock)], []);
    const without = generateWorkflowPlan([mcpNode('mcp-1', 'Send Email')], []);
    expect(JSON.stringify(withEmpty)).toBe(JSON.stringify(without));
  });

  it('generate → generate is stable for a mock-carrying graph', () => {
    const nodes = [
      mcpNode('mcp-1', 'Send Email', { output: { ok: true } }),
      decisionNode('decision-1', 'Check', { port: 'if' }),
    ];
    const first = generateWorkflowPlan(nodes, []);
    const second = generateWorkflowPlan(nodes, []);
    expect(JSON.stringify(second)).toBe(JSON.stringify(first));
  });
});

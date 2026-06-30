import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData, NodePolicy } from '../../types';
import { generateWorkflowPlan } from '../workflowPlanGenerator';

/**
 * Regression tests for the nodePolicy round-trip on the GENERATOR side.
 *
 * Before this feature, workflowPlanGenerator silently DROPPED any nodePolicy
 * present on builder node data when regenerating the plan. It must now emit
 * the block on every executable entry type - and emit NOTHING when the
 * policy is default, so pre-existing plans stay byte-identical.
 */

function mcpNode(id: string, label: string, nodePolicy?: NodePolicy): Node<BuilderNodeData> {
  return {
    id,
    type: 'flowNode',
    position: { x: 100, y: 100 },
    data: {
      id,
      label,
      kind: 'action',
      toolData: { apiName: 'Gmail', apiSlug: 'gmail', toolSlug: 'send_email', method: 'POST' },
      ...(nodePolicy ? { nodePolicy } : {}),
    } as BuilderNodeData,
  };
}

function transformNode(id: string, label: string, nodePolicy?: NodePolicy): Node<BuilderNodeData> {
  return {
    id,
    type: 'flowNode',
    position: { x: 200, y: 100 },
    data: { id, label, kind: 'transform', ...(nodePolicy ? { nodePolicy } : {}) } as BuilderNodeData,
  };
}

function decisionNode(id: string, label: string, nodePolicy?: NodePolicy): Node<BuilderNodeData> {
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
      ...(nodePolicy ? { nodePolicy } : {}),
    } as BuilderNodeData,
  };
}

function splitNode(id: string, label: string, nodePolicy?: NodePolicy): Node<BuilderNodeData> {
  return {
    id,
    type: 'splitNode',
    position: { x: 400, y: 100 },
    data: {
      id,
      label,
      kind: 'split',
      list: '{{trigger:chat.output.items}}',
      ...(nodePolicy ? { nodePolicy } : {}),
    } as BuilderNodeData,
  };
}

function mergeNode(id: string, label: string, nodePolicy?: NodePolicy): Node<BuilderNodeData> {
  return {
    id,
    type: 'mergeNode',
    position: { x: 500, y: 100 },
    data: { id, label, kind: 'merge', ...(nodePolicy ? { nodePolicy } : {}) } as BuilderNodeData,
  };
}

function triggerNode(id: string, nodePolicy?: NodePolicy): Node<BuilderNodeData> {
  return {
    id,
    type: 'triggerNode',
    position: { x: 0, y: 0 },
    data: {
      id: 'chat-trigger-1',
      label: 'Chat',
      kind: 'entry',
      ...(nodePolicy ? { nodePolicy } : {}),
    } as BuilderNodeData,
  };
}

describe('workflowPlanGenerator - nodePolicy emission', () => {
  it('emits nodePolicy on mcp entries (was previously dropped on regeneration)', () => {
    const plan = generateWorkflowPlan(
      [mcpNode('mcp-1', 'Send Email', { retryCount: 2, retryBackoffMs: 1500 })],
      []
    );
    expect(plan.mcps).toHaveLength(1);
    expect(plan.mcps[0].nodePolicy).toEqual({ retryCount: 2, retryBackoffMs: 1500 });
  });

  it('emits nodePolicy on cores from the step processor (transform)', () => {
    const plan = generateWorkflowPlan(
      [transformNode('transform-1', 'Shape Data', { continueOnFailure: true })],
      []
    );
    const core = plan.cores!.find((c) => c.type === 'transform');
    expect(core?.nodePolicy).toEqual({ continueOnFailure: true });
  });

  it('emits nodePolicy on cores registered by the edge processor (split)', () => {
    const plan = generateWorkflowPlan(
      [splitNode('split-1', 'Per Item', { retryCount: 1, timeoutMs: 5000 })],
      []
    );
    const core = plan.cores!.find((c) => c.type === 'split');
    expect(core?.nodePolicy).toEqual({ retryCount: 1, timeoutMs: 5000 });
  });

  it('strips continueOnFailure on decision nodes (backend would reject the plan)', () => {
    const plan = generateWorkflowPlan(
      [decisionNode('decision-1', 'Check', { retryCount: 1, continueOnFailure: true })],
      []
    );
    const core = plan.cores!.find((c) => c.type === 'decision');
    expect(core?.nodePolicy).toEqual({ retryCount: 1 });
  });

  it('strips executeOnce on split nodes and omits the block when nothing is left', () => {
    const stripped = generateWorkflowPlan(
      [splitNode('split-1', 'Per Item', { executeOnce: true, timeoutMs: 5000 })],
      []
    );
    expect(stripped.cores!.find((c) => c.type === 'split')?.nodePolicy).toEqual({ timeoutMs: 5000 });

    const omitted = generateWorkflowPlan(
      [mergeNode('merge-1', 'Join', { executeOnce: true })],
      []
    );
    const mergeCore = omitted.cores!.find((c) => c.type === 'merge');
    expect(mergeCore).toBeDefined();
    expect(mergeCore).not.toHaveProperty('nodePolicy');
  });

  it('never emits nodePolicy on triggers', () => {
    const plan = generateWorkflowPlan(
      [triggerNode('trigger-1', { retryCount: 3 })],
      []
    );
    expect(plan.triggers).toHaveLength(1);
    expect(plan.triggers[0]).not.toHaveProperty('nodePolicy');
  });

  it('plans without any policy regenerate byte-identical (no nodePolicy keys anywhere)', () => {
    const nodes = [
      triggerNode('trigger-1'),
      mcpNode('mcp-1', 'Send Email'),
      transformNode('transform-1', 'Shape Data'),
      decisionNode('decision-1', 'Check'),
    ];
    const plan = generateWorkflowPlan(nodes, []);
    expect(JSON.stringify(plan)).not.toContain('nodePolicy');
  });

  it('an explicit all-default policy is byte-identical to no policy at all', () => {
    const withDefaults = generateWorkflowPlan(
      [
        mcpNode('mcp-1', 'Send Email', {
          retryCount: 0,
          retryBackoffMs: 0,
          continueOnFailure: false,
          timeoutMs: 0,
          executeOnce: false,
        }),
      ],
      []
    );
    const without = generateWorkflowPlan([mcpNode('mcp-1', 'Send Email')], []);
    expect(JSON.stringify(withDefaults)).toBe(JSON.stringify(without));
  });

  it('generate → generate is stable for a policy-carrying graph', () => {
    const nodes = [
      mcpNode('mcp-1', 'Send Email', { retryCount: 2, retryBackoffMs: 1000 }),
      splitNode('split-1', 'Per Item', { timeoutMs: 9000 }),
    ];
    const first = generateWorkflowPlan(nodes, []);
    const second = generateWorkflowPlan(nodes, []);
    expect(JSON.stringify(second)).toBe(JSON.stringify(first));
  });
});

import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { generateWorkflowPlan } from '../workflowPlanGenerator';

/**
 * `layoutDirection` is the workflow's rendering identity in the plan (its DB copy):
 * the builder stamps the active reading direction on save so it survives version /
 * publish / share / clone and seeds the canvas on the next load.
 *
 * The key is written ONLY when a direction is passed, so callers that don't care
 * (and every pre-feature plan) stay byte-identical, i.e. the key is simply absent.
 */

function mcpNode(id: string, label: string): Node<BuilderNodeData> {
  return {
    id,
    type: 'flowNode',
    position: { x: 100, y: 100 },
    data: {
      id,
      label,
      kind: 'action',
      toolData: { apiName: 'Gmail', apiSlug: 'gmail', toolSlug: 'send_email', method: 'POST' },
    } as BuilderNodeData,
  };
}

describe('generateWorkflowPlan - layoutDirection', () => {
  it('stamps layoutDirection="vertical" when passed', () => {
    const plan = generateWorkflowPlan([mcpNode('a', 'Send')], [], 'vertical');
    expect(plan.layoutDirection).toBe('vertical');
  });

  it('stamps layoutDirection="horizontal" when passed', () => {
    const plan = generateWorkflowPlan([mcpNode('a', 'Send')], [], 'horizontal');
    expect(plan.layoutDirection).toBe('horizontal');
  });

  it('omits the key entirely when no direction is passed (byte-identical for legacy callers)', () => {
    const plan = generateWorkflowPlan([mcpNode('a', 'Send')], []);
    expect('layoutDirection' in plan).toBe(false);
  });
});

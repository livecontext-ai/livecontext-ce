import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';

import { processAgents } from '../../../utils/agentProcessor';
import { createPlanGeneratorContext } from '../../../utils/planGeneratorContext';
import { createAgentNodes } from '../AgentNodeCreator';
import type { BuilderNodeData } from '../../../types';

// End-to-end reproduction of the reported bug: a browser_agent node whose model
// was changed in the inspector must still show the NEW model after the plan is
// exported and re-imported (the "reload"). Pre-fix, the importer rebuilt only
// paramExpressions and never restored data.params, so on reload the picker fell
// back to the stale top-level data.model and every params.llm sub-field
// (max_steps, ...) reverted to its default.

function browserNodeAfterPick(): Node<BuilderNodeData> {
  // Shape produced by BrowserAgentParametersForm.handleModelPick after the user
  // picked openai/gpt-4o (top-level provider/model + params.llm + the
  // stringified paramExpressions mirror), on a node that also carries a custom
  // max_steps.
  const llm = { provider: 'openai', model: 'gpt-4o', max_steps: 40 };
  return {
    id: 'n-browser',
    type: 'browserAgentNode',
    position: { x: 0, y: 0 },
    data: {
      id: 'browser_agent-1',
      label: 'Browse',
      kind: 'browser_agent',
      agentType: 'browser_agent',
      provider: 'openai',
      model: 'gpt-4o',
      params: { llm, task: 'do the thing' },
      paramExpressions: {
        llm: JSON.stringify(llm),
        task: 'do the thing',
      },
    } as unknown as BuilderNodeData,
  };
}

function exportThenImport(node: Node<BuilderNodeData>): Node<BuilderNodeData> {
  const ctx = createPlanGeneratorContext([node], []);
  processAgents(ctx);
  const exported = ctx.plan.agents!;
  expect(exported).toHaveLength(1);
  const { nodes } = createAgentNodes(exported, 0, 0, 0);
  return nodes[0];
}

describe('browser_agent model round-trip (export -> import / reload)', () => {
  it('re-imports the picked provider/model into the top-level display fields', () => {
    const reimported = exportThenImport(browserNodeAfterPick());
    expect(reimported.data.provider).toBe('openai');
    expect(reimported.data.model).toBe('gpt-4o');
  });

  it('restores data.params.llm so llm sub-fields (max_steps) survive the reload', () => {
    const reimported = exportThenImport(browserNodeAfterPick());
    const params = (reimported.data as any).params;
    // Pre-fix regression: params was undefined here, so max_steps reverted to
    // the form default (25) on reload.
    expect(params).toBeDefined();
    expect(params.llm.provider).toBe('openai');
    expect(params.llm.model).toBe('gpt-4o');
    expect(params.llm.max_steps).toBe(40);
    // params.llm must be a real object, not the stringified plan form, so the
    // runner (params.llm must be a dict) and the inspector both read it.
    expect(typeof params.llm).toBe('object');
  });

  it('parses a stringified params.llm from the plan (backend/generator path) into a real object', () => {
    // A plan produced by the backend/generator carries params.llm as a JSON
    // STRING, not an object. The importer must JSON.parse it so the inspector
    // and the runner both receive a dict. This exercises the string branch of
    // the restore that tests 1-2 (object input) do not reach.
    const agentFromPlan: any = {
      graphNodeId: 'n-browser',
      type: 'browser_agent',
      label: 'Browse',
      provider: 'openai',
      model: 'gpt-4o',
      params: {
        llm: JSON.stringify({ provider: 'openai', model: 'gpt-4o', max_steps: 40 }),
        task: 'do the thing',
      },
    };

    const { nodes } = createAgentNodes([agentFromPlan], 0, 0, 0);
    const params = (nodes[0].data as any).params;

    expect(typeof params.llm).toBe('object');
    expect(params.llm.provider).toBe('openai');
    expect(params.llm.model).toBe('gpt-4o');
    expect(params.llm.max_steps).toBe(40);
    // A non-object scalar param passes through untouched.
    expect(params.task).toBe('do the thing');
  });
});

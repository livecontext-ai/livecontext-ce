import { describe, expect, it } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { createPlanGeneratorContext } from '../utils/planGeneratorContext';
import { processSteps } from '../utils/stepProcessor';

function makeMcpNode(toolData: Record<string, unknown>): Node<BuilderNodeData> {
  return {
    id: 'node-send-message',
    type: 'flowNode',
    position: { x: 120, y: 80 },
    data: {
      id: 'node-send-message',
      label: 'Send Message',
      kind: 'action',
      apiData: { apiSlug: 'slack' },
      toolData: {
        toolSlug: 'send-message',
        ...toolData,
      },
    } as BuilderNodeData,
  };
}

describe('stepProcessor credential export', () => {
  it('exports the workflow-selected user credential id', () => {
    const ctx = createPlanGeneratorContext([
      makeMcpNode({ selectedCredentialId: 42 }),
    ], []);

    processSteps(ctx);

    expect(ctx.plan.mcps).toHaveLength(1);
    expect(ctx.plan.mcps[0]).toMatchObject({
      id: 'slack/send-message',
      selectedCredentialId: 42,
    });
    expect(ctx.plan.mcps[0].platformCredentialId).toBeUndefined();
  });

  it('does not leak a saved user credential id when the step is platform-pinned', () => {
    const ctx = createPlanGeneratorContext([
      makeMcpNode({
        credentialSource: 'platform',
        platformCredentialId: 77,
        selectedCredentialId: 42,
      }),
    ], []);

    processSteps(ctx);

    expect(ctx.plan.mcps[0]).toMatchObject({
      credentialSource: 'platform',
      platformCredentialId: 77,
    });
    expect(ctx.plan.mcps[0].selectedCredentialId).toBeUndefined();
  });
});

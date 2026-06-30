import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NodeCreationService } from '../NodeCreationService';
import { generateWorkflowPlan } from '../../../utils/workflowPlanGenerator';

/**
 * Importer-side nodePolicy hydration + full round-trip.
 *
 * The plan-level `nodePolicy` block must be read back from every executable
 * entry (mcps / tables / agents / cores / interfaces) into the created
 * builder node's data, so that:
 *  - the inspector Settings section shows the saved policy, and
 *  - regenerating the plan from the imported graph emits the SAME block
 *    (generate → import → generate stability - previously the policy was
 *    silently dropped on regeneration).
 */

// Resolve the gmail tool with real-looking metadata so re-generated plans keep
// the step (the generator only emits nodes carrying toolData/apiData).
vi.mock('../ToolDataService', () => ({
  ToolDataService: {
    fetchToolsBatch: vi.fn().mockResolvedValue(new Map()),
    getFromBatchCache: vi.fn((toolId: string) =>
      toolId === 'gmail/send_email' || toolId === 'send_email'
        ? {
            toolData: { apiName: 'Gmail', apiSlug: 'gmail', toolSlug: 'send_email', method: 'POST' },
            apiData: { apiName: 'Gmail', apiSlug: 'gmail' },
          }
        : undefined
    ),
    fetchToolDataFromPlan: vi.fn().mockResolvedValue({}),
    fetchToolData: vi.fn().mockResolvedValue({}),
    fetchToolDataBySlug: vi.fn().mockResolvedValue({}),
    fetchDataSourceData: vi.fn().mockResolvedValue({}),
    clearCache: vi.fn(),
  },
}));

beforeEach(() => {
  vi.clearAllMocks();
});

describe('NodeCreationService - nodePolicy hydration', () => {
  it('hydrates an mcp entry policy onto the created step node (via graphNodeId)', async () => {
    const plan: any = {
      triggers: [],
      mcps: [
        {
          id: 'gmail/send_email',
          type: 'mcp',
          label: 'Send Email',
          graphNodeId: 'step-original-1',
          position: { x: 100, y: 100 },
          nodePolicy: { retryCount: 2, retryBackoffMs: 1500 },
        },
      ],
      edges: [],
    };

    const result = await NodeCreationService.createNodes(plan);
    const node = result.nodes.find((n) => n.id === 'step-original-1');
    expect(node).toBeDefined();
    expect(node!.data.nodePolicy).toEqual({ retryCount: 2, retryBackoffMs: 1500 });
  });

  it('hydrates a core entry policy onto the created control node', async () => {
    const plan: any = {
      triggers: [],
      mcps: [],
      cores: [
        {
          id: 'decision-1',
          type: 'decision',
          label: 'Check',
          position: { x: 200, y: 100 },
          decisionConditions: [
            { id: 'c-if', type: 'if', label: 'If', expression: 'true' },
            { id: 'c-else', type: 'else', label: 'Else' },
          ],
          nodePolicy: { retryCount: 1 },
        },
      ],
      edges: [],
    };

    const result = await NodeCreationService.createNodes(plan);
    const node = result.nodes.find((n) => n.type === 'decisionNode');
    expect(node).toBeDefined();
    expect(node!.data.nodePolicy).toEqual({ retryCount: 1 });
  });

  it('hydrates an interface entry policy via the interface id map', async () => {
    const plan: any = {
      triggers: [],
      mcps: [],
      interfaces: [
        {
          id: 'iface-uuid-1',
          label: 'Landing',
          position: { x: 300, y: 100 },
          nodePolicy: { timeoutMs: 60000 },
        },
      ],
      edges: [],
    };

    const result = await NodeCreationService.createNodes(plan);
    const node = result.nodes.find((n) => n.type === 'interfaceNode');
    expect(node).toBeDefined();
    expect(node!.data.nodePolicy).toEqual({ timeoutMs: 60000 });
  });

  it('falls back to the label map when the plan entry has no graphNodeId (hand-written plan)', async () => {
    const plan: any = {
      triggers: [],
      mcps: [
        {
          id: 'gmail/send_email',
          type: 'mcp',
          label: 'Send Email',
          position: { x: 100, y: 100 },
          nodePolicy: { continueOnFailure: true },
        },
      ],
      edges: [],
    };

    const result = await NodeCreationService.createNodes(plan);
    const node = result.nodes.find((n) => n.data.label === 'Send Email');
    expect(node).toBeDefined();
    expect(node!.data.nodePolicy).toEqual({ continueOnFailure: true });
  });

  it('does not set nodePolicy when the entry has none or it is all-default', async () => {
    const plan: any = {
      triggers: [],
      mcps: [
        { id: 'a/b', type: 'mcp', label: 'No Policy', graphNodeId: 'step-a', position: { x: 0, y: 0 } },
        {
          id: 'c/d',
          type: 'mcp',
          label: 'Default Policy',
          graphNodeId: 'step-b',
          position: { x: 0, y: 50 },
          nodePolicy: { retryCount: 0, continueOnFailure: false },
        },
      ],
      edges: [],
    };

    const result = await NodeCreationService.createNodes(plan);
    for (const id of ['step-a', 'step-b']) {
      const node = result.nodes.find((n) => n.id === id);
      expect(node).toBeDefined();
      expect(node!.data.nodePolicy).toBeUndefined();
    }
  });
});

describe('nodePolicy round-trip - generate → import → generate', () => {
  it('keeps the policy blocks identical across a full round-trip', async () => {
    const originalNodes: any[] = [
      {
        id: 'step-rt-1',
        type: 'flowNode',
        position: { x: 100, y: 100 },
        data: {
          id: 'step-rt-1',
          label: 'Send Email',
          kind: 'action',
          toolData: { apiName: 'Gmail', apiSlug: 'gmail', toolSlug: 'send_email', method: 'POST' },
          apiData: { apiName: 'Gmail', apiSlug: 'gmail' },
          nodePolicy: { retryCount: 2, retryBackoffMs: 1000 },
        },
      },
      {
        id: 'transform-rt-1',
        type: 'flowNode',
        position: { x: 250, y: 100 },
        data: {
          id: 'transform-rt-1',
          label: 'Shape Data',
          kind: 'transform',
          nodePolicy: { continueOnFailure: true, timeoutMs: 5000 },
        },
      },
      {
        id: 'decision-rt-1',
        type: 'decisionNode',
        position: { x: 400, y: 100 },
        data: {
          id: 'decision-rt-1',
          label: 'Check',
          kind: 'decision',
          decisionConditions: [
            { id: 'd-if', type: 'if', label: 'If', expression: 'true' },
            { id: 'd-else', type: 'else', label: 'Else' },
          ],
          nodePolicy: { retryCount: 1 },
        },
      },
    ];

    const firstPlan = generateWorkflowPlan(originalNodes, []);
    expect(firstPlan.mcps[0].nodePolicy).toEqual({ retryCount: 2, retryBackoffMs: 1000 });

    const imported = await NodeCreationService.createNodes(firstPlan as any);

    const importedStep = imported.nodes.find((n) => n.id === 'step-rt-1');
    const importedTransform = imported.nodes.find((n) => n.id === 'transform-rt-1');
    const importedDecision = imported.nodes.find((n) => n.id === 'decision-rt-1');
    expect(importedStep!.data.nodePolicy).toEqual({ retryCount: 2, retryBackoffMs: 1000 });
    expect(importedTransform!.data.nodePolicy).toEqual({ continueOnFailure: true, timeoutMs: 5000 });
    expect(importedDecision!.data.nodePolicy).toEqual({ retryCount: 1 });

    const secondPlan = generateWorkflowPlan(imported.nodes, []);
    expect(secondPlan.mcps).toHaveLength(1);
    expect(secondPlan.mcps[0].nodePolicy).toEqual(firstPlan.mcps[0].nodePolicy);
    expect(secondPlan.cores!.find((c) => c.type === 'transform')!.nodePolicy).toEqual(
      firstPlan.cores!.find((c) => c.type === 'transform')!.nodePolicy
    );
    expect(secondPlan.cores!.find((c) => c.type === 'decision')!.nodePolicy).toEqual(
      firstPlan.cores!.find((c) => c.type === 'decision')!.nodePolicy
    );
  });

  it('a plan without nodePolicy stays without nodePolicy across the round-trip', async () => {
    const nodes: any[] = [
      {
        id: 'step-clean-1',
        type: 'flowNode',
        position: { x: 100, y: 100 },
        data: {
          id: 'step-clean-1',
          label: 'Send Email',
          kind: 'action',
          toolData: { apiName: 'Gmail', apiSlug: 'gmail', toolSlug: 'send_email', method: 'POST' },
        },
      },
    ];

    const firstPlan = generateWorkflowPlan(nodes, []);
    expect(JSON.stringify(firstPlan)).not.toContain('nodePolicy');

    const imported = await NodeCreationService.createNodes(firstPlan as any);
    const secondPlan = generateWorkflowPlan(imported.nodes, []);
    expect(JSON.stringify(secondPlan)).not.toContain('nodePolicy');
  });
});

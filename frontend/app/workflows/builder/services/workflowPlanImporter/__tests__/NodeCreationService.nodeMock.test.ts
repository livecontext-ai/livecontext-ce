import { describe, it, expect, vi, beforeEach } from 'vitest';
import { NodeCreationService } from '../NodeCreationService';
import { generateWorkflowPlan } from '../../../utils/workflowPlanGenerator';

/**
 * Importer-side mock hydration + full round-trip (mirrors the nodePolicy
 * hydration tests).
 *
 * The plan-level `mock` block must be read back from every executable entry
 * (mcps / tables / agents / cores / interfaces) into the created builder
 * node's data so the inspector Mock section shows it and the generator
 * round-trips it (generate → import → generate stability).
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

describe('NodeCreationService - mock hydration', () => {
  it('hydrates an mcp entry mock onto the created step node (via graphNodeId)', async () => {
    const plan: any = {
      triggers: [],
      mcps: [
        {
          id: 'gmail/send_email',
          type: 'mcp',
          label: 'Send Email',
          graphNodeId: 'step-original-1',
          position: { x: 100, y: 100 },
          mock: { output: { id: 'msg_1', status: 'sent' } },
        },
      ],
      edges: [],
    };

    const result = await NodeCreationService.createNodes(plan);
    const node = result.nodes.find((n) => n.id === 'step-original-1');
    expect(node).toBeDefined();
    expect(node!.data.mock).toEqual({ output: { id: 'msg_1', status: 'sent' } });
  });

  it('hydrates a core entry mock (bare port) onto the created control node', async () => {
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
          mock: { port: 'else' },
        },
      ],
      edges: [],
    };

    const result = await NodeCreationService.createNodes(plan);
    const node = result.nodes.find((n) => n.type === 'decisionNode');
    expect(node).toBeDefined();
    expect(node!.data.mock).toEqual({ port: 'else' });
  });

  it('hydrates an interface entry mock via the interface id map', async () => {
    const plan: any = {
      triggers: [],
      mcps: [],
      interfaces: [
        {
          id: 'iface-uuid-1',
          label: 'Landing',
          position: { x: 300, y: 100 },
          mock: { output: { landing: { clicked: true } } },
        },
      ],
      edges: [],
    };

    const result = await NodeCreationService.createNodes(plan);
    const node = result.nodes.find((n) => n.type === 'interfaceNode');
    expect(node).toBeDefined();
    expect(node!.data.mock).toEqual({ output: { landing: { clicked: true } } });
  });

  it('hydrates both nodePolicy AND mock when an entry carries both blocks', async () => {
    const plan: any = {
      triggers: [],
      mcps: [
        {
          id: 'gmail/send_email',
          type: 'mcp',
          label: 'Send Email',
          graphNodeId: 'step-both-1',
          position: { x: 100, y: 100 },
          nodePolicy: { retryCount: 2 },
          mock: { source: 'catalog_example' },
        },
      ],
      edges: [],
    };

    const result = await NodeCreationService.createNodes(plan);
    const node = result.nodes.find((n) => n.id === 'step-both-1');
    expect(node!.data.nodePolicy).toEqual({ retryCount: 2 });
    expect(node!.data.mock).toEqual({ source: 'catalog_example' });
  });

  it('does not set mock when the entry has none or it is empty/invalid', async () => {
    const plan: any = {
      triggers: [],
      mcps: [
        { id: 'a/b', type: 'mcp', label: 'No Mock', graphNodeId: 'step-a', position: { x: 0, y: 0 } },
        {
          id: 'c/d',
          type: 'mcp',
          label: 'Empty Mock',
          graphNodeId: 'step-b',
          position: { x: 0, y: 50 },
          mock: {},
        },
        {
          id: 'e/f',
          type: 'mcp',
          label: 'Invalid Mock',
          graphNodeId: 'step-c',
          position: { x: 0, y: 100 },
          mock: { output: 'not-an-object' },
        },
      ],
      edges: [],
    };

    const result = await NodeCreationService.createNodes(plan);
    for (const id of ['step-a', 'step-b', 'step-c']) {
      const node = result.nodes.find((n) => n.id === id);
      expect(node).toBeDefined();
      expect(node!.data.mock).toBeUndefined();
    }
  });
});

describe('mock round-trip - generate → import → generate', () => {
  it('keeps the mock blocks identical across a full round-trip', async () => {
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
          mock: { output: { id: 'msg_1' }, enabled: false },
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
          mock: { port: 'if' },
        },
      },
    ];

    const firstPlan = generateWorkflowPlan(originalNodes, []);
    expect(firstPlan.mcps[0].mock).toEqual({ output: { id: 'msg_1' }, enabled: false });

    const imported = await NodeCreationService.createNodes(firstPlan as any);
    const importedStep = imported.nodes.find((n) => n.id === 'step-rt-1');
    const importedDecision = imported.nodes.find((n) => n.id === 'decision-rt-1');
    expect(importedStep!.data.mock).toEqual({ output: { id: 'msg_1' }, enabled: false });
    expect(importedDecision!.data.mock).toEqual({ port: 'if' });

    const secondPlan = generateWorkflowPlan(imported.nodes, []);
    expect(secondPlan.mcps[0].mock).toEqual(firstPlan.mcps[0].mock);
    expect(secondPlan.cores!.find((c) => c.type === 'decision')!.mock).toEqual(
      firstPlan.cores!.find((c) => c.type === 'decision')!.mock
    );
  });

  it('a plan without mock stays without mock across the round-trip', async () => {
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
    expect(JSON.stringify(firstPlan)).not.toContain('"mock"');

    const imported = await NodeCreationService.createNodes(firstPlan as any);
    const secondPlan = generateWorkflowPlan(imported.nodes, []);
    expect(JSON.stringify(secondPlan)).not.toContain('"mock"');
  });
});

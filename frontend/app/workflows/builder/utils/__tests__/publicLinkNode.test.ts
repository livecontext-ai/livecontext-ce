/**
 * public_link core node - plan wiring (frontend layer of the backend contract).
 *
 * The backend contract stores the public_link config in the GENERIC `params`
 * map ({ file, ttl_minutes, disposition }), unlike download_file which has a
 * dedicated `download` key. These tests pin:
 *  - export: builder data (publicLink* fields) -> plan cores[] entry
 *  - import: plan cores[] entry -> builder data
 *  - roundtrip: generate -> import -> generate leaves the core unchanged
 */
import { describe, it, expect, vi } from 'vitest';
import type { Node } from 'reactflow';
import { processTransformAndWaitNodes } from '../stepProcessor';
import { NodeCreationService } from '../../services/workflowPlanImporter/NodeCreationService';
import type { BuilderNodeData } from '../../types';

vi.mock('@/lib/api/api-client', () => ({
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), getTokenProvider: () => null },
}));
vi.mock('../../services/workflowPlanImporter/ToolDataService', () => ({
  ToolDataService: { fetchToolsBatch: vi.fn().mockResolvedValue(new Map()), getToolData: vi.fn() },
}));

function publicLinkNode(extraData: Record<string, unknown>): Node<BuilderNodeData> {
  return {
    id: 'public_link-1',
    type: 'flowNode',
    position: { x: 10, y: 20 },
    data: {
      id: 'public_link-1',
      label: 'Share Video',
      kind: 'public_link',
      ...extraData,
    } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

function runStepProcessor(node: Node<BuilderNodeData>) {
  const ctx: any = {
    nodes: [node],
    edges: [],
    plan: { cores: [] },
    stepLabelMap: new Map<string, string>(),
    stepPlanByNodeId: new Map<string, any>(),
  };
  processTransformAndWaitNodes(ctx);
  const core = ctx.plan.cores.find((c: any) => c.type === 'public_link');
  expect(core).toBeDefined();
  return core;
}

function importCoreNode(coreNode: any) {
  const result = (NodeCreationService as any).createCoreNodesInline(
    [coreNode],
    [],
    100,
    100,
  );
  return result.nodes[0];
}

describe('stepProcessor - public_link export (builder data -> plan params map)', () => {
  it('emits the config under the generic params map, NOT a dedicated key', () => {
    const core = runStepProcessor(publicLinkNode({
      publicLinkFile: '{{interface:card.output.video}}',
      publicLinkTtlMinutes: 60,
      publicLinkDisposition: 'attachment',
    }));

    expect(core.params).toEqual({
      file: '{{interface:card.output.video}}',
      ttl_minutes: 60,
      disposition: 'attachment',
    });
    // No download_file-style dedicated config key
    expect(core).not.toHaveProperty('publicLink');
    expect(core).not.toHaveProperty('download');
  });

  it('always emits params.file (empty string when unset) and omits optional params', () => {
    const core = runStepProcessor(publicLinkNode({}));
    expect(core.params.file).toBe('');
    expect(core.params).not.toHaveProperty('ttl_minutes');
    expect(core.params).not.toHaveProperty('disposition');
  });

  it('keeps ttl_minutes as a NUMBER in the plan', () => {
    const core = runStepProcessor(publicLinkNode({
      publicLinkFile: '{{core:download.output.file}}',
      publicLinkTtlMinutes: 240,
    }));
    expect(core.params.ttl_minutes).toBe(240);
    expect(typeof core.params.ttl_minutes).toBe('number');
  });

  it('uses core: label semantics (id, label, position preserved)', () => {
    const core = runStepProcessor(publicLinkNode({ publicLinkFile: '{{x}}' }));
    expect(core.id).toBe('public_link-1');
    expect(core.label).toBe('Share Video');
    expect(core.position).toEqual({ x: 10, y: 20 });
  });
});

describe('NodeCreationService - public_link import (plan params map -> builder data)', () => {
  it('reads file, ttl_minutes, and disposition from params', () => {
    const node = importCoreNode({
      type: 'public_link',
      label: 'Share Video',
      params: {
        file: '{{interface:card.output.video}}',
        ttl_minutes: 60,
        disposition: 'attachment',
      },
    });

    expect(node.data.kind).toBe('public_link');
    expect(node.type).toBe('flowNode');
    expect(node.data.publicLinkFile).toBe('{{interface:card.output.video}}');
    expect(node.data.publicLinkTtlMinutes).toBe(60);
    expect(node.data.publicLinkDisposition).toBe('attachment');
  });

  it('defaults cleanly when params are absent', () => {
    const node = importCoreNode({ type: 'public_link', label: 'Bare Link' });
    expect(node.data.publicLinkFile).toBe('');
    expect(node.data.publicLinkTtlMinutes).toBeUndefined();
    expect(node.data.publicLinkDisposition).toBeUndefined();
  });

  it('ignores a non-numeric ttl_minutes and an unknown disposition', () => {
    const node = importCoreNode({
      type: 'public_link',
      label: 'Weird Link',
      params: { file: '{{f}}', ttl_minutes: '60', disposition: 'render' },
    });
    expect(node.data.publicLinkTtlMinutes).toBeUndefined();
    expect(node.data.publicLinkDisposition).toBeUndefined();
  });

  it('generates a public_link- prefixed node id for detection when none is provided', () => {
    const node = importCoreNode({ type: 'public_link', label: 'Anon' });
    expect(node.id.startsWith('public_link-')).toBe(true);
    expect(node.data.id).toBe(node.id);
  });
});

describe('public_link plan roundtrip (generate -> import -> generate)', () => {
  it('the cores[] entry survives a full roundtrip unchanged', () => {
    const original = runStepProcessor(publicLinkNode({
      publicLinkFile: '{{interface:card.output.video}}',
      publicLinkTtlMinutes: 240,
      publicLinkDisposition: 'inline',
    }));

    const importedNode = importCoreNode({ ...original });
    const regenerated = runStepProcessor(importedNode);

    expect(regenerated.type).toBe(original.type);
    expect(regenerated.label).toBe(original.label);
    expect(regenerated.id).toBe(original.id);
    expect(regenerated.params).toEqual(original.params);
  });

  it('roundtrip without optional params does not invent them', () => {
    const original = runStepProcessor(publicLinkNode({
      publicLinkFile: '{{core:download.output.file}}',
    }));

    const importedNode = importCoreNode({ ...original });
    const regenerated = runStepProcessor(importedNode);

    expect(regenerated.params).toEqual(original.params);
    expect(regenerated.params).not.toHaveProperty('ttl_minutes');
    expect(regenerated.params).not.toHaveProperty('disposition');
  });
});

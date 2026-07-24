/**
 * media core node - plan wiring (frontend layer of the backend contract).
 *
 * Like public_link, the media config lives in the GENERIC `params` map
 * ({ operation, input/video/audio/tracks, ...contract-named options }).
 * These tests pin:
 *  - export: builder data (mediaOperation + mediaParams) -> plan cores[] entry
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

function mediaNode(extraData: Record<string, unknown>): Node<BuilderNodeData> {
  return {
    id: 'media-1',
    type: 'flowNode',
    position: { x: 10, y: 20 },
    data: {
      id: 'media-1',
      label: 'Add Soundtrack',
      kind: 'media',
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
  const core = ctx.plan.cores.find((c: any) => c.type === 'media');
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

describe('stepProcessor - media export (builder data -> plan params map)', () => {
  it('emits the config under the generic params map with the contract field names', () => {
    const core = runStepProcessor(mediaNode({
      mediaOperation: 'mux_audio',
      mediaParams: {
        video: '{{interface:card.output.video}}',
        audio: '{{core:download.output.file}}',
        volume: 250,
        loop: true,
      },
    }));

    expect(core.params).toEqual({
      operation: 'mux_audio',
      video: '{{interface:card.output.video}}',
      audio: '{{core:download.output.file}}',
      volume: 250,
      loop: true,
    });
    // No dedicated config key like download_file's `download`
    expect(core).not.toHaveProperty('media');
    expect(core).not.toHaveProperty('download');
  });

  it('always emits operation (empty when unset) and required file exprs, omitting defaults', () => {
    const bare = runStepProcessor(mediaNode({}));
    expect(bare.params).toEqual({ operation: '' });

    const probe = runStepProcessor(mediaNode({ mediaOperation: 'probe', mediaParams: {} }));
    expect(probe.params).toEqual({ operation: 'probe', input: '' });

    const mux = runStepProcessor(mediaNode({
      mediaOperation: 'mux_audio',
      mediaParams: { video: '{{v}}', audio: '{{a}}', volume: 100, audio_fit: 'pad', normalize: true },
    }));
    expect(mux.params).toEqual({ operation: 'mux_audio', video: '{{v}}', audio: '{{a}}' });
  });

  it('keeps numbers and booleans as real JSON types in the plan (never stringified)', () => {
    const core = runStepProcessor(mediaNode({
      mediaOperation: 'mix',
      mediaParams: {
        tracks: [
          { source: '{{a}}', id: 'voice', volume: 120 },
          { source: '{{b}}', offset_seconds: 2.5, duck_under: 'voice', duck_amount_db: 6, loop: true },
        ],
        normalize: -14,
      },
    }));
    expect(core.params.tracks).toHaveLength(2);
    expect(core.params.tracks[0].volume).toBe(120);
    expect(typeof core.params.tracks[0].volume).toBe('number');
    expect(core.params.tracks[1].loop).toBe(true);
    expect(typeof core.params.tracks[1].loop).toBe('boolean');
    expect(core.params.normalize).toBe(-14);
  });

  it('uses core: label semantics (id, label, position preserved)', () => {
    const core = runStepProcessor(mediaNode({ mediaOperation: 'probe', mediaParams: { input: '{{f}}' } }));
    expect(core.id).toBe('media-1');
    expect(core.label).toBe('Add Soundtrack');
    expect(core.position).toEqual({ x: 10, y: 20 });
  });
});

describe('NodeCreationService - media import (plan params map -> builder data)', () => {
  it('reads operation and the contract params into mediaOperation + mediaParams', () => {
    const node = importCoreNode({
      type: 'media',
      label: 'Add Soundtrack',
      params: {
        operation: 'mux_audio',
        video: '{{interface:card.output.video}}',
        audio: '{{core:download.output.file}}',
        volume: 250,
        loop: true,
      },
    });

    expect(node.data.kind).toBe('media');
    expect(node.type).toBe('flowNode');
    expect(node.data.mediaOperation).toBe('mux_audio');
    expect(node.data.mediaParams).toEqual({
      video: '{{interface:card.output.video}}',
      audio: '{{core:download.output.file}}',
      volume: 250,
      loop: true,
    });
  });

  it('defaults cleanly when params are absent', () => {
    const node = importCoreNode({ type: 'media', label: 'Bare Media' });
    expect(node.data.mediaOperation).toBeUndefined();
    expect(node.data.mediaParams).toEqual({});
  });

  it('ignores an unknown operation and unknown param keys', () => {
    const node = importCoreNode({
      type: 'media',
      label: 'Weird Media',
      params: { operation: 'transcode', input: '{{f}}', ffmpeg_args: '-y' },
    });
    expect(node.data.mediaOperation).toBeUndefined();
    expect(node.data.mediaParams).toEqual({ input: '{{f}}' });
  });

  it('generates a media- prefixed node id for detection when none is provided', () => {
    const node = importCoreNode({ type: 'media', label: 'Anon' });
    expect(node.id.startsWith('media-')).toBe(true);
    expect(node.data.id).toBe(node.id);
  });
});

describe('media plan roundtrip (generate -> import -> generate)', () => {
  it('a mux_audio cores[] entry survives a full roundtrip unchanged', () => {
    const original = runStepProcessor(mediaNode({
      mediaOperation: 'mux_audio',
      mediaParams: {
        video: '{{interface:card.output.video}}',
        audio: '{{core:download.output.file}}',
        volume: 250,
        offset_seconds: 3.5,
        keep_original_audio: true,
        original_volume: 40,
        audio_fit: 'shortest',
        normalize: -14,
        audio_bitrate: '256k',
      },
    }));

    const importedNode = importCoreNode({ ...original });
    const regenerated = runStepProcessor(importedNode);

    expect(regenerated.type).toBe(original.type);
    expect(regenerated.label).toBe(original.label);
    expect(regenerated.id).toBe(original.id);
    expect(regenerated.params).toEqual(original.params);
  });

  it('an audio-only mix with tracks (ducking, loops, speeds) survives a full roundtrip unchanged', () => {
    const original = runStepProcessor(mediaNode({
      mediaOperation: 'mix',
      mediaParams: {
        tracks: [
          { source: '{{a}}', id: 'voice', volume: 120, fade_in_seconds: 0.5 },
          { source: '{{b}}', volume: 30, loop: true, duck_under: 'voice', duck_amount_db: 15, speed: 1.25 },
        ],
        normalize: false,
        output_format: 'aac',
      },
    }));

    const importedNode = importCoreNode({ ...original });
    const regenerated = runStepProcessor(importedNode);

    expect(regenerated.params).toEqual(original.params);
  });

  it('roundtrip without optional params does not invent them', () => {
    const original = runStepProcessor(mediaNode({
      mediaOperation: 'extract_audio',
      mediaParams: { input: '{{core:download.output.file}}' },
    }));

    const importedNode = importCoreNode({ ...original });
    const regenerated = runStepProcessor(importedNode);

    expect(regenerated.params).toEqual(original.params);
    expect(regenerated.params).not.toHaveProperty('output_format');
    expect(regenerated.params).not.toHaveProperty('audio_bitrate');
    expect(regenerated.params).not.toHaveProperty('trim_start_seconds');
  });
});

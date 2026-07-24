import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import { collectInterfaces } from '../interfaceProcessor';
import type { BuilderNodeData } from '../../types';

/**
 * Plan serialization of the interface node's preview box. The box is snapped to the
 * interface's format, so previewWidth/Height must travel as a PAIR: a plan carrying
 * only one dim would reconstruct a different shape on import. The 400x250 default
 * (classic/unset format) is elided to keep plans lean.
 */
function makeCtx(interfaceData: Record<string, unknown>) {
  const node = {
    id: 'interface-abc',
    type: 'interfaceNode',
    position: { x: 10, y: 20 },
    data: { id: 'interface-abc', label: 'My UI', kind: 'interface', interfaceData } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
  return {
    nodes: [node],
    edges: [],
    plan: {} as any,
    interfaceNodeIdMap: new Map(),
  } as any;
}

describe('collectInterfaces preview box serialization', () => {
  it('serializes previewWidth and previewHeight TOGETHER for a non-default box', () => {
    const ctx = makeCtx({ interfaceId: 'abc', previewWidth: 225, previewHeight: 400 });
    collectInterfaces(ctx);
    const entry = ctx.plan.interfaces[0];
    expect(entry.previewWidth).toBe(225);
    expect(entry.previewHeight).toBe(400);
  });

  it('elides the exact 400x250 default box (classic/unset format)', () => {
    const ctx = makeCtx({ interfaceId: 'abc', previewWidth: 400, previewHeight: 250 });
    collectInterfaces(ctx);
    const entry = ctx.plan.interfaces[0];
    expect(entry.previewWidth).toBeUndefined();
    expect(entry.previewHeight).toBeUndefined();
  });

  it('drops a legacy single-dim box entirely (the node re-snaps itself to the format on load)', () => {
    const ctx = makeCtx({ interfaceId: 'abc', previewHeight: 250 });
    collectInterfaces(ctx);
    const entry = ctx.plan.interfaces[0];
    expect(entry.previewWidth).toBeUndefined();
    expect(entry.previewHeight).toBeUndefined();
  });

  it('serializes nothing when no box is stored', () => {
    const ctx = makeCtx({ interfaceId: 'abc' });
    collectInterfaces(ctx);
    const entry = ctx.plan.interfaces[0];
    expect(entry.previewWidth).toBeUndefined();
    expect(entry.previewHeight).toBeUndefined();
  });
});

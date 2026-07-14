import { describe, it, expect } from 'vitest';
import { createInterfaceNodes } from '../InterfaceNodeCreator';
import { collectInterfaces } from '../../../utils/interfaceProcessor';

/**
 * Same round-trip contract as the PDF fields (see InterfaceNodeCreator.pdfRoundtrip.test.ts):
 * the interface node's generateVideo toggle + videoPreset/videoMaxDurationSeconds options must
 * survive IMPORT (plan -> interfaceData), EXPORT (interfaceData -> plan) and the full round-trip.
 * A drop in either direction means an agent-built plan opens with the toggle OFF and a save
 * silently strips the video output from the plan.
 */
describe('interface node video fields survive the frontend plan round-trip', () => {
  it('IMPORT: plan -> interfaceData maps generateVideo / videoPreset / videoMaxDurationSeconds', () => {
    const { nodes } = createInterfaceNodes(
      [{ id: 'iface-1', label: 'Clip Card', generateVideo: true, videoPreset: 'square', videoMaxDurationSeconds: 45 }],
      0, 0);

    const interfaceData = (nodes[0].data as any).interfaceData;
    expect(interfaceData.generateVideo).toBe(true);
    expect(interfaceData.videoPreset).toBe('square');
    expect(interfaceData.videoMaxDurationSeconds).toBe(45);
  });

  it('EXPORT: interfaceData -> plan serializes generateVideo / videoPreset / videoMaxDurationSeconds', () => {
    const node = {
      id: 'interface-iface-1',
      type: 'interfaceNode',
      position: { x: 0, y: 0 },
      data: {
        label: 'Clip Card',
        interfaceData: {
          interfaceId: 'iface-1',
          generateVideo: true,
          videoPreset: 'horizontal',
          videoMaxDurationSeconds: 60,
        },
      },
    };
    const ctx: any = { nodes: [node], plan: {}, interfaceNodeIdMap: new Map() };

    collectInterfaces(ctx);

    const entry = ctx.plan.interfaces[0];
    expect(entry.generateVideo).toBe(true);
    expect(entry.videoPreset).toBe('horizontal');
    expect(entry.videoMaxDurationSeconds).toBe(60);
  });

  it('EXPORT: omits video fields entirely when generateVideo is off (no plan pollution)', () => {
    const node = {
      id: 'interface-iface-2',
      type: 'interfaceNode',
      position: { x: 0, y: 0 },
      data: { label: 'Plain', interfaceData: { interfaceId: 'iface-2', videoPreset: 'vertical' } },
    };
    const ctx: any = { nodes: [node], plan: {}, interfaceNodeIdMap: new Map() };

    collectInterfaces(ctx);

    const entry = ctx.plan.interfaces[0];
    expect(entry.generateVideo).toBeUndefined();
    expect(entry.videoPreset).toBeUndefined();
    expect(entry.videoMaxDurationSeconds).toBeUndefined();
  });

  it('EXPORT: non-positive duration is not serialized (renderer default applies)', () => {
    const node = {
      id: 'interface-iface-4',
      type: 'interfaceNode',
      position: { x: 0, y: 0 },
      data: {
        label: 'Bad Duration',
        interfaceData: { interfaceId: 'iface-4', generateVideo: true, videoMaxDurationSeconds: 0 },
      },
    };
    const ctx: any = { nodes: [node], plan: {}, interfaceNodeIdMap: new Map() };

    collectInterfaces(ctx);

    const entry = ctx.plan.interfaces[0];
    expect(entry.generateVideo).toBe(true);
    expect(entry.videoMaxDurationSeconds).toBeUndefined();
  });

  it('ROUND-TRIP: import a plan then export it preserves generateVideo + options', () => {
    const planIn = { id: 'iface-3', label: 'Duel', generateVideo: true, videoPreset: 'vertical', videoMaxDurationSeconds: 30 };
    const { nodes } = createInterfaceNodes([planIn], 0, 0);

    const ctx: any = { nodes, plan: {}, interfaceNodeIdMap: new Map() };
    collectInterfaces(ctx);

    const entry = ctx.plan.interfaces[0];
    expect(entry.generateVideo).toBe(true);
    expect(entry.videoPreset).toBe('vertical');
    expect(entry.videoMaxDurationSeconds).toBe(30);
  });

  it('ROUND-TRIP: videoMode + videoFps survive import -> export; omitted when unset', () => {
    const planIn = {
      id: 'iface-5', label: 'Smooth Duel',
      generateVideo: true, videoPreset: 'vertical', videoMaxDurationSeconds: 20,
      videoMode: 'smooth', videoFps: 60,
    };
    const { nodes } = createInterfaceNodes([planIn], 0, 0);
    const interfaceData = (nodes[0].data as any).interfaceData;
    expect(interfaceData.videoMode).toBe('smooth');
    expect(interfaceData.videoFps).toBe(60);

    const ctx: any = { nodes, plan: {}, interfaceNodeIdMap: new Map() };
    collectInterfaces(ctx);
    const entry = ctx.plan.interfaces[0];
    expect(entry.videoMode).toBe('smooth');
    expect(entry.videoFps).toBe(60);

    // Unset mode/fps stay out of the plan (renderer defaults apply).
    const bare = createInterfaceNodes(
      [{ id: 'iface-6', label: 'Bare', generateVideo: true }], 0, 0);
    const ctx2: any = { nodes: bare.nodes, plan: {}, interfaceNodeIdMap: new Map() };
    collectInterfaces(ctx2);
    expect(ctx2.plan.interfaces[0].videoMode).toBeUndefined();
    expect(ctx2.plan.interfaces[0].videoFps).toBeUndefined();
  });
});

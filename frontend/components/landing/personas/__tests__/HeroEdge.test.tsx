// @vitest-environment jsdom
import React from 'react';
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { internalsSymbol, Position, type EdgeProps, type ReactFlowState } from 'reactflow';

/**
 * The hero feeds the product's edge endpoints corrected for its CSS scale. These pin the
 * wiring: the store is read, the matching handle is used, and BuilderEdge receives the
 * corrected coordinates rather than ReactFlow's shrunk ones.
 */
const received = vi.hoisted(() => ({ props: null as Record<string, unknown> | null }));
vi.mock('next/dynamic', () => ({ default: () => (props: Record<string, unknown>) => { received.props = props; return null; } }));

const SCALE = 0.317;
const handle = (id: string, position: Position, top: number) => ({ id, position, x: (130 - 6) * SCALE, y: top * SCALE, width: 12, height: 12 });
const state = {
  nodeInternals: new Map([
    ['a', { id: 'a', positionAbsolute: { x: -130, y: 0 }, width: 260, height: 76, [internalsSymbol]: { handleBounds: { source: [handle('other', Position.Right, 30), handle('out', Position.Bottom, 70)], target: [] } } }],
    ['b', { id: 'b', positionAbsolute: { x: 170, y: 200 }, width: 260, height: 76, [internalsSymbol]: { handleBounds: { source: [], target: [handle('in', Position.Top, -6)] } } }],
  ]),
} as unknown as ReactFlowState;

vi.mock('reactflow', async (importOriginal) => {
  const actual = await importOriginal<typeof import('reactflow')>();
  return { ...actual, useStore: (selector: (s: ReactFlowState) => unknown) => selector(state) };
});

import { endpointOf, HeroEdge, sameEndpoint } from '../HeroEdge';

afterEach(() => { cleanup(); received.props = null; });

const edge = { id: 'e', source: 'a', target: 'b', sourceHandleId: 'out', targetHandleId: 'in', sourceX: -84.6, sourceY: 26, targetX: 215.4, targetY: 198, sourcePosition: Position.Bottom, targetPosition: Position.Top } as unknown as EdgeProps;

describe('HeroEdge', () => {
  it('hands BuilderEdge the centred, true endpoints instead of ReactFlow\'s shrunk ones', () => {
    render(<HeroEdge {...edge} />);
    expect(received.props?.sourceX).toBeCloseTo(0, 6);
    expect(received.props?.sourceY).toBeCloseTo(76 + 6, 6);
    expect(received.props?.targetX).toBeCloseTo(300, 6);
    expect(received.props?.targetY).toBeCloseTo(200 - 6, 6);
    expect(received.props?.id).toBe('e');
  });

  it('uses the handle the edge names, not the first one', () => {
    expect(endpointOf(state, 'a', 'source', 'out')?.handle?.position).toBe(Position.Bottom);
    expect(endpointOf(state, 'a', 'source', null)?.handle?.position).toBe(Position.Right);
  });

  it('reads nothing for a node that is not measured yet, and BuilderEdge keeps ReactFlow\'s point', () => {
    expect(endpointOf(state, 'missing', 'source', null)).toBeUndefined();
    render(<HeroEdge {...edge} source="missing" />);
    expect(received.props?.sourceX).toBe(-84.6);
    expect(received.props?.sourceY).toBe(26);
  });

  it('considers two endpoints equal only when every number feeding the path is', () => {
    const a = endpointOf(state, 'a', 'source', 'out');
    expect(sameEndpoint(a, endpointOf(state, 'a', 'source', 'out'))).toBe(true);
    expect(sameEndpoint(a, a && { ...a, node: { ...a.node, x: a.node.x + 1 } })).toBe(false);
    expect(sameEndpoint(a, a && { ...a, handle: a.handle && { ...a.handle, y: a.handle.y + 1 } })).toBe(false);
  });
});

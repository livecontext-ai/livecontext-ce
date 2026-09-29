import { describe, expect, it } from 'vitest';
import { Position } from 'reactflow';
import { correctHandlePoint, type MeasuredHandle } from '../heroEdgeGeometry';

/**
 * On a phone the hero window is drawn at a CSS scale of ~0.317 and ReactFlow's handle
 * positions come out wrong: measured on production, an edge left its 260px node 45.4px
 * from the left instead of at its centre.
 *
 * The fixtures below are built with ReactFlow 11's REAL formulas, not a simplified model:
 * `getHandleBounds` stores x/y from getBoundingClientRect (shrunk by the outer scale) and
 * width/height from offsetWidth/offsetHeight (not shrunk); `getHandlePosition` then adds
 * width/2 on x and, for a bottom handle, height on y. With the builder's 12px handle,
 * `bottom: -6px` / `top: -6px`, on a 260 x 76 node.
 */
const node = { x: -130, y: 0, width: 260, height: 76 };
const HANDLE = 12;

/** What ReactFlow stores for a centred handle whose true top-left is (left, top) in the node. */
function measured(position: Position, top: number, scale: number): MeasuredHandle {
  const left = node.width / 2 - HANDLE / 2;
  return { x: left * scale, y: top * scale, width: HANDLE, height: HANDLE, position };
}

/** Where ReactFlow itself would put the edge from that stored handle (getHandlePosition). */
function reactFlowPoint(handle: MeasuredHandle) {
  return {
    x: node.x + handle.x + handle.width / 2,
    y: node.y + handle.y + (handle.position === Position.Bottom ? handle.height : 0),
  };
}

const BOTTOM_TOP = node.height + 6 - HANDLE; // bottom: -6px
const TOP_TOP = -6; // top: -6px

describe('correctHandlePoint', () => {
  it('reproduces the production symptom from the real formula (45.4px instead of 130px)', () => {
    const handle = measured(Position.Bottom, BOTTOM_TOP, 0.317);
    expect(reactFlowPoint(handle).x - node.x).toBeCloseTo(45.3, 1);
  });

  it('puts a phone-scaled bottom handle back at the centre of the node, exactly below it', () => {
    const handle = measured(Position.Bottom, BOTTOM_TOP, 0.317);
    const point = correctHandlePoint(node, handle, reactFlowPoint(handle));
    expect(point.x).toBeCloseTo(node.x + 130, 6);
    expect(point.y).toBeCloseTo(node.y + node.height + 6, 6);
  });

  it('puts a phone-scaled top handle back at the centre of the node, exactly above it', () => {
    const handle = measured(Position.Top, TOP_TOP, 0.317);
    const point = correctHandlePoint(node, handle, reactFlowPoint(handle));
    expect(point.x).toBeCloseTo(node.x + 130, 6);
    expect(point.y).toBeCloseTo(node.y - 6, 6);
  });

  it('agrees with ReactFlow at scale 1 (desktop is unchanged)', () => {
    for (const handle of [measured(Position.Bottom, BOTTOM_TOP, 1), measured(Position.Top, TOP_TOP, 1)]) {
      const point = correctHandlePoint(node, handle, reactFlowPoint(handle));
      const expected = reactFlowPoint(handle);
      expect(point.x).toBeCloseTo(expected.x, 6);
      expect(point.y).toBeCloseTo(expected.y, 6);
    }
  });

  it('is exact at any scale, including the tablet range', () => {
    for (const scale of [0.1, 0.5, 0.8, 0.97]) {
      const handle = measured(Position.Bottom, BOTTOM_TOP, scale);
      const point = correctHandlePoint(node, handle, reactFlowPoint(handle));
      expect(point.x, String(scale)).toBeCloseTo(0, 6);
      expect(point.y, String(scale)).toBeCloseTo(node.height + 6, 6);
    }
  });

  it('leaves a left or right handle to ReactFlow (only top and bottom are centred)', () => {
    const fallback = { x: 7, y: 9 };
    for (const position of [Position.Left, Position.Right]) {
      expect(correctHandlePoint(node, { ...measured(Position.Bottom, BOTTOM_TOP, 0.5), position }, fallback)).toBe(fallback);
    }
  });

  it('leaves the point untouched when the handle is missing, the node unknown, or the scale implausible', () => {
    const fallback = { x: 7, y: 9 };
    const handle = measured(Position.Bottom, BOTTOM_TOP, 0.5);
    expect(correctHandlePoint(undefined, handle, fallback)).toBe(fallback);
    expect(correctHandlePoint(node, undefined, fallback)).toBe(fallback);
    expect(correctHandlePoint({ ...node, width: 10 }, handle, fallback)).toBe(fallback);
    expect(correctHandlePoint(node, { ...handle, x: 0 }, fallback)).toBe(fallback);
    expect(correctHandlePoint(node, { ...handle, x: handle.x * 4 }, fallback)).toBe(fallback);
  });
});

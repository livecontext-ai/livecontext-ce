import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { getNodeDimensions, layoutConfigForDirection } from '../LayoutService';

/**
 * dagre's `nodesep`/`ranksep` are relative to the FLOW AXIS, so their roles swap with
 * `rankdir`. Flipping rankdir alone (the obvious mistake) leaves a vertical graph
 * spaced with numbers tuned for a horizontal one: nodes are wide and short, so the
 * within-rank gap has to grow and the along-flow gap has to shrink.
 */
describe('layoutConfigForDirection', () => {
  it('leaves the horizontal config untouched, so nothing changes for existing canvases', () => {
    // An empty override means applyDagreLayout keeps every LAYOUT_CONFIG default.
    expect(layoutConfigForDirection('horizontal')).toEqual({});
  });

  it('lays vertical graphs out top-to-bottom', () => {
    expect(layoutConfigForDirection('vertical').rankdir).toBe('TB');
  });

  it('re-tunes the spacing for the axis swap instead of only flipping rankdir', () => {
    const vertical = layoutConfigForDirection('vertical');
    // In TB, nodesep is the gap between SIBLING branches (horizontal), and nodes are
    // 200-900px wide: it must exceed the LR value of 40 or wide branches collide.
    expect(vertical.nodesep).toBeGreaterThan(40);
    // ranksep is now the vertical gap between ranks, where nodes are only ~80-140px
    // tall, so it stays the larger of the two - matching the fleet's TB ratio.
    expect(vertical.ranksep!).toBeGreaterThan(vertical.nodesep!);
  });

  it('drops the upper-left packing, which bunches a top-down graph to one side', () => {
    // dagre centres each rank when align is undefined; that is what a TB graph wants
    // (and what the fleet, TB since it shipped, uses).
    expect(layoutConfigForDirection('vertical').align).toBeUndefined();
    expect('align' in layoutConfigForDirection('vertical')).toBe(true);
  });
});

/**
 * The estimate dagre packs against has to describe the node as it will actually be
 * RENDERED. A branching node's rows stack down the node in horizontal and across it
 * in vertical (`getBranchRowFlow`), so the row count drives a different axis in each
 * direction. Feeding the horizontal estimate to a vertical layout under-reports the
 * width - which is the within-rank axis under `rankdir: TB` - and packs the next
 * branch on top of it. This is the same class of bug the row-aware HEIGHT estimate
 * was originally added to fix on the horizontal canvas.
 */
describe('getNodeDimensions transposes a branching node with the direction', () => {
  const switchNode = (cases: number): Node<BuilderNodeData> =>
    ({
      id: 's1',
      type: 'switchNode',
      position: { x: 0, y: 0 },
      data: { id: 's1', label: 'Route', kind: 'switch', switchCases: Array.from({ length: cases }, (_, i) => ({ id: `c${i}` })) },
    }) as unknown as Node<BuilderNodeData>;

  it('grows a 5-case switch DOWNWARD in horizontal', () => {
    const dims = getNodeDimensions(switchNode(5), true, 'horizontal');
    expect(dims.height).toBeGreaterThan(250);
    // Width is label-driven only: the rows are stacked, not side by side.
    expect(dims.width).toBeLessThan(dims.height);
  });

  it('grows the SAME switch SIDEWAYS in vertical, and flattens its height', () => {
    const dims = getNodeDimensions(switchNode(5), true, 'vertical');
    // Five rows side by side: far wider than the label-only estimate would give.
    expect(dims.width).toBeGreaterThan(600);
    expect(dims.width).toBeGreaterThan(dims.height);
    // And only one row tall, instead of the ~318px the horizontal estimate reports.
    expect(dims.height).toBeLessThan(160);
  });

  it('scales the vertical width with the branch count', () => {
    const two = getNodeDimensions(switchNode(2), true, 'vertical').width;
    const five = getNodeDimensions(switchNode(5), true, 'vertical').width;
    expect(five).toBeGreaterThan(two);
  });

  it('defaults to the horizontal estimate, so existing callers are untouched', () => {
    expect(getNodeDimensions(switchNode(5), true)).toEqual(getNodeDimensions(switchNode(5), true, 'horizontal'));
  });

  it('leaves plain nodes identical in both directions', () => {
    // Only ROW nodes reflow; a plain node is the same box either way, so the change
    // must be a strict no-op for it.
    const plain = {
      id: 'p1', type: 'flowNode', position: { x: 0, y: 0 },
      data: { id: 'p1', label: 'Send email', kind: 'action' },
    } as unknown as Node<BuilderNodeData>;
    expect(getNodeDimensions(plain, true, 'vertical')).toEqual(getNodeDimensions(plain, true, 'horizontal'));
  });
});

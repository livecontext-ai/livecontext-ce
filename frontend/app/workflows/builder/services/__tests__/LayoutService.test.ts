import { describe, it, expect } from 'vitest';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import {
  applyDagreLayout,
  estimateNodeWidth,
  getNodeDimensions,
} from '../LayoutService';

/**
 * Minimal Node<BuilderNodeData> factory. `width`/`height` are the ReactFlow
 * MEASURED dimensions (populated only after a node has rendered) - left
 * undefined to simulate the pre-render import / plan-sync path.
 */
function makeNode(overrides: {
  id: string;
  type?: string;
  label?: string;
  kind?: string;
  width?: number;
  height?: number;
  data?: Record<string, any>;
}): Node<BuilderNodeData> {
  return {
    id: overrides.id,
    type: overrides.type ?? 'flowNode',
    position: { x: 0, y: 0 },
    width: overrides.width,
    height: overrides.height,
    data: {
      id: overrides.id,
      label: overrides.label ?? '',
      kind: (overrides.kind ?? 'tool') as any,
      ...overrides.data,
    } as BuilderNodeData,
  };
}

const LONG_LABEL = 'List Channel Members (youtube.channel-memberships.creator)';

// =============================================================================
// estimateNodeWidth
// =============================================================================
describe('estimateNodeWidth', () => {
  it('floors a short label at the provided minimum width', () => {
    // "Run" is tiny - chrome + 3 chars is well under 200, so the floor wins.
    expect(estimateNodeWidth('Run', 200)).toBe(200);
  });

  it('grows beyond the minimum for a long label', () => {
    const wide = estimateNodeWidth(LONG_LABEL, 200);
    expect(wide).toBeGreaterThan(200);
    // 96 chrome + 56 chars * 7px ≈ 488
    expect(wide).toBeGreaterThan(400);
  });

  it('is monotonic in label length', () => {
    expect(estimateNodeWidth('a'.repeat(40), 200)).toBeGreaterThan(
      estimateNodeWidth('a'.repeat(10), 200),
    );
  });

  it('caps pathologically long labels', () => {
    expect(estimateNodeWidth('x'.repeat(5000), 200)).toBe(900);
  });

  it('respects a higher floor (fleet agent nodes)', () => {
    expect(estimateNodeWidth('Agent', 280)).toBe(280);
  });
});

// =============================================================================
// getNodeDimensions
// =============================================================================
describe('getNodeDimensions', () => {
  it('uses ReactFlow measured dimensions when present (auto-layout button path)', () => {
    const node = makeNode({ id: 'a', label: LONG_LABEL, width: 512, height: 144 });
    expect(getNodeDimensions(node)).toEqual({ width: 512, height: 144 });
  });

  it('ignores measured dimensions when ignoreMeasured=true (deterministic fleet path)', () => {
    const node = makeNode({ id: 'a', label: LONG_LABEL, width: 512, height: 144 });
    const dims = getNodeDimensions(node, true);
    expect(dims.width).toBe(estimateNodeWidth(LONG_LABEL, 200)); // estimate, NOT the measured 512
    expect(dims.height).toBe(80);
  });

  it('ignores measured dimensions that are zero / non-finite and falls back to estimate', () => {
    const node = makeNode({ id: 'a', label: 'Send Email', width: 0, height: 0 });
    const dims = getNodeDimensions(node);
    expect(dims.width).toBe(estimateNodeWidth('Send Email', 200));
    expect(dims.height).toBe(80);
  });

  it('estimates width from the label when unmeasured (import / plan-sync path)', () => {
    const shortDims = getNodeDimensions(makeNode({ id: 'a', label: 'Get Values' }));
    const longDims = getNodeDimensions(makeNode({ id: 'b', label: LONG_LABEL }));
    expect(longDims.width).toBeGreaterThan(shortDims.width);
    expect(longDims.width).toBe(estimateNodeWidth(LONG_LABEL, 200));
  });

  it('keeps control-node base widths as the floor', () => {
    // A decision node with a short label keeps its 220 base, not the 200 default.
    const dims = getNodeDimensions(makeNode({ id: 'd', type: 'decisionNode', label: 'OK?' }));
    expect(dims.width).toBe(220);
  });

  it('uses stored note dimensions, not label width (notes wrap)', () => {
    const node = makeNode({ id: 'n', type: 'noteNode', label: 'x'.repeat(80), data: { noteWidth: 300, noteHeight: 160 } });
    expect(getNodeDimensions(node)).toEqual({ width: 300, height: 160 });
  });

  it('uses interface preview dimensions even over measured dims (priority)', () => {
    const node = makeNode({
      id: 'iface',
      type: 'interfaceNode',
      kind: 'interface',
      width: 999, // a stale measured value must NOT win over explicit preview size
      height: 999,
      data: { interfaceData: { showPreview: true, previewWidth: 333, previewHeight: 222 } },
    });
    expect(getNodeDimensions(node)).toEqual({ width: 333, height: 222 });
  });

  it('uses a resized data_input box size, not the label estimate, when unmeasured', () => {
    const node = makeNode({
      id: 'di',
      kind: 'data_input',
      label: 'x'.repeat(80), // long label must NOT drive width for a resizable box
      data: { dataInputWidth: 420, dataInputHeight: 260 },
    });
    expect(getNodeDimensions(node)).toEqual({ width: 420, height: 260 });
  });

  it('treats a fleet agent node as wider (>= 280) and label-aware', () => {
    const node = makeNode({ id: 'agent-1', label: 'A Very Long Agent Name Here', data: { fleetHandles: ['model', 'tools'] } });
    expect(getNodeDimensions(node).width).toBeGreaterThanOrEqual(280);
  });
});

// =============================================================================
// getNodeDimensions - row-aware heights (the tall-switch overlap bug)
// =============================================================================
describe('getNodeDimensions - branching node heights scale with port rows', () => {
  const switchCases = (n: number) =>
    Array.from({ length: n }, (_, i) => ({ id: `sw-case-${i}`, type: 'case', label: `Case ${i + 1}`, value: '' }));

  it('estimates a 6-case switch tall enough to cover its rendered rows (regression)', () => {
    // Pre-fix, every branching node was estimated 80-100px tall regardless of its
    // rows, so Dagre packed the next vertical lane on top of a tall switch. A
    // 6-row switch renders ~360px: the estimate must be in that range, not 100.
    const node = makeNode({ id: 'sw', type: 'switchNode', data: { switchCases: switchCases(6) } });
    expect(getNodeDimensions(node).height).toBeGreaterThanOrEqual(320);
  });

  it('is monotonic in the number of rows', () => {
    const h = (n: number) =>
      getNodeDimensions(makeNode({ id: 'sw', type: 'switchNode', data: { switchCases: switchCases(n) } })).height;
    expect(h(5)).toBeGreaterThan(h(3));
    expect(h(3)).toBeGreaterThan(h(1));
  });

  it('renders 0 rows for an explicitly EMPTY row array (matches the component: [] is not nullish)', () => {
    // The components fall back to createDefault* only for undefined, NOT for [] -
    // an empty switchCases renders zero rows, so the estimate must be header-only.
    const empty = getNodeDimensions(makeNode({ id: 'sw', type: 'switchNode', data: { switchCases: [] } }));
    const withDefaults = getNodeDimensions(makeNode({ id: 'sw2', type: 'switchNode' }));
    expect(empty.height).toBeLessThan(withDefaults.height);
    expect(empty.height).toBe(96); // ROW_NODE_BASE_PX, no rows
  });

  it('still honors measured dimensions over the row estimate when ignoreMeasured=false', () => {
    // Priority 2 (measured) must pre-empt the row-aware path for callers that
    // explicitly want pixel-accuracy.
    const node = makeNode({ id: 'sw', type: 'switchNode', width: 333, height: 444, data: { switchCases: switchCases(6) } });
    expect(getNodeDimensions(node, false)).toEqual({ width: 333, height: 444 });
  });

  it('uses each node type’s default row count when row data is absent', () => {
    // Defaults mirror the components’ createDefault* fallbacks:
    // switch = 3 rows (2 cases + default), decision = 2 (if/else), approval = 3.
    const h = (type: string) => getNodeDimensions(makeNode({ id: 'x', type })).height;
    expect(h('switchNode')).toBeGreaterThan(h('decisionNode'));
    expect(h('userApprovalNode')).toBe(h('switchNode'));
    expect(h('decisionNode')).toBeGreaterThan(80); // 2 rows is already taller than a plain node
  });

  it('covers every row-rendering branching type (fork/merge/classify/option/guardrail)', () => {
    const rows3 = (key: string) => ({ [key]: [{ id: '1' }, { id: '2' }, { id: '3' }] });
    const cases: Array<[string, Record<string, any>]> = [
      ['forkNode', rows3('forkOutputs')],
      ['mergeNode', rows3('mergeInputs')],
      ['classifyNode', rows3('classifyCategories')],
      ['optionNode', rows3('optionChoices')],
    ];
    for (const [type, data] of cases) {
      const three = getNodeDimensions(makeNode({ id: 'n', type, data })).height;
      const plain = getNodeDimensions(makeNode({ id: 'p', type: 'flowNode' })).height;
      expect(three, type).toBeGreaterThan(plain + 100); // 3 rows ≈ 226px vs 80px
    }
    // Guardrail renders 2 fixed rows - taller than a plain node even with no row data.
    expect(getNodeDimensions(makeNode({ id: 'g', type: 'guardrailNode' })).height).toBeGreaterThan(150);
  });

  it('feeds the row-aware height to Dagre so two tall rank-mates do not overlap (regression)', () => {
    // a fans out to TWO 6-case switches: they share a rank. Each renders ~360px
    // tall in the real DOM. Pre-fix Dagre was told they were ~80-100px tall and
    // spaced their tops ~120px apart → the second rendered ON TOP of the first
    // (the reported screenshot). Post-fix the tops must clear the real extent.
    const a = makeNode({ id: 'a', label: 'Start' });
    const sw1 = makeNode({ id: 'sw1', type: 'switchNode', label: 'Route A', data: { switchCases: switchCases(6) } });
    const sw2 = makeNode({ id: 'sw2', type: 'switchNode', label: 'Route B', data: { switchCases: switchCases(6) } });
    const out = applyDagreLayout(
      [a, sw1, sw2],
      [
        { id: 'a->sw1', source: 'a', target: 'sw1' },
        { id: 'a->sw2', source: 'a', target: 'sw2' },
      ],
    );
    const y1 = out.find(n => n.id === 'sw1')!.position.y;
    const y2 = out.find(n => n.id === 'sw2')!.position.y;
    const RENDERED_SWITCH_6_ROWS_PX = 360; // ≈ real DOM height of a 6-row switch
    expect(Math.abs(y1 - y2)).toBeGreaterThanOrEqual(RENDERED_SWITCH_6_ROWS_PX);
  });
});

// =============================================================================
// applyDagreLayout - overlap regression (the YouTube-row bug)
// =============================================================================
describe('applyDagreLayout - no horizontal overlap', () => {
  const edge = (source: string, target: string): Edge => ({ id: `${source}->${target}`, source, target });

  it('places a measured wide node fully left of its successor (no overlap)', () => {
    // Reproduces the reported bug: a wide MCP node (long label, measured 480px)
    // followed by another node in an LR chain. With the old hardcoded 200px,
    // the successor was placed ~320px away and overlapped the wide node.
    const a = makeNode({ id: 'a', label: LONG_LABEL, width: 480, height: 80 });
    const b = makeNode({ id: 'b', label: 'Next', width: 200, height: 80 });

    const out = applyDagreLayout([a, b], [edge('a', 'b')]);
    const A = out.find(n => n.id === 'a')!;
    const B = out.find(n => n.id === 'b')!;

    // B's left edge must start at/after A's right edge.
    expect(B.position.x).toBeGreaterThanOrEqual(A.position.x + 480);
  });

  it('separates unmeasured wide nodes using the label estimate', () => {
    const a = makeNode({ id: 'a', label: LONG_LABEL }); // unmeasured → estimated wide
    const b = makeNode({ id: 'b', label: 'Next' });

    const out = applyDagreLayout([a, b], [edge('a', 'b')]);
    const A = out.find(n => n.id === 'a')!;
    const B = out.find(n => n.id === 'b')!;

    // Gap between left edges must be at least A's estimated width (i.e. no overlap).
    expect(B.position.x - A.position.x).toBeGreaterThanOrEqual(estimateNodeWidth(LONG_LABEL, 200));
  });

  it('does not overlap a wide node with the next column in a 3-node chain', () => {
    const a = makeNode({ id: 'a', label: 'Start', width: 200, height: 80 });
    const b = makeNode({ id: 'b', label: LONG_LABEL, width: 480, height: 80 }); // wide middle
    const c = makeNode({ id: 'c', label: 'End', width: 200, height: 80 });

    const out = applyDagreLayout([a, b, c], [edge('a', 'b'), edge('b', 'c')]);
    const map = new Map(out.map(n => [n.id, n.position.x]));

    // c must clear b's right edge.
    expect(map.get('c')!).toBeGreaterThanOrEqual(map.get('b')! + 480);
    // b must clear a's right edge.
    expect(map.get('b')!).toBeGreaterThanOrEqual(map.get('a')! + 200);
  });
});

// =============================================================================
// applyDagreLayout - independent components packed (compaction)
// =============================================================================
describe('applyDagreLayout - independent components', () => {
  const edge = (source: string, target: string): Edge => ({ id: `${source}->${target}`, source, target });
  const at = (node: Node<BuilderNodeData>, x: number, y: number) => { node.position = { x, y }; return node; };

  it('does NOT let a wide node in one chain push another chain’s columns', () => {
    // chain A: a1 (long label → wide estimate) → a2 ; chain B: b1 → b2. Disconnected.
    // With the old single global grid, b2 would sit at column-2 = max(wideA,200)+ranksep.
    // Per-component layout packs B independently: b2 ≈ 200 + ranksep, well under 400.
    const a1 = makeNode({ id: 'a1', label: LONG_LABEL }); // wide estimate
    const a2 = makeNode({ id: 'a2', label: 'A2' });
    const b1 = makeNode({ id: 'b1', label: 'B1' });
    const b2 = makeNode({ id: 'b2', label: 'B2' });

    const out = applyDagreLayout([a1, a2, b1, b2], [edge('a1', 'a2'), edge('b1', 'b2')]);
    const pos = new Map(out.map(n => [n.id, n.position]));

    expect(pos.get('b2')!.x).toBeLessThan(400);                                  // not dragged to chain A's wide column
    expect(pos.get('a2')!.x).toBeGreaterThanOrEqual(estimateNodeWidth(LONG_LABEL, 200)); // chain A still respects its own wide node
  });

  it('stacks disconnected chains on separate vertical bands (no cross-chain overlap)', () => {
    const a1 = makeNode({ id: 'a1', width: 200, height: 80 });
    const a2 = makeNode({ id: 'a2', width: 200, height: 80 });
    const b1 = makeNode({ id: 'b1', width: 200, height: 80 });
    const b2 = makeNode({ id: 'b2', width: 200, height: 80 });

    const out = applyDagreLayout([a1, a2, b1, b2], [edge('a1', 'a2'), edge('b1', 'b2')]);
    const pos = new Map(out.map(n => [n.id, n.position]));
    // The two chains occupy different y bands → their nodes don't overlap vertically.
    const aBand = pos.get('a1')!.y;
    const bBand = pos.get('b1')!.y;
    expect(Math.abs(aBand - bBand)).toBeGreaterThanOrEqual(80); // at least node-height apart
  });

  it('orders components by their original perpendicular coordinate (lane order preserved)', () => {
    // chain A lower on screen (y=500), chain B higher (y=0). B must end up above A.
    const a1 = at(makeNode({ id: 'a1', width: 200, height: 80 }), 0, 500);
    const a2 = at(makeNode({ id: 'a2', width: 200, height: 80 }), 0, 500);
    const b1 = at(makeNode({ id: 'b1', width: 200, height: 80 }), 0, 0);
    const b2 = at(makeNode({ id: 'b2', width: 200, height: 80 }), 0, 0);

    const out = applyDagreLayout([a1, a2, b1, b2], [edge('a1', 'a2'), edge('b1', 'b2')]);
    const pos = new Map(out.map(n => [n.id, n.position]));
    expect(pos.get('b1')!.y).toBeLessThan(pos.get('a1')!.y);
  });

  it('single connected component takes the fast path (successor to the right)', () => {
    const a = makeNode({ id: 'a', label: 'Start' });
    const b = makeNode({ id: 'b', label: 'Next' });
    const out = applyDagreLayout([a, b], [edge('a', 'b')]);
    const pos = new Map(out.map(n => [n.id, n.position]));
    expect(pos.get('b')!.x).toBeGreaterThan(pos.get('a')!.x);
  });

  it('auto-layout is idempotent w.r.t. measured dims (button == agent-sync layout)', () => {
    // The builder lays out from estimates on import / agent plan-sync (nodes not yet
    // measured); the auto-layout button must reproduce the SAME layout, not "jump".
    // So applyDagreLayout must be identical whether or not nodes carry measured dims.
    const build = (measured: boolean) => {
      const dim = (w: number, h: number) => (measured ? { width: w, height: h } : {});
      const a = makeNode({ id: 'a', label: LONG_LABEL, ...dim(516, 84) });
      const b = makeNode({ id: 'b', label: 'Next step', ...dim(232, 80) });
      const c = makeNode({ id: 'c', label: 'Final', ...dim(180, 80) });
      return applyDagreLayout([a, b, c], [edge('a', 'b'), edge('b', 'c')])
        .map(n => ({ id: n.id, x: Math.round(n.position.x), y: Math.round(n.position.y) }));
    };
    expect(build(true)).toEqual(build(false));
  });
});

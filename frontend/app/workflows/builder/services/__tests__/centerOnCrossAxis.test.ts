import { describe, it, expect } from 'vitest';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { centerOnCrossAxis } from '../LayoutService';

// A plain node is 200 wide, 80 tall (LayoutService estimate for a short label).
const W = 200;
const H = 80;
const node = (id: string, x: number, y: number): Node<BuilderNodeData> =>
  ({ id, type: 'flowNode', position: { x, y }, data: { id, label: id, kind: 'action' } }) as unknown as Node<BuilderNodeData>;
const edge = (s: string, t: string): Edge => ({ id: `${s}->${t}`, source: s, target: t }) as Edge;

const HORIZONTAL = { rankdir: 'LR', nodesep: 40, ranksep: 90, ignoreMeasured: true } as any;
const VERTICAL = { rankdir: 'TB', nodesep: 44, ranksep: 104, ignoreMeasured: true } as any;

const cx = (n: Node<BuilderNodeData>) => n.position.x + W / 2;
const cy = (n: Node<BuilderNodeData>) => n.position.y + H / 2;
const byId = (ns: Node<BuilderNodeData>[], id: string) => ns.find((n) => n.id === id)!;

describe('centerOnCrossAxis', () => {
  it('straightens a single 1:1 chain link (vertical): the child lines up under its parent', () => {
    // Parent centred at x=100, child dagre-placed off to the side at x=160.
    const nodes = [node('a', 0, 0), node('b', 60, 200)];
    const out = centerOnCrossAxis(nodes, [edge('a', 'b')], VERTICAL);
    // Cross axis is x in vertical: the child's centre now equals the parent's.
    expect(cx(byId(out, 'b'))).toBeCloseTo(cx(byId(out, 'a')), 1);
  });

  it('straightens a single 1:1 chain link (horizontal): the child lines up on the parent row', () => {
    const nodes = [node('a', 0, 0), node('b', 200, 55)];
    const out = centerOnCrossAxis(nodes, [edge('a', 'b')], HORIZONTAL);
    // Cross axis is y in horizontal.
    expect(cy(byId(out, 'b'))).toBeCloseTo(cy(byId(out, 'a')), 1);
  });

  it('centres a parent over the midpoint of its two children (vertical)', () => {
    // Two children spread on the x axis; parent should land on their midpoint.
    const nodes = [node('p', 0, 0), node('c1', -150, 200), node('c2', 350, 200)];
    const out = centerOnCrossAxis(nodes, [edge('p', 'c1'), edge('p', 'c2')], VERTICAL);
    const mid = (cx(byId(out, 'c1')) + cx(byId(out, 'c2'))) / 2;
    expect(cx(byId(out, 'p'))).toBeCloseTo(mid, 1);
  });

  it('never moves a node along the FLOW axis', () => {
    const nodes = [node('a', 0, 0), node('b', 60, 200)];
    const beforeY = { a: byId(nodes, 'a').position.y, b: byId(nodes, 'b').position.y };
    const out = centerOnCrossAxis(nodes, [edge('a', 'b')], VERTICAL);
    // Vertical flow axis is y: it must be untouched.
    expect(byId(out, 'a').position.y).toBe(beforeY.a);
    expect(byId(out, 'b').position.y).toBe(beforeY.b);
  });

  it('never leaves two nodes in a rank overlapping (the safety invariant)', () => {
    // A fan-out to three children whose centring would pile them onto one point.
    const nodes = [
      node('p', 0, 0),
      node('c1', 0, 200), node('c2', 5, 200), node('c3', 10, 200),
    ];
    const edges = [edge('p', 'c1'), edge('p', 'c2'), edge('p', 'c3')];
    const out = centerOnCrossAxis(nodes, edges, VERTICAL);
    const rank = ['c1', 'c2', 'c3'].map((id) => byId(out, id)).sort((a, b) => cx(a) - cx(b));
    for (let i = 1; i < rank.length; i++) {
      const gapBetween = cx(rank[i]) - cx(rank[i - 1]);
      // Centre-to-centre must clear both half-widths plus the configured gap.
      expect(gapBetween).toBeGreaterThanOrEqual(W + VERTICAL.nodesep - 0.5);
    }
  });

  it('centres on the MEASURED width when present, not the label estimate', () => {
    // The visible mis-centring came from centring estimated widths while nodes paint
    // at their measured width. A measured branch node (451) must centre its VISUAL
    // middle over its children, not its 200-wide estimate.
    const parent = { ...node('p', 0, 0), width: 451, height: 130 } as Node<BuilderNodeData>;
    const c1 = { ...node('c1', -150, 200), width: 300, height: 80 } as Node<BuilderNodeData>;
    const c2 = { ...node('c2', 350, 200), width: 300, height: 80 } as Node<BuilderNodeData>;
    const out = centerOnCrossAxis([parent, c1, c2], [edge('p', 'c1'), edge('p', 'c2')], VERTICAL);
    const p = byId(out, 'p');
    // Visual centre = left + measured/2, using the 451 measured width.
    const visualCenter = p.position.x + 451 / 2;
    const childrenMid = ((byId(out, 'c1').position.x + 300 / 2) + (byId(out, 'c2').position.x + 300 / 2)) / 2;
    expect(visualCenter).toBeCloseTo(childrenMid, 1);
  });

  it('is a no-op for a single node', () => {
    const nodes = [node('solo', 17, 42)];
    const out = centerOnCrossAxis(nodes, [], VERTICAL);
    expect(out[0].position).toEqual({ x: 17, y: 42 });
  });

  it('leaves a merge (multiple parents) to its child-centred position, not stacked under one parent', () => {
    // m has two parents a,b and no children: pass 2 must skip it (parents !== 1),
    // so it keeps dagre's position rather than snapping under a.
    const nodes = [node('a', -200, 0), node('b', 200, 0), node('m', 0, 200)];
    const out = centerOnCrossAxis(nodes, [edge('a', 'm'), edge('b', 'm')], VERTICAL);
    // Stays between the two parents (near x-centre 100 = midpoint of a,b centres), not
    // snapped onto a's centre (-100).
    expect(cx(byId(out, 'm'))).toBeGreaterThan(cx(byId(out, 'a')));
    expect(cx(byId(out, 'm'))).toBeLessThan(cx(byId(out, 'b')));
  });
});

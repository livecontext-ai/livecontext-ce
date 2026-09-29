import { describe, it, expect } from 'vitest';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { applyDagreLayout, getNodeDimensions, layoutConfigForDirection } from '../LayoutService';
import type { WorkflowLayoutDirection } from '@/contexts/WorkflowLayoutDirectionContext';

function node(id: string, label = id): Node<BuilderNodeData> {
  return {
    id,
    type: 'flowNode',
    position: { x: NaN, y: NaN },
    data: { id, label, kind: 'tool' } as BuilderNodeData,
  };
}
const edge = (source: string, target: string, data?: Record<string, unknown>): Edge =>
  ({ id: `${source}->${target}`, source, target, data });

const DIRECTIONS: WorkflowLayoutDirection[] = ['horizontal', 'vertical'];

/** Axis helpers: `cross` is perpendicular to the flow, `flow` along it. */
const axes = (direction: WorkflowLayoutDirection) =>
  direction === 'vertical'
    ? { cross: 'x' as const, flow: 'y' as const, crossSize: 'width' as const, flowSize: 'height' as const }
    : { cross: 'y' as const, flow: 'x' as const, crossSize: 'height' as const, flowSize: 'width' as const };

function crossCenter(n: Node<BuilderNodeData>, direction: WorkflowLayoutDirection): number {
  const a = axes(direction);
  return n.position[a.cross] + getNodeDimensions(n, true, direction)[a.crossSize] / 2;
}

/**
 * trigger -> step -> fanout -> five children: the "five nodes on level 3" case. The
 * children are given labels of different lengths on purpose, which is what used to
 * leave the fan lopsided.
 */
function fiveOnLevelThree() {
  const nodes = [
    node('trigger', 'Webhook'),
    node('step', 'Fetch the order'),
    node('fanout', 'Dispatch'),
    node('c0', 'Email'),
    node('c1', 'Send a Slack message to the team'),
    node('c2', 'CRM'),
    node('c3', 'Update the spreadsheet row'),
    node('c4', 'SMS'),
  ];
  const edges = [
    edge('trigger', 'step'),
    edge('step', 'fanout'),
    ...['c0', 'c1', 'c2', 'c3', 'c4'].map((c) => edge('fanout', c)),
  ];
  return { nodes, edges };
}

describe('balanced auto-layout - a fan-out is symmetric around its parent', () => {
  for (const direction of DIRECTIONS) {
    it(`spreads five children evenly on both sides of their parent (${direction})`, () => {
      const { nodes, edges } = fiveOnLevelThree();
      const laid = applyDagreLayout(nodes, edges, layoutConfigForDirection(direction));
      const byId = new Map(laid.map((n) => [n.id, n]));
      const parent = crossCenter(byId.get('fanout')!, direction);
      const kids = ['c0', 'c1', 'c2', 'c3', 'c4'].map((id) => crossCenter(byId.get(id)!, direction));

      // The middle child sits straight under the parent (the mean would not do: with
      // children of different sizes it lands beside the middle one)...
      expect(Math.abs(kids[2] - parent)).toBeLessThan(1);
      // ...with as many children on one side as on the other (the old horizontal
      // layout hung all five on ONE side).
      const before = kids.filter((c) => c < parent - 1).length;
      const after = kids.filter((c) => c > parent + 1).length;
      expect(before).toBe(2);
      expect(after).toBe(2);
      // The chain above it is one straight line into the middle of the fan.
      expect(Math.abs(crossCenter(byId.get('trigger')!, direction) - parent)).toBeLessThan(1);
      expect(Math.abs(crossCenter(byId.get('step')!, direction) - parent)).toBeLessThan(1);
    });
  }

  for (const direction of DIRECTIONS) {
    // Regression (seen live, left to right): children of different label lengths were split
    // into one "rank" per width, so the overlap guard never compared them, and two branches
    // that meet again at a merge were both pulled onto the merge's line. Result: Email under
    // Slack and SMS under Sheet, drawn on top of each other.
    it(`never stacks two nodes on each other when branches of different sizes meet at a merge (${direction})`, () => {
      const { nodes, edges } = fiveOnLevelThree();
      nodes.push(node('wrap', 'Wrap up'));
      edges.push(edge('c0', 'wrap'), edge('c4', 'wrap'));
      const laid = applyDagreLayout(nodes, edges, layoutConfigForDirection(direction));
      const box = (n: Node<BuilderNodeData>) => ({ ...n.position, ...getNodeDimensions(n, true, direction) });
      for (let i = 0; i < laid.length; i++) {
        for (let j = i + 1; j < laid.length; j++) {
          const a = box(laid[i]);
          const b = box(laid[j]);
          const overlap = a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
          expect(overlap, `${laid[i].id} overlaps ${laid[j].id}`).toBe(false);
        }
      }
      // And the fan stays symmetric, merge or not.
      const byId = new Map(laid.map((n) => [n.id, n]));
      const parent = crossCenter(byId.get('fanout')!, direction);
      const kids = ['c0', 'c1', 'c2', 'c3', 'c4'].map((id) => crossCenter(byId.get(id)!, direction));
      expect(kids.filter((c) => c < parent - 1)).toHaveLength(2);
      expect(kids.filter((c) => c > parent + 1)).toHaveLength(2);
    });
  }

  it('balances the horizontal layout even without an explicit config (marketplace, fleet default)', () => {
    const { nodes, edges } = fiveOnLevelThree();
    const laid = applyDagreLayout(nodes, edges);
    const byId = new Map(laid.map((n) => [n.id, n]));
    const parent = crossCenter(byId.get('fanout')!, 'horizontal');
    const kids = ['c0', 'c1', 'c2', 'c3', 'c4'].map((id) => crossCenter(byId.get(id)!, 'horizontal'));
    expect(kids.filter((c) => c < parent - 1)).toHaveLength(2);
    expect(kids.filter((c) => c > parent + 1)).toHaveLength(2);
  });
});

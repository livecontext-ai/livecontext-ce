import { describe, it, expect } from 'vitest';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { applyDagreLayout, layoutConfigForDirection, placeUnpositionedNodes } from '../LayoutService';
import { WorkflowPlanImporter } from '../workflowPlanImporter/WorkflowPlanImporter';

/**
 * A node the agent creates is saved with no position. The importer used to answer ANY
 * such node with a Dagre pass over the whole graph, so every agent action (and every
 * reopen of a workflow holding an agent-made node) put the user's saved layout back to
 * the automatic one. Reproduced live on 2026-09-23.
 */

const config = layoutConfigForDirection('horizontal');

function node(id: string, position?: { x: number; y: number }): Node<BuilderNodeData> {
  return {
    id,
    type: 'flowNode',
    position: position ?? { x: NaN, y: NaN },
    data: { id, label: id, kind: 'tool' } as BuilderNodeData,
  };
}

const edge = (source: string, target: string): Edge => ({ id: `${source}-${target}`, source, target });

describe('placeUnpositionedNodes', () => {
  it('regression: keeps every stored position when the agent added a node without one', () => {
    const nodes = [node('start', { x: 0, y: 0 }), node('shape', { x: 290, y: 0 }), node('finish', { x: 580, y: 500 }), node('added')];
    const edges = [edge('start', 'shape'), edge('shape', 'finish'), edge('finish', 'added')];

    const { nodes: result } = placeUnpositionedNodes(nodes, edges, config);

    const byId = new Map(result.map((n) => [n.id, n.position]));
    expect(byId.get('start')).toEqual({ x: 0, y: 0 });
    expect(byId.get('shape')).toEqual({ x: 290, y: 0 });
    expect(byId.get('finish')).toEqual({ x: 580, y: 500 });
  });

  it('places a new node at the offset Dagre gives it from its placed neighbour, anchored where the user put it', () => {
    const nodes = [node('a', { x: 1000, y: 700 }), node('b')];
    const edges = [edge('a', 'b')];
    const dagre = new Map(applyDagreLayout(nodes, edges, config).map((n) => [n.id, n.position]));

    const { nodes: result } = placeUnpositionedNodes(nodes, edges, config);

    const b = result.find((n) => n.id === 'b')!;
    expect(b.position).toEqual({
      x: Math.round(1000 + dagre.get('b')!.x - dagre.get('a')!.x),
      y: Math.round(700 + dagre.get('b')!.y - dagre.get('a')!.y),
    });
    expect(b.positionAbsolute).toEqual(b.position);
  });

  it('places a chain of new nodes outward from the placed part of the graph', () => {
    const nodes = [node('a', { x: 1000, y: 700 }), node('b'), node('c')];
    const edges = [edge('a', 'b'), edge('b', 'c')];

    const { nodes: result } = placeUnpositionedNodes(nodes, edges, config);

    const byId = new Map(result.map((n) => [n.id, n.position]));
    // Horizontal flow: each hop goes further right of the user's node, not back to Dagre's origin.
    expect(byId.get('b')!.x).toBeGreaterThan(1000);
    expect(byId.get('c')!.x).toBeGreaterThan(byId.get('b')!.x);
  });

  it('places a new node with no placed neighbour (a note) below the user layout, not on top of it', () => {
    const nodes = [node('a', { x: 300, y: 200 }), node('b', { x: 600, y: 400 }), node('note')];

    const { nodes: result } = placeUnpositionedNodes(nodes, [edge('a', 'b')], config);

    const note = result.find((n) => n.id === 'note')!;
    expect(note.position.x).toBe(300);
    // Below the lowest placed node's bottom edge, plus the along-flow gap (ranksep 90).
    expect(note.position.y).toBeGreaterThanOrEqual(400 + 80 + 90);
  });

  it('keeps the internal layout of a new group that has no placed neighbour', () => {
    const nodes = [node('a', { x: 0, y: 0 }), node('n1'), node('n2')];
    const edges = [edge('n1', 'n2')];
    const dagre = new Map(applyDagreLayout(nodes, edges, config).map((n) => [n.id, n.position]));

    const { nodes: result } = placeUnpositionedNodes(nodes, edges, config);

    const byId = new Map(result.map((n) => [n.id, n.position]));
    expect(byId.get('n2')!.x - byId.get('n1')!.x).toBe(Math.round(dagre.get('n2')!.x) - Math.round(dagre.get('n1')!.x));
  });

  it('lays out the whole graph when no node has a position, and says so', () => {
    const nodes = [node('a'), node('b')];

    const result = placeUnpositionedNodes(nodes, [edge('a', 'b')], config);

    expect(result.laidOutFromScratch).toBe(true);
    expect(result.nodes.every((n) => Number.isFinite(n.position.x))).toBe(true);
  });

  it('returns the nodes untouched when every node has a position', () => {
    const nodes = [node('a', { x: 5, y: 6 }), node('b', { x: 300, y: 6 })];

    const result = placeUnpositionedNodes(nodes, [edge('a', 'b')], config);

    expect(result.nodes).toBe(nodes);
    expect(result.laidOutFromScratch).toBe(false);
  });

  it('a partial placement is not reported as laid out from scratch', () => {
    const result = placeUnpositionedNodes([node('a', { x: 0, y: 0 }), node('b')], [edge('a', 'b')], config);

    expect(result.laidOutFromScratch).toBe(false);
  });
});

describe('WorkflowPlanImporter keeps saved positions', () => {
  it('regression: reopening a plan with one agent-made node keeps the positions the user saved', async () => {
    const plan = {
      name: 'Position Save Test',
      triggers: [{ id: 'start', label: 'Start', type: 'manual', position: { x: 0, y: 0 } }],
      cores: [
        { id: 'core:shape', label: 'Shape', type: 'transform', position: { x: 290, y: 0 }, transform: { mappings: [] } },
        { id: 'core:finish', label: 'Finish', type: 'transform', position: { x: 580, y: 500 }, transform: { mappings: [] } },
        { id: 'core:added', label: 'Added', type: 'transform', position: {}, transform: { mappings: [] } },
      ],
      mcps: [],
      edges: [
        { from: 'trigger:start', to: 'core:shape' },
        { from: 'core:shape', to: 'core:finish' },
        { from: 'core:finish', to: 'core:added' },
      ],
    };

    const result = await WorkflowPlanImporter.importPlan(JSON.stringify(plan), [], { fallbackDirection: 'horizontal' });

    expect(result.success).toBe(true);
    expect(result.laidOutFromScratch).toBe(false);
    const finish = result.nodes.find((n) => n.data.label === 'Finish')!;
    expect(finish.position).toEqual({ x: 580, y: 500 });
    const added = result.nodes.find((n) => n.data.label === 'Added')!;
    expect(Number.isFinite(added.position.x)).toBe(true);
  });
});

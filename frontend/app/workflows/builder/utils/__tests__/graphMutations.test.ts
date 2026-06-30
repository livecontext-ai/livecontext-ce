import { describe, it, expect } from 'vitest';
import type { Edge, Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import {
  cloneNodesForPaste,
  computeDownstreamNodeIds,
  nodeHasConnections,
  removeEdgesTouchingNodes,
} from '../graphMutations';

const node = (id: string, extra: Partial<Node<BuilderNodeData>> = {}): Node<BuilderNodeData> => ({
  id,
  type: 'flowNode',
  position: { x: 0, y: 0 },
  data: { id, label: id, kind: 'action' } as BuilderNodeData,
  ...extra,
});

const edge = (source: string, target: string, extra: Partial<Edge> = {}): Edge => ({
  id: `edge-${source}-${target}`,
  source,
  target,
  ...extra,
});

describe('computeDownstreamNodeIds', () => {
  it('returns all forward-reachable descendants, excluding the root', () => {
    const edges = [edge('A', 'B'), edge('B', 'C'), edge('B', 'D'), edge('X', 'A')];
    expect(computeDownstreamNodeIds('A', edges).sort()).toEqual(['B', 'C', 'D']);
  });

  it('returns an empty array for a leaf node', () => {
    expect(computeDownstreamNodeIds('C', [edge('A', 'B'), edge('B', 'C')])).toEqual([]);
  });

  it('skips back-edges so loop-backs do not pull the whole graph in', () => {
    const edges = [edge('A', 'B'), edge('B', 'A', { data: { isBackEdge: true } })];
    expect(computeDownstreamNodeIds('A', edges)).toEqual(['B']);
  });

  it('terminates on a forward cycle without revisiting', () => {
    const edges = [edge('A', 'B'), edge('B', 'C'), edge('C', 'B')];
    expect(computeDownstreamNodeIds('A', edges).sort()).toEqual(['B', 'C']);
  });
});

describe('nodeHasConnections', () => {
  it('is true when the node is a source or a target', () => {
    const edges = [edge('A', 'B')];
    expect(nodeHasConnections('A', edges)).toBe(true);
    expect(nodeHasConnections('B', edges)).toBe(true);
    expect(nodeHasConnections('C', edges)).toBe(false);
  });
});

describe('removeEdgesTouchingNodes', () => {
  it('removes every edge that touches any of the given nodes', () => {
    const edges = [edge('A', 'B'), edge('B', 'C'), edge('C', 'D')];
    const result = removeEdgesTouchingNodes(edges, new Set(['B']));
    expect(result.map((e) => e.id)).toEqual(['edge-C-D']);
  });
});

describe('cloneNodesForPaste', () => {
  it('assigns fresh seeded IDs and keeps only edges internal to the copied set', () => {
    const nodes = [node('A'), node('B')];
    const edges = [edge('A', 'B'), edge('X', 'A')];
    const result = cloneNodesForPaste(nodes, edges, { seed: 'S' });

    expect(result.nodes.map((n) => n.id)).toEqual(['A-S-0', 'B-S-1']);
    expect(result.newIds).toEqual(['A-S-0', 'B-S-1']);
    // External edge X->A dropped; internal A->B remapped to the new ids.
    expect(result.edges).toHaveLength(1);
    expect(result.edges[0].source).toBe('A-S-0');
    expect(result.edges[0].target).toBe('B-S-1');
  });

  it('applies the default +40,+40 offset when no position is given', () => {
    const nodes = [node('A', { position: { x: 100, y: 200 } })];
    const result = cloneNodesForPaste(nodes, [], { seed: 'S' });
    expect(result.nodes[0].position).toEqual({ x: 140, y: 240 });
  });

  it('translates the group so its top-left lands at the paste position', () => {
    const nodes = [
      node('A', { position: { x: 100, y: 100 } }),
      node('B', { position: { x: 140, y: 180 } }),
    ];
    const result = cloneNodesForPaste(nodes, [], { seed: 'S', position: { x: 0, y: 0 } });
    // min is (100,100) -> A maps to (0,0), B keeps its relative offset (40,80).
    expect(result.nodes[0].position).toEqual({ x: 0, y: 0 });
    expect(result.nodes[1].position).toEqual({ x: 40, y: 80 });
  });

  it('strips runtime callback props from cloned data', () => {
    const dirty = node('A');
    (dirty.data as unknown as Record<string, unknown>).onDeleteNode = () => {};
    const result = cloneNodesForPaste([dirty], [], { seed: 'S' });
    expect((result.nodes[0].data as unknown as Record<string, unknown>).onDeleteNode).toBeUndefined();
    expect(result.nodes[0].data.id).toBe('A-S-0');
  });

  it('clears isEntryInterface on the copy (only one entry interface allowed)', () => {
    const entry = node('iface');
    (entry.data as Record<string, any>).interfaceData = { isEntryInterface: true, interfaceId: 7 };
    const result = cloneNodesForPaste([entry], [], { seed: 'S' });
    expect((result.nodes[0].data as Record<string, any>).interfaceData.isEntryInterface).toBe(false);
    expect((result.nodes[0].data as Record<string, any>).interfaceData.interfaceId).toBe(7);
  });

  it('remaps node IDs embedded in port handles', () => {
    const nodes = [node('A'), node('B')];
    const edges = [edge('A', 'B', { sourceHandle: 'split-A-exit', targetHandle: 'input_1' })];
    const result = cloneNodesForPaste(nodes, edges, { seed: 'S' });
    expect(result.edges[0].sourceHandle).toBe('split-A-S-0-exit');
    expect(result.edges[0].targetHandle).toBe('input_1');
  });

  it('returns empty results for an empty input', () => {
    expect(cloneNodesForPaste([], [], { seed: 'S' })).toEqual({ nodes: [], edges: [], newIds: [] });
  });
});

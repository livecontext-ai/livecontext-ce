import { describe, it, expect } from 'vitest';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '@/app/workflows/builder/types';
import {
  aggregateContainerStatusCounts,
  colorEdgesByStatus,
  isFleetContainerId,
} from '../fleetStatusAggregation';

function node(id: string, statusCounts?: Record<string, number>): Node<BuilderNodeData> {
  return {
    id,
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: { id, label: id, kind: 'tool', ...(statusCounts ? { statusCounts } : {}) } as any,
  };
}
const edge = (source: string, target: string, data?: Record<string, any>): Edge =>
  ({ id: `${source}->${target}`, source, target, ...(data ? { data } : {}) }) as Edge;

const sc = (n: Node<BuilderNodeData>) => (n.data as any).statusCounts as Record<string, number> | undefined;

describe('isFleetContainerId', () => {
  it('flags grouping nodes only', () => {
    expect(isFleetContainerId('category-a-table')).toBe(true);
    expect(isFleetContainerId('provider-a-google')).toBe(true);
    expect(isFleetContainerId('folder-a-f1')).toBe(true);
    expect(isFleetContainerId('agg-agent-1')).toBe(true);
    expect(isFleetContainerId('res-a-table-A')).toBe(false);
    expect(isFleetContainerId('agent-1')).toBe(false);
  });
});

describe('aggregateContainerStatusCounts', () => {
  it('sums a category group over its table leaves (A=1 + B=2 → 3)', () => {
    const cat = node('category-agent-1-table');
    const a = node('res-agent-1-table-A', { COMPLETED: 1 });
    const b = node('res-agent-1-table-B', { COMPLETED: 2 });
    const nodes = [node('agent-1'), cat, a, b];
    const edges = [
      edge('agent-1', 'category-agent-1-table', { category: 'resources' }),
      edge('category-agent-1-table', 'res-agent-1-table-A'),
      edge('category-agent-1-table', 'res-agent-1-table-B'),
    ];

    aggregateContainerStatusCounts(nodes, edges);

    expect(sc(cat)).toEqual({ COMPLETED: 3 });
    // leaves untouched
    expect(sc(a)).toEqual({ COMPLETED: 1 });
    expect(sc(b)).toEqual({ COMPLETED: 2 });
  });

  it('sums a provider over its tool children, mixing completed + failed', () => {
    const prov = node('provider-agent-1-google');
    const t1 = node('res-agent-1-tool-1', { COMPLETED: 2 });
    const t2 = node('res-agent-1-tool-2', { FAILED: 3 });
    const nodes = [node('agent-1'), prov, t1, t2];
    const edges = [
      edge('agent-1', 'provider-agent-1-google', { category: 'tools' }),
      edge('provider-agent-1-google', 'res-agent-1-tool-1'),
      edge('provider-agent-1-google', 'res-agent-1-tool-2'),
    ];

    aggregateContainerStatusCounts(nodes, edges);

    expect(sc(prov)).toEqual({ COMPLETED: 2, FAILED: 3 });
  });

  it('rolls a nested folder → sub-folder → skills hierarchy all the way up', () => {
    const root = node('folder-agent-1-root');
    const sub = node('folder-agent-1-sub');
    const s1 = node('res-agent-1-skill-1', { COMPLETED: 1 });
    const s2 = node('res-agent-1-skill-2', { COMPLETED: 4 });
    const nodes = [node('agent-1'), root, sub, s1, s2];
    const edges = [
      edge('agent-1', 'folder-agent-1-root', { category: 'skills' }),
      edge('folder-agent-1-root', 'res-agent-1-skill-1'),
      edge('folder-agent-1-root', 'folder-agent-1-sub'),
      edge('folder-agent-1-sub', 'res-agent-1-skill-2'),
    ];

    aggregateContainerStatusCounts(nodes, edges);

    expect(sc(sub)).toEqual({ COMPLETED: 4 });
    expect(sc(root)).toEqual({ COMPLETED: 5 }); // 1 (direct skill) + 4 (sub-folder)
  });

  it('never folds an agent node - agent run stats are left untouched and not summed in', () => {
    const agent = node('agent-1', { COMPLETED: 99 });
    const cat = node('category-agent-1-table');
    const a = node('res-agent-1-table-A', { COMPLETED: 1 });
    const nodes = [agent, cat, a];
    const edges = [
      edge('agent-1', 'category-agent-1-table', { category: 'resources' }),
      edge('category-agent-1-table', 'res-agent-1-table-A'),
    ];

    aggregateContainerStatusCounts(nodes, edges);

    expect(sc(agent)).toEqual({ COMPLETED: 99 }); // not overwritten
    expect(sc(cat)).toEqual({ COMPLETED: 1 }); // does NOT include the agent's 99
  });

  it('sums BUDGET_EXHAUSTED independently (stays a subset of FAILED)', () => {
    const prov = node('provider-agent-1-x');
    const t1 = node('res-agent-1-tool-1', { FAILED: 2, BUDGET_EXHAUSTED: 1 });
    const t2 = node('res-agent-1-tool-2', { FAILED: 1, BUDGET_EXHAUSTED: 1 });
    const nodes = [node('agent-1'), prov, t1, t2];
    const edges = [
      edge('agent-1', 'provider-agent-1-x', { category: 'tools' }),
      edge('provider-agent-1-x', 'res-agent-1-tool-1'),
      edge('provider-agent-1-x', 'res-agent-1-tool-2'),
    ];

    aggregateContainerStatusCounts(nodes, edges);

    expect(sc(prov)).toEqual({ FAILED: 3, BUDGET_EXHAUSTED: 2 });
  });
});

describe('colorEdgesByStatus', () => {
  it('stamps the agent → Resources(N) edge from the aggregator node counts (green when all completed)', () => {
    const agg = node('agg-agent-1', { COMPLETED: 3 });
    const nodes = [node('agent-1'), agg];
    const e = edge('agent-1', 'agg-agent-1', { category: 'resources' });
    const edges = [e];

    colorEdgesByStatus(nodes, edges);

    expect((e.data as any).statusCounts).toEqual({ COMPLETED: 3 });
    expect((e.style as any).stroke).toBe('#10b981');
    expect(e.markerEnd).toBe('url(#arrow-completed)');
  });

  it('strokes amber for a mixed completed + failed target', () => {
    const cat = node('category-agent-1-table', { COMPLETED: 2, FAILED: 1 });
    const e = edge('agent-1', 'category-agent-1-table', { category: 'resources' });
    colorEdgesByStatus([node('agent-1'), cat], [e]);
    expect((e.style as any).stroke).toBe('#f59e0b');
    expect(e.markerEnd).toBe('url(#arrow-partial_success)');
  });

  it('keeps a sub-agent edge on its OWN counts, never the target node total', () => {
    const callee = node('agent-2', { COMPLETED: 99 });
    const e = edge('agent-1', 'agent-2', { category: 'sub-agents', statusCounts: { COMPLETED: 1, FAILED: 1 } });
    colorEdgesByStatus([node('agent-1'), callee], [e]);
    expect((e.data as any).statusCounts).toEqual({ COMPLETED: 1, FAILED: 1 }); // unchanged
    expect((e.style as any).stroke).toBe('#f59e0b');
  });

  it('leaves edges to count-less / zero-count targets alone', () => {
    const leaf = node('res-agent-1-tool-1'); // no counts
    const e = edge('category-agent-1-x', 'res-agent-1-tool-1');
    colorEdgesByStatus([leaf], [e]);
    expect(e.style).toBeUndefined();
    expect(e.markerEnd).toBeUndefined();
  });
});

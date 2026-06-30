/**
 * Edge coherence regression: a FAILED source node's outgoing edges must render
 * as `failed` (red), not `skipped` (grey).
 *
 * Found in the 2026-06-23 prod test session: when a node errors (e.g. a failing
 * HTTP node, or a guardrail that hit a credit/error), the engine persists its
 * outgoing edges as `skipped` in StateSnapshot (that skipped count is
 * load-bearing for merge convergence and MUST stay skipped there). But a grey
 * "skipped" edge is visually identical to a branch a SUCCEEDING node simply
 * didn't take, so a failed node looked indistinguishable from a passing one and
 * a user asked "why is the edge [near the failure] coloured like a normal skip".
 *
 * Fix: in updateEdgesFromBatch, when the source node itself failed, recolour its
 * skipped outgoing edges to `failed`. Pure presentation - convergence untouched.
 */

import { describe, it, expect } from 'vitest';
import type { Node, Edge } from 'reactflow';
import { updateEdgesFromBatch, computeNodeBackendKey, type BatchEdgeData } from '../edgeStatusService';
import type { BuilderNodeData, NodeStatus } from '../../types';

function makeNode(id: string, label: string, kind: string, status?: NodeStatus): Node<BuilderNodeData> {
  return {
    id,
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: { id, label, kind, status } as BuilderNodeData,
  };
}

function makeEdge(source: string, target: string): Edge {
  return { id: `${source}->${target}`, source, target };
}

describe('updateEdgesFromBatch - failed source node edge coherence', () => {
  const target = makeNode('save-1', 'Save Record', 'action');

  // The backend marks a failed node's outgoing edge as skipped:1. Derive the
  // from/to refs from the real node-key mapper so the match is faithful.
  function skippedBatch(source: Node<BuilderNodeData>): BatchEdgeData[] {
    const from = computeNodeBackendKey(source);
    const to = computeNodeBackendKey(target);
    // Guard: if the node shape ever stops producing a key, fail loudly rather
    // than silently no-matching (which would make the assertions meaningless).
    expect(from).toBeTruthy();
    expect(to).toBeTruthy();
    return [{ id: 'be1', from: from!, to: to!, running: 0, completed: 0, skipped: 1 }];
  }

  it('renders a FAILED source node\'s skipped outgoing edge as `failed` (red)', () => {
    const source = makeNode('fetch-1', 'Fetch Data', 'action', 'failed');
    const edges = [makeEdge('fetch-1', 'save-1')];
    const result = updateEdgesFromBatch(edges, skippedBatch(source), [source, target]);
    expect(result[0].data?.status).toBe('failed');
  });

  it('keeps a skipped edge `skipped` (grey) when the source node SUCCEEDED (normal unmet branch)', () => {
    const source = makeNode('fetch-1', 'Fetch Data', 'action', 'completed');
    const edges = [makeEdge('fetch-1', 'save-1')];
    const result = updateEdgesFromBatch(edges, skippedBatch(source), [source, target]);
    expect(result[0].data?.status).toBe('skipped');
  });

  it('does not touch a completed edge even when the source failed (only skipped edges recolour)', () => {
    const source = makeNode('fetch-1', 'Fetch Data', 'action', 'failed');
    const from = computeNodeBackendKey(source)!;
    const to = computeNodeBackendKey(target)!;
    const completedBatch: BatchEdgeData[] = [{ id: 'be1', from, to, running: 0, completed: 1, skipped: 0 }];
    const edges = [makeEdge('fetch-1', 'save-1')];
    const result = updateEdgesFromBatch(edges, completedBatch, [source, target]);
    expect(result[0].data?.status).toBe('completed');
  });
});

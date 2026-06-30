// @vitest-environment jsdom
/**
 * Regression tests for the bug-2 fix (audit 2026-05-09) on
 * {@link useEpochStateViewing}.
 *
 * Bug 2 user-reported: clicking an epoch after a workflow completes still
 * shows aggregate ("all epochs") status counts on the canvas instead of
 * per-epoch counts. Refresh "fixes" it because nodesRef.current starts
 * empty post-refresh, so nothing stale shows during the async fetch.
 *
 * Fix: pre-reset canvas counts SYNCHRONOUSLY on epoch CHANGE (not on every
 * effect re-run - gated to viewingEpoch transition only, so SSE-driven
 * snapshotSeq ticks don't blink the canvas).
 *
 * These tests pin two contracts:
 *   1. preResetClearsCanvasCountsBeforeFetchResolves - after epoch click,
 *      setNodes is called synchronously with cleared statusCounts BEFORE
 *      the deferred fetch resolves.
 *   2. preResetSkippedOnSnapshotSeqTickWhileSameEpoch - when snapshotSeq
 *      increments while viewingEpoch stays the same, the pre-reset MUST
 *      NOT fire (no canvas flicker on SSE updates).
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import type { Node, Edge } from 'reactflow';

const getEpochState = vi.fn();
const getEpochSignals = vi.fn();

vi.mock('@/lib/api', () => ({
  orchestratorApi: {
    getEpochState: (...args: unknown[]) => getEpochState(...args),
    getEpochSignals: (...args: unknown[]) => getEpochSignals(...args),
  },
}));

vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: {
    getShowcaseEpochState: vi.fn(),
    getShowcaseEpochSignals: vi.fn(),
  },
}));

vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: () => null,
}));

vi.mock('@/contexts/workflow-run/streamingDebug', () => ({
  streamDebug: { log: vi.fn(), warn: vi.fn() },
}));

import { useEpochStateViewing } from '../useEpochStateViewing';

type AnyNode = Node<{ label: string; status?: unknown; statusCounts?: unknown; selectedBranch?: unknown }>;

function makeNode(id: string, label: string, statusCounts?: Record<string, number>): AnyNode {
  return {
    id,
    type: 'flow',
    position: { x: 0, y: 0 },
    data: {
      label,
      status: 'completed',
      statusCounts: statusCounts ?? { COMPLETED: 5, FAILED: 1 },
    },
  } as AnyNode;
}

describe('useEpochStateViewing - pre-reset on epoch change (audit MF-1/MF-2 fix)', () => {
  beforeEach(() => {
    getEpochState.mockReset();
    getEpochSignals.mockReset();
    getEpochSignals.mockResolvedValue([]);
  });

  it('preResetClearsCanvasCountsBeforeFetchResolves', async () => {
    // Aggregate-counted nodes representing a completed workflow.
    const initialNodes: AnyNode[] = [
      makeNode('n1', 'Step One', { COMPLETED: 5 }),
      makeNode('n2', 'Step Two', { COMPLETED: 3, FAILED: 1 }),
    ];
    const initialEdges: Edge[] = [
      { id: 'e1', source: 'n1', target: 'n2', data: { status: 'completed', statusCounts: { COMPLETED: 1 } } },
    ];

    const nodesRef = { current: [...initialNodes] };
    const edgesRef = { current: [...initialEdges] };
    const setNodes = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(nodesRef.current) : updater;
      nodesRef.current = next;
    });
    const setEdges = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(edgesRef.current) : updater;
      edgesRef.current = next;
    });

    // Deferred - fetch hangs until we resolve it manually.
    let resolveFetch: (value: unknown) => void = () => {};
    const fetchPromise = new Promise(res => { resolveFetch = res; });
    getEpochState.mockReturnValue(fetchPromise);

    // Initial render: viewingEpoch = null (live mode), no pre-reset expected.
    const { rerender } = renderHook(
      ({ viewingEpoch, snapshotSeq }) =>
        useEpochStateViewing({
          viewingEpoch,
          runId: 'run-1',
          nodesRef: nodesRef as any,
          edgesRef: edgesRef as any,
          setNodes: setNodes as any,
          setEdges: setEdges as any,
          workflowLoaded: true,
          snapshotSeq,
        }),
      { initialProps: { viewingEpoch: null as number | null, snapshotSeq: 0 } },
    );

    // No setNodes call yet - viewingEpoch is null.
    expect(setNodes).not.toHaveBeenCalled();

    // User clicks epoch 5 → viewingEpoch transitions null → 5.
    rerender({ viewingEpoch: 5, snapshotSeq: 0 });

    // Synchronously, BEFORE the deferred fetch resolves, the pre-reset MUST
    // have cleared statusCounts on every node (and edge).
    expect(setNodes).toHaveBeenCalled();
    const firstSetNodesCall = setNodes.mock.calls[0][0];
    expect(typeof firstSetNodesCall).toBe('function');
    const resetNodes = firstSetNodesCall(initialNodes);
    expect(resetNodes).toHaveLength(2);
    for (const node of resetNodes as AnyNode[]) {
      expect(node.data.statusCounts).toBeUndefined();
      expect(node.data.status).toBeUndefined();
      expect(node.data.selectedBranch).toBeUndefined();
    }

    // Edges also reset.
    expect(setEdges).toHaveBeenCalled();
    const firstSetEdgesCall = setEdges.mock.calls[0][0];
    const resetEdges = firstSetEdgesCall(initialEdges);
    expect((resetEdges[0] as Edge).data.status).toBeUndefined();
    expect((resetEdges[0] as Edge).data.statusCounts).toBeUndefined();

    // Now resolve the fetch - applyEpochState runs and sets epoch-specific counts.
    await act(async () => {
      resolveFetch({ nodes: { 'mcp:step_one': { COMPLETED: 1 } }, edges: {} });
      // Drain microtasks so applyEpochState completes.
      await new Promise(r => setTimeout(r, 0));
    });

    // Final state: epoch-specific overlay applied. setNodes called at least
    // twice (1 pre-reset + 1 apply).
    await waitFor(() => expect(setNodes.mock.calls.length).toBeGreaterThanOrEqual(2));
  });

  it('preResetFiresWhenReEnteringSameEpochAfterLive', async () => {
    // MF-4 (audit 2026-05-09 round-2): re-clicking the SAME epoch after
    // returning to live mode (null) MUST trigger the pre-reset again.
    // Without resetting prevViewingEpochRef on the live-restore path,
    // sequence (null → 5 → null → 5) skips the reset on the third
    // transition because prev==5 still equals viewingEpoch==5.
    const initialNodes: AnyNode[] = [makeNode('n1', 'Step One', { COMPLETED: 5 })];
    const initialEdges: Edge[] = [];

    const nodesRef = { current: [...initialNodes] };
    const edgesRef = { current: [...initialEdges] };
    const setNodes = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(nodesRef.current) : updater;
      nodesRef.current = next;
    });
    const setEdges = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(edgesRef.current) : updater;
      edgesRef.current = next;
    });

    getEpochState.mockResolvedValue({ nodes: {}, edges: {} });

    const { rerender } = renderHook(
      ({ viewingEpoch, snapshotSeq }) =>
        useEpochStateViewing({
          viewingEpoch,
          runId: 'run-1',
          nodesRef: nodesRef as any,
          edgesRef: edgesRef as any,
          setNodes: setNodes as any,
          setEdges: setEdges as any,
          workflowLoaded: true,
          snapshotSeq,
        }),
      { initialProps: { viewingEpoch: null as number | null, snapshotSeq: 0 } },
    );

    // Step 1: enter epoch 5 → reset fires.
    rerender({ viewingEpoch: 5, snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });
    const callsAfterStep1 = setNodes.mock.calls.filter(c => typeof c[0] === 'function').length;
    expect(callsAfterStep1).toBeGreaterThan(0);

    // Step 2: return to live (null). The live-restore branch runs;
    // prevViewingEpochRef MUST be reset to null so step 3 re-fires the gate.
    setNodes.mockClear();
    setEdges.mockClear();
    rerender({ viewingEpoch: null, snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    // Step 3: re-click epoch 5 - pre-reset MUST fire again because the
    // user transitioned through live mode.
    setNodes.mockClear();
    setEdges.mockClear();
    rerender({ viewingEpoch: 5, snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    const reEntryFunctionalCalls = setNodes.mock.calls.filter(c => typeof c[0] === 'function').length;
    expect(reEntryFunctionalCalls).toBeGreaterThan(0);
  });

  it('preResetFiresWhenReEnteringEpochAfterEditModeToggle', async () => {
    // MF-4 (audit round-3 2026-05-09): the live-restore branch resets
    // prevViewingEpochRef even when there's no runId (edit-mode toggle).
    // Without resetting in this no-runId branch, returning to run-mode
    // and re-selecting the same epoch as before the toggle would skip
    // the pre-reset because the gate would still see prev==N.
    //
    // Sequence: viewingEpoch=5 (in run-mode) → toggle to edit (runId
    // undefined, viewingEpoch=null) → back to run-mode → click epoch 5.
    const initialNodes: AnyNode[] = [makeNode('n1', 'Step One')];
    const nodesRef = { current: [...initialNodes] };
    const edgesRef = { current: [] as Edge[] };
    const setNodes = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(nodesRef.current) : updater;
      nodesRef.current = next;
    });
    const setEdges = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(edgesRef.current) : updater;
      edgesRef.current = next;
    });

    getEpochState.mockResolvedValue({ nodes: {}, edges: {} });

    const { rerender } = renderHook(
      ({ viewingEpoch, runId, snapshotSeq }) =>
        useEpochStateViewing({
          viewingEpoch,
          runId,
          nodesRef: nodesRef as any,
          edgesRef: edgesRef as any,
          setNodes: setNodes as any,
          setEdges: setEdges as any,
          workflowLoaded: true,
          snapshotSeq,
        }),
      { initialProps: { viewingEpoch: null as number | null, runId: 'run-1' as string | undefined, snapshotSeq: 0 } },
    );

    // Step 1: enter epoch 5 in run-mode → reset fires.
    rerender({ viewingEpoch: 5, runId: 'run-1', snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    // Step 2: user toggles to edit-mode → runId becomes undefined,
    // viewingEpoch becomes null. This hits the early-return no-runId
    // branch where MF-4 must still set prev=null.
    setNodes.mockClear();
    setEdges.mockClear();
    rerender({ viewingEpoch: null, runId: undefined, snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    // Step 3: user goes back to run-mode and re-clicks epoch 5.
    // Pre-reset MUST fire because prev was reset to null on step 2.
    setNodes.mockClear();
    setEdges.mockClear();
    rerender({ viewingEpoch: 5, runId: 'run-1', snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    const reEntryFunctionalCalls = setNodes.mock.calls.filter(c => typeof c[0] === 'function').length;
    expect(reEntryFunctionalCalls).toBeGreaterThan(0);
  });

  it('runningNodeIdsFromEpochStateSetsNodeStatusToRunning', async () => {
    // Per-epoch shimmer regression: backend ships `runningNodeIds` in the
    // epoch state response (WorkflowEpochService merges JSONB + Redis live
    // counts), and the frontend must map those keys back to the canvas
    // nodes and set data.status='running' so FlowNode renders the shimmer
    // in per-epoch viewing - the all-epochs path uses RunStateStore.runningSteps,
    // but that store is epoch-agnostic so per-epoch needs its own source.
    const initialNodes: AnyNode[] = [makeNode('n1', 'Step One', undefined)];
    const nodesRef = { current: [...initialNodes] };
    const edgesRef = { current: [] as Edge[] };
    const setNodes = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(nodesRef.current) : updater;
      nodesRef.current = next;
    });
    const setEdges = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(edgesRef.current) : updater;
      edgesRef.current = next;
    });

    getEpochState.mockResolvedValue({
      nodes: {},
      edges: {},
      runningNodeIds: ['mcp:step_one'],
    });

    const { rerender } = renderHook(
      ({ viewingEpoch, snapshotSeq }) =>
        useEpochStateViewing({
          viewingEpoch,
          runId: 'run-1',
          nodesRef: nodesRef as any,
          edgesRef: edgesRef as any,
          setNodes: setNodes as any,
          setEdges: setEdges as any,
          workflowLoaded: true,
          snapshotSeq,
        }),
      { initialProps: { viewingEpoch: null as number | null, snapshotSeq: 0 } },
    );

    rerender({ viewingEpoch: 5, snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    await waitFor(() => {
      expect(nodesRef.current[0].data.status).toBe('running');
    });
  });

  it('runningNodeIdsOverrideTerminalCountsForSameNode', async () => {
    // Split node with mixed state: backend reports terminal counts (some items
    // completed) AND `runningNodeIds` (items still in flight). The shimmer must
    // win so the user sees the node is still processing - matches the all-epochs
    // semantic where deriveStatusFromCounts returns 'running' when running > 0
    // AND processed < total.
    const initialNodes: AnyNode[] = [makeNode('n1', 'Step One', undefined)];
    const nodesRef = { current: [...initialNodes] };
    const edgesRef = { current: [] as Edge[] };
    const setNodes = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(nodesRef.current) : updater;
      nodesRef.current = next;
    });
    const setEdges = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(edgesRef.current) : updater;
      edgesRef.current = next;
    });

    getEpochState.mockResolvedValue({
      nodes: { 'mcp:step_one': { COMPLETED: 2 } },
      edges: {},
      runningNodeIds: ['mcp:step_one'],
    });

    const { rerender } = renderHook(
      ({ viewingEpoch, snapshotSeq }) =>
        useEpochStateViewing({
          viewingEpoch,
          runId: 'run-1',
          nodesRef: nodesRef as any,
          edgesRef: edgesRef as any,
          setNodes: setNodes as any,
          setEdges: setEdges as any,
          workflowLoaded: true,
          snapshotSeq,
        }),
      { initialProps: { viewingEpoch: null as number | null, snapshotSeq: 0 } },
    );

    rerender({ viewingEpoch: 5, snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    await waitFor(() => {
      // statusCounts come from the terminal counts loop (preserved), status
      // comes from the running overlay (overrides 'completed').
      expect(nodesRef.current[0].data.status).toBe('running');
      expect(nodesRef.current[0].data.statusCounts).toEqual({ COMPLETED: 2 });
    });
  });

  it('runningNodeIdsHandlesCoreAndAgentPrefixes', async () => {
    // Backend can emit any prefix in runningNodeIds (mcp:, core:, agent:, table:,
    // interface:, trigger:, note:). The overlay must round-trip through
    // extractNormalizedLabelFromKey for every prefix so per-epoch shimmer is not
    // accidentally scoped to mcp: nodes only. Cover the two prefixes whose key
    // extraction uses specialized helpers (core / agent) - the others share the
    // generic substring branch.
    const initialNodes: AnyNode[] = [
      makeNode('n1', 'My Decision', undefined),
      makeNode('n2', 'Ai Sorter', undefined),
    ];
    const nodesRef = { current: [...initialNodes] };
    const edgesRef = { current: [] as Edge[] };
    const setNodes = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(nodesRef.current) : updater;
      nodesRef.current = next;
    });
    const setEdges = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(edgesRef.current) : updater;
      edgesRef.current = next;
    });

    getEpochState.mockResolvedValue({
      nodes: {},
      edges: {},
      runningNodeIds: ['core:my_decision', 'agent:ai_sorter'],
    });

    const { rerender } = renderHook(
      ({ viewingEpoch, snapshotSeq }) =>
        useEpochStateViewing({
          viewingEpoch,
          runId: 'run-1',
          nodesRef: nodesRef as any,
          edgesRef: edgesRef as any,
          setNodes: setNodes as any,
          setEdges: setEdges as any,
          workflowLoaded: true,
          snapshotSeq,
        }),
      { initialProps: { viewingEpoch: null as number | null, snapshotSeq: 0 } },
    );

    rerender({ viewingEpoch: 5, snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    await waitFor(() => {
      expect(nodesRef.current[0].data.status).toBe('running');
      expect(nodesRef.current[1].data.status).toBe('running');
    });
  });

  it('emptyRunningNodeIdsLeavesTerminalStatusUntouched', async () => {
    // Closed epoch path: backend drains runningNodeIds at close, so the field
    // arrives empty (or missing). Terminal counts must still derive their
    // status normally - guards against a regression where the overlay clobbers
    // legitimate completed/failed counts on historical epochs.
    const initialNodes: AnyNode[] = [makeNode('n1', 'Step One', undefined)];
    const nodesRef = { current: [...initialNodes] };
    const edgesRef = { current: [] as Edge[] };
    const setNodes = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(nodesRef.current) : updater;
      nodesRef.current = next;
    });
    const setEdges = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(edgesRef.current) : updater;
      edgesRef.current = next;
    });

    getEpochState.mockResolvedValue({
      nodes: { 'mcp:step_one': { COMPLETED: 3 } },
      edges: {},
      runningNodeIds: [],
    });

    const { rerender } = renderHook(
      ({ viewingEpoch, snapshotSeq }) =>
        useEpochStateViewing({
          viewingEpoch,
          runId: 'run-1',
          nodesRef: nodesRef as any,
          edgesRef: edgesRef as any,
          setNodes: setNodes as any,
          setEdges: setEdges as any,
          workflowLoaded: true,
          snapshotSeq,
        }),
      { initialProps: { viewingEpoch: null as number | null, snapshotSeq: 0 } },
    );

    rerender({ viewingEpoch: 5, snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    await waitFor(() => {
      expect(nodesRef.current[0].data.status).toBe('completed');
    });
  });

  it('activeEpochOverlaysLiveRunningCountFromBatchStepsOntoNode', async () => {
    // Issue 2 (2026-05-28): focus on the RUNNING epoch showed the shimmer but
    // NO running count. The running ITEM count is never persisted per-epoch, so
    // for the ACTIVE epoch the focus view reads it from the live all-mode
    // batchSteps (same source the all-epochs view renders) and attaches it as a
    // RUNNING statusCount on the running node.
    const initialNodes: AnyNode[] = [makeNode('n1', 'Step One', undefined)];
    const nodesRef = { current: [...initialNodes] };
    const edgesRef = { current: [] as Edge[] };
    const setNodes = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(nodesRef.current) : updater;
      nodesRef.current = next;
    });
    const setEdges = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(edgesRef.current) : updater;
      edgesRef.current = next;
    });

    getEpochState.mockResolvedValue({
      nodes: {},
      edges: {},
      runningNodeIds: ['mcp:step_one'],
      isActive: true,
    });

    const { rerender } = renderHook(
      ({ viewingEpoch, snapshotSeq }) =>
        useEpochStateViewing({
          viewingEpoch,
          runId: 'run-1',
          nodesRef: nodesRef as any,
          edgesRef: edgesRef as any,
          setNodes: setNodes as any,
          setEdges: setEdges as any,
          workflowLoaded: true,
          snapshotSeq,
          batchSteps: [{ id: 'mcp:step_one', statusCounts: { running: 3 } }],
          batchEdges: [],
        }),
      { initialProps: { viewingEpoch: null as number | null, snapshotSeq: 0 } },
    );

    rerender({ viewingEpoch: 5, snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    await waitFor(() => {
      expect(nodesRef.current[0].data.status).toBe('running');
      expect(nodesRef.current[0].data.statusCounts).toEqual({ RUNNING: 3 });
    });
  });

  it('activeEpochOverlaysLiveRunningCountFromBatchEdgesOntoEdge', async () => {
    // Same gap for edges: recordEdgeCounts never persists a running status, so
    // the focus view reads the in-flight edge count from the live batchEdges
    // and merges it onto the canvas edge as a RUNNING statusCount.
    const initialNodes: AnyNode[] = [
      makeNode('n1', 'Step One', undefined),
      makeNode('n2', 'Step Two', undefined),
    ];
    const initialEdges: Edge[] = [{ id: 'e1', source: 'n1', target: 'n2', data: {} }];
    const nodesRef = { current: [...initialNodes] };
    const edgesRef = { current: [...initialEdges] };
    const setNodes = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(nodesRef.current) : updater;
      nodesRef.current = next;
    });
    const setEdges = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(edgesRef.current) : updater;
      edgesRef.current = next;
    });

    getEpochState.mockResolvedValue({
      nodes: {},
      edges: {},
      runningNodeIds: [],
      isActive: true,
    });

    const { rerender } = renderHook(
      ({ viewingEpoch, snapshotSeq }) =>
        useEpochStateViewing({
          viewingEpoch,
          runId: 'run-1',
          nodesRef: nodesRef as any,
          edgesRef: edgesRef as any,
          setNodes: setNodes as any,
          setEdges: setEdges as any,
          workflowLoaded: true,
          snapshotSeq,
          batchSteps: [],
          batchEdges: [{ id: 'mcp:step_one->mcp:step_two', from: 'mcp:step_one', to: 'mcp:step_two', running: 2 }],
        }),
      { initialProps: { viewingEpoch: null as number | null, snapshotSeq: 0 } },
    );

    rerender({ viewingEpoch: 5, snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    await waitFor(() => {
      const edge = edgesRef.current[0] as Edge;
      expect((edge.data as { status?: string }).status).toBe('running');
      expect((edge.data as { statusCounts?: Record<string, number> }).statusCounts).toEqual({ RUNNING: 2 });
    });
  });

  it('closedEpochDoesNotOverlayLiveRunningCounts', async () => {
    // Gating: live running counts belong to the CURRENT active epoch. When
    // focused on a CLOSED epoch (isActive falsy), the live batchSteps (which
    // reflect a later running epoch) must NOT bleed onto this epoch's nodes.
    const initialNodes: AnyNode[] = [makeNode('n1', 'Step One', undefined)];
    const nodesRef = { current: [...initialNodes] };
    const edgesRef = { current: [] as Edge[] };
    const setNodes = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(nodesRef.current) : updater;
      nodesRef.current = next;
    });
    const setEdges = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(edgesRef.current) : updater;
      edgesRef.current = next;
    });

    getEpochState.mockResolvedValue({
      nodes: { 'mcp:step_one': { COMPLETED: 4 } },
      edges: {},
      runningNodeIds: [],
      isActive: false,
    });

    const { rerender } = renderHook(
      ({ viewingEpoch, snapshotSeq }) =>
        useEpochStateViewing({
          viewingEpoch,
          runId: 'run-1',
          nodesRef: nodesRef as any,
          edgesRef: edgesRef as any,
          setNodes: setNodes as any,
          setEdges: setEdges as any,
          workflowLoaded: true,
          snapshotSeq,
          batchSteps: [{ id: 'mcp:step_one', statusCounts: { running: 9 } }],
          batchEdges: [],
        }),
      { initialProps: { viewingEpoch: null as number | null, snapshotSeq: 0 } },
    );

    rerender({ viewingEpoch: 5, snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    await waitFor(() => {
      // Terminal count from the closed epoch wins; no RUNNING bleed-through.
      expect(nodesRef.current[0].data.status).toBe('completed');
      expect(nodesRef.current[0].data.statusCounts).toEqual({ COMPLETED: 4 });
    });
  });

  it('skippedEdgeIsNotReflaggedRunningByLiveOverlay', async () => {
    // Code-review guard: a purely-skipped edge (no items flowed) must NOT be
    // flipped to 'running' by a live batch edge - protects against a sibling
    // branch's running count bleeding onto a skipped branch (same source→target,
    // indeterminate port) in the active epoch.
    const initialNodes: AnyNode[] = [
      makeNode('n1', 'Step One', undefined),
      makeNode('n2', 'Step Two', undefined),
    ];
    const initialEdges: Edge[] = [{ id: 'e1', source: 'n1', target: 'n2', data: {} }];
    const nodesRef = { current: [...initialNodes] };
    const edgesRef = { current: [...initialEdges] };
    const setNodes = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(nodesRef.current) : updater;
      nodesRef.current = next;
    });
    const setEdges = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(edgesRef.current) : updater;
      edgesRef.current = next;
    });

    getEpochState.mockResolvedValue({
      nodes: {},
      edges: { 'mcp:step_one->mcp:step_two': { SKIPPED: 3 } },
      runningNodeIds: [],
      isActive: true,
    });

    const { rerender } = renderHook(
      ({ viewingEpoch, snapshotSeq }) =>
        useEpochStateViewing({
          viewingEpoch,
          runId: 'run-1',
          nodesRef: nodesRef as any,
          edgesRef: edgesRef as any,
          setNodes: setNodes as any,
          setEdges: setEdges as any,
          workflowLoaded: true,
          snapshotSeq,
          batchSteps: [],
          batchEdges: [{ id: 'mcp:step_one->mcp:step_two', from: 'mcp:step_one', to: 'mcp:step_two', running: 2 }],
        }),
      { initialProps: { viewingEpoch: null as number | null, snapshotSeq: 0 } },
    );

    rerender({ viewingEpoch: 5, snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    await waitFor(() => {
      const edge = edgesRef.current[0] as Edge;
      expect((edge.data as { status?: string }).status).toBe('skipped');
      expect((edge.data as { statusCounts?: Record<string, number> }).statusCounts).toEqual({ SKIPPED: 3 });
    });
  });

  it('preResetSkippedOnSnapshotSeqTickWhileSameEpoch', async () => {
    // Pin MF-1: the pre-reset is gated to viewingEpoch CHANGE only.
    // SSE-driven snapshotSeq updates while parked on the same epoch
    // MUST NOT trigger a flicker.
    const initialNodes: AnyNode[] = [makeNode('n1', 'Step One')];
    const initialEdges: Edge[] = [];

    const nodesRef = { current: [...initialNodes] };
    const edgesRef = { current: [...initialEdges] };
    const setNodes = vi.fn();
    const setEdges = vi.fn();

    getEpochState.mockResolvedValue({ nodes: {}, edges: {} });

    const { rerender } = renderHook(
      ({ viewingEpoch, snapshotSeq }) =>
        useEpochStateViewing({
          viewingEpoch,
          runId: 'run-1',
          nodesRef: nodesRef as any,
          edgesRef: edgesRef as any,
          setNodes: setNodes as any,
          setEdges: setEdges as any,
          workflowLoaded: true,
          snapshotSeq,
        }),
      { initialProps: { viewingEpoch: null as number | null, snapshotSeq: 0 } },
    );

    // Enter epoch 5 - pre-reset fires once.
    rerender({ viewingEpoch: 5, snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });
    const setNodesCallsAfterFirstClick = setNodes.mock.calls.length;
    expect(setNodesCallsAfterFirstClick).toBeGreaterThan(0);

    // SSE update increments snapshotSeq, viewingEpoch stays 5.
    setNodes.mockClear();
    setEdges.mockClear();
    rerender({ viewingEpoch: 5, snapshotSeq: 7 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    // The pre-reset MUST NOT have fired again. setEdges in particular,
    // which is ONLY called by the pre-reset path (applyEpochState's edge
    // logic also calls setEdges, so we check for the FUNCTIONAL setter
    // signature - the pre-reset uses `prev => prev.map(...)`, applyEpochState
    // passes a concrete array. The pre-reset is the only `(prev) =>` call
    // path here that runs synchronously before the fetch resolves.
    // If the gate works, setEdges should NOT have been called with a function
    // argument as its first arg between the two rerenders.
    const functionalSetEdgesCalls = setEdges.mock.calls.filter(
      call => typeof call[0] === 'function'
    );
    expect(functionalSetEdgesCalls).toHaveLength(0);
  });
});

describe('useEpochStateViewing - guardrail per-epoch edge disambiguation (integration)', () => {
  beforeEach(() => {
    getEpochState.mockReset();
    getEpochSignals.mockReset();
    getEpochSignals.mockResolvedValue([]);
  });

  it('guardrailPassFailEdgesGetDistinctPerEpochStatus', async () => {
    // End-to-end of the edgeMatcher pass/fail fix THROUGH the real consumer.
    // A guardrail's two outgoing edges share source+target and differ only by
    // sourceHandle ("pass"/"fail"). For one epoch the backend reports
    // pass=COMPLETED, fail=SKIPPED. Pre-fix, the port-blind matcher made BOTH
    // edges match the FIRST per-epoch key (pass), so both arrows were painted
    // with one branch's status (the user saw "both completed" / "both skipped").
    // Post-fix each arrow binds to its own key -> distinct statuses.
    // Only the API/contexts are mocked here; edgeMatchesBatchEdge runs for real.
    const guard = makeNode('g1', 'Risk Screen', undefined);
    const merge = makeNode('m1', 'Merge', undefined);
    const initialNodes: AnyNode[] = [guard, merge];
    const initialEdges: Edge[] = [
      { id: 'e-pass', source: 'g1', target: 'm1', sourceHandle: 'pass', data: {} },
      { id: 'e-fail', source: 'g1', target: 'm1', sourceHandle: 'fail', data: {} },
    ];

    const nodesRef = { current: [...initialNodes] };
    const edgesRef = { current: [...initialEdges] };
    const setNodes = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(nodesRef.current) : updater;
      nodesRef.current = next;
    });
    const setEdges = vi.fn((updater: any) => {
      const next = typeof updater === 'function' ? updater(edgesRef.current) : updater;
      edgesRef.current = next;
    });

    getEpochState.mockResolvedValue({
      nodes: { 'agent:risk_screen': { COMPLETED: 1 } },
      edges: {
        'agent:risk_screen:pass->core:merge': { COMPLETED: 1 },
        'agent:risk_screen:fail->core:merge': { SKIPPED: 1 },
      },
    });

    const { rerender } = renderHook(
      ({ viewingEpoch, snapshotSeq }) =>
        useEpochStateViewing({
          viewingEpoch,
          runId: 'run-1',
          nodesRef: nodesRef as any,
          edgesRef: edgesRef as any,
          setNodes: setNodes as any,
          setEdges: setEdges as any,
          workflowLoaded: true,
          snapshotSeq,
        }),
      { initialProps: { viewingEpoch: null as number | null, snapshotSeq: 0 } },
    );

    rerender({ viewingEpoch: 1, snapshotSeq: 0 });
    await act(async () => { await new Promise(r => setTimeout(r, 0)); });

    await waitFor(() => {
      const pass = edgesRef.current.find(e => e.id === 'e-pass') as Edge;
      const fail = edgesRef.current.find(e => e.id === 'e-fail') as Edge;
      // Each arrow keeps ONLY its own branch's terminal count.
      expect((pass.data as { statusCounts?: Record<string, number> }).statusCounts).toEqual({ COMPLETED: 1 });
      expect((fail.data as { statusCounts?: Record<string, number> }).statusCounts).toEqual({ SKIPPED: 1 });
      // And therefore distinct derived statuses (pre-fix both were identical).
      expect((pass.data as { status?: string }).status).toBe('completed');
      expect((fail.data as { status?: string }).status).toBe('skipped');
    });
  });
});

// @vitest-environment jsdom
import { describe, it, expect, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useRunStateProcessing } from '../useRunStateProcessing';

// Regression for the 2026-05-30 "pinned run shows no WS status on edges/nodes
// until a workflow switch" bug. The paint effect bails on an empty nodesRef and
// keys off refs + workflowLoaded only. A pinned/async-loaded graph can flip
// workflowLoaded true BEFORE its nodes are committed → the effect bails and,
// having no reactive dep on the nodes, never retriggers when they appear (a
// remount/workflow-switch was the only thing that re-ran it). The fix threads a
// reactive `nodesReady` flag into the deps so the paint re-fires the moment the
// nodes exist.

function baseProps(overrides: Record<string, unknown> = {}) {
  return {
    runState: { batchSteps: [{ stepId: 'mcp:step1', normalizedStepId: 'step1', status: 'completed', statusCounts: {} }] } as any,
    workflowLoaded: true,
    nodesReady: false,
    nodesRef: { current: [] as any[] },
    edgesRef: { current: [] as any[] },
    setNodes: vi.fn(),
    setEdges: vi.fn(),
    setWorkflowStatus: vi.fn(),
    workflowId: 'wf-1',
    effectiveRunId: 'run-1',
    isViewingHistoricalEpoch: false,
    ...overrides,
  };
}

describe('useRunStateProcessing - re-fires the status paint once nodes are ready', () => {
  it('bails while nodes are empty, then paints when nodesReady flips true (pinned async-load race)', () => {
    const props = baseProps();

    const { rerender } = renderHook((p) => useRunStateProcessing(p as any), { initialProps: props });

    // workflowLoaded=true + batchSteps present, but the canvas nodes have not
    // been committed yet (pinned graph still loading) → effect bails, no paint.
    expect(props.setNodes).not.toHaveBeenCalled();

    // Nodes commit AFTER workflowLoaded already turned true. nodesRef is synced
    // by the parent before this effect re-runs; nodesReady flips the reactive dep.
    props.nodesRef.current = [{ id: 'mcp:step1', data: { label: 'step1' }, position: { x: 0, y: 0 } }];
    rerender(baseProps({ nodesRef: props.nodesRef, setNodes: props.setNodes, nodesReady: true }));

    // The reactive nodesReady flip re-fires the paint - no remount needed.
    expect(props.setNodes).toHaveBeenCalled();
  });

  it('does not paint when isViewingHistoricalEpoch is true even after nodesReady flips (suppression preserved)', () => {
    const setNodes = vi.fn();
    const nodesRef = { current: [] as any[] };
    const { rerender } = renderHook((p) => useRunStateProcessing(p as any), {
      initialProps: baseProps({ setNodes, nodesRef, isViewingHistoricalEpoch: true }),
    });

    nodesRef.current = [{ id: 'mcp:step1', data: { label: 'step1' }, position: { x: 0, y: 0 } }];
    rerender(baseProps({ setNodes, nodesRef, nodesReady: true, isViewingHistoricalEpoch: true }));

    // Live paint stays suppressed while viewing a historical epoch - that path
    // is handled by useEpochStateViewing, not this hook.
    expect(setNodes).not.toHaveBeenCalled();
  });
});

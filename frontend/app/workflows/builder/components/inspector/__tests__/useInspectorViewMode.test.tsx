// @vitest-environment jsdom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

import { useInspectorViewMode } from '../useInspectorViewMode';

describe('useInspectorViewMode - execution-data toggle default by run data', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('opens a node with NO run data in Configuration view (showExecutionData=false) in run mode', () => {
    const { result } = renderHook(() =>
      useInspectorViewMode({
        isRunMode: true,
        runId: 'run-1',
        isInterfaceNode: false,
        nodeId: 'mcp:step_a',
        nodeHasRunData: false,
      })
    );

    expect(result.current.showExecutionData).toBe(false);
  });

  it('opens a node WITH run data in Run data view (showExecutionData=true) in run mode', () => {
    const { result } = renderHook(() =>
      useInspectorViewMode({
        isRunMode: true,
        runId: 'run-1',
        isInterfaceNode: false,
        nodeId: 'mcp:step_a',
        nodeHasRunData: true,
      })
    );

    expect(result.current.showExecutionData).toBe(true);
  });

  it('flips to Configuration when switching from a node WITH data to a node WITHOUT data', () => {
    const { result, rerender } = renderHook(
      ({ nodeId, nodeHasRunData }) =>
        useInspectorViewMode({
          isRunMode: true,
          runId: 'run-1',
          isInterfaceNode: false,
          nodeId,
          nodeHasRunData,
        }),
      { initialProps: { nodeId: 'mcp:has_data', nodeHasRunData: true } }
    );

    expect(result.current.showExecutionData).toBe(true);

    rerender({ nodeId: 'mcp:no_data', nodeHasRunData: false });

    expect(result.current.showExecutionData).toBe(false);
  });

  it('flips to Run data when switching from a node WITHOUT data to a node WITH data', () => {
    const { result, rerender } = renderHook(
      ({ nodeId, nodeHasRunData }) =>
        useInspectorViewMode({
          isRunMode: true,
          runId: 'run-1',
          isInterfaceNode: false,
          nodeId,
          nodeHasRunData,
        }),
      { initialProps: { nodeId: 'mcp:no_data', nodeHasRunData: false } }
    );

    expect(result.current.showExecutionData).toBe(false);

    rerender({ nodeId: 'mcp:has_data', nodeHasRunData: true });

    expect(result.current.showExecutionData).toBe(true);
  });

  it('flips a still-selected node to Run data once it first produces run data during a live run', () => {
    const { result, rerender } = renderHook(
      ({ nodeHasRunData }) =>
        useInspectorViewMode({
          isRunMode: true,
          runId: 'run-1',
          isInterfaceNode: false,
          nodeId: 'mcp:step_a',
          nodeHasRunData,
        }),
      { initialProps: { nodeHasRunData: false } }
    );

    expect(result.current.showExecutionData).toBe(false);

    // statusCounts arrive while the same node stays selected
    rerender({ nodeHasRunData: true });

    expect(result.current.showExecutionData).toBe(true);
  });

  it('does NOT force Configuration outside run mode (edit mode keeps run data default)', () => {
    const { result } = renderHook(() =>
      useInspectorViewMode({
        isRunMode: false,
        runId: undefined,
        isInterfaceNode: false,
        nodeId: 'mcp:step_a',
        nodeHasRunData: false,
      })
    );

    expect(result.current.showExecutionData).toBe(true);
  });

  it('keeps the execution-data default consistent for an interface node', () => {
    const { result } = renderHook(() =>
      useInspectorViewMode({
        isRunMode: true,
        runId: 'run-1',
        isInterfaceNode: true,
        nodeId: 'interface:page_a',
        nodeHasRunData: false,
      })
    );

    // The toggle is hidden for interface nodes, but the per-node default still resolves
    // to false (no run data) - inert here, yet consistent with every other node type.
    expect(result.current.showExecutionData).toBe(false);
  });

  it('keeps a manual toggle until a different node is selected', () => {
    const { result, rerender } = renderHook(
      ({ nodeId, nodeHasRunData }) =>
        useInspectorViewMode({
          isRunMode: true,
          runId: 'run-1',
          isInterfaceNode: false,
          nodeId,
          nodeHasRunData,
        }),
      { initialProps: { nodeId: 'mcp:has_data', nodeHasRunData: true } }
    );

    expect(result.current.showExecutionData).toBe(true);

    // User manually flips a node-with-data to Configuration to read its schema
    act(() => {
      result.current.handleShowExecutionDataChange(false);
    });
    expect(result.current.showExecutionData).toBe(false);

    // Re-rendering the SAME node (same id, same data presence) does not clobber the choice
    rerender({ nodeId: 'mcp:has_data', nodeHasRunData: true });
    expect(result.current.showExecutionData).toBe(false);
  });
});

describe('useInspectorViewMode - the run-data flag is latched per node', () => {
  it('does NOT fall back to Configuration when a node stops reporting run data', () => {
    // nodeHasRunData now folds in "executing / parked on a signal", which can go
    // back to false without the node leaving any statusCounts behind (a branch
    // pruned mid-flight). Following it down would yank the panel back to the
    // form under a reader who is looking at the run.
    const { result, rerender } = renderHook(
      ({ hasRunData }: { hasRunData: boolean }) =>
        useInspectorViewMode({
          isRunMode: true,
          runId: 'run-1',
          isInterfaceNode: false,
          nodeId: 'core:step_a',
          nodeHasRunData: hasRunData,
        }),
      { initialProps: { hasRunData: true } },
    );

    expect(result.current.showExecutionData).toBe(true);

    rerender({ hasRunData: false });
    expect(result.current.showExecutionData).toBe(true);
  });

  it('re-evaluates from scratch on a DIFFERENT node, so the latch cannot leak across nodes', () => {
    const { result, rerender } = renderHook(
      ({ nodeId, hasRunData }: { nodeId: string; hasRunData: boolean }) =>
        useInspectorViewMode({
          isRunMode: true,
          runId: 'run-1',
          isInterfaceNode: false,
          nodeId,
          nodeHasRunData: hasRunData,
        }),
      { initialProps: { nodeId: 'core:step_a', hasRunData: true } },
    );

    expect(result.current.showExecutionData).toBe(true);

    rerender({ nodeId: 'core:step_b', hasRunData: false });
    expect(result.current.showExecutionData).toBe(false);
  });

  it('still switches to the run view when a node produces data during a live run', () => {
    const { result, rerender } = renderHook(
      ({ hasRunData }: { hasRunData: boolean }) =>
        useInspectorViewMode({
          isRunMode: true,
          runId: 'run-1',
          isInterfaceNode: false,
          nodeId: 'core:step_a',
          nodeHasRunData: hasRunData,
        }),
      { initialProps: { hasRunData: false } },
    );

    expect(result.current.showExecutionData).toBe(false);

    rerender({ hasRunData: true });
    expect(result.current.showExecutionData).toBe(true);
  });
});

describe('useInspectorViewMode - the latch is per RUN, not only per node', () => {
  it('re-evaluates the same node in a different run, where it may never have executed', () => {
    const { result, rerender } = renderHook(
      ({ runId, hasRunData }: { runId: string; hasRunData: boolean }) =>
        useInspectorViewMode({
          isRunMode: true,
          runId,
          isInterfaceNode: false,
          nodeId: 'core:step_a',
          nodeHasRunData: hasRunData,
        }),
      { initialProps: { runId: 'run-1', hasRunData: true } },
    );

    expect(result.current.showExecutionData).toBe(true);

    rerender({ runId: 'run-2', hasRunData: false });
    expect(result.current.showExecutionData).toBe(false);
  });
});

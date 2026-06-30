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

  it('forces configuration viewMode for an interface node while the toggle default still follows run data', () => {
    const { result } = renderHook(() =>
      useInspectorViewMode({
        isRunMode: true,
        runId: 'run-1',
        isInterfaceNode: true,
        nodeId: 'interface:page_a',
        nodeHasRunData: false,
      })
    );

    // Interface nodes are pinned to configuration view (they own a 3-mode output column).
    expect(result.current.viewMode).toBe('configuration');
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

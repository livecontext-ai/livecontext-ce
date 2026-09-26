// @vitest-environment jsdom
/**
 * A reading-direction change is an EDIT of the workflow: it is saved into the plan with the
 * positions it re-lays, so it must arm Save and be one undo step.
 *
 * Why this needs its own guard: the dirty/undo signature was nodes and edges only. A
 * direction change whose re-layout lands every node where it already was (a one-node graph)
 * left Save disarmed, so the new direction never reached the database; and an undo restored
 * the old positions under the new handles.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { Edge, Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import type { WorkflowLayoutDirection } from '@/contexts/WorkflowLayoutDirectionContext';
import { computeGraphSignature } from '../graphSignature';
import { useDirtyState } from '../useDirtyState';
import { useHistory } from '../useHistory';

const node = (id: string, position = { x: 0, y: 0 }): Node<BuilderNodeData> => ({
  id,
  type: 'flowNode',
  position,
  data: { id, label: id, kind: 'action' } as BuilderNodeData,
});

const NODES = [node('A')];
const EDGES: Edge[] = [];

describe('computeGraphSignature - layout direction', () => {
  it('differs when only the direction differs', () => {
    expect(computeGraphSignature(NODES, EDGES, 'horizontal'))
      .not.toBe(computeGraphSignature(NODES, EDGES, 'vertical'));
  });

  it('is unchanged for callers that do not track a direction', () => {
    expect(computeGraphSignature(NODES, EDGES)).toBe(JSON.stringify({
      nodes: [{ id: 'A', type: 'flowNode', position: { x: 0, y: 0 }, data: { id: 'A', label: 'A', kind: 'action' } }],
      edges: [],
    }));
  });
});

describe('useDirtyState - layout direction', () => {
  interface Props {
    nodes: Node<BuilderNodeData>[];
    direction: WorkflowLayoutDirection;
  }

  function setup(layoutDirection: WorkflowLayoutDirection) {
    const view = renderHook(
      ({ nodes, direction }: Props) => useDirtyState({
        nodes,
        edges: EDGES,
        workflowLoaded: true,
        isRunMode: false,
        layoutDirection: direction,
      }),
      { initialProps: { nodes: [...NODES], direction: layoutDirection } },
    );
    return {
      ...view,
      // A fresh array, as React Flow hands one over on every commit: the hook compares on
      // effects, which a rerender with the same references would not re-run.
      rerender: ({ direction }: { direction: WorkflowLayoutDirection }) =>
        view.rerender({ nodes: [...NODES], direction }),
    };
  }

  /** The hook takes its baseline over two settle renders, as it does after a load. */
  function settle(view: ReturnType<typeof setup>, direction: WorkflowLayoutDirection) {
    view.rerender({ direction });
    view.rerender({ direction });
  }

  it('regression: a direction change alone arms Save', () => {
    const view = setup('horizontal');
    settle(view, 'horizontal');
    expect(view.result.current.isDirty).toBe(false);

    view.rerender({ direction: 'vertical' });

    expect(view.result.current.isDirty).toBe(true);
  });

  it('is clean again once the change is saved', () => {
    const view = setup('horizontal');
    settle(view, 'horizontal');
    view.rerender({ direction: 'vertical' });

    act(() => view.result.current.resetDirtyState(NODES, EDGES));
    view.rerender({ direction: 'vertical' });

    expect(view.result.current.isDirty).toBe(false);
  });

  it('is clean when the direction goes back to the saved one', () => {
    const view = setup('horizontal');
    settle(view, 'horizontal');
    view.rerender({ direction: 'vertical' });
    view.rerender({ direction: 'horizontal' });

    expect(view.result.current.isDirty).toBe(false);
  });
});

describe('useHistory - layout direction', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  interface Props {
    nodes: Node<BuilderNodeData>[];
    direction: WorkflowLayoutDirection;
  }

  function setup() {
    const setNodes = vi.fn();
    const setEdges = vi.fn();
    const setDirection = vi.fn();
    const view = renderHook(
      ({ nodes, direction }: Props) =>
        useHistory(nodes, EDGES, setNodes, setEdges, true, { value: direction, set: setDirection }),
      { initialProps: { nodes: NODES, direction: 'horizontal' } as Props },
    );
    return { ...view, setNodes, setDirection };
  }

  function flushDebounce() {
    act(() => {
      vi.advanceTimersByTime(400);
    });
  }

  it('records a direction change as an undo step, even when no node moved', () => {
    const view = setup();
    flushDebounce();

    view.rerender({ nodes: NODES, direction: 'vertical' });
    flushDebounce();

    expect(view.result.current.canUndo).toBe(true);
  });

  it('regression: undo puts the direction back WITH the positions it was laid out for', () => {
    const view = setup();
    flushDebounce();
    // The toggle: direction and re-laid positions in one render.
    view.rerender({ nodes: [node('A', { x: 0, y: 300 })], direction: 'vertical' });
    flushDebounce();

    act(() => view.result.current.undo());

    expect(view.setNodes).toHaveBeenLastCalledWith([expect.objectContaining({ position: { x: 0, y: 0 } })]);
    expect(view.setDirection).toHaveBeenLastCalledWith('horizontal');
  });

  it('redo re-applies the direction', () => {
    const view = setup();
    flushDebounce();
    view.rerender({ nodes: [node('A', { x: 0, y: 300 })], direction: 'vertical' });
    flushDebounce();
    act(() => view.result.current.undo());
    act(() => {
      vi.advanceTimersByTime(150);
    });

    act(() => view.result.current.redo());

    expect(view.setDirection).toHaveBeenLastCalledWith('vertical');
  });
});

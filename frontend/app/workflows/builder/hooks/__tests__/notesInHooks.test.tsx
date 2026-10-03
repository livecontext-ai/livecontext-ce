// @vitest-environment jsdom
/**
 * The hooks the canvas really goes through for notes: the delete button (useGraphOperations),
 * every ReactFlow change (useSelection), and the prepared graph (usePreparedGraph) that
 * raises and highlights the notes of the selected node.
 */
import { describe, it, expect, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import type { Dispatch, SetStateAction } from 'react';
import type { Edge, Node, NodeChange } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { useGraphOperations } from '../useGraphOperations';
import { useSelection } from '../useSelection';
import { usePreparedGraph } from '../usePreparedGraph';
import { stripRuntimeProps } from '../../utils/nodeDataUtils';

vi.mock('@/lib/analytics/analytics', () => ({ track: vi.fn() }));

type AnyNode = Node<BuilderNodeData>;

const flow = (id: string): AnyNode => ({
  id, type: 'flowNode', position: { x: 100, y: 100 }, data: { id, label: id, kind: 'action' } as BuilderNodeData,
});
const note = (id: string, anchor?: string): AnyNode => ({
  id, type: 'noteNode', position: { x: 100, y: -60 },
  data: { id, label: id, kind: 'action', noteText: 'x', ...(anchor ? { noteAttachedTo: anchor } : {}) } as BuilderNodeData,
});

describe('useGraphOperations.handleDeleteNode', () => {
  it('deletes the node together with its notes', () => {
    const setNodes = vi.fn();
    const { result } = renderHook(() =>
      useGraphOperations([], setNodes as unknown as Dispatch<SetStateAction<AnyNode[]>>, [], vi.fn(), vi.fn(), 'default' as never, vi.fn()),
    );

    result.current.handleDeleteNode('A');

    const updater = setNodes.mock.calls[0][0] as (prev: AnyNode[]) => AnyNode[];
    expect(updater([flow('A'), flow('B'), note('nA', 'A'), note('free')]).map((n) => n.id)).toEqual(['B', 'free']);
  });
});

describe('useSelection.onNodesChange', () => {
  it('passes a node drag on with the drag of its notes', () => {
    const onNodesChangeBase = vi.fn();
    const nodes = [flow('A'), note('nA', 'A')];
    const { result } = renderHook(() =>
      useSelection({
        selectedNodeIds: [],
        setSelectedNodeIds: vi.fn(),
        setNodes: vi.fn(),
        onNodesChangeBase,
        setIsAdvancedMode: vi.fn(),
        setSelectedLoopChild: vi.fn(),
        isNodeCreatorOpen: false,
        nodes,
      }),
    );

    result.current.onNodesChange([{ type: 'position', id: 'A', position: { x: 150, y: 120 }, dragging: true }]);

    const sent = onNodesChangeBase.mock.calls[0][0] as NodeChange[];
    expect(sent).toContainEqual(expect.objectContaining({ type: 'position', id: 'nA', position: { x: 150, y: -40 } }));
  });
});

describe('usePreparedGraph', () => {
  const callbacks = { handleDeleteNode: vi.fn(), handleDuplicateNode: vi.fn(), handleNodeUpdate: vi.fn() };
  const prepare = (selected: string[]) =>
    renderHook(() => usePreparedGraph([flow('A'), note('nA', 'A'), note('free')], [] as Edge[], selected, 'default' as never, callbacks))
      .result.current.preparedNodes;

  it('raises and highlights the notes of the selected node, and only those', () => {
    const prepared = prepare(['A']);
    const attached = prepared.find((n) => n.id === 'nA')!;
    const free = prepared.find((n) => n.id === 'free')!;

    expect(attached.data.noteFocused).toBe(true);
    expect(attached.style?.zIndex).toBe(20);
    expect(free.data.noteFocused).toBe(false);
    expect(free.style?.zIndex).toBe(0);
  });

  it('never persists the highlight: it is a runtime prop', () => {
    const attached = prepare(['A']).find((n) => n.id === 'nA')!;
    expect(stripRuntimeProps(attached.data)).not.toHaveProperty('noteFocused');
    expect(stripRuntimeProps(attached.data)).toHaveProperty('noteAttachedTo', 'A');
  });
});

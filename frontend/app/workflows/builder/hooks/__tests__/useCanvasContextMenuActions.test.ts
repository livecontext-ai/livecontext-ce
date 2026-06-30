// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import type { Dispatch, SetStateAction } from 'react';
import type { Edge, Node, XYPosition } from 'reactflow';
import type { BuilderNodeData, PaletteDragItem, PaletteItem } from '../../types';
import { nodeClipboard } from '../../services/nodeClipboard';
import { useCanvasContextMenuActions } from '../useCanvasContextMenuActions';

vi.mock('../../nodes/nodeClasses', () => ({
  getPaletteItemDataFromId: (id: string) => ({ id, label: 'Note', nodeType: 'noteNode', kind: 'action' }),
}));

const node = (id: string, position = { x: 0, y: 0 }): Node<BuilderNodeData> => ({
  id,
  type: 'flowNode',
  position,
  data: { id, label: id, kind: 'action' } as BuilderNodeData,
});

const edge = (source: string, target: string): Edge => ({ id: `edge-${source}-${target}`, source, target });

interface Harness {
  setNodes: ReturnType<typeof vi.fn>;
  setEdges: ReturnType<typeof vi.fn>;
  setSelectedNodeIds: ReturnType<typeof vi.fn>;
  onCreateNode: ReturnType<typeof vi.fn>;
}

function setup(opts: { nodes?: Node<BuilderNodeData>[]; edges?: Edge[]; selectedNodeIds?: string[] } = {}) {
  const harness: Harness = {
    setNodes: vi.fn(),
    setEdges: vi.fn(),
    setSelectedNodeIds: vi.fn(),
    onCreateNode: vi.fn(),
  };
  const { result } = renderHook(() =>
    useCanvasContextMenuActions({
      nodes: opts.nodes ?? [node('A'), node('B')],
      edges: opts.edges ?? [edge('A', 'B')],
      selectedNodeIds: opts.selectedNodeIds ?? [],
      setNodes: harness.setNodes as unknown as Dispatch<SetStateAction<Node<BuilderNodeData>[]>>,
      setEdges: harness.setEdges as unknown as Dispatch<SetStateAction<Edge[]>>,
      setSelectedNodeIds: harness.setSelectedNodeIds as unknown as Dispatch<SetStateAction<string[]>>,
      onCreateNode: harness.onCreateNode as unknown as (
        item: PaletteDragItem | PaletteItem,
        position: XYPosition,
        options?: { parentId?: string },
      ) => void,
    }),
  );
  return { actions: result.current, ...harness };
}

describe('useCanvasContextMenuActions', () => {
  beforeEach(() => nodeClipboard.clear());

  it('copyNode copies just the node when it is not part of a multi-selection', () => {
    const { actions } = setup();
    actions.copyNode('A');
    expect(nodeClipboard.get()?.nodes.map((n) => n.id)).toEqual(['A']);
  });

  it('copyNode copies the whole selection (with internal edges) when the node is selected', () => {
    const { actions } = setup({ selectedNodeIds: ['A', 'B'] });
    actions.copyNode('A');
    const payload = nodeClipboard.get();
    expect(payload?.nodes.map((n) => n.id).sort()).toEqual(['A', 'B']);
    expect(payload?.edges.map((e) => e.id)).toEqual(['edge-A-B']);
  });

  it('paste appends cloned nodes and selects them', () => {
    nodeClipboard.set({ nodes: [node('A')], edges: [] });
    const { actions, setNodes, setSelectedNodeIds } = setup();
    actions.paste({ x: 500, y: 500 });

    const updater = setNodes.mock.calls[0][0] as (prev: Node<BuilderNodeData>[]) => Node<BuilderNodeData>[];
    const next = updater([node('A'), node('B')]);
    expect(next).toHaveLength(3); // 2 existing + 1 pasted
    expect(setSelectedNodeIds).toHaveBeenCalledWith([next[2].id]);
  });

  it('paste is a no-op when the clipboard is empty', () => {
    const { actions, setNodes } = setup();
    actions.paste();
    expect(setNodes).not.toHaveBeenCalled();
  });

  it('duplicateNode appends a clone and selects it', () => {
    const { actions, setNodes, setSelectedNodeIds } = setup();
    actions.duplicateNode('A');
    expect(setNodes).toHaveBeenCalledTimes(1);
    expect(setSelectedNodeIds).toHaveBeenCalledTimes(1);
  });

  it('selectDownstream selects the node plus its descendants', () => {
    const { actions, setSelectedNodeIds } = setup({ edges: [edge('A', 'B'), edge('B', 'C')] });
    actions.selectDownstream('A');
    expect(setSelectedNodeIds).toHaveBeenCalledWith(['A', 'B', 'C']);
  });

  it('disconnectNode removes every edge touching the node', () => {
    const { actions, setEdges } = setup({ edges: [edge('A', 'B'), edge('B', 'C')] });
    actions.disconnectNode('A');
    const updater = setEdges.mock.calls[0][0] as (prev: Edge[]) => Edge[];
    expect(updater([edge('A', 'B'), edge('B', 'C')]).map((e) => e.id)).toEqual(['edge-B-C']);
  });

  it('deleteNode removes the node, its edges, and its selection entry', () => {
    const { actions, setNodes, setEdges, setSelectedNodeIds } = setup();
    actions.deleteNode('A');

    const nodesUpdater = setNodes.mock.calls[0][0] as (prev: Node<BuilderNodeData>[]) => Node<BuilderNodeData>[];
    expect(nodesUpdater([node('A'), node('B')]).map((n) => n.id)).toEqual(['B']);

    const edgesUpdater = setEdges.mock.calls[0][0] as (prev: Edge[]) => Edge[];
    expect(edgesUpdater([edge('A', 'B')]).length).toBe(0);

    const selUpdater = setSelectedNodeIds.mock.calls[0][0] as (prev: string[]) => string[];
    expect(selUpdater(['A', 'B'])).toEqual(['B']);
  });

  it('selectAll selects every node id', () => {
    const { actions, setSelectedNodeIds } = setup();
    actions.selectAll();
    expect(setSelectedNodeIds).toHaveBeenCalledWith(['A', 'B']);
  });

  it('addNoteNear creates a note positioned below the node', () => {
    const { actions, onCreateNode } = setup({ nodes: [node('A', { x: 10, y: 20 })] });
    actions.addNoteNear('A');
    expect(onCreateNode).toHaveBeenCalledTimes(1);
    const [item, position] = onCreateNode.mock.calls[0];
    expect(item.nodeType).toBe('noteNode');
    expect(position.x).toBe(10);
    expect(position.y).toBeGreaterThan(20);
  });
});

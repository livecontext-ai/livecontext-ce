// @vitest-environment jsdom

import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Edge, Node } from 'reactflow';
import { useGraphOperations } from '../useGraphOperations';
import type { BuilderNodeData, PaletteDragItem } from '../../types';

describe('useGraphOperations initialData', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('applies shortcut configuration while preserving generated identity', () => {
    vi.spyOn(Date, 'now').mockReturnValue(1234);
    let nodes: Node<BuilderNodeData>[] = [];
    let edges: Edge[] = [];
    let selectedIds: string[] = [];

    const setNodes = (update: React.SetStateAction<Node<BuilderNodeData>[]>) => {
      nodes = typeof update === 'function' ? update(nodes) : update;
    };
    const setEdges = (update: React.SetStateAction<Edge[]>) => {
      edges = typeof update === 'function' ? update(edges) : update;
    };
    const setSelectedIds = (update: React.SetStateAction<string[]>) => {
      selectedIds = typeof update === 'function' ? update(selectedIds) : update;
    };

    const { result } = renderHook(() => useGraphOperations(
      nodes,
      setNodes,
      edges,
      setEdges,
      setSelectedIds,
      'bezier',
      () => {},
    ));

    const item: PaletteDragItem = {
      id: 'schedule-trigger',
      label: 'Check Gmail every minute',
      description: 'Gmail polling',
      kind: 'entry',
      nodeType: 'flowNode',
      initialData: {
        id: 'must-not-win',
        label: 'must-not-win',
        scheduleTriggerData: {
          cronExpression: '* * * * *',
          timezone: 'UTC',
          maxExecutions: null,
        },
      },
    };

    let createdId: string | undefined;
    act(() => {
      createdId = result.current.handleCreateNode(item, { x: 100, y: 200 });
    });

    expect(createdId).toBe('schedule-trigger-1234');
    expect(selectedIds).toEqual(['schedule-trigger-1234']);
    expect(nodes[0]).toMatchObject({
      id: 'schedule-trigger-1234',
      position: { x: 100, y: 200 },
      data: {
        id: 'schedule-trigger-1234',
        label: 'Check Gmail every minute',
        kind: 'entry',
        scheduleTriggerData: { cronExpression: '* * * * *' },
      },
    });
    expect(nodes[0].data).not.toHaveProperty('apiData');
  });
});

// @vitest-environment jsdom
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { nodeClipboard, useClipboardHasContent } from '../nodeClipboard';

const node = (id: string): Node<BuilderNodeData> => ({
  id,
  type: 'flowNode',
  position: { x: 0, y: 0 },
  data: { id, label: id, kind: 'action' } as BuilderNodeData,
});

describe('nodeClipboard', () => {
  beforeEach(() => nodeClipboard.clear());

  it('stores and returns a payload, and reports content', () => {
    expect(nodeClipboard.hasContent()).toBe(false);
    nodeClipboard.set({ nodes: [node('A')], edges: [] });
    expect(nodeClipboard.hasContent()).toBe(true);
    expect(nodeClipboard.get()?.nodes.map((n) => n.id)).toEqual(['A']);
  });

  it('sanitizes runtime callback props out of stored node data', () => {
    const dirty = node('A');
    (dirty.data as unknown as Record<string, unknown>).onDeleteNode = () => {};
    nodeClipboard.set({ nodes: [dirty], edges: [] });
    expect((nodeClipboard.get()?.nodes[0].data as unknown as Record<string, unknown>).onDeleteNode).toBeUndefined();
  });

  it('treats an empty node list as a cleared clipboard', () => {
    nodeClipboard.set({ nodes: [], edges: [] });
    expect(nodeClipboard.get()).toBeNull();
    expect(nodeClipboard.hasContent()).toBe(false);
  });

  it('clears the buffer', () => {
    nodeClipboard.set({ nodes: [node('A')], edges: [] });
    nodeClipboard.clear();
    expect(nodeClipboard.hasContent()).toBe(false);
  });

  it('notifies subscribers on change and stops after unsubscribe', () => {
    const listener = vi.fn();
    const unsubscribe = nodeClipboard.subscribe(listener);
    nodeClipboard.set({ nodes: [node('A')], edges: [] });
    expect(listener).toHaveBeenCalledTimes(1);
    unsubscribe();
    nodeClipboard.clear();
    expect(listener).toHaveBeenCalledTimes(1);
  });
});

describe('useClipboardHasContent', () => {
  beforeEach(() => nodeClipboard.clear());

  it('reactively reflects the clipboard state', () => {
    const { result } = renderHook(() => useClipboardHasContent());
    expect(result.current).toBe(false);
    act(() => nodeClipboard.set({ nodes: [node('A')], edges: [] }));
    expect(result.current).toBe(true);
    act(() => nodeClipboard.clear());
    expect(result.current).toBe(false);
  });
});

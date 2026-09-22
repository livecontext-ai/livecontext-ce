// @vitest-environment jsdom
/**
 * Recognising an interface node after a plan round-trip.
 *
 * `data.id` is not an identity. A node is re-imported with `data.id` set to its
 * graph node id, which is whatever created the canvas node: a template literal,
 * an agent-authored plan key, a bare uuid. 221 of the 364 interface nodes in
 * production plans carry an id that does not begin with `interface`.
 *
 * `isInterfaceNode` decides real things - the interface-specific Output column
 * (Output / Preview / Schema), the Preview action, and the deliberate ABSENCE of
 * the Edit / Run data switcher, which an interface node does not use. Reading it
 * off an id prefix meant those 221 nodes were treated as ordinary nodes.
 */
import { describe, it, expect } from 'vitest';
import { renderHook } from '@testing-library/react';
import type { Node } from 'reactflow';

import { useInspectorNodeMeta } from '../useInspectorNodeMeta';
import type { BuilderNodeData } from '../../../types';

function meta(node: Node<BuilderNodeData>) {
  return renderHook(() => useInspectorNodeMeta(node)).result.current;
}

function ifaceNode(id: string, over: Partial<Node<BuilderNodeData>> = {}): Node<BuilderNodeData> {
  return {
    id,
    type: 'interfaceNode',
    position: { x: 0, y: 0 },
    data: { id, label: 'Page', kind: 'interface' } as BuilderNodeData,
    ...over,
  };
}

describe('isInterfaceNode', () => {
  it('recognises the node by its TYPE, whatever id the plan gave it', () => {
    expect(meta(ifaceNode('__template_interface__')).isInterfaceNode).toBe(true);
    expect(meta(ifaceNode('core:landing_page')).isInterfaceNode).toBe(true);
    expect(meta(ifaceNode('9241f07f-f3fd-4dcc-9c45-e46f458a9eb6')).isInterfaceNode).toBe(true);
  });

  it('recognises one carried on a flowNode by its bound interfaceId', () => {
    const n = ifaceNode('core-data', { type: 'flowNode' });
    (n.data as any).interfaceData = { interfaceId: 'abc-123' };
    expect(meta(n).isInterfaceNode).toBe(true);
  });

  it('still recognises the id-prefixed form the builder creates', () => {
    expect(meta(ifaceNode('interface-1758000000000', { type: 'flowNode' })).isInterfaceNode).toBe(true);
  });

  it('does not claim a node that is not one', () => {
    const transform: Node<BuilderNodeData> = {
      id: 'core-1',
      type: 'flowNode',
      position: { x: 0, y: 0 },
      data: { id: 'core-1', label: 'Build greeting', kind: 'transform' } as BuilderNodeData,
    };
    expect(meta(transform).isInterfaceNode).toBe(false);
  });
});

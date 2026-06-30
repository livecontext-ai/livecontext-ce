import { describe, it, expect } from 'vitest';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { nodesHaveChanged, edgesHaveChanged } from '../graphCompare';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeNode(
  id: string,
  overrides?: Partial<BuilderNodeData>,
): Node<BuilderNodeData> {
  return {
    id,
    position: { x: 0, y: 0 },
    data: {
      id,
      label: id,
      kind: 'action' as const,
      ...overrides,
    },
    type: 'flowNode',
  } as Node<BuilderNodeData>;
}

function makeEdge(
  id: string,
  source: string,
  target: string,
  data?: Record<string, any>,
): Edge {
  return { id, source, target, data } as Edge;
}

// ---------------------------------------------------------------------------
// nodesHaveChanged
// ---------------------------------------------------------------------------

describe('nodesHaveChanged', () => {
  describe('length differences', () => {
    it('should return true when a node is added', () => {
      const oldNodes = [makeNode('a')];
      const newNodes = [makeNode('a'), makeNode('b')];
      expect(nodesHaveChanged(oldNodes, newNodes)).toBe(true);
    });

    it('should return true when a node is removed', () => {
      const oldNodes = [makeNode('a'), makeNode('b')];
      const newNodes = [makeNode('a')];
      expect(nodesHaveChanged(oldNodes, newNodes)).toBe(true);
    });

    it('should return true when going from non-empty to empty', () => {
      expect(nodesHaveChanged([makeNode('a')], [])).toBe(true);
    });

    it('should return true when going from empty to non-empty', () => {
      expect(nodesHaveChanged([], [makeNode('a')])).toBe(true);
    });
  });

  describe('no changes', () => {
    it('should return false for two empty arrays', () => {
      expect(nodesHaveChanged([], [])).toBe(false);
    });

    it('should return false when both arrays have the same nodes', () => {
      const nodes = [makeNode('a'), makeNode('b')];
      expect(nodesHaveChanged(nodes, nodes)).toBe(false);
    });

    it('should return false for structurally identical nodes (different references)', () => {
      const old = [makeNode('a', { status: 'completed' })];
      const neu = [makeNode('a', { status: 'completed' })];
      expect(nodesHaveChanged(old, neu)).toBe(false);
    });

    it('should return false when nodes are in different order but identical', () => {
      const old = [makeNode('a'), makeNode('b')];
      const neu = [makeNode('b'), makeNode('a')];
      expect(nodesHaveChanged(old, neu)).toBe(false);
    });
  });

  describe('node ID changes', () => {
    it('should return true when a node is replaced by a different one (same length)', () => {
      const old = [makeNode('a'), makeNode('b')];
      const neu = [makeNode('a'), makeNode('c')];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });
  });

  describe('status changes', () => {
    it('should return true when status changes from undefined to a value', () => {
      const old = [makeNode('a')];
      const neu = [makeNode('a', { status: 'running' })];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when status changes from one value to another', () => {
      const old = [makeNode('a', { status: 'running' })];
      const neu = [makeNode('a', { status: 'completed' })];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when status changes from a value to undefined', () => {
      const old = [makeNode('a', { status: 'failed' })];
      const neu = [makeNode('a')];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return false when status is the same value', () => {
      const old = [makeNode('a', { status: 'completed' })];
      const neu = [makeNode('a', { status: 'completed' })];
      expect(nodesHaveChanged(old, neu)).toBe(false);
    });
  });

  describe('statusCounts changes', () => {
    it('should return true when statusCounts goes from undefined to defined', () => {
      const old = [makeNode('a')];
      const neu = [makeNode('a', { statusCounts: { completed: 1 } })];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when statusCounts goes from defined to undefined', () => {
      const old = [makeNode('a', { statusCounts: { completed: 1 } })];
      const neu = [makeNode('a')];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when statusCounts values differ', () => {
      const old = [makeNode('a', { statusCounts: { completed: 1, error: 0 } })];
      const neu = [makeNode('a', { statusCounts: { completed: 2, error: 0 } })];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when statusCounts has different keys count', () => {
      const old = [makeNode('a', { statusCounts: { completed: 1 } })];
      const neu = [makeNode('a', { statusCounts: { completed: 1, error: 2 } })];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return false when statusCounts are the same reference', () => {
      const counts = { completed: 1, error: 0 };
      const old = [makeNode('a', { statusCounts: counts })];
      const neu = [makeNode('a', { statusCounts: counts })];
      expect(nodesHaveChanged(old, neu)).toBe(false);
    });

    it('should return false when statusCounts are structurally identical (different refs)', () => {
      const old = [makeNode('a', { statusCounts: { completed: 3, running: 1 } })];
      const neu = [makeNode('a', { statusCounts: { completed: 3, running: 1 } })];
      expect(nodesHaveChanged(old, neu)).toBe(false);
    });

    it('should return true when both have empty objects but different references', () => {
      // Two different empty objects are !== by reference, so they will be compared
      // They have 0 keys each, so the comparison will pass -> no change
      const old = [makeNode('a', { statusCounts: {} })];
      const neu = [makeNode('a', { statusCounts: {} })];
      expect(nodesHaveChanged(old, neu)).toBe(false);
    });
  });

  describe('loopChildren changes', () => {
    const baseChild = (id: string, status: string, counts?: Record<string, number>) => ({
      id,
      label: id,
      kind: 'action' as const,
      nodeType: 'flowNode' as const,
      status,
      statusCounts: counts,
    });

    it('should return true when loopChildren goes from undefined to an array', () => {
      const old = [makeNode('a')];
      const neu = [makeNode('a', {
        loopChildren: [baseChild('c1', 'pending')] as any,
      })];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when loopChildren goes from an array to undefined', () => {
      const old = [makeNode('a', {
        loopChildren: [baseChild('c1', 'pending')] as any,
      })];
      const neu = [makeNode('a')];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when loopChildren length changes', () => {
      const old = [makeNode('a', {
        loopChildren: [baseChild('c1', 'pending')] as any,
      })];
      const neu = [makeNode('a', {
        loopChildren: [baseChild('c1', 'pending'), baseChild('c2', 'pending')] as any,
      })];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when a loopChild status changes', () => {
      const old = [makeNode('a', {
        loopChildren: [baseChild('c1', 'pending')] as any,
      })];
      const neu = [makeNode('a', {
        loopChildren: [baseChild('c1', 'completed')] as any,
      })];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when loopChild statusCounts value changes', () => {
      const old = [makeNode('a', {
        loopChildren: [baseChild('c1', 'running', { completed: 1 })] as any,
      })];
      const neu = [makeNode('a', {
        loopChildren: [baseChild('c1', 'running', { completed: 2 })] as any,
      })];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when loopChild statusCounts has a new key', () => {
      const old = [makeNode('a', {
        loopChildren: [baseChild('c1', 'running', { completed: 1 })] as any,
      })];
      const neu = [makeNode('a', {
        loopChildren: [baseChild('c1', 'running', { completed: 1, error: 1 })] as any,
      })];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when loopChild statusCounts goes from undefined to defined', () => {
      const old = [makeNode('a', {
        loopChildren: [baseChild('c1', 'running')] as any,
      })];
      const neu = [makeNode('a', {
        loopChildren: [baseChild('c1', 'running', { completed: 1 })] as any,
      })];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when loopChild statusCounts goes from defined to undefined', () => {
      const old = [makeNode('a', {
        loopChildren: [baseChild('c1', 'running', { completed: 1 })] as any,
      })];
      const neu = [makeNode('a', {
        loopChildren: [baseChild('c1', 'running')] as any,
      })];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return false when loopChildren are identical', () => {
      const children = [baseChild('c1', 'completed', { completed: 1 })];
      const old = [makeNode('a', { loopChildren: children as any })];
      const neu = [makeNode('a', { loopChildren: children as any })];
      expect(nodesHaveChanged(old, neu)).toBe(false);
    });

    it('should return false when loopChildren are structurally identical (different refs)', () => {
      const old = [makeNode('a', {
        loopChildren: [baseChild('c1', 'completed', { completed: 1 })] as any,
      })];
      const neu = [makeNode('a', {
        loopChildren: [baseChild('c1', 'completed', { completed: 1 })] as any,
      })];
      expect(nodesHaveChanged(old, neu)).toBe(false);
    });

    it('should return false when both loopChildren are empty arrays', () => {
      const old = [makeNode('a', { loopChildren: [] })];
      const neu = [makeNode('a', { loopChildren: [] })];
      expect(nodesHaveChanged(old, neu)).toBe(false);
    });

    it('should handle multiple children with only the last one changed', () => {
      const old = [makeNode('a', {
        loopChildren: [
          baseChild('c1', 'completed', { completed: 1 }),
          baseChild('c2', 'running', { running: 1 }),
        ] as any,
      })];
      const neu = [makeNode('a', {
        loopChildren: [
          baseChild('c1', 'completed', { completed: 1 }),
          baseChild('c2', 'completed', { completed: 1 }),
        ] as any,
      })];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });
  });

  describe('multiple nodes with mixed changes', () => {
    it('should return true if only one of many nodes changed status', () => {
      const old = [
        makeNode('a', { status: 'completed' }),
        makeNode('b', { status: 'completed' }),
        makeNode('c', { status: 'running' }),
      ];
      const neu = [
        makeNode('a', { status: 'completed' }),
        makeNode('b', { status: 'completed' }),
        makeNode('c', { status: 'completed' }),
      ];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });

    it('should return false when all nodes are identical', () => {
      const old = [
        makeNode('a', { status: 'completed' }),
        makeNode('b', { status: 'running' }),
      ];
      const neu = [
        makeNode('a', { status: 'completed' }),
        makeNode('b', { status: 'running' }),
      ];
      expect(nodesHaveChanged(old, neu)).toBe(false);
    });

    it('should detect status change even in different order', () => {
      const old = [
        makeNode('a', { status: 'completed' }),
        makeNode('b', { status: 'running' }),
      ];
      const neu = [
        makeNode('b', { status: 'completed' }),
        makeNode('a', { status: 'completed' }),
      ];
      expect(nodesHaveChanged(old, neu)).toBe(true);
    });
  });

  describe('reordered nodes', () => {
    it('should return false when nodes are reordered but otherwise identical', () => {
      const old = [
        makeNode('a', { status: 'completed', statusCounts: { completed: 1 } }),
        makeNode('b', { status: 'running', statusCounts: { running: 1 } }),
        makeNode('c', { status: 'pending' }),
      ];
      const neu = [
        makeNode('c', { status: 'pending' }),
        makeNode('a', { status: 'completed', statusCounts: { completed: 1 } }),
        makeNode('b', { status: 'running', statusCounts: { running: 1 } }),
      ];
      expect(nodesHaveChanged(old, neu)).toBe(false);
    });
  });
});

// ---------------------------------------------------------------------------
// edgesHaveChanged
// ---------------------------------------------------------------------------

describe('edgesHaveChanged', () => {
  describe('length differences', () => {
    it('should return true when an edge is added', () => {
      const old = [makeEdge('e1', 'a', 'b')];
      const neu = [makeEdge('e1', 'a', 'b'), makeEdge('e2', 'b', 'c')];
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when an edge is removed', () => {
      const old = [makeEdge('e1', 'a', 'b'), makeEdge('e2', 'b', 'c')];
      const neu = [makeEdge('e1', 'a', 'b')];
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true from non-empty to empty', () => {
      expect(edgesHaveChanged([makeEdge('e1', 'a', 'b')], [])).toBe(true);
    });

    it('should return true from empty to non-empty', () => {
      expect(edgesHaveChanged([], [makeEdge('e1', 'a', 'b')])).toBe(true);
    });
  });

  describe('no changes', () => {
    it('should return false for two empty arrays', () => {
      expect(edgesHaveChanged([], [])).toBe(false);
    });

    it('should return false when both arrays reference the same edges', () => {
      const edges = [makeEdge('e1', 'a', 'b')];
      expect(edgesHaveChanged(edges, edges)).toBe(false);
    });

    it('should return false when edges are structurally identical', () => {
      const old = [makeEdge('e1', 'a', 'b')];
      const neu = [makeEdge('e1', 'a', 'b')];
      expect(edgesHaveChanged(old, neu)).toBe(false);
    });

    it('should return false when edges with data are identical', () => {
      const old = [makeEdge('e1', 'a', 'b', { status: 'completed' })];
      const neu = [makeEdge('e1', 'a', 'b', { status: 'completed' })];
      expect(edgesHaveChanged(old, neu)).toBe(false);
    });
  });

  describe('id changes', () => {
    it('should return true when edge IDs differ at the same position', () => {
      const old = [makeEdge('e1', 'a', 'b')];
      const neu = [makeEdge('e2', 'a', 'b')];
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when edges are reordered', () => {
      // NOTE: edgesHaveChanged does NOT use a map; it compares positionally
      const old = [makeEdge('e1', 'a', 'b'), makeEdge('e2', 'b', 'c')];
      const neu = [makeEdge('e2', 'b', 'c'), makeEdge('e1', 'a', 'b')];
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });
  });

  describe('status changes', () => {
    it('should return true when status goes from undefined to a value', () => {
      const old = [makeEdge('e1', 'a', 'b')];
      const neu = [makeEdge('e1', 'a', 'b', { status: 'running' })];
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when status changes value', () => {
      const old = [makeEdge('e1', 'a', 'b', { status: 'running' })];
      const neu = [makeEdge('e1', 'a', 'b', { status: 'completed' })];
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when status goes from a value to undefined', () => {
      const old = [makeEdge('e1', 'a', 'b', { status: 'failed' })];
      const neu = [makeEdge('e1', 'a', 'b')];
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });

    it('should return false when status is the same', () => {
      const old = [makeEdge('e1', 'a', 'b', { status: 'completed' })];
      const neu = [makeEdge('e1', 'a', 'b', { status: 'completed' })];
      expect(edgesHaveChanged(old, neu)).toBe(false);
    });
  });

  describe('statusCounts changes', () => {
    it('should return true when statusCounts goes from undefined to defined', () => {
      const old = [makeEdge('e1', 'a', 'b')];
      const neu = [makeEdge('e1', 'a', 'b', { statusCounts: { completed: 1 } })];
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when statusCounts goes from defined to undefined', () => {
      const old = [makeEdge('e1', 'a', 'b', { statusCounts: { completed: 1 } })];
      const neu = [makeEdge('e1', 'a', 'b')];
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when statusCounts values differ', () => {
      const old = [makeEdge('e1', 'a', 'b', { statusCounts: { completed: 1 } })];
      const neu = [makeEdge('e1', 'a', 'b', { statusCounts: { completed: 2 } })];
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when statusCounts keys differ', () => {
      const old = [makeEdge('e1', 'a', 'b', { statusCounts: { completed: 1 } })];
      const neu = [makeEdge('e1', 'a', 'b', { statusCounts: { completed: 1, error: 1 } })];
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });

    it('should return false when statusCounts are the same reference', () => {
      const counts = { completed: 3 };
      const old = [makeEdge('e1', 'a', 'b', { statusCounts: counts })];
      const neu = [makeEdge('e1', 'a', 'b', { statusCounts: counts })];
      expect(edgesHaveChanged(old, neu)).toBe(false);
    });

    it('should return false when statusCounts are structurally identical (different refs)', () => {
      const old = [makeEdge('e1', 'a', 'b', { statusCounts: { completed: 3, running: 1 } })];
      const neu = [makeEdge('e1', 'a', 'b', { statusCounts: { completed: 3, running: 1 } })];
      expect(edgesHaveChanged(old, neu)).toBe(false);
    });

    it('should return false when both have empty statusCounts objects', () => {
      const old = [makeEdge('e1', 'a', 'b', { statusCounts: {} })];
      const neu = [makeEdge('e1', 'a', 'b', { statusCounts: {} })];
      expect(edgesHaveChanged(old, neu)).toBe(false);
    });

    it('should return true when one statusCounts is null and other is defined', () => {
      const old = [makeEdge('e1', 'a', 'b', { statusCounts: null })];
      const neu = [makeEdge('e1', 'a', 'b', { statusCounts: { completed: 1 } })];
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });
  });

  describe('multiple edges', () => {
    it('should return false when all edges are identical', () => {
      const old = [
        makeEdge('e1', 'a', 'b', { status: 'completed', statusCounts: { completed: 1 } }),
        makeEdge('e2', 'b', 'c', { status: 'running' }),
        makeEdge('e3', 'c', 'd'),
      ];
      const neu = [
        makeEdge('e1', 'a', 'b', { status: 'completed', statusCounts: { completed: 1 } }),
        makeEdge('e2', 'b', 'c', { status: 'running' }),
        makeEdge('e3', 'c', 'd'),
      ];
      expect(edgesHaveChanged(old, neu)).toBe(false);
    });

    it('should return true when only the last edge changes status', () => {
      const old = [
        makeEdge('e1', 'a', 'b', { status: 'completed' }),
        makeEdge('e2', 'b', 'c', { status: 'completed' }),
        makeEdge('e3', 'c', 'd', { status: 'running' }),
      ];
      const neu = [
        makeEdge('e1', 'a', 'b', { status: 'completed' }),
        makeEdge('e2', 'b', 'c', { status: 'completed' }),
        makeEdge('e3', 'c', 'd', { status: 'completed' }),
      ];
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });

    it('should return true when only the middle edge has statusCounts change', () => {
      const old = [
        makeEdge('e1', 'a', 'b', { statusCounts: { completed: 1 } }),
        makeEdge('e2', 'b', 'c', { statusCounts: { running: 2 } }),
        makeEdge('e3', 'c', 'd', { statusCounts: { pending: 3 } }),
      ];
      const neu = [
        makeEdge('e1', 'a', 'b', { statusCounts: { completed: 1 } }),
        makeEdge('e2', 'b', 'c', { statusCounts: { running: 5 } }),
        makeEdge('e3', 'c', 'd', { statusCounts: { pending: 3 } }),
      ];
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });
  });

  describe('edge order matters (positional comparison)', () => {
    it('should detect a difference when edges swap positions', () => {
      const old = [
        makeEdge('e1', 'a', 'b', { status: 'completed' }),
        makeEdge('e2', 'b', 'c', { status: 'running' }),
      ];
      const neu = [
        makeEdge('e2', 'b', 'c', { status: 'running' }),
        makeEdge('e1', 'a', 'b', { status: 'completed' }),
      ];
      // Positional: e1 vs e2 -> IDs differ -> changed
      expect(edgesHaveChanged(old, neu)).toBe(true);
    });
  });
});

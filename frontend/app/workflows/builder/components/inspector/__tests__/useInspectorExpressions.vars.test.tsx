// @vitest-environment jsdom
/**
 * Tests the workflow-variable ($vars / vars:) skip in useInspectorExpressions:
 * {{$vars.name}} and {{vars:name}} reference org/personal workflow variables,
 * not node outputs - they must never surface an "unknown variable" warning
 * (findUnknownVariables) nor auto-wire a connection
 * (createConnectionsForVariables), while real node references keep both
 * behaviors intact.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import type { Node } from 'reactflow';

import { useInspectorExpressions } from '../useInspectorExpressions';
import type { Connection } from '../useInspectorConnections';
import type { BuilderNodeData } from '../../../types';

function makeNode(id: string, data: Record<string, unknown> = {}): Node<BuilderNodeData> {
  return {
    id,
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: { id, label: id, ...data } as unknown as BuilderNodeData,
  } as Node<BuilderNodeData>;
}

/** Renders the hook around a current node plus a source node exposing `mcp:fetch`. */
function renderExpressionsHook() {
  const currentNode = makeNode('node-1', { paramExpressions: {} });
  const sourceNode = makeNode('node-2', { output: 'mcp:fetch' });
  const allNodes = [currentNode, sourceNode];

  const onUpdate = vi.fn();
  const setConnections = vi.fn();

  const rendered = renderHook(() =>
    useInspectorExpressions({
      node: currentNode,
      data: currentNode.data,
      allNodes,
      connectionType: 'bezier',
      onUpdate,
      setConnections,
      selectedLoopChild: null,
    })
  );

  return { ...rendered, currentNode, setConnections, onUpdate };
}

/** Applies the last functional updater passed to setConnections against `prev`. */
function applyLastConnectionsUpdate(
  setConnections: ReturnType<typeof vi.fn>,
  prev: Connection[]
): Connection[] {
  const lastCall = setConnections.mock.calls.at(-1);
  expect(lastCall).toBeDefined();
  const updater = lastCall![0] as (prev: Connection[]) => Connection[];
  expect(typeof updater).toBe('function');
  return updater(prev);
}

describe('useInspectorExpressions - workflow variables ($vars / vars:) skip', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('extractVariables', () => {
    it('skips $vars.* and vars:* references but keeps node references', () => {
      const { result } = renderExpressionsHook();

      const extracted = result.current.extractVariables(
        '{{$vars.x}} {{vars:y.z}} {{mcp:ghost.output.a}}'
      );

      expect(extracted).toEqual(['mcp:ghost']);
    });

    it('returns nothing for an expression made only of workflow variables', () => {
      const { result } = renderExpressionsHook();

      const extracted = result.current.extractVariables(
        'Use {{$vars.api_base_url}} and {{vars:region}}'
      );

      expect(extracted).toEqual([]);
    });

    it('still deduplicates and keeps default-value syntax for node references', () => {
      const { result } = renderExpressionsHook();

      const extracted = result.current.extractVariables(
        '{{mcp:fetch.output.a|fallback}} {{mcp:fetch.output.b}} {{$vars.x|default}}'
      );

      expect(extracted).toEqual(['mcp:fetch']);
    });
  });

  describe('findUnknownVariables', () => {
    it('flags only the unknown node reference, never $vars/vars: references', () => {
      const { result } = renderExpressionsHook();

      const unknown = result.current.findUnknownVariables({
        prompt: '{{$vars.x}} {{vars:y.z}} {{mcp:ghost.output.a}}',
      });

      expect(unknown).toEqual(['mcp:ghost']);
    });

    it('reports no unknowns when the only node reference has a source node', () => {
      const { result } = renderExpressionsHook();

      const unknown = result.current.findUnknownVariables({
        prompt: '{{$vars.token}} {{mcp:fetch.output.a}}',
      });

      expect(unknown).toEqual([]);
    });

    it('reports no unknowns for expressions containing only workflow variables', () => {
      const { result } = renderExpressionsHook();

      const unknown = result.current.findUnknownVariables({
        url: '{{$vars.api_base_url}}/items',
        header: '{{vars:auth_header}}',
      });

      expect(unknown).toEqual([]);
    });
  });

  describe('createConnectionsForVariables', () => {
    it('auto-wires the known node reference but never a $vars/vars: reference', () => {
      const { result, currentNode, setConnections } = renderExpressionsHook();
      setConnections.mockClear(); // drop the mount-time auto-sync call

      act(() => {
        result.current.createConnectionsForVariables(
          '{{$vars.x}} {{vars:y.z}} {{mcp:fetch.output.a}}',
          `param-prompt-${currentNode.id}`
        );
      });

      const next = applyLastConnectionsUpdate(setConnections, []);

      expect(next).toHaveLength(1);
      expect(next[0].source).toBe(`input-mcp:fetch-${currentNode.id}`);
      expect(next[0].target).toBe(`param-prompt-${currentNode.id}`);
      expect(next[0].type).toBe('bezier');
      // No handle was created for the workflow variables.
      expect(next.some(c => c.source.includes('$vars') || c.source.includes('vars:y'))).toBe(false);
    });

    it('adds no connection when the expression only contains workflow variables', () => {
      const { result, currentNode, setConnections } = renderExpressionsHook();
      setConnections.mockClear();

      act(() => {
        result.current.createConnectionsForVariables(
          '{{$vars.only}} and {{vars:other}}',
          `param-prompt-${currentNode.id}`
        );
      });

      const next = applyLastConnectionsUpdate(setConnections, []);

      expect(next).toEqual([]);
    });

    it('does not prune an existing connection whose input is a real node reference alongside $vars', () => {
      const { result, currentNode, setConnections } = renderExpressionsHook();
      setConnections.mockClear();

      const handleId = `param-prompt-${currentNode.id}`;
      const existing: Connection = {
        id: 'conn-existing',
        source: `input-mcp:fetch-${currentNode.id}`,
        target: handleId,
        sourceHandle: `input-mcp:fetch-${currentNode.id}`,
        targetHandle: handleId,
        type: 'bezier',
      };

      act(() => {
        result.current.createConnectionsForVariables(
          '{{mcp:fetch.output.a}} {{$vars.x}}',
          handleId
        );
      });

      const next = applyLastConnectionsUpdate(setConnections, [existing]);

      // The existing wire survives and no duplicate is added.
      expect(next).toEqual([existing]);
    });
  });
});

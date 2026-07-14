import { describe, it, expect } from 'vitest';
import type { Node } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import {
  sanitizeNodeMock,
  gateNodeMockForNode,
  ensureMockPort,
  nodeSupportsMock,
  isMockBlockedCore,
  isPortSelectingNode,
  isMcpCatalogToolNode,
  nodePortOptions,
  isMockedOutput,
  stripMockMarkers,
} from '../nodeMock';

function makeNode(
  type: string,
  kind: string,
  extraData: Partial<BuilderNodeData> = {},
  id = `${kind}-1`
): Node<BuilderNodeData> {
  return {
    id,
    type,
    position: { x: 0, y: 0 },
    data: { id, label: id, kind, ...extraData } as BuilderNodeData,
  };
}

function mcpToolNode(): Node<BuilderNodeData> {
  return makeNode('flowNode', 'action', {
    toolData: { toolId: 'gmail/send_email', toolSlug: 'send_email' },
  } as Partial<BuilderNodeData>);
}

describe('sanitizeNodeMock', () => {
  it('returns undefined for absent / non-object values', () => {
    expect(sanitizeNodeMock(undefined)).toBeUndefined();
    expect(sanitizeNodeMock(null)).toBeUndefined();
    expect(sanitizeNodeMock('mock')).toBeUndefined();
    expect(sanitizeNodeMock(42)).toBeUndefined();
    expect(sanitizeNodeMock([{ output: {} }])).toBeUndefined();
  });

  it('returns undefined for an empty block (an empty block = no mock)', () => {
    expect(sanitizeNodeMock({})).toBeUndefined();
  });

  it('returns undefined for a lone enabled flag (no mock content)', () => {
    expect(sanitizeNodeMock({ enabled: false })).toBeUndefined();
    expect(sanitizeNodeMock({ enabled: true })).toBeUndefined();
  });

  it('keeps enabled ONLY when explicitly false (true is the default)', () => {
    expect(sanitizeNodeMock({ enabled: false, output: { a: 1 } })).toEqual({
      enabled: false,
      output: { a: 1 },
    });
    expect(sanitizeNodeMock({ enabled: true, output: { a: 1 } })).toEqual({ output: { a: 1 } });
    expect(sanitizeNodeMock({ enabled: 'false', output: { a: 1 } })).toEqual({ output: { a: 1 } });
  });

  it('keeps source only when one of the 3 valid values', () => {
    expect(sanitizeNodeMock({ source: 'static', output: { a: 1 } })).toEqual({
      source: 'static',
      output: { a: 1 },
    });
    expect(sanitizeNodeMock({ source: 'catalog_example' })).toEqual({ source: 'catalog_example' });
    expect(sanitizeNodeMock({ source: 'error' })).toEqual({ source: 'error' });
    expect(sanitizeNodeMock({ source: 'other' })).toBeUndefined();
    expect(sanitizeNodeMock({ source: 42 })).toBeUndefined();
  });

  it('keeps output only when a plain object', () => {
    expect(sanitizeNodeMock({ output: { a: 1 } })).toEqual({ output: { a: 1 } });
    expect(sanitizeNodeMock({ output: [1, 2] })).toBeUndefined();
    expect(sanitizeNodeMock({ output: 'text' })).toBeUndefined();
    expect(sanitizeNodeMock({ output: null })).toBeUndefined();
  });

  it('keeps port only when a non-empty string', () => {
    expect(sanitizeNodeMock({ port: 'else' })).toEqual({ port: 'else' });
    expect(sanitizeNodeMock({ port: '' })).toBeUndefined();
    expect(sanitizeNodeMock({ port: '   ' })).toBeUndefined();
    expect(sanitizeNodeMock({ port: 3 })).toBeUndefined();
  });

  it('keeps error only when an object with a non-empty string message', () => {
    expect(sanitizeNodeMock({ error: { message: 'boom' } })).toEqual({
      error: { message: 'boom' },
    });
    expect(sanitizeNodeMock({ error: { message: '' } })).toBeUndefined();
    expect(sanitizeNodeMock({ error: { message: '  ' } })).toBeUndefined();
    expect(sanitizeNodeMock({ error: {} })).toBeUndefined();
    expect(sanitizeNodeMock({ error: 'boom' })).toBeUndefined();
  });

  it('keeps error.output when a plain object and drops it otherwise', () => {
    expect(sanitizeNodeMock({ error: { message: 'boom', output: { code: 500 } } })).toEqual({
      error: { message: 'boom', output: { code: 500 } },
    });
    expect(sanitizeNodeMock({ error: { message: 'boom', output: [1] } })).toEqual({
      error: { message: 'boom' },
    });
  });

  it('ignores unknown fields (forward compatibility)', () => {
    expect(sanitizeNodeMock({ output: { a: 1 }, futureFlag: true })).toEqual({ output: { a: 1 } });
  });
});

describe('nodeSupportsMock / isMockBlockedCore', () => {
  it('rejects triggers and notes', () => {
    expect(nodeSupportsMock(makeNode('triggerNode', 'entry'))).toBe(false);
    expect(nodeSupportsMock(makeNode('noteNode', 'note'))).toBe(false);
  });

  it('rejects split / merge / aggregate / loop / fork coordinator cores', () => {
    expect(nodeSupportsMock(makeNode('splitNode', 'split'))).toBe(false);
    expect(nodeSupportsMock(makeNode('mergeNode', 'merge'))).toBe(false);
    expect(nodeSupportsMock(makeNode('aggregateNode', 'aggregate'))).toBe(false);
    expect(nodeSupportsMock(makeNode('whileGroupNode', 'loop'))).toBe(false);
    expect(nodeSupportsMock(makeNode('forkNode', 'fork'))).toBe(false);
    expect(isMockBlockedCore(makeNode('splitNode', 'split'))).toBe(true);
    expect(isMockBlockedCore(makeNode('flowNode', 'action'))).toBe(false);
  });

  it('accepts mcp steps, cores, agents, tables and interfaces', () => {
    expect(nodeSupportsMock(mcpToolNode())).toBe(true);
    expect(nodeSupportsMock(makeNode('flowNode', 'transform'))).toBe(true);
    expect(nodeSupportsMock(makeNode('decisionNode', 'decision'))).toBe(true);
    expect(nodeSupportsMock(makeNode('classifyNode', 'classify'))).toBe(true);
    expect(nodeSupportsMock(makeNode('interfaceNode', 'interface'))).toBe(true);
  });
});

describe('isPortSelectingNode', () => {
  it('accepts decision / switch / option / approval / classify only', () => {
    expect(isPortSelectingNode(makeNode('decisionNode', 'decision'))).toBe(true);
    expect(isPortSelectingNode(makeNode('switchNode', 'switch'))).toBe(true);
    expect(isPortSelectingNode(makeNode('optionNode', 'option'))).toBe(true);
    expect(isPortSelectingNode(makeNode('userApprovalNode', 'approval'))).toBe(true);
    expect(isPortSelectingNode(makeNode('classifyNode', 'classify'))).toBe(true);
    expect(isPortSelectingNode(makeNode('flowNode', 'action'))).toBe(false);
    expect(isPortSelectingNode(makeNode('guardrailNode', 'guardrail'))).toBe(false);
  });
});

describe('isMcpCatalogToolNode', () => {
  it('requires a bound catalog tool (toolId or toolSlug)', () => {
    expect(isMcpCatalogToolNode(mcpToolNode())).toBe(true);
    expect(
      isMcpCatalogToolNode(
        makeNode('flowNode', 'action', { toolData: { toolSlug: 'send_email' } } as Partial<BuilderNodeData>)
      )
    ).toBe(true);
    expect(isMcpCatalogToolNode(makeNode('flowNode', 'action'))).toBe(false);
    expect(
      isMcpCatalogToolNode(makeNode('flowNode', 'action', { toolData: {} } as Partial<BuilderNodeData>))
    ).toBe(false);
  });
});

describe('gateNodeMockForNode', () => {
  it('returns undefined when the node does not support mocks', () => {
    expect(gateNodeMockForNode({ output: { a: 1 } }, makeNode('triggerNode', 'entry'))).toBeUndefined();
    expect(gateNodeMockForNode({ output: { a: 1 } }, makeNode('splitNode', 'split'))).toBeUndefined();
    expect(gateNodeMockForNode({ output: { a: 1 } }, makeNode('forkNode', 'fork'))).toBeUndefined();
  });

  it('drops catalog_example on non-mcp nodes (and returns undefined when nothing remains)', () => {
    const transform = makeNode('flowNode', 'transform');
    expect(gateNodeMockForNode({ source: 'catalog_example' }, transform)).toBeUndefined();
    expect(
      gateNodeMockForNode({ source: 'catalog_example', output: { a: 1 } }, transform)
    ).toEqual({ output: { a: 1 } });
  });

  it('keeps catalog_example on mcp catalog tool nodes', () => {
    expect(gateNodeMockForNode({ source: 'catalog_example' }, mcpToolNode())).toEqual({
      source: 'catalog_example',
    });
  });

  it('drops port on non-port-selecting nodes (and returns undefined when nothing remains)', () => {
    const mcp = mcpToolNode();
    expect(gateNodeMockForNode({ port: 'if' }, mcp)).toBeUndefined();
    expect(gateNodeMockForNode({ port: 'if', output: { a: 1 } }, mcp)).toEqual({
      output: { a: 1 },
    });
  });

  it('keeps port on port-selecting nodes', () => {
    const decision = makeNode('decisionNode', 'decision');
    expect(gateNodeMockForNode({ port: 'else' }, decision)).toEqual({ port: 'else' });
  });

  it('is a no-op for undefined input', () => {
    expect(gateNodeMockForNode(undefined, mcpToolNode())).toBeUndefined();
  });
});

describe('ensureMockPort', () => {
  // Backend parse rule: a STATIC mock on a port-selecting node must select a
  // branch, otherwise EVERY subsequent editor run fails at parse time. The
  // guard defaults the port so UI-authored mocks are always valid.

  it('defaults a static mock to the first port on a decision node', () => {
    const decision = makeNode('decisionNode', 'decision', {
      decisionConditions: [
        { id: 'c1', type: 'if', label: 'If' },
        { id: 'c2', type: 'else', label: 'Else' },
      ],
    });
    expect(ensureMockPort({ output: { a: 1 } }, decision)).toEqual({
      output: { a: 1 },
      port: 'if',
    });
  });

  it('defaults to the first approval port on an approval node', () => {
    const approval = makeNode('userApprovalNode', 'approval');
    expect(ensureMockPort({ output: {} }, approval)).toEqual({
      output: {},
      port: 'approved',
    });
  });

  it('keeps an already-selected port untouched', () => {
    const decision = makeNode('decisionNode', 'decision');
    expect(ensureMockPort({ output: { a: 1 }, port: 'else' }, decision)).toEqual({
      output: { a: 1 },
      port: 'else',
    });
  });

  it('never adds a port on non-branching nodes', () => {
    expect(ensureMockPort({ output: { a: 1 } }, mcpToolNode())).toEqual({ output: { a: 1 } });
    expect(
      ensureMockPort({ output: { a: 1 } }, makeNode('flowNode', 'transform'))
    ).toEqual({ output: { a: 1 } });
  });

  it('leaves catalog_example and error mocks untouched (they are not static)', () => {
    const decision = makeNode('decisionNode', 'decision');
    expect(ensureMockPort({ source: 'catalog_example' }, decision)).toEqual({
      source: 'catalog_example',
    });
    expect(ensureMockPort({ error: { message: 'boom' } }, decision)).toEqual({
      error: { message: 'boom' },
    });
  });

  it('leaves a mock without output untouched (nothing static to route)', () => {
    const decision = makeNode('decisionNode', 'decision');
    expect(ensureMockPort({ enabled: false }, decision)).toEqual({ enabled: false });
  });

  it('passes through when the node has no derivable ports', () => {
    // A classify node without categories has no valid ports to default to.
    const classify = makeNode('classifyNode', 'classify');
    expect(ensureMockPort({ output: {} }, classify)).toEqual({ output: {} });
  });

  it('is a no-op for undefined input', () => {
    expect(ensureMockPort(undefined, makeNode('decisionNode', 'decision'))).toBeUndefined();
  });
});

describe('nodePortOptions', () => {
  it('derives decision ports from decisionConditions (if / elseif_N / else)', () => {
    const decision = makeNode('decisionNode', 'decision', {
      decisionConditions: [
        { id: 'c1', type: 'if', label: 'If' },
        { id: 'c2', type: 'elseif', label: 'Else If' },
        { id: 'c3', type: 'elseif', label: 'Else If 2' },
        { id: 'c4', type: 'else', label: 'Else' },
      ],
    });
    expect(nodePortOptions(decision)).toEqual(['if', 'elseif_0', 'elseif_1', 'else']);
  });

  it('falls back to [if, else] for a decision without conditions', () => {
    expect(nodePortOptions(makeNode('decisionNode', 'decision'))).toEqual(['if', 'else']);
  });

  it('derives switch ports from switchCases (case_N / default, array index like edgeProcessor)', () => {
    const switchNode = makeNode('switchNode', 'switch', {
      switchCases: [
        { id: 's1', type: 'case', label: 'A', value: 'a' },
        { id: 's2', type: 'case', label: 'B', value: 'b' },
        { id: 's3', type: 'default', label: 'Default' },
      ],
    });
    expect(nodePortOptions(switchNode)).toEqual(['case_0', 'case_1', 'default']);
  });

  it('derives option ports from optionChoices (choice_N)', () => {
    const option = makeNode('optionNode', 'option', {
      optionChoices: [
        { id: 'o1', label: 'One' },
        { id: 'o2', label: 'Two' },
      ],
    });
    expect(nodePortOptions(option)).toEqual(['choice_0', 'choice_1']);
  });

  it('returns the fixed approval ports', () => {
    expect(nodePortOptions(makeNode('userApprovalNode', 'approval'))).toEqual([
      'approved',
      'rejected',
      'timeout',
    ]);
  });

  it('derives classify ports from classifyCategories (category_N)', () => {
    const classify = makeNode('classifyNode', 'classify', {
      classifyCategories: [
        { id: 'k1', label: 'Spam' },
        { id: 'k2', label: 'Ham' },
        { id: 'k3', label: 'Other' },
      ],
    });
    expect(nodePortOptions(classify)).toEqual(['category_0', 'category_1', 'category_2']);
  });

  it('returns [] for non-port-selecting nodes', () => {
    expect(nodePortOptions(mcpToolNode())).toEqual([]);
    expect(nodePortOptions(makeNode('flowNode', 'transform'))).toEqual([]);
  });
});

describe('mocked-output markers', () => {
  it('isMockedOutput detects the engine marker key', () => {
    expect(isMockedOutput({ __mocked__: true, a: 1 })).toBe(true);
    expect(isMockedOutput({ __mocked__: false, a: 1 })).toBe(false);
    expect(isMockedOutput({ a: 1 })).toBe(false);
    expect(isMockedOutput(null)).toBe(false);
    expect(isMockedOutput([{ __mocked__: true }])).toBe(false);
  });

  it('stripMockMarkers copies the output without __mocked__ / __mock_source__', () => {
    expect(
      stripMockMarkers({ __mocked__: true, __mock_source__: 'static', a: 1, nested: { b: 2 } })
    ).toEqual({ a: 1, nested: { b: 2 } });
  });

  it('stripMockMarkers returns undefined for non-objects', () => {
    expect(stripMockMarkers(null)).toBeUndefined();
    expect(stripMockMarkers('x')).toBeUndefined();
    expect(stripMockMarkers([1])).toBeUndefined();
  });
});

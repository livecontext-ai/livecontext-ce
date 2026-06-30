import { describe, it, expect } from 'vitest';
import type { Node, Edge, Connection } from 'reactflow';
import type { BuilderNodeData } from '../../types';
import { validateConnection, isWiredOutputPort } from '../connectionValidator';

// ---------------------------------------------------------------------------
// Helpers to create mock objects
// ---------------------------------------------------------------------------

function makeNode(
  overrides: Partial<Node<BuilderNodeData>> & { id: string },
): Node<BuilderNodeData> {
  return {
    position: { x: 0, y: 0 },
    data: {
      id: overrides.id,
      label: overrides.id,
      kind: 'action',
      ...((overrides.data ?? {}) as any),
    },
    type: 'flowNode',
    ...overrides,
  } as Node<BuilderNodeData>;
}

function makeEdge(
  overrides: Partial<Edge> & { id: string; source: string; target: string },
): Edge {
  return {
    ...overrides,
  } as Edge;
}

function conn(
  source: string,
  target: string,
  sourceHandle?: string | null,
  targetHandle?: string | null,
): Connection {
  return {
    source,
    target,
    sourceHandle: sourceHandle ?? null,
    targetHandle: targetHandle ?? null,
  };
}

// ---------------------------------------------------------------------------
// Default pool of nodes used in many tests
// ---------------------------------------------------------------------------

const triggerNode = makeNode({
  id: 'webhook-trigger-1',
  type: 'flowNode',
  data: {
    id: 'webhook-trigger-1',
    label: 'Webhook',
    kind: 'entry',
    toolData: { apiName: 'x', method: 'POST' },
  } as any,
});

const mcpStepA = makeNode({
  id: 'step-a',
  type: 'flowNode',
  data: {
    id: 'mcp-step-a',
    label: 'Step A',
    kind: 'action',
    toolData: { apiName: 'a', method: 'GET' },
  } as any,
});

const mcpStepB = makeNode({
  id: 'step-b',
  type: 'flowNode',
  data: {
    id: 'mcp-step-b',
    label: 'Step B',
    kind: 'action',
    toolData: { apiName: 'b', method: 'GET' },
  } as any,
});

const decisionNode = makeNode({
  id: 'decision-1',
  type: 'decisionNode',
  data: {
    id: 'decision-1',
    label: 'Decision',
    kind: 'decision',
  } as any,
});

const switchNode = makeNode({
  id: 'switch-1',
  type: 'switchNode',
  data: {
    id: 'switch-1',
    label: 'Switch',
    kind: 'switch',
  } as any,
});

const optionNode = makeNode({
  id: 'option-1',
  type: 'optionNode',
  data: {
    id: 'option-1',
    label: 'Option',
    kind: 'option',
  } as any,
});

const loopNode = makeNode({
  id: 'loop-1',
  type: 'whileGroupNode',
  data: {
    id: 'loop-1',
    label: 'Loop',
    kind: 'loop',
  } as any,
});

const splitNode = makeNode({
  id: 'split-1',
  type: 'splitNode',
  data: {
    id: 'split-1',
    label: 'Split',
    kind: 'split',
  } as any,
});

const forkNode = makeNode({
  id: 'fork-1',
  type: 'forkNode',
  data: {
    id: 'fork-1',
    label: 'Fork',
    kind: 'fork',
  } as any,
});

const mergeNode = makeNode({
  id: 'merge-1',
  type: 'mergeNode',
  data: {
    id: 'merge-1',
    label: 'Merge',
    kind: 'merge',
  } as any,
});

const interfaceNode = makeNode({
  id: 'interface-1',
  type: 'interfaceNode',
  data: {
    id: 'interface-1',
    label: 'Interface',
    kind: 'interface',
  } as any,
});

const classifyNode = makeNode({
  id: 'classify-1',
  type: 'classifyNode',
  data: {
    id: 'classify-1',
    label: 'Classify',
    kind: 'agent',
  } as any,
});

const guardrailNode = makeNode({
  id: 'guardrail-1',
  type: 'guardrailNode',
  data: {
    id: 'guardrail-1',
    label: 'Guardrail',
    kind: 'agent',
  } as any,
});

const agentNode = makeNode({
  id: 'agent-1',
  type: 'agentNode',
  data: {
    id: 'agent-1',
    label: 'Agent',
    kind: 'agent',
  } as any,
});

const defaultNodes: Node<BuilderNodeData>[] = [
  triggerNode,
  mcpStepA,
  mcpStepB,
  decisionNode,
  switchNode,
  optionNode,
  loopNode,
  splitNode,
  forkNode,
  mergeNode,
  interfaceNode,
  classifyNode,
  guardrailNode,
  agentNode,
];

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('validateConnection', () => {
  // ===================== Self-connections =====================

  describe('self-connections', () => {
    it('should reject a connection where source equals target', () => {
      const result = validateConnection(
        conn('step-a', 'step-a'),
        defaultNodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should reject self-connection even with handles', () => {
      const result = validateConnection(
        conn('step-a', 'step-a', 'source-bottom', 'target-top'),
        defaultNodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should reject self-connection on a loop node', () => {
      const result = validateConnection(
        conn('loop-1', 'loop-1', 'loop-1-source-exit', 'loop-1-target-entry'),
        defaultNodes,
        [],
      );
      expect(result).toBe(false);
    });
  });

  // ===================== Loop exit constraints =====================

  describe('loop exit constraints', () => {
    it('should reject a connection from a loop source handle that is NOT the exit', () => {
      // e.g. loop-1-source-body should be rejected
      const result = validateConnection(
        conn('loop-1', 'step-a', 'loop-1-source-body', null),
        defaultNodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should allow a connection from a loop exit source handle', () => {
      const result = validateConnection(
        conn('loop-1', 'step-a', 'loop-1-source-exit', null),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should reject a connection from loop-iterate-source handle', () => {
      const result = validateConnection(
        conn('loop-1', 'step-a', 'loop-iterate-source', null),
        defaultNodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should reject a second exit connection from a loop node', () => {
      const existingEdges: Edge[] = [
        makeEdge({
          id: 'e1',
          source: 'loop-1',
          target: 'step-a',
          sourceHandle: 'loop-1-source-exit',
        }),
      ];
      const result = validateConnection(
        conn('loop-1', 'step-b', 'loop-1-source-exit', null),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(false);
    });

    it('should allow the first exit connection from a loop node', () => {
      const result = validateConnection(
        conn('loop-1', 'step-a', 'loop-1-source-exit', null),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });
  });

  // ===================== Loop entry constraints =====================

  describe('loop entry constraints', () => {
    it('should reject a connection to a loop target handle that is NOT the entry', () => {
      const result = validateConnection(
        conn('step-a', 'loop-1', null, 'loop-1-target-body'),
        defaultNodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should allow a connection to a loop entry target handle', () => {
      const result = validateConnection(
        conn('step-a', 'loop-1', null, 'loop-1-target-entry'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should reject a second entry connection to a loop node', () => {
      const existingEdges: Edge[] = [
        makeEdge({
          id: 'e1',
          source: 'step-a',
          target: 'loop-1',
          targetHandle: 'loop-1-target-entry',
        }),
      ];
      const result = validateConnection(
        conn('step-b', 'loop-1', null, 'loop-1-target-entry'),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(false);
    });

    it('should allow the first entry connection to a loop node', () => {
      const result = validateConnection(
        conn('step-a', 'loop-1', null, 'loop-1-target-entry'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });
  });

  // ===================== Single-entry branching nodes =====================

  describe('single-entry branching nodes (decision, switch, option, classify, guardrail, fork, split, loop)', () => {
    it('should reject a second incoming edge to a decision node', () => {
      const existingEdges: Edge[] = [
        makeEdge({ id: 'e1', source: 'step-a', target: 'decision-1' }),
      ];
      const result = validateConnection(
        conn('step-b', 'decision-1'),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(false);
    });

    it('should allow the first incoming edge to a decision node', () => {
      const result = validateConnection(
        conn('step-a', 'decision-1'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should reject a second incoming edge to a switch node', () => {
      const existingEdges: Edge[] = [
        makeEdge({ id: 'e1', source: 'step-a', target: 'switch-1' }),
      ];
      const result = validateConnection(
        conn('step-b', 'switch-1'),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(false);
    });

    it('should allow the first incoming edge to a switch node', () => {
      const result = validateConnection(
        conn('step-a', 'switch-1'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should reject a second incoming edge to an option node', () => {
      const existingEdges: Edge[] = [
        makeEdge({ id: 'e1', source: 'step-a', target: 'option-1' }),
      ];
      const result = validateConnection(
        conn('step-b', 'option-1'),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(false);
    });

    it('should reject a second incoming edge to a classify node', () => {
      const existingEdges: Edge[] = [
        makeEdge({ id: 'e1', source: 'step-a', target: 'classify-1' }),
      ];
      const result = validateConnection(
        conn('step-b', 'classify-1'),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(false);
    });

    it('should reject a second incoming edge to a guardrail node', () => {
      const existingEdges: Edge[] = [
        makeEdge({ id: 'e1', source: 'step-a', target: 'guardrail-1' }),
      ];
      const result = validateConnection(
        conn('step-b', 'guardrail-1'),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(false);
    });

    it('should reject a second incoming edge to a fork node', () => {
      const existingEdges: Edge[] = [
        makeEdge({ id: 'e1', source: 'step-a', target: 'fork-1' }),
      ];
      const result = validateConnection(
        conn('step-b', 'fork-1'),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(false);
    });

    it('should reject a second incoming edge to a split node', () => {
      const existingEdges: Edge[] = [
        makeEdge({ id: 'e1', source: 'step-a', target: 'split-1' }),
      ];
      const result = validateConnection(
        conn('step-b', 'split-1'),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(false);
    });

    it('should allow multiple incoming edges to a merge node', () => {
      const existingEdges: Edge[] = [
        makeEdge({ id: 'e1', source: 'step-a', target: 'merge-1' }),
      ];
      const result = validateConnection(
        conn('step-b', 'merge-1'),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(true);
    });
  });

  // ===================== Branching node duplicate branch edge =====================

  describe('branching node duplicate branch edge', () => {
    it('should reject a second connection from the same decision branch handle', () => {
      const existingEdges: Edge[] = [
        makeEdge({
          id: 'e1',
          source: 'decision-1',
          target: 'step-a',
          sourceHandle: 'decision-1-if',
        }),
      ];
      const result = validateConnection(
        conn('decision-1', 'step-b', 'decision-1-if', null),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(false);
    });

    it('should allow a connection from a different decision branch handle', () => {
      const existingEdges: Edge[] = [
        makeEdge({
          id: 'e1',
          source: 'decision-1',
          target: 'step-a',
          sourceHandle: 'decision-1-if',
        }),
      ];
      const result = validateConnection(
        conn('decision-1', 'step-b', 'decision-1-else', null),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(true);
    });

    it('should reject a second connection from the same switch branch handle', () => {
      const existingEdges: Edge[] = [
        makeEdge({
          id: 'e1',
          source: 'switch-1',
          target: 'step-a',
          sourceHandle: 'switch-1-case-0',
        }),
      ];
      const result = validateConnection(
        conn('switch-1', 'step-b', 'switch-1-case-0', null),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(false);
    });

    it('should allow connection from a different switch case handle', () => {
      const existingEdges: Edge[] = [
        makeEdge({
          id: 'e1',
          source: 'switch-1',
          target: 'step-a',
          sourceHandle: 'switch-1-case-0',
        }),
      ];
      const result = validateConnection(
        conn('switch-1', 'step-b', 'switch-1-case-1', null),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(true);
    });

    it('should reject a second connection from the same fork branch handle', () => {
      const existingEdges: Edge[] = [
        makeEdge({
          id: 'e1',
          source: 'fork-1',
          target: 'step-a',
          sourceHandle: 'fork-1-branch_0',
        }),
      ];
      const result = validateConnection(
        conn('fork-1', 'step-b', 'fork-1-branch_0', null),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(false);
    });

    it('should allow a connection from a different fork branch handle', () => {
      const existingEdges: Edge[] = [
        makeEdge({
          id: 'e1',
          source: 'fork-1',
          target: 'step-a',
          sourceHandle: 'fork-1-branch_0',
        }),
      ];
      const result = validateConnection(
        conn('fork-1', 'step-b', 'fork-1-branch_1', null),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(true);
    });

    it('should reject a second connection from the same guardrail branch handle', () => {
      const existingEdges: Edge[] = [
        makeEdge({
          id: 'e1',
          source: 'guardrail-1',
          target: 'step-a',
          sourceHandle: 'guardrail-1-pass',
        }),
      ];
      const result = validateConnection(
        conn('guardrail-1', 'step-b', 'guardrail-1-pass', null),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(false);
    });

    it('should allow a connection from a different guardrail branch handle', () => {
      const existingEdges: Edge[] = [
        makeEdge({
          id: 'e1',
          source: 'guardrail-1',
          target: 'step-a',
          sourceHandle: 'guardrail-1-pass',
        }),
      ];
      const result = validateConnection(
        conn('guardrail-1', 'step-b', 'guardrail-1-fail', null),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(true);
    });
  });

  // ===================== Interface connections (standard left/right handles) =====================

  describe('interface standard handle connections', () => {
    it('should allow standard connection to an interface node', () => {
      const result = validateConnection(
        conn('step-a', 'interface-1', 'source-right', null),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow connection from an interface node to a step', () => {
      const result = validateConnection(
        conn('interface-1', 'step-a', 'source-right', null),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });
  });

  // ===================== Generic trigger rejection =====================

  describe('generic trigger connection rejection', () => {
    it('should reject connection FROM a generic trigger (triggers) node', () => {
      const genericTrigger = makeNode({
        id: 'triggers-1',
        type: 'flowNode',
        data: {
          id: 'triggers-1',
          label: 'Triggers',
          kind: 'entry',
        } as any,
      });
      const nodes = [...defaultNodes, genericTrigger];
      const result = validateConnection(
        conn('triggers-1', 'step-a'),
        nodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should reject connection TO a generic trigger (triggers) node', () => {
      const genericTrigger = makeNode({
        id: 'triggers-1',
        type: 'flowNode',
        data: {
          id: 'triggers-1',
          label: 'Triggers',
          kind: 'entry',
        } as any,
      });
      const nodes = [...defaultNodes, genericTrigger];
      const result = validateConnection(
        conn('step-a', 'triggers-1'),
        nodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should reject connection FROM a generic entry node without dataSourceData', () => {
      const genericEntry = makeNode({
        id: 'some-entry-1',
        type: 'flowNode',
        data: {
          id: 'some-entry-1',
          label: 'Generic Entry',
          kind: 'entry',
        } as any,
      });
      const nodes = [...defaultNodes, genericEntry];
      const result = validateConnection(
        conn('some-entry-1', 'step-a'),
        nodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should reject connection TO a generic entry trigger without specific trigger ID', () => {
      const genericEntry = makeNode({
        id: 'my-custom-1',
        type: 'flowNode',
        data: {
          id: 'my-custom-1',
          label: 'Custom Entry',
          kind: 'entry',
        } as any,
      });
      const nodes = [...defaultNodes, genericEntry];
      const result = validateConnection(
        conn('step-a', 'my-custom-1'),
        nodes,
        [],
      );
      expect(result).toBe(false);
    });
  });

  // ===================== Trigger multiple outgoing connections (implicit fork) =====================

  describe('trigger multiple outgoing connections (implicit fork)', () => {
    it('should allow the first outgoing connection from a trigger', () => {
      const result = validateConnection(
        conn('webhook-trigger-1', 'step-a'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow a second outgoing connection from a trigger (implicit fork)', () => {
      const existingEdges: Edge[] = [
        makeEdge({ id: 'e1', source: 'webhook-trigger-1', target: 'step-a' }),
      ];
      const result = validateConnection(
        conn('webhook-trigger-1', 'step-b'),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(true);
    });

    it('should allow different triggers to each have outgoing connections', () => {
      const scheduleTrigger = makeNode({
        id: 'schedule-trigger-1',
        type: 'triggerNode',
        data: {
          id: 'schedule-trigger-1',
          label: 'Schedule',
          kind: 'entry',
          toolData: { apiName: 'schedule', method: 'GET' },
        } as any,
      });
      const nodes = [...defaultNodes, scheduleTrigger];
      const existingEdges: Edge[] = [
        makeEdge({ id: 'e1', source: 'webhook-trigger-1', target: 'step-a' }),
      ];
      const result = validateConnection(
        conn('schedule-trigger-1', 'step-b'),
        nodes,
        existingEdges,
      );
      expect(result).toBe(true);
    });

    it('should allow multiple outgoing connections from any trigger (implicit fork)', () => {
      const scheduleTrigger = makeNode({
        id: 'schedule-trigger-1',
        type: 'triggerNode',
        data: {
          id: 'schedule-trigger-1',
          label: 'Schedule',
          kind: 'entry',
          toolData: { apiName: 'schedule', method: 'GET' },
        } as any,
      });
      const nodes = [...defaultNodes, scheduleTrigger];
      const existingEdges: Edge[] = [
        makeEdge({ id: 'e1', source: 'schedule-trigger-1', target: 'step-a' }),
      ];
      const result = validateConnection(
        conn('schedule-trigger-1', 'step-b'),
        nodes,
        existingEdges,
      );
      expect(result).toBe(true);
    });

    it('should allow multiple triggers each with multiple outgoing connections', () => {
      const chatTrigger = makeNode({
        id: 'chat-trigger-1',
        type: 'triggerNode',
        data: {
          id: 'chat-trigger-1',
          label: 'Chat',
          kind: 'entry',
          toolData: { apiName: 'chat', method: 'POST' },
        } as any,
      });
      const nodes = [...defaultNodes, chatTrigger];
      const existingEdges: Edge[] = [
        makeEdge({ id: 'e1', source: 'webhook-trigger-1', target: 'step-a' }),
        makeEdge({ id: 'e2', source: 'chat-trigger-1', target: 'step-b' }),
      ];
      // Both triggers can add more connections (implicit fork)
      const result1 = validateConnection(
        conn('webhook-trigger-1', 'step-b'),
        nodes,
        existingEdges,
      );
      expect(result1).toBe(true);

      const result2 = validateConnection(
        conn('chat-trigger-1', 'step-a'),
        nodes,
        existingEdges,
      );
      expect(result2).toBe(true);
    });
  });

  // ===================== Generic table rejection =====================

  describe('generic table connection rejection', () => {
    it('should reject connection FROM a tables-trigger without datasource configured', () => {
      const genericTable = makeNode({
        id: 'tables-trigger-1',
        type: 'flowNode',
        data: {
          id: 'tables-trigger',
          label: 'Tables',
          kind: 'entry',
        } as any,
      });
      const nodes = [...defaultNodes, genericTable];
      const result = validateConnection(
        conn('tables-trigger-1', 'step-a'),
        nodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should reject connection TO a generic tables node', () => {
      const genericTable = makeNode({
        id: 'tables-trigger-1',
        type: 'flowNode',
        data: {
          id: 'tables-trigger',
          label: 'Tables',
          kind: 'entry',
        } as any,
      });
      const nodes = [...defaultNodes, genericTable];
      const result = validateConnection(
        conn('step-a', 'tables-trigger-1'),
        nodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should allow connection FROM a tables-trigger WITH datasource configured', () => {
      const configuredTable = makeNode({
        id: 'tables-trigger-1',
        type: 'flowNode',
        data: {
          id: 'tables-trigger-1',
          label: 'My Table',
          kind: 'entry',
          dataSourceData: { dataSourceId: 1, dataSourceName: 'test', tableName: 'users' },
          toolData: { apiName: 'x', method: 'GET' },
        } as any,
      });
      const nodes = [...defaultNodes, configuredTable];
      const result = validateConnection(
        conn('tables-trigger-1', 'step-a'),
        nodes,
        [],
      );
      expect(result).toBe(true);
    });
  });

  // ===================== Generic MCP / API rejection =====================

  describe('generic MCP/API node rejection', () => {
    it('should reject connection FROM a generic mcp node without toolData', () => {
      const genericMcp = makeNode({
        id: 'mcp-node-1',
        type: 'flowNode',
        data: {
          id: 'mcp-node-1',
          label: 'MCP Generic',
          kind: 'action',
        } as any,
      });
      const nodes = [...defaultNodes, genericMcp];
      const result = validateConnection(
        conn('mcp-node-1', 'step-a'),
        nodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should reject connection FROM a generic api node without toolData', () => {
      const genericApi = makeNode({
        id: 'api-node-1',
        type: 'flowNode',
        data: {
          id: 'api-node-1',
          label: 'API Generic',
          kind: 'action',
        } as any,
      });
      const nodes = [...defaultNodes, genericApi];
      const result = validateConnection(
        conn('api-node-1', 'step-a'),
        nodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should reject connection FROM a node with apiData but no toolData', () => {
      const apiOnly = makeNode({
        id: 'api-only-1',
        type: 'flowNode',
        data: {
          id: 'api-only-1',
          label: 'API Only',
          kind: 'action',
          apiData: { apiName: 'SomeAPI' },
        } as any,
      });
      const nodes = [...defaultNodes, apiOnly];
      const result = validateConnection(
        conn('api-only-1', 'step-a'),
        nodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should reject connection TO a node with apiData but no toolData', () => {
      const apiOnly = makeNode({
        id: 'api-only-1',
        type: 'flowNode',
        data: {
          id: 'api-only-1',
          label: 'API Only',
          kind: 'action',
          apiData: { apiName: 'SomeAPI' },
        } as any,
      });
      const nodes = [...defaultNodes, apiOnly];
      const result = validateConnection(
        conn('step-a', 'api-only-1'),
        nodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should reject connection TO a generic mcp node without toolData', () => {
      const genericMcp = makeNode({
        id: 'mcp-node-1',
        type: 'flowNode',
        data: {
          id: 'mcp-node-1',
          label: 'MCP Generic',
          kind: 'action',
        } as any,
      });
      const nodes = [...defaultNodes, genericMcp];
      const result = validateConnection(
        conn('step-a', 'mcp-node-1'),
        nodes,
        [],
      );
      expect(result).toBe(false);
    });
  });

  // ===================== Valid connections =====================

  describe('valid connections', () => {
    it('should allow a connection between two configured MCP steps', () => {
      const result = validateConnection(
        conn('step-a', 'step-b'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow a connection from a configured webhook trigger to an MCP step', () => {
      const result = validateConnection(
        conn('webhook-trigger-1', 'step-a'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow a connection from MCP step to a decision node', () => {
      const result = validateConnection(
        conn('step-a', 'decision-1'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow a connection from decision if-handle to MCP step', () => {
      const result = validateConnection(
        conn('decision-1', 'step-a', 'decision-1-if', null),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow a connection from MCP step to merge node', () => {
      const result = validateConnection(
        conn('step-a', 'merge-1'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow multiple edges into a merge node', () => {
      const existingEdges: Edge[] = [
        makeEdge({ id: 'e1', source: 'step-a', target: 'merge-1' }),
      ];
      const result = validateConnection(
        conn('step-b', 'merge-1'),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(true);
    });

    it('should allow a connection from trigger to agent node', () => {
      const result = validateConnection(
        conn('webhook-trigger-1', 'agent-1'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow a connection from agent node to step', () => {
      const result = validateConnection(
        conn('agent-1', 'step-a'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });
  });

  // ===================== Source node not found =====================

  describe('source or target node not found in nodes array', () => {
    it('should return true when source node is not in the nodes array (no validation possible)', () => {
      const result = validateConnection(
        conn('nonexistent', 'step-a'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should return true when target node is not in the nodes array (no validation possible)', () => {
      const result = validateConnection(
        conn('step-a', 'nonexistent'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should return true when both source and target are not in nodes', () => {
      const result = validateConnection(
        conn('nonexistent-1', 'nonexistent-2'),
        [],
        [],
      );
      expect(result).toBe(true);
    });
  });

  // ===================== Edge cases with handles =====================

  describe('handle edge cases', () => {
    it('should handle null sourceHandle and targetHandle gracefully', () => {
      const result = validateConnection(
        conn('step-a', 'step-b', null, null),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow non-loop handles starting with "loop-" but ending with "-exit"', () => {
      // This tests the sourceHandle guard: starts with "loop-" and includes "-source" but ends with "-exit" so is allowed
      const result = validateConnection(
        conn('loop-1', 'step-a', 'loop-1-source-exit', null),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow non-loop target handles ending with "-entry"', () => {
      const result = validateConnection(
        conn('step-a', 'loop-1', null, 'loop-1-target-entry'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should reject loop-body-source handle (loop internal handle)', () => {
      const result = validateConnection(
        conn('loop-1', 'step-a', 'loop-body-source', null),
        defaultNodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should reject loop-body-target handle (loop internal handle)', () => {
      const result = validateConnection(
        conn('step-a', 'loop-1', null, 'loop-body-target'),
        defaultNodes,
        [],
      );
      expect(result).toBe(false);
    });
  });

  // ===================== Specific trigger type checks =====================

  describe('specific trigger types', () => {
    it('should allow connection from a webhook-trigger with entry kind', () => {
      const webhookTrigger = makeNode({
        id: 'webhook-trigger-99',
        type: 'flowNode',
        data: {
          id: 'webhook-trigger-99',
          label: 'Webhook',
          kind: 'entry',
          toolData: { apiName: 'webhook', method: 'POST' },
        } as any,
      });
      const nodes = [...defaultNodes, webhookTrigger];
      const result = validateConnection(
        conn('webhook-trigger-99', 'step-a'),
        nodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow connection from a schedule-trigger with entry kind', () => {
      const scheduleTrigger = makeNode({
        id: 'schedule-trigger-1',
        type: 'flowNode',
        data: {
          id: 'schedule-trigger-1',
          label: 'Schedule',
          kind: 'entry',
          toolData: { apiName: 'schedule', method: 'GET' },
        } as any,
      });
      const nodes = [...defaultNodes, scheduleTrigger];
      const result = validateConnection(
        conn('schedule-trigger-1', 'step-a'),
        nodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow connection from a chat-trigger with entry kind', () => {
      const chatTrigger = makeNode({
        id: 'chat-trigger-1',
        type: 'flowNode',
        data: {
          id: 'chat-trigger-1',
          label: 'Chat',
          kind: 'entry',
          toolData: { apiName: 'chat', method: 'POST' },
        } as any,
      });
      const nodes = [...defaultNodes, chatTrigger];
      const result = validateConnection(
        conn('chat-trigger-1', 'step-a'),
        nodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow connection from a manual-trigger with entry kind', () => {
      const manualTrigger = makeNode({
        id: 'manual-trigger-1',
        type: 'flowNode',
        data: {
          id: 'manual-trigger-1',
          label: 'Manual',
          kind: 'entry',
          toolData: { apiName: 'manual', method: 'POST' },
        } as any,
      });
      const nodes = [...defaultNodes, manualTrigger];
      const result = validateConnection(
        conn('manual-trigger-1', 'step-a'),
        nodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should reject connection TO a webhook-trigger with entry kind (target is entry)', () => {
      // Webhook trigger is kind=entry but with a recognized ID pattern, so isTargetGenericEntryTrigger = false
      // However, the trigger has a recognized ID, so the generic entry check does NOT reject it.
      // The trigger IS allowed as a target (no rule prevents connecting TO it if it has a known trigger ID).
      const webhookTarget = makeNode({
        id: 'webhook-trigger-99',
        type: 'flowNode',
        data: {
          id: 'webhook-trigger-99',
          label: 'Webhook',
          kind: 'entry',
          toolData: { apiName: 'webhook', method: 'POST' },
        } as any,
      });
      const nodes = [...defaultNodes, webhookTarget];
      const result = validateConnection(
        conn('step-a', 'webhook-trigger-99'),
        nodes,
        [],
      );
      expect(result).toBe(true);
    });
  });

  // ===================== MCP node with toolData (configured - should be allowed) =====================

  describe('configured MCP nodes', () => {
    it('should allow connection from mcp node WITH toolData', () => {
      const configuredMcp = makeNode({
        id: 'mcp-step-1',
        type: 'flowNode',
        data: {
          id: 'mcp-step-1',
          label: 'Configured MCP',
          kind: 'action',
          toolData: { apiName: 'SomeAPI', method: 'GET' },
        } as any,
      });
      const nodes = [...defaultNodes, configuredMcp];
      const result = validateConnection(
        conn('mcp-step-1', 'step-a'),
        nodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow connection to mcp node WITH toolData', () => {
      const configuredMcp = makeNode({
        id: 'mcp-step-1',
        type: 'flowNode',
        data: {
          id: 'mcp-step-1',
          label: 'Configured MCP',
          kind: 'action',
          toolData: { apiName: 'SomeAPI', method: 'GET' },
        } as any,
      });
      const nodes = [...defaultNodes, configuredMcp];
      const result = validateConnection(
        conn('step-a', 'mcp-step-1'),
        nodes,
        [],
      );
      expect(result).toBe(true);
    });
  });

  // ===================== Empty nodes / edges arrays =====================

  describe('empty nodes and edges arrays', () => {
    it('should return true when nodes array is empty (no nodes to validate against)', () => {
      const result = validateConnection(
        conn('step-a', 'step-b'),
        [],
        [],
      );
      expect(result).toBe(true);
    });

    it('should still reject self-connections even with empty nodes', () => {
      const result = validateConnection(
        conn('step-a', 'step-a'),
        [],
        [],
      );
      expect(result).toBe(false);
    });
  });

  // ===================== Multiple validation checks combined =====================

  describe('combined validation scenarios', () => {
    it('should reject self-connection even for a loop node with valid handles', () => {
      const result = validateConnection(
        conn('loop-1', 'loop-1', 'loop-1-source-exit', 'loop-1-target-entry'),
        defaultNodes,
        [],
      );
      expect(result).toBe(false);
    });

    it('should validate that a connection from a configured trigger to a decision works', () => {
      const result = validateConnection(
        conn('webhook-trigger-1', 'decision-1'),
        defaultNodes,
        [],
      );
      expect(result).toBe(true);
    });

    it('should allow connection from a decision else handle with existing if connection', () => {
      const existingEdges: Edge[] = [
        makeEdge({
          id: 'e1',
          source: 'decision-1',
          target: 'step-a',
          sourceHandle: 'decision-1-if',
        }),
      ];
      const result = validateConnection(
        conn('decision-1', 'step-b', 'decision-1-else', null),
        defaultNodes,
        existingEdges,
      );
      expect(result).toBe(true);
    });

    it('should handle connecting through multiple branches (fork -> steps -> merge)', () => {
      const existingEdges: Edge[] = [
        makeEdge({
          id: 'e1',
          source: 'fork-1',
          target: 'step-a',
          sourceHandle: 'fork-1-branch_0',
        }),
      ];
      // Connect fork branch_1 to step-b
      const result1 = validateConnection(
        conn('fork-1', 'step-b', 'fork-1-branch_1', null),
        defaultNodes,
        existingEdges,
      );
      expect(result1).toBe(true);

      // Connect step-a to merge
      const result2 = validateConnection(
        conn('step-a', 'merge-1'),
        defaultNodes,
        existingEdges,
      );
      expect(result2).toBe(true);

      // Connect step-b to merge (second input)
      const existingEdges2 = [
        ...existingEdges,
        makeEdge({ id: 'e2', source: 'step-a', target: 'merge-1' }),
      ];
      const result3 = validateConnection(
        conn('step-b', 'merge-1'),
        defaultNodes,
        existingEdges2,
      );
      expect(result3).toBe(true);
    });
  });

  // ===================== Form trigger as target (entry kind, recognized trigger ID) =====================

  describe('form trigger handling', () => {
    it('should reject connection TO a form-trigger when it has entry kind', () => {
      const formTrigger = makeNode({
        id: 'form-trigger-1',
        type: 'flowNode',
        data: {
          id: 'form-trigger-1',
          label: 'Form',
          kind: 'entry',
        } as any,
      });
      const nodes = [...defaultNodes, formTrigger];
      // form-trigger is a known pattern, so isTargetGenericEntryTrigger = false
      // But kind=entry with no datasource means it will be evaluated differently
      // form-trigger is NOT in the "isTargetGenericEntryTrigger" block because the check
      // excludes form-trigger patterns. So it should not be rejected by that check.
      // However it IS generic since no toolData or dataSourceData.
      // Let's check: isTargetApiNode = false, isTargetMcpGeneric = false (id does not start with mcp- or api-)
      // isTargetGenericTable = false, isTargetTriggerGeneric = false (starts with "form-trigger", not "triggers")
      // isTargetGenericEntryTrigger: kind=entry AND id is excluded (form-trigger-*)
      // So: isTargetGenericEntryTrigger = false
      // Thus it should be allowed as a target
      const result = validateConnection(
        conn('step-a', 'form-trigger-1'),
        nodes,
        [],
      );
      expect(result).toBe(true);
    });
  });
});

// ---------------------------------------------------------------------------
// isWiredOutputPort - drives the "one port = one node" connect-end hint
// ---------------------------------------------------------------------------

describe('isWiredOutputPort', () => {
  it('returns true when a branching node port already has an outgoing edge', () => {
    const edges: Edge[] = [
      makeEdge({ id: 'e1', source: 'decision-1', target: 'step-a', sourceHandle: 'decision-1-if' }),
    ];
    expect(isWiredOutputPort('decision-1', 'decision-1-if', defaultNodes, edges)).toBe(true);
  });

  it('returns false for a different, still-unwired port on the same node', () => {
    const edges: Edge[] = [
      makeEdge({ id: 'e1', source: 'decision-1', target: 'step-a', sourceHandle: 'decision-1-if' }),
    ];
    expect(isWiredOutputPort('decision-1', 'decision-1-else', defaultNodes, edges)).toBe(false);
  });

  it('returns true for an already-wired fork branch handle', () => {
    const edges: Edge[] = [
      makeEdge({ id: 'e1', source: 'fork-1', target: 'step-a', sourceHandle: 'fork-1-branch_0' }),
    ];
    expect(isWiredOutputPort('fork-1', 'fork-1-branch_0', defaultNodes, edges)).toBe(true);
  });

  it('returns true for an already-wired while exit handle', () => {
    const edges: Edge[] = [
      makeEdge({ id: 'e1', source: 'loop-1', target: 'step-a', sourceHandle: 'loop-1-source-exit' }),
    ];
    expect(isWiredOutputPort('loop-1', 'loop-1-source-exit', defaultNodes, edges)).toBe(true);
  });

  it('returns false for a port-less node (trigger) even when it already has an edge - implicit fork is allowed', () => {
    const edges: Edge[] = [
      makeEdge({ id: 'e1', source: 'webhook-trigger-1', target: 'step-a', sourceHandle: 'source-right' }),
    ];
    expect(isWiredOutputPort('webhook-trigger-1', 'source-right', defaultNodes, edges)).toBe(false);
  });

  it('returns false for a port-less plain step even when it already has an edge', () => {
    const edges: Edge[] = [
      makeEdge({ id: 'e1', source: 'step-a', target: 'step-b', sourceHandle: 'source-right' }),
    ];
    expect(isWiredOutputPort('step-a', 'source-right', defaultNodes, edges)).toBe(false);
  });

  it('returns false when the port has no outgoing edge yet (first connection)', () => {
    expect(isWiredOutputPort('decision-1', 'decision-1-if', defaultNodes, [])).toBe(false);
  });

  it('returns false when nodeId or sourceHandle is null/undefined', () => {
    expect(isWiredOutputPort(null, 'decision-1-if', defaultNodes, [])).toBe(false);
    expect(isWiredOutputPort('decision-1', null, defaultNodes, [])).toBe(false);
    expect(isWiredOutputPort(undefined, undefined, defaultNodes, [])).toBe(false);
  });

  it('returns false when the node is not present in the nodes array', () => {
    const edges: Edge[] = [
      makeEdge({ id: 'e1', source: 'ghost-1', target: 'step-a', sourceHandle: 'ghost-1-if' }),
    ];
    expect(isWiredOutputPort('ghost-1', 'ghost-1-if', defaultNodes, edges)).toBe(false);
  });
});

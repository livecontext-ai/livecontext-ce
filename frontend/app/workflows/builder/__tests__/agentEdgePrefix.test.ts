/**
 * Tests for edge prefix generation across ALL node types.
 *
 * Every node type must generate the correct prefix in edges:
 *   trigger: → triggers (manual, webhook, chat, schedule, form)
 *   mcp:    → tool calls, API operations
 *   agent:  → agent, guardrail, classify
 *   table:  → CRUD operations (read, insert, update, delete)
 *   core:   → control flow (transform, decision, code, wait, http_request, etc.)
 *   interface: → user interfaces
 *
 * Bug fixed: edgeProcessor used isAgentNode() (only pure agents) instead of
 * isAiReasoningNode() → guardrail/classify got mcp: prefix → backend couldn't
 * find the target node → skip propagation broken.
 */

import { describe, it, expect } from 'vitest';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { processEdgesV2 } from '../utils/edgeProcessor';
import { createPlanGeneratorContext } from '../utils/planGeneratorContext';
import { normalizeLabel } from '../utils/labelNormalizer';

// ============================================================================
// Node Factories
// ============================================================================

function makeTriggerNode(label: string, id?: string): Node<BuilderNodeData> {
  return {
    id: id || 'trigger-start',
    type: 'flowNode',
    position: { x: 0, y: 0 },
    data: { id: 'manual-trigger', label, kind: 'entry' } as BuilderNodeData,
  };
}

function makeAgentNode(label: string, id?: string): Node<BuilderNodeData> {
  const nodeId = id || `agent-${label.toLowerCase().replace(/\s/g, '-')}`;
  return {
    id: nodeId,
    type: 'flowNode',
    position: { x: 200, y: 0 },
    data: { id: nodeId, label, kind: 'reasoning' } as BuilderNodeData,
  };
}

function makeGuardrailNode(label: string, id?: string): Node<BuilderNodeData> {
  const nodeId = id || `guardrail-${label.toLowerCase().replace(/\s/g, '-')}`;
  return {
    id: nodeId,
    type: 'guardrailNode',
    position: { x: 400, y: 0 },
    data: { id: nodeId, label, kind: 'guardrail' } as BuilderNodeData,
  };
}

function makeClassifyNode(label: string, id?: string): Node<BuilderNodeData> {
  const nodeId = id || `classify-${label.toLowerCase().replace(/\s/g, '-')}`;
  return {
    id: nodeId,
    type: 'classifyNode',
    position: { x: 600, y: 0 },
    data: { id: nodeId, label, kind: 'classify' } as BuilderNodeData,
  };
}

function makeStepNode(label: string, id?: string): Node<BuilderNodeData> {
  const nodeId = id || `step-${label.toLowerCase().replace(/\s/g, '-')}`;
  return {
    id: nodeId,
    type: 'flowNode',
    position: { x: 800, y: 0 },
    data: {
      id: nodeId, label, kind: 'action',
      toolData: { toolId: 'test-tool', apiName: 'TestAPI', method: 'GET' },
    } as BuilderNodeData,
  };
}

function makeCrudNode(label: string, crudOp: string, id?: string): Node<BuilderNodeData> {
  const nodeId = id || `crud-${label.toLowerCase().replace(/\s/g, '-')}`;
  return {
    id: nodeId,
    type: 'flowNode',
    position: { x: 800, y: 0 },
    data: {
      id: nodeId, label, kind: 'action',
      dataSourceData: { crudOperation: crudOp, dataSourceId: '3' },
    } as unknown as BuilderNodeData,
  };
}

function makeCoreNode(label: string, coreType: string, id?: string): Node<BuilderNodeData> {
  const nodeId = id || `core-${label.toLowerCase().replace(/\s/g, '-')}`;
  return {
    id: nodeId,
    type: 'flowNode',
    position: { x: 800, y: 0 },
    data: { id: coreType, label, kind: 'transform' } as BuilderNodeData,
  };
}

function makeInterfaceNode(label: string, id?: string): Node<BuilderNodeData> {
  const nodeId = id || `interface-${label.toLowerCase().replace(/\s/g, '-')}`;
  return {
    id: nodeId,
    type: 'flowNode',
    position: { x: 800, y: 0 },
    data: { id: `interface-${label.toLowerCase().replace(/\s/g, '-')}`, label, kind: 'interface' } as BuilderNodeData,
  };
}

function makeEdge(source: string, target: string, opts?: {
  sourceHandle?: string; targetHandle?: string;
}): Edge {
  return {
    id: `e-${source}-${target}`,
    source,
    target,
    sourceHandle: opts?.sourceHandle,
    targetHandle: opts?.targetHandle,
  };
}

// ============================================================================
// Helpers
// ============================================================================

function findEdge(edges: Array<{ from: string; to: string }>, from: string, to: string) {
  return edges.find((e) => e.from === from && e.to === to);
}

function findEdgeByTo(edges: Array<{ from: string; to: string }>, to: string) {
  return edges.find((e) => e.to === to);
}

type RegMap = 'trigger' | 'step' | 'interface';

function processWithContext(
  nodes: Node<BuilderNodeData>[],
  edges: Edge[],
  registrations: Array<{ node: Node<BuilderNodeData>; label: string; map: RegMap }>
) {
  const ctx = createPlanGeneratorContext(nodes, edges);
  for (const reg of registrations) {
    const normalized = normalizeLabel(reg.label);
    if (reg.map === 'trigger') {
      ctx.triggerSlugMap.set(reg.node.id, normalized);
    } else if (reg.map === 'interface') {
      ctx.interfaceNodeIdMap.set(reg.node.id, { realId: reg.node.id, label: reg.label });
    } else {
      ctx.stepLabelMap.set(reg.node.id, normalized);
    }
  }
  processEdgesV2(ctx);
  return ctx.plan.edges;
}

// ============================================================================
// 1. trigger: prefix
// ============================================================================

describe('edgeProcessor - trigger: prefix', () => {
  it('trigger FROM uses trigger: prefix', () => {
    const trigger = makeTriggerNode('Start');
    const step = makeStepNode('Fetch Data');

    const planEdges = processWithContext(
      [trigger, step],
      [makeEdge(trigger.id, step.id)],
      [
        { node: trigger, label: 'Start', map: 'trigger' },
        { node: step, label: 'Fetch Data', map: 'step' },
      ]
    );

    const edge = findEdge(planEdges, 'trigger:start', 'mcp:fetch_data');
    expect(edge).toBeDefined();
  });
});

// ============================================================================
// 2. mcp: prefix
// ============================================================================

describe('edgeProcessor - mcp: prefix for tool/API step nodes', () => {
  it('edge TO a regular mcp step uses mcp: prefix', () => {
    const trigger = makeTriggerNode('Start');
    const step = makeStepNode('Fetch Data');

    const planEdges = processWithContext(
      [trigger, step],
      [makeEdge(trigger.id, step.id)],
      [
        { node: trigger, label: 'Start', map: 'trigger' },
        { node: step, label: 'Fetch Data', map: 'step' },
      ]
    );

    expect(findEdgeByTo(planEdges, 'mcp:fetch_data')).toBeDefined();
    expect(findEdgeByTo(planEdges, 'agent:fetch_data')).toBeUndefined();
  });

  it('edge between two mcp steps both use mcp: prefix', () => {
    const step1 = makeStepNode('Fetch Data', 'step-fetch');
    const step2 = makeStepNode('Process Data', 'step-process');

    const planEdges = processWithContext(
      [step1, step2],
      [makeEdge(step1.id, step2.id)],
      [
        { node: step1, label: 'Fetch Data', map: 'step' },
        { node: step2, label: 'Process Data', map: 'step' },
      ]
    );

    expect(findEdge(planEdges, 'mcp:fetch_data', 'mcp:process_data')).toBeDefined();
  });
});

// ============================================================================
// 3. agent: prefix (agent, guardrail, classify)
// ============================================================================

describe('edgeProcessor - agent: prefix for all AI reasoning node types', () => {
  it('edge TO a pure agent node uses agent: prefix', () => {
    const trigger = makeTriggerNode('Start');
    const agent = makeAgentNode('AI Agent');

    const planEdges = processWithContext(
      [trigger, agent],
      [makeEdge(trigger.id, agent.id)],
      [
        { node: trigger, label: 'Start', map: 'trigger' },
        { node: agent, label: 'AI Agent', map: 'step' },
      ]
    );

    expect(findEdgeByTo(planEdges, 'agent:ai_agent')).toBeDefined();
    expect(findEdgeByTo(planEdges, 'mcp:ai_agent')).toBeUndefined();
  });

  it('edge TO a guardrail node uses agent: prefix (not mcp:)', () => {
    const agent = makeAgentNode('AI Agent');
    const guardrail = makeGuardrailNode('Content Guard');

    const planEdges = processWithContext(
      [agent, guardrail],
      [makeEdge(agent.id, guardrail.id)],
      [
        { node: agent, label: 'AI Agent', map: 'step' },
        { node: guardrail, label: 'Content Guard', map: 'step' },
      ]
    );

    expect(findEdgeByTo(planEdges, 'agent:content_guard')).toBeDefined();
    expect(findEdgeByTo(planEdges, 'mcp:content_guard')).toBeUndefined();
  });

  it('edge TO a classify node uses agent: prefix (not mcp:)', () => {
    const agent = makeAgentNode('AI Agent');
    const classify = makeClassifyNode('Classify Content');

    const planEdges = processWithContext(
      [agent, classify],
      [makeEdge(agent.id, classify.id)],
      [
        { node: agent, label: 'AI Agent', map: 'step' },
        { node: classify, label: 'Classify Content', map: 'step' },
      ]
    );

    expect(findEdgeByTo(planEdges, 'agent:classify_content')).toBeDefined();
    expect(findEdgeByTo(planEdges, 'mcp:classify_content')).toBeUndefined();
  });

  it('agent → guardrail → classify chain: all edges use agent: prefix', () => {
    const agent = makeAgentNode('AI Agent');
    const guardrail = makeGuardrailNode('Content Guard');
    const classify = makeClassifyNode('Classify Content');

    const planEdges = processWithContext(
      [agent, guardrail, classify],
      [makeEdge(agent.id, guardrail.id), makeEdge(guardrail.id, classify.id)],
      [
        { node: agent, label: 'AI Agent', map: 'step' },
        { node: guardrail, label: 'Content Guard', map: 'step' },
        { node: classify, label: 'Classify Content', map: 'step' },
      ]
    );

    expect(findEdge(planEdges, 'agent:ai_agent', 'agent:content_guard')).toBeDefined();
    expect(findEdge(planEdges, 'agent:content_guard', 'agent:classify_content')).toBeDefined();
    expect(findEdge(planEdges, 'agent:ai_agent', 'mcp:content_guard')).toBeUndefined();
    expect(findEdge(planEdges, 'agent:content_guard', 'mcp:classify_content')).toBeUndefined();
  });
});

// ============================================================================
// 4. table: prefix (CRUD)
// ============================================================================

describe('edgeProcessor - table: prefix for CRUD nodes', () => {
  it('edge TO a crud-read-row node uses table: prefix', () => {
    const trigger = makeTriggerNode('Start');
    const crud = makeCrudNode('Read Rows', 'crud-read-row');

    const planEdges = processWithContext(
      [trigger, crud],
      [makeEdge(trigger.id, crud.id)],
      [
        { node: trigger, label: 'Start', map: 'trigger' },
        { node: crud, label: 'Read Rows', map: 'step' },
      ]
    );

    expect(findEdgeByTo(planEdges, 'table:read_rows')).toBeDefined();
    expect(findEdgeByTo(planEdges, 'mcp:read_rows')).toBeUndefined();
  });

  it('crud → crud chain uses table: prefix on both ends', () => {
    const crud1 = makeCrudNode('Read Rows', 'crud-read-row', 'crud-read');
    const crud2 = makeCrudNode('Insert Row', 'crud-create-row', 'crud-insert');

    const planEdges = processWithContext(
      [crud1, crud2],
      [makeEdge(crud1.id, crud2.id)],
      [
        { node: crud1, label: 'Read Rows', map: 'step' },
        { node: crud2, label: 'Insert Row', map: 'step' },
      ]
    );

    expect(findEdge(planEdges, 'table:read_rows', 'table:insert_row')).toBeDefined();
  });
});

// ============================================================================
// 5. core: prefix (control flow nodes)
// ============================================================================

describe('edgeProcessor - core: prefix for control flow nodes', () => {
  it('edge TO a transform node uses core: prefix', () => {
    const trigger = makeTriggerNode('Start');
    const transform = makeCoreNode('Transform Data', 'transform');

    const planEdges = processWithContext(
      [trigger, transform],
      [makeEdge(trigger.id, transform.id)],
      [
        { node: trigger, label: 'Start', map: 'trigger' },
      ]
    );

    expect(findEdgeByTo(planEdges, 'core:transform_data')).toBeDefined();
    expect(findEdgeByTo(planEdges, 'mcp:transform_data')).toBeUndefined();
  });

  it('edge TO a code node uses core: prefix', () => {
    const trigger = makeTriggerNode('Start');
    const code = makeCoreNode('Run Code', 'code');

    const planEdges = processWithContext(
      [trigger, code],
      [makeEdge(trigger.id, code.id)],
      [
        { node: trigger, label: 'Start', map: 'trigger' },
      ]
    );

    expect(findEdgeByTo(planEdges, 'core:run_code')).toBeDefined();
    expect(findEdgeByTo(planEdges, 'mcp:run_code')).toBeUndefined();
  });

  it('edge TO a wait node uses core: prefix', () => {
    const trigger = makeTriggerNode('Start');
    const wait: Node<BuilderNodeData> = {
      id: 'wait-node',
      type: 'flowNode',
      position: { x: 0, y: 0 },
      data: { id: 'wait', label: 'Wait 5s', kind: 'wait' } as BuilderNodeData,
    };

    const planEdges = processWithContext(
      [trigger, wait],
      [makeEdge(trigger.id, wait.id)],
      [
        { node: trigger, label: 'Start', map: 'trigger' },
      ]
    );

    expect(findEdgeByTo(planEdges, 'core:wait_5s')).toBeDefined();
    expect(findEdgeByTo(planEdges, 'mcp:wait_5s')).toBeUndefined();
  });

  it('edge TO an http_request node uses core: prefix', () => {
    const trigger = makeTriggerNode('Start');
    const http: Node<BuilderNodeData> = {
      id: 'http-node',
      type: 'flowNode',
      position: { x: 0, y: 0 },
      data: { id: 'http-request', label: 'Test API', kind: 'http_request' } as BuilderNodeData,
    };

    const planEdges = processWithContext(
      [trigger, http],
      [makeEdge(trigger.id, http.id)],
      [
        { node: trigger, label: 'Start', map: 'trigger' },
      ]
    );

    expect(findEdgeByTo(planEdges, 'core:test_api')).toBeDefined();
    expect(findEdgeByTo(planEdges, 'mcp:test_api')).toBeUndefined();
  });

  it('core → core chain uses core: prefix on both ends', () => {
    const transform = makeCoreNode('Transform Data', 'transform', 'core-transform');
    const code = makeCoreNode('Run Code', 'code', 'core-code');

    const planEdges = processWithContext(
      [transform, code],
      [makeEdge(transform.id, code.id)],
      []
    );

    expect(findEdge(planEdges, 'core:transform_data', 'core:run_code')).toBeDefined();
  });
});

// ============================================================================
// 6. interface: prefix
// ============================================================================

describe('edgeProcessor - interface: prefix for interface nodes', () => {
  it('edge TO an interface node uses interface: prefix', () => {
    const step = makeStepNode('Fetch Data');
    const iface = makeInterfaceNode('Dashboard');

    const planEdges = processWithContext(
      [step, iface],
      [makeEdge(step.id, iface.id)],
      [
        { node: step, label: 'Fetch Data', map: 'step' },
        { node: iface, label: 'Dashboard', map: 'interface' },
      ]
    );

    expect(findEdgeByTo(planEdges, 'interface:dashboard')).toBeDefined();
    expect(findEdgeByTo(planEdges, 'mcp:dashboard')).toBeUndefined();
  });

  it('agent → interface uses correct prefixes', () => {
    const agent = makeAgentNode('AI Agent');
    const iface = makeInterfaceNode('Results Page');

    const planEdges = processWithContext(
      [agent, iface],
      [makeEdge(agent.id, iface.id)],
      [
        { node: agent, label: 'AI Agent', map: 'step' },
        { node: iface, label: 'Results Page', map: 'interface' },
      ]
    );

    expect(findEdge(planEdges, 'agent:ai_agent', 'interface:results_page')).toBeDefined();
  });
});

// ============================================================================
// 7. Cross-type edges - every node type connects to every other
// ============================================================================

describe('edgeProcessor - cross-type prefix correctness', () => {
  it('mcp → guardrail uses agent: on target', () => {
    const step = makeStepNode('Fetch Data');
    const guardrail = makeGuardrailNode('Validate Output');

    const planEdges = processWithContext(
      [step, guardrail],
      [makeEdge(step.id, guardrail.id)],
      [
        { node: step, label: 'Fetch Data', map: 'step' },
        { node: guardrail, label: 'Validate Output', map: 'step' },
      ]
    );

    expect(findEdge(planEdges, 'mcp:fetch_data', 'agent:validate_output')).toBeDefined();
    expect(findEdge(planEdges, 'mcp:fetch_data', 'mcp:validate_output')).toBeUndefined();
  });

  it('table → classify uses agent: on target', () => {
    const crud = makeCrudNode('Read Rows', 'crud-read-row');
    const classify = makeClassifyNode('Route Content');

    const planEdges = processWithContext(
      [crud, classify],
      [makeEdge(crud.id, classify.id)],
      [
        { node: crud, label: 'Read Rows', map: 'step' },
        { node: classify, label: 'Route Content', map: 'step' },
      ]
    );

    expect(findEdge(planEdges, 'table:read_rows', 'agent:route_content')).toBeDefined();
    expect(findEdge(planEdges, 'table:read_rows', 'mcp:route_content')).toBeUndefined();
  });

  it('core → agent uses agent: on target', () => {
    const core = makeCoreNode('Transform Data', 'transform');
    const agent = makeAgentNode('AI Agent');

    const planEdges = processWithContext(
      [core, agent],
      [makeEdge(core.id, agent.id)],
      [
        { node: agent, label: 'AI Agent', map: 'step' },
      ]
    );

    expect(findEdge(planEdges, 'core:transform_data', 'agent:ai_agent')).toBeDefined();
  });

  it('core → guardrail uses agent: on target', () => {
    const core = makeCoreNode('Transform Data', 'transform');
    const guardrail = makeGuardrailNode('Check Output');

    const planEdges = processWithContext(
      [core, guardrail],
      [makeEdge(core.id, guardrail.id)],
      [
        { node: guardrail, label: 'Check Output', map: 'step' },
      ]
    );

    expect(findEdge(planEdges, 'core:transform_data', 'agent:check_output')).toBeDefined();
    expect(findEdge(planEdges, 'core:transform_data', 'mcp:check_output')).toBeUndefined();
  });

  it('table → interface uses interface: on target', () => {
    const crud = makeCrudNode('Read Rows', 'crud-read-row');
    const iface = makeInterfaceNode('Data View');

    const planEdges = processWithContext(
      [crud, iface],
      [makeEdge(crud.id, iface.id)],
      [
        { node: crud, label: 'Read Rows', map: 'step' },
        { node: iface, label: 'Data View', map: 'interface' },
      ]
    );

    expect(findEdge(planEdges, 'table:read_rows', 'interface:data_view')).toBeDefined();
  });
});

// ============================================================================
// 8. Full chain - all 6 prefix types in one workflow
// ============================================================================

describe('edgeProcessor - full chain with ALL node type prefixes', () => {
  it('trigger → mcp → core → table → agent → guardrail → classify → interface', () => {
    const trigger = makeTriggerNode('Start');
    const step = makeStepNode('Fetch Data');
    const core = makeCoreNode('Transform Data', 'transform');
    const crud = makeCrudNode('Read Rows', 'crud-read-row');
    const agent = makeAgentNode('AI Agent');
    const guardrail = makeGuardrailNode('Content Guard');
    const classify = makeClassifyNode('Classify Content');
    const iface = makeInterfaceNode('Dashboard');

    const planEdges = processWithContext(
      [trigger, step, core, crud, agent, guardrail, classify, iface],
      [
        makeEdge(trigger.id, step.id),
        makeEdge(step.id, core.id),
        makeEdge(core.id, crud.id),
        makeEdge(crud.id, agent.id),
        makeEdge(agent.id, guardrail.id),
        makeEdge(guardrail.id, classify.id),
        makeEdge(classify.id, iface.id),
      ],
      [
        { node: trigger, label: 'Start', map: 'trigger' },
        { node: step, label: 'Fetch Data', map: 'step' },
        { node: crud, label: 'Read Rows', map: 'step' },
        { node: agent, label: 'AI Agent', map: 'step' },
        { node: guardrail, label: 'Content Guard', map: 'step' },
        { node: classify, label: 'Classify Content', map: 'step' },
        { node: iface, label: 'Dashboard', map: 'interface' },
      ]
    );

    // Every edge with correct prefix
    expect(findEdge(planEdges, 'trigger:start', 'mcp:fetch_data')).toBeDefined();
    expect(findEdge(planEdges, 'mcp:fetch_data', 'core:transform_data')).toBeDefined();
    expect(findEdge(planEdges, 'core:transform_data', 'table:read_rows')).toBeDefined();
    expect(findEdge(planEdges, 'table:read_rows', 'agent:ai_agent')).toBeDefined();
    expect(findEdge(planEdges, 'agent:ai_agent', 'agent:content_guard')).toBeDefined();
    expect(findEdge(planEdges, 'agent:content_guard', 'agent:classify_content')).toBeDefined();
    expect(findEdge(planEdges, 'agent:classify_content', 'interface:dashboard')).toBeDefined();

    // No wrong prefixes anywhere
    expect(findEdge(planEdges, 'table:read_rows', 'mcp:ai_agent')).toBeUndefined();
    expect(findEdge(planEdges, 'agent:ai_agent', 'mcp:content_guard')).toBeUndefined();
    expect(findEdge(planEdges, 'agent:content_guard', 'mcp:classify_content')).toBeUndefined();
    expect(findEdge(planEdges, 'agent:classify_content', 'mcp:dashboard')).toBeUndefined();
    expect(findEdge(planEdges, 'mcp:fetch_data', 'mcp:transform_data')).toBeUndefined();
    expect(findEdge(planEdges, 'core:transform_data', 'mcp:read_rows')).toBeUndefined();
  });
});

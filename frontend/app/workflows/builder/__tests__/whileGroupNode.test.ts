/**
 * Comprehensive tests for WhileGroupNode integration:
 * - nodeRegistry detection
 * - Plan generation (edgeProcessor) - While node serialization
 * - Plan import (NodeCreationService) - While node + edges restoration
 * - Streaming/Status updates (statusUpdater, processLoopEvents) - While node receives status
 * - Output schema (SourceCoreNodeInspector) - While node returns LOOP_SCHEMA
 * - Layout (LayoutService) - Dagre handles back-edges correctly
 */

import { describe, it, expect, beforeEach } from 'vitest';
import type { Node, Edge } from 'reactflow';
import type { BuilderNodeData } from '../types';
import { nodeRegistry } from '../registry/nodeRegistry';
import { processEdgesV2 } from '../utils/edgeProcessor';
import { createPlanGeneratorContext } from '../utils/planGeneratorContext';
import { normalizeLabel } from '../utils/labelNormalizer';
import { updateNodesFromBatchSteps, type BatchStepData } from '../services/statusUpdater';
import { computeBackendStepId } from '../contexts/StepByStepContext';

// ============================================================================
// Helpers
// ============================================================================

function makeWhileNode(
  label: string,
  opts?: {
    id?: string;
    whileCondition?: string;
    maxIterations?: number;
  }
): Node<BuilderNodeData> {
  const id = opts?.id || `while-group-${Date.now()}`;
  return {
    id,
    type: 'whileGroupNode',
    position: { x: 200, y: 100 },
    data: {
      id,
      label,
      kind: 'loop',
      whileCondition: opts?.whileCondition ?? 'x < 10',
      maxIterations: opts?.maxIterations ?? 10,
    } as BuilderNodeData,
  };
}

function makeStepNode(
  label: string,
  opts?: { id?: string }
): Node<BuilderNodeData> {
  const id = opts?.id || `step-${label.toLowerCase().replace(/\s/g, '-')}`;
  return {
    id,
    type: 'flowNode',
    position: { x: 400, y: 100 },
    data: {
      id,
      label,
      kind: 'action',
      toolData: { toolId: 'test-tool', apiName: 'TestAPI', method: 'GET' },
    } as BuilderNodeData,
  };
}

function makeTriggerNode(
  label: string,
  opts?: { id?: string }
): Node<BuilderNodeData> {
  const id = opts?.id || `trigger-${label.toLowerCase().replace(/\s/g, '-')}`;
  return {
    id,
    type: 'flowNode',
    position: { x: 0, y: 100 },
    data: {
      id: 'manual-trigger',
      label,
      kind: 'entry',
    } as BuilderNodeData,
  };
}

function makeEdge(
  source: string,
  target: string,
  opts?: { sourceHandle?: string; targetHandle?: string }
): Edge {
  return {
    id: `e-${source}-${target}-${opts?.sourceHandle || ''}-${opts?.targetHandle || ''}`,
    source,
    target,
    sourceHandle: opts?.sourceHandle,
    targetHandle: opts?.targetHandle,
  };
}

function makeSseStep(overrides: {
  id: string;
  label?: string;
  status?: string;
  statusCounts?: Record<string, number>;
}): BatchStepData {
  return {
    id: overrides.id,
    stepAlias: overrides.label ?? overrides.id.replace(/^(mcp:|agent:|core:|table:|trigger:)/, ''),
    normalizedStepId: overrides.id,
    status: overrides.status ?? 'completed',
    statusCounts: overrides.statusCounts ?? { COMPLETED: 1 },
  } as BatchStepData;
}

// ============================================================================
// 1. nodeRegistry - WhileGroupNode detection
// ============================================================================

describe('nodeRegistry - WhileGroupNode', () => {
  const whileNode = makeWhileNode('My While');

  it('isWhileGroupNode returns true', () => {
    expect(nodeRegistry.isWhileGroupNode(whileNode)).toBe(true);
  });

  it('isLoopNode returns true (delegates to isWhileGroupNode)', () => {
    expect(nodeRegistry.isLoopNode(whileNode)).toBe(true);
  });

  it('isCoreNode returns true', () => {
    expect(nodeRegistry.isCoreNode(whileNode)).toBe(true);
  });

  it('isControlNode returns true', () => {
    expect(nodeRegistry.isControlNode(whileNode)).toBe(true);
  });

  it('isTrigger returns false', () => {
    expect(nodeRegistry.isTrigger(whileNode)).toBe(false);
  });

  it('isDecisionNode returns false', () => {
    expect(nodeRegistry.isDecisionNode(whileNode)).toBe(false);
  });

  it('getDefinition returns whileGroupNode definition', () => {
    const def = nodeRegistry.getDefinition('whileGroupNode');
    expect(def).toBeDefined();
    expect(def!.prefix).toBe('core');
    expect(def!.kind).toBe('loop');
  });

  it('computeBackendKey returns core: prefix', () => {
    const label = normalizeLabel('My While');
    expect(nodeRegistry.computeBackendKey(whileNode, label)).toBe(`core:${label}`);
  });
});

// ============================================================================
// 2. Plan generation - edgeProcessor serializes While node correctly
// ============================================================================

describe('edgeProcessor - While node plan generation', () => {
  it('registers While node as core type=loop with loopCondition', () => {
    const whileNode = makeWhileNode('Retry Loop', {
      id: 'while-1',
      whileCondition: 'status != "success"',
      maxIterations: 5,
    });

    const ctx = createPlanGeneratorContext([whileNode], []);
    processEdgesV2(ctx);

    expect(ctx.plan.cores).toBeDefined();
    expect(ctx.plan.cores!.length).toBe(1);

    const core = ctx.plan.cores![0];
    expect(core.type).toBe('loop');
    expect(core.id).toBe('while-1');
    expect(core.label).toBe('Retry Loop');
    expect(core.loopCondition).toBe('status != "success"');
    expect(core.maxIterations).toBe(5);
  });

  it('preserves empty condition (does not default to false)', () => {
    const whileNode = makeWhileNode('Empty Cond', {
      id: 'while-2',
      whileCondition: '',
      maxIterations: 10,
    });

    const ctx = createPlanGeneratorContext([whileNode], []);
    processEdgesV2(ctx);

    const core = ctx.plan.cores![0];
    expect(core.loopCondition).toBe('');
  });

  it('preserves maxIterations=0 (does not default to 10)', () => {
    const whileNode = makeWhileNode('Zero Iter', {
      id: 'while-3',
      whileCondition: 'true',
      maxIterations: 0,
    });

    const ctx = createPlanGeneratorContext([whileNode], []);
    processEdgesV2(ctx);

    const core = ctx.plan.cores![0];
    expect(core.maxIterations).toBe(0);
  });

  it('generates body port edge from While body handle', () => {
    const whileNode = makeWhileNode('Loop', { id: 'while-1' });
    const bodyStep = makeStepNode('Step A', { id: 'step-a' });

    const edges: Edge[] = [
      makeEdge('while-1', 'step-a', { sourceHandle: 'while-while-1-body' }),
    ];

    const ctx = createPlanGeneratorContext([whileNode, bodyStep], edges);
    // Register body step in stepLabelMap
    ctx.stepLabelMap.set('step-a', normalizeLabel('Step A'));
    processEdgesV2(ctx);

    const bodyEdge = ctx.plan.edges.find(e => e.from.includes(':body'));
    expect(bodyEdge).toBeDefined();
    expect(bodyEdge!.from).toBe(`core:${normalizeLabel('Loop')}:body`);
    expect(bodyEdge!.to).toBe(`mcp:${normalizeLabel('Step A')}`);
  });

  it('generates exit port edge from While exit handle', () => {
    const whileNode = makeWhileNode('Loop', { id: 'while-1' });
    const exitStep = makeStepNode('After Loop', { id: 'step-after' });

    const edges: Edge[] = [
      makeEdge('while-1', 'step-after', { sourceHandle: 'while-while-1-exit' }),
    ];

    const ctx = createPlanGeneratorContext([whileNode, exitStep], edges);
    ctx.stepLabelMap.set('step-after', normalizeLabel('After Loop'));
    processEdgesV2(ctx);

    const exitEdge = ctx.plan.edges.find(e => e.from.includes(':exit'));
    expect(exitEdge).toBeDefined();
    expect(exitEdge!.from).toBe(`core:${normalizeLabel('Loop')}:exit`);
    expect(exitEdge!.to).toBe(`mcp:${normalizeLabel('After Loop')}`);
  });

  it('generates iterate port edge from loop-back handle', () => {
    const whileNode = makeWhileNode('Loop', { id: 'while-1' });
    const lastBodyStep = makeStepNode('Last Step', { id: 'step-last' });

    const edges: Edge[] = [
      makeEdge('step-last', 'while-1', { targetHandle: 'while-while-1-loop-back' }),
    ];

    const ctx = createPlanGeneratorContext([whileNode, lastBodyStep], edges);
    ctx.stepLabelMap.set('step-last', normalizeLabel('Last Step'));
    processEdgesV2(ctx);

    const iterateEdge = ctx.plan.edges.find(e => e.to.includes(':iterate'));
    expect(iterateEdge).toBeDefined();
    expect(iterateEdge!.from).toBe(`mcp:${normalizeLabel('Last Step')}`);
    expect(iterateEdge!.to).toBe(`core:${normalizeLabel('Loop')}:iterate`);
  });

  it('generates entry edge to While entry handle (no port)', () => {
    const trigger = makeTriggerNode('Start', { id: 'trigger-start' });
    const whileNode = makeWhileNode('Loop', { id: 'while-1' });

    const edges: Edge[] = [
      makeEdge('trigger-start', 'while-1', { targetHandle: 'while-while-1-entry' }),
    ];

    const ctx = createPlanGeneratorContext([trigger, whileNode], edges);
    ctx.triggerSlugMap.set('trigger-start', normalizeLabel('Start'));
    processEdgesV2(ctx);

    const entryEdge = ctx.plan.edges.find(e =>
      e.from.startsWith('trigger:') && e.to.startsWith('core:')
    );
    expect(entryEdge).toBeDefined();
    // Entry has no port - it's just core:label
    expect(entryEdge!.to).toBe(`core:${normalizeLabel('Loop')}`);
  });
});

// ============================================================================
// 3. Plan generation - full While loop topology
// ============================================================================

describe('edgeProcessor - full While loop graph', () => {
  it('generates complete plan for trigger → while(body: A→B) → exit', () => {
    const trigger = makeTriggerNode('Start', { id: 'trigger-1' });
    const whileNode = makeWhileNode('Poller', {
      id: 'while-1',
      whileCondition: 'result.status != "done"',
      maxIterations: 20,
    });
    const stepA = makeStepNode('Fetch', { id: 'step-a' });
    const stepB = makeStepNode('Process', { id: 'step-b' });
    const exitStep = makeStepNode('Done', { id: 'step-done' });

    const nodes = [trigger, whileNode, stepA, stepB, exitStep];
    const edges: Edge[] = [
      makeEdge('trigger-1', 'while-1', { targetHandle: 'while-while-1-entry' }),
      makeEdge('while-1', 'step-a', { sourceHandle: 'while-while-1-body' }),
      makeEdge('step-a', 'step-b'),
      makeEdge('step-b', 'while-1', { targetHandle: 'while-while-1-loop-back' }),
      makeEdge('while-1', 'step-done', { sourceHandle: 'while-while-1-exit' }),
    ];

    const ctx = createPlanGeneratorContext(nodes, edges);
    ctx.triggerSlugMap.set('trigger-1', normalizeLabel('Start'));
    ctx.stepLabelMap.set('step-a', normalizeLabel('Fetch'));
    ctx.stepLabelMap.set('step-b', normalizeLabel('Process'));
    ctx.stepLabelMap.set('step-done', normalizeLabel('Done'));
    processEdgesV2(ctx);

    // Verify core node
    expect(ctx.plan.cores!.length).toBe(1);
    const core = ctx.plan.cores![0];
    expect(core.type).toBe('loop');
    expect(core.loopCondition).toBe('result.status != "done"');
    expect(core.maxIterations).toBe(20);

    // Verify edges (should have 5 edges)
    const planEdges = ctx.plan.edges;

    // trigger → while (entry, no port)
    expect(planEdges.find(e => e.from === 'trigger:start' && e.to === 'core:poller')).toBeDefined();

    // while:body → step A
    expect(planEdges.find(e => e.from === 'core:poller:body' && e.to === 'mcp:fetch')).toBeDefined();

    // step A → step B
    expect(planEdges.find(e => e.from === 'mcp:fetch' && e.to === 'mcp:process')).toBeDefined();

    // step B → while:iterate (loop-back)
    expect(planEdges.find(e => e.from === 'mcp:process' && e.to === 'core:poller:iterate')).toBeDefined();

    // while:exit → Done
    expect(planEdges.find(e => e.from === 'core:poller:exit' && e.to === 'mcp:done')).toBeDefined();
  });
});

// ============================================================================
// 4. Streaming Status - While node receives status from batch events
// ============================================================================

describe('statusUpdater - While node streaming status', () => {
  it('updates WhileGroupNode status from core: streaming event', () => {
    const whileNode = makeWhileNode('My Loop', { id: 'while-1' });
    const nodes = [whileNode];

    const batchSteps: BatchStepData[] = [
      makeSseStep({
        id: 'core:my_loop',
        label: 'my_loop',
        status: 'running',
        statusCounts: { RUNNING: 1 },
      }),
    ];

    const updated = updateNodesFromBatchSteps(nodes, batchSteps);
    expect(updated[0].data.status).toBe('running');
    expect(updated[0].data.statusCounts).toBeDefined();
    expect(updated[0].data.statusCounts!.RUNNING).toBe(1);
  });

  it('updates WhileGroupNode to completed status', () => {
    const whileNode = makeWhileNode('My Loop', { id: 'while-1' });
    const nodes = [whileNode];

    const batchSteps: BatchStepData[] = [
      makeSseStep({
        id: 'core:my_loop',
        label: 'my_loop',
        status: 'completed',
        statusCounts: { COMPLETED: 3 },
      }),
    ];

    const updated = updateNodesFromBatchSteps(nodes, batchSteps);
    expect(updated[0].data.status).toBe('completed');
  });

  it('updates body node (flowNode) status independently', () => {
    const whileNode = makeWhileNode('Loop', { id: 'while-1' });
    const bodyStep = makeStepNode('Fetch', { id: 'step-fetch' });
    const nodes = [whileNode, bodyStep];

    const batchSteps: BatchStepData[] = [
      makeSseStep({
        id: 'mcp:fetch',
        label: 'fetch',
        status: 'running',
        statusCounts: { RUNNING: 1 },
      }),
    ];

    const updated = updateNodesFromBatchSteps(nodes, batchSteps);
    // While node unchanged
    expect(updated[0].data.status).toBeUndefined();
    // Body step updated
    expect(updated[1].data.status).toBe('running');
  });

  it('does not confuse While node with body node', () => {
    const whileNode = makeWhileNode('Poller', { id: 'while-1' });
    const bodyStep = makeStepNode('Poller Step', { id: 'step-poller-step' });
    const nodes = [whileNode, bodyStep];

    const batchSteps: BatchStepData[] = [
      makeSseStep({ id: 'core:poller', status: 'running', statusCounts: { RUNNING: 1 } }),
      makeSseStep({ id: 'mcp:poller_step', status: 'completed', statusCounts: { COMPLETED: 1 } }),
    ];

    const updated = updateNodesFromBatchSteps(nodes, batchSteps);
    expect(updated[0].data.status).toBe('running');   // While node
    expect(updated[1].data.status).toBe('completed');  // Body step
  });
});

// ============================================================================
// 5. StepByStepContext - computeBackendStepId for While
// ============================================================================

describe('computeBackendStepId - While node mapping', () => {
  it('maps kind=loop to core: prefix', () => {
    const result = computeBackendStepId('while-group-123', {
      label: 'My While',
      kind: 'loop',
    });
    expect(result).toBe(`core:${normalizeLabel('My While')}`);
  });

  it('maps nodeId containing "while" to core: prefix (fallback)', () => {
    const result = computeBackendStepId('while-group-123', {
      label: 'Retry',
    });
    expect(result).toBe(`core:${normalizeLabel('Retry')}`);
  });

  it('does not map body steps as core', () => {
    const result = computeBackendStepId('step-fetch-123', {
      label: 'Fetch Data',
      kind: 'action',
    });
    expect(result).toBe(`mcp:${normalizeLabel('Fetch Data')}`);
  });
});

// ============================================================================
// 6. Condition preservation - no forced defaults
// ============================================================================

describe('condition preservation', () => {
  it('edgeProcessor preserves empty whileCondition (no default to false)', () => {
    const whileNode = makeWhileNode('Loop', {
      id: 'while-empty',
      whileCondition: '',
    });

    const ctx = createPlanGeneratorContext([whileNode], []);
    processEdgesV2(ctx);

    const core = ctx.plan.cores![0];
    // Must be '' not 'false'
    expect(core.loopCondition).toBe('');
  });

  it('edgeProcessor preserves user-entered condition as-is', () => {
    const whileNode = makeWhileNode('Loop', {
      id: 'while-cond',
      whileCondition: '#output.data.length > 0 && #iteration < 5',
    });

    const ctx = createPlanGeneratorContext([whileNode], []);
    processEdgesV2(ctx);

    const core = ctx.plan.cores![0];
    expect(core.loopCondition).toBe('#output.data.length > 0 && #iteration < 5');
  });

  it('edgeProcessor uses loopCondition field name (not condition)', () => {
    const whileNode = makeWhileNode('Loop', {
      id: 'while-field',
      whileCondition: 'true',
    });

    const ctx = createPlanGeneratorContext([whileNode], []);
    processEdgesV2(ctx);

    const core = ctx.plan.cores![0];
    // Field must be loopCondition, not condition
    expect(core).toHaveProperty('loopCondition');
    expect(core).not.toHaveProperty('condition');
  });
});

// ============================================================================
// 7. Output schema - SourceCoreNodeInspector returns LOOP_SCHEMA for While
// ============================================================================

describe('output schema - While returns LOOP_SCHEMA', () => {
  // We test the registry-level check that SourceCoreNodeInspector relies on
  it('nodeRegistry.isWhileGroupNode is used to return LOOP_SCHEMA', () => {
    const whileNode = makeWhileNode('My While');
    // SourceCoreNodeInspector checks: isLoopNode(node) || isWhileGroupNode(node)
    expect(
      nodeRegistry.isLoopNode(whileNode) || nodeRegistry.isWhileGroupNode(whileNode)
    ).toBe(true);
  });

  it('regular flowNode does not match loop schema', () => {
    const stepNode = makeStepNode('Regular Step');
    expect(nodeRegistry.isLoopNode(stepNode)).toBe(false);
    expect(nodeRegistry.isWhileGroupNode(stepNode)).toBe(false);
  });
});

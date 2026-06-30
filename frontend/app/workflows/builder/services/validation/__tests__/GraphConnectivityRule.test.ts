import { describe, it, expect, beforeEach } from 'vitest';
import { GraphConnectivityRule } from '../rules-v2/GraphConnectivityRule';
import {
  makeTriggerNode,
  makeStepNode,
  makeAgentNode,
  makeDecisionNode,
  makeSplitNode,
  makeEdge,
  buildContext,
  resetNodeCounter,
  resetEdgeCounter,
} from './test-helpers';

describe('GraphConnectivityRule', () => {
  let rule: GraphConnectivityRule;

  beforeEach(() => {
    rule = new GraphConnectivityRule();
    resetNodeCounter();
    resetEdgeCounter();
  });

  it('should have correct metadata', () => {
    expect(rule.ruleName).toBe('GraphConnectivity');
    expect(rule.isCritical).toBe(true);
    expect(rule.priority).toBe(20);
  });

  // ====================================================================
  // Skip when no edges
  // ====================================================================

  it('should skip connectivity checks when there are no edges', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const step = makeStepNode('Step 1', { id: 'step-1' });
    const ctx = buildContext([trigger, step], []);
    const result = rule.validate(ctx);

    // No connectivity issues should be reported when there are no edges
    expect(result.issues).toHaveLength(0);
  });

  // ====================================================================
  // All nodes reachable
  // ====================================================================

  it('should report no issues when all nodes are reachable from trigger', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const s1 = makeStepNode('Step 1', { id: 'step-1' });
    const s2 = makeStepNode('Step 2', { id: 'step-2' });
    const edges = [
      makeEdge(trigger.id, s1.id),
      makeEdge(s1.id, s2.id),
    ];
    const ctx = buildContext([trigger, s1, s2], edges);
    const result = rule.validate(ctx);

    expect(result.issues).toHaveLength(0);
  });

  it('should report no issues for a fully connected diamond graph', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const s1 = makeStepNode('Left', { id: 'left' });
    const s2 = makeStepNode('Right', { id: 'right' });
    const s3 = makeStepNode('Merge', { id: 'merge' });
    const edges = [
      makeEdge(trigger.id, s1.id),
      makeEdge(trigger.id, s2.id),
      makeEdge(s1.id, s3.id),
      makeEdge(s2.id, s3.id),
    ];
    const ctx = buildContext([trigger, s1, s2, s3], edges);
    const result = rule.validate(ctx);

    expect(result.issues).toHaveLength(0);
  });

  // ====================================================================
  // Orphan steps (not reachable from triggers)
  // ====================================================================

  it('should warn about orphan step not reachable from any trigger', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const s1 = makeStepNode('Connected Step', { id: 'connected' });
    const s2 = makeStepNode('Orphan Step', { id: 'orphan' });
    const edges = [
      makeEdge(trigger.id, s1.id),
      // orphan has no incoming edge from trigger path
      makeEdge(s2.id, s1.id), // Orphan only has outgoing to connected
    ];
    const ctx = buildContext([trigger, s1, s2], edges);
    const result = rule.validate(ctx);

    const orphanStep = result.issues.find(
      (i) => i.message.includes('not reachable from any trigger') && i.elementType === 'mcp'
    );
    expect(orphanStep).toBeDefined();
    expect(orphanStep!.severity).toBe('warning');
  });

  it('should NOT warn about steps reachable via transitive edges', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const s1 = makeStepNode('Step 1', { id: 'step-1' });
    const s2 = makeStepNode('Step 2', { id: 'step-2' });
    const s3 = makeStepNode('Step 3', { id: 'step-3' });
    const edges = [
      makeEdge(trigger.id, s1.id),
      makeEdge(s1.id, s2.id),
      makeEdge(s2.id, s3.id),
    ];
    const ctx = buildContext([trigger, s1, s2, s3], edges);
    const result = rule.validate(ctx);

    const orphanStep = result.issues.find(
      (i) => i.message.includes('not reachable')
    );
    expect(orphanStep).toBeUndefined();
  });

  it('should detect multiple orphan steps', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const connected = makeStepNode('Connected', { id: 'connected' });
    const orphan1 = makeStepNode('Orphan 1', { id: 'orphan-1' });
    const orphan2 = makeStepNode('Orphan 2', { id: 'orphan-2' });
    const edges = [
      makeEdge(trigger.id, connected.id),
      makeEdge(orphan1.id, orphan2.id), // Orphan chain
    ];
    const ctx = buildContext([trigger, connected, orphan1, orphan2], edges);
    const result = rule.validate(ctx);

    const orphanWarnings = result.issues.filter(
      (i) => i.message.includes('not reachable') && i.elementType === 'mcp'
    );
    expect(orphanWarnings).toHaveLength(2);
  });

  // ====================================================================
  // Orphan control nodes
  // ====================================================================

  it('should warn about orphan control nodes not reachable from triggers', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const step = makeStepNode('Step 1', { id: 'step-1' });
    const orphanDecision = makeDecisionNode(
      'Check',
      [
        { id: 'c1', type: 'if', label: 'IF', expression: 'x > 5' },
        { id: 'c2', type: 'else', label: 'ELSE' },
      ],
      'decision-orphan'
    );
    const edges = [makeEdge(trigger.id, step.id)];
    // decision-orphan has no edges connecting it to the trigger
    const ctx = buildContext([trigger, step, orphanDecision], edges);
    const result = rule.validate(ctx);

    const orphanCore = result.issues.find(
      (i) =>
        i.message.includes('not reachable from any trigger') &&
        i.elementType === 'core'
    );
    expect(orphanCore).toBeDefined();
    expect(orphanCore!.severity).toBe('warning');
  });

  it('should NOT warn about control nodes reachable from triggers', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const decision = makeDecisionNode(
      'Check',
      [
        { id: 'c1', type: 'if', label: 'IF', expression: 'x > 5' },
        { id: 'c2', type: 'else', label: 'ELSE' },
      ],
      'decision-1'
    );
    const edges = [makeEdge(trigger.id, decision.id)];
    const ctx = buildContext([trigger, decision], edges);
    const result = rule.validate(ctx);

    const orphanCore = result.issues.find(
      (i) =>
        i.message.includes('not reachable') && i.elementType === 'core'
    );
    expect(orphanCore).toBeUndefined();
  });

  // ====================================================================
  // Multiple connected components
  // ====================================================================

  it('should detect nodes in a separate connected component', () => {
    const t1 = makeTriggerNode('Trigger A', 'trigger-a');
    const s1 = makeStepNode('Step A', { id: 'step-a' });
    // Second disconnected component (no trigger)
    const s2 = makeStepNode('Step B', { id: 'step-b' });
    const s3 = makeStepNode('Step C', { id: 'step-c' });
    const edges = [
      makeEdge(t1.id, s1.id),
      makeEdge(s2.id, s3.id), // Separate component
    ];
    const ctx = buildContext([t1, s1, s2, s3], edges);
    const result = rule.validate(ctx);

    // s2 and s3 are orphans
    const orphans = result.issues.filter(
      (i) => i.message.includes('not reachable')
    );
    expect(orphans).toHaveLength(2);
  });

  // ====================================================================
  // Reachability through multiple triggers
  // ====================================================================

  it('should consider nodes reachable from ANY trigger as non-orphan', () => {
    const t1 = makeTriggerNode('Trigger A', 'trigger-a');
    const t2 = makeTriggerNode('Trigger B', 'trigger-b');
    const s1 = makeStepNode('Step 1', { id: 'step-1' });
    const s2 = makeStepNode('Step 2', { id: 'step-2' });
    // s1 is reachable from t1, s2 is reachable from t2
    const edges = [
      makeEdge(t1.id, s1.id),
      makeEdge(t2.id, s2.id),
    ];
    const ctx = buildContext([t1, t2, s1, s2], edges);
    const result = rule.validate(ctx);

    const orphanSteps = result.issues.filter(
      (i) => i.message.includes('not reachable') && i.elementType === 'mcp'
    );
    expect(orphanSteps).toHaveLength(0);
  });

  // ====================================================================
  // Edge case: nodes with no trigger at all
  // ====================================================================

  it('should mark all steps as orphan when there is no trigger', () => {
    const s1 = makeStepNode('Step 1', { id: 'step-1' });
    const s2 = makeStepNode('Step 2', { id: 'step-2' });
    const edges = [makeEdge(s1.id, s2.id)];
    const ctx = buildContext([s1, s2], edges);
    const result = rule.validate(ctx);

    // No triggers means no reachability, both should be orphans
    const orphans = result.issues.filter(
      (i) => i.message.includes('not reachable')
    );
    expect(orphans).toHaveLength(2);
  });
});

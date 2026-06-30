import { describe, it, expect, beforeEach } from 'vitest';
import { CycleDetectionRule } from '../rules-v2/CycleDetectionRule';
import {
  makeTriggerNode,
  makeStepNode,
  makeLoopNode,
  makeDecisionNode,
  makeEdge,
  buildContext,
  resetNodeCounter,
  resetEdgeCounter,
} from './test-helpers';

describe('CycleDetectionRule', () => {
  let rule: CycleDetectionRule;

  beforeEach(() => {
    rule = new CycleDetectionRule();
    resetNodeCounter();
    resetEdgeCounter();
  });

  it('should have correct metadata', () => {
    expect(rule.ruleName).toBe('CycleDetection');
    expect(rule.isCritical).toBe(true);
    expect(rule.priority).toBe(11);
  });

  // ====================================================================
  // Acyclic graphs (no cycles)
  // ====================================================================

  it('should report no cycles for a linear workflow', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const s1 = makeStepNode('Step 1', { id: 'step-1' });
    const s2 = makeStepNode('Step 2', { id: 'step-2' });
    const edges = [
      makeEdge(trigger.id, s1.id),
      makeEdge(s1.id, s2.id),
    ];
    const ctx = buildContext([trigger, s1, s2], edges);
    const result = rule.validate(ctx);

    expect(result.hasErrors).toBe(false);
    expect(result.issues).toHaveLength(0);
  });

  it('should report no cycles for a diamond graph (fork/merge without cycle)', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const s1 = makeStepNode('Step 1', { id: 'step-1' });
    const s2 = makeStepNode('Step 2', { id: 'step-2' });
    const s3 = makeStepNode('Step 3', { id: 'step-3' });
    const edges = [
      makeEdge(trigger.id, s1.id),
      makeEdge(trigger.id, s2.id),
      makeEdge(s1.id, s3.id),
      makeEdge(s2.id, s3.id),
    ];
    const ctx = buildContext([trigger, s1, s2, s3], edges);
    const result = rule.validate(ctx);

    expect(result.hasErrors).toBe(false);
  });

  it('should report no cycles for empty workflow', () => {
    const ctx = buildContext([], []);
    const result = rule.validate(ctx);

    expect(result.hasErrors).toBe(false);
    expect(result.issues).toHaveLength(0);
  });

  it('should report no cycles for single node with no edges', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const ctx = buildContext([trigger], []);
    const result = rule.validate(ctx);

    expect(result.hasErrors).toBe(false);
  });

  it('should report no cycles for a wide tree graph', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const steps = Array.from({ length: 5 }, (_, i) =>
      makeStepNode(`Step ${i + 1}`, { id: `step-${i + 1}` })
    );
    const edges = steps.map((s) => makeEdge(trigger.id, s.id));
    const ctx = buildContext([trigger, ...steps], edges);
    const result = rule.validate(ctx);

    expect(result.hasErrors).toBe(false);
  });

  // ====================================================================
  // Simple 2-node cycle
  // ====================================================================

  it('should detect a simple 2-node cycle (A -> B -> A)', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const s1 = makeStepNode('Step A', { id: 'step-a' });
    const s2 = makeStepNode('Step B', { id: 'step-b' });
    const edges = [
      makeEdge(trigger.id, s1.id),
      makeEdge(s1.id, s2.id),
      makeEdge(s2.id, s1.id), // Back-edge creating cycle
    ];
    const ctx = buildContext([trigger, s1, s2], edges);
    const result = rule.validate(ctx);

    expect(result.hasErrors).toBe(true);
    // Both nodes in the cycle should be reported
    expect(result.issues.length).toBeGreaterThanOrEqual(2);
    const messages = result.issues.map((i) => i.message);
    expect(messages.some((m) => m.includes('cycle'))).toBe(true);
  });

  // ====================================================================
  // 3-node cycle
  // ====================================================================

  it('should detect a 3-node cycle (A -> B -> C -> A)', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const s1 = makeStepNode('Step A', { id: 'step-a' });
    const s2 = makeStepNode('Step B', { id: 'step-b' });
    const s3 = makeStepNode('Step C', { id: 'step-c' });
    const edges = [
      makeEdge(trigger.id, s1.id),
      makeEdge(s1.id, s2.id),
      makeEdge(s2.id, s3.id),
      makeEdge(s3.id, s1.id), // Back-edge creating cycle
    ];
    const ctx = buildContext([trigger, s1, s2, s3], edges);
    const result = rule.validate(ctx);

    expect(result.hasErrors).toBe(true);
    expect(result.issues.length).toBeGreaterThanOrEqual(3);
  });

  // ====================================================================
  // Self-loop on non-loop node
  // ====================================================================

  it('should detect a self-loop on a regular step node', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const s1 = makeStepNode('Step A', { id: 'step-a' });
    const edges = [
      makeEdge(trigger.id, s1.id),
      makeEdge(s1.id, s1.id), // Self-loop
    ];
    const ctx = buildContext([trigger, s1], edges);
    const result = rule.validate(ctx);

    expect(result.hasErrors).toBe(true);
    expect(result.issues.some((i) => i.message.includes('cycle'))).toBe(true);
  });

  // ====================================================================
  // Self-loop on loop node (should be ALLOWED)
  // ====================================================================

  it('should NOT report self-loop on a loop node (intentional iteration)', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const loop = makeLoopNode('My Loop', 'true', 'loop-1');
    const edges = [
      makeEdge(trigger.id, loop.id),
      makeEdge(loop.id, loop.id), // Self-loop on loop node
    ];
    const ctx = buildContext([trigger, loop], edges);
    const result = rule.validate(ctx);

    // Self-loop on loop node should not be reported as a cycle
    const cycleIssues = result.issues.filter(
      (i) => i.severity === 'error' && i.message.includes('cycle')
    );
    expect(cycleIssues).toHaveLength(0);
  });

  // ====================================================================
  // Disconnected cycle (not reachable from trigger)
  // ====================================================================

  it('should detect cycles in disconnected subgraphs', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const s1 = makeStepNode('Step 1', { id: 'step-1' });
    // Disconnected cycle
    const s2 = makeStepNode('Orphan A', { id: 'orphan-a' });
    const s3 = makeStepNode('Orphan B', { id: 'orphan-b' });
    const edges = [
      makeEdge(trigger.id, s1.id),
      makeEdge(s2.id, s3.id),
      makeEdge(s3.id, s2.id), // Cycle in disconnected subgraph
    ];
    const ctx = buildContext([trigger, s1, s2, s3], edges);
    const result = rule.validate(ctx);

    expect(result.hasErrors).toBe(true);
  });

  // ====================================================================
  // Complex multi-path cycle
  // ====================================================================

  it('should detect cycle even when there are multiple paths', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const a = makeStepNode('A', { id: 'a' });
    const b = makeStepNode('B', { id: 'b' });
    const c = makeStepNode('C', { id: 'c' });
    const d = makeStepNode('D', { id: 'd' });
    // A -> B -> C -> D -> B (cycle through B-C-D)
    const edges = [
      makeEdge(trigger.id, a.id),
      makeEdge(a.id, b.id),
      makeEdge(b.id, c.id),
      makeEdge(c.id, d.id),
      makeEdge(d.id, b.id), // Creates the cycle
    ];
    const ctx = buildContext([trigger, a, b, c, d], edges);
    const result = rule.validate(ctx);

    expect(result.hasErrors).toBe(true);
    // At minimum b, c, d should be flagged
    expect(result.issues.length).toBeGreaterThanOrEqual(3);
  });

  // ====================================================================
  // Cycle path information
  // ====================================================================

  it('should include cycle path information in error context', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const s1 = makeStepNode('Alpha', { id: 'alpha' });
    const s2 = makeStepNode('Beta', { id: 'beta' });
    const edges = [
      makeEdge(trigger.id, s1.id),
      makeEdge(s1.id, s2.id),
      makeEdge(s2.id, s1.id),
    ];
    const ctx = buildContext([trigger, s1, s2], edges);
    const result = rule.validate(ctx);

    expect(result.hasErrors).toBe(true);
    const issueWithContext = result.issues.find(
      (i) => i.context?.cyclePath !== undefined
    );
    expect(issueWithContext).toBeDefined();
    expect(Array.isArray(issueWithContext!.context!.cyclePath)).toBe(true);
  });

  it('should include human-readable cycle path labels in error message', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const s1 = makeStepNode('Alpha', { id: 'alpha' });
    const s2 = makeStepNode('Beta', { id: 'beta' });
    const edges = [
      makeEdge(trigger.id, s1.id),
      makeEdge(s1.id, s2.id),
      makeEdge(s2.id, s1.id),
    ];
    const ctx = buildContext([trigger, s1, s2], edges);
    const result = rule.validate(ctx);

    // The message should include readable labels
    const msg = result.issues[0]?.message || '';
    expect(msg).toContain('Alpha');
    expect(msg).toContain('Beta');
  });

  // ====================================================================
  // Long chain without cycle
  // ====================================================================

  it('should handle a long linear chain without false positives', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const steps = Array.from({ length: 20 }, (_, i) =>
      makeStepNode(`Step ${i + 1}`, { id: `step-${i + 1}` })
    );
    const edges = [
      makeEdge(trigger.id, steps[0].id),
      ...steps.slice(0, -1).map((s, i) => makeEdge(s.id, steps[i + 1].id)),
    ];
    const ctx = buildContext([trigger, ...steps], edges);
    const result = rule.validate(ctx);

    expect(result.hasErrors).toBe(false);
  });

  // ====================================================================
  // Decision node with cycle
  // ====================================================================

  it('should detect cycle through a decision node', () => {
    const trigger = makeTriggerNode('Start', 'trigger-1');
    const decision = makeDecisionNode(
      'Check',
      [
        { id: 'c1', type: 'if', label: 'IF', expression: 'x > 5' },
        { id: 'c2', type: 'else', label: 'ELSE' },
      ],
      'decision-1'
    );
    const s1 = makeStepNode('Step 1', { id: 'step-1' });
    const edges = [
      makeEdge(trigger.id, decision.id),
      makeEdge(decision.id, s1.id),
      makeEdge(s1.id, decision.id), // Cycle back to decision
    ];
    const ctx = buildContext([trigger, decision, s1], edges);
    const result = rule.validate(ctx);

    expect(result.hasErrors).toBe(true);
  });
});

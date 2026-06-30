import { describe, it, expect, beforeEach } from 'vitest';
import { GraphStructureRule } from '../rules-v2/GraphStructureRule';
import {
  makeTriggerNode,
  makeStepNode,
  makeLoopNode,
  makeEdge,
  buildContext,
  resetNodeCounter,
  resetEdgeCounter,
} from './test-helpers';

describe('GraphStructureRule', () => {
  let rule: GraphStructureRule;

  beforeEach(() => {
    rule = new GraphStructureRule();
    resetNodeCounter();
    resetEdgeCounter();
  });

  it('should have correct metadata', () => {
    expect(rule.ruleName).toBe('GraphStructure');
    expect(rule.isCritical).toBe(true);
    expect(rule.priority).toBe(5);
  });

  // ===================== #1: At least one trigger =====================

  describe('#1 - At least one trigger', () => {
    it('should error when no triggers exist', () => {
      const step = makeStepNode('Step 1');
      const ctx = buildContext([step], []);
      const result = rule.validate(ctx);

      expect(result.hasErrors).toBe(true);
      expect(result.issues.some((i) => i.message.includes('at least one trigger'))).toBe(true);
    });

    it('should pass when trigger exists', () => {
      const trigger = makeTriggerNode('Start');
      const ctx = buildContext([trigger], []);
      const result = rule.validate(ctx);

      expect(result.issues.some((i) => i.message.includes('at least one trigger'))).toBe(false);
    });

    it('should pass with empty workflow', () => {
      const ctx = buildContext([], []);
      const result = rule.validate(ctx);

      expect(result.hasErrors).toBe(true);
      expect(result.issues).toHaveLength(1); // only the "no trigger" error
    });
  });

  // ===================== #2: DAG sharing - now ALLOWED =====================

  describe('#2 - Multiple triggers sharing DAG (allowed via dag_group)', () => {
    it('should pass when triggers have independent DAGs', () => {
      const t1 = makeTriggerNode('Trigger A', 'trig-a');
      const t2 = makeTriggerNode('Trigger B', 'trig-b');
      const s1 = makeStepNode('Step 1', { id: 'step-1' });
      const s2 = makeStepNode('Step 2', { id: 'step-2' });
      const edges = [
        makeEdge(t1.id, s1.id),
        makeEdge(t2.id, s2.id),
      ];
      const ctx = buildContext([t1, t2, s1, s2], edges);
      const result = rule.validate(ctx);

      expect(result.issues.filter((i) => i.context?.rule === 'dag_shared_nodes')).toHaveLength(0);
    });

    it('should NOT error when triggers share a descendant node (dag_group auto-assigned)', () => {
      const t1 = makeTriggerNode('Trigger A', 'trig-a');
      const t2 = makeTriggerNode('Trigger B', 'trig-b');
      const shared = makeStepNode('Shared Step', { id: 'shared' });
      const edges = [
        makeEdge(t1.id, shared.id),
        makeEdge(t2.id, shared.id),
      ];
      const ctx = buildContext([t1, t2, shared], edges);
      const result = rule.validate(ctx);

      // No dag_shared_nodes errors - sharing is now allowed
      const dagIssues = result.issues.filter((i) => i.context?.rule === 'dag_shared_nodes');
      expect(dagIssues).toHaveLength(0);
    });

    it('should NOT error when triggers share a deep descendant', () => {
      const t1 = makeTriggerNode('T1', 'trig-1');
      const t2 = makeTriggerNode('T2', 'trig-2');
      const s1 = makeStepNode('S1', { id: 's1' });
      const s2 = makeStepNode('S2', { id: 's2' });
      const shared = makeStepNode('Deep Shared', { id: 'deep-shared' });
      const edges = [
        makeEdge(t1.id, s1.id),
        makeEdge(s1.id, shared.id),
        makeEdge(t2.id, s2.id),
        makeEdge(s2.id, shared.id),
      ];
      const ctx = buildContext([t1, t2, s1, s2, shared], edges);
      const result = rule.validate(ctx);

      expect(result.issues.filter((i) => i.context?.rule === 'dag_shared_nodes')).toHaveLength(0);
    });

    it('should pass with single trigger', () => {
      const t1 = makeTriggerNode('Only Trigger');
      const s1 = makeStepNode('Step', { id: 'step-1' });
      const edges = [makeEdge(t1.id, s1.id)];
      const ctx = buildContext([t1, s1], edges);
      const result = rule.validate(ctx);

      expect(result.issues.filter((i) => i.context?.rule === 'dag_shared_nodes')).toHaveLength(0);
    });

    it('should pass with 3 triggers sharing the same downstream', () => {
      const t1 = makeTriggerNode('Webhook', 'trig-1');
      const t2 = makeTriggerNode('Chat', 'trig-2');
      const t3 = makeTriggerNode('Manual', 'trig-3');
      const step = makeStepNode('Shared', { id: 'shared' });
      const edges = [
        makeEdge(t1.id, step.id),
        makeEdge(t2.id, step.id),
        makeEdge(t3.id, step.id),
      ];
      const ctx = buildContext([t1, t2, t3, step], edges);
      const result = rule.validate(ctx);

      expect(result.hasErrors).toBe(false);
    });

    it('should pass with mixed independent and shared triggers', () => {
      // t1 and t2 share downstream; t3 is independent
      const t1 = makeTriggerNode('T1', 'trig-1');
      const t2 = makeTriggerNode('T2', 'trig-2');
      const t3 = makeTriggerNode('T3', 'trig-3');
      const shared = makeStepNode('Shared', { id: 'shared' });
      const independent = makeStepNode('Independent', { id: 'independent' });
      const edges = [
        makeEdge(t1.id, shared.id),
        makeEdge(t2.id, shared.id),
        makeEdge(t3.id, independent.id),
      ];
      const ctx = buildContext([t1, t2, t3, shared, independent], edges);
      const result = rule.validate(ctx);

      expect(result.hasErrors).toBe(false);
    });
  });

  // ===================== #5: Self-loop =====================

  describe('#5 - Self-loop on non-loop nodes', () => {
    it('should error on self-loop for a step node', () => {
      const trigger = makeTriggerNode('Start', 'trig');
      const step = makeStepNode('Self Loop Step', { id: 'self-step' });
      const edges = [
        makeEdge(trigger.id, step.id),
        makeEdge(step.id, step.id), // self-loop
      ];
      const ctx = buildContext([trigger, step], edges);
      const result = rule.validate(ctx);

      expect(result.hasErrors).toBe(true);
      const selfLoopIssues = result.issues.filter((i) => i.context?.rule === 'self_loop');
      expect(selfLoopIssues).toHaveLength(1);
      expect(selfLoopIssues[0].message).toContain('cannot connect to itself');
    });

    it('should allow self-loop on loop nodes', () => {
      const trigger = makeTriggerNode('Start', 'trig');
      const loop = makeLoopNode('My Loop', 'i < 10', 'loop-1');
      const edges = [
        makeEdge(trigger.id, loop.id),
        makeEdge(loop.id, loop.id), // self-loop on loop node = OK
      ];
      const ctx = buildContext([trigger, loop], edges);
      const result = rule.validate(ctx);

      const selfLoopIssues = result.issues.filter((i) => i.context?.rule === 'self_loop');
      expect(selfLoopIssues).toHaveLength(0);
    });

    it('should not report self-loop when no self-loops exist', () => {
      const trigger = makeTriggerNode('Start', 'trig');
      const s1 = makeStepNode('Step', { id: 's1' });
      const edges = [makeEdge(trigger.id, s1.id)];
      const ctx = buildContext([trigger, s1], edges);
      const result = rule.validate(ctx);

      const selfLoopIssues = result.issues.filter((i) => i.context?.rule === 'self_loop');
      expect(selfLoopIssues).toHaveLength(0);
    });
  });
});

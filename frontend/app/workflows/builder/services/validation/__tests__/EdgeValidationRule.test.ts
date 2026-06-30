import { describe, it, expect, beforeEach } from 'vitest';
import { EdgeValidationRule } from '../rules-v2/EdgeValidationRule';
import {
  makeTriggerNode,
  makeStepNode,
  makeMergeNode,
  makeEdge,
  buildContext,
  resetNodeCounter,
  resetEdgeCounter,
} from './test-helpers';

describe('EdgeValidationRule', () => {
  let rule: EdgeValidationRule;

  beforeEach(() => {
    rule = new EdgeValidationRule();
    resetNodeCounter();
    resetEdgeCounter();
  });

  it('should have correct metadata', () => {
    expect(rule.ruleName).toBe('EdgeValidation');
    expect(rule.isCritical).toBe(true);
    expect(rule.priority).toBe(14);
  });

  // ===================== #17: Edge to non-existent node =====================

  describe('#17 - Edge source/target does not exist', () => {
    it('should error when edge source does not exist', () => {
      const step = makeStepNode('Step', { id: 'step-1' });
      const edges = [makeEdge('nonexistent', step.id)];
      const ctx = buildContext([step], edges);
      const result = rule.validate(ctx);

      const srcIssues = result.issues.filter((i) => i.context?.rule === 'edge_invalid_source');
      expect(srcIssues).toHaveLength(1);
      expect(srcIssues[0].severity).toBe('error');
    });

    it('should error when edge target does not exist', () => {
      const trigger = makeTriggerNode('Start', 'trig');
      const edges = [makeEdge(trigger.id, 'nonexistent')];
      const ctx = buildContext([trigger], edges);
      const result = rule.validate(ctx);

      const tgtIssues = result.issues.filter((i) => i.context?.rule === 'edge_invalid_target');
      expect(tgtIssues).toHaveLength(1);
      expect(tgtIssues[0].severity).toBe('error');
    });

    it('should pass when both source and target exist', () => {
      const trigger = makeTriggerNode('Start', 'trig');
      const step = makeStepNode('Step', { id: 'step-1' });
      const edges = [makeEdge(trigger.id, step.id)];
      const ctx = buildContext([trigger, step], edges);
      const result = rule.validate(ctx);

      const refIssues = result.issues.filter((i) =>
        ['edge_invalid_source', 'edge_invalid_target'].includes(i.context?.rule as string)
      );
      expect(refIssues).toHaveLength(0);
    });
  });

  // ===================== #18: Trigger with incoming edges =====================

  describe('#18 - Trigger with incoming edges', () => {
    it('should error when trigger has incoming edge', () => {
      const trigger = makeTriggerNode('Start', 'trig');
      const step = makeStepNode('Step', { id: 'step-1' });
      const edges = [
        makeEdge(trigger.id, step.id),
        makeEdge(step.id, trigger.id), // incoming to trigger
      ];
      const ctx = buildContext([trigger, step], edges);
      const result = rule.validate(ctx);

      const incomingIssues = result.issues.filter((i) => i.context?.rule === 'trigger_has_incoming');
      expect(incomingIssues).toHaveLength(1);
      expect(incomingIssues[0].severity).toBe('error');
      expect(incomingIssues[0].message).toContain('cannot have incoming edges');
    });

    it('should pass when trigger has no incoming edges', () => {
      const trigger = makeTriggerNode('Start', 'trig');
      const step = makeStepNode('Step', { id: 'step-1' });
      const edges = [makeEdge(trigger.id, step.id)];
      const ctx = buildContext([trigger, step], edges);
      const result = rule.validate(ctx);

      const incomingIssues = result.issues.filter((i) => i.context?.rule === 'trigger_has_incoming');
      expect(incomingIssues).toHaveLength(0);
    });
  });

  // ===================== #19: Merge with < 2 incoming =====================

  describe('#19 - Merge node with < 2 incoming edges', () => {
    it('should warn when merge has only 1 incoming edge', () => {
      const trigger = makeTriggerNode('Start', 'trig');
      const merge = makeMergeNode('Wait All', 'merge-1');
      const edges = [makeEdge(trigger.id, merge.id)];
      const ctx = buildContext([trigger, merge], edges);
      const result = rule.validate(ctx);

      const mergeIssues = result.issues.filter((i) => i.context?.rule === 'merge_few_incoming');
      expect(mergeIssues).toHaveLength(1);
      expect(mergeIssues[0].severity).toBe('warning');
    });

    it('should pass when merge has 2+ incoming edges', () => {
      const trigger = makeTriggerNode('Start', 'trig');
      const s1 = makeStepNode('Step 1', { id: 'step-1' });
      const s2 = makeStepNode('Step 2', { id: 'step-2' });
      const merge = makeMergeNode('Wait All', 'merge-1');
      const edges = [
        makeEdge(trigger.id, s1.id),
        makeEdge(trigger.id, s2.id),
        makeEdge(s1.id, merge.id),
        makeEdge(s2.id, merge.id),
      ];
      const ctx = buildContext([trigger, s1, s2, merge], edges);
      const result = rule.validate(ctx);

      const mergeIssues = result.issues.filter((i) => i.context?.rule === 'merge_few_incoming');
      expect(mergeIssues).toHaveLength(0);
    });

    it('should warn when merge has 0 incoming edges', () => {
      const merge = makeMergeNode('Orphan Merge', 'merge-1');
      const ctx = buildContext([merge], []);
      const result = rule.validate(ctx);

      const mergeIssues = result.issues.filter((i) => i.context?.rule === 'merge_few_incoming');
      expect(mergeIssues).toHaveLength(1);
    });
  });

  // ===================== No edges =====================

  describe('no edges', () => {
    it('should report no issues for empty edge set', () => {
      const ctx = buildContext([], []);
      const result = rule.validate(ctx);
      expect(result.issues).toHaveLength(0);
    });
  });
});

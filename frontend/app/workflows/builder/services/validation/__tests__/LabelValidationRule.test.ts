import { describe, it, expect, beforeEach } from 'vitest';
import { LabelValidationRule } from '../rules-v2/LabelValidationRule';
import {
  makeTriggerNode,
  makeStepNode,
  makeAgentNode,
  makeDecisionNode,
  makeCrudNode,
  makeInterfaceNode,
  makeNoteNode,
  makeEdge,
  buildContext,
  resetNodeCounter,
  resetEdgeCounter,
} from './test-helpers';

describe('LabelValidationRule', () => {
  let rule: LabelValidationRule;

  beforeEach(() => {
    rule = new LabelValidationRule();
    resetNodeCounter();
    resetEdgeCounter();
  });

  it('should have correct metadata', () => {
    expect(rule.ruleName).toBe('LabelValidation');
    expect(rule.isCritical).toBe(true);
    expect(rule.priority).toBe(7);
  });

  // ===================== #6: Missing label =====================

  describe('#6 - Missing label', () => {
    it('should error when trigger has no label', () => {
      const trigger = makeTriggerNode('');
      const ctx = buildContext([trigger], []);
      const result = rule.validate(ctx);

      const labelIssues = result.issues.filter((i) => i.context?.rule === 'missing_label');
      expect(labelIssues).toHaveLength(1);
      expect(labelIssues[0].elementType).toBe('trigger');
    });

    it('should error when step has no label', () => {
      const step = makeStepNode('');
      const ctx = buildContext([step], []);
      const result = rule.validate(ctx);

      const labelIssues = result.issues.filter((i) => i.context?.rule === 'missing_label');
      expect(labelIssues).toHaveLength(1);
      expect(labelIssues[0].elementType).toBe('mcp');
    });

    it('should error when agent has no label', () => {
      const agent = makeAgentNode('');
      const ctx = buildContext([agent], []);
      const result = rule.validate(ctx);

      const labelIssues = result.issues.filter((i) => i.context?.rule === 'missing_label');
      expect(labelIssues).toHaveLength(1);
      expect(labelIssues[0].elementType).toBe('agent');
    });

    it('should error when decision has no label', () => {
      const decision = makeDecisionNode('', [{ id: 'c1', type: 'if', label: 'IF', expression: 'true' }]);
      const ctx = buildContext([decision], []);
      const result = rule.validate(ctx);

      const labelIssues = result.issues.filter((i) => i.context?.rule === 'missing_label');
      expect(labelIssues).toHaveLength(1);
      expect(labelIssues[0].elementType).toBe('core');
    });

    it('should error when CRUD node has no label', () => {
      const crud = makeCrudNode('', { dataSourceId: 'ds-1' });
      const ctx = buildContext([crud], []);
      const result = rule.validate(ctx);

      const labelIssues = result.issues.filter((i) => i.context?.rule === 'missing_label');
      expect(labelIssues).toHaveLength(1);
      expect(labelIssues[0].elementType).toBe('table');
    });

    it('should error when interface has no label', () => {
      const iface = makeInterfaceNode('');
      const ctx = buildContext([iface], []);
      const result = rule.validate(ctx);

      const labelIssues = result.issues.filter((i) => i.context?.rule === 'missing_label');
      expect(labelIssues).toHaveLength(1);
      expect(labelIssues[0].elementType).toBe('interface');
    });

    it('should skip note nodes (non-executable)', () => {
      const note = makeNoteNode('');
      const ctx = buildContext([note], []);
      const result = rule.validate(ctx);

      const labelIssues = result.issues.filter((i) => i.context?.rule === 'missing_label');
      expect(labelIssues).toHaveLength(0);
    });

    it('should pass when all nodes have labels', () => {
      const trigger = makeTriggerNode('Start');
      const step = makeStepNode('Do something');
      const ctx = buildContext([trigger, step], []);
      const result = rule.validate(ctx);

      const labelIssues = result.issues.filter((i) => i.context?.rule === 'missing_label');
      expect(labelIssues).toHaveLength(0);
    });

    it('should error on whitespace-only label', () => {
      const step = makeStepNode('   ');
      const ctx = buildContext([step], []);
      const result = rule.validate(ctx);

      const labelIssues = result.issues.filter((i) => i.context?.rule === 'missing_label');
      expect(labelIssues).toHaveLength(1);
    });
  });

  // ===================== #7: Duplicate labels =====================

  describe('#7 - Duplicate labels', () => {
    it('should error when two nodes have the same label', () => {
      const s1 = makeStepNode('Do thing', { id: 'step-1' });
      const s2 = makeStepNode('Do thing', { id: 'step-2' });
      const ctx = buildContext([s1, s2], []);
      const result = rule.validate(ctx);

      const dupeIssues = result.issues.filter((i) => i.context?.rule === 'duplicate_label');
      expect(dupeIssues.length).toBeGreaterThanOrEqual(2); // both nodes flagged
    });

    it('should error when labels normalize to the same key (case insensitive)', () => {
      const s1 = makeStepNode('My Step', { id: 'step-1' });
      const s2 = makeStepNode('my step', { id: 'step-2' });
      const ctx = buildContext([s1, s2], []);
      const result = rule.validate(ctx);

      const dupeIssues = result.issues.filter((i) => i.context?.rule === 'duplicate_label');
      expect(dupeIssues.length).toBeGreaterThanOrEqual(2);
    });

    it('should error across different node types with same label', () => {
      const trigger = makeTriggerNode('Process');
      const step = makeStepNode('Process');
      const ctx = buildContext([trigger, step], []);
      const result = rule.validate(ctx);

      const dupeIssues = result.issues.filter((i) => i.context?.rule === 'duplicate_label');
      expect(dupeIssues.length).toBeGreaterThanOrEqual(2);
    });

    it('should pass when all labels are unique', () => {
      const t = makeTriggerNode('Start');
      const s1 = makeStepNode('Step A');
      const s2 = makeStepNode('Step B');
      const ctx = buildContext([t, s1, s2], []);
      const result = rule.validate(ctx);

      const dupeIssues = result.issues.filter((i) => i.context?.rule === 'duplicate_label');
      expect(dupeIssues).toHaveLength(0);
    });
  });

  // ===================== #8: Unrecognized prefix =====================

  describe('#8 - Unrecognized prefix', () => {
    it('should pass for all valid node types', () => {
      const trigger = makeTriggerNode('T');
      const step = makeStepNode('S');
      const agent = makeAgentNode('A');
      const decision = makeDecisionNode('D', [{ id: 'c1', type: 'if', label: 'IF', expression: 'true' }]);
      const crud = makeCrudNode('C', { dataSourceId: 'ds-1' });
      const iface = makeInterfaceNode('I');
      const ctx = buildContext([trigger, step, agent, decision, crud, iface], []);
      const result = rule.validate(ctx);

      const prefixIssues = result.issues.filter((i) => i.context?.rule === 'invalid_prefix');
      expect(prefixIssues).toHaveLength(0);
    });
  });
});

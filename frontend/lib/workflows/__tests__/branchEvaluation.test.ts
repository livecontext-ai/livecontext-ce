import { describe, it, expect } from 'vitest';
import {
  normalizeBranchEvaluation,
  normalizeBranchEvaluations,
  describeResult,
} from '../branchEvaluation';

/**
 * The renderers read a canonical shape; the backend used to write four different ones.
 *
 * This is the frontend half of a contract mismatch that made the inspector's branch
 * table structurally empty: it read `branch` / `resolved` / `selected` while decision
 * rows carried `branch_type` / `resolved_condition` and no selection at all. Nothing
 * failed, nothing logged; the table simply drew blanks and never highlighted the
 * branch the run took.
 */
describe('normalizeBranchEvaluation', () => {
  it('reads the canonical shape a branching node now reports', () => {
    const entry = normalizeBranchEvaluation({
      index: 0,
      branch: 'if',
      condition: '{{value}} > 10',
      resolved: '15 > 10',
      result: true,
      selected: true,
      outcome: 'matched',
    });

    expect(entry.branch).toBe('if');
    expect(entry.resolved).toBe('15 > 10');
    expect(entry.result).toBe(true);
    expect(entry.selected).toBe(true);
    expect(entry.outcome).toBe('matched');
  });

  it('reads a decision row written before the shape was unified', () => {
    // The exact keys that shipped: the table showed an empty Branch column and a dash
    // under Resolved for every decision ever run.
    const entry = normalizeBranchEvaluation({
      branch_type: 'if',
      condition: '{{value}} > 10',
      resolved_condition: '15 > 10',
      result: true,
      index: 0,
    });

    expect(entry.branch).toBe('if');
    expect(entry.resolved).toBe('15 > 10');
    expect(entry.selected).toBe(false);
  });

  it('reads an option row written before the shape was unified', () => {
    const entry = normalizeBranchEvaluation({
      choice_id: 'c1',
      choice_label: 'High',
      expression: '{{value}} > 10',
      resolved_expression: '15 > 10',
      result: true,
    });

    expect(entry.branch).toBe('High');
    expect(entry.condition).toBe('{{value}} > 10');
    expect(entry.resolved).toBe('15 > 10');
  });

  it('reads a switch case row', () => {
    const entry = normalizeBranchEvaluation({
      case_type: 'case_1',
      case_label: 'Active',
      case_value: 'active',
      result: true,
      is_default: false,
    });

    expect(entry.branch).toBe('case_1');
    expect(entry.condition).toBe('active');
  });

  it('keeps the author name apart from the port', () => {
    // The Cases table shows what the author called the case. Rendering the port
    // instead replaced every name with a number: "Gold tier" became "case_0".
    const named = normalizeBranchEvaluation({
      branch: 'case_0',
      case_label: 'Gold tier',
      condition: 'gold',
      resolved: 'gold == gold',
      result: true,
      selected: true,
      outcome: 'matched',
    });

    expect(named.branch).toBe('case_0');
    expect(named.label).toBe('Gold tier');
  });

  it('falls back to the port when the branch has no author name', () => {
    expect(normalizeBranchEvaluation({ branch: 'if' }).label).toBe('if');
  });

  it('reads an option choice port and its author label', () => {
    const entry = normalizeBranchEvaluation({
      branch: 'choice_0',
      choice_id: 'c1',
      choice_label: 'High',
      condition: '{{value}} > 10',
      resolved: '15 > 10',
      result: true,
      selected: true,
      outcome: 'matched',
    });

    expect(entry.branch).toBe('choice_0');
    expect(entry.label).toBe('High');
  });

  it('carries the references that resolved to nothing', () => {
    const entry = normalizeBranchEvaluation({
      branch: 'if',
      condition: '{{trigger:ghost.output.task}} == null',
      resolved: '<unresolved: trigger:ghost.output.task> == null',
      result: true,
      selected: true,
      outcome: 'matched',
      unresolved: [
        { reference: 'trigger:ghost.output.task', reason: "no node or trigger named 'trigger:ghost'" },
      ],
    });

    expect(entry.unresolved).toHaveLength(1);
    expect(entry.unresolved[0].reason).toContain('no node or trigger named');
  });

  it('never treats a missing selection as a selection', () => {
    // A legacy row cannot say which branch won: it was never written. Guessing here
    // would highlight the wrong row, which is worse than highlighting none.
    expect(normalizeBranchEvaluation({ branch: 'if' }).selected).toBe(false);
    expect(normalizeBranchEvaluation({ branch: 'if', selected: 'yes' }).selected).toBe(false);
  });

  it('survives junk without throwing', () => {
    expect(normalizeBranchEvaluation(null).branch).toBe('');
    expect(normalizeBranchEvaluation(undefined).result).toBeNull();
    expect(normalizeBranchEvaluation({ unresolved: 'not-an-array' }).unresolved).toEqual([]);
    expect(normalizeBranchEvaluations('not-an-array')).toEqual([]);
    expect(normalizeBranchEvaluations([{ branch: 'a' }, { branch_type: 'b' }])).toHaveLength(2);
  });
});

describe('describeResult', () => {
  it('says a branch had no condition rather than printing a tautological true', () => {
    // Printing `true` for an else is what made a skipped else read like a matched if.
    const fallback = normalizeBranchEvaluation({
      branch: 'else',
      condition: '',
      resolved: '(no condition)',
      result: null,
      selected: false,
      outcome: 'fallback',
    });

    expect(describeResult(fallback)).toBe('no condition');
  });

  it('reports a real boolean as a boolean', () => {
    expect(describeResult(normalizeBranchEvaluation({ result: true }))).toBe('true');
    expect(describeResult(normalizeBranchEvaluation({ result: false }))).toBe('false');
  });

  it('says a path was taken although its condition was false', () => {
    // A loop exits BECAUSE its condition stopped holding, so the path it took is
    // reported with outcome `taken` rather than `not_matched`.
    const exit = normalizeBranchEvaluation({
      branch: 'exit',
      condition: '{{value}} > 100',
      resolved: '15 > 100',
      result: false,
      selected: true,
      outcome: 'taken',
    });

    expect(exit.selected).toBe(true);
    expect(exit.outcome).toBe('taken');
    expect(describeResult(exit)).toBe('false');
  });

  it('reports an evaluation error ahead of its result', () => {
    const errored = normalizeBranchEvaluation({ result: false, error: 'SpEL parse failure' });
    expect(describeResult(errored)).toBe('error');
  });
});

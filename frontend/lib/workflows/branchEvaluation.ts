/**
 * The one shape the inspector reads a branch evaluation in, whatever wrote the row.
 *
 * Every branching node now reports `BranchEvaluationReport`'s canonical keys
 * (branch / condition / resolved / result / selected / outcome). Before that each
 * node had its own spelling and none of them carried `selected`, so the renderers
 * here - written against a canonical record that nothing ever populated - read
 * `branch`, `resolved` and `selected` from rows that carried `branch_type`,
 * `resolved_condition` and no selection at all. The table drew an empty Branch
 * column, a dash for Resolved, and never highlighted the row the run took.
 *
 * Rows already in the database keep their old keys, so each legacy spelling is
 * read as a fallback. `selected` is the one thing a legacy row cannot supply: it
 * was never written, so an old run still shows no highlight.
 */

export interface NormalizedBranchEvaluation {
  /** The port this branch routes through. */
  branch: string;
  /**
   * What the author called this branch, falling back to the port.
   *
   * A switch case and an option choice are NAMED by their author ("Gold tier"), and
   * the port (`case_0`) cannot say that. Showing the port alone replaced every
   * author-given name in the Cases table with a number.
   */
  label: string;
  /** The condition as configured, empty when the branch has none. */
  condition: string;
  /** The condition with its references substituted, as evaluated. */
  resolved: string | null;
  /** What it evaluated to. Null means there was nothing to evaluate. */
  result: boolean | null;
  /** Whether the run took this branch. False for every row written before this existed. */
  selected: boolean;
  /** Why it was or was not taken, when the row says. */
  outcome: string | null;
  /** Evaluation error, when there was one. */
  error: string | null;
  /** References that pointed at nothing. */
  unresolved: Array<{ reference: string; reason: string }>;
}

function firstString(...candidates: unknown[]): string | null {
  for (const candidate of candidates) {
    if (typeof candidate === 'string' && candidate.length > 0) return candidate;
  }
  return null;
}

/**
 * Reads one evaluation entry, canonical or legacy.
 *
 * Legacy spellings, one per node that invented its own: `branch_type` (decision),
 * `case_type` / `case_label` (switch), `choice_label` / `choice_id` (option),
 * and `resolved_condition` / `resolved_expression` for the resolved form.
 */
export function normalizeBranchEvaluation(raw: unknown): NormalizedBranchEvaluation {
  const entry = (raw ?? {}) as Record<string, unknown>;

  const unresolvedRaw = entry.unresolved;
  const unresolved = Array.isArray(unresolvedRaw)
    ? unresolvedRaw
        .map((item) => {
          const ref = (item ?? {}) as Record<string, unknown>;
          return {
            reference: typeof ref.reference === 'string' ? ref.reference : '',
            reason: typeof ref.reason === 'string' ? ref.reason : '',
          };
        })
        .filter((item) => item.reference.length > 0)
    : [];

  const branch =
    firstString(
      entry.branch,
      entry.branch_type,
      entry.case_type,
      entry.choice_label,
      entry.case_label,
      entry.label,
      entry.type,
      entry.choice_id
    ) ?? '';

  return {
    branch,
    label: firstString(entry.case_label, entry.choice_label, entry.label) ?? branch,
    condition:
      firstString(entry.condition, entry.expression, entry.case_value, entry.value) ?? '',
    resolved: firstString(entry.resolved, entry.resolved_condition, entry.resolved_expression),
    result: typeof entry.result === 'boolean' ? entry.result : null,
    selected: entry.selected === true,
    outcome: firstString(entry.outcome),
    error: firstString(entry.error),
    unresolved,
  };
}

export function normalizeBranchEvaluations(value: unknown): NormalizedBranchEvaluation[] {
  if (!Array.isArray(value)) return [];
  return value.map(normalizeBranchEvaluation);
}

/**
 * How a result reads in the table.
 *
 * A branch with no condition shows why it is exempt rather than a boolean it never
 * produced: printing `true` for an else is exactly what made a skipped else look
 * like a matched if.
 */
export function describeResult(evaluation: NormalizedBranchEvaluation): string {
  if (evaluation.error) return 'error';
  if (evaluation.result === null) return 'no condition';
  return String(evaluation.result);
}

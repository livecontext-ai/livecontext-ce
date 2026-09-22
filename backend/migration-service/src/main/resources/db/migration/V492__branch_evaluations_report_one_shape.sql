-- Every branching node reports its branches in ONE shape, and the docs say which.
--
-- An agent reads node_type_documentation to learn what a node returns. For the three
-- branching types it read "Detailed evaluation results", which named no field at all,
-- except for `option`, which named four fields that have now changed. Each node had
-- invented its own spelling: a decision said branch_type / resolved_condition, an
-- option said choice_label / resolved_expression, a switch reported no resolved value
-- per case, and none of the three said which branch was SELECTED.
--
-- That last omission is what a user sees. An `else` has no condition to evaluate, so it
-- reported `result: true` by definition, sitting beside a matched `if` that also
-- reported `result: true`: the branch the run took was indistinguishable from the one it
-- skipped. The shape below reports `result: null` for a branch with no condition and
-- carries `selected` on every entry.
--
-- Doc-side only. The engine change lives in BranchEvaluationReport, and the frontend
-- reads both the new keys and the old ones, because rows already in this database keep
-- the spelling they were written with.
--
-- jsonb_set on a single path on purpose: restating the whole `outputs` object would
-- re-assert every other field from this file's point of view and silently revert any
-- description a later migration refined.

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET outputs = jsonb_set(
        outputs,
        '{evaluations,description}',
        '"One entry per branch: index, branch (the port the edge carries: if / elseif_N / else), condition, resolved (the expression the evaluation decided on), result (null when the branch has no condition, as an else does not), selected (exactly one entry is true, or none when no branch matched), outcome (matched / not_matched / matched_not_selected / fallback / error), error when the condition could not be evaluated, unresolved listing any NAMESPACED reference (trigger:x, core:y) that pointed at nothing"'::jsonb
    ),
    updated_at = NOW()
WHERE type = 'decision'
  AND outputs ? 'evaluations';

UPDATE node_type_documentation
SET outputs = jsonb_set(
        outputs,
        '{evaluations,description}',
        '"One entry per case: index, branch (the port: case_N / default), case_label (what the author named it), condition (the value this case matches on), resolved (the comparison that was made, subject == candidate), result (null for the default, which has nothing to compare), selected (one entry is true, or none when nothing matched and no default is declared), outcome (matched / matched_not_selected when a later case matches the same value / not_matched / fallback)"'::jsonb
    ),
    updated_at = NOW()
WHERE type = 'switch'
  AND outputs ? 'evaluations';

UPDATE node_type_documentation
SET outputs = jsonb_set(
        outputs,
        '{evaluations,description}',
        '"One entry per choice: index, branch (the port: choice_N), choice_id, choice_label, condition (the expression as configured), resolved (the expression the evaluation decided on), result, selected (exactly one entry is true, or none when no choice matched), outcome (matched / not_matched / matched_not_selected / error), error when the choice has no expression or could not be evaluated, unresolved listing any NAMESPACED reference (trigger:x, core:y) that pointed at nothing"'::jsonb
    ),
    updated_at = NOW()
WHERE type = 'option'
  AND outputs ? 'evaluations';

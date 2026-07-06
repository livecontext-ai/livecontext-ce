-- ============================================================================
-- V390: Clear stale find_rows global_variables (item/index parallel-context vars)
-- ============================================================================
-- The original V11 seed described find_rows as split-like: it advertised
-- global_variables = {"item": "Current row in parallel context ...",
--                     "index": "Current row index (0-based) in parallel context"}.
--
-- V20 (then V77) corrected the description/parameters/outputs/concepts to make
-- clear find_rows is a plain collection node: it returns an items[] array and
-- does NOT split/spawn a per-row body/parallel context (to iterate, connect a
-- Split node after it). Those targeted UPDATEs did NOT touch global_variables,
-- so the stale item/index runtime vars survived.
--
-- Both agent-facing help paths (NodeHelpFormatter -> body_variables and
-- NodeLibraryService -> global_variables) serialize this column verbatim, so an
-- agent reading find_rows help sees a self-contradictory doc: "does NOT
-- split/spawn" alongside body_variables advertising {{item}}/{{index}} that the
-- node never creates. Clearing the column removes the misleading vars.
--
-- find_rows is the only node with this stale parallel-context global_variables;
-- Split legitimately keeps current_item/current_index because it does spawn.
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET global_variables = NULL,
    updated_at = NOW()
WHERE type = 'find_rows'
  AND global_variables IS NOT NULL;

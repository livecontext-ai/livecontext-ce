-- V358: Align core:loop node_type_documentation.outputs with the runtime + LoopNodeSpec.
--
-- The loop output evolves across LoopNode.execute() (first entry) and BackEdgeHandler
-- (each iteration + termination). The termination path emits a `reason` field
-- (condition_false | max_iterations_reached) that the runtime writes and the
-- docs/node-schemas/loop.md examples already reference, but the agent-facing row
-- seeded in V11 never declared it - so an agent could not discover
-- {{core:<loop>.output.reason}}. Add `reason` and refresh the field descriptions to
-- match LoopNodeSpec exactly (selected_path is only body|exit; the `iterate` value
-- never occurs at runtime - it is a port, not an output value). Keeps the 3-way
-- coherence contract (NodeSpec <-> node_type_documentation <-> /api/node-definitions)
-- green for LOOP, which is NOT in KNOWN_SCHEMA_DRIFT.
UPDATE node_type_documentation
SET outputs = '{"iteration": {"type": "number", "description": "Current iteration number (0 on first entry, increments each iteration)"}, "maxIterations": {"type": "number", "description": "Configured maximum allowed iterations"}, "terminated": {"type": "boolean", "description": "Whether the loop has terminated (condition false or max iterations reached)"}, "enter_body": {"type": "boolean", "description": "Whether the loop body should be entered this evaluation"}, "selected_path": {"type": "string", "description": "The selected loop path: body or exit"}, "reason": {"type": "string", "description": "Exit reason, present only once terminated: condition_false or max_iterations_reached"}}'::jsonb,
    updated_at = NOW()
WHERE type = 'loop';

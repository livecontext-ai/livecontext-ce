-- ============================================================================
-- V349: Strip em-dashes / en-dashes from agent-facing node documentation prose.
--
--   node_type_documentation is the "LLM sees" leg of the 3-way node-output-schema
--   alignment: the agent reads these rows at runtime. The em-dash and en-dash
--   glyphs read as "AI-generated" (see AGENTS.md, "Ban the Em-Dash"), and an
--   agent that reads them in its own node docs tends to echo the same glyph in
--   its output. This migration removes them from the seeded docs.
--
--   It rewrites only PROSE values (description, examples, concepts, parameter and
--   output descriptions, ...). JSON keys / field names are ASCII identifiers with
--   no dash glyph, so the mapper / DB-doc / frontend-schema field-name alignment
--   is untouched, and every JSONB column round-trips through ::text and back to a
--   structurally identical value with only the dash glyph swapped for a hyphen.
--
--   The three glyphs in the translate() match-set below are INTENTIONAL: this is
--   the migration that removes them, so it must name them to match them. They are
--   U+2014 (em-dash), U+2013 (en-dash) and U+2015 (horizontal bar).
--
--   Idempotent: the WHERE guard matches only rows that still contain a dash glyph,
--   so a re-run (or a fresh DB already seeded clean) is a no-op.
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET
    description      = translate(description, '--―', '---'),
    parameters       = translate(parameters::text, '--―', '---')::jsonb,
    outputs          = translate(outputs::text, '--―', '---')::jsonb,
    global_variables = translate(global_variables::text, '--―', '---')::jsonb,
    examples         = translate(examples::text, '--―', '---')::jsonb,
    keywords         = translate(keywords::text, '--―', '---')::jsonb,
    edge_ports       = translate(edge_ports::text, '--―', '---')::jsonb,
    concepts         = translate(concepts::text, '--―', '---')::jsonb,
    comparison       = translate(comparison::text, '--―', '---')::jsonb,
    updated_at       = NOW()
WHERE description ~ '[--―]'
   OR parameters::text ~ '[--―]'
   OR outputs::text ~ '[--―]'
   OR global_variables::text ~ '[--―]'
   OR examples::text ~ '[--―]'
   OR keywords::text ~ '[--―]'
   OR edge_ports::text ~ '[--―]'
   OR concepts::text ~ '[--―]'
   OR comparison::text ~ '[--―]';

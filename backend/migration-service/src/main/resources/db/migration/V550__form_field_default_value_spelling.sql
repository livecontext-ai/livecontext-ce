-- ---------------------------------------------------------------------------
-- V550: the public form's copy of a form trigger's fields spells a default
-- `defaultValue`, the one key the public form reads.
--
-- Agents wrote `default` (or `default_value`), which the builder stored as is
-- and the public form never read: the field looked configured and was never
-- pre-filled. Found in a Gemini chat on prod on 2026-09-29.
--
-- The public form renders "trigger".standalone_form_endpoints.form_config, a
-- COPY of the plan's fields pushed on save and pin, never the plan itself. The
-- code now writes that copy in the canonical shape whatever wrote the plan, so
-- only the copies written before it need this pass (1 row on prod the day it
-- was measured).
--
-- The plans themselves are deliberately NOT rewritten: every reader of a plan
-- (execute, the agent's execute schema, the editor) reads the alias as a
-- fallback, and rewriting a plan would make it differ from its saved version,
-- so the next save would mint a version that changes nothing a user can see.
-- Plans converge the next time a builder path writes their fields.
--
-- Rule per field: a non-blank `defaultValue` wins and the aliases are dropped;
-- otherwise the first non-blank of `default`, `default_value` becomes
-- `defaultValue`. Blank means empty or whitespace only (Java isBlank), and a
-- JSON null counts as blank. Field order and every other key are kept.
--
-- Idempotent: only rows that still hold an alias are touched, and a second run
-- finds none.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION "trigger".v550_is_blank(value text)
RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
    SELECT value IS NULL OR value ~ '^[[:space:]]*$'
$$;

CREATE OR REPLACE FUNCTION "trigger".v550_canonical_form_fields(fields jsonb)
RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
    SELECT coalesce(jsonb_agg(
        CASE
            WHEN jsonb_typeof(f) <> 'object' OR NOT (f ?| array['default', 'default_value']) THEN f
            WHEN NOT "trigger".v550_is_blank(f->>'defaultValue')
                THEN f - 'default' - 'default_value'
            WHEN NOT "trigger".v550_is_blank(f->>'default')
                THEN (f - 'default' - 'default_value') || jsonb_build_object('defaultValue', f->'default')
            WHEN NOT "trigger".v550_is_blank(f->>'default_value')
                THEN (f - 'default' - 'default_value') || jsonb_build_object('defaultValue', f->'default_value')
            ELSE f - 'default' - 'default_value'
        END ORDER BY ord), '[]'::jsonb)
    FROM jsonb_array_elements(fields) WITH ORDINALITY AS e(f, ord)
$$;

UPDATE "trigger".standalone_form_endpoints
   SET form_config = "trigger".v550_canonical_form_fields(form_config)
 WHERE CASE
           -- CASE, not AND: jsonb_array_elements raises on a non-array, and only CASE fixes
           -- the order the planner evaluates the two conditions in.
           WHEN jsonb_typeof(form_config) = 'array' THEN EXISTS (
               SELECT 1 FROM jsonb_array_elements(form_config) f
               WHERE jsonb_typeof(f) = 'object' AND f ?| array['default', 'default_value'])
           ELSE false
       END;

DROP FUNCTION "trigger".v550_canonical_form_fields(jsonb);
DROP FUNCTION "trigger".v550_is_blank(text);

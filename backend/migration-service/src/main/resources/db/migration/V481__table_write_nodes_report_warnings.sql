-- A table write step can succeed and still have stored something unusable. Say so where the agent
-- looks.
--
-- Every write runs through the column-type coercion, which REPORTS what it did instead of refusing
-- the value: mostly normalisations that need nothing, and some that mean the value cannot be used
-- at all (it could not be read, or it could not be resolved to a file). The step already carried
-- those sentences inside its `message`, but nothing NAMED them, so no help or example pointed a
-- reader at that tail and no downstream step could branch on prose - a published workflow could
-- write a dead cell and read as clean to anyone who did not parse a message.
--
-- EXTENDS the outputs V253 wrote rather than replacing them, and re-running this file is a no-op.
SET search_path TO orchestrator;

UPDATE node_type_documentation
SET
  outputs = COALESCE(outputs, '{}'::jsonb) || '{
    "warnings": {"type": "array", "description": "What the column types reported about the values this step wrote, present only when there is something to say. Most entries are normalisations that need nothing (''Converted date format to ISO'', ''Interpreted comma as decimal separator''). An entry saying the value could not be PARSED, READ or RESOLVED to a file needs a fix, and comes in two flavours. The value was NOT STORED (that cell is null now, and the row looks complete until you read it back): ''Cannot parse as ...'', ''Invalid number'', ''Invalid epoch value'', ''Cannot convert boolean to date'', ''File reference found in EMAIL/PHONE column''. Or the value IS stored and nothing can use it: ''it cannot be displayed'' (a file reference with nothing to fetch it by: write the whole reference a file-producing step gave you, do not rebuild one from a path), ''does not look like a file URL'', ''Coercion error''. A vector column never warns: a bad embedding FAILS the step instead, and no row is written. Each entry names its column and, when the same finding covered several cells, how many; never which row."}
  }'::jsonb,
  concepts = (
    SELECT COALESCE(jsonb_agg(concept), '[]'::jsonb)
    FROM jsonb_array_elements(COALESCE(node_type_documentation.concepts, '[]'::jsonb)) AS concept
    WHERE concept #>> '{}' NOT LIKE 'A successful write can still report%'
  ) || '["A successful write can still report warnings: read output.warnings before treating the write as clean. A step that stored a file reference it cannot display says so there - it is the only output you can branch on, the message only repeats it as prose."]'::jsonb,
  updated_at = NOW()
WHERE type IN ('insert_row', 'update_row');

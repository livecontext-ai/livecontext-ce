-- ============================================================================
-- V330: Normalize node_type_documentation.examples back to an all-string array.
--
--   V326 appended a JSON OBJECT into the task node's `examples`:
--     examples || '[{"title": ..., "body": "workflow(...)"}]'::jsonb
--   but the orchestrator maps this column as List<String>
--   (NodeTypeDocumentationEntity: @JdbcTypeCode(SqlTypes.JSON) List<String>).
--   A non-string element makes Jackson throw while hydrating the entity at
--   startup, crash-looping orchestrator (and the CE monolith) on a FRESH DB
--   where V326 runs against the seeded docs. Reproduced on blank volumes.
--
--   Fix-forward: rewrite every examples array so all elements are strings -
--   an object becomes its "body" text (the intended example string), falling
--   back to the element's compact JSON if "body" is absent. String elements
--   are left untouched, so existing content is preserved and ordering kept.
--
--   Idempotent: the WHERE guard only matches rows that still contain a
--   non-string element, so a second run (or a fresh DB where the data is
--   already clean) is a no-op.
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET
    examples = (
        SELECT jsonb_agg(
                   CASE
                       WHEN jsonb_typeof(elem) = 'string' THEN elem
                       WHEN jsonb_typeof(elem) = 'object' AND elem ? 'body'
                            THEN to_jsonb(elem ->> 'body')
                       ELSE to_jsonb(elem #>> '{}')
                   END
                   ORDER BY ord)
        FROM jsonb_array_elements(examples) WITH ORDINALITY AS e(elem, ord)
    ),
    updated_at = NOW()
WHERE examples IS NOT NULL
  AND jsonb_typeof(examples) = 'array'
  AND EXISTS (
        SELECT 1
        FROM jsonb_array_elements(examples) AS x
        WHERE jsonb_typeof(x) <> 'string'
  );

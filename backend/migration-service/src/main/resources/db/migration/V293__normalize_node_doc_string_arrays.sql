-- ============================================================================
-- V293: Normalize node_type_documentation string-array fields
--
-- V292 accidentally wrote concepts/examples as arrays of objects for the task
-- node. NodeTypeDocumentationEntity maps both columns as List<String>, so any
-- object element prevents Hibernate from loading enabled node docs at startup.
-- Keep the richer text by flattening {title, body} into "title: body".
-- ============================================================================

SET search_path TO orchestrator;

WITH normalized AS (
    SELECT
        id,
        CASE
            WHEN concepts IS NULL THEN NULL
            WHEN jsonb_typeof(concepts) <> 'array' THEN concepts
            ELSE COALESCE((
                SELECT jsonb_agg(
                    CASE
                        WHEN jsonb_typeof(item) = 'string' THEN to_jsonb(item #>> ARRAY[]::text[])
                        WHEN jsonb_typeof(item) = 'object' AND item ? 'title' AND item ? 'body'
                            THEN to_jsonb((item ->> 'title') || ': ' || (item ->> 'body'))
                        WHEN jsonb_typeof(item) = 'object' AND item ? 'body'
                            THEN to_jsonb(item ->> 'body')
                        WHEN jsonb_typeof(item) = 'object' AND item ? 'title'
                            THEN to_jsonb(item ->> 'title')
                        ELSE to_jsonb(item::text)
                    END
                    ORDER BY ord
                )
                FROM jsonb_array_elements(concepts) WITH ORDINALITY AS elements(item, ord)
            ), '[]'::jsonb)
        END AS normalized_concepts,
        CASE
            WHEN examples IS NULL THEN NULL
            WHEN jsonb_typeof(examples) <> 'array' THEN examples
            ELSE COALESCE((
                SELECT jsonb_agg(
                    CASE
                        WHEN jsonb_typeof(item) = 'string' THEN to_jsonb(item #>> ARRAY[]::text[])
                        WHEN jsonb_typeof(item) = 'object' AND item ? 'title' AND item ? 'body'
                            THEN to_jsonb((item ->> 'title') || ': ' || (item ->> 'body'))
                        WHEN jsonb_typeof(item) = 'object' AND item ? 'body'
                            THEN to_jsonb(item ->> 'body')
                        WHEN jsonb_typeof(item) = 'object' AND item ? 'title'
                            THEN to_jsonb(item ->> 'title')
                        ELSE to_jsonb(item::text)
                    END
                    ORDER BY ord
                )
                FROM jsonb_array_elements(examples) WITH ORDINALITY AS elements(item, ord)
            ), '[]'::jsonb)
        END AS normalized_examples
    FROM node_type_documentation
    WHERE
        (jsonb_typeof(concepts) = 'array' AND EXISTS (
            SELECT 1 FROM jsonb_array_elements(concepts) AS concept(item)
            WHERE jsonb_typeof(item) <> 'string'
        ))
        OR
        (jsonb_typeof(examples) = 'array' AND EXISTS (
            SELECT 1 FROM jsonb_array_elements(examples) AS example(item)
            WHERE jsonb_typeof(item) <> 'string'
        ))
)
UPDATE node_type_documentation docs
SET
    concepts = normalized.normalized_concepts,
    examples = normalized.normalized_examples,
    updated_at = NOW()
FROM normalized
WHERE docs.id = normalized.id;

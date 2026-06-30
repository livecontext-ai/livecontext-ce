-- V30: Fix file node concepts column - V27 incorrectly appended JSON objects to a List<String> column
-- The concepts column must be a JSONB array of strings, not mixed strings+objects.
-- This migration converts the object entries back to plain strings.

SET search_path TO orchestrator;

-- ConvertToFile: replace object entry with string
UPDATE node_type_documentation
SET concepts = (
    SELECT jsonb_agg(
        CASE
            WHEN jsonb_typeof(elem) = 'object' THEN to_jsonb(elem ->> 'file_ref')
            ELSE elem
        END
    )
    FROM jsonb_array_elements(concepts) AS elem
)
WHERE type = 'convert_to_file' AND concepts IS NOT NULL;

-- Compression: replace object entry with string
UPDATE node_type_documentation
SET concepts = (
    SELECT jsonb_agg(
        CASE
            WHEN jsonb_typeof(elem) = 'object' THEN to_jsonb(elem ->> 'file_ref')
            ELSE elem
        END
    )
    FROM jsonb_array_elements(concepts) AS elem
)
WHERE type = 'compression' AND concepts IS NOT NULL;

-- ExtractFromFile: replace object entry with string
UPDATE node_type_documentation
SET concepts = (
    SELECT jsonb_agg(
        CASE
            WHEN jsonb_typeof(elem) = 'object' THEN to_jsonb(elem ->> 'file_ref_input')
            ELSE elem
        END
    )
    FROM jsonb_array_elements(concepts) AS elem
)
WHERE type = 'extract_from_file' AND concepts IS NOT NULL;

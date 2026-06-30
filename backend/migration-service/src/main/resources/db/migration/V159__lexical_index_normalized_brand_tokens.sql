-- ---------------------------------------------------------------------------
-- V159: Lexical search - index brand-normalized tokens
--
-- Problem in prod (catalog v0): the AI-agent search for queries containing a
-- canonical brand spelled as one word (e.g. "openai create image") returns 0
-- hits for `openai-create-image` even though the tool exists. Root cause:
--
--   - `provider` is stored as "Open Ai" (two tokens), tokenized by the English
--     parser to {open, ai}.
--   - `plainto_tsquery('english', 'openai create image generate')` produces
--     `'openai' & 'creat' & 'imag' & 'generat'` (AND).
--   - The `openai` lexeme never appears in any "Open Ai" row → row excluded.
--   - Same problem for "Google Cloud", "AWS IAM", "Microsoft Azure", "DALL-E",
--     "GPT-4", every hyphenated tool slug, etc.  77 / 89 OpenAI tools and
--     ~hundreds of multi-word-provider rows are unreachable by their canonical
--     concatenated brand name.
--
-- Fix (generic, no per-provider patching):
--
--   In the `update_lexical_tsvectors` trigger, ALSO index a "compact" form
--   of provider + tool_name + resource + action where every space, dash and
--   underscore is stripped, tokenized via the `simple` parser (no stemming,
--   no stop-words, lowercase only).  Result: "Open Ai"        → 'openai',
--                                            "create_image"   → 'createimage',
--                                            "openai-create-image" slug-style
--                                              full text     → 'openaicreateimage'.
--
--   The query side derives the same compact form and OR-s it into the search
--   so users typing the canonical brand match without losing English-stemmed
--   matches on the rest of the text.
--
-- The trigger is defined in V12; this migration replaces the function body
-- and forces a reindex of every row by bumping `updated_at`.
--
-- Idempotent.
-- ---------------------------------------------------------------------------

SET search_path TO catalog, public;

CREATE OR REPLACE FUNCTION catalog.update_lexical_tsvectors()
RETURNS TRIGGER AS $$
DECLARE
    provider_text          TEXT;
    keywords_primary_text  TEXT;
    keywords_synonyms_text TEXT;
    keywords_params_text   TEXT;
    use_cases_text         TEXT;
    summary_text           TEXT;
    tool_name_text         TEXT;
    -- Compact (brand-normalized) bag: every separator stripped, lowercased.
    -- Indexed with the 'simple' parser so it survives stemming and matches
    -- canonical brand names typed as one word ("openai", "googlecloud", "dalle").
    compact_text           TEXT;
BEGIN
    provider_text          := replace(coalesce(NEW.provider, ''), '/', ' ');
    tool_name_text         := coalesce(NEW.tool_name, '');
    keywords_primary_text  := coalesce(array_to_string(NEW.keywords_primary, ' '), '');
    keywords_synonyms_text := coalesce(array_to_string(NEW.keywords_synonyms, ' '), '');
    keywords_params_text   := coalesce(array_to_string(NEW.keywords_params, ' '), '');
    use_cases_text         := coalesce(array_to_string(NEW.use_cases, ' '), '');
    summary_text           := coalesce(NEW.summary_extended, NEW.summary, '');

    -- Build per-field compact tokens (one token per field, each is the field
    -- with all whitespace/dash/underscore removed). We also keep a
    -- "slug-style" full token combining provider+resource+action - that is
    -- what reproduces the openai-create-image slug seen by users.
    compact_text :=
        regexp_replace(lower(provider_text),  '[\s\-_/]+', '', 'g') || ' ' ||
        regexp_replace(lower(tool_name_text), '[\s\-_/]+', '', 'g') || ' ' ||
        regexp_replace(lower(coalesce(NEW.resource, '')), '[\s\-_/]+', '', 'g') || ' ' ||
        regexp_replace(lower(coalesce(NEW.action,   '')), '[\s\-_/]+', '', 'g') || ' ' ||
        regexp_replace(
            lower(provider_text || coalesce(NEW.resource, '') || coalesce(NEW.action, '')),
            '[\s\-_/]+', '', 'g'
        );

    NEW.tsv_combined :=
        setweight(to_tsvector('english', coalesce(NEW.subcategory, '')), 'A') ||
        setweight(to_tsvector('english', tool_name_text), 'A') ||
        setweight(to_tsvector('english', keywords_primary_text), 'A') ||
        setweight(to_tsvector('english', keywords_synonyms_text), 'A') ||
        -- NEW: brand-normalized tokens, weight A (top tier).
        -- Uses 'simple' parser so "openai" stays as 'openai' (not stemmed).
        setweight(to_tsvector('simple',  compact_text), 'A') ||
        setweight(to_tsvector('english', use_cases_text), 'B') ||
        setweight(to_tsvector('english', coalesce(NEW.resource, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(NEW.action, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(NEW.category, '')), 'B') ||
        setweight(to_tsvector('english', summary_text), 'C') ||
        setweight(to_tsvector('english', provider_text), 'C') ||
        setweight(to_tsvector('english', coalesce(NEW.endpoint, '')), 'D') ||
        setweight(to_tsvector('english', keywords_params_text), 'D');

    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Force reindex of every row so existing data picks up the new compact tokens.
-- The trigger fires on UPDATE and rewrites tsv_combined.
UPDATE catalog.lexical_search_index SET updated_at = NOW();

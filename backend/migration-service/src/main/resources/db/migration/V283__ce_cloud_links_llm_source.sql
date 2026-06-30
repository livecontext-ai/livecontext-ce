-- CE runtime LLM source selection.
-- BYOK preserves existing CE installs. CLOUD routes only the model-completion
-- hop to LiveContext Cloud while tool execution and observability stay local.

ALTER TABLE publication.ce_cloud_links
    ADD COLUMN IF NOT EXISTS llm_source VARCHAR(16) NOT NULL DEFAULT 'BYOK';

ALTER TABLE publication.ce_cloud_links
    DROP CONSTRAINT IF EXISTS ce_cloud_links_llm_source_check;

ALTER TABLE publication.ce_cloud_links
    ADD CONSTRAINT ce_cloud_links_llm_source_check
    CHECK (llm_source IN ('CLOUD', 'BYOK'));

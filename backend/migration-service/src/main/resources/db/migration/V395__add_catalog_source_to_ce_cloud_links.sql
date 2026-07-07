-- CE source for third-party catalog API platform credentials.
-- BYOK preserves existing CE installs (tools execute locally with the install's
-- own credentials). CLOUD relays the tool execution to the linked LiveContext
-- account, which injects its platform credentials and bills markup there.
-- Exact mirror of llm_source (V283); the two toggles are independent.

ALTER TABLE publication.ce_cloud_links
    ADD COLUMN IF NOT EXISTS catalog_source VARCHAR(16) NOT NULL DEFAULT 'BYOK';

ALTER TABLE publication.ce_cloud_links
    DROP CONSTRAINT IF EXISTS ce_cloud_links_catalog_source_check;

ALTER TABLE publication.ce_cloud_links
    ADD CONSTRAINT ce_cloud_links_catalog_source_check
    CHECK (catalog_source IN ('CLOUD', 'BYOK'));

-- Add UNIQUE constraint on (api_tool_id, credential_name) to back ON CONFLICT upsert
-- in CatalogSeedCredentialService.linkToolCredential().
-- First deduplicate any existing rows (keep one per group using ctid tie-breaker).

DELETE FROM catalog.tool_credentials tc1
USING catalog.tool_credentials tc2
WHERE tc1.api_tool_id = tc2.api_tool_id
  AND tc1.credential_name = tc2.credential_name
  AND (tc1.updated_at < tc2.updated_at
       OR (tc1.updated_at = tc2.updated_at AND tc1.ctid < tc2.ctid));

ALTER TABLE catalog.tool_credentials
    ADD CONSTRAINT uq_tool_credentials_tool_cred_name UNIQUE (api_tool_id, credential_name);

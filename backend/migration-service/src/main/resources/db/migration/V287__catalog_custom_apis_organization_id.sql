-- Add workspace scope to user-created custom APIs.
-- Third-party imported APIs remain global with organization_id NULL.

ALTER TABLE catalog.apis
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

UPDATE catalog.apis a
   SET organization_id = (
         SELECT om.organization_id::text
           FROM auth.organization_member om
          WHERE om.user_id = a.created_by::bigint
            AND om.is_default = true
          LIMIT 1
       )
 WHERE a.source = 'custom'
   AND a.organization_id IS NULL
   AND a.created_by ~ '^[0-9]+$'
   AND EXISTS (
         SELECT 1
           FROM auth.organization_member om
          WHERE om.user_id = a.created_by::bigint
            AND om.is_default = true
       );

CREATE INDEX IF NOT EXISTS idx_apis_custom_org_active_created
    ON catalog.apis (organization_id, created_at DESC)
    WHERE source = 'custom' AND is_active = true;

COMMENT ON COLUMN catalog.apis.organization_id IS
    'Workspace that owns a custom API. NULL is reserved for imported global APIs '
    'and legacy personal custom APIs that could not be backfilled.';

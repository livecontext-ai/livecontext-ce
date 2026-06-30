SET search_path TO publication;

ALTER TABLE publication_receipts
    ADD COLUMN IF NOT EXISTS id UUID;

UPDATE publication_receipts
   SET id = gen_random_uuid()
 WHERE id IS NULL;

ALTER TABLE publication_receipts
    ALTER COLUMN id SET DEFAULT gen_random_uuid(),
    ALTER COLUMN id SET NOT NULL;

ALTER TABLE publication_receipts
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

ALTER TABLE publication_receipts
    DROP CONSTRAINT IF EXISTS publication_receipts_pkey;

ALTER TABLE publication_receipts
    ADD CONSTRAINT publication_receipts_pkey PRIMARY KEY (id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_publication_receipts_personal_scope
    ON publication_receipts (tenant_id, publication_id)
    WHERE organization_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_publication_receipts_org_scope
    ON publication_receipts (organization_id, publication_id)
    WHERE organization_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_publication_receipts_personal_acquired
    ON publication_receipts (tenant_id, acquired_at DESC)
    WHERE organization_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_publication_receipts_org_acquired
    ON publication_receipts (organization_id, acquired_at DESC)
    WHERE organization_id IS NOT NULL;

COMMENT ON COLUMN publication_receipts.organization_id IS
    'Scope tag for CE acquisitions. NULL rows are personal receipts keyed by tenant_id; non-NULL rows are organization receipts shared by the team scope.';

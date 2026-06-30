-- V278: Repair image_screening_decisions for databases where V274 is recorded
-- in Flyway history but the table is missing.
--
-- Auto-screening is best-effort, but a missing table still marks the enclosing
-- publication transaction rollback-only after the repository insert fails.
-- Keep this idempotent so repaired and correctly migrated databases both pass.

CREATE TABLE IF NOT EXISTS publication.image_screening_decisions (
    id                       BIGSERIAL PRIMARY KEY,
    publication_id           UUID         NOT NULL
                                          REFERENCES publication.workflow_publications(id)
                                          ON DELETE RESTRICT,
    snapshot_version         INTEGER      NOT NULL DEFAULT 0,
    decided_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    decided_by               VARCHAR(255) NOT NULL,
    organization_id          VARCHAR(255) NULL,
    image_url_hash           VARCHAR(64)  NOT NULL,
    image_url_host           VARCHAR(255) NOT NULL,
    image_source             VARCHAR(10)  NOT NULL
                                          CHECK (image_source IN ('HTML','CSS','JS')),
    decision                 VARCHAR(20)  NOT NULL
                                          CHECK (decision IN
                                              ('REPLACED_STOCK',
                                               'REPLACED_AI',
                                               'KEPT_ATTESTED',
                                               'KEPT_OWN_UPLOAD',
                                               'SKIPPED')),
    replacement_ref          VARCHAR(500) NULL,
    attestation_text         TEXT         NULL,
    attestation_text_version VARCHAR(40)  NULL,
    user_agent               TEXT         NULL,
    ip_hash                  VARCHAR(64)  NULL,
    CONSTRAINT uq_image_decision UNIQUE
        (publication_id, snapshot_version, image_url_hash)
);

CREATE INDEX IF NOT EXISTS idx_isd_publication
    ON publication.image_screening_decisions (publication_id, decided_at DESC);

CREATE INDEX IF NOT EXISTS idx_isd_organization
    ON publication.image_screening_decisions (organization_id, decided_at DESC)
    WHERE organization_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_isd_host
    ON publication.image_screening_decisions (image_url_host);

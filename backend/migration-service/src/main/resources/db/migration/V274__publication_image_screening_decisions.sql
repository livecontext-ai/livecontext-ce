-- V274: image_screening_decisions audit table for Wave 2a part 2.
--
-- Legal evidence trail of every decision a publisher made on a flagged
-- image during the pre-publish screening flow. Required for the LCEN /
-- DSA hosting-provider safe harbor: when a takedown notice lands at
-- abuse@livecontext.ai for a Template containing an infringing image,
-- this table answers "did the publisher acknowledge that image and
-- attest they had rights, or did they replace it with stock?".
--
-- Schema choices (per the v3 audit on Wave 2a):
--   * image_url_hash = SHA-256 hex of the original URL (not the URL
--     itself) - GDPR Art. 32 pseudonymization. The URL may carry signed
--     S3 tokens or session ids; storing the raw URL would amplify PII
--     exposure. The host is kept in cleartext for legal cross-reference
--     ("is this from a known piracy host?").
--   * ON DELETE RESTRICT on publication_id - deleting a publication MUST
--     NOT destroy the safe-harbor evidence; an admin-gated archive flow
--     comes later if needed.
--   * decision enum kept as VARCHAR(20) + CHECK (no PG enum) so future
--     additions don't need DDL - matches the V33 convention used for
--     workflow_publications.display_mode.
--   * ip_hash optional - when present, SHA-256 of the request remote
--     IP. Pseudonymized per GDPR; not all callers will populate it.
--   * attestation_text_version pins WHICH version of the §8 CGU clause
--     the publisher attested against. Critical if §8 wording evolves
--     and a takedown notice arrives years later - we must prove the
--     publisher saw the exact wording in force at decision time.
--
-- Retention: 15 months (12 LCEN minimum + 90-day litigation hold buffer).
-- Cron purge lands in a follow-up; the table is append-only by design.

CREATE TABLE publication.image_screening_decisions (
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

CREATE INDEX idx_isd_publication
    ON publication.image_screening_decisions (publication_id, decided_at DESC);

CREATE INDEX idx_isd_organization
    ON publication.image_screening_decisions (organization_id, decided_at DESC)
    WHERE organization_id IS NOT NULL;

CREATE INDEX idx_isd_host
    ON publication.image_screening_decisions (image_url_host);

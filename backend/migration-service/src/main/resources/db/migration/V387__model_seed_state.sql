-- CE-side model-catalog SEED bookkeeping.
-- Single-row (PK=1) marker of the last curated seed VERSION applied at boot by
-- ModelSeedBootstrapService (model-catalog/models.json). The seed re-applies
-- ONLY when the shipped models.json `version` is greater than applied_version,
-- so a fresh release refreshes the catalog exactly once (no per-boot churn) and
-- an unchanged version is a no-op. Distinct from catalog_bundle_sync_status
-- (V114), which tracks the signed cloud BUNDLE fetch/apply; this row tracks the
-- code-shipped seed, which needs no cloud link.
SET search_path TO agent;

CREATE TABLE IF NOT EXISTS model_seed_state (
    id               SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    -- Highest models.json `version` already merged into model_config_overrides.
    -- NULL before the first successful seed apply.
    applied_version  BIGINT,
    applied_at       TIMESTAMPTZ
);

-- Seed the single row so UPDATE semantics hold without an upsert.
INSERT INTO model_seed_state (id) VALUES (1) ON CONFLICT DO NOTHING;

COMMENT ON TABLE model_seed_state
    IS 'CE-side marker of the last curated model-catalog seed version applied at boot. Single row (id=1).';

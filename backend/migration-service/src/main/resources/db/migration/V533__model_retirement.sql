-- V533: retire a model for good.
--
-- `enabled = false` was never permanent. A catalog feed sync, the CE seed, a signed bundle or a
-- migration could bring a model back: a deleted row is re-inserted by the next sync that still sees
-- it, `deprecated_at` is cleared by every merge that meets the model again, and several bridge
-- migrations upsert `enabled = TRUE`. So the catalog filled up with hundreds of dead models, and the
-- CE relay (which only refused a model with no row at all) kept running disabled ones.
--
-- A RETIRED row (`retired_at IS NOT NULL`) is out of the catalog until an admin restores it:
--   * the merge (sync, seed, bundle) skips it entirely (CatalogMergeService);
--   * it is never shipped in a CE bundle or seed (CatalogBundleService);
--   * the relay refuses it (CloudLlmRelayController);
--   * the two triggers below are the backstop for every other writer, SQL migrations included:
--     a retired row always reads enabled = false / bundle_enabled = false, and it cannot be
--     deleted (a DELETE of a retired row is skipped, so the tombstone survives an admin reset
--     and a migration that deletes rows).
-- The row itself is the tombstone, so the admin "Retired" list keeps the model's name and history.

ALTER TABLE agent.model_config_overrides
    ADD COLUMN IF NOT EXISTS retired_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS retired_by VARCHAR(100);

COMMENT ON COLUMN agent.model_config_overrides.retired_at IS
    'V533: set when an admin retires the model. A retired row is never re-enabled, re-synced, shipped to CE, relayed or deleted until an admin restores it (retired_at = NULL).';
COMMENT ON COLUMN agent.model_config_overrides.retired_by IS
    'V533: user id of the admin who retired the model (display only).';

CREATE INDEX IF NOT EXISTS idx_model_config_overrides_retired
    ON agent.model_config_overrides (retired_at)
    WHERE retired_at IS NOT NULL;

CREATE OR REPLACE FUNCTION agent.keep_retired_model_disabled()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.retired_at IS NOT NULL THEN
        NEW.enabled := FALSE;
        NEW.bundle_enabled := FALSE;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_keep_retired_model_disabled ON agent.model_config_overrides;
CREATE TRIGGER trg_keep_retired_model_disabled
    BEFORE INSERT OR UPDATE ON agent.model_config_overrides
    FOR EACH ROW EXECUTE FUNCTION agent.keep_retired_model_disabled();

CREATE OR REPLACE FUNCTION agent.keep_retired_model_row()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.retired_at IS NOT NULL THEN
        -- Skip the delete: the row is the tombstone. Restore the model first to delete it.
        RETURN NULL;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_keep_retired_model_row ON agent.model_config_overrides;
CREATE TRIGGER trg_keep_retired_model_row
    BEFORE DELETE ON agent.model_config_overrides
    FOR EACH ROW EXECUTE FUNCTION agent.keep_retired_model_row();

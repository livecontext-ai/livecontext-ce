-- ---------------------------------------------------------------------------
-- V156: Per-category ranking & enable for model catalog
--
-- Extends the global single-axis (ranking, enabled) on model_config_overrides
-- with a sidecar table that stores per-category overrides. Initial categories:
--   - 'chat'              - main agent / chat picker
--   - 'browser_agent'     - models proposed by web_search(action='help_models')
--   - 'image_generation'  - image-gen tool (mode='image' rows; seeded in V157)
--
-- Forward-extensible: the category column accepts any lowercase snake_case
-- identifier so future categories (e.g. 'video_generation', 'file_processing',
-- 'embedding') can be introduced without a schema migration. The values just
-- need to be added to the application-side enum + admin UI tabs.
--
-- Resolution semantics (read in ModelCatalogService.getModelsForCategory):
--   - row present in model_category_settings(category, model_config_id) → use rank/enabled from sidecar
--   - row absent → fall back to model_config_overrides.ranking / .enabled (today's global behaviour)
--
-- The audit chain in model_field_edits is extended to log category-scoped
-- edits under field_name='category:<cat>.<field>'. The HMAC trigger on
-- model_field_edits itself stays untouched.
-- ---------------------------------------------------------------------------

SET search_path = agent, public;

CREATE TABLE IF NOT EXISTS model_category_settings (
    model_config_id BIGINT      NOT NULL REFERENCES model_config_overrides(id) ON DELETE CASCADE,
    category        VARCHAR(32) NOT NULL,
    rank            INTEGER,
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (model_config_id, category),
    -- Permissive shape constraint only: any lowercase snake_case identifier.
    -- Adding a new category (video_generation, file_processing, …) is a
    -- pure code change - no migration needed.
    CONSTRAINT model_category_settings_category_shape_chk
        CHECK (category ~ '^[a-z][a-z0-9_]*$' AND length(category) <= 32)
);

-- Hot path: ModelCatalogService.getModelsForCategory(category) sorts by rank.
CREATE INDEX IF NOT EXISTS idx_model_category_settings_category_rank
    ON model_category_settings (category, rank);

-- Backfill the 'chat' category from the existing global ranking/enabled so
-- the current behaviour is preserved (no row → fall back; with row → same
-- values as today). Rows whose mode is non-chat (image, embedding, audio)
-- are NOT backfilled into 'chat'; image-gen rows get their seed in V157.
INSERT INTO model_category_settings (model_config_id, category, rank, enabled)
SELECT id,
       'chat',
       ranking,
       COALESCE(enabled, TRUE)
FROM   model_config_overrides
WHERE  (mode IS NULL OR mode = 'chat')
  AND  deprecated_at IS NULL
ON CONFLICT (model_config_id, category) DO NOTHING;

-- Backfill 'browser_agent' as a mirror of 'chat': same models, same initial
-- ranking + enabled. Admins can then re-rank or disable independently from
-- /settings/ai-providers without touching the chat list. Bridges are
-- preserved here too - recalculateDefaults() filters bridges out of
-- defaultDirect* downstream (browser_agent can't use full-session bridges
-- for atomic per-step completions), but the row itself stays so the admin
-- decision surface is symmetric across categories.
INSERT INTO model_category_settings (model_config_id, category, rank, enabled)
SELECT id,
       'browser_agent',
       ranking,
       COALESCE(enabled, TRUE)
FROM   model_config_overrides
WHERE  (mode IS NULL OR mode = 'chat')
  AND  deprecated_at IS NULL
ON CONFLICT (model_config_id, category) DO NOTHING;

-- Auto-update updated_at on each UPDATE.
CREATE OR REPLACE FUNCTION touch_model_category_settings_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_touch_model_category_settings_updated_at ON model_category_settings;
CREATE TRIGGER trg_touch_model_category_settings_updated_at
    BEFORE UPDATE ON model_category_settings
    FOR EACH ROW
    EXECUTE FUNCTION touch_model_category_settings_updated_at();

-- ---------------------------------------------------------------------------
-- Audit hook: route INSERT/UPDATE/DELETE on category settings into the
-- existing model_field_edits chain so the HMAC hash-chain stays whole and
-- existing verifiers (agent.verify_model_audit_chain) keep working.
--
-- Field-name convention: 'category:<cat>.rank' / 'category:<cat>.enabled'.
-- Mirrors the no-op-when-migrating guard on log_model_field_changes
-- (V109:270) so Flyway connections without app.model_audit_hmac_key set
-- don't fail the migration.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION log_model_category_changes() RETURNS TRIGGER AS $$
DECLARE
    editor   TEXT := current_setting('app.current_user_id', true);
    esrc     TEXT := COALESCE(current_setting('app.model_edit_source', true), 'admin');
    hmackey  TEXT := current_setting('app.model_audit_hmac_key', true);
    cat      TEXT := COALESCE(NEW.category, OLD.category);
    cfg_id   BIGINT := COALESCE(NEW.model_config_id, OLD.model_config_id);
BEGIN
    IF hmackey IS NULL OR length(hmackey) = 0 THEN
        RETURN COALESCE(NEW, OLD);
    END IF;

    IF TG_OP = 'INSERT' THEN
        PERFORM log_field(cfg_id, 'category:' || cat || '.rank',
                          NULL, NEW.rank::text, esrc, editor);
        PERFORM log_field(cfg_id, 'category:' || cat || '.enabled',
                          NULL, NEW.enabled::text, esrc, editor);
    ELSIF TG_OP = 'UPDATE' THEN
        PERFORM log_field(cfg_id, 'category:' || cat || '.rank',
                          OLD.rank::text, NEW.rank::text, esrc, editor);
        PERFORM log_field(cfg_id, 'category:' || cat || '.enabled',
                          OLD.enabled::text, NEW.enabled::text, esrc, editor);
    ELSIF TG_OP = 'DELETE' THEN
        PERFORM log_field(cfg_id, 'category:' || cat || '.rank',
                          OLD.rank::text, NULL, esrc, editor);
        PERFORM log_field(cfg_id, 'category:' || cat || '.enabled',
                          OLD.enabled::text, NULL, esrc, editor);
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_model_category_settings ON model_category_settings;
CREATE TRIGGER trg_audit_model_category_settings
    AFTER INSERT OR UPDATE OR DELETE ON model_category_settings
    FOR EACH ROW
    EXECUTE FUNCTION log_model_category_changes();

COMMENT ON TABLE model_category_settings IS
    'Per-category (rank, enabled) overrides over model_config_overrides. Absent row = fall back to global ranking/enabled. Initial categories: chat | browser_agent | image_generation. Forward-extensible: any lowercase snake_case identifier (e.g. video_generation, file_processing) is accepted by the shape CHECK without a schema change.';

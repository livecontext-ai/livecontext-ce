-- Model-catalog v4 infrastructure.
-- Scope: promotes model_config_overrides from admin-override to full catalog source-of-truth,
--        adds billing_settings, catalog_bundles, catalog_import_errors, model_field_edits,
--        and the pricing_outbox used by cross-service TX-safe pricing sync.
--
-- Units contract (read the project docs):
--   price_input / price_output  = USD per 1M tokens (provider list price)
--   credits_input / credits_output = derived: price × markup × 10 (trigger)
--   1 credit = $0.001

SET lock_timeout = '10s';
SET statement_timeout = '180s';

-- Required for hmac() used by the audit hash-chain trigger.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

SET search_path TO agent;

-- ---------------------------------------------------------------------------
-- 1. Platform billing knob
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS billing_settings (
    id         INT PRIMARY KEY DEFAULT 1,
    markup     NUMERIC(5,2) NOT NULL DEFAULT 1.20,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT billing_settings_singleton CHECK (id = 1),
    CONSTRAINT billing_settings_markup_positive CHECK (markup > 0)
);
INSERT INTO billing_settings (id, markup) VALUES (1, 1.20)
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Extend model_config_overrides to full catalog row
-- ---------------------------------------------------------------------------
ALTER TABLE model_config_overrides
    ADD COLUMN IF NOT EXISTS description          TEXT,
    ADD COLUMN IF NOT EXISTS credits_input        NUMERIC(12,4),
    ADD COLUMN IF NOT EXISTS credits_output       NUMERIC(12,4),
    ADD COLUMN IF NOT EXISTS source               VARCHAR(20) NOT NULL DEFAULT 'manual',
    ADD COLUMN IF NOT EXISTS source_model_id      VARCHAR(200),
    ADD COLUMN IF NOT EXISTS canonical_id         VARCHAR(150),
    ADD COLUMN IF NOT EXISTS user_modified_fields TEXT[]      NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS bundle_version       BIGINT,
    ADD COLUMN IF NOT EXISTS last_synced_at       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_admin_edit_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS context_window       INTEGER,
    ADD COLUMN IF NOT EXISTS max_output_tokens    INTEGER,
    ADD COLUMN IF NOT EXISTS supports_tools       BOOLEAN,
    ADD COLUMN IF NOT EXISTS supports_vision      BOOLEAN,
    ADD COLUMN IF NOT EXISTS modalities           JSONB,
    ADD COLUMN IF NOT EXISTS deprecated_at        TIMESTAMPTZ;

ALTER TABLE model_config_overrides
    ALTER COLUMN display_name SET NOT NULL;

ALTER TABLE model_config_overrides
    DROP CONSTRAINT IF EXISTS model_config_overrides_source_check,
    ADD  CONSTRAINT model_config_overrides_source_check
         CHECK (source IN ('manual','curated','openrouter','litellm','bundle'));

CREATE INDEX IF NOT EXISTS idx_model_config_source         ON model_config_overrides(source);
CREATE INDEX IF NOT EXISTS idx_model_config_canonical_id   ON model_config_overrides(canonical_id);
CREATE INDEX IF NOT EXISTS idx_model_config_bundle_version ON model_config_overrides(bundle_version);
CREATE INDEX IF NOT EXISTS idx_model_config_deprecated     ON model_config_overrides(deprecated_at)
    WHERE deprecated_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. Trigger: derive credits_input / credits_output from price × markup × 10
--    NULL price → NULL credits (unknown model, billing code must fall back).
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION derive_model_credits() RETURNS TRIGGER AS $$
DECLARE
    m NUMERIC(5,2);
BEGIN
    SELECT markup INTO m FROM billing_settings WHERE id = 1;
    IF m IS NULL THEN m := 1.20; END IF;

    NEW.credits_input  := CASE WHEN NEW.price_input  IS NULL THEN NULL
                               ELSE ROUND(NEW.price_input  * m * 10, 4) END;
    NEW.credits_output := CASE WHEN NEW.price_output IS NULL THEN NULL
                               ELSE ROUND(NEW.price_output * m * 10, 4) END;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_derive_credits ON model_config_overrides;
CREATE TRIGGER trg_derive_credits
    BEFORE INSERT OR UPDATE OF price_input, price_output
    ON model_config_overrides
    FOR EACH ROW EXECUTE FUNCTION derive_model_credits();

-- ---------------------------------------------------------------------------
-- 4. Bulk recompute of credits when platform markup changes.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION recompute_all_model_credits() RETURNS TRIGGER AS $$
BEGIN
    IF OLD.markup IS DISTINCT FROM NEW.markup THEN
        UPDATE model_config_overrides
           SET credits_input  = CASE WHEN price_input  IS NULL THEN NULL
                                     ELSE ROUND(price_input  * NEW.markup * 10, 4) END,
               credits_output = CASE WHEN price_output IS NULL THEN NULL
                                     ELSE ROUND(price_output * NEW.markup * 10, 4) END;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_recompute_credits_on_markup ON billing_settings;
CREATE TRIGGER trg_recompute_credits_on_markup
    AFTER UPDATE OF markup ON billing_settings
    FOR EACH ROW EXECUTE FUNCTION recompute_all_model_credits();

-- ---------------------------------------------------------------------------
-- 5. Catalog bundles (cloud → CE distribution)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS catalog_bundles (
    id              BIGSERIAL PRIMARY KEY,
    version         BIGINT      NOT NULL UNIQUE,
    schema_version  INT         NOT NULL DEFAULT 1,
    checksum        CHAR(64)    NOT NULL,            -- SHA-256 hex of canonical payload
    signature       TEXT        NOT NULL,            -- Ed25519 base64
    signing_key_id  VARCHAR(50) NOT NULL,
    issuer          VARCHAR(100) NOT NULL,
    model_count     INT         NOT NULL,
    raw_bytes_size  INT         NOT NULL,
    source_url      TEXT,
    imported_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    activated_at    TIMESTAMPTZ,
    is_active       BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_catalog_bundles_one_active
    ON catalog_bundles ((1)) WHERE is_active = TRUE;

CREATE TABLE IF NOT EXISTS catalog_import_errors (
    id           BIGSERIAL PRIMARY KEY,
    bundle_id    BIGINT REFERENCES catalog_bundles(id) ON DELETE CASCADE,
    provider     VARCHAR(50),
    model_id     VARCHAR(150),
    error_code   VARCHAR(50)  NOT NULL,
    error_detail TEXT,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_catalog_import_errors_bundle ON catalog_import_errors(bundle_id);
CREATE INDEX IF NOT EXISTS idx_catalog_import_errors_pm     ON catalog_import_errors(provider, model_id);

-- ---------------------------------------------------------------------------
-- 6. Field-level edit audit (hash-chain signed - see V109b)
-- ---------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS model_field_edit_chain_seq;

CREATE TABLE IF NOT EXISTS model_field_edits (
    id                 BIGSERIAL PRIMARY KEY,
    chain_seq          BIGINT      NOT NULL UNIQUE,
    model_config_id    BIGINT      NOT NULL REFERENCES model_config_overrides(id) ON DELETE CASCADE,
    field_name         VARCHAR(80) NOT NULL,
    old_value          TEXT,
    new_value          TEXT,
    edit_source        VARCHAR(20) NOT NULL,           -- 'admin' | 'bundle' | 'sync'
    edited_by_user_id  VARCHAR(100),
    edited_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    prev_row_hmac      BYTEA,
    row_hmac           BYTEA       NOT NULL,
    CONSTRAINT model_field_edits_source_check
        CHECK (edit_source IN ('admin','bundle','sync'))
);
CREATE INDEX IF NOT EXISTS idx_model_field_edits_config ON model_field_edits(model_config_id);
CREATE INDEX IF NOT EXISTS idx_model_field_edits_at     ON model_field_edits(edited_at DESC);

-- HMAC hash-chain trigger. Uses session var `app.model_audit_hmac_key` set by
-- agent-service Hikari ConnectionInitSql. Advisory lock serializes the
-- chain_seq → prev_row_hmac lookup under concurrent admin edits.
CREATE OR REPLACE FUNCTION compute_model_field_edit_hmac() RETURNS TRIGGER AS $$
DECLARE
    secret  BYTEA;
    prev    BYTEA;
    payload BYTEA;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('model_field_edits_chain'));

    secret := convert_to(current_setting('app.model_audit_hmac_key', true), 'UTF8');
    IF secret IS NULL OR length(secret) = 0 THEN
        RAISE EXCEPTION 'app.model_audit_hmac_key not set - audit rows cannot be written';
    END IF;

    NEW.chain_seq := nextval('model_field_edit_chain_seq');

    -- "Latest committed row" - not `chain_seq - 1`. The sequence is
    -- non-transactional, so a rolled-back INSERT leaves a hole. Anchoring on
    -- the most recent committed predecessor keeps the chain unbroken across
    -- sequence gaps. The advisory xact lock above serializes concurrent
    -- INSERTs so this read is deterministic within a transaction.
    SELECT row_hmac INTO prev
      FROM model_field_edits
     ORDER BY chain_seq DESC
     LIMIT 1
       FOR UPDATE;
    NEW.prev_row_hmac := prev;

    payload := (
        NEW.chain_seq::text || '|' ||
        NEW.model_config_id::text || '|' ||
        NEW.field_name || '|' ||
        COALESCE(NEW.old_value, '') || '|' ||
        COALESCE(NEW.new_value, '') || '|' ||
        NEW.edit_source || '|' ||
        NEW.edited_at::text || '|' ||
        COALESCE(NEW.edited_by_user_id, '') || '|' ||
        COALESCE(encode(prev, 'hex'), 'GENESIS')
    )::BYTEA;

    NEW.row_hmac := hmac(payload, secret, 'sha256');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_model_field_edits_hmac ON model_field_edits;
CREATE TRIGGER trg_model_field_edits_hmac
    BEFORE INSERT ON model_field_edits
    FOR EACH ROW EXECUTE FUNCTION compute_model_field_edit_hmac();

-- ---------------------------------------------------------------------------
-- 7. Pricing outbox (TX-safe cross-service sync to auth.model_pricing)
--    Written in the same local TX as model_config_overrides upsert.
--    Publisher drains with SKIP LOCKED + ShedLock.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pricing_outbox (
    id               BIGSERIAL PRIMARY KEY,
    aggregate_type   VARCHAR(30)  NOT NULL,   -- 'model_pricing_sync' | 'model_pricing_bulk'
    idempotency_key  VARCHAR(200) NOT NULL,
    payload          JSONB        NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at     TIMESTAMPTZ,
    attempts         INT          NOT NULL DEFAULT 0,
    last_error       TEXT,
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pricing_outbox_aggregate_check
        CHECK (aggregate_type IN ('model_pricing_sync','model_pricing_bulk'))
);
CREATE INDEX IF NOT EXISTS idx_pricing_outbox_pending
    ON pricing_outbox (next_attempt_at)
    WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_pricing_outbox_published
    ON pricing_outbox (published_at)
    WHERE published_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 8. Audit trigger on model_config_overrides updates
--    WHEN (OLD.* IS DISTINCT FROM NEW.*) - skip no-op updates.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION log_field(cfg_id BIGINT, fname TEXT, oldv TEXT, newv TEXT,
                                      esrc TEXT, editor TEXT) RETURNS VOID AS $$
BEGIN
    IF oldv IS NOT DISTINCT FROM newv THEN RETURN; END IF;
    INSERT INTO model_field_edits(model_config_id, field_name, old_value, new_value,
                                  edit_source, edited_by_user_id)
    VALUES (cfg_id, fname, oldv, newv, esrc, NULLIF(editor, ''));
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION log_model_field_changes() RETURNS TRIGGER AS $$
DECLARE
    editor TEXT := current_setting('app.current_user_id', true);
    esrc   TEXT := COALESCE(current_setting('app.model_edit_source', true), 'admin');
    hmackey TEXT := current_setting('app.model_audit_hmac_key', true);
BEGIN
    -- Audit trigger is a no-op when the HMAC session var is not set
    -- (e.g. migration-service Flyway connection). The HMAC-chain trigger on
    -- model_field_edits itself would raise anyway, but no-oping here avoids
    -- blocking Flyway migrations that UPDATE model_config_overrides rows.
    IF hmackey IS NULL OR length(hmackey) = 0 THEN
        RETURN NEW;
    END IF;

    PERFORM log_field(NEW.id, 'display_name',      OLD.display_name,                NEW.display_name,                esrc, editor);
    PERFORM log_field(NEW.id, 'tier',              OLD.tier,                        NEW.tier,                        esrc, editor);
    PERFORM log_field(NEW.id, 'description',       OLD.description,                 NEW.description,                 esrc, editor);
    PERFORM log_field(NEW.id, 'price_input',       OLD.price_input::text,           NEW.price_input::text,           esrc, editor);
    PERFORM log_field(NEW.id, 'price_output',      OLD.price_output::text,          NEW.price_output::text,          esrc, editor);
    PERFORM log_field(NEW.id, 'rate_limit_tpm',    OLD.rate_limit_tpm::text,        NEW.rate_limit_tpm::text,        esrc, editor);
    PERFORM log_field(NEW.id, 'rate_limit_rpm',    OLD.rate_limit_rpm::text,        NEW.rate_limit_rpm::text,        esrc, editor);
    PERFORM log_field(NEW.id, 'ranking',           OLD.ranking::text,               NEW.ranking::text,               esrc, editor);
    PERFORM log_field(NEW.id, 'enabled',           OLD.enabled::text,               NEW.enabled::text,               esrc, editor);
    PERFORM log_field(NEW.id, 'deprecated_at',     OLD.deprecated_at::text,         NEW.deprecated_at::text,         esrc, editor);
    PERFORM log_field(NEW.id, 'canonical_id',      OLD.canonical_id,                NEW.canonical_id,                esrc, editor);
    PERFORM log_field(NEW.id, 'context_window',    OLD.context_window::text,        NEW.context_window::text,        esrc, editor);
    PERFORM log_field(NEW.id, 'max_output_tokens', OLD.max_output_tokens::text,     NEW.max_output_tokens::text,     esrc, editor);
    PERFORM log_field(NEW.id, 'supports_tools',    OLD.supports_tools::text,        NEW.supports_tools::text,        esrc, editor);
    PERFORM log_field(NEW.id, 'supports_vision',   OLD.supports_vision::text,       NEW.supports_vision::text,       esrc, editor);
    PERFORM log_field(NEW.id, 'source',            OLD.source,                      NEW.source,                      esrc, editor);
    PERFORM log_field(NEW.id, 'bundle_version',    OLD.bundle_version::text,        NEW.bundle_version::text,        esrc, editor);
    -- modalities (JSONB) and user_modified_fields intentionally NOT audited
    -- (internal bookkeeping, noisy text-compare on JSONB).
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_model_field_edits ON model_config_overrides;
CREATE TRIGGER trg_audit_model_field_edits
    AFTER UPDATE ON model_config_overrides
    FOR EACH ROW
    WHEN (OLD.* IS DISTINCT FROM NEW.*)
    EXECUTE FUNCTION log_model_field_changes();

COMMENT ON TABLE  model_config_overrides IS 'Live model catalog (was override-only, now source of truth). See the project docs.';
COMMENT ON TABLE  catalog_bundles        IS 'Signed cloud→CE catalog distribution. Only one row may have is_active=true.';
COMMENT ON TABLE  pricing_outbox         IS 'TX-safe outbox for auth.model_pricing sync. Drained by ModelPricingOutboxPublisher.';
COMMENT ON TABLE  model_field_edits      IS 'HMAC hash-chain audit of admin model edits. Verify with agent.verify_model_audit_chain().';

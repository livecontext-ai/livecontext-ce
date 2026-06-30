-- Model configuration overrides
-- Allows admins to customize model rankings, tiers, pricing, and availability
-- from the UI without redeploying. NULL fields fall back to application.yml defaults.

SET search_path TO agent;

CREATE TABLE IF NOT EXISTS model_config_overrides (
    id           BIGSERIAL    PRIMARY KEY,
    provider     VARCHAR(50)  NOT NULL,
    model_id     VARCHAR(150) NOT NULL,
    enabled      BOOLEAN,
    display_name VARCHAR(255),
    tier         VARCHAR(20),
    ranking      INTEGER,
    recommended  BOOLEAN,
    price_input  NUMERIC(10,4),
    price_output NUMERIC(10,4),
    is_custom    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE(provider, model_id)
);

CREATE INDEX idx_model_config_provider ON model_config_overrides(provider);

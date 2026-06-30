-- Add per-model rate limit columns to model_config_overrides
-- NULL = inherit from provider-level config; 0 = disable; > 0 = override
ALTER TABLE agent.model_config_overrides
    ADD COLUMN rate_limit_tpm             INTEGER DEFAULT NULL,
    ADD COLUMN rate_limit_rpm             INTEGER DEFAULT NULL,
    ADD COLUMN rate_limit_tpm_per_tenant  INTEGER DEFAULT NULL,
    ADD COLUMN rate_limit_rpm_per_tenant  INTEGER DEFAULT NULL;

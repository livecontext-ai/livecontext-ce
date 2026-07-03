-- ============================================================================
-- V383: Workflow variables - reusable key/value store referenced in workflow
-- expressions as {{$vars.name}} (canonical) or {{vars:name}} (alias).
--
-- Scope semantics (mirrors the V362 workspace model):
--   organization_id NOT NULL -> workspace-shared: every member of the org reads
--                               it, non-VIEWER members write it.
--   organization_id NULL     -> personal scope of tenant_id.
--
-- Values are encrypted at rest by CredentialEncryptionService ("ENC:" prefix,
-- same key material as auth.platform_credentials). is_secret makes the value
-- WRITE-ONLY: masked to null in every listing (UI, public API, agent tool);
-- the runtime bundle still resolves the real value into workflow runs, so
-- resolved values can appear in run outputs like any other parameter
-- (run-output redaction is a possible future hardening, not promised here).
--
-- Per-plan creation cap max_workflow_variables (NULL = unlimited), enforced by
-- ResourceType.WORKFLOW_VARIABLE. CE-free bypasses resource limits entirely
-- (EntitlementGuard ceFreeNoopMode / AppEditionProvider), so no CE seed needed.
-- Adjust live with: UPDATE auth.plan SET max_workflow_variables = X WHERE code = 'PRO';
-- ============================================================================

SET search_path TO auth;

CREATE TABLE IF NOT EXISTS workflow_variables (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255),
    name            VARCHAR(64)  NOT NULL,
    value           TEXT         NOT NULL,
    value_type      VARCHAR(16)  NOT NULL DEFAULT 'STRING',
    is_secret       BOOLEAN      NOT NULL DEFAULT FALSE,
    description     TEXT,
    created_by      VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One name per scope: workspace rows are unique per organization (shared by all
-- members), personal rows are unique per tenant.
CREATE UNIQUE INDEX IF NOT EXISTS ux_workflow_variables_org_name
    ON workflow_variables (organization_id, name) WHERE organization_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_workflow_variables_personal_name
    ON workflow_variables (tenant_id, name) WHERE organization_id IS NULL;
CREATE INDEX IF NOT EXISTS ix_workflow_variables_tenant
    ON workflow_variables (tenant_id);

-- Per-plan cap (NULL = unlimited). FREE=3 per product decision 2026-07-03;
-- paid tiers scale like max_workflows (V58) - the cap is a monetization tier,
-- not an anti-abuse ceiling.
ALTER TABLE plan ADD COLUMN IF NOT EXISTS max_workflow_variables INTEGER;

UPDATE plan SET max_workflow_variables = 3   WHERE code = 'FREE';
UPDATE plan SET max_workflow_variables = 25  WHERE code = 'STARTER';
UPDATE plan SET max_workflow_variables = 100 WHERE code = 'PRO';
UPDATE plan SET max_workflow_variables = 500 WHERE code = 'TEAM';
-- ENTERPRISE_* and PAYG: leave NULL (= unlimited).
-- CREDIT_PACK / CREDIT_PACK_TEAM: leave NULL (addon plans, base plan governs).

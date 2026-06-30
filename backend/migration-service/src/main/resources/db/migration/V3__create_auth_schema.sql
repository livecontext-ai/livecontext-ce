-- ============================================================================
-- V3: Auth Schema (consolidated)
-- Creates auth schema with all tables in final state.
-- Includes: users, billing, credits, organizations, resource management.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS auth;
SET search_path TO auth;

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE,
    email VARCHAR(100),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    avatar_url VARCHAR(255),
    auth_provider VARCHAR(20) NOT NULL,
    provider_id VARCHAR(255) UNIQUE NOT NULL,
    enabled BOOLEAN DEFAULT TRUE,
    email_verified BOOLEAN DEFAULT FALSE,
    api_key VARCHAR(255) UNIQUE,
    api_key_hash VARCHAR(64),
    api_key_hint VARCHAR(10),
    api_key_created_at TIMESTAMP,
    user_version BIGINT NOT NULL DEFAULT 1,
    age TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP,
    CONSTRAINT chk_users_age_not_future CHECK (age IS NULL OR age <= CURRENT_TIMESTAMP)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email_unique ON users(email) WHERE email IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_users_provider_id ON users(provider_id);
CREATE INDEX IF NOT EXISTS idx_users_api_key_hash ON users(api_key_hash);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role)
);

CREATE TABLE IF NOT EXISTS user_onboarding (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    display_name VARCHAR(100) NOT NULL,
    profession VARCHAR(100),
    company_size VARCHAR(50),
    interests TEXT[] DEFAULT '{}',
    use_cases TEXT[] DEFAULT '{}',
    experience_level VARCHAR(50),
    onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE,
    onboarding_skipped BOOLEAN NOT NULL DEFAULT FALSE,
    onboarding_step INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    display_name_changed_at TIMESTAMP,
    CONSTRAINT uk_user_onboarding_user_id UNIQUE (user_id)
);

CREATE TABLE IF NOT EXISTS email_verification_codes (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    code VARCHAR(6) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    expires_at TIMESTAMPTZ NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_evc_user_id ON email_verification_codes(user_id);

CREATE TABLE IF NOT EXISTS plan (
    id BIGSERIAL PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    description TEXT,
    included_tool_credits BIGINT,
    included_llm_tokens BIGINT,
    included_storage_bytes BIGINT,
    payg_price_per_tool_credit BIGINT,
    payg_price_per_llm_token BIGINT,
    payg_price_per_gb_month BIGINT,
    max_members INTEGER DEFAULT 1
);

CREATE TABLE IF NOT EXISTS price (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL REFERENCES plan(id),
    cadence TEXT NOT NULL CHECK (cadence IN ('monthly','yearly','payg')),
    currency TEXT NOT NULL DEFAULT 'usd',
    amount_cents INT NOT NULL DEFAULT 0,
    provider TEXT NOT NULL DEFAULT 'stripe',
    provider_price_id TEXT UNIQUE,
    UNIQUE (plan_id, cadence)
);

CREATE TABLE IF NOT EXISTS billing_customer (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES users(id),
    provider TEXT NOT NULL DEFAULT 'stripe',
    provider_customer_id TEXT UNIQUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS subscription (
    id BIGSERIAL PRIMARY KEY,
    billing_customer_id BIGINT NOT NULL REFERENCES billing_customer(id),
    plan_id BIGINT NOT NULL REFERENCES plan(id),
    price_id BIGINT REFERENCES price(id),
    cadence TEXT NOT NULL CHECK (cadence IN ('monthly', 'yearly', 'payg')),
    provider TEXT NOT NULL DEFAULT 'stripe',
    provider_subscription_id TEXT UNIQUE,
    status TEXT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    credit_quantity INT NOT NULL DEFAULT 0,
    credit_price_id BIGINT REFERENCES price(id),
    current_period_start TIMESTAMPTZ NOT NULL,
    current_period_end TIMESTAMPTZ NOT NULL,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    remaining_tokens BIGINT DEFAULT 10000,
    remaining_requests BIGINT DEFAULT 1000,
    used_storage BIGINT DEFAULT 0,
    max_storage BIGINT DEFAULT 1073741824,
    remaining_credits DECIMAL(15,4) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS usage_cycle (
    id BIGSERIAL PRIMARY KEY,
    subscription_id BIGINT NOT NULL REFERENCES subscription(id),
    cycle_start TIMESTAMPTZ NOT NULL,
    cycle_end TIMESTAMPTZ NOT NULL,
    used_tool_credits BIGINT NOT NULL DEFAULT 0,
    used_llm_tokens BIGINT NOT NULL DEFAULT 0,
    used_storage_bytes BIGINT NOT NULL DEFAULT 0,
    UNIQUE (subscription_id, cycle_start, cycle_end)
);

CREATE TABLE IF NOT EXISTS billing_event (
    id BIGSERIAL PRIMARY KEY,
    provider TEXT NOT NULL,
    event_id TEXT NOT NULL UNIQUE,
    type TEXT NOT NULL,
    payload JSONB NOT NULL,
    received_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS organization (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    slug VARCHAR(100) NOT NULL UNIQUE,
    is_personal BOOLEAN NOT NULL DEFAULT FALSE,
    owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    avatar_url VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_org_owner ON organization(owner_id);

CREATE TABLE IF NOT EXISTS organization_member (
    id BIGSERIAL PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL DEFAULT 'member' CHECK (role IN ('owner', 'admin', 'member', 'viewer')),
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    invited_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE (organization_id, user_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_org_member_single_default ON organization_member(user_id) WHERE is_default = TRUE;

CREATE TABLE IF NOT EXISTS organization_invitation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'member' CHECK (role IN ('admin', 'member', 'viewer')),
    token VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'CANCELLED')),
    invited_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    UNIQUE(organization_id, email, status)
);

CREATE TABLE IF NOT EXISTS credit_ledger (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    amount DECIMAL(15,4) NOT NULL,
    balance_after DECIMAL(15,4) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_id VARCHAR(255),
    provider VARCHAR(50),
    model VARCHAR(100),
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_cl_user_date ON credit_ledger(user_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_cl_source_id_unique ON credit_ledger(source_id) WHERE source_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS model_pricing (
    id SERIAL PRIMARY KEY,
    provider VARCHAR(50) NOT NULL,
    model VARCHAR(100) NOT NULL,
    input_rate DECIMAL(10,6) NOT NULL,
    output_rate DECIMAL(10,6) NOT NULL,
    fixed_cost DECIMAL(10,4) NOT NULL DEFAULT 0,
    effective_from DATE NOT NULL DEFAULT CURRENT_DATE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE(provider, model, effective_from)
);

CREATE TABLE IF NOT EXISTS credit_reconciliation_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    balance DECIMAL(15,4) NOT NULL,
    ledger_sum DECIMAL(15,4) NOT NULL,
    drift DECIMAL(15,4) NOT NULL,
    pending_dead_letters INT NOT NULL DEFAULT 0,
    explained BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS credit_consumption_dead_letter (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(255) NOT NULL,
    source_type VARCHAR(100),
    source_id VARCHAR(500),
    provider VARCHAR(100),
    model VARCHAR(100),
    prompt_tokens INT DEFAULT 0,
    completion_tokens INT DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    status VARCHAR(20) DEFAULT 'PENDING',
    retry_count INT DEFAULT 0,
    last_retry_at TIMESTAMPTZ,
    error_reason TEXT
);
CREATE INDEX IF NOT EXISTS idx_credit_dead_letter_status ON credit_consumption_dead_letter(status) WHERE status IN ('PENDING', 'RETRYING');

CREATE TABLE IF NOT EXISTS tenant_resource_counters (
    tenant_id VARCHAR(255) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    next_index INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, resource_type)
);

CREATE TABLE IF NOT EXISTS org_resource_restrictions (
    id BIGSERIAL PRIMARY KEY,
    organization_id VARCHAR(50) NOT NULL,
    member_user_id VARCHAR(50) NOT NULL,
    resource_type VARCHAR(20) NOT NULL,
    resource_id VARCHAR(50) NOT NULL,
    restricted_by VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_org_restriction UNIQUE (organization_id, member_user_id, resource_type, resource_id)
);
CREATE INDEX IF NOT EXISTS idx_orr_org_member ON org_resource_restrictions(organization_id, member_user_id);

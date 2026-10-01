-- Personal subscription offers. The seeded policy is intentionally inactive.
CREATE TABLE auth.personal_offer_policy (
    id BIGSERIAL PRIMARY KEY,
    campaign_key VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    label VARCHAR(128) NOT NULL,
    wait_hours INT NOT NULL DEFAULT 4,
    validity_hours INT NOT NULL DEFAULT 72,
    checkout_hold_minutes INT NOT NULL DEFAULT 30,
    reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    reminder_hours INT NOT NULL DEFAULT 12,
    payg_credits_per_usd INT NOT NULL DEFAULT 800,
    allow_conversion_stack BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_personal_offer_policy_version UNIQUE (campaign_key, version),
    CONSTRAINT chk_personal_offer_policy_state CHECK (state IN ('DRAFT', 'ACTIVE', 'PAUSED')),
    CONSTRAINT chk_personal_offer_policy_timing CHECK (
        wait_hours >= 0 AND validity_hours > 0 AND checkout_hold_minutes BETWEEN 30 AND 1440
        AND reminder_hours > 0 AND reminder_hours < validity_hours
        AND payg_credits_per_usd > 0)
);
CREATE UNIQUE INDEX uq_personal_offer_active_campaign
    ON auth.personal_offer_policy(campaign_key) WHERE state = 'ACTIVE';

CREATE TABLE auth.personal_offer_matrix (
    policy_id BIGINT NOT NULL REFERENCES auth.personal_offer_policy(id) ON DELETE RESTRICT,
    plan_code VARCHAR(32) NOT NULL,
    monthly_credits INT NOT NULL,
    bonus_credits INT NOT NULL,
    PRIMARY KEY (policy_id, plan_code, monthly_credits),
    CONSTRAINT chk_personal_offer_matrix_bonus CHECK (bonus_credits >= 0)
);

ALTER TABLE auth.reward_code
    ADD COLUMN recipient_user_id BIGINT REFERENCES auth.users(id) ON DELETE SET NULL,
    ADD COLUMN campaign_key VARCHAR(64),
    ADD COLUMN policy_version_id BIGINT REFERENCES auth.personal_offer_policy(id),
    ADD COLUMN issued_at TIMESTAMPTZ;
ALTER TABLE auth.reward_code DROP CONSTRAINT chk_reward_code_program;
ALTER TABLE auth.reward_code ADD CONSTRAINT chk_reward_code_program
    CHECK (program IN ('PROMO', 'REFERRAL', 'PARTNER', 'PERSONAL_UPGRADE'));
ALTER TABLE auth.reward_code ADD CONSTRAINT chk_personal_offer_code_shape CHECK (
    program <> 'PERSONAL_UPGRADE' OR
    ((recipient_user_id IS NOT NULL OR active = FALSE) AND owner_user_id IS NULL AND campaign_key IS NOT NULL
     AND policy_version_id IS NOT NULL AND issued_at IS NOT NULL
     AND benefit_kind = 'CREDIT_GRANT' AND benefit_trigger = 'PAID_CONVERSION'
     AND owner_reward_kind = 'NONE' AND clawback_enabled = TRUE));
CREATE UNIQUE INDEX uq_personal_offer_recipient_campaign
    ON auth.reward_code(recipient_user_id, campaign_key)
    WHERE program = 'PERSONAL_UPGRADE';

CREATE TABLE auth.personal_offer_checkout_attempt (
    id UUID PRIMARY KEY,
    reward_code_id BIGINT NOT NULL REFERENCES auth.reward_code(id) ON DELETE RESTRICT,
    recipient_user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
    policy_version_id BIGINT NOT NULL REFERENCES auth.personal_offer_policy(id),
    plan_code VARCHAR(32) NOT NULL,
    monthly_credits INT NOT NULL,
    credit_tier_index INT NOT NULL,
    cadence VARCHAR(8) NOT NULL,
    bonus_credits INT NOT NULL,
    plan_price_id VARCHAR(255) NOT NULL,
    credit_price_id VARCHAR(255),
    first_invoice_preview_amount BIGINT,
    stripe_customer_id VARCHAR(255),
    client_nonce VARCHAR(512),
    stripe_session_id VARCHAR(255) UNIQUE,
    stripe_subscription_id VARCHAR(255),
    stripe_invoice_id VARCHAR(255),
    session_url TEXT,
    session_expires_at TIMESTAMPTZ NOT NULL,
    next_reconcile_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_personal_offer_attempt_status CHECK
      (status IN ('CREATING', 'OPEN', 'COMPLETED', 'PAID', 'GRANTED', 'NO_BONUS', 'EXPIRED', 'SUPERSEDED', 'FAILED', 'REVIEW_REQUIRED')),
    CONSTRAINT chk_personal_offer_attempt_cadence CHECK (cadence IN ('monthly', 'yearly')),
    CONSTRAINT chk_personal_offer_attempt_bonus CHECK (bonus_credits >= 0),
    CONSTRAINT chk_personal_offer_attempt_preview CHECK
      (first_invoice_preview_amount IS NULL OR first_invoice_preview_amount > 0)
);
CREATE INDEX idx_personal_offer_attempt_user ON auth.personal_offer_checkout_attempt(recipient_user_id, created_at DESC);
CREATE INDEX idx_personal_offer_attempt_reconcile
    ON auth.personal_offer_checkout_attempt(next_reconcile_at, created_at)
    WHERE status IN ('CREATING', 'OPEN', 'COMPLETED', 'PAID');
CREATE UNIQUE INDEX uq_personal_offer_attempt_open
    ON auth.personal_offer_checkout_attempt(recipient_user_id)
    WHERE status IN ('CREATING', 'OPEN', 'COMPLETED');
CREATE INDEX idx_personal_offer_attempt_subscription
    ON auth.personal_offer_checkout_attempt(stripe_subscription_id)
    WHERE stripe_subscription_id IS NOT NULL;

CREATE TABLE auth.personal_offer_reversed_invoice (
    invoice_id VARCHAR(255) PRIMARY KEY,
    reason VARCHAR(32) NOT NULL,
    reversed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE auth.reward_redemption
    ADD COLUMN campaign_key VARCHAR(64),
    ADD COLUMN offer_attempt_id UUID REFERENCES auth.personal_offer_checkout_attempt(id),
    ADD COLUMN qualifying_invoice_id VARCHAR(255);
CREATE UNIQUE INDEX uq_personal_offer_grant_user_campaign
    ON auth.reward_redemption(redeemer_user_id, campaign_key)
    WHERE program = 'PERSONAL_UPGRADE';
CREATE UNIQUE INDEX uq_personal_offer_grant_invoice
    ON auth.reward_redemption(qualifying_invoice_id)
    WHERE program = 'PERSONAL_UPGRADE';

CREATE TABLE auth.personal_offer_first_paid_purchase (
    user_id BIGINT PRIMARY KEY REFERENCES auth.users(id) ON DELETE RESTRICT,
    invoice_id VARCHAR(255) UNIQUE,
    provider_subscription_id VARCHAR(255),
    paid_at TIMESTAMPTZ,
    status VARCHAR(16) NOT NULL,
    verified_at TIMESTAMPTZ,
    CONSTRAINT chk_personal_offer_first_paid_status CHECK (status IN ('UNKNOWN', 'VERIFIED_NEW', 'PAID'))
);
-- Existing accounts must be reconciled against Stripe before eligibility can be asserted.
INSERT INTO auth.personal_offer_first_paid_purchase(user_id, status)
SELECT id, 'UNKNOWN' FROM auth.users ON CONFLICT (user_id) DO NOTHING;

CREATE TABLE auth.personal_offer_lifecycle (
    user_id BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    campaign_key VARCHAR(64) NOT NULL,
    exhausted_at TIMESTAMPTZ,
    offer_code_id BIGINT REFERENCES auth.reward_code(id),
    initial_status VARCHAR(16) NOT NULL DEFAULT 'pending',
    initial_claimed_at TIMESTAMPTZ,
    initial_accepted_at TIMESTAMPTZ,
    reminder_status VARCHAR(16) NOT NULL DEFAULT 'pending',
    reminder_claimed_at TIMESTAMPTZ,
    reminder_accepted_at TIMESTAMPTZ,
    stopped_reason VARCHAR(32),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, campaign_key),
    CONSTRAINT chk_personal_offer_initial_status CHECK
       (initial_status IN ('pending', 'claimed', 'accepted', 'suppressed', 'unknown')),
    CONSTRAINT chk_personal_offer_reminder_status CHECK
       (reminder_status IN ('pending', 'claimed', 'accepted', 'suppressed', 'unknown'))
);

INSERT INTO auth.personal_offer_policy
    (campaign_key, version, state, label, wait_hours, validity_hours,
     checkout_hold_minutes, reminder_enabled, reminder_hours, payg_credits_per_usd)
VALUES ('free-credit-upgrade', 1, 'DRAFT', 'Initial personal upgrade offer', 4, 72, 30, FALSE, 12, 800);

INSERT INTO auth.personal_offer_matrix(policy_id, plan_code, monthly_credits, bonus_credits)
SELECT p.id, m.plan_code, m.monthly_credits, m.bonus_credits
FROM auth.personal_offer_policy p
CROSS JOIN (VALUES
 ('STARTER',5000,0),('STARTER',10000,0),('STARTER',25000,0),('STARTER',50000,8000),('STARTER',100000,8000),
 ('PRO',5000,0),('PRO',10000,0),('PRO',25000,0),('PRO',50000,8000),('PRO',100000,8000),
 ('PRO',250000,40000),('PRO',500000,80000),('PRO',1000000,80000),('PRO',5000000,80000),('PRO',10000000,80000),
 ('TEAM',5000,0),('TEAM',10000,0),('TEAM',25000,0),('TEAM',50000,8000),('TEAM',100000,8000),
 ('TEAM',250000,40000),('TEAM',500000,80000),('TEAM',1000000,80000),('TEAM',5000000,80000),('TEAM',10000000,80000)
) AS m(plan_code, monthly_credits, bonus_credits)
WHERE p.campaign_key = 'free-credit-upgrade' AND p.version = 1;

-- Capture reversals before acknowledging/deduplicating their Stripe webhook event.
CREATE TABLE auth.personal_offer_reversal_task (
    charge_id VARCHAR(255) PRIMARY KEY,
    reason VARCHAR(32) NOT NULL CHECK (reason IN ('REFUNDED','DISPUTED')),
    invoice_id VARCHAR(255),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RESOLVED','NOT_APPLICABLE')),
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);
CREATE INDEX idx_personal_offer_reversal_due ON auth.personal_offer_reversal_task(next_attempt_at)
    WHERE status='PENDING';

-- A partner's offer to one client: the plan, monthly credits and billing cycle the partner
-- recommends, behind a short public token (livecontext.ai/offer/<token>). The offer page reads it
-- anonymously (the plan and the code's credits only, never earnings); the partner creates, lists
-- and deactivates their own offers. Created by auth-service; purged with the partner's account
-- (the label may name the client).
CREATE TABLE IF NOT EXISTS auth.partner_offer (
    id                BIGSERIAL    PRIMARY KEY,
    token             VARCHAR(16)  NOT NULL,
    partner_user_id   BIGINT       NOT NULL,
    reward_code_id    BIGINT       NOT NULL,
    plan_code         VARCHAR(16)  NOT NULL,
    credit_tier_index INT          NOT NULL,
    billing_cycle     VARCHAR(8)   NOT NULL,
    label             VARCHAR(120),
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_partner_offer_token UNIQUE (token),
    CONSTRAINT chk_partner_offer_plan CHECK (plan_code IN ('STARTER', 'PRO', 'TEAM')),
    CONSTRAINT chk_partner_offer_tier CHECK (credit_tier_index BETWEEN 0 AND 9),
    -- Starter stops at its credit cap (CreditTierConstants.STARTER_MAX_TIER_INDEX).
    CONSTRAINT chk_partner_offer_starter_tier CHECK (plan_code <> 'STARTER' OR credit_tier_index <= 4),
    CONSTRAINT chk_partner_offer_cycle CHECK (billing_cycle IN ('monthly', 'yearly'))
);

-- The partner's own list, newest first.
CREATE INDEX IF NOT EXISTS idx_partner_offer_partner
    ON auth.partner_offer (partner_user_id, created_at DESC);

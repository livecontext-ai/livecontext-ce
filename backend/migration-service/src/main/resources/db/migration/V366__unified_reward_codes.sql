-- V366: Unified reward-code system (PROMO, REFERRAL, PARTNER).
--
-- One config-driven model replaces the dormant promo-code feature and adds
-- referral (friend invites) plus a partner/influencer seam. A reward_code is a
-- program template; a reward_redemption is the per-redeemer lifecycle row. The
-- program discriminator plus benefit/reward policy columns express all three
-- programs through configuration, not three code paths. CHECK constraints make
-- an illegal configuration unrepresentable (see the coherence checks below) so
-- this stays a real model, not a flag bag.
--
-- Tables live in the auth schema next to subscription + credit_ledger because
-- auth-service owns credit consumption. Reward state is Cloud-anchored: grants
-- flow through the single CreditService.grantCredits chokepoint; CE participates
-- through cloud-connect (the reward rows always live on the cloud account).
--
-- Style: no em-dash or en-dash anywhere (the legacy V328/V346 comments use them;
-- do not copy that style).

-- Program template.
CREATE TABLE IF NOT EXISTS auth.reward_code (
    id                          BIGSERIAL    PRIMARY KEY,
    code                        VARCHAR(64)  NOT NULL,
    program                     VARCHAR(16)  NOT NULL,
    -- Owner that earns when a redeemer converts. NULL for PROMO, self for
    -- REFERRAL, the partner account for PARTNER.
    owner_user_id               BIGINT,
    -- Redeemer benefit.
    benefit_kind                VARCHAR(24)  NOT NULL,
    benefit_amount              INT          NOT NULL DEFAULT 0,
    benefit_duration_days       INT          NOT NULL DEFAULT 0,
    benefit_trigger             VARCHAR(16)  NOT NULL,
    -- Owner reward (granted on the redeemer's paid conversion).
    owner_reward_kind           VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    owner_reward_amount         INT          NOT NULL DEFAULT 0,
    -- Hold and clawback.
    hold_days                   INT          NOT NULL DEFAULT 0,
    clawback_enabled            BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Cap (optional, relaxed). NULL cap_limit means uncapped.
    cap_scope                   VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    cap_limit                   INT,
    current_redemptions         INT          NOT NULL DEFAULT 0,
    -- Partner payout seam (inert in v1, populated by a future revenue-share program).
    payout_kind                 VARCHAR(24),
    payout_bps                  INT,
    payout_currency             VARCHAR(3),
    payout_external_account_id  VARCHAR(255),
    active                      BOOLEAN      NOT NULL DEFAULT TRUE,
    valid_from                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    valid_until                 TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_reward_code_program
        CHECK (program IN ('PROMO', 'REFERRAL', 'PARTNER')),
    CONSTRAINT chk_reward_code_benefit_kind
        CHECK (benefit_kind IN ('FREE_NODE_COUNTER', 'CREDIT_GRANT')),
    CONSTRAINT chk_reward_code_benefit_trigger
        CHECK (benefit_trigger IN ('REDEEM_TIME', 'PAID_CONVERSION')),
    CONSTRAINT chk_reward_code_owner_reward_kind
        CHECK (owner_reward_kind IN ('NONE', 'CREDIT_GRANT', 'PARTNER_PAYOUT')),
    CONSTRAINT chk_reward_code_cap_scope
        CHECK (cap_scope IN ('NONE', 'GLOBAL', 'PER_OWNER_SOFT')),
    -- Coherence: a free-node counter is a redeem-time benefit with no hold and
    -- no owner reward (the legacy promo shape); a credit grant has no day window.
    CONSTRAINT chk_reward_code_free_node_shape
        CHECK (benefit_kind <> 'FREE_NODE_COUNTER'
               OR (hold_days = 0 AND owner_reward_kind = 'NONE' AND benefit_trigger = 'REDEEM_TIME')),
    CONSTRAINT chk_reward_code_credit_grant_shape
        CHECK (benefit_kind <> 'CREDIT_GRANT' OR benefit_duration_days = 0)
);

-- Codes are matched case-insensitively (upper-cased on input).
CREATE UNIQUE INDEX IF NOT EXISTS uq_reward_code_code ON auth.reward_code (UPPER(code));
-- Owner lookups (the personal-code mint path) stay tiny: most rows have no owner.
CREATE INDEX IF NOT EXISTS idx_reward_code_owner
    ON auth.reward_code (owner_user_id) WHERE owner_user_id IS NOT NULL;
-- One REFERRAL and one PARTNER code per owner.
CREATE UNIQUE INDEX IF NOT EXISTS uq_reward_code_owner_program
    ON auth.reward_code (owner_user_id, program) WHERE owner_user_id IS NOT NULL;

-- Per-redeemer lifecycle row.
CREATE TABLE IF NOT EXISTS auth.reward_redemption (
    id                       BIGSERIAL    PRIMARY KEY,
    reward_code_id           BIGINT       NOT NULL REFERENCES auth.reward_code(id) ON DELETE CASCADE,
    redeemer_user_id         BIGINT       NOT NULL,
    owner_user_id            BIGINT,
    program                  VARCHAR(16)  NOT NULL,
    status                   VARCHAR(16)  NOT NULL,
    -- FREE_NODE_COUNTER fields (PROMO only). benefit_type is the claim discriminator.
    benefit_type             VARCHAR(64),
    benefit_until            TIMESTAMPTZ,
    free_credits_used        INT          NOT NULL DEFAULT 0,
    free_credits_cap         INT          NOT NULL DEFAULT 0,
    -- Conversion, hold, clawback (CREDIT_GRANT / PAID_CONVERSION programs).
    provider_subscription_id VARCHAR(255),
    qualified_at             TIMESTAMPTZ,
    release_due_at           TIMESTAMPTZ,
    released_at              TIMESTAMPTZ,
    clawed_back_at           TIMESTAMPTZ,
    redeemer_reward_amount   INT,
    owner_reward_amount      INT,
    -- Idempotency keys bound to credit_ledger.source_id (VARCHAR 512).
    reward_source_id         VARCHAR(512),
    owner_reward_source_id   VARCHAR(512),
    redeemed_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    active                   BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_reward_redemption_status
        CHECK (status IN ('GRANTED', 'PENDING', 'QUALIFIED', 'RELEASED', 'CLAWED_BACK', 'TRACK_ONLY', 'INELIGIBLE')),
    -- One redemption per (redeemer, code); also the concurrent double-redeem guard.
    CONSTRAINT uq_reward_redemption_user_code UNIQUE (redeemer_user_id, reward_code_id)
);

-- One REFERRAL reward per redeemer across all referral codes (cloud-account level).
CREATE UNIQUE INDEX IF NOT EXISTS uq_reward_redemption_referral_referee
    ON auth.reward_redemption (redeemer_user_id) WHERE program = 'REFERRAL';
-- Clawback resolves the row by the converting Stripe subscription.
CREATE INDEX IF NOT EXISTS idx_reward_redemption_sub
    ON auth.reward_redemption (provider_subscription_id) WHERE provider_subscription_id IS NOT NULL;
-- Sweeper / qualify scan of unconverted redemptions.
CREATE INDEX IF NOT EXISTS idx_reward_redemption_pending
    ON auth.reward_redemption (status) WHERE status = 'PENDING';
-- Releaser scan of held, qualified rewards.
CREATE INDEX IF NOT EXISTS idx_reward_redemption_release
    ON auth.reward_redemption (release_due_at) WHERE status = 'QUALIFIED';
-- Hot free-node claim path.
CREATE INDEX IF NOT EXISTS idx_reward_redemption_user_counter
    ON auth.reward_redemption (redeemer_user_id) WHERE benefit_until IS NOT NULL;

-- Defensive carry of any existing promo rows into the unified model. Expected to
-- move zero rows: V346 deleted the only seed (PHLAUNCH) and no Java create path
-- ever wrote more, so promo_code ships empty on every environment. This makes the
-- V367 drop safe even if a stray row exists. PROMO maps to a FREE_NODE_COUNTER
-- redeem-time benefit with a GLOBAL cap, preserving the legacy semantics exactly.
INSERT INTO auth.reward_code
    (program, code, owner_user_id, benefit_kind, benefit_amount, benefit_duration_days,
     benefit_trigger, owner_reward_kind, owner_reward_amount, hold_days, clawback_enabled,
     cap_scope, cap_limit, current_redemptions, active, valid_from, valid_until)
SELECT
    'PROMO', pc.code, NULL, 'FREE_NODE_COUNTER', pc.free_credits_cap, pc.benefit_duration_days,
    'REDEEM_TIME', 'NONE', 0, 0, FALSE,
    'GLOBAL', pc.max_redemptions, pc.current_redemptions, pc.active, pc.valid_from, pc.valid_until
FROM auth.promo_code pc;

INSERT INTO auth.reward_redemption
    (reward_code_id, redeemer_user_id, owner_user_id, program, status,
     benefit_type, benefit_until, free_credits_used, free_credits_cap, redeemed_at, active)
SELECT
    rc.id, pr.user_id, NULL, 'PROMO', 'GRANTED',
    pr.benefit_type, pr.benefit_until, pr.free_credits_used, pr.free_credits_cap, pr.redeemed_at, pr.active
FROM auth.promo_redemption pr
JOIN auth.promo_code pc ON pc.id = pr.promo_code_id
JOIN auth.reward_code rc ON UPPER(rc.code) = UPPER(pc.code) AND rc.program = 'PROMO';

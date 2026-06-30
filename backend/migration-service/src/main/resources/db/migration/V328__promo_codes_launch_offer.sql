-- V328: Launch promo codes - time-boxed "free workflow nodes" offer.
--
-- A redeemable promo code (e.g. a launch code) grants the redeeming user a window
-- (benefit_duration_days from THEIR redemption) during which workflow-node
-- executions are FREE up to a per-account cap (free_credits_cap node-credits),
-- after which normal credit billing resumes. Cloud-only in practice: the redeem
-- endpoint lives on the Stripe-gated BillingController, and the free-node hook in
-- CreditService only engages when credits are actually metered (non-unlimited
-- mode). Tables live in the auth schema next to subscription + credit_ledger
-- because auth-service owns credit consumption.

CREATE TABLE IF NOT EXISTS auth.promo_code (
    id                    BIGSERIAL    PRIMARY KEY,
    code                  VARCHAR(64)  NOT NULL,
    benefit_type          VARCHAR(64)  NOT NULL,            -- e.g. WORKFLOW_NODE_FREE
    benefit_duration_days INT          NOT NULL DEFAULT 30, -- length of each redeemer's free window
    free_credits_cap      INT          NOT NULL,            -- per-redemption cap (node-credits)
    max_redemptions       INT,                              -- NULL = unlimited total redemptions
    current_redemptions   INT          NOT NULL DEFAULT 0,
    valid_from            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    valid_until           TIMESTAMPTZ  NOT NULL,            -- code activable until this instant
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Codes are matched case-insensitively (we upper-case on input); enforce uniqueness on UPPER(code).
CREATE UNIQUE INDEX IF NOT EXISTS uq_promo_code_code ON auth.promo_code (UPPER(code));

CREATE TABLE IF NOT EXISTS auth.promo_redemption (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    promo_code_id     BIGINT       NOT NULL REFERENCES auth.promo_code(id) ON DELETE CASCADE,
    benefit_type      VARCHAR(64)  NOT NULL,                -- snapshot of promo_code.benefit_type
    redeemed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    benefit_until     TIMESTAMPTZ  NOT NULL,                -- redeemed_at + benefit_duration_days
    free_credits_used INT          NOT NULL DEFAULT 0,
    free_credits_cap  INT          NOT NULL,                -- snapshot of promo_code.free_credits_cap
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    -- one redemption per (user, code): the unique index is also the race guard
    -- on concurrent double-redeem (the in-tx INSERT fails -> rollback).
    CONSTRAINT uq_promo_redemption_user_code UNIQUE (user_id, promo_code_id)
);

-- Hot path: the per-node claim looks up an active redemption for the executor.
-- Partial index keeps it tiny (most users have no redemption).
CREATE INDEX IF NOT EXISTS idx_promo_redemption_user_active
    ON auth.promo_redemption (user_id)
    WHERE active = TRUE;

-- (CE build: launch-promo seed intentionally omitted; the promo table ships empty.)

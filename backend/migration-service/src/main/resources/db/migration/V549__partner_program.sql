-- V549: Partner / influencer program on top of the V366 reward-code model.
--
-- Three additions, each the smallest extension of what already exists:
--
-- 1. A reward code can grant a complimentary PLAN for a number of days
--    (benefit_plan_code + benefit_plan_days), next to its credit grant. Used by
--    the single-use "creator" code (e.g. PRO for 90 days + 50,000 PAYG credits).
--    A plan grant is only ever a redeem-time benefit.
-- 2. A complimentary internal subscription can END: subscription.comp_ends_at.
--    The hourly internal renewal scheduler reverts the account to FREE on that date,
--    whatever the billing period. NULL = permanent comp (the admin grant).
-- 3. Revenue-share commissions for PARTNER codes: payout_months on the code (how
--    long a referred customer earns the partner a share) and one
--    partner_commission row per paid Stripe invoice of a referred customer.
--
-- Style: no em-dash or en-dash anywhere.

ALTER TABLE auth.reward_code
    ADD COLUMN IF NOT EXISTS label             VARCHAR(128),
    ADD COLUMN IF NOT EXISTS benefit_plan_code VARCHAR(32),
    ADD COLUMN IF NOT EXISTS benefit_plan_days INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS payout_months     INT;

ALTER TABLE auth.reward_code DROP CONSTRAINT IF EXISTS chk_reward_code_plan_grant_shape;
ALTER TABLE auth.reward_code ADD CONSTRAINT chk_reward_code_plan_grant_shape
    CHECK (benefit_plan_code IS NULL
           OR (benefit_trigger = 'REDEEM_TIME' AND benefit_plan_days > 0));

ALTER TABLE auth.reward_code DROP CONSTRAINT IF EXISTS chk_reward_code_payout_shape;
ALTER TABLE auth.reward_code ADD CONSTRAINT chk_reward_code_payout_shape
    CHECK (owner_reward_kind <> 'PARTNER_PAYOUT'
           OR (program = 'PARTNER' AND payout_bps IS NOT NULL AND payout_bps BETWEEN 0 AND 10000
               AND payout_months IS NOT NULL AND payout_months > 0));

-- A customer is attributed to at most ONE partner (first code wins), exactly as a
-- referee has at most one referrer.
CREATE UNIQUE INDEX IF NOT EXISTS uq_reward_redemption_partner_redeemer
    ON auth.reward_redemption (redeemer_user_id) WHERE program = 'PARTNER';

ALTER TABLE auth.subscription
    ADD COLUMN IF NOT EXISTS comp_ends_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS auth.partner_commission (
    id                   BIGSERIAL    PRIMARY KEY,
    redemption_id        BIGINT       NOT NULL REFERENCES auth.reward_redemption(id) ON DELETE CASCADE,
    reward_code_id       BIGINT       NOT NULL,
    partner_user_id      BIGINT       NOT NULL,
    customer_user_id     BIGINT       NOT NULL,
    provider_invoice_id  VARCHAR(255) NOT NULL,
    -- Invoice amount the share is computed on (excluding tax), minor units.
    base_amount_minor    BIGINT       NOT NULL,
    currency             VARCHAR(3)   NOT NULL,
    payout_bps           INT          NOT NULL,
    commission_minor     BIGINT       NOT NULL,
    -- HOLD until due_at (refund window), then payable; PAID once settled by an
    -- admin; VOID when the invoice was refunded or disputed before payout.
    status               VARCHAR(8)   NOT NULL DEFAULT 'HOLD',
    invoice_paid_at      TIMESTAMPTZ  NOT NULL,
    due_at               TIMESTAMPTZ  NOT NULL,
    paid_at              TIMESTAMPTZ,
    -- The admin who marked the line paid: settling moves money, so who did it is a column,
    -- not only a log line.
    paid_by_user_id      BIGINT,
    voided_at            TIMESTAMPTZ,
    void_reason          VARCHAR(32),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_partner_commission_status CHECK (status IN ('HOLD', 'PAID', 'VOID')),
    -- Idempotency: a replayed invoice.paid never records a second share.
    CONSTRAINT uq_partner_commission_invoice UNIQUE (provider_invoice_id)
);

CREATE INDEX IF NOT EXISTS idx_partner_commission_partner
    ON auth.partner_commission (partner_user_id, status);
CREATE INDEX IF NOT EXISTS idx_partner_commission_customer
    ON auth.partner_commission (customer_user_id, status);
CREATE INDEX IF NOT EXISTS idx_partner_commission_code
    ON auth.partner_commission (reward_code_id);

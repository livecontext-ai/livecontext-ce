-- V188: pending_credit_upgrades - Stripe credit-pack tier upgrade state machine
--
-- Backs the Option A flow (one-shot InvoiceItem + sub-item update) replacing the
-- former billing_cycle_anchor=NOW pattern in StripeBillingService.upgradeCreditTierImmediate.
--
-- Purpose: persist the upgrade intent across the 4-call Stripe sequence so that:
--   (a) webhook handlers (invoice.paid / invoice.payment_failed) can resolve which
--       in-flight upgrade an invoice corresponds to without parsing metadata blindly;
--   (b) a reconciliation job can replay the sub-item update if the app crashes
--       between invoices.pay() and subscriptions.update();
--   (c) the upserting webhook (customer.subscription.updated) can skip the
--       legacy handleCreditPackChange path when a pending upgrade row is present
--       (the grant is owned by invoice.paid in the new flow).
--
-- Statuses:
--   PENDING_3DS      - invoices.pay() returned requires_action, awaiting Stripe
--                      Customer Action (webhook payment_intent.succeeded or
--                      invoice.paid resumes the flow).
--   PAID_SUB_PENDING - invoices.pay() succeeded but subscriptions.update either
--                      hasn't run yet (race) or failed transiently. Reconcile job
--                      picks these up.
--   COMPLETED        - entire flow done, sub-item aligned, ledger granted.
--   FAILED           - invoices.payment_failed received, or unrecoverable error.

CREATE TABLE auth.pending_credit_upgrade (
    id                       BIGSERIAL PRIMARY KEY,
    user_id                  BIGINT       NOT NULL,
    subscription_id          BIGINT       NOT NULL,
    provider_subscription_id TEXT         NOT NULL,
    stripe_invoice_id        TEXT         NOT NULL UNIQUE,
    stripe_invoice_item_id   TEXT         NOT NULL,
    target_tier_index        INT          NOT NULL,
    target_credit_quantity   INT          NOT NULL,
    target_credit_price_id   TEXT         NOT NULL,
    status                   VARCHAR(32)  NOT NULL,
    error_message            TEXT,
    created_at               TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at               TIMESTAMP    NOT NULL DEFAULT now(),
    completed_at             TIMESTAMP,
    CONSTRAINT pending_credit_upgrade_status_chk
        CHECK (status IN ('PENDING_3DS', 'PAID_SUB_PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT pending_credit_upgrade_user_fk
        FOREIGN KEY (user_id) REFERENCES auth.users(id),
    CONSTRAINT pending_credit_upgrade_subscription_fk
        FOREIGN KEY (subscription_id) REFERENCES auth.subscription(id)
);

-- For reconcile job: scan unresolved upgrades only.
CREATE INDEX idx_pending_credit_upgrade_active
    ON auth.pending_credit_upgrade(status, created_at)
    WHERE status IN ('PENDING_3DS', 'PAID_SUB_PENDING');

-- For onSubscriptionUpsert guard: skip handleCreditPackChange when a pending or
-- recently completed upgrade exists for the same Stripe subscription.
CREATE INDEX idx_pending_credit_upgrade_sub
    ON auth.pending_credit_upgrade(provider_subscription_id, status, created_at DESC);

CREATE INDEX idx_pending_credit_upgrade_user
    ON auth.pending_credit_upgrade(user_id);

COMMENT ON TABLE auth.pending_credit_upgrade IS
    'In-flight credit-pack tier upgrades. Drives the Option A Stripe flow + webhook routing.';

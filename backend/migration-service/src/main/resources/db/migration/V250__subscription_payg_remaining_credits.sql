-- V250 - Add payg_remaining_credits 2nd-scalar bucket on auth.subscription.
--
-- Context: enable PAYG-first credit model where one-time top-ups (Stripe
-- mode=PAYMENT) feed a separate wallet bucket, consumed AFTER sub-included
-- credits when both are present. CreditService.deductCredits will split
-- the debit across (sub) and (payg) buckets via a private splitBuckets helper
-- introduced in PR2, writing two ledger rows with sourceId suffix ":sub"/":payg"
-- to preserve idx_cl_source_id_unique.
--
-- The single-scalar remaining_credits (V3:117) stays. Sub renewal touches ONLY
-- remaining_credits (resetBalance unchanged). PAYG top-ups land on the new
-- column via grantCredits(sourceType='PAYG_TOPUP') routing (PR2).
--
-- Invariant updated (documented in Subscription.java javadoc PR1.4):
--   delinquent = TRUE  ⇒  (remaining_credits + payg_remaining_credits) ≤ 0
--
-- Index strategy: partial index on (billing_customer_id) WHERE payg > 0
-- supports the "find subscription by user" lookup which traverses
-- subscription → billing_customer → users via the FK chain. The
-- billing_customer_id column is the natural sort key for that JOIN
-- (subscription.billing_customer_id = V3:109 FK to billing_customer.id).
-- Ops queries like "list users with positive PAYG balance" go via this
-- index then JOIN to billing_customer/users - no index bloat on the
-- global subscription B-tree. The active-subscription main index on
-- (billing_customer_id, status) from V3 handles the common path.

ALTER TABLE auth.subscription
    ADD COLUMN IF NOT EXISTS payg_remaining_credits DECIMAL(15, 4) NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_subscription_payg_active
    ON auth.subscription (billing_customer_id)
    WHERE payg_remaining_credits > 0;

-- ============================================================================
-- V498 : a yearly Stripe subscription receives its credit pack every MONTH.
--
-- Context: the credit pack is priced per unit per month ($1.00 monthly, $12.00
-- yearly = twelve months at the monthly rate, no discount - V290), and the
-- pricing page sells it as "credits per month" whatever the billing cycle.
-- Monthly subscribers are re-granted on every Stripe invoice.paid
-- (billing_reason=subscription_cycle). A yearly subscriber raises that invoice
-- once every twelve months, so they were granted ONE month of credits for a
-- year of payment: prod user 121 paid 100 units x $12 and received 100,000
-- credits once, where the monthly equivalent receives 100,000 x 12.
--
-- Fix: the monthly credit cycle of a yearly subscription is tracked by an
-- index anchored on current_period_start (cycle N starts at
-- current_period_start + N months, N in 1..11). Cycle 0 is the grant made at
-- the start of the billing period (subscription creation or invoice.paid);
-- YearlyCreditCycleScheduler grants the following ones. The anchor is the
-- billing period itself, so the index is reset to 0 whenever
-- current_period_start moves (Subscription.setCurrentPeriodStart).
--
-- Existing rows: DEFAULT 0 = "only the period-start grant happened", which is
-- exactly true for every yearly subscription created before this migration;
-- the scheduler then catches them up on its first pass.
-- ============================================================================

ALTER TABLE auth.subscription
    ADD COLUMN IF NOT EXISTS credit_cycle_index INTEGER NOT NULL DEFAULT 0;

-- The scheduler selects on cadence = 'yearly', and cadence used to be a label the webhook
-- upsert could overwrite with the literal 'monthly' whenever it carried no price id. A
-- yearly Stripe row mislabelled that way would make this fix a silent no-op for that
-- customer, so relabel from the one fact Stripe wrote on every row: the period length.
-- No monthly period is longer than six months; no yearly one is shorter or longer than a
-- year. Active rows only: a trialing row's period IS its trial, whatever its length, and a
-- canceled row is never selected by the scheduler anyway. Idempotent.
UPDATE auth.subscription
   SET cadence = 'yearly'
 WHERE provider = 'stripe'
   AND status = 'active'
   AND cadence = 'monthly'
   AND current_period_end - current_period_start > INTERVAL '6 months'
   AND current_period_end - current_period_start < INTERVAL '13 months';

COMMENT ON COLUMN auth.subscription.credit_cycle_index IS
    'Yearly Stripe subscriptions only: index of the last monthly credit cycle granted, '
    'anchored on current_period_start (cycle N = current_period_start + N months). '
    '0 = only the period-start grant. Reset to 0 whenever current_period_start moves. '
    'Always 0 on monthly and internal subscriptions.';

-- V529: lifecycle emails, part two (cloud only; both tables stay empty in a self-hosted edition).
--
-- 1. Durable checkout.started throttle (auth-service, CheckoutStartedThrottle).
--    At most one checkout.started per user and checkout kind per 24 hours, so opening the
--    checkout five times starts ONE recovery sequence. The window used to live in memory, per
--    pod: with several auth-service pods each one let an event through. It now lives on the
--    user row and is claimed by one conditional UPDATE (... WHERE col IS NULL OR col <= now - 24h),
--    which Postgres serializes on the row lock, so it holds across pods and restarts.
--    One column per kind: a credits top-up never swallows the plan checkout that follows it.
--    Not mapped on the User entity on purpose, so a whole-row save can never rewind them.
--
-- 2. Write-once signup guard (auth-service, UserLifecycleContextService.recordSignup).
--    user.signed_up is sent from two places (the first login that creates the account, and the
--    verification of an address that was unverified at creation) and two first logins can race on
--    one account. The first to stamp lifecycle_signup_emitted_at (... WHERE ... IS NULL) sends it,
--    every other attempt updates no row and sends nothing. Mapped read-only on User.
--
-- 3. Monthly recap ledger (orchestrator-service, MonthlyRecapScheduler).
--    One row per person and month, CLAIMED (insert ... on conflict do nothing) before the recap
--    event is sent, so a second pass, a second pod or a restart never sends the same month twice.
--    A claim whose send could not be handed over is deleted again so a later pass retries it.
--    Personal, never workspace-scoped: deleted by the orchestrator purge follower on a USER purge.

ALTER TABLE auth.users
    ADD COLUMN IF NOT EXISTS last_checkout_subscription_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_checkout_credits_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lifecycle_signup_emitted_at   TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS orchestrator.lifecycle_monthly_recaps (
    tenant_id   VARCHAR(255) NOT NULL,
    recap_month VARCHAR(7)   NOT NULL,
    claimed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, recap_month)
);

COMMENT ON TABLE orchestrator.lifecycle_monthly_recaps IS
    'One row per person and recap month (YYYY-MM): the monthly recap email was handed to auth-service. V529.';

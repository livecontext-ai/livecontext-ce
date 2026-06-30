-- PR11 - per-user quota cap configurable by OWNER/ADMIN.
--
-- One row per (org, member) pair carrying optional per-dimension caps for
-- a billing period. NULL on a dimension = no cap on that dimension. When
-- a member tries to consume past one of their caps, CreditService.consume*
-- refuses with `CreditConsumeResult.quotaCapExceeded(...)` - distinct from
-- the existing `insufficientCredits` so the frontend can surface a
-- "ask your org admin to raise your cap" message instead of "top up".
--
-- Reset cadence MONTHLY_SUB_CYCLE = aligned with the org owner's Stripe
-- billing cycle (resolves plan §9.2 - no DST/timezone surprises since
-- Stripe periods are canonical). Future cadences ("DAILY", "WEEKLY")
-- documented in the column comment for forward compatibility but only
-- MONTHLY_SUB_CYCLE is wired in v1.
--
-- Plan reference: §11 (last milestone). Q1=b enforcement: caps apply to
-- the EXECUTOR's consumption, regardless of who the redirect makes pay -
-- a member with a 100-credit cap cannot burn 1000 credits of the owner's
-- wallet just because the bill lands on the owner. Cross-references
-- {@code credit_ledger.executor_user_id} (V200) for accounting.

CREATE TABLE auth.org_member_quota_limit (
    org_id            UUID         NOT NULL REFERENCES auth.organization(id) ON DELETE CASCADE,
    user_id           BIGINT       NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,

    -- NULL = no cap on this dimension. NUMERIC(18,4) matches credit_ledger.amount precision.
    period_credits         NUMERIC(18,4) NULL,
    period_storage_bytes   BIGINT        NULL,
    period_llm_tokens      BIGINT        NULL,

    -- Forward-compatible reset cadence enum. Only MONTHLY_SUB_CYCLE is
    -- supported in v1; widen this CHECK when DAILY / WEEKLY ship.
    reset_cadence     VARCHAR(32)  NOT NULL DEFAULT 'MONTHLY_SUB_CYCLE',

    created_by_user_id BIGINT      NOT NULL REFERENCES auth.users(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (org_id, user_id),
    CONSTRAINT chk_omql_cadence CHECK (reset_cadence IN ('MONTHLY_SUB_CYCLE')),
    -- All three NULL is allowed but useless - keep the row for audit-trail
    -- continuity (set/remove events). No CHECK on at-least-one-non-null.

    -- At-rest sanity: positive-only caps. A 0-credit cap would be a hard
    -- lock-out which is better expressed by REMOVING the membership.
    CONSTRAINT chk_omql_credits_positive  CHECK (period_credits        IS NULL OR period_credits        > 0),
    CONSTRAINT chk_omql_storage_positive  CHECK (period_storage_bytes  IS NULL OR period_storage_bytes  > 0),
    CONSTRAINT chk_omql_tokens_positive   CHECK (period_llm_tokens     IS NULL OR period_llm_tokens     > 0)
);

-- Lookups: enforcement reads (org_id, user_id) on every consume - covered
-- by the PK. Reverse lookup ("all orgs where a given user has a cap") is
-- rare but useful in the cleanup-on-leave path.
CREATE INDEX idx_omql_user ON auth.org_member_quota_limit(user_id);

COMMENT ON TABLE auth.org_member_quota_limit IS
    'PR11 - per-member quota caps. OWNER/ADMIN-configurable. Enforced in '
    'CreditService.consume* against the EXECUTOR (not the payer) so Q1=b '
    'redirect cannot bypass caps. NULL on a dim = no cap on that dim.';
COMMENT ON COLUMN auth.org_member_quota_limit.reset_cadence IS
    'Forward-compatible enum. v1 supports only MONTHLY_SUB_CYCLE (aligned '
    'with org owner subscription period). Widen the CHECK constraint when '
    'DAILY / WEEKLY cadences ship.';

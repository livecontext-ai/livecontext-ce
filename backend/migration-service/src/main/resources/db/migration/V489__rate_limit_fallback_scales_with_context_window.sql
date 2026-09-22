-- Raise rows still carrying the generic rate-limit fallback, which for a
-- large-context model is a ceiling below the cost of ONE of its own requests.
--
-- THE FAILURE. A token ceiling is only a ceiling relative to what one request
-- costs, and that is bounded by the model's context window. The blanket
-- 60000/500/20000/200 fallback predates 1M-context models, and on one of them
-- it inverts the limiter: no request ever fits a fresh window, so every call
-- waits out the full 60s window before proceeding.
--
-- Measured on production 2026-09-11, deepseek/deepseek-v4-flash (1M context,
-- ~50,000 estimated tokens per tool-heavy agent turn, ceiling 60,000):
--     16 of 17 calls delayed, ~51s of limiter wait each
--     871s of cumulative wait, for provider responses that took 1.5-10s
-- 701 of 793 catalog rows carried that same fallback.
--
-- WHAT THIS RAISES. Only rows carrying the fallback fingerprint EXACTLY
-- (60000/500/20000/200) whose rate-limit fields are not user-modified. Same
-- reasoning as V441: an admin who chose those four numbers by hand would be
-- indistinguishable, but the value is the documented default and appears
-- uniformly across whole provider families. A user-modified row is a decision
-- and is left alone.
--
-- THE VALUES mirror the ARITHMETIC of CatalogMergeService.applyRateLimitDefaults
-- exactly, so a row repaired here and a row inserted by a later sync agree:
--     tpm            = max(2,000,000, min(context_window x 4, 100,000,000))
--     tpm_per_tenant = min(max(500,000, tpm / 4), tpm)
-- The 100,000,000 clamp is CatalogMergeService.MAX_DERIVED_TPM. context_window
-- is feed-supplied and untrusted, and Postgres does not wrap on overflow, it
-- aborts: a single row with a bogus context_window (2e9 x 4 exceeds int) would
-- fail this statement and take the whole deploy down with it. The arithmetic is
-- done in bigint and clamped before the cast back.
-- The upper bound on the tenant share is not decoration: the flat floor is one
-- number while tpm is per-model, so without it a row whose tpm lands under
-- 500,000 would hand a single tenant more than the whole platform.
-- RPM is left at 500: it was never the binding dimension (measured
-- rate_limit_rpm_current = 0 against a limit of 500 during the incident above),
-- and the researched per-provider values live in ai.agent.rate-limits.
--
-- WHY A MIGRATION AND NOT JUST THE CODE CHANGE. A feed sync does repair most of
-- these on its own: MergeOptions.forSync is partialUpdate=false, so applyFields
-- nulls a column the feed does not carry and the new fallback then fills the
-- derived value. But that only reaches rows the feed actually carries. Rows
-- inserted by migrations bypass the merge entirely - of the 7 that V440 seeded,
-- 3 (minimax/MiniMax-M2.7, zai/glm-4.6v, zai/glm-5v-turbo) had not been touched
-- by any of the last three syncs and would keep 60,000 indefinitely. A sync also
-- needs an admin to trigger it (ModelCatalogSyncService has no scheduler), so
-- without this migration the fix ships dormant.
--
-- NOT MIRRORED: the method's FIRST guard. applyRateLimitDefaults returns early
-- for a model with a curated ai.agent.rate-limits entry, leaving its columns
-- NULL so the researched value wins; this statement cannot consult that table
-- (it is YAML) and would leave numbers instead. Unreachable in practice - V441
-- already cleared the curated rows and none of the 259 seed rows carrying the
-- fingerprint is curated - but it is the one place the two can diverge.
--
-- IDEMPOTENT. The predicate matches only the exact old fingerprint, so re-running
-- changes nothing, and an install already repaired by hand is skipped.

-- Same guards V440/V441 put on this table: never let a blocked UPDATE hold the
-- Flyway migration lock open indefinitely. Failing the deploy is recoverable,
-- stalling it behind someone else's long transaction is not.
SET lock_timeout = '10s';
SET statement_timeout = '60s';

WITH target AS (
    SELECT id,
           GREATEST(
               2000000::bigint,
               LEAST(COALESCE(context_window, 0)::bigint * 4, 100000000::bigint)
           )::int AS tpm
    FROM agent.model_config_overrides
    WHERE rate_limit_tpm            = 60000
      AND rate_limit_rpm            = 500
      AND rate_limit_tpm_per_tenant = 20000
      AND rate_limit_rpm_per_tenant = 200
      AND NOT ('rateLimitTpm' = ANY(user_modified_fields))
      AND NOT ('rateLimitTpmPerTenant' = ANY(user_modified_fields))
)
UPDATE agent.model_config_overrides m
SET rate_limit_tpm            = t.tpm,
    rate_limit_tpm_per_tenant = LEAST(GREATEST(500000, t.tpm / 4), t.tpm),
    updated_at                = now()
FROM target t
WHERE t.id = m.id;

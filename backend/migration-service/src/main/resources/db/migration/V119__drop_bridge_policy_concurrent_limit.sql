-- Drop the max_concurrent_per_user column from bridge_access_policy.
--
-- The field was defined in V118 and plumbed through the DTO / repository /
-- admin UI, but the auth-service access-decision path never read it - no
-- call site increments or checks a concurrency counter before allowing a
-- bridge dispatch. Enforcing it properly requires a Redis INCR/DECR keyed
-- by (user, bridge) with a release-on-response-complete hook, which is a
-- non-trivial plumbing change.
--
-- The per-user daily quota (max_requests_per_user_per_day) already protects
-- the shared CLI subscription from abuse. We drop the half-implemented
-- column rather than ship a policy knob that silently does nothing - a
-- knob that's displayed in the admin UI but never enforced is a
-- misleading-by-omission security footgun.
--
-- Re-introducing per-user concurrency is tracked as a separate follow-up;
-- when it lands, the new column + enforcement ship together in the same
-- migration.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

SET search_path TO auth;

ALTER TABLE bridge_access_policy DROP CONSTRAINT IF EXISTS bridge_access_policy_concurrent_positive;
ALTER TABLE bridge_access_policy DROP COLUMN IF EXISTS max_concurrent_per_user;

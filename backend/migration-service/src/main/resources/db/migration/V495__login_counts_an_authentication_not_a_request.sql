-- A login is an AUTHENTICATION EVENT, not a request.
--
-- Until now auth_login_total and the `login.success` audit trail were derived
-- from last_login_at: UserResolutionService rewrote that column whenever it was
-- more than LOGIN_DEDUP_MINUTES (10) old, and treated "I moved the column" as
-- "this person just signed in". resolveUser runs on EVERY gateway request that
-- carries a JWT, so the result was one counted login per active principal per
-- 10 minutes, for as long as anything kept making requests.
--
-- Measured on prod 2026-09-17: 96 `login.success` in 12 hours for THREE
-- accounts, minimum gap between two events for the same account exactly 10.0
-- minutes, against 16 real LOGIN events in Keycloak over 24 hours. Everything
-- downstream inherited that: the PostHog login funnel, the audit trail a
-- security review reads, and AuthNoLoginTraffic, which could no longer fire at
-- all because an open tab or a scheduled workflow kept the counter alive even
-- if nobody on earth could sign in.
--
-- The fix needs a column because the honest signal lives in the token, not in
-- the clock: OIDC's `auth_time` claim is the instant the person authenticated.
-- It is constant across every refresh of the same session and moves forward on
-- a genuinely new one, so "auth_time is newer than what we stored" is exactly
-- one event per authentication, decided by an atomic conditional UPDATE that is
-- safe across auth replicas and across the ~10 parallel resolves a page load
-- fires. Storing the instant rather than a session id is deliberate: it is
-- monotonic, so a person with two live sessions (two browsers, phone plus
-- laptop) cannot make the value flap back and forth and bill a login on every
-- alternation, which a `sid` comparison would.
--
-- last_login_at keeps its current write path and its current meaning in
-- practice ("last seen"); it simply stops being read as a login. Nothing that
-- consumes it changes.
--
-- DEPLOYING THIS TO CLOUD NEEDS -f migration_enabled=true: the JPA entity
-- carries the field unconditionally and auth-service runs ddl-auto: validate,
-- so without the column the new auth pods do not boot.

ALTER TABLE auth.users
    ADD COLUMN IF NOT EXISTS last_authenticated_at TIMESTAMPTZ;

COMMENT ON COLUMN auth.users.last_authenticated_at IS
    'Instant of the most recent authentication seen for this user, from the OIDC auth_time claim. '
    'Moves forward only. A login is counted when an incoming token carries an auth_time strictly '
    'newer than this. NULL means no token carrying auth_time has been seen yet (self-hosted '
    'embedded tokens and API keys never carry one, and never count a login here).';

-- Seed from last_login_at rather than leaving NULL.
--
-- A NULL column would make the very first request of every already-signed-in
-- user look like a brand new authentication, so the deploy would publish one
-- artificial login per active account: a spike on the exact dashboard this
-- migration exists to make truthful. Seeding from last_login_at suppresses it,
-- because a session that is already open authenticated BEFORE it was last seen,
-- so its auth_time is necessarily older than the seeded value and correctly
-- counts nothing. The next real sign-in has a newer auth_time and counts once.
UPDATE auth.users
   SET last_authenticated_at = last_login_at
 WHERE last_authenticated_at IS NULL
   AND last_login_at IS NOT NULL;

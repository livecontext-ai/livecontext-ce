-- V256 - Wire Stripe PAYG price IDs (TEST mode) into auth.price.
--
-- V251 seeded the three PAYG cadences (payg_small / payg_medium / payg_large)
-- with provider_price_id = NULL, deliberately leaving the actual Stripe price
-- IDs out of Flyway so each environment could be wired independently. This
-- migration carries the TEST mode IDs produced by the rk_test_* API key on
-- 2026-05-19 and is intended for any environment whose auth-service runs
-- against TEST mode Stripe (sk_test_*).
--
-- LIVE rollout: a follow-up migration (V2xx) will be added when the prod
-- Stripe account is provisioned in live mode. That follow-up drops the
-- `provider_price_id IS NULL` guard and unconditionally rewrites the column
-- with the `price_*` IDs returned by the rk_live_* run. The two migrations
-- intentionally do not collide because the LIVE migration overwrites.
--
-- Idempotence: the WHERE clause makes this migration a no-op if the column is
-- already populated (e.g. an ops one-liner already filled it, or a previous
-- run of V256 succeeded). Re-running V256 cannot revert a LIVE wiring.

UPDATE auth.price
   SET provider_price_id = 'price_1TYheU1MnvbO0ZY3D4uOBeO6'
 WHERE cadence = 'payg_small'
   AND provider_price_id IS NULL;

UPDATE auth.price
   SET provider_price_id = 'price_1TYheV1MnvbO0ZY3maKBuhee'
 WHERE cadence = 'payg_medium'
   AND provider_price_id IS NULL;

UPDATE auth.price
   SET provider_price_id = 'price_1TYheW1MnvbO0ZY3u6H2szO9'
 WHERE cadence = 'payg_large'
   AND provider_price_id IS NULL;

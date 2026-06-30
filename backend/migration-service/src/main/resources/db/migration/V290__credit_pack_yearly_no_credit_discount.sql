-- V290: credit-pack pricing cleanup for the unified margin model.
--
-- The application now uses one Stripe credit-pack product for every base plan:
--   * CREDIT_PACK monthly: $1.00 / unit / month
--   * CREDIT_PACK yearly:  $12.00 / unit / year
--
-- Team premium is represented by plan-aware Stripe quantities in
-- CreditTierConstants, not by a separate CREDIT_PACK_TEAM price.

UPDATE auth.price
   SET amount_cents = 1200,
       provider_price_id = 'price_1Tbh1M1MnvbO0ZY36xN4c3So'
 WHERE plan_id = (SELECT id FROM auth.plan WHERE code = 'CREDIT_PACK')
   AND cadence = 'yearly';

-- V255 intentionally dropped auth.plan.disabled and auth.price.disabled because
-- the flags had no readers. Keep the obsolete CREDIT_PACK_TEAM rows for
-- history, but remove their Stripe mapping so runtime caches ignore them.
UPDATE auth.price
   SET provider_price_id = NULL
 WHERE plan_id = (SELECT id FROM auth.plan WHERE code = 'CREDIT_PACK_TEAM')
   AND cadence IN ('monthly', 'yearly');

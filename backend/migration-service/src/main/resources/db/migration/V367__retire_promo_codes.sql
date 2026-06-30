-- V367: Retire the promo-code tables, superseded by the unified reward-code model.
--
-- The promo feature is dead: V328 seeded one code (PHLAUNCH), V346 deleted it,
-- there is no Java create path, the frontend redeem card was removed, and the only
-- live consumer (the free-workflow-node benefit in CreditService) now reads
-- auth.reward_redemption instead. V366 created the unified tables and defensively
-- carried any stray promo rows, so a hard DROP is safe and leaves ONE system.
--
-- Child first (the FK from promo_redemption references promo_code).
DROP TABLE IF EXISTS auth.promo_redemption;
DROP TABLE IF EXISTS auth.promo_code;

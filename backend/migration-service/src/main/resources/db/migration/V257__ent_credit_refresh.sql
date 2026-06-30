-- V257 - Enterprise credit refresh (fix anomalie ENT_PREMIUM, align all ENT to ~$8/1k).
--
-- Before: ENT_PREMIUM ($2000) offered 250K credits = $8/1k, while ENT_BASIC,
-- ENT_STANDARD and ENT_ULTIMATE were all at $10/1k. PREMIUM was the only outlier,
-- giving a better per-credit rate than both the tier below AND the tier above.
-- Result: a non-monotone escalator - upgrading from STANDARD to PREMIUM gained
-- ratio (good), but continuing to ULTIMATE lost that bonus (bad UX, confusing).
--
-- After: all 4 ENT tiers normalized at ~$8.33/1k by raising the credit count
-- of BASIC / STANDARD / ULTIMATE (PREMIUM unchanged). No price changes, no
-- Stripe migration, no loser - existing subscribers gain credits at next renewal.
-- ENT_PREMIUM stays at 250K so its prior $8/1k rate is preserved (slight gain
-- vs the others because 250K / $2000 = $8.00 exactly, the rounded sweet spot).
--
-- Coherence after V257:
--   ENT_BASIC      $500  / 60K   = $8.33/1k
--   ENT_STANDARD   $1000 / 120K  = $8.33/1k
--   ENT_PREMIUM    $2000 / 250K  = $8.00/1k  (unchanged)
--   ENT_ULTIMATE   $5000 / 600K  = $8.33/1k
--
-- Idempotence: WHERE clause matches the exact pre-V257 values. Re-running V257
-- is a no-op if the column has already been updated past its old value.

UPDATE auth.plan
   SET included_tool_credits = 60000
 WHERE code = 'ENTERPRISE_BASIC'
   AND included_tool_credits = 50000;

UPDATE auth.plan
   SET included_tool_credits = 120000
 WHERE code = 'ENTERPRISE_STANDARD'
   AND included_tool_credits = 100000;

UPDATE auth.plan
   SET included_tool_credits = 600000
 WHERE code = 'ENTERPRISE_ULTIMATE'
   AND included_tool_credits = 500000;

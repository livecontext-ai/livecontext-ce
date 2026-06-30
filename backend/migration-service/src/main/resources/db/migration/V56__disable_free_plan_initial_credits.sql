-- V56: Disable FREE plan initial credits (2026-04-08)
-- New FREE signups will receive 0 credits instead of 1000.
-- Existing users keep their current balance; on monthly renewal the FreeSubscriptionRenewalScheduler
-- will also grant 0 (since it reads the same plan.included_llm_tokens column).
--
-- To re-enable: UPDATE plan SET included_llm_tokens = 1000 WHERE code = 'FREE'; (with search_path=auth)

SET search_path TO auth;

UPDATE plan
SET included_llm_tokens = 0
WHERE code = 'FREE';

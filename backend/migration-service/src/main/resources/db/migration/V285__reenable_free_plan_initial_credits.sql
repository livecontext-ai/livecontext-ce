-- Re-enable 1,000 initial credits for the FREE plan.
-- V56 set this to 0; we now want to give new signups a starter balance
-- so the welcome gift modal has something to celebrate.
UPDATE auth.plan
SET included_llm_tokens = 1000
WHERE code = 'FREE';

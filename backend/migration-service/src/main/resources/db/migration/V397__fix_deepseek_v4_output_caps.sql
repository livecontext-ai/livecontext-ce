-- Fix wrongly-low output-token caps on the direct-provider DeepSeek V4 models.
--
-- Why: agent.model_config_overrides.max_output_tokens for the DIRECT `deepseek`
-- provider is fed by LiteLLM, which reports the STALE 8192 cap (DeepSeek's old
-- chat limit) for deepseek-v4-pro / deepseek-v4-flash. The real cap is far
-- higher (the openrouter/deepseek-v4-* rows carry 384000 / 16384). Because the
-- effective request cap is MIN(requested, model_max_output_tokens)
-- (MaxTokensClamp), a too-low 8192 makes a REASONING model (deepseek-v4-pro)
-- burn its whole completion budget "thinking" and return an EMPTY answer
-- (finishReason=stop, 0 visible content) - observed in prod 2026-07-08.
--
-- The two feeds live in disjoint identity spaces keyed on (provider, model_id):
-- OpenRouter rows are provider='openrouter' / 'deepseek/deepseek-v4-pro', direct
-- rows are provider='deepseek' / 'deepseek-v4-pro'. The sync never cross-links
-- them, so the correct OpenRouter cap never reaches the direct row, and a
-- refresh re-writes LiteLLM's 8192. We therefore (a) set the correct cap and
-- (b) PROTECT the field via user_modified_fields so a future catalog sync never
-- reverts it.
--
-- Runtime safety: the effective value stays MIN(requested, cap); the platform
-- default request is 16000, so this only raises the effective cap from 8192 to
-- 16000 (2x head-room for reasoning + answer) and never sends more than the
-- agent actually requests. Non-DeepSeek and deepseek-chat rows are untouched
-- (deepseek-chat's 8192 is its REAL API limit and must stay).
--
-- Idempotent: fixed target values + set-union on the text[] protection array;
-- re-running is a no-op (matches the manual prod correction applied 2026-07-08).

SET lock_timeout = '10s';
SET statement_timeout = '60s';
SET search_path TO agent;

UPDATE model_config_overrides
   SET max_output_tokens = 384000,
       user_modified_fields = (
         SELECT array_agg(DISTINCT e)
           FROM unnest(COALESCE(user_modified_fields, '{}'::text[]) || ARRAY['maxOutputTokens']) AS e),
       updated_at = NOW()
 WHERE provider = 'deepseek' AND model_id = 'deepseek-v4-pro'
   AND (max_output_tokens IS DISTINCT FROM 384000
        OR NOT ('maxOutputTokens' = ANY (COALESCE(user_modified_fields, '{}'::text[]))));

UPDATE model_config_overrides
   SET max_output_tokens = 16384,
       user_modified_fields = (
         SELECT array_agg(DISTINCT e)
           FROM unnest(COALESCE(user_modified_fields, '{}'::text[]) || ARRAY['maxOutputTokens']) AS e),
       updated_at = NOW()
 WHERE provider = 'deepseek' AND model_id = 'deepseek-v4-flash'
   AND (max_output_tokens IS DISTINCT FROM 16384
        OR NOT ('maxOutputTokens' = ANY (COALESCE(user_modified_fields, '{}'::text[]))));

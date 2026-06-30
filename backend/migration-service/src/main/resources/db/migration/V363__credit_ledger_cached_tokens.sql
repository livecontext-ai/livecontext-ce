-- V363: credit_ledger.cached_tokens - surface the cached (cache-read) token
-- subset on each LLM ledger row so the usage-history UI can show how much of a
-- turn's input was billed at the discounted cache rate vs full price.
--
-- The value is the cache-read subset of prompt_tokens (provider-agnostic:
-- max(cachedTokens, cacheReadTokens) from LlmTokenBreakdown). It is ALWAYS a
-- subset of prompt_tokens, never additive, so downstream consumers must not add
-- it to prompt_tokens.
--
-- Nullable, no DEFAULT, no backfill: a metadata-only ALTER that is instant even
-- on the hot auth.credit_ledger table. Pre-V363 rows and non-LLM rows (workflow
-- node, image generation, web search, marketplace, markup) carry NULL.
ALTER TABLE auth.credit_ledger
    ADD COLUMN IF NOT EXISTS cached_tokens INTEGER;

COMMENT ON COLUMN auth.credit_ledger.cached_tokens IS
    'Cache-read token subset of prompt_tokens for LLM rows (billed at the discounted cache rate). NULL for pre-V363 rows and non-LLM rows.';

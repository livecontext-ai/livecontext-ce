-- V497: capability tokens stored encrypted + hashed, dead plaintext column dropped.
--
-- Numbered 497 because 493-496 are all spoken for: 493/494 landed on dev as the free-tier
-- allowance, and 495/496 were used by the own-key migrations (reverted off dev, but a number
-- that has been applied anywhere is never reused).
--
-- Why. An audit of production on 2026-09-17 found every credential secret encrypted but 83
-- capability tokens in clear (webhook URL tokens, share links, chat/form endpoint tokens,
-- widget tokens, invitation tokens, Telegram approval callbacks): anyone holding a database
-- copy could replay them against the platform with no key at all. Each token column now holds
-- the AES ciphertext (ENC:..., decrypted on read so the UI still shows the link) and gains a
-- sibling <col>_hash (keyed HMAC-SHA256 of the plaintext) that every lookup goes through.
--
-- What this migration does NOT do: it cannot compute the hashes (the HMAC key lives in the
-- application, never in the database). Existing rows are rewritten by each service
-- (PlaintextTokenBackfill) 10 minutes after readiness (token-at-rest.backfill.delay-seconds);
-- until then the token column still holds the plaintext, the hash is NULL, and the new code
-- resolves such rows through a read-only plaintext fallback. Do not add NOT NULL to a hash
-- column.
--
-- Deploy notes. (1) auth-service and conversation-service run ddl-auto=validate: deploy with
-- -f migration_enabled=true or their pods do not boot. (2) The rewrite is ONE-WAY and delayed
-- on purpose: a replica on the previous image compares the column to the plaintext and cannot
-- read ENC:, so the delay lets a rolling deploy finish first. Rolling the image back inside
-- that window loses only the rows the new code happened to save meanwhile (an ordinary JPA save
-- writes the whole row and encrypts its token early); after the backfill has run, the previous
-- image resolves no token at all and the only way back is a database restore (.dump.gpg).
--
-- Column widths: a 35-char token becomes 132 chars of ciphertext, so every VARCHAR(64)/(40)
-- token column is widened to 255. Uniqueness on the token column is preserved (ciphertexts
-- are unique per row: random IV).

-- agent ----------------------------------------------------------------------------------
ALTER TABLE agent.agent_webhook_tokens ALTER COLUMN token TYPE VARCHAR(255);
ALTER TABLE agent.agent_webhook_tokens ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_agent_webhook_tokens_token_hash ON agent.agent_webhook_tokens (token_hash);

ALTER TABLE agent.agent_widget_configs ALTER COLUMN widget_token TYPE VARCHAR(255);
ALTER TABLE agent.agent_widget_configs ADD COLUMN IF NOT EXISTS widget_token_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_agent_widget_configs_widget_token_hash ON agent.agent_widget_configs (widget_token_hash);

-- publication ----------------------------------------------------------------------------
ALTER TABLE publication.shared_links ALTER COLUMN token TYPE VARCHAR(255);
ALTER TABLE publication.shared_links ALTER COLUMN resource_token TYPE VARCHAR(255);
ALTER TABLE publication.shared_links ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64);
ALTER TABLE publication.shared_links ADD COLUMN IF NOT EXISTS resource_token_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_shared_links_token_hash ON publication.shared_links (token_hash);
CREATE INDEX IF NOT EXISTS idx_shared_links_resource_token_hash ON publication.shared_links (resource_token_hash);

-- conversation ---------------------------------------------------------------------------
ALTER TABLE conversation.conversations ALTER COLUMN share_token TYPE VARCHAR(255);
ALTER TABLE conversation.conversations ADD COLUMN IF NOT EXISTS share_token_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_conversations_share_token_hash ON conversation.conversations (share_token_hash);

-- trigger --------------------------------------------------------------------------------
ALTER TABLE trigger.standalone_webhooks ALTER COLUMN token TYPE VARCHAR(255);
ALTER TABLE trigger.standalone_webhooks ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_standalone_webhooks_token_hash ON trigger.standalone_webhooks (token_hash);

ALTER TABLE trigger.standalone_chat_endpoints ALTER COLUMN token TYPE VARCHAR(255);
ALTER TABLE trigger.standalone_chat_endpoints ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_standalone_chat_endpoints_token_hash ON trigger.standalone_chat_endpoints (token_hash);

ALTER TABLE trigger.standalone_form_endpoints ALTER COLUMN token TYPE VARCHAR(255);
ALTER TABLE trigger.standalone_form_endpoints ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_standalone_form_endpoints_token_hash ON trigger.standalone_form_endpoints (token_hash);

-- webhook_tokens.token is already TEXT
ALTER TABLE trigger.webhook_tokens ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_webhook_tokens_token_hash ON trigger.webhook_tokens (token_hash);

-- auth -----------------------------------------------------------------------------------
-- organization_invitation.token is already VARCHAR(255)
ALTER TABLE auth.organization_invitation ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_organization_invitation_token_hash ON auth.organization_invitation (token_hash);

-- users.api_key: the plaintext predecessor of api_key_hash. No code reads or writes it
-- (User.java maps api_key_hash / api_key_hint / api_key_created_at only) and production holds
-- zero non-null values; a column that could only ever hold a secret in clear is dropped.
ALTER TABLE auth.users DROP COLUMN IF EXISTS api_key;

-- orchestrator ---------------------------------------------------------------------------
ALTER TABLE orchestrator.approval_channel_deliveries ALTER COLUMN callback_token TYPE VARCHAR(255);
ALTER TABLE orchestrator.approval_channel_deliveries ADD COLUMN IF NOT EXISTS callback_token_hash VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_approval_channel_deliveries_callback_token_hash
    ON orchestrator.approval_channel_deliveries (callback_token_hash);

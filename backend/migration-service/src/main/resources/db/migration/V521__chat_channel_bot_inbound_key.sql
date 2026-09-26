-- ============================================================================
-- V521: what a bot's inbound callbacks are verified with.
--
-- Telegram needed nothing: its webhook is set by us through its API, and the
-- button payload carries a token nobody can guess. The providers added next
-- are set up in the person's own console and authenticate their callbacks
-- with material that belongs to that one bot:
--
--   Discord   the application's PUBLIC key. Every interaction is signed with
--             Ed25519 and must be verified, and Discord refuses to save the
--             endpoint URL until it is. Public by definition, so stored as is.
--   WhatsApp  a verify token WE generate, which the person pastes into their
--             Meta app so the subscription handshake can be answered for this
--             bot and no other. It gates the handshake, not the messages.
--
-- Neither is a credential. Nothing here lets anyone act as the bot; the bot's
-- token stays in the credential store, where it always was.
-- ============================================================================

ALTER TABLE orchestrator.chat_channel_bots
    ADD COLUMN IF NOT EXISTS inbound_key VARCHAR(200);

COMMENT ON COLUMN orchestrator.chat_channel_bots.inbound_key IS
    'Per-bot inbound verification material: Discord application public key, or the '
    'WhatsApp webhook verify token. Not a secret credential. V521.';

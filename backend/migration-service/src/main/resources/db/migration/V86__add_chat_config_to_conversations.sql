-- Add per-conversation chat configuration (temperature, maxTokens, maxIterations, etc.)
-- Nullable: null means "use defaults"
ALTER TABLE conversation.conversations ADD COLUMN chat_config jsonb;

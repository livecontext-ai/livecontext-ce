-- ============================================================================
-- V10: Conversation Schema (consolidated)
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS conversation;
SET search_path TO conversation;

CREATE TABLE IF NOT EXISTS conversations (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    title VARCHAR(500),
    model VARCHAR(100),
    provider VARCHAR(100),
    workflow_id VARCHAR(255),
    agent_id VARCHAR(255),
    parent_conversation_id VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    pending_action JSONB,
    approved_services JSONB DEFAULT '[]'::jsonb,
    share_token VARCHAR(64) UNIQUE,
    share_mode VARCHAR(20) NOT NULL DEFAULT 'off',
    memory_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_conversations_user_id ON conversations(user_id);
CREATE INDEX IF NOT EXISTS idx_conversations_user_active_updated ON conversations(user_id, active, updated_at);

CREATE TABLE IF NOT EXISTS messages (
    id VARCHAR(255) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT,
    tool_calls TEXT,
    tool_call_id VARCHAR(100),
    tool_name VARCHAR(100),
    model VARCHAR(100),
    agent_id VARCHAR(255),
    execution_id VARCHAR(255),
    feedback SMALLINT,
    timestamp VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_messages_conversation_id ON messages(conversation_id);
CREATE INDEX IF NOT EXISTS idx_messages_conversation_created ON messages(conversation_id, created_at);

CREATE TABLE IF NOT EXISTS streams (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    stream_id VARCHAR(255) NOT NULL UNIQUE,
    user_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    error_message TEXT
);
CREATE INDEX IF NOT EXISTS idx_stream_conversation_status ON streams(conversation_id, status);
CREATE INDEX IF NOT EXISTS idx_stream_user_status ON streams(user_id, status);

CREATE TABLE IF NOT EXISTS tool_results (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id VARCHAR(255) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    tenant_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    tool_call_id VARCHAR(255),
    success BOOLEAN NOT NULL,
    duration_ms BIGINT,
    content_full TEXT,
    error_message TEXT,
    metadata JSONB,
    execution_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_tool_results_conversation ON tool_results(conversation_id);

CREATE TABLE IF NOT EXISTS message_attachments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id VARCHAR(255) NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    storage_id UUID NOT NULL,
    attachment_type VARCHAR(20) NOT NULL CHECK (attachment_type IN ('IMAGE', 'PDF', 'TEXT', 'OTHER')),
    file_name VARCHAR(255),
    mime_type VARCHAR(100),
    size_bytes INTEGER,
    display_order INTEGER DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_message_attachments_message_id ON message_attachments(message_id);

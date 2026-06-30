-- V26: Add embedded auth support (CE mode)
-- 1. Create refresh_tokens table for JWT refresh token rotation
-- 2. Add password_hash column to users for email+password auth

-- Refresh tokens for embedded auth (CE mode)
CREATE TABLE IF NOT EXISTS auth.refresh_tokens (
    id              BIGSERIAL PRIMARY KEY,
    token_hash      VARCHAR(64) NOT NULL UNIQUE,
    user_id         BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    expires_at      TIMESTAMP NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at      TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    user_agent      VARCHAR(512),
    ip_address      VARCHAR(45)
);

CREATE INDEX IF NOT EXISTS idx_refresh_token_hash ON auth.refresh_tokens(token_hash);
CREATE INDEX IF NOT EXISTS idx_refresh_token_user ON auth.refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_token_expires ON auth.refresh_tokens(expires_at);

-- Add password_hash to users for local email+password authentication
ALTER TABLE auth.users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);

COMMENT ON TABLE auth.refresh_tokens IS 'Refresh tokens for embedded JWT auth (CE mode)';
COMMENT ON COLUMN auth.users.password_hash IS 'BCrypt password hash for local email+password auth (CE mode)';

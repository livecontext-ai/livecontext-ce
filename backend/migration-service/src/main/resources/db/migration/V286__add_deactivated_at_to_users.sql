-- V286: Add deactivated_at timestamp to users for 30-day grace period before hard-delete
ALTER TABLE auth.users ADD COLUMN IF NOT EXISTS deactivated_at TIMESTAMP;

-- Index for the purge cron (find accounts past grace period)
CREATE INDEX IF NOT EXISTS idx_users_deactivated_at
    ON auth.users (deactivated_at)
    WHERE deactivated_at IS NOT NULL AND enabled = false;

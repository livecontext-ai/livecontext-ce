-- V270 -- Default CLI-bridge policy: 'admin_only' instead of 'disabled'.
--
-- Context:
-- V118 seeded the four CLI bridges (claude-code, codex, gemini-cli,
-- mistral-vibe) as 'disabled' to opt-in by default. In practice this means
-- every fresh CE / Cloud / prod install ships with bridges locked, and a
-- workflow agent using e.g. claude-code fails with:
--   "Bridge agent execution error: Bridge access denied for claude-code:
--    bridge_disabled"
-- even when the caller is the admin who actually owns the Claude Pro
-- subscription powering the bridge host. The admin had to remember to flip
-- each bridge via PUT /api/bridge-access/{provider} after every install,
-- which nobody does - turning the safety default into a permanent footgun.
--
-- The shared-subscription drain risk that motivated 'disabled' is still
-- closed by 'admin_only' (only ADMIN-role users dispatch) plus an explicit
-- daily quota that the admin can layer on top. Non-admin users remain
-- gated. Admin already trusts themselves with their own subscription.
--
-- Idempotent + non-destructive:
--   - Only rows still marked as seeded ('updated_by' = 'system:v118') flip
--     to 'admin_only'. Any policy already touched by an admin keeps its
--     current mode untouched.
--   - 'updated_by' is rewritten to 'system:v270' so a later admin-driven
--     change to 'disabled' or 'all_users' won't be re-flipped on the next
--     upgrade.
--   - Bridges added after V118 (none today, but the table is open-ended)
--     are not affected unless they happen to carry the V118 marker.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

UPDATE auth.bridge_access_policy
SET access_mode = 'admin_only',
    updated_at  = NOW(),
    updated_by  = 'system:v270'
WHERE access_mode = 'disabled'
  AND updated_by  = 'system:v118';

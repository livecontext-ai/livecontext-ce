-- V312: Per-(user, workspace) default chat options.
--
-- Self-service personal defaults: each user sets their own default chat options
-- (temperature, tools mode, web search, image generation, turn limits, …) PER
-- workspace, in the account Preferences page. These seed the message composer +
-- every new conversation created in that workspace.
--
-- Scope rationale: keyed (user_id, organization_id) because the existing chat-config
-- "draft" already resets on workspace switch (useOrgScopedReset) and the config can
-- embed org-scoped resources (default skills) and plan-gated choices (models, image
-- gen) - a single user-global default would reference things invalid in another
-- workspace. Each user edits only their own row; no cross-member impact.

CREATE TABLE IF NOT EXISTS conversation.user_chat_defaults (
    user_id          VARCHAR(255) NOT NULL,
    organization_id  VARCHAR(255) NOT NULL,
    config           JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, organization_id)
);

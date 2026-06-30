-- V215 - PR22c R3 closeout: webhook_tokens.organization_id
--
-- Context: PR22 R1 audit flagged WebhookDispatchService.dispatch (pinned branch)
-- as having no cross-scope token guard. PR22b added organizationId to
-- WebhookTokenDto and a guard that compares tokenOrg against waitingRun.orgId.
--
-- The R2 audit caught that the guard ships as dead code because
-- WebhookTokenEntity had no organization_id column and toTokenDto never set the
-- field. This migration adds the column so the guard becomes load-bearing:
-- newly created pinned tokens carry the workspace tag, and WebhookDispatchService
-- can refuse cross-scope fires.
--
-- Backfill policy: NULL is intentional for pre-existing rows. The dispatch
-- guard is permissive on tokenOrg=NULL, so legacy and multi-DAG tokens stay
-- functional. Only new tokens created post-deploy carry the tag. A future
-- backfill from the parent workflow pinned-run org_id can be run as a one-off
-- if needed.
--
-- Index choice: partial index on non-null values matches the access pattern
-- (org-scoped lookups skip legacy NULL rows entirely).

ALTER TABLE trigger.webhook_tokens
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_webhook_tokens_organization_id
    ON trigger.webhook_tokens (organization_id)
    WHERE organization_id IS NOT NULL;

COMMENT ON COLUMN trigger.webhook_tokens.organization_id IS
    'PR22c R3 - workspace tag for cross-scope dispatch guard. NULL = personal scope or legacy pre-PR22c-deploy token (dispatch guard is permissive on NULL; pinned run org_id is the canonical scope in that case).';

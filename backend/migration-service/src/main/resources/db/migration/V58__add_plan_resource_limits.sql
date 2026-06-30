-- ============================================================================
-- V57: Per-plan resource creation limits
-- Adds 5 columns on auth.plan to cap how many resources of each type a user
-- on a given plan can create. NULL = unlimited (consistent with included_*).
-- Counted resources: workflows, agents, datasources, interfaces, applications.
-- "Applications" = publications acquired from the marketplace (not published).
-- Adjust limits live with: UPDATE auth.plan SET max_workflows = X WHERE code = 'FREE';
-- ============================================================================

SET search_path TO auth;

ALTER TABLE plan ADD COLUMN IF NOT EXISTS max_workflows    INTEGER;
ALTER TABLE plan ADD COLUMN IF NOT EXISTS max_agents       INTEGER;
ALTER TABLE plan ADD COLUMN IF NOT EXISTS max_datasources  INTEGER;
ALTER TABLE plan ADD COLUMN IF NOT EXISTS max_interfaces   INTEGER;
ALTER TABLE plan ADD COLUMN IF NOT EXISTS max_applications INTEGER;

-- Seed values per plan (NULL = unlimited).
-- Tweakable later via plain UPDATE without redeploy.
UPDATE plan SET max_workflows = 5,   max_agents = 3,   max_datasources = 3,   max_interfaces = 3,   max_applications = 3   WHERE code = 'FREE';
UPDATE plan SET max_workflows = 25,  max_agents = 10,  max_datasources = 10,  max_interfaces = 10,  max_applications = 15  WHERE code = 'STARTER';
UPDATE plan SET max_workflows = 100, max_agents = 50,  max_datasources = 50,  max_interfaces = 50,  max_applications = 50  WHERE code = 'PRO';
UPDATE plan SET max_workflows = 500, max_agents = 250, max_datasources = 250, max_interfaces = 250, max_applications = 250 WHERE code = 'TEAM';

-- ENTERPRISE_* and PAYG: leave NULL (= unlimited)
-- CREDIT_PACK / CREDIT_PACK_TEAM: leave NULL (addon plans, base plan governs)

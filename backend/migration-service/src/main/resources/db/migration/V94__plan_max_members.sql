-- V94: seed per-plan member limits.
--
-- V3 created auth.plan with max_members DEFAULT 1, and V58 (add_plan_resource_limits) set
-- per-plan limits for workflows/agents/datasources/interfaces/applications but left
-- max_members at 1 for every plan. As a result even a correctly-subscribed TEAM owner is
-- rejected by OrganizationMemberService.inviteMember with "Member limit reached (1)"
-- before any member can be added. This migration sets sensible defaults so plans that
-- Plan.supportsTeam() reports as team-capable actually have seats to fill.
--
-- Numbers are intentionally conservative - product can raise them later without a schema
-- change. FREE/STARTER/PRO/PAYG remain at 1 (no team support - Plan.supportsTeam()
-- returns false for them, so the limit is moot but we keep it aligned with intent).

UPDATE auth.plan SET max_members = 10  WHERE code = 'TEAM';
UPDATE auth.plan SET max_members = 25  WHERE code = 'ENTERPRISE_BASIC';
UPDATE auth.plan SET max_members = 50  WHERE code = 'ENTERPRISE_STANDARD';
UPDATE auth.plan SET max_members = 100 WHERE code = 'ENTERPRISE_PREMIUM';
UPDATE auth.plan SET max_members = 500 WHERE code = 'ENTERPRISE_ULTIMATE';

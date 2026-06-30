-- ============================================================================
-- V335: Per-plan cap on how many marketplace publications an org may PUBLISH.
-- Distinct from max_applications (= publications ACQUIRED from the marketplace).
-- Enforced by EntitlementGuard (ResourceType.PUBLICATION) on first publish of a
-- workflow/application; cloud-only (CE-free = unlimited). NULL = unlimited.
-- Deliberately generous on paid tiers (publishing is free of credit cost; the
-- cap is an anti-abuse ceiling, not a monetization lever).
-- Adjust live with: UPDATE auth.plan SET max_publications = X WHERE code = 'PRO';
-- ============================================================================

SET search_path TO auth;

ALTER TABLE plan ADD COLUMN IF NOT EXISTS max_publications INTEGER;

-- Seed values per plan (NULL = unlimited). Generous on STARTER/PRO/TEAM.
UPDATE plan SET max_publications = 3    WHERE code = 'FREE';
UPDATE plan SET max_publications = 50   WHERE code = 'STARTER';
UPDATE plan SET max_publications = 300  WHERE code = 'PRO';
UPDATE plan SET max_publications = 2000 WHERE code = 'TEAM';

-- ENTERPRISE_* and PAYG: leave NULL (= unlimited).
-- CREDIT_PACK / CREDIT_PACK_TEAM: leave NULL (addon plans, base plan governs).

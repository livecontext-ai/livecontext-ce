-- V197: raise TEAM max_members from 10 to 25.
--
-- V94 set TEAM at 10. Industry norm for workflow-automation tools (n8n, Zapier
-- Team, Make Teams) is unlimited seats; 10 was conservative and pushed teams to
-- ENTERPRISE_BASIC purely on headcount even when their usage didn't justify the
-- upgrade. Raising to 25 matches ENTERPRISE_BASIC's seat count, shifting the
-- ENTERPRISE differentiation onto credits/storage/SSO/SLA rather than seat-count.
--
-- ENTERPRISE_BASIC stays at 25 seats - it still differentiates via 50k credits
-- (10x TEAM) and 500 GB storage (5x TEAM).

UPDATE auth.plan SET max_members = 25 WHERE code = 'TEAM';

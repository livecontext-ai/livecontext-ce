-- V348: Seed the "travel" marketplace category (flights, hotels, stays, destinations).
--
-- Companion to V300's 12-category taxonomy. Added after a prod re-categorization of
-- the public marketplace applications surfaced a cluster (flight / hotel / short-stay /
-- destination finders) with no good home under the original categories. Travel was
-- first inserted directly in prod; this migration keeps the codebase and every other
-- environment (CE, fresh installs) in sync.
--
-- The id matches the value already live in prod (the a0000000-…-0000000000NN series;
-- 0x13 here rather than the next hex 0x0d, to mirror prod exactly). icon_slug is
-- kebab-case → resolved to a Lucide icon by getCategoryIcon().
-- Idempotent: ON CONFLICT (slug) DO NOTHING (slug is UNIQUE since V1; no-op in prod).

INSERT INTO orchestrator.workflow_categories
    (id, slug, name, description, icon_slug, color, display_order, is_active, created_at, updated_at)
VALUES
    ('a0000000-0000-4000-8000-000000000013', 'travel', 'Travel', 'Flights, hotels, stays and destination guides.', 'plane', '#06b6d4', 125, TRUE, now(), now())
ON CONFLICT (slug) DO NOTHING;

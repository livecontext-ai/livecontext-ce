-- V534: CE cloud link "plan required" state (self-hosted side, publication-service).
--
-- Linking a self-hosted install to LiveContext Cloud is reserved to paid cloud accounts. When the
-- cloud answers register or heartbeat with 403 CLOUD_LINK_PLAN_REQUIRED, the CE keeps the link
-- (tokens, registered_at, llm_source, catalog_source) and only records that it is SUSPENDED until
-- the owner pays again; the next 2xx from the cloud clears both columns, so paying restores the
-- link with no re-link. Read by CloudLinkService.getLinkStatus (planRequired, planRequiredPlanCode).
--
-- Every reference is schema-qualified: beforeEachMigrate resets search_path to orchestrator, so an
-- unqualified name would resolve against the wrong schema (the V381 lesson). Idempotent.

ALTER TABLE publication.ce_cloud_links
    ADD COLUMN IF NOT EXISTS plan_required_at        TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS plan_required_plan_code VARCHAR(50) NULL;

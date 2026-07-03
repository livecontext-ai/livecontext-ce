-- V381: persist the signed catalog-bundle payload at BUILD time.
--
-- Until now catalog_bundles stored only metadata and the payload was
-- re-derived from the LIVE model_config_overrides table at every download.
-- Consequence: ANY later edit of the catalog (admin Models panel, provider
-- feed sync) made the freshly-derived bytes diverge from the stored checksum,
-- turning EVERY bundle download - latest AND historical versions - into an
-- HTTP 409 until an operator manually rebuilt. With the payload persisted,
-- serving never depends on the live table: old versions stay servable
-- forever and catalog edits only mean "a newer bundle can be built", which
-- the auto-rebuild scheduler now does.
--
-- Existing rows keep payload NULL (legacy): the serve path returns a clear
-- "republishing" error for them and the auto-rebuild scheduler treats a
-- NULL-payload active bundle as stale, so installs self-heal on its next tick.
-- Both tables live in the agent schema; beforeEachMigrate resets search_path
-- to orchestrator, so references MUST be schema-qualified (or SET search_path).
ALTER TABLE agent.catalog_bundles ADD COLUMN IF NOT EXISTS payload TEXT;

-- Cloud-admin-only fine control over WHAT the bundle ships, decoupled from the
-- cloud's own greying: bundle_enabled = NULL inherits the row's enabled (the
-- historical behavior), TRUE ships the model ENABLED to CE installs even when
-- the cloud greys it locally, FALSE ships it disabled even when the cloud uses
-- it. The bundle payload carries the EFFECTIVE enabled (bundle_enabled
-- overriding enabled) - this column itself is never serialized, so CE-side
-- rows and user_modified_fields semantics are untouched.
ALTER TABLE agent.model_config_overrides ADD COLUMN IF NOT EXISTS bundle_enabled BOOLEAN;

-- V515: the model a DISABLED model is replaced by at execution time.
--
-- Disabling a model (enabled = false) only removed it from the pickers. Every place that
-- had already stored it (agents, workflow agent / classify / guardrail nodes, sub-agents,
-- chat endpoints, compaction overrides) kept sending it verbatim, so the day an admin
-- turned one off every run on it failed, at the provider or at a bridge access check the
-- catalog's provider normalisation rerouted it into.
--
-- ModelReplacementResolver now swaps an explicitly disabled pair for the pair named here
-- before anything else looks at the model (provider normalisation, execution links,
-- budget guards, billing). NULL = no explicit choice, the platform default model is used.
-- The replacement is itself resolved through the execution links, so a replacement that
-- is linked to a CLI bridge executes there and is billed as the replacement.
--
-- Admin-local: never carried by a catalog bundle, so no bundle merge can clobber it.
-- On a self-hosted (CE) install the ENABLED flag does travel in the bundle, so a model the
-- cloud ships disabled is swapped there too: for the CE admin's own replacement if set,
-- else the install's platform default (which may be another provider key).
--
-- beforeEachMigrate resets search_path to orchestrator, so references MUST be
-- schema-qualified.

ALTER TABLE agent.model_config_overrides
    ADD COLUMN IF NOT EXISTS replacement_provider VARCHAR(50),
    ADD COLUMN IF NOT EXISTS replacement_model    VARCHAR(150);

COMMENT ON COLUMN agent.model_config_overrides.replacement_provider IS
    'Provider of the model that replaces this one at execution time while it is disabled. NULL with replacement_model NULL = fall back to the platform default model (V515).';
COMMENT ON COLUMN agent.model_config_overrides.replacement_model IS
    'Model id that replaces this one at execution time while it is disabled (V515).';

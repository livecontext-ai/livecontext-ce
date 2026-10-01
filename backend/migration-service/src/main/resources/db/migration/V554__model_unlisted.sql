-- V554: an UNLISTED model - still available, no longer offered.
--
-- Until now a model had two runtime states: enabled (in every picker) and disabled (out of
-- every picker, and every run that still names it swapped for its replacement, V515). An
-- admin had no way to stop promoting an older model without also moving the agents,
-- workflow nodes and chats that use it onto another one.
--
-- unlisted = true on an ENABLED row is that third state: the model leaves the picker lists
-- (the chat composer still shows it, in a collapsed "hidden models" group), it is never the
-- platform default, and agents are not offered it; but it stays runnable, so everything
-- that already uses it keeps running on it. Because the row stays enabled, its V515
-- replacement is kept and simply not applied (ModelReplacementResolver only reads disabled
-- or deprecated rows). Switching the model back off makes the replacement apply again.
--
-- enabled = false wins: an unlisted flag on a disabled row changes nothing.
--
-- It travels to self-hosted (CE) installs in the catalog bundle, beside the `enabled` flag it
-- qualifies (unlisting an OFF model turns `enabled` back on, so without it a CE would list the
-- model). Only the bundle and the curated seed apply it, never a feed sync, and a CE admin's
-- own choice is protected by user_modified_fields.
--
-- beforeEachMigrate resets search_path to orchestrator, so references MUST be
-- schema-qualified.

ALTER TABLE agent.model_config_overrides
    ADD COLUMN IF NOT EXISTS unlisted BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN agent.model_config_overrides.unlisted IS
    'TRUE on an enabled row: available and runnable, but not offered in the pickers, never the platform default, and its V515 replacement is not applied (V554). Ignored while enabled = false.';

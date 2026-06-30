-- Pre-migration for V109 model-catalog refactor.
-- V109 promotes display_name to NOT NULL. Backfill first to avoid downtime on prod rows.
-- Safe to rerun: no-op if no NULL rows remain.

SET lock_timeout = '5s';
SET statement_timeout = '60s';

UPDATE agent.model_config_overrides
SET display_name = model_id
WHERE display_name IS NULL;

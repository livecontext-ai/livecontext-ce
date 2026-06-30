-- ============================================================================
-- V96: Fix event_types column type on trigger.datasource_trigger_subscriptions.
--   V95 was edited post-apply from TEXT[] to JSONB. `CREATE TABLE IF NOT EXISTS`
--   no-op'd the re-create, leaving the column as text[] while the entity
--   (DatasourceTriggerSubscriptionEntity) writes JSONB. Convert in place using
--   to_jsonb() so existing arrays (if any) survive the migration.
-- ============================================================================

DO $$
DECLARE
    col_type text;
BEGIN
    SELECT data_type INTO col_type
    FROM information_schema.columns
    WHERE table_schema = 'trigger'
      AND table_name   = 'datasource_trigger_subscriptions'
      AND column_name  = 'event_types';

    IF col_type = 'ARRAY' THEN
        ALTER TABLE "trigger".datasource_trigger_subscriptions
            ALTER COLUMN event_types TYPE JSONB USING to_jsonb(event_types);
    END IF;
END $$;

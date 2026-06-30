-- Re-apply V35 columns that were skipped due to Flyway repair
-- Uses IF NOT EXISTS / safe checks to be idempotent

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'trigger' AND table_name = 'scheduled_executions'
                   AND column_name = 'agent_entity_id') THEN
        ALTER TABLE trigger.scheduled_executions ADD COLUMN agent_entity_id UUID;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'trigger' AND table_name = 'scheduled_executions'
                   AND column_name = 'schedule_prompt') THEN
        ALTER TABLE trigger.scheduled_executions ADD COLUMN schedule_prompt TEXT;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'trigger' AND table_name = 'scheduled_executions'
                   AND column_name = 'with_memory') THEN
        ALTER TABLE trigger.scheduled_executions ADD COLUMN with_memory BOOLEAN NOT NULL DEFAULT false;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_scheduled_executions_agent_entity_id
    ON trigger.scheduled_executions (agent_entity_id)
    WHERE agent_entity_id IS NOT NULL;

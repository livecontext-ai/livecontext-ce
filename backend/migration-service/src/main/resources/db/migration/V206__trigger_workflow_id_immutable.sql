-- ============================================================================
-- V206: Make workflow_id immutable on standalone trigger rows
--
-- Closes the F4 PUB-HIJACK class of bugs at the database layer.
--
-- Prior bug shape: at publish time, an application clone inherited
-- params.scheduleId from the source plan verbatim. The first sync of the
-- clone called StandaloneScheduleService.updateWorkflowReference which
-- silently overwrote workflow_id from source to clone. No audit log, no
-- exception, no notification. Source workflow went silently inert.
--
-- This migration installs a BEFORE UPDATE trigger on each of the 4 standalone
-- trigger tables that raises an exception if workflow_id is changed on an
-- existing row. Combined with the deletion of updateWorkflowReference from
-- the 4 standalone services (orchestrator-side change in same release), this
-- makes the silent rebind class of bugs PHYSICALLY IMPOSSIBLE.
--
-- Legitimate "ownership transfer" (rare admin/backfill scenarios) must
-- delete + recreate the row, not UPDATE. For one-off backfills, the operator
-- temporarily disables the trigger within a single transaction:
--
--   BEGIN;
--   ALTER TABLE trigger.scheduled_executions
--     DISABLE TRIGGER scheduled_executions_workflow_id_immutable;
--   UPDATE trigger.scheduled_executions SET workflow_id = '...' WHERE id = '...';
--   ALTER TABLE trigger.scheduled_executions
--     ENABLE TRIGGER scheduled_executions_workflow_id_immutable;
--   COMMIT;
--
-- See the project docs for the full design.
-- ============================================================================

SET search_path TO trigger;

-- Reusable trigger function: raise on any attempted workflow_id rewrite
-- (NULL -> non-NULL is allowed: that's the initial INSERT/UPDATE landing).
CREATE OR REPLACE FUNCTION raise_immutable_workflow_id()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.workflow_id IS NOT NULL
       AND NEW.workflow_id IS DISTINCT FROM OLD.workflow_id THEN
        RAISE EXCEPTION 'workflow_id is immutable on %.%: cannot change from % to %',
            TG_TABLE_SCHEMA, TG_TABLE_NAME, OLD.workflow_id, NEW.workflow_id
            USING ERRCODE = 'check_violation',
                  HINT    = 'Use delete + recreate, not UPDATE. For backfill, temporarily DISABLE this trigger within a single transaction.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Apply the trigger to all 4 standalone trigger tables.
-- Each table has its own trigger object pointing at the shared function so
-- operators can DISABLE/ENABLE per-table without affecting siblings.

CREATE TRIGGER scheduled_executions_workflow_id_immutable
    BEFORE UPDATE ON scheduled_executions
    FOR EACH ROW EXECUTE FUNCTION raise_immutable_workflow_id();

CREATE TRIGGER standalone_webhooks_workflow_id_immutable
    BEFORE UPDATE ON standalone_webhooks
    FOR EACH ROW EXECUTE FUNCTION raise_immutable_workflow_id();

CREATE TRIGGER standalone_chat_endpoints_workflow_id_immutable
    BEFORE UPDATE ON standalone_chat_endpoints
    FOR EACH ROW EXECUTE FUNCTION raise_immutable_workflow_id();

CREATE TRIGGER standalone_form_endpoints_workflow_id_immutable
    BEFORE UPDATE ON standalone_form_endpoints
    FOR EACH ROW EXECUTE FUNCTION raise_immutable_workflow_id();

COMMENT ON FUNCTION raise_immutable_workflow_id() IS
    'Enforces workflow_id immutability on standalone trigger ownership rows. '
    'The only legitimate "rebind" is delete + recreate. For one-off backfills, '
    'temporarily DISABLE the per-table trigger within a single tx. See '
    'the project docs (F4 PUB-HIJACK fix).';

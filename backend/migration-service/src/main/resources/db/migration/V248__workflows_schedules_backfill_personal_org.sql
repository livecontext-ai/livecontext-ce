-- V248 - Backfill orchestrator.workflows + trigger.scheduled_executions rows whose
-- organization_id stayed NULL after V218.
--
-- Context: V218 (2026-05-14) backfilled organization_id on rows that already
-- existed at deploy time. Rows created AFTER V218 by paths that forgot to
-- stamp the column (WorkflowManagementService.saveDraft, the public-facing
-- ScheduleController.createOrUpdateSchedule, and TriggerClient.createOrUpdateSchedule
-- without an orgId arg - all closed in the companion code commit) landed
-- NULL-org and stayed invisible to org-scoped list endpoints (bell Triggers
-- tab, WorkflowListController, ActiveAutomationsService, etc), even though
-- the schedule fired correctly because the trigger dispatcher does not filter
-- on organization_id at runtime.
--
-- Strategy mirrors V218: route each NULL-org row to the row owner's default
-- organization. The predicate joins on auth.organization_member.is_default = true
-- (matches V218's convention) - an onboarded user's personal org is the default
-- membership row created by OrganizationService.createPersonalOrganization at
-- signup. Rows whose tenant_id is not a plain integer (system rows, fixtures,
-- '_publications' marker) are skipped - same predicate V218 uses.
--
-- Paths that landed NULL-org and are closed in the companion code commit:
--   * WorkflowManagementService.saveDraft (orchestrator) - auto-draft and
--     manual-draft REST/MCP entry points.
--   * WorkflowBuilderProvider.finish (orchestrator) - agent-builder save path.
--   * ScheduleController.createOrUpdateSchedule (trigger-service) - public REST.
--   * TriggerClient.createOrUpdateSchedule (orchestrator → trigger-service wire)
--     used by ScheduleSyncService when reconciling schedules from a pinned plan.
--   * WorkflowRunPersistenceService.createWorkflowEntity +
--     WorkflowEntityResolverService.createWorkflowEntity (runtime auto-stub).
--
-- Idempotent: WHERE organization_id IS NULL - re-runs are no-ops once applied.
-- Defensive against deleted users: skipped if no default-org membership row
-- exists for the tenant.

UPDATE orchestrator.workflows w
   SET organization_id = (
         SELECT om.organization_id::text
           FROM auth.organization_member om
          WHERE om.user_id = w.tenant_id::bigint
            AND om.is_default = true
          LIMIT 1)
 WHERE w.organization_id IS NULL
   AND w.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
         SELECT 1
           FROM auth.organization_member om
          WHERE om.user_id = w.tenant_id::bigint
            AND om.is_default = true);

UPDATE trigger.scheduled_executions s
   SET organization_id = (
         SELECT om.organization_id::text
           FROM auth.organization_member om
          WHERE om.user_id = s.tenant_id::bigint
            AND om.is_default = true
          LIMIT 1)
 WHERE s.organization_id IS NULL
   AND s.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
         SELECT 1
           FROM auth.organization_member om
          WHERE om.user_id = s.tenant_id::bigint
            AND om.is_default = true);

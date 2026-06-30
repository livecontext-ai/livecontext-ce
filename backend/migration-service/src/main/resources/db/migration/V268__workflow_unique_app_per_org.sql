-- V268 - closes the "duplicate APPLICATION clone per (org, publication)" data shape
-- that caused IncorrectResultSizeDataAccessException on
-- findByOrganizationIdAndSourcePublicationIdAndWorkflowType (Optional<WorkflowEntity>
-- threw NonUniqueResult when 2+ rows existed). Pre-Round-11 acquire flow allowed
-- two workflows in the same (org, source_pub) bucket via tenant-only
-- hasReceiptInScope race; Round-11 closed the code path but historical data could
-- still pollute. Operator-facing symptom: 404 on /app/applications/{pubId}.
--
-- CONCURRENTLY skipped on purpose - workflows table is ~32 rows in prod and the
-- short table-level lock is preferable to the Flyway transactional=false dance.

CREATE UNIQUE INDEX IF NOT EXISTS uq_workflow_org_source_pub_application
    ON orchestrator.workflows (organization_id, source_publication_id)
    WHERE workflow_type = 'APPLICATION' AND source_publication_id IS NOT NULL;

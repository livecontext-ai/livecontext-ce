-- V532: index for CASA LC-037 gap 2's lineage query (StorageRepository.existsSubWorkflowInvocation).
--
-- The query:
--   SELECT EXISTS(SELECT 1 FROM storage.storage s
--   WHERE s.workflow_id = :parentWorkflowId AND s.source_type = 'STEP_OUTPUT'
--   AND s.status = 'ACTIVE' AND s.data ->> 'subRunId' = :childRunId)
--
-- has no usable index today. storage.storage IS indexed on workflow_id, but only as
-- idx_storage_tenant_workflow (tenant_id, workflow_id, created_at DESC) WHERE status = 'ACTIVE' -
-- this call has no tenant_id predicate (the share-context caller is the OWNER's tenant, resolved
-- one hop away in orchestrator, not passed down here), so that composite index's leading column
-- can never be constrained and the planner falls back to a full scan.
--
-- WHAT WAS MEASURED (scratch DB, 300k synthetic storage.storage rows, mixed source_type/status,
-- see CASA batch report for the exact script): before this index, EXPLAIN (ANALYZE, BUFFERS) shows
-- a parallel seq scan, 281.8 ms execution, 6569 buffer hits. After, a bitmap index scan on
-- (workflow_id, source_type, status) narrows to ~5-8 candidate rows before the (unindexed,
-- unindexable - JSONB operator) subRunId filter runs over them: 8.5 ms, 8 buffer hits. This
-- endpoint is on the hot path of every file render inside a shared application that uses
-- sub_workflow (storage-service's FileController/MonolithFileController call it as a fallback on
-- every such file, per CASA LC-037 gap 2), so it is not a rarely-hit admin query.
--
-- NOT partial on status = 'ACTIVE': the query's status predicate is a fixed literal today, but a
-- 3-column composite already gives the planner an exact-match index scan for this call; narrowing
-- further would only save index size, not shape, and would need a rebuild the day a second status
-- value needs the same query.
--
-- CONCURRENTLY (flyway:executeInTransaction=false): SHARE UPDATE EXCLUSIVE, so concurrent reads
-- and writes on storage.storage proceed during the build - the same convention as V509
-- (idx_wsd_start_time / idx_agent_tool_calls_created_at). On crash the index is left INVALID;
-- recovery is DROP INDEX + re-run V532 (flyway:repair if needed).

-- flyway:executeInTransaction=false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_storage_sub_workflow_lineage
    ON storage.storage (workflow_id, source_type, status);

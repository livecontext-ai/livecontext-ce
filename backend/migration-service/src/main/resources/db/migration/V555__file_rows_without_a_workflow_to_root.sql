-- V555: move files filed under a workflow that does not exist to the root of the Files browser.
--
-- The Files browser shows a workflow's files in a folder named after the workflow and drops a
-- folder whose workflow it cannot find. Two producers stamped files with a workflow id that
-- names no workflow, so those files existed, were counted in the storage quota, were listed by
-- the agent's files tool, and were invisible to the user:
--   * an ad-hoc node run from chat (run id 'adhoc-<uuid>'), filed under its synthetic plan id;
--   * the file tools called outside a workflow, filed under the literal 'unknown'.
-- Measured in production on 2026-09-30: 5 ad-hoc rows and 130 'unknown' rows (824 MB).
-- StorageService.saveS3FileIndex now files such rows at the root (WorkflowFileScope); this
-- repairs the ones already written.
--
-- Only workflow_id and run_id change. s3_key keeps pointing at the same object, and neither the
-- quota breakdown (keyed by category, not workflow) nor retention (an s3_key vetoes a purge)
-- depends on the two columns. Rows whose workflow was DELETED are left alone: their UUID is
-- well formed and not ad-hoc, so this predicate does not match them. A Data Input upload made
-- before its workflow was saved ('draft') matches too and is also moved to the root, where it
-- becomes visible for the first time.
--
-- Out of scope: rows filed under a well-formed UUID that is not a workflow (RunCloneService's
-- extra index row under the new run id, acquire-clone copies under a publication id). Neither
-- rule can tell those from a real workflow; they need a fix in their producers.

UPDATE storage.storage
SET workflow_id = NULL,
    run_id = NULL
WHERE storage_type = 'S3_FILE'
  AND workflow_id IS NOT NULL
  AND (run_id LIKE 'adhoc-%'
       OR workflow_id !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

-- V339 - repair workflow_type on acquire-clone rows (companion to the code fix
-- that stamps only the acquisition ROOT as APPLICATION).
--
-- Pre-fix, every workflow cloned during an acquisition was created as
-- workflow_type='APPLICATION' with the publication's id in source_publication_id.
-- Two data shapes need repair:
--
-- (a) ORPHAN CHILDREN from failed sub-workflow acquisitions. Post-V268, the
--     root insert hit uq_workflow_org_source_pub_application and the acquire
--     500'd - but each child was committed by its own internal call BEFORE the
--     collision, leaving exactly one APPLICATION child per failed attempt.
--     That orphan keeps the (org, publication) unique bucket occupied, so the
--     org can NEVER acquire that publication ("already acquired" / V268
--     duplicate key). Identify them as APPLICATION clones whose scope holds NO
--     receipt and that are not the publisher's own application fixture.
--
-- (b) WORKFLOWS CLONED BY AGENT PUBLICATIONS. An agent acquisition has no
--     application root at all; the fixed code stamps every such clone
--     WORKFLOW. Retype the legacy rows so old and new acquirers of the same
--     agent publication see those workflows in the same place (Workflows
--     page), and so the /acquired Applications list (now APPLICATION-typed)
--     stops surfacing them.
--
-- Publisher-side APPLICATION fixtures (created at publish, no receipt either)
-- are excluded via publisher_id = tenant_id.
--
-- Multi-APPLICATION (org, publication) duplicates cannot exist: V268's CREATE
-- UNIQUE INDEX would have failed on such data, so this repair only ever
-- retypes rows that are alone in their bucket or agent-publication clones.
--
-- Known narrow edges (accepted for a best-effort repair, both recoverable):
--  * False positive: an ORG-owned publication whose publisher APPLICATION
--    fixture was deleted and re-created by a TEAMMATE (fixture tenant_id ≠
--    publisher_id, no receipt) is retyped - the publisher's app card moves to
--    the Workflows page; the row itself is preserved.
--  * False negative: an orphan child from a failed RE-acquisition in a scope
--    that already holds a receipt is not retyped; the typed "already
--    acquired" probe plus the row-deleting compensation make new occurrences
--    impossible, and any legacy one can be retyped manually.

UPDATE orchestrator.workflows w
SET workflow_type = 'WORKFLOW'
WHERE w.workflow_type = 'APPLICATION'
  AND w.source_publication_id IS NOT NULL
  AND (
    -- (b) any clone of an AGENT publication
    EXISTS (
        SELECT 1 FROM publication.workflow_publications p
        WHERE p.id = w.source_publication_id
          AND p.publication_type = 'AGENT'
    )
    OR (
        -- (a) orphan child: no receipt in the row's scope...
        NOT EXISTS (
            SELECT 1 FROM publication.publication_receipts r
            WHERE r.publication_id = w.source_publication_id
              AND (
                  r.tenant_id = w.tenant_id
                  OR (r.organization_id IS NOT NULL AND r.organization_id = w.organization_id)
              )
        )
        -- ...and not the publisher's own application fixture
        AND NOT EXISTS (
            SELECT 1 FROM publication.workflow_publications p2
            WHERE p2.id = w.source_publication_id
              AND p2.publisher_id = w.tenant_id
        )
    )
  );

-- ============================================================================
-- V136: Race-safe uniqueness for standalone trigger resources.
--
-- Context
-- -------
-- The four standalone trigger tables (standalone_webhooks, standalone_chat_endpoints,
-- standalone_form_endpoints, scheduled_executions) store a `source_node_id` set by
-- the builder to dedup "same React-Flow node ⇒ same row" across rerenders, remounts
-- and page refreshes. App-level dedup (findByTenantIdAndSourceNodeId) is advisory
-- and races under React StrictMode double-mount + dual-create paths (palette click +
-- form effect). Result: silent quota burn.
--
-- Fix
-- ---
-- 1) Collapse any pre-existing duplicates on (tenant_id, source_node_id) so the
--    unique index can be created. Keep the oldest row (the one the rest of the
--    system most likely already references), reattach its workflow_id from the
--    most recently linked duplicate if the keeper has NULL (heal orphans).
-- 2) Create a partial unique index on (tenant_id, source_node_id) where
--    source_node_id IS NOT NULL. Partial because legacy rows may still have NULL
--    and the app never deduplicates on NULL.
-- ============================================================================

BEGIN;

-- ---- standalone_webhooks -----------------------------------------------------
WITH ranked AS (
    SELECT id, tenant_id, source_node_id, workflow_id, created_at,
           ROW_NUMBER() OVER (
               PARTITION BY tenant_id, source_node_id
               ORDER BY created_at ASC, id ASC
           ) AS rn,
           FIRST_VALUE(workflow_id) OVER (
               PARTITION BY tenant_id, source_node_id
               ORDER BY (workflow_id IS NULL), created_at DESC
           ) AS best_workflow_id
      FROM "trigger".standalone_webhooks
     WHERE source_node_id IS NOT NULL
),
to_keep AS (
    SELECT id, best_workflow_id FROM ranked WHERE rn = 1 AND workflow_id IS NULL AND best_workflow_id IS NOT NULL
),
to_delete AS (
    SELECT id FROM ranked WHERE rn > 1
)
UPDATE "trigger".standalone_webhooks sw
   SET workflow_id = tk.best_workflow_id
  FROM to_keep tk
 WHERE sw.id = tk.id;

DELETE FROM "trigger".standalone_webhooks
 WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY tenant_id, source_node_id
                   ORDER BY created_at ASC, id ASC
               ) AS rn
          FROM "trigger".standalone_webhooks
         WHERE source_node_id IS NOT NULL
    ) x
    WHERE x.rn > 1
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_standalone_webhooks_tenant_source_node
    ON "trigger".standalone_webhooks (tenant_id, source_node_id)
    WHERE source_node_id IS NOT NULL;


-- ---- standalone_chat_endpoints ----------------------------------------------
WITH ranked AS (
    SELECT id, tenant_id, source_node_id, workflow_id, created_at,
           ROW_NUMBER() OVER (
               PARTITION BY tenant_id, source_node_id
               ORDER BY created_at ASC, id ASC
           ) AS rn,
           FIRST_VALUE(workflow_id) OVER (
               PARTITION BY tenant_id, source_node_id
               ORDER BY (workflow_id IS NULL), created_at DESC
           ) AS best_workflow_id
      FROM "trigger".standalone_chat_endpoints
     WHERE source_node_id IS NOT NULL
),
to_keep AS (
    SELECT id, best_workflow_id FROM ranked WHERE rn = 1 AND workflow_id IS NULL AND best_workflow_id IS NOT NULL
)
UPDATE "trigger".standalone_chat_endpoints sce
   SET workflow_id = tk.best_workflow_id
  FROM to_keep tk
 WHERE sce.id = tk.id;

DELETE FROM "trigger".standalone_chat_endpoints
 WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY tenant_id, source_node_id
                   ORDER BY created_at ASC, id ASC
               ) AS rn
          FROM "trigger".standalone_chat_endpoints
         WHERE source_node_id IS NOT NULL
    ) x
    WHERE x.rn > 1
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_standalone_chat_endpoints_tenant_source_node
    ON "trigger".standalone_chat_endpoints (tenant_id, source_node_id)
    WHERE source_node_id IS NOT NULL;


-- ---- standalone_form_endpoints ----------------------------------------------
WITH ranked AS (
    SELECT id, tenant_id, source_node_id, workflow_id, created_at,
           ROW_NUMBER() OVER (
               PARTITION BY tenant_id, source_node_id
               ORDER BY created_at ASC, id ASC
           ) AS rn,
           FIRST_VALUE(workflow_id) OVER (
               PARTITION BY tenant_id, source_node_id
               ORDER BY (workflow_id IS NULL), created_at DESC
           ) AS best_workflow_id
      FROM "trigger".standalone_form_endpoints
     WHERE source_node_id IS NOT NULL
),
to_keep AS (
    SELECT id, best_workflow_id FROM ranked WHERE rn = 1 AND workflow_id IS NULL AND best_workflow_id IS NOT NULL
)
UPDATE "trigger".standalone_form_endpoints sfe
   SET workflow_id = tk.best_workflow_id
  FROM to_keep tk
 WHERE sfe.id = tk.id;

DELETE FROM "trigger".standalone_form_endpoints
 WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY tenant_id, source_node_id
                   ORDER BY created_at ASC, id ASC
               ) AS rn
          FROM "trigger".standalone_form_endpoints
         WHERE source_node_id IS NOT NULL
    ) x
    WHERE x.rn > 1
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_standalone_form_endpoints_tenant_source_node
    ON "trigger".standalone_form_endpoints (tenant_id, source_node_id)
    WHERE source_node_id IS NOT NULL;


-- ---- scheduled_executions ---------------------------------------------------
-- V60 already enforces UNIQUE (workflow_id, trigger_id). Add a second backstop
-- on (tenant_id, source_node_id) for the creation-phase dedup before
-- workflow_id is ever set.
WITH ranked AS (
    SELECT id, tenant_id, source_node_id, workflow_id, created_at,
           ROW_NUMBER() OVER (
               PARTITION BY tenant_id, source_node_id
               ORDER BY created_at ASC, id ASC
           ) AS rn,
           FIRST_VALUE(workflow_id) OVER (
               PARTITION BY tenant_id, source_node_id
               ORDER BY (workflow_id IS NULL), created_at DESC
           ) AS best_workflow_id
      FROM "trigger".scheduled_executions
     WHERE source_node_id IS NOT NULL
),
to_keep AS (
    SELECT id, best_workflow_id FROM ranked WHERE rn = 1 AND workflow_id IS NULL AND best_workflow_id IS NOT NULL
)
UPDATE "trigger".scheduled_executions se
   SET workflow_id = tk.best_workflow_id
  FROM to_keep tk
 WHERE se.id = tk.id;

DELETE FROM "trigger".scheduled_executions
 WHERE id IN (
    SELECT id FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY tenant_id, source_node_id
                   ORDER BY created_at ASC, id ASC
               ) AS rn
          FROM "trigger".scheduled_executions
         WHERE source_node_id IS NOT NULL
    ) x
    WHERE x.rn > 1
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_scheduled_executions_tenant_source_node
    ON "trigger".scheduled_executions (tenant_id, source_node_id)
    WHERE source_node_id IS NOT NULL;

COMMIT;

package com.apimarketplace.auth.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Single source of truth for deleting all OPERATIONAL org-scoped data across every
 * service schema for a given organization id. Reused by account deletion
 * ({@link AccountPurgeService}) and workspace deletion ({@link WorkspacePurgeService}).
 *
 * <p><b>Deliberate scope - operational only.</b> This NEVER touches the financial /
 * audit ledger ({@code auth.credit_ledger}, {@code auth.usage_cycle},
 * {@code auth.credit_reconciliation_log}, {@code auth.organization_audit_event}) nor the
 * {@code auth.organization} row / its memberships. The workspace flow keeps the org row
 * as a tombstone so owner-pays credit-ledger references stay valid (ADR-009); the caller
 * decides what to do with the org shell.
 *
 * <p>Runs as native cross-schema SQL - the deliberate exception to the "each service owns
 * its schema" rule, exactly like the account-purge path. The CALLER owns the surrounding
 * {@code @Transactional}.
 *
 * <p><b>Per-statement isolation via SAVEPOINT.</b> Each {@code nativeExec} runs inside its own
 * JDBC savepoint, so a single failing statement (a future schema/type drift, a missing table)
 * rolls back ONLY that statement and the purge continues - without it, Postgres aborts the whole
 * transaction on any error and every later statement (plus the caller's commit) would fail.
 * Statements are also type-safe: org-id columns are a UUID/VARCHAR mix across schemas, so
 * predicates cast {@code organization_id::text = ?}; the one UUID column uses {@code = ?::uuid}.
 *
 * <p>⚠️ <b>Storage:</b> deletes the {@code storage.storage} rows but NOT the underlying
 * S3/MinIO binary objects (they become orphans - inherited debt from the account-purge
 * path; a binary sweep is a follow-up).
 *
 * <p>⚠️ <b>Custom APIs:</b> {@code catalog.apis} (user-created custom APIs carry an
 * {@code organization_id}; the 600+ global third-party APIs are {@code organization_id
 * NULL}) is intentionally NOT purged here - same as the account-purge path - because that
 * table is the global catalog and a mistyped predicate would be catastrophic. The org's
 * custom APIs become invisible orphans (org tombstoned). Tracked as a follow-up.
 *
 * <p>{@link #PURGED_ORG_SCOPED_TABLES} declares every table this purges; the
 * {@code WorkspaceDataPurgerTest} captures the issued SQL and asserts (a) every statement is
 * org-scoped, (b) the retained financial/audit tables are never touched, and (c) every declared
 * table is actually hit. Thanks to the per-statement savepoint isolation, adding a new org-scoped
 * table here is always safe - a wrong/missing table name rolls back only its own statement.
 */
@Component
public class WorkspaceDataPurger {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceDataPurger.class);

    @PersistenceContext
    private EntityManager em;

    /**
     * Every {@code schema.table} this purger deletes org-scoped rows from. Kept in sync with
     * {@link #purgeOperationalData} and consumed by the anti-drift coverage test. Order here
     * is documentation only; the method runs children-before-parents for FK safety.
     */
    public static final List<String> PURGED_ORG_SCOPED_TABLES = List.of(
            "conversation.conversations",
            "orchestrator.workflow_runs",
            "orchestrator.workflows",
            "orchestrator.projects",
            "orchestrator.notifications",
            "agent.agent_executions",
            "agent.agent_tasks",
            "agent.agent_task_recurrences",
            "agent.agent_task_notes",
            "agent.agent_task_events",
            "agent.agent_task_claims",
            "agent.agents",
            "agent.skill_folders",
            "agent.skills",
            "interface.interfaces",
            "datasource.data_sources",
            "trigger.scheduled_executions",
            "trigger.standalone_webhooks",
            "trigger.standalone_chat_endpoints",
            "trigger.standalone_form_endpoints",
            "trigger.webhook_tokens",
            "trigger.datasource_trigger_subscriptions",
            "storage.storage",
            "storage.organization_storage_quota",
            "storage.org_storage_breakdown",
            "storage.org_storage_usage_history",
            "publication.workflow_publications",
            "publication.publication_receipts",
            "auth.org_resource_restrictions",
            "auth.org_member_quota_limit",
            "auth.credentials"
    );

    /**
     * Deletes all operational org-scoped rows for {@code orgId}. Idempotent. Does NOT touch
     * the financial ledger / audit / organization row. Must be called inside a transaction.
     */
    public void purgeOperationalData(String orgId) {
        // conversation schema
        nativeExec("DELETE FROM conversation.messages WHERE conversation_id IN " +
                "(SELECT id FROM conversation.conversations WHERE organization_id::text = ?)", orgId);
        nativeExec("DELETE FROM conversation.conversations WHERE organization_id::text = ?", orgId);

        // orchestrator schema - FK cascades handle child tables (plan_versions, signals, epochs)
        // workflow_runs.id is UUID but workflow_step_data.run_id is VARCHAR - cast the subquery
        // id to text so the IN comparison doesn't trip 'character varying = uuid'.
        nativeExec("DELETE FROM orchestrator.workflow_step_data WHERE run_id IN " +
                "(SELECT id::text FROM orchestrator.workflow_runs WHERE organization_id::text = ?)", orgId);
        nativeExec("DELETE FROM orchestrator.workflow_runs WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM orchestrator.workflows WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM orchestrator.projects WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM orchestrator.notifications WHERE organization_id::text = ?", orgId);

        // agent schema (children before parents)
        nativeExec("DELETE FROM agent.agent_execution_tool_calls WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agent_execution_messages WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agent_execution_iterations WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agent_executions WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agent_task_recurrences WHERE organization_id::text = ?", orgId);
        // agent_task_notes/events/claims also ON DELETE CASCADE from agent_tasks; deleting them
        // explicitly first is defense-in-depth (survives a future cascade removal) and correctly ordered.
        nativeExec("DELETE FROM agent.agent_task_notes WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agent_task_events WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agent_task_claims WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agent_tasks WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agents WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.skill_folders WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.skills WHERE organization_id::text = ?", orgId);

        // interface schema
        nativeExec("DELETE FROM interface.interfaces WHERE organization_id::text = ?", orgId);

        // datasource schema
        nativeExec("DELETE FROM datasource.data_sources WHERE organization_id::text = ?", orgId);

        // trigger schema
        nativeExec("DELETE FROM trigger.scheduled_executions WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM trigger.standalone_webhooks WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM trigger.standalone_chat_endpoints WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM trigger.standalone_form_endpoints WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM trigger.webhook_tokens WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM trigger.datasource_trigger_subscriptions WHERE organization_id::text = ?", orgId);

        // storage schema - S3 objects become orphans (inaccessible without DB ref)
        int storageRows = nativeExec("DELETE FROM storage.storage WHERE organization_id::text = ?", orgId);
        if (storageRows > 0) {
            logger.info("Workspace purge: deleted {} storage rows for org {}", storageRows, orgId);
        }
        // Org storage accounting: the V205 quota row + the V222 LIVE breakdown/usage-history tables
        // (storage.org_storage_*, the entity-mapped ones the trackers write - NOT the dead V205
        // storage.organization_storage_breakdown/usage_history). All keyed by organization_id VARCHAR.
        nativeExec("DELETE FROM storage.organization_storage_quota WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM storage.org_storage_breakdown WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM storage.org_storage_usage_history WHERE organization_id::text = ?", orgId);

        // publication schema (ORG-owned publications + org-scoped receipts)
        nativeExec("DELETE FROM publication.workflow_publications WHERE owner_type = 'ORG' AND owner_id::text = ?", orgId);
        nativeExec("DELETE FROM publication.publication_receipts WHERE organization_id::text = ?", orgId);

        // auth schema - org-scoped OPERATIONAL rows (NOT the financial ledger / audit).
        // org_member_quota_limit.org_id is UUID (not organization_id VARCHAR); it has an
        // ON DELETE CASCADE on the org row, but the workspace flow keeps that row, so we
        // must delete it explicitly here.
        nativeExec("DELETE FROM auth.org_resource_restrictions WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM auth.org_member_quota_limit WHERE org_id = ?::uuid", orgId);
        nativeExec("DELETE FROM auth.credentials WHERE organization_id::text = ?", orgId);
    }

    /**
     * Execute one delete inside its OWN SAVEPOINT, so a single failing statement (e.g. a future
     * schema/type drift, or a missing table) rolls back ONLY that statement instead of poisoning
     * the whole purge transaction - Postgres aborts a transaction on any error, so a plain
     * try/catch swallow does NOT keep "best-effort per statement". Best-effort: a failure is
     * rolled back to the savepoint and logged, and the purge continues with the next table. The
     * SQL uses one positional JDBC {@code ?} bound to the org-id string; returns the row count.
     */
    private int nativeExec(String sql, String orgId) {
        final int[] rows = {0};
        em.unwrap(org.hibernate.Session.class).doWork(conn -> {
            java.sql.Savepoint sp = conn.setSavepoint();
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, orgId);
                rows[0] = ps.executeUpdate();
                conn.releaseSavepoint(sp);
            } catch (Exception e) {
                // Best-effort per statement: roll back ONLY this statement (catch any error, not just
                // SQLException, so a RuntimeException can't leak the savepoint and poison the tx).
                conn.rollback(sp);
                try {
                    conn.releaseSavepoint(sp);
                } catch (java.sql.SQLException ignore) {
                    // savepoint already gone after the rollback - nothing to release
                }
                logger.warn("Workspace purge statement rolled back [{}] org={}: {}",
                        sql.substring(0, Math.min(sql.length(), 60)), orgId, e.getMessage());
            }
        });
        return rows[0];
    }
}

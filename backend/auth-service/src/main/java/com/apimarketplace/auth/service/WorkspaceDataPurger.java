package com.apimarketplace.auth.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Auth-side half of a purge: deletes the org-scoped OPERATIONAL rows that live in the
 * {@code auth} schema and records the decision in {@code auth.purge_log}, the outbox that
 * every other service's {@code PurgeFollower} consumes to delete ITS rows. Reused by account
 * deletion ({@link AccountPurgeService}) and workspace deletion ({@link WorkspacePurgeService}).
 *
 * <p><b>This class no longer touches any other schema, and that is the point.</b> Until
 * 2026-09-02 it fanned 31 DELETEs over nine schemas in native cross-schema SQL, the one class
 * that pinned every schema to the same Postgres: as long as auth reached into
 * {@code datasource.*} directly, user tables and vectors could never move to their own
 * database. Now each service deletes its own rows within minutes of the log row appearing
 * (auth-client {@code PurgeFollower}, cursor per schema), auth writes only to {@code auth.*},
 * and the CI guard {@code scripts/ci/check-cross-schema-sql.py} keeps it that way with an
 * empty allow-list.
 *
 * <p><b>What did not change.</b> The old purger offered no atomicity across schemas either:
 * every statement ran in its own savepoint and a failure was logged and skipped. The
 * followers are at-least-once and stop on failure instead of skipping, which is strictly
 * better for a deletion the user was promised. The financial / audit ledger
 * ({@code auth.credit_ledger}, {@code auth.usage_cycle}, {@code auth.credit_reconciliation_log},
 * {@code auth.organization_audit_event}) and the {@code auth.organization} row are still never
 * touched here; the workspace flow keeps the org row as a tombstone (ADR-009).
 *
 * <p><b>Custom APIs</b> ({@code catalog.apis} rows with an {@code organization_id}) are still
 * not purged anywhere; the catalog has no follower yet. Tracked as before.
 *
 * <p>The log row is written in the CALLER's transaction, after the auth-schema deletes, so a
 * rolled-back purge leaves no promise behind and a committed one is guaranteed to be picked
 * up. Per-statement SAVEPOINTs are kept for the auth deletes, for the same reason as before:
 * one drifting table must not poison the caller's commit.
 */
@Component
public class WorkspaceDataPurger {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceDataPurger.class);

    @PersistenceContext
    private EntityManager em;

    /**
     * Every {@code auth.<table>} this purger deletes org-scoped rows from. The tables of the
     * other schemas are each follower's business now; see the {@code *PurgeFollower} classes
     * and their tests, which pin their own lists.
     */
    public static final List<String> PURGED_AUTH_TABLES = List.of(
            "auth.org_resource_restrictions",
            "auth.org_member_quota_limit",
            "auth.credentials",
            "auth.organization_sso_domain"
    );

    /** Where the log rows come from; shows up in {@code auth.purge_log.source}. */
    public static final String SOURCE_WORKSPACE = "workspace-delete";
    public static final String SOURCE_ACCOUNT = "account-delete";

    /**
     * Deletes the auth-schema operational rows of {@code orgId} and logs the purge for every
     * follower. Idempotent. Must be called inside a transaction. Best-effort per statement:
     * a failing delete is rolled back to its savepoint and logged (the workspace flow).
     *
     * @param source {@link #SOURCE_WORKSPACE} or {@link #SOURCE_ACCOUNT}
     */
    public void purgeOperationalData(String orgId, String source) {
        deleteOperationalRows(orgId, null);
        recordPurge("ORG", orgId, source);
    }

    /**
     * The auth-schema half of {@link #purgeOperationalData} without the outbox row, for a caller
     * that must decide at the end of its transaction whether the purge happens at all (account
     * deletion: {@link AccountPurgeService}). With a non-null {@code failures} list each failing
     * statement is appended to it, so the caller can roll the whole purge back instead of
     * deleting the org row over rows that were never removed; with {@code null} a failure is
     * only logged, as {@link #purgeOperationalData} has always done.
     */
    public void deleteOperationalRows(String orgId, List<String> failures) {
        // org_member_quota_limit.org_id is UUID (not organization_id VARCHAR); it has an
        // ON DELETE CASCADE on the org row, but the workspace flow keeps that row, so we
        // must delete it explicitly here.
        nativeExec("DELETE FROM auth.org_resource_restrictions WHERE organization_id::text = ?", orgId, failures);
        nativeExec("DELETE FROM auth.org_member_quota_limit WHERE org_id = ?::uuid", orgId, failures);
        nativeExec("DELETE FROM auth.credentials WHERE organization_id::text = ?", orgId, failures);
        // A deleted workspace keeps its org row, so its verified domains would keep the domain
        // locked (one verified owner per domain) and routable. Freeing them lets the rightful
        // owner verify it again elsewhere.
        nativeExec("DELETE FROM auth.organization_sso_domain WHERE organization_id::text = ?", orgId, failures);
    }

    /** Logs an ORG purge for the followers; see {@link #recordPurge} for where it must sit. */
    public void recordOrgPurge(String orgId, String source) {
        recordPurge("ORG", orgId, source);
    }

    /**
     * Logs an ACCOUNT purge so the followers drop the user-owned rows they hold
     * ({@code publication.workflow_publications} with {@code owner_type = 'USER'},
     * {@code agent.user_skill_overrides}). The auth-schema user rows are deleted by
     * {@link AccountPurgeService} itself.
     */
    public void recordUserPurge(String userId) {
        recordPurge("USER", userId, SOURCE_ACCOUNT);
    }

    /**
     * Writes the outbox row. Callers must make this the LAST thing of substance in their
     * transaction and keep whatever follows fast: the internal purge feed serves a row only
     * once it is 60 seconds old (so a lower seq still inside an open transaction cannot be
     * skipped by a follower that already saw a higher one), and that margin assumes no purge
     * transaction lives long after this insert. A slow remote call added after it would
     * silently reopen the gap.
     */
    private void recordPurge(String subjectType, String subjectId, String source) {
        em.unwrap(org.hibernate.Session.class).doWork(conn -> {
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO auth.purge_log (subject_type, subject_id, source) VALUES (?, ?, ?)")) {
                ps.setString(1, subjectType);
                ps.setString(2, subjectId);
                ps.setString(3, source);
                ps.executeUpdate();
            }
        });
        logger.info("Purge logged: {} {} ({}); followers will delete their rows within minutes",
                subjectType, subjectId, source);
    }

    /**
     * Execute one delete inside its OWN SAVEPOINT, so a single failing statement (a future
     * schema/type drift, a missing table) rolls back ONLY that statement instead of poisoning
     * the caller's transaction. Postgres aborts a transaction on any error, so a plain
     * try/catch swallow would NOT keep "best-effort per statement".
     */
    private int nativeExec(String sql, String orgId, List<String> failures) {
        final int[] rows = {0};
        em.unwrap(org.hibernate.Session.class).doWork(conn -> {
            java.sql.Savepoint sp = conn.setSavepoint();
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, orgId);
                rows[0] = ps.executeUpdate();
                conn.releaseSavepoint(sp);
            } catch (Exception e) {
                conn.rollback(sp);
                try {
                    conn.releaseSavepoint(sp);
                } catch (java.sql.SQLException ignore) {
                    // savepoint already gone after the rollback - nothing to release
                }
                String statement = sql.substring(0, Math.min(sql.length(), 60));
                logger.warn("Workspace purge statement rolled back [{}] org={}: {}",
                        statement, orgId, e.getMessage());
                if (failures != null) {
                    failures.add(statement + " -> " + e.getMessage());
                }
            }
        });
        return rows[0];
    }
}

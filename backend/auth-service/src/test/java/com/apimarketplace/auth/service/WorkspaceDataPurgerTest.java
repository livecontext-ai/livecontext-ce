package com.apimarketplace.auth.service;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the safety invariants of the cross-schema purge WITHOUT a live DB by capturing the SQL
 * it issues (via the Hibernate {@code doWork} + JDBC {@code prepareStatement} path):
 *   (1) every statement is ORG-SCOPED (carries the {@code ?} org-id predicate) - no statement can
 *       ever delete another workspace's rows;
 *   (2) it NEVER targets a retained financial / audit table, nor the organization row;
 *   (3) every declared {@link WorkspaceDataPurger#PURGED_ORG_SCOPED_TABLES} table is hit (anti-drift);
 *   (4) predicates are type-safe (::text / ::uuid casts) so a UUID/VARCHAR column mix can't abort the tx.
 * Each statement runs inside its own savepoint (mocked here).
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceDataPurgerTest {

    @Mock private EntityManager em;
    @Mock private Session session;
    @Mock private Connection conn;
    @Mock private PreparedStatement ps;
    @Mock private Savepoint savepoint;
    @InjectMocks private WorkspaceDataPurger purger;

    private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";

    /** Tables that must NEVER be deleted - owner-pays ledger / audit (schema-qualified so the
     *  substring check doesn't false-positive on e.g. trigger.datasource_trigger_subscriptions). */
    private static final List<String> RETAINED_TABLES = List.of(
            "auth.credit_ledger", "auth.usage_cycle", "auth.credit_reconciliation_log",
            "auth.organization_audit_event", "auth.billing_customer", "auth.subscription");

    @BeforeEach
    void setUp() throws Exception {
        when(em.unwrap(Session.class)).thenReturn(session);
        // Invoke each unit of Work with the mocked connection (the real impl runs DELETEs in savepoints).
        doAnswer(inv -> { ((Work) inv.getArgument(0)).execute(conn); return null; })
                .when(session).doWork(any());
        lenient().when(conn.setSavepoint()).thenReturn(savepoint);
        lenient().when(conn.prepareStatement(anyString())).thenReturn(ps);
        lenient().when(ps.executeUpdate()).thenReturn(0);
    }

    private List<String> capturePurgeSql() throws Exception {
        purger.purgeOperationalData(ORG_ID);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn, atLeastOnce()).prepareStatement(sql.capture());
        return sql.getAllValues();
    }

    @Test
    @DisplayName("every purge statement is org-scoped (carries the ? org-id predicate)")
    void everyStatementIsOrgScoped() throws Exception {
        for (String s : capturePurgeSql()) {
            assertThat(s).as("statement must be a DELETE: %s", s).containsIgnoringCase("DELETE FROM");
            assertThat(s)
                    .as("statement must be org-scoped via the ? parameter (never an unscoped delete): %s", s)
                    .contains("?")
                    .containsIgnoringCase("WHERE");
        }
    }

    @Test
    @DisplayName("purge NEVER touches the retained financial / audit tables nor the organization row")
    void neverTouchesRetainedTables() throws Exception {
        for (String s : capturePurgeSql()) {
            String lower = s.toLowerCase(Locale.ROOT);
            for (String retained : RETAINED_TABLES) {
                assertThat(lower)
                        .as("purge must not delete retained table %s: %s", retained, s)
                        .doesNotContain("delete from " + retained);
            }
            // Must not delete the organization row itself (it is tombstoned, not deleted).
            assertThat(lower).doesNotContain("delete from auth.organization ");
        }
    }

    @Test
    @DisplayName("purge covers every declared org-scoped table (anti-drift with the constant)")
    void coversEveryDeclaredTable() throws Exception {
        List<String> sql = capturePurgeSql();
        for (String table : WorkspaceDataPurger.PURGED_ORG_SCOPED_TABLES) {
            assertThat(sql).as("no DELETE issued for declared table %s", table)
                    .anyMatch(s -> s.contains("DELETE FROM " + table));
        }
    }

    @Test
    @DisplayName("the workflow_step_data subquery casts workflow_runs.id::text (UUID id vs VARCHAR run_id)")
    void stepDataSubqueryCastsRunId() throws Exception {
        // Regression: workflow_runs.id is UUID but workflow_step_data.run_id is VARCHAR, so
        // 'run_id IN (SELECT id ...)' must select id::text or it trips 'varchar = uuid' and aborts
        // that statement. Reverting to 'SELECT id' leaves the other SQL-shape tests green - pin it.
        assertThat(capturePurgeSql())
                .as("step-data delete must cast the workflow_runs id subquery to ::text")
                .anyMatch(s -> s.contains("orchestrator.workflow_step_data")
                        && s.contains("SELECT id::text FROM orchestrator.workflow_runs"));
    }

    @Test
    @DisplayName("org-id predicates are type-safe (cast), so a UUID column never trips 'varchar = uuid'")
    void orgIdPredicatesAreTypeSafe() throws Exception {
        // organization_id columns are a MIX of UUID and VARCHAR across schemas; binding a String
        // param against a bare UUID column aborts that statement. Every predicate must cast:
        // organization_id::text = ? (universal) or org_id = ?::uuid (the one UUID column).
        for (String s : capturePurgeSql()) {
            assertThat(s).as("uncast organization_id predicate (must be ::text): %s", s)
                    .doesNotContain("organization_id = ?");
            assertThat(s).as("uncast owner_id predicate (must be ::text): %s", s)
                    .doesNotContain("owner_id = ?");
        }
    }

    @Test
    @DisplayName("the org-scoped credentials delete is by organization_id, not the user tenant_id")
    void credentialsDeletedByOrgNotUser() throws Exception {
        List<String> sql = capturePurgeSql();
        assertThat(sql).anyMatch(s -> s.contains("DELETE FROM auth.credentials WHERE organization_id::text = ?"));
        assertThat(sql).noneMatch(s -> s.contains("auth.credentials WHERE tenant_id"));
    }

    @Test
    @DisplayName("a failing statement is rolled back to its OWN savepoint and the purge keeps going (isolation)")
    void failingStatementRollsBackToSavepointAndPurgeContinues() throws Exception {
        // This is the WHOLE POINT of the per-statement savepoint: Postgres aborts the entire
        // transaction on any error, so without the savepoint a single bad statement (a future
        // schema/type drift, a missing table) would poison the purge and the caller's commit.
        // First DELETE throws; every later one succeeds.
        when(ps.executeUpdate()).thenThrow(new java.sql.SQLException("boom")).thenReturn(0);

        // Must NOT propagate - the purge is best-effort per statement.
        purger.purgeOperationalData(ORG_ID);

        // The failure rolled back to its savepoint (NOT the whole tx via the no-arg rollback)...
        verify(conn, times(1)).rollback(savepoint);
        // ...and the purge continued: many more statements were prepared after the failure.
        verify(conn, atLeast(5)).prepareStatement(anyString());
    }
}

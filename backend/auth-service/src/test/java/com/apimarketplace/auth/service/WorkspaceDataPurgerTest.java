package com.apimarketplace.auth.service;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The purger's contract after the 2026-09-02 split: it deletes ONLY in the {@code auth}
 * schema, it writes the outbox row that the other services' followers consume, and it
 * still never touches the retained ledger or the organization row. The cross-schema
 * statements it used to issue are each follower's business now (and their tests pin them);
 * here their ABSENCE is the assertion, because that absence is what lets a schema move.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceDataPurger (auth schema + purge_log outbox)")
class WorkspaceDataPurgerTest {

    @Mock private EntityManager em;
    @Mock private Session session;
    @Mock private Connection conn;
    @Mock private PreparedStatement ps;
    @Mock private Savepoint savepoint;

    private WorkspaceDataPurger purger;

    private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";
    private static final List<String> RETAINED_TABLES = List.of(
            "auth.credit_ledger", "auth.usage_cycle", "auth.credit_reconciliation_log",
            "auth.organization_audit_event", "auth.billing_customer", "auth.subscription");
    private static final List<String> FOREIGN_SCHEMAS = List.of(
            "orchestrator.", "storage.", "agent.", "datasource.", "conversation.",
            "catalog.", "interface.", "trigger.", "publication.");

    @BeforeEach
    void setUp() throws Exception {
        purger = new WorkspaceDataPurger();
        org.springframework.test.util.ReflectionTestUtils.setField(purger, "em", em);
        when(em.unwrap(Session.class)).thenReturn(session);
        doAnswer(inv -> { ((Work) inv.getArgument(0)).execute(conn); return null; })
                .when(session).doWork(any());
        lenient().when(conn.setSavepoint()).thenReturn(savepoint);
        lenient().when(conn.prepareStatement(anyString())).thenReturn(ps);
        lenient().when(ps.executeUpdate()).thenReturn(0);
    }

    private List<String> captureSql(Runnable call) throws SQLException {
        call.run();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(conn, atLeastOnce()).prepareStatement(sql.capture());
        return sql.getAllValues();
    }

    @Test
    @DisplayName("Deletes only in the auth schema: no statement names another service's schema")
    void deletesOnlyInAuthSchema() throws Exception {
        List<String> sql = captureSql(() -> purger.purgeOperationalData(ORG_ID, WorkspaceDataPurger.SOURCE_WORKSPACE));

        for (String s : sql) {
            String lower = s.toLowerCase();
            for (String schema : FOREIGN_SCHEMAS) {
                assertThat(lower).as("cross-schema statement, this is what pins schemas to one database: %s", s)
                        .doesNotContain(" " + schema);
            }
        }
    }

    @Test
    @DisplayName("Covers every declared auth table, each with an org-scoped, type-safe predicate")
    void coversDeclaredAuthTables() throws Exception {
        List<String> sql = captureSql(() -> purger.purgeOperationalData(ORG_ID, WorkspaceDataPurger.SOURCE_WORKSPACE));
        String all = String.join("\n", sql).toLowerCase();

        for (String table : WorkspaceDataPurger.PURGED_AUTH_TABLES) {
            assertThat(all).as("no DELETE issued for declared table %s", table).contains("delete from " + table);
        }
        for (String s : sql) {
            if (!s.toUpperCase().startsWith("DELETE")) continue;
            assertThat(s).as("every delete must be org-scoped: %s", s).contains("?");
            if (s.contains("organization_id")) {
                assertThat(s).as("uncast organization_id predicate (must be ::text): %s", s).contains("organization_id::text = ?");
            }
        }
    }

    @Test
    @DisplayName("Writes the outbox row AFTER the auth deletes, with subject ORG and the caller's source")
    void writesOutboxRowLast() throws Exception {
        List<String> sql = captureSql(() -> purger.purgeOperationalData(ORG_ID, WorkspaceDataPurger.SOURCE_ACCOUNT));

        String last = sql.get(sql.size() - 1);
        assertThat(last).contains("INSERT INTO auth.purge_log").contains("subject_type").contains("subject_id").contains("source");
        InOrder order = inOrder(ps);
        order.verify(ps).setString(1, "ORG");
        order.verify(ps).setString(2, ORG_ID);
        order.verify(ps).setString(3, WorkspaceDataPurger.SOURCE_ACCOUNT);
    }

    @Test
    @DisplayName("An account purge is logged with subject USER so followers drop user-owned rows")
    void userPurgeIsLogged() throws Exception {
        List<String> sql = captureSql(() -> purger.recordUserPurge("42"));

        assertThat(sql).hasSize(1);
        assertThat(sql.get(0)).contains("INSERT INTO auth.purge_log");
        verify(ps).setString(1, "USER");
        verify(ps).setString(2, "42");
        verify(ps).setString(3, WorkspaceDataPurger.SOURCE_ACCOUNT);
    }

    @Test
    @DisplayName("Never touches the retained financial / audit tables nor the organization row")
    void neverTouchesRetainedTables() throws Exception {
        List<String> sql = captureSql(() -> purger.purgeOperationalData(ORG_ID, WorkspaceDataPurger.SOURCE_WORKSPACE));

        for (String s : sql) {
            String lower = s.toLowerCase();
            for (String retained : RETAINED_TABLES) {
                assertThat(lower).as("retained table touched: %s", s).doesNotContain(retained);
            }
            assertThat(lower).doesNotContain("delete from auth.organization ");
        }
    }

    /**
     * The outbox row is the promise the followers act on. A delete that failed and was
     * rolled back to its savepoint must not prevent the promise: the follower for THAT data
     * is unaffected and the auth table will be retried on the next purge attempt. So the
     * insert runs even when an auth delete blew up.
     */
    @Test
    @DisplayName("A failing auth delete is rolled back to its savepoint and the outbox row is still written")
    void failingDeleteDoesNotPreventOutbox() throws Exception {
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            if (s.contains("org_member_quota_limit")) throw new SQLException("relation does not exist");
            return ps;
        });

        List<String> sql = captureSql(() -> purger.purgeOperationalData(ORG_ID, WorkspaceDataPurger.SOURCE_WORKSPACE));

        verify(conn).rollback(savepoint);
        assertThat(sql.get(sql.size() - 1)).contains("INSERT INTO auth.purge_log");
    }

    @Test
    @DisplayName("LC-065: the workspace credentials are revoked at their providers BEFORE the credential delete")
    void revokesAtProviderBeforeCredentialDelete() throws Exception {
        var credentialService = org.mockito.Mockito.mock(com.apimarketplace.auth.credential.service.CredentialService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(purger, "credentialService", credentialService);

        purger.purgeOperationalData(ORG_ID, WorkspaceDataPurger.SOURCE_WORKSPACE);

        InOrder order = inOrder(credentialService, conn);
        order.verify(credentialService).revokeAllForWorkspacePurge(ORG_ID, null);
        order.verify(conn).prepareStatement(org.mockito.ArgumentMatchers.startsWith("DELETE FROM auth.credentials"));
    }

    @Test
    @DisplayName("LC-065: a revocation failure never aborts the purge; the credential rows are still deleted")
    void revocationFailureDoesNotAbortPurge() throws Exception {
        var credentialService = org.mockito.Mockito.mock(com.apimarketplace.auth.credential.service.CredentialService.class);
        org.mockito.Mockito.when(credentialService.revokeAllForWorkspacePurge(ORG_ID, null))
                .thenThrow(new IllegalStateException("db hiccup"));
        org.springframework.test.util.ReflectionTestUtils.setField(purger, "credentialService", credentialService);

        List<String> sql = captureSql(() -> purger.purgeOperationalData(ORG_ID, WorkspaceDataPurger.SOURCE_WORKSPACE));

        assertThat(sql).anyMatch(s -> s.startsWith("DELETE FROM auth.credentials"));
    }

    @Test
    @DisplayName("Regression LC-065: a failed revocation lookup is rolled back to its savepoint BEFORE the credential delete")
    void failedRevocationLookupRolledBackBeforeCredentialDelete() throws Exception {
        // The lookup is SQL on the purge's connection: without this rollback Postgres refuses every
        // later statement of the purge ("current transaction is aborted").
        var credentialService = org.mockito.Mockito.mock(com.apimarketplace.auth.credential.service.CredentialService.class);
        org.mockito.Mockito.when(credentialService.revokeAllForWorkspacePurge(ORG_ID, null))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException("lookup", "SELECT", new SQLException("boom")));
        org.springframework.test.util.ReflectionTestUtils.setField(purger, "credentialService", credentialService);

        purger.purgeOperationalData(ORG_ID, WorkspaceDataPurger.SOURCE_WORKSPACE);

        InOrder order = inOrder(conn);
        order.verify(conn).rollback(savepoint);
        order.verify(conn).prepareStatement(org.mockito.ArgumentMatchers.startsWith("DELETE FROM auth.credentials"));
    }

    // -- SAML connection + Keycloak IdP --

    @Test
    @DisplayName("purge deletes the workspace SAML connection row, org-scoped")
    void purgeDeletesSamlConnection() throws Exception {
        List<String> sql = captureSql(() -> purger.purgeOperationalData(ORG_ID, WorkspaceDataPurger.SOURCE_WORKSPACE));

        assertThat(sql).contains("DELETE FROM auth.organization_saml_connection WHERE organization_id::text = ?");
    }

    @Test
    @DisplayName("a purge that removed a SAML connection also deletes the workspace IdP in Keycloak")
    void purgeDeletesKeycloakIdentityProviderWhenAConnectionExisted() throws Exception {
        KeycloakSamlIdentityProviderClient kc = org.mockito.Mockito.mock(KeycloakSamlIdentityProviderClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(purger, "samlIdentityProviderClient", kc);
        when(conn.prepareStatement(anyString())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            PreparedStatement statement = org.mockito.Mockito.mock(PreparedStatement.class);
            when(statement.executeUpdate()).thenReturn(s.contains("organization_saml_connection") ? 1 : 0);
            return statement;
        });

        purger.purgeOperationalData(ORG_ID, WorkspaceDataPurger.SOURCE_WORKSPACE);

        verify(kc).delete(OrganizationSamlService.aliasFor(java.util.UUID.fromString(ORG_ID)));
    }

    @Test
    @DisplayName("no SAML connection removed: Keycloak is never called")
    void purgeWithoutSamlConnectionLeavesKeycloakAlone() throws Exception {
        KeycloakSamlIdentityProviderClient kc = org.mockito.Mockito.mock(KeycloakSamlIdentityProviderClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(purger, "samlIdentityProviderClient", kc);

        purger.purgeOperationalData(ORG_ID, WorkspaceDataPurger.SOURCE_WORKSPACE);

        org.mockito.Mockito.verifyNoInteractions(kc);
    }

    @Test
    @DisplayName("inside a transaction the Keycloak IdP is deleted only after commit, never on rollback")
    void keycloakDeleteWaitsForCommit() throws Exception {
        KeycloakSamlIdentityProviderClient kc = org.mockito.Mockito.mock(KeycloakSamlIdentityProviderClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(purger, "samlIdentityProviderClient", kc);
        when(ps.executeUpdate()).thenReturn(1);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            purger.purgeOperationalData(ORG_ID, WorkspaceDataPurger.SOURCE_WORKSPACE);
            org.mockito.Mockito.verifyNoInteractions(kc);

            var syncs = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);
            syncs.get(0).afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            org.mockito.Mockito.verifyNoInteractions(kc);

            syncs.get(0).afterCommit();
            verify(kc).delete(OrganizationSamlService.aliasFor(java.util.UUID.fromString(ORG_ID)));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("a Keycloak failure never fails the purge")
    void keycloakFailureIsSwallowed() throws Exception {
        KeycloakSamlIdentityProviderClient kc = org.mockito.Mockito.mock(KeycloakSamlIdentityProviderClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(purger, "samlIdentityProviderClient", kc);
        when(ps.executeUpdate()).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("KC down")).when(kc).delete(anyString());

        purger.purgeOperationalData(ORG_ID, WorkspaceDataPurger.SOURCE_WORKSPACE);

        verify(kc).delete(anyString());
    }
}

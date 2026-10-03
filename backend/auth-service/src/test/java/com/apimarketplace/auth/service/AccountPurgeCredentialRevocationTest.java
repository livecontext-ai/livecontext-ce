package com.apimarketplace.auth.service;

import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-065: the account purge revokes the user's credentials at their providers before its bulk
 * {@code DELETE FROM auth.credentials}, and a revocation failure never aborts the purge.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountPurgeService revokes credentials before the bulk delete")
class AccountPurgeCredentialRevocationTest {

    private static final String CREDENTIAL_DELETE = "DELETE FROM auth.credentials WHERE tenant_id = ?";

    @Mock private UserRepository userRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private RestTemplate restTemplate;
    @Mock private WorkspaceDataPurger workspaceDataPurger;
    @Mock private EntityManager em;
    @Mock private Session session;
    @Mock private Connection connection;
    @Mock private PreparedStatement statement;
    @Mock private Savepoint savepoint;
    @Mock private CredentialService credentialService;

    private AccountPurgeService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AccountPurgeService(userRepository, organizationRepository, Optional.empty(),
                restTemplate, workspaceDataPurger, subscriptionRepository,
                TransactionOperations.withoutTransaction());
        ReflectionTestUtils.setField(service, "em", em);
        ReflectionTestUtils.setField(service, "authMode", "embedded");
        ReflectionTestUtils.setField(service, "credentialService", credentialService);

        User user = new User();
        user.setId(42L);
        user.setEnabled(false);
        user.setDeactivatedAt(java.time.LocalDateTime.now().minusDays(40));
        when(em.find(eq(User.class), eq(42L), any(LockModeType.class))).thenReturn(user);
        when(organizationRepository.findByOwnerId(42L)).thenReturn(List.of());
        // Every purge statement runs as JDBC work in its own savepoint: run it on a mock connection.
        lenient().when(em.unwrap(Session.class)).thenReturn(session);
        lenient().doAnswer(inv -> {
            ((Work) inv.getArgument(0)).execute(connection);
            return null;
        }).when(session).doWork(any());
        lenient().when(connection.setSavepoint()).thenReturn(savepoint);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(statement);
    }

    @Test
    @DisplayName("revokes every credential the user owns, THEN bulk-deletes them")
    void revokesBeforeDelete() throws Exception {
        service.purgeUser(42L);

        InOrder order = inOrder(credentialService, connection);
        order.verify(credentialService).revokeAllForAccountPurge("42");
        order.verify(connection).prepareStatement(CREDENTIAL_DELETE);
    }

    @Test
    @DisplayName("a revocation failure never aborts the purge: the credentials are still deleted")
    void failureDoesNotAbort() throws Exception {
        when(credentialService.revokeAllForAccountPurge("42")).thenThrow(new IllegalStateException("boom"));

        service.purgeUser(42L);

        verify(connection).prepareStatement(CREDENTIAL_DELETE);
        verify(workspaceDataPurger).recordUserPurge("42");
    }

    @Test
    @DisplayName("Regression: a failed revocation lookup is rolled back to its savepoint BEFORE the delete")
    void failedLookupRolledBackBeforeDelete() throws Exception {
        // The lookup is SQL on the purge's connection: without the savepoint rollback, Postgres
        // would refuse every later purge statement ("current transaction is aborted").
        when(credentialService.revokeAllForAccountPurge("42"))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException("lookup", "SELECT", new java.sql.SQLException("boom")));

        service.purgeUser(42L);

        InOrder order = inOrder(connection);
        order.verify(connection).rollback(savepoint);
        order.verify(connection).prepareStatement(CREDENTIAL_DELETE);
    }
}

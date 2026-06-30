package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationInvitationRepository;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.web.AppEditionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Milestone-1 audit round-2 regression - pin the afterCommit semantics of
 * the gateway cache bust.
 *
 * <p>Pre-fix: {@code bustGatewayCacheFor} fired the HTTP call to the gateway
 * synchronously, INSIDE the outer {@code @Transactional} of mutation methods
 * (remove, leave, change-role, transfer, soft-delete, accept). The gateway
 * then re-resolved the user via auth-service over HTTP, reading PRE-commit
 * state from the SAME DB, and repopulated the cache with the stale row it
 * was about to bust. Worst case (security-relevant on removeMember): a
 * removed member's gateway cache could keep their old membership for the
 * full 5-min TTL - exactly the scenario the bust was supposed to prevent.
 *
 * <p>Post-fix: the bust is registered as a {@link TransactionSynchronization}
 * and fires in {@code afterCommit()}. If there is no active transaction
 * (manual / test-bench paths), the call is synchronous as a fallback.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationMemberService.bustGatewayCacheFor - afterCommit semantics (audit round-2)")
class OrganizationMemberServiceCacheBustAfterCommitTest {

    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationInvitationRepository invitationRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private UserRepository userRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private OnboardingService onboardingService;
    @Mock private OrganizationAuditService auditService;
    @Mock private OrganizationInvitationMailer invitationMailer;
    @Mock private AppEditionProvider editionProvider;
    @Mock private GatewayCacheClient gatewayCacheClient;

    private OrganizationMemberService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new OrganizationMemberService(memberRepository, invitationRepository,
                organizationRepository, userRepository, subscriptionRepository,
                onboardingService, auditService, invitationMailer, editionProvider, gatewayCacheClient,
                1000, 1000);
        user = new User("u", "u@test.com", AuthProvider.KEYCLOAK, "kc-uuid-123");
        user.setId(42L);
    }

    private void invokeBust(User u) throws Exception {
        Method m = OrganizationMemberService.class.getDeclaredMethod("bustGatewayCacheFor", User.class);
        m.setAccessible(true);
        m.invoke(service, u);
    }

    @Test
    @DisplayName("inside an active transaction: bust DEFERRED to afterCommit - no HTTP call before commit")
    void deferredInsideTransaction() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        try {
            invokeBust(user);

            // PRE-commit: NO HTTP call to gateway yet - that's the bug fix.
            verify(gatewayCacheClient, never()).invalidateUserCache(org.mockito.ArgumentMatchers.any());

            // The synchronization is registered for afterCommit.
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

            // Simulate commit by firing afterCommit on every registered sync.
            for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                s.afterCommit();
            }

            verify(gatewayCacheClient).invalidateUserCache("kc-uuid-123");
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    @DisplayName("inside a tx that ROLLS BACK: bust never fires (no wasted HTTP call)")
    void rollbackSuppressesBust() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        try {
            invokeBust(user);

            // Simulate rollback - afterCommit MUST NOT fire on the registered sync.
            for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
                // NOT calling s.afterCommit() - that's the contract.
            }

            verify(gatewayCacheClient, never()).invalidateUserCache(org.mockito.ArgumentMatchers.any());
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    @DisplayName("outside a transaction: bust fires synchronously (manual / test-bench fallback)")
    void synchronousFallbackWhenNoTransaction() throws Exception {
        // Ensure no active sync.
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();

        invokeBust(user);

        verify(gatewayCacheClient).invalidateUserCache("kc-uuid-123");
    }

    @Test
    @DisplayName("null user / null providerId / blank providerId: silent no-op (defensive)")
    void noOpForNullOrBlank() throws Exception {
        invokeBust(null);
        verify(gatewayCacheClient, never()).invalidateUserCache(org.mockito.ArgumentMatchers.any());

        User noProvider = new User();
        invokeBust(noProvider);
        verify(gatewayCacheClient, never()).invalidateUserCache(org.mockito.ArgumentMatchers.any());

        User blankProvider = new User();
        blankProvider.setProviderId("  ");
        invokeBust(blankProvider);
        verify(gatewayCacheClient, never()).invalidateUserCache(org.mockito.ArgumentMatchers.any());
    }
}

package com.apimarketplace.auth.bootstrap;

import com.apimarketplace.auth.ce.CeInstallStateService;
import com.apimarketplace.auth.ce.CeStatusView;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.common.web.AppEditionProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FirstAdminBootstrap}. Pure Mockito - no Spring boot.
 *
 * <p>The contract is "auto-promote first user to ADMIN, but only in CE, only
 * before the install is bootstrapped, and atomically w.r.t. parallel
 * registrants". Each test pins one invariant of that contract.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FirstAdminBootstrap")
class FirstAdminBootstrapTest {

    @Mock
    private EntityManager em;

    @Mock
    private Query query;

    @Mock
    private AppEditionProvider edition;

    @Mock
    private CeInstallStateService installState;

    @Mock
    private UserRepository userRepository;

    private FirstAdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new FirstAdminBootstrap(em, edition, installState, userRepository);
        lenient().when(em.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(anyString(), anyString())).thenReturn(query);
    }

    @Nested
    @DisplayName("Edition gate (I1: Cloud never auto-promotes)")
    class EditionGateTests {

        @Test
        @DisplayName("Cloud edition → false, even when users table is empty (sev-10 prod-escalation guard)")
        void cloudReturnsFalseEvenWhenUsersEmpty() {
            when(edition.isCe()).thenReturn(false);

            boolean result = bootstrap.claimFirstAdminSlot();

            assertThat(result).isFalse();
            verify(userRepository, never()).count();
            verify(em, never()).createNativeQuery(anyString());
            verify(installState, never()).getStatus();
        }
    }

    @Nested
    @DisplayName("Bootstrap-state gate (I3: one-shot - install state is the boundary, not row count)")
    class BootstrapStateGateTests {

        @Test
        @DisplayName("CE + bootstrapped=true + users count=0 → false (regression: admin deleted, snapshot wiped)")
        void ceBootstrappedReturnsFalseEvenWhenCountZero() {
            when(edition.isCe()).thenReturn(true);
            when(installState.getStatus()).thenReturn(new CeStatusView(true, null, "1.0", false));

            boolean result = bootstrap.claimFirstAdminSlot();

            assertThat(result).isFalse();
            verify(userRepository, never()).count();
            verify(em, never()).createNativeQuery(anyString());
        }
    }

    @Nested
    @DisplayName("Promotion path (I5: serializes via advisory lock then checks count)")
    class PromotionTests {

        @BeforeEach
        void ceAndNotBootstrapped() {
            when(edition.isCe()).thenReturn(true);
            when(installState.getStatus()).thenReturn(CeStatusView.notBootstrapped());
        }

        @Test
        @DisplayName("CE + not bootstrapped + count==0 → true (first user gets ADMIN)")
        void ceFirstUserReturnsTrue() {
            when(userRepository.count()).thenReturn(0L);

            boolean result = bootstrap.claimFirstAdminSlot();

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("CE + not bootstrapped + count>0 → false (subsequent user gets USER only)")
        void ceSubsequentUserReturnsFalse() {
            when(userRepository.count()).thenReturn(3L);

            boolean result = bootstrap.claimFirstAdminSlot();

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Advisory lock fires before count read (race protection - pg_advisory_xact_lock)")
        void advisoryLockAcquiredBeforeCount() {
            when(userRepository.count()).thenReturn(0L);

            bootstrap.claimFirstAdminSlot();

            verify(em, times(1)).createNativeQuery(
                    "SELECT pg_advisory_xact_lock(hashtext(:key))");
            verify(query).setParameter("key", FirstAdminBootstrap.LOCK_KEY);
            verify(query).getSingleResult();
            verify(userRepository).count();
        }
    }
}

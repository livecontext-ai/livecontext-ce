package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract: the reserve/commit/release lifecycle MUST stamp
 * {@code credit_ledger.executor_user_id} with the original caller and
 * {@code credit_ledger.user_id} with the resolved payer. Under owner-pays
 * (ADR-009), a member who fires a platform-credentialed catalog tool in a
 * TEAM workspace has the reservation debit the OWNER's wallet while the
 * per-member quota SUM
 * ({@link CreditLedgerRepository#sumDebitedByExecutorSince}) still
 * attributes the consumption to the member.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditService - reserve/commit/release route via owner-pays")
class ReserveRowExecutorIdContractTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private CreditLedgerRepository ledgerRepository;
    @Mock private ModelPricingService pricingService;
    @Mock private PlanResolutionService planResolutionService;

    @Captor private ArgumentCaptor<CreditLedgerEntry> ledgerCaptor;

    private CreditService service;

    private static final Long EXECUTOR = 42L; // member
    private static final Long OWNER = 99L;    // workspace owner

    @Nested
    @DisplayName("Solo user (executor == payer, no redirect)")
    class SoloUser {

        @BeforeEach
        void setUp() {
            service = new CreditService(subscriptionRepository, ledgerRepository, pricingService,
                    false, true, false);
            // No planResolutionService wired → CreditService.resolvePayer returns input as-is.
            Subscription sub = new Subscription();
            sub.setRemainingCredits(new BigDecimal("100.0000"));
            lenient().when(subscriptionRepository.findActiveByUserIdForUpdate(EXECUTOR))
                    .thenReturn(Optional.of(sub));
            lenient().when(subscriptionRepository.findActiveByUserId(EXECUTOR))
                    .thenReturn(Optional.of(sub));
        }

        @Test
        @DisplayName("tryReserveMarkup → user_id = executor, executor_user_id = executor")
        void tryReserveMarkupSetsExecutor() {
            service.tryReserveMarkup(
                    EXECUTOR, "res-1", "openai", "gpt-4",
                    new BigDecimal("5.0000"), null, 15, "RUN", "run-1", false);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getUserId()).isEqualTo(EXECUTOR);
            assertThat(entry.getExecutorUserId()).isEqualTo(EXECUTOR);
            assertThat(entry.getSourceType()).isEqualTo("PLATFORM_MARKUP_RESERVE");
        }
    }

    @Nested
    @DisplayName("Member in TEAM workspace (owner-pays redirect)")
    class MemberInTeamWorkspace {

        @BeforeEach
        void setUp() {
            service = new CreditService(subscriptionRepository, ledgerRepository, pricingService,
                    false, true, false);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    service, "planResolutionService", planResolutionService);
            // Lenient: only tryReserveMarkup exercises resolvePayer; commit/release
            // read user_id from the existing row and never resolve.
            lenient().when(planResolutionService.resolvePayerUserId(EXECUTOR)).thenReturn(OWNER);

            Subscription ownerSub = new Subscription();
            ownerSub.setRemainingCredits(new BigDecimal("100.0000"));
            lenient().when(subscriptionRepository.findActiveByUserIdForUpdate(OWNER))
                    .thenReturn(Optional.of(ownerSub));
            lenient().when(subscriptionRepository.findActiveByUserId(OWNER))
                    .thenReturn(Optional.of(ownerSub));
        }

        @Test
        @DisplayName("tryReserveMarkup(member) → user_id = OWNER, executor_user_id = MEMBER")
        void tryReserveMarkupRedirectsToOwner() {
            service.tryReserveMarkup(
                    EXECUTOR, "res-redirect", "openai", "gpt-4",
                    new BigDecimal("5.0000"), null, 15, "RUN", "run-1", false);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getUserId())
                    .as("Owner-pays: reservation debits the OWNER's wallet")
                    .isEqualTo(OWNER);
            assertThat(entry.getExecutorUserId())
                    .as("Quota SUM attributes the consumption to the MEMBER who triggered it")
                    .isEqualTo(EXECUTOR);
            assertThat(entry.getSourceType()).isEqualTo("PLATFORM_MARKUP_RESERVE");
        }

        @Test
        @DisplayName("commitReservation reads user_id from the reserve row → locks OWNER's subscription")
        void commitLocksOwnerSubscription() {
            CreditLedgerEntry reserve = new CreditLedgerEntry();
            reserve.setUserId(OWNER);
            reserve.setExecutorUserId(EXECUTOR);
            reserve.setAmount(new BigDecimal("-5.0000"));
            reserve.setSourceType("PLATFORM_MARKUP_RESERVE");
            reserve.setSourceId("res-commit");
            when(ledgerRepository.findFirstBySourceId("res-commit")).thenReturn(Optional.of(reserve));

            service.commitReservation("res-commit", new BigDecimal("5.0000"), "openai", "gpt-4");

            verify(subscriptionRepository).findActiveByUserIdForUpdate(OWNER);
            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry committed = ledgerCaptor.getValue();
            assertThat(committed.getUserId()).isEqualTo(OWNER);
            assertThat(committed.getExecutorUserId()).isEqualTo(EXECUTOR);
            assertThat(committed.getSourceType()).isEqualTo("PLATFORM_MARKUP");
        }

        @Test
        @DisplayName("releaseReservation refunds OWNER's wallet, preserves executor breadcrumb")
        void releaseRefundsOwner() {
            CreditLedgerEntry reserve = new CreditLedgerEntry();
            reserve.setUserId(OWNER);
            reserve.setExecutorUserId(EXECUTOR);
            reserve.setAmount(new BigDecimal("-5.0000"));
            reserve.setSourceType("PLATFORM_MARKUP_RESERVE");
            reserve.setSourceId("res-release");
            when(ledgerRepository.findFirstBySourceId("res-release")).thenReturn(Optional.of(reserve));

            service.releaseReservation("res-release", "partial-result");

            verify(subscriptionRepository).findActiveByUserIdForUpdate(OWNER);
            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry released = ledgerCaptor.getValue();
            assertThat(released.getUserId()).isEqualTo(OWNER);
            assertThat(released.getExecutorUserId()).isEqualTo(EXECUTOR);
            assertThat(released.getSourceType()).isEqualTo("PLATFORM_MARKUP_RELEASED");
        }
    }
}

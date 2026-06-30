package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PendingCreditUpgrade;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.PendingCreditUpgradeRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.stripe.StripeClient;
import com.stripe.model.Invoice;
import com.stripe.service.InvoiceItemService;
import com.stripe.service.InvoiceService;
import com.stripe.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PendingCreditUpgradeReconciler}.
 *
 * Covers the reconciliation contract:
 * - PAID_SUB_PENDING younger than 5 min: skipped (let sync flow complete).
 * - PAID_SUB_PENDING older than 5 min with paid invoice: aligned + granted + marked COMPLETED.
 * - PAID_SUB_PENDING older than 7 days, invoice still not paid: marked FAILED.
 * - PENDING_3DS younger than 24 h: skipped (user may still complete 3DS).
 * - PENDING_3DS older than 24 h, invoice still unpaid: voided + deleted + marked FAILED.
 * - PENDING_3DS older than 24 h, invoice turned paid: race-resolved (aligned + granted + COMPLETED).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PendingCreditUpgradeReconciler Tests")
class PendingCreditUpgradeReconcilerTest {

    @Mock private StripeClient stripe;
    @Mock private PendingCreditUpgradeRepository pendingRepo;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private PriceCacheService priceCacheService;
    @Mock private CreditAttributionService creditAttributionService;

    @Mock private InvoiceService invoiceService;
    @Mock private InvoiceItemService invoiceItemService;
    @Mock private SubscriptionService stripeSubscriptionService;

    private PendingCreditUpgradeReconciler reconciler;

    private static final Long USER_ID = 42L;
    private static final Long SUB_ID = 1L;

    @BeforeEach
    void setUp() {
        reconciler = new PendingCreditUpgradeReconciler(
                stripe, pendingRepo, subscriptionRepository, priceCacheService, creditAttributionService);
        lenient().when(stripe.invoices()).thenReturn(invoiceService);
        lenient().when(stripe.invoiceItems()).thenReturn(invoiceItemService);
        lenient().when(stripe.subscriptions()).thenReturn(stripeSubscriptionService);
    }

    private PendingCreditUpgrade buildPending(String status, LocalDateTime createdAt) {
        PendingCreditUpgrade p = new PendingCreditUpgrade();
        p.setId(1L);
        p.setUserId(USER_ID);
        p.setSubscriptionId(SUB_ID);
        p.setProviderSubscriptionId("sub_test");
        p.setStripeInvoiceId("in_test");
        p.setStripeInvoiceItemId("ii_test");
        p.setTargetTierIndex(3);
        p.setTargetCreditQuantity(35);
        p.setTargetCreditPriceId("price_pack_test");
        p.setStatus(status);
        p.setCreatedAt(createdAt);
        return p;
    }

    @Test
    @DisplayName("PAID_SUB_PENDING younger than 5 min: skipped (let sync complete)")
    void paidSubPendingTooYoungSkipped() throws Exception {
        PendingCreditUpgrade pending = buildPending(
                PendingCreditUpgrade.STATUS_PAID_SUB_PENDING,
                LocalDateTime.now().minusMinutes(2));
        when(pendingRepo.findByStatusOrderByCreatedAtAsc(
                eq(PendingCreditUpgrade.STATUS_PAID_SUB_PENDING), any()))
                .thenReturn(List.of(pending));
        when(pendingRepo.findByStatusOrderByCreatedAtAsc(
                eq(PendingCreditUpgrade.STATUS_PENDING_3DS), any()))
                .thenReturn(List.of());

        reconciler.reconcile();

        // No Stripe interaction beyond list pulls
        verifyNoInteractions(creditAttributionService);
        verify(invoiceService, never()).retrieve(any(String.class));
    }

    @Test
    @DisplayName("PAID_SUB_PENDING > 5 min with paid invoice: aligned + granted + COMPLETED (happy path)")
    void paidSubPendingPaidInvoiceDrivesCompletion() throws Exception {
        PendingCreditUpgrade pending = buildPending(
                PendingCreditUpgrade.STATUS_PAID_SUB_PENDING,
                LocalDateTime.now().minusMinutes(6));
        when(pendingRepo.findByStatusOrderByCreatedAtAsc(
                eq(PendingCreditUpgrade.STATUS_PAID_SUB_PENDING), any()))
                .thenReturn(List.of(pending));
        when(pendingRepo.findByStatusOrderByCreatedAtAsc(
                eq(PendingCreditUpgrade.STATUS_PENDING_3DS), any()))
                .thenReturn(List.of());

        Invoice paidInvoice = mock(Invoice.class);
        when(paidInvoice.getStatus()).thenReturn("paid");
        when(invoiceService.retrieve("in_test")).thenReturn(paidInvoice);

        com.stripe.model.Subscription stripeSub = mock(com.stripe.model.Subscription.class);
        com.stripe.model.SubscriptionItemCollection items =
                mock(com.stripe.model.SubscriptionItemCollection.class);
        when(stripeSub.getItems()).thenReturn(items);
        when(items.getData()).thenReturn(java.util.Collections.emptyList());
        when(stripeSubscriptionService.retrieve("sub_test")).thenReturn(stripeSub);

        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.empty());

        reconciler.reconcile();

        verify(stripeSubscriptionService).update(eq("sub_test"), any(), any());
        verify(creditAttributionService).handleCreditUpgradeInvoicePaid(pending);
        verify(pendingRepo).save(argThat(p ->
                PendingCreditUpgrade.STATUS_COMPLETED.equals(p.getStatus())
                        && p.getCompletedAt() != null));
        verify(invoiceService, never()).voidInvoice(any(String.class));
    }

    @Test
    @DisplayName("PAID_SUB_PENDING > 7 days with unpaid invoice: cleaned up + marked FAILED")
    void paidSubPendingStuckMarkedFailed() throws Exception {
        PendingCreditUpgrade pending = buildPending(
                PendingCreditUpgrade.STATUS_PAID_SUB_PENDING,
                LocalDateTime.now().minusDays(8));
        when(pendingRepo.findByStatusOrderByCreatedAtAsc(
                eq(PendingCreditUpgrade.STATUS_PAID_SUB_PENDING), any()))
                .thenReturn(List.of(pending));
        when(pendingRepo.findByStatusOrderByCreatedAtAsc(
                eq(PendingCreditUpgrade.STATUS_PENDING_3DS), any()))
                .thenReturn(List.of());

        Invoice draftInvoice = mock(Invoice.class);
        when(draftInvoice.getStatus()).thenReturn("draft");
        when(draftInvoice.getId()).thenReturn("in_test");
        when(invoiceService.retrieve("in_test")).thenReturn(draftInvoice);

        reconciler.reconcile();

        verify(invoiceService).voidInvoice("in_test");
        verify(invoiceItemService).delete("ii_test");
        verify(pendingRepo).save(argThat(p ->
                PendingCreditUpgrade.STATUS_FAILED.equals(p.getStatus())
                        && p.getErrorMessage() != null
                        && p.getErrorMessage().contains("stuck_paid_sub_pending_7d")));
        verifyNoInteractions(creditAttributionService);
    }

    @Test
    @DisplayName("PENDING_3DS younger than 24h: skipped (user may still complete)")
    void pending3DSTooYoungSkipped() throws Exception {
        PendingCreditUpgrade pending = buildPending(
                PendingCreditUpgrade.STATUS_PENDING_3DS,
                LocalDateTime.now().minusHours(2));
        when(pendingRepo.findByStatusOrderByCreatedAtAsc(
                eq(PendingCreditUpgrade.STATUS_PAID_SUB_PENDING), any()))
                .thenReturn(List.of());
        when(pendingRepo.findByStatusOrderByCreatedAtAsc(
                eq(PendingCreditUpgrade.STATUS_PENDING_3DS), any()))
                .thenReturn(List.of(pending));

        reconciler.reconcile();

        verify(invoiceService, never()).retrieve(any(String.class));
        verifyNoInteractions(creditAttributionService);
    }

    @Test
    @DisplayName("PENDING_3DS > 24h with unpaid invoice: voided + deleted + marked FAILED")
    void pending3DSAbandonedCleanedUp() throws Exception {
        PendingCreditUpgrade pending = buildPending(
                PendingCreditUpgrade.STATUS_PENDING_3DS,
                LocalDateTime.now().minusHours(25));
        when(pendingRepo.findByStatusOrderByCreatedAtAsc(
                eq(PendingCreditUpgrade.STATUS_PAID_SUB_PENDING), any()))
                .thenReturn(List.of());
        when(pendingRepo.findByStatusOrderByCreatedAtAsc(
                eq(PendingCreditUpgrade.STATUS_PENDING_3DS), any()))
                .thenReturn(List.of(pending));

        Invoice openInvoice = mock(Invoice.class);
        when(openInvoice.getStatus()).thenReturn("open");
        when(openInvoice.getId()).thenReturn("in_test");
        when(invoiceService.retrieve("in_test")).thenReturn(openInvoice);

        reconciler.reconcile();

        verify(invoiceService).voidInvoice("in_test");
        verify(invoiceItemService).delete("ii_test");
        verify(pendingRepo).save(argThat(p ->
                PendingCreditUpgrade.STATUS_FAILED.equals(p.getStatus())
                        && "3ds_abandoned_24h".equals(p.getErrorMessage())));
    }

    @Test
    @DisplayName("PENDING_3DS > 24h with metadata-fallback item id: void invoice but SKIP item delete")
    void pending3DSAbandonedSkipsDeleteOnMetadataFallback() throws Exception {
        PendingCreditUpgrade pending = buildPending(
                PendingCreditUpgrade.STATUS_PENDING_3DS,
                LocalDateTime.now().minusHours(25));
        pending.setStripeInvoiceItemId("metadata-fallback:in_test");
        when(pendingRepo.findByStatusOrderByCreatedAtAsc(
                eq(PendingCreditUpgrade.STATUS_PAID_SUB_PENDING), any()))
                .thenReturn(List.of());
        when(pendingRepo.findByStatusOrderByCreatedAtAsc(
                eq(PendingCreditUpgrade.STATUS_PENDING_3DS), any()))
                .thenReturn(List.of(pending));

        Invoice openInvoice = mock(Invoice.class);
        when(openInvoice.getStatus()).thenReturn("open");
        when(openInvoice.getId()).thenReturn("in_test");
        when(invoiceService.retrieve("in_test")).thenReturn(openInvoice);

        reconciler.reconcile();

        verify(invoiceService).voidInvoice("in_test");
        // Crucial: never call delete on the synthetic id - would 404 on Stripe.
        verify(invoiceItemService, never()).delete(any(String.class));
        verify(pendingRepo).save(argThat(p ->
                PendingCreditUpgrade.STATUS_FAILED.equals(p.getStatus())));
    }

    @Test
    @DisplayName("PENDING_3DS > 24h that turned out paid: drives completion (race resolved)")
    void pending3DSRacePaidCompletes() throws Exception {
        PendingCreditUpgrade pending = buildPending(
                PendingCreditUpgrade.STATUS_PENDING_3DS,
                LocalDateTime.now().minusHours(25));
        when(pendingRepo.findByStatusOrderByCreatedAtAsc(
                eq(PendingCreditUpgrade.STATUS_PAID_SUB_PENDING), any()))
                .thenReturn(List.of());
        when(pendingRepo.findByStatusOrderByCreatedAtAsc(
                eq(PendingCreditUpgrade.STATUS_PENDING_3DS), any()))
                .thenReturn(List.of(pending));

        Invoice paidInvoice = mock(Invoice.class);
        when(paidInvoice.getStatus()).thenReturn("paid");
        when(invoiceService.retrieve("in_test")).thenReturn(paidInvoice);

        // Stripe sub retrieve / update stubs for alignSubItemIdempotent.
        com.stripe.model.Subscription stripeSub = mock(com.stripe.model.Subscription.class);
        com.stripe.model.SubscriptionItemCollection items =
                mock(com.stripe.model.SubscriptionItemCollection.class);
        when(stripeSub.getItems()).thenReturn(items);
        when(items.getData()).thenReturn(java.util.Collections.emptyList());
        when(stripeSubscriptionService.retrieve("sub_test")).thenReturn(stripeSub);

        when(subscriptionRepository.findById(SUB_ID)).thenReturn(Optional.empty());

        reconciler.reconcile();

        // Race-resolved path: void NOT called (invoice already paid).
        verify(invoiceService, never()).voidInvoice(any(String.class));
        verify(creditAttributionService).handleCreditUpgradeInvoicePaid(pending);
        verify(pendingRepo).save(argThat(p ->
                PendingCreditUpgrade.STATUS_COMPLETED.equals(p.getStatus())
                        && p.getCompletedAt() != null));
    }
}

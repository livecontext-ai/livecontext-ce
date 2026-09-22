package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.BillingEvent;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.BillingEventRepository;
import com.apimarketplace.auth.util.NonceUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reconciler re-grants paid PAYG top-ups the webhook could not attribute.
 *
 * <p>Regression: two $10 top-ups (8000 credits each) were charged on 2026-09-06 and dropped
 * with "has invalid nonce - skipping grant" because the webhook landed on the auth replica that
 * had not generated the nonce (ephemeral per-pod AES key). The stored events were the only
 * trace; a Stripe redelivery is deduplicated by event id, so nothing ever retried them.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaygTopupReconciler re-grants paid PAYG top-ups that never reached the ledger")
class PaygTopupReconcilerTest {

    @Mock private BillingEventRepository billingEventRepository;
    @Mock private BillingCustomerRepository billingCustomerRepository;
    @Mock private CreditAttributionService creditAttributionService;
    @Mock private NonceUtil nonceUtil;
    @Mock private ObjectProvider<SubscriptionCacheBuster> cacheBusterProvider;
    @Mock private SubscriptionCacheBuster cacheBuster;
    @Mock private PlatformTransactionManager transactionManager;

    private final ObjectMapper json = new ObjectMapper();
    private PaygTopupReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new PaygTopupReconciler(billingEventRepository, billingCustomerRepository,
                creditAttributionService, nonceUtil, cacheBusterProvider, transactionManager);
        lenient().when(cacheBusterProvider.getIfAvailable()).thenReturn(cacheBuster);
    }

    /** Mirrors the real Stripe checkout.session.completed payload shape stored in billing_event. */
    private BillingEvent paidTopup(String eventId, String sessionId, String customer, String nonce,
                                   String tier, String creditAmount) throws Exception {
        String payload = "{\"data\":{\"object\":{"
                + "\"id\":\"" + sessionId + "\",\"object\":\"checkout.session\","
                + (customer == null ? "" : "\"customer\":\"" + customer + "\",")
                + (nonce == null ? "" : "\"client_reference_id\":\"" + nonce + "\",")
                + "\"payment_status\":\"paid\",\"mode\":\"payment\","
                + "\"metadata\":{\"kind\":\"payg_topup\",\"tier\":\"" + tier + "\""
                + (creditAmount == null ? "" : ",\"credit_amount\":\"" + creditAmount + "\"") + "}}}}";
        return new BillingEvent("stripe", eventId, "checkout.session.completed", json.readTree(payload));
    }

    private BillingCustomer customerOfUser(long userId) {
        User user = new User();
        user.setId(userId);
        BillingCustomer bc = new BillingCustomer(user, "internal");
        bc.setProviderCustomerId("cus_" + userId);
        return bc;
    }

    @Test
    @DisplayName("an undecodable nonce is resolved through the Stripe customer and the top-up is granted with the session id as idempotence key")
    void recoversViaStripeCustomerWhenNonceIsUnreadable() throws Exception {
        BillingEvent event = paidTopup("evt_1", "cs_live_a170", "cus_121", "n_dead_key", "small", "8000");
        when(billingEventRepository.findPaidPaygTopupsWithoutLedger(any(), anyInt())).thenReturn(List.of(event));
        when(nonceUtil.decodeNonce("n_dead_key")).thenReturn(null);
        when(billingCustomerRepository.findByProviderCustomerId("cus_121")).thenReturn(Optional.of(customerOfUser(121L)));

        int granted = reconciler.reconcileOnce();

        assertThat(granted).isEqualTo(1);
        verify(creditAttributionService).grantPaygTopup(eq(121L), eq(new BigDecimal("8000")), eq("cs_live_a170"), eq("small"));
        verify(cacheBuster).fanOutForOwner(121L, "reconcile.payg_topup");
    }

    @Test
    @DisplayName("a decodable nonce wins and the customer is not looked up")
    void nonceTakesPrecedence() throws Exception {
        BillingEvent event = paidTopup("evt_2", "cs_ok", "cus_121", "n_ok", "medium", "40000");
        when(billingEventRepository.findPaidPaygTopupsWithoutLedger(any(), anyInt())).thenReturn(List.of(event));
        when(nonceUtil.decodeNonce("n_ok")).thenReturn(121L);

        assertThat(reconciler.reconcileOnce()).isEqualTo(1);

        verify(creditAttributionService).grantPaygTopup(eq(121L), eq(new BigDecimal("40000")), eq("cs_ok"), eq("medium"));
        verify(billingCustomerRepository, never()).findByProviderCustomerId(any());
    }

    @Test
    @DisplayName("a session with no nonce at all is still attributed through the customer")
    void missingNonceFallsBackToCustomer() throws Exception {
        BillingEvent event = paidTopup("evt_3", "cs_nononce", "cus_121", null, "small", "8000");
        when(billingEventRepository.findPaidPaygTopupsWithoutLedger(any(), anyInt())).thenReturn(List.of(event));
        when(billingCustomerRepository.findByProviderCustomerId("cus_121")).thenReturn(Optional.of(customerOfUser(121L)));

        assertThat(reconciler.reconcileOnce()).isEqualTo(1);

        verify(nonceUtil, never()).decodeNonce(any());
        verify(creditAttributionService).grantPaygTopup(eq(121L), eq(new BigDecimal("8000")), eq("cs_nononce"), eq("small"));
    }

    @Test
    @DisplayName("an event that resolves to no user is skipped, not granted to anyone, and does not stop the pass")
    void unattributableEventIsSkippedAndTheOthersProceed() throws Exception {
        BillingEvent orphan = paidTopup("evt_orphan", "cs_orphan", "cus_unknown", "n_dead", "small", "8000");
        BillingEvent good = paidTopup("evt_good", "cs_good", "cus_121", "n_dead2", "small", "8000");
        when(billingEventRepository.findPaidPaygTopupsWithoutLedger(any(), anyInt())).thenReturn(List.of(orphan, good));
        when(nonceUtil.decodeNonce(any())).thenReturn(null);
        when(billingCustomerRepository.findByProviderCustomerId("cus_unknown")).thenReturn(Optional.empty());
        when(billingCustomerRepository.findByProviderCustomerId("cus_121")).thenReturn(Optional.of(customerOfUser(121L)));

        assertThat(reconciler.reconcileOnce()).isEqualTo(1);

        verify(creditAttributionService, never()).grantPaygTopup(any(), any(), eq("cs_orphan"), any());
        verify(creditAttributionService).grantPaygTopup(eq(121L), eq(new BigDecimal("8000")), eq("cs_good"), eq("small"));
    }

    @Test
    @DisplayName("a grant failure on one event is logged and the next event is still processed")
    void grantFailureDoesNotAbortThePass() throws Exception {
        BillingEvent first = paidTopup("evt_a", "cs_a", "cus_121", "n", "small", "8000");
        BillingEvent second = paidTopup("evt_b", "cs_b", "cus_121", "n", "small", "8000");
        when(billingEventRepository.findPaidPaygTopupsWithoutLedger(any(), anyInt())).thenReturn(List.of(first, second));
        when(nonceUtil.decodeNonce("n")).thenReturn(121L);
        doThrow(new IllegalStateException("db down")).when(creditAttributionService)
                .grantPaygTopup(eq(121L), any(), eq("cs_a"), any());

        assertThat(reconciler.reconcileOnce()).isEqualTo(1);

        verify(creditAttributionService).grantPaygTopup(eq(121L), eq(new BigDecimal("8000")), eq("cs_b"), eq("small"));
        // One transaction per event: the failed one rolled back, the good one committed.
        verify(transactionManager, times(1)).rollback(any());
        verify(transactionManager, times(1)).commit(any());
    }

    @Test
    @DisplayName("unusable or non-positive credit_amount metadata is skipped (never a zero or negative grant)")
    void badCreditAmountIsSkipped() throws Exception {
        BillingEvent missing = paidTopup("evt_m", "cs_m", "cus_121", "n", "small", null);
        BillingEvent garbage = paidTopup("evt_g", "cs_g", "cus_121", "n", "small", "lots");
        BillingEvent zero = paidTopup("evt_z", "cs_z", "cus_121", "n", "small", "0");
        when(billingEventRepository.findPaidPaygTopupsWithoutLedger(any(), anyInt()))
                .thenReturn(List.of(missing, garbage, zero));

        assertThat(reconciler.reconcileOnce()).isZero();

        verify(creditAttributionService, never()).grantPaygTopup(any(), any(), any(), any());
        verify(nonceUtil, never()).decodeNonce(any());
    }

    @Test
    @DisplayName("nothing to recover is a silent no-op")
    void emptyPassIsANoOp() {
        when(billingEventRepository.findPaidPaygTopupsWithoutLedger(any(), anyInt())).thenReturn(List.of());

        assertThat(reconciler.reconcileOnce()).isZero();

        verify(creditAttributionService, never()).grantPaygTopup(any(), any(), any(), any());
    }
}

package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.PersonalOfferCheckoutAttempt;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.RewardRedemption;
import com.apimarketplace.auth.domain.RewardStatus;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.*;
import com.stripe.StripeClient;
import com.stripe.model.Invoice;
import com.stripe.model.InvoiceLineItem;
import com.stripe.model.InvoiceLineItemCollection;
import com.stripe.model.InvoiceCollection;
import com.stripe.model.checkout.Session;
import com.stripe.model.checkout.SessionCollection;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PersonalOfferPaymentServiceTest {
    private final PersonalOfferCheckoutAttemptRepository attempts = mock(PersonalOfferCheckoutAttemptRepository.class);
    private final PersonalOfferFirstPaidPurchaseRepository firstPaid = mock(PersonalOfferFirstPaidPurchaseRepository.class);
    private final PersonalOfferReversedInvoiceRepository reversals = mock(PersonalOfferReversedInvoiceRepository.class);
    private final RewardCodeRepository codes = mock(RewardCodeRepository.class);
    private final RewardRedemptionRepository redemptions = mock(RewardRedemptionRepository.class);
    private final BillingCustomerRepository customers = mock(BillingCustomerRepository.class);
    private final SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RewardService rewards = mock(RewardService.class);
    private final PriceCacheService prices = mock(PriceCacheService.class);
    private final StripeClient stripe = mock(StripeClient.class, RETURNS_DEEP_STUBS);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Query lockQuery = mock(Query.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private PersonalOfferPaymentService service;

    @BeforeEach
    void setUp() {
        service = new PersonalOfferPaymentService(attempts, firstPaid, reversals, codes,
                redemptions, customers, subscriptions, users, rewards, prices, stripe, transactionManager);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(lockQuery);
        when(lockQuery.setParameter(any(String.class), any())).thenReturn(lockQuery);
        when(lockQuery.getSingleResult()).thenReturn(1);
    }

    @Test
    void paidInvoiceUsesCheckoutPriceSnapshotAfterCatalogPriceChanges() {
        PersonalOfferCheckoutAttempt attempt = new PersonalOfferCheckoutAttempt();
        attempt.setPlanCode("PRO");
        attempt.setCadence("monthly");
        attempt.setCreditTierIndex(1);
        attempt.setPlanPriceId("price_plan_at_checkout");
        attempt.setCreditPriceId("price_pack_at_checkout");
        when(prices.getPriceId("PRO", "monthly")).thenReturn(Optional.of("price_new_plan"));
        when(prices.getCreditPriceId("PRO", "monthly")).thenReturn(Optional.of("price_new_pack"));

        Invoice invoice = new Invoice();
        InvoiceLineItemCollection lines = new InvoiceLineItemCollection();
        lines.setData(List.of(line("price_plan_at_checkout", 1), line("price_pack_at_checkout", 10)));
        invoice.setLines(lines);

        assertThat(ReflectionTestUtils.<Boolean>invokeMethod(service, "invoiceMatchesSelection", invoice, attempt))
                .isTrue();
        verifyNoInteractions(prices);
    }

    @Test
    void refundMarkerBeforePaidWebhookConsumesFirstPurchaseButNeverGrants() {
        User user = new User();
        user.setId(7L);
        BillingCustomer customer = new BillingCustomer();
        customer.setUser(user);
        customer.setProviderCustomerId("cus_7");
        when(customers.findByProviderCustomerId("cus_7")).thenReturn(Optional.of(customer));
        when(users.lockForPersonalOffer(7L)).thenReturn(Optional.of(user));
        when(attempts.findByStripeSubscriptionId("sub_7")).thenReturn(Optional.empty());
        when(reversals.existsById("in_7")).thenReturn(true);

        Invoice invoice = new Invoice();
        invoice.setId("in_7");
        invoice.setCustomer("cus_7");
        invoice.setStatus("paid");
        invoice.setBillingReason("subscription_create");
        invoice.setAmountPaid(100L);
        Invoice.StatusTransitions transitions = new Invoice.StatusTransitions();
        transitions.setPaidAt(Instant.parse("2026-09-29T10:00:00Z").getEpochSecond());
        invoice.setStatusTransitions(transitions);

        service.onPaidInvoice(invoice, "sub_7");

        verify(firstPaid).saveAndFlush(argThat(row -> "PAID".equals(row.getStatus())
                && "in_7".equals(row.getInvoiceId())));
        verifyNoInteractions(rewards);
    }

    @Test
    void expiredUnpaidStripeSessionBecomesTerminalAndLeavesReconcileQueue() throws Exception {
        PersonalOfferCheckoutAttempt attempt = new PersonalOfferCheckoutAttempt();
        attempt.setId(java.util.UUID.randomUUID());
        attempt.setStatus("OPEN");
        attempt.setStripeSessionId("cs_expired");
        when(attempts.lockById(attempt.getId())).thenReturn(Optional.of(attempt));
        Session session = new Session();
        session.setId("cs_expired");
        session.setStatus("expired");
        when(stripe.checkout().sessions().retrieve("cs_expired")).thenReturn(session);

        service.reconcileAttempt(attempt);

        assertThat(attempt.getStatus()).isEqualTo("EXPIRED");
        verify(attempts).save(attempt);
        verify(attempts).scheduleNextReconcile(eq(attempt.getId()), any(Instant.class));
        verify(stripe.invoices(), never()).list(any(com.stripe.param.InvoiceListParams.class));
    }

    @Test
    void reconciliationCommitsAttemptTransitionBeforeTakingInvoiceLock() throws Exception {
        PersonalOfferCheckoutAttempt attempt = new PersonalOfferCheckoutAttempt();
        attempt.setId(java.util.UUID.randomUUID());
        attempt.setStatus("OPEN");
        attempt.setStripeSessionId("cs_paid");
        when(attempts.lockById(attempt.getId())).thenReturn(Optional.of(attempt));
        Session session = new Session();
        session.setId("cs_paid");
        session.setStatus("complete");
        session.setSubscription("sub_paid");
        when(stripe.checkout().sessions().retrieve("cs_paid")).thenReturn(session);
        Invoice invoice = new Invoice();
        invoice.setId("in_paid");
        invoice.setStatus("paid");
        invoice.setAmountPaid(100L);
        invoice.setBillingReason("subscription_create");
        InvoiceCollection invoices = mock(InvoiceCollection.class);
        when(invoices.autoPagingIterable()).thenReturn(List.of(invoice));
        when(stripe.invoices().list(any(com.stripe.param.InvoiceListParams.class))).thenReturn(invoices);

        service.reconcileAttempt(attempt);

        var order = inOrder(transactionManager, entityManager);
        order.verify(transactionManager).commit(any());
        order.verify(entityManager).createNativeQuery(any(String.class));
    }

    @Test
    void unexpectedZeroPaidFirstInvoiceIsDurableReviewWithoutBonus() {
        PersonalOfferCheckoutAttempt attempt = payableAttempt();
        BillingCustomer customer = customer();
        when(customers.findByProviderCustomerId("cus_7")).thenReturn(Optional.of(customer));
        when(users.lockForPersonalOffer(7L)).thenReturn(Optional.of(customer.getUser()));
        when(attempts.findByStripeSubscriptionId("sub_7")).thenReturn(Optional.of(attempt));
        when(attempts.lockById(attempt.getId())).thenReturn(Optional.of(attempt));
        Invoice invoice = paidInvoice(0L);

        service.onPaidInvoice(invoice, "sub_7");

        assertThat(attempt.getStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(attempt.getStripeInvoiceId()).isEqualTo("in_7");
        verify(attempts).saveAndFlush(attempt);
        verifyNoInteractions(rewards);
        verify(firstPaid, never()).saveAndFlush(any());
    }

    @Test
    void positiveFirstInvoiceWithProvisionedSubscriptionGrantsExactlySelectedBonus() throws Exception {
        PersonalOfferCheckoutAttempt attempt = payableAttempt();
        BillingCustomer customer = customer();
        when(customers.findByProviderCustomerId("cus_7")).thenReturn(Optional.of(customer));
        when(users.lockForPersonalOffer(7L)).thenReturn(Optional.of(customer.getUser()));
        when(attempts.findByStripeSubscriptionId("sub_7")).thenReturn(Optional.of(attempt));
        when(attempts.lockById(attempt.getId())).thenReturn(Optional.of(attempt));
        Invoice invoice = paidInvoice(1000L);
        InvoiceLineItemCollection lines = new InvoiceLineItemCollection();
        lines.setData(List.of(line("price_plan_at_checkout", 1), line("price_pack_at_checkout", 10)));
        invoice.setLines(lines);
        InvoiceCollection history = mock(InvoiceCollection.class);
        when(history.autoPagingIterable()).thenReturn(List.of(invoice));
        when(stripe.invoices().list(any(com.stripe.param.InvoiceListParams.class))).thenReturn(history);
        Subscription subscription = new Subscription();
        subscription.setStatus("active");
        subscription.setBillingCustomer(customer);
        when(subscriptions.findByProviderSubscriptionId("sub_7")).thenReturn(Optional.of(subscription));
        RewardCode code = new RewardCode();
        code.setId(50L);
        code.setRecipientUserId(7L);
        when(codes.findById(50L)).thenReturn(Optional.of(code));
        RewardRedemption qualified = new RewardRedemption();
        qualified.setId(80L);
        qualified.setStatus(RewardStatus.QUALIFIED);
        RewardRedemption released = new RewardRedemption();
        released.setId(80L);
        released.setStatus(RewardStatus.RELEASED);
        when(rewards.qualifyPersonalPaid(eq(code), eq(attempt.getId()), eq("sub_7"), eq("in_7"),
                eq(8_000), any(Instant.class))).thenReturn(qualified);
        when(redemptions.findById(80L)).thenReturn(Optional.of(released));

        service.onPaidInvoice(invoice, "sub_7");

        assertThat(attempt.getStatus()).isEqualTo("GRANTED");
        verify(rewards).releaseOne(80L);
        verify(firstPaid).saveAndFlush(argThat(row -> "PAID".equals(row.getStatus())
                && "in_7".equals(row.getInvoiceId())));
    }

    @Test
    void unknownCreateOutcomeRecoversSessionByAttemptMetadata() throws Exception {
        PersonalOfferCheckoutAttempt attempt = payableAttempt();
        attempt.setStatus("CREATING");
        attempt.setStripeSessionId(null);
        attempt.setStripeCustomerId("cus_7");
        attempt.setCreatedAt(Instant.now().minusSeconds(120));
        when(attempts.lockById(attempt.getId())).thenReturn(Optional.of(attempt));
        Session session = new Session();
        session.setId("cs_recovered");
        session.setStatus("open");
        session.setUrl("https://checkout.stripe.test/recovered");
        session.setMetadata(java.util.Map.of("personal_offer_attempt_id", attempt.getId().toString()));
        SessionCollection found = mock(SessionCollection.class);
        when(found.autoPagingIterable()).thenReturn(List.of(session));
        when(stripe.checkout().sessions().list(any(com.stripe.param.checkout.SessionListParams.class)))
                .thenReturn(found);

        service.reconcileAttempt(attempt);

        assertThat(attempt.getStatus()).isEqualTo("OPEN");
        assertThat(attempt.getStripeSessionId()).isEqualTo("cs_recovered");
        verify(attempts).save(attempt);
    }

    private PersonalOfferCheckoutAttempt payableAttempt() {
        PersonalOfferCheckoutAttempt attempt = new PersonalOfferCheckoutAttempt();
        attempt.setId(java.util.UUID.randomUUID());
        attempt.setStatus("OPEN");
        attempt.setRewardCodeId(50L);
        attempt.setRecipientUserId(7L);
        attempt.setPlanCode("PRO");
        attempt.setCadence("monthly");
        attempt.setCreditTierIndex(1);
        attempt.setPlanPriceId("price_plan_at_checkout");
        attempt.setCreditPriceId("price_pack_at_checkout");
        attempt.setBonusCredits(8_000);
        attempt.setSessionExpiresAt(Instant.now().plusSeconds(1800));
        return attempt;
    }

    private BillingCustomer customer() {
        User user = new User();
        user.setId(7L);
        BillingCustomer customer = new BillingCustomer();
        customer.setUser(user);
        customer.setProviderCustomerId("cus_7");
        return customer;
    }

    private Invoice paidInvoice(long amount) {
        Invoice invoice = new Invoice();
        invoice.setId("in_7");
        invoice.setCustomer("cus_7");
        invoice.setStatus("paid");
        invoice.setBillingReason("subscription_create");
        invoice.setAmountPaid(amount);
        Invoice.StatusTransitions transitions = new Invoice.StatusTransitions();
        transitions.setPaidAt(Instant.parse("2026-09-29T10:00:00Z").getEpochSecond());
        invoice.setStatusTransitions(transitions);
        return invoice;
    }

    private InvoiceLineItem line(String priceId, long quantity) {
        var details = new InvoiceLineItem.Pricing.PriceDetails();
        details.setPrice(priceId);
        var pricing = new InvoiceLineItem.Pricing();
        pricing.setPriceDetails(details);
        var line = new InvoiceLineItem();
        line.setPricing(pricing);
        line.setQuantity(quantity);
        return line;
    }
}

package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.BillingCustomer;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.BillingCustomerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.RewardService;
import com.stripe.model.Charge;
import com.stripe.model.Invoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The referral glue inside WebhookController: the paid-conversion gate
 * (tryQualifyReferralConversion) and the refund clawback resolution. The
 * conversion lifecycle itself is unit-tested in RewardConversionLifecycleTest;
 * here we pin the WebhookController gating decisions. The handlers are private,
 * so they are invoked via ReflectionTestUtils (the established pattern in
 * WebhookControllerTest); rewardService is field-injected.
 */
@DisplayName("WebhookController - referral conversion + clawback gating")
class WebhookControllerReferralTest {

    private SubscriptionRepository subscriptionRepository;
    private BillingCustomerRepository billingCustomerRepository;
    private RewardService rewardService;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        billingCustomerRepository = mock(BillingCustomerRepository.class);
        rewardService = mock(RewardService.class);
        controller = new WebhookController(
                mock(com.apimarketplace.auth.repository.BillingEventRepository.class),
                mock(com.apimarketplace.auth.service.SubscriptionService.class),
                billingCustomerRepository,
                subscriptionRepository,
                mock(com.fasterxml.jackson.databind.ObjectMapper.class),
                mock(com.apimarketplace.auth.repository.PlanRepository.class),
                mock(com.apimarketplace.auth.service.PriceCacheService.class),
                mock(com.apimarketplace.auth.service.CreditAttributionService.class),
                mock(com.apimarketplace.auth.repository.PendingCreditUpgradeRepository.class),
                mock(com.apimarketplace.auth.service.StripeBillingService.class),
                mock(com.apimarketplace.auth.util.NonceUtil.class),
                mock(com.stripe.StripeClient.class),
                "whsec_test");
        ReflectionTestUtils.setField(controller, "rewardService", rewardService);
    }

    private void mockLocalSub(String subId, String planCode, Long userId) {
        Plan plan = mock(Plan.class);
        lenient().when(plan.getCode()).thenReturn(planCode);
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(userId);
        BillingCustomer bc = mock(BillingCustomer.class);
        lenient().when(bc.getUser()).thenReturn(user);
        Subscription sub = mock(Subscription.class);
        lenient().when(sub.getPlan()).thenReturn(plan);
        lenient().when(sub.getBillingCustomer()).thenReturn(bc);
        lenient().when(subscriptionRepository.findByProviderSubscriptionId(subId)).thenReturn(Optional.of(sub));
    }

    private Invoice paidInvoice(long amountPaid) {
        Invoice inv = mock(Invoice.class);
        lenient().when(inv.getId()).thenReturn("in_1");
        lenient().when(inv.getStatus()).thenReturn("paid");
        lenient().when(inv.getAmountPaid()).thenReturn(amountPaid);
        return inv;
    }

    private void qualify(Invoice inv, String subId) {
        ReflectionTestUtils.invokeMethod(controller, "tryQualifyReferralConversion", inv, subId);
    }

    @Test
    @DisplayName("qualifies on a captured paid invoice for a non-FREE plan")
    void qualifiesOnPaidConversion() {
        mockLocalSub("sub_1", "STARTER", 7L);
        qualify(paidInvoice(2000L), "sub_1");
        verify(rewardService).qualifyOnPaidConversion(7L, "sub_1");
    }

    @Test
    @DisplayName("does NOT qualify on a trial's zero-amount invoice")
    void noQualifyOnZeroAmount() {
        mockLocalSub("sub_1", "STARTER", 7L);
        qualify(paidInvoice(0L), "sub_1");
        verify(rewardService, never()).qualifyOnPaidConversion(anyLong(), anyString());
    }

    @Test
    @DisplayName("does NOT qualify when the plan is FREE")
    void noQualifyOnFreePlan() {
        mockLocalSub("sub_1", "FREE", 7L);
        qualify(paidInvoice(2000L), "sub_1");
        verify(rewardService, never()).qualifyOnPaidConversion(anyLong(), anyString());
    }

    @Test
    @DisplayName("does NOT qualify when the invoice status is not paid")
    void noQualifyWhenNotPaid() {
        mockLocalSub("sub_1", "STARTER", 7L);
        Invoice inv = mock(Invoice.class);
        lenient().when(inv.getId()).thenReturn("in_1");
        lenient().when(inv.getStatus()).thenReturn("open");
        lenient().when(inv.getAmountPaid()).thenReturn(2000L);
        qualify(inv, "sub_1");
        verify(rewardService, never()).qualifyOnPaidConversion(anyLong(), anyString());
    }

    @Test
    @DisplayName("does NOT qualify before the local subscription is provisioned (sweeper backstop)")
    void noQualifyWhenLocalSubMissing() {
        when(subscriptionRepository.findByProviderSubscriptionId("sub_x")).thenReturn(Optional.empty());
        qualify(paidInvoice(2000L), "sub_x");
        verify(rewardService, never()).qualifyOnPaidConversion(anyLong(), anyString());
    }

    @Test
    @DisplayName("a full refund claws back the referee's reward, resolved from the charge customer")
    void fullRefundClawsBack() {
        Charge charge = mock(Charge.class);
        lenient().when(charge.getId()).thenReturn("ch_1");
        lenient().when(charge.getAmount()).thenReturn(2000L);
        lenient().when(charge.getAmountRefunded()).thenReturn(2000L);
        lenient().when(charge.getCustomer()).thenReturn("cus_1");
        BillingCustomer bc = mock(BillingCustomer.class);
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(7L);
        lenient().when(bc.getUser()).thenReturn(user);
        when(billingCustomerRepository.findByProviderCustomerId("cus_1")).thenReturn(Optional.of(bc));

        ReflectionTestUtils.invokeMethod(controller, "handleChargeRefunded", charge);

        verify(rewardService).clawbackByRedeemerUserId(7L, "REFUNDED");
    }

    @Test
    @DisplayName("a partial refund does NOT claw back")
    void partialRefundNoClawback() {
        Charge charge = mock(Charge.class);
        lenient().when(charge.getId()).thenReturn("ch_1");
        lenient().when(charge.getAmount()).thenReturn(2000L);
        lenient().when(charge.getAmountRefunded()).thenReturn(500L);
        ReflectionTestUtils.invokeMethod(controller, "handleChargeRefunded", charge);
        verify(rewardService, never()).clawbackByRedeemerUserId(anyLong(), anyString());
    }
}

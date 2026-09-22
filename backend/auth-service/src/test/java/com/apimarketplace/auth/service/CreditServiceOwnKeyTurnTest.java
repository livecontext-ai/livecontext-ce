package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;
import com.apimarketplace.common.credit.ModelTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A turn that ran on the tenant's OWN provider key: the ledger takes a flat fee per turn
 * (the provider bills the tokens), the row is tagged with the route and the provider-side
 * estimate, and the result still reports what the turn CONSUMED at the platform rate so
 * counters and budgets keep their meaning. Before this path such a turn was billed the
 * token rate: the user paid twice.
 *
 * <p>Runs against a real subscription (metered mode, the cloud shape): the fee is what
 * leaves the balance. Unlimited mode (CE, dedicated) is covered on its own.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditService.consumeForOwnKeyTurn")
class CreditServiceOwnKeyTurnTest {

    private static final Long USER_ID = 42L;
    private static final LlmTokenBreakdown USAGE = LlmTokenBreakdown.of(30_000, 800);
    private static final LlmTokenBreakdown NO_USAGE = LlmTokenBreakdown.of(0, 0);

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private CreditLedgerRepository ledgerRepository;
    @Mock private ModelPricingService pricingService;

    private CreditService service;
    private Subscription subscription;
    /** The last row the ledger saved (the chat idempotency lookup finds "the row just written"). */
    private final AtomicReference<CreditLedgerEntry> lastSaved = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        service = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
        ReflectionTestUtils.setField(service, "ownKeyTurnPricing", new OwnKeyTurnPricing(
                new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("5"), new BigDecimal("10"), new BigDecimal("2")));
        subscription = new Subscription();
        subscription.setId(1L);
        subscription.setRemainingCredits(new BigDecimal("100.0000"));
        lenient().when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(subscription));
        lenient().when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.of(subscription));
        lenient().when(ledgerRepository.save(any())).thenAnswer(inv -> {
            lastSaved.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        lenient().when(ledgerRepository.findFirstBySourceIdAndSourceType(any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(lastSaved.get()));
        lenient().when(pricingService.calculateCost("anthropic", "claude-sonnet", USAGE)).thenReturn(new BigDecimal("60.0000"));
        lenient().when(pricingService.providerCost("anthropic", "claude-sonnet", USAGE)).thenReturn(new BigDecimal("30.0000"));
        lenient().when(pricingService.calculateCost("anthropic", "claude-sonnet", NO_USAGE)).thenReturn(BigDecimal.ZERO);
        lenient().when(pricingService.providerCost("anthropic", "claude-sonnet", NO_USAGE)).thenReturn(BigDecimal.ZERO);
        lenient().when(pricingService.tierOf("anthropic", "claude-sonnet")).thenReturn(ModelTier.HIGH);
    }

    @Test
    @DisplayName("bills the tier's flat fee against the subscription, not the token cost, and tags the very row it writes")
    void billsFlatFeeAndTagsRow() {
        CreditConsumeResult result = service.consumeForOwnKeyTurn(
                USER_ID, "exec-1", "anthropic", "claude-sonnet", USAGE, "AGENT_EXECUTION");

        assertThat(result.success()).isTrue();
        // The debit is the HIGH-tier fee (5), where the platform route would have taken 60.
        assertThat(result.creditsUsed()).isEqualByComparingTo("5");
        assertThat(subscription.getRemainingCredits()).isEqualByComparingTo("95");
        // ...and the turn still reports what it consumed at the platform rate.
        assertThat(result.consumptionCredits()).isEqualByComparingTo("60");

        // ONE row, tagged as it is written: no second lookup, no second save.
        verify(ledgerRepository, times(1)).save(any());
        CreditLedgerEntry row = lastSaved.get();
        assertThat(row.getAmount()).isEqualByComparingTo("-5");
        assertThat(row.getSourceType()).isEqualTo("AGENT_EXECUTION");
        assertThat(row.getKeyRoute()).isEqualTo(CreditService.KEY_ROUTE_OWN_KEY);
        assertThat(row.getProviderCostCredits()).isEqualByComparingTo("30");
        assertThat(row.getPromptTokens()).isEqualTo(30_000);
        assertThat(row.getCompletionTokens()).isEqualTo(800);
        assertThat(row.getDescription()).contains("own key").contains("high tier").contains("flat 5 credits");
        verify(ledgerRepository, never()).findFirstBySourceIdAndSourceType(any(), any());
    }

    @Test
    @DisplayName("an unknown model (no pricing row) bills the unknown-tier fee rather than failing or guessing a band")
    void unknownTierFee() {
        when(pricingService.tierOf("anthropic", "claude-sonnet")).thenReturn(ModelTier.UNKNOWN);

        CreditConsumeResult result = service.consumeForOwnKeyTurn(
                USER_ID, "exec-2", "anthropic", "claude-sonnet", USAGE, "AGENT_EXECUTION");

        assertThat(result.creditsUsed()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("regression: a turn that moved NO token (refused before the call, or nothing returned) is written at zero, never charged a fee for a call the provider never billed")
    void zeroUsageIsZeroFee() {
        CreditConsumeResult result = service.consumeForOwnKeyTurn(
                USER_ID, "exec-0", "anthropic", "claude-sonnet", NO_USAGE, "AGENT_EXECUTION");

        assertThat(result.success()).isTrue();
        assertThat(result.creditsUsed()).isEqualByComparingTo("0");
        assertThat(result.consumptionCredits()).isEqualByComparingTo("0");
        assertThat(subscription.getRemainingCredits()).isEqualByComparingTo("100");
        // Same as the token-rate path on zero usage: nothing consumed, nothing recorded.
        verify(ledgerRepository, never()).save(any());
    }

    @Test
    @DisplayName("a cache-only turn (no prompt or completion, cache read only) DID move tokens and is billed")
    void cacheOnlyTurnIsBilled() {
        LlmTokenBreakdown cacheOnly = new LlmTokenBreakdown(0, 0, 0, 5_000, 0, 0);
        when(pricingService.calculateCost("anthropic", "claude-sonnet", cacheOnly)).thenReturn(new BigDecimal("1.5"));
        when(pricingService.providerCost("anthropic", "claude-sonnet", cacheOnly)).thenReturn(new BigDecimal("0.75"));

        CreditConsumeResult result = service.consumeForOwnKeyTurn(
                USER_ID, "exec-c", "anthropic", "claude-sonnet", cacheOnly, "AGENT_EXECUTION");

        // Cheaper than its own tier fee, so the cap below applies: 1.5, not 5.
        assertThat(result.creditsUsed()).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("regression: a turn CHEAPER than its tier fee is billed the platform price, never more - bringing your own key must never cost more than not bringing it")
    void aTurnCheaperThanItsFeeIsCappedAtThePlatformPrice() {
        // A short turn on an expensive model: measured on the prod ledger, about a quarter of
        // the real turns on the two cheap tiers land here.
        LlmTokenBreakdown shortTurn = LlmTokenBreakdown.of(400, 60);
        when(pricingService.calculateCost("anthropic", "claude-sonnet", shortTurn)).thenReturn(new BigDecimal("1.2000"));
        when(pricingService.providerCost("anthropic", "claude-sonnet", shortTurn)).thenReturn(new BigDecimal("0.9000"));

        CreditConsumeResult result = service.consumeForOwnKeyTurn(
                USER_ID, "exec-short", "anthropic", "claude-sonnet", shortTurn, "AGENT_EXECUTION");

        // The HIGH tier fee is 5; the platform route would have taken 1.2, so 1.2 is the debit.
        assertThat(result.creditsUsed()).isEqualByComparingTo("1.2");
        assertThat(result.consumptionCredits()).isEqualByComparingTo("1.2");
        assertThat(subscription.getRemainingCredits()).isEqualByComparingTo("98.8");
        // Still an own-key row, with its estimate: the cap changes the amount, not the route.
        assertThat(lastSaved.get().getKeyRoute()).isEqualTo(CreditService.KEY_ROUTE_OWN_KEY);
        assertThat(lastSaved.get().getProviderCostCredits()).isEqualByComparingTo("0.9");
    }

    @Test
    @DisplayName("without an OwnKeyTurnPricing bean the fee is the token cost: the pre-V506 behaviour, never a free turn")
    void withoutPricingBeanBillsTokenCost() {
        ReflectionTestUtils.setField(service, "ownKeyTurnPricing", null);

        CreditConsumeResult result = service.consumeForOwnKeyTurn(
                USER_ID, "exec-3", "anthropic", "claude-sonnet", USAGE, "AGENT_EXECUTION");

        assertThat(result.creditsUsed()).isEqualByComparingTo("60");
        assertThat(result.consumptionCredits()).isEqualByComparingTo("60");
        assertThat(lastSaved.get().getKeyRoute()).isEqualTo(CreditService.KEY_ROUTE_OWN_KEY);
    }

    @Test
    @DisplayName("a chat turn keeps the platform route's idempotency: one row per conversation, the retry is a zero no-op")
    void chatIsIdempotentPerConversation() {
        CreditConsumeResult first = service.consumeForOwnKeyTurn(
                USER_ID, "conv-9", "anthropic", "claude-sonnet", USAGE, "CHAT_CONVERSATION");
        CreditConsumeResult retry = service.consumeForOwnKeyTurn(
                USER_ID, "conv-9", "anthropic", "claude-sonnet", USAGE, "CHAT_CONVERSATION");

        assertThat(first.creditsUsed()).isEqualByComparingTo("5");
        assertThat(retry.creditsUsed()).isEqualByComparingTo("0");
        assertThat(retry.consumptionCredits()).isEqualByComparingTo("0");
        verify(ledgerRepository, times(1)).save(any());
        verify(pricingService, times(1)).calculateCost("anthropic", "claude-sonnet", USAGE);
    }

    @Test
    @DisplayName("consumption is what the platform route would bill (cloud rate), the estimate is the provider's list price")
    void threeNumbersHaveThreeMeanings() {
        service.consumeForOwnKeyTurn(USER_ID, "exec-4", "anthropic", "claude-sonnet", USAGE, "AGENT_EXECUTION");

        CreditLedgerEntry row = lastSaved.get();
        assertThat(row.getAmount().negate()).as("fee").isEqualByComparingTo("5");
        assertThat(row.getProviderCostCredits()).as("provider estimate").isEqualByComparingTo("30");
        verify(pricingService).calculateCost("anthropic", "claude-sonnet", USAGE);
        verify(pricingService).providerCost("anthropic", "claude-sonnet", USAGE);
    }

    @Test
    @DisplayName("insufficient credits: the fee is refused, and the audit row of the refusal is tagged with the route like the dead-letter entry written for the same turn")
    void insufficientCreditsRefusalRowIsTagged() {
        subscription.setRemainingCredits(new BigDecimal("2.0000"));   // the HIGH-tier fee is 5

        CreditConsumeResult result = service.consumeForOwnKeyTurn(
                USER_ID, "exec-6", "anthropic", "claude-sonnet", USAGE, "AGENT_EXECUTION");

        assertThat(result.success()).isFalse();
        assertThat(subscription.getRemainingCredits()).isEqualByComparingTo("2");
        CreditLedgerEntry audit = lastSaved.get();
        assertThat(audit).isNotNull();
        assertThat(audit.getSourceType()).isEqualTo("AGENT_EXECUTION_REJECTED");
        assertThat(audit.getAmount()).isEqualByComparingTo("0");
        assertThat(audit.getKeyRoute()).isEqualTo(CreditService.KEY_ROUTE_OWN_KEY);
        assertThat(audit.getProviderCostCredits()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("no subscription: the fee is refused like any other debit, nothing is written, the route never makes a turn free")
    void noSubscriptionRefuses() {
        when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());

        CreditConsumeResult result = service.consumeForOwnKeyTurn(
                USER_ID, "exec-5", "anthropic", "claude-sonnet", USAGE, "AGENT_EXECUTION");

        assertThat(result.success()).isFalse();
        assertThat(lastSaved.get()).isNull();
    }

    @Nested
    @DisplayName("unlimited mode (CE, dedicated cloud)")
    class UnlimitedMode {

        @BeforeEach
        void unlimited() {
            service = new CreditService(subscriptionRepository, ledgerRepository, pricingService, true);
            ReflectionTestUtils.setField(service, "ownKeyTurnPricing", new OwnKeyTurnPricing(
                    new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("5"), new BigDecimal("10"), new BigDecimal("2")));
        }

        @Test
        @DisplayName("nothing is priced: the tracking row keeps the consumption figure, so its amount means the same thing as every other row, and it is still tagged")
        void unlimitedTracksConsumptionNotTheFee() {
            CreditConsumeResult result = service.consumeForOwnKeyTurn(
                    USER_ID, "exec-u", "anthropic", "claude-sonnet", USAGE, "AGENT_EXECUTION");

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo("60");
            assertThat(result.consumptionCredits()).isEqualByComparingTo("60");
            CreditLedgerEntry row = lastSaved.get();
            assertThat(row.getKeyRoute()).isEqualTo(CreditService.KEY_ROUTE_OWN_KEY);
            assertThat(row.getProviderCostCredits()).isEqualByComparingTo("30");
            verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(any());
        }
    }
}

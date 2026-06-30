package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CreditService#consumeForCeRelay} - the idempotent post-flight
 * billing path for the centralized (reserve→settle) CE LLM relay model.
 *
 * <p>The behaviours pinned here are exactly the ones the new model relies on:
 * one ledger row per execution keyed on {@code executionId}, a settle/reaper race that
 * cannot double-bill, never losing already-incurred cost, and a no-regression guarantee
 * for the legacy per-call relay (unique {@code "ce-llm-"+UUID} sourceIds).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditService.consumeForCeRelay")
class CreditServiceCeRelayTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private CreditLedgerRepository ledgerRepository;

    @Mock
    private ModelPricingService pricingService;

    @Captor
    private ArgumentCaptor<CreditLedgerEntry> ledgerCaptor;

    @Captor
    private ArgumentCaptor<Subscription> subscriptionCaptor;

    private CreditService creditService;

    private static final Long USER_ID = 42L;
    private static final String EXECUTION_ID = "ce-exec-abc-123";
    private static final String PROVIDER = "google";
    private static final String MODEL = "gemini-3-flash-preview";
    private static final int PROMPT_TOKENS = 4266;
    private static final int COMPLETION_TOKENS = 91;
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100.0000");

    @BeforeEach
    void setUp() {
        creditService = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
    }

    private void mockActiveSubscription(BigDecimal balance) {
        Subscription sub = new Subscription();
        sub.setId(1L);
        sub.setRemainingCredits(balance);
        lenient().when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                .thenReturn(Optional.of(sub));
        lenient().when(subscriptionRepository.findActiveByUserId(USER_ID))
                .thenReturn(Optional.of(sub));
    }

    @Test
    @DisplayName("settles ONE CE_LLM_RELAY row with cache-aware cost and summed tokens")
    void settlesSingleCeRelayRowWithCacheAwareCost() {
        // The aggregated usage of one execution: cache-read discount applies to most of
        // the input, plus reasoning tokens. Mirrors the real fingerprint of the bug report.
        LlmTokenBreakdown usage = new LlmTokenBreakdown(
                PROMPT_TOKENS, COMPLETION_TOKENS,
                /*cacheCreation*/ 0, /*cacheRead*/ 3999, /*cached*/ 0, /*reasoning*/ 107);
        BigDecimal cost = new BigDecimal("2.2100");
        mockActiveSubscription(INITIAL_BALANCE);
        when(pricingService.calculateCost(PROVIDER, MODEL, usage)).thenReturn(cost);

        CreditConsumeResult result = creditService.consumeForCeRelay(
                USER_ID, EXECUTION_ID, PROVIDER, MODEL, usage);

        assertThat(result.success()).isTrue();
        assertThat(result.creditsUsed()).isEqualByComparingTo(cost);
        assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("97.7900"));

        verify(ledgerRepository, times(1)).save(ledgerCaptor.capture());
        CreditLedgerEntry entry = ledgerCaptor.getValue();
        assertThat(entry.getSourceType()).isEqualTo("CE_LLM_RELAY");
        assertThat(entry.getSourceId()).isEqualTo(EXECUTION_ID);
        assertThat(entry.getProvider()).isEqualTo(PROVIDER);
        assertThat(entry.getModel()).isEqualTo(MODEL);
        assertThat(entry.getPromptTokens()).isEqualTo(PROMPT_TOKENS);
        assertThat(entry.getCompletionTokens()).isEqualTo(COMPLETION_TOKENS);
        assertThat(entry.getAmount()).isEqualByComparingTo(cost.negate());
        // Cache/reasoning composition stays in the description so a discounted amount is auditable.
        assertThat(entry.getDescription()).contains("CE relay", PROVIDER, MODEL, "cache read 3999", "reasoning 107");
    }

    @Test
    @DisplayName("idempotent on duplicate executionId - settle + reaper race cannot double-bill")
    void idempotentOnDuplicateExecutionId() {
        // The centralized model lets BOTH the end-of-execution settle and the crash-recovery
        // reaper fire with the same executionId. The second must be a silent no-op: no second
        // ledger row, no re-debit, no pricing lookup.
        CreditLedgerEntry existing = new CreditLedgerEntry();
        existing.setSourceType("CE_LLM_RELAY");
        existing.setSourceId(EXECUTION_ID);
        when(ledgerRepository.findFirstBySourceIdAndSourceType(EXECUTION_ID, "CE_LLM_RELAY"))
                .thenReturn(Optional.of(existing));
        mockActiveSubscription(INITIAL_BALANCE);

        CreditConsumeResult result = creditService.consumeForCeRelay(
                USER_ID, EXECUTION_ID, PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS));

        assertThat(result.success()).isTrue();
        assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(ledgerRepository, never()).save(any());
        verify(subscriptionRepository, never()).save(any());
        verify(pricingService, never()).calculateCost(anyString(), anyString(), any(LlmTokenBreakdown.class));
    }

    @Test
    @DisplayName("debits into negative balance + delinquent when cost exceeds balance - never loses post-flight cost")
    void debitsIntoNegativeWhenCostExceedsBalance() {
        // The relayed LLM call already ran on CE; if a reaper settles an execution whose real
        // cost overshot the balance, we MUST record the debit (delinquency), never drop it or
        // write a *_REJECTED placeholder. Mirrors consumeForChat's post-flight semantics.
        BigDecimal balance = new BigDecimal("1.0000");
        BigDecimal cost = new BigDecimal("4.9500");
        BigDecimal expectedNewBalance = balance.subtract(cost);
        mockActiveSubscription(balance);
        LlmTokenBreakdown usage = LlmTokenBreakdown.of(4183, 20);
        when(pricingService.calculateCost(PROVIDER, MODEL, usage)).thenReturn(cost);

        CreditConsumeResult result = creditService.consumeForCeRelay(
                USER_ID, EXECUTION_ID, PROVIDER, MODEL, usage);

        assertThat(result.success()).isTrue();
        assertThat(result.creditsUsed()).isEqualByComparingTo(cost);
        assertThat(result.remainingCredits()).isEqualByComparingTo(expectedNewBalance);
        assertThat(expectedNewBalance.signum()).isNegative();

        verify(subscriptionRepository).save(subscriptionCaptor.capture());
        assertThat(subscriptionCaptor.getValue().getRemainingCredits()).isEqualByComparingTo(expectedNewBalance);
        assertThat(subscriptionCaptor.getValue().getDelinquent()).isTrue();

        verify(ledgerRepository, times(1)).save(ledgerCaptor.capture());
        CreditLedgerEntry entry = ledgerCaptor.getValue();
        assertThat(entry.getSourceType()).isEqualTo("CE_LLM_RELAY");
        assertThat(entry.getAmount()).isEqualByComparingTo(cost.negate());
        assertThat(entry.getDescription()).doesNotStartWith("REJECTED:");
    }

    @Test
    @DisplayName("legacy per-call sourceId (no prior row) still debits - idempotency guard is a no-op")
    void legacyPerCallSourceIdStillDebits() {
        // Backward-compat: an un-upgraded CE install keeps billing one row per forwarded call
        // with a unique "ce-llm-"+UUID sourceId. The narrowed (sourceId, CE_LLM_RELAY) guard
        // never matches a fresh id, so the debit proceeds exactly as before - one row per call.
        String legacyPerCallSourceId = "ce-llm-550e8400-e29b-41d4-a716-446655440000";
        BigDecimal cost = new BigDecimal("0.5000");
        mockActiveSubscription(INITIAL_BALANCE);
        when(ledgerRepository.findFirstBySourceIdAndSourceType(legacyPerCallSourceId, "CE_LLM_RELAY"))
                .thenReturn(Optional.empty());
        LlmTokenBreakdown usage = LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS);
        when(pricingService.calculateCost(PROVIDER, MODEL, usage)).thenReturn(cost);

        CreditConsumeResult result = creditService.consumeForCeRelay(
                USER_ID, legacyPerCallSourceId, PROVIDER, MODEL, usage);

        assertThat(result.success()).isTrue();
        assertThat(result.creditsUsed()).isEqualByComparingTo(cost);
        verify(ledgerRepository, times(1)).save(ledgerCaptor.capture());
        assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("CE_LLM_RELAY");
        assertThat(ledgerCaptor.getValue().getSourceId()).isEqualTo(legacyPerCallSourceId);
    }

    @Test
    @DisplayName("returns failure when user has no active subscription")
    void returnsFailureWhenNoSubscription() {
        LlmTokenBreakdown usage = LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS);
        when(pricingService.calculateCost(PROVIDER, MODEL, usage)).thenReturn(new BigDecimal("0.7500"));
        lenient().when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID)).thenReturn(Optional.empty());
        lenient().when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(Optional.empty());

        CreditConsumeResult result = creditService.consumeForCeRelay(
                USER_ID, EXECUTION_ID, PROVIDER, MODEL, usage);

        assertThat(result.success()).isFalse();
        verify(ledgerRepository, never()).save(any());
    }
}

package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.repository.CreditLedgerRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreditService Tests")
class CreditServiceTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private CreditLedgerRepository ledgerRepository;

    @Mock
    private ModelPricingService pricingService;

    private CreditService creditService;

    @BeforeEach
    void setUp() {
        creditService = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
    }

    @Captor
    private ArgumentCaptor<CreditLedgerEntry> ledgerCaptor;

    @Captor
    private ArgumentCaptor<Subscription> subscriptionCaptor;

    // ===== Constants =====
    private static final Long USER_ID = 42L;
    private static final String SOURCE_ID = "exec-abc-123";
    private static final String PROVIDER = "openai";
    private static final String MODEL = "gpt-4o";
    private static final int PROMPT_TOKENS = 1000;
    private static final int COMPLETION_TOKENS = 500;
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100.0000");

    // ===== Helpers =====

    private Subscription createSubscription(BigDecimal remainingCredits) {
        Subscription sub = new Subscription();
        sub.setId(1L);
        sub.setRemainingCredits(remainingCredits);
        return sub;
    }

    private void mockActiveSubscription(BigDecimal balance) {
        Subscription sub = createSubscription(balance);
        lenient().when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                .thenReturn(Optional.of(sub));
        lenient().when(subscriptionRepository.findActiveByUserId(USER_ID))
                .thenReturn(Optional.of(sub));
    }

    private void mockNoSubscription() {
        lenient().when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                .thenReturn(Optional.empty());
        lenient().when(subscriptionRepository.findActiveByUserId(USER_ID))
                .thenReturn(Optional.empty());
    }

    // ===== consumeForAgent =====

    @Nested
    @DisplayName("consumeForAgent")
    class ConsumeForAgent {

        @Test
        @DisplayName("should deduct correct amount and create ledger entry with all fields")
        void shouldDeductCorrectAmountAndCreateLedgerEntry() {
            BigDecimal cost = new BigDecimal("0.7500");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(cost);
            assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("99.2500"));

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getUserId()).isEqualTo(USER_ID);
            assertThat(entry.getAmount()).isEqualByComparingTo(cost.negate());
            assertThat(entry.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("99.2500"));
            assertThat(entry.getSourceType()).isEqualTo("AGENT_EXECUTION");
            assertThat(entry.getSourceId()).isEqualTo(SOURCE_ID);
            assertThat(entry.getProvider()).isEqualTo(PROVIDER);
            assertThat(entry.getModel()).isEqualTo(MODEL);
            assertThat(entry.getPromptTokens()).isEqualTo(PROMPT_TOKENS);
            assertThat(entry.getCompletionTokens()).isEqualTo(COMPLETION_TOKENS);
            assertThat(entry.getDescription()).contains(PROVIDER, MODEL);
        }

        @Test
        @DisplayName("should return noSubscription result when user has no active subscription")
        void shouldReturnNoSubscriptionWhenNoActiveSubscription() {
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(new BigDecimal("0.7500"));
            mockNoSubscription();

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("No active subscription");
            verify(ledgerRepository, never()).save(any());
        }

        @Test
        @DisplayName("should skip deduction when cost is zero (0 prompt + 0 completion tokens)")
        void shouldSkipDeductionWhenCostIsZero() {
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(0, 0)))
                    .thenReturn(BigDecimal.ZERO);

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, 0, 0);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            // Balance is fetched via getBalance() inside the zero-cost path
            assertThat(result.remainingCredits()).isEqualByComparingTo(INITIAL_BALANCE);
            verify(ledgerRepository, never()).save(any());
        }

        @Test
        @DisplayName("Blocks deduction when balance is less than cost and writes a REJECTED audit row")
        void shouldBlockDeductionWhenBalanceLessThanCost() {
            BigDecimal lowBalance = new BigDecimal("0.1000");
            BigDecimal cost = new BigDecimal("5.0000");
            Subscription sub = createSubscription(lowBalance);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            // Contract unchanged: caller still sees insufficientCredits
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Insufficient credits");
            assertThat(result.remainingCredits()).isEqualByComparingTo(lowBalance);

            // Subscription NOT mutated - the debit did not happen
            verify(subscriptionRepository, never()).save(any());

            // Delta 1 - audit row is written so rejection is traceable (formerly silent loss)
            ArgumentCaptor<CreditLedgerEntry> captor = ArgumentCaptor.forClass(CreditLedgerEntry.class);
            verify(ledgerRepository).save(captor.capture());
            CreditLedgerEntry entry = captor.getValue();
            assertThat(entry.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(entry.getBalanceAfter()).isEqualByComparingTo(lowBalance);
            assertThat(entry.getSourceType()).isEqualTo("AGENT_EXECUTION_REJECTED");
            assertThat(entry.getProvider()).isEqualTo(PROVIDER);
            assertThat(entry.getModel()).isEqualTo(MODEL);
            assertThat(entry.getPromptTokens()).isEqualTo(PROMPT_TOKENS);
            assertThat(entry.getCompletionTokens()).isEqualTo(COMPLETION_TOKENS);
            assertThat(entry.getDescription()).startsWith("REJECTED:");
        }

        @Test
        @DisplayName("Blocks deduction when balance is already negative and still emits audit row")
        void shouldBlockDeductionWhenBalanceAlreadyNegative() {
            BigDecimal negativeBalance = new BigDecimal("-10.0000");
            BigDecimal cost = new BigDecimal("2.5000");
            Subscription sub = createSubscription(negativeBalance);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Insufficient credits");
            verify(subscriptionRepository, never()).save(any());

            // Rejection audit still fires - even from an already-negative balance, so Finance
            // can see continued attempt volume instead of the account going silent.
            ArgumentCaptor<CreditLedgerEntry> captor = ArgumentCaptor.forClass(CreditLedgerEntry.class);
            verify(ledgerRepository).save(captor.capture());
            assertThat(captor.getValue().getSourceType()).isEqualTo("AGENT_EXECUTION_REJECTED");
            assertThat(captor.getValue().getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(captor.getValue().getBalanceAfter()).isEqualByComparingTo(negativeBalance);
        }

        @Test
        @DisplayName("Rejection audit still returns insufficientCredits when the ledger write itself throws")
        void shouldReturnInsufficientEvenIfRejectionAuditFails() {
            BigDecimal lowBalance = new BigDecimal("0.1000");
            BigDecimal cost = new BigDecimal("5.0000");
            Subscription sub = createSubscription(lowBalance);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);
            // Simulate audit infra blip - the primary signal (insufficientCredits) must survive.
            org.mockito.Mockito.doThrow(new RuntimeException("ledger DB down"))
                    .when(ledgerRepository).save(any());

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Insufficient credits");
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("should allow deduction when balance exactly matches cost")
        void shouldAllowDeductionWhenBalanceExactlyMatchesCost() {
            BigDecimal exactBalance = new BigDecimal("5.0000");
            BigDecimal cost = new BigDecimal("5.0000");
            Subscription sub = createSubscription(exactBalance);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(cost);
            assertThat(result.remainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("should handle very large token counts without overflow")
        void shouldHandleVeryLargeTokenCounts() {
            int largePromptTokens = 1_500_000;
            int largeCompletionTokens = 500_000;
            BigDecimal largeCost = new BigDecimal("875.0000");
            BigDecimal largeBalance = new BigDecimal("1000.0000");
            mockActiveSubscription(largeBalance);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(largePromptTokens, largeCompletionTokens)))
                    .thenReturn(largeCost);

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, largePromptTokens, largeCompletionTokens);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(largeCost);
            assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("125.0000"));
        }

        @Test
        @DisplayName("should handle very small amount (1 token)")
        void shouldHandleVerySmallAmount() {
            BigDecimal tinyCost = new BigDecimal("0.0001");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(1, 0)))
                    .thenReturn(tinyCost);

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, 1, 0);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(tinyCost);
            assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("99.9999"));
        }

        @Test
        @DisplayName("should handle null provider and model gracefully via pricing service")
        void shouldHandleNullProviderAndModel() {
            BigDecimal cost = new BigDecimal("0.5000");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(null, null, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, null, null, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isTrue();

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getProvider()).isNull();
            assertThat(entry.getModel()).isNull();
        }

        @Test
        @DisplayName("should save subscription with updated balance after deduction")
        void shouldSaveSubscriptionWithUpdatedBalance() {
            BigDecimal cost = new BigDecimal("3.0000");
            Subscription sub = createSubscription(INITIAL_BALANCE);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            creditService.consumeForAgent(USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(subscriptionRepository).save(subscriptionCaptor.capture());
            assertThat(subscriptionCaptor.getValue().getRemainingCredits())
                    .isEqualByComparingTo(new BigDecimal("97.0000"));
        }

        @Test
        @DisplayName("should include description with provider, model, and token counts")
        void shouldIncludeDescriptionWithDetails() {
            BigDecimal cost = new BigDecimal("1.0000");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            creditService.consumeForAgent(USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            String desc = ledgerCaptor.getValue().getDescription();
            assertThat(desc).isEqualTo("openai/gpt-4o: 1000 input + 500 output tokens");
        }
    }

    // ===== consumeForChat =====

    @Nested
    @DisplayName("consumeForChat")
    class ConsumeForChat {

        private static final String CONVERSATION_ID = "conv-abc-456";

        @Test
        @DisplayName("should deduct correct amount based on token-based pricing")
        void shouldDeductCorrectAmountBasedOnTokens() {
            BigDecimal cost = new BigDecimal("1.2500");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            CreditConsumeResult result = creditService.consumeForChat(
                    USER_ID, CONVERSATION_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(cost);
            assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("98.7500"));
        }

        @Test
        @DisplayName("should create ledger entry with sourceType CHAT_CONVERSATION")
        void shouldCreateLedgerEntryWithCorrectSourceType() {
            BigDecimal cost = new BigDecimal("0.5000");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            creditService.consumeForChat(USER_ID, CONVERSATION_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getSourceType()).isEqualTo("CHAT_CONVERSATION");
            assertThat(entry.getSourceId()).isEqualTo(CONVERSATION_ID);
            assertThat(entry.getProvider()).isEqualTo(PROVIDER);
            assertThat(entry.getModel()).isEqualTo(MODEL);
            assertThat(entry.getPromptTokens()).isEqualTo(PROMPT_TOKENS);
            assertThat(entry.getCompletionTokens()).isEqualTo(COMPLETION_TOKENS);
            assertThat(entry.getDescription()).contains("Chat");
        }

        @Test
        @DisplayName("should return noSubscription result when user has no subscription")
        void shouldReturnNoSubscription() {
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(new BigDecimal("0.5000"));
            mockNoSubscription();

            CreditConsumeResult result = creditService.consumeForChat(
                    USER_ID, CONVERSATION_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("No active subscription");
        }

        @Test
        @DisplayName("writes zero-amount ledger row when calculated cost is 0 but tokens>0 (pricing-anomaly audit trail)")
        void writesLedgerRowForBridgeCostZero() {
            // Edge case: pricing row missing or misconfigured → calculateCost returns 0,
            // but the chat still happened. Finance needs the audit row. Historically
            // this was the common bridge path (V117 rates=0); post-V130 bridges bill
            // at cloud rates so a zero cost is now an anomaly signal.
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost("claude-code", "claude-sonnet-4-6", LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(BigDecimal.ZERO);

            CreditConsumeResult result = creditService.consumeForChat(
                    USER_ID, CONVERSATION_ID, "claude-code", "claude-sonnet-4-6",
                    PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            // Balance unchanged - no debit, just the audit row.
            assertThat(result.remainingCredits()).isEqualByComparingTo(INITIAL_BALANCE);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(entry.getBalanceAfter()).isEqualByComparingTo(INITIAL_BALANCE);
            assertThat(entry.getSourceType()).isEqualTo("CHAT_CONVERSATION");
            assertThat(entry.getProvider()).isEqualTo("claude-code");
            assertThat(entry.getModel()).isEqualTo("claude-sonnet-4-6");
            assertThat(entry.getPromptTokens()).isEqualTo(PROMPT_TOKENS);
            assertThat(entry.getCompletionTokens()).isEqualTo(COMPLETION_TOKENS);

            // Subscription balance must NOT be touched on a zero-cost row.
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips ledger write when cost=0 AND tokens=0 (genuine no-op)")
        void skipsLedgerWriteForZeroCostAndZeroTokens() {
            // The zero-token path is the pre-V120 behavior - no provider context,
            // nothing to record. Keep the early-return for genuine no-ops so the
            // ledger is not polluted by workflow/marketplace paths that pass
            // null tokens through deductCredits.
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost("claude-code", "claude-sonnet-4-6", LlmTokenBreakdown.of(0, 0)))
                    .thenReturn(BigDecimal.ZERO);

            CreditConsumeResult result = creditService.consumeForChat(
                    USER_ID, CONVERSATION_ID, "claude-code", "claude-sonnet-4-6", 0, 0);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(ledgerRepository, never()).save(any());
        }

        @Test
        @DisplayName("Debits chat into negative balance when post-flight cost exceeds balance - never writes CHAT_CONVERSATION_REJECTED")
        void debitsIntoNegativeBalanceForChatPostFlight() {
            // Production incident 2026-04-30 07:01: a chat with 668 744 input tokens
            // on claude-code/claude-opus-4-6 cost 3462.14 credits but the user's
            // balance was only 1429.54. The pre-fix code wrote a CHAT_CONVERSATION_REJECTED
            // amount=0 row and left the platform absorbing the LLM cost. Post-fix:
            // chat is post-flight, the LLM has already run, so we ALWAYS debit -
            // even into negative balance. The pre-flight canAfford gate is what
            // refuses fresh chat starts when balance < projected cost.
            BigDecimal balance = new BigDecimal("1429.5390");
            BigDecimal cost = new BigDecimal("3462.1450");
            BigDecimal expectedNewBalance = balance.subtract(cost);
            mockActiveSubscription(balance);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            CreditConsumeResult result = creditService.consumeForChat(
                    USER_ID, CONVERSATION_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(cost);
            assertThat(result.remainingCredits()).isEqualByComparingTo(expectedNewBalance);
            assertThat(expectedNewBalance.signum()).isNegative();

            // Subscription was actually mutated to the negative balance - the platform
            // is no longer absorbing the cost - AND flipped to delinquent so the user
            // cannot start new workflows / reservations until they top up (V148 invariant
            // delinquent=TRUE ⇒ remainingCredits ≤ 0).
            verify(subscriptionRepository).save(subscriptionCaptor.capture());
            assertThat(subscriptionCaptor.getValue().getRemainingCredits())
                    .isEqualByComparingTo(expectedNewBalance);
            assertThat(subscriptionCaptor.getValue().getDelinquent()).isTrue();

            // Ledger row is a normal CHAT_CONVERSATION debit, NOT CHAT_CONVERSATION_REJECTED.
            // Exactly one row written - proves the rejection branch is unreachable for chat.
            verify(ledgerRepository, times(1)).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getSourceType()).isEqualTo("CHAT_CONVERSATION");
            assertThat(entry.getAmount()).isEqualByComparingTo(cost.negate());
            assertThat(entry.getBalanceAfter()).isEqualByComparingTo(expectedNewBalance);
            assertThat(entry.getDescription()).doesNotStartWith("REJECTED:");
        }

        @Test
        @DisplayName("Idempotent on retry - existing CHAT_CONVERSATION row returns success without re-debit")
        void idempotentOnRetryDoesNotDoubleDebit() {
            // Production incident 2026-04-30 07:04: the same chat turn was retried
            // 3 minutes after the first call. With chat now always debiting on the
            // first call, the retry must short-circuit by detecting the existing
            // {@code CHAT_CONVERSATION} row (not just any row with that source_id -
            // see {@link #legacyRejectedRowDoesNotShortCircuitRealDebit}).
            CreditLedgerEntry existing = new CreditLedgerEntry();
            existing.setSourceType("CHAT_CONVERSATION");
            existing.setSourceId(CONVERSATION_ID);
            when(ledgerRepository.findFirstBySourceIdAndSourceType(CONVERSATION_ID, "CHAT_CONVERSATION"))
                    .thenReturn(Optional.of(existing));
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = creditService.consumeForChat(
                    USER_ID, CONVERSATION_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);

            // No double-debit, no second ledger row, no pricing lookup, no balance mutation.
            verify(ledgerRepository, never()).save(any());
            verify(subscriptionRepository, never()).save(any());
            verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(anyLong());
            verify(pricingService, never()).calculateCost(anyString(), anyString(), any(com.apimarketplace.auth.service.LlmTokenBreakdown.class));
        }

        @Test
        @DisplayName("Legacy CHAT_CONVERSATION_REJECTED row does NOT short-circuit a real debit")
        void legacyRejectedRowDoesNotShortCircuitRealDebit() {
            // Pre-fix prod state: rows like CHAT_CONVERSATION_REJECTED (amount=0) exist
            // for turns that overshot the balance. The platform was never reimbursed.
            // After deploy, if a caller retries one of those legacy turns, idempotency
            // must NOT skip the debit just because some row with that source_id exists.
            // The narrowed query filters on sourceType='CHAT_CONVERSATION' (not the
            // generic existsBySourceId), so a REJECTED row alone does NOT trigger the
            // short-circuit and a real debit proceeds.
            BigDecimal cost = new BigDecimal("0.5000");
            mockActiveSubscription(INITIAL_BALANCE);
            // Narrowed query returns empty: no successful CHAT_CONVERSATION row exists,
            // only (hypothetically) a CHAT_CONVERSATION_REJECTED - which we don't even
            // need to mock because the production code never queries for it.
            when(ledgerRepository.findFirstBySourceIdAndSourceType(CONVERSATION_ID, "CHAT_CONVERSATION"))
                    .thenReturn(Optional.empty());
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            CreditConsumeResult result = creditService.consumeForChat(
                    USER_ID, CONVERSATION_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(cost);

            // A real CHAT_CONVERSATION debit row was written - platform reimbursed.
            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getSourceType()).isEqualTo("CHAT_CONVERSATION");
            assertThat(entry.getAmount()).isEqualByComparingTo(cost.negate());
        }

        @Test
        @DisplayName("Already-negative balance + new chat turn debits further negative and keeps delinquent flag")
        void alreadyNegativeBalanceDebitsFurtherAndStaysDelinquent() {
            // After a prior overshoot the user is already at -500 with delinquent=true.
            // A second chat turn arrives (the user must have bypassed the pre-flight
            // gate somehow - flat /credits/check, race condition, …). The post-flight
            // debit must still go through (LLM already ran) and the delinquent flag
            // must remain set so future workflow reserves stay refused.
            BigDecimal startingBalance = new BigDecimal("-500.0000");
            BigDecimal cost = new BigDecimal("100.0000");
            BigDecimal expectedNewBalance = startingBalance.subtract(cost);
            Subscription sub = createSubscription(startingBalance);
            sub.setDelinquent(true);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            lenient().when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(sub));
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            CreditConsumeResult result = creditService.consumeForChat(
                    USER_ID, CONVERSATION_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(cost);
            assertThat(result.remainingCredits()).isEqualByComparingTo(expectedNewBalance);

            verify(subscriptionRepository).save(subscriptionCaptor.capture());
            assertThat(subscriptionCaptor.getValue().getRemainingCredits())
                    .isEqualByComparingTo(expectedNewBalance);
            assertThat(subscriptionCaptor.getValue().getDelinquent()).isTrue();

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("CHAT_CONVERSATION");
            assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo(cost.negate());
        }

        @Test
        @DisplayName("Positive-balance debit does NOT touch delinquent flag")
        void positiveBalanceDebitLeavesDelinquentAlone() {
            // Guard against accidentally setting delinquent=true on a healthy debit.
            // The flag is the gate for new workflow reserves; flipping it on a normal
            // chat turn would lock out users with positive balance from running flows.
            BigDecimal cost = new BigDecimal("0.5000");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            creditService.consumeForChat(
                    USER_ID, CONVERSATION_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(subscriptionRepository).save(subscriptionCaptor.capture());
            assertThat(subscriptionCaptor.getValue().getDelinquent()).isFalse();
            assertThat(subscriptionCaptor.getValue().getRemainingCredits())
                    .isEqualByComparingTo(INITIAL_BALANCE.subtract(cost));
        }

        @Test
        @DisplayName("Debits down to exactly zero when balance equals cost (boundary)")
        void debitsExactlyToZeroWhenBalanceEqualsCost() {
            // Boundary check: balance == cost is NOT an insufficient case (>= is the
            // historical contract). Verify the allowNegative branch did not flip
            // the equality semantics by accident.
            BigDecimal balance = new BigDecimal("12.3456");
            mockActiveSubscription(balance);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(balance);

            CreditConsumeResult result = creditService.consumeForChat(
                    USER_ID, CONVERSATION_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isTrue();
            assertThat(result.remainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("CHAT_CONVERSATION");
            assertThat(ledgerCaptor.getValue().getBalanceAfter()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ===== grantCredits =====

    @Nested
    @DisplayName("grantCredits")
    class GrantCredits {

        @Test
        @DisplayName("should add credits and create positive ledger entry")
        void shouldAddCreditsAndCreatePositiveLedgerEntry() {
            BigDecimal grantAmount = new BigDecimal("50.0000");
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = creditService.grantCredits(
                    USER_ID, grantAmount, "SUBSCRIPTION_RENEWAL", "renew-001", "Monthly renewal");

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("150.0000"));
        }

        @Test
        @DisplayName("should save subscription with increased balance")
        void shouldSaveSubscriptionWithIncreasedBalance() {
            BigDecimal grantAmount = new BigDecimal("25.0000");
            Subscription sub = createSubscription(INITIAL_BALANCE);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));

            creditService.grantCredits(USER_ID, grantAmount, "BONUS", "bonus-001", "Signup bonus");

            verify(subscriptionRepository).save(subscriptionCaptor.capture());
            assertThat(subscriptionCaptor.getValue().getRemainingCredits())
                    .isEqualByComparingTo(new BigDecimal("125.0000"));
        }

        @Test
        @DisplayName("should create ledger entry with positive amount and correct fields")
        void shouldCreateLedgerEntryWithPositiveAmount() {
            BigDecimal grantAmount = new BigDecimal("10.0000");
            mockActiveSubscription(INITIAL_BALANCE);

            creditService.grantCredits(USER_ID, grantAmount, "PURCHASE", "purch-001", "Credit pack purchase");

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getUserId()).isEqualTo(USER_ID);
            assertThat(entry.getAmount()).isEqualByComparingTo(grantAmount);
            assertThat(entry.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("110.0000"));
            assertThat(entry.getSourceType()).isEqualTo("PURCHASE");
            assertThat(entry.getSourceId()).isEqualTo("purch-001");
            assertThat(entry.getDescription()).isEqualTo("Credit pack purchase");
        }

        @Test
        @DisplayName("should return noSubscription when user has no subscription")
        void shouldReturnNoSubscriptionWhenNone() {
            mockNoSubscription();

            CreditConsumeResult result = creditService.grantCredits(
                    USER_ID, new BigDecimal("50.0000"), "BONUS", "b-001", "Test");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("No active subscription");
            verify(ledgerRepository, never()).save(any());
            verify(subscriptionRepository, never()).save(any(Subscription.class));
        }

        @Test
        @DisplayName("should work correctly when granting credits to a negative balance")
        void shouldGrantCreditsToNegativeBalance() {
            BigDecimal negativeBalance = new BigDecimal("-20.0000");
            BigDecimal grantAmount = new BigDecimal("50.0000");
            mockActiveSubscription(negativeBalance);

            CreditConsumeResult result = creditService.grantCredits(
                    USER_ID, grantAmount, "TOP_UP", "top-001", "Emergency top-up");

            assertThat(result.success()).isTrue();
            assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("30.0000"));
        }
    }

    // ===== getBalance =====

    @Nested
    @DisplayName("getBalance")
    class GetBalance {

        @Test
        @DisplayName("should return remaining credits from active subscription")
        void shouldReturnRemainingCredits() {
            Subscription sub = createSubscription(new BigDecimal("75.5000"));
            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(sub));

            BigDecimal balance = creditService.getBalance(USER_ID);

            assertThat(balance).isEqualByComparingTo(new BigDecimal("75.5000"));
        }

        @Test
        @DisplayName("should return zero when user has no active subscription")
        void shouldReturnZeroWhenNoSubscription() {
            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.empty());

            BigDecimal balance = creditService.getBalance(USER_ID);

            assertThat(balance).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("should return negative balance when subscription has overdraft")
        void shouldReturnNegativeBalance() {
            Subscription sub = createSubscription(new BigDecimal("-5.2300"));
            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(sub));

            BigDecimal balance = creditService.getBalance(USER_ID);

            assertThat(balance).isEqualByComparingTo(new BigDecimal("-5.2300"));
        }
    }

    // ===== getUsageHistory =====

    @Nested
    @DisplayName("getUsageHistory")
    class GetUsageHistory {

        @Test
        @DisplayName("OWNER view (payer == self): personal/own workspace returns wallet-scoped rows")
        void shouldReturnPaginatedHistoryOwnerView() {
            // Personal workspace OR user owns this team - resolvePayer returns
            // self. Query returns ALL rows on the wallet (workspace-wide view).
            Pageable pageable = PageRequest.of(0, 10);
            CreditLedgerEntry entry = new CreditLedgerEntry();
            entry.setUserId(USER_ID);
            entry.setAmount(new BigDecimal("-1.0000"));
            Page<CreditLedgerEntry> page = new PageImpl<>(List.of(entry), pageable, 1);

            when(ledgerRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, null, pageable))
                    .thenReturn(page);

            Page<CreditLedgerEntry> result = creditService.getUsageHistory(USER_ID, pageable);

            assertThat(result.getTotalElements()).isEqualTo(1);
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("should return empty page when no history exists (owner view)")
        void shouldReturnEmptyPageWhenNoHistory() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<CreditLedgerEntry> emptyPage = new PageImpl<>(List.of(), pageable, 0);
            when(ledgerRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, null, pageable))
                    .thenReturn(emptyPage);

            Page<CreditLedgerEntry> result = creditService.getUsageHistory(USER_ID, pageable);

            assertThat(result.getTotalElements()).isEqualTo(0);
            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("2026-05-22 user-reported fix: MEMBER in TEAM workspace sees ONLY their own executions IN THIS WORKSPACE (payer-scoped + executor-scoped intersection)")
        void teamMemberSeesOnlyOwnExecutionsInWorkspace() {
            // Repro: user 5 viewing a TEAM workspace owned by user 1.
            // resolvePayer(5) reads X-Active-Organization-ID, finds the
            // workspace's owner = user 1 → returns 1.
            // Member-view: payer != self → query by
            // (user_id=1, executor_user_id=5) - only rows the member acted
            // on this workspace's wallet. NOT the owner's solo activity,
            // NOT other colleagues' executions.
            // Switching workspaces would change resolvePayer's output and
            // therefore the query - user-reported "history same across
            // workspaces" bug is fixed.
            Long ownerUserId = 1L;
            PlanResolutionService resolver = mock(PlanResolutionService.class);
            creditService.setPlanResolutionService(resolver);
            when(resolver.resolvePayerUserId(USER_ID)).thenReturn(ownerUserId);

            Pageable pageable = PageRequest.of(0, 10);
            CreditLedgerEntry memberExec = new CreditLedgerEntry();
            memberExec.setUserId(ownerUserId);  // bill payer = workspace owner
            memberExec.setExecutorUserId(USER_ID);
            Page<CreditLedgerEntry> page = new PageImpl<>(List.of(memberExec), pageable, 1);
            when(ledgerRepository.findByPayerAndExecutorOrderByCreatedAtDesc(
                    ownerUserId, USER_ID, null, pageable)).thenReturn(page);

            Page<CreditLedgerEntry> result = creditService.getUsageHistory(USER_ID, pageable);

            assertThat(result).isSameAs(page);
            verify(ledgerRepository).findByPayerAndExecutorOrderByCreatedAtDesc(
                    ownerUserId, USER_ID, null, pageable);
            // Critical: must NOT fall through to owner-view (wallet-wide
            // findByUserId) which would leak colleagues' executions.
            verify(ledgerRepository, never()).findByUserIdOrderByCreatedAtDesc(anyLong(), any(), any());
            // Critical: must NOT use the cross-workspace participation finder
            // either - that ignored workspace context entirely.
            verify(ledgerRepository, never()).findByUserParticipationOrderByCreatedAtDesc(anyLong(), any());
        }

        @Test
        @DisplayName("2026-05-22: OWNER viewing their TEAM workspace sees workspace-wide history (all members' executions billed to them)")
        void ownerViewingOwnTeamSeesWorkspaceWide() {
            // Owner of livecontextai (user 1) viewing the livecontextai
            // workspace: resolvePayer(1) returns 1 (themselves). Query
            // returns ALL rows on their wallet - including rows where
            // colleagues executed. The user-reported "transparency for
            // owner" contract.
            Long ownerId = 1L;
            PlanResolutionService resolver = mock(PlanResolutionService.class);
            creditService.setPlanResolutionService(resolver);
            when(resolver.resolvePayerUserId(ownerId)).thenReturn(ownerId);

            Pageable pageable = PageRequest.of(0, 10);
            Page<CreditLedgerEntry> page = new PageImpl<>(List.of(), pageable, 0);
            when(ledgerRepository.findByUserIdOrderByCreatedAtDesc(ownerId, null, pageable))
                    .thenReturn(page);

            creditService.getUsageHistory(ownerId, pageable);

            verify(ledgerRepository).findByUserIdOrderByCreatedAtDesc(ownerId, null, pageable);
            verify(ledgerRepository, never()).findByPayerAndExecutorOrderByCreatedAtDesc(
                    anyLong(), anyLong(), any(), any());
        }
    }

    // ===== getUsageHistoryByType =====

    @Nested
    @DisplayName("getUsageHistoryByType")
    class GetUsageHistoryByType {

        @Test
        @DisplayName("OWNER view: filtered history uses wallet-scoped finder")
        void shouldDelegateWithCorrectFilter() {
            Pageable pageable = PageRequest.of(0, 5);
            Page<CreditLedgerEntry> page = new PageImpl<>(List.of(), pageable, 0);
            when(ledgerRepository.findByUserIdAndSourceTypeOrderByCreatedAtDesc(
                    USER_ID, "AGENT_EXECUTION", null, pageable)).thenReturn(page);

            Page<CreditLedgerEntry> result = creditService.getUsageHistoryByType(
                    USER_ID, "AGENT_EXECUTION", pageable);

            assertThat(result).isEqualTo(page);
            verify(ledgerRepository).findByUserIdAndSourceTypeOrderByCreatedAtDesc(
                    USER_ID, "AGENT_EXECUTION", null, pageable);
        }

        @Test
        @DisplayName("2026-05-22 user-reported fix: MEMBER filtered history uses payer+executor finder (workspace-scoped, member-only)")
        void memberFilteredHistoryUsesPayerAndExecutorFinder() {
            Long ownerUserId = 1L;
            PlanResolutionService resolver = mock(PlanResolutionService.class);
            creditService.setPlanResolutionService(resolver);
            when(resolver.resolvePayerUserId(USER_ID)).thenReturn(ownerUserId);

            Pageable pageable = PageRequest.of(0, 5);
            Page<CreditLedgerEntry> page = new PageImpl<>(List.of(), pageable, 0);
            when(ledgerRepository.findByPayerAndExecutorAndSourceTypeOrderByCreatedAtDesc(
                    ownerUserId, USER_ID, "AGENT_EXECUTION", null, pageable)).thenReturn(page);

            Page<CreditLedgerEntry> result = creditService.getUsageHistoryByType(
                    USER_ID, "AGENT_EXECUTION", pageable);

            assertThat(result).isSameAs(page);
            verify(ledgerRepository).findByPayerAndExecutorAndSourceTypeOrderByCreatedAtDesc(
                    ownerUserId, USER_ID, "AGENT_EXECUTION", null, pageable);
            verify(ledgerRepository, never()).findByUserIdAndSourceTypeOrderByCreatedAtDesc(
                    anyLong(), anyString(), any(), any());
            verify(ledgerRepository, never()).findByUserParticipationAndSourceTypeOrderByCreatedAtDesc(
                    anyLong(), anyString(), any());
        }
    }

    // ===== getUsageSummary =====

    @Nested
    @DisplayName("getUsageSummary")
    class GetUsageSummary {

        @Test
        @DisplayName("should return correct breakdown with balance and consumed totals")
        void shouldReturnCorrectBreakdown() {
            Subscription sub = createSubscription(new BigDecimal("80.0000"));
            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(sub));

            List<Object[]> summaryData = List.of(
                    new Object[]{"AGENT_EXECUTION", 15L, new BigDecimal("12.5000")},
                    new Object[]{"WORKFLOW_RUN", 5L, new BigDecimal("5.0000")},
                    new Object[]{"CHAT_CONVERSATION", 3L, new BigDecimal("2.5000")}
            );
            // OWNER view (no resolver, default identity): wallet-scoped summary.
            when(ledgerRepository.getConsumptionSummary(eq(USER_ID), any(LocalDateTime.class), any()))
                    .thenReturn(summaryData);

            Map<String, Object> result = creditService.getUsageSummary(USER_ID);

            assertThat(result.get("balance")).isEqualTo(new BigDecimal("80.0000"));
            assertThat((BigDecimal) result.get("totalConsumedLast30Days"))
                    .isEqualByComparingTo(new BigDecimal("20.0000"));

            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> byType =
                    (Map<String, Map<String, Object>>) result.get("breakdownByType");
            assertThat(byType).hasSize(3);
            assertThat(byType.get("AGENT_EXECUTION").get("count")).isEqualTo(15L);
            assertThat((BigDecimal) byType.get("AGENT_EXECUTION").get("credits"))
                    .isEqualByComparingTo(new BigDecimal("12.5000"));
            assertThat(byType.get("WORKFLOW_RUN").get("count")).isEqualTo(5L);
            assertThat(byType.get("CHAT_CONVERSATION").get("count")).isEqualTo(3L);
        }

        @Test
        @DisplayName("2026-05-22 user-reported fix: MEMBER summary uses payer+executor finder - workspace-scoped, member-only, matches history+analytics on same page")
        void teamOrgMemberSummaryUsesPayerAndExecutorScope() {
            // Member of a TEAM workspace owned by user 7. resolvePayer reads
            // X-Active-Organization-ID → returns 7. Member-view: query
            // intersects payer (workspace wallet) AND executor (current user)
            // - sees ONLY their own executions in THIS workspace.
            Long ownerUserId = 7L;
            PlanResolutionService resolver = mock(PlanResolutionService.class);
            creditService.setPlanResolutionService(resolver);
            when(resolver.resolvePayerUserId(USER_ID)).thenReturn(ownerUserId);
            when(resolver.resolvePayerUserId(ownerUserId)).thenReturn(ownerUserId);

            // Balance: workspace wallet (owner's sub).
            Subscription ownerSub = createSubscription(new BigDecimal("5000.0000"));
            when(subscriptionRepository.findActiveByUserId(ownerUserId))
                    .thenReturn(Optional.of(ownerSub));

            // Consumption breakdown: payer+executor intersection.
            List<Object[]> memberInWorkspaceData = List.<Object[]>of(
                    new Object[]{"CHAT_CONVERSATION", 2L, new BigDecimal("10.0000")}
            );
            when(ledgerRepository.getConsumptionSummaryByPayerAndExecutor(
                    eq(ownerUserId), eq(USER_ID), any(LocalDateTime.class), any()))
                    .thenReturn(memberInWorkspaceData);

            Map<String, Object> result = creditService.getUsageSummary(USER_ID);

            assertThat(result.get("balance")).isEqualTo(new BigDecimal("5000.0000"));
            assertThat((BigDecimal) result.get("totalConsumedLast30Days"))
                    .isEqualByComparingTo(new BigDecimal("10.0000"));
            // Critical guards: workspace-scoped query, NOT wallet-wide
            // (would leak colleagues), NOT cross-workspace participation
            // (would ignore workspace switching).
            verify(ledgerRepository).getConsumptionSummaryByPayerAndExecutor(
                    eq(ownerUserId), eq(USER_ID), any(LocalDateTime.class), any());
            verify(ledgerRepository, never()).getConsumptionSummary(anyLong(), any(LocalDateTime.class), any());
            verify(ledgerRepository, never()).getConsumptionSummaryForParticipant(
                    anyLong(), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("should return zero totals when no consumption history")
        void shouldReturnZeroWhenNoHistory() {
            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.empty());
            // Owner view (no resolver): wallet-scoped finder.
            when(ledgerRepository.getConsumptionSummary(eq(USER_ID), any(LocalDateTime.class), any()))
                    .thenReturn(List.of());

            Map<String, Object> result = creditService.getUsageSummary(USER_ID);

            assertThat(result.get("balance")).isEqualTo(BigDecimal.ZERO);
            assertThat((BigDecimal) result.get("totalConsumedLast30Days"))
                    .isEqualByComparingTo(BigDecimal.ZERO);
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> byType =
                    (Map<String, Map<String, Object>>) result.get("breakdownByType");
            assertThat(byType).isEmpty();
        }
    }

    @Nested
    @DisplayName("getUsageAnalytics")
    class GetUsageAnalytics {

        @Test
        @DisplayName("2026-05-22 user-reported fix: MEMBER analytics uses payer+executor intersection (workspace-scoped chart, member-only data)")
        void memberAnalyticsUsesPayerAndExecutorFinders() {
            // Member in a TEAM workspace owned by user 7. resolvePayer reads
            // X-Active-Organization-ID → returns 7. Member-view analytics:
            // payer+executor intersection. Switching workspaces changes the
            // payerUserId and therefore the chart data.
            Long ownerUserId = 7L;
            PlanResolutionService resolver = mock(PlanResolutionService.class);
            creditService.setPlanResolutionService(resolver);
            when(resolver.resolvePayerUserId(USER_ID)).thenReturn(ownerUserId);

            when(ledgerRepository.getDailyUsageByTypeForPayerAndExecutor(
                    eq(ownerUserId), eq(USER_ID), any(LocalDateTime.class), any()))
                    .thenReturn(List.of());
            when(ledgerRepository.getDistinctProvidersForPayerAndExecutor(
                    eq(ownerUserId), eq(USER_ID), any(LocalDateTime.class), any()))
                    .thenReturn(List.of("openai"));
            when(ledgerRepository.getDistinctModelsForPayerAndExecutor(
                    eq(ownerUserId), eq(USER_ID), any(LocalDateTime.class), any()))
                    .thenReturn(List.of("gpt-4o"));
            when(ledgerRepository.getDistinctSourceTypesForPayerAndExecutor(
                    eq(ownerUserId), eq(USER_ID), any(LocalDateTime.class), any()))
                    .thenReturn(List.of("CHAT_CONVERSATION"));

            Map<String, Object> result = creditService.getUsageAnalytics(
                    USER_ID, 30, null, null, null);

            assertThat(result.get("providers")).isEqualTo(List.of("openai"));
            // Critical guards: payer+executor intersection finders are used,
            // NOT wallet-wide (would leak colleagues), NOT participation
            // (would ignore workspace switching).
            verify(ledgerRepository).getDailyUsageByTypeForPayerAndExecutor(
                    eq(ownerUserId), eq(USER_ID), any(LocalDateTime.class), any());
            verify(ledgerRepository, never()).getDailyUsageByType(anyLong(), any(LocalDateTime.class), any());
        }
    }

    // ===== V366 (ADR-0010) - per-workspace usage reporting =====

    @Nested
    @DisplayName("V366 per-workspace usage reporting")
    class WorkspaceUsageReporting {

        @Test
        @DisplayName("consume stamps the ACTIVE workspace org onto the ledger row (write path)")
        void consumeStampsActiveWorkspaceOrg() {
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL,
                    LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(new BigDecimal("0.5000"));

            // The org context is bound exactly as CreditConsumptionClient binds it on
            // the async consume path (runWithOrgScope) before the debit runs.
            com.apimarketplace.common.web.TenantResolver.runWithOrgScope("org-team-7", () ->
                    creditService.consumeForAgent(USER_ID, SOURCE_ID, PROVIDER, MODEL,
                            PROMPT_TOKENS, COMPLETION_TOKENS));

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getOrganizationId()).isEqualTo("org-team-7");
        }

        @Test
        @DisplayName("consume leaves org NULL when there is no active workspace context (system/legacy path -> 'All workspaces' only)")
        void consumeLeavesOrgNullWithoutWorkspaceContext() {
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL,
                    LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(new BigDecimal("0.5000"));

            creditService.consumeForAgent(USER_ID, SOURCE_ID, PROVIDER, MODEL,
                    PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getOrganizationId()).isNull();
        }

        @Test
        @DisplayName("getUsageSummary forwards a non-null workspace filter to the org-scoped query (owner view)")
        void summaryForwardsOrgFilterOwnerView() {
            mockActiveSubscription(INITIAL_BALANCE);
            when(ledgerRepository.getConsumptionSummary(eq(USER_ID), any(LocalDateTime.class), eq("org-9")))
                    .thenReturn(List.of());

            creditService.getUsageSummary(USER_ID, "org-9");

            verify(ledgerRepository).getConsumptionSummary(eq(USER_ID), any(LocalDateTime.class), eq("org-9"));
        }

        @Test
        @DisplayName("getUsageSummary with null filter aggregates ALL workspaces (passes null org)")
        void summaryNullFilterAggregatesAllWorkspaces() {
            mockActiveSubscription(INITIAL_BALANCE);
            when(ledgerRepository.getConsumptionSummary(eq(USER_ID), any(LocalDateTime.class), isNull()))
                    .thenReturn(List.of());

            creditService.getUsageSummary(USER_ID, null);

            verify(ledgerRepository).getConsumptionSummary(eq(USER_ID), any(LocalDateTime.class), isNull());
        }

        @Test
        @DisplayName("getUsageSummary(userId) legacy overload defaults to the all-workspaces aggregate (null filter)")
        void summaryLegacyOverloadDefaultsToAllWorkspaces() {
            mockActiveSubscription(INITIAL_BALANCE);
            when(ledgerRepository.getConsumptionSummary(eq(USER_ID), any(LocalDateTime.class), isNull()))
                    .thenReturn(List.of());

            creditService.getUsageSummary(USER_ID);

            verify(ledgerRepository).getConsumptionSummary(eq(USER_ID), any(LocalDateTime.class), isNull());
        }

        @Test
        @DisplayName("getUsageHistory forwards the workspace filter to the owner finder")
        void historyForwardsOrgFilter() {
            Pageable pageable = PageRequest.of(0, 10);
            when(ledgerRepository.findByUserIdOrderByCreatedAtDesc(eq(USER_ID), eq("org-9"), eq(pageable)))
                    .thenReturn(new PageImpl<>(List.of(), pageable, 0));

            creditService.getUsageHistory(USER_ID, "org-9", pageable);

            verify(ledgerRepository).findByUserIdOrderByCreatedAtDesc(USER_ID, "org-9", pageable);
        }

        @Test
        @DisplayName("getUsageAnalytics forwards the workspace filter to the daily + distinct owner queries")
        void analyticsForwardsOrgFilter() {
            when(ledgerRepository.getDailyUsageByType(eq(USER_ID), any(LocalDateTime.class), eq("org-9")))
                    .thenReturn(List.of());
            when(ledgerRepository.getDistinctProviders(eq(USER_ID), any(LocalDateTime.class), eq("org-9")))
                    .thenReturn(List.of());
            when(ledgerRepository.getDistinctModels(eq(USER_ID), any(LocalDateTime.class), eq("org-9")))
                    .thenReturn(List.of());
            when(ledgerRepository.getDistinctSourceTypes(eq(USER_ID), any(LocalDateTime.class), eq("org-9")))
                    .thenReturn(List.of());

            creditService.getUsageAnalytics(USER_ID, 30, null, null, null, "org-9");

            verify(ledgerRepository).getDailyUsageByType(eq(USER_ID), any(LocalDateTime.class), eq("org-9"));
            verify(ledgerRepository).getDistinctProviders(eq(USER_ID), any(LocalDateTime.class), eq("org-9"));
            verify(ledgerRepository).getDistinctModels(eq(USER_ID), any(LocalDateTime.class), eq("org-9"));
            verify(ledgerRepository).getDistinctSourceTypes(eq(USER_ID), any(LocalDateTime.class), eq("org-9"));
        }
    }

    // ===== Ledger Integrity =====

    @Nested
    @DisplayName("Ledger Integrity")
    class LedgerIntegrity {

        @Test
        @DisplayName("every deduction should create a ledger entry with negative amount")
        void deductionShouldCreateNegativeAmountEntry() {
            BigDecimal cost = new BigDecimal("3.7500");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            creditService.consumeForAgent(USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getAmount()).isNegative();
            assertThat(entry.getAmount()).isEqualByComparingTo(new BigDecimal("-3.7500"));
        }

        @Test
        @DisplayName("every deduction should record correct balanceAfter")
        void deductionShouldRecordCorrectBalanceAfter() {
            BigDecimal cost = new BigDecimal("10.0000");
            mockActiveSubscription(new BigDecimal("50.0000"));
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            creditService.consumeForAgent(USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getBalanceAfter())
                    .isEqualByComparingTo(new BigDecimal("40.0000"));
        }

        @Test
        @DisplayName("grantCredits should create a ledger entry with positive amount")
        void grantCreditsShouldCreatePositiveAmountEntry() {
            BigDecimal grant = new BigDecimal("25.0000");
            mockActiveSubscription(INITIAL_BALANCE);

            creditService.grantCredits(USER_ID, grant, "BONUS", "b-1", "Test grant");

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getAmount()).isPositive();
            assertThat(entry.getAmount()).isEqualByComparingTo(grant);
            assertThat(entry.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("125.0000"));
        }

        @Test
        @DisplayName("deduction ledger entry should have all token fields set for agent execution")
        void deductionShouldHaveAllTokenFields() {
            BigDecimal cost = new BigDecimal("1.0000");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            creditService.consumeForAgent(USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getSourceType()).isEqualTo("AGENT_EXECUTION");
            assertThat(entry.getSourceId()).isEqualTo(SOURCE_ID);
            assertThat(entry.getProvider()).isEqualTo(PROVIDER);
            assertThat(entry.getModel()).isEqualTo(MODEL);
            assertThat(entry.getPromptTokens()).isEqualTo(PROMPT_TOKENS);
            assertThat(entry.getCompletionTokens()).isEqualTo(COMPLETION_TOKENS);
        }

    }

    // ===== Edge Cases =====

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("should skip deduction when pricing returns negative cost (treated as zero)")
        void shouldSkipDeductionWhenNegativeCost() {
            BigDecimal negativeCost = new BigDecimal("-1.0000");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(negativeCost);

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            // cost <= 0 triggers the early return in deductCredits
            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(ledgerRepository, never()).save(any());
        }

        @Test
        @DisplayName("should call findActiveByUserIdForUpdate for SELECT FOR UPDATE pattern")
        void shouldUseFindActiveByUserIdForLocking() {
            BigDecimal cost = new BigDecimal("1.0000");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            creditService.consumeForAgent(USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            // Verify the findActiveByUserIdForUpdate is called (used for lock/update pattern)
            verify(subscriptionRepository, atLeastOnce()).findActiveByUserIdForUpdate(USER_ID);
        }

        @Test
        @DisplayName("consumeForAgent with cost exactly equal to balance should leave zero balance")
        void shouldLeaveZeroBalanceWhenCostEqualsBalance() {
            BigDecimal exactCost = new BigDecimal("100.0000");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(exactCost);

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isTrue();
            assertThat(result.remainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ===== CreditConsumeResult DTO =====

    @Nested
    @DisplayName("CreditConsumeResult DTO")
    class CreditConsumeResultDTO {

        @Test
        @DisplayName("success() should create result with success=true and no error")
        void successShouldCreateCorrectResult() {
            CreditConsumeResult result = CreditConsumeResult.success(
                    new BigDecimal("5.0000"), new BigDecimal("95.0000"));

            assertThat(result.success()).isTrue();
            assertThat(result.error()).isNull();
            assertThat(result.creditsUsed()).isEqualByComparingTo(new BigDecimal("5.0000"));
            assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("95.0000"));
        }

        @Test
        @DisplayName("insufficientCredits() should create result with success=false and error message")
        void insufficientCreditsShouldCreateCorrectResult() {
            CreditConsumeResult result = CreditConsumeResult.insufficientCredits(
                    new BigDecimal("3.0000"), new BigDecimal("10.0000"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Insufficient credits");
            assertThat(result.error()).contains("3.0000");
            assertThat(result.error()).contains("10.0000");
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("3.0000"));
        }

        @Test
        @DisplayName("noSubscription() should create result with success=false and zero credits")
        void noSubscriptionShouldCreateCorrectResult() {
            CreditConsumeResult result = CreditConsumeResult.noSubscription();

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("No active subscription");
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.remainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ===== Stress Test =====

    @Nested
    @DisplayName("Stress Tests")
    class StressTests {

        @Test
        @DisplayName("100 rapid sequential deductions should all create ledger entries with monotonically decreasing balanceAfter")
        void hundredSequentialDeductionsShouldBeConsistent() {
            int deductionCount = 100;
            BigDecimal costPerDeduction = new BigDecimal("1.0000");
            BigDecimal startBalance = new BigDecimal("500.0000");

            // We need a real mutable subscription to track running balance across calls
            Subscription sub = createSubscription(startBalance);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            when(pricingService.calculateCost(eq(PROVIDER), eq(MODEL), any(com.apimarketplace.auth.service.LlmTokenBreakdown.class)))
                    .thenReturn(costPerDeduction);

            List<CreditConsumeResult> results = new ArrayList<>();
            for (int i = 0; i < deductionCount; i++) {
                CreditConsumeResult result = creditService.consumeForAgent(
                        USER_ID, "exec-" + i, PROVIDER, MODEL, 100, 50);
                results.add(result);
            }

            // Verify all 100 calls succeeded
            assertThat(results).allMatch(CreditConsumeResult::success);

            // Verify all 100 ledger entries were created
            verify(ledgerRepository, times(deductionCount)).save(ledgerCaptor.capture());
            List<CreditLedgerEntry> allEntries = ledgerCaptor.getAllValues();
            assertThat(allEntries).hasSize(deductionCount);

            // Verify balanceAfter is monotonically decreasing
            BigDecimal previousBalance = startBalance;
            for (int i = 0; i < allEntries.size(); i++) {
                CreditLedgerEntry entry = allEntries.get(i);
                assertThat(entry.getBalanceAfter())
                        .as("Entry %d balanceAfter should be less than previous balance", i)
                        .isLessThan(previousBalance);
                previousBalance = entry.getBalanceAfter();
            }

            // Verify final balance matches expected
            BigDecimal expectedFinalBalance = startBalance.subtract(
                    costPerDeduction.multiply(BigDecimal.valueOf(deductionCount)));
            assertThat(sub.getRemainingCredits())
                    .isEqualByComparingTo(expectedFinalBalance);
            assertThat(expectedFinalBalance).isEqualByComparingTo(new BigDecimal("400.0000"));

            // Verify the last ledger entry's balanceAfter equals the final subscription balance
            assertThat(allEntries.get(deductionCount - 1).getBalanceAfter())
                    .isEqualByComparingTo(expectedFinalBalance);
        }

        @Test
        @DisplayName("mixed grants and deductions should maintain consistent ledger")
        void mixedGrantsAndDeductionsShouldBeConsistent() {
            BigDecimal startBalance = new BigDecimal("100.0000");
            Subscription sub = createSubscription(startBalance);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));

            // 10 deductions of 5 credits each = 50 credits consumed
            when(pricingService.calculateCost(eq(PROVIDER), eq(MODEL), any(com.apimarketplace.auth.service.LlmTokenBreakdown.class)))
                    .thenReturn(new BigDecimal("5.0000"));
            for (int i = 0; i < 10; i++) {
                creditService.consumeForAgent(USER_ID, "exec-" + i, PROVIDER, MODEL, 100, 50);
            }

            // 1 grant of 30 credits
            creditService.grantCredits(USER_ID, new BigDecimal("30.0000"),
                    "BONUS", "grant-1", "Mid-cycle bonus");

            // 5 more deductions of 5 credits = 25 credits consumed
            for (int i = 10; i < 15; i++) {
                creditService.consumeForAgent(USER_ID, "exec-" + i, PROVIDER, MODEL, 100, 50);
            }

            // Expected: 100 - 50 + 30 - 25 = 55
            assertThat(sub.getRemainingCredits())
                    .isEqualByComparingTo(new BigDecimal("55.0000"));

            // Total ledger entries: 10 + 1 + 5 = 16
            verify(ledgerRepository, times(16)).save(any(CreditLedgerEntry.class));
        }
    }

    // ===== Additional Edge Cases =====

    @Nested
    @DisplayName("Additional Edge Cases")
    class AdditionalEdgeCases {

        @Test
        @DisplayName("should handle empty string sourceId without error")
        void shouldHandleEmptyStringSourceId() {
            BigDecimal cost = new BigDecimal("1.0000");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, "", PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isTrue();

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getSourceId()).isEmpty();
        }

        @Test
        @DisplayName("should truncate description longer than 500 characters")
        void shouldTruncateVeryLongDescription() {
            BigDecimal grantAmount = new BigDecimal("10.0000");
            mockActiveSubscription(INITIAL_BALANCE);

            String longDescription = "A".repeat(600);
            creditService.grantCredits(USER_ID, grantAmount, "BONUS", "b-1", longDescription);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getDescription()).hasSize(500);
        }

        @Test
        @DisplayName("should not truncate description at exactly 500 characters")
        void shouldNotTruncateDescriptionAtExactly500Chars() {
            BigDecimal grantAmount = new BigDecimal("10.0000");
            mockActiveSubscription(INITIAL_BALANCE);

            String exactDescription = "B".repeat(500);
            creditService.grantCredits(USER_ID, grantAmount, "BONUS", "b-2", exactDescription);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getDescription()).hasSize(500);
            assertThat(entry.getDescription()).isEqualTo(exactDescription);
        }

        @Test
        @DisplayName("should handle null description gracefully")
        void shouldHandleNullDescription() {
            BigDecimal grantAmount = new BigDecimal("10.0000");
            mockActiveSubscription(INITIAL_BALANCE);

            creditService.grantCredits(USER_ID, grantAmount, "BONUS", "b-3", null);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getDescription()).isNull();
        }

        @Test
        @DisplayName("should handle MAX_VALUE tokens via BigDecimal without overflow")
        void shouldHandleMaxValueTokens() {
            int maxTokens = Integer.MAX_VALUE;
            BigDecimal hugeCost = new BigDecimal("999999.9999");
            BigDecimal hugeBalance = new BigDecimal("99999999.9999");
            mockActiveSubscription(hugeBalance);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(maxTokens, maxTokens)))
                    .thenReturn(hugeCost);

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, maxTokens, maxTokens);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(hugeCost);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getPromptTokens()).isEqualTo(maxTokens);
            assertThat(entry.getCompletionTokens()).isEqualTo(maxTokens);
        }

        @Test
        @DisplayName("multiple rapid calls should succeed until balance exhausted then block")
        void multipleRapidCallsShouldBlockWhenExhausted() {
            BigDecimal smallBalance = new BigDecimal("3.0000");
            BigDecimal costPerCall = new BigDecimal("2.0000");

            Subscription sub = createSubscription(smallBalance);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            when(pricingService.calculateCost(eq(PROVIDER), eq(MODEL), any(com.apimarketplace.auth.service.LlmTokenBreakdown.class)))
                    .thenReturn(costPerCall);

            List<CreditConsumeResult> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                CreditConsumeResult result = creditService.consumeForAgent(
                        USER_ID, "rapid-" + i, PROVIDER, MODEL, 100, 50);
                results.add(result);
            }

            // First call succeeds (3 - 2 = 1), second and onwards are blocked (1 < 2)
            assertThat(results.get(0).success()).isTrue();
            assertThat(results.get(0).remainingCredits()).isEqualByComparingTo(new BigDecimal("1.0000"));

            // Remaining calls should fail (balance=1, cost=2)
            for (int i = 1; i < 5; i++) {
                assertThat(results.get(i).success()).isFalse();
                assertThat(results.get(i).error()).contains("Insufficient credits");
            }

            // Delta 1 - 1 success debit + 4 rejection audit rows = 5 total ledger writes.
            // Rejected rows carry amount=0 so they don't alter the balance sum invariant.
            verify(ledgerRepository, times(5)).save(any());
        }

        @Test
        @DisplayName("concurrent grant and deduct on same user should both use locked method")
        void grantAndDeductShouldBothUseLockingMethod() {
            BigDecimal startBalance = new BigDecimal("50.0000");
            Subscription sub = createSubscription(startBalance);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(new BigDecimal("10.0000"));

            // Deduct first
            creditService.consumeForAgent(USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);
            // Then grant
            creditService.grantCredits(USER_ID, new BigDecimal("20.0000"), "BONUS", "b-1", "Grant");

            // Both should have used the locked method
            verify(subscriptionRepository, times(2)).findActiveByUserIdForUpdate(USER_ID);

            // Final balance: 50 - 10 + 20 = 60
            assertThat(sub.getRemainingCredits())
                    .isEqualByComparingTo(new BigDecimal("60.0000"));
        }

        @Test
        @DisplayName("deductCredits should use findActiveByUserIdForUpdate not findActiveByUserId")
        void deductCreditsShouldUsePessimisticLocking() {
            BigDecimal cost = new BigDecimal("5.0000");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(cost);

            creditService.consumeForAgent(USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            // Must use the locked version for writes
            verify(subscriptionRepository).findActiveByUserIdForUpdate(USER_ID);
        }

        @Test
        @DisplayName("getBalance should use non-locked findActiveByUserId for read-only access")
        void getBalanceShouldUseNonLockedMethod() {
            Subscription sub = createSubscription(INITIAL_BALANCE);
            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(sub));

            creditService.getBalance(USER_ID);

            verify(subscriptionRepository).findActiveByUserId(USER_ID);
            verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(USER_ID);
        }
    }

    // ===== consumeForWorkflowNode =====

    @Nested
    @DisplayName("consumeForWorkflowNode")
    class ConsumeForWorkflowNode {

        private static final String NODE_SOURCE_ID = "run-abc:node-xyz";

        @Test
        @DisplayName("should deduct exactly 1 credit and create ledger entry with WORKFLOW_NODE source type")
        void shouldDeductExactlyOneCreditWithWorkflowNodeSourceType() {
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = creditService.consumeForWorkflowNode(USER_ID, NODE_SOURCE_ID);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("99.0000"));

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getUserId()).isEqualTo(USER_ID);
            assertThat(entry.getSourceType()).isEqualTo("WORKFLOW_NODE");
            assertThat(entry.getSourceId()).isEqualTo(NODE_SOURCE_ID);
            assertThat(entry.getAmount()).isEqualByComparingTo(new BigDecimal("-1"));
            assertThat(entry.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("99.0000"));
        }

        @Test
        @DisplayName("should return noSubscription when user has no active subscription")
        void shouldReturnNoSubscriptionWhenNone() {
            mockNoSubscription();

            CreditConsumeResult result = creditService.consumeForWorkflowNode(USER_ID, NODE_SOURCE_ID);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("No active subscription");
            verify(ledgerRepository, never()).save(any());
        }

        @Test
        @DisplayName("Blocks when balance is less than 1 credit (no overdraft) and emits a REJECTED audit row")
        void shouldBlockWhenBalanceLessThanOneCred() {
            BigDecimal lowBalance = new BigDecimal("0.5000");
            Subscription sub = createSubscription(lowBalance);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));

            CreditConsumeResult result = creditService.consumeForWorkflowNode(USER_ID, NODE_SOURCE_ID);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Insufficient credits");
            assertThat(result.remainingCredits()).isEqualByComparingTo(lowBalance);
            verify(subscriptionRepository, never()).save(any());
            // Delta 1 - rejected workflow node consumption is audited (amount=0, WORKFLOW_NODE_REJECTED)
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("WORKFLOW_NODE_REJECTED");
        }

        @Test
        @DisplayName("should use sourceId as description suffix")
        void shouldUseSourceIdAsDescriptionSuffix() {
            mockActiveSubscription(INITIAL_BALANCE);

            creditService.consumeForWorkflowNode(USER_ID, NODE_SOURCE_ID);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getDescription())
                    .isEqualTo("Workflow node: " + NODE_SOURCE_ID);
        }

        @Test
        @DisplayName("should not set provider, model, or token fields in ledger entry")
        void shouldNotSetProviderModelOrTokenFields() {
            mockActiveSubscription(INITIAL_BALANCE);

            creditService.consumeForWorkflowNode(USER_ID, NODE_SOURCE_ID);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getProvider()).isNull();
            assertThat(entry.getModel()).isNull();
            assertThat(entry.getPromptTokens()).isNull();
            assertThat(entry.getCompletionTokens()).isNull();
        }
    }

    // ===== consumeForMarketplacePurchase =====

    @Nested
    @DisplayName("consumeForMarketplacePurchase")
    class ConsumeForMarketplacePurchase {

        private static final String PUB_ID = "pub-uuid-abc-123";

        @Test
        @DisplayName("should deduct exact credit amount and create ledger with MARKETPLACE_PURCHASE type")
        void shouldDeductExactCreditsAndCreateLedger() {
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = creditService.consumeForMarketplacePurchase(USER_ID, PUB_ID, 25);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo("25");
            assertThat(result.remainingCredits()).isEqualByComparingTo("75.0000");

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getUserId()).isEqualTo(USER_ID);
            assertThat(entry.getSourceType()).isEqualTo("MARKETPLACE_PURCHASE");
            assertThat(entry.getSourceId()).isEqualTo(PUB_ID);
            assertThat(entry.getAmount()).isEqualByComparingTo("-25");
            assertThat(entry.getBalanceAfter()).isEqualByComparingTo("75.0000");
            assertThat(entry.getDescription()).isEqualTo("Publication purchase: " + PUB_ID);
        }

        @Test
        @DisplayName("Returns insufficientCredits when balance is below cost and writes MARKETPLACE_PURCHASE_REJECTED audit row")
        void shouldReturnInsufficientWhenBalanceBelowCost() {
            mockActiveSubscription(new BigDecimal("10.0000"));

            CreditConsumeResult result = creditService.consumeForMarketplacePurchase(USER_ID, PUB_ID, 25);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Insufficient credits");
            assertThat(result.remainingCredits()).isEqualByComparingTo("10.0000");
            // Delta 1 - marketplace rejection is audited too (audit trail is uniform across sources)
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("MARKETPLACE_PURCHASE_REJECTED");
        }

        @Test
        @DisplayName("should return noSubscription when no active subscription")
        void shouldReturnNoSubscription() {
            mockNoSubscription();

            CreditConsumeResult result = creditService.consumeForMarketplacePurchase(USER_ID, PUB_ID, 25);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("No active subscription");
            verify(ledgerRepository, never()).save(any());
        }

        @Test
        @DisplayName("should not set provider, model, or token fields in ledger entry")
        void shouldNotSetProviderModelOrTokenFields() {
            mockActiveSubscription(INITIAL_BALANCE);

            creditService.consumeForMarketplacePurchase(USER_ID, PUB_ID, 10);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getProvider()).isNull();
            assertThat(entry.getModel()).isNull();
            assertThat(entry.getPromptTokens()).isNull();
            assertThat(entry.getCompletionTokens()).isNull();
        }

        @Test
        @DisplayName("should handle zero cost gracefully (returns success with 0 credits used)")
        void shouldHandleZeroCost() {
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = creditService.consumeForMarketplacePurchase(USER_ID, PUB_ID, 0);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(ledgerRepository, never()).save(any()); // cost <= 0 → early return
        }
    }

    // ===== consumeForWebSearch =====

    @Nested
    @DisplayName("consumeForWebSearch")
    class ConsumeForWebSearch {

        private static final String WS_SOURCE_ID = "web-search:CHAT:stream-1:tool-7:0";

        @Test
        @DisplayName("debits default configured cost and writes WEB_SEARCH ledger entry")
        void debitsDefaultConfiguredCostAndWritesLedger() {
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = creditService.consumeForWebSearch(USER_ID, WS_SOURCE_ID);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo("1");
            assertThat(result.remainingCredits()).isEqualByComparingTo("99.0000");

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getSourceType()).isEqualTo("WEB_SEARCH");
            assertThat(entry.getSourceId()).isEqualTo(WS_SOURCE_ID);
            assertThat(entry.getProvider()).isEqualTo("websearch");
            assertThat(entry.getModel()).isEqualTo("default");
            assertThat(entry.getAmount()).isEqualByComparingTo("-1");
            verifyNoInteractions(pricingService);
        }

        @Test
        @DisplayName("webSearchBillsFromConfiguredCostEvenWhenModelPricingRowIsMissing")
        void webSearchBillsFromConfiguredCostEvenWhenModelPricingRowIsMissing() {
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = creditService.consumeForWebSearch(USER_ID, WS_SOURCE_ID);

            assertThat(result.success()).isTrue();
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo("-1");
            verify(pricingService, never()).hasPricing("websearch", "default");
            verify(pricingService, never()).calculateCost("websearch", "default", LlmTokenBreakdown.of(0, 0));
        }

        @Test
        @DisplayName("uses backend-configured websearch credit price")
        void usesBackendConfiguredWebSearchCreditPrice() {
            CreditService customPriceService = new CreditService(
                    subscriptionRepository, ledgerRepository, pricingService,
                    false, true, false, new BigDecimal("2.5000"));
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = customPriceService.consumeForWebSearch(USER_ID, WS_SOURCE_ID);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo("2.5000");
            assertThat(result.remainingCredits()).isEqualByComparingTo("97.5000");
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo("-2.5000");
        }

        @Test
        @DisplayName("idempotent: short-circuits to success when sourceId already exists in ledger")
        void idempotentOnDuplicateSourceId() {
            when(ledgerRepository.existsBySourceId(WS_SOURCE_ID)).thenReturn(true);
            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(createSubscription(INITIAL_BALANCE)));

            CreditConsumeResult result = creditService.consumeForWebSearch(USER_ID, WS_SOURCE_ID);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(ledgerRepository, never()).save(any());
            verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(anyLong());
        }

        @Test
        @DisplayName("insufficient balance writes WEB_SEARCH_REJECTED audit row and returns failure")
        void insufficientBalanceWritesRejectedAudit() {
            mockActiveSubscription(new BigDecimal("0.5000"));

            CreditConsumeResult result = creditService.consumeForWebSearch(USER_ID, WS_SOURCE_ID);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Insufficient credits");
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("WEB_SEARCH_REJECTED");
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("workflow-scope sourceId from SourceIdBuilder is accepted (no scope discrimination)")
        void acceptsWorkflowScopeSourceId() {
            String wfSourceId = "web-search:RUN:run-9:step:mcp:search:0";
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = creditService.consumeForWebSearch(USER_ID, wfSourceId);

            assertThat(result.success()).isTrue();
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceId()).isEqualTo(wfSourceId);
        }

        @Test
        @DisplayName("returns noSubscription when user has no active subscription")
        void returnsNoSubscriptionWhenNone() {
            mockNoSubscription();

            CreditConsumeResult result = creditService.consumeForWebSearch(USER_ID, WS_SOURCE_ID);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("No active subscription");
            verify(ledgerRepository, never()).save(any());
        }
    }

    // ===== consumeForWebFetch =====

    @Nested
    @DisplayName("consumeForWebFetch")
    class ConsumeForWebFetch {

        private static final String WF_SOURCE_ID = "web-fetch:CHAT:stream-1:tool-7:0";

        @Test
        @DisplayName("debits default configured cost and writes WEB_FETCH ledger entry")
        void debitsDefaultConfiguredCostAndWritesLedger() {
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = creditService.consumeForWebFetch(USER_ID, WF_SOURCE_ID);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo("1");
            assertThat(result.remainingCredits()).isEqualByComparingTo("99.0000");

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getSourceType()).isEqualTo("WEB_FETCH");
            assertThat(entry.getSourceId()).isEqualTo(WF_SOURCE_ID);
            assertThat(entry.getProvider()).isEqualTo("websearch");
            assertThat(entry.getModel()).isEqualTo("default");
            assertThat(entry.getAmount()).isEqualByComparingTo("-1");
            verifyNoInteractions(pricingService);
        }

        @Test
        @DisplayName("webFetchBillsFromConfiguredCostEvenWhenModelPricingRowIsMissing")
        void webFetchBillsFromConfiguredCostEvenWhenModelPricingRowIsMissing() {
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = creditService.consumeForWebFetch(USER_ID, WF_SOURCE_ID);

            assertThat(result.success()).isTrue();
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo("-1");
            verify(pricingService, never()).hasPricing("websearch", "default");
            verify(pricingService, never()).calculateCost("websearch", "default", LlmTokenBreakdown.of(0, 0));
        }

        @Test
        @DisplayName("uses backend-configured web fetch credit price")
        void usesBackendConfiguredWebFetchCreditPrice() {
            CreditService customPriceService = new CreditService(
                    subscriptionRepository, ledgerRepository, pricingService,
                    false, true, false, BigDecimal.ONE, new BigDecimal("2.5000"));
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = customPriceService.consumeForWebFetch(USER_ID, WF_SOURCE_ID);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo("2.5000");
            assertThat(result.remainingCredits()).isEqualByComparingTo("97.5000");
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo("-2.5000");
        }

        @Test
        @DisplayName("idempotent: short-circuits to success when sourceId already exists in ledger")
        void idempotentOnDuplicateSourceId() {
            when(ledgerRepository.existsBySourceId(WF_SOURCE_ID)).thenReturn(true);
            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(createSubscription(INITIAL_BALANCE)));

            CreditConsumeResult result = creditService.consumeForWebFetch(USER_ID, WF_SOURCE_ID);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(ledgerRepository, never()).save(any());
            verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(anyLong());
        }

        @Test
        @DisplayName("insufficient balance writes WEB_FETCH_REJECTED audit row and returns failure")
        void insufficientBalanceWritesRejectedAudit() {
            mockActiveSubscription(new BigDecimal("0.5000"));

            CreditConsumeResult result = creditService.consumeForWebFetch(USER_ID, WF_SOURCE_ID);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Insufficient credits");
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("WEB_FETCH_REJECTED");
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("workflow-scope sourceId from SourceIdBuilder is accepted (no scope discrimination)")
        void acceptsWorkflowScopeSourceId() {
            String wfSourceId = "web-fetch:RUN:run-9:step:mcp:fetch:0";
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = creditService.consumeForWebFetch(USER_ID, wfSourceId);

            assertThat(result.success()).isTrue();
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceId()).isEqualTo(wfSourceId);
        }

        @Test
        @DisplayName("returns noSubscription when user has no active subscription")
        void returnsNoSubscriptionWhenNone() {
            mockNoSubscription();

            CreditConsumeResult result = creditService.consumeForWebFetch(USER_ID, WF_SOURCE_ID);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("No active subscription");
            verify(ledgerRepository, never()).save(any());
        }
    }

    // ===== consumeForImageGeneration =====

    @Nested
    @DisplayName("consumeForImageGeneration")
    class ConsumeForImageGeneration {

        private static final String IG_SOURCE_ID = "image-generation:CHAT:stream-1:tool-7:0";
        private static final String IG_PROVIDER = "openai";
        private static final String IG_MODEL = "gpt-image-1.5-low"; // pseudo-model billing key

        @Test
        @DisplayName("n=1: cost = input_rate × 1, ledger row records IMAGE_GENERATION + provider + model + actualImageCount")
        void deductsForSingleImage() {
            BigDecimal cost = new BigDecimal("10");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.hasPricing(IG_PROVIDER, IG_MODEL)).thenReturn(true);
            when(pricingService.calculateUnitCost(IG_PROVIDER, IG_MODEL, 1)).thenReturn(cost);

            CreditConsumeResult result = creditService.consumeForImageGeneration(
                    USER_ID, IG_SOURCE_ID, IG_PROVIDER, IG_MODEL, 1);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo("10");
            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getSourceType()).isEqualTo("IMAGE_GENERATION");
            assertThat(entry.getProvider()).isEqualTo(IG_PROVIDER);
            assertThat(entry.getModel()).isEqualTo(IG_MODEL);
            assertThat(entry.getPromptTokens()).isEqualTo(1); // actualImageCount aliased into promptTokens slot
            assertThat(entry.getAmount()).isEqualByComparingTo("-10");
        }

        @Test
        @DisplayName("n=4: cost scales linearly with actualImageCount via calculateUnitCost")
        void costScalesWithActualImageCount() {
            BigDecimal cost = new BigDecimal("40");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.hasPricing(IG_PROVIDER, IG_MODEL)).thenReturn(true);
            when(pricingService.calculateUnitCost(IG_PROVIDER, IG_MODEL, 4)).thenReturn(cost);

            CreditConsumeResult result = creditService.consumeForImageGeneration(
                    USER_ID, IG_SOURCE_ID, IG_PROVIDER, IG_MODEL, 4);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo("40");
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getPromptTokens()).isEqualTo(4);
            assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo("-40");
        }

        @Test
        @DisplayName("partial-success n=2 (4 requested, 2 returned): bills only the 2 actually returned")
        void billsActualImageCountNotRequestedCount() {
            // The provider returned 2 of 4 requested images (per-image content moderation
            // rejected 2). The caller MUST pass actualImageCount=2 - billing requested=4
            // would over-charge the user.
            BigDecimal cost = new BigDecimal("20");
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.hasPricing(IG_PROVIDER, IG_MODEL)).thenReturn(true);
            when(pricingService.calculateUnitCost(IG_PROVIDER, IG_MODEL, 2)).thenReturn(cost);

            CreditConsumeResult result = creditService.consumeForImageGeneration(
                    USER_ID, IG_SOURCE_ID, IG_PROVIDER, IG_MODEL, /* actualImageCount */ 2);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo("20");
            // calculateUnitCost was called with 2 (not 4) - the contract is enforced
            verify(pricingService).calculateUnitCost(IG_PROVIDER, IG_MODEL, 2);
            verify(pricingService, never()).calculateUnitCost(eq(IG_PROVIDER), eq(IG_MODEL), eq(4));
        }

        @Test
        @DisplayName("zero images returned (all rejected by content-mod): no bill, no ledger row, success=true")
        void zeroImagesReturnsSuccessWithoutBilling() {
            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(createSubscription(INITIAL_BALANCE)));

            CreditConsumeResult result = creditService.consumeForImageGeneration(
                    USER_ID, IG_SOURCE_ID, IG_PROVIDER, IG_MODEL, 0);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(ledgerRepository, never()).save(any());
            verify(pricingService, never()).hasPricing(anyString(), anyString());
            verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(anyLong());
        }

        @Test
        @DisplayName("returns noPricing when (provider, model) row missing - fail-fast before deduct")
        void returnsNoPricingWhenRowMissing() {
            when(pricingService.hasPricing(IG_PROVIDER, IG_MODEL)).thenReturn(false);

            CreditConsumeResult result = creditService.consumeForImageGeneration(
                    USER_ID, IG_SOURCE_ID, IG_PROVIDER, IG_MODEL, 1);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("No pricing configured for openai/gpt-image-1.5-low");
            verify(ledgerRepository, never()).save(any());
            verify(pricingService, never()).calculateUnitCost(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("idempotent: short-circuits to success when sourceId already exists in ledger")
        void idempotentOnDuplicateSourceId() {
            when(pricingService.hasPricing(IG_PROVIDER, IG_MODEL)).thenReturn(true);
            when(ledgerRepository.existsBySourceId(IG_SOURCE_ID)).thenReturn(true);
            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(createSubscription(INITIAL_BALANCE)));

            CreditConsumeResult result = creditService.consumeForImageGeneration(
                    USER_ID, IG_SOURCE_ID, IG_PROVIDER, IG_MODEL, 1);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(ledgerRepository, never()).save(any());
            verify(pricingService, never()).calculateUnitCost(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("insufficient balance writes IMAGE_GENERATION_REJECTED audit row")
        void insufficientBalanceWritesRejectedAudit() {
            when(pricingService.hasPricing(IG_PROVIDER, IG_MODEL)).thenReturn(true);
            when(pricingService.calculateUnitCost(IG_PROVIDER, IG_MODEL, 1))
                    .thenReturn(new BigDecimal("80"));
            mockActiveSubscription(new BigDecimal("10.0000"));

            CreditConsumeResult result = creditService.consumeForImageGeneration(
                    USER_ID, IG_SOURCE_ID, IG_PROVIDER, IG_MODEL, 1);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Insufficient credits");
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("IMAGE_GENERATION_REJECTED");
        }

        @Test
        @DisplayName("returns noSubscription when user has no active subscription")
        void returnsNoSubscriptionWhenNone() {
            when(pricingService.hasPricing(IG_PROVIDER, IG_MODEL)).thenReturn(true);
            when(pricingService.calculateUnitCost(IG_PROVIDER, IG_MODEL, 1))
                    .thenReturn(new BigDecimal("10"));
            mockNoSubscription();

            CreditConsumeResult result = creditService.consumeForImageGeneration(
                    USER_ID, IG_SOURCE_ID, IG_PROVIDER, IG_MODEL, 1);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("No active subscription");
            verify(ledgerRepository, never()).save(any());
        }
    }

    // ===== consumeForImageGenerationByok =====

    @Nested
    @DisplayName("consumeForImageGenerationByok (BYOK trace)")
    class ConsumeForImageGenerationByok {

        private static final String IG_SOURCE_ID = "image-generation:CHAT:s:t:0";
        private static final String IG_PROVIDER = "openai";
        private static final String IG_MODEL = "gpt-image-1.5-medium";

        @Test
        @DisplayName("BYOK: writes 0-amount ledger row with provider/model/imageCount, balance never deducted")
        void byokWritesZeroTraceRow() {
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = creditService.consumeForImageGenerationByok(
                    USER_ID, IG_SOURCE_ID, IG_PROVIDER, IG_MODEL, 3);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.remainingCredits()).isEqualByComparingTo(INITIAL_BALANCE);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getSourceType()).isEqualTo("IMAGE_GENERATION_BYOK");
            assertThat(entry.getSourceId()).isEqualTo(IG_SOURCE_ID);
            assertThat(entry.getProvider()).isEqualTo(IG_PROVIDER);
            assertThat(entry.getModel()).isEqualTo(IG_MODEL);
            assertThat(entry.getPromptTokens()).isEqualTo(3);
            assertThat(entry.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            // BYOK never touches subscription
            verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(anyLong());
            verify(subscriptionRepository, never()).save(any());
            // pricingService never queried (BYOK path doesn't need pricing)
            verifyNoInteractions(pricingService);
        }

        @Test
        @DisplayName("zero images returned → no trace row written (consistent with platform path)")
        void zeroImagesNoRow() {
            CreditConsumeResult result = creditService.consumeForImageGenerationByok(
                    USER_ID, IG_SOURCE_ID, IG_PROVIDER, IG_MODEL, 0);

            assertThat(result.success()).isTrue();
            verify(ledgerRepository, never()).save(any());
        }

        @Test
        @DisplayName("idempotent: duplicate sourceId returns success without writing")
        void idempotentDuplicate() {
            when(ledgerRepository.existsBySourceId(IG_SOURCE_ID)).thenReturn(true);
            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(createSubscription(INITIAL_BALANCE)));

            CreditConsumeResult result = creditService.consumeForImageGenerationByok(
                    USER_ID, IG_SOURCE_ID, IG_PROVIDER, IG_MODEL, 1);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(ledgerRepository, never()).save(any());
        }
    }

    // ===== truncateDescription edge cases =====

    @Nested
    @DisplayName("truncateDescription edge cases")
    class TruncateDescription {

        // truncateDescription is private; tested via consumeForWorkflowNode
        // (description = "Workflow node: " + sourceId, 15 fixed chars + sourceId length)

        @Test
        @DisplayName("description at exact MAX_DESCRIPTION_LENGTH (500) should not be truncated")
        void descriptionAtExactMaxShouldNotBeTruncated() {
            // "Workflow node: " = 15 chars, so sourceId must be 485 chars to hit exactly 500
            String sourceId = "x".repeat(485);
            mockActiveSubscription(INITIAL_BALANCE);

            creditService.consumeForWorkflowNode(USER_ID, sourceId);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            String desc = ledgerCaptor.getValue().getDescription();
            assertThat(desc).hasSize(500);
            assertThat(desc).endsWith(sourceId);
        }

        @Test
        @DisplayName("description 1 char over MAX should be truncated to 500")
        void descriptionOneCharOverMaxShouldBeTruncatedTo500() {
            // 15 + 486 = 501 chars -> truncated to 500
            String sourceId = "y".repeat(486);
            mockActiveSubscription(INITIAL_BALANCE);

            creditService.consumeForWorkflowNode(USER_ID, sourceId);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getDescription()).hasSize(500);
        }

        @Test
        @DisplayName("null description should pass through without NullPointerException")
        void nullDescriptionShouldPassThrough() {
            // grantCredits accepts a description parameter directly, making it easier
            // to pass null than constructing a sourceId that causes a null description
            mockActiveSubscription(INITIAL_BALANCE);

            // Does not throw
            creditService.grantCredits(USER_ID, BigDecimal.ONE, "BONUS", "b-null", null);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getDescription()).isNull();
        }

        @Test
        @DisplayName("empty string description should pass through unchanged")
        void emptyStringDescriptionShouldPassThrough() {
            mockActiveSubscription(INITIAL_BALANCE);

            creditService.grantCredits(USER_ID, BigDecimal.ONE, "BONUS", "b-empty", "");

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getDescription()).isEmpty();
        }

        @Test
        @DisplayName("unicode characters at truncation boundary should be sliced at char boundary")
        void unicodeAtTruncationBoundaryShouldBeSlicedAtCharBoundary() {
            // Each "€" is one Java char (U+20AC), so 500 "€" = exactly 500 chars
            String sourceId = "€".repeat(486); // 15 + 486 = 501 chars total -> truncated
            mockActiveSubscription(INITIAL_BALANCE);

            creditService.consumeForWorkflowNode(USER_ID, sourceId);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            String desc = ledgerCaptor.getValue().getDescription();
            assertThat(desc).hasSize(500);
            // The truncation is a simple substring, not emoji-aware; verify no exception
            assertThat(desc).startsWith("Workflow node: ");
        }
    }

    // ===== Credit Blocking Model =====

    @Nested
    @DisplayName("Credit Blocking Model")
    class CreditBlockingModel {

        @Test
        @DisplayName("consumeForAgent with zero balance is blocked and emits AGENT_EXECUTION_REJECTED audit")
        void consumeForAgentWithZeroBalanceShouldBeBlocked() {
            Subscription sub = createSubscription(BigDecimal.ZERO);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(new BigDecimal("2.0000"));

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Insufficient credits");
            // Delta 1: audit row with amount=0, not zero saves.
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("AGENT_EXECUTION_REJECTED");
        }

        @Test
        @DisplayName("consumeForAgent with negative balance should be blocked")
        void consumeForAgentWithNegativeBalanceShouldBeBlocked() {
            Subscription sub = createSubscription(new BigDecimal("-5.0000"));
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(new BigDecimal("3.0000"));

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Insufficient credits");
        }

        @Test
        @DisplayName("consecutive deductions should stop when balance exhausted")
        void consecutiveDeductionsShouldStopWhenExhausted() {
            BigDecimal costPerCall = new BigDecimal("1.0000");
            Subscription sub = createSubscription(new BigDecimal("2.0000"));
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            when(pricingService.calculateCost(eq(PROVIDER), eq(MODEL), any(com.apimarketplace.auth.service.LlmTokenBreakdown.class)))
                    .thenReturn(costPerCall);

            CreditConsumeResult r1 = creditService.consumeForAgent(USER_ID, "exec-1", PROVIDER, MODEL, 100, 50);
            CreditConsumeResult r2 = creditService.consumeForAgent(USER_ID, "exec-2", PROVIDER, MODEL, 100, 50);
            CreditConsumeResult r3 = creditService.consumeForAgent(USER_ID, "exec-3", PROVIDER, MODEL, 100, 50);

            assertThat(r1.success()).isTrue();
            assertThat(r1.remainingCredits()).isEqualByComparingTo(new BigDecimal("1.0000"));

            assertThat(r2.success()).isTrue();
            assertThat(r2.remainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);

            // Third call blocked - balance=0, cost=1
            assertThat(r3.success()).isFalse();
            assertThat(r3.error()).contains("Insufficient credits");

            // Delta 1: 2 successful debits + 1 rejection audit = 3 ledger writes.
            verify(ledgerRepository, times(3)).save(any());
        }

        @Test
        @DisplayName("insufficientCredits() is returned when balance < cost")
        void insufficientCreditsReturnedWhenBalanceTooLow() {
            BigDecimal hugeNegativeBalance = new BigDecimal("-99999.9999");
            Subscription sub = createSubscription(hugeNegativeBalance);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(new BigDecimal("1.0000"));

            CreditConsumeResult result = creditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Insufficient credits");
            assertThat(result.remainingCredits()).isEqualByComparingTo(hugeNegativeBalance);
        }
    }

    // ===== hasSufficientCredits =====

    @Nested
    @DisplayName("hasSufficientCredits")
    class HasSufficientCredits {

        @Test
        @DisplayName("should return true when balance is well above minimum")
        void shouldReturnTrueWhenBalancePositive() {
            mockActiveSubscription(new BigDecimal("100.0000"));
            assertThat(creditService.hasSufficientCredits(USER_ID)).isTrue();
        }

        @Test
        @DisplayName("should return true when balance is exactly 1 (minimum)")
        void shouldReturnTrueWhenBalanceExactlyOne() {
            mockActiveSubscription(new BigDecimal("1.0000"));
            assertThat(creditService.hasSufficientCredits(USER_ID)).isTrue();
        }

        @Test
        @DisplayName("should return false when balance is 0.99 (below minimum)")
        void shouldReturnFalseWhenBalanceBelowOne() {
            mockActiveSubscription(new BigDecimal("0.9900"));
            assertThat(creditService.hasSufficientCredits(USER_ID)).isFalse();
        }

        @Test
        @DisplayName("should return false when balance is 0.48 (the production bypass case)")
        void shouldReturnFalseWhenBalanceIsProductionBypassCase() {
            mockActiveSubscription(new BigDecimal("0.4800"));
            assertThat(creditService.hasSufficientCredits(USER_ID)).isFalse();
        }

        @Test
        @DisplayName("should return false when balance is zero")
        void shouldReturnFalseWhenBalanceZero() {
            mockActiveSubscription(BigDecimal.ZERO);
            assertThat(creditService.hasSufficientCredits(USER_ID)).isFalse();
        }

        @Test
        @DisplayName("should return false when balance is negative")
        void shouldReturnFalseWhenBalanceNegative() {
            mockActiveSubscription(new BigDecimal("-5.0000"));
            assertThat(creditService.hasSufficientCredits(USER_ID)).isFalse();
        }

        @Test
        @DisplayName("should return false when no subscription exists")
        void shouldReturnFalseWhenNoSubscription() {
            mockNoSubscription();
            assertThat(creditService.hasSufficientCredits(USER_ID)).isFalse();
        }
    }

    // ===== canAfford =====

    @Nested
    @DisplayName("canAfford (cost-aware pre-flight)")
    class CanAfford {

        @Test
        @DisplayName("returns true when balance is exactly the projected cost")
        void trueWhenBalanceEqualsProjected() {
            mockActiveSubscription(new BigDecimal("78.50"));
            assertThat(creditService.canAfford(USER_ID, new BigDecimal("78.50"))).isTrue();
        }

        @Test
        @DisplayName("returns true when balance exceeds projected cost")
        void trueWhenBalanceExceedsProjected() {
            mockActiveSubscription(new BigDecimal("100.00"));
            assertThat(creditService.canAfford(USER_ID, new BigDecimal("25.00"))).isTrue();
        }

        @Test
        @DisplayName("returns false when balance is below projected cost - the regression this method exists to catch")
        void falseWhenBalanceBelowProjected() {
            mockActiveSubscription(new BigDecimal("1.50"));
            assertThat(creditService.canAfford(USER_ID, new BigDecimal("78.00"))).isFalse();
        }

        @Test
        @DisplayName("returns false when no subscription exists, regardless of cost")
        void falseWhenNoSubscription() {
            mockNoSubscription();
            assertThat(creditService.canAfford(USER_ID, new BigDecimal("10.00"))).isFalse();
        }

        @Test
        @DisplayName("null projected cost falls back to hasSufficientCredits - never passes for zero balance")
        void nullProjectedFallsBackToSufficient() {
            mockActiveSubscription(BigDecimal.ZERO);
            assertThat(creditService.canAfford(USER_ID, null)).isFalse();
        }

        @Test
        @DisplayName("zero projected cost falls back to hasSufficientCredits")
        void zeroProjectedFallsBackToSufficient() {
            mockActiveSubscription(new BigDecimal("2.00"));
            assertThat(creditService.canAfford(USER_ID, BigDecimal.ZERO)).isTrue();
        }

        @Test
        @DisplayName("negative projected cost falls back to hasSufficientCredits (defensive)")
        void negativeProjectedFallsBackToSufficient() {
            mockActiveSubscription(new BigDecimal("2.00"));
            assertThat(creditService.canAfford(USER_ID, new BigDecimal("-1.00"))).isTrue();
        }
    }

    // ===== Unlimited mode (CE) =====

    @Nested
    @DisplayName("Unlimited mode (CE)")
    class UnlimitedMode {

        private CreditService unlimitedCreditService;

        @BeforeEach
        void setUp() {
            unlimitedCreditService = new CreditService(subscriptionRepository, ledgerRepository, pricingService, true);
        }

        @Test
        @DisplayName("consumeForWorkflowNode should create ledger entry with cost=1 in unlimited mode")
        void workflowNodeShouldCreateLedgerEntryInUnlimitedMode() {
            when(ledgerRepository.existsBySourceId("run-1:node-a")).thenReturn(false);

            CreditConsumeResult result = unlimitedCreditService.consumeForWorkflowNode(USER_ID, "run-1:node-a");

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("999999999"));

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getAmount()).isEqualByComparingTo(new BigDecimal("-1"));
            assertThat(entry.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("999999999"));
            assertThat(entry.getSourceType()).isEqualTo("WORKFLOW_NODE");
            // No subscription deduction should occur
            verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(anyLong());
        }

        @Test
        @DisplayName("consumeForAgent should create ledger entry with real $ cost in unlimited mode")
        void agentShouldCreateLedgerEntryWithRealCostInUnlimitedMode() {
            BigDecimal realCost = new BigDecimal("0.0350");
            when(pricingService.calculateCost(PROVIDER, MODEL, LlmTokenBreakdown.of(PROMPT_TOKENS, COMPLETION_TOKENS)))
                    .thenReturn(realCost);

            CreditConsumeResult result = unlimitedCreditService.consumeForAgent(
                    USER_ID, SOURCE_ID, PROVIDER, MODEL, PROMPT_TOKENS, COMPLETION_TOKENS);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(realCost);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getAmount()).isEqualByComparingTo(realCost.negate());
            assertThat(entry.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("999999999"));
            assertThat(entry.getProvider()).isEqualTo(PROVIDER);
            assertThat(entry.getModel()).isEqualTo(MODEL);
        }

        @Test
        @DisplayName("consumeForWorkflowNode should skip duplicate sourceId in unlimited mode")
        void workflowNodeShouldSkipDuplicateInUnlimitedMode() {
            when(ledgerRepository.existsBySourceId("run-1:node-dup")).thenReturn(true);

            CreditConsumeResult result = unlimitedCreditService.consumeForWorkflowNode(USER_ID, "run-1:node-dup");

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(ledgerRepository, never()).save(any());
        }

        @Test
        @DisplayName("getBalance should return unlimited sentinel")
        void getBalanceShouldReturnSentinel() {
            BigDecimal balance = unlimitedCreditService.getBalance(USER_ID);
            assertThat(balance).isEqualByComparingTo(new BigDecimal("999999999"));
        }

        @Test
        @DisplayName("hasSufficientCredits should always return true in unlimited mode")
        void hasSufficientCreditsShouldAlwaysReturnTrue() {
            assertThat(unlimitedCreditService.hasSufficientCredits(USER_ID)).isTrue();
        }

        @Test
        @DisplayName("canAfford should always return true in unlimited mode, even for impossible costs")
        void canAffordShouldAlwaysReturnTrueInUnlimitedMode() {
            assertThat(unlimitedCreditService.canAfford(USER_ID, new BigDecimal("99999999"))).isTrue();
            assertThat(unlimitedCreditService.canAfford(USER_ID, null)).isTrue();
        }
    }

    // ===== getRunCostSummary =====

    @Nested
    @DisplayName("getRunCostSummary")
    class GetRunCostSummary {

        @Test
        @DisplayName("should aggregate cost and breakdown by source type for a run, including web-search and web-fetch")
        void shouldAggregateCostByRun() {
            String runId = "run-abc-123";
            BigDecimal totalCost = new BigDecimal("7.2500");
            when(ledgerRepository.sumCostByRunIdIncludingWebSearch(
                    USER_ID, runId + ":%", "web-search:RUN:" + runId + ":%",
                    "web-fetch:RUN:" + runId + ":%")).thenReturn(totalCost);
            when(ledgerRepository.getCostBreakdownByRunIdIncludingWebSearch(
                    USER_ID, runId + ":%", "web-search:RUN:" + runId + ":%",
                    "web-fetch:RUN:" + runId + ":%")).thenReturn(List.of(
                    new Object[]{"WORKFLOW_NODE", 3L, new BigDecimal("3.0000"), 0L, 0L},
                    new Object[]{"AGENT_EXECUTION", 1L, new BigDecimal("2.2500"), 1500L, 800L},
                    new Object[]{"WEB_SEARCH", 1L, new BigDecimal("1.0000"), 0L, 0L},
                    new Object[]{"WEB_FETCH", 1L, new BigDecimal("1.0000"), 0L, 0L}
            ));

            Map<String, Object> result = creditService.getRunCostSummary(USER_ID, runId);

            assertThat(result.get("runId")).isEqualTo(runId);
            assertThat((BigDecimal) result.get("totalCost")).isEqualByComparingTo(totalCost);
            assertThat((long) result.get("totalTokens")).isEqualTo(2300L);

            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> breakdown =
                    (Map<String, Map<String, Object>>) result.get("breakdownByType");
            assertThat(breakdown).containsKeys("WORKFLOW_NODE", "AGENT_EXECUTION", "WEB_SEARCH", "WEB_FETCH");
            assertThat((BigDecimal) breakdown.get("AGENT_EXECUTION").get("cost"))
                    .isEqualByComparingTo(new BigDecimal("2.2500"));
            assertThat((Long) breakdown.get("AGENT_EXECUTION").get("promptTokens")).isEqualTo(1500L);
            assertThat((BigDecimal) breakdown.get("WEB_SEARCH").get("cost"))
                    .isEqualByComparingTo(new BigDecimal("1.0000"));
            assertThat((BigDecimal) breakdown.get("WEB_FETCH").get("cost"))
                    .isEqualByComparingTo(new BigDecimal("1.0000"));
        }

        @Test
        @DisplayName("memberWorkspaceRunCostUsesOwnerWalletLedger")
        void memberWorkspaceRunCostUsesOwnerWalletLedger() {
            Long ownerUserId = 7L;
            PlanResolutionService resolver = mock(PlanResolutionService.class);
            creditService.setPlanResolutionService(resolver);
            when(resolver.resolvePayerUserId(USER_ID)).thenReturn(ownerUserId);

            String runId = "run-owner-workspace";
            BigDecimal totalCost = new BigDecimal("3.0000");
            when(ledgerRepository.sumCostByRunIdIncludingWebSearch(
                    ownerUserId, runId + ":%", "web-search:RUN:" + runId + ":%",
                    "web-fetch:RUN:" + runId + ":%")).thenReturn(totalCost);
            when(ledgerRepository.getCostBreakdownByRunIdIncludingWebSearch(
                    ownerUserId, runId + ":%", "web-search:RUN:" + runId + ":%",
                    "web-fetch:RUN:" + runId + ":%")).thenReturn(List.<Object[]>of(
                    new Object[]{"WORKFLOW_NODE", 3L, new BigDecimal("3.0000"), 0L, 0L}
            ));

            Map<String, Object> result = creditService.getRunCostSummary(USER_ID, runId);

            assertThat((BigDecimal) result.get("totalCost")).isEqualByComparingTo(totalCost);
            verify(ledgerRepository).sumCostByRunIdIncludingWebSearch(
                    ownerUserId, runId + ":%", "web-search:RUN:" + runId + ":%",
                    "web-fetch:RUN:" + runId + ":%");
            verify(ledgerRepository, never()).sumCostByRunIdIncludingWebSearch(
                    eq(USER_ID), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("should return zero cost for non-existent run")
        void shouldReturnZeroForNonExistentRun() {
            when(ledgerRepository.sumCostByRunIdIncludingWebSearch(
                    USER_ID, "nonexistent:%", "web-search:RUN:nonexistent:%",
                    "web-fetch:RUN:nonexistent:%")).thenReturn(BigDecimal.ZERO);
            when(ledgerRepository.getCostBreakdownByRunIdIncludingWebSearch(
                    USER_ID, "nonexistent:%", "web-search:RUN:nonexistent:%",
                    "web-fetch:RUN:nonexistent:%")).thenReturn(List.of());

            Map<String, Object> result = creditService.getRunCostSummary(USER_ID, "nonexistent");

            assertThat((BigDecimal) result.get("totalCost")).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat((long) result.get("totalTokens")).isEqualTo(0L);
            @SuppressWarnings("unchecked")
            Map<String, ?> breakdown = (Map<String, ?>) result.get("breakdownByType");
            assertThat(breakdown).isEmpty();
        }
    }
}

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

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CREDIT SERVICE CONSUMPTION AUDIT - covers gaps not in CreditServiceTest:
 * - Unlimited mode (consumption tracked but balance never deducted)
 * - WorkflowNode idempotency (existsBySourceId guard)
 * - Null remainingCredits on subscription
 * - Source type coverage for all execution paths
 * - Custom sourceType in consumeForAgent
 * - Cross-source-type ledger isolation
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CreditService - Consumption Audit (unlimited, idempotency, edge cases)")
class CreditServiceConsumptionAuditTest {

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

    private static final Long USER_ID = 42L;
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100.0000");

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

    // =====================================================================
    // UNLIMITED MODE
    // =====================================================================

    @Nested
    @DisplayName("Unlimited Mode - consumption tracked but balance never deducted")
    class UnlimitedMode {

        private CreditService unlimitedService;

        @BeforeEach
        void setUp() {
            unlimitedService = new CreditService(subscriptionRepository, ledgerRepository, pricingService, true);
        }

        @Test
        @DisplayName("consumeForAgent in unlimited mode: ledger entry created, balance stays 999999999")
        void consumeForAgentUnlimitedTracksLedger() {
            BigDecimal cost = new BigDecimal("5.0000");
            when(pricingService.calculateCost("openai", "gpt-4", LlmTokenBreakdown.of(1000, 500)))
                    .thenReturn(cost);

            CreditConsumeResult result = unlimitedService.consumeForAgent(
                    USER_ID, "exec-1", "openai", "gpt-4", 1000, 500);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(cost);
            assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("999999999"));

            // Ledger entry IS created (tracking)
            verify(ledgerRepository).save(ledgerCaptor.capture());
            CreditLedgerEntry entry = ledgerCaptor.getValue();
            assertThat(entry.getAmount()).isEqualByComparingTo(cost.negate());
            assertThat(entry.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("999999999"));
            assertThat(entry.getSourceType()).isEqualTo("AGENT_EXECUTION");

            // Subscription is NEVER modified
            verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(anyLong());
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("consumeForWorkflowNode in unlimited mode: 1 credit tracked in ledger (CE/Cloud observability)")
        void consumeForWorkflowNodeUnlimitedZeroCost() {
            // Workflow node cost is BigDecimal.ONE in both CE and Cloud - CE tracks for observability
            CreditConsumeResult result = unlimitedService.consumeForWorkflowNode(USER_ID, "run:node:0:0:0:0");

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ONE);

            // Ledger entry IS created (tracking)
            verify(ledgerRepository).save(any());
        }

        @Test
        @DisplayName("consumeForChat in unlimited mode: tracks but never deducts")
        void consumeForChatUnlimitedTracks() {
            BigDecimal cost = new BigDecimal("2.5000");
            when(pricingService.calculateCost("anthropic", "claude-3", LlmTokenBreakdown.of(500, 200)))
                    .thenReturn(cost);

            CreditConsumeResult result = unlimitedService.consumeForChat(
                    USER_ID, "conv-1", "anthropic", "claude-3", 500, 200);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(cost);
            assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("999999999"));

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("CHAT_CONVERSATION");

            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("V363: unlimited-mode chat still records the cached subset on the tracking row")
        void consumeForChatUnlimitedRecordsCached() {
            var usage = new LlmTokenBreakdown(36402, 228, 0, 0, 18048, 0);
            when(pricingService.calculateCost("deepseek", "deepseek-chat", usage))
                    .thenReturn(new BigDecimal("10.3324"));

            unlimitedService.consumeForChat(USER_ID, "conv-ul-cache", "deepseek", "deepseek-chat", usage);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getCachedTokens()).isEqualTo(18048);
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("getBalance in unlimited mode: always returns 999999999")
        void getBalanceUnlimitedAlwaysHigh() {
            BigDecimal balance = unlimitedService.getBalance(USER_ID);
            assertThat(balance).isEqualByComparingTo(new BigDecimal("999999999"));
            verify(subscriptionRepository, never()).findActiveByUserId(anyLong());
        }

        @Test
        @DisplayName("hasSufficientCredits in unlimited mode: always returns true")
        void hasSufficientCreditsUnlimitedAlwaysTrue() {
            assertThat(unlimitedService.hasSufficientCredits(USER_ID)).isTrue();
        }

        @Test
        @DisplayName("grantCredits in unlimited mode: ignored, returns success with zero cost")
        void grantCreditsUnlimitedIgnored() {
            CreditConsumeResult result = unlimitedService.grantCredits(
                    USER_ID, new BigDecimal("50"), "ADMIN_GRANT", "admin-1", "Test grant");

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);

            verify(ledgerRepository, never()).save(any());
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("consumeForWebSearch in unlimited mode: ledger entry created, balance never deducted")
        void consumeForWebSearchUnlimitedTracksLedger() {
            CreditConsumeResult result = unlimitedService.consumeForWebSearch(USER_ID, "ws-1");

            assertThat(result.success()).isTrue();
            assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("999999999"));

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("WEB_SEARCH");
            assertThat(ledgerCaptor.getValue().getBalanceAfter())
                    .isEqualByComparingTo(new BigDecimal("999999999"));
            verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(anyLong());
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("consumeForImageGeneration in unlimited mode: ledger created with actual count, balance never deducted")
        void consumeForImageGenerationUnlimitedTracksLedger() {
            when(pricingService.hasPricing("openai", "gpt-image-1.5-low")).thenReturn(true);
            when(pricingService.calculateUnitCost("openai", "gpt-image-1.5-low", 3))
                    .thenReturn(new BigDecimal("30"));

            CreditConsumeResult result = unlimitedService.consumeForImageGeneration(
                    USER_ID, "ig-1", "openai", "gpt-image-1.5-low", 3);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo("30");
            assertThat(result.remainingCredits()).isEqualByComparingTo(new BigDecimal("999999999"));

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("IMAGE_GENERATION");
            assertThat(ledgerCaptor.getValue().getPromptTokens()).isEqualTo(3); // actualImageCount
            verify(subscriptionRepository, never()).findActiveByUserIdForUpdate(anyLong());
            verify(subscriptionRepository, never()).save(any());
        }
    }

    // =====================================================================
    // IDEMPOTENCY - consumeForWorkflowNode
    // =====================================================================

    @Nested
    @DisplayName("WorkflowNode idempotency - duplicate sourceId guard")
    class WorkflowNodeIdempotency {

        private CreditService service;

        @BeforeEach
        void setUp() {
            service = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
        }

        @Test
        @DisplayName("first call with sourceId: deducts 1 credit")
        void firstCallDeductsCredit() {
            when(ledgerRepository.existsBySourceId("run:node:0:0:0:0")).thenReturn(false);
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = service.consumeForWorkflowNode(USER_ID, "run:node:0:0:0:0");

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ONE);
            verify(ledgerRepository).save(any());
        }

        @Test
        @DisplayName("duplicate call with same sourceId: returns success with ZERO credits used (no double charge)")
        void duplicateCallSkipsDeduction() {
            when(ledgerRepository.existsBySourceId("run:node:0:0:0:0")).thenReturn(true);
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = service.consumeForWorkflowNode(USER_ID, "run:node:0:0:0:0");

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.remainingCredits()).isEqualByComparingTo(INITIAL_BALANCE);

            // No ledger entry and no subscription modification
            verify(ledgerRepository, never()).save(any());
            verify(subscriptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("null sourceId: NO idempotency check, always deducts")
        void nullSourceIdAlwaysDeducts() {
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = service.consumeForWorkflowNode(USER_ID, null);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ONE);
            verify(ledgerRepository, never()).existsBySourceId(any());
            verify(ledgerRepository).save(any());
        }

        @Test
        @DisplayName("different sourceIds for same node at different epochs: both charged")
        void differentEpochsDifferentSourceIds() {
            when(ledgerRepository.existsBySourceId("run:node:0:0:0:0")).thenReturn(false);
            when(ledgerRepository.existsBySourceId("run:node:1:0:0:0")).thenReturn(false);
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult r1 = service.consumeForWorkflowNode(USER_ID, "run:node:0:0:0:0");
            CreditConsumeResult r2 = service.consumeForWorkflowNode(USER_ID, "run:node:1:0:0:0");

            assertThat(r1.success()).isTrue();
            assertThat(r2.success()).isTrue();
            verify(ledgerRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("signal node sourceId format (runId:nodeId:epoch:spawn) → idempotent with step node if same")
        void signalAndStepSameSourceIdAreIdempotent() {
            // Signal nodes use format: runId:nodeId:epoch:spawn
            // Step nodes use format: runId:nodeId:epoch:spawn:iteration:itemIndex
            // If they happen to match (unlikely), the duplicate guard catches it
            String signalSourceId = "run-1:core:approval:3:2";
            when(ledgerRepository.existsBySourceId(signalSourceId)).thenReturn(true);
            mockActiveSubscription(INITIAL_BALANCE);

            CreditConsumeResult result = service.consumeForWorkflowNode(USER_ID, signalSourceId);

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // =====================================================================
    // NULL REMAINING CREDITS ON SUBSCRIPTION
    // =====================================================================

    @Nested
    @DisplayName("Null remainingCredits on subscription")
    class NullRemainingCredits {

        private CreditService service;

        @BeforeEach
        void setUp() {
            service = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
        }

        @Test
        @DisplayName("null remainingCredits treated as ZERO → blocks deduction")
        void nullRemainingCreditsTreatedAsZero() {
            Subscription sub = createSubscription(null);
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            when(pricingService.calculateCost("openai", "gpt-4", LlmTokenBreakdown.of(100, 50)))
                    .thenReturn(new BigDecimal("1.0000"));

            CreditConsumeResult result = service.consumeForAgent(
                    USER_ID, "exec-1", "openai", "gpt-4", 100, 50);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Insufficient credits");
        }

        @Test
        @DisplayName("null remainingCredits → getBalance returns ZERO")
        void nullRemainingCreditsGetBalanceReturnsZero() {
            Subscription sub = createSubscription(null);
            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(sub));

            BigDecimal balance = service.getBalance(USER_ID);

            assertThat(balance).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("null remainingCredits → hasSufficientCredits returns false")
        void nullRemainingCreditsInsufficientCredits() {
            Subscription sub = createSubscription(null);
            when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(sub));

            assertThat(service.hasSufficientCredits(USER_ID)).isFalse();
        }
    }

    // =====================================================================
    // SOURCE TYPE ISOLATION - consumeForAgent with custom sourceType
    // =====================================================================

    @Nested
    @DisplayName("Custom sourceType in consumeForAgent")
    class CustomSourceType {

        private CreditService service;

        @BeforeEach
        void setUp() {
            service = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
        }

        @Test
        @DisplayName("CLASSIFY_EXECUTION source type stored in ledger")
        void classifySourceType() {
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost("openai", "gpt-4", LlmTokenBreakdown.of(200, 0)))
                    .thenReturn(new BigDecimal("0.0200"));

            CreditConsumeResult result = service.consumeForAgent(
                    USER_ID, "classify-exec-1", "openai", "gpt-4", 200, 0, "CLASSIFY_EXECUTION");

            assertThat(result.success()).isTrue();

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("CLASSIFY_EXECUTION");
        }

        @Test
        @DisplayName("GUARDRAIL_EXECUTION source type stored in ledger")
        void guardrailSourceType() {
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost("anthropic", "claude-3", LlmTokenBreakdown.of(300, 0)))
                    .thenReturn(new BigDecimal("0.0300"));

            CreditConsumeResult result = service.consumeForAgent(
                    USER_ID, "guard-exec-1", "anthropic", "claude-3", 300, 0, "GUARDRAIL_EXECUTION");

            assertThat(result.success()).isTrue();

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("GUARDRAIL_EXECUTION");
        }

        @Test
        @DisplayName("default consumeForAgent (no sourceType param) uses AGENT_EXECUTION")
        void defaultSourceTypeIsAgentExecution() {
            mockActiveSubscription(INITIAL_BALANCE);
            when(pricingService.calculateCost("openai", "gpt-4", LlmTokenBreakdown.of(100, 50)))
                    .thenReturn(new BigDecimal("0.2500"));

            service.consumeForAgent(USER_ID, "exec-1", "openai", "gpt-4", 100, 50);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("AGENT_EXECUTION");
        }
    }

    // =====================================================================
    // MARKETPLACE PURCHASE - cannot go negative
    // =====================================================================

    @Nested
    @DisplayName("Marketplace purchase - large cost blocked correctly")
    class MarketplacePurchaseLargeCost {

        private CreditService service;

        @BeforeEach
        void setUp() {
            service = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
        }

        @Test
        @DisplayName("purchase of 1000 credits with only 50 balance → blocked")
        void largePurchaseBlocked() {
            Subscription sub = createSubscription(new BigDecimal("50.0000"));
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));

            CreditConsumeResult result = service.consumeForMarketplacePurchase(USER_ID, "pub-1", 1000);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Insufficient credits");
            verify(subscriptionRepository, never()).save(any());
        }
    }

    // =====================================================================
    // Cache-aware billing passthrough (2026-06-11 fix)
    // =====================================================================

    @Nested
    @DisplayName("Cache-aware billing - breakdown reaches pricing, ledger keeps raw tokens")
    class CacheAwareBillingPassthrough {

        private CreditService service;

        @BeforeEach
        void setUp() {
            service = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
            mockActiveSubscription(new BigDecimal("1000.0000"));
        }

        @Test
        @DisplayName("consumeForAgent forwards the full cache breakdown to ModelPricingService")
        void consumeForAgentForwardsBreakdownToPricing() {
            // Regression: pre-fix the cost was computed from prompt+completion only,
            // billing claude-code cache reads at full input rate.
            var usage = new LlmTokenBreakdown(131195, 677, 45390, 85800, 0, 0);
            when(pricingService.calculateCost("claude-code", "claude-opus-4-6", usage))
                    .thenReturn(new BigDecimal("343.5375"));

            CreditConsumeResult result = service.consumeForAgent(
                    USER_ID, "exec-cache-1", "claude-code", "claude-opus-4-6", usage, "AGENT_EXECUTION");

            assertThat(result.success()).isTrue();
            assertThat(result.creditsUsed()).isEqualByComparingTo("343.5375");
            verify(pricingService).calculateCost("claude-code", "claude-opus-4-6", usage);
        }

        @Test
        @DisplayName("ledger row keeps the raw provider-reported prompt/completion counts and audits the cache split")
        void ledgerKeepsRawTokensAndAuditsCacheSplit() {
            var usage = new LlmTokenBreakdown(131195, 677, 45390, 85800, 0, 0);
            when(pricingService.calculateCost(any(), any(), any(LlmTokenBreakdown.class)))
                    .thenReturn(new BigDecimal("343.5375"));

            service.consumeForAgent(USER_ID, "exec-cache-2", "claude-code", "claude-opus-4-6",
                    usage, "AGENT_EXECUTION");

            verify(ledgerRepository).save(ledgerCaptor.capture());
            var entry = ledgerCaptor.getValue();
            assertThat(entry.getPromptTokens()).isEqualTo(131195);
            assertThat(entry.getCompletionTokens()).isEqualTo(677);
            // V363: persisted cached subset = max(cachedTokens, cacheReadTokens) = max(0, 85800).
            assertThat(entry.getCachedTokens()).isEqualTo(85800);
            assertThat(entry.getDescription())
                    .contains("cache write 45390")
                    .contains("cache read 85800");
        }

        @Test
        @DisplayName("legacy 7-arg consumeForAgent is equivalent to an all-zero breakdown")
        void legacyOverloadEquivalentToZeroBreakdown() {
            when(pricingService.calculateCost(eq("openai"), eq("gpt-4o"),
                    eq(LlmTokenBreakdown.of(500, 200))))
                    .thenReturn(new BigDecimal("2.0000"));

            CreditConsumeResult result = service.consumeForAgent(
                    USER_ID, "exec-legacy-1", "openai", "gpt-4o", 500, 200, "AGENT_EXECUTION");

            assertThat(result.success()).isTrue();
            verify(pricingService).calculateCost("openai", "gpt-4o", LlmTokenBreakdown.of(500, 200));
        }

        @Test
        @DisplayName("consumeForChat forwards the cache breakdown to ModelPricingService")
        void consumeForChatForwardsBreakdown() {
            var usage = new LlmTokenBreakdown(1000, 500, 2000, 10000, 0, 0);
            when(pricingService.calculateCost("anthropic", "claude-sonnet-4-6", usage))
                    .thenReturn(new BigDecimal("21.0000"));

            CreditConsumeResult result = service.consumeForChat(
                    USER_ID, "conv-cache-1", "anthropic", "claude-sonnet-4-6", usage);

            assertThat(result.success()).isTrue();
            verify(pricingService).calculateCost("anthropic", "claude-sonnet-4-6", usage);
        }

        @Test
        @DisplayName("V363: consumeForChat persists the DeepSeek cached subset (cachedTokens) on the ledger row")
        void consumeForChatPersistsDeepSeekCachedSubset() {
            // DeepSeek shape: cache hits land in cachedTokens (a subset of prompt), cacheRead stays 0.
            var usage = new LlmTokenBreakdown(36402, 228, 0, 0, 18048, 0);
            when(pricingService.calculateCost("deepseek", "deepseek-chat", usage))
                    .thenReturn(new BigDecimal("10.3324"));

            service.consumeForChat(USER_ID, "conv-cache-ds", "deepseek", "deepseek-chat", usage);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            var entry = ledgerCaptor.getValue();
            assertThat(entry.getPromptTokens()).isEqualTo(36402);
            assertThat(entry.getCachedTokens()).isEqualTo(18048);
        }

        @Test
        @DisplayName("V363: consumeForAgent persists the Anthropic cache-read as cachedTokens via max(cached, cacheRead)")
        void consumeForAgentPersistsAnthropicCacheReadAsCached() {
            // Anthropic shape: cachedTokens=0; the discounted reads live in cacheReadTokens.
            var usage = new LlmTokenBreakdown(5000, 300, 1200, 9000, 0, 0);
            when(pricingService.calculateCost(any(), any(), any(LlmTokenBreakdown.class)))
                    .thenReturn(new BigDecimal("4.0000"));

            service.consumeForAgent(USER_ID, "exec-cache-anthropic", "anthropic",
                    "claude-sonnet-4-6", usage, "AGENT_EXECUTION");

            verify(ledgerRepository).save(ledgerCaptor.capture());
            // max(cachedTokens=0, cacheReadTokens=9000) = 9000
            assertThat(ledgerCaptor.getValue().getCachedTokens()).isEqualTo(9000);
        }

        @Test
        @DisplayName("V363: a turn with no cache hit persists cachedTokens=0 (distinct from a non-LLM null)")
        void noCacheHitPersistsZero() {
            when(pricingService.calculateCost(eq("openai"), eq("gpt-4o"),
                    eq(LlmTokenBreakdown.of(500, 200))))
                    .thenReturn(new BigDecimal("2.0000"));

            service.consumeForAgent(USER_ID, "exec-no-cache", "openai", "gpt-4o", 500, 200, "AGENT_EXECUTION");

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getCachedTokens()).isEqualTo(0);
        }

        @Test
        @DisplayName("V363: a non-LLM debit (workflow node) leaves cachedTokens null")
        void nonLlmDebitLeavesCachedTokensNull() {
            when(ledgerRepository.existsBySourceId("run:node:0:0:0:0")).thenReturn(false);

            service.consumeForWorkflowNode(USER_ID, "run:node:0:0:0:0");

            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getCachedTokens()).isNull();
        }

        @Test
        @DisplayName("V363: an insufficient-balance rejection audit row still records the cached subset")
        void rejectionAuditRowRecordsCached() {
            // Override the 1000-balance stub with a balance too low to cover the cost so the
            // allowNegative=false agent path writes a *_REJECTED audit row (rejection branch).
            Subscription poor = createSubscription(new BigDecimal("0.5000"));
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(poor));
            var usage = new LlmTokenBreakdown(36402, 228, 0, 0, 18048, 0);
            when(pricingService.calculateCost("deepseek", "deepseek-chat", usage))
                    .thenReturn(new BigDecimal("10.3324"));

            CreditConsumeResult result = service.consumeForAgent(
                    USER_ID, "exec-reject", "deepseek", "deepseek-chat", usage, "AGENT_EXECUTION");

            assertThat(result.success()).isFalse();
            verify(ledgerRepository).save(ledgerCaptor.capture());
            var rejected = ledgerCaptor.getValue();
            assertThat(rejected.getSourceType()).isEqualTo("AGENT_EXECUTION_REJECTED");
            assertThat(rejected.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(rejected.getCachedTokens()).isEqualTo(18048);
        }

        @Test
        @DisplayName("V363: a zero-cost bridge row (cost==0, tokens>0) still records the cached subset")
        void zeroCostRowRecordsCached() {
            // cost==0 with tokens>0 takes the zero-cost audit branch (before the subscription debit).
            var usage = new LlmTokenBreakdown(1000, 200, 0, 0, 640, 0);
            when(pricingService.calculateCost("deepseek", "deepseek-chat", usage))
                    .thenReturn(BigDecimal.ZERO);

            service.consumeForChat(USER_ID, "conv-zero", "deepseek", "deepseek-chat", usage);

            verify(ledgerRepository).save(ledgerCaptor.capture());
            var entry = ledgerCaptor.getValue();
            assertThat(entry.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(entry.getCachedTokens()).isEqualTo(640);
        }
    }

    // =====================================================================
    // ALL FIVE SOURCE TYPES - full coverage
    // =====================================================================

    @Nested
    @DisplayName("Source type ledger coverage - all 5 source types recorded correctly")
    class SourceTypeCoverage {

        private CreditService service;

        @BeforeEach
        void setUp() {
            service = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
            mockActiveSubscription(new BigDecimal("1000.0000"));
        }

        @Test
        @DisplayName("AGENT_EXECUTION via consumeForAgent")
        void agentExecution() {
            when(pricingService.calculateCost(any(), any(), any(com.apimarketplace.auth.service.LlmTokenBreakdown.class)))
                    .thenReturn(new BigDecimal("1.0000"));
            service.consumeForAgent(USER_ID, "exec-1", "openai", "gpt-4", 500, 200);
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("AGENT_EXECUTION");
        }

        @Test
        @DisplayName("CHAT_CONVERSATION via consumeForChat")
        void chatConversation() {
            when(pricingService.calculateCost(any(), any(), any(com.apimarketplace.auth.service.LlmTokenBreakdown.class)))
                    .thenReturn(new BigDecimal("0.5000"));
            service.consumeForChat(USER_ID, "conv-1", "anthropic", "claude-3", 300, 100);
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("CHAT_CONVERSATION");
        }

        @Test
        @DisplayName("WORKFLOW_NODE via consumeForWorkflowNode")
        void workflowNode() {
            when(ledgerRepository.existsBySourceId("run:node:0:0:0:0")).thenReturn(false);
            service.consumeForWorkflowNode(USER_ID, "run:node:0:0:0:0");
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("WORKFLOW_NODE");
        }

        @Test
        @DisplayName("MARKETPLACE_PURCHASE via consumeForMarketplacePurchase")
        void marketplacePurchase() {
            service.consumeForMarketplacePurchase(USER_ID, "pub-1", 10);
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("MARKETPLACE_PURCHASE");
        }

        @Test
        @DisplayName("CLASSIFY_EXECUTION via consumeForAgent with custom sourceType")
        void classifyExecution() {
            when(pricingService.calculateCost(any(), any(), any(com.apimarketplace.auth.service.LlmTokenBreakdown.class)))
                    .thenReturn(new BigDecimal("0.1000"));
            service.consumeForAgent(USER_ID, "class-1", "openai", "gpt-4", 200, 0, "CLASSIFY_EXECUTION");
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("CLASSIFY_EXECUTION");
        }

        @Test
        @DisplayName("GUARDRAIL_EXECUTION via consumeForAgent with custom sourceType")
        void guardrailExecution() {
            when(pricingService.calculateCost(any(), any(), any(com.apimarketplace.auth.service.LlmTokenBreakdown.class)))
                    .thenReturn(new BigDecimal("0.0500"));
            service.consumeForAgent(USER_ID, "guard-1", "openai", "gpt-4", 100, 0, "GUARDRAIL_EXECUTION");
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("GUARDRAIL_EXECUTION");
        }

        @Test
        @DisplayName("WEB_SEARCH via consumeForWebSearch")
        void webSearch() {
            service.consumeForWebSearch(USER_ID, "ws-1");
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("WEB_SEARCH");
        }

        @Test
        @DisplayName("IMAGE_GENERATION via consumeForImageGeneration")
        void imageGeneration() {
            when(pricingService.hasPricing("openai", "gpt-image-1.5-low")).thenReturn(true);
            when(pricingService.calculateUnitCost("openai", "gpt-image-1.5-low", 1))
                    .thenReturn(new BigDecimal("10"));
            service.consumeForImageGeneration(USER_ID, "ig-1", "openai", "gpt-image-1.5-low", 1);
            verify(ledgerRepository).save(ledgerCaptor.capture());
            assertThat(ledgerCaptor.getValue().getSourceType()).isEqualTo("IMAGE_GENERATION");
        }
    }

    // =====================================================================
    // SEQUENTIAL CONSUMPTION ACROSS SOURCE TYPES
    // =====================================================================

    @Nested
    @DisplayName("Sequential consumption across source types - balance consistency")
    class SequentialConsumption {

        private CreditService service;

        @BeforeEach
        void setUp() {
            service = new CreditService(subscriptionRepository, ledgerRepository, pricingService, false);
        }

        @Test
        @DisplayName("workflow node + agent + chat + marketplace = cumulative deduction")
        void mixedConsumptionCumulative() {
            // Use a single subscription object to track balance changes
            Subscription sub = createSubscription(new BigDecimal("20.0000"));
            when(subscriptionRepository.findActiveByUserIdForUpdate(USER_ID))
                    .thenReturn(Optional.of(sub));
            lenient().when(subscriptionRepository.findActiveByUserId(USER_ID))
                    .thenReturn(Optional.of(sub));

            // 1. Workflow node: -1 credit → balance = 19
            when(ledgerRepository.existsBySourceId("run:node:0:0:0:0")).thenReturn(false);
            CreditConsumeResult r1 = service.consumeForWorkflowNode(USER_ID, "run:node:0:0:0:0");
            assertThat(r1.success()).isTrue();
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(new BigDecimal("19.0000"));

            // 2. Agent execution: -5 credits → balance = 14
            when(pricingService.calculateCost("openai", "gpt-4", LlmTokenBreakdown.of(1000, 500)))
                    .thenReturn(new BigDecimal("5.0000"));
            CreditConsumeResult r2 = service.consumeForAgent(USER_ID, "exec-1", "openai", "gpt-4", 1000, 500);
            assertThat(r2.success()).isTrue();
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(new BigDecimal("14.0000"));

            // 3. Chat: -3 credits → balance = 11
            when(pricingService.calculateCost("anthropic", "claude-3", LlmTokenBreakdown.of(500, 200)))
                    .thenReturn(new BigDecimal("3.0000"));
            CreditConsumeResult r3 = service.consumeForChat(USER_ID, "conv-1", "anthropic", "claude-3", 500, 200);
            assertThat(r3.success()).isTrue();
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(new BigDecimal("11.0000"));

            // 4. Marketplace: -10 credits → balance = 1
            CreditConsumeResult r4 = service.consumeForMarketplacePurchase(USER_ID, "pub-1", 10);
            assertThat(r4.success()).isTrue();
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(new BigDecimal("1.0000"));

            // 5. Next agent execution: -5 credits → BLOCKED (1 < 5)
            CreditConsumeResult r5 = service.consumeForAgent(USER_ID, "exec-2", "openai", "gpt-4", 1000, 500);
            assertThat(r5.success()).isFalse();
            assertThat(r5.error()).contains("Insufficient credits");

            // Balance unchanged
            assertThat(sub.getRemainingCredits()).isEqualByComparingTo(new BigDecimal("1.0000"));

            // Delta 1 - 4 successful deductions + 1 rejection audit row = 5 ledger writes.
            // The rejected row has amount=0 so the balance sum invariant is unchanged.
            verify(ledgerRepository, times(5)).save(any());
        }
    }
}

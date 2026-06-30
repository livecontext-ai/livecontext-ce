package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.service.CreditService;
import com.apimarketplace.auth.service.LlmTokenBreakdown;
import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;
import com.apimarketplace.auth.service.ModelPricingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreditController Tests")
class CreditControllerTest {

    @Mock
    private CreditService creditService;

    @Mock
    private ModelPricingService pricingService;

    @InjectMocks
    private CreditController controller;

    private static final Long USER_ID = 42L;
    private static final String ORG_ID = "11111111-1111-4111-8111-111111111111";

    // ---- POST /api/credits/consume ----

    @Nested
    @DisplayName("POST /api/credits/consume")
    class ConsumeTests {

        @Test
        @DisplayName("should consume credits for AGENT_EXECUTION with valid payload and return 200")
        void shouldConsumeForAgentExecution() {
            var request = new CreditController.CreditConsumeRequest(
                    "AGENT_EXECUTION", "agent-run-1", "openai", "gpt-4", 100, 50);
            var expectedResult = CreditConsumeResult.success(new BigDecimal("2.50"), new BigDecimal("97.50"));

            when(creditService.consumeForAgent(USER_ID,"agent-run-1", "openai", "gpt-4", LlmTokenBreakdown.of(100, 50), "AGENT_EXECUTION"))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isTrue();
            assertThat(response.getBody().creditsUsed()).isEqualByComparingTo("2.50");
            assertThat(response.getBody().remainingCredits()).isEqualByComparingTo("97.50");

            verify(creditService).consumeForAgent(USER_ID,"agent-run-1", "openai", "gpt-4", LlmTokenBreakdown.of(100, 50), "AGENT_EXECUTION");
        }

        @Test
        @DisplayName("should consume credits for CLI_SESSION (zero-token bridge/CLI run) via consumeForAgent and return 200 - regression for the source-type allow-list")
        void shouldConsumeForCliSession() {
            // CLI/bridge sessions (claude-code/codex/gemini) stamp 0 tokens (the external CLI
            // pays its own provider). Pre-fix CLI_SESSION hit the switch default →
            // IllegalArgumentException → 500 → a rejection/dead-letter row on every CLI run.
            // It must be allow-listed and routed to consumeForAgent (which yields ~0 credits).
            var request = new CreditController.CreditConsumeRequest(
                    "CLI_SESSION", "cli-run-1", "anthropic", "claude-sonnet-4-6", 0, 0);
            var expectedResult = CreditConsumeResult.success(BigDecimal.ZERO, new BigDecimal("100.00"));

            when(creditService.consumeForAgent(USER_ID, "cli-run-1", "anthropic", "claude-sonnet-4-6", LlmTokenBreakdown.of(0, 0), "CLI_SESSION"))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(creditService).consumeForAgent(USER_ID, "cli-run-1", "anthropic", "claude-sonnet-4-6", LlmTokenBreakdown.of(0, 0), "CLI_SESSION");
        }

        @Test
        @DisplayName("should throw for removed WORKFLOW_RUN source type")
        void shouldThrowForWorkflowRun() {
            var request = new CreditController.CreditConsumeRequest(
                    "WORKFLOW_RUN", "wf-run-123", null, null, null, null);

            assertThatThrownBy(() -> controller.consume(USER_ID,request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown source type: WORKFLOW_RUN");
        }

        @Test
        @DisplayName("should consume credits for CHAT_CONVERSATION and return 200")
        void shouldConsumeForChatConversation() {
            var request = new CreditController.CreditConsumeRequest(
                    "CHAT_CONVERSATION", "conv-456", "anthropic", "claude-3", 200, 100);
            var expectedResult = CreditConsumeResult.success(new BigDecimal("5.00"), new BigDecimal("95.00"));

            when(creditService.consumeForChat(USER_ID,"conv-456", "anthropic", "claude-3", LlmTokenBreakdown.of(200, 100)))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isTrue();

            verify(creditService).consumeForChat(USER_ID,"conv-456", "anthropic", "claude-3", LlmTokenBreakdown.of(200, 100));
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for unknown source type")
        void shouldThrowForUnknownSourceType() {
            var request = new CreditController.CreditConsumeRequest(
                    "UNKNOWN", "src-1", "provider", "model", 10, 10);

            assertThatThrownBy(() -> controller.consume(USER_ID,request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown source type: UNKNOWN");
        }

        @Test
        @DisplayName("should default promptTokens to 0 when null for AGENT_EXECUTION")
        void shouldDefaultNullPromptTokensToZero() {
            var request = new CreditController.CreditConsumeRequest(
                    "AGENT_EXECUTION", "agent-run-2", "openai", "gpt-4", null, 50);
            var expectedResult = CreditConsumeResult.success(new BigDecimal("1.00"), new BigDecimal("99.00"));

            when(creditService.consumeForAgent(USER_ID,"agent-run-2", "openai", "gpt-4", LlmTokenBreakdown.of(0, 50), "AGENT_EXECUTION"))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(creditService).consumeForAgent(USER_ID,"agent-run-2", "openai", "gpt-4", LlmTokenBreakdown.of(0, 50), "AGENT_EXECUTION");
        }

        @Test
        @DisplayName("should default completionTokens to 0 when null for AGENT_EXECUTION")
        void shouldDefaultNullCompletionTokensToZero() {
            var request = new CreditController.CreditConsumeRequest(
                    "AGENT_EXECUTION", "agent-run-3", "openai", "gpt-4", 100, null);
            var expectedResult = CreditConsumeResult.success(new BigDecimal("1.00"), new BigDecimal("99.00"));

            when(creditService.consumeForAgent(USER_ID,"agent-run-3", "openai", "gpt-4", LlmTokenBreakdown.of(100, 0), "AGENT_EXECUTION"))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(creditService).consumeForAgent(USER_ID,"agent-run-3", "openai", "gpt-4", LlmTokenBreakdown.of(100, 0), "AGENT_EXECUTION");
        }

        @Test
        @DisplayName("should default both tokens to 0 when null for CHAT_CONVERSATION")
        void shouldDefaultBothNullTokensToZeroForChat() {
            var request = new CreditController.CreditConsumeRequest(
                    "CHAT_CONVERSATION", "conv-789", "anthropic", "claude-3", null, null);
            var expectedResult = CreditConsumeResult.success(new BigDecimal("0.50"), new BigDecimal("99.50"));

            when(creditService.consumeForChat(USER_ID,"conv-789", "anthropic", "claude-3", LlmTokenBreakdown.of(0, 0)))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(creditService).consumeForChat(USER_ID,"conv-789", "anthropic", "claude-3", LlmTokenBreakdown.of(0, 0));
        }

        @Test
        @DisplayName("should return 402 when service returns failure result")
        void shouldReturn402WhenServiceReturnsFailure() {
            var request = new CreditController.CreditConsumeRequest(
                    "AGENT_EXECUTION", "agent-run-4", "openai", "gpt-4", 100, 50);
            var failResult = CreditConsumeResult.insufficientCredits(new BigDecimal("1.00"), new BigDecimal("5.00"));

            when(creditService.consumeForAgent(USER_ID,"agent-run-4", "openai", "gpt-4", LlmTokenBreakdown.of(100, 50), "AGENT_EXECUTION"))
                    .thenReturn(failResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode().value()).isEqualTo(402);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isFalse();
            assertThat(response.getBody().error()).contains("Insufficient credits");
        }

        @Test
        @DisplayName("should throw IllegalArgumentException when sourceType is null")
        void shouldThrowForNullSourceType() {
            var request = new CreditController.CreditConsumeRequest(
                    null, "src-1", "provider", "model", 10, 10);

            assertThatThrownBy(() -> controller.consume(USER_ID,request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sourceType must not be null");
        }

        @Test
        @DisplayName("should consume credits for CLASSIFY_EXECUTION and pass sourceType through")
        void shouldConsumeForClassifyExecution() {
            var request = new CreditController.CreditConsumeRequest(
                    "CLASSIFY_EXECUTION", "classify-run-1", "openai", "gpt-4o", 500, 100);
            var expectedResult = CreditConsumeResult.success(new BigDecimal("0.50"), new BigDecimal("99.50"));

            when(creditService.consumeForAgent(USER_ID,"classify-run-1", "openai", "gpt-4o", LlmTokenBreakdown.of(500, 100), "CLASSIFY_EXECUTION"))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isTrue();

            verify(creditService).consumeForAgent(USER_ID,"classify-run-1", "openai", "gpt-4o", LlmTokenBreakdown.of(500, 100), "CLASSIFY_EXECUTION");
        }

        @Test
        @DisplayName("should consume credits for GUARDRAIL_EXECUTION and pass sourceType through")
        void shouldConsumeForGuardrailExecution() {
            var request = new CreditController.CreditConsumeRequest(
                    "GUARDRAIL_EXECUTION", "guard-run-1", "anthropic", "claude-3", 300, 50);
            var expectedResult = CreditConsumeResult.success(new BigDecimal("0.30"), new BigDecimal("99.70"));

            when(creditService.consumeForAgent(USER_ID,"guard-run-1", "anthropic", "claude-3", LlmTokenBreakdown.of(300, 50), "GUARDRAIL_EXECUTION"))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isTrue();

            verify(creditService).consumeForAgent(USER_ID,"guard-run-1", "anthropic", "claude-3", LlmTokenBreakdown.of(300, 50), "GUARDRAIL_EXECUTION");
        }

        @Test
        @DisplayName("should consume credits for COMPACTION_SUMMARY (Stage 5.4 async COLD summariser) via consumeForAgent")
        void shouldConsumeForCompactionSummary() {
            // Regression: the source type was introduced on agent-service (Stage 5.4) but the
            // CreditController switch was not updated, so every async COLD summariser call
            // returned 500 and the corresponding Prometheus sample was never recorded. The
            // auth-service + agent-service enum lists MUST stay in sync.
            var request = new CreditController.CreditConsumeRequest(
                    "COMPACTION_SUMMARY", "compact-run-1", "zai", "glm-5-turbo", 300, 50);
            var expectedResult = CreditConsumeResult.success(new BigDecimal("0.12"), new BigDecimal("99.88"));

            when(creditService.consumeForAgent(USER_ID,"compact-run-1", "zai", "glm-5-turbo", LlmTokenBreakdown.of(300, 50), "COMPACTION_SUMMARY"))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isTrue();

            verify(creditService).consumeForAgent(USER_ID,"compact-run-1", "zai", "glm-5-turbo", LlmTokenBreakdown.of(300, 50), "COMPACTION_SUMMARY");
        }

        @Test
        @DisplayName("should consume credits for CE_LLM_RELAY via the idempotent consumeForCeRelay path")
        void shouldConsumeForCeLlmRelay() {
            // CE_LLM_RELAY now routes to consumeForCeRelay (idempotent on (sourceId,
            // CE_LLM_RELAY)) instead of the non-idempotent consumeForAgent, so the
            // centralized per-execution settle and the crash-recovery reaper can both
            // fire with the same executionId without double-billing.
            var request = new CreditController.CreditConsumeRequest(
                    "CE_LLM_RELAY", "ce-llm-1", "deepseek", "deepseek-chat", 300, 50);
            var expectedResult = CreditConsumeResult.success(new BigDecimal("0.12"), new BigDecimal("99.88"));

            when(creditService.consumeForCeRelay(USER_ID,"ce-llm-1", "deepseek", "deepseek-chat", LlmTokenBreakdown.of(300, 50)))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isTrue();

            verify(creditService).consumeForCeRelay(USER_ID,"ce-llm-1", "deepseek", "deepseek-chat", LlmTokenBreakdown.of(300, 50));
        }

        @Test
        @DisplayName("should return 402 when no subscription exists")
        void shouldReturn402WhenNoSubscription() {
            var request = new CreditController.CreditConsumeRequest(
                    "WORKFLOW_NODE", "run-999:node-001", null, null, null, null);
            var noSubResult = CreditConsumeResult.noSubscription();

            when(creditService.consumeForWorkflowNode(USER_ID,"run-999:node-001"))
                    .thenReturn(noSubResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode().value()).isEqualTo(402);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isFalse();
            assertThat(response.getBody().error()).isEqualTo("No active subscription");
            assertThat(response.getBody().remainingCredits()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("should consume credits for WORKFLOW_NODE and return 200")
        void shouldConsumeForWorkflowNode() {
            var request = new CreditController.CreditConsumeRequest(
                    "WORKFLOW_NODE", "run-123:node-456", null, null, null, null);
            var expectedResult = CreditConsumeResult.success(BigDecimal.ONE, new BigDecimal("99.00"));

            when(creditService.consumeForWorkflowNode(USER_ID,"run-123:node-456"))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isTrue();
            assertThat(response.getBody().creditsUsed()).isEqualByComparingTo(BigDecimal.ONE);

            verify(creditService).consumeForWorkflowNode(USER_ID,"run-123:node-456");
            verifyNoMoreInteractions(creditService);
        }

        @Test
        @DisplayName("should return 402 when WORKFLOW_NODE consume returns noSubscription")
        void shouldReturn402ForWorkflowNodeWhenNoSubscription() {
            var request = new CreditController.CreditConsumeRequest(
                    "WORKFLOW_NODE", "run-999:node-001", null, null, null, null);
            var noSubResult = CreditConsumeResult.noSubscription();

            when(creditService.consumeForWorkflowNode(USER_ID,"run-999:node-001"))
                    .thenReturn(noSubResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode().value()).isEqualTo(402);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isFalse();
            assertThat(response.getBody().error()).isEqualTo("No active subscription");

            verify(creditService).consumeForWorkflowNode(USER_ID,"run-999:node-001");
            verifyNoMoreInteractions(creditService);
        }
        @Test
        @DisplayName("should consume credits for MARKETPLACE_PURCHASE with cost field and return 200")
        void shouldConsumeForMarketplacePurchase() {
            var request = new CreditController.CreditConsumeRequest(
                    "MARKETPLACE_PURCHASE", "pub-uuid-123", null, null, null, null, 25);
            var expectedResult = CreditConsumeResult.success(new BigDecimal("25"), new BigDecimal("75.00"));

            when(creditService.consumeForMarketplacePurchase(USER_ID,"pub-uuid-123", 25))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isTrue();
            assertThat(response.getBody().creditsUsed()).isEqualByComparingTo("25");
            assertThat(response.getBody().remainingCredits()).isEqualByComparingTo("75.00");

            verify(creditService).consumeForMarketplacePurchase(USER_ID,"pub-uuid-123", 25);
        }

        @Test
        @DisplayName("should default cost to 0 when null for MARKETPLACE_PURCHASE")
        void shouldDefaultCostToZeroWhenNull() {
            var request = new CreditController.CreditConsumeRequest(
                    "MARKETPLACE_PURCHASE", "pub-uuid-456", null, null, null, null, null);
            var expectedResult = CreditConsumeResult.success(BigDecimal.ZERO, new BigDecimal("100.00"));

            when(creditService.consumeForMarketplacePurchase(USER_ID,"pub-uuid-456", 0))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(creditService).consumeForMarketplacePurchase(USER_ID,"pub-uuid-456", 0);
        }

        @Test
        @DisplayName("should return 402 when MARKETPLACE_PURCHASE fails with insufficient credits")
        void shouldReturn402ForMarketplacePurchaseInsufficientCredits() {
            var request = new CreditController.CreditConsumeRequest(
                    "MARKETPLACE_PURCHASE", "pub-uuid-789", null, null, null, null, 100);
            var failResult = CreditConsumeResult.insufficientCredits(new BigDecimal("10.00"), new BigDecimal("100"));

            when(creditService.consumeForMarketplacePurchase(USER_ID,"pub-uuid-789", 100))
                    .thenReturn(failResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode().value()).isEqualTo(402);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().success()).isFalse();
            assertThat(response.getBody().error()).contains("Insufficient credits");
        }

        @Test
        @DisplayName("should use backward-compatible 6-arg constructor defaulting cost to null")
        void shouldWorkWithBackwardCompatibleConstructor() {
            var request = new CreditController.CreditConsumeRequest(
                    "WORKFLOW_NODE", "run-compat:node-1", null, null, null, null);
            var expectedResult = CreditConsumeResult.success(BigDecimal.ONE, new BigDecimal("99.00"));

            when(creditService.consumeForWorkflowNode(USER_ID,"run-compat:node-1"))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(request.cost()).isNull();
            assertThat(request.imageCount()).isNull();
        }

        @Test
        @DisplayName("WEB_SEARCH sourceType routes to consumeForWebSearch")
        void shouldRouteWebSearchSourceType() {
            var request = new CreditController.CreditConsumeRequest(
                    "WEB_SEARCH", "ws-1", "websearch", "default", null, null);
            var expectedResult = CreditConsumeResult.success(new BigDecimal("1"), new BigDecimal("99"));

            when(creditService.consumeForWebSearch(USER_ID,"ws-1")).thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(creditService).consumeForWebSearch(USER_ID,"ws-1");
        }

        @Test
        @DisplayName("WEB_FETCH sourceType routes to consumeForWebFetch")
        void shouldRouteWebFetchSourceType() {
            var request = new CreditController.CreditConsumeRequest(
                    "WEB_FETCH", "wf-1", "websearch", "default", null, null);
            var expectedResult = CreditConsumeResult.success(new BigDecimal("1"), new BigDecimal("99"));

            when(creditService.consumeForWebFetch(USER_ID,"wf-1")).thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(creditService).consumeForWebFetch(USER_ID,"wf-1");
        }

        @Test
        @DisplayName("IMAGE_GENERATION sourceType routes to consumeForImageGeneration with imageCount")
        void shouldRouteImageGenerationSourceType() {
            var request = new CreditController.CreditConsumeRequest(
                    "IMAGE_GENERATION", "ig-1", "openai", "gpt-image-1.5-low",
                    /* prompt */ null, /* completion */ null, /* cost */ null, /* imageCount */ 4);
            var expectedResult = CreditConsumeResult.success(new BigDecimal("40"), new BigDecimal("60"));

            when(creditService.consumeForImageGeneration(USER_ID,"ig-1", "openai", "gpt-image-1.5-low", 4))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(creditService).consumeForImageGeneration(USER_ID,"ig-1", "openai", "gpt-image-1.5-low", 4);
        }

        @Test
        @DisplayName("IMAGE_GENERATION defaults imageCount to 1 when null (backward-compat)")
        void shouldDefaultImageCountToOneWhenNull() {
            var request = new CreditController.CreditConsumeRequest(
                    "IMAGE_GENERATION", "ig-2", "google", "gemini-2.5-flash-image", null, null);
            // 6-arg constructor → imageCount=null → controller defaults to 1
            var expectedResult = CreditConsumeResult.success(new BigDecimal("5"), new BigDecimal("95"));

            when(creditService.consumeForImageGeneration(USER_ID,"ig-2", "google", "gemini-2.5-flash-image", 1))
                    .thenReturn(expectedResult);

            ResponseEntity<CreditConsumeResult> response = controller.consume(USER_ID,request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(creditService).consumeForImageGeneration(USER_ID,"ig-2", "google", "gemini-2.5-flash-image", 1);
        }
    }

    // ---- GET /api/credits/pricing/{provider}/{model}/exists ----

    @Nested
    @DisplayName("GET /api/credits/pricing/{provider}/{model}/exists")
    class PricingExistsTests {

        @Test
        @DisplayName("returns exists=true when pricing row found")
        void existsTrue() {
            when(pricingService.hasPricing("openai", "gpt-image-1.5-low")).thenReturn(true);

            ResponseEntity<Map<String, Object>> response = controller.pricingExists("openai", "gpt-image-1.5-low");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("exists", true);
            assertThat(response.getBody()).containsEntry("provider", "openai");
            assertThat(response.getBody()).containsEntry("model", "gpt-image-1.5-low");
        }

        @Test
        @DisplayName("returns exists=false when pricing row missing")
        void existsFalse() {
            when(pricingService.hasPricing("unknown", "model-x")).thenReturn(false);

            ResponseEntity<Map<String, Object>> response = controller.pricingExists("unknown", "model-x");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("exists", false);
        }
    }

    // ---- GET /api/credits/balance ----

    @Nested
    @DisplayName("GET /api/credits/balance")
    class BalanceTests {

        @Test
        @DisplayName("should return normal balance from service")
        void shouldReturnNormalBalance() {
            // V250 - controller now calls getBalanceBreakdown() (returns the
            // 4-tuple total/sub/payg/delinquent) instead of getBalance().
            BigDecimal balance = new BigDecimal("150.75");
            when(creditService.getBalanceBreakdown(USER_ID)).thenReturn(
                new CreditService.BalanceBreakdown(balance, balance, BigDecimal.ZERO, false));

            ResponseEntity<Map<String, Object>> response = controller.getBalance(USER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody()).containsEntry("balance", balance);
        }

        @Test
        @DisplayName("should return zero balance")
        void shouldReturnZeroBalance() {
            when(creditService.getBalanceBreakdown(USER_ID)).thenReturn(
                new CreditService.BalanceBreakdown(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false));

            ResponseEntity<Map<String, Object>> response = controller.getBalance(USER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody()).containsEntry("balance", BigDecimal.ZERO);
        }
    }

    // ---- POST /api/credits/check-chat ----

    @Nested
    @DisplayName("POST /api/credits/check-chat (cost-aware pre-flight)")
    class CheckChatBudgetTests {

        @Test
        @DisplayName("returns 200 allowed=true when balance covers projected cost")
        void returns200WhenBalanceCoversProjectedCost() {
            BigDecimal balance = new BigDecimal("100.00");
            BigDecimal projected = new BigDecimal("78.00");
            when(creditService.getBalance(USER_ID)).thenReturn(balance);
            when(pricingService.hasPricing("claude-code", "claude-sonnet-4-6")).thenReturn(true);
            when(pricingService.calculateCost("claude-code", "claude-sonnet-4-6", 4100, 8192))
                    .thenReturn(projected);
            when(creditService.canAfford(USER_ID, projected, "CHAT_CONVERSATION")).thenReturn(true);

            var request = new CreditController.ChatBudgetRequest(
                    "claude-code", "claude-sonnet-4-6", 4100, 8192);
            ResponseEntity<Map<String, Object>> response = controller.checkChatBudget(USER_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .containsEntry("allowed", true)
                    .containsEntry("balance", balance)
                    .containsEntry("estimatedCost", projected);
        }

        @Test
        @DisplayName("returns 402 allowed=false when projected cost exceeds balance - the critical regression guard")
        void returns402WhenProjectedExceedsBalance() {
            // Regression: production user had 1.50 credits, pre-check returned allowed (balance >= 1),
            // inference ran, post-check consumed nothing (402) and user got a free answer.
            // This endpoint computes the actual projected cost instead of a floor check.
            BigDecimal balance = new BigDecimal("1.50");
            BigDecimal projected = new BigDecimal("78.00");
            when(creditService.getBalance(USER_ID)).thenReturn(balance);
            when(pricingService.hasPricing("claude-code", "claude-sonnet-4-6")).thenReturn(true);
            when(pricingService.calculateCost("claude-code", "claude-sonnet-4-6", 4100, 8192))
                    .thenReturn(projected);
            when(creditService.canAfford(USER_ID, projected, "CHAT_CONVERSATION")).thenReturn(false);

            var request = new CreditController.ChatBudgetRequest(
                    "claude-code", "claude-sonnet-4-6", 4100, 8192);
            ResponseEntity<Map<String, Object>> response = controller.checkChatBudget(USER_ID, request);

            assertThat(response.getStatusCode().value()).isEqualTo(402);
            assertThat(response.getBody())
                    .containsEntry("allowed", false)
                    .containsEntry("balance", balance)
                    .containsEntry("estimatedCost", projected);
        }

        @Test
        @DisplayName("null estimated tokens default to 0 when computing projected cost")
        void nullTokensDefaultToZero() {
            when(creditService.getBalance(USER_ID)).thenReturn(new BigDecimal("10.00"));
            when(pricingService.hasPricing("openai", "gpt-4")).thenReturn(true);
            when(pricingService.calculateCost("openai", "gpt-4", 0, 0))
                    .thenReturn(BigDecimal.ZERO);
            when(creditService.canAfford(USER_ID, BigDecimal.ZERO, "CHAT_CONVERSATION")).thenReturn(true);

            var request = new CreditController.ChatBudgetRequest("openai", "gpt-4", null, null);
            ResponseEntity<Map<String, Object>> response = controller.checkChatBudget(USER_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(pricingService).calculateCost("openai", "gpt-4", 0, 0);
        }

        @Test
        @DisplayName("returns 402 without consulting canAfford when pricing row is missing - cannot price the call")
        void returns402WhenPricingRowMissing() {
            // Regression guard for audit P0-2: the upstream ModelPricingService silently
            // falls back to default mid-tier rates when a (provider, model) has no row.
            // That fail-open is wrong for the budget gate: a frontier/bridge model with
            // no pricing row would be priced like gpt-3.5 and pass the gate, then the
            // post-flight real-cost debit would fail with 402 - exactly the incident we
            // are closing. Deny instead.
            when(creditService.getBalance(USER_ID)).thenReturn(new BigDecimal("1000.00"));
            when(pricingService.hasPricing("new-provider", "unreleased-model")).thenReturn(false);

            var request = new CreditController.ChatBudgetRequest(
                    "new-provider", "unreleased-model", 4100, 8192);
            ResponseEntity<Map<String, Object>> response = controller.checkChatBudget(USER_ID, request);

            assertThat(response.getStatusCode().value()).isEqualTo(402);
            assertThat(response.getBody())
                    .containsEntry("allowed", false)
                    .containsKey("error");
            // Critical: calculateCost must NOT be invoked - we refused BEFORE pricing
            // because the server had no authoritative rate to price with. If this fires,
            // we'd be back to the default-rate fail-open that caused the original incident.
            verify(pricingService, never()).calculateCost(anyString(), anyString(), anyInt(), anyInt());
            verify(pricingService, never()).calculateCost(anyString(), anyString(), any(LlmTokenBreakdown.class));
            verify(creditService, never()).canAfford(anyLong(), any(BigDecimal.class), anyString());
        }

        @Test
        @DisplayName("returns 402 when provider is null - hasPricing rejects null input")
        void returns402OnNullProvider() {
            when(creditService.getBalance(USER_ID)).thenReturn(new BigDecimal("1000.00"));
            when(pricingService.hasPricing(null, "gpt-4")).thenReturn(false);

            var request = new CreditController.ChatBudgetRequest(null, "gpt-4", 100, 200);
            ResponseEntity<Map<String, Object>> response = controller.checkChatBudget(USER_ID, request);

            assertThat(response.getStatusCode().value()).isEqualTo(402);
            verify(pricingService, never()).calculateCost(anyString(), anyString(), anyInt(), anyInt());
            verify(pricingService, never()).calculateCost(anyString(), anyString(), any(LlmTokenBreakdown.class));
        }

        @Test
        @DisplayName("returns 402 when model is null - hasPricing rejects null input")
        void returns402OnNullModel() {
            when(creditService.getBalance(USER_ID)).thenReturn(new BigDecimal("1000.00"));
            when(pricingService.hasPricing("openai", null)).thenReturn(false);

            var request = new CreditController.ChatBudgetRequest("openai", null, 100, 200);
            ResponseEntity<Map<String, Object>> response = controller.checkChatBudget(USER_ID, request);

            assertThat(response.getStatusCode().value()).isEqualTo(402);
            verify(pricingService, never()).calculateCost(anyString(), anyString(), anyInt(), anyInt());
            verify(pricingService, never()).calculateCost(anyString(), anyString(), any(LlmTokenBreakdown.class));
        }
    }

    // ---- GET /api/credits/summary ----

    @Nested
    @DisplayName("GET /api/credits/summary")
    class SummaryTests {

        @Test
        @DisplayName("should return usage summary with balance and breakdown")
        void shouldReturnUsageSummary() {
            Map<String, Object> summary = new HashMap<>();
            summary.put("balance", new BigDecimal("100.00"));
            summary.put("totalConsumedLast30Days", new BigDecimal("50.00"));
            summary.put("breakdownByType", Map.of(
                    "AGENT_EXECUTION", Map.of("count", 10L, "credits", new BigDecimal("30.00")),
                    "WORKFLOW_RUN", Map.of("count", 20L, "credits", new BigDecimal("20.00"))
            ));

            when(creditService.getUsageSummary(eq(USER_ID), isNull())).thenReturn(summary);

            ResponseEntity<Map<String, Object>> response = controller.getSummary(USER_ID, null, false);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody()).containsKey("balance");
            assertThat(response.getBody()).containsKey("totalConsumedLast30Days");
            assertThat(response.getBody()).containsKey("breakdownByType");
            assertThat(response.getBody().get("balance")).isEqualTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("V366: a picked workspace (header present, allWorkspaces=false) is passed as the org reporting filter")
        void summaryPicksWorkspaceFilter() {
            when(creditService.getUsageSummary(eq(USER_ID), eq(ORG_ID)))
                    .thenReturn(Map.of("balance", new BigDecimal("1.00")));

            controller.getSummary(USER_ID, ORG_ID, false);

            verify(creditService).getUsageSummary(USER_ID, ORG_ID);
            verify(creditService, never()).getUsageSummary(eq(USER_ID), isNull());
        }

        @Test
        @DisplayName("V366: allWorkspaces=true aggregates across workspaces (null org filter) even with an active workspace header")
        void summaryAllWorkspacesIgnoresHeader() {
            when(creditService.getUsageSummary(eq(USER_ID), isNull()))
                    .thenReturn(Map.of("balance", new BigDecimal("1.00")));

            controller.getSummary(USER_ID, ORG_ID, true);

            verify(creditService).getUsageSummary(USER_ID, null);
            verify(creditService, never()).getUsageSummary(eq(USER_ID), eq(ORG_ID));
        }
    }

    // ---- GET /api/credits/history ----

    @Nested
    @DisplayName("GET /api/credits/history")
    class HistoryTests {

        @Test
        @DisplayName("should use default pagination (page=0, size=20) with no sourceType")
        void shouldUseDefaultPagination() {
            PageRequest expectedPageRequest = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<CreditLedgerEntry> emptyPage = new PageImpl<>(Collections.emptyList());

            when(creditService.getUsageHistory(eq(USER_ID), isNull(), eq(expectedPageRequest)))
                    .thenReturn(emptyPage);

            ResponseEntity<Page<CreditLedgerEntry>> response = controller.getHistory(USER_ID,0, 20, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getContent()).isEmpty();

            verify(creditService).getUsageHistory(USER_ID, null, expectedPageRequest);
            verify(creditService, never()).getUsageHistoryByType(anyLong(), anyString(), any(), any());
        }

        @Test
        @DisplayName("regression: usage history binds X-Organization-ID while resolving the workspace owner wallet")
        void shouldBindOrganizationScopeForHistory() {
            PageRequest expectedPageRequest = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<CreditLedgerEntry> emptyPage = new PageImpl<>(Collections.emptyList());
            AtomicReference<String> seenOrgId = new AtomicReference<>();

            when(creditService.getUsageHistory(eq(USER_ID), eq(ORG_ID), eq(expectedPageRequest))).thenAnswer(invocation -> {
                seenOrgId.set(com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId());
                return emptyPage;
            });

            ResponseEntity<Page<CreditLedgerEntry>> response =
                    controller.getHistory(USER_ID, ORG_ID, 0, 20, null, false);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(seenOrgId.get()).isEqualTo(ORG_ID);
            assertThat(com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId())
                    .as("controller must restore the prior thread-local scope after the call")
                    .isNull();
            // V366: a picked workspace (header present, allWorkspaces=false) is passed as the
            // org reporting filter too, not only bound to the thread-local scope.
            verify(creditService).getUsageHistory(USER_ID, ORG_ID, expectedPageRequest);
        }

        @Test
        @DisplayName("V366: allWorkspaces=true ignores the active workspace header and aggregates all workspaces (null org filter)")
        void historyAllWorkspacesIgnoresHeader() {
            PageRequest expectedPageRequest = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            when(creditService.getUsageHistory(eq(USER_ID), isNull(), eq(expectedPageRequest)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            controller.getHistory(USER_ID, ORG_ID, 0, 20, null, true);

            verify(creditService).getUsageHistory(USER_ID, null, expectedPageRequest);
            verify(creditService, never()).getUsageHistory(eq(USER_ID), eq(ORG_ID), any());
        }

        @Test
        @DisplayName("should use custom pagination (page=2, size=10)")
        void shouldUseCustomPagination() {
            PageRequest expectedPageRequest = PageRequest.of(2, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
            CreditLedgerEntry entry = new CreditLedgerEntry();
            entry.setUserId(USER_ID);
            entry.setSourceType("AGENT_EXECUTION");
            Page<CreditLedgerEntry> page = new PageImpl<>(List.of(entry));

            when(creditService.getUsageHistory(eq(USER_ID), isNull(), eq(expectedPageRequest)))
                    .thenReturn(page);

            ResponseEntity<Page<CreditLedgerEntry>> response = controller.getHistory(USER_ID,2, 10, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getContent()).hasSize(1);

            verify(creditService).getUsageHistory(USER_ID, null, expectedPageRequest);
        }

        @Test
        @DisplayName("should filter by sourceType when provided")
        void shouldFilterBySourceType() {
            PageRequest expectedPageRequest = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<CreditLedgerEntry> emptyPage = new PageImpl<>(Collections.emptyList());

            when(creditService.getUsageHistoryByType(eq(USER_ID), eq("AGENT_EXECUTION"), isNull(), eq(expectedPageRequest)))
                    .thenReturn(emptyPage);

            ResponseEntity<Page<CreditLedgerEntry>> response =
                    controller.getHistory(USER_ID,0, 20, "AGENT_EXECUTION");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            verify(creditService).getUsageHistoryByType(USER_ID, "AGENT_EXECUTION", null, expectedPageRequest);
            verify(creditService, never()).getUsageHistory(anyLong(), any(), any());
        }

        @Test
        @DisplayName("should call unfiltered method when sourceType is null")
        void shouldCallUnfilteredWhenSourceTypeNull() {
            PageRequest expectedPageRequest = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<CreditLedgerEntry> emptyPage = new PageImpl<>(Collections.emptyList());

            when(creditService.getUsageHistory(eq(USER_ID), isNull(), eq(expectedPageRequest)))
                    .thenReturn(emptyPage);

            controller.getHistory(USER_ID,0, 20, null);

            verify(creditService).getUsageHistory(USER_ID, null, expectedPageRequest);
            verify(creditService, never()).getUsageHistoryByType(anyLong(), anyString(), any(), any());
        }

        @Test
        @DisplayName("should treat empty/blank sourceType as no filter")
        void shouldTreatEmptySourceTypeAsNoFilter() {
            PageRequest expectedPageRequest = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<CreditLedgerEntry> emptyPage = new PageImpl<>(Collections.emptyList());

            when(creditService.getUsageHistory(eq(USER_ID), isNull(), eq(expectedPageRequest)))
                    .thenReturn(emptyPage);

            controller.getHistory(USER_ID,0, 20, "");

            verify(creditService).getUsageHistory(USER_ID, null, expectedPageRequest);
            verify(creditService, never()).getUsageHistoryByType(anyLong(), anyString(), any(), any());
        }

        @Test
        @DisplayName("should treat blank (whitespace-only) sourceType as no filter")
        void shouldTreatBlankSourceTypeAsNoFilter() {
            PageRequest expectedPageRequest = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<CreditLedgerEntry> emptyPage = new PageImpl<>(Collections.emptyList());

            when(creditService.getUsageHistory(eq(USER_ID), isNull(), eq(expectedPageRequest)))
                    .thenReturn(emptyPage);

            controller.getHistory(USER_ID,0, 20, "   ");

            verify(creditService).getUsageHistory(USER_ID, null, expectedPageRequest);
            verify(creditService, never()).getUsageHistoryByType(anyLong(), anyString(), any(), any());
        }

        @Test
        @DisplayName("should pass negative page number through to service")
        void shouldPassNegativePageNumber() {
            // Negative page numbers are passed to PageRequest.of which will throw IllegalArgumentException
            assertThatThrownBy(() -> controller.getHistory(USER_ID,-1, 20, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should pass large page size through to service")
        void shouldPassLargePageSize() {
            PageRequest expectedPageRequest = PageRequest.of(0, 10000, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<CreditLedgerEntry> emptyPage = new PageImpl<>(Collections.emptyList());

            when(creditService.getUsageHistory(eq(USER_ID), isNull(), eq(expectedPageRequest)))
                    .thenReturn(emptyPage);

            ResponseEntity<Page<CreditLedgerEntry>> response = controller.getHistory(USER_ID,0, 10000, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(creditService).getUsageHistory(USER_ID, null, expectedPageRequest);
        }
    }

    // ---- GET /api/credits/analytics ----

    @Nested
    @DisplayName("GET /api/credits/analytics")
    class AnalyticsTests {

        @Test
        @DisplayName("should return analytics with default 30 days and no filters")
        void shouldReturnAnalyticsWithDefaults() {
            Map<String, Object> analyticsData = new HashMap<>();
            analyticsData.put("dailyUsage", List.of(
                    Map.of("date", "2026-03-01", "sourceType", "AGENT_EXECUTION", "count", 5L, "credits", new BigDecimal("10.00"), "tokens", 5000L)
            ));
            analyticsData.put("providers", List.of("openai", "anthropic"));
            analyticsData.put("models", List.of("gpt-4", "claude-3"));
            analyticsData.put("sourceTypes", List.of("AGENT_EXECUTION", "CHAT_CONVERSATION"));

            when(creditService.getUsageAnalytics(USER_ID,30, null, null, null, null))
                    .thenReturn(analyticsData);

            ResponseEntity<Map<String, Object>> response = controller.getAnalytics(USER_ID, null,30, null, null, null, false);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody()).containsKey("dailyUsage");
            assertThat(response.getBody()).containsKey("providers");
            assertThat(response.getBody()).containsKey("models");
            assertThat(response.getBody()).containsKey("sourceTypes");

            verify(creditService).getUsageAnalytics(USER_ID,30, null, null, null, null);
        }

        @Test
        @DisplayName("should pass filters through to service")
        void shouldPassFiltersThroughToService() {
            Map<String, Object> analyticsData = new HashMap<>();
            analyticsData.put("dailyUsage", Collections.emptyList());
            analyticsData.put("providers", Collections.emptyList());
            analyticsData.put("models", Collections.emptyList());
            analyticsData.put("sourceTypes", Collections.emptyList());

            when(creditService.getUsageAnalytics(USER_ID,7, "AGENT_EXECUTION", "openai", "gpt-4", null))
                    .thenReturn(analyticsData);

            ResponseEntity<Map<String, Object>> response =
                    controller.getAnalytics(USER_ID, null,7, "AGENT_EXECUTION", "openai", "gpt-4", false);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();

            verify(creditService).getUsageAnalytics(USER_ID,7, "AGENT_EXECUTION", "openai", "gpt-4", null);
        }

        @Test
        @DisplayName("should return empty analytics when no usage data exists")
        void shouldReturnEmptyAnalytics() {
            Map<String, Object> emptyAnalytics = new HashMap<>();
            emptyAnalytics.put("dailyUsage", Collections.emptyList());
            emptyAnalytics.put("providers", Collections.emptyList());
            emptyAnalytics.put("models", Collections.emptyList());
            emptyAnalytics.put("sourceTypes", Collections.emptyList());

            when(creditService.getUsageAnalytics(USER_ID,90, null, null, null, null))
                    .thenReturn(emptyAnalytics);

            ResponseEntity<Map<String, Object>> response = controller.getAnalytics(USER_ID, null,90, null, null, null, false);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat((List<?>) response.getBody().get("dailyUsage")).isEmpty();
            assertThat((List<?>) response.getBody().get("providers")).isEmpty();

            verify(creditService).getUsageAnalytics(USER_ID,90, null, null, null, null);
        }
    }

    // ---- GET /api/credits/pricing ----

    @Nested
    @DisplayName("GET /api/credits/pricing")
    class PricingTests {

        @Test
        @DisplayName("should return all active pricing from ModelPricingService")
        void shouldReturnAllActivePricing() {
            ModelPricing pricing1 = new ModelPricing();
            pricing1.setProvider("openai");
            pricing1.setModel("gpt-4");
            pricing1.setInputRate(new BigDecimal("0.03"));
            pricing1.setOutputRate(new BigDecimal("0.06"));

            ModelPricing pricing2 = new ModelPricing();
            pricing2.setProvider("anthropic");
            pricing2.setModel("claude-3");
            pricing2.setInputRate(new BigDecimal("0.015"));
            pricing2.setOutputRate(new BigDecimal("0.075"));

            when(pricingService.getAllActivePricing()).thenReturn(List.of(pricing1, pricing2));

            ResponseEntity<List<ModelPricing>> response = controller.getPricing();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody().get(0).getProvider()).isEqualTo("openai");
            assertThat(response.getBody().get(1).getProvider()).isEqualTo("anthropic");

            verify(pricingService).getAllActivePricing();
        }

        @Test
        @DisplayName("should return empty list when no active pricing exists")
        void shouldReturnEmptyListWhenNoPricing() {
            when(pricingService.getAllActivePricing()).thenReturn(Collections.emptyList());

            ResponseEntity<List<ModelPricing>> response = controller.getPricing();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody()).isEmpty();
        }
    }

    // ---- E2E Credit Flow ----

    @Nested
    @DisplayName("E2E Credit Flow")
    class E2ECreditFlow {

        @Test
        @DisplayName("consume then check balance reflects deduction")
        void consumeThenBalanceReflectsDeduction() {
            // Step 1: consume 1 credit for a WORKFLOW_NODE
            var consumeRequest = new CreditController.CreditConsumeRequest(
                    "WORKFLOW_NODE", "run-e2e:node-001", null, null, null, null);
            BigDecimal balanceAfterConsume = new BigDecimal("49.00");
            var consumeResult = CreditConsumeResult.success(BigDecimal.ONE, balanceAfterConsume);

            when(creditService.consumeForWorkflowNode(USER_ID,"run-e2e:node-001"))
                    .thenReturn(consumeResult);

            ResponseEntity<CreditConsumeResult> consumeResponse = controller.consume(USER_ID,consumeRequest);

            assertThat(consumeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(consumeResponse.getBody()).isNotNull();
            assertThat(consumeResponse.getBody().success()).isTrue();
            assertThat(consumeResponse.getBody().remainingCredits()).isEqualByComparingTo(balanceAfterConsume);

            // Step 2: check balance - should match the remaining credits returned by consume.
            // V250: controller now reads getBalanceBreakdown (4-tuple total/sub/payg/delinquent).
            when(creditService.getBalanceBreakdown(USER_ID)).thenReturn(
                new CreditService.BalanceBreakdown(balanceAfterConsume, balanceAfterConsume, BigDecimal.ZERO, false));

            ResponseEntity<Map<String, Object>> balanceResponse = controller.getBalance(USER_ID);

            assertThat(balanceResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(balanceResponse.getBody()).isNotNull();
            assertThat(balanceResponse.getBody()).containsEntry("balance", balanceAfterConsume);

            // Verify both service methods were called once
            verify(creditService).consumeForWorkflowNode(USER_ID,"run-e2e:node-001");
            verify(creditService).getBalanceBreakdown(USER_ID);
        }

        @Test
        @DisplayName("consume then history shows the ledger entry")
        void consumeThenHistoryShowsLedgerEntry() {
            // Step 1: consume for agent execution
            var consumeRequest = new CreditController.CreditConsumeRequest(
                    "AGENT_EXECUTION", "run-e2e-agent-1", "openai", "gpt-4", 500, 200);
            var consumeResult = CreditConsumeResult.success(new BigDecimal("1.50"), new BigDecimal("98.50"));

            when(creditService.consumeForAgent(USER_ID,"run-e2e-agent-1", "openai", "gpt-4", LlmTokenBreakdown.of(500, 200), "AGENT_EXECUTION"))
                    .thenReturn(consumeResult);

            ResponseEntity<CreditConsumeResult> consumeResponse = controller.consume(USER_ID,consumeRequest);

            assertThat(consumeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(consumeResponse.getBody()).isNotNull();
            assertThat(consumeResponse.getBody().creditsUsed()).isEqualByComparingTo(new BigDecimal("1.50"));

            // Step 2: query history - the ledger entry from the consume should appear
            CreditLedgerEntry entry = new CreditLedgerEntry();
            entry.setUserId(USER_ID);
            entry.setSourceType("AGENT_EXECUTION");
            entry.setSourceId("run-e2e-agent-1");
            entry.setAmount(new BigDecimal("-1.50"));
            entry.setBalanceAfter(new BigDecimal("98.50"));
            entry.setProvider("openai");
            entry.setModel("gpt-4");
            entry.setPromptTokens(500);
            entry.setCompletionTokens(200);

            PageRequest pageRequest = PageRequest.of(0, 20, org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
            Page<CreditLedgerEntry> historyPage = new PageImpl<>(List.of(entry));

            when(creditService.getUsageHistory(USER_ID, null, pageRequest)).thenReturn(historyPage);

            ResponseEntity<Page<CreditLedgerEntry>> historyResponse = controller.getHistory(USER_ID,0, 20, null);

            assertThat(historyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(historyResponse.getBody()).isNotNull();
            assertThat(historyResponse.getBody().getContent()).hasSize(1);

            CreditLedgerEntry returnedEntry = historyResponse.getBody().getContent().get(0);
            assertThat(returnedEntry.getSourceType()).isEqualTo("AGENT_EXECUTION");
            assertThat(returnedEntry.getSourceId()).isEqualTo("run-e2e-agent-1");
            assertThat(returnedEntry.getAmount()).isEqualByComparingTo(new BigDecimal("-1.50"));
            assertThat(returnedEntry.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("98.50"));

            // Verify both service methods were called once each
            verify(creditService).consumeForAgent(USER_ID,"run-e2e-agent-1", "openai", "gpt-4", LlmTokenBreakdown.of(500, 200), "AGENT_EXECUTION");
            verify(creditService).getUsageHistory(USER_ID, null, pageRequest);
        }

        @Test
        @DisplayName("WORKFLOW_NODE consume then history filtered by WORKFLOW_NODE shows the entry")
        void workflowNodeConsumeThenHistoryFilteredByType() {
            // Step 1: consume a workflow node credit
            var consumeRequest = new CreditController.CreditConsumeRequest(
                    "WORKFLOW_NODE", "run-e2e-2:node-007", null, null, null, null);
            var consumeResult = CreditConsumeResult.success(BigDecimal.ONE, new BigDecimal("99.00"));

            when(creditService.consumeForWorkflowNode(USER_ID,"run-e2e-2:node-007"))
                    .thenReturn(consumeResult);

            ResponseEntity<CreditConsumeResult> consumeResponse = controller.consume(USER_ID,consumeRequest);
            assertThat(consumeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

            // Step 2: query history filtered by WORKFLOW_NODE
            CreditLedgerEntry entry = new CreditLedgerEntry();
            entry.setUserId(USER_ID);
            entry.setSourceType("WORKFLOW_NODE");
            entry.setSourceId("run-e2e-2:node-007");
            entry.setAmount(new BigDecimal("-1"));
            entry.setBalanceAfter(new BigDecimal("99.00"));

            PageRequest pageRequest = PageRequest.of(0, 20, org.springframework.data.domain.Sort.by(
                    org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
            Page<CreditLedgerEntry> historyPage = new PageImpl<>(List.of(entry));

            when(creditService.getUsageHistoryByType(USER_ID, "WORKFLOW_NODE", null, pageRequest))
                    .thenReturn(historyPage);

            ResponseEntity<Page<CreditLedgerEntry>> historyResponse =
                    controller.getHistory(USER_ID,0, 20, "WORKFLOW_NODE");

            assertThat(historyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(historyResponse.getBody()).isNotNull();
            assertThat(historyResponse.getBody().getContent()).hasSize(1);
            assertThat(historyResponse.getBody().getContent().get(0).getSourceType()).isEqualTo("WORKFLOW_NODE");

            verify(creditService).consumeForWorkflowNode(USER_ID,"run-e2e-2:node-007");
            verify(creditService).getUsageHistoryByType(USER_ID, "WORKFLOW_NODE", null, pageRequest);
        }
    }
}

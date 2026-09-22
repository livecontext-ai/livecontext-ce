package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.ClassifyRequestDto;
import com.apimarketplace.agent.client.dto.execution.ClassifyResponseDto;
import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.loop.PreIterationGuard;
import com.apimarketplace.agent.service.budget.GuardChainFactory;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ClassifyService.
 *
 * Key invariants verified:
 * 1. Delegates to AgentLoopService in single-shot mode (tools=null, maxIterations=1).
 * 2. Prompt/completion token split is propagated from AgentLoopResult.
 * 3. Budget guards are built via GuardChainFactory.
 * 4. JSON parsing, plain-text fallback, and confidence clamping work correctly.
 * 5. AgentLoopService failures are mapped to error DTOs.
 */
@DisplayName("ClassifyService")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClassifyServiceTest {

    @Mock
    private AgentLoopService agentLoopService;

    @Mock
    private GuardChainFactory guardChainFactory;

    @Mock
    private BridgeLoopDispatcher bridgeDispatcher;

    @Mock
    private com.apimarketplace.agent.service.ModelCatalogService modelCatalogService;

    @Mock
    private ExecutionLinkRouter executionLinkRouter;

    /**
     * The decision engine. Every test in this class exercises the LLM path, so it is left
     * unstubbed: {@code supports(...)} then answers false and the service routes as before.
     */
    @Mock
    private TypeSafeSystemOneClient typeSafeClient;

    private ClassifyService service;

    private static final List<ClassifyRequestDto.CategoryDto> CATEGORIES = List.of(
        new ClassifyRequestDto.CategoryDto("billing", "Billing-related issues"),
        new ClassifyRequestDto.CategoryDto("support", "Technical support requests"),
        new ClassifyRequestDto.CategoryDto("spam", "Unwanted or irrelevant messages")
    );

    @BeforeEach
    void setUp() {
        service = new ClassifyService(agentLoopService, guardChainFactory, new ObjectMapper(), bridgeDispatcher, modelCatalogService, executionLinkRouter, typeSafeClient);
        when(guardChainFactory.forAgent(any(), any(), any(), any())).thenReturn(PreIterationGuard.ALWAYS_PROCEED);
        // Default: non-bridge provider routing (tests opt into bridge path explicitly)
        when(bridgeDispatcher.shouldDispatch(any())).thenReturn(false);
        // Default: provider resolution is a no-op pass-through (returns the
        // caller's provider) so existing assertions stay unchanged.
        when(modelCatalogService.resolveProvider(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        // Default: the billed pair is not linked, so every pre-existing expectation
        // (loop runs on the requested provider/model) holds unchanged.
        when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(null);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Happy path - clean JSON
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Happy path - clean JSON response")
    class HappyPath {

        @Test
        @DisplayName("full JSON response is parsed correctly")
        void fullJsonResponse() {
            String json = "{\"selected_category\":\"billing\",\"confidence\":0.97,\"reasoning\":\"Invoice mentioned\"}";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 120, 80, 40));

            ClassifyResponseDto result = service.execute(request("My invoice is wrong"));

            assertThat(result.success()).isTrue();
            assertThat(result.selectedCategory()).isEqualTo("billing");
            assertThat(result.confidence()).isEqualTo(0.97);
            assertThat(result.reasoning()).isEqualTo("Invoice mentioned");
            assertThat(result.tokensUsed()).isEqualTo(120);
            assertThat(result.promptTokens()).isEqualTo(80);
            assertThat(result.completionTokens()).isEqualTo(40);
            assertThat(result.error()).isNull();
        }

        @Test
        @DisplayName("JSON wrapped in markdown fence is parsed correctly")
        void jsonInMarkdownFence() {
            String json = "```json\n{\"selected_category\":\"spam\",\"confidence\":0.85,\"reasoning\":\"no value\"}\n```";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 60, 40, 20));

            ClassifyResponseDto result = service.execute(request("Buy now!"));

            assertThat(result.success()).isTrue();
            assertThat(result.selectedCategory()).isEqualTo("spam");
            assertThat(result.tokensUsed()).isEqualTo(60);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  AgentLoopService integration - single-shot mode
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AgentLoopService integration")
    class AgentLoopIntegration {

        @Test
        @DisplayName("context is built with tools=null, maxIterations=1, autoDiscoverTools=false")
        void singleShotContext() {
            when(agentLoopService.execute(any(), isNull())).thenReturn(
                loopResult("{\"selected_category\":\"billing\",\"confidence\":0.9,\"reasoning\":\"ok\"}", 100, 70, 30));

            service.execute(request("test"));

            ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(captor.capture(), isNull());

            AgentLoopContext ctx = captor.getValue();
            assertThat(ctx.tools()).isNull();
            assertThat(ctx.maxIterations()).isEqualTo(1);
            assertThat(ctx.isAutoDiscoverEnabled()).isFalse();
            assertThat(ctx.systemPrompt()).isEqualTo(ClassifyService.SYSTEM_PROMPT);
            assertThat(ctx.provider()).isEqualTo("openai");
            assertThat(ctx.getPurposeOrDefault())
                .as("CLASSIFY must bypass the centralized MAIN pipeline")
                .isEqualTo(com.apimarketplace.agent.loop.CallPurpose.CLASSIFY);
            assertThat(ctx.isMainPurpose()).isFalse();
        }

        @Test
        @DisplayName("guard chain is built via GuardChainFactory with tenantId and agentEntityId")
        void guardChainBuilt() {
            when(agentLoopService.execute(any(), isNull())).thenReturn(
                loopResult("{\"selected_category\":\"billing\",\"confidence\":0.9,\"reasoning\":\"ok\"}", 100, 70, 30));

            ClassifyRequestDto req = new ClassifyRequestDto(
                "test", null, CATEGORIES, "openai", null, null, null, "tenant-1", "agent-123");
            service.execute(req);

            verify(guardChainFactory).forAgent("tenant-1", "agent-123", "openai", null);
        }

        @Test
        @DisplayName("callback is null (no streaming)")
        void callbackIsNull() {
            when(agentLoopService.execute(any(), isNull())).thenReturn(
                loopResult("{\"selected_category\":\"billing\",\"confidence\":0.9,\"reasoning\":\"ok\"}", 100, 70, 30));

            service.execute(request("test"));

            verify(agentLoopService).execute(any(), (StreamingCallback) isNull());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Token count preservation - prompt/completion split
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Token count preservation - prompt/completion split")
    class TokenCountPreservation {

        @Test
        @DisplayName("prompt and completion tokens preserved on success")
        void tokensPreservedOnSuccess() {
            String json = "{\"selected_category\":\"billing\",\"confidence\":0.9,\"reasoning\":\"ok\"}";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 250, 200, 50));

            ClassifyResponseDto result = service.execute(request("test"));

            assertThat(result.tokensUsed()).isEqualTo(250);
            assertThat(result.promptTokens()).isEqualTo(200);
            assertThat(result.completionTokens()).isEqualTo(50);
        }

        @Test
        @DisplayName("tokens preserved when JSON is invalid - falls back to plain text")
        void tokensPreservedOnJsonParseFailure() {
            String brokenJson = "I think the category: billing for sure";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(brokenJson, 350, 300, 50));

            ClassifyResponseDto result = service.execute(request("invoice problem"));

            assertThat(result.tokensUsed()).isEqualTo(350);
            assertThat(result.promptTokens()).isEqualTo(300);
            assertThat(result.completionTokens()).isEqualTo(50);
        }

        @Test
        @DisplayName("tokens preserved when response is empty string")
        void tokensPreservedOnEmptyResponse() {
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult("", 50, 45, 5));

            ClassifyResponseDto result = service.execute(request("test"));

            assertThat(result.success()).isFalse();
            assertThat(result.tokensUsed()).isEqualTo(50);
            assertThat(result.promptTokens()).isEqualTo(45);
            assertThat(result.completionTokens()).isEqualTo(5);
        }

        @Test
        @DisplayName("zero tokens when usage is null")
        void zeroTokensWhenUsageNull() {
            AgentLoopResult result = AgentLoopResult.builder()
                .success(true)
                .content("{\"selected_category\":\"support\",\"confidence\":0.7,\"reasoning\":\"tech\"}")
                .provider("openai").model("gpt-4o")
                .build();
            when(agentLoopService.execute(any(), isNull())).thenReturn(result);

            ClassifyResponseDto dto = service.execute(request("test"));

            assertThat(dto.tokensUsed()).isEqualTo(0);
            assertThat(dto.promptTokens()).isEqualTo(0);
            assertThat(dto.completionTokens()).isEqualTo(0);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Confidence clamping
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Confidence clamping to [0.0, 1.0]")
    class ConfidenceClamping {

        @Test
        @DisplayName("confidence above 1.0 is clamped to 1.0")
        void confidenceAboveOneIsClamped() {
            String json = "{\"selected_category\":\"billing\",\"confidence\":1.5,\"reasoning\":\"sure\"}";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 100, 70, 30));

            assertThat(service.execute(request("test")).confidence()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("negative confidence is clamped to 0.0")
        void negativeConfidenceIsClamped() {
            String json = "{\"selected_category\":\"billing\",\"confidence\":-0.3,\"reasoning\":\"unsure\"}";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 100, 70, 30));

            assertThat(service.execute(request("test")).confidence()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("null confidence defaults to 0.5")
        void nullConfidenceDefaultsToHalf() {
            String json = "{\"selected_category\":\"billing\",\"reasoning\":\"no confidence field\"}";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 100, 70, 30));

            assertThat(service.execute(request("test")).confidence()).isEqualTo(0.5);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Plain-text fallback
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Plain-text fallback extraction")
    class PlainTextFallback {

        @Test
        @DisplayName("category extracted from 'category: X' pattern")
        void categoryKeywordExtraction() {
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult("category: billing", 30, 20, 10));

            ClassifyResponseDto result = service.execute(request("invoice"));

            assertThat(result.success()).isTrue();
            assertThat(result.selectedCategory()).isEqualTo("billing");
            assertThat(result.confidence()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("completely unrecognizable response returns failure")
        void unrecognizableResponse() {
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult("I cannot determine.", 20, 15, 5));

            ClassifyResponseDto result = service.execute(request("test"));

            assertThat(result.success()).isFalse();
            assertThat(result.selectedCategory()).isNull();
            assertThat(result.tokensUsed()).isEqualTo(20);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Missing required field in JSON
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Missing required fields in JSON response")
    class MissingRequiredFields {

        @Test
        @DisplayName("missing selected_category returns failure")
        void missingSelectedCategory() {
            String json = "{\"confidence\":0.9,\"reasoning\":\"no category\"}";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 80, 60, 20));

            ClassifyResponseDto result = service.execute(request("test"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("No category selected");
            assertThat(result.tokensUsed()).isEqualTo(80);
        }

        @Test
        @DisplayName("empty selected_category string returns failure")
        void emptySelectedCategory() {
            String json = "{\"selected_category\":\"\",\"confidence\":0.9}";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 80, 60, 20));

            assertThat(service.execute(request("test")).success()).isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  AgentLoopService failure
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AgentLoopService failure handling")
    class LoopFailure {

        @Test
        @DisplayName("loop failure maps to error DTO with tokens preserved")
        void loopFailure() {
            AgentLoopResult failure = AgentLoopResult.failure("Budget exhausted", 200, "openai",
                AgentStopReason.BUDGET_EXHAUSTED);
            when(agentLoopService.execute(any(), isNull())).thenReturn(failure);

            ClassifyResponseDto result = service.execute(request("test"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Budget exhausted");
        }

        @Test
        @DisplayName("exception in execute is caught and returns error DTO")
        void exceptionInExecute() {
            when(agentLoopService.execute(any(), isNull())).thenThrow(new RuntimeException("Network timeout"));

            ClassifyResponseDto result = service.execute(request("test"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Network timeout");
            assertThat(result.tokensUsed()).isEqualTo(0);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Bridge provider routing (claude-code, codex, gemini-cli, mistral-vibe)
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Bridge provider routing")
    class BridgeRouting {

        @Test
        @DisplayName("claude-code provider dispatches to bridge, NOT agent loop")
        void claudeCodeRoutesToBridge() {
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            String json = "{\"selected_category\":\"billing\",\"confidence\":0.9,\"reasoning\":\"via bridge\"}";
            when(bridgeDispatcher.execute(any(), anyBoolean())).thenReturn(loopResult(json, 100, 60, 40));

            ClassifyRequestDto req = new ClassifyRequestDto(
                "invoice", null, CATEGORIES, "claude-code", null, null, null, "tenant-1", "agent-1");
            ClassifyResponseDto result = service.execute(req);

            assertThat(result.success()).isTrue();
            assertThat(result.selectedCategory()).isEqualTo("billing");
            verify(bridgeDispatcher).execute(any(), anyBoolean());
            verify(agentLoopService, never()).execute(any(), any());
        }

        @Test
        @DisplayName("F25 regression: a bridge model mislabelled 'anthropic' is normalised to claude-code and dispatched via the bridge")
        void mislabelledBridgeModelNormalisedToBridge() {
            // The bridge-only model claude-opus-4-7 was stored with
            // provider="anthropic"; the catalog resolver corrects it to
            // claude-code, which MUST dispatch via the bridge (not the direct API).
            when(modelCatalogService.resolveProvider("anthropic", "claude-opus-4-7")).thenReturn("claude-code");
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            String json = "{\"selected_category\":\"billing\",\"confidence\":0.9,\"reasoning\":\"via bridge\"}";
            org.mockito.ArgumentCaptor<com.apimarketplace.agent.loop.AgentLoopContext> ctx =
                org.mockito.ArgumentCaptor.forClass(com.apimarketplace.agent.loop.AgentLoopContext.class);
            when(bridgeDispatcher.execute(ctx.capture(), anyBoolean())).thenReturn(loopResult(json, 100, 60, 40));

            ClassifyRequestDto req = new ClassifyRequestDto(
                "invoice", null, CATEGORIES, "anthropic", "claude-opus-4-7", null, null, "tenant-1", "agent-1");
            ClassifyResponseDto result = service.execute(req);

            assertThat(result.success()).isTrue();
            verify(bridgeDispatcher).execute(any(), anyBoolean());
            verify(agentLoopService, never()).execute(any(), any());
            // The corrected slug propagated into the dispatched context, and the
            // stale 'anthropic' slug was never asked about.
            assertThat(ctx.getValue().provider()).isEqualTo("claude-code");
            verify(bridgeDispatcher).shouldDispatch("claude-code");
            verify(bridgeDispatcher, never()).shouldDispatch("anthropic");
        }

        @Test
        @DisplayName("openai provider keeps using agent loop (not bridge)")
        void openaiStaysOnAgentLoop() {
            when(bridgeDispatcher.shouldDispatch("openai")).thenReturn(false);
            when(agentLoopService.execute(any(), isNull())).thenReturn(
                loopResult("{\"selected_category\":\"billing\",\"confidence\":0.9,\"reasoning\":\"ok\"}", 100, 70, 30));

            service.execute(request("test"));

            verify(agentLoopService).execute(any(), isNull());
            verify(bridgeDispatcher, never()).execute(any(), anyBoolean());
        }

        @Test
        @DisplayName("bridge failure surfaces as error DTO with tokens preserved")
        void bridgeFailureMapsToError() {
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            AgentLoopResult failure = AgentLoopResult.failure(
                "Bridge execution failed: no response from bridge server",
                50, "claude-code", AgentStopReason.ERROR);
            when(bridgeDispatcher.execute(any(), anyBoolean())).thenReturn(failure);

            ClassifyRequestDto req = new ClassifyRequestDto(
                "test", null, CATEGORIES, "claude-code", null, null, null, null, null);
            ClassifyResponseDto result = service.execute(req);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Bridge execution failed");
        }

        @Test
        @DisplayName("EXECUTION-LINK FALLBACK: a linked bridge failure silently retries on the billed pair's direct API and succeeds")
        void linkedBridgeFailureFallsBackToDirectApi() {
            when(executionLinkRouter.runnableRoute("openai", "gpt-4o", ClassifyService.ACTIVITY_SOURCE))
                .thenReturn(new com.apimarketplace.agent.service.ModelExecutionLinkService.ExecutionRoute(
                    "claude-code", "claude-opus-4-8"));
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            when(bridgeDispatcher.execute(any(), anyBoolean())).thenReturn(
                AgentLoopResult.failure("CLI crashed", 50, "claude-code", AgentStopReason.ERROR));
            String json = "{\"selected_category\":\"billing\",\"confidence\":0.9,\"reasoning\":\"fallback ok\"}";
            org.mockito.ArgumentCaptor<AgentLoopContext> ctx = org.mockito.ArgumentCaptor.forClass(AgentLoopContext.class);
            when(agentLoopService.execute(ctx.capture(), isNull())).thenReturn(loopResult(json, 100, 60, 40));

            ClassifyRequestDto req = new ClassifyRequestDto(
                "invoice", null, CATEGORIES, "openai", "gpt-4o", null, null, "tenant-1", "agent-1");
            ClassifyResponseDto result = service.execute(req);

            // The fallback ran on the BILLED pair, never on claude-code.
            assertThat(ctx.getValue().provider()).isEqualTo("openai");
            assertThat(ctx.getValue().model()).isEqualTo("gpt-4o");
            // No restricted-toolset marker travels into a direct-API call.
            assertThat(ctx.getValue().credentials()).isNull();
            // Invisible to the caller: the failed bridge attempt never surfaces.
            assertThat(result.success()).isTrue();
            assertThat(result.selectedCategory()).isEqualTo("billing");
            assertThat(result.provider()).isEqualTo("openai");
        }

        @Test
        @DisplayName("EXECUTION-LINK FALLBACK: when the direct-API retry ALSO fails, the error surfaces normally (a single retry, never a loop)")
        void linkedBridgeFailureFallbackAlsoFailsSurfacesError() {
            when(executionLinkRouter.runnableRoute("openai", "gpt-4o", ClassifyService.ACTIVITY_SOURCE))
                .thenReturn(new com.apimarketplace.agent.service.ModelExecutionLinkService.ExecutionRoute(
                    "claude-code", "claude-opus-4-8"));
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            when(bridgeDispatcher.execute(any(), anyBoolean())).thenReturn(
                AgentLoopResult.failure("CLI crashed", 50, "claude-code", AgentStopReason.ERROR));
            when(agentLoopService.execute(any(), isNull())).thenReturn(
                AgentLoopResult.failure("upstream 500", 10, "openai", AgentStopReason.ERROR));

            ClassifyRequestDto req = new ClassifyRequestDto(
                "invoice", null, CATEGORIES, "openai", "gpt-4o", null, null, "tenant-1", "agent-1");
            ClassifyResponseDto result = service.execute(req);

            verify(bridgeDispatcher, org.mockito.Mockito.times(1)).execute(any(), anyBoolean());
            verify(agentLoopService, org.mockito.Mockito.times(1)).execute(any(), isNull());
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("upstream 500");
        }

        @Test
        @DisplayName("EXECUTION-LINK FALLBACK: records a Prometheus fallback counter for operators")
        void linkedBridgeFailureRecordsPrometheusMetric() {
            com.apimarketplace.agent.metrics.AgentPrometheusMetrics metrics =
                org.mockito.Mockito.mock(com.apimarketplace.agent.metrics.AgentPrometheusMetrics.class);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "prometheusMetrics", metrics);
            when(executionLinkRouter.runnableRoute("openai", "gpt-4o", ClassifyService.ACTIVITY_SOURCE))
                .thenReturn(new com.apimarketplace.agent.service.ModelExecutionLinkService.ExecutionRoute(
                    "claude-code", "claude-opus-4-8"));
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            when(bridgeDispatcher.execute(any(), anyBoolean())).thenReturn(
                AgentLoopResult.failure("CLI crashed", 50, "claude-code", AgentStopReason.ERROR));
            when(agentLoopService.execute(any(), isNull())).thenReturn(
                loopResult("{\"selected_category\":\"billing\",\"confidence\":0.9,\"reasoning\":\"ok\"}", 100, 60, 40));

            ClassifyRequestDto req = new ClassifyRequestDto(
                "invoice", null, CATEGORIES, "openai", "gpt-4o", null, null, "tenant-1", "agent-1");
            service.execute(req);

            verify(metrics).recordExecutionLinkFallback("openai", "gpt-4o", "claude-code");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Default provider fallback
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("null provider in request stays null - AgentLoopService handles resolution")
    void nullProviderPassedThrough() {
        String json = "{\"selected_category\":\"billing\",\"confidence\":0.9,\"reasoning\":\"ok\"}";
        when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 100, 70, 30));

        ClassifyRequestDto req = new ClassifyRequestDto("test", null, CATEGORIES, null, null, null, null, null, null);
        ClassifyResponseDto result = service.execute(req);

        assertThat(result.provider()).isNull();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private ClassifyRequestDto request(String content) {
        return new ClassifyRequestDto(content, null, CATEGORIES, "openai", null, null, null, null, null);
    }

    private AgentLoopResult loopResult(String content, int total, int prompt, int completion) {
        UsageInfo usage = UsageInfo.builder()
            .promptTokens(prompt)
            .completionTokens(completion)
            .totalTokens(total)
            .build();
        CompletionResponse response = CompletionResponse.builder()
            .content(content).finishReason("stop").usage(usage).build();
        return AgentLoopResult.builder()
            .success(true)
            .content(content)
            .response(response)
            .usage(usage)
            .provider("openai")
            .model("gpt-4o")
            .iterations(1)
            .durationMs(100)
            .stopReason(AgentStopReason.COMPLETED)
            .build();
    }

    /**
     * A classify turn is billed from the response DTO alone, so whatever the DTO does not
     * carry is not charged. It used to carry prompt and completion only: over a model
     * execution link that meant the bridge's INCLUSIVE prompt total under the billed
     * provider's label with no cache line, charging the whole context at full input rate
     * (6.1x its cost, measured), and without a link it meant the cache was free. Both are
     * the same missing transport, and these tests are what stops it going missing again.
     */
    @Nested
    @DisplayName("The cache counters reach the bill")
    class CacheCountersTravel {

        @org.junit.jupiter.api.BeforeEach
        void billedPairIsAnthropic() {
            // The request names anthropic, and the catalog resolves it to itself. Without
            // this the billed provider is null, which is subset-shaped, and the conversion
            // under test would be the wrong one - exactly the kind of fixture that makes a
            // convention test pass for a reason that has nothing to do with the code.
            when(modelCatalogService.resolveProvider(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        }

        private ClassifyRequestDto anthropicRequest() {
            return new ClassifyRequestDto("My invoice is wrong", null, CATEGORIES,
                "anthropic", "claude-fable-5", null, null, null, null);
        }

        /** The Claude Code bridge shape: the prompt total already contains the cache. */
        private AgentLoopResult bridgeReported() {
            return AgentLoopResult.builder()
                .success(true)
                .content("{\"selected_category\":\"billing\",\"confidence\":0.9}")
                .model("claude-fable-5")
                .usage(UsageInfo.builder()
                    .promptTokens(6 + 18_945 + 79_368)
                    .completionTokens(1_915)
                    .totalTokens(6 + 18_945 + 79_368 + 1_915)
                    .cacheCreationInputTokens(18_945)
                    .cacheReadInputTokens(79_368)
                    .build())
                .build();
        }

        @Test
        @DisplayName("an unlinked run carries its cache counters, so the cached part stops being free")
        void unlinkedRunCarriesTheCache() {
            when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(null);
            when(agentLoopService.execute(any(), any())).thenReturn(
                AgentLoopResult.builder()
                    .success(true)
                    .content("{\"selected_category\":\"billing\",\"confidence\":0.9}")
                    .model("claude-fable-5")
                    .usage(UsageInfo.builder()
                        .promptTokens(6).completionTokens(1_915).totalTokens(1_921)
                        .cacheCreationInputTokens(18_945).cacheReadInputTokens(79_368).build())
                    .build());

            ClassifyResponseDto response = service.execute(anthropicRequest());

            assertThat(response.cacheUsage()).isNotNull();
            assertThat(response.cacheUsage().cacheCreationInputTokens()).isEqualTo(18_945);
            assertThat(response.cacheUsage().cacheReadInputTokens()).isEqualTo(79_368);
            // Unlinked, nothing to convert: the API already reports plain input.
            assertThat(response.promptTokens()).isEqualTo(6);
        }

        @Test
        @DisplayName("a run moved onto a bridge by a link reports PLAIN input and its cache beside it, the convention the billed provider is read with")
        void linkedRunIsConvertedAndCarried() {
            when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(
                new com.apimarketplace.agent.service.ModelExecutionLinkService.ExecutionRoute("claude-code", "claude-fable-5"));
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            when(bridgeDispatcher.execute(any(), org.mockito.ArgumentMatchers.eq(true))).thenReturn(bridgeReported());

            ClassifyResponseDto response = service.execute(anthropicRequest());

            // 98,319 stripped back to the 6 tokens of plain input...
            assertThat(response.promptTokens()).isEqualTo(6);
            // ...and the cache carried on its own line, where the cache rate applies.
            assertThat(response.cacheUsage()).isNotNull();
            assertThat(response.cacheUsage().cacheReadInputTokens()).isEqualTo(79_368);
            assertThat(response.cacheUsage().cacheCreationInputTokens()).isEqualTo(18_945);
        }

        @Test
        @DisplayName("stripping without carrying would have been worse, and this is the assertion that says so")
        void strippingAloneWouldHaveDeletedTheCache() {
            // Pinned as a statement of the trade: the prompt IS reduced, so if cacheUsage
            // were ever dropped from the DTO again the cache would leave the bill entirely
            // rather than merely be over-charged. The two assertions must move together.
            when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(
                new com.apimarketplace.agent.service.ModelExecutionLinkService.ExecutionRoute("claude-code", "claude-fable-5"));
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            when(bridgeDispatcher.execute(any(), org.mockito.ArgumentMatchers.eq(true))).thenReturn(bridgeReported());

            ClassifyResponseDto response = service.execute(anthropicRequest());

            assertThat(response.promptTokens()).isLessThan(98_319);
            assertThat(response.cacheUsage()).as("the cache must have somewhere to go").isNotNull();
        }

        @Test
        @DisplayName("a turn that burned the context and then failed to classify still reports what it burned")
        void aFailedClassificationStillCarriesWhatItSpent() {
            // The tokens were spent whether or not a category came back, so the failure DTO
            // carries the same counters as the success one. Only the ASYNC consumer spends
            // them: the inline path builds ClassifyResult.failure(), which zeroes every
            // count including this one. That asymmetry predates this change (it zeroes the
            // prompt and completion too) and is left alone here rather than widened.
            when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(null);
            when(agentLoopService.execute(any(), any())).thenReturn(
                AgentLoopResult.builder()
                    .success(true)
                    .content("I could not decide.")
                    .model("claude-fable-5")
                    .usage(UsageInfo.builder()
                        .promptTokens(6).completionTokens(1_915).totalTokens(1_921)
                        .cacheCreationInputTokens(18_945).cacheReadInputTokens(79_368).build())
                    .build());

            ClassifyResponseDto response = service.execute(request("My invoice is wrong"));

            assertThat(response.success()).as("no category could be extracted").isFalse();
            assertThat(response.cacheUsage()).isNotNull();
            assertThat(response.cacheUsage().cacheReadInputTokens()).isEqualTo(79_368);
            assertThat(response.cacheUsage().cacheCreationInputTokens()).isEqualTo(18_945);
        }

        @Test
        @DisplayName("a provider that reports no counts yields no cache, and the classification still returns")
        void noUsageIsNotAFailure() {
            when(executionLinkRouter.runnableRoute(any(), any(), any())).thenReturn(null);
            when(agentLoopService.execute(any(), any())).thenReturn(
                AgentLoopResult.builder()
                    .success(true)
                    .content("{\"selected_category\":\"billing\",\"confidence\":0.9}")
                    .model("claude-fable-5")
                    .build());

            ClassifyResponseDto response = service.execute(anthropicRequest());

            assertThat(response.success()).isTrue();
            assertThat(response.cacheUsage()).isNull();
        }
    }
}

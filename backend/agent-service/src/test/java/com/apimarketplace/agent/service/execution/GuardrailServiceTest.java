package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.GuardrailRequestDto;
import com.apimarketplace.agent.client.dto.execution.GuardrailResponseDto;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for GuardrailService.
 *
 * Key invariants verified:
 * 1. Delegates to AgentLoopService in single-shot mode (tools=null, maxIterations=1).
 * 2. Prompt/completion token split is propagated from AgentLoopResult.
 * 3. Budget guards are built via GuardChainFactory.
 * 4. "passed" derivation, redact action synthesis, plain-text fallback.
 * 5. AgentLoopService failures are mapped to error DTOs.
 */
@DisplayName("GuardrailService")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GuardrailServiceTest {

    @Mock
    private AgentLoopService agentLoopService;

    @Mock
    private GuardChainFactory guardChainFactory;

    @Mock
    private BridgeLoopDispatcher bridgeDispatcher;

    @Mock
    private com.apimarketplace.agent.service.ModelCatalogService modelCatalogService;

    private GuardrailService service;

    private static final List<GuardrailRequestDto.RuleDto> RULES = List.of(
        new GuardrailRequestDto.RuleDto("hate_speech", "Hateful content targeting a group"),
        new GuardrailRequestDto.RuleDto("pii", "Personal identifiable information"),
        new GuardrailRequestDto.RuleDto("profanity", "Explicit language")
    );

    @BeforeEach
    void setUp() {
        service = new GuardrailService(agentLoopService, guardChainFactory, new ObjectMapper(), bridgeDispatcher, modelCatalogService);
        when(guardChainFactory.forAgent(any(), any(), any(), any())).thenReturn(PreIterationGuard.ALWAYS_PROCEED);
        when(bridgeDispatcher.shouldDispatch(any())).thenReturn(false);
        // Default: provider resolution is a no-op pass-through (returns the
        // caller's provider) so existing assertions stay unchanged. Bridge-routing
        // tests override this to assert the normalised slug.
        when(modelCatalogService.resolveProvider(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Happy path - clean JSON
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Happy path - clean JSON response")
    class HappyPath {

        @Test
        @DisplayName("passed response with no violations")
        void passedNoViolations() {
            String json = """
                {"passed":true,"violations":[],"details":{"hate_speech":{"violated":false,"severity":"low","explanation":"OK","matched_content":""}},"sanitized":null}
                """;
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 200, 160, 40));

            GuardrailResponseDto result = service.execute(request("Safe content", "flag"));

            assertThat(result.success()).isTrue();
            assertThat(result.passed()).isTrue();
            assertThat(result.violations()).isEmpty();
            assertThat(result.tokensUsed()).isEqualTo(200);
            assertThat(result.promptTokens()).isEqualTo(160);
            assertThat(result.completionTokens()).isEqualTo(40);
            assertThat(result.error()).isNull();
        }

        @Test
        @DisplayName("failed response with violations list")
        void failedWithViolations() {
            String json = """
                {"passed":false,"violations":["hate_speech","profanity"],"details":{},"sanitized":"[REDACTED] content"}
                """;
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 300, 240, 60));

            GuardrailResponseDto result = service.execute(request("Bad content", "block"));

            assertThat(result.success()).isTrue();
            assertThat(result.passed()).isFalse();
            assertThat(result.violations()).containsExactlyInAnyOrder("hate_speech", "profanity");
            assertThat(result.sanitized()).isEqualTo("[REDACTED] content");
            assertThat(result.tokensUsed()).isEqualTo(300);
            assertThat(result.promptTokens()).isEqualTo(240);
            assertThat(result.completionTokens()).isEqualTo(60);
        }

        @Test
        @DisplayName("JSON in markdown fence is parsed correctly")
        void jsonInMarkdownFence() {
            String json = "```json\n{\"passed\":true,\"violations\":[],\"details\":{},\"sanitized\":null}\n```";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 150, 120, 30));

            GuardrailResponseDto result = service.execute(request("ok text", "flag"));

            assertThat(result.success()).isTrue();
            assertThat(result.passed()).isTrue();
            assertThat(result.tokensUsed()).isEqualTo(150);
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
                loopResult("{\"passed\":true,\"violations\":[],\"details\":{},\"sanitized\":null}", 100, 70, 30));

            service.execute(request("test", "flag"));

            ArgumentCaptor<AgentLoopContext> captor = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(agentLoopService).execute(captor.capture(), isNull());

            AgentLoopContext ctx = captor.getValue();
            assertThat(ctx.tools()).isNull();
            assertThat(ctx.maxIterations()).isEqualTo(1);
            assertThat(ctx.isAutoDiscoverEnabled()).isFalse();
            assertThat(ctx.systemPrompt()).isEqualTo(GuardrailService.SYSTEM_PROMPT);
            assertThat(ctx.getPurposeOrDefault())
                .as("GUARDRAIL must bypass the centralized MAIN pipeline")
                .isEqualTo(com.apimarketplace.agent.loop.CallPurpose.GUARDRAIL);
            assertThat(ctx.isMainPurpose()).isFalse();
        }

        @Test
        @DisplayName("guard chain is built via GuardChainFactory")
        void guardChainBuilt() {
            when(agentLoopService.execute(any(), isNull())).thenReturn(
                loopResult("{\"passed\":true,\"violations\":[],\"details\":{},\"sanitized\":null}", 100, 70, 30));

            GuardrailRequestDto req = new GuardrailRequestDto(
                "test", null, RULES, "flag", "openai", null, null, null, "tenant-1", "agent-456");
            service.execute(req);

            verify(guardChainFactory).forAgent("tenant-1", "agent-456", "openai", null);
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
            String json = "{\"passed\":true,\"violations\":[],\"details\":{},\"sanitized\":null}";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 180, 140, 40));

            GuardrailResponseDto result = service.execute(request("ok", "flag"));

            assertThat(result.tokensUsed()).isEqualTo(180);
            assertThat(result.promptTokens()).isEqualTo(140);
            assertThat(result.completionTokens()).isEqualTo(40);
        }

        @Test
        @DisplayName("tokens preserved when JSON is invalid - plain-text fallback")
        void tokensPreservedOnJsonParseFailure() {
            String broken = "The content does not violate any of the specified rules.";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(broken, 400, 350, 50));

            GuardrailResponseDto result = service.execute(request("test", "flag"));

            assertThat(result.tokensUsed()).isEqualTo(400);
            assertThat(result.promptTokens()).isEqualTo(350);
            assertThat(result.completionTokens()).isEqualTo(50);
        }

        @Test
        @DisplayName("tokens preserved on empty LLM response")
        void tokensPreservedOnEmptyResponse() {
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult("", 25, 20, 5));

            GuardrailResponseDto result = service.execute(request("test", "flag"));

            assertThat(result.success()).isFalse();
            assertThat(result.tokensUsed()).isEqualTo(25);
            assertThat(result.promptTokens()).isEqualTo(20);
        }

        @Test
        @DisplayName("zero tokens when usage is null")
        void zeroTokensWhenUsageNull() {
            AgentLoopResult result = AgentLoopResult.builder()
                .success(true)
                .content("{\"passed\":true,\"violations\":[],\"details\":{},\"sanitized\":null}")
                .provider("openai").model("gpt-4o")
                .build();
            when(agentLoopService.execute(any(), isNull())).thenReturn(result);

            GuardrailResponseDto dto = service.execute(request("test", "flag"));

            assertThat(dto.tokensUsed()).isEqualTo(0);
            assertThat(dto.promptTokens()).isEqualTo(0);
            assertThat(dto.completionTokens()).isEqualTo(0);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  "passed" field derivation when absent from JSON
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("'passed' derivation when absent from LLM response")
    class PassedDerivation {

        @Test
        @DisplayName("no violations → passed=true when 'passed' field absent")
        void noViolationsImpliesPassed() {
            String json = "{\"violations\":[],\"details\":{}}";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 100, 70, 30));

            assertThat(service.execute(request("ok", "flag")).passed()).isTrue();
        }

        @Test
        @DisplayName("violations present → passed=false when 'passed' field absent")
        void violationsImplyFailed() {
            String json = "{\"violations\":[\"pii\"],\"details\":{}}";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 100, 70, 30));

            assertThat(service.execute(request("my ssn is 123", "flag")).passed()).isFalse();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Redact action - sanitized fallback synthesis
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Redact action - sanitized value synthesis")
    class RedactAction {

        @Test
        @DisplayName("passed content gets original sanitized value on redact action")
        void passedContentOnRedact() {
            String json = "{\"passed\":true,\"violations\":[],\"details\":{}}";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 100, 70, 30));

            assertThat(service.execute(request("safe content", "redact")).sanitized()).isEqualTo("safe content");
        }

        @Test
        @DisplayName("failed content gets [CONTENT BLOCKED] when sanitized absent on redact action")
        void failedContentBlockedOnRedact() {
            String json = "{\"passed\":false,\"violations\":[\"hate_speech\"],\"details\":{}}";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 100, 70, 30));

            assertThat(service.execute(request("offensive text", "redact")).sanitized()).isEqualTo("[CONTENT BLOCKED]");
        }

        @Test
        @DisplayName("LLM-provided sanitized value takes precedence over synthesis")
        void llmSanitizedTakesPrecedence() {
            String json = "{\"passed\":false,\"violations\":[\"pii\"],\"details\":{},\"sanitized\":\"My [REDACTED] is here\"}";
            when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 100, 70, 30));

            assertThat(service.execute(request("My SSN is 123", "redact")).sanitized()).isEqualTo("My [REDACTED] is here");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Plain-text fallback
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Plain-text fallback - heuristic violation detection")
    class PlainTextFallback {

        @Test
        @DisplayName("'violated' keyword triggers failure")
        void violatedKeywordTriggersFail() {
            when(agentLoopService.execute(any(), isNull())).thenReturn(
                loopResult("The content has violated rule hate_speech", 40, 30, 10));

            GuardrailResponseDto result = service.execute(request("test", "flag"));

            assertThat(result.passed()).isFalse();
            assertThat(result.violations()).contains("hate_speech");
        }

        @Test
        @DisplayName("neutral text returns passed=true")
        void neutralTextReturnsPassed() {
            when(agentLoopService.execute(any(), isNull())).thenReturn(
                loopResult("Everything looks good here.", 20, 15, 5));

            GuardrailResponseDto result = service.execute(request("nice content", "flag"));

            assertThat(result.passed()).isTrue();
            assertThat(result.violations()).isEmpty();
            assertThat(result.sanitized()).isEqualTo("nice content");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  AgentLoopService failure
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AgentLoopService failure handling")
    class LoopFailure {

        @Test
        @DisplayName("loop failure maps to error DTO")
        void loopFailure() {
            AgentLoopResult failure = AgentLoopResult.failure("Budget exhausted", 200, "openai",
                AgentStopReason.BUDGET_EXHAUSTED);
            when(agentLoopService.execute(any(), isNull())).thenReturn(failure);

            GuardrailResponseDto result = service.execute(request("test", "flag"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Budget exhausted");
        }

        @Test
        @DisplayName("exception in execute is caught and returns error DTO")
        void exceptionInExecute() {
            when(agentLoopService.execute(any(), isNull())).thenThrow(new RuntimeException("Connection refused"));

            GuardrailResponseDto result = service.execute(request("test", "flag"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Connection refused");
            assertThat(result.tokensUsed()).isEqualTo(0);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Default provider fallback
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("null provider in request stays null - AgentLoopService handles resolution")
    void nullProviderPassedThrough() {
        String json = "{\"passed\":true,\"violations\":[],\"details\":{},\"sanitized\":null}";
        when(agentLoopService.execute(any(), isNull())).thenReturn(loopResult(json, 100, 70, 30));

        GuardrailRequestDto req = new GuardrailRequestDto("test", null, RULES, "flag", null, null, null, null, null, null);
        GuardrailResponseDto result = service.execute(req);

        assertThat(result.provider()).isNull();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Bridge provider routing
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Bridge provider routing")
    class BridgeRouting {

        @Test
        @DisplayName("claude-code provider dispatches to bridge, NOT agent loop")
        void claudeCodeRoutesToBridge() {
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            String json = "{\"passed\":true,\"violations\":[],\"details\":{},\"sanitized\":null}";
            when(bridgeDispatcher.execute(any())).thenReturn(loopResult(json, 100, 60, 40));

            GuardrailRequestDto req = new GuardrailRequestDto(
                "content", null, RULES, "flag", "claude-code", null, null, null, "tenant-1", "agent-1");
            GuardrailResponseDto result = service.execute(req);

            assertThat(result.success()).isTrue();
            assertThat(result.passed()).isTrue();
            org.mockito.Mockito.verify(bridgeDispatcher).execute(any());
            org.mockito.Mockito.verify(agentLoopService, org.mockito.Mockito.never()).execute(any(), any());
        }

        @Test
        @DisplayName("openai provider keeps using agent loop (not bridge)")
        void openaiStaysOnAgentLoop() {
            when(bridgeDispatcher.shouldDispatch("openai")).thenReturn(false);
            when(agentLoopService.execute(any(), isNull())).thenReturn(
                loopResult("{\"passed\":true,\"violations\":[],\"details\":{},\"sanitized\":null}", 100, 70, 30));

            service.execute(request("hello", "flag"));

            org.mockito.Mockito.verify(agentLoopService).execute(any(), isNull());
            org.mockito.Mockito.verify(bridgeDispatcher, org.mockito.Mockito.never()).execute(any());
        }

        @Test
        @DisplayName("F25 regression: a bridge model mislabelled 'anthropic' is normalised to claude-code and dispatched via the bridge")
        void mislabelledBridgeModelNormalisedToBridge() {
            // The frontend heuristic / LLM-authored plan stored the bridge-only
            // model claude-opus-4-7 with provider="anthropic". The catalog
            // resolver corrects it to claude-code, which MUST then dispatch via
            // the bridge (subscription + BridgeAccessGuard), NOT the direct
            // Anthropic API (credit pool, ungated) as it did pre-fix.
            when(modelCatalogService.resolveProvider("anthropic", "claude-opus-4-7"))
                .thenReturn("claude-code");
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            String json = "{\"passed\":true,\"violations\":[],\"details\":{},\"sanitized\":null}";
            when(bridgeDispatcher.execute(any())).thenReturn(loopResult(json, 100, 60, 40));

            GuardrailRequestDto req = new GuardrailRequestDto(
                "content", null, RULES, "flag", "anthropic", "claude-opus-4-7", null, null, "tenant-1", "agent-1");
            GuardrailResponseDto result = service.execute(req);

            assertThat(result.success()).isTrue();
            // Routed to the bridge, never the direct-API agent loop.
            ArgumentCaptor<AgentLoopContext> ctx = ArgumentCaptor.forClass(AgentLoopContext.class);
            verify(bridgeDispatcher).execute(ctx.capture());
            verify(agentLoopService, org.mockito.Mockito.never()).execute(any(), any());
            // The corrected slug propagated into the dispatched context.
            assertThat(ctx.getValue().provider()).isEqualTo("claude-code");
            // shouldDispatch was asked about the NORMALISED slug, never the stale one.
            verify(bridgeDispatcher).shouldDispatch("claude-code");
            verify(bridgeDispatcher, org.mockito.Mockito.never()).shouldDispatch("anthropic");
        }

        @Test
        @DisplayName("a valid direct-API pair (anthropic + claude-opus-4-6) is left untouched")
        void validDirectPairUnchanged() {
            // resolveProvider is a no-op pass-through (default stub) for valid
            // pairs - the node keeps provider=anthropic and stays on the agent
            // loop (direct API), so deliberate direct-API choices don't regress.
            when(agentLoopService.execute(any(), isNull())).thenReturn(
                loopResult("{\"passed\":true,\"violations\":[],\"details\":{},\"sanitized\":null}", 100, 70, 30));

            GuardrailRequestDto req = new GuardrailRequestDto(
                "content", null, RULES, "flag", "anthropic", "claude-opus-4-6", null, null, "tenant-1", "agent-1");
            service.execute(req);

            verify(agentLoopService).execute(any(), isNull());
            verify(bridgeDispatcher, org.mockito.Mockito.never()).execute(any());
        }

        @Test
        @DisplayName("bridge failure surfaces as error DTO")
        void bridgeFailureMapsToError() {
            when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
            AgentLoopResult failure = AgentLoopResult.failure(
                "Bridge execution failed: no response from bridge server",
                50, "claude-code", AgentStopReason.ERROR);
            when(bridgeDispatcher.execute(any())).thenReturn(failure);

            GuardrailRequestDto req = new GuardrailRequestDto(
                "content", null, RULES, "flag", "claude-code", null, null, null, null, null);
            GuardrailResponseDto result = service.execute(req);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Bridge execution failed");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private GuardrailRequestDto request(String content, String action) {
        return new GuardrailRequestDto(content, null, RULES, action, "openai", null, null, null, null, null);
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
}

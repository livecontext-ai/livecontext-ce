package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.bridge.BridgeAccessDecision;
import com.apimarketplace.agent.bridge.BridgeAccessDeniedException;
import com.apimarketplace.agent.bridge.BridgeAccessGuard;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BridgeLoopDispatcher}.
 *
 * Protects the gate that keeps CLI-based providers (claude-code, codex, gemini-cli,
 * mistral-vibe) from hitting {@code BridgeProviderStub.complete()} inside AgentLoopService.
 *
 * <p>Invariants verified:
 * <ul>
 *   <li>{@code shouldDispatch} only returns true for bridge providers AND when the client is wired.</li>
 *   <li>{@code execute(context)} builds a correct DTO from the context.</li>
 *   <li>Bridge success is translated to {@link AgentLoopResult} success with content + usage.</li>
 *   <li>Bridge null response is translated to a structured ERROR result.</li>
 *   <li>Absent bridge client → {@code shouldDispatch=false} and {@code execute} returns ERROR.</li>
 * </ul>
 */
@DisplayName("BridgeLoopDispatcher")
@ExtendWith(MockitoExtension.class)
class BridgeLoopDispatcherTest {

    @Mock
    private SubAgentBridgeClient bridgeClient;

    private BridgeLoopDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new BridgeLoopDispatcher();
        dispatcher.setBridgeClient(bridgeClient);
    }

    @Nested
    @DisplayName("shouldDispatch()")
    class ShouldDispatch {

        @Test
        @DisplayName("claude-code with client wired → true")
        void claudeCodeWithClient() {
            assertThat(dispatcher.shouldDispatch("claude-code")).isTrue();
            assertThat(dispatcher.shouldDispatch("codex")).isTrue();
            assertThat(dispatcher.shouldDispatch("gemini-cli")).isTrue();
            assertThat(dispatcher.shouldDispatch("mistral-vibe")).isTrue();
        }

        @Test
        @DisplayName("API providers → false (anthropic/openai/google/deepseek)")
        void apiProvidersReturnFalse() {
            assertThat(dispatcher.shouldDispatch("anthropic")).isFalse();
            assertThat(dispatcher.shouldDispatch("openai")).isFalse();
            assertThat(dispatcher.shouldDispatch("google")).isFalse();
            assertThat(dispatcher.shouldDispatch("deepseek")).isFalse();
        }

        @Test
        @DisplayName("case-insensitive match (Claude-Code, CLAUDE-CODE)")
        void caseInsensitive() {
            assertThat(dispatcher.shouldDispatch("Claude-Code")).isTrue();
            assertThat(dispatcher.shouldDispatch("CLAUDE-CODE")).isTrue();
        }

        @Test
        @DisplayName("null / blank provider → false")
        void nullOrBlankProviderReturnsFalse() {
            assertThat(dispatcher.shouldDispatch(null)).isFalse();
            assertThat(dispatcher.shouldDispatch("")).isFalse();
            assertThat(dispatcher.shouldDispatch("  ")).isFalse();
        }

        @Test
        @DisplayName("bridge client unavailable → shouldDispatch is false even for bridge providers")
        void withoutClientReturnsFalse() {
            BridgeLoopDispatcher unwired = new BridgeLoopDispatcher();
            assertThat(unwired.shouldDispatch("claude-code")).isFalse();
            assertThat(unwired.isAvailable()).isFalse();
        }
    }

    @Nested
    @DisplayName("execute(context)")
    class ExecuteContext {

        @Test
        @DisplayName("successful bridge response → AgentLoopResult.success with content + usage")
        void successMapsFields() {
            AgentExecutionResponseDto bridgeResp = new AgentExecutionResponseDto(
                true, "hello world", "hello world", List.of(), 1,
                Map.of("promptTokens", 10, "completionTokens", 20, "totalTokens", 30),
                null, 150L, "claude-code", "claude-sonnet-4-6",
                List.of(), AgentStopReason.COMPLETED.name(),
                Map.of("foo", "bar"), List.of(), List.of(), List.of(),
                List.of(), List.of(), null
            );
            when(bridgeClient.execute(any())).thenReturn(bridgeResp);

            AgentLoopContext context = AgentLoopContext.builder()
                .provider("claude-code").model("claude-sonnet-4-6")
                .systemPrompt("system").userPrompt("user")
                .maxIterations(1).temperature(0.7).maxTokens(500)
                .tenantId("tenant-1").agentId("agent-1").build();

            AgentLoopResult result = dispatcher.execute(context);

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("hello world");
            assertThat(result.usage()).isNotNull();
            assertThat(result.usage().getTotal()).isEqualTo(30);
            assertThat(result.provider()).isEqualTo("claude-code");
            assertThat(result.model()).isEqualTo("claude-sonnet-4-6");
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
            assertThat(result.metrics()).containsEntry("foo", "bar");
        }

        @Test
        @DisplayName("a tool-less context sends an EMPTY module list, so the CLI gets zero platform tools")
        void toolLessContextSendsEmptyModules() {
            AgentExecutionResponseDto ok = new AgentExecutionResponseDto(
                true, "ok", "ok", List.of(), 1, Map.of(),
                null, 10L, "claude-code", "model-x",
                List.of(), AgentStopReason.COMPLETED.name(),
                Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClient.execute(any())).thenReturn(ok);

            // Exactly the shape classify and guardrail build: one turn, no tools.
            AgentLoopContext context = AgentLoopContext.builder()
                .provider("claude-code").model("model-x")
                .systemPrompt("system").userPrompt("user")
                .tools(null).autoDiscoverTools(false)
                .maxIterations(1).temperature(0.0).maxTokens(500)
                .tenantId("tenant-1").build();

            dispatcher.execute(context);

            ArgumentCaptor<AgentExecutionRequestDto> captor =
                ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            org.mockito.Mockito.verify(bridgeClient).execute(captor.capture());
            // null here would mean "nobody decided" and expand to the default module set
            // (table, workflow, files, catalog...), handing a one-shot judge a mutating,
            // credit-spending toolset it never has on the direct-API path.
            assertThat(captor.getValue().enabledModules()).isEmpty();
        }

        @Test
        @DisplayName("a context that brings tools keeps module resolution to the callee (null)")
        void toolCarryingContextLeavesModulesUnset() {
            AgentExecutionResponseDto ok = new AgentExecutionResponseDto(
                true, "ok", "ok", List.of(), 1, Map.of(),
                null, 10L, "claude-code", "model-x",
                List.of(), AgentStopReason.COMPLETED.name(),
                Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClient.execute(any())).thenReturn(ok);

            AgentLoopContext context = AgentLoopContext.builder()
                .provider("claude-code").model("model-x")
                .systemPrompt("system").userPrompt("user")
                .tools(List.of(com.apimarketplace.agent.domain.ToolDefinition.builder()
                    .name("table").description("d").parameters(List.of()).build()))
                .maxIterations(1).temperature(0.0).maxTokens(500)
                .tenantId("tenant-1").build();

            dispatcher.execute(context);

            ArgumentCaptor<AgentExecutionRequestDto> captor =
                ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            org.mockito.Mockito.verify(bridgeClient).execute(captor.capture());
            assertThat(captor.getValue().enabledModules()).isNull();
        }

        @Test
        @DisplayName("a context that asks for tool auto-discovery leaves module resolution to the callee")
        void autoDiscoveringContextLeavesModulesUnset() {
            AgentExecutionResponseDto ok = new AgentExecutionResponseDto(
                true, "ok", "ok", List.of(), 1, Map.of(),
                null, 10L, "claude-code", "model-x",
                List.of(), AgentStopReason.COMPLETED.name(),
                Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClient.execute(any())).thenReturn(ok);

            // No tools in hand, but the caller wants them discovered: that is not tool-less,
            // so the callee keeps deciding the module set.
            AgentLoopContext context = AgentLoopContext.builder()
                .provider("claude-code").model("model-x")
                .systemPrompt("system").userPrompt("user")
                .tools(null).autoDiscoverTools(true)
                .maxIterations(1).temperature(0.0).maxTokens(500)
                .tenantId("tenant-1").build();

            dispatcher.execute(context);

            ArgumentCaptor<AgentExecutionRequestDto> captor =
                ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            org.mockito.Mockito.verify(bridgeClient).execute(captor.capture());
            assertThat(captor.getValue().enabledModules()).isNull();
        }

        @Test
        @DisplayName("bridge DTO carries provider/model/temperature/maxTokens/prompts/tenantId from context")
        void dtoCarriesContextFields() {
            AgentExecutionResponseDto ok = new AgentExecutionResponseDto(
                true, "ok", "ok", List.of(), 1, Map.of(),
                null, 10L, "claude-code", "model-x",
                List.of(), AgentStopReason.COMPLETED.name(),
                Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClient.execute(any())).thenReturn(ok);

            AgentLoopContext context = AgentLoopContext.builder()
                .provider("claude-code").model("model-x")
                .systemPrompt("SYSTEM-PROMPT").userPrompt("USER-PROMPT")
                .maxIterations(1).temperature(0.3).maxTokens(512)
                .tenantId("tenant-42").agentId("agent-99").build();

            dispatcher.execute(context);

            ArgumentCaptor<AgentExecutionRequestDto> captor =
                ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
            org.mockito.Mockito.verify(bridgeClient).execute(captor.capture());

            AgentExecutionRequestDto dto = captor.getValue();
            assertThat(dto.provider()).isEqualTo("claude-code");
            assertThat(dto.model()).isEqualTo("model-x");
            assertThat(dto.systemPrompt()).isEqualTo("SYSTEM-PROMPT");
            assertThat(dto.prompt()).isEqualTo("USER-PROMPT");
            assertThat(dto.temperature()).isEqualTo(0.3);
            assertThat(dto.maxTokens()).isEqualTo(512);
            assertThat(dto.maxIterations()).isEqualTo(1);
            assertThat(dto.tenantId()).isEqualTo("tenant-42");
            assertThat(dto.agentEntityId()).isEqualTo("agent-99");
            // classify/guardrail don't stream - streamChannelId must be null
            assertThat(dto.streamChannelId()).isNull();
            assertThat(dto.conversationId()).isNull();
        }

        @Test
        @DisplayName("bridge null response → AgentLoopResult.failure with ERROR stop reason")
        void nullResponseMapsToError() {
            when(bridgeClient.execute(any())).thenReturn(null);

            AgentLoopContext context = AgentLoopContext.builder()
                .provider("claude-code").model("claude-sonnet-4-6")
                .systemPrompt("s").userPrompt("u").maxIterations(1).build();

            AgentLoopResult result = dispatcher.execute(context);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Bridge execution failed");
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.ERROR);
            assertThat(result.provider()).isEqualTo("claude-code");
        }

        @Test
        @DisplayName("bridge response with empty conversationHistory + non-blank content → synthesises ASSISTANT message (Gemini-empty-history regression guard)")
        void emptyHistorySynthesisesAssistantMessage() {
            // Mirrors AgentLoopService.synthesiseHistoryFallback: when the
            // bridge response has visible content but no conversationHistory
            // entries (some bridge flavours don't echo the assistant turn
            // in conversationHistory), we must synthesise a single
            // ASSISTANT message so GuardrailService/ClassifyService can
            // persist it. Without this, ~17% of guardrail/classify runs
            // through the bridge would land with 0 messages in the side
            // panel - symmetric to the API direct path bug fixed in
            // AgentLoopService.
            AgentExecutionResponseDto resp = new AgentExecutionResponseDto(
                true, "Final answer text", "Final answer text", List.of(), 1,
                Map.of("promptTokens", 5, "completionTokens", 5, "totalTokens", 10),
                null, 100L, "claude-code", "claude-sonnet-4-6",
                List.of(), AgentStopReason.COMPLETED.name(),
                Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClient.execute(any())).thenReturn(resp);

            AgentLoopContext context = AgentLoopContext.builder()
                .provider("claude-code").model("claude-sonnet-4-6")
                .systemPrompt("s").userPrompt("u").maxIterations(1).build();

            AgentLoopResult result = dispatcher.execute(context);

            assertThat(result.success()).isTrue();
            assertThat(result.conversationHistory())
                .as("bridge fallback must produce exactly one synthesised ASSISTANT message")
                .hasSize(1);
            assertThat(result.conversationHistory().get(0).role().name()).isEqualTo("ASSISTANT");
            assertThat(result.conversationHistory().get(0).content()).isEqualTo("Final answer text");
        }

        @Test
        @DisplayName("bridge response with empty conversationHistory + blank content → conversationHistory remains empty (no fake message)")
        void emptyHistoryBlankContentStaysEmpty() {
            AgentExecutionResponseDto resp = new AgentExecutionResponseDto(
                true, "", "", List.of(), 1,
                Map.of(), null, 100L, "claude-code", "claude-sonnet-4-6",
                List.of(), AgentStopReason.COMPLETED.name(),
                Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClient.execute(any())).thenReturn(resp);

            AgentLoopContext context = AgentLoopContext.builder()
                .provider("claude-code").model("claude-sonnet-4-6")
                .systemPrompt("s").userPrompt("u").maxIterations(1).build();

            AgentLoopResult result = dispatcher.execute(context);

            // No content → no synthesis - the ledger should reflect a truly
            // empty bridge call rather than fabricate a phantom assistant turn.
            assertThat(result.conversationHistory()).isEmpty();
        }

        @Test
        @DisplayName("bridge failure response preserves error message + stop reason")
        void failureResponsePreservesError() {
            AgentExecutionResponseDto failure = new AgentExecutionResponseDto(
                false, null, null, List.of(), 0, Map.of(),
                "budget exhausted", 80L, "claude-code", "claude-sonnet-4-6",
                List.of(), AgentStopReason.BUDGET_EXHAUSTED.name(),
                Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClient.execute(any())).thenReturn(failure);

            AgentLoopContext context = AgentLoopContext.builder()
                .provider("claude-code").model("claude-sonnet-4-6")
                .systemPrompt("s").userPrompt("u").maxIterations(1).build();

            AgentLoopResult result = dispatcher.execute(context);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("budget exhausted");
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.BUDGET_EXHAUSTED);
        }

        @Test
        @DisplayName("bridge failure response still carries the tokens the run spent before it stopped")
        void failureResponsePreservesUsage() {
            // A Stop / kill / crash does not refund the model calls already made. The bridge
            // reports them; this converter used to drop them on the failure branch, so the
            // observability row - and the credit debit built from it - read zero. The
            // direct-API loop has always returned usage on its cancel path, which is why a
            // stopped deepseek turn billed and a stopped bridge turn did not.
            AgentExecutionResponseDto stopped = new AgentExecutionResponseDto(
                false, null, null, List.of(), 0,
                Map.of("promptTokens", 15255, "completionTokens", 40, "totalTokens", 15295),
                null, 86180L, "codex", "gpt-5.6-sol",
                List.of(), AgentStopReason.STOPPED_BY_USER.name(),
                Map.of(),
                List.of(Map.of("promptTokens", 15255, "completionTokens", 40, "totalTokens", 15295)),
                List.of(), List.of(),
                List.of(), List.of(), null);
            when(bridgeClient.execute(any())).thenReturn(stopped);

            AgentLoopContext context = AgentLoopContext.builder()
                .provider("codex").model("gpt-5.6-sol")
                .systemPrompt("s").userPrompt("u").maxIterations(1).build();

            AgentLoopResult result = dispatcher.execute(context);

            assertThat(result.success()).isFalse();
            assertThat(result.usage()).isNotNull();
            assertThat(result.usage().promptTokens()).isEqualTo(15255);
            assertThat(result.usage().completionTokens()).isEqualTo(40);
            // The per-iteration breakdown travels too: observability builds its iteration
            // rows from it, and an empty list there is the same silence one level down.
            assertThat(result.usagePerIteration()).hasSize(1);
            assertThat(result.usagePerIteration().get(0).promptTokens()).isEqualTo(15255);
        }

        @Test
        @DisplayName("bridge client unavailable → execute returns ERROR without calling HTTP")
        void withoutClientReturnsError() {
            BridgeLoopDispatcher unwired = new BridgeLoopDispatcher();
            AgentLoopContext context = AgentLoopContext.builder()
                .provider("claude-code").model("claude-sonnet-4-6")
                .systemPrompt("s").userPrompt("u").maxIterations(1).build();

            AgentLoopResult result = unwired.execute(context);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Bridge client not available");
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.ERROR);
        }
    }

    @Nested
    @DisplayName("dispatchRaw(request, userRoles)")
    class DispatchRaw {

        @Test
        @DisplayName("passes request DTO through unchanged to bridge client")
        void passThrough() {
            AgentExecutionRequestDto request = new AgentExecutionRequestDto(
                "Hi", "system", "claude-code", "claude-sonnet-4-6",    // prompt, systemPrompt, provider, model
                0.7, 4096,                                                 // temperature, maxTokens
                null, false, null, 10, 600,                                // tools, autoDiscover, maxTools, maxIterations, executionTimeout
                null,                                                      // conversationHistory
                "tenant-1", null, null, null, null,                        // tenantId, runId, nodeId, variables, credentials
                null,                                                      // maxCreditBudget
                null, null, null,                                          // streamChannelId, itemIndex, loopIteration
                "conv-1", "conversation",                                  // conversationId, streamingFormat
                null, null, null, null,                                    // parentConversationId, subAgentName, subAgentAvatarUrl, subAgentId
                null, null,                                                // workflowRunId, attachments
                "agent-1",                                                 // agentEntityId
                null, null, null,                                          // tenantBalance, pricingRates, creditsConsumedSoFar
                null, null,                                                // loopIdenticalStop, loopConsecutiveStop
                null,                                                      // executionId
                null,                                                      // source
                null,                                                      // reasoningEffort
                null                                                       // enabledModules
            );
            AgentExecutionResponseDto resp = new AgentExecutionResponseDto(
                true, "ok", "ok", List.of(), 1, Map.of(), null, 10L,
                "claude-code", "claude-sonnet-4-6", List.of(),
                AgentStopReason.COMPLETED.name(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), null);
            when(bridgeClient.execute(request)).thenReturn(resp);

            AgentExecutionResponseDto result = dispatcher.dispatchRaw(request, "USER");

            assertThat(result).isSameAs(resp);
        }

        @Test
        @DisplayName("returns null when bridge client unavailable")
        void withoutClientReturnsNull() {
            BridgeLoopDispatcher unwired = new BridgeLoopDispatcher();
            AgentExecutionRequestDto request = new AgentExecutionRequestDto(
                "Hi", null, "claude-code", "claude-sonnet-4-6",        // prompt, systemPrompt, provider, model
                null, null,                                                // temperature, maxTokens
                null, null, null, null, null,                              // tools, autoDiscover, maxTools, maxIterations, executionTimeout
                null,                                                      // conversationHistory
                null, null, null, null, null,                              // tenantId, runId, nodeId, variables, credentials
                null,                                                      // maxCreditBudget
                null, null, null,                                          // streamChannelId, itemIndex, loopIteration
                null, null,                                                // conversationId, streamingFormat
                null, null, null, null,                                    // parentConversationId, subAgentName, subAgentAvatarUrl, subAgentId
                null, null,                                                // workflowRunId, attachments
                null,                                                      // agentEntityId
                null, null, null,                                          // tenantBalance, pricingRates, creditsConsumedSoFar
                null, null,                                                // loopIdenticalStop, loopConsecutiveStop
                null,                                                      // executionId
                null,                                                      // source
                null,                                                      // reasoningEffort
                null                                                       // enabledModules
            );

            assertThat(unwired.dispatchRaw(request, null)).isNull();
        }

        @Test
        @DisplayName("forwards the userRoles parameter to BridgeAccessGuard.enforce - regression for the prod 'bridge_disabled' loss-of-context bug")
        void threadsUserRolesToGuard() {
            // Regression for the prod symptom "Bridge agent execution error:
            // Bridge access denied for claude-code: bridge_disabled" observed on
            // 2026-05-21. Pre-fix, dispatchRaw hard-coded null for the roles
            // arg, so the guard never saw ADMIN - meaning the V270 admin_only
            // default would still deny every admin workflow caller. The test
            // pins the threading contract: whatever the controller hands us
            // MUST reach the guard verbatim, otherwise admin-only policies
            // become unreachable from this code path.
            BridgeAccessGuard guard = org.mockito.Mockito.mock(BridgeAccessGuard.class);
            dispatcher.setBridgeAccessGuard(guard);

            AgentExecutionRequestDto request = new AgentExecutionRequestDto(
                "Hi", "system", "claude-code", "claude-sonnet-4-6",
                0.7, 4096,
                null, false, null, 10, 600,
                null,
                "tenant-1", null, null, null, null,
                null,
                null, null, null,
                null, null,
                null, null, null, null,
                null, null,
                null,
                null, null, null,
                null, null,
                null,  // executionId
                null,  // source
                null,  // reasoningEffort
                null   // enabledModules
            );
            AgentExecutionResponseDto resp = new AgentExecutionResponseDto(
                true, "ok", "ok", List.of(), 1, Map.of(), null, 10L,
                "claude-code", "claude-sonnet-4-6", List.of(),
                AgentStopReason.COMPLETED.name(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), null);
            when(bridgeClient.execute(any())).thenReturn(resp);

            dispatcher.dispatchRaw(request, "ADMIN,USER");

            verify(guard).enforce(eq("tenant-1"), eq("ADMIN,USER"), eq("claude-code"), anyBoolean());
        }

        @Test
        @DisplayName("guard denies admin_only for a non-admin → propagates BridgeAccessDeniedException and never dispatches to the bridge")
        void adminOnlyDenialPropagatesAndSkipsBridge() {
            // The non-bridge path gates inside LLMProviderFactory; dispatchRaw bypasses
            // the factory, so the guard MUST short-circuit here. When the admin_only
            // policy denies a non-admin caller (reason admin_only_requires_admin_role),
            // dispatchRaw has to surface the typed BridgeAccessDeniedException so
            // GlobalExceptionHandler can map it to 403, and it must NOT forward the
            // request to the bridge client (no subscription drain on a denied call).
            BridgeAccessGuard guard = org.mockito.Mockito.mock(BridgeAccessGuard.class);
            dispatcher.setBridgeAccessGuard(guard);

            AgentExecutionRequestDto request = new AgentExecutionRequestDto(
                "Hi", "system", "claude-code", "claude-sonnet-4-6",
                0.7, 4096,
                null, false, null, 10, 600,
                null,
                "tenant-1", null, null, null, null,
                null,
                null, null, null,
                null, null,
                null, null, null, null,
                null, null,
                "agent-1",
                null, null, null,
                null, null,
                null,  // executionId
                null,  // source
                null,  // reasoningEffort
                null   // enabledModules
            );

            // Non-admin "USER" hits the admin_only policy → typed denial.
            org.mockito.Mockito.doThrow(
                    new BridgeAccessDeniedException("claude-code", BridgeAccessDecision.REASON_NOT_ADMIN))
                .when(guard).enforce(eq("tenant-1"), eq("USER"), eq("claude-code"), anyBoolean());

            assertThatThrownBy(() -> dispatcher.dispatchRaw(request, "USER"))
                .isInstanceOf(BridgeAccessDeniedException.class)
                .satisfies(ex -> assertThat(((BridgeAccessDeniedException) ex).getReason())
                    .isEqualTo(BridgeAccessDecision.REASON_NOT_ADMIN));

            // The denial must abort before forwarding to the bridge.
            verify(bridgeClient, org.mockito.Mockito.never()).execute(any());
        }
    }

    @Nested
    @DisplayName("a bridge the caller CHOSE is gated; one an execution link ROUTED to is not")
    class ChosenVsRouted {

        private static final AgentExecutionResponseDto OK = new AgentExecutionResponseDto(
            true, "ok", "ok", List.of(), 1, Map.of(), null, 10L,
            "claude-code", "claude-fable-5", List.of(),
            AgentStopReason.COMPLETED.name(), Map.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), null);

        private AgentLoopContext nonAdminContext() {
            return AgentLoopContext.builder()
                .provider("claude-code").model("claude-fable-5")
                .systemPrompt("system").userPrompt("scan the agenda")
                .maxIterations(1).tenantId("121").userRoles("USER").build();
        }

        private AgentExecutionRequestDto nonAdminRequest() {
            return new AgentExecutionRequestDto(
                "Hi", "system", "claude-code", "claude-fable-5", 0.7, 4096,
                null, false, null, 10, 600, null,
                "121", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
        }

        private BridgeAccessGuard denyingGuard() {
            BridgeAccessGuard guard = org.mockito.Mockito.mock(BridgeAccessGuard.class);
            // lenient: in the ROUTED cases this stub is meant to go unused - that is the
            // behaviour under test, and verify(never()) is what asserts it. Strict stubbing
            // would otherwise report the unused denial as a test defect.
            org.mockito.Mockito.lenient().doThrow(new BridgeAccessDeniedException(
                    "claude-code", BridgeAccessDecision.REASON_NOT_ADMIN, null))
                .when(guard).enforce(any(), any(), eq("claude-code"), anyBoolean());
            dispatcher.setBridgeAccessGuard(guard);
            return guard;
        }

        @Test
        @DisplayName("execute: a chosen bridge is refused and never reaches the CLI")
        void executeChosenIsGated() {
            denyingGuard();

            assertThatThrownBy(() -> dispatcher.execute(nonAdminContext(), false))
                .isInstanceOf(BridgeAccessDeniedException.class);
            verify(bridgeClient, org.mockito.Mockito.never()).execute(any());
        }

        @Test
        @DisplayName("execute: a routed run skips the selection policy and reaches the CLI")
        void executeRoutedIsNotGated() {
            BridgeAccessGuard guard = denyingGuard();
            when(bridgeClient.execute(any())).thenReturn(OK);

            // Production's Agenda Scout: stored on anthropic, sent here by link 7, owned by a
            // non-admin. The guard answers "may this user SELECT this CLI" - the wrong question for
            // a run that asked for anthropic and is billed for it. Same USER roles, same tenant,
            // same provider as the test above: the ONLY difference is who chose the CLI.
            AgentLoopResult result = dispatcher.execute(nonAdminContext(), true);

            assertThat(result.success()).isTrue();
            verify(guard, org.mockito.Mockito.never()).enforce(any(), any(), any(), anyBoolean());
            verify(bridgeClient).execute(any());
        }

        @Test
        @DisplayName("dispatchRaw: a chosen bridge is refused and never reaches the CLI")
        void dispatchRawChosenIsGated() {
            denyingGuard();

            assertThatThrownBy(() -> dispatcher.dispatchRaw(nonAdminRequest(), "USER", false))
                .isInstanceOf(BridgeAccessDeniedException.class);
            verify(bridgeClient, org.mockito.Mockito.never()).execute(any());
        }

        @Test
        @DisplayName("dispatchRaw: a routed run skips the selection policy and reaches the CLI")
        void dispatchRawRoutedIsNotGated() {
            BridgeAccessGuard guard = denyingGuard();
            when(bridgeClient.execute(any())).thenReturn(OK);

            AgentExecutionResponseDto response = dispatcher.dispatchRaw(nonAdminRequest(), "USER", true);

            assertThat(response).isSameAs(OK);
            verify(guard, org.mockito.Mockito.never()).enforce(any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("the legacy single-argument forms mean CHOSEN, so nothing that used them got looser")
        void legacyFormsMeanChosen() {
            denyingGuard();

            assertThatThrownBy(() -> dispatcher.execute(nonAdminContext()))
                .isInstanceOf(BridgeAccessDeniedException.class);
            assertThatThrownBy(() -> dispatcher.dispatchRaw(nonAdminRequest(), "USER"))
                .isInstanceOf(BridgeAccessDeniedException.class);
        }
    }
}

package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.agent.domain.AgentStopReason;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.loop.PreIterationGuard;
import com.apimarketplace.agent.service.ModelExecutionLinkService;
import com.apimarketplace.agent.service.budget.GuardChainFactory;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@DisplayName("AgentRemoteExecutionService")
@ExtendWith(MockitoExtension.class)
class AgentRemoteExecutionServiceTest {

    @Mock private AgentLoopService agentLoopService;
    @Mock private RedisStreamingCallback redisStreamingCallback;
    @Mock private ConversationRedisStreamingCallback conversationRedisStreamingCallback;
    @Mock private CoreToolsCache coreToolsCache;
    @Mock private AgentActivityPublisher agentActivityPublisher;
    @Mock private GuardChainFactory guardChainFactory;
    @Mock private ClassifyService classifyService;
    @Mock private GuardrailService guardrailService;
    @Mock private BridgeLoopDispatcher bridgeDispatcher;
    @Mock private com.apimarketplace.agent.service.ModelCatalogService modelCatalogService;
    @Mock private ActiveStreamRegistry activeStreamRegistry;

    private AgentRemoteExecutionService service;

    @BeforeEach
    void setUp() {
        service = new AgentRemoteExecutionService(
            agentLoopService,
            new ObjectMapper(),
            redisStreamingCallback,
            conversationRedisStreamingCallback,
            coreToolsCache,
            agentActivityPublisher,
            guardChainFactory,
            classifyService,
            guardrailService,
            bridgeDispatcher,
            modelCatalogService,
            activeStreamRegistry
        );
        lenient().when(bridgeDispatcher.shouldDispatch(any())).thenReturn(false);
        // executeAgent normalises the provider against the catalog before dispatch;
        // default the stub to a no-op pass-through (returns the caller's provider) so
        // withProvider(...) is a no-op and existing assertions on the forwarded DTO hold.
        lenient().when(modelCatalogService.resolveProvider(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        // Bridge path resolves the per-model default effort before dispatch; default
        // the stub to "no change" so existing assertions on the forwarded DTO hold.
        lenient().when(modelCatalogService.resolveEffortWithDefault(any(), any(), any())).thenReturn(null);
        lenient().when(guardChainFactory.forAgentWithFallback(any(), any(), any(), any(), any(), any()))
            .thenReturn(PreIterationGuard.ALWAYS_PROCEED);
    }

    @Test
    @DisplayName("Injects dispatcher executionId into loop credentials so scheduled task claims link to observability")
    void injectsDispatcherExecutionIdIntoLoopCredentials() {
        String executionId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
        when(agentLoopService.execute(contextCaptor.capture(), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());

        service.executeAgent(request(Map.of("__taskId__", taskId, "existing", "value"), executionId));

        assertThat(contextCaptor.getValue().executionId()).isEqualTo(executionId);
        assertThat(contextCaptor.getValue().credentials())
            .containsEntry("__executionId__", executionId)
            .containsEntry("__taskId__", taskId)
            .containsEntry("existing", "value");
    }

    @Test
    @DisplayName("Clamps the requested max_tokens to the model's catalog output ceiling")
    void clampsMaxTokensToModelCeiling() {
        ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
        when(agentLoopService.execute(contextCaptor.capture(), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());
        when(modelCatalogService.resolveMaxOutputTokens("deepseek", "deepseek-chat")).thenReturn(8192);

        service.executeAgent(request(Map.of(), UUID.randomUUID().toString(), null, 16000));

        // 16000 requested but DeepSeek-chat caps output at 8192 → clamped (would have 400'd).
        assertThat(contextCaptor.getValue().maxTokens()).isEqualTo(8192);
    }

    @Test
    @DisplayName("Falls back to the safe 8192 floor when the catalog has no cap for the model")
    void clampsMaxTokensToSafeFloorWhenCapUnknown() {
        ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
        when(agentLoopService.execute(contextCaptor.capture(), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());
        when(modelCatalogService.resolveMaxOutputTokens("deepseek", "deepseek-chat")).thenReturn(null);

        service.executeAgent(request(Map.of(), UUID.randomUUID().toString(), null, 16000));

        assertThat(contextCaptor.getValue().maxTokens()).isEqualTo(8192); // unknown cap → safe floor
    }

    @Test
    @DisplayName("Auto-discover with a restricted enabledModules set scopes core tool schemas via the FILTERED cache overload (drops catalog) - guards the workflow over-billing fix")
    void autoDiscoverWithEnabledModulesUsesFilteredOverloadAndDropsCatalog() {
        ArgumentCaptor<AgentLoopContext> ctx = ArgumentCaptor.forClass(AgentLoopContext.class);
        when(agentLoopService.execute(ctx.capture(), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());
        // Whatever the resolved core-tool-name set is, the cache returns one tool so we can
        // assert the loop received the FILTERED result and not the full unfiltered set.
        when(coreToolsCache.getCoreTools(anySet())).thenReturn(List.of(ToolDefinition.builder().name("table").build()));

        // mode=none resolves (AgentModuleResolver) to the internal modules WITHOUT catalog;
        // the orchestrator forwards exactly that module set on the DTO.
        List<String> enabledModules = List.of("table", "interface", "agent", "skill",
            "workflow", "application", "web_search", "files");
        service.executeAgent(autoDiscoverRequest(enabledModules));

        // The FILTERED overload is used with the rebuilt core tool names - and the no-arg
        // (every-schema) overload is NEVER called. This is the assertion the pre-fix code fails:
        // it called getCoreTools() unconditionally, billing every core schema regardless of mode.
        ArgumentCaptor<Set<String>> names = ArgumentCaptor.forClass(Set.class);
        verify(coreToolsCache).getCoreTools(names.capture());
        verify(coreToolsCache, never()).getCoreTools();
        assertThat(names.getValue())
            .as("restricted (mode=none) agent must NOT be charged for the catalog schema")
            .doesNotContain("catalog")
            .contains("table");
        assertThat(ctx.getValue().tools()).extracting(ToolDefinition::name).containsExactly("table");
    }

    @Test
    @DisplayName("Auto-discover with an EMPTY enabledModules ([]) advertises ZERO core tools (mode=off / tool-less judge agent) - FILTERED overload with an empty name set, never the unfiltered one")
    void autoDiscoverWithEmptyEnabledModulesAdvertisesNoTools() {
        ArgumentCaptor<AgentLoopContext> ctx = ArgumentCaptor.forClass(AgentLoopContext.class);
        when(agentLoopService.execute(ctx.capture(), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());
        when(coreToolsCache.getCoreTools(anySet())).thenReturn(List.of());

        // mode=off resolves (AgentModuleResolver) to an EMPTY module set; the orchestrator forwards [].
        // DefaultSystemPrompts.build([]) yields NO core tool names → the FILTERED cache returns 0 tools.
        // A tool-less judge/classifier agent thus pays for ZERO tool schemas every iteration.
        service.executeAgent(autoDiscoverRequest(List.of()));

        ArgumentCaptor<Set<String>> names = ArgumentCaptor.forClass(Set.class);
        verify(coreToolsCache).getCoreTools(names.capture());
        verify(coreToolsCache, never()).getCoreTools();
        assertThat(names.getValue())
            .as("mode=off (empty modules) must advertise ZERO core tool schemas")
            .isEmpty();
        assertThat(ctx.getValue().tools()).isNullOrEmpty();
    }

    @Test
    @DisplayName("Auto-discover with null enabledModules keeps the legacy unfiltered cache - unrestricted agents (no toolsConfig) are unchanged (no-op back-compat)")
    void autoDiscoverWithNullEnabledModulesUsesUnfilteredOverload() {
        ArgumentCaptor<AgentLoopContext> ctx = ArgumentCaptor.forClass(AgentLoopContext.class);
        when(agentLoopService.execute(ctx.capture(), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());
        when(coreToolsCache.getCoreTools()).thenReturn(List.of(ToolDefinition.builder().name("catalog").build()));

        service.executeAgent(autoDiscoverRequest(null));

        verify(coreToolsCache).getCoreTools();
        verify(coreToolsCache, never()).getCoreTools(anySet());
        assertThat(ctx.getValue().tools()).extracting(ToolDefinition::name).containsExactly("catalog");
    }

    @Test
    @DisplayName("Queue payload source is preserved in fleet activity")
    void queuePayloadSourceIsPreservedInFleetActivity() throws Exception {
        String executionId = UUID.randomUUID().toString();
        when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());

        AgentExecutionRequestDto dto = request(Map.of(), executionId, "WIDGET");
        String payload = new ObjectMapper().writeValueAsString(dto);

        service.executeByType(AgentExecutionTask.TYPE_AGENT, payload);

        verify(agentActivityPublisher).publishExecutionStarted(
            "agent-1", executionId, "deepseek-chat", "WIDGET", null);
    }

    @Test
    @DisplayName("Queue path forwards user roles to bridge dispatcher for admin_only policies")
    void queuePathForwardsUserRolesToBridgeDispatcher() throws Exception {
        String executionId = UUID.randomUUID().toString();
        AgentExecutionRequestDto dto = request(Map.of(), executionId, "CHAT");
        String payload = new ObjectMapper().writeValueAsString(dto);
        when(bridgeDispatcher.shouldDispatch(any())).thenReturn(true);
        when(bridgeDispatcher.dispatchRaw(any(AgentExecutionRequestDto.class), org.mockito.ArgumentMatchers.eq("ADMIN,USER")))
            .thenReturn(new AgentExecutionResponseDto(
                true, "done", "done", List.of(), 1, Map.of("totalTokens", 1),
                null, 10, "claude-code", "claude-sonnet-4-6", List.of(),
                "COMPLETED", Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));

        service.executeByType(AgentExecutionTask.TYPE_AGENT, payload, "ADMIN,USER");

        verify(bridgeDispatcher).dispatchRaw(any(AgentExecutionRequestDto.class), org.mockito.ArgumentMatchers.eq("ADMIN,USER"));
    }

    @Test
    @DisplayName("Bridge path stamps the per-model default reasoning effort onto the forwarded DTO when the caller set none")
    void bridgePathAppliesModelDefaultEffort() {
        String executionId = UUID.randomUUID().toString();
        AgentExecutionRequestDto dto = request(Map.of(), executionId, "CHAT"); // caller effort is null
        when(bridgeDispatcher.shouldDispatch(any())).thenReturn(true);
        // Agent-service resolves the per-model admin default when the caller didn't set one.
        when(modelCatalogService.resolveEffortWithDefault(any(), any(), any())).thenReturn("medium");
        when(bridgeDispatcher.dispatchRaw(any(AgentExecutionRequestDto.class), any()))
            .thenReturn(new AgentExecutionResponseDto(
                true, "done", "done", List.of(), 1, Map.of("totalTokens", 1),
                null, 10, "claude-code", "claude-sonnet-4-6", List.of(),
                "COMPLETED", Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));

        service.executeAgent(dto, "USER");

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(bridgeDispatcher).dispatchRaw(captor.capture(), org.mockito.ArgumentMatchers.eq("USER"));
        assertThat(captor.getValue().reasoningEffort()).isEqualTo("medium");
    }

    @Test
    @DisplayName("F25 regression: a bridge model mislabelled 'anthropic' is normalised to claude-code and dispatched via the bridge, never the direct API")
    void mislabelledBridgeModelNormalisedToBridge() {
        String executionId = UUID.randomUUID().toString();
        // Node stored provider="anthropic" for a bridge-only model (frontend
        // heuristic / LLM-authored plan). The catalog resolver corrects it to
        // claude-code BEFORE shouldDispatch, so it dispatches via the bridge
        // (subscription + BridgeAccessGuard) instead of the direct Anthropic API.
        AgentExecutionRequestDto dto = request(Map.of(), executionId, "CHAT").withProvider("anthropic");
        when(modelCatalogService.resolveProvider(org.mockito.ArgumentMatchers.eq("anthropic"), any()))
            .thenReturn("claude-code");
        when(bridgeDispatcher.shouldDispatch("claude-code")).thenReturn(true);
        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        when(bridgeDispatcher.dispatchRaw(captor.capture(), any()))
            .thenReturn(new AgentExecutionResponseDto(
                true, "done", "done", List.of(), 1, Map.of("totalTokens", 1),
                null, 10, "claude-code", "claude-opus-4-7", List.of(),
                "COMPLETED", Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));

        service.executeAgent(dto, "USER");

        // The corrected slug propagated into the dispatched request, and the
        // stale 'anthropic' slug was never asked about (never fell to the
        // direct-API agent loop, which would have drained the credit pool +
        // bypassed BridgeAccessGuard).
        assertThat(captor.getValue().provider()).isEqualTo("claude-code");
        // Direct claude-code (NO link): full freedom is preserved (the user's hard
        // requirement). __restrictedToolset__ is absent, so the bridge runs in normal mode.
        assertThat(captor.getValue().credentials()).doesNotContainKey("__restrictedToolset__");
        verify(bridgeDispatcher).shouldDispatch("claude-code");
        verify(bridgeDispatcher, org.mockito.Mockito.never()).shouldDispatch("anthropic");
        verify(agentLoopService, org.mockito.Mockito.never()).execute(any(), any(StreamingCallback.class));
    }

    @Test
    @DisplayName("A valid direct-API pair is left on the direct path (resolveProvider no-op -> agent loop, not bridge)")
    void validDirectPairStaysDirect() {
        // Default stub: resolveProvider is a pass-through, so a genuine
        // (deepseek, deepseek-chat) request keeps provider=deepseek and runs the
        // direct agent loop - deliberate direct-API choices do not regress.
        when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());

        service.executeAgent(request(Map.of(), UUID.randomUUID().toString()), "USER");

        verify(agentLoopService).execute(any(), any(StreamingCallback.class));
        verify(bridgeDispatcher, org.mockito.Mockito.never()).dispatchRaw(any(), any());
    }

    @Test
    @DisplayName("Model execution link: a billed (non-bridge) model routes through the bridge with the EXECUTION target, and the response is re-stamped with the BILLED identity so billing stays on the billed price")
    void executionLinkRoutesThroughBridgeAndPreservesBilledIdentity() {
        // billed = anthropic/claude-opus-4-8 (NOT a bridge provider) linked to codex/gpt-5.3-codex.
        ModelExecutionLinkService linkService = org.mockito.Mockito.mock(ModelExecutionLinkService.class);
        when(linkService.resolve(org.mockito.ArgumentMatchers.eq("anthropic"),
                org.mockito.ArgumentMatchers.eq("claude-opus-4-8"), org.mockito.ArgumentMatchers.any()))
            .thenReturn(java.util.Optional.of(
                new com.apimarketplace.agent.service.ModelExecutionLinkService.ExecutionRoute("codex", "gpt-5.3-codex")));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "executionLinkService", linkService);
        when(bridgeDispatcher.isAvailable()).thenReturn(true);
        // Routing dispatches to the bridge because the EXECUTION provider (codex) is a CLI bridge.
        when(bridgeDispatcher.shouldDispatch("codex")).thenReturn(true);
        // The per-model default reasoning effort must resolve against the EXECUTION
        // target (the model the CLI actually runs), not the billed model.
        when(modelCatalogService.resolveEffortWithDefault(any(),
                org.mockito.ArgumentMatchers.eq("codex"), org.mockito.ArgumentMatchers.eq("gpt-5.3-codex")))
            .thenReturn("high");

        ArgumentCaptor<AgentExecutionRequestDto> dispatched = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        // The bridge echoes its OWN execution identity (codex) in the response.
        when(bridgeDispatcher.dispatchRaw(dispatched.capture(), any()))
            .thenReturn(new AgentExecutionResponseDto(
                true, "done", "done", List.of(), 1, Map.of("totalTokens", 1),
                null, 10, "codex", "gpt-5.3-codex", List.of(),
                "COMPLETED", Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null));

        // The user selected/was billed anthropic/claude-opus-4-8.
        AgentExecutionRequestDto dto = request(Map.of(), UUID.randomUUID().toString(), "CHAT")
            .withExecutionTarget("anthropic", "claude-opus-4-8");
        AgentExecutionResponseDto response = service.executeAgent(dto, "USER");

        // EXECUTION went to the bridge with the CODEX target so the CLI actually runs codex.
        // (dispatchRaw enforces BridgeAccessGuard on this provider, so the codex subscription
        // is the one checked - the execution provider, not the billed one.)
        assertThat(dispatched.getValue().provider()).isEqualTo("codex");
        assertThat(dispatched.getValue().model()).isEqualTo("gpt-5.3-codex");
        // The reasoning-effort default was resolved against the execution target.
        assertThat(dispatched.getValue().reasoningEffort()).isEqualTo("high");
        // The direct-API agent loop was never used (the link forced the bridge).
        verify(agentLoopService, never()).execute(any(), any(StreamingCallback.class));
        // BILLING-CRITICAL: the response carries the BILLED identity, not the bridge's, so the
        // orchestrator's observability/credit consumption charge the Anthropic price, not codex.
        assertThat(response.provider()).isEqualTo("anthropic");
        assertThat(response.model()).isEqualTo("claude-opus-4-8");
        // The linked run is dispatched in restricted "API mode" (only the platform MCP tools,
        // no native tools / project files / account leak) - carried via the credentials map.
        assertThat(dispatched.getValue().credentials()).containsEntry("__restrictedToolset__", true);
    }

    @Test
    @DisplayName("A link to a CLI BRIDGE is dropped when the bridge transport is unavailable: the BILLED model runs on its own provider (no failed bridge dispatch)")
    void bridgeLinkDroppedWhenBridgeUnavailable() {
        ModelExecutionLinkService linkService = org.mockito.Mockito.mock(ModelExecutionLinkService.class);
        // The billed model is linked to a CLI bridge (codex)...
        when(linkService.resolve(org.mockito.ArgumentMatchers.eq("deepseek"),
                org.mockito.ArgumentMatchers.eq("deepseek-chat"), org.mockito.ArgumentMatchers.any()))
            .thenReturn(java.util.Optional.of(
                new com.apimarketplace.agent.service.ModelExecutionLinkService.ExecutionRoute("codex", "gpt-5.3-codex")));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "executionLinkService", linkService);
        // ...but the bridge transport is not wired here.
        when(bridgeDispatcher.isAvailable()).thenReturn(false);
        ArgumentCaptor<AgentLoopContext> ctx = ArgumentCaptor.forClass(AgentLoopContext.class);
        when(agentLoopService.execute(ctx.capture(), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());

        service.executeAgent(request(Map.of(), UUID.randomUUID().toString()), "USER");

        // The link was dropped: the BILLED model (deepseek) ran on the direct loop, NOT codex.
        verify(agentLoopService).execute(any(), any(StreamingCallback.class));
        verify(bridgeDispatcher, never()).dispatchRaw(any(), any());
        assertThat(ctx.getValue().provider()).isEqualTo("deepseek");
        assertThat(ctx.getValue().model()).isEqualTo("deepseek-chat");
    }

    @Test
    @DisplayName("Model execution link to a NON-bridge provider (openrouter): the direct agent loop runs on the EXECUTION identity, and the response is re-stamped with the BILLED identity so billing stays on the billed price")
    void executionLinkToNonBridgeProviderRunsDirectLoopAndRelabels() {
        ModelExecutionLinkService linkService = org.mockito.Mockito.mock(ModelExecutionLinkService.class);
        // billed = anthropic/claude-opus-4-8, executed via openrouter (a regular API provider).
        when(linkService.resolve(org.mockito.ArgumentMatchers.eq("anthropic"),
                org.mockito.ArgumentMatchers.eq("claude-opus-4-8"), org.mockito.ArgumentMatchers.any()))
            .thenReturn(java.util.Optional.of(
                new com.apimarketplace.agent.service.ModelExecutionLinkService.ExecutionRoute(
                    "openrouter", "anthropic/claude-3.5-sonnet")));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "executionLinkService", linkService);
        // openrouter is NOT a bridge -> direct loop (shouldDispatch is false by default in setUp).
        ArgumentCaptor<AgentLoopContext> ctx = ArgumentCaptor.forClass(AgentLoopContext.class);
        when(agentLoopService.execute(ctx.capture(), any(StreamingCallback.class)))
            .thenReturn(AgentLoopResult.builder()
                .success(true).content("done")
                .provider("openrouter").model("anthropic/claude-3.5-sonnet")
                .iterations(1).durationMs(10).stopReason(AgentStopReason.COMPLETED).build());

        AgentExecutionRequestDto dto = request(Map.of(), UUID.randomUUID().toString(), "CHAT")
            .withExecutionTarget("anthropic", "claude-opus-4-8");  // billed identity selected by the user
        AgentExecutionResponseDto response = service.executeAgent(dto, "USER");

        // The loop ran on the EXECUTION identity (openrouter), so the OpenRouter API is what's called.
        assertThat(ctx.getValue().provider()).isEqualTo("openrouter");
        assertThat(ctx.getValue().model()).isEqualTo("anthropic/claude-3.5-sonnet");
        // The bridge was never used (non-bridge execution provider).
        verify(bridgeDispatcher, never()).dispatchRaw(any(), any());
        // BILLING-CRITICAL: the response is re-stamped to the BILLED identity, so billing stays Anthropic.
        assertThat(response.provider()).isEqualTo("anthropic");
        assertThat(response.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    @DisplayName("Surface scoping: the run's activity source (e.g. CHAT) is forwarded to the link resolver so a link can be scoped to one surface")
    void executionLinkResolveReceivesTheRunActivitySource() {
        ModelExecutionLinkService linkService = org.mockito.Mockito.mock(ModelExecutionLinkService.class);
        ArgumentCaptor<String> sourceArg = ArgumentCaptor.forClass(String.class);
        when(linkService.resolve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                sourceArg.capture()))
            .thenReturn(java.util.Optional.empty()); // no link: just observe the surface passed in
        org.springframework.test.util.ReflectionTestUtils.setField(service, "executionLinkService", linkService);
        when(agentLoopService.execute(any(), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());

        // A chat-sourced run.
        service.executeAgent(request(Map.of(), UUID.randomUUID().toString(), "CHAT"), "USER");

        // resolve() was asked about THIS surface, so a CHAT-scoped link could match it.
        assertThat(sourceArg.getValue()).isEqualTo("CHAT");
    }

    @Test
    @DisplayName("Conversation format with a null streamChannelId does NOT open a conversation stream (fleet-activity-only callback)")
    void conversationFormatWithoutStreamChannelDoesNotOpenConversationStream() {
        // request(...) sets streamingFormat="conversation" but leaves streamChannelId null.
        // The (format == conversation && streamChannelId != null) branch must NOT fire;
        // execution falls through to the fleet-activity-only callback (agentEntityId != null).
        ArgumentCaptor<StreamingCallback> callbackCaptor = ArgumentCaptor.forClass(StreamingCallback.class);
        when(agentLoopService.execute(any(AgentLoopContext.class), callbackCaptor.capture()))
            .thenReturn(successfulLoopResult());

        service.executeAgent(request(Map.of(), UUID.randomUUID().toString()));

        // No conversation stream was opened (the WHY: streamChannelId is null, so the
        // conversation streaming path is skipped and no stream registration leaks).
        verify(conversationRedisStreamingCallback, never())
            .forExecution(any(), any(), any(), any(), any(), any(), any(), any());
        verify(redisStreamingCallback, never()).forExecution(any(), any(), any(), any());
        // A fleet-activity-only callback is still wired so the agent card shimmers.
        assertThat(callbackCaptor.getValue()).isNotNull();
    }

    @Test
    @DisplayName("Streaming routing: conversation format with a non-null streamChannelId opens the CONVERSATION callback factory, never the workflow one")
    void conversationFormatWithStreamChannelOpensConversationCallbackFactory() {
        // The base request(...) helper leaves streamChannelId null (only the negative branch
        // is exercised by conversationFormatWithoutStreamChannelDoesNotOpenConversationStream).
        // Here streamChannelId is set, so the positive (format == conversation && channel != null)
        // branch fires and must instantiate the CONVERSATION callback factory.
        when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());

        service.executeAgent(streamingRequest("conversation", "stream-channel-1"));

        // The WHY: the conversation format must produce flat events on stream:events + ws:conversation,
        // which is wired by the conversation callback factory called with the run's stream + conversation
        // identity and model - never the workflow envelope factory.
        verify(conversationRedisStreamingCallback).forExecution(
            org.mockito.ArgumentMatchers.eq("stream-channel-1"),
            org.mockito.ArgumentMatchers.eq("conversation-1"),
            org.mockito.ArgumentMatchers.eq("deepseek-chat"),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull());
        verify(redisStreamingCallback, never()).forExecution(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Streaming routing: a workflow agent NODE with a conversation TEES both callback factories (live conversation panel + run-view envelopes)")
    void conversationFormatOnWorkflowNodeTeesBothCallbacks() {
        // Regression: the two formats used to be mutually exclusive, so the async queue
        // path had to pin streamingFormat="workflow" and the conversation panel of a
        // direct-API workflow agent stayed silent until delivery. With nodeId +
        // workflowRunId present, the conversation branch now ALSO wires the workflow
        // envelope callback through TeeStreamingCallback - both surfaces stream.
        when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());
        when(conversationRedisStreamingCallback.forExecution(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(org.mockito.Mockito.mock(ConversationRedisStreamingCallback.ConversationCallback.class));
        when(redisStreamingCallback.forExecution(any(), any(), any(), any()))
            .thenReturn(org.mockito.Mockito.mock(StreamingCallback.class));

        service.executeAgent(workflowNodeConversationRequest("run-1", "core:agent_node", 3));

        verify(conversationRedisStreamingCallback).forExecution(
            org.mockito.ArgumentMatchers.eq("run-1"),
            org.mockito.ArgumentMatchers.eq("conversation-1"),
            org.mockito.ArgumentMatchers.eq("deepseek-chat"),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("run-1"));
        verify(redisStreamingCallback).forExecution(
            org.mockito.ArgumentMatchers.eq("run-1"),
            org.mockito.ArgumentMatchers.eq("core:agent_node"),
            org.mockito.ArgumentMatchers.eq(3),
            org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("Streaming routing: conversation format WITHOUT a nodeId (chat, sub-agent, task) does NOT tee the workflow callback")
    void conversationFormatWithoutNodeIdDoesNotTee() {
        when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());

        service.executeAgent(streamingRequest("conversation", "stream-channel-2"));

        verify(redisStreamingCallback, never()).forExecution(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Streaming routing: workflow format with a non-null streamChannelId opens the WORKFLOW envelope callback factory, never the conversation one")
    void workflowFormatWithStreamChannelOpensWorkflowCallbackFactory() {
        // streamingFormat != "conversation" with a non-null streamChannelId takes the second
        // branch, which must instantiate the WORKFLOW envelope callback factory.
        when(agentLoopService.execute(any(AgentLoopContext.class), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());

        service.executeAgent(streamingRequest("workflow", "stream-channel-1"));

        // The WHY: the workflow format must produce envelope events on ws:workflow:run, which is
        // wired by the workflow callback factory called with the run's channel + node/item/iteration
        // context - never the conversation flat-event factory.
        verify(redisStreamingCallback).forExecution(
            org.mockito.ArgumentMatchers.eq("stream-channel-1"),
            org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull());
        verify(conversationRedisStreamingCallback, never())
            .forExecution(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Bridge returning a null response yields a FAILED 'no response from bridge server' result and publishes FAILED activity")
    void bridgeNullResponseYieldsFailureAndPublishesFailedActivity() {
        String executionId = UUID.randomUUID().toString();
        AgentExecutionRequestDto dto = request(Map.of(), executionId, "CHAT");
        when(bridgeDispatcher.shouldDispatch(any())).thenReturn(true);
        when(bridgeDispatcher.dispatchRaw(any(AgentExecutionRequestDto.class), any()))
            .thenReturn(null);

        AgentExecutionResponseDto response = service.executeAgent(dto, "USER");

        // The WHY: a null bridge response must not NPE; it surfaces as an explicit FAILED
        // result the orchestrator can read, not a silent success.
        assertThat(response.success()).isFalse();
        assertThat(response.error()).isEqualTo("Bridge execution failed: no response from bridge server");
        assertThat(response.stopReason()).isEqualTo(AgentStopReason.ERROR.name());
        verify(agentActivityPublisher).publishExecutionCompleted(
            org.mockito.ArgumentMatchers.eq("agent-1"),
            org.mockito.ArgumentMatchers.eq(executionId),
            org.mockito.ArgumentMatchers.eq("FAILED"),
            org.mockito.ArgumentMatchers.eq(0),
            org.mockito.ArgumentMatchers.eq(0),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("Bridge path: a BridgeAccessDeniedException is re-thrown (so it maps to 403/429) after publishing FAILED activity")
    void bridgeAccessDeniedIsRethrownAndPublishesFailedActivity() {
        String executionId = UUID.randomUUID().toString();
        AgentExecutionRequestDto dto = request(Map.of(), executionId, "CHAT");
        when(bridgeDispatcher.shouldDispatch(any())).thenReturn(true);
        com.apimarketplace.agent.bridge.BridgeAccessDeniedException denial =
            new com.apimarketplace.agent.bridge.BridgeAccessDeniedException("claude-code", "quota_exhausted");
        when(bridgeDispatcher.dispatchRaw(any(AgentExecutionRequestDto.class), any()))
            .thenThrow(denial);

        // The WHY: the typed denial must surface to GlobalExceptionHandler, NOT be squashed
        // into a generic 200/FAILED response that hides quota vs misconfiguration.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.executeAgent(dto, "USER"))
            .isSameAs(denial);

        verify(agentActivityPublisher).publishExecutionCompleted(
            org.mockito.ArgumentMatchers.eq("agent-1"),
            org.mockito.ArgumentMatchers.eq(executionId),
            org.mockito.ArgumentMatchers.eq("FAILED"),
            org.mockito.ArgumentMatchers.eq(0),
            org.mockito.ArgumentMatchers.eq(0),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("Multi-tenancy contract: the request tenantId is propagated into the AgentLoopContext driving the direct-API execution")
    void tenantIdIsPropagatedIntoLoopContext() {
        // tenantId is this service's tenant-isolation handle: it must travel from the
        // inbound DTO onto the AgentLoopContext so downstream tool/sub-agent calls
        // execute under the same tenant. (organizationId is intentionally NOT carried
        // here - it is thread-bound upstream via headers/TenantResolver, per the DTO doc.)
        ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
        when(agentLoopService.execute(contextCaptor.capture(), any(StreamingCallback.class)))
            .thenReturn(successfulLoopResult());

        service.executeAgent(request(Map.of(), UUID.randomUUID().toString()));

        assertThat(contextCaptor.getValue().tenantId())
            .as("tenant isolation: the loop context must inherit the request tenantId")
            .isEqualTo("tenant-1");
    }

    private AgentExecutionRequestDto request(Map<String, Object> credentials, String executionId) {
        return request(credentials, executionId, null);
    }

    /**
     * A streaming request with an explicit {@code streamingFormat} and a non-null
     * {@code streamChannelId}, so the POSITIVE callback-routing branch fires. The base
     * {@link #request} helper leaves streamChannelId null, which only exercises the
     * negative (no-stream) path - this helper drives the format-to-factory routing.
     */
    private AgentExecutionRequestDto streamingRequest(String streamingFormat, String streamChannelId) {
        return new AgentExecutionRequestDto(
            "Stream the answer.",
            "You are a streaming agent.",
            "deepseek",
            "deepseek-chat",
            0.0,
            320,
            List.of(),
            false,
            10,
            4,
            150,
            null,
            "tenant-1",
            null,             // runId
            null,             // nodeId
            null,             // variables
            Map.of(),         // credentials
            null,             // maxCreditBudget
            streamChannelId,  // streamChannelId - the field under test
            null,             // itemIndex
            null,             // loopIteration
            "conversation-1", // conversationId
            streamingFormat,  // streamingFormat - the field under test
            null,             // parentConversationId
            null,             // subAgentName
            null,             // subAgentAvatarUrl
            null,             // subAgentId
            null,             // workflowRunId
            null,             // attachments
            "agent-1",        // agentEntityId
            100.0,            // tenantBalance
            null,             // pricingRates
            0.0,              // creditsConsumedSoFar
            null,             // loopIdenticalStop
            null,             // loopConsecutiveStop
            UUID.randomUUID().toString(), // executionId
            null,             // source
            null,             // reasoningEffort
            null              // enabledModules
        );
    }

    private AgentExecutionRequestDto request(Map<String, Object> credentials, String executionId, String source) {
        return request(credentials, executionId, source, 320);
    }

    /**
     * A workflow-NODE conversation request the way AgentNode builds it (inline or async):
     * streamChannelId = workflowRunId = the run id, plus nodeId/itemIndex so the worker's
     * tee can key the workflow-envelope callback.
     */
    private AgentExecutionRequestDto workflowNodeConversationRequest(String runId, String nodeId, Integer itemIndex) {
        return new AgentExecutionRequestDto(
            "Stream the answer.",
            "You are a workflow agent.",
            "deepseek",
            "deepseek-chat",
            0.0,
            320,
            List.of(),
            false,
            10,
            4,
            150,
            null,
            "tenant-1",
            runId,            // runId
            nodeId,           // nodeId
            null,             // variables
            Map.of(),         // credentials
            null,             // maxCreditBudget
            runId,            // streamChannelId
            itemIndex,        // itemIndex
            null,             // loopIteration
            "conversation-1", // conversationId
            "conversation",   // streamingFormat
            null,             // parentConversationId
            null,             // subAgentName
            null,             // subAgentAvatarUrl
            null,             // subAgentId
            runId,            // workflowRunId
            null,             // attachments
            "agent-1",        // agentEntityId
            100.0,            // tenantBalance
            null,             // pricingRates
            0.0,              // creditsConsumedSoFar
            null,             // loopIdenticalStop
            null,             // loopConsecutiveStop
            UUID.randomUUID().toString(), // executionId
            null,             // source
            null,             // reasoningEffort
            null              // enabledModules
        );
    }

    private AgentExecutionRequestDto request(Map<String, Object> credentials, String executionId, String source, int maxTokens) {
        return new AgentExecutionRequestDto(
            "Claim the backlog task.",
            "You are a scheduled agent.",
            "deepseek",
            "deepseek-chat",
            0.0,
            maxTokens,
            List.of(),
            false,
            10,
            4,
            150,
            null,
            "tenant-1",
            null,
            null,
            null,
            credentials,
            null,
            null,
            null,
            null,
            "conversation-1",
            "conversation",
            null,
            null,
            null,
            null,
            null,
            null,
            "agent-1",
            100.0,
            null,
            0.0,
            null,
            null,
            executionId,
            source,
            null,  // reasoningEffort
            null   // enabledModules
        );
    }

    /**
     * A workflow-agent request the way AgentNode builds it for an entity-backed agent:
     * {@code tools = null} + {@code autoDiscoverTools = true}, so convertToContext enters the
     * auto-discover branch. {@code enabledModules} is the canonical module set the
     * orchestrator forwards (null ⇒ unrestricted).
     */
    private AgentExecutionRequestDto autoDiscoverRequest(List<String> enabledModules) {
        return new AgentExecutionRequestDto(
            "Judge the snapshot.",
            "You are a judge.",
            "deepseek",
            "deepseek-chat",
            0.0,
            320,
            null,    // tools - null so the auto-discover branch runs
            true,    // autoDiscoverTools
            10,
            4,
            150,
            null,
            "tenant-1",
            null,
            null,
            null,
            Map.of(),
            null,
            null,
            null,
            null,
            "conversation-1",
            "conversation",
            null,
            null,
            null,
            null,
            null,
            null,
            "agent-1",
            100.0,
            null,
            0.0,
            null,
            null,
            UUID.randomUUID().toString(),
            "WORKFLOW",
            null,            // reasoningEffort
            enabledModules   // the field under test
        );
    }

    private AgentLoopResult successfulLoopResult() {
        UsageInfo usage = UsageInfo.builder()
            .promptTokens(20)
            .completionTokens(10)
            .totalTokens(30)
            .build();
        CompletionResponse response = CompletionResponse.builder()
            .content("done")
            .finishReason("stop")
            .usage(usage)
            .build();
        return AgentLoopResult.builder()
            .success(true)
            .response(response)
            .content("done")
            .usage(usage)
            .provider("deepseek")
            .model("deepseek-chat")
            .iterations(1)
            .durationMs(100)
            .stopReason(AgentStopReason.COMPLETED)
            .build();
    }
}

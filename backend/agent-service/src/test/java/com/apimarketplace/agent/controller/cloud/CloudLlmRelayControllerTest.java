package com.apimarketplace.agent.controller.cloud;

import com.apimarketplace.agent.cloud.CeRelayReleaseRequest;
import com.apimarketplace.agent.cloud.CeRelaySettleRequest;
import com.apimarketplace.agent.cloud.CloudLlmRelayRequest;
import com.apimarketplace.agent.cloud.CloudLlmStreamEvent;
import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.ModelExecutionLinkService;
import com.apimarketplace.agent.service.cloud.CeRelayAccrualStore;
import com.apimarketplace.agent.service.cloud.CeRelaySettlementService;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.LlmCacheTokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CloudLlmRelayController")
class CloudLlmRelayControllerTest {

    private static final long CLOUD_USER_ID = 42L;
    private static final String INSTALL_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PROVIDER = "deepseek";
    private static final String MODEL = "deepseek-chat";

    @Mock private AuthClient authClient;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private LLMProviderFactory providerFactory;
    @Mock private LLMProvider provider;
    @Mock private CeRelayAccrualStore accrualStore;
    @Mock private CeRelaySettlementService settlementService;
    @Mock private ModelConfigOverrideRepository modelConfigRepository;
    @Mock private ModelExecutionLinkService executionLinkService;
    @Mock private LLMProvider execProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CloudLlmRelayController controller;

    @BeforeEach
    void setUp() {
        // Default controller: centralized billing honored when the request carries an executionId.
        controller = new CloudLlmRelayController(
                authClient, creditClient, providerFactory, objectMapper, accrualStore, settlementService,
                modelConfigRepository, true);
        // The relay rejects a model the cloud no longer curates (no model_config_overrides row)
        // before the budget gate. The default test model IS curated; lenient so the validation /
        // settle / release tests that never reach model resolution don't trip strict-stub checks.
        // The MODEL_NOT_SUPPORTED tests use a model id with no row (Mockito returns Optional.empty()).
        lenient().when(modelConfigRepository.findByProviderAndModelId(PROVIDER, MODEL))
                .thenReturn(Optional.of(new ModelConfigOverrideEntity()));
    }

    @Test
    @DisplayName("complete validates the CE link, rewrites only the tenant, and consumes cloud credits")
    void completeValidatesLinkRewritesTenantAndConsumesCredits() {
        CompletionRequest ceRequest = request(false);
        CompletionResponse llmResponse = response("done", 11, 7);
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(provider.complete(any())).thenReturn(llmResponse);
        when(creditClient.checkChatBudget(eq("42"), eq(PROVIDER), eq(MODEL), anyInt(), eq(256)))
                .thenReturn(true);
        when(creditClient.consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(),
                eq(PROVIDER), eq(MODEL), eq(11), eq(7), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(Map.of("success", true));

        ResponseEntity<?> response = controller.complete(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, ceRequest));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(llmResponse);
        ArgumentCaptor<CompletionRequest> requestCaptor = ArgumentCaptor.forClass(CompletionRequest.class);
        verify(provider).complete(requestCaptor.capture());
        CompletionRequest cloudRequest = requestCaptor.getValue();
        assertThat(cloudRequest.tenantId()).isEqualTo("42");
        assertThat(cloudRequest.model()).isEqualTo(MODEL);
        assertThat(cloudRequest.stream()).isFalse();
        assertThat(cloudRequest.tools()).extracting(ToolDefinition::name).containsExactly("local_lookup");
        // Regression: the tenant rewrite used to DROP the CE-resolved reasoning
        // effort, silently resetting relayed requests to the API default (high).
        assertThat(cloudRequest.reasoningEffort()).isEqualTo("xhigh");
    }

    @Test
    @DisplayName("execution link to a CLI BRIDGE is rejected as not relayable - never dispatches the billed provider (the misleading-credit-error fix)")
    void bridgeTargetExecutionLinkIsRejectedNotRelayed() {
        ReflectionTestUtils.setField(controller, "executionLinkService", executionLinkService);
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        // Admin linked billed deepseek/deepseek-chat -> the claude-code CLI bridge ("All surfaces").
        when(executionLinkService.resolve(PROVIDER, MODEL, "CE_LLM_RELAY"))
                .thenReturn(Optional.of(new ModelExecutionLinkService.ExecutionRoute("claude-code", "claude-opus-4-6")));

        ResponseEntity<?> response = controller.complete(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, request(false)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "BRIDGE_EXECUTION_NOT_RELAYABLE"));
        // Pre-fix bug: the relay ignored the link and fell through to provider.complete() on the
        // empty-credit platform key -> the misleading "credit balance too low". Post-fix: the billed
        // provider is never dispatched (a CLI bridge cannot run over the single-completion relay).
        verify(provider, never()).complete(any());
    }

    @Test
    @DisplayName("execution link to an API provider redirects the dispatch (openrouter) while billing stays on the billed pair")
    void apiTargetExecutionLinkRedirectsDispatchButBillsBilled() {
        ReflectionTestUtils.setField(controller, "executionLinkService", executionLinkService);
        CompletionResponse llmResponse = response("done", 11, 7);
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        // Link redirects EXECUTION to a regular API provider (not a bridge).
        when(executionLinkService.resolve(PROVIDER, MODEL, "CE_LLM_RELAY"))
                .thenReturn(Optional.of(new ModelExecutionLinkService.ExecutionRoute("openrouter", "or/some-model")));
        when(providerFactory.getProvider("openrouter")).thenReturn(execProvider);
        when(execProvider.complete(any())).thenReturn(llmResponse);
        when(creditClient.checkChatBudget(eq("42"), eq(PROVIDER), eq(MODEL), anyInt(), anyInt())).thenReturn(true);
        when(creditClient.consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(), eq(PROVIDER), eq(MODEL),
                anyInt(), anyInt(), any(LlmCacheTokens.class))).thenReturn(Map.of("success", true));

        ResponseEntity<?> response = controller.complete(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, request(false)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Dispatch ran on the EXECUTION provider (openrouter) with the execution model, NOT the billed one.
        ArgumentCaptor<CompletionRequest> cap = ArgumentCaptor.forClass(CompletionRequest.class);
        verify(execProvider).complete(cap.capture());
        assertThat(cap.getValue().model()).isEqualTo("or/some-model");
        verify(provider, never()).complete(any());
        // Billing recorded the BILLED pair (kept-price contract), not openrouter.
        verify(creditClient).consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(), eq(PROVIDER), eq(MODEL),
                anyInt(), anyInt(), any(LlmCacheTokens.class));
    }

    @Test
    @DisplayName("complete forwards the provider's cache/reasoning counters verbatim to billing (cache-aware fix)")
    void completeForwardsCacheCountersToBilling() {
        // Regression for the 2026-06-11 cache-billing fix on the CE relay path: the
        // UsageInfo cache counters from the provider response must reach consumeCredits
        // so auth-service can apply the provider's cache discounts.
        CompletionResponse llmResponse = CompletionResponse.builder()
                .content("done")
                .finishReason("stop")
                .model(MODEL)
                .usage(UsageInfo.builder()
                        .promptTokens(1000)
                        .completionTokens(500)
                        .totalTokens(1500)
                        .cacheCreationInputTokens(200)
                        .cacheReadInputTokens(300)
                        .cachedTokens(400)
                        .reasoningTokens(50)
                        .build())
                .build();
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(provider.complete(any())).thenReturn(llmResponse);
        when(creditClient.checkChatBudget(eq("42"), eq(PROVIDER), eq(MODEL), anyInt(), eq(256)))
                .thenReturn(true);
        when(creditClient.consumeCredits(any(), any(), any(), any(), any(), any(), any(),
                any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(Map.of("success", true));

        controller.complete(CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, request(false)));

        verify(creditClient).consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(),
                eq(PROVIDER), eq(MODEL), eq(1000), eq(500),
                eq(new com.apimarketplace.common.credit.LlmCacheTokens(200, 300, 400, 50)));
    }

    @Test
    @DisplayName("complete bills with empty cache counters when the provider returns no usage (estimate fallback)")
    void completeBillsWithoutCacheCountersWhenUsageMissing() {
        CompletionResponse llmResponse = CompletionResponse.builder()
                .content("done")
                .finishReason("stop")
                .model(MODEL)
                .build(); // no usage - controller falls back to its estimate
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(provider.complete(any())).thenReturn(llmResponse);
        when(creditClient.checkChatBudget(eq("42"), eq(PROVIDER), eq(MODEL), anyInt(), eq(256)))
                .thenReturn(true);
        when(creditClient.consumeCredits(any(), any(), any(), any(), any(), any(), any(),
                any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(Map.of("success", true));

        controller.complete(CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, request(false)));

        verify(creditClient).consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(),
                eq(PROVIDER), eq(MODEL), anyInt(), anyInt(),
                eq(new com.apimarketplace.common.credit.LlmCacheTokens(null, null, null, null)));
    }

    @Test
    @DisplayName("complete refuses inactive CE links before provider dispatch")
    void completeRejectsInactiveCeLink() {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(false);

        ResponseEntity<?> response = controller.complete(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, request(false)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "CE_LINK_NOT_ACTIVE"));
        verifyNoInteractions(providerFactory, creditClient);
    }

    @Test
    @DisplayName("complete fail-closes on insufficient cloud credits before model dispatch")
    void completeRejectsInsufficientCreditsBeforeModelDispatch() {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(creditClient.checkChatBudget(eq("42"), eq(PROVIDER), eq(MODEL), anyInt(), eq(256)))
                .thenReturn(false);

        ResponseEntity<?> response = controller.complete(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, request(false)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "INSUFFICIENT_CREDITS"));
        verify(provider, never()).complete(any());
        verify(creditClient, never()).consumeCredits(any(), any(), any(), any(), any(), any(), any(),
                any(com.apimarketplace.common.credit.LlmCacheTokens.class));
    }

    @Test
    @DisplayName("complete rejects bridge providers explicitly because bridge execution stays local")
    void completeRejectsBridgeProviders() {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);

        ResponseEntity<?> response = controller.complete(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest("codex", request(false)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "BRIDGE_PROVIDER_NOT_RELAYABLE"));
        verifyNoInteractions(providerFactory, creditClient);
    }

    @Test
    @DisplayName("complete rejects unsupported local providers explicitly")
    void completeRejectsUnsupportedLocalProviders() {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);

        ResponseEntity<?> response = controller.complete(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest("local-openai-compatible", request(false)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "PROVIDER_NOT_RELAYABLE"));
        verifyNoInteractions(providerFactory, creditClient);
    }

    @Test
    @DisplayName("complete rejects a model the cloud no longer curates with MODEL_NOT_SUPPORTED, before gate + dispatch")
    void completeRejectsUnmanagedModel() {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        // No model_config_overrides row for "ghost-model": the CE is on a stale/foreign
        // catalog. The relay must reject BEFORE the budget gate so it never masquerades as
        // INSUFFICIENT_CREDITS, and never dispatches to the provider.

        ResponseEntity<?> response = controller.complete(
                CLOUD_USER_ID, INSTALL_ID,
                new CloudLlmRelayRequest(PROVIDER, requestWithModel("ghost-model", false)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "MODEL_NOT_SUPPORTED"));
        verify(creditClient, never()).checkChatBudget(any(), any(), any(), anyInt(), anyInt());
        verify(provider, never()).complete(any());
    }

    @Test
    @DisplayName("stream emits a MODEL_NOT_SUPPORTED error event (400) for a model the cloud no longer curates")
    void streamRejectsUnmanagedModel() throws Exception {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);

        ResponseEntity<StreamingResponseBody> response = controller.stream(
                CLOUD_USER_ID, INSTALL_ID,
                new CloudLlmRelayRequest(PROVIDER, requestWithModel("ghost-model", true)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<CloudLlmStreamEvent> events = Arrays.stream(output.toString(StandardCharsets.UTF_8).split("\\R"))
                .filter(line -> !line.isBlank())
                .map(this::readEvent)
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(CloudLlmStreamEvent.Type.ERROR);
        assertThat(events.get(0).error()).isEqualTo("MODEL_NOT_SUPPORTED");
        verify(provider, never()).completeStreaming(any(), any());
        verify(creditClient, never()).checkChatBudget(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("complete skips the unmanaged-model guard for a blank request model (provider default is trusted)")
    void completeSkipsModelGuardForBlankModel() {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(provider.getDefaultModel()).thenReturn("provider-default");
        // Gate fails so we can assert execution REACHED it - i.e. the model guard did not reject the
        // blank-model request (it resolves to the trusted provider default, never validated).
        when(creditClient.checkChatBudget(eq("42"), eq(PROVIDER), eq("provider-default"), anyInt(), anyInt()))
                .thenReturn(false);

        ResponseEntity<?> response = controller.complete(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, requestWithModel("", false)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "INSUFFICIENT_CREDITS"));
        verify(modelConfigRepository, never()).findByProviderAndModelId(eq(PROVIDER), eq("provider-default"));
    }

    @Test
    @DisplayName("stream relays content, thinking, tool calls, completion, and bills after completion")
    void streamRelaysEventsAndConsumesCredits() throws Exception {
        ToolCall toolCall = ToolCall.builder()
                .id("call-1")
                .toolName("local_lookup")
                .arguments(Map.of("query", "status"))
                .index(0)
                .build();
        CompletionResponse finalResponse = CompletionResponse.builder()
                .content("final")
                .finishReason("tool_calls")
                .model(MODEL)
                .toolCalls(List.of(toolCall))
                .usage(UsageInfo.builder()
                        .promptTokens(13)
                        .completionTokens(5)
                        .totalTokens(18)
                        .build())
                .build();
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(creditClient.checkChatBudget(eq("42"), eq(PROVIDER), eq(MODEL), anyInt(), eq(256)))
                .thenReturn(true);
        when(creditClient.consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(),
                eq(PROVIDER), eq(MODEL), eq(13), eq(5), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(Map.of("success", true));
        doAnswer(invocation -> {
            StreamingCallback callback = invocation.getArgument(1);
            callback.onChunk("hel");
            callback.onThinking("thinking");
            callback.onToolCall(toolCall);
            callback.onComplete(finalResponse);
            return null;
        }).when(provider).completeStreaming(any(), any());

        ResponseEntity<StreamingResponseBody> response = controller.stream(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, request(true)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<CloudLlmStreamEvent> events = Arrays.stream(output.toString(StandardCharsets.UTF_8).split("\\R"))
                .filter(line -> !line.isBlank())
                .map(line -> readEvent(line))
                .toList();
        assertThat(events).extracting(CloudLlmStreamEvent::type).containsExactly(
                CloudLlmStreamEvent.Type.CONTENT_CHUNK,
                CloudLlmStreamEvent.Type.THINKING_CHUNK,
                CloudLlmStreamEvent.Type.TOOL_CALL,
                CloudLlmStreamEvent.Type.COMPLETED);
        assertThat(events.get(0).content()).isEqualTo("hel");
        assertThat(events.get(1).thinking()).isEqualTo("thinking");
        assertThat(events.get(2).toolCall()).isEqualTo(toolCall);
        assertThat(events.get(3).response()).isEqualTo(finalResponse);

        ArgumentCaptor<CompletionRequest> requestCaptor = ArgumentCaptor.forClass(CompletionRequest.class);
        verify(provider).completeStreaming(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().tenantId()).isEqualTo("42");
        assertThat(requestCaptor.getValue().stream()).isTrue();
        verify(creditClient).consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(),
                eq(PROVIDER), eq(MODEL), eq(13), eq(5), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
    }

    @Test
    @DisplayName("stream: execution link to a CLI BRIDGE emits a BRIDGE_EXECUTION_NOT_RELAYABLE error event (400), never dispatches the billed provider")
    void streamBridgeTargetExecutionLinkIsRejectedNotRelayed() throws Exception {
        ReflectionTestUtils.setField(controller, "executionLinkService", executionLinkService);
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(executionLinkService.resolve(PROVIDER, MODEL, "CE_LLM_RELAY"))
                .thenReturn(Optional.of(new ModelExecutionLinkService.ExecutionRoute("claude-code", "claude-opus-4-6")));

        ResponseEntity<StreamingResponseBody> response = controller.stream(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, request(true)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<CloudLlmStreamEvent> events = Arrays.stream(output.toString(StandardCharsets.UTF_8).split("\\R"))
                .filter(line -> !line.isBlank())
                .map(this::readEvent)
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(CloudLlmStreamEvent.Type.ERROR);
        assertThat(events.get(0).error()).isEqualTo("BRIDGE_EXECUTION_NOT_RELAYABLE");
        // The billed provider is never dispatched, and the budget gate is never reached.
        verify(provider, never()).completeStreaming(any(), any());
        verify(creditClient, never()).checkChatBudget(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("stream: execution link to an API provider dispatches on the target (openrouter) while billing stays on the billed pair")
    void streamApiTargetExecutionLinkRedirectsDispatchButBillsBilled() throws Exception {
        ReflectionTestUtils.setField(controller, "executionLinkService", executionLinkService);
        CompletionResponse finalResponse = CompletionResponse.builder()
                .content("final")
                .finishReason("stop")
                .model("or/some-model")
                .usage(UsageInfo.builder().promptTokens(13).completionTokens(5).totalTokens(18).build())
                .build();
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(executionLinkService.resolve(PROVIDER, MODEL, "CE_LLM_RELAY"))
                .thenReturn(Optional.of(new ModelExecutionLinkService.ExecutionRoute("openrouter", "or/some-model")));
        when(providerFactory.getProvider("openrouter")).thenReturn(execProvider);
        when(creditClient.checkChatBudget(eq("42"), eq(PROVIDER), eq(MODEL), anyInt(), anyInt())).thenReturn(true);
        when(creditClient.consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(), eq(PROVIDER), eq(MODEL),
                eq(13), eq(5), any(LlmCacheTokens.class))).thenReturn(Map.of("success", true));
        doAnswer(invocation -> {
            StreamingCallback callback = invocation.getArgument(1);
            callback.onChunk("hel");
            callback.onComplete(finalResponse);
            return null;
        }).when(execProvider).completeStreaming(any(), any());

        ResponseEntity<StreamingResponseBody> response = controller.stream(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, request(true)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Dispatch ran on the EXECUTION provider (openrouter) with the execution model, NOT the billed one.
        ArgumentCaptor<CompletionRequest> reqCap = ArgumentCaptor.forClass(CompletionRequest.class);
        verify(execProvider).completeStreaming(reqCap.capture(), any());
        assertThat(reqCap.getValue().model()).isEqualTo("or/some-model");
        verify(provider, never()).completeStreaming(any(), any());
        // Billing recorded the BILLED pair (kept-price contract), not openrouter.
        verify(creditClient).consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(), eq(PROVIDER), eq(MODEL),
                eq(13), eq(5), any(LlmCacheTokens.class));
    }

    @Test
    @DisplayName("stream propagates client disconnect to provider shouldStop and bills streamed content once")
    void streamPropagatesDisconnectAndBillsPartialContent() throws Exception {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(creditClient.checkChatBudget(eq("42"), eq(PROVIDER), eq(MODEL), anyInt(), eq(256)))
                .thenReturn(true);
        when(creditClient.consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(),
                eq(PROVIDER), eq(MODEL), anyInt(), anyInt(), any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(Map.of("success", true));
        doAnswer(invocation -> {
            StreamingCallback callback = invocation.getArgument(1);
            callback.onChunk("partial");
            assertThat(callback.shouldStop()).isTrue();
            return null;
        }).when(provider).completeStreaming(any(), any());

        ResponseEntity<StreamingResponseBody> response = controller.stream(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, request(true)));

        response.getBody().writeTo(new DisconnectingOutputStream());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(creditClient).consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(),
                eq(PROVIDER), eq(MODEL), anyInt(), eq(1), any(com.apimarketplace.common.credit.LlmCacheTokens.class));
    }

    @Test
    @DisplayName("stream fallback billing counts bytes without retaining the full content buffer")
    void streamFallbackBillingCountsContentWithoutRetainingFullBuffer() throws Exception {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(creditClient.checkChatBudget(eq("42"), eq(PROVIDER), eq(MODEL), anyInt(), eq(256)))
                .thenReturn(true);
        when(creditClient.consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(),
                eq(PROVIDER), eq(MODEL), anyInt(), anyInt(),
                any(com.apimarketplace.common.credit.LlmCacheTokens.class)))
                .thenReturn(Map.of("success", true));
        doAnswer(invocation -> {
            StreamingCallback callback = invocation.getArgument(1);
            callback.onChunk("x".repeat(4096));
            callback.onChunk("y".repeat(4096));
            callback.onComplete(CompletionResponse.builder()
                    .content(null)
                    .finishReason("stop")
                    .model(MODEL)
                    .build());
            return null;
        }).when(provider).completeStreaming(any(), any());

        ResponseEntity<StreamingResponseBody> response = controller.stream(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, request(true)));

        response.getBody().writeTo(OutputStream.nullOutputStream());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(creditClient).consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(),
                eq(PROVIDER), eq(MODEL), anyInt(), eq(2048),
                any(com.apimarketplace.common.credit.LlmCacheTokens.class));
    }

    // ===== Centralized (accrue → settle) billing =====

    @Test
    @DisplayName("centralized complete accrues usage and does NOT bill per call")
    void centralizedCompleteAccruesAndDoesNotBillPerCall() {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(provider.complete(any())).thenReturn(response("done", 11, 7));
        when(accrualStore.snapshot("exec-1")).thenReturn(Optional.empty());
        when(creditClient.checkChatBudget(eq("42"), eq(PROVIDER), eq(MODEL), anyInt(), eq(256)))
                .thenReturn(true);

        ResponseEntity<?> response = controller.complete(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, request(false), "exec-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Usage is accrued under the execution, not billed as its own ledger line.
        verify(accrualStore).accrue(eq("exec-1"), eq("42"), eq(PROVIDER), eq(MODEL),
                eq(new CeRelayAccrualStore.AccruedUsage(11, 7, 0, 0, 0, 0)), anyLong());
        verify(creditClient, never()).consumeCredits(any(), any(), any(), any(), any(), any(), any(),
                any(LlmCacheTokens.class));
    }

    @Test
    @DisplayName("centralized gate checks accrued-so-far + next call against the wallet")
    void centralizedGateUsesAccruedPlusNextCall() {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(provider.complete(any())).thenReturn(response("done", 11, 7));
        when(accrualStore.snapshot("exec-1")).thenReturn(Optional.of(new CeRelayAccrualStore.AccruedSnapshot(
                "42", PROVIDER, MODEL, new CeRelayAccrualStore.AccruedUsage(1000, 500, 0, 0, 0, 0), 123L)));
        // est completion = maxTokens (256); accrued completion 500 ⇒ cumulative 756.
        when(creditClient.checkChatBudget(eq("42"), eq(PROVIDER), eq(MODEL), anyInt(), eq(756)))
                .thenReturn(true);

        controller.complete(CLOUD_USER_ID, INSTALL_ID,
                new CloudLlmRelayRequest(PROVIDER, request(false), "exec-1"));

        verify(creditClient).checkChatBudget(eq("42"), eq(PROVIDER), eq(MODEL), anyInt(), eq(756));
    }

    @Test
    @DisplayName("centralized stream accrues the completion usage exactly once and does NOT bill per call")
    void centralizedStreamAccruesOnceAndDoesNotBill() throws Exception {
        CompletionResponse finalResponse = CompletionResponse.builder()
                .content("final").finishReason("stop").model(MODEL)
                .usage(UsageInfo.builder().promptTokens(13).completionTokens(5).totalTokens(18).build())
                .build();
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(accrualStore.snapshot("exec-1")).thenReturn(java.util.Optional.empty());
        when(creditClient.checkChatBudget(eq("42"), eq(PROVIDER), eq(MODEL), anyInt(), eq(256)))
                .thenReturn(true);
        doAnswer(invocation -> {
            StreamingCallback callback = invocation.getArgument(1);
            callback.onChunk("fin");
            callback.onComplete(finalResponse);
            return null;
        }).when(provider).completeStreaming(any(), any());

        ResponseEntity<StreamingResponseBody> response = controller.stream(
                CLOUD_USER_ID, INSTALL_ID, new CloudLlmRelayRequest(PROVIDER, request(true), "exec-1"));
        response.getBody().writeTo(OutputStream.nullOutputStream());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Accrued exactly once (onComplete); the finally fallback is deduped by the AtomicBoolean.
        verify(accrualStore, times(1)).accrue(eq("exec-1"), eq("42"), eq(PROVIDER), eq(MODEL),
                eq(new CeRelayAccrualStore.AccruedUsage(13, 5, 0, 0, 0, 0)), anyLong());
        verify(creditClient, never()).consumeCredits(any(), any(), any(), any(), any(), any(), any(),
                any(LlmCacheTokens.class));
    }

    @Test
    @DisplayName("settle delegates to the settlement service and reports settled=true when billed")
    void settleDelegatesAndReportsBilled() {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(settlementService.settleFromAccrual("exec-1"))
                .thenReturn(CeRelaySettlementService.SettleOutcome.BILLED);

        ResponseEntity<?> response = controller.settle(CLOUD_USER_ID, INSTALL_ID,
                new CeRelaySettleRequest("exec-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("settled", true));
        verify(settlementService).settleFromAccrual("exec-1");
    }

    @Test
    @DisplayName("settle reports settled=false on a RETRY outcome (accrual kept for the reaper)")
    void settleReportsNotSettledOnRetry() {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(settlementService.settleFromAccrual("exec-1"))
                .thenReturn(CeRelaySettlementService.SettleOutcome.RETRY);

        ResponseEntity<?> response = controller.settle(CLOUD_USER_ID, INSTALL_ID,
                new CeRelaySettleRequest("exec-1"));

        assertThat(response.getBody()).isEqualTo(Map.of("settled", false));
    }

    @Test
    @DisplayName("settle reports settled=true on NOTHING_TO_BILL (idempotent / already settled)")
    void settleReportsSettledOnNothingToBill() {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(settlementService.settleFromAccrual("exec-1"))
                .thenReturn(CeRelaySettlementService.SettleOutcome.NOTHING_TO_BILL);

        ResponseEntity<?> response = controller.settle(CLOUD_USER_ID, INSTALL_ID,
                new CeRelaySettleRequest("exec-1"));

        assertThat(response.getBody()).isEqualTo(Map.of("settled", true));
    }

    @Test
    @DisplayName("settle rejects a blank executionId without touching the settlement service")
    void settleRejectsBlankExecutionId() {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);

        ResponseEntity<?> response = controller.settle(CLOUD_USER_ID, INSTALL_ID,
                new CeRelaySettleRequest("  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(settlementService, never()).settleFromAccrual(any());
    }

    @Test
    @DisplayName("release drops the accrual without billing")
    void releaseDropsAccrualWithoutBilling() {
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);

        ResponseEntity<?> response = controller.release(CLOUD_USER_ID, INSTALL_ID,
                new CeRelayReleaseRequest("exec-1", "no llm calls"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("released", true));
        verify(accrualStore).remove("exec-1");
        verify(creditClient, never()).consumeCredits(any(), any(), any(), any(), any(), any(), any(),
                any(LlmCacheTokens.class));
    }

    @Test
    @DisplayName("kill-switch off forces legacy per-call billing even when executionId is present")
    void killSwitchOffForcesLegacyPerCallBilling() {
        CloudLlmRelayController legacyController = new CloudLlmRelayController(
                authClient, creditClient, providerFactory, objectMapper, accrualStore, settlementService,
                modelConfigRepository, false);
        when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
        when(providerFactory.getProvider(PROVIDER)).thenReturn(provider);
        when(provider.getProviderName()).thenReturn(PROVIDER);
        when(provider.complete(any())).thenReturn(response("done", 11, 7));
        when(creditClient.checkChatBudget(eq("42"), eq(PROVIDER), eq(MODEL), anyInt(), eq(256)))
                .thenReturn(true);
        when(creditClient.consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(),
                eq(PROVIDER), eq(MODEL), eq(11), eq(7), any(LlmCacheTokens.class)))
                .thenReturn(Map.of("success", true));

        legacyController.complete(CLOUD_USER_ID, INSTALL_ID,
                new CloudLlmRelayRequest(PROVIDER, request(false), "exec-1"));

        verify(creditClient).consumeCredits(eq("42"), eq("CE_LLM_RELAY"), any(),
                eq(PROVIDER), eq(MODEL), eq(11), eq(7), any(LlmCacheTokens.class));
        verify(accrualStore, never()).accrue(any(), any(), any(), any(), any(), anyLong());
    }

    /** Like {@link #request(boolean)} but with a caller-chosen model id (for the unmanaged-model guard). */
    private CompletionRequest requestWithModel(String model, boolean stream) {
        return CompletionRequest.builder()
                .tenantId("ce-local-user")
                .model(model)
                .systemPrompt("You route tools locally.")
                .userPrompt("Use the local lookup tool.")
                .maxTokens(256)
                .stream(stream)
                .build();
    }

    private CompletionRequest request(boolean stream) {
        return CompletionRequest.builder()
                .tenantId("ce-local-user")
                .model(MODEL)
                .systemPrompt("You route tools locally.")
                .userPrompt("Use the local lookup tool.")
                .maxTokens(256)
                .tools(List.of(ToolDefinition.builder()
                        .id("tool-1")
                        .name("local_lookup")
                        .description("Local tool that must not run in Cloud")
                        .build()))
                .stream(stream)
                // CE-resolved effort must survive the tenant rewrite (ClaudeProvider
                // maps it to output_config.effort on supporting models).
                .reasoningEffort("xhigh")
                .build();
    }

    private static CompletionResponse response(String content, int promptTokens, int completionTokens) {
        return CompletionResponse.builder()
                .content(content)
                .finishReason("stop")
                .model(MODEL)
                .usage(UsageInfo.builder()
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .totalTokens(promptTokens + completionTokens)
                        .build())
                .build();
    }

    private CloudLlmStreamEvent readEvent(String line) {
        try {
            return objectMapper.readValue(line, CloudLlmStreamEvent.class);
        } catch (Exception e) {
            throw new AssertionError("Invalid stream event: " + line, e);
        }
    }

    private static final class DisconnectingOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            throw new IOException("client disconnected");
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            throw new IOException("client disconnected");
        }
    }
}

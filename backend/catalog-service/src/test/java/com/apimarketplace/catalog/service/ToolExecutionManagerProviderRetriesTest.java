package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.service.analytics.ApiCallAnalytics;
import com.apimarketplace.catalog.service.http.ProviderRetryContext;
import com.apimarketplace.catalog.service.relay.CeCatalogCloudRelay;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The PRODUCER of {@code metadata.providerRetries}: the only evidence a step leaves that its
 * provider call was re-sent.
 *
 * <p><b>Why the producer needs its own test.</b> The wait happens inside one tool call, so the node
 * stays RUNNING and emits nothing while it waits. The orchestrator reads this key back out of the
 * response metadata and stamps it on the step output. Both halves are plain strings in two
 * different services, and the orchestrator's test writes the key into its own fixture, so it
 * certifies the reader against an assumption rather than against what is actually produced. Rename
 * or mistype it here and every test on both sides still passes while the count vanishes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ToolExecutionManager - the provider re-send count on the response")
class ToolExecutionManagerProviderRetriesTest {

    /** The key the orchestrator's StepPayloadService reads. */
    private static final String METADATA_KEY = "providerRetries";

    @Mock private ToolContextService toolContextService;
    @Mock private ApiService apiService;
    @Mock private ResponseShaper responseShaper;
    @Mock private NextActionBuilder nextActionBuilder;
    @Mock private ResponseCache responseCache;
    @Mock private com.apimarketplace.catalog.repository.ToolNextHintRepository toolNextHintRepository;
    @Mock private ToolResponseService toolResponseService;
    @Mock private CredentialClient credentialClient;
    @Mock private CeCatalogCloudRelay ceCatalogCloudRelay;
    @Mock private ApiCallAnalytics apiCallAnalytics;

    private ToolExecutionManager manager;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        var orchestrator = new com.apimarketplace.catalog.service.execution.ToolExecutionOrchestrator(
                new com.apimarketplace.catalog.service.execution.OutputProjector(objectMapper));
        var binary = new com.apimarketplace.catalog.service.execution.BinaryResponseHandler(objectMapper);
        manager = new ToolExecutionManager(toolContextService, apiService, objectMapper, responseShaper,
                nextActionBuilder, responseCache, toolNextHintRepository, toolResponseService, orchestrator, binary,
                null, credentialClient, null, ceCatalogCloudRelay);
        manager.setApiCallAnalytics(apiCallAnalytics);

        lenient().when(credentialClient.getCredentialStateVersion(anyString()))
                .thenReturn(CredentialClient.STATE_VERSION_UNAVAILABLE);
        lenient().when(ceCatalogCloudRelay.tryRelay(anyString(), any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(responseShaper.shape(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> new ResponseShaper.ShapingResult(inv.getArgument(0), java.util.List.of(),
                        ResponseShaper.Action.UNTOUCHED, 0, 0));
        lenient().when(nextActionBuilder.build(any(), any(), any())).thenReturn(Optional.empty());

        ToolContextService.ToolContext context = new ToolContextService.ToolContext();
        context.setToolId(UUID.randomUUID().toString());
        context.setApiId(UUID.randomUUID().toString());
        context.setToolName("send_message");
        context.setApiSlug("slack");
        context.setToolSlug("slack-send-message");
        context.setEndpoint("/chat.postMessage");
        context.setHttpMethod("POST");
        context.setAllowedParameterNames(java.util.Set.of("text"));
        when(toolContextService.loadToolContext("slack/send_message")).thenReturn(Optional.of(context));

        lenient().when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                .thenReturn(Map.of("success", true, "data", Map.of("ok", true), "status", 200));
    }

    @BeforeEach
    void startFromAKnownContext() {
        // Cleared on the way IN as well as out. These tests call the manager directly, which in
        // production is only ever reached through a door that opened the call with
        // ProviderRetryContext.begin(...) - so a direct call inherits whatever the fork's thread
        // was carrying. Asserting "no re-sends" from an unknown starting state is not an assertion.
        ProviderRetryContext.clear();
    }

    @AfterEach
    void clearContext() {
        ProviderRetryContext.clear();
    }

    private ToolExecutionResponse execute() {
        return manager.executeTool("slack/send_message", ToolExecutionRequest.builder()
                .parameters(Map.of("text", "hi")).build(), "42", null, "req");
    }

    @Test
    @DisplayName("a re-sent call reports its count under the name the orchestrator reads")
    void aResentCallReportsTheCount() {
        // Written by the execution path, which has already returned by the time the response is
        // assembled - so this test drives the same thread-local the real retry loop writes.
        ProviderRetryContext.recordRetry();
        ProviderRetryContext.recordRetry();

        ToolExecutionResponse response = execute();

        assertThat(response.getMetadata())
                .as("the only trace of a wait that produced no event")
                .containsEntry(METADATA_KEY, 2);
    }

    @Test
    @DisplayName("a call answered first time carries no such key at all")
    void aCallAnsweredFirstTimeCarriesNothing() {
        ToolExecutionResponse response = execute();

        assertThat(response.getMetadata())
                .as("absent, not zero: nothing changes for the overwhelming majority of calls, and "
                        + "a 0 on every response would be noise the reader has to filter")
                .doesNotContainKey(METADATA_KEY);
    }

    @Test
    @DisplayName("the request's budget field binds from the JSON name the gateway sends")
    void theRequestFieldBindsFromTheWireName() throws Exception {
        // The other half of the same wire joint. CatalogToolsGateway writes
        // "providerRetryMaxWaitSeconds" into the body; if this DTO's property were named anything
        // else, Jackson would drop it in SILENCE and the platform would keep its own budget.
        ToolExecutionRequest parsed = new ObjectMapper().readValue(
                "{\"parameters\":{\"text\":\"hi\"},\"providerRetryMaxWaitSeconds\":0}",
                ToolExecutionRequest.class);

        assertThat(parsed.getProviderRetryMaxWaitSeconds())
                .as("0 is the value that matters most, and is the one a null-ish binding would lose")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("an absent budget in the request body stays null, not 0")
    void anAbsentBudgetStaysNull() throws Exception {
        ToolExecutionRequest parsed = new ObjectMapper().readValue(
                "{\"parameters\":{\"text\":\"hi\"}}", ToolExecutionRequest.class);

        assertThat(parsed.getProviderRetryMaxWaitSeconds())
                .as("a primitive int here would read every silent caller as 'never retry'")
                .isNull();
    }
}

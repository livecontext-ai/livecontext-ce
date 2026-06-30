package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CE-side relay provider tests: the {@code web_search} tool relays to the linked
 * cloud deployment ONLY for tenants whose effective LLM source is CLOUD, checked
 * at runtime per call (the link can change while the CE instance runs).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CloudRelayWebSearchToolsProvider (CE)")
class CloudRelayWebSearchToolsProviderTest {

    private static final String TENANT = "7";
    private static final CloudLlmRuntimeCredentials CREDENTIALS =
            new CloudLlmRuntimeCredentials("token-1", "install-1", "https://livecontext.ai/api");

    @Mock
    private CloudLlmRuntimeAccess runtimeAccess;
    @Mock
    private CloudWebSearchRelayClient relayClient;

    private CloudRelayWebSearchToolsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CloudRelayWebSearchToolsProvider(runtimeAccess, relayClient);
    }

    private static ToolExecutionContext context(Map<String, Object> credentials) {
        return new ToolExecutionContext(TENANT, credentials, Map.of(), Set.of(), null, null, null, null);
    }

    @Nested
    @DisplayName("tool exposure (getTools)")
    class Exposure {

        @Test
        @DisplayName("exposes web_search when the relay wiring exists")
        void exposesWebSearchWhenWired() {
            List<AgentToolDefinition> tools = provider.getTools();

            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).name()).isEqualTo("web_search");
            assertThat(tools.get(0).category()).isEqualTo(ToolCategory.WEB_SEARCH);
            // Relay scope is search-only - the definition must not advertise
            // fetch / agent_browse the relay cannot serve.
            assertThat(tools.get(0).description()).doesNotContain("fetch:", "agent_browse");
        }

        @Test
        @DisplayName("exposes NOTHING when no runtime access is wired (CE without remote marketplace)")
        void exposesNothingWithoutRuntimeAccess() {
            provider = new CloudRelayWebSearchToolsProvider(null, relayClient);

            assertThat(provider.getTools()).isEmpty();
        }
    }

    @Nested
    @DisplayName("search - linked tenant")
    class LinkedSearch {

        @Test
        @DisplayName("relays the search with the cloud-link credentials and returns the cloud response")
        void relaysSearchAndReturnsResponse() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
            when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.of(CREDENTIALS));
            Map<String, Object> cloudResponse = Map.of("results", List.of(
                    Map.of("url", "https://example.com", "title", "t", "snippet", "s")));
            when(relayClient.search(any(), any())).thenReturn(cloudResponse);

            ToolExecutionResult result = provider.execute("web_search",
                    Map.of("action", "search", "query", "java 21", "max_results", 5, "time_range", "week"),
                    context(Map.of("__streamId__", "stream-1", "__toolCallId__", "tc-1")));

            assertThat(result.success()).isTrue();
            assertThat(result.data()).isEqualTo(cloudResponse);

            ArgumentCaptor<CeWebSearchRelayRequest> requestCaptor =
                    ArgumentCaptor.forClass(CeWebSearchRelayRequest.class);
            verify(relayClient).search(org.mockito.ArgumentMatchers.eq(CREDENTIALS), requestCaptor.capture());
            CeWebSearchRelayRequest sent = requestCaptor.getValue();
            assertThat(sent.query()).isEqualTo("java 21");
            assertThat(sent.maxResults()).isEqualTo(5);
            assertThat(sent.timeRange()).isEqualTo("week");
            // Chat identifiers travel with the relay so the cloud bills with the
            // same idempotency-safe sourceId scheme as a local search.
            assertThat(sent.streamId()).isEqualTo("stream-1");
            assertThat(sent.toolCallId()).isEqualTo("tc-1");
        }

        @Test
        @DisplayName("relay HTTP failure surfaces as EXTERNAL_SERVICE_ERROR")
        void relayFailureSurfacesAsExternalServiceError() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
            when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.of(CREDENTIALS));
            when(relayClient.search(any(), any()))
                    .thenThrow(new IllegalStateException("Cloud web search relay returned 502"));

            ToolExecutionResult result = provider.execute("web_search",
                    Map.of("action", "search", "query", "java"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXTERNAL_SERVICE_ERROR);
            assertThat(result.error()).contains("502");
        }
    }

    @Nested
    @DisplayName("search - unlinked / BYOK tenant")
    class UnlinkedSearch {

        @Test
        @DisplayName("BYOK tenant gets a failure and NO relay call is made")
        void byokTenantNeverTriggersRelay() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(false);

            ToolExecutionResult result = provider.execute("web_search",
                    Map.of("action", "search", "query", "java"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).isEqualTo(CloudRelayWebSearchToolsProvider.UNAVAILABLE_MESSAGE);
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("CLOUD source selected but link not ready (no credentials) → failure, no relay call")
        void cloudSelectedButLinkNotReadyFailsWithoutRelay() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
            when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.empty());

            ToolExecutionResult result = provider.execute("web_search",
                    Map.of("action", "search", "query", "java"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("link-state resolution failure → fail-closed, no relay call")
        void linkResolutionFailureFailsClosed() {
            when(runtimeAccess.isCloudSelected(TENANT))
                    .thenThrow(new IllegalStateException("publication-service unreachable"));

            ToolExecutionResult result = provider.execute("web_search",
                    Map.of("action", "search", "query", "java"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("no runtime access wired → failure, no relay call")
        void noRuntimeAccessFailsWithoutRelay() {
            provider = new CloudRelayWebSearchToolsProvider(null, relayClient);

            ToolExecutionResult result = provider.execute("web_search",
                    Map.of("action", "search", "query", "java"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(relayClient);
        }
    }

    @Nested
    @DisplayName("parameter validation and help")
    class ValidationAndHelp {

        @Test
        @DisplayName("missing query → MISSING_PARAMETER before any link lookup")
        void missingQueryFailsBeforeLinkLookup() {
            ToolExecutionResult result = provider.execute("web_search",
                    Map.of("action", "search"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            verifyNoInteractions(runtimeAccess, relayClient);
        }

        @Test
        @DisplayName("missing action → MISSING_PARAMETER")
        void missingActionFails() {
            ToolExecutionResult result = provider.execute("web_search",
                    Map.of("query", "java"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("unsupported action (fetch) → VALIDATION_ERROR explaining search-only scope")
        void unsupportedActionFailsWithSearchOnlyHint() {
            ToolExecutionResult result = provider.execute("web_search",
                    Map.of("action", "fetch", "url", "https://example.com"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(result.error()).contains("search");
            verify(relayClient, never()).search(any(), any());
        }

        @Test
        @DisplayName("unknown tool name → TOOL_NOT_FOUND")
        void unknownToolFails() {
            ToolExecutionResult result = provider.execute("other_tool",
                    Map.of("action", "search"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        }

        @Test
        @DisplayName("help is free: succeeds without touching the link or the relay")
        void helpIsFreeAndLocal() {
            ToolExecutionResult result = provider.execute("web_search",
                    Map.of("action", "help"), context(Map.of()));

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data).containsKeys("description", "actions");
            verifyNoInteractions(runtimeAccess, relayClient);
        }
    }
}

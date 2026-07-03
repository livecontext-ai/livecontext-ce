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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CE-side browser-agent relay provider tests: the {@code agent_browse} tool relays a
 * browse (and session-control calls) to the linked cloud ONLY for tenants whose
 * effective LLM source is CLOUD, checked at runtime per call.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CloudRelayBrowserAgentToolsProvider (CE)")
class CloudRelayBrowserAgentToolsProviderTest {

    private static final String TENANT = "7";
    private static final CloudLlmRuntimeCredentials CREDENTIALS =
            new CloudLlmRuntimeCredentials("token-1", "install-1", "https://livecontext.ai/api");

    @Mock
    private CloudLlmRuntimeAccess runtimeAccess;
    @Mock
    private CloudBrowserAgentRelayClient relayClient;

    private CloudRelayBrowserAgentToolsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CloudRelayBrowserAgentToolsProvider(runtimeAccess, relayClient);
    }

    private static ToolExecutionContext context(Map<String, Object> credentials) {
        return new ToolExecutionContext(TENANT, credentials, Map.of(), Set.of(), null, null, null, null);
    }

    @Nested
    @DisplayName("tool exposure (getTools)")
    class Exposure {

        @Test
        @DisplayName("exposes agent_browse when the relay wiring exists")
        void exposesAgentBrowseWhenWired() {
            List<AgentToolDefinition> tools = provider.getTools();

            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).name()).isEqualTo("agent_browse");
            assertThat(tools.get(0).category()).isEqualTo(ToolCategory.WEB_SEARCH);
        }

        @Test
        @DisplayName("exposes NOTHING when no runtime access is wired (CE without remote marketplace)")
        void exposesNothingWithoutRuntimeAccess() {
            provider = new CloudRelayBrowserAgentToolsProvider(null, relayClient);

            assertThat(provider.getTools()).isEmpty();
        }
    }

    @Nested
    @DisplayName("agent_browse - linked tenant")
    class LinkedBrowse {

        @Test
        @DisplayName("relays the browse with the cloud-link credentials + CE run ids and returns the cloud response verbatim")
        void relaysBrowseAndReturnsResponse() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
            when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.of(CREDENTIALS));
            Map<String, Object> cloudResponse = Map.of(
                    "stop_reason", "COMPLETED",
                    "cdp_ws_url", "wss://cloud/cdp/ses_1",
                    "cdp_token", "jwt-1");
            when(relayClient.agentBrowse(any(), any())).thenReturn(cloudResponse);

            ToolExecutionResult result = provider.execute("agent_browse",
                    Map.of("action", "agent_browse", "task", "book a flight",
                            "start_url", "https://example.com", "max_steps", 25,
                            "llm", Map.of("provider", "google", "model", "gemini-2.5-flash"),
                            "interaction_mode", "autonomous"),
                    context(Map.of("__streamId__", "stream-1", "__toolCallId__", "tc-1")));

            assertThat(result.success()).isTrue();
            assertThat(result.data()).isEqualTo(cloudResponse);

            ArgumentCaptor<CeBrowseRelayRequest> captor = ArgumentCaptor.forClass(CeBrowseRelayRequest.class);
            verify(relayClient).agentBrowse(eq(CREDENTIALS), captor.capture());
            CeBrowseRelayRequest sent = captor.getValue();
            assertThat(sent.task()).isEqualTo("book a flight");
            assertThat(sent.startUrl()).isEqualTo("https://example.com");
            assertThat(sent.maxSteps()).isEqualTo(25);
            assertThat(sent.llm()).containsEntry("provider", "google");
            assertThat(sent.options()).containsEntry("interaction_mode", "autonomous");
            // CE chat ids ride the relay so the cloud CDP token carries matching rid/nid.
            assertThat(sent.streamId()).isEqualTo("stream-1");
            assertThat(sent.toolCallId()).isEqualTo("tc-1");
        }

        @Test
        @DisplayName("relay HTTP failure surfaces as EXTERNAL_SERVICE_ERROR")
        void relayFailureSurfacesAsExternalServiceError() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
            when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.of(CREDENTIALS));
            when(relayClient.agentBrowse(any(), any()))
                    .thenThrow(new IllegalStateException("Cloud browser agent relay returned 502"));

            ToolExecutionResult result = provider.execute("agent_browse",
                    Map.of("action", "agent_browse", "task", "x"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXTERNAL_SERVICE_ERROR);
            assertThat(result.error()).contains("502");
        }

        @Test
        @DisplayName("missing task → MISSING_PARAMETER (after link check, before relay)")
        void missingTaskFails() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
            when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.of(CREDENTIALS));

            ToolExecutionResult result = provider.execute("agent_browse",
                    Map.of("action", "agent_browse"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            verify(relayClient, org.mockito.Mockito.never()).agentBrowse(any(), any());
        }
    }

    @Nested
    @DisplayName("session control - linked tenant")
    class LinkedControl {

        @Test
        @DisplayName("browse_status relays to browseControl with the status verb")
        void relaysStatus() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
            when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.of(CREDENTIALS));
            when(relayClient.browseControl(any(), eq("ses_1"), eq("status"), any()))
                    .thenReturn(Map.of("status", "running"));

            ToolExecutionResult result = provider.execute("agent_browse",
                    Map.of("action", "browse_status", "session_id", "ses_1"), context(Map.of()));

            assertThat(result.success()).isTrue();
            assertThat(result.data()).isEqualTo(Map.of("status", "running"));
            ArgumentCaptor<CeBrowseControlRequest> captor =
                    ArgumentCaptor.forClass(CeBrowseControlRequest.class);
            verify(relayClient).browseControl(eq(CREDENTIALS), eq("ses_1"), eq("status"), captor.capture());
            assertThat(captor.getValue().sessionId()).isEqualTo("ses_1");
            assertThat(captor.getValue().hint()).isNull();
        }

        @Test
        @DisplayName("browse_intervene threads the hint into the control request")
        void relaysInterveneHint() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
            when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.of(CREDENTIALS));
            when(relayClient.browseControl(any(), eq("ses_1"), eq("intervene"), any()))
                    .thenReturn(Map.of("ok", true));

            provider.execute("agent_browse",
                    Map.of("action", "browse_intervene", "session_id", "ses_1", "hint", "click Accept"),
                    context(Map.of()));

            ArgumentCaptor<CeBrowseControlRequest> captor =
                    ArgumentCaptor.forClass(CeBrowseControlRequest.class);
            verify(relayClient).browseControl(eq(CREDENTIALS), eq("ses_1"), eq("intervene"), captor.capture());
            assertThat(captor.getValue().hint()).isEqualTo("click Accept");
        }

        @Test
        @DisplayName("missing session_id → MISSING_PARAMETER, no relay call")
        void missingSessionIdFails() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
            when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.of(CREDENTIALS));

            ToolExecutionResult result = provider.execute("agent_browse",
                    Map.of("action", "browse_status"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            verify(relayClient, org.mockito.Mockito.never()).browseControl(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("unlinked / BYOK tenant")
    class Unlinked {

        @Test
        @DisplayName("BYOK tenant gets PERMISSION_DENIED and NO relay call")
        void byokTenantNeverTriggersRelay() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(false);

            ToolExecutionResult result = provider.execute("agent_browse",
                    Map.of("action", "agent_browse", "task", "x"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).isEqualTo(CloudRelayBrowserAgentToolsProvider.UNAVAILABLE_MESSAGE);
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("CLOUD selected but link not ready (no credentials) → PERMISSION_DENIED, no relay call")
        void cloudSelectedButLinkNotReady() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
            when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.empty());

            ToolExecutionResult result = provider.execute("agent_browse",
                    Map.of("action", "agent_browse", "task", "x"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("link-state resolution failure → fail-closed, no relay call")
        void linkResolutionFailsClosed() {
            when(runtimeAccess.isCloudSelected(TENANT))
                    .thenThrow(new IllegalStateException("publication-service unreachable"));

            ToolExecutionResult result = provider.execute("agent_browse",
                    Map.of("action", "agent_browse", "task", "x"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(relayClient);
        }
    }

    @Nested
    @DisplayName("validation and help")
    class ValidationAndHelp {

        @Test
        @DisplayName("missing action → MISSING_PARAMETER")
        void missingActionFails() {
            ToolExecutionResult result = provider.execute("agent_browse",
                    Map.of("task", "x"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            verifyNoInteractions(runtimeAccess, relayClient);
        }

        @Test
        @DisplayName("unknown action → VALIDATION_ERROR")
        void unknownActionFails() {
            ToolExecutionResult result = provider.execute("agent_browse",
                    Map.of("action", "teleport"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            verifyNoInteractions(runtimeAccess, relayClient);
        }

        @Test
        @DisplayName("unknown tool name → TOOL_NOT_FOUND")
        void unknownToolFails() {
            ToolExecutionResult result = provider.execute("other_tool",
                    Map.of("action", "agent_browse"), context(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        }

        @Test
        @DisplayName("help is free: succeeds without touching the link or the relay")
        void helpIsFreeAndLocal() {
            ToolExecutionResult result = provider.execute("agent_browse",
                    Map.of("action", "help"), context(Map.of()));

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data).containsKeys("description", "actions");
            verifyNoInteractions(runtimeAccess, relayClient);
        }
    }
}

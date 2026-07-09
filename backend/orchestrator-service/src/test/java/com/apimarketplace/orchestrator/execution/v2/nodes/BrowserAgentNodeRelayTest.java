package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.tools.websearch.CeBrowseRelayRequest;
import com.apimarketplace.orchestrator.tools.websearch.CloudBrowserAgentRelayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cloud-linked CE relay branch of {@link BrowserAgentNode}: when the local
 * {@link com.apimarketplace.orchestrator.tools.websearch.BrowserAgentModule} is absent
 * but the install is cloud-linked, the node forwards the browse to the cloud instead of
 * erroring, preserves the output contract (result + cloud-hosted CDP url), and records
 * NO local observability (billing happens once on the cloud). When the relay is
 * unavailable it keeps the actionable failure.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrowserAgentNode - cloud-linked CE relay branch")
class BrowserAgentNodeRelayTest {

    private static final String TENANT = "tenant-1";
    private static final CloudLlmRuntimeCredentials CREDENTIALS =
            new CloudLlmRuntimeCredentials("token-1", "install-1", "https://livecontext.ai/api");

    @Mock
    private CloudBrowserAgentRelayClient relayClient;
    @Mock
    private CloudLlmRuntimeAccess runtimeAccess;
    @Mock
    private AgentClient agentClient;
    @Mock
    private UnifiedSignalService signalService;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create("run-7", "wr-7", TENANT, "item-0", 0, Map.of(), null);
    }

    private BrowserAgentNode relayNode(Map<String, Object> config) {
        BrowserAgentNode node = new BrowserAgentNode("browser:test", config);
        node.setCloudBrowserRelayClient(relayClient);
        node.setCloudRuntimeAccess(runtimeAccess);
        // Wire the local billing/observability collaborator AND the takeover-signal
        // service so the relay branch can be proven to touch NEITHER: the cloud is
        // the sole biller, and the relay path never yields a takeover signal.
        node.setAgentClient(agentClient);
        node.setSignalService(signalService);
        return node;
    }

    @Test
    @DisplayName("relays the browse to the cloud and surfaces the cloud CDP url in the node output")
    void relaysBrowseAndPreservesCdpUrl() {
        when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
        when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.of(CREDENTIALS));
        Map<String, Object> cloudOutput = Map.of(
                "stop_reason", "COMPLETED",
                "session_id", "ses_1",
                "cdp_ws_url", "wss://cloud.example.com/cdp/ses_1",
                "cdp_token", "jwt-cloud",
                "final_result", "done");
        when(relayClient.agentBrowse(any(), any())).thenReturn(cloudOutput);

        BrowserAgentNode node = relayNode(Map.of(
                "task", "book a flight", "start_url", "https://example.com", "max_steps", 25,
                "llm", Map.of("provider", "google"), "interaction_mode", "autonomous"));

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isSuccess()).isTrue();
        // Output contract preserved: cloud CDP url/token pass through unchanged.
        assertThat(result.output())
                .containsEntry("cdp_ws_url", "wss://cloud.example.com/cdp/ses_1")
                .containsEntry("cdp_token", "jwt-cloud")
                .containsEntry("session_id", "ses_1")
                .containsEntry("node_type", "BROWSER_AGENT");

        ArgumentCaptor<CeBrowseRelayRequest> captor = ArgumentCaptor.forClass(CeBrowseRelayRequest.class);
        verify(relayClient).agentBrowse(any(), captor.capture());
        CeBrowseRelayRequest sent = captor.getValue();
        assertThat(sent.task()).isEqualTo("book a flight");
        assertThat(sent.startUrl()).isEqualTo("https://example.com");
        assertThat(sent.maxSteps()).isEqualTo(25);
        assertThat(sent.options()).containsEntry("interaction_mode", "autonomous");
        // The run id doubles as streamId, the node id as toolCallId (live-view routing).
        assertThat(sent.streamId()).isEqualTo("run-7");
        assertThat(sent.toolCallId()).isEqualTo("browser:test");
        // CE side bills/records NOTHING on the relay path - the cloud account is the
        // sole writer of the agent_executions row (no double charge).
        verifyNoInteractions(agentClient);
    }

    @Test
    @DisplayName("relay HTTP failure → node FAILED with the cloud error, partial output carries the error")
    void relayFailureMapsToFailedNode() {
        when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
        when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.of(CREDENTIALS));
        when(relayClient.agentBrowse(any(), any()))
                .thenThrow(new IllegalStateException("Cloud browser agent relay returned 502"));

        BrowserAgentNode node = relayNode(Map.of("task", "x"));

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).get().asString().contains("502");
    }

    @Test
    @DisplayName("cloud collapses a USER_TAKEOVER session to a 502 → node FAILED, and the relay path raises NO takeover signal (disclosed gap: relay cannot yield BROWSER_USER_TAKEOVER)")
    void relayUserTakeoverSurfacesFailedWithoutTakeoverSignal() {
        when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
        when(runtimeAccess.resolveCloudRuntime(TENANT)).thenReturn(Optional.of(CREDENTIALS));
        // The cloud relay controller collapses any non-COMPLETED browse session
        // (including USER_TAKEOVER) to a 502, so the relay client rethrows rather
        // than returning a body with stop_reason=USER_TAKEOVER.
        when(relayClient.agentBrowse(any(), any()))
                .thenThrow(new IllegalStateException(
                        "Cloud browser agent relay returned 502: USER_TAKEOVER"));

        BrowserAgentNode node = relayNode(Map.of("task", "x"));

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).get().asString().contains("502");
        // Unlike the LOCAL module path (which raises a blocking BROWSER_USER_TAKEOVER
        // signal on stop_reason=USER_TAKEOVER), the relay branch never inspects
        // stop_reason: a takeover in the cloud NEVER produces a takeover signal here.
        verifyNoInteractions(signalService);
        // And still no local billing/observability on the failed relay.
        verifyNoInteractions(agentClient);
    }

    @Test
    @DisplayName("BYOK / unlinked tenant → node FAILED with the actionable cloud-link message, no relay call")
    void unlinkedTenantKeepsActionableError() {
        when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(false);

        BrowserAgentNode node = relayNode(Map.of("task", "x"));

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).get().asString().contains("cloud link");
        verifyNoInteractions(relayClient);
    }

    @Test
    @DisplayName("no relay wiring at all → node FAILED (local module absent, nothing to relay to)")
    void noRelayWiringFails() {
        BrowserAgentNode node = new BrowserAgentNode("browser:test", Map.of("task", "x"));
        // No module, no relay client, no runtime access.

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isSuccess()).isFalse();
        verifyNoInteractions(relayClient);
    }

    @Test
    @DisplayName("acceptServices does NOT throw when the local module is absent but the relay is wired")
    void acceptServicesAllowsRelayOnlyWiring() {
        BrowserAgentNode node = new BrowserAgentNode("browser:test", Map.of("task", "x"));

        ServiceRegistry registry = ServiceRegistry.builder()
                .cloudBrowserAgentRelayClient(relayClient)
                .cloudLlmRuntimeAccess(runtimeAccess)
                .build();

        // No exception - relay-only wiring is a valid CE configuration.
        node.acceptServices(registry);
    }

    @Test
    @DisplayName("acceptServices throws when BOTH the local module AND the relay are absent")
    void acceptServicesFailsWhenNeitherWired() {
        BrowserAgentNode node = new BrowserAgentNode("browser:test", Map.of("task", "x"));

        ServiceRegistry registry = ServiceRegistry.builder().build();

        assertThatThrownBy(() -> node.acceptServices(registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no browser stack is available")
                .hasMessageContaining("websearch.enabled");
    }
}

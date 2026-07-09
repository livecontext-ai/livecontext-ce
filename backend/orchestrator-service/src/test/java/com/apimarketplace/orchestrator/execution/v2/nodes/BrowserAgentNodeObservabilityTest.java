package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.tools.websearch.BrowserAgentModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Observability + ServiceRegistry wiring contract for {@link BrowserAgentNode}.
 *
 * <p>Covers two distinct concerns:</p>
 * <ul>
 *   <li><b>Wiring</b> - {@link BrowserAgentNode#acceptServices} pulls
 *       {@link BrowserAgentModule} and {@link AgentClient} from the registry and
 *       fails loudly when either is missing.</li>
 *   <li><b>Recording</b> - after a successful runner session, the node posts an
 *       {@link AgentObservabilityRequest} with {@code agent_type='browser_agent'},
 *       canonical stop reason, mapped tokens, and one iteration/tool-call entry
 *       per browser step.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrowserAgentNode - ServiceRegistry wiring + observability recording")
class BrowserAgentNodeObservabilityTest {

    @Mock private BrowserAgentModule browserAgentModule;
    @Mock private AgentClient agentClient;
    @Mock private WorkflowPlan plan;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
            "run-1", "wr-1", "tenant-1", "item-0", 0,
            Map.of(), plan
        );
    }

    private ServiceRegistry registryWith(BrowserAgentModule module, AgentClient client) {
        return ServiceRegistry.builder()
            .browserAgentModule(module)
            .agentClient(client)
            .build();
    }

    @Test
    @DisplayName("acceptServices pulls BrowserAgentModule and AgentClient from the registry")
    void acceptServicesWiresBothBeans() {
        BrowserAgentNode node = new BrowserAgentNode("browser:test", Map.of("task", "find news"));

        node.acceptServices(registryWith(browserAgentModule, agentClient));

        // Indirect verification: execute() short-circuits with "module not injected" when the
        // module is missing. If acceptServices wired the module correctly, execute() proceeds
        // to call browserAgentModule.execute(). We verify by triggering a session.
        when(browserAgentModule.execute(eq("agent_browse"), anyMap(), anyString(), any()))
            .thenReturn(Optional.of(ToolExecutionResult.success(Map.of(
                "stop_reason", "COMPLETED",
                "steps", List.of()
            ))));

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isSuccess()).isTrue();
        verify(browserAgentModule, times(1))
            .execute(eq("agent_browse"), anyMap(), eq("tenant-1"), any());
    }

    @Test
    @DisplayName("acceptServices throws IllegalStateException when BrowserAgentModule is missing")
    void acceptServicesFailsLoudOnMissingModule() {
        BrowserAgentNode node = new BrowserAgentNode("browser:test", Map.of());

        assertThatThrownBy(() -> node.acceptServices(registryWith(null, agentClient)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no browser stack is available")
            .hasMessageContaining("websearch.enabled");
    }

    @Test
    @DisplayName("acceptServices throws IllegalStateException when AgentClient is missing")
    void acceptServicesFailsLoudOnMissingAgentClient() {
        BrowserAgentNode node = new BrowserAgentNode("browser:test", Map.of());

        assertThatThrownBy(() -> node.acceptServices(registryWith(browserAgentModule, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AgentClient")
            .hasMessageContaining("agent-service");
    }

    @Test
    @DisplayName("recordObservability builds USER/ASSISTANT timeline so Agent Performance side panel can replay the run")
    void recordsConversationTimeline() {
        // Workflow path: nodeConfig has the task verbatim (no templates here),
        // and the runner returns extracted_data + final_result. The
        // synthesised timeline must contain USER (task) + ASSISTANT (each
        // extracted entry) + ASSISTANT (final_result, deduped if identical).
        BrowserAgentNode node = new BrowserAgentNode(
            "browser:replay",
            Map.of("task", "Open example.com and read the H1",
                   "llm", Map.of("provider", "google", "model", "gemini-2.5-flash"))
        );
        node.acceptServices(registryWith(browserAgentModule, agentClient));

        Map<String, Object> rawOutput = new LinkedHashMap<>();
        rawOutput.put("stop_reason", "COMPLETED");
        rawOutput.put("steps", List.of());
        rawOutput.put("cost", Map.of("tokens_in", 100, "tokens_out", 20, "llm_calls", 1));
        rawOutput.put("extracted_data", List.of(
            "Navigated to https://example.com",
            "<result>Example Domain</result>"
        ));
        rawOutput.put("final_result", "The H1 text is 'Example Domain'");

        when(browserAgentModule.execute(eq("agent_browse"), anyMap(), anyString(), any()))
            .thenReturn(Optional.of(ToolExecutionResult.success(rawOutput)));

        node.execute(context);

        ArgumentCaptor<AgentObservabilityRequest> captor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient, times(1)).recordObservability(captor.capture());
        List<AgentObservabilityRequest.MessageData> msgs = captor.getValue().getMessages();
        assertThat(msgs).hasSize(4);
        assertThat(msgs.get(0).getRole()).isEqualTo("USER");
        assertThat(msgs.get(0).getContent()).isEqualTo("Open example.com and read the H1");
        assertThat(msgs.get(1).getRole()).isEqualTo("ASSISTANT");
        assertThat(msgs.get(1).getContent()).contains("Navigated to https://example.com");
        assertThat(msgs.get(2).getRole()).isEqualTo("ASSISTANT");
        assertThat(msgs.get(2).getContent()).contains("Example Domain");
        assertThat(msgs.get(3).getRole()).isEqualTo("ASSISTANT");
        assertThat(msgs.get(3).getContent()).isEqualTo("The H1 text is 'Example Domain'");
    }

    @Test
    @DisplayName("recordObservability posts agent_type='browser_agent' with canonical COMPLETED stop reason")
    void recordsBrowserAgentObservabilityOnSuccess() {
        BrowserAgentNode node = new BrowserAgentNode(
            "browser:scraper",
            Map.of("task", "scrape", "llm", Map.of("provider", "anthropic", "model", "claude-sonnet"))
        );
        node.acceptServices(registryWith(browserAgentModule, agentClient));

        Map<String, Object> step1 = new LinkedHashMap<>();
        step1.put("action", "navigate");
        step1.put("target", "https://example.com");
        step1.put("eval", "page loaded");
        step1.put("memory", "homepage seen");
        step1.put("next_goal", "click signup");
        step1.put("screenshot_key", "s3://shots/1.png");
        step1.put("duration_ms", 250L);

        Map<String, Object> step2 = new LinkedHashMap<>();
        step2.put("action", "click");
        step2.put("target", "#signup");
        step2.put("duration_ms", 80L);

        Map<String, Object> rawOutput = new LinkedHashMap<>();
        rawOutput.put("stop_reason", "COMPLETED");
        rawOutput.put("steps", List.of(step1, step2));
        rawOutput.put("cost", Map.of("tokens_in", 1200, "tokens_out", 340, "llm_calls", 2));
        rawOutput.put("session_id", "sess-abc");
        rawOutput.put("final_url", "https://example.com/welcome");

        when(browserAgentModule.execute(eq("agent_browse"), anyMap(), anyString(), any()))
            .thenReturn(Optional.of(ToolExecutionResult.success(rawOutput)));

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isSuccess()).isTrue();

        ArgumentCaptor<AgentObservabilityRequest> captor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient, times(1)).recordObservability(captor.capture());

        AgentObservabilityRequest req = captor.getValue();
        assertThat(req.getAgentType()).isEqualTo("browser_agent");
        assertThat(req.getNodeId()).isEqualTo("browser:scraper");
        assertThat(req.getRunId()).isEqualTo("run-1");
        assertThat(req.getTenantId()).isEqualTo("tenant-1");
        assertThat(req.getStatus()).isEqualTo("COMPLETED");
        assertThat(req.getStopReason()).isEqualTo("COMPLETED");
        assertThat(req.getIterationCount()).isEqualTo(2);
        assertThat(req.getTotalToolCalls()).isEqualTo(2);
        assertThat(req.getPromptTokens()).isEqualTo(1200L);
        assertThat(req.getCompletionTokens()).isEqualTo(340L);
        assertThat(req.getTotalTokens()).isEqualTo(1540L);
        assertThat(req.getProvider()).isEqualTo("anthropic");
        assertThat(req.getModel()).isEqualTo("claude-sonnet");
        assertThat(req.getIterations()).hasSize(2);
        assertThat(req.getToolCalls()).hasSize(2);
        assertThat(req.getToolCalls().get(0).getToolName()).isEqualTo("navigate");
        assertThat(req.getToolCalls().get(0).getMetadata())
            .containsEntry("eval", "page loaded")
            .containsEntry("memory", "homepage seen")
            .containsEntry("next_goal", "click signup")
            .containsEntry("screenshot_key", "s3://shots/1.png");
    }

    @Test
    @DisplayName("recordObservability still fires on failure path with mapped stop reason")
    void recordsObservabilityOnFailureWithMappedStopReason() {
        BrowserAgentNode node = new BrowserAgentNode("browser:test", Map.of("task", "x"));
        node.acceptServices(registryWith(browserAgentModule, agentClient));

        Map<String, Object> rawOutput = new LinkedHashMap<>();
        rawOutput.put("stop_reason", "MAX_STEPS");
        rawOutput.put("steps", List.of());

        when(browserAgentModule.execute(eq("agent_browse"), anyMap(), anyString(), any()))
            .thenReturn(Optional.of(new ToolExecutionResult(
                false, rawOutput, "step budget exhausted", null, Map.of()
            )));

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isSuccess()).isFalse();

        ArgumentCaptor<AgentObservabilityRequest> captor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient, times(1)).recordObservability(captor.capture());

        AgentObservabilityRequest req = captor.getValue();
        assertThat(req.getStatus()).isEqualTo("FAILED");
        // Runner's free-form MAX_STEPS canonicalises to MAX_ITERATIONS in the observability row.
        assertThat(req.getStopReason()).isEqualTo("MAX_ITERATIONS");
        assertThat(req.getErrorMessage()).isEqualTo("step budget exhausted");
    }

    @Test
    @DisplayName("recordObservability propagates cache_read + cache_creation tokens onto the request")
    void recordsCacheTokensFromRunnerCostBlock() {
        // Pins the contract from runner.py::_extract_token_usage:
        //   cost.cache_read_tokens     → req.cacheReadTokens
        //   cost.cache_creation_tokens → req.cacheCreationTokens
        // Without this, browser-use's cache hit data flows from the Python
        // runner all the way to MinIO/Postgres but the auth-service ledger
        // never sees it - silent under-discount once cache-aware billing
        // ships in v0.1.
        BrowserAgentNode node = new BrowserAgentNode(
            "browser:cache-aware",
            Map.of("task", "scrape", "llm", Map.of("provider", "anthropic", "model", "claude-sonnet-4-6"))
        );
        node.acceptServices(registryWith(browserAgentModule, agentClient));

        Map<String, Object> step1 = new LinkedHashMap<>();
        step1.put("action", "navigate");
        step1.put("duration_ms", 250L);

        Map<String, Object> rawOutput = new LinkedHashMap<>();
        rawOutput.put("stop_reason", "COMPLETED");
        rawOutput.put("steps", List.of(step1));
        rawOutput.put("cost", Map.of(
            "tokens_in", 5000,            // includes the 3000 cached portion (subset convention)
            "tokens_out", 800,
            "cache_read_tokens", 3000,
            "cache_creation_tokens", 500,
            "llm_calls", 4,
            "browser_seconds", 12.5
        ));
        rawOutput.put("session_id", "sess-cache");

        when(browserAgentModule.execute(eq("agent_browse"), anyMap(), anyString(), any()))
            .thenReturn(Optional.of(ToolExecutionResult.success(rawOutput)));

        node.execute(context);

        ArgumentCaptor<AgentObservabilityRequest> captor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient, times(1)).recordObservability(captor.capture());

        AgentObservabilityRequest req = captor.getValue();
        assertThat(req.getPromptTokens()).isEqualTo(5000L);
        assertThat(req.getCompletionTokens()).isEqualTo(800L);
        assertThat(req.getCacheReadTokens()).isEqualTo(3000L);
        assertThat(req.getCacheCreationTokens()).isEqualTo(500L);
        // browser_seconds → durationMs (12.5s = 12500ms)
        assertThat(req.getDurationMs()).isEqualTo(12500L);
    }

    @Test
    @DisplayName("recordObservability leaves cache fields at zero when runner reports no cache usage")
    void doesNotSetCacheTokensWhenZero() {
        // When the cost block reports cache_read=0 / cache_creation=0
        // (non-Anthropic providers, or Anthropic without prompt-caching),
        // the setters are skipped entirely so the DTO defaults (0) flow
        // through. Mirrors the existing prompt/completion skip-on-zero
        // contract - the row still records iterations + duration, just
        // doesn't pollute the cache columns with explicit zeros.
        BrowserAgentNode node = new BrowserAgentNode(
            "browser:nocache",
            Map.of("task", "scrape", "llm", Map.of("provider", "google", "model", "gemini-2.5-flash"))
        );
        node.acceptServices(registryWith(browserAgentModule, agentClient));

        Map<String, Object> rawOutput = new LinkedHashMap<>();
        rawOutput.put("stop_reason", "COMPLETED");
        rawOutput.put("steps", List.of());
        rawOutput.put("cost", Map.of(
            "tokens_in", 1000,
            "tokens_out", 200,
            "cache_read_tokens", 0,
            "cache_creation_tokens", 0,
            "llm_calls", 1
        ));

        when(browserAgentModule.execute(eq("agent_browse"), anyMap(), anyString(), any()))
            .thenReturn(Optional.of(ToolExecutionResult.success(rawOutput)));

        node.execute(context);

        ArgumentCaptor<AgentObservabilityRequest> captor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient, times(1)).recordObservability(captor.capture());

        AgentObservabilityRequest req = captor.getValue();
        assertThat(req.getPromptTokens()).isEqualTo(1000L);
        assertThat(req.getCompletionTokens()).isEqualTo(200L);
        assertThat(req.getCacheReadTokens()).isEqualTo(0L);
        assertThat(req.getCacheCreationTokens()).isEqualTo(0L);
    }

    @Test
    @DisplayName("recordObservability is NOT called when the module never handled the action (defensive)")
    void doesNotRecordWhenModuleReturnsEmpty() {
        BrowserAgentNode node = new BrowserAgentNode("browser:test", Map.of());
        node.acceptServices(registryWith(browserAgentModule, agentClient));

        when(browserAgentModule.execute(eq("agent_browse"), anyMap(), anyString(), any()))
            .thenReturn(Optional.empty());

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isSuccess()).isFalse();
        // Empty Optional means the runner never produced a session - there's nothing to record.
        verify(agentClient, never()).recordObservability(any());
    }
}

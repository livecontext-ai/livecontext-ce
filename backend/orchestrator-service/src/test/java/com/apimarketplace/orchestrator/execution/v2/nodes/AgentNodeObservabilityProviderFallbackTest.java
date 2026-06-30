package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the provider/model fallback on the observability request built by
 * {@link AgentNode#buildObservabilityRequest}.
 *
 * <p>Prod incident (2026-04-24): WORKFLOW agent rows landed with
 * {@code provider=NULL,model=NULL} because the remote {@link AgentExecutionResponseDto}
 * returned null for provider/model even though the agent had configured openai/gpt-4o.
 * Downstream, {@code CreditConsumptionClient} fell back to the literal strings
 * "unknown"/"unknown" on the ledger and {@code ModelPricingService} applied
 * {@code DEFAULT_INPUT_RATE/OUTPUT_RATE}, producing a bogus cost line. The fix is a belt
 * at the {@code AgentNode} boundary: prefer the runtime values, fall back to
 * {@code agentConfig.provider()/model()} when missing or blank.
 *
 * <p>Kept in its own file because {@code AgentNodeTest.java} is excluded from test
 * compile (see {@code pom.xml testExcludes}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentNode - observability provider/model fallback")
class AgentNodeObservabilityProviderFallbackTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private AgentClient mockAgentClient;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("user_input", "Analyze");
        context = ExecutionContext.create(
            "run-fallback-1",
            "workflow-run-fallback-1",
            "tenant-fallback-1",
            "item-0",
            0,
            triggerData,
            mockPlan
        );
    }

    private Agent agentWithProviderModel(String provider, String model) {
        return new Agent(
            "agent-config-1", "agent", "Test Agent", null, null,
            provider, model,
            "You are a test agent", "Analyze this",
            0.7, 4096, 10, 5,
            List.of(), null, Map.of(), List.of(),
            null, List.of(), null, null
        );
    }

    private AgentNode nodeFor(Agent agent) {
        AgentNode node = new AgentNode("agent:test_node", agent);
        ServiceRegistry registry = ServiceRegistry.builder()
            .agentClient(mockAgentClient)
            .build();
        node.acceptServices(registry);
        return node;
    }

    private AgentExecutionResponseDto responseWithRuntime(String provider, String model) {
        return new AgentExecutionResponseDto(
            true,                    // success
            "Analyzer response",     // finalResponse
            "Analyzer response",     // content
            List.of(),               // toolResults
            1,                       // iterations
            Map.of(),                // totalUsage
            null,                    // error
            250L,                    // durationMs
            provider,                // provider (runtime)
            model,                   // model (runtime)
            List.of(),               // conversationHistory
            null,                    // stopReason
            Map.of(),                // metrics
            List.of(),               // usagePerIteration
            List.of(),               // iterationDurations
            List.of(),               // finishReasonsPerIteration
            null,                    // thinkingSections
            null,                    // orderedEntries
            null                     // budgetScope
        );
    }

    @Test
    @DisplayName("Propagates runtime provider/model onto the observability row when the runtime supplied them")
    void usesRuntimeProviderAndModel() {
        Agent agent = agentWithProviderModel("openai", "gpt-4o");
        AgentNode node = nodeFor(agent);

        // Runtime actually ran openrouter/llama-3-70b - must win over agentConfig values.
        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(responseWithRuntime("openrouter", "llama-3-70b"));

        node.execute(context);

        ArgumentCaptor<AgentObservabilityRequest> captor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(mockAgentClient).recordObservability(captor.capture());
        AgentObservabilityRequest obs = captor.getValue();

        assertThat(obs.getProvider()).isEqualTo("openrouter");
        assertThat(obs.getModel()).isEqualTo("llama-3-70b");
    }

    @Test
    @DisplayName("Falls back to agentConfig provider/model when the runtime response omitted them (null)")
    void fallsBackWhenRuntimeProviderNull() {
        Agent agent = agentWithProviderModel("openai", "gpt-4o");
        AgentNode node = nodeFor(agent);

        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(responseWithRuntime(null, null));

        node.execute(context);

        ArgumentCaptor<AgentObservabilityRequest> captor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(mockAgentClient).recordObservability(captor.capture());
        AgentObservabilityRequest obs = captor.getValue();

        assertThat(obs.getProvider()).isEqualTo("openai");
        assertThat(obs.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    @DisplayName("Treats blank/whitespace runtime provider/model as missing and uses agentConfig fallback")
    void fallsBackWhenRuntimeProviderBlank() {
        Agent agent = agentWithProviderModel("openai", "gpt-4o");
        AgentNode node = nodeFor(agent);

        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(responseWithRuntime("   ", ""));

        node.execute(context);

        ArgumentCaptor<AgentObservabilityRequest> captor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(mockAgentClient).recordObservability(captor.capture());
        AgentObservabilityRequest obs = captor.getValue();

        assertThat(obs.getProvider()).isEqualTo("openai");
        assertThat(obs.getModel()).isEqualTo("gpt-4o");
    }
}

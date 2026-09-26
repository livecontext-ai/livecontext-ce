package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * agent-service writes whether a run executed on an admin replacement (the configured model
 * is disabled) on the response metrics. The orchestrator builds the observability report, so
 * it must copy that outcome for {@code agent_run_stopped.model_replaced} to ever be non-empty.
 */
@DisplayName("AgentNode.stampModelReplacement")
class AgentNodeStampModelReplacementTest {

    @Test
    @DisplayName("a replaced run carries model_replaced=true and the disabled model id")
    void replacedRunIsStamped() {
        AgentObservabilityRequest req = new AgentObservabilityRequest();

        AgentNode.stampModelReplacement(req, Map.of("modelReplaced", true, "replacedModel", "old-model"));

        assertThat(req.getModelReplaced()).isTrue();
        assertThat(req.getReplacedModel()).isEqualTo("old-model");
    }

    @Test
    @DisplayName("a resolver that swapped nothing stamps false and no replaced model")
    void notReplacedRunIsStampedFalse() {
        AgentObservabilityRequest req = new AgentObservabilityRequest();

        AgentNode.stampModelReplacement(req, Map.of("modelReplaced", false, "replacedModel", "ignored"));

        assertThat(req.getModelReplaced()).isFalse();
        assertThat(req.getReplacedModel()).isNull();
    }

    @Test
    @DisplayName("no replacement metric (older agent-service) stamps nothing, so false is never guessed")
    void absentMetricStampsNothing() {
        AgentObservabilityRequest req = new AgentObservabilityRequest();

        AgentNode.stampModelReplacement(req, Map.of("keyRoute", "PLATFORM"));
        AgentNode.stampModelReplacement(req, null);

        assertThat(req.getModelReplaced()).isNull();
        assertThat(req.getReplacedModel()).isNull();
    }

    @Test
    @DisplayName("wiring (inline path): a run whose response metrics say modelReplaced reaches the observability row")
    void inlineAgentPathStampsTheReport() {
        AgentClient agentClient = mock(AgentClient.class);
        Agent agent = new Agent(
            "agent-config-1", "agent", "Test Agent", null, null,
            "openai", "old-model",
            "You are a test agent", "Analyze this",
            0.7, 4096, 10, 5,
            List.of(), null, Map.of(), List.of(),
            null, List.of(), null, null
        );
        AgentNode node = new AgentNode("agent:test_node", agent);
        node.acceptServices(ServiceRegistry.builder().agentClient(agentClient).build());
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("user_input", "Analyze");
        ExecutionContext context = ExecutionContext.create("run-1", "workflow-run-1", "tenant-1", "item-0", 0,
                triggerData, mock(WorkflowPlan.class));
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(new AgentExecutionResponseDto(
                true, "done", "done", List.of(), 1, Map.of(), null, 250L,
                "openai", "new-model", List.of(), null,
                Map.of("modelReplaced", true, "replacedModel", "old-model"),
                List.of(), List.of(), List.of(), null, null, null));

        node.execute(context);

        ArgumentCaptor<AgentObservabilityRequest> captor = ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(captor.capture());
        assertThat(captor.getValue().getModelReplaced()).isTrue();
        assertThat(captor.getValue().getReplacedModel()).isEqualTo("old-model");
    }
}

package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.agent.AgentConfigResolver;
import com.apimarketplace.orchestrator.services.persistence.StepPayloadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-004: once a workflow run holds Gmail / Drive data, an agent node only sends its prompt to an
 * allow-listed provider, and the execution it dispatches is tagged so agent-service enforces the
 * same rule on the provider that actually runs it.
 */
@DisplayName("AgentNode restricted-data gate")
@ExtendWith(MockitoExtension.class)
class AgentNodeRestrictedDataTest {

    @Mock private WorkflowPlan plan;
    @Mock private AgentClient agentClient;
    @Mock private AgentConfigResolver agentConfigResolver;
    @Mock private StepPayloadService stepPayloadService;

    private AgentNode node(String provider) {
        lenient().when(agentConfigResolver.getToolsConfig(any(), any(), any())).thenReturn(Map.of());
        AgentNode node = new AgentNode("agent:summarise", new Agent("agent-1", "agent", "Summariser", null, false,
                provider, "model-x", null, "Summarise the email", 0.7, 4096, 10, 5, List.of(), null, Map.of(),
                List.of(), null, List.of(), null, null));
        node.acceptServices(ServiceRegistry.builder()
                .agentClient(agentClient)
                .agentConfigResolver(agentConfigResolver)
                .stepPayloadService(stepPayloadService)
                .build());
        return node;
    }

    private ExecutionContext context() {
        return ExecutionContext.create("run-1", "workflow-run-1", "tenant-1", "item-1", 0,
                Map.of("user_input", "Hello"), plan);
    }

    @Test
    @DisplayName("restricted run + provider outside the allow-list: the node fails with the refusal and dispatches nothing")
    void refusedForDisallowedProvider() {
        when(stepPayloadService.isRunRestricted(anyString())).thenReturn(true);

        NodeExecutionResult result = node("deepseek").execute(context());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).hasValueSatisfying(m -> assertThat(m).contains("deepseek").contains("Gmail"));
        verify(agentClient, never()).executeAgent(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("restricted run + allowed provider: dispatched, with the execution tagged RESTRICTED")
    void allowedProviderDispatchesTagged() {
        when(stepPayloadService.isRunRestricted(anyString())).thenReturn(true);
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(success());

        node("anthropic").execute(context());

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(agentClient).executeAgent(captor.capture());
        assertThat(captor.getValue().credentials()).containsEntry(DataSensitivity.CREDENTIAL_KEY, "RESTRICTED");
    }

    @Test
    @DisplayName("ordinary run: any provider, no tag")
    void ordinaryRunUntouched() {
        when(stepPayloadService.isRunRestricted(anyString())).thenReturn(false);
        when(agentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(success());

        node("deepseek").execute(context());

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(agentClient).executeAgent(captor.capture());
        assertThat(captor.getValue().credentials()).doesNotContainKey(DataSensitivity.CREDENTIAL_KEY);
    }

    private static AgentExecutionResponseDto success() {
        return new AgentExecutionResponseDto(true, "OK", "OK", List.of(), 1, Map.of(), null, 100L,
                "anthropic", "model-x", List.of(), null, Map.of(), List.of(), List.of(), List.of(),
                null, null, null);
    }
}

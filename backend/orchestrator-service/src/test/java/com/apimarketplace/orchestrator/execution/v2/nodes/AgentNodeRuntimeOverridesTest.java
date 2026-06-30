package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.agent.AgentRuntimeOverrides;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the bug where {@code AgentNode.execute()} performed up to TWO extra
 * {@code resolveAgentConfig} HTTP fetches per execution: one for {@code executionTimeout}
 * (via {@code AgentConfigResolver.getExecutionTimeout}, since removed) and one for the
 * loop thresholds. The fix plumbs both through {@link AgentRuntimeOverrides}, populated
 * once by {@code ExecutionNodeFactory.createAgentNodes} from the same DTO fetch that
 * produced the resolved {@code Agent} record.
 *
 * <p>These tests pin the new contract: {@code executeAgent}'s DTO carries the override
 * values, and {@code resolveAgentConfig} is never invoked from inside the AgentNode
 * execute path on the loop-threshold codepath.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentNode - runtimeOverrides plumbing")
class AgentNodeRuntimeOverridesTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private AgentClient mockAgentClient;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("user_input", "Hello");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    @Test
    @DisplayName("execute() does not call resolveAgentConfig for loop thresholds when overrides are set (entity-backed agent)")
    void executeDoesNotRefetchAgentDtoForLoopThresholds() {
        // Use an entity-backed agent so the budget-guard path (separate concern from
        // this fix) DOES fire one resolveAgentConfig call. We assert exactly ONE call
        // - the pre-fix shape made THREE (budget + loop-thresholds + executionTimeout).
        UUID entityId = UUID.randomUUID();
        AgentNode node = newEntityBackedNode(entityId);
        injectAgentClient(node);
        node.setRuntimeOverrides(new AgentRuntimeOverrides(240, 5, 9, null, null));

        // Budget-guard fetch returns a DTO with NO creditBudget so the guard
        // short-circuits after one fetch (no checkAndResetBudget loop).
        com.apimarketplace.agent.client.dto.AgentDto noBudgetDto = new com.apimarketplace.agent.client.dto.AgentDto();
        noBudgetDto.setId(entityId);
        when(mockAgentClient.resolveAgentConfig(entityId, context.tenantId(), context.organizationId()))
            .thenReturn(noBudgetDto);
        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse());

        node.execute(context);

        // Exactly ONE call - the budget-guard's. Loop thresholds + executionTimeout
        // come from runtimeOverrides without HTTP. Pre-fix shape would assert 3.
        verify(mockAgentClient, times(1)).resolveAgentConfig(any(UUID.class), any(), any());
    }

    @Test
    @DisplayName("execute() forwards runtimeOverrides into AgentExecutionRequestDto loop fields")
    void executeForwardsLoopOverridesIntoDto() {
        AgentNode node = newNode();
        injectAgentClient(node);
        node.setRuntimeOverrides(new AgentRuntimeOverrides(180, 4, 8, null, null));

        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse());

        node.execute(context);

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(mockAgentClient).executeAgent(captor.capture());
        AgentExecutionRequestDto dto = captor.getValue();
        assertEquals(180, dto.executionTimeout());
        assertEquals(4, dto.loopIdenticalStop());
        assertEquals(8, dto.loopConsecutiveStop());
    }

    @Test
    @DisplayName("execute() puts the per-agent inactivity window on the DTO credentials when the override is set")
    void executeForwardsInactivityTimeoutCredential() {
        AgentNode node = newNode();
        injectAgentClient(node);
        node.setRuntimeOverrides(new AgentRuntimeOverrides(null, null, null, null, 300));

        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse());

        node.execute(context);

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(mockAgentClient).executeAgent(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().credentials())
            .as("the per-agent inactivity window must ride on the DTO credentials so agent-service honors it")
            .containsEntry("__inactivityTimeoutSeconds__", 300);
    }

    @Test
    @DisplayName("execute() OMITS the inactivity credential when the override is null (the 5-min default applies)")
    void executeOmitsInactivityCredentialWhenNull() {
        AgentNode node = newNode();
        injectAgentClient(node);
        node.setRuntimeOverrides(new AgentRuntimeOverrides(null, null, null, null, null));

        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse());

        node.execute(context);

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(mockAgentClient).executeAgent(captor.capture());
        java.util.Map<String, Object> creds = captor.getValue().credentials();
        org.assertj.core.api.Assertions.assertThat(creds == null || !creds.containsKey("__inactivityTimeoutSeconds__"))
            .as("no inactivity credential when the override is null")
            .isTrue();
    }

    @Test
    @DisplayName("execute() forwards runtimeOverrides.reasoningEffort into the DTO so the bridge gets the per-agent effort")
    void executeForwardsReasoningEffortIntoDto() {
        AgentNode node = newNode();
        injectAgentClient(node);
        node.setRuntimeOverrides(new AgentRuntimeOverrides(null, null, null, "high", null));

        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse());

        node.execute(context);

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(mockAgentClient).executeAgent(captor.capture());
        assertEquals("high", captor.getValue().reasoningEffort());
    }

    @Test
    @DisplayName("execute() with default EMPTY overrides forwards null loop fields")
    void executeWithEmptyOverridesForwardsNulls() {
        AgentNode node = newNode();
        injectAgentClient(node);
        // No setRuntimeOverrides() call - default EMPTY overrides apply.

        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse());

        node.execute(context);

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(mockAgentClient).executeAgent(captor.capture());
        AgentExecutionRequestDto dto = captor.getValue();
        assertNull(dto.executionTimeout());
        assertNull(dto.loopIdenticalStop());
        assertNull(dto.loopConsecutiveStop());
    }

    @Test
    @DisplayName("setRuntimeOverrides(null) keeps EMPTY default (no NPE downstream)")
    void setRuntimeOverridesNullCoercesToEmpty() {
        AgentNode node = newNode();
        injectAgentClient(node);
        node.setRuntimeOverrides(null);

        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse());

        node.execute(context);

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(mockAgentClient).executeAgent(captor.capture());
        AgentExecutionRequestDto dto = captor.getValue();
        assertNull(dto.executionTimeout());
        assertNull(dto.loopIdenticalStop());
        assertNull(dto.loopConsecutiveStop());
    }

    private AgentNode newNode() {
        Agent agent = new Agent(
            "agent-1", "agent", "Analyzer", null, null,
            "openai", "gpt-4o", "You are a data analyst", "Analyze",
            0.7, 4096, 10, 5,
            List.of(), null, Map.of(), List.of(), null, List.of(), null, null);
        return new AgentNode("agent:analyzer", agent);
    }

    private AgentNode newEntityBackedNode(UUID entityId) {
        Agent agent = new Agent(
            "agent-1", "agent", "Analyzer", entityId.toString(), null,
            "openai", "gpt-4o", "You are a data analyst", "Analyze",
            0.7, 4096, 10, 5,
            List.of(), null, Map.of(), List.of(), null, List.of(), null, null);
        return new AgentNode("agent:analyzer", agent);
    }

    private void injectAgentClient(AgentNode node) {
        node.acceptServices(ServiceRegistry.builder()
            .agentClient(mockAgentClient)
            .build());
    }

    private AgentExecutionResponseDto successResponse() {
        return new AgentExecutionResponseDto(
            true, "ok", "ok",
            List.of(), 1, Map.of(),
            null, 100L, "openai", "gpt-4o",
            List.of(), "COMPLETED",
            Map.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), null
        );
    }
}

package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.credit.CreditBudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins Delta 2 - workflow agent dispatch is gated by a cost-aware pre-flight
 * against the tenant's credit budget, mirroring the chat path's existing
 * {@code checkChatBudget} gate. Without this gate, a workflow burns tokens in
 * agent-service, then post-flight 402s, and only an audit trail survives.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentNode - pre-flight budget gate (Delta 2)")
class AgentNodePreflightBudgetTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private AgentClient mockAgentClient;
    @Mock private CreditBudgetService mockCreditBudgetService;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("user_input", "Analyze");
        context = ExecutionContext.create(
            "run-pf-1", "workflow-run-pf-1",
            "tenant-pf-1", "item-0", 0,
            triggerData, mockPlan);
    }

    private Agent agent(String provider, String model, Integer maxTokens) {
        return new Agent(
            "agent-config-1", "agent", "Test Agent", null, null,
            provider, model,
            "You are a test agent", "Analyze this",
            0.7, maxTokens, 10, 5,
            List.of(), null, Map.of(), List.of(),
            null, List.of(), null, null
        );
    }

    private AgentNode nodeWithServices(Agent agentConfig) {
        AgentNode node = new AgentNode("agent:test_node", agentConfig);
        ServiceRegistry registry = ServiceRegistry.builder()
            .agentClient(mockAgentClient)
            .creditBudgetService(mockCreditBudgetService)
            .build();
        node.acceptServices(registry);
        return node;
    }

    private AgentExecutionResponseDto successResponse() {
        return new AgentExecutionResponseDto(
            true, "ok", "ok", List.of(), 1, Map.of(), null, 100L,
            "openai", "gpt-4o",
            List.of(), null, Map.of(), List.of(), List.of(), List.of(),
            null, null, null);
    }

    @Test
    @DisplayName("Blocks dispatch when pre-flight denies the budget - agent-service is never called")
    void deniesDispatchWhenBudgetGateDenies() {
        Agent agentCfg = agent("openai", "gpt-4o", 4096);
        AgentNode node = nodeWithServices(agentCfg);

        when(mockCreditBudgetService.preflightAgentBudget(
                eq("tenant-pf-1"), eq("openai"), eq("gpt-4o"), anyInt(), anyInt()))
            .thenReturn(false);

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.errorMessage().get()).contains("Insufficient credits");
        assertThat(result.errorMessage().get()).contains("pre-flight");
        // Critical invariant: LLM dispatch must never happen when pre-flight denies.
        verify(mockAgentClient, never()).executeAgent(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("Allows dispatch when pre-flight approves - forwards to agent-service")
    void allowsDispatchWhenBudgetGateApproves() {
        Agent agentCfg = agent("openai", "gpt-4o", 4096);
        AgentNode node = nodeWithServices(agentCfg);

        when(mockCreditBudgetService.preflightAgentBudget(
                any(), any(), any(), anyInt(), anyInt())).thenReturn(true);
        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(successResponse());

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isSuccess()).isTrue();
        verify(mockAgentClient).executeAgent(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("Skips the gate when creditBudgetService is absent (CE without billing) - does not block dispatch")
    void skipsGateWhenServiceAbsent() {
        Agent agentCfg = agent("openai", "gpt-4o", 4096);
        AgentNode node = new AgentNode("agent:test_node", agentCfg);
        ServiceRegistry registry = ServiceRegistry.builder()
            .agentClient(mockAgentClient)
            .build();  // no creditBudgetService
        node.acceptServices(registry);

        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(successResponse());

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isSuccess()).isTrue();
        verify(mockAgentClient).executeAgent(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("Skips the gate when provider/model is null - lets the existing path surface the config error")
    void skipsGateWhenProviderOrModelNull() {
        Agent agentCfg = agent(null, null, 4096);
        AgentNode node = nodeWithServices(agentCfg);

        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class))).thenReturn(successResponse());

        node.execute(context);

        // Without (provider, model) we can't price the call - the gate is skipped so the
        // downstream execution surfaces the config bug as a normal error, not as a misleading
        // "insufficient credits" response.
        verify(mockCreditBudgetService, never()).preflightAgentBudget(any(), any(), any(), anyInt(), anyInt());
        verify(mockAgentClient).executeAgent(any(AgentExecutionRequestDto.class));
    }

    @Test
    @DisplayName("Estimated completion tokens defaults to 4096 when agentConfig.maxTokens is null")
    void defaultsCompletionEstimateWhenMaxTokensNull() {
        Agent agentCfg = agent("openai", "gpt-4o", null);
        AgentNode node = nodeWithServices(agentCfg);

        when(mockCreditBudgetService.preflightAgentBudget(
                any(), any(), any(), anyInt(), eq(4096))).thenReturn(false);

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isFailure()).isTrue();
        verify(mockCreditBudgetService).preflightAgentBudget(
                eq("tenant-pf-1"), eq("openai"), eq("gpt-4o"), anyInt(), eq(4096));
    }

    /**
     * Coverage for the pre-flight gate across the three alternate execution paths
     * (classify / guardrail / async queue). The audit specifically flagged that the
     * original patch only gated {@code executeAgent} while these three paths could
     * still dispatch without a credit check. Each test denies the gate and asserts
     * the corresponding downstream (classify/guardrail client, or async registry)
     * is never touched.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("Coverage - gate fires across all dispatch paths")
    class AllPathsGated {

        private Agent classifyAgent(String provider, String model) {
            // Categories non-empty so agent_type=classify routing picks executeClassify.
            // Field order: id, type, label, agentConfigId, withMemory, provider, model,
            // systemPrompt, prompt, temperature, maxTokens, maxIterations, maxTools, tools,
            // parentLoopId, params, classifyCategories, classifyParams, guardrailRules,
            // guardrailParams, graphNodeId.
            return new Agent(
                "classify-1", "classify", "Classifier", null, null,
                provider, model,
                "You classify", "Classify this",
                0.2, 1024, 1, 1,
                List.of(),                                                // tools
                null, Map.of(),                                           // parentLoopId, params
                List.of(Map.of("label", "spam", "description", "spam category")),  // classifyCategories
                null,                                                     // classifyParams
                List.of(),                                                // guardrailRules
                null, null                                                // guardrailParams, graphNodeId
            );
        }

        private Agent guardrailAgent(String provider, String model) {
            return new Agent(
                "guardrail-1", "guardrail", "Guardrail", null, null,
                provider, model,
                "You check", "Check this",
                0.0, 1024, 1, 1,
                List.of(),                                                // tools
                null, Map.of("action", "flag"),                           // parentLoopId, params
                List.of(),                                                // classifyCategories
                null,                                                     // classifyParams
                List.of(Map.of("id", "no_pii", "description", "No PII")), // guardrailRules
                null, null                                                // guardrailParams, graphNodeId
            );
        }

        @Test
        @DisplayName("executeClassify path: gate denial blocks classify dispatch")
        void classifyGated() {
            AgentNode node = nodeWithServices(classifyAgent("openai", "gpt-4o"));
            when(mockCreditBudgetService.preflightAgentBudget(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(false);

            NodeExecutionResult result = node.execute(context);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.errorMessage().get()).contains("Insufficient credits");
            // Classify never dispatches to agent-service when the gate denies.
            verify(mockAgentClient, never()).executeClassify(any());
        }

        @Test
        @DisplayName("executeGuardrail path: gate denial blocks guardrail dispatch")
        void guardrailGated() {
            AgentNode node = nodeWithServices(guardrailAgent("openai", "gpt-4o"));
            when(mockCreditBudgetService.preflightAgentBudget(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(false);

            NodeExecutionResult result = node.execute(context);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.errorMessage().get()).contains("Insufficient credits");
            verify(mockAgentClient, never()).executeGuardrail(any());
        }

        @Test
        @DisplayName("Async queue path: gate denial blocks enqueueing before a worker picks the job up")
        void asyncQueueGated() {
            Agent agentCfg = agent("openai", "gpt-4o", 2048);
            AgentNode node = new AgentNode("agent:async_node", agentCfg);
            com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry pendingReg =
                org.mockito.Mockito.mock(com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry.class);
            ServiceRegistry registry = ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .creditBudgetService(mockCreditBudgetService)
                .pendingAgentRegistry(pendingReg)
                .build();
            node.acceptServices(registry);
            node.setAsyncQueueEnabled(true);

            when(mockCreditBudgetService.preflightAgentBudget(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(false);

            NodeExecutionResult result = node.execute(context);

            assertThat(result.isFailure()).isTrue();
            assertThat(result.errorMessage().get()).contains("Insufficient credits");
            // The pending-agent registry must NOT see a registration - the gate keeps the
            // work queue clean instead of enqueueing a job that will 402 later.
            verify(pendingReg, never()).register(any());
        }
    }
}

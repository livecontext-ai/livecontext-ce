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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The agent pre-flight budget gate and the loop length it dispatches.
 *
 * <p>LC-056 closed a real hole: nothing clamped {@code maxIterations} server-side (it comes from
 * the plan, which the author edits). The clamp stays. Its first fix also priced the gate at
 * {@code maxIterations} full turns, which the regression review of 2026-09-29 reverted: that
 * estimate was 10 to 100 times one turn (an agent made in the UI defaults to 100), refused
 * dispatches a small balance could pay for many times over, and duplicated the budget check the
 * agent loop already makes before EVERY turn (agent-service TenantBudgetGuard; for a run sent to
 * a CLI bridge, the bridge's own guard, which agent-service hands the balance and the agent
 * budget, see AgentRemoteExecutionServiceBridgeBudgetTest). The gate now prices one worst-case
 * turn, and the ceiling is agent-service's own limit.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentNode - pre-flight priced at one turn, loop length clamped server-side")
class AgentNodeIterationBudgetTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private AgentClient mockAgentClient;
    @Mock private CreditBudgetService mockCreditBudgetService;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("user_input", "Analyze");
        context = ExecutionContext.create(
                "run-it-1", "workflow-run-it-1", "tenant-it-1", "item-0", 0, triggerData, mockPlan);
    }

    private Agent agent(Integer maxTokens, Integer maxIterations) {
        return new Agent(
                "agent-config-1", "agent", "Test Agent", null, null,
                "openai", "gpt-4o",
                "You are a test agent", "Analyze this",
                0.7, maxTokens, maxIterations, 5,
                List.of(), null, Map.of(), List.of(),
                null, List.of(), null, null);
    }

    private AgentNode node(Agent agentConfig) {
        AgentNode node = new AgentNode("agent:test_node", agentConfig);
        node.acceptServices(ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                .creditBudgetService(mockCreditBudgetService)
                .build());
        return node;
    }

    private AgentExecutionResponseDto successResponse() {
        return new AgentExecutionResponseDto(
                true, "ok", "ok", List.of(), 1, Map.of(), null, 100L,
                "openai", "gpt-4o",
                List.of(), null, Map.of(), List.of(), List.of(), List.of(),
                null, null, null);
    }

    private void allowAndSucceed() {
        when(mockCreditBudgetService.preflightAgentBudget(anyString(), anyString(), anyString(),
                anyInt(), anyInt())).thenReturn(true);
        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
                .thenReturn(successResponse());
    }

    private int dispatchedMaxIterations() {
        ArgumentCaptor<AgentExecutionRequestDto> dispatched =
                ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(mockAgentClient).executeAgent(dispatched.capture());
        return dispatched.getValue().maxIterations();
    }

    @Test
    @DisplayName("regression 2026-09-29: a 100-turn agent is priced at ONE turn, not 100")
    void estimateIsOneTurnWhateverTheLoopLength() {
        AgentNode node = node(agent(4096, 100));
        allowAndSucceed();

        node.execute(context);

        ArgumentCaptor<Integer> completion = ArgumentCaptor.forClass(Integer.class);
        verify(mockCreditBudgetService).preflightAgentBudget(
                eq("tenant-it-1"), eq("openai"), eq("gpt-4o"), anyInt(), completion.capture());
        assertThat(completion.getValue())
                .as("the loop checks the balance before every turn; the gate prices the first")
                .isEqualTo(4096);
    }

    @Test
    @DisplayName("the prompt estimate does not grow with the loop length either")
    void promptEstimateIsIndependentOfTheLoopLength() {
        AgentNode singleTurn = node(agent(4096, 1));
        AgentNode hundredTurns = node(agent(4096, 100));
        allowAndSucceed();

        singleTurn.execute(context);
        hundredTurns.execute(context);

        ArgumentCaptor<Integer> prompts = ArgumentCaptor.forClass(Integer.class);
        verify(mockCreditBudgetService, org.mockito.Mockito.times(2)).preflightAgentBudget(
                anyString(), anyString(), anyString(), prompts.capture(), eq(4096));
        assertThat(prompts.getAllValues().get(1)).isEqualTo(prompts.getAllValues().get(0));
    }

    @Test
    @DisplayName("regression 2026-09-29: an agent configured for 300 turns runs 300, the old 100 ceiling cut it")
    void loopLengthBetweenOldAndNewCeilingIsKept() {
        AgentNode node = node(agent(4096, 300));
        allowAndSucceed();

        node.execute(context);

        assertThat(dispatchedMaxIterations()).isEqualTo(300);
    }

    @Test
    @DisplayName("LC-056: maxIterations above agent-service's own limit is clamped to it in the dispatch")
    void clampsMaxIterationsServerSide() {
        AgentNode node = node(agent(1000, 50_000));
        allowAndSucceed();

        node.execute(context);

        assertThat(AgentNode.MAX_ITERATIONS_CEILING).isEqualTo(1000);
        assertThat(dispatchedMaxIterations()).isEqualTo(AgentNode.MAX_ITERATIONS_CEILING);
    }

    @Test
    @DisplayName("a non-positive iteration budget falls back to the plan default, NOT to the ceiling")
    void nonPositiveIterationBudgetFallsBackToThePlanDefault() {
        AgentNode node = node(agent(1000, 0));
        allowAndSucceed();

        node.execute(context);

        assertThat(dispatchedMaxIterations())
                .as("a zeroed maxIterations must not dispatch a ceiling-length loop")
                .isEqualTo(AgentNode.UNSPECIFIED_MAX_ITERATIONS);
    }

    @Test
    @DisplayName("an ABSENT maxIterations is the plan default of 10")
    void absentMaxIterationsUsesThePlanDefault() {
        AgentNode node = node(agent(1000, null));
        allowAndSucceed();

        node.execute(context);

        assertThat(dispatchedMaxIterations()).isEqualTo(10);
    }

    @Test
    @DisplayName("a within-ceiling iteration budget is passed through unchanged")
    void withinCeilingIsUnchanged() {
        AgentNode node = node(agent(4096, 7));
        allowAndSucceed();

        node.execute(context);

        assertThat(dispatchedMaxIterations()).isEqualTo(7);
    }

    @Test
    @DisplayName("a denied pre-flight fails the node with an insufficient-credits message and dispatches nothing")
    void denialBlocksTheDispatch() {
        AgentNode node = node(agent(4096, 10));
        when(mockCreditBudgetService.preflightAgentBudget(anyString(), anyString(), anyString(),
                anyInt(), anyInt())).thenReturn(false);

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.errorMessage().orElseThrow()).contains("Insufficient credits");
        verify(mockAgentClient, never()).executeAgent(any(AgentExecutionRequestDto.class));
    }
}

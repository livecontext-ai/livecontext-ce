package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
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
 * LC-056, the other direction. Scaling the pre-flight estimate by the iteration budget closed the
 * under-pricing of the agent loop, but it was applied to every dispatch path including the two
 * that are single-shot: classify and guardrail run with {@code maxIterations(1)} on the
 * agent-service side no matter what the plan says.
 *
 * <p>An ordinary classify node carries the plan default of 10, so it was priced at 10 turns and a
 * tenant holding the credits for the one turn it actually runs was refused. An over-strict credit
 * gate is an outage, not a safety margin. Since the regression review of 2026-09-29 every path is
 * priced at one turn (the agent loop checks the balance before each of its own turns), and these
 * pin that classify and guardrail still are, while the dispatch still tells agent-service the
 * right loop length for each type.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentNode - classify and guardrail are priced at the one turn they run (LC-056)")
class AgentNodeSingleTurnPreflightTest {

    private static final int MAX_TOKENS = 1024;
    /** The plan default an ordinary classify/guardrail node carries when the author sets nothing. */
    private static final int PLAN_DEFAULT_ITERATIONS = 10;

    @Mock private WorkflowPlan mockPlan;
    @Mock private AgentClient mockAgentClient;
    @Mock private CreditBudgetService mockCreditBudgetService;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("user_input", "Analyze");
        context = ExecutionContext.create(
                "run-st-1", "workflow-run-st-1", "tenant-st-1", "item-0", 0, triggerData, mockPlan);
    }

    private Agent classifyAgent() {
        return new Agent(
                "classify-1", "classify", "Classifier", null, null,
                "openai", "gpt-4o",
                "You classify", "Classify this",
                0.2, MAX_TOKENS, PLAN_DEFAULT_ITERATIONS, 1,
                List.of(), null, Map.of(),
                List.of(Map.of("label", "spam", "description", "spam category")),
                null, List.of(), null, null);
    }

    private Agent guardrailAgent() {
        return new Agent(
                "guardrail-1", "guardrail", "Guardrail", null, null,
                "openai", "gpt-4o",
                "You check", "Check this",
                0.0, MAX_TOKENS, PLAN_DEFAULT_ITERATIONS, 1,
                List.of(), null, Map.of("action", "flag"),
                List.of(), null,
                List.of(Map.of("id", "no_pii", "description", "No PII")),
                null, null);
    }

    private Agent loopingAgent() {
        return new Agent(
                "agent-1", "agent", "Test Agent", null, null,
                "openai", "gpt-4o",
                "You are a test agent", "Analyze this",
                0.7, MAX_TOKENS, PLAN_DEFAULT_ITERATIONS, 5,
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

    /** The completion estimate the gate was asked to approve. */
    private int capturedCompletionEstimate() {
        ArgumentCaptor<Integer> completion = ArgumentCaptor.forClass(Integer.class);
        verify(mockCreditBudgetService).preflightAgentBudget(
                eq("tenant-st-1"), eq("openai"), eq("gpt-4o"), anyInt(), completion.capture());
        return completion.getValue();
    }

    @Test
    @DisplayName("classify is priced at ONE turn even when the plan carries the default of 10")
    void classifyIsPricedAtOneTurn() {
        AgentNode node = node(classifyAgent());
        when(mockCreditBudgetService.preflightAgentBudget(anyString(), anyString(), anyString(),
                anyInt(), anyInt())).thenReturn(false);

        node.execute(context);

        assertThat(capturedCompletionEstimate())
                .as("classify dispatches with maxIterations(1); pricing it at 10 refuses a tenant "
                        + "who can afford the call it actually makes")
                .isEqualTo(MAX_TOKENS);
        verify(mockAgentClient, never()).executeClassify(any());
    }

    @Test
    @DisplayName("guardrail is priced at ONE turn even when the plan carries the default of 10")
    void guardrailIsPricedAtOneTurn() {
        AgentNode node = node(guardrailAgent());
        when(mockCreditBudgetService.preflightAgentBudget(anyString(), anyString(), anyString(),
                anyInt(), anyInt())).thenReturn(false);

        node.execute(context);

        assertThat(capturedCompletionEstimate()).isEqualTo(MAX_TOKENS);
        verify(mockAgentClient, never()).executeGuardrail(any());
    }

    @Test
    @DisplayName("the agentic path is gated too, at one turn (the per-turn check is agent-service's)")
    void agentPathIsGatedAtOneTurn() {
        AgentNode node = node(loopingAgent());
        when(mockCreditBudgetService.preflightAgentBudget(anyString(), anyString(), anyString(),
                anyInt(), anyInt())).thenReturn(false);

        node.execute(context);

        assertThat(capturedCompletionEstimate())
                .as("regression 2026-09-29: pricing the whole loop refused dispatches a small balance could pay")
                .isEqualTo(MAX_TOKENS);
    }

    @Test
    @DisplayName("a denial says it priced one turn")
    void denialMessageMatchesTheBudgetItPriced() {
        AgentNode node = node(classifyAgent());
        when(mockCreditBudgetService.preflightAgentBudget(anyString(), anyString(), anyString(),
                anyInt(), anyInt())).thenReturn(false);

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.errorMessage().orElseThrow())
                .contains("Insufficient credits")
                .contains("one agent turn");
    }

    @Test
    @DisplayName("iterationBudgetFor (the loop length dispatched) maps every type, and an unknown type loops")
    void iterationBudgetForMapsEveryType() {
        AgentNode node = node(loopingAgent());

        assertThat(node.iterationBudgetFor("classify")).isEqualTo(AgentNode.SINGLE_TURN_ITERATIONS);
        assertThat(node.iterationBudgetFor("GUARDRAIL")).isEqualTo(AgentNode.SINGLE_TURN_ITERATIONS);
        assertThat(node.iterationBudgetFor("agent")).isEqualTo(PLAN_DEFAULT_ITERATIONS);
        // Null and unknown fall to the agentic loop length: an unknown type is dispatched as an agent.
        assertThat(node.iterationBudgetFor(null)).isEqualTo(PLAN_DEFAULT_ITERATIONS);
        assertThat(node.iterationBudgetFor("something_new")).isEqualTo(PLAN_DEFAULT_ITERATIONS);
    }
}

package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.GuardrailRequestDto;
import com.apimarketplace.agent.client.dto.execution.GuardrailResponseDto;
import com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgent;
import com.apimarketplace.orchestrator.execution.v2.async.PendingAgentRegistry;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.agent.GuardrailRuleEvaluator;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guardrail rules are enforced by type: deterministic rules are decided in the node, only the
 * rest reaches the model, on the inline and the async path.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentNode guardrail typed rules")
class AgentNodeGuardrailRulesTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private AgentClient mockAgentClient;
    @Mock private PendingAgentRegistry mockPendingAgentRegistry;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create("run-g", "wr-g", "tenant-g", "item-0", 0, new HashMap<>(), mockPlan);
    }

    private static Agent guardrail(String content, List<Map<String, Object>> rules) {
        return new Agent(
            "agent-guardrail-1", "guardrail", "Content Guard", null, null,
            "openai", "gpt-4o", null, null, 0.1, 512, 1, 1,
            List.of(), null,
            Map.of("content", content),
            List.of(), null,
            rules, null, null);
    }

    private static Map<String, Object> keywordRule(String action) {
        return Map.of("id", "kw", "type", "keyword_filter", "action", action,
            "config", Map.of("keywordsExpression", "refund, chargeback"));
    }

    private static Map<String, Object> toxicRule() {
        return Map.of("id", "tox", "type", "toxic_language", "action", "block", "config", Map.of());
    }

    private AgentNode inlineNode(Agent agent) {
        AgentNode node = new AgentNode("agent:content_guard", agent);
        node.acceptServices(ServiceRegistry.builder().agentClient(mockAgentClient).build());
        return node;
    }

    @Test
    @DisplayName("regression: a keyword rule is ENFORCED (it used to reach the model as 'Check for this issue'), and no LLM is called")
    void deterministicRulesSkipTheModel() {
        AgentNode node = inlineNode(guardrail("I want a refund", List.of(keywordRule("block"))));

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).containsEntry("passed", false);
        assertThat((List<Object>) result.output().get("violations")).containsExactly("kw");
        verify(mockAgentClient, never()).executeGuardrail(any());
    }

    @Test
    @DisplayName("regression: a flagged deterministic violation still FAILS the guardrail (flag never disarms it)")
    void flaggedViolationFails() {
        AgentNode node = inlineNode(guardrail("I want a refund", List.of(keywordRule("flag"))));

        NodeExecutionResult result = node.execute(context);

        assertThat(result.output()).containsEntry("passed", false);
        assertThat((List<Object>) result.output().get("violations")).containsExactly("kw");
    }

    @Test
    @DisplayName("an invalid rule config fails the node naming the rule, instead of silently not checking")
    void invalidConfigFailsTheNode() {
        // A regex that does not compile. A config left BLANK is not invalid: it goes to the model.
        Map<String, Object> invalid = Map.of("id", "rx", "type", "regex_pattern", "action", "block",
            "config", Map.of("pattern", "[unclosed"));
        AgentNode node = inlineNode(guardrail("text", List.of(invalid)));

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.errorMessage().orElse("")).contains("'rx'").contains("regex_pattern");
        verify(mockAgentClient, never()).executeGuardrail(any());
    }

    @Test
    @DisplayName("mixed rules: only the model-judged rule is sent, with its type and action, and the verdicts merge")
    void mixedRulesSendOnlyModelRules() {
        when(mockAgentClient.executeGuardrail(any(GuardrailRequestDto.class))).thenReturn(new GuardrailResponseDto(
            true, true, List.of(), Map.of(), "I want a refund", null, 120L, "openai", "gpt-4o", 50, 40, 10,
            null, null, null));
        AgentNode node = inlineNode(guardrail("I want a refund", List.of(keywordRule("block"), toxicRule())));

        NodeExecutionResult result = node.execute(context);

        ArgumentCaptor<GuardrailRequestDto> sent = ArgumentCaptor.forClass(GuardrailRequestDto.class);
        verify(mockAgentClient).executeGuardrail(sent.capture());
        assertThat(sent.getValue().rules()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo("tox");
            assertThat(r.type()).isEqualTo("toxic_language");
            assertThat(r.action()).isEqualTo("block");
            assertThat(r.description()).contains("toxic");
        });
        assertThat(result.output()).as("the model passed, the keyword rule did not").containsEntry("passed", false);
        assertThat((List<Object>) result.output().get("violations")).containsExactly("kw");
    }

    @Test
    @DisplayName("async path: only model-judged rules are enqueued, the deterministic outcome rides in the pending snapshot")
    void asyncPathCarriesPrecomputedOutcome() {
        AgentNode node = new AgentNode("agent:content_guard",
            guardrail("I want a refund", List.of(keywordRule("block"), toxicRule())));
        node.acceptServices(ServiceRegistry.builder().agentClient(mockAgentClient)
            .pendingAgentRegistry(mockPendingAgentRegistry).build());
        node.setAsyncQueueEnabled(true);

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isAsyncRunning()).isTrue();
        AgentExecutionRequestMessage message = (AgentExecutionRequestMessage) result.output().get("queueMessage");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) message.requestPayload().get("rules");
        assertThat(rules).extracting(r -> r.get("id")).containsExactly("tox");
        ArgumentCaptor<PendingAgent> pending = ArgumentCaptor.forClass(PendingAgent.class);
        verify(mockPendingAgentRegistry).register(pending.capture());
        assertThat(pending.getValue().resolvedInputData()).containsKey(GuardrailRuleEvaluator.PRECOMPUTED_KEY);
    }

    @Test
    @DisplayName("async path: a guardrail whose every rule is deterministic completes inline, nothing is enqueued")
    void asyncPathAllDeterministicCompletesInline() {
        AgentNode node = new AgentNode("agent:content_guard",
            guardrail("I want a refund", List.of(keywordRule("block"))));
        node.acceptServices(ServiceRegistry.builder().agentClient(mockAgentClient)
            .pendingAgentRegistry(mockPendingAgentRegistry).build());
        node.setAsyncQueueEnabled(true);

        NodeExecutionResult result = node.execute(context);

        assertThat(result.isAsyncRunning()).isFalse();
        assertThat(result.output()).containsEntry("passed", false);
        verify(mockPendingAgentRegistry, never()).register(any());
    }
}

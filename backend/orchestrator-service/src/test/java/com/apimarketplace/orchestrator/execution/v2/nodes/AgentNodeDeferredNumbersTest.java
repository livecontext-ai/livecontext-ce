package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AgentNode - numeric settings written as {{...}}")
class AgentNodeDeferredNumbersTest {

    private final ExecutionContext context = ExecutionContext.create(
        "run-1", "wr-1", "tenant-1", "item-1", 0, Map.of(), mock(WorkflowPlan.class));

    private static Agent agentWith(Map<String, String> deferred) {
        return new Agent("a1", "agent", "Writer", null, true, "openai", "gpt", "sys", "prompt",
            null, null, null, null, List.of(), null, Map.of(),
            null, null, null, null, null, deferred);
    }

    private AgentNode nodeResolvingTo(Map<String, Object> values, Map<String, String> deferred) {
        AgentNode node = new AgentNode("agent:writer", agentWith(deferred));
        V2TemplateAdapter adapter = mock(V2TemplateAdapter.class);
        when(adapter.resolveTemplates(anyMap(), any())).thenAnswer(TemplateResolutionStubs.resolving(values));
        node.setTemplateAdapter(adapter);
        return node;
    }

    @Test
    @DisplayName("regression: templated temperature / maxTokens / maxIterations / maxTools are resolved, not defaulted")
    void numbersAreResolved() {
        // They used to become 0.7 / 4096 / 10 / 5 with no word, whatever the reference held.
        AgentNode node = nodeResolvingTo(
            Map.of("{{core:cfg.output.t}}", 0.2, "{{core:cfg.output.tok}}", "900",
                "{{core:cfg.output.it}}", 3, "{{core:cfg.output.tools}}", 2),
            Map.of("temperature", "{{core:cfg.output.t}}", "maxTokens", "{{core:cfg.output.tok}}",
                "maxIterations", "{{core:cfg.output.it}}", "maxTools", "{{core:cfg.output.tools}}"));

        Agent effective = node.resolveDeferredNumbers(context);

        assertEquals(0.2, effective.temperature());
        assertEquals(900, effective.maxTokens());
        assertEquals(3, effective.maxIterations());
        assertEquals(2, effective.maxTools());
        assertTrue(effective.deferredScalars().isEmpty(), "nothing is left to resolve on the copy");
    }

    @Test
    @DisplayName("a templated maxTokens resolving to something that is not a positive whole number fails naming it")
    void invalidNumberFails() {
        AgentNode node = nodeResolvingTo(Map.of("{{core:cfg.output.tok}}", "lots"),
            Map.of("maxTokens", "{{core:cfg.output.tok}}"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> node.resolveDeferredNumbers(context));
        assertTrue(e.getMessage().contains("maxTokens"), e.getMessage());
    }

    @Test
    @DisplayName("execute fails the node, naming the field, instead of running on the default")
    void executeFailsOnInvalidNumber() {
        AgentNode node = nodeResolvingTo(Map.of("{{core:cfg.output.it}}", 0),
            Map.of("maxIterations", "{{core:cfg.output.it}}"));

        NodeExecutionResult result = node.execute(context);

        assertFalse(result.isSuccess());
        assertTrue(result.errorMessage().orElse("").contains("maxIterations"), result.errorMessage().orElse(""));
    }

    @Test
    @DisplayName("the per-execution copy carries every instance field of the node")
    void copyCarriesEveryField() throws Exception {
        AgentNode node = nodeResolvingTo(Map.of(), Map.of());
        node.addSuccessor(mock(ExecutionNode.class));
        node.addPredecessor("core:before");

        AgentNode copy = node.copyFor(agentWith(Map.of()));

        for (Class<?> c = AgentNode.class; c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getName().equals("agentConfig")) {
                    continue;
                }
                field.setAccessible(true);
                assertSame(field.get(node), field.get(copy), "field " + field.getName() + " must be carried over");
            }
        }
    }

    @Test
    @DisplayName("a number pulled from a workspace variable runs with its value but is withheld in the report")
    void workspaceVariableNumberIsWithheld() {
        AgentNode node = nodeResolvingTo(Map.of("{{$vars.budget}}", 900), Map.of("maxTokens", "{{$vars.budget}}"));

        AgentNode copy = node.copyForExecution(context);

        assertEquals(900, copy.getAgentConfig().maxTokens());
        assertEquals(com.apimarketplace.orchestrator.services.template.ReportedParams.WITHHELD_WORKSPACE_VARIABLE,
            copy.reportedNumber("maxTokens", 900));
        assertEquals(0.7, copy.reportedNumber("temperature", 0.7), "an untemplated number is reported as is");
    }
}

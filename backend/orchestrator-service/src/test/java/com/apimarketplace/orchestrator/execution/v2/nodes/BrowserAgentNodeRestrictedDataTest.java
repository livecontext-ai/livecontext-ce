package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.classification.RestrictedDataPolicy;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.persistence.StepPayloadService;
import com.apimarketplace.orchestrator.tools.websearch.BrowserAgentModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LC-004: the browsing task is written from this run's data and handed to the browser agent's own
 * LLM. Once the run holds Gmail / Drive data, that LLM must be on the restricted-data allow-list,
 * checked BEFORE the job is ever submitted to the runner.
 */
@DisplayName("BrowserAgentNode restricted-data gate")
@ExtendWith(MockitoExtension.class)
class BrowserAgentNodeRestrictedDataTest {

    @Mock private WorkflowPlan plan;
    @Mock private AgentClient agentClient;
    @Mock private BrowserAgentModule browserAgentModule;
    @Mock private StepPayloadService stepPayloadService;

    @AfterEach
    void resetPolicy() {
        RestrictedDataPolicy.setLlmAllowListEnforced(true);
    }

    private BrowserAgentNode node(Map<String, Object> nodeConfig) {
        BrowserAgentNode node = new BrowserAgentNode("core:browse", nodeConfig);
        node.acceptServices(ServiceRegistry.builder()
                .agentClient(agentClient)
                .browserAgentModule(browserAgentModule)
                .stepPayloadService(stepPayloadService)
                .build());
        return node;
    }

    private ExecutionContext context() {
        return ExecutionContext.create("run-1", "workflow-run-1", "tenant-1", "item-1", 0,
                Map.of(), plan);
    }

    @Test
    @DisplayName("restricted run + llm.provider outside the allow-list: refused before the runner is ever called")
    void refusedForDisallowedProvider() {
        lenient().when(stepPayloadService.isRunRestricted(anyString())).thenReturn(true);

        NodeExecutionResult result = node(Map.of("task", "read my inbox",
                "llm", Map.of("provider", "deepseek"))).execute(context());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).hasValueSatisfying(m ->
                assertThat(m).contains("deepseek").contains(RestrictedDataPolicy.REFUSAL_CODE));
        verifyNoInteractions(browserAgentModule);
    }

    @Test
    @DisplayName("restricted run + no llm.provider configured: refused, naming the default model")
    void refusedWhenNoProviderConfigured() {
        lenient().when(stepPayloadService.isRunRestricted(anyString())).thenReturn(true);

        NodeExecutionResult result = node(Map.of("task", "read my inbox")).execute(context());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).hasValueSatisfying(m -> assertThat(m).contains("browser agent default model"));
        verifyNoInteractions(browserAgentModule);
    }

    @Test
    @DisplayName("restricted run + allow-list not enforced (CE default): proceeds")
    void proceedsWhenAllowListNotEnforced() {
        lenient().when(stepPayloadService.isRunRestricted(anyString())).thenReturn(true);
        RestrictedDataPolicy.setLlmAllowListEnforced(false);
        when(browserAgentModule.execute(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenThrow(new RuntimeException("no further wiring in this test"));

        NodeExecutionResult result = node(Map.of("task", "read my inbox",
                "llm", Map.of("provider", "deepseek"))).execute(context());

        // Proved by NOT getting the restricted-data refusal (any other failure past this point
        // is this test's own minimal wiring, not the gate under test).
        assertThat(result.errorMessage().orElse("")).doesNotContain(RestrictedDataPolicy.REFUSAL_CODE);
    }

    @Test
    @DisplayName("ordinary run: any provider proceeds past the gate")
    void ordinaryRunProceedsPastTheGate() {
        lenient().when(stepPayloadService.isRunRestricted(anyString())).thenReturn(false);
        when(browserAgentModule.execute(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenThrow(new RuntimeException("no further wiring in this test"));

        NodeExecutionResult result = node(Map.of("task", "read my inbox",
                "llm", Map.of("provider", "deepseek"))).execute(context());

        assertThat(result.errorMessage().orElse("")).doesNotContain(RestrictedDataPolicy.REFUSAL_CODE);
    }
}

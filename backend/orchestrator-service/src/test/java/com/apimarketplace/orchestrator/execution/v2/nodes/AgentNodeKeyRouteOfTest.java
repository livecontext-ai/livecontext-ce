package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.services.agent.AgentExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key route agent-service pinned for an execution reaches the orchestrator on the
 * response metrics and must be copied onto the observability report, since that report
 * is what triggers the debit (an OWN_KEY turn is billed a flat fee, not tokens).
 */
@DisplayName("AgentNode.keyRouteOf")
class AgentNodeKeyRouteOfTest {

    @Test
    @DisplayName("reads the route agent-service wrote on the response metrics")
    void readsTheRouteFromMetrics() {
        AgentExecutionResult result = AgentExecutionResult.builder()
            .success(true)
            .metrics(Map.of("keyRoute", "OWN_KEY"))
            .build();

        assertThat(AgentNode.keyRouteOf(result)).isEqualTo("OWN_KEY");
    }

    @Test
    @DisplayName("answers null (platform route at the debit) when the result carries no route: older agent-service, no metrics, blank or non-string value")
    void nullWhenAbsent() {
        Map<String, Object> blank = new HashMap<>();
        blank.put("keyRoute", "  ");
        Map<String, Object> wrongType = new HashMap<>();
        wrongType.put("keyRoute", 42);

        assertThat(AgentNode.keyRouteOf(null)).isNull();
        assertThat(AgentNode.keyRouteOf(AgentExecutionResult.builder().success(true).build())).isNull();
        assertThat(AgentNode.keyRouteOf(AgentExecutionResult.builder().success(true).metrics(Map.of()).build())).isNull();
        assertThat(AgentNode.keyRouteOf(AgentExecutionResult.builder().success(true).metrics(blank).build())).isNull();
        assertThat(AgentNode.keyRouteOf(AgentExecutionResult.builder().success(true).metrics(wrongType).build())).isNull();
    }
}

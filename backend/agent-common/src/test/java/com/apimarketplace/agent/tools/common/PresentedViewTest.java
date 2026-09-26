package com.apimarketplace.agent.tools.common;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PresentedView")
class PresentedViewTest {

    @Test
    @DisplayName("the visualization type is present_<kind>, never the plain marker a write result carries")
    void typeIsPrefixed() {
        ToolExecutionResult result = PresentedView.result("table", "table_id", "7", "Leads");

        assertThat(result.success()).isTrue();
        assertThat(result.metadata().get("visualization"))
            .isEqualTo(Map.of("type", "present_table", "id", "7", "title", "Leads"));
    }

    @Test
    @DisplayName("the agent reads back what was presented and the ids it named")
    void dataEchoesTheIds() {
        ToolExecutionResult result = PresentedView.result("application",
            Map.of("workflow_id", "wf-1", "run_id", "run-1"), "wf-1", "Lead Finder", Map.of("runId", "run-1"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data).containsEntry("presented", "application")
            .containsEntry("workflow_id", "wf-1").containsEntry("run_id", "run-1").containsKey("message");
        assertThat(result.metadata().get("visualization"))
            .isEqualTo(Map.of("type", "present_application", "id", "wf-1", "title", "Lead Finder", "runId", "run-1"));
    }

    @Test
    @DisplayName("a blank or missing name falls back to the label, so the panel title is never empty")
    void titleFallback() {
        assertThat(PresentedView.titleOf(null, "Agent")).isEqualTo("Agent");
        assertThat(PresentedView.titleOf("  ", "Agent")).isEqualTo("Agent");
        assertThat(PresentedView.titleOf("Scout", "Agent")).isEqualTo("Scout");
    }

    @Test
    @DisplayName("the agent's title param wins; a blank or absent one keeps the resource's title")
    void requestedTitle() {
        assertThat(PresentedView.requestedTitleOr(Map.of("title", " Results "), "Leads")).isEqualTo("Results");
        assertThat(PresentedView.requestedTitleOr(Map.of("title", "  "), "Leads")).isEqualTo("Leads");
        assertThat(PresentedView.requestedTitleOr(Map.of(), "Leads")).isEqualTo("Leads");
        assertThat(PresentedView.requestedTitleOr(null, "Leads")).isEqualTo("Leads");
    }
}

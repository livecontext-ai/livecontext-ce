package com.apimarketplace.orchestrator.controllers.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WorkflowLevelsResponse.
 */
@DisplayName("WorkflowLevelsResponse")
class WorkflowLevelsResponseTest {

    @Test
    @DisplayName("Should store constructor values")
    void shouldStoreConstructorValues() {
        List<Map<String, Object>> levels = List.of(
            Map.of("level", 0, "nodes", List.of("trigger:start")),
            Map.of("level", 1, "nodes", List.of("mcp:step1"))
        );

        WorkflowLevelsResponse response = new WorkflowLevelsResponse(levels, 1, 2, false, true);

        assertThat(response.getLevels()).hasSize(2);
        assertThat(response.getMaxLevel()).isEqualTo(1);
        assertThat(response.getTotalSteps()).isEqualTo(2);
        assertThat(response.isHasLoops()).isFalse();
        assertThat(response.isHasConditionalLogic()).isTrue();
    }

    @Test
    @DisplayName("Should handle empty levels")
    void shouldHandleEmptyLevels() {
        WorkflowLevelsResponse response = new WorkflowLevelsResponse(List.of(), 0, 0, false, false);

        assertThat(response.getLevels()).isEmpty();
        assertThat(response.getMaxLevel()).isZero();
        assertThat(response.getTotalSteps()).isZero();
    }

    @Test
    @DisplayName("Should report loops and conditional logic")
    void shouldReportLoopsAndConditionalLogic() {
        WorkflowLevelsResponse response = new WorkflowLevelsResponse(List.of(), 3, 10, true, true);

        assertThat(response.isHasLoops()).isTrue();
        assertThat(response.isHasConditionalLogic()).isTrue();
    }
}

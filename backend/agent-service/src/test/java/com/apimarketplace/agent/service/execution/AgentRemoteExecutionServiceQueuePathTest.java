package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the queue-path role propagation contract. The role string travels in
 * {@link AgentExecutionTask#metadata()} instead of the request DTO so the same
 * DTO remains usable by sync HTTP dispatch, while queued bridge executions can
 * still enforce {@code admin_only} policies.
 */
@DisplayName("AgentRemoteExecutionService - queue path role propagation")
class AgentRemoteExecutionServiceQueuePathTest {

    @Test
    @DisplayName("Back-compat executeAgent(request) overload remains for legacy queue tasks without role metadata")
    void oneArgExecuteAgentRemainsForLegacyQueueTasks() throws NoSuchMethodException {
        var method = AgentRemoteExecutionService.class.getDeclaredMethod(
                "executeAgent", AgentExecutionRequestDto.class);
        assertThat(method).isNotNull();
    }

    @Test
    @DisplayName("Three-arg executeByType accepts metadata roles from the queue worker")
    void executeByTypeAcceptsUserRolesFromQueueMetadata() throws NoSuchMethodException {
        var method = AgentRemoteExecutionService.class.getDeclaredMethod(
                "executeByType", String.class, String.class, String.class);
        assertThat(method).isNotNull();
    }

    @Test
    @DisplayName("AgentExecutionRequestDto keeps roles out of the JSON payload because metadata carries them")
    void dtoCarriesNoUserRolesField() {
        var components = AgentExecutionRequestDto.class.getRecordComponents();
        assertThat(components).isNotNull();
        for (var rc : components) {
            assertThat(rc.getName().toLowerCase()).doesNotContain("role");
        }
    }
}

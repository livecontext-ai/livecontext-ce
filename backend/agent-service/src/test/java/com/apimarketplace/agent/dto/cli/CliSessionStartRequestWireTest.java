package com.apimarketplace.agent.dto.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session start body is written by {@code mcp/agent-cli-server.mjs}, in another language
 * and another test suite, so the field NAMES are the contract. A renamed record component
 * compiles on its own and simply stops receiving the value; nothing in Java would notice.
 */
class CliSessionStartRequestWireTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("maxToolHoldSeconds is the wire name the bridge sends, next to the watchdog window")
    void readsTheBridgeFieldNames() throws Exception {
        String body = "{\"conversationId\":\"conv-1\",\"streamId\":\"s-1\","
                + "\"inactivityTimeoutSeconds\":300,\"maxToolHoldSeconds\":350}";

        CliSessionStartRequest request = mapper.readValue(body, CliSessionStartRequest.class);

        assertThat(request.conversationId()).isEqualTo("conv-1");
        assertThat(request.inactivityTimeoutSeconds()).isEqualTo(300);
        assertThat(request.maxToolHoldSeconds()).isEqualTo(350);
    }

    @Test
    @DisplayName("the run markers are read under the names agent-cli-server sends")
    void readsTheRunMarkers() throws Exception {
        String body = "{\"conversationId\":\"conv-1\",\"taskId\":\"task-9\",\"unattendedRun\":true,"
                + "\"requireToolAuthorization\":true,\"agentDepth\":2,\"workflowRunId\":\"run-4\"}";

        CliSessionStartRequest request = mapper.readValue(body, CliSessionStartRequest.class);

        assertThat(request.taskId()).isEqualTo("task-9");
        assertThat(request.unattendedRun()).isTrue();
        assertThat(request.requireToolAuthorization()).isTrue();
        assertThat(request.agentDepth()).isEqualTo(2);
        assertThat(request.workflowRunId()).isEqualTo("run-4");
    }

    @Test
    @DisplayName("a body from an older bridge, without the field, still parses and reads null")
    void olderBridgeBodyStillParses() throws Exception {
        String body = "{\"conversationId\":\"conv-1\",\"streamId\":\"s-1\",\"inactivityTimeoutSeconds\":300}";

        CliSessionStartRequest request = mapper.readValue(body, CliSessionStartRequest.class);

        assertThat(request.maxToolHoldSeconds()).isNull();
        assertThat(request.inactivityTimeoutSeconds()).isEqualTo(300);
        assertThat(request.taskId()).isNull();
        assertThat(request.unattendedRun()).isNull();
        assertThat(request.agentDepth()).isNull();
        assertThat(request.workflowRunId()).isNull();
    }
}

package com.apimarketplace.agent.service.dto;

import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The chat path reports a model replacement in the JSON body conversation-service posts
 * ({@code modelReplaced} / {@code replacedModel}). Before these fields existed on the record
 * the values were dropped at deserialization, so no chat {@code agent_run_stopped} event
 * ever carried {@code model_replaced}.
 */
class ChatObservabilityAdapterModelReplacementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AgentObservabilityRequest adapt(String json) throws Exception {
        ChatAgentObservabilityRequest request = MAPPER.readValue(json, ChatAgentObservabilityRequest.class);
        return ChatObservabilityAdapter.toUnifiedRequest("42", "org-1", request);
    }

    @Test
    @DisplayName("modelReplaced=true and replacedModel posted by conversation-service reach the unified request")
    void replacementReportSurvivesDeserializationAndAdapter() throws Exception {
        AgentObservabilityRequest req = adapt(
                "{\"success\":true,\"model\":\"new-model\",\"modelReplaced\":true,\"replacedModel\":\"old-model\"}");

        assertThat(req.getModelReplaced()).isTrue();
        assertThat(req.getReplacedModel()).isEqualTo("old-model");
    }

    @Test
    @DisplayName("modelReplaced=false keeps the flag but never carries a replacedModel")
    void replacedModelIsDroppedWhenNotReplaced() throws Exception {
        AgentObservabilityRequest req = adapt(
                "{\"success\":true,\"model\":\"m\",\"modelReplaced\":false,\"replacedModel\":\"stray\"}");

        assertThat(req.getModelReplaced()).isFalse();
        assertThat(req.getReplacedModel()).isNull();
    }

    @Test
    @DisplayName("a body without the report leaves both fields null (not reported)")
    void absentReportStaysNull() throws Exception {
        AgentObservabilityRequest req = adapt("{\"success\":true,\"model\":\"m\",\"keyRoute\":\"OWN_KEY\"}");

        assertThat(req.getModelReplaced()).isNull();
        assertThat(req.getReplacedModel()).isNull();
        assertThat(req.getKeyRoute()).isEqualTo("OWN_KEY");
    }
}

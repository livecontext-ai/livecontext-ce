package com.apimarketplace.catalog.service.submission;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ApiSubmissionCommand(JsonNode payload, String userId, List<JsonNode> tools) {

    public ApiSubmissionCommand(JsonNode payload, String userId) {
        this(payload, userId, extractTools(payload));
    }

    public String apiName() {
        return payload.path("apiName").asText("Unnamed API");
    }

    public String apiDescription() {
        return payload.path("apiDescription").asText("No description provided");
    }

    private static List<JsonNode> extractTools(JsonNode payload) {
        if (payload == null) {
            return List.of();
        }
        JsonNode mcpTools = payload.get("mcpTools");
        if (mcpTools == null || !mcpTools.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        mcpTools.forEach(result::add);
        return Collections.unmodifiableList(result);
    }
}

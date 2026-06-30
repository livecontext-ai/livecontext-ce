package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Wire-format tests for {@link ToolExecutionRequest}.
 *
 * <p>Locks the snake_case alias on {@code max_items} - the catalog HTTP body
 * sent by {@code CatalogExecuteModule.executeCatalogExecute} uses
 * {@code "max_items"}, and a silent deser miss would mean AGENT mode never
 * receives the agent's pagination cap.
 */
@DisplayName("ToolExecutionRequest deserialisation")
class ToolExecutionRequestDeserialisationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("snake_case max_items wire field deserialises to maxItems Java field")
    void snakeCaseMaxItemsDeserialises() throws Exception {
        String json = "{\"parameters\":{\"q\":\"foo\"}, \"max_items\": 5}";

        ToolExecutionRequest req = objectMapper.readValue(json, ToolExecutionRequest.class);

        assertEquals(5, req.getMaxItems());
    }

    @Test
    @DisplayName("camelCase maxItems wire field also deserialises (back-compat)")
    void camelCaseMaxItemsDeserialises() throws Exception {
        String json = "{\"parameters\":{\"q\":\"foo\"}, \"maxItems\": 7}";

        ToolExecutionRequest req = objectMapper.readValue(json, ToolExecutionRequest.class);

        assertEquals(7, req.getMaxItems());
    }

    @Test
    @DisplayName("missing max_items leaves field null")
    void missingMaxItemsLeavesNull() throws Exception {
        String json = "{\"parameters\":{\"q\":\"foo\"}}";

        ToolExecutionRequest req = objectMapper.readValue(json, ToolExecutionRequest.class);

        assertNull(req.getMaxItems());
    }
}

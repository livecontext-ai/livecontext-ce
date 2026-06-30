package com.apimarketplace.orchestrator.services.notification;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificationsResponse wire contract")
class NotificationsResponseTest {

    @Test
    @DisplayName("empty page items serialize as [] even when the mapper omits empty values")
    void emptyItemsAreSerialized() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        NotificationsResponse response = new NotificationsResponse(List.of(), 0, 99, 5, false);

        JsonNode root = mapper.readTree(mapper.writeValueAsString(response));

        assertThat(root.has("items")).isTrue();
        assertThat(root.get("items").isArray()).isTrue();
        assertThat(root.get("items")).isEmpty();
        assertThat(root.get("page").asInt()).isEqualTo(99);
        assertThat(root.get("size").asInt()).isEqualTo(5);
    }
}

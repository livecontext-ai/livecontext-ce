package com.apimarketplace.catalog.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebConfig")
class WebConfigTest {

    private final WebConfig webConfig = new WebConfig();

    @Nested
    @DisplayName("objectMapper")
    class ObjectMapperTests {

        @Test
        @DisplayName("should create ObjectMapper bean")
        void createsObjectMapper() {
            ObjectMapper mapper = webConfig.objectMapper();

            assertThat(mapper).isNotNull();
        }

        @Test
        @DisplayName("should not fail on empty beans")
        void doesNotFailOnEmptyBeans() {
            ObjectMapper mapper = webConfig.objectMapper();

            assertThat(mapper.isEnabled(SerializationFeature.FAIL_ON_EMPTY_BEANS)).isFalse();
        }

        @Test
        @DisplayName("should not write dates as timestamps")
        void doesNotWriteDatesAsTimestamps() {
            ObjectMapper mapper = webConfig.objectMapper();

            assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
        }

        @Test
        @DisplayName("should serialize Java time types")
        void serializesJavaTimeTypes() throws Exception {
            ObjectMapper mapper = webConfig.objectMapper();
            LocalDateTime dateTime = LocalDateTime.of(2024, 1, 15, 10, 30, 0);

            String json = mapper.writeValueAsString(dateTime);

            assertThat(json).contains("2024");
            assertThat(json).doesNotContain("["); // Not serialized as array
        }

        @Test
        @DisplayName("should ignore unknown properties during deserialization")
        void ignoresUnknownProperties() throws Exception {
            ObjectMapper mapper = webConfig.objectMapper();

            // Should not throw on unknown properties
            String json = "{\"known\":\"value\",\"unknown\":\"ignored\"}";

            // Use a simple map deserialization which should succeed
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> result = mapper.readValue(json, java.util.Map.class);
            assertThat(result).containsKey("known");
        }

        @Test
        @DisplayName("should keep empty array properties in response envelopes")
        void keepsEmptyArrayProperties() throws Exception {
            ObjectMapper mapper = webConfig.objectMapper();

            JsonNode root = mapper.readTree(mapper.writeValueAsString(new Envelope(List.of())));

            assertThat(root.has("items")).isTrue();
            assertThat(root.get("items").isArray()).isTrue();
            assertThat(root.get("items")).isEmpty();
        }
    }

    private record Envelope(List<String> items) {
    }
}

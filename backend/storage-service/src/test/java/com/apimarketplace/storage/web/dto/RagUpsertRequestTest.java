package com.apimarketplace.storage.web.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RagUpsertRequest DTO Tests")
class RagUpsertRequestTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build with all fields set")
        void shouldBuildWithAllFieldsSet() {
            Map<String, Object> meta = Map.of("source", "web", "language", "en");

            RagUpsertRequest request = RagUpsertRequest.builder()
                .id("rag-001")
                .text("Sample text for vector embedding")
                .meta(meta)
                .collection("knowledge-base")
                .build();

            assertThat(request.getId()).isEqualTo("rag-001");
            assertThat(request.getText()).isEqualTo("Sample text for vector embedding");
            assertThat(request.getMeta()).containsEntry("source", "web");
            assertThat(request.getMeta()).containsEntry("language", "en");
            assertThat(request.getCollection()).isEqualTo("knowledge-base");
        }

        @Test
        @DisplayName("should build with minimal fields")
        void shouldBuildWithMinimalFields() {
            RagUpsertRequest request = RagUpsertRequest.builder()
                .id("rag-min")
                .text("Minimal text")
                .build();

            assertThat(request.getId()).isEqualTo("rag-min");
            assertThat(request.getText()).isEqualTo("Minimal text");
            assertThat(request.getMeta()).isNull();
            assertThat(request.getCollection()).isNull();
        }
    }

    @Nested
    @DisplayName("Setters")
    class SettersTests {

        @Test
        @DisplayName("should set and get id")
        void shouldSetAndGetId() {
            RagUpsertRequest request = RagUpsertRequest.builder().build();
            request.setId("new-id");

            assertThat(request.getId()).isEqualTo("new-id");
        }

        @Test
        @DisplayName("should set and get text")
        void shouldSetAndGetText() {
            RagUpsertRequest request = RagUpsertRequest.builder().build();
            request.setText("Updated text content");

            assertThat(request.getText()).isEqualTo("Updated text content");
        }

        @Test
        @DisplayName("should set and get meta")
        void shouldSetAndGetMeta() {
            RagUpsertRequest request = RagUpsertRequest.builder().build();
            Map<String, Object> meta = Map.of("key", "value");
            request.setMeta(meta);

            assertThat(request.getMeta()).containsEntry("key", "value");
        }

        @Test
        @DisplayName("should set and get collection")
        void shouldSetAndGetCollection() {
            RagUpsertRequest request = RagUpsertRequest.builder().build();
            request.setCollection("my-collection");

            assertThat(request.getCollection()).isEqualTo("my-collection");
        }
    }
}

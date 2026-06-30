package com.apimarketplace.storage.web.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DocumentRequest DTO Tests")
class DocumentRequestTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build with all fields set")
        void shouldBuildWithAllFieldsSet() {
            Map<String, Object> document = Map.of("title", "Test");
            Map<String, Object> metadata = Map.of("source", "api");

            DocumentRequest request = DocumentRequest.builder()
                .collection("test-collection")
                .document(document)
                .metadata(metadata)
                .build();

            assertThat(request.getCollection()).isEqualTo("test-collection");
            assertThat(request.getDocument()).containsEntry("title", "Test");
            assertThat(request.getMetadata()).containsEntry("source", "api");
        }

        @Test
        @DisplayName("should build with minimal fields")
        void shouldBuildWithMinimalFields() {
            DocumentRequest request = DocumentRequest.builder()
                .collection("minimal")
                .build();

            assertThat(request.getCollection()).isEqualTo("minimal");
            assertThat(request.getDocument()).isNull();
            assertThat(request.getMetadata()).isNull();
        }
    }

    @Nested
    @DisplayName("Setters")
    class SettersTests {

        @Test
        @DisplayName("should set and get collection")
        void shouldSetAndGetCollection() {
            DocumentRequest request = DocumentRequest.builder().build();
            request.setCollection("my-collection");

            assertThat(request.getCollection()).isEqualTo("my-collection");
        }

        @Test
        @DisplayName("should set and get document")
        void shouldSetAndGetDocument() {
            DocumentRequest request = DocumentRequest.builder().build();
            Map<String, Object> document = Map.of("key", "value");
            request.setDocument(document);

            assertThat(request.getDocument()).isEqualTo(document);
        }

        @Test
        @DisplayName("should set and get metadata")
        void shouldSetAndGetMetadata() {
            DocumentRequest request = DocumentRequest.builder().build();
            Map<String, Object> metadata = Map.of("author", "test");
            request.setMetadata(metadata);

            assertThat(request.getMetadata()).isEqualTo(metadata);
        }
    }
}

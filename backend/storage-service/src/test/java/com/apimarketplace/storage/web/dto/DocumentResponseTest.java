package com.apimarketplace.storage.web.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DocumentResponse DTO Tests")
class DocumentResponseTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build a complete response")
        void shouldBuildCompleteResponse() {
            Map<String, Object> document = Map.of("title", "Test Document");
            Map<String, Object> metadata = Map.of("version", 1);
            long now = System.currentTimeMillis();

            DocumentResponse response = DocumentResponse.builder()
                .id("doc-123")
                .collection("test")
                .document(document)
                .metadata(metadata)
                .createdAt(now)
                .updatedAt(now)
                .error(null)
                .build();

            assertThat(response.getId()).isEqualTo("doc-123");
            assertThat(response.getCollection()).isEqualTo("test");
            assertThat(response.getDocument()).containsEntry("title", "Test Document");
            assertThat(response.getMetadata()).containsEntry("version", 1);
            assertThat(response.getCreatedAt()).isEqualTo(now);
            assertThat(response.getUpdatedAt()).isEqualTo(now);
            assertThat(response.getError()).isNull();
        }

        @Test
        @DisplayName("should build an error response")
        void shouldBuildErrorResponse() {
            DocumentResponse response = DocumentResponse.builder()
                .error("Something went wrong")
                .build();

            assertThat(response.getError()).isEqualTo("Something went wrong");
            assertThat(response.getId()).isNull();
            assertThat(response.getCollection()).isNull();
        }

        @Test
        @DisplayName("should build with only id")
        void shouldBuildWithOnlyId() {
            DocumentResponse response = DocumentResponse.builder()
                .id("doc-456")
                .build();

            assertThat(response.getId()).isEqualTo("doc-456");
            assertThat(response.getCreatedAt()).isZero();
            assertThat(response.getUpdatedAt()).isZero();
        }
    }

    @Nested
    @DisplayName("Setters")
    class SettersTests {

        @Test
        @DisplayName("should set and get all fields")
        void shouldSetAndGetAllFields() {
            DocumentResponse response = DocumentResponse.builder().build();

            response.setId("doc-789");
            response.setCollection("updated");
            response.setDocument(Map.of("data", "value"));
            response.setMetadata(Map.of("key", "meta"));
            response.setCreatedAt(1000L);
            response.setUpdatedAt(2000L);
            response.setError("test error");

            assertThat(response.getId()).isEqualTo("doc-789");
            assertThat(response.getCollection()).isEqualTo("updated");
            assertThat(response.getDocument()).containsEntry("data", "value");
            assertThat(response.getMetadata()).containsEntry("key", "meta");
            assertThat(response.getCreatedAt()).isEqualTo(1000L);
            assertThat(response.getUpdatedAt()).isEqualTo(2000L);
            assertThat(response.getError()).isEqualTo("test error");
        }
    }
}

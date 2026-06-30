package com.apimarketplace.storage.web.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RagUpsertResponse DTO Tests")
class RagUpsertResponseTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build a successful response")
        void shouldBuildSuccessfulResponse() {
            long now = System.currentTimeMillis();

            RagUpsertResponse response = RagUpsertResponse.builder()
                .id("rag-001")
                .status("indexed")
                .vectorCount(5)
                .indexedAt(now)
                .build();

            assertThat(response.getId()).isEqualTo("rag-001");
            assertThat(response.getStatus()).isEqualTo("indexed");
            assertThat(response.getVectorCount()).isEqualTo(5);
            assertThat(response.getIndexedAt()).isEqualTo(now);
            assertThat(response.getError()).isNull();
        }

        @Test
        @DisplayName("should build an error response")
        void shouldBuildErrorResponse() {
            RagUpsertResponse response = RagUpsertResponse.builder()
                .id("rag-002")
                .status("error")
                .error("Embedding generation failed")
                .build();

            assertThat(response.getId()).isEqualTo("rag-002");
            assertThat(response.getStatus()).isEqualTo("error");
            assertThat(response.getError()).isEqualTo("Embedding generation failed");
            assertThat(response.getVectorCount()).isZero();
        }

        @Test
        @DisplayName("should build with default values for primitive fields")
        void shouldBuildWithDefaults() {
            RagUpsertResponse response = RagUpsertResponse.builder().build();

            assertThat(response.getVectorCount()).isZero();
            assertThat(response.getIndexedAt()).isZero();
            assertThat(response.getId()).isNull();
            assertThat(response.getStatus()).isNull();
            assertThat(response.getError()).isNull();
        }
    }

    @Nested
    @DisplayName("Setters")
    class SettersTests {

        @Test
        @DisplayName("should set and get all fields")
        void shouldSetAndGetAllFields() {
            RagUpsertResponse response = RagUpsertResponse.builder().build();

            response.setId("rag-updated");
            response.setStatus("processing");
            response.setVectorCount(10);
            response.setIndexedAt(999L);
            response.setError("temporary error");

            assertThat(response.getId()).isEqualTo("rag-updated");
            assertThat(response.getStatus()).isEqualTo("processing");
            assertThat(response.getVectorCount()).isEqualTo(10);
            assertThat(response.getIndexedAt()).isEqualTo(999L);
            assertThat(response.getError()).isEqualTo("temporary error");
        }
    }
}

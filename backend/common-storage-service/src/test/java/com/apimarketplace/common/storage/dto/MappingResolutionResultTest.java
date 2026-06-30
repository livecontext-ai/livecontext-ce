package com.apimarketplace.common.storage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MappingResolutionResult DTO Tests")
class MappingResolutionResultTest {

    @Nested
    @DisplayName("success factory method")
    class SuccessFactoryTests {

        @Test
        @DisplayName("should create successful result with preview and item count")
        void shouldCreateSuccessfulResult() {
            Map<String, Object> preview = Map.of("field1", "value1");

            MappingResolutionResult result = MappingResolutionResult.success(preview, 10);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getPreview()).containsEntry("field1", "value1");
            assertThat(result.getItemCount()).isEqualTo(10);
            assertThat(result.getError()).isNull();
            assertThat(result.getUnresolvedFields()).isEmpty();
        }

        @Test
        @DisplayName("should create successful result with empty preview")
        void shouldCreateSuccessfulResultWithEmptyPreview() {
            MappingResolutionResult result = MappingResolutionResult.success(Collections.emptyMap(), 0);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getPreview()).isEmpty();
            assertThat(result.getItemCount()).isZero();
        }
    }

    @Nested
    @DisplayName("failure factory method")
    class FailureFactoryTests {

        @Test
        @DisplayName("should create failure result with error message")
        void shouldCreateFailureResult() {
            MappingResolutionResult result = MappingResolutionResult.failure("Something went wrong");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).isEqualTo("Something went wrong");
            assertThat(result.getPreview()).isEmpty();
            assertThat(result.getItemCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build with all fields set")
        void shouldBuildWithAllFields() {
            Map<String, Object> preview = Map.of("key", "value");
            List<String> unresolved = List.of("field1", "field2");

            MappingResolutionResult result = MappingResolutionResult.builder()
                .success(true)
                .preview(preview)
                .itemCount(5)
                .unresolvedFields(unresolved)
                .error(null)
                .build();

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getPreview()).containsEntry("key", "value");
            assertThat(result.getItemCount()).isEqualTo(5);
            assertThat(result.getUnresolvedFields()).containsExactly("field1", "field2");
            assertThat(result.getError()).isNull();
        }

        @Test
        @DisplayName("should default preview to empty map when null")
        void shouldDefaultPreviewToEmptyMap() {
            MappingResolutionResult result = MappingResolutionResult.builder()
                .success(true)
                .preview(null)
                .build();

            assertThat(result.getPreview()).isNotNull();
            assertThat(result.getPreview()).isEmpty();
        }

        @Test
        @DisplayName("should default unresolvedFields to empty list when null")
        void shouldDefaultUnresolvedFieldsToEmptyList() {
            MappingResolutionResult result = MappingResolutionResult.builder()
                .success(true)
                .unresolvedFields(null)
                .build();

            assertThat(result.getUnresolvedFields()).isNotNull();
            assertThat(result.getUnresolvedFields()).isEmpty();
        }

        @Test
        @DisplayName("should build with error only")
        void shouldBuildWithErrorOnly() {
            MappingResolutionResult result = MappingResolutionResult.builder()
                .success(false)
                .error("Network timeout")
                .build();

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).isEqualTo("Network timeout");
        }
    }
}

package com.apimarketplace.datasource.controllers.datasource;

import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.SortDirection;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.SortRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DataSourceRequestParser.
 */
@DisplayName("DataSourceRequestParser")
class DataSourceRequestParserTest {

    private DataSourceRequestParser parser;

    @BeforeEach
    void setUp() {
        parser = new DataSourceRequestParser();
    }

    @Nested
    @DisplayName("parseSortParameter")
    class ParseSortParameterTests {

        @ParameterizedTest(name = "Should return empty list for null/empty: ''{0}''")
        @NullAndEmptySource
        @DisplayName("Should return empty list for null or empty sort")
        void shouldReturnEmptyForNullOrEmpty(String sort) {
            List<SortRequest> result = parser.parseSortParameter(sort);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should parse single sort parameter")
        void shouldParseSingleSort() {
            List<SortRequest> result = parser.parseSortParameter("name:asc");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).colId()).isEqualTo("name");
            assertThat(result.get(0).sort()).isEqualTo(SortDirection.ASC);
        }

        @Test
        @DisplayName("Should parse multiple sort parameters")
        void shouldParseMultipleSorts() {
            List<SortRequest> result = parser.parseSortParameter("priority:desc,created_at:asc");

            assertThat(result).hasSize(2);
            assertThat(result.get(0).colId()).isEqualTo("priority");
            assertThat(result.get(0).sort()).isEqualTo(SortDirection.DESC);
            assertThat(result.get(1).colId()).isEqualTo("created_at");
            assertThat(result.get(1).sort()).isEqualTo(SortDirection.ASC);
        }

        @Test
        @DisplayName("Should default to ASC for column without direction")
        void shouldDefaultToAscWithoutDirection() {
            List<SortRequest> result = parser.parseSortParameter("name");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).colId()).isEqualTo("name");
            assertThat(result.get(0).sort()).isEqualTo(SortDirection.ASC);
        }

        @Test
        @DisplayName("Should default to ASC for invalid direction")
        void shouldDefaultToAscForInvalidDirection() {
            List<SortRequest> result = parser.parseSortParameter("name:invalid");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).colId()).isEqualTo("name");
            assertThat(result.get(0).sort()).isEqualTo(SortDirection.ASC);
        }
    }

    @Nested
    @DisplayName("parseSortParameters (sortBy, sortOrder)")
    class ParseSortParametersTests {

        @Test
        @DisplayName("Should parse sortBy and sortOrder")
        void shouldParseSortByAndOrder() {
            List<SortRequest> result = parser.parseSortParameters("name", "desc");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).colId()).isEqualTo("name");
            assertThat(result.get(0).sort()).isEqualTo(SortDirection.DESC);
        }

        @Test
        @DisplayName("Should return empty list when sortBy is null")
        void shouldReturnEmptyWhenSortByNull() {
            List<SortRequest> result = parser.parseSortParameters(null, "asc");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should default to ASC for invalid sortOrder")
        void shouldDefaultToAscForInvalidOrder() {
            List<SortRequest> result = parser.parseSortParameters("name", "xyz");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).sort()).isEqualTo(SortDirection.ASC);
        }
    }

    @Nested
    @DisplayName("parseFilterParameter")
    class ParseFilterParameterTests {

        @ParameterizedTest(name = "Should return empty map for null/empty filter: ''{0}''")
        @NullAndEmptySource
        @DisplayName("Should return empty map for null or empty filter")
        void shouldReturnEmptyMapForNullOrEmpty(String filter) {
            Map<String, Object> result = parser.parseFilterParameter(filter);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return search map for plain text filter")
        void shouldReturnSearchMapForPlainText() {
            Map<String, Object> result = parser.parseFilterParameter("hello");

            assertThat(result).containsEntry("search", "hello");
        }

        @Test
        @DisplayName("Should return empty map for JSON-like filter")
        void shouldReturnEmptyMapForJsonFilter() {
            Map<String, Object> result = parser.parseFilterParameter("{\"status\":\"active\"}");

            assertThat(result).isEmpty(); // JSON parsing returns empty for now
        }
    }

    @Nested
    @DisplayName("mapToJsonPatchOperation")
    class MapToJsonPatchOperationTests {

        @Test
        @DisplayName("Should convert map to JsonPatchOperation")
        void shouldConvertMap() {
            Map<String, Object> patchMap = Map.of(
                "op", "replace",
                "path", "/name",
                "value", "New Name"
            );

            var result = parser.mapToJsonPatchOperation(patchMap);

            assertThat(result.path()).isEqualTo("/name");
            assertThat(result.value()).isEqualTo("New Name");
        }
    }
}

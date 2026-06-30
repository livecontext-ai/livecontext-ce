package com.apimarketplace.orchestrator.stepdata;

import com.apimarketplace.orchestrator.domain.execution.NodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DetailedStepDataResponse record.
 */
@DisplayName("DetailedStepDataResponse")
class DetailedStepDataResponseTest {

    @Nested
    @DisplayName("record fields")
    class RecordFieldTests {

        @Test
        @DisplayName("Should store all fields correctly")
        void shouldStoreAllFields() {
            List<ColumnDefinition> columns = List.of(
                ColumnDefinition.builder().field("status").header("Status").build()
            );
            List<Map<String, Object>> rows = List.of(
                Map.of("status", "COMPLETED")
            );
            DetailedStepDataResponse.PaginationInfo pagination =
                DetailedStepDataResponse.PaginationInfo.of(0, 20, 100);

            DetailedStepDataResponse response = new DetailedStepDataResponse(
                NodeType.MCP, "fetch_data", "api/tool-1", columns, rows, pagination
            );

            assertThat(response.nodeType()).isEqualTo(NodeType.MCP);
            assertThat(response.stepAlias()).isEqualTo("fetch_data");
            assertThat(response.toolId()).isEqualTo("api/tool-1");
            assertThat(response.columns()).hasSize(1);
            assertThat(response.rows()).hasSize(1);
            assertThat(response.pagination()).isNotNull();
        }

        @Test
        @DisplayName("Should preserve null map entries in detailed row output")
        void shouldPreserveNullMapEntriesInDetailedRowOutput() throws Exception {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("file", null);
            output.put("source_url", "http://localhost:8099/actuator/health");
            List<Map<String, Object>> rows = List.of(Map.of("output", output));

            DetailedStepDataResponse response = new DetailedStepDataResponse(
                NodeType.DOWNLOAD_FILE,
                "unsafe_download",
                "Unsafe Download",
                List.of(),
                rows,
                DetailedStepDataResponse.PaginationInfo.of(1, 20, 1)
            );

            String json = new ObjectMapper().writeValueAsString(response);

            assertThat(json).contains("\"file\":null");
            assertThat(json).contains("\"source_url\":\"http://localhost:8099/actuator/health\"");
        }
    }

    @Nested
    @DisplayName("PaginationInfo")
    class PaginationInfoTests {

        @Test
        @DisplayName("Should create with of factory and calculate hasMore correctly")
        void shouldCalculateHasMore() {
            DetailedStepDataResponse.PaginationInfo page0 =
                DetailedStepDataResponse.PaginationInfo.of(0, 20, 100);

            assertThat(page0.page()).isZero();
            assertThat(page0.pageSize()).isEqualTo(20);
            assertThat(page0.totalRows()).isEqualTo(100);
            assertThat(page0.hasMore()).isTrue();
        }

        @Test
        @DisplayName("Should return false for hasMore when on last page")
        void shouldReturnFalseOnLastPage() {
            DetailedStepDataResponse.PaginationInfo lastPage =
                DetailedStepDataResponse.PaginationInfo.of(5, 20, 100);

            // page 5 * pageSize 20 = 100, which is not < 100
            assertThat(lastPage.hasMore()).isFalse();
        }

        @Test
        @DisplayName("Should return false when total rows is zero")
        void shouldReturnFalseWhenEmpty() {
            DetailedStepDataResponse.PaginationInfo empty =
                DetailedStepDataResponse.PaginationInfo.of(0, 20, 0);

            assertThat(empty.hasMore()).isFalse();
        }

        @Test
        @DisplayName("Should return true when there are more pages")
        void shouldReturnTrueWhenMorePages() {
            DetailedStepDataResponse.PaginationInfo middle =
                DetailedStepDataResponse.PaginationInfo.of(2, 10, 50);

            // page 2 * pageSize 10 = 20, which is < 50
            assertThat(middle.hasMore()).isTrue();
        }
    }
}

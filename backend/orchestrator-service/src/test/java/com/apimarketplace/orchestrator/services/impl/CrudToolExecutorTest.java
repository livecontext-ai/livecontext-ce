package com.apimarketplace.orchestrator.services.impl;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.CrudResultDto;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for CrudToolExecutor - ensures CRUD operations are dispatched correctly
 * and output is coherent.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CrudToolExecutor")
class CrudToolExecutorTest {

    @Mock
    private DataSourceClient dataSourceClient;

    private CrudToolExecutor executor;

    @BeforeEach
    void setUp() {
        var limits = new com.apimarketplace.orchestrator.config.OrchestratorLimitsConfig();
        executor = new CrudToolExecutor(dataSourceClient, limits);
    }

    @Nested
    @DisplayName("Operation routing")
    class OperationRoutingTests {

        @Test
        @DisplayName("Should route crud/find to read-row operation")
        void shouldRouteFindToReadRow() {
            CrudResultDto result = new CrudResultDto(
                "read-row", true, "Read 3 rows",
                new CrudResultDto.ResultData(
                    List.of(Map.of("name", "A"), Map.of("name", "B"), Map.of("name", "C")),
                    3, false, 0, null, null, null, null, null
                )
            );
            when(dataSourceClient.executeCrud(any())).thenReturn(result);

            Map<String, Object> input = Map.of("dataSourceId", 1L, "crud", Map.of());
            ExecutionResult execResult = executor.execute("crud/find", input, "tenant-1");

            assertThat(execResult.isSuccess()).isTrue();
            assertThat(execResult.output()).containsKey("rows");
            assertThat(execResult.output().get("operation")).isEqualTo("find");
        }

        @Test
        @DisplayName("Should route crud/read-row correctly")
        void shouldRouteReadRow() {
            CrudResultDto result = new CrudResultDto(
                "read-row", true, "Read 2 rows",
                new CrudResultDto.ResultData(
                    List.of(Map.of("name", "Alice"), Map.of("name", "Bob")),
                    2, false, 0, null, null, null, null, null
                )
            );
            when(dataSourceClient.executeCrud(any())).thenReturn(result);

            Map<String, Object> input = Map.of("dataSourceId", 1L, "crud", Map.of());
            ExecutionResult execResult = executor.execute("crud/read-row", input, "tenant-1");

            assertThat(execResult.isSuccess()).isTrue();
            assertThat(execResult.output()).containsKey("rows");
            assertThat(execResult.output().get("row_count")).isEqualTo(2);
        }

        @Test
        @DisplayName("Should route crud/create-row correctly")
        void shouldRouteCreateRow() {
            CrudResultDto result = new CrudResultDto(
                "create-row", true, "Created 1 row",
                new CrudResultDto.ResultData(
                    null, null, null, null, List.of(42L), 1, null, null, null
                )
            );
            when(dataSourceClient.executeCrud(any())).thenReturn(result);

            Map<String, Object> input = Map.of(
                "dataSourceId", 1L,
                "crud", Map.of("rows", List.of(Map.of("id", "r1", "columns", Map.of("name", "Test"))))
            );
            ExecutionResult execResult = executor.execute("crud/create-row", input, "tenant-1");

            assertThat(execResult.isSuccess()).isTrue();
            assertThat(execResult.output().get("row_id")).isEqualTo("42");
            assertThat(execResult.output().get("inserted_count")).isEqualTo(1);
            assertThat(execResult.output().get("inserted_values")).isEqualTo(Map.of("name", "Test"));
            assertThat(execResult.output().get("created_at")).isNotNull();
        }

        @Test
        @DisplayName("Should return error for unknown operation")
        void shouldReturnErrorForUnknownOperation() {
            Map<String, Object> input = Map.of("dataSourceId", 1L, "crud", Map.of());
            ExecutionResult execResult = executor.execute("crud/unknown-op", input, "tenant-1");

            assertThat(execResult.isSuccess()).isFalse();
            assertThat(execResult.output().get("error").toString()).contains("Unknown CRUD operation");
        }
    }

    @Nested
    @DisplayName("Output coherence")
    class OutputCoherenceTests {

        @Test
        @DisplayName("Read-row output should contain rows, row_count, operation, success")
        void readRowOutputShouldBeCoherent() {
            List<Map<String, Object>> rows = List.of(
                Map.of("id", 1, "name", "Alice"),
                Map.of("id", 2, "name", "Bob")
            );
            CrudResultDto result = new CrudResultDto(
                "read-row", true, "Read 2 rows",
                new CrudResultDto.ResultData(rows, 2, true, 0, null, null, null, null, null)
            );
            when(dataSourceClient.executeCrud(any())).thenReturn(result);

            ExecutionResult execResult = executor.execute("crud/read-row", Map.of("dataSourceId", 1L, "crud", Map.of()), "t1");

            assertThat(execResult.isSuccess()).isTrue();
            assertThat(execResult.output().get("operation")).isEqualTo("read-row");
            assertThat(execResult.output().get("success")).isEqualTo(true);
            assertThat(execResult.output().get("rows")).isEqualTo(rows);
            assertThat(execResult.output().get("row_count")).isEqualTo(2);
            assertThat(execResult.output().get("has_more")).isEqualTo(true);
        }

        @Test
        @DisplayName("Create-row output should contain row_id, inserted_values, created_at")
        void createRowOutputShouldBeCoherent() {
            CrudResultDto result = new CrudResultDto(
                "create-row", true, "Created 1 row",
                new CrudResultDto.ResultData(null, null, null, null, List.of(10L), 1, null, null, null)
            );
            when(dataSourceClient.executeCrud(any())).thenReturn(result);

            ExecutionResult execResult = executor.execute("crud/create-row",
                Map.of("dataSourceId", 1L, "crud", Map.of("rows", List.of(Map.of("id", "r1", "columns", Map.of("a", "b"))))), "t1");

            assertThat(execResult.output().get("row_id")).isEqualTo("10");
            assertThat(execResult.output().get("inserted_count")).isEqualTo(1);
            assertThat(execResult.output().get("inserted_values")).isEqualTo(Map.of("a", "b"));
            assertThat(execResult.output().get("created_at")).isNotNull();
        }

        @Test
        @DisplayName("Update-row output should contain updated_count, updated_at")
        void updateRowOutputShouldBeCoherent() {
            CrudResultDto result = new CrudResultDto(
                "update-row", true, "Updated 3 rows",
                new CrudResultDto.ResultData(null, null, null, null, null, null, 3, null, null)
            );
            when(dataSourceClient.executeCrud(any())).thenReturn(result);

            Map<String, Object> crud = Map.of(
                "where", Map.of("column", "status", "operator", "=", "value", "active"),
                "set", Map.of("status", "inactive")
            );
            ExecutionResult execResult = executor.execute("crud/update-row",
                Map.of("dataSourceId", 1L, "crud", crud), "t1");

            assertThat(execResult.isSuccess()).isTrue();
            assertThat(execResult.output().get("updated_count")).isEqualTo(3);
            assertThat(execResult.output().get("rows_affected")).isEqualTo(3);
            assertThat(execResult.output().get("updated_at")).isNotNull();
        }

        @Test
        @DisplayName("Delete-row output should contain deleted_count, deleted_at")
        void deleteRowOutputShouldBeCoherent() {
            CrudResultDto result = new CrudResultDto(
                "delete-row", true, "Deleted 2 rows",
                new CrudResultDto.ResultData(null, null, null, null, null, null, null, 2, null)
            );
            when(dataSourceClient.executeCrud(any())).thenReturn(result);

            Map<String, Object> crud = Map.of(
                "where", Map.of("column", "id", "operator", "=", "value", "1")
            );
            ExecutionResult execResult = executor.execute("crud/delete-row",
                Map.of("dataSourceId", 1L, "crud", crud), "t1");

            assertThat(execResult.isSuccess()).isTrue();
            assertThat(execResult.output().get("deleted_count")).isEqualTo(2);
            assertThat(execResult.output().get("rows_affected")).isEqualTo(2);
            assertThat(execResult.output().get("deleted_at")).isNotNull();
        }

        @Test
        @DisplayName("Failed CRUD should return success=false with error info")
        void failedCrudShouldReturnFailure() {
            CrudResultDto result = new CrudResultDto("read-row", false, "No rows found", null);
            when(dataSourceClient.executeCrud(any())).thenReturn(result);

            ExecutionResult execResult = executor.execute("crud/read-row", Map.of("dataSourceId", 1L, "crud", Map.of()), "t1");

            assertThat(execResult.isSuccess()).isFalse();
            assertThat(execResult.output().get("success")).isEqualTo(false);
        }
    }

    @Nested
    @DisplayName("DataSourceId extraction")
    class DataSourceIdTests {

        @Test
        @DisplayName("Should fail when dataSourceId is missing")
        void shouldFailWhenDataSourceIdMissing() {
            ExecutionResult execResult = executor.execute("crud/read-row", Map.of("crud", Map.of()), "t1");

            assertThat(execResult.isSuccess()).isFalse();
            assertThat(execResult.output().get("error").toString()).contains("dataSourceId is required");
        }

        @Test
        @DisplayName("Should accept dataSourceId as String")
        void shouldAcceptDataSourceIdAsString() {
            CrudResultDto result = new CrudResultDto(
                "read-row", true, "Read 0 rows",
                new CrudResultDto.ResultData(List.of(), 0, false, 0, null, null, null, null, null)
            );
            when(dataSourceClient.executeCrud(any())).thenReturn(result);

            ExecutionResult execResult = executor.execute("crud/read-row",
                Map.of("dataSourceId", "42", "crud", Map.of()), "t1");

            assertThat(execResult.isSuccess()).isTrue();
        }
    }
}

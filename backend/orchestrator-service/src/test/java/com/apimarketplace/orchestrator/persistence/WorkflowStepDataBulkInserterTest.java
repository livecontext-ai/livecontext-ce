package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Plan v4 §14 - WorkflowStepDataBulkInserter")
class WorkflowStepDataBulkInserterTest {

    @Mock NamedParameterJdbcTemplate mockJdbc;

    SimpleMeterRegistry meterRegistry;
    ObjectMapper jsonMapper;
    WorkflowStepDataBulkInserter bulkOn;
    WorkflowStepDataBulkInserter bulkOff;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        jsonMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        bulkOn = new WorkflowStepDataBulkInserter(mockJdbc, jsonMapper, meterRegistry, true);
        bulkOff = new WorkflowStepDataBulkInserter(mockJdbc, jsonMapper, meterRegistry, false);
    }

    private WorkflowStepDataEntity newStepRow(int itemIndex) {
        WorkflowStepDataEntity e = new WorkflowStepDataEntity();
        e.setWorkflowRunId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        e.setRunId("run-1");
        e.setStepAlias("mcp:fanout-step");
        e.setToolId("test-tool");
        e.setStatus("PENDING");
        e.setTenantId("tenant-a");
        e.setEpoch(1);
        e.setSpawn(0);
        e.setIteration(0);
        e.setItemIndex(itemIndex);
        e.setItemId("item-" + itemIndex);
        e.setTriggerId("trigger:webhook");
        e.setStartTime(Instant.parse("2026-05-11T10:00:00Z"));
        return e;
    }

    @Nested
    @DisplayName("Feature flag")
    class FeatureFlagTests {

        @Test
        @DisplayName("Bulk OFF → IllegalStateException, no DB call")
        void offThrows() {
            assertThatThrownBy(() -> bulkOff.saveBatch(List.of(newStepRow(0))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("step-data-bulk-insert is OFF");
            verify(mockJdbc, never()).batchUpdate(anyString(), any(SqlParameterSource[].class));
        }

        @Test
        @DisplayName("isBulkEnabled accessor reflects construction-time flag")
        void accessorReflectsFlag() {
            assertThat(bulkOn.isBulkEnabled()).isTrue();
            assertThat(bulkOff.isBulkEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Empty + non-empty batch")
    class BatchBehavior {

        @Test
        @DisplayName("Empty list → no-op, increments batch_skipped_count")
        void emptyBatchNoOp() {
            int rows = bulkOn.saveBatch(List.of());

            assertThat(rows).isZero();
            verify(mockJdbc, never()).batchUpdate(anyString(), any(SqlParameterSource[].class));
            assertThat(meterRegistry.counter("orchestrator.step_data.batch_skipped_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Single-row batch → batchUpdate called, rows_inserted=1")
        void singleRowInserts() {
            when(mockJdbc.batchUpdate(anyString(), any(SqlParameterSource[].class)))
                    .thenReturn(new int[]{1});

            int rows = bulkOn.saveBatch(List.of(newStepRow(0)));

            assertThat(rows).isEqualTo(1);
            assertThat(meterRegistry.counter("orchestrator.step_data.rows_inserted_count").count())
                    .isEqualTo(1.0);
            assertThat(meterRegistry.counter("orchestrator.step_data.batch_ok_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Multi-row batch with idempotent dups: ON CONFLICT DO NOTHING returns 0 for dups")
        void idempotentDupSkip() {
            // 3 rows, 1 was a pre-existing dup → 2 actual inserts
            when(mockJdbc.batchUpdate(anyString(), any(SqlParameterSource[].class)))
                    .thenReturn(new int[]{1, 0, 1});

            int rows = bulkOn.saveBatch(List.of(newStepRow(0), newStepRow(1), newStepRow(2)));

            assertThat(rows).isEqualTo(2);
            assertThat(meterRegistry.counter("orchestrator.step_data.rows_inserted_count").count())
                    .isEqualTo(2.0);
        }
    }

    @Nested
    @DisplayName("SQL composition + ON CONFLICT constraint name")
    class SqlComposition {

        @Test
        @DisplayName("INSERT SQL uses ON CONFLICT ON CONSTRAINT idx_workflow_step_data_unique_v6 (audit C M2 form)")
        void usesConstraintNameForm() {
            when(mockJdbc.batchUpdate(anyString(), any(SqlParameterSource[].class)))
                    .thenReturn(new int[]{1});

            bulkOn.saveBatch(List.of(newStepRow(0)));

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(mockJdbc).batchUpdate(sql.capture(), any(SqlParameterSource[].class));
            assertThat(sql.getValue())
                    .contains("ON CONFLICT ON CONSTRAINT idx_workflow_step_data_unique_v6 DO NOTHING")
                    .contains("INSERT INTO orchestrator.workflow_step_data")
                    .contains("CAST(:input_data AS jsonb)")    // JSONB casts
                    .contains("CAST(:metadata AS jsonb)")
                    .contains("CAST(:merge_received_branches AS jsonb)")
                    .contains("CAST(:merge_skipped_branches AS jsonb)");
        }

        @Test
        @DisplayName("toParams binds all 8 unique-constraint columns")
        void paramsBindUniqueColumns() {
            WorkflowStepDataEntity e = newStepRow(0);
            var p = bulkOn.toParams(e);

            assertThat(p.getValues())
                    .containsKey("workflow_run_id")
                    .containsKey("step_alias")
                    .containsKey("trigger_id")
                    .containsKey("iteration")
                    .containsKey("item_index")
                    .containsKey("epoch")
                    .containsKey("spawn")
                    .containsKey("status");
        }

        @Test
        @DisplayName("Empty JSONB Map serializes as NULL (not empty {})")
        void emptyJsonbSerializesNull() {
            WorkflowStepDataEntity e = newStepRow(0);
            e.setInputData(new HashMap<>());     // empty map → SQL NULL
            e.setMetadata(Map.of("key", "val")); // non-empty → JSON string

            var p = bulkOn.toParams(e);

            assertThat(p.getValue("input_data")).isNull();
            assertThat(p.getValue("metadata")).isEqualTo("{\"key\":\"val\"}");
        }
    }

    @Nested
    @DisplayName("Error handling - caller falls back to per-row save")
    class ErrorHandling {

        @Test
        @DisplayName("batchUpdate throws → batch_error_count incremented, exception propagates")
        void dbErrorPropagates() {
            when(mockJdbc.batchUpdate(anyString(), any(SqlParameterSource[].class)))
                    .thenThrow(new RuntimeException("connection refused"));

            assertThatThrownBy(() -> bulkOn.saveBatch(List.of(newStepRow(0))))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("connection refused");
            assertThat(meterRegistry.counter("orchestrator.step_data.batch_error_count").count())
                    .isEqualTo(1.0);
            // Counter for OK not incremented
            assertThat(meterRegistry.counter("orchestrator.step_data.batch_ok_count").count())
                    .isZero();
        }
    }
}

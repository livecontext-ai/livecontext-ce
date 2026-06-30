package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Plan v4 §14 phase 2q - StepDataNativeRepository.insertBatchIgnoringDuplicates")
class StepDataNativeRepositoryBatchTest {

    @Mock JdbcTemplate jdbcTemplate;

    private ObjectMapper objectMapper;
    private StepDataNativeRepository repo;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        repo = new StepDataNativeRepository(jdbcTemplate, objectMapper);
    }

    private WorkflowStepDataEntity newRow(int itemIndex) {
        WorkflowStepDataEntity e = new WorkflowStepDataEntity();
        e.setWorkflowRunId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        e.setRunId("run-1");
        e.setStepAlias("mcp:fanout");
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

    @Test
    @DisplayName("Empty list → 0, no DB call")
    void emptyListNoOp() {
        int rows = repo.insertBatchIgnoringDuplicates(List.of());

        assertThat(rows).isZero();
        verify(jdbcTemplate, never()).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
    }

    @Test
    @DisplayName("3-row batch where all inserted → returns 3")
    void allInserted() {
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1, 1, 1});

        int rows = repo.insertBatchIgnoringDuplicates(
                List.of(newRow(0), newRow(1), newRow(2)));

        assertThat(rows).isEqualTo(3);
    }

    @Test
    @DisplayName("3-row batch with 1 idempotent dup → returns 2")
    void idempotentDupReturnedZero() {
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1, 0, 1});

        int rows = repo.insertBatchIgnoringDuplicates(
                List.of(newRow(0), newRow(1), newRow(2)));

        assertThat(rows).isEqualTo(2);
    }

    @Test
    @DisplayName("Null triggerId defaulted to 'trigger:default' per single-row path")
    void nullTriggerIdDefaulted() {
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1});

        WorkflowStepDataEntity row = newRow(0);
        row.setTriggerId(null);

        repo.insertBatchIgnoringDuplicates(List.of(row));

        assertThat(row.getTriggerId()).isEqualTo("trigger:default");
    }

    @Test
    @DisplayName("BatchPreparedStatementSetter.getBatchSize matches list size")
    void batchSizeMatchesListSize() {
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1, 1, 1, 1, 1});

        repo.insertBatchIgnoringDuplicates(
                List.of(newRow(0), newRow(1), newRow(2), newRow(3), newRow(4)));

        ArgumentCaptor<BatchPreparedStatementSetter> captor =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(anyString(), captor.capture());
        assertThat(captor.getValue().getBatchSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("Uses ON CONFLICT (workflow_run_id, step_alias, trigger_id, iteration, item_index, epoch, spawn, status) DO NOTHING")
    void usesOnConflictDoNothing() {
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1});

        repo.insertBatchIgnoringDuplicates(List.of(newRow(0)));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).batchUpdate(sqlCaptor.capture(), any(BatchPreparedStatementSetter.class));
        assertThat(sqlCaptor.getValue())
                .contains("INSERT INTO workflow_step_data")
                .contains("ON CONFLICT (workflow_run_id, step_alias, trigger_id, iteration, item_index, epoch, spawn, status)")
                .contains("DO NOTHING");
    }
}

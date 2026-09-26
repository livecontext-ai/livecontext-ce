package com.apimarketplace.orchestrator.stepdata;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeType;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.stepdata.ColumnDefinition.ColumnType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The run-logs rows as the endpoints really return them: the REAL row mapper and column service,
 * only the repository mocked.
 *
 * Bug: the row {@code id} was the row's position in the current page (newest first), so epoch 21
 * read "ID 1" and the same execution changed number with every filter or page. It is now the
 * row's execution coordinates, and the database id travels as a hidden key for the grid.
 */
@DisplayName("Detailed step data - row identity end to end")
class DetailedStepDataRowIdentityTest {

    private static final String RUN_ID = "run-test";
    private static final String STEP_ALIAS = "mcp:search";
    private static final String TENANT_ID = "tenant-test";

    private WorkflowStepDataRepository repository;
    private DetailedStepDataService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowStepDataRepository.class);
        service = new DetailedStepDataService(
                repository, mock(StorageService.class), new ColumnDefinitionService(), new StepDataRowMapper());
    }

    private static WorkflowStepDataEntity row(long id, int epoch, int spawn, int iteration, int itemIndex) {
        WorkflowStepDataEntity e = new WorkflowStepDataEntity();
        e.setId(id);
        e.setRunId(RUN_ID);
        e.setStepAlias(STEP_ALIAS);
        e.setTenantId(TENANT_ID);
        e.setStatus("completed");
        e.setNodeType(NodeType.MCP);
        e.setToolId("tool-x");
        e.setEpoch(epoch);
        e.setSpawn(spawn);
        e.setIteration(iteration);
        e.setItemIndex(itemIndex);
        return e;
    }

    @Test
    @DisplayName("Epoch 21 filtered alone reads '21', not 'ID 1' (its position on the page)")
    void filteredPageShowsCoordinatesNotPosition() {
        when(repository.findDetailedByRunIdAndStepAliasAndTenantId(
                eq(RUN_ID), eq(STEP_ALIAS), eq(TENANT_ID), eq(21), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row(9021L, 21, 0, 0, 0))));

        DetailedStepDataResponse response = service.getDetailedStepData(
                RUN_ID, STEP_ALIAS, TENANT_ID, 1, 20, null, 21);

        assertThat(response.rows().get(0))
                .containsEntry("id", "21")
                .containsEntry(StepDataRowMapper.ROW_KEY_FIELD, 9021L);
    }

    @Test
    @DisplayName("Every row of a page keeps its own coordinates, whatever its position")
    void pageRowsCarryTheirCoordinates() {
        when(repository.findDetailedByRunIdAndStepAliasAndTenantId(
                eq(RUN_ID), eq(STEP_ALIAS), eq(TENANT_ID), nullable(Integer.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        row(3L, 20, 0, 2, 0), row(2L, 20, 1, 0, 0), row(1L, 19, 0, 0, 3))));

        DetailedStepDataResponse response = service.getDetailedStepData(
                RUN_ID, STEP_ALIAS, TENANT_ID, 2, 3, null, null);

        assertThat(response.rows()).extracting(r -> r.get("id")).containsExactly("20.0.2", "20.1", "19.0.0.3");
    }

    @Test
    @DisplayName("Columns lead with id + coordinates, id is text, and the hidden row key is no column")
    void columnsLeadWithCoordinatesAndHideTheRowKey() {
        when(repository.findDetailedByRunIdAndStepAliasAndTenantId(
                eq(RUN_ID), eq(STEP_ALIAS), eq(TENANT_ID), nullable(Integer.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row(1L, 3, 0, 0, 0))));

        List<ColumnDefinition> columns = service.getDetailedStepData(
                RUN_ID, STEP_ALIAS, TENANT_ID, 1, 20, null, null).columns();

        assertThat(columns).extracting(ColumnDefinition::field)
                .startsWith("id", "epoch", "spawn", "iteration")
                .doesNotContain(StepDataRowMapper.ROW_KEY_FIELD);
        // "20.0.2" is not a number: a NUMBER column would sort and format it as one.
        assertThat(columns.get(0).type()).isEqualTo(ColumnType.STRING);
    }

    @Test
    @DisplayName("The single-row endpoint uses the same identity (it used to show itemNumber or the DB id)")
    void byIdEndpointUsesTheSameIdentity() {
        WorkflowStepDataEntity entity = row(777L, 5, 0, 1, 0);
        entity.setItemNumber(42);
        when(repository.findById(777L)).thenReturn(Optional.of(entity));

        DetailedStepDataResponse response = service.getDetailedStepDataById(777L, RUN_ID, TENANT_ID);

        Map<String, Object> only = response.rows().get(0);
        assertThat(only).containsEntry("id", "5.0.1").containsEntry(StepDataRowMapper.ROW_KEY_FIELD, 777L);
    }
}

package com.apimarketplace.orchestrator.stepdata;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeType;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the status / epoch filters in
 * {@link DetailedStepDataService#getDetailedStepData}. Before the fix, the
 * inspector's WorkflowStepTable filtered status + epoch client-side via a
 * {@code rowFilter}, which meant filters only applied to the current page -
 * pagination totals and page count stayed unfiltered. The fix pushes both
 * filters server-side so totals reflect the filtered set.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DetailedStepDataService - status/epoch server-side filters")
class DetailedStepDataServiceFilterTest {

    @Mock private WorkflowStepDataRepository repository;
    @Mock private StorageService storageService;
    @Mock private ColumnDefinitionService columnDefinitionService;
    @Mock private StepDataRowMapper rowMapper;

    private DetailedStepDataService service;

    private static final String RUN_ID = "run-test";
    private static final String STEP_ALIAS = "mcp:my_step";
    private static final String TENANT_ID = "tenant-test";

    @BeforeEach
    void setUp() {
        service = new DetailedStepDataService(repository, storageService, columnDefinitionService, rowMapper);
    }

    private WorkflowStepDataEntity entity(long id, String status, int epoch) {
        WorkflowStepDataEntity e = new WorkflowStepDataEntity();
        e.setId(id);
        e.setRunId(RUN_ID);
        e.setStepAlias(STEP_ALIAS);
        e.setStatus(status);
        e.setEpoch(epoch);
        e.setNodeType(NodeType.MCP);
        e.setToolId("tool-x");
        e.setTenantId(TENANT_ID);
        return e;
    }

    private void mockRepoReturns(List<WorkflowStepDataEntity> entities) {
        when(repository.findDetailedByRunIdAndStepAliasAndTenantId(
                eq(RUN_ID), eq(STEP_ALIAS), eq(TENANT_ID), nullable(Integer.class), any(Pageable.class)))
                .thenAnswer(invocation -> pageOf(
                        filterByEpoch(entities, invocation.getArgument(3)),
                        invocation.getArgument(4)));
        when(repository.findDetailedByRunIdAndStepAliasAndTenantIdAndStatusIn(
                eq(RUN_ID), eq(STEP_ALIAS), eq(TENANT_ID), nullable(Integer.class), anyList(), any(Pageable.class)))
                .thenAnswer(invocation -> pageOf(
                        filterByEpochAndStatus(entities, invocation.getArgument(3), invocation.getArgument(4)),
                        invocation.getArgument(5)));
        // Row mapper / column derivation are exercised when we have rows; stub
        // them lightly so the service can finish building the response.
        when(rowMapper.mapToRow(any(), any(), any(Integer.class))).thenReturn(Map.of());
        when(columnDefinitionService.deriveColumnsFromRows(any())).thenReturn(List.of());
    }

    private List<WorkflowStepDataEntity> filterByEpoch(List<WorkflowStepDataEntity> entities, Integer epoch) {
        return entities.stream()
                .filter(e -> epoch == null || Objects.equals(e.getEpoch(), epoch))
                .toList();
    }

    private List<WorkflowStepDataEntity> filterByEpochAndStatus(
            List<WorkflowStepDataEntity> entities,
            Integer epoch,
            List<String> rawStatuses) {
        return filterByEpoch(entities, epoch).stream()
                .filter(e -> rawStatuses.contains(e.getStatus().toLowerCase()))
                .toList();
    }

    private Page<WorkflowStepDataEntity> pageOf(List<WorkflowStepDataEntity> entities, Pageable pageable) {
        int start = Math.min((int) pageable.getOffset(), entities.size());
        int end = Math.min(start + pageable.getPageSize(), entities.size());
        return new PageImpl<>(entities.subList(start, end), pageable, entities.size());
    }

    @Nested
    @DisplayName("status filter")
    class StatusFilter {

        @Test
        @DisplayName("Filters out non-matching rows BEFORE pagination so totalRows reflects the filtered set")
        void filtersBeforePagination() {
            // 4 entities: 2 completed, 2 failed. Filter on status=completed
            // → totalRows must be 2, NOT 4 (the unfiltered count).
            mockRepoReturns(List.of(
                    entity(1L, "completed", 0),
                    entity(2L, "failed",    0),
                    entity(3L, "completed", 0),
                    entity(4L, "failed",    0)
            ));

            DetailedStepDataResponse response = service.getDetailedStepData(
                    RUN_ID, STEP_ALIAS, TENANT_ID, 1, 20, "completed", null);

            assertThat(response.pagination().totalRows()).isEqualTo(2);
            assertThat(response.rows()).hasSize(2);
        }

        @Test
        @DisplayName("Maps canonical 'completed' to BOTH 'completed' and 'success' raw DB values")
        void completedFilterIncludesSuccessRows() {
            // Frontend sends canonical 'completed'; backend must accept rows
            // whose raw status is 'success' (legacy). Without the StepStatusFilter
            // expansion these would be silently dropped.
            mockRepoReturns(List.of(
                    entity(1L, "completed", 0),
                    entity(2L, "success",   0),
                    entity(3L, "running",   0)
            ));

            DetailedStepDataResponse response = service.getDetailedStepData(
                    RUN_ID, STEP_ALIAS, TENANT_ID, 1, 20, "completed", null);

            assertThat(response.pagination().totalRows()).isEqualTo(2);
        }

        @Test
        @DisplayName("Maps canonical 'failed' to BOTH 'failed' and 'error' raw DB values")
        void failedFilterIncludesErrorRows() {
            mockRepoReturns(List.of(
                    entity(1L, "failed",  0),
                    entity(2L, "error",   0),
                    entity(3L, "running", 0)
            ));

            DetailedStepDataResponse response = service.getDetailedStepData(
                    RUN_ID, STEP_ALIAS, TENANT_ID, 1, 20, "failed", null);

            assertThat(response.pagination().totalRows()).isEqualTo(2);
        }

        @Test
        @DisplayName("Treats null/blank status as 'no filter' - totalRows matches the unfiltered set")
        void blankStatusIsNoFilter() {
            mockRepoReturns(List.of(
                    entity(1L, "completed", 0),
                    entity(2L, "failed",    0)
            ));

            DetailedStepDataResponse blank = service.getDetailedStepData(
                    RUN_ID, STEP_ALIAS, TENANT_ID, 1, 20, "  ", null);
            DetailedStepDataResponse nullVal = service.getDetailedStepData(
                    RUN_ID, STEP_ALIAS, TENANT_ID, 1, 20, null, null);

            assertThat(blank.pagination().totalRows()).isEqualTo(2);
            assertThat(nullVal.pagination().totalRows()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("epoch filter")
    class EpochFilter {

        @Test
        @DisplayName("Restricts to a single epoch BEFORE pagination so page count reflects the filtered set")
        void filtersBeforePagination() {
            mockRepoReturns(List.of(
                    entity(1L, "completed", 0),
                    entity(2L, "completed", 1),
                    entity(3L, "completed", 1),
                    entity(4L, "completed", 2)
            ));

            DetailedStepDataResponse response = service.getDetailedStepData(
                    RUN_ID, STEP_ALIAS, TENANT_ID, 1, 20, null, 1);

            assertThat(response.pagination().totalRows()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("combined status + epoch")
    class CombinedFilters {

        @Test
        @DisplayName("Applies both filters with AND semantics")
        void appliesBothFilters() {
            mockRepoReturns(List.of(
                    entity(1L, "completed", 0),  // wrong epoch
                    entity(2L, "completed", 1),  // KEEP
                    entity(3L, "failed",    1),  // wrong status
                    entity(4L, "success",   1)   // KEEP (success ⇒ canonical 'completed')
            ));

            DetailedStepDataResponse response = service.getDetailedStepData(
                    RUN_ID, STEP_ALIAS, TENANT_ID, 1, 20, "completed", 1);

            assertThat(response.pagination().totalRows()).isEqualTo(2);
        }

        @Test
        @DisplayName("Returns empty rows + zero totalRows when no row matches both filters")
        void emptyResultPreservesNodeTypeForStableSchema() {
            mockRepoReturns(List.of(
                    entity(1L, "completed", 0),
                    entity(2L, "failed",    0)
            ));

            DetailedStepDataResponse response = service.getDetailedStepData(
                    RUN_ID, STEP_ALIAS, TENANT_ID, 1, 20, "running", null);

            assertThat(response.pagination().totalRows()).isZero();
            assertThat(response.rows()).isEmpty();
            // Even with zero filtered rows, expose the node type from the
            // unfiltered set so the column schema does not collapse and the
            // frontend can keep rendering the same headers.
            assertThat(response.nodeType()).isEqualTo(NodeType.MCP);
        }
    }
}

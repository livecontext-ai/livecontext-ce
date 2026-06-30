package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.withSettings;

@ExtendWith(MockitoExtension.class)
@DisplayName("StateDataLoader")
class StateDataLoaderTest {

    @Mock
    private WorkflowStepDataRepository stepDataRepository;

    private StateDataLoader loader;

    @BeforeEach
    void setUp() {
        loader = new StateDataLoader(stepDataRepository);
    }

    @Nested
    @DisplayName("Multi-DAG Epoch Loading")
    class MultiDagEpochLoadingTests {

        private final UUID RUN_UUID = UUID.randomUUID();

        private WorkflowRunEntity mockRun() {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            when(run.getId()).thenReturn(RUN_UUID);
            return run;
        }

        @Test
        @DisplayName("epoch=0 hits the DISTINCT ON epoch-filtered query (caps fan-out for OOM safety per 2026-05-22 hardening)")
        void epochZeroLoadsOnlyEpochZero() {
            WorkflowRunEntity run = mockRun();

            when(stepDataRepository.findByWorkflowRunIdAndEpochLatestPerAliasLightweight(RUN_UUID, 0))
                .thenReturn(List.of());

            var result = loader.loadAndPrepareStepData(run, 0, null);

            verify(stepDataRepository).findByWorkflowRunIdAndEpochLatestPerAliasLightweight(RUN_UUID, 0);
            verify(stepDataRepository, never()).findLatestPerAliasLightweight(any(UUID.class));
            assertNotNull(result);
        }

        @Test
        @DisplayName("epoch>0 hits the latest-per-alias query (multi-epoch path)")
        void epochGreaterThanZeroLoadsLatestPerAlias() {
            WorkflowRunEntity run = mockRun();

            when(stepDataRepository.findLatestPerAliasLightweight(RUN_UUID))
                .thenReturn(List.of());

            var result = loader.loadAndPrepareStepData(run, 3, null);

            verify(stepDataRepository).findLatestPerAliasLightweight(RUN_UUID);
            verify(stepDataRepository, never()).findByWorkflowRunIdAndEpochLatestPerAliasLightweight(any(UUID.class), anyInt());
            assertNotNull(result);
        }

        @Test
        @DisplayName("resetSteps metadata key forces multi-epoch path even at epoch=0")
        void resetStepsMetadataForcesMultiEpochPath() {
            WorkflowRunEntity run = mockRun();

            when(stepDataRepository.findLatestPerAliasLightweight(RUN_UUID))
                .thenReturn(List.of());

            var result = loader.loadAndPrepareStepData(run, 0, java.util.Map.of("resetSteps", true));

            verify(stepDataRepository).findLatestPerAliasLightweight(RUN_UUID);
            verify(stepDataRepository, never()).findByWorkflowRunIdAndEpochLatestPerAliasLightweight(any(UUID.class), anyInt());
            assertNotNull(result);
        }

        @Test
        @DisplayName("Multi-epoch path returns one entity per alias - no over-fetch across epochs")
        void multiEpochPathReturnsOnePerAlias() {
            // Regression guard for prod OOM 2026-05-07 12:40 UTC.
            // The previous query findByWorkflowRunIdLightweight loaded EVERY row across all
            // epochs (17 180 rows on the offending Gmail Auto-Labeler run). StepStateBuilder
            // then consumed only the last entity per alias. The driver materialised a 22 MB
            // PgResultSet × ~30 concurrent threads → heap exhaustion. The new
            // findLatestPerAliasLightweight returns at most one row per alias (DISTINCT ON
            // step_alias, ORDER BY id DESC), so the loader's group-by-alias map has the
            // same shape with ~32 rows instead of 17 180.

            WorkflowRunEntity run = mockRun();
            WorkflowStepDataEntity stepA = createStepEntity("mcp:step_a", 2, "SUCCESS");
            WorkflowStepDataEntity stepB = createStepEntity("mcp:step_b", 0, "SUCCESS");

            when(stepDataRepository.findLatestPerAliasLightweight(RUN_UUID))
                .thenReturn(List.of(stepA, stepB));

            var result = loader.loadAndPrepareStepData(run, 3, null);

            assertEquals(2, result.stepEntities().size(),
                "Loader returns exactly the rows the optimised query produced - one per alias");
            assertEquals(2, result.stepsByAlias().size());
            assertTrue(result.stepsByAlias().containsKey("mcp:step_a"));
            assertTrue(result.stepsByAlias().containsKey("mcp:step_b"));
            verify(stepDataRepository).findLatestPerAliasLightweight(RUN_UUID);
        }

        @Test
        @DisplayName("Multi-epoch result size is bounded by distinct alias count, never by raw row count")
        void multiEpochResultSizeBoundedByAliases() {
            // Invariant guard: loadAndPrepareStepData MUST NOT propagate one entity per
            // (alias × item × epoch) - only one per alias. If a future caller introduces
            // a query that returns N rows per alias, this test fails before the OOM does.
            WorkflowRunEntity run = mockRun();
            int distinctAliases = 5;
            List<WorkflowStepDataEntity> latestPerAlias = List.of(
                createStepEntity("mcp:a", 7, "SUCCESS"),
                createStepEntity("mcp:b", 7, "SUCCESS"),
                createStepEntity("mcp:c", 7, "SUCCESS"),
                createStepEntity("mcp:d", 7, "SUCCESS"),
                createStepEntity("mcp:e", 7, "SUCCESS")
            );
            when(stepDataRepository.findLatestPerAliasLightweight(RUN_UUID))
                .thenReturn(latestPerAlias);

            var result = loader.loadAndPrepareStepData(run, 7, null);

            assertEquals(distinctAliases, result.stepEntities().size(),
                "Multi-epoch path must surface at most one row per distinct alias");
            assertEquals(distinctAliases, result.stepsByAlias().size());
            result.stepsByAlias().values().forEach(entities ->
                assertEquals(1, entities.size(),
                    "Each alias bucket must hold a single representative entity"));
        }

        private WorkflowStepDataEntity createStepEntity(String alias, int epoch, String status) {
            WorkflowStepDataEntity entity = mock(WorkflowStepDataEntity.class, withSettings().lenient());
            when(entity.getStepAlias()).thenReturn(alias);
            when(entity.getEpoch()).thenReturn(epoch);
            when(entity.getStatus()).thenReturn(status);
            return entity;
        }
    }
}

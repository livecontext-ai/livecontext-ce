package com.apimarketplace.orchestrator.domain;

import com.apimarketplace.orchestrator.utils.ExecutionConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for execution coordinate fields (epoch, spawn, iteration, itemIndex)
 * of {@link WorkflowStepDataEntity}.
 */
@DisplayName("WorkflowStepDataEntity - Execution Coordinates")
class WorkflowStepDataEntityCoordinatesTest {

    private static final UUID WORKFLOW_RUN_ID = UUID.randomUUID();
    private static final String RUN_ID = "run-001";
    private static final String STEP_ALIAS = "My Step";
    private static final String TOOL_ID = "mcp:my_step";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String TENANT_ID = "tenant-abc";
    private static final Instant START_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END_TIME = Instant.parse("2026-01-01T00:01:00Z");

    /**
     * Helper to create an entity via the full constructor.
     */
    private WorkflowStepDataEntity createEntity(Integer epoch, Integer spawn,
                                                  Integer iteration, Integer itemIndex) {
        return new WorkflowStepDataEntity(
                WORKFLOW_RUN_ID, RUN_ID, STEP_ALIAS, TOOL_ID,
                Map.of("key", "value"), null, 200,
                STATUS_COMPLETED, START_TIME, END_TIME,
                null, TENANT_ID,
                epoch, spawn, iteration, itemIndex,
                null
        );
    }

    // =========================================================================
    // 1. Default values
    // =========================================================================

    @Nested
    @DisplayName("Default values")
    class DefaultValuesTests {

        @Test
        @DisplayName("No-arg constructor should initialize all coordinates to 0")
        void noArgConstructorShouldInitializeCoordinatesToZero() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();

            assertThat(entity.getEpoch()).isEqualTo(0);
            assertThat(entity.getSpawn()).isEqualTo(0);
            assertThat(entity.getIteration()).isEqualTo(0);
            assertThat(entity.getItemIndex()).isEqualTo(0);
        }

        @Test
        @DisplayName("No-arg constructor should leave non-coordinate fields as null")
        void noArgConstructorShouldLeaveNonCoordinateFieldsNull() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();

            assertThat(entity.getId()).isNull();
            assertThat(entity.getWorkflowRunId()).isNull();
            assertThat(entity.getRunId()).isNull();
            assertThat(entity.getStepAlias()).isNull();
            assertThat(entity.getStatus()).isNull();
        }
    }

    // =========================================================================
    // 2. Constructor with explicit values
    // =========================================================================

    @Nested
    @DisplayName("Constructor with explicit coordinate values")
    class ExplicitConstructorTests {

        @Test
        @DisplayName("Should set all coordinate fields from constructor arguments")
        void shouldSetAllCoordinateFields() {
            WorkflowStepDataEntity entity = createEntity(3, 5, 7, 42);

            assertThat(entity.getEpoch()).isEqualTo(3);
            assertThat(entity.getSpawn()).isEqualTo(5);
            assertThat(entity.getIteration()).isEqualTo(7);
            assertThat(entity.getItemIndex()).isEqualTo(42);
        }

        @Test
        @DisplayName("Should set all coordinate fields to zero explicitly")
        void shouldSetAllCoordinateFieldsToZeroExplicitly() {
            WorkflowStepDataEntity entity = createEntity(0, 0, 0, 0);

            assertThat(entity.getEpoch()).isEqualTo(0);
            assertThat(entity.getSpawn()).isEqualTo(0);
            assertThat(entity.getIteration()).isEqualTo(0);
            assertThat(entity.getItemIndex()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should preserve non-coordinate constructor fields alongside coordinates")
        void shouldPreserveNonCoordinateFields() {
            WorkflowStepDataEntity entity = createEntity(1, 2, 3, 4);

            assertThat(entity.getWorkflowRunId()).isEqualTo(WORKFLOW_RUN_ID);
            assertThat(entity.getRunId()).isEqualTo(RUN_ID);
            assertThat(entity.getStepAlias()).isEqualTo(STEP_ALIAS);
            assertThat(entity.getToolId()).isEqualTo(TOOL_ID);
            assertThat(entity.getStatus()).isEqualTo(STATUS_COMPLETED);
            assertThat(entity.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(entity.getHttpStatus()).isEqualTo(200);
        }
    }

    // =========================================================================
    // 3. Constructor with null coordinates
    // =========================================================================

    @Nested
    @DisplayName("Constructor with null coordinates")
    class NullCoordinateConstructorTests {

        @Test
        @DisplayName("All null coordinates should default to 0")
        void allNullCoordinatesShouldDefaultToZero() {
            WorkflowStepDataEntity entity = createEntity(null, null, null, null);

            assertThat(entity.getEpoch()).isEqualTo(0);
            assertThat(entity.getSpawn()).isEqualTo(0);
            assertThat(entity.getIteration()).isEqualTo(0);
            assertThat(entity.getItemIndex()).isEqualTo(0);
        }

        @Test
        @DisplayName("Null epoch should default to 0, others kept")
        void nullEpochShouldDefaultToZero() {
            WorkflowStepDataEntity entity = createEntity(null, 5, 7, 9);

            assertThat(entity.getEpoch()).isEqualTo(0);
            assertThat(entity.getSpawn()).isEqualTo(5);
            assertThat(entity.getIteration()).isEqualTo(7);
            assertThat(entity.getItemIndex()).isEqualTo(9);
        }

        @Test
        @DisplayName("Null spawn should default to 0, others kept")
        void nullSpawnShouldDefaultToZero() {
            WorkflowStepDataEntity entity = createEntity(3, null, 7, 9);

            assertThat(entity.getEpoch()).isEqualTo(3);
            assertThat(entity.getSpawn()).isEqualTo(0);
            assertThat(entity.getIteration()).isEqualTo(7);
            assertThat(entity.getItemIndex()).isEqualTo(9);
        }

        @Test
        @DisplayName("Null iteration should default to 0, others kept")
        void nullIterationShouldDefaultToZero() {
            WorkflowStepDataEntity entity = createEntity(3, 5, null, 9);

            assertThat(entity.getEpoch()).isEqualTo(3);
            assertThat(entity.getSpawn()).isEqualTo(5);
            assertThat(entity.getIteration()).isEqualTo(0);
            assertThat(entity.getItemIndex()).isEqualTo(9);
        }

        @Test
        @DisplayName("Null itemIndex should default to 0, others kept")
        void nullItemIndexShouldDefaultToZero() {
            WorkflowStepDataEntity entity = createEntity(3, 5, 7, null);

            assertThat(entity.getEpoch()).isEqualTo(3);
            assertThat(entity.getSpawn()).isEqualTo(5);
            assertThat(entity.getIteration()).isEqualTo(7);
            assertThat(entity.getItemIndex()).isEqualTo(0);
        }
    }

    // =========================================================================
    // 4. Setter/getter round-trip
    // =========================================================================

    @Nested
    @DisplayName("Setter/getter round-trip")
    class SetterGetterRoundTripTests {

        @Test
        @DisplayName("Epoch setter and getter round-trip")
        void epochSetterGetterRoundTrip() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();

            entity.setEpoch(42);
            assertThat(entity.getEpoch()).isEqualTo(42);

            entity.setEpoch(0);
            assertThat(entity.getEpoch()).isEqualTo(0);

            entity.setEpoch(999);
            assertThat(entity.getEpoch()).isEqualTo(999);
        }

        @Test
        @DisplayName("Spawn setter and getter round-trip")
        void spawnSetterGetterRoundTrip() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();

            entity.setSpawn(7);
            assertThat(entity.getSpawn()).isEqualTo(7);

            entity.setSpawn(0);
            assertThat(entity.getSpawn()).isEqualTo(0);

            entity.setSpawn(500);
            assertThat(entity.getSpawn()).isEqualTo(500);
        }

        @Test
        @DisplayName("Iteration setter and getter round-trip")
        void iterationSetterGetterRoundTrip() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();

            entity.setIteration(15);
            assertThat(entity.getIteration()).isEqualTo(15);

            entity.setIteration(0);
            assertThat(entity.getIteration()).isEqualTo(0);

            entity.setIteration(100);
            assertThat(entity.getIteration()).isEqualTo(100);
        }

        @Test
        @DisplayName("ItemIndex setter and getter round-trip")
        void itemIndexSetterGetterRoundTrip() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();

            entity.setItemIndex(2003);
            assertThat(entity.getItemIndex()).isEqualTo(2003);

            entity.setItemIndex(0);
            assertThat(entity.getItemIndex()).isEqualTo(0);

            entity.setItemIndex(999999);
            assertThat(entity.getItemIndex()).isEqualTo(999999);
        }

        @Test
        @DisplayName("Setter should allow null values (no null-coalescing like constructor)")
        void setterShouldAllowNullValues() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();

            entity.setEpoch(null);
            assertThat(entity.getEpoch()).isNull();

            entity.setSpawn(null);
            assertThat(entity.getSpawn()).isNull();

            entity.setIteration(null);
            assertThat(entity.getIteration()).isNull();

            entity.setItemIndex(null);
            assertThat(entity.getItemIndex()).isNull();
        }
    }

    // =========================================================================
    // 5. Split item index calculation
    // =========================================================================

    @Nested
    @DisplayName("Split item index calculation")
    class SplitItemIndexCalculationTests {

        private static final int MULTIPLIER = ExecutionConstants.SPLIT_ITEM_INDEX_MULTIPLIER;

        @Test
        @DisplayName("Multiplier constant should be 1000")
        void multiplierShouldBe1000() {
            assertThat(MULTIPLIER).isEqualTo(1000);
        }

        @Test
        @DisplayName("Basic: parent=0, local=0 yields childIndex=0")
        void parentZeroLocalZeroYieldsZero() {
            int parentIndex = 0;
            int localIndex = 0;
            int childIndex = parentIndex * MULTIPLIER + localIndex;

            assertThat(childIndex).isEqualTo(0);

            // Verify entity can hold this value
            WorkflowStepDataEntity entity = createEntity(0, 0, 0, childIndex);
            assertThat(entity.getItemIndex()).isEqualTo(0);
        }

        @Test
        @DisplayName("Basic: parent=2, local=3 yields childIndex=2003")
        void parentTwoLocalThreeYields2003() {
            int parentIndex = 2;
            int localIndex = 3;
            int childIndex = parentIndex * MULTIPLIER + localIndex;

            assertThat(childIndex).isEqualTo(2003);

            WorkflowStepDataEntity entity = createEntity(0, 0, 0, childIndex);
            assertThat(entity.getItemIndex()).isEqualTo(2003);
        }

        @Test
        @DisplayName("Nested 3-level: parent=2003, local=5 yields childIndex=2003005")
        void threeLevel_parent2003_local5_yields2003005() {
            // Level 1: parent=2, local=3 -> 2003
            int level1 = 2 * MULTIPLIER + 3;
            assertThat(level1).isEqualTo(2003);

            // Level 2: parent=2003, local=5 -> 2003005
            int level2 = level1 * MULTIPLIER + 5;
            assertThat(level2).isEqualTo(2_003_005);

            WorkflowStepDataEntity entity = createEntity(0, 0, 0, level2);
            assertThat(entity.getItemIndex()).isEqualTo(2_003_005);
        }

        @Test
        @DisplayName("Max single level: parent=0, local=999 yields childIndex=999")
        void maxSingleLevel_parentZero_local999_yields999() {
            int childIndex = 0 * MULTIPLIER + 999;

            assertThat(childIndex).isEqualTo(999);

            WorkflowStepDataEntity entity = createEntity(0, 0, 0, childIndex);
            assertThat(entity.getItemIndex()).isEqualTo(999);
        }

        @Test
        @DisplayName("Boundary: parent=1, local=0 yields childIndex=1000")
        void boundary_parentOne_localZero_yields1000() {
            int childIndex = 1 * MULTIPLIER + 0;

            assertThat(childIndex).isEqualTo(1000);

            WorkflowStepDataEntity entity = createEntity(0, 0, 0, childIndex);
            assertThat(entity.getItemIndex()).isEqualTo(1000);
        }

        @Test
        @DisplayName("Multiple children from same parent should have sequential item indices")
        void multipleChildrenFromSameParentShouldBeSequential() {
            int parentIndex = 5;
            List<Integer> childIndices = new ArrayList<>();

            for (int localIndex = 0; localIndex < 10; localIndex++) {
                int childIndex = parentIndex * MULTIPLIER + localIndex;
                childIndices.add(childIndex);
            }

            assertThat(childIndices).containsExactly(
                    5000, 5001, 5002, 5003, 5004, 5005, 5006, 5007, 5008, 5009
            );
        }
    }

    // =========================================================================
    // 6. Reverse split item index extraction
    // =========================================================================

    @Nested
    @DisplayName("Reverse split item index extraction")
    class ReverseSplitItemIndexExtractionTests {

        private static final int MULTIPLIER = ExecutionConstants.SPLIT_ITEM_INDEX_MULTIPLIER;

        @Test
        @DisplayName("childIndex=2003 extracts parent=2, local=3")
        void childIndex2003_extractsParent2Local3() {
            int childIndex = 2003;
            int parentIndex = childIndex / MULTIPLIER;
            int localIndex = childIndex % MULTIPLIER;

            assertThat(parentIndex).isEqualTo(2);
            assertThat(localIndex).isEqualTo(3);
        }

        @Test
        @DisplayName("childIndex=0 extracts parent=0, local=0")
        void childIndexZero_extractsParentZeroLocalZero() {
            int childIndex = 0;
            int parentIndex = childIndex / MULTIPLIER;
            int localIndex = childIndex % MULTIPLIER;

            assertThat(parentIndex).isEqualTo(0);
            assertThat(localIndex).isEqualTo(0);
        }

        @Test
        @DisplayName("childIndex=999 extracts parent=0, local=999")
        void childIndex999_extractsParentZeroLocal999() {
            int childIndex = 999;
            int parentIndex = childIndex / MULTIPLIER;
            int localIndex = childIndex % MULTIPLIER;

            assertThat(parentIndex).isEqualTo(0);
            assertThat(localIndex).isEqualTo(999);
        }

        @Test
        @DisplayName("Round-trip: compute child then extract parent and local")
        void roundTrip_computeChildThenExtract() {
            int originalParent = 7;
            int originalLocal = 42;
            int childIndex = originalParent * MULTIPLIER + originalLocal;

            int extractedParent = childIndex / MULTIPLIER;
            int extractedLocal = childIndex % MULTIPLIER;

            assertThat(extractedParent).isEqualTo(originalParent);
            assertThat(extractedLocal).isEqualTo(originalLocal);
        }

        @Test
        @DisplayName("Three-level reverse: childIndex=2003005 extracts immediate parent=2003, local=5")
        void threeLevelReverse_childIndex2003005() {
            int childIndex = 2_003_005;
            int immediateParent = childIndex / MULTIPLIER;
            int localIndex = childIndex % MULTIPLIER;

            assertThat(immediateParent).isEqualTo(2003);
            assertThat(localIndex).isEqualTo(5);

            // Further decompose immediate parent
            int grandparent = immediateParent / MULTIPLIER;
            int parentLocal = immediateParent % MULTIPLIER;

            assertThat(grandparent).isEqualTo(2);
            assertThat(parentLocal).isEqualTo(3);
        }
    }

    // =========================================================================
    // 7. Unique constraint combinations
    // =========================================================================

    @Nested
    @DisplayName("Unique constraint combinations")
    class UniqueConstraintCombinationsTests {

        /**
         * Represents the columns in the unique constraint:
         * (workflow_run_id, step_alias, iteration, item_index, epoch, spawn, status)
         */
        private record UniqueKey(UUID workflowRunId, String stepAlias,
                                 int iteration, int itemIndex,
                                 int epoch, int spawn, String status) {
        }

        private UniqueKey keyOf(WorkflowStepDataEntity entity) {
            return new UniqueKey(
                    entity.getWorkflowRunId(), entity.getStepAlias(),
                    entity.getIteration(), entity.getItemIndex(),
                    entity.getEpoch(), entity.getSpawn(), entity.getStatus()
            );
        }

        @Test
        @DisplayName("Same step, same coordinates, different status should produce distinct keys")
        void sameStepSameCoordinatesDifferentStatusShouldBeDistinct() {
            WorkflowStepDataEntity running = new WorkflowStepDataEntity(
                    WORKFLOW_RUN_ID, RUN_ID, STEP_ALIAS, TOOL_ID,
                    null, null, null, STATUS_RUNNING,
                    START_TIME, null, null, TENANT_ID,
                    0, 0, 0, 0, null
            );
            WorkflowStepDataEntity completed = new WorkflowStepDataEntity(
                    WORKFLOW_RUN_ID, RUN_ID, STEP_ALIAS, TOOL_ID,
                    null, null, 200, STATUS_COMPLETED,
                    START_TIME, END_TIME, null, TENANT_ID,
                    0, 0, 0, 0, null
            );

            assertThat(keyOf(running)).isNotEqualTo(keyOf(completed));
        }

        @Test
        @DisplayName("Same step, different epoch, same others should produce distinct keys")
        void sameStepDifferentEpochShouldBeDistinct() {
            WorkflowStepDataEntity epoch0 = createEntity(0, 0, 0, 0);
            WorkflowStepDataEntity epoch1 = createEntity(1, 0, 0, 0);

            assertThat(keyOf(epoch0)).isNotEqualTo(keyOf(epoch1));
        }

        @Test
        @DisplayName("Same step, different spawn, same others should produce distinct keys")
        void sameStepDifferentSpawnShouldBeDistinct() {
            WorkflowStepDataEntity spawn0 = createEntity(0, 0, 0, 0);
            WorkflowStepDataEntity spawn1 = createEntity(0, 1, 0, 0);

            assertThat(keyOf(spawn0)).isNotEqualTo(keyOf(spawn1));
        }

        @Test
        @DisplayName("Same step, different iteration, same others should produce distinct keys")
        void sameStepDifferentIterationShouldBeDistinct() {
            WorkflowStepDataEntity iter0 = createEntity(0, 0, 0, 0);
            WorkflowStepDataEntity iter1 = createEntity(0, 0, 1, 0);

            assertThat(keyOf(iter0)).isNotEqualTo(keyOf(iter1));
        }

        @Test
        @DisplayName("Same step, different itemIndex, same others should produce distinct keys")
        void sameStepDifferentItemIndexShouldBeDistinct() {
            WorkflowStepDataEntity item0 = createEntity(0, 0, 0, 0);
            WorkflowStepDataEntity item1 = createEntity(0, 0, 0, 1);

            assertThat(keyOf(item0)).isNotEqualTo(keyOf(item1));
        }

        @Test
        @DisplayName("Identical coordinates and status should produce equal keys")
        void identicalCoordinatesAndStatusShouldBeEqual() {
            WorkflowStepDataEntity a = createEntity(2, 3, 4, 5);
            WorkflowStepDataEntity b = createEntity(2, 3, 4, 5);

            assertThat(keyOf(a)).isEqualTo(keyOf(b));
        }
    }

    // =========================================================================
    // 8. Edge cases
    // =========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Epoch set to Integer.MAX_VALUE")
        void epochMaxValue() {
            WorkflowStepDataEntity entity = createEntity(Integer.MAX_VALUE, 0, 0, 0);

            assertThat(entity.getEpoch()).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("Spawn set to Integer.MAX_VALUE")
        void spawnMaxValue() {
            WorkflowStepDataEntity entity = createEntity(0, Integer.MAX_VALUE, 0, 0);

            assertThat(entity.getSpawn()).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("ItemIndex with deep nesting: parent=999, local=999 yields 999999")
        void itemIndexDeepNesting() {
            int parentIndex = 999;
            int localIndex = 999;
            int childIndex = parentIndex * ExecutionConstants.SPLIT_ITEM_INDEX_MULTIPLIER + localIndex;

            assertThat(childIndex).isEqualTo(999_999);

            WorkflowStepDataEntity entity = createEntity(0, 0, 0, childIndex);
            assertThat(entity.getItemIndex()).isEqualTo(999_999);
        }

        @Test
        @DisplayName("Iteration=0 represents first iteration")
        void iterationZeroIsFirstIteration() {
            WorkflowStepDataEntity entity = createEntity(0, 0, 0, 0);

            assertThat(entity.getIteration()).isEqualTo(0);
        }

        @Test
        @DisplayName("Iteration=null should default to 0 (same as first iteration)")
        void iterationNullShouldDefaultToZero() {
            WorkflowStepDataEntity entity = createEntity(0, 0, null, 0);

            assertThat(entity.getIteration()).isEqualTo(0);
        }

        @Test
        @DisplayName("Negative epoch value set via setter")
        void negativeEpochViaSetter() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            entity.setEpoch(-1);

            assertThat(entity.getEpoch()).isEqualTo(-1);
        }

        @Test
        @DisplayName("Negative spawn value set via setter")
        void negativeSpawnViaSetter() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            entity.setSpawn(-5);

            assertThat(entity.getSpawn()).isEqualTo(-5);
        }

        @Test
        @DisplayName("Negative iteration value set via setter")
        void negativeIterationViaSetter() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            entity.setIteration(-10);

            assertThat(entity.getIteration()).isEqualTo(-10);
        }

        @Test
        @DisplayName("Negative itemIndex value set via setter")
        void negativeItemIndexViaSetter() {
            WorkflowStepDataEntity entity = new WorkflowStepDataEntity();
            entity.setItemIndex(-100);

            assertThat(entity.getItemIndex()).isEqualTo(-100);
        }

        @Test
        @DisplayName("All coordinates at Integer.MAX_VALUE simultaneously")
        void allCoordinatesAtMaxValue() {
            WorkflowStepDataEntity entity = createEntity(
                    Integer.MAX_VALUE, Integer.MAX_VALUE,
                    Integer.MAX_VALUE, Integer.MAX_VALUE
            );

            assertThat(entity.getEpoch()).isEqualTo(Integer.MAX_VALUE);
            assertThat(entity.getSpawn()).isEqualTo(Integer.MAX_VALUE);
            assertThat(entity.getIteration()).isEqualTo(Integer.MAX_VALUE);
            assertThat(entity.getItemIndex()).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("Overwriting coordinate via setter after constructor")
        void overwriteCoordinateViaSetterAfterConstructor() {
            WorkflowStepDataEntity entity = createEntity(1, 2, 3, 4);

            entity.setEpoch(10);
            entity.setSpawn(20);
            entity.setIteration(30);
            entity.setItemIndex(40);

            assertThat(entity.getEpoch()).isEqualTo(10);
            assertThat(entity.getSpawn()).isEqualTo(20);
            assertThat(entity.getIteration()).isEqualTo(30);
            assertThat(entity.getItemIndex()).isEqualTo(40);
        }
    }

    // =========================================================================
    // 9. Stress test: 1000 entities with various coordinate combinations
    // =========================================================================

    @Nested
    @DisplayName("Stress test - 1000 entities with various coordinate combinations")
    class StressTests {

        @Test
        @DisplayName("All 1000 coordinate combinations should be distinct")
        void allCoordinateCombinationsShouldBeDistinct() {
            List<WorkflowStepDataEntity> entities = new ArrayList<>(1000);
            Set<String> coordinateKeys = new HashSet<>(1000);

            int count = 0;
            // Generate 1000 unique combinations by varying epoch, spawn, iteration, itemIndex
            // 10 epochs x 10 spawns x 10 iterations x 10 itemIndices = 10000 possible, pick first 1000
            outer:
            for (int epoch = 0; epoch < 10; epoch++) {
                for (int spawn = 0; spawn < 10; spawn++) {
                    for (int iteration = 0; iteration < 10; iteration++) {
                        for (int itemIndex = 0; itemIndex < 10; itemIndex++) {
                            if (count >= 1000) break outer;

                            WorkflowStepDataEntity entity = createEntity(epoch, spawn, iteration, itemIndex);
                            entities.add(entity);

                            String key = epoch + ":" + spawn + ":" + iteration + ":" + itemIndex;
                            coordinateKeys.add(key);
                            count++;
                        }
                    }
                }
            }

            assertThat(entities).hasSize(1000);
            assertThat(coordinateKeys).hasSize(1000);
        }

        @Test
        @DisplayName("All entities should have correct default-safe coordinates")
        void allEntitiesShouldHaveCorrectCoordinates() {
            List<WorkflowStepDataEntity> entities = new ArrayList<>(1000);

            for (int i = 0; i < 1000; i++) {
                int epoch = i / 100;
                int spawn = (i % 100) / 10;
                int iteration = i % 10;
                int itemIndex = i;

                WorkflowStepDataEntity entity = createEntity(epoch, spawn, iteration, itemIndex);
                entities.add(entity);
            }

            for (int i = 0; i < 1000; i++) {
                WorkflowStepDataEntity entity = entities.get(i);
                int expectedEpoch = i / 100;
                int expectedSpawn = (i % 100) / 10;
                int expectedIteration = i % 10;
                int expectedItemIndex = i;

                assertThat(entity.getEpoch())
                        .as("Entity %d epoch", i)
                        .isEqualTo(expectedEpoch);
                assertThat(entity.getSpawn())
                        .as("Entity %d spawn", i)
                        .isEqualTo(expectedSpawn);
                assertThat(entity.getIteration())
                        .as("Entity %d iteration", i)
                        .isEqualTo(expectedIteration);
                assertThat(entity.getItemIndex())
                        .as("Entity %d itemIndex", i)
                        .isEqualTo(expectedItemIndex);
            }
        }

        @Test
        @DisplayName("Entities created with null coordinates should all default to 0")
        void entitiesWithNullCoordinatesShouldAllDefaultToZero() {
            for (int i = 0; i < 100; i++) {
                WorkflowStepDataEntity entity = createEntity(null, null, null, null);

                assertThat(entity.getEpoch())
                        .as("Entity %d epoch", i)
                        .isEqualTo(0);
                assertThat(entity.getSpawn())
                        .as("Entity %d spawn", i)
                        .isEqualTo(0);
                assertThat(entity.getIteration())
                        .as("Entity %d iteration", i)
                        .isEqualTo(0);
                assertThat(entity.getItemIndex())
                        .as("Entity %d itemIndex", i)
                        .isEqualTo(0);
            }
        }
    }
}

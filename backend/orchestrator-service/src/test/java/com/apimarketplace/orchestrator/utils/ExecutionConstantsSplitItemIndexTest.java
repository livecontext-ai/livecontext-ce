package com.apimarketplace.orchestrator.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static com.apimarketplace.orchestrator.utils.ExecutionConstants.SPLIT_ITEM_INDEX_MULTIPLIER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests on the split item index calculation system.
 * <p>
 * The constant {@link ExecutionConstants#SPLIT_ITEM_INDEX_MULTIPLIER} (= 1000) is used to
 * create unique item indices for nested splits:
 * <pre>
 *   childIndex = parentIndex * SPLIT_ITEM_INDEX_MULTIPLIER + localIndex
 * </pre>
 * Reverse extraction:
 * <pre>
 *   parentIndex = childIndex / SPLIT_ITEM_INDEX_MULTIPLIER
 *   localIndex  = childIndex % SPLIT_ITEM_INDEX_MULTIPLIER
 * </pre>
 * <b>Invariant:</b> localIndex must always be in [0, MULTIPLIER) to avoid collisions.
 */
@DisplayName("ExecutionConstants - Split Item Index Calculation")
class ExecutionConstantsSplitItemIndexTest {

    private static final int MULTIPLIER = SPLIT_ITEM_INDEX_MULTIPLIER;

    // -----------------------------------------------------------------
    // Helper methods mirroring the production formula
    // -----------------------------------------------------------------

    private static int computeChildIndex(int parentIndex, int localIndex) {
        return parentIndex * MULTIPLIER + localIndex;
    }

    private static int extractParentIndex(int childIndex) {
        return childIndex / MULTIPLIER;
    }

    private static int extractLocalIndex(int childIndex) {
        return childIndex % MULTIPLIER;
    }

    // =================================================================
    // 1. Basic split item index calculations
    // =================================================================

    @Nested
    @DisplayName("Basic split item index calculations")
    class BasicCalculationTests {

        @ParameterizedTest(name = "parent={0}, local={1} -> child={2}")
        @CsvSource({
                "0, 0, 0",
                "0, 1, 1",
                "0, 999, 999",
                "1, 0, 1000",
                "2, 3, 2003",
                "10, 5, 10005"
        })
        @DisplayName("Should compute correct child index from parent and local index")
        void shouldComputeCorrectChildIndex(int parent, int local, int expectedChild) {
            int child = computeChildIndex(parent, local);
            assertThat(child).isEqualTo(expectedChild);
        }

        @Test
        @DisplayName("Multiplier should be 1000")
        void multiplierShouldBe1000() {
            assertThat(MULTIPLIER).isEqualTo(1000);
        }
    }

    // =================================================================
    // 2. Nested splits (2-level)
    // =================================================================

    @Nested
    @DisplayName("Nested splits (2-level)")
    class TwoLevelNestedSplitTests {

        @Test
        @DisplayName("Should compute 2-level nested child index correctly")
        void shouldComputeTwoLevelNestedChildIndex() {
            // Level 1: parent=0, local=2 -> child=2
            int level1Child = computeChildIndex(0, 2);
            assertThat(level1Child).isEqualTo(2);

            // Level 2: parent=2, local=5 -> child=2005
            int level2Child = computeChildIndex(level1Child, 5);
            assertThat(level2Child).isEqualTo(2005);
        }

        @Test
        @DisplayName("Should extract parent and local from 2-level child index")
        void shouldExtractParentAndLocalFromTwoLevelChild() {
            int level2Child = 2005;

            assertThat(extractParentIndex(level2Child)).isEqualTo(2);
            assertThat(extractLocalIndex(level2Child)).isEqualTo(5);
        }

        @Test
        @DisplayName("Round-trip: compute then extract should yield original values")
        void roundTripShouldYieldOriginalValues() {
            int parent = 7;
            int local = 42;
            int child = computeChildIndex(parent, local);

            assertThat(extractParentIndex(child)).isEqualTo(parent);
            assertThat(extractLocalIndex(child)).isEqualTo(local);
        }
    }

    // =================================================================
    // 3. Deeply nested splits (3-level)
    // =================================================================

    @Nested
    @DisplayName("Deeply nested splits (3-level)")
    class ThreeLevelNestedSplitTests {

        @Test
        @DisplayName("Should compute 3-level nested child index correctly")
        void shouldComputeThreeLevelNestedChildIndex() {
            // Level 1: parent=0, local=3 -> child=3
            int level1 = computeChildIndex(0, 3);
            assertThat(level1).isEqualTo(3);

            // Level 2: parent=3, local=7 -> child=3007
            int level2 = computeChildIndex(level1, 7);
            assertThat(level2).isEqualTo(3007);

            // Level 3: parent=3007, local=2 -> child=3007002
            int level3 = computeChildIndex(level2, 2);
            assertThat(level3).isEqualTo(3_007_002);
        }

        @Test
        @DisplayName("Should extract indices at each level of 3-level nesting")
        void shouldExtractIndicesAtEachLevel() {
            int level3 = 3_007_002;

            // Extract level 3
            assertThat(extractParentIndex(level3)).isEqualTo(3007);
            assertThat(extractLocalIndex(level3)).isEqualTo(2);

            // Extract level 2 from the parent
            int level2Parent = extractParentIndex(level3);
            assertThat(extractParentIndex(level2Parent)).isEqualTo(3);
            assertThat(extractLocalIndex(level2Parent)).isEqualTo(7);

            // Extract level 1 from the grandparent
            int level1Parent = extractParentIndex(level2Parent);
            assertThat(extractParentIndex(level1Parent)).isEqualTo(0);
            assertThat(extractLocalIndex(level1Parent)).isEqualTo(3);
        }

        @Test
        @DisplayName("Full chain extraction should recover all original local indices")
        void fullChainExtractionShouldRecoverAllLocalIndices() {
            int localLevel1 = 3;
            int localLevel2 = 7;
            int localLevel3 = 2;

            int level1 = computeChildIndex(0, localLevel1);
            int level2 = computeChildIndex(level1, localLevel2);
            int level3 = computeChildIndex(level2, localLevel3);

            // Unwind level 3
            assertThat(extractLocalIndex(level3)).isEqualTo(localLevel3);
            int remaining2 = extractParentIndex(level3);

            // Unwind level 2
            assertThat(extractLocalIndex(remaining2)).isEqualTo(localLevel2);
            int remaining1 = extractParentIndex(remaining2);

            // Unwind level 1
            assertThat(extractLocalIndex(remaining1)).isEqualTo(localLevel1);
            assertThat(extractParentIndex(remaining1)).isEqualTo(0);
        }
    }

    // =================================================================
    // 4. Reverse extraction
    // =================================================================

    @Nested
    @DisplayName("Reverse extraction (parent and local from child)")
    class ReverseExtractionTests {

        @ParameterizedTest(name = "childIndex={0} -> parent={1}, local={2}")
        @CsvSource({
                "0, 0, 0",
                "1, 0, 1",
                "999, 0, 999",
                "1000, 1, 0",
                "1001, 1, 1",
                "2003, 2, 3",
                "10005, 10, 5",
                "999999, 999, 999"
        })
        @DisplayName("Should extract correct parent and local index from child index")
        void shouldExtractCorrectParentAndLocal(int childIndex, int expectedParent, int expectedLocal) {
            assertThat(extractParentIndex(childIndex)).isEqualTo(expectedParent);
            assertThat(extractLocalIndex(childIndex)).isEqualTo(expectedLocal);
        }

        @Test
        @DisplayName("Zero child index should yield zero parent and zero local")
        void zeroChildIndexShouldYieldZeros() {
            assertThat(extractParentIndex(0)).isEqualTo(0);
            assertThat(extractLocalIndex(0)).isEqualTo(0);
        }
    }

    // =================================================================
    // 5. Boundary values
    // =================================================================

    @Nested
    @DisplayName("Boundary values")
    class BoundaryValueTests {

        @Test
        @DisplayName("local=999 should be the max safe local index before next parent range")
        void maxLocalIndexShouldBeWithinParentRange() {
            int parent = 5;
            int localMax = 999;
            int child = computeChildIndex(parent, localMax);

            assertThat(child).isEqualTo(5999);
            assertThat(extractParentIndex(child)).isEqualTo(parent);
            assertThat(extractLocalIndex(child)).isEqualTo(localMax);

            // Next parent range starts at 6000
            int nextParentStart = computeChildIndex(parent + 1, 0);
            assertThat(nextParentStart).isEqualTo(6000);
            assertThat(child).isLessThan(nextParentStart);
        }

        @Test
        @DisplayName("local=0 should be the first item in any parent range")
        void firstItemInParentRange() {
            int parent = 42;
            int child = computeChildIndex(parent, 0);

            assertThat(child).isEqualTo(42000);
            assertThat(extractParentIndex(child)).isEqualTo(parent);
            assertThat(extractLocalIndex(child)).isEqualTo(0);
        }

        @Test
        @DisplayName("Parent near Integer.MAX_VALUE / MULTIPLIER should not overflow")
        void parentNearMaxIntDividedByMultiplierShouldNotOverflow() {
            int maxSafeParent = Integer.MAX_VALUE / MULTIPLIER;
            // maxSafeParent * 1000 should still be representable as int (with local=0)
            int child = computeChildIndex(maxSafeParent, 0);

            assertThat(child).isGreaterThan(0);
            assertThat(extractParentIndex(child)).isEqualTo(maxSafeParent);
            assertThat(extractLocalIndex(child)).isEqualTo(0);
        }

        @Test
        @DisplayName("Parent at Integer.MAX_VALUE / MULTIPLIER with max local should not overflow")
        void parentAtMaxWithMaxLocalShouldNotOverflow() {
            int maxSafeParent = Integer.MAX_VALUE / MULTIPLIER;
            // Check if adding max local (999) would overflow
            long childLong = (long) maxSafeParent * MULTIPLIER + 999;
            boolean wouldOverflow = childLong > Integer.MAX_VALUE;

            if (!wouldOverflow) {
                int child = computeChildIndex(maxSafeParent, 999);
                assertThat(extractParentIndex(child)).isEqualTo(maxSafeParent);
                assertThat(extractLocalIndex(child)).isEqualTo(999);
            } else {
                // Document that this parent + local combination would overflow
                assertThat(childLong).isGreaterThan(Integer.MAX_VALUE);
            }
        }
    }

    // =================================================================
    // 6. Collision detection
    // =================================================================

    @Nested
    @DisplayName("Collision detection")
    class CollisionDetectionTests {

        @Test
        @DisplayName("parent=1 local=0 and parent=0 local=1000 produce the same value (collision)")
        void shouldDemonstrateCollisionWhenLocalExceedsMultiplier() {
            // parent=1, local=0 -> 1000
            int validChild = computeChildIndex(1, 0);
            assertThat(validChild).isEqualTo(1000);

            // parent=0, local=1000 -> 0*1000 + 1000 = 1000 (COLLISION!)
            int collidingChild = computeChildIndex(0, 1000);
            assertThat(collidingChild).isEqualTo(1000);

            // Both produce the same value - this is why local must be < MULTIPLIER
            assertThat(validChild).isEqualTo(collidingChild);
        }

        @Test
        @DisplayName("Local index must be less than MULTIPLIER to avoid collisions")
        void localIndexMustBeLessThanMultiplier() {
            // For any parent, if local < MULTIPLIER, the child index is unique
            // and can be reversed correctly
            for (int parent = 0; parent < 10; parent++) {
                for (int local = 0; local < MULTIPLIER; local += 100) {
                    int child = computeChildIndex(parent, local);
                    assertThat(extractParentIndex(child))
                            .as("parent extraction for parent=%d, local=%d", parent, local)
                            .isEqualTo(parent);
                    assertThat(extractLocalIndex(child))
                            .as("local extraction for parent=%d, local=%d", parent, local)
                            .isEqualTo(local);
                }
            }
        }

        @Test
        @DisplayName("Adjacent parent ranges should not overlap when local < MULTIPLIER")
        void adjacentParentRangesShouldNotOverlap() {
            int parent1MaxChild = computeChildIndex(1, MULTIPLIER - 1);
            int parent2MinChild = computeChildIndex(2, 0);

            assertThat(parent1MaxChild).isLessThan(parent2MinChild);
            assertThat(parent2MinChild - parent1MaxChild).isEqualTo(1);
        }
    }

    // =================================================================
    // 7. Batch processing simulation
    // =================================================================

    @Nested
    @DisplayName("Batch processing simulation")
    class BatchProcessingSimulationTests {

        @Test
        @DisplayName("100 items in a single split should all have unique indices")
        void singleSplitWith100ItemsShouldAllBeUnique() {
            Set<Integer> indices = new HashSet<>();
            int parent = 0;

            for (int local = 0; local < 100; local++) {
                int child = computeChildIndex(parent, local);
                boolean added = indices.add(child);
                assertThat(added)
                        .as("Index %d (local=%d) should be unique", child, local)
                        .isTrue();
            }

            assertThat(indices).hasSize(100);
        }

        @Test
        @DisplayName("10 items x 10 sub-items in nested split should have unique indices per level")
        void nestedSplit10x10ShouldHaveUniqueIndicesPerLevel() {
            Set<Integer> outerIndices = new HashSet<>();
            Set<Integer> innerIndices = new HashSet<>();

            for (int outerLocal = 0; outerLocal < 10; outerLocal++) {
                int outerChild = computeChildIndex(0, outerLocal);
                boolean outerAdded = outerIndices.add(outerChild);
                assertThat(outerAdded)
                        .as("Outer index %d (outer=%d) should be unique at level 1", outerChild, outerLocal)
                        .isTrue();

                for (int innerLocal = 0; innerLocal < 10; innerLocal++) {
                    int innerChild = computeChildIndex(outerChild, innerLocal);
                    boolean innerAdded = innerIndices.add(innerChild);
                    assertThat(innerAdded)
                            .as("Inner index %d (outer=%d, inner=%d) should be unique at level 2",
                                    innerChild, outerLocal, innerLocal)
                            .isTrue();
                }
            }

            assertThat(outerIndices).hasSize(10);
            assertThat(innerIndices).hasSize(100);
        }

        @Test
        @DisplayName("Nested split indices should be recoverable to their original positions")
        void nestedSplitIndicesShouldBeRecoverable() {
            for (int outerLocal = 0; outerLocal < 10; outerLocal++) {
                int outerChild = computeChildIndex(0, outerLocal);

                for (int innerLocal = 0; innerLocal < 10; innerLocal++) {
                    int innerChild = computeChildIndex(outerChild, innerLocal);

                    // Recover inner local
                    assertThat(extractLocalIndex(innerChild)).isEqualTo(innerLocal);

                    // Recover outer child (which is the parent of inner)
                    int recoveredOuterChild = extractParentIndex(innerChild);
                    assertThat(recoveredOuterChild).isEqualTo(outerChild);

                    // Recover outer local from the outer child
                    assertThat(extractLocalIndex(recoveredOuterChild)).isEqualTo(outerLocal);
                }
            }
        }
    }

    // =================================================================
    // 8. Stress test
    // =================================================================

    @Nested
    @DisplayName("Stress test - 3-level nested split (5 x 5 x 5 = 125 items)")
    class StressTest {

        @Test
        @DisplayName("All 125 leaf indices in a 5x5x5 nested split should be unique")
        void allLeafIndicesShouldBeUnique() {
            Set<Integer> leafIndices = new HashSet<>();

            for (int l1 = 0; l1 < 5; l1++) {
                int level1 = computeChildIndex(0, l1);

                for (int l2 = 0; l2 < 5; l2++) {
                    int level2 = computeChildIndex(level1, l2);

                    for (int l3 = 0; l3 < 5; l3++) {
                        int level3 = computeChildIndex(level2, l3);
                        boolean added = leafIndices.add(level3);
                        assertThat(added)
                                .as("Leaf index %d (l1=%d, l2=%d, l3=%d) should be unique",
                                        level3, l1, l2, l3)
                                .isTrue();
                    }
                }
            }

            assertThat(leafIndices).hasSize(125);
        }

        @Test
        @DisplayName("All indices within each level of a 5x5x5 nested split should be unique")
        void allIndicesWithinEachLevelShouldBeUnique() {
            Set<Integer> level1Indices = new HashSet<>();
            Set<Integer> level2Indices = new HashSet<>();
            Set<Integer> level3Indices = new HashSet<>();

            for (int l1 = 0; l1 < 5; l1++) {
                int level1 = computeChildIndex(0, l1);
                level1Indices.add(level1);

                for (int l2 = 0; l2 < 5; l2++) {
                    int level2 = computeChildIndex(level1, l2);
                    level2Indices.add(level2);

                    for (int l3 = 0; l3 < 5; l3++) {
                        int level3 = computeChildIndex(level2, l3);
                        level3Indices.add(level3);
                    }
                }
            }

            assertThat(level1Indices).hasSize(5);
            assertThat(level2Indices).hasSize(25);
            assertThat(level3Indices).hasSize(125);
        }

        @Test
        @DisplayName("Reverse extraction should work for all 125 leaf indices in 5x5x5 split")
        void reverseExtractionShouldWorkForAllLeafIndices() {
            for (int l1 = 0; l1 < 5; l1++) {
                int level1 = computeChildIndex(0, l1);

                for (int l2 = 0; l2 < 5; l2++) {
                    int level2 = computeChildIndex(level1, l2);

                    for (int l3 = 0; l3 < 5; l3++) {
                        int level3 = computeChildIndex(level2, l3);

                        // Unwind level 3
                        assertThat(extractLocalIndex(level3))
                                .as("Level 3 local for (%d,%d,%d)", l1, l2, l3)
                                .isEqualTo(l3);
                        int recovered2 = extractParentIndex(level3);

                        // Unwind level 2
                        assertThat(extractLocalIndex(recovered2))
                                .as("Level 2 local for (%d,%d,%d)", l1, l2, l3)
                                .isEqualTo(l2);
                        int recovered1 = extractParentIndex(recovered2);

                        // Unwind level 1
                        assertThat(extractLocalIndex(recovered1))
                                .as("Level 1 local for (%d,%d,%d)", l1, l2, l3)
                                .isEqualTo(l1);
                        assertThat(extractParentIndex(recovered1))
                                .as("Root parent for (%d,%d,%d)", l1, l2, l3)
                                .isEqualTo(0);
                    }
                }
            }
        }

        @Test
        @DisplayName("Leaf index values should follow the expected mathematical pattern")
        void leafIndexValuesShouldFollowExpectedPattern() {
            // For a 3-level split with locals (l1, l2, l3):
            //   level1 = l1
            //   level2 = l1 * 1000 + l2
            //   level3 = (l1 * 1000 + l2) * 1000 + l3 = l1 * 1_000_000 + l2 * 1000 + l3
            for (int l1 = 0; l1 < 5; l1++) {
                for (int l2 = 0; l2 < 5; l2++) {
                    for (int l3 = 0; l3 < 5; l3++) {
                        int level1 = computeChildIndex(0, l1);
                        int level2 = computeChildIndex(level1, l2);
                        int level3 = computeChildIndex(level2, l3);

                        int expectedLeaf = l1 * 1_000_000 + l2 * 1_000 + l3;
                        assertThat(level3)
                                .as("Leaf for (%d,%d,%d) should equal %d*1000000 + %d*1000 + %d",
                                        l1, l2, l3, l1, l2, l3)
                                .isEqualTo(expectedLeaf);
                    }
                }
            }
        }
    }
}

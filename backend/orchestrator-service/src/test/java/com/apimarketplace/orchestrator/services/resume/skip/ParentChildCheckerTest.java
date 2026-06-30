package com.apimarketplace.orchestrator.services.resume.skip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ParentChildChecker")
class ParentChildCheckerTest {

    private ParentChildChecker checker;

    @BeforeEach
    void setUp() {
        checker = new ParentChildChecker();
    }

    @Nested
    @DisplayName("isDependencyParentCompleted()")
    class IsDependencyParentCompletedTests {

        @Test
        @DisplayName("Should return true when parent is completed for sub-node")
        void shouldReturnTrueWhenParentCompleted() {
            Set<String> completed = Set.of("core:while");

            assertTrue(checker.isDependencyParentCompleted(
                "core:while::controller", completed
            ));
        }

        @Test
        @DisplayName("Should return true for condition_checker sub-node when parent completed")
        void shouldReturnTrueForConditionChecker() {
            Set<String> completed = Set.of("core:while");

            assertTrue(checker.isDependencyParentCompleted(
                "core:while::condition_checker", completed
            ));
        }

        @Test
        @DisplayName("Should return false when parent is not completed")
        void shouldReturnFalseWhenParentNotCompleted() {
            Set<String> completed = Set.of("core:other");

            assertFalse(checker.isDependencyParentCompleted(
                "core:while::controller", completed
            ));
        }

        @Test
        @DisplayName("Should return false for null dependency ID")
        void shouldReturnFalseForNull() {
            assertFalse(checker.isDependencyParentCompleted(null, Set.of()));
        }

        @Test
        @DisplayName("Should return false for non-sub-node ID")
        void shouldReturnFalseForNonSubNode() {
            Set<String> completed = Set.of("core:while");

            assertFalse(checker.isDependencyParentCompleted(
                "mcp:step1", completed
            ));
        }
    }

    @Nested
    @DisplayName("isDependencyParentSkipped()")
    class IsDependencyParentSkippedTests {

        @Test
        @DisplayName("Should return true when parent is skipped for sub-node")
        void shouldReturnTrueWhenParentSkipped() {
            Set<String> skipped = Set.of("core:while");

            assertTrue(checker.isDependencyParentSkipped(
                "core:while::condition_checker", skipped
            ));
        }

        @Test
        @DisplayName("Should return true when parent is skipped for controller sub-node")
        void shouldReturnTrueForControllerSubNode() {
            Set<String> skipped = Set.of("core:while");

            assertTrue(checker.isDependencyParentSkipped(
                "core:while::controller", skipped
            ));
        }

        @Test
        @DisplayName("Should return false when parent is not skipped")
        void shouldReturnFalseWhenParentNotSkipped() {
            Set<String> skipped = Set.of("core:other");

            assertFalse(checker.isDependencyParentSkipped(
                "core:while::controller", skipped
            ));
        }

        @Test
        @DisplayName("Should return false for null dependency ID")
        void shouldReturnFalseForNull() {
            assertFalse(checker.isDependencyParentSkipped(null, Set.of()));
        }

        @Test
        @DisplayName("Should check base loop key for core: prefix")
        void shouldCheckBaseLoopKey() {
            Set<String> skipped = Set.of("core:my_loop");

            // "core:my_loop::something" should match via parent check
            assertTrue(checker.isDependencyParentSkipped(
                "core:my_loop::controller", skipped
            ));
        }

        @Test
        @DisplayName("Should return false for non-sub-node ID without matching parent")
        void shouldReturnFalseForNonSubNodeWithoutMatch() {
            Set<String> skipped = Set.of("core:other_loop");

            assertFalse(checker.isDependencyParentSkipped(
                "mcp:step1", skipped
            ));
        }
    }
}

package com.apimarketplace.orchestrator.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExecutionConstants")
class ExecutionConstantsTest {

    @Nested
    @DisplayName("Execution limits")
    class ExecutionLimitsTests {
        @Test
        @DisplayName("Should have reasonable default max loop iterations")
        void shouldHaveReasonableDefaultMaxLoopIterations() {
            assertEquals(100, ExecutionConstants.DEFAULT_MAX_LOOP_ITERATIONS);
        }

        @Test
        @DisplayName("Should have reasonable default trigger batch size")
        void shouldHaveReasonableDefaultTriggerBatchSize() {
            assertEquals(100, ExecutionConstants.DEFAULT_TRIGGER_BATCH_SIZE);
        }

        @Test
        @DisplayName("Should have reasonable default max datasource items")
        void shouldHaveReasonableDefaultMaxDatasourceItems() {
            assertEquals(10000, ExecutionConstants.DEFAULT_MAX_DATASOURCE_ITEMS);
        }

        @Test
        @DisplayName("Should have reasonable default step timeout")
        void shouldHaveReasonableDefaultStepTimeout() {
            assertEquals(30_000, ExecutionConstants.DEFAULT_STEP_TIMEOUT_MS);
        }

        @Test
        @DisplayName("Should have reasonable default workflow timeout")
        void shouldHaveReasonableDefaultWorkflowTimeout() {
            assertEquals(300_000, ExecutionConstants.DEFAULT_WORKFLOW_TIMEOUT_MS);
        }
    }

    @Nested
    @DisplayName("Item index calculation")
    class ItemIndexCalculationTests {
        @Test
        @DisplayName("Should have split item index multiplier")
        void shouldHaveSplitItemIndexMultiplier() {
            assertEquals(1000, ExecutionConstants.SPLIT_ITEM_INDEX_MULTIPLIER);
        }

        @Test
        @DisplayName("Multiplier should allow child index calculation")
        void multiplierShouldAllowChildIndexCalculation() {
            int parentIndex = 2;
            int localIndex = 3;
            int childIndex = parentIndex * ExecutionConstants.SPLIT_ITEM_INDEX_MULTIPLIER + localIndex;

            assertEquals(2003, childIndex);
        }
    }

    @Nested
    @DisplayName("Node prefixes")
    class NodePrefixesTests {
        @Test
        @DisplayName("Should have correct trigger prefix")
        void shouldHaveCorrectTriggerPrefix() {
            assertEquals("trigger:", ExecutionConstants.PREFIX_TRIGGER);
        }

        @Test
        @DisplayName("Should have correct step prefix")
        void shouldHaveCorrectStepPrefix() {
            assertEquals("mcp:", ExecutionConstants.PREFIX_STEP);
        }

        @Test
        @DisplayName("Should have correct table prefix")
        void shouldHaveCorrectTablePrefix() {
            assertEquals("table:", ExecutionConstants.PREFIX_TABLE);
        }

        @Test
        @DisplayName("Should have correct agent prefix")
        void shouldHaveCorrectAgentPrefix() {
            assertEquals("agent:", ExecutionConstants.PREFIX_AGENT);
        }

        @Test
        @DisplayName("Should have correct core prefix")
        void shouldHaveCorrectCorePrefix() {
            assertEquals("core:", ExecutionConstants.PREFIX_CORE);
        }

        @Test
        @DisplayName("Should have correct note prefix")
        void shouldHaveCorrectNotePrefix() {
            assertEquals("note:", ExecutionConstants.PREFIX_NOTE);
        }

        @Test
        @DisplayName("Should have correct interface prefix")
        void shouldHaveCorrectInterfacePrefix() {
            assertEquals("interface:", ExecutionConstants.PREFIX_INTERFACE);
        }
    }

    @Nested
    @DisplayName("Status values")
    class StatusValuesTests {
        @Test
        @DisplayName("Should have all status values")
        void shouldHaveAllStatusValues() {
            assertEquals("SKIPPED", ExecutionConstants.STATUS_SKIPPED);
            assertEquals("COMPLETED", ExecutionConstants.STATUS_COMPLETED);
            assertEquals("FAILED", ExecutionConstants.STATUS_FAILED);
            assertEquals("RUNNING", ExecutionConstants.STATUS_RUNNING);
            assertEquals("READY", ExecutionConstants.STATUS_READY);
            assertEquals("PENDING", ExecutionConstants.STATUS_PENDING);
        }
    }

    @Nested
    @DisplayName("Event types")
    class EventTypesTests {
        @Test
        @DisplayName("Should have step-by-step event types")
        void shouldHaveStepByStepEventTypes() {
            assertEquals("STEP_BY_STEP_READY", ExecutionConstants.EVENT_STEP_BY_STEP_READY);
            assertEquals("STEP_BY_STEP_PAUSED", ExecutionConstants.EVENT_STEP_BY_STEP_PAUSED);
        }
    }

    @Nested
    @DisplayName("Context keys")
    class ContextKeysTests {
        @Test
        @DisplayName("Should have item index keys")
        void shouldHaveItemIndexKeys() {
            assertEquals("item_index", ExecutionConstants.KEY_ITEM_INDEX);
            assertEquals("itemIndex", ExecutionConstants.KEY_ITEM_INDEX_ALT);
        }

        @Test
        @DisplayName("Should have other context keys")
        void shouldHaveOtherContextKeys() {
            assertEquals("epoch", ExecutionConstants.KEY_EPOCH);
            assertEquals("spawn", ExecutionConstants.KEY_SPAWN);
            assertEquals("http_status", ExecutionConstants.KEY_HTTP_STATUS);
        }
    }

    @Nested
    @DisplayName("Decision branch names")
    class DecisionBranchNamesTests {
        @Test
        @DisplayName("Should have decision branch names")
        void shouldHaveDecisionBranchNames() {
            assertEquals("if", ExecutionConstants.BRANCH_IF);
            assertEquals("then", ExecutionConstants.BRANCH_THEN);
            assertEquals("elsif", ExecutionConstants.BRANCH_ELSIF);
            assertEquals("else", ExecutionConstants.BRANCH_ELSE);
        }
    }

    @Nested
    @DisplayName("extractPrefix()")
    class ExtractPrefixTests {
        @ParameterizedTest
        @CsvSource({
            "mcp:step1, mcp:",
            "trigger:webhook, trigger:",
            "core:decision, core:",
            "table:users, table:",
            "agent:claude, agent:"
        })
        @DisplayName("Should extract prefix from node ID")
        void shouldExtractPrefixFromNodeId(String nodeId, String expectedPrefix) {
            assertEquals(expectedPrefix, ExecutionConstants.extractPrefix(nodeId));
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(ExecutionConstants.extractPrefix(null));
        }

        @Test
        @DisplayName("Should return null for no prefix")
        void shouldReturnNullForNoPrefix() {
            assertNull(ExecutionConstants.extractPrefix("nodeid"));
        }
    }

    @Nested
    @DisplayName("hasPrefix()")
    class HasPrefixTests {
        @Test
        @DisplayName("Should return true when prefix matches")
        void shouldReturnTrueWhenPrefixMatches() {
            assertTrue(ExecutionConstants.hasPrefix("mcp:step1", ExecutionConstants.PREFIX_STEP));
            assertTrue(ExecutionConstants.hasPrefix("trigger:webhook", ExecutionConstants.PREFIX_TRIGGER));
            assertTrue(ExecutionConstants.hasPrefix("core:decision", ExecutionConstants.PREFIX_CORE));
        }

        @Test
        @DisplayName("Should return false when prefix does not match")
        void shouldReturnFalseWhenPrefixDoesNotMatch() {
            assertFalse(ExecutionConstants.hasPrefix("mcp:step1", ExecutionConstants.PREFIX_TRIGGER));
            assertFalse(ExecutionConstants.hasPrefix("trigger:webhook", ExecutionConstants.PREFIX_STEP));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should return false for null/empty node ID")
        void shouldReturnFalseForNullEmptyNodeId(String nodeId) {
            assertFalse(ExecutionConstants.hasPrefix(nodeId, ExecutionConstants.PREFIX_STEP));
        }

        @Test
        @DisplayName("Should return false for null prefix")
        void shouldReturnFalseForNullPrefix() {
            assertFalse(ExecutionConstants.hasPrefix("mcp:step1", null));
        }
    }
}

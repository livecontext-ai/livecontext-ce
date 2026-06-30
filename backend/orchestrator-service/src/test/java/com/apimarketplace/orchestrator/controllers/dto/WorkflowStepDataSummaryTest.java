package com.apimarketplace.orchestrator.controllers.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WorkflowStepDataSummary record.
 */
@DisplayName("WorkflowStepDataSummary")
class WorkflowStepDataSummaryTest {

    @Test
    @DisplayName("Should store all fields correctly")
    void shouldStoreAllFields() {
        UUID runUuid = UUID.randomUUID();
        UUID storageId = UUID.randomUUID();
        Instant start = Instant.now();
        Instant end = start.plusSeconds(30);
        Map<String, Object> input = Map.of("query", "test");
        Map<String, Object> metadata = Map.of("toolName", "search");

        WorkflowStepDataSummary summary = new WorkflowStepDataSummary(
            1L, runUuid, "run-123", "fetch_data", "api/search",
            "COMPLETED", start, end, 200, storageId,
            0, 0, 1, 0, null, input, metadata, "tenant-1",
            // Node-type-specific fields
            null, null, null, null, null,
            null, null, null,
            null, null, null,
            null, null, null, null, null
        );

        assertThat(summary.id()).isEqualTo(1L);
        assertThat(summary.workflowRunId()).isEqualTo(runUuid);
        assertThat(summary.runId()).isEqualTo("run-123");
        assertThat(summary.stepAlias()).isEqualTo("fetch_data");
        assertThat(summary.toolId()).isEqualTo("api/search");
        assertThat(summary.status()).isEqualTo("COMPLETED");
        assertThat(summary.startTime()).isEqualTo(start);
        assertThat(summary.endTime()).isEqualTo(end);
        assertThat(summary.httpStatus()).isEqualTo(200);
        assertThat(summary.outputStorageId()).isEqualTo(storageId);
        assertThat(summary.iteration()).isZero();
        assertThat(summary.itemIndex()).isZero();
        assertThat(summary.epoch()).isEqualTo(1);
        assertThat(summary.spawn()).isZero();
        assertThat(summary.errorMessage()).isNull();
        assertThat(summary.inputData()).containsEntry("query", "test");
        assertThat(summary.metadata()).containsEntry("toolName", "search");
        assertThat(summary.tenantId()).isEqualTo("tenant-1");
    }

    @Test
    @DisplayName("Should handle null optional fields")
    void shouldHandleNullFields() {
        WorkflowStepDataSummary summary = new WorkflowStepDataSummary(
            1L, UUID.randomUUID(), "run-1", "step", "tool",
            "FAILED", null, null, null, null,
            null, null, null, null, "Connection error", null, null, "tenant-1",
            // Node-type-specific fields
            null, null, null, null, null,
            null, null, null,
            null, null, null,
            null, null, null, null, null
        );

        assertThat(summary.httpStatus()).isNull();
        assertThat(summary.startTime()).isNull();
        assertThat(summary.errorMessage()).isEqualTo("Connection error");
        assertThat(summary.inputData()).isNull();
    }

    @Test
    @DisplayName("Should store node-type-specific fields for decision node")
    void shouldStoreDecisionNodeFields() {
        WorkflowStepDataSummary summary = new WorkflowStepDataSummary(
            1L, UUID.randomUUID(), "run-1", "check_status", "core",
            "COMPLETED", null, null, null, null,
            0, 0, 0, 0, null, null, null, "tenant-1",
            // Node-type-specific fields
            "DECISION", "core:check_status",
            "#result > 100", true, "if",
            null, null, null,
            null, null, null,
            null, null, null, null, null
        );

        assertThat(summary.nodeType()).isEqualTo("DECISION");
        assertThat(summary.normalizedKey()).isEqualTo("core:check_status");
        assertThat(summary.conditionExpression()).isEqualTo("#result > 100");
        assertThat(summary.conditionResult()).isTrue();
        assertThat(summary.selectedBranch()).isEqualTo("if");
    }

    @Test
    @DisplayName("Should store node-type-specific fields for loop node")
    void shouldStoreLoopNodeFields() {
        WorkflowStepDataSummary summary = new WorkflowStepDataSummary(
            1L, UUID.randomUUID(), "run-1", "retry_loop", "core",
            "COMPLETED", null, null, null, null,
            0, 0, 0, 0, null, null, null, "tenant-1",
            // Node-type-specific fields
            "LOOP", "core:retry_loop",
            null, null, null,
            "loop-abc", 3, "MAX_ITERATIONS",
            null, null, null,
            null, null, null, null, null
        );

        assertThat(summary.nodeType()).isEqualTo("LOOP");
        assertThat(summary.loopId()).isEqualTo("loop-abc");
        assertThat(summary.loopIteration()).isEqualTo(3);
        assertThat(summary.loopExitReason()).isEqualTo("MAX_ITERATIONS");
    }

    @Test
    @DisplayName("Should store node-type-specific fields for merge node")
    void shouldStoreMergeNodeFields() {
        WorkflowStepDataSummary summary = new WorkflowStepDataSummary(
            1L, UUID.randomUUID(), "run-1", "wait_all", "core",
            "COMPLETED", null, null, null, null,
            0, 0, 0, 0, null, null, null, "tenant-1",
            // Node-type-specific fields
            "MERGE", "core:wait_all",
            null, null, null,
            null, null, null,
            "AND", List.of("branch_0", "branch_1"), List.of("branch_2"),
            null, null, null, null, null
        );

        assertThat(summary.nodeType()).isEqualTo("MERGE");
        assertThat(summary.mergeStrategy()).isEqualTo("AND");
        assertThat(summary.mergeReceivedBranches()).containsExactly("branch_0", "branch_1");
        assertThat(summary.mergeSkippedBranches()).containsExactly("branch_2");
    }

    @Test
    @DisplayName("Should store skip tracking fields")
    void shouldStoreSkipFields() {
        WorkflowStepDataSummary summary = new WorkflowStepDataSummary(
            1L, UUID.randomUUID(), "run-1", "skipped_step", "mcp",
            "SKIPPED", null, null, null, null,
            0, 0, 0, 0, null, null, null, "tenant-1",
            // Node-type-specific fields
            "MCP", "mcp:skipped_step",
            null, null, null,
            null, null, null,
            null, null, null,
            "Branch not selected", "core:check_status",
            null, null, null
        );

        assertThat(summary.skipReason()).isEqualTo("Branch not selected");
        assertThat(summary.skipSourceNode()).isEqualTo("core:check_status");
    }
}

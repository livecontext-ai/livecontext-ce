package com.apimarketplace.orchestrator.controllers.dto;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WorkflowRunSummary.
 */
@DisplayName("WorkflowRunSummary")
class WorkflowRunSummaryTest {

    @Test
    @DisplayName("Should store all fields")
    void shouldStoreAllFields() {
        UUID id = UUID.randomUUID();
        Instant startedAt = Instant.now();
        Instant endedAt = startedAt.plusMillis(5000);
        Instant lastFireAt = startedAt.plusMillis(3600_000);
        Map<String, Object> payload = Map.of("data", "value");
        Map<String, Object> metadata = Map.of("source", "webhook");
        Map<String, Object> plan = Map.of("version", 2);

        WorkflowRunSummary summary = new WorkflowRunSummary(
            id, "run-123", "tenant-1", RunStatus.COMPLETED,
            "automatic", startedAt, endedAt, 5000L, 3,
            payload, metadata, plan, 2, 5, lastFireAt);

        assertThat(summary.id()).isEqualTo(id);
        assertThat(summary.runId()).isEqualTo("run-123");
        assertThat(summary.tenantId()).isEqualTo("tenant-1");
        assertThat(summary.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(summary.executionMode()).isEqualTo("automatic");
        assertThat(summary.startedAt()).isEqualTo(startedAt);
        assertThat(summary.endedAt()).isEqualTo(endedAt);
        assertThat(summary.durationMs()).isEqualTo(5000L);
        assertThat(summary.totalNodes()).isEqualTo(3);
        assertThat(summary.triggerPayload()).containsEntry("data", "value");
        assertThat(summary.metadata()).containsEntry("source", "webhook");
        assertThat(summary.plan()).containsEntry("version", 2);
        assertThat(summary.planVersion()).isEqualTo(2);
        assertThat(summary.currentEpoch()).isEqualTo(5);
        assertThat(summary.lastFireAt()).isEqualTo(lastFireAt);
    }

    @Test
    @DisplayName("Should handle null optional fields")
    void shouldHandleNullOptionalFields() {
        WorkflowRunSummary summary = new WorkflowRunSummary(
            null, "run-456", "tenant-2", RunStatus.RUNNING,
            null, Instant.now(), null, null, null,
            null, null, null, null, null, null);

        assertThat(summary.id()).isNull();
        assertThat(summary.endedAt()).isNull();
        assertThat(summary.durationMs()).isNull();
        assertThat(summary.totalNodes()).isNull();
        assertThat(summary.planVersion()).isNull();
        assertThat(summary.currentEpoch()).isNull();
        assertThat(summary.lastFireAt()).isNull();
    }
}

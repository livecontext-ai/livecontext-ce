package com.apimarketplace.orchestrator.controllers.dto;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WorkflowSummary.
 */
@DisplayName("WorkflowSummary")
class WorkflowSummaryTest {

    @Test
    @DisplayName("Should store all fields")
    void shouldStoreAllFields() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> plan = Map.of("version", 2);
        Map<String, Object> schedule = Map.of("cron", "0 * * * *");
        Map<String, String> tokens = Map.of("trigger:wh", "wh_token");
        List<Map<String, Object>> icons = List.of(Map.of("type", "trigger", "icon", "webhook"));

        UUID pubId = UUID.randomUUID();
        WorkflowSummary summary = new WorkflowSummary(
            id, "My Workflow", "Description", "tenant-1",
            WorkflowEntity.WorkflowStatus.ACTIVE,
            now, now, now, 10L,
            Map.of(), plan, schedule, tokens, icons,
            pubId, now, true, "ACTIVE", null,
            WorkflowEntity.WorkflowType.WORKFLOW, 5, true, "production");

        assertThat(summary.id()).isEqualTo(id);
        assertThat(summary.name()).isEqualTo("My Workflow");
        assertThat(summary.description()).isEqualTo("Description");
        assertThat(summary.tenantId()).isEqualTo("tenant-1");
        assertThat(summary.status()).isEqualTo(WorkflowEntity.WorkflowStatus.ACTIVE);
        assertThat(summary.runCount()).isEqualTo(10L);
        assertThat(summary.webhookTokens()).containsEntry("trigger:wh", "wh_token");
        assertThat(summary.nodeIcons()).hasSize(1);
        assertThat(summary.sourcePublicationId()).isEqualTo(pubId);
        assertThat(summary.isPublished()).isTrue();
        assertThat(summary.publicationStatus()).isEqualTo("ACTIVE");
        assertThat(summary.hasActiveRun()).isTrue();
    }

    @Test
    @DisplayName("Should handle null optional fields")
    void shouldHandleNullOptionalFields() {
        WorkflowSummary summary = new WorkflowSummary(
            null, "WF", null, "t1", null,
            null, null, null, 0L,
            null, null, null, null, null,
            null, null, false, null, null, null, null, false, "draft");

        assertThat(summary.id()).isNull();
        assertThat(summary.description()).isNull();
        assertThat(summary.plan()).isNull();
        assertThat(summary.nodeIcons()).isNull();
        assertThat(summary.sourcePublicationId()).isNull();
        assertThat(summary.isPublished()).isFalse();
        assertThat(summary.publicationStatus()).isNull();
    }
}

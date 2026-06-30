package com.apimarketplace.orchestrator.trigger.queue;

import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("ExecutionQueueClaimStore - Postgres")
class ExecutionQueueClaimStorePostgresTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcTemplate jdbc;
    static JdbcExecutionQueueClaimStore store;

    @BeforeAll
    static void setUpClass() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available - ExecutionQueueClaimStorePostgresTest skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        store = new JdbcExecutionQueueClaimStore(jdbc, new ObjectMapper());

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS orchestrator");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS orchestrator.workflow_execution_queue_claims (
                    request_id VARCHAR(128) PRIMARY KEY,
                    run_id_public VARCHAR(255) NOT NULL,
                    trigger_id VARCHAR(255),
                    trigger_type VARCHAR(64),
                    status VARCHAR(32) NOT NULL,
                    claimed_by VARCHAR(255),
                    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    epoch INTEGER,
                    result JSONB,
                    message TEXT,
                    completed_at TIMESTAMPTZ,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
    }

    @BeforeEach
    void cleanRows() {
        jdbc.execute("TRUNCATE orchestrator.workflow_execution_queue_claims");
    }

    @Test
    @DisplayName("Final claim preserves epoch and JSONB result for duplicate deliveries")
    void finalClaimPreservesEpochAndResultForDuplicateDeliveries() {
        QueuedExecutionMessage message = message("req-final");

        ExecutionQueueClaimStore.ClaimRecord initial = store.claimForExecution(message, "owner-a");
        store.markEpochStarted(message.requestId(), message.runIdPublic(), message.triggerId(), 7);
        TriggerExecutionResult result = TriggerExecutionResult.success(
                message.runIdPublic(), message.triggerId(), TriggerType.MANUAL, Set.of("node:ready"), 7);
        store.complete(message, ExecutionQueueClaimStore.STATUS_DONE, result);

        ExecutionQueueClaimStore.ClaimRecord duplicate = store.claimForExecution(message, "owner-b");

        assertThat(initial.newlyClaimed()).isTrue();
        assertThat(duplicate.isFinal()).isTrue();
        assertThat(duplicate.epoch()).isEqualTo(7);
        assertThat(duplicate.result()).isEqualTo(result);
    }

    @Test
    @DisplayName("Purge deletes only old completed claims")
    void purgeDeletesOnlyOldCompletedClaims() {
        QueuedExecutionMessage oldCompleted = message("req-old-completed");
        QueuedExecutionMessage recentCompleted = message("req-recent-completed");
        QueuedExecutionMessage running = message("req-running");

        store.claimForExecution(oldCompleted, "owner-a");
        store.complete(oldCompleted, ExecutionQueueClaimStore.STATUS_DONE,
                TriggerExecutionResult.success(oldCompleted.runIdPublic(), oldCompleted.triggerId(),
                        TriggerType.MANUAL, Set.of(), 1));
        jdbc.update("""
                UPDATE orchestrator.workflow_execution_queue_claims
                SET completed_at = now() - interval '8 days'
                WHERE request_id = ?
                """, oldCompleted.requestId());

        store.claimForExecution(recentCompleted, "owner-b");
        store.complete(recentCompleted, ExecutionQueueClaimStore.STATUS_DONE,
                TriggerExecutionResult.success(recentCompleted.runIdPublic(), recentCompleted.triggerId(),
                        TriggerType.MANUAL, Set.of(), 1));
        store.claimForExecution(running, "owner-c");

        int purged = store.purgeCompletedBefore(Instant.now().minusSeconds(7 * 24 * 3600), 10);

        assertThat(purged).isEqualTo(1);
        assertThat(rowExists(oldCompleted.requestId())).isFalse();
        assertThat(rowExists(recentCompleted.requestId())).isTrue();
        assertThat(rowExists(running.requestId())).isTrue();
    }

    private boolean rowExists(String requestId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM orchestrator.workflow_execution_queue_claims WHERE request_id = ?",
                Integer.class,
                requestId);
        return count != null && count > 0;
    }

    private static QueuedExecutionMessage message(String requestId) {
        return new QueuedExecutionMessage(
                QueuedExecutionMessage.SCHEMA_VERSION,
                requestId,
                "run-" + requestId,
                "trigger:manual",
                TriggerType.MANUAL,
                Map.of("source", "test"),
                "PRO",
                1,
                "tenant-1",
                "org-1",
                "OWNER",
                false,
                Instant.parse("2026-05-27T10:00:00Z"),
                Instant.parse("2026-05-27T10:05:00Z"));
    }
}

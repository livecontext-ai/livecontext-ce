package com.apimarketplace.agent.repository;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres regression tests for the two task-system CAS claims whose
 * correctness the whole double-fire / double-claim story rests on. Both are
 * single conditional UPDATEs; the invariant pinned here is "two claims over
 * the same row yield exactly one winner" against real Postgres semantics.
 *
 * <ul>
 *   <li>{@code AgentTaskRecurrenceRepository.claimFireSlot} - guards recurrence
 *       double-fire when ShedLock's {@code lockAtMostFor} expires and a second
 *       instance re-fetches the same due row (structural mirror of the JPQL).</li>
 *   <li>{@code AgentTaskRepository.claimIfAvailableByOrganizationId} - guards
 *       two agents claiming the same backlog task (structural mirror of the JPQL).</li>
 * </ul>
 *
 * <p>Skipped without Docker.
 */
@Testcontainers
@DisplayName("Task-system CAS claims - one winner per slot on real Postgres")
class AgentTaskCasClaimPostgresIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ORG_ID = "00000000-0000-0000-0000-000000000000";
    private static final String TENANT_ID = "1";

    // Structural mirror of AgentTaskRecurrenceRepository#claimFireSlot (JPQL over
    // agent.agent_task_recurrences): advance the tracker only when next_fire_at
    // still equals the value the caller read from findDue.
    private static final String CLAIM_FIRE_SLOT_SQL = """
        UPDATE agent.agent_task_recurrences
           SET next_fire_at = ?,
               last_fired_at = ?,
               fire_count = fire_count + 1
         WHERE id = ?
           AND next_fire_at = ?
    """;

    // Structural mirror of AgentTaskRepository#claimIfAvailableByOrganizationId
    // (JPQL over agent.agent_tasks): claim a backlog task only while unassigned
    // and still pending.
    private static final String CLAIM_TASK_SQL = """
        UPDATE agent.agent_tasks
           SET assigned_to_agent_id = ?,
               status = 'in_progress',
               started_at = now(),
               updated_at = now()
         WHERE id = ?
           AND organization_id = ?
           AND assigned_to_agent_id IS NULL
           AND status = 'pending'
    """;

    static JdbcTemplate jdbc;

    @BeforeAll
    static void setUpClass() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available - AgentTaskCasClaimPostgresIT skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS agent");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS agent.agent_task_recurrences (
                id UUID PRIMARY KEY,
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255),
                next_fire_at TIMESTAMPTZ NOT NULL,
                last_fired_at TIMESTAMPTZ,
                fire_count INT NOT NULL DEFAULT 0
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS agent.agent_tasks (
                id UUID PRIMARY KEY,
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255),
                assigned_to_agent_id UUID,
                status VARCHAR(40) NOT NULL,
                started_at TIMESTAMPTZ,
                updated_at TIMESTAMPTZ
            )
        """);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE agent.agent_task_recurrences, agent.agent_tasks");
    }

    @Test
    @DisplayName("claimFireSlot: second claim with the same expected next_fire_at matches 0 rows (no double-fire)")
    void secondFireSlotClaimLoses() {
        UUID id = UUID.randomUUID();
        Instant due = Instant.parse("2026-07-02T10:00:00Z");
        Instant next = Instant.parse("2026-07-02T11:00:00Z");
        Instant now = Instant.parse("2026-07-02T10:00:05Z");
        jdbc.update("INSERT INTO agent.agent_task_recurrences (id, tenant_id, organization_id, next_fire_at, fire_count) VALUES (?, ?, ?, ?, 7)",
                id, TENANT_ID, ORG_ID, Timestamp.from(due));

        int first = jdbc.update(CLAIM_FIRE_SLOT_SQL,
                Timestamp.from(next), Timestamp.from(now), id, Timestamp.from(due));
        int second = jdbc.update(CLAIM_FIRE_SLOT_SQL,
                Timestamp.from(next), Timestamp.from(now), id, Timestamp.from(due));

        assertThat(first).as("first claim wins").isEqualTo(1);
        assertThat(second).as("second claim over the SAME slot must lose").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT fire_count FROM agent.agent_task_recurrences WHERE id = ?", Integer.class, id))
                .as("fire tracker advanced exactly once")
                .isEqualTo(8);
    }

    @Test
    @DisplayName("claimFireSlot: a claim for the NEXT slot (new expected value) wins again")
    void nextSlotClaimWins() {
        UUID id = UUID.randomUUID();
        Instant due = Instant.parse("2026-07-02T10:00:00Z");
        Instant next = Instant.parse("2026-07-02T11:00:00Z");
        Instant afterNext = Instant.parse("2026-07-02T12:00:00Z");
        jdbc.update("INSERT INTO agent.agent_task_recurrences (id, tenant_id, organization_id, next_fire_at, fire_count) VALUES (?, ?, ?, ?, 0)",
                id, TENANT_ID, ORG_ID, Timestamp.from(due));

        jdbc.update(CLAIM_FIRE_SLOT_SQL,
                Timestamp.from(next), Timestamp.from(due), id, Timestamp.from(due));
        int nextClaim = jdbc.update(CLAIM_FIRE_SLOT_SQL,
                Timestamp.from(afterNext), Timestamp.from(next), id, Timestamp.from(next));

        assertThat(nextClaim).as("the legitimate next-window fire still works").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT fire_count FROM agent.agent_task_recurrences WHERE id = ?", Integer.class, id))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("claimIfAvailable: two agents claiming the same backlog task yield exactly one winner")
    void backlogClaimHasSingleWinner() {
        UUID taskId = UUID.randomUUID();
        UUID agentA = UUID.randomUUID();
        UUID agentB = UUID.randomUUID();
        jdbc.update("INSERT INTO agent.agent_tasks (id, tenant_id, organization_id, status) VALUES (?, ?, ?, 'pending')",
                taskId, TENANT_ID, ORG_ID);

        int a = jdbc.update(CLAIM_TASK_SQL, agentA, taskId, ORG_ID);
        int b = jdbc.update(CLAIM_TASK_SQL, agentB, taskId, ORG_ID);

        assertThat(a).as("first claimer wins").isEqualTo(1);
        assertThat(b).as("second claimer must lose (task no longer unassigned)").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT assigned_to_agent_id FROM agent.agent_tasks WHERE id = ?", UUID.class, taskId))
                .as("winner keeps the task")
                .isEqualTo(agentA);
    }
}

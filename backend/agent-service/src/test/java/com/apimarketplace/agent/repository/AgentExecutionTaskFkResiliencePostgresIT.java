package com.apimarketplace.agent.repository;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-Postgres regression for the {@code agent_executions.task_id} FK-violation found in the
 * 2026-06-24 task-system live run: a task can be hard-deleted between agent dispatch and the
 * end-of-run observability record, so the recording INSERT carries a now-gone {@code task_id} and
 * violates {@code fk_agent_executions_task_id} - aborting the recording transaction and cascading
 * into the metrics/storage writes.
 *
 * <p>The migration contract (V72 nullable {@code task_id} + V280 {@code ON DELETE SET NULL}) already
 * handles the delete-AFTER-insert case (deleting a task NULLs existing executions' link). It does
 * NOT cover the insert-AFTER-delete race. The fix
 * ({@code AgentObservabilityService.doRecordFromRequest}) guards the INSERT with
 * {@link AgentTaskRepository#lockTaskRowIfExists(UUID)}: it takes the same {@code FOR KEY SHARE} lock
 * the FK INSERT would, so an existing task cannot be deleted between the check and the INSERT (the
 * race is closed); a gone task returns no row and the recorder NULLs the denormalised {@code task_id}
 * (a NULL link is the intended post-delete state).
 *
 * <p>This IT pins, against a real V72/V280 schema slice:
 * <ul>
 *   <li>the {@code lockTaskRowIfExists} SQL primitive the guard relies on (present -> row, gone -> none),</li>
 *   <li>that an unguarded INSERT for an absent task DOES violate the FK (the bug condition),</li>
 *   <li>that {@code ON DELETE SET NULL} NULLs an existing link on task delete,</li>
 *   <li>that the guard's outcome (INSERT with {@code task_id=NULL}) is accepted.</li>
 * </ul>
 * The service-level wiring (the recorder actually calling the guard) is proven against real Postgres
 * by the CE task suite re-run (docs/ce-qa-live-20260624/task-testing.md), which shows zero FK
 * violations post-fix.
 *
 * <p>Skipped without Docker.
 */
@Testcontainers
@DisplayName("agent_executions.task_id FK resilience - insert-after-task-delete is guarded, not a violation")
class AgentExecutionTaskFkResiliencePostgresIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String TENANT_ID = "1";

    // Structural mirror of AgentTaskRepository#lockTaskRowIfExists @Query body - pins the
    // presence semantics the recorder guard depends on. (The FOR KEY SHARE row-lock that closes the
    // delete-race is a Postgres guarantee held until commit; under JdbcTemplate autocommit here the
    // lock releases immediately, so this exercises presence, not the cross-transaction lock.)
    private static final String LOCK_TASK_ROW_IF_EXISTS_SQL =
            "SELECT 1 FROM agent.agent_tasks WHERE id = ? FOR KEY SHARE";

    static JdbcTemplate jdbc;

    @BeforeAll
    static void setUpClass() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available - AgentExecutionTaskFkResiliencePostgresIT skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA agent");
        jdbc.execute("CREATE TABLE agent.agent_tasks ("
                + "  id UUID PRIMARY KEY,"
                + "  tenant_id VARCHAR(255) NOT NULL,"
                + "  status VARCHAR(32) NOT NULL"
                + ")");
        // V72: task_id added nullable. V280: FK to agent_tasks ON DELETE SET NULL.
        jdbc.execute("CREATE TABLE agent.agent_executions ("
                + "  id UUID PRIMARY KEY,"
                + "  tenant_id VARCHAR(255) NOT NULL,"
                + "  task_id UUID,"
                + "  status VARCHAR(32) NOT NULL,"
                + "  CONSTRAINT fk_agent_executions_task_id FOREIGN KEY (task_id)"
                + "    REFERENCES agent.agent_tasks(id) ON DELETE SET NULL"
                + ")");
    }

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM agent.agent_executions");
        jdbc.execute("DELETE FROM agent.agent_tasks");
    }

    @Test
    @DisplayName("lockTaskRowIfExists SQL: a row (->lock) for a present task, none after delete and for an absent id")
    void lockTaskRowIfExistsReflectsPresenceAndDeletion() {
        UUID taskId = UUID.randomUUID();
        insertTask(taskId);

        assertThat(taskRowLockable(taskId)).isTrue();

        deleteTask(taskId);
        assertThat(taskRowLockable(taskId)).isFalse();
        assertThat(taskRowLockable(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("BUG condition: inserting an execution for an absent task violates fk_agent_executions_task_id")
    void insertingExecutionForAbsentTaskViolatesFk() {
        UUID goneTaskId = UUID.randomUUID(); // never inserted

        assertThatThrownBy(() -> insertExecution(UUID.randomUUID(), goneTaskId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_agent_executions_task_id");
    }

    @Test
    @DisplayName("ON DELETE SET NULL: deleting a task NULLs the link on an EXISTING execution (delete-after-insert path)")
    void deletingTaskNullsExistingExecutionLink() {
        UUID taskId = UUID.randomUUID();
        UUID execId = UUID.randomUUID();
        insertTask(taskId);
        insertExecution(execId, taskId);

        deleteTask(taskId);

        UUID linkedAfter = jdbc.queryForObject(
                "SELECT task_id FROM agent.agent_executions WHERE id = ?", UUID.class, execId);
        assertThat(linkedAfter).isNull();
    }

    @Test
    @DisplayName("GUARD outcome: recording with task_id=NULL (what the guard does when the task is gone) is accepted")
    void guardedInsertWithNullTaskIdSucceeds() {
        UUID execId = UUID.randomUUID();

        // The recorder guard sets task_id=null once lockTaskRowIfExists reports the task is gone.
        insertExecution(execId, null);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agent.agent_executions WHERE id = ? AND task_id IS NULL",
                Integer.class, execId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("RACE CLOSED: FOR KEY SHARE on the task blocks a concurrent delete until the recorder commits, then ON DELETE SET NULL fires")
    void forKeyShareLockBlocksConcurrentDeleteUntilCommit() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID execId = UUID.randomUUID();
        insertTask(taskId);
        insertExecution(execId, taskId);

        try (Connection recorder = newConnection(); Connection deleter = newConnection()) {
            // T1 (the recorder) takes the FOR KEY SHARE lock exactly as the guard does, and holds the
            // transaction open - simulating the window between the lock-check and the execution INSERT.
            recorder.setAutoCommit(false);
            try (PreparedStatement lock = recorder.prepareStatement(LOCK_TASK_ROW_IF_EXISTS_SQL)) {
                lock.setObject(1, taskId);
                try (ResultSet rs = lock.executeQuery()) {
                    assertThat(rs.next()).as("FOR KEY SHARE returns the row for a present task").isTrue();
                }
            }

            // T2 (a concurrent task delete) MUST block on T1's lock; prove it with a short lock_timeout.
            deleter.setAutoCommit(true);
            try (Statement s = deleter.createStatement()) {
                s.execute("SET lock_timeout = '750ms'");
            }
            assertThatThrownBy(() -> {
                try (PreparedStatement del = deleter.prepareStatement("DELETE FROM agent.agent_tasks WHERE id = ?")) {
                    del.setObject(1, taskId);
                    del.executeUpdate();
                }
            }).as("the delete is blocked by the recorder's FOR KEY SHARE lock")
              .hasMessageContaining("lock timeout");

            // The execution row therefore still links a present task (no FK violation possible).
            assertThat(taskRowLockable(taskId)).isTrue();

            // T1 commits -> the share-lock is released.
            recorder.commit();

            // Now the delete succeeds and ON DELETE SET NULL nulls the (committed) execution link.
            try (Statement s = deleter.createStatement()) {
                s.execute("SET lock_timeout = '5s'");
            }
            try (PreparedStatement del = deleter.prepareStatement("DELETE FROM agent.agent_tasks WHERE id = ?")) {
                del.setObject(1, taskId);
                assertThat(del.executeUpdate()).isEqualTo(1);
            }
        }

        UUID linkAfter = jdbc.queryForObject(
                "SELECT task_id FROM agent.agent_executions WHERE id = ?", UUID.class, execId);
        assertThat(linkAfter).as("ON DELETE SET NULL fired once the lock released").isNull();
    }

    private static Connection newConnection() throws java.sql.SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private boolean taskRowLockable(UUID taskId) {
        // Mirrors AgentTaskRepository#lockTaskRowIfExists: returns a row (Integer 1) iff the task
        // exists (the recorder treats a returned row as "exists + locked", a null as "gone").
        return !jdbc.queryForList(LOCK_TASK_ROW_IF_EXISTS_SQL, Integer.class, taskId).isEmpty();
    }

    private void insertTask(UUID id) {
        jdbc.update("INSERT INTO agent.agent_tasks (id, tenant_id, status) VALUES (?, ?, 'pending')",
                id, TENANT_ID);
    }

    private void deleteTask(UUID id) {
        jdbc.update("DELETE FROM agent.agent_tasks WHERE id = ?", id);
    }

    private void insertExecution(UUID id, UUID taskId) {
        jdbc.update("INSERT INTO agent.agent_executions (id, tenant_id, task_id, status) VALUES (?, ?, ?, 'FAILED')",
                id, TENANT_ID, taskId);
    }
}

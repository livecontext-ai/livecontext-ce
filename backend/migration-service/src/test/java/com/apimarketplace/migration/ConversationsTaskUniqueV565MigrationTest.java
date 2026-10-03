package com.apimarketplace.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V565 makes a RESTRICTED delegated task's conversation unique per (workspace, agent, task) among
 * ACTIVE rows (CASA LC-066), after soft-deleting the duplicates the find-then-insert race already
 * left. Replayed by Flyway against a real Postgres with such duplicates, so "the oldest survives,
 * the index is valid, a second active insert is refused, a soft-deleted one is not" is exercised,
 * not read off the SQL.
 */
@DisplayName("V565 unique active task conversation per (organization, agent, task)")
class ConversationsTaskUniqueV565MigrationTest {

    private static final String DB = "conversations_task_unique_v565";
    private static final String MIGRATION = "V565__conversations_task_unique.sql";

    private static final String SCHEMA_AND_ROWS = """
            CREATE SCHEMA conversation;
            CREATE TABLE conversation.conversations (
                id VARCHAR(255) PRIMARY KEY,
                organization_id VARCHAR(64),
                agent_id VARCHAR(255),
                task_id VARCHAR(255),
                active BOOLEAN NOT NULL DEFAULT TRUE,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            INSERT INTO conversation.conversations (id, organization_id, agent_id, task_id, active, created_at) VALUES
                ('c-oldest', 'org-1', 'agent-1', 'task-1', TRUE, '2026-09-01 10:00:00'),
                ('c-dup',    'org-1', 'agent-1', 'task-1', TRUE, '2026-09-01 10:00:01'),
                ('c-other-agent', 'org-1', 'agent-2', 'task-1', TRUE, '2026-09-01 10:00:02'),
                ('c-main-1', 'org-1', 'agent-1', NULL, TRUE, '2026-09-01 09:00:00'),
                ('c-main-2', 'org-1', 'agent-1', NULL, TRUE, '2026-09-01 09:00:01');
            """;

    @Test
    @DisplayName("keeps the oldest duplicate active, soft-deletes the others, leaves main conversations alone, builds a valid unique index")
    void dedupesThenEnforces(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);
            assertThatCode(() -> postgres.runFlyway(DB, tempDir)).doesNotThrowAnyException();

            assertThat(scalar(postgres, "SELECT string_agg(id, ',' ORDER BY id) FROM conversation.conversations "
                    + "WHERE active")).isEqualTo("c-main-1,c-main-2,c-oldest,c-other-agent");
            assertThat(scalar(postgres, "SELECT count(*)::text FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                    + "WHERE c.relname = 'uq_conversations_task_per_agent' AND i.indisvalid AND i.indisunique"))
                    .isEqualTo("1");

            // The race the index closes: a second ACTIVE conversation for the same task and agent.
            assertThatThrownBy(() -> execute(postgres, "INSERT INTO conversation.conversations "
                    + "(id, organization_id, agent_id, task_id) VALUES ('c-race', 'org-1', 'agent-1', 'task-1')"))
                    .hasMessageContaining("uq_conversations_task_per_agent");
            // A soft-deleted one, another workspace, or a main conversation is not a duplicate.
            assertThatCode(() -> execute(postgres, "INSERT INTO conversation.conversations "
                    + "(id, organization_id, agent_id, task_id, active) VALUES ('c-deleted', 'org-1', 'agent-1', 'task-1', FALSE)"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(postgres, "INSERT INTO conversation.conversations "
                    + "(id, organization_id, agent_id, task_id) VALUES ('c-org-2', 'org-2', 'agent-1', 'task-1')"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> execute(postgres, "INSERT INTO conversation.conversations "
                    + "(id, organization_id, agent_id, task_id) VALUES ('c-main-3', 'org-1', 'agent-1', NULL)"))
                    .doesNotThrowAnyException();
        }
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__production_shapes.sql"), SCHEMA_AND_ROWS);
        Files.copy(Path.of("src/main/resources/db/migration/" + MIGRATION), directory.resolve(MIGRATION));
    }

    private static String scalar(FlywayTestSupport.PostgresTarget p, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(p.jdbcUrl(DB), p.username(), p.password());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void execute(FlywayTestSupport.PostgresTarget p, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(p.jdbcUrl(DB), p.username(), p.password());
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}

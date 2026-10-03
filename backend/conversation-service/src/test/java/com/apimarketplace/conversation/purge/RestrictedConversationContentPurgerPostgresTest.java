package com.apimarketplace.conversation.purge;

import com.apimarketplace.testsupport.ScratchPostgres;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The restricted-content purge and backfill of conversation-service on real Postgres (CASA LC-011 /
 * LC-066). A mocked JdbcTemplate cannot tell whether a data-modifying CTE, a {@code RETURNING}
 * feeding a second UPDATE or a {@code String[]} bound to {@code CAST(? AS varchar[])} actually runs.
 *
 * <p>Each test runs in ONE transaction that is rolled back afterwards, schema included (DDL is
 * transactional in Postgres): the scratch database is shared with other classes of the same CI job,
 * and a {@code conversation} schema left behind, or one of theirs dropped, would break them.
 * Runs against {@code CONVERSATION_TEST_PG_URL}; skipped on a laptop without it, never on CI
 * (see {@link ScratchPostgres}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Restricted conversation purge and backfill (real Postgres)")
class RestrictedConversationContentPurgerPostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CONVERSATION_TEST_PG",
            "it is the only proof that the conversation purge's SQL runs and clears the summary with the rows");

    private Connection connection;
    private JdbcTemplate jdbc;

    @BeforeAll
    void requireDatabase() {
        DB.require();
    }

    @BeforeEach
    void openRolledBackSchema() throws SQLException {
        connection = DriverManager.getConnection(DB.url(), DB.user(), DB.password());
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        // Only the columns the purge and the backfill read or write, with their production types.
        jdbc.execute("DROP SCHEMA IF EXISTS conversation CASCADE");
        jdbc.execute("CREATE SCHEMA conversation");
        jdbc.execute("""
                CREATE TABLE conversation.conversations (
                    id           VARCHAR(255) PRIMARY KEY,
                    summary_cold JSONB
                )""");
        jdbc.execute("""
                CREATE TABLE conversation.tool_results (
                    id               UUID PRIMARY KEY,
                    conversation_id  VARCHAR(255) NOT NULL REFERENCES conversation.conversations(id),
                    content_full     TEXT,
                    error_message    TEXT,
                    content_preview  TEXT,
                    metadata         JSONB,
                    data_sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
                    created_at       TIMESTAMP NOT NULL DEFAULT now()
                )""");
        jdbc.execute("""
                CREATE TABLE conversation.messages (
                    id               VARCHAR(255) PRIMARY KEY,
                    conversation_id  VARCHAR(255) NOT NULL REFERENCES conversation.conversations(id),
                    role             VARCHAR(20) NOT NULL,
                    content          TEXT,
                    tool_calls       TEXT,
                    data_sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
                    created_at       TIMESTAMP NOT NULL DEFAULT now()
                )""");
    }

    @AfterEach
    void rollBack() throws SQLException {
        connection.rollback();
        connection.close();
    }

    private void conversation(String id, boolean withSummary) {
        jdbc.update("INSERT INTO conversation.conversations (id, summary_cold) VALUES (?, CAST(? AS jsonb))",
                id, withSummary ? "{\"decisions\":[\"wire 45000 EUR\"],\"turns_covered\":[0,1]}" : null);
    }

    private UUID toolResult(String conversationId, String sensitivity, int daysOld, String metadataJson) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO conversation.tool_results (id, conversation_id, content_full, metadata, "
                        + "data_sensitivity, created_at) VALUES (?, ?, 'From: ceo@corp.test wire 45000 EUR', "
                        + "CAST(? AS jsonb), ?, now() - make_interval(days => ?))",
                id, conversationId, metadataJson, sensitivity, daysOld);
        return id;
    }

    private String message(String conversationId, String role, String sensitivity, int daysOld) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO conversation.messages (id, conversation_id, role, content, tool_calls, "
                        + "data_sensitivity, created_at) VALUES (?, ?, ?, 'the CEO asked for a wire', "
                        + "'[{\"id\":\"c1\",\"iconSlug\":\"gmail\",\"arguments\":\"from:ceo\"}]', ?, "
                        + "now() - make_interval(days => ?))",
                id, conversationId, role, sensitivity, daysOld);
        return id;
    }

    private String summaryOf(String conversationId) {
        return jdbc.queryForObject("SELECT summary_cold::text FROM conversation.conversations WHERE id = ?",
                String.class, conversationId);
    }

    private String sensitivityOf(String table, Object id) {
        return jdbc.queryForObject("SELECT data_sensitivity FROM conversation." + table + " WHERE id = ?",
                String.class, id);
    }

    private RestrictedConversationContentPurger purger(int batchSize) {
        return new RestrictedConversationContentPurger(jdbc, new ObjectMapper(), true, 30, batchSize);
    }

    @Test
    @DisplayName("regression: old restricted rows are redacted and their conversation loses its summary in the same pass")
    void redactsAndClearsTheSummary() {
        conversation("conv-gmail", true);
        conversation("conv-plain", true);
        conversation("conv-recent", true);
        UUID oldResult = toolResult("conv-gmail", "RESTRICTED", 40, "{\"iconSlug\":\"gmail\",\"toolName\":\"list\"}");
        String oldMessage = message("conv-gmail", "ASSISTANT", "RESTRICTED", 40);
        toolResult("conv-plain", "NORMAL", 40, "{\"iconSlug\":\"github\"}");
        UUID recent = toolResult("conv-recent", "RESTRICTED", 5, "{\"iconSlug\":\"gmail\"}");

        RestrictedConversationContentPurger.PurgeReport report = purger(500).purge(Instant.now());

        assertThat(report.toolResultsRedacted()).isEqualTo(1);
        assertThat(report.messagesRedacted()).isEqualTo(1);
        assertThat(report.summariesCleared()).isEqualTo(1);
        assertThat(sensitivityOf("tool_results", oldResult)).isEqualTo("REDACTED");
        assertThat(jdbc.queryForObject("SELECT content_full FROM conversation.tool_results WHERE id = ?",
                String.class, oldResult)).isNull();
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM conversation.tool_results WHERE id = ?",
                String.class, oldResult)).contains("\"iconSlug\": \"gmail\"").contains("contentRedacted");
        assertThat(sensitivityOf("messages", oldMessage)).isEqualTo("REDACTED");
        assertThat(jdbc.queryForObject("SELECT content FROM conversation.messages WHERE id = ?",
                String.class, oldMessage)).isEqualTo(RestrictedConversationContentPurger.PLACEHOLDER);
        assertThat(jdbc.queryForObject("SELECT tool_calls FROM conversation.messages WHERE id = ?",
                String.class, oldMessage)).doesNotContain("from:ceo");
        assertThat(summaryOf("conv-gmail")).isNull();
        // Nothing of theirs was redacted, so their summaries stay.
        assertThat(summaryOf("conv-plain")).isNotNull();
        assertThat(summaryOf("conv-recent")).isNotNull();
        assertThat(sensitivityOf("tool_results", recent)).isEqualTo("RESTRICTED");
    }

    @Test
    @DisplayName("across several batches every redacted conversation loses its summary")
    void multipleBatches() {
        conversation("conv-a", true);
        conversation("conv-b", true);
        toolResult("conv-a", "RESTRICTED", 40, "{\"iconSlug\":\"gmail\"}");
        toolResult("conv-a", "RESTRICTED", 41, "{\"iconSlug\":\"gmail\"}");
        toolResult("conv-b", "RESTRICTED", 42, "{\"iconSlug\":\"googledrive\"}");

        RestrictedConversationContentPurger.PurgeReport report = purger(1).purge(Instant.now());

        assertThat(report.toolResultsRedacted()).isEqualTo(3);
        assertThat(report.summariesCleared()).isEqualTo(2);
        assertThat(summaryOf("conv-a")).isNull();
        assertThat(summaryOf("conv-b")).isNull();
    }

    @Test
    @DisplayName("a second pass finds nothing: redacted rows and cleared summaries are not touched again")
    void idempotent() {
        conversation("conv-a", true);
        toolResult("conv-a", "RESTRICTED", 40, "{\"iconSlug\":\"gmail\"}");
        purger(500).purge(Instant.now());
        // A summary rebuilt from the redacted history after the first pass.
        jdbc.update("UPDATE conversation.conversations SET summary_cold = '{\"decisions\":[]}' WHERE id = 'conv-a'");

        RestrictedConversationContentPurger.PurgeReport second = purger(500).purge(Instant.now());

        assertThat(second.toolResultsRedacted()).isZero();
        assertThat(second.summariesCleared()).isZero();
        assertThat(summaryOf("conv-a")).isNotNull();
    }

    @Test
    @DisplayName("the backfill's array-bound batches run: a Gmail tool result and the assistant turn after it are tagged")
    void backfillTagsOnRealPostgres() {
        conversation("conv-legacy", false);
        String userTurn = message("conv-legacy", "USER", "NORMAL", 3);
        UUID gmail = toolResult("conv-legacy", "NORMAL", 2, "{\"iconSlug\":\"gmail\"}");
        String assistantTurn = message("conv-legacy", "ASSISTANT", "NORMAL", 1);
        jdbc.update("UPDATE conversation.messages SET tool_calls = NULL WHERE id IN (?, ?)", userTurn, assistantTurn);

        RestrictedConversationBackfill.Report report = new RestrictedConversationBackfill(jdbc, true, 1).run();

        assertThat(report.toolResults()).isEqualTo(1);
        assertThat(report.messages()).isEqualTo(1);
        assertThat(sensitivityOf("tool_results", gmail)).isEqualTo("RESTRICTED");
        assertThat(sensitivityOf("messages", assistantTurn)).isEqualTo("RESTRICTED");
        assertThat(sensitivityOf("messages", userTurn)).isEqualTo("NORMAL");
    }
}

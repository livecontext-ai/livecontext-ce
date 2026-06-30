package com.apimarketplace.conversation.repository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the FK-resilience contract that {@link ConversationRepository#lockConversationRowIfExists}
 * + {@code StreamService.createStream} rely on, against a real PostgreSQL 16 - the
 * {@code streams_conversation_id_fkey} guard that stops a chat sent to a deleted / never-persisted
 * conversation from turning the {@code conversation.streams} INSERT into an unhandled
 * DataIntegrityViolation (a full FK stacktrace + an aborted side-transaction).
 *
 * <p>The FK + the lock primitive are pure SQL, so this exercises them via JDBC directly (the schema
 * is migration-defined, not entity-mapped, so an hbm2ddl slice would not even create the constraint).
 *
 * <p>Regression coverage (each fails / is meaningless on the pre-guard code path):
 * <ul>
 *   <li>{@code FOR KEY SHARE} returns a row iff the conversation exists - the exact signal the guard
 *       reads to decide skip-vs-insert.</li>
 *   <li>inserting a stream for an absent conversation really does violate
 *       {@code streams_conversation_id_fkey} (the bug being prevented).</li>
 *   <li>a guarded insert (conversation present) succeeds.</li>
 *   <li>the {@code FOR KEY SHARE} lock BLOCKS a concurrent conversation delete until the holder
 *       commits - so the check-then-insert window is closed, not merely narrowed.</li>
 * </ul>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("conversation.streams FK resilience (FOR KEY SHARE) against real Postgres")
class StreamConversationFkResiliencePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("stream_fk_test")
            .withUsername("test")
            .withPassword("test");

    private static final String LOCK_SQL =
            "SELECT 1 FROM conversation.conversations WHERE id = ? FOR KEY SHARE";

    @BeforeAll
    void createSchema() throws SQLException {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS conversation");
            st.execute("CREATE TABLE conversation.conversations (" +
                    "  id varchar(255) PRIMARY KEY," +
                    "  user_id varchar(255)," +
                    "  organization_id varchar(255))");
            // ON DELETE CASCADE mirrors production (migration V10). It does not affect what these
            // tests assert (insert-time FK check + the FOR KEY SHARE lock), but keeps the schema
            // faithful so the constraint name/behaviour matches the real table.
            st.execute("CREATE TABLE conversation.streams (" +
                    "  id varchar(255) PRIMARY KEY," +
                    "  conversation_id varchar(255) NOT NULL," +
                    "  stream_id varchar(255)," +
                    "  user_id varchar(255)," +
                    "  status varchar(50)," +
                    "  CONSTRAINT streams_conversation_id_fkey FOREIGN KEY (conversation_id)" +
                    "    REFERENCES conversation.conversations(id) ON DELETE CASCADE)");
        }
    }

    @BeforeEach
    void clean() throws SQLException {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM conversation.streams");
            st.execute("DELETE FROM conversation.conversations");
        }
    }

    @AfterAll
    void noop() { /* container stops via @Container lifecycle */ }

    private static Connection conn() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void insertConversation(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO conversation.conversations (id, user_id, organization_id) VALUES (?, 'u1', 'o1')")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    private boolean lockReturnsRow(Connection c, String conversationId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(LOCK_SQL)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Test
    @DisplayName("FOR KEY SHARE returns a row iff the conversation exists (the guard's skip-vs-insert signal)")
    void lockReflectsPresenceAndAbsence() throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            assertThat(lockReturnsRow(c, "missing")).isFalse();
            insertConversation(c, "conv-1");
            c.commit();
            assertThat(lockReturnsRow(c, "conv-1")).isTrue();
            c.commit();
        }
    }

    @Test
    @DisplayName("inserting a stream for an absent conversation violates streams_conversation_id_fkey")
    void insertingStreamForAbsentConversationViolatesFk() throws SQLException {
        try (Connection c = conn()) {
            SQLException thrown = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO conversation.streams (id, conversation_id, stream_id, status) " +
                            "VALUES ('s1', 'no-such-conversation', 'stream-1', 'ACTIVE')")) {
                ps.executeUpdate();
            } catch (SQLException e) {
                thrown = e;
            }
            // Cast to Throwable: SQLException implements Iterable<Throwable>, so a raw assertThat(thrown)
            // is ambiguous between assertThat(Throwable) and assertThat(Iterable).
            assertThat((Throwable) thrown)
                    .isNotNull()
                    .hasMessageContaining("streams_conversation_id_fkey");
        }
    }

    @Test
    @DisplayName("a guarded insert (conversation present) succeeds")
    void guardedInsertWithPresentConversationSucceeds() throws SQLException {
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            insertConversation(c, "conv-ok");
            // Same order as createStream: lock the parent, then insert the child in the same tx.
            assertThat(lockReturnsRow(c, "conv-ok")).isTrue();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO conversation.streams (id, conversation_id, stream_id, status) " +
                            "VALUES ('s-ok', 'conv-ok', 'stream-ok', 'ACTIVE')")) {
                ps.executeUpdate();
            }
            c.commit();
        }
        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM conversation.streams WHERE conversation_id='conv-ok'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("FOR KEY SHARE blocks a concurrent conversation delete until the holder commits (race closed)")
    void forKeyShareLockBlocksConcurrentDeleteUntilCommit() throws Exception {
        try (Connection seed = conn()) {
            insertConversation(seed, "conv-race");
        }

        Connection holder = conn();
        holder.setAutoCommit(false);
        // T1 takes the same FOR KEY SHARE lock createStream takes, and holds it open.
        assertThat(lockReturnsRow(holder, "conv-race")).isTrue();

        AtomicReference<String> deleteOutcome = new AtomicReference<>();
        CountDownLatch deleteAttempted = new CountDownLatch(1);
        Thread deleter = new Thread(() -> {
            try (Connection c = conn()) {
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    st.execute("SET lock_timeout = '750ms'");
                }
                deleteAttempted.countDown();
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM conversation.conversations WHERE id = 'conv-race'")) {
                    ps.executeUpdate();
                    c.commit();
                    deleteOutcome.set("DELETED");
                } catch (SQLException e) {
                    c.rollback();
                    deleteOutcome.set("BLOCKED:" + e.getMessage());
                }
            } catch (SQLException e) {
                deleteOutcome.set("ERROR:" + e.getMessage());
            }
        });
        deleter.start();
        boolean attempted = deleteAttempted.await(5, TimeUnit.SECONDS);
        assertThat(attempted).isTrue();
        deleter.join(5000);

        // While T1 held the share lock, T2's delete could not proceed - it hit the lock timeout.
        assertThat(deleteOutcome.get()).startsWith("BLOCKED:");

        // Releasing the lock lets the delete through (no streams reference it).
        holder.commit();
        holder.close();
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM conversation.conversations WHERE id = 'conv-race'")) {
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }
}

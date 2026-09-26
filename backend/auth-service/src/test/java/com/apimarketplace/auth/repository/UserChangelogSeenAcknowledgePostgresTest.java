package com.apimarketplace.auth.repository;

import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The changelog acknowledgement SQL, run EXACTLY as shipped (read from the {@code @Query} of
 * {@link UserChangelogSeenRepository#acknowledge}) against the CI scratch Postgres, with the real
 * foreign key to the users table.
 *
 * <p>Regression (prod 2026-09-22): the upsert was a plain {@code INSERT ... VALUES}, so a session
 * whose account had been deleted (the gateway caches user resolution for minutes) tripped
 * {@code user_changelog_seen_user_id_fkey} and the request answered 500. The unit tests mock the
 * repository, so this class is the only thing CI runs that can see the SQL itself: reverting it
 * to {@code VALUES} fails {@link #unknownUserWritesNothingAndRaisesNothing}.
 *
 * <p>The scratch database is shared by several classes, so this one works on PRIVATE copies of
 * the two tables (same pattern as {@code CheckoutStartedThrottlePostgresTest}) and never drops the
 * users table another class may rely on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("UserChangelogSeenRepository.acknowledge - real Postgres, real foreign key")
class UserChangelogSeenAcknowledgePostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CREDENTIAL_TEST_PG",
            "it is the only CI proof that acknowledging the changelog for a deleted user writes "
                    + "nothing instead of failing on the foreign key");

    private static final String USERS = "auth.changelog_ack_users";
    private static final String SEEN = "auth.changelog_ack_seen";

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private String acknowledgeSql;

    @BeforeAll
    void setUpSchema() throws Exception {
        DB.require();
        dataSource = new DriverManagerDataSource(DB.url(), DB.user(), DB.password());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(dataSource);
        named = new NamedParameterJdbcTemplate(dataSource);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("DROP TABLE IF EXISTS " + SEEN + " CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS " + USERS + " CASCADE");
        jdbc.execute("CREATE TABLE " + USERS + " (id BIGINT PRIMARY KEY)");
        // As V480 declares it: one row per user, cascading with the user.
        jdbc.execute("CREATE TABLE " + SEEN + " ("
                + " user_id BIGINT PRIMARY KEY REFERENCES " + USERS + "(id) ON DELETE CASCADE,"
                + " entry_key VARCHAR(64) NOT NULL,"
                + " seen_at TIMESTAMPTZ NOT NULL)");

        String shipped = UserChangelogSeenRepository.class
                .getMethod("acknowledge", Long.class, String.class)
                .getAnnotation(Query.class).value();
        assertThat(shipped).contains("auth.user_changelog_seen").contains("auth.users");
        acknowledgeSql = shipped
                .replace("auth.user_changelog_seen", SEEN)
                .replace("auth.users", USERS);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM " + SEEN);
        jdbc.execute("DELETE FROM " + USERS);
    }

    private int acknowledge(long userId, String key) {
        return named.update(acknowledgeSql,
                new MapSqlParameterSource().addValue("userId", userId).addValue("entryKey", key));
    }

    private String storedKey(long userId) {
        return jdbc.query("SELECT entry_key FROM " + SEEN + " WHERE user_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, userId);
    }

    @Test
    @DisplayName("unknown user: 0 rows, no exception, nothing written (was a foreign-key 500)")
    void unknownUserWritesNothingAndRaisesNothing() {
        int written = acknowledge(424242L, "2026-09-changelog");

        assertThat(written).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SEEN, Integer.class)).isZero();
    }

    @Test
    @DisplayName("existing user: first acknowledgement inserts, the next one updates, one row")
    void existingUserInsertsThenUpdates() {
        jdbc.update("INSERT INTO " + USERS + " (id) VALUES (7)");

        assertThat(acknowledge(7L, "2026-08-changelog")).isEqualTo(1);
        assertThat(acknowledge(7L, "2026-09-changelog")).isEqualTo(1);

        assertThat(storedKey(7L)).isEqualTo("2026-09-changelog");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SEEN, Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("account deleted while the acknowledgement waits: 0 rows, not a foreign-key failure")
    void accountDeletedConcurrentlyIsUnknownUser() throws Exception {
        jdbc.update("INSERT INTO " + USERS + " (id) VALUES (9)");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // A purge holding the user row: delete it, and keep the transaction open.
        CompletableFuture<Void> purge = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM " + USERS + " WHERE id = 9");
            deleted.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(deleted.await(10, TimeUnit.SECONDS)).isTrue();

        // The acknowledgement blocks on the row lock; release the purge so it commits.
        CompletableFuture<Integer> ack = CompletableFuture.supplyAsync(() -> acknowledge(9L, "2026-09-changelog"));
        // Release only once the acknowledgement is really parked on the row lock, so the test
        // exercises the wait-then-re-read path rather than a plain "user already gone" read.
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until && jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%"
                        + SEEN + "%'", Integer.class) == 0) {
            Thread.sleep(20);
        }
        assertThat(ack).as("the acknowledgement must be blocked by the purge's row lock").isNotDone();
        release.countDown();
        purge.get(10, TimeUnit.SECONDS);

        // Old SQL: the foreign-key check failed after the delete committed. New SQL: re-reads, finds no user.
        assertThat(ack.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(storedKey(9L)).isNull();
    }

    @Test
    @DisplayName("UserRepository.lockExistingForKeyShare (the ce-link register guard): id when present, empty when not")
    void keyShareLockQueryAnswersPresence() throws Exception {
        String shipped = UserRepository.class.getMethod("lockExistingForKeyShare", Long.class)
                .getAnnotation(Query.class).value();
        assertThat(shipped).contains("FOR KEY SHARE");
        String sql = shipped.replace("auth.users", USERS);
        jdbc.update("INSERT INTO " + USERS + " (id) VALUES (11)");

        assertThat(named.queryForList(sql, new MapSqlParameterSource("id", 11L), Long.class)).containsExactly(11L);
        assertThat(named.queryForList(sql, new MapSqlParameterSource("id", 12L), Long.class)).isEmpty();
    }
}

package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Replays the REAL V495 against accounts as they exist on the day of the upgrade.
 *
 * <p>The line under test is the seeding {@code UPDATE}, and it is the only part of this
 * change that can go wrong loudly on a dashboard. A login is now counted when a token's
 * {@code auth_time} is newer than {@code last_authenticated_at}. Leave that column NULL and
 * every account that is already signed in looks, on its first request after the deploy,
 * like a brand new authentication: one artificial login per active user, published on the
 * exact graph this migration exists to make truthful.
 *
 * <p>Seeding from {@code last_login_at} suppresses that, and is provably safe rather than
 * merely convenient: a session that is already open authenticated BEFORE it was last seen,
 * so its {@code auth_time} is necessarily older than the seeded value and correctly counts
 * nothing, while the next real sign-in is newer and counts once.
 *
 * <p>Asserts the DATABASE after Flyway runs, never the .sql text. A text assertion would
 * pass against a migration whose {@code WHERE} clause quietly matches no row.
 */
@DisplayName("V495 seeds the authentication instant so the deploy publishes no fake logins")
class LoginAuthenticationInstantMigrationTest {

    private static final String DB = "login_auth_instant_v495";

    private static final String MIGRATION =
            "V495__login_counts_an_authentication_not_a_request.sql";

    /**
     * auth.users trimmed to what V495 reasons about, with the three shapes that exist on
     * upgrade day: signed in recently, signed in long ago, and never signed in at all.
     */
    private static final String SCHEMA_AND_ROWS = """
            CREATE SCHEMA auth;

            CREATE TABLE auth.users (
                id            BIGSERIAL PRIMARY KEY,
                email         VARCHAR(255) NOT NULL,
                last_login_at TIMESTAMPTZ
            );

            INSERT INTO auth.users (email, last_login_at) VALUES
            ('active@test.local',  TIMESTAMPTZ '2026-09-17 07:30:00+00'),
            ('dormant@test.local', TIMESTAMPTZ '2026-03-01 09:00:00+00'),
            ('never@test.local',   NULL);
            """;

    @Test
    @DisplayName("an account that has signed in is seeded from its last-seen timestamp")
    void seedsFromLastLogin(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);

            assertThatCode(() -> postgres.runFlyway(DB, tempDir)).doesNotThrowAnyException();

            // Equal, not merely non-null: the value has to be one that no live session's
            // auth_time can exceed, and last_login_at is the only timestamp with that
            // property. Anything earlier would let an open session count a login.
            assertThat(authenticatedAt(postgres, DB, "active@test.local"))
                    .isEqualTo("2026-09-17 07:30:00+00");
            assertThat(authenticatedAt(postgres, DB, "dormant@test.local"))
                    .isEqualTo("2026-03-01 09:00:00+00");
        }
    }

    @Test
    @DisplayName("an account that never signed in stays NULL, so its first sign-in counts")
    void leavesNeverLoggedInNull(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_never");

            postgres.runFlyway(DB + "_never", tempDir);

            // NULL is what makes the conditional advance accept the first authentication.
            // Seeding it with now() instead would swallow that person's real first login.
            assertThat(authenticatedAt(postgres, DB + "_never", "never@test.local")).isNull();
        }
    }

    @Test
    @DisplayName("re-running is a no-op: the seed never overwrites an instant already recorded")
    void seedIsGuardedAndIdempotent(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_idem");
            postgres.runFlyway(DB + "_idem", tempDir);

            // The account signs in again after the upgrade, so the runtime advances the
            // column past its seeded value.
            execute(postgres, DB + "_idem",
                    "UPDATE auth.users SET last_authenticated_at = TIMESTAMPTZ '2026-09-18 11:00:00+00' "
                            + "WHERE email = 'active@test.local'");
            // Replaying the seed (a repaired install, a restored dump, a re-applied baseline)
            // must not drag it back. That is what the IS NULL guard buys, and a migration
            // without it would silently un-record every login since the upgrade.
            execute(postgres, DB + "_idem", seedStatement());

            assertThat(authenticatedAt(postgres, DB + "_idem", "active@test.local"))
                    .isEqualTo("2026-09-18 11:00:00+00");
        }
    }

    @Test
    @DisplayName("the column is nullable, so the migration cannot fail on a populated table")
    void columnIsNullable(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_null");

            postgres.runFlyway(DB + "_null", tempDir);

            // A NOT NULL column added to auth.users would need a default and would rewrite
            // the whole table under an exclusive lock, on the one table every request reads.
            assertThat(query(postgres, DB + "_null",
                    "SELECT is_nullable FROM information_schema.columns "
                            + "WHERE table_schema = 'auth' AND table_name = 'users' "
                            + "AND column_name = 'last_authenticated_at'"))
                    .isEqualTo("YES");
        }
    }

    /** The seeding statement as V495 writes it, re-read from the file rather than retyped. */
    private static String seedStatement() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/" + MIGRATION));
        int start = sql.lastIndexOf("UPDATE auth.users");
        assertThat(start).as("V495 must still carry its seeding UPDATE").isGreaterThan(-1);
        return sql.substring(start, sql.indexOf(';', start) + 1);
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_auth_users.sql"), SCHEMA_AND_ROWS);
        // The harness reads only this directory, so the real migration is copied in: a test
        // that forgot it would assert against an untouched table and fail loudly, not pass.
        Files.copy(Path.of("src/main/resources/db/migration/" + MIGRATION),
                directory.resolve(MIGRATION));
    }

    private static String authenticatedAt(FlywayTestSupport.PostgresTarget postgres, String db,
                                          String email) throws Exception {
        return query(postgres, db,
                "SELECT to_char(last_authenticated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') || '+00' "
                        + "FROM auth.users WHERE email = '" + email + "'");
    }

    private static void execute(FlywayTestSupport.PostgresTarget postgres, String db, String sql)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.jdbcUrl(db), postgres.username(), postgres.password());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String query(FlywayTestSupport.PostgresTarget postgres, String db, String sql)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.jdbcUrl(db), postgres.username(), postgres.password());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}

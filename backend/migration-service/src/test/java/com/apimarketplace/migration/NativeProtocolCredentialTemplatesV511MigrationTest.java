package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Replays the REAL V511 against the states it has to survive.
 *
 * <p>V511 gives the ssh, sftp and database nodes the credential template each one needs,
 * and it is an UPSERT rather than an insert because V88 already seeded all three with
 * property names those nodes cannot read ({@code database} / {@code use_ssl} where
 * {@code DatabaseNode} reads {@code database_name} / {@code ssl_enabled}).
 *
 * <p>The trap this test exists for is the conflict TARGET. V103 made the unique key
 * {@code (credential_name, variant)} and backfilled {@code variant = auth_type}, so
 * V88's rows sit at variant {@code 'custom'}. An INSERT that omits the column takes the
 * table DEFAULT {@code 'primary'} instead, and then:
 *
 * <ul>
 *   <li>{@code ('ssh','primary')} never conflicts with {@code ('ssh','custom')}, so the
 *       DO UPDATE branch is dead code and nothing is repaired;</li>
 *   <li>a SECOND row appears for the same name;</li>
 *   <li>and the wizard's {@code DISTINCT ON (credential_name) ORDER BY variant ASC} picks
 *       {@code 'custom'} first, so the broken V88 definition stays the one users see.</li>
 * </ul>
 *
 * <p>That is a green migration that fixes nothing, which is why the assertions below are
 * on row COUNT and on the repaired field names rather than on the statement succeeding.
 * V295 hit the same trap and documents it; this is the test that version never had.
 */
@DisplayName("V511 repairs the native protocol credential templates instead of duplicating them")
class NativeProtocolCredentialTemplatesV511MigrationTest {

    private static final String MIGRATION = "V511__fix_ssh_sftp_database_credential_templates.sql";

    /**
     * ONE container for the class, a fresh DATABASE per test.
     *
     * <p>Each test used to open its own, which is four image starts for four scratch schemas.
     * On a loaded machine two of them failed to LAUNCH, and the suite then reported errors that
     * had nothing to do with the migration, which is worse than slow: it teaches the reader to
     * discount a red result from this file.
     */
    private static FlywayTestSupport.PostgresTarget postgres;

    @BeforeAll
    static void openPostgres() {
        postgres = FlywayTestSupport.openPostgres();
    }

    @AfterAll
    static void closePostgres() {
        if (postgres != null) {
            postgres.close();
        }
    }

    /**
     * The catalog as V103 leaves it, trimmed to what V511 touches. The UNIQUE key is the
     * whole point: with the wrong target the upsert silently becomes a plain insert.
     */
    private static final String SCHEMA = """
            CREATE SCHEMA catalog;
            SET search_path TO catalog;

            CREATE TABLE credentials (
                id                UUID PRIMARY KEY,
                credential_name   VARCHAR(255) NOT NULL,
                display_name      VARCHAR(255),
                description       TEXT,
                credential_type   VARCHAR(100),
                auth_type         VARCHAR(100),
                variant           VARCHAR(50) DEFAULT 'primary',
                test_endpoint     TEXT,
                documentation_url TEXT,
                icon_url          TEXT,
                icon_slug         VARCHAR(255),
                properties        JSONB,
                extends_          JSONB,
                metadata          JSONB,
                created_at        BIGINT,
                updated_at        BIGINT,
                CONSTRAINT credentials_credential_name_variant_key UNIQUE (credential_name, variant)
            );
            """;

    /**
     * V88's three rows as V103 leaves them: variant carried over from auth_type, and the
     * property names the nodes cannot read. The ids are V88's, so preserving them is
     * observable.
     */
    private static final String V88_ROWS = """
            SET search_path TO catalog;
            INSERT INTO credentials (id, credential_name, display_name, credential_type, auth_type, variant, properties)
            VALUES
              ('a1b2c3d4-e5f6-7890-abcd-000000000002'::uuid, 'database', 'Database (old)', 'database', 'custom', 'custom',
               '[{"name":"database"},{"name":"use_ssl"}]'::jsonb),
              ('a1b2c3d4-e5f6-7890-abcd-000000000003'::uuid, 'ssh', 'SSH Server (old)', 'ssh', 'custom', 'custom',
               '[{"name":"host"}]'::jsonb),
              ('a1b2c3d4-e5f6-7890-abcd-000000000004'::uuid, 'sftp', 'SFTP (old)', 'sftp', 'custom', 'custom',
               '[{"name":"host"}]'::jsonb);
            """;

    @Test
    @DisplayName("on an install that ran V88, the three rows are REPAIRED in place, not duplicated")
    void repairsTheExistingRows(@TempDir Path tempDir) throws Exception {
        String db = "v509_repair";
        writeFixture(tempDir, V88_ROWS);
        postgres.createDatabase(db);

        assertThatCode(() -> postgres.runFlyway(db, tempDir)).doesNotThrowAnyException();

        // One row per name. Two means the conflict target missed and the wizard now has a
        // broken row and a good one, showing the broken one.
        for (String name : new String[] {"ssh", "sftp", "database"}) {
            assertThat(count(db, name))
                    .as("'%s' should have exactly one template after the upsert", name)
                    .isEqualTo(1);
        }

        // The repair actually happened: the field names DatabaseNode reads are present and the
        // ones it cannot read are gone. A dead DO UPDATE branch fails here.
        String props = properties(db, "database");
        assertThat(props).contains("database_name").contains("ssl_enabled");
        assertThat(props)
                .as("the V88 names are what the node cannot read; leaving them is the bug")
                .doesNotContain("\"name\": \"database\"")
                .doesNotContain("\"name\": \"use_ssl\"");

        // Repaired IN PLACE: the row keeps V88's id, so anything that ever referenced it still
        // resolves. A new id here means a second row was written instead.
        assertThat(id(db, "ssh")).isEqualTo("a1b2c3d4-e5f6-7890-abcd-000000000003");
        assertThat(displayName(db, "ssh"))
                .as("the DO UPDATE branch must actually have run")
                .isEqualTo("SSH");

        // And the variant convention holds, which is what made the conflict match.
        assertThat(variant(db, "ssh")).isEqualTo("custom");
    }

    @Test
    @DisplayName("on a fresh install with no V88 rows, the three templates are inserted once, at the right variant")
    void insertsOnAFreshInstall(@TempDir Path tempDir) throws Exception {
        String db = "v509_fresh";
        writeFixture(tempDir, null);
        postgres.createDatabase(db);

        assertThatCode(() -> postgres.runFlyway(db, tempDir)).doesNotThrowAnyException();

        for (String name : new String[] {"ssh", "sftp", "database"}) {
            assertThat(count(db, name)).isEqualTo(1);
            assertThat(variant(db, name))
                    .as("variant must equal auth_type (V103's convention), or a later re-seed "
                            + "of '%s' will insert a sibling instead of updating it", name)
                    .isEqualTo("custom");
        }
    }

    /**
     * The state that actually produces a duplicate: a row already sitting at variant
     * {@code 'primary'}. The migration owns {@code 'custom'} and must not touch the other, and
     * the row users end up seeing must still be the repaired one, since the listing orders by
     * variant and {@code 'custom'} sorts before {@code 'primary'}.
     */
    @Test
    @DisplayName("a stray 'primary' row is left alone, and the repaired row is still the one that shows")
    void doesNotCollideWithAPrimaryVariantRow(@TempDir Path tempDir) throws Exception {
        String db = "v509_primary";
        writeFixture(tempDir, """
                SET search_path TO catalog;
                INSERT INTO credentials (id, credential_name, display_name, credential_type, auth_type, variant, properties)
                VALUES ('a1b2c3d4-e5f6-7890-abcd-0000000000ff'::uuid, 'ssh', 'SSH (stray)', 'ssh', 'custom', 'primary',
                        '[{"name":"host"}]'::jsonb);
                """);
        postgres.createDatabase(db);

        assertThatCode(() -> postgres.runFlyway(db, tempDir)).doesNotThrowAnyException();

        assertThat(count(db, "ssh"))
                .as("the migration owns the 'custom' variant and must not disturb another one")
                .isEqualTo(2);
        assertThat(displayName(db, "ssh"))
                .as("the listing takes the lowest variant, so 'custom' wins and it has to be "
                        + "the repaired definition the user sees")
                .isEqualTo("SSH");
    }

    /**
     * The property V295's header asks for in so many words: the file has to be safe to copy to
     * a fresh version after a {@code TRUNCATE catalog.credentials}. Flyway will not replay an
     * applied version, so the SQL is re-run directly.
     */
    @Test
    @DisplayName("running the migration twice changes nothing the second time")
    void isSafeToReplay(@TempDir Path tempDir) throws Exception {
        String db = "v509_replay";
        writeFixture(tempDir, V88_ROWS);
        postgres.createDatabase(db);
        postgres.runFlyway(db, tempDir);

        execute(db, Files.readString(Path.of("src/main/resources/db/migration/" + MIGRATION)));

        for (String name : new String[] {"ssh", "sftp", "database"}) {
            assertThat(count(db, name))
                    .as("a replay that inserts is a replay that cannot be used to recover '%s' "
                            + "after a catalog truncate", name)
                    .isEqualTo(1);
        }
    }

    private static void writeFixture(Path directory, String seedRows) throws Exception {
        Files.writeString(directory.resolve("V1__catalog_credentials.sql"), SCHEMA);
        if (seedRows != null) {
            Files.writeString(directory.resolve("V2__seed_rows.sql"), seedRows);
        }
        // Copied in rather than referenced, so a test that forgot it asserts against untouched
        // rows and fails loudly instead of passing on nothing.
        Files.copy(Path.of("src/main/resources/db/migration/" + MIGRATION),
                directory.resolve(MIGRATION));
    }

    private static int count(String db, String name) throws Exception {
        return Integer.parseInt(scalar(db,
                "SELECT COUNT(*) FROM catalog.credentials WHERE credential_name = '" + name + "'"));
    }

    private static String properties(String db, String name) throws Exception {
        return column(db, "properties::text", name);
    }

    private static String id(String db, String name) throws Exception {
        return column(db, "id::text", name);
    }

    private static String variant(String db, String name) throws Exception {
        return column(db, "variant", name);
    }

    private static String displayName(String db, String name) throws Exception {
        return column(db, "display_name", name);
    }

    /** The row the listing would show: lowest variant first, exactly as the controller orders. */
    private static String column(String db, String expression, String name) throws Exception {
        return scalar(db, "SELECT " + expression + " FROM catalog.credentials "
                + "WHERE credential_name = '" + name + "' ORDER BY variant ASC LIMIT 1");
    }

    private static String scalar(String db, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                     postgres.jdbcUrl(db), postgres.username(), postgres.password());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void execute(String db, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(
                     postgres.jdbcUrl(db), postgres.username(), postgres.password());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

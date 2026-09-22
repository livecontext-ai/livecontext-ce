package com.apimarketplace.orchestrator.tools.workflow.builder;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V486 documents what a generation COST, in the row an agent reads.
 *
 * <p><b>Why this class exists, in one sentence: the first version of V486 wrote to a column that
 * does not exist, and nothing in the repo noticed.</b> It set {@code best_practices}, a name read
 * off the wrong position in V429's column list; the array of tips actually lives in
 * {@code concepts}. Every unit test stayed green - the drift guard in {@code GenerateNodeSpecTest}
 * reads the migration as TEXT, and no other test executes one - and the defect surfaced only when
 * an application tried to boot, where Flyway aborts the whole startup. On a deploy that is a failed
 * rollout, with the schema history left carrying a failed entry.
 *
 * <p>So the fixture below deliberately carries the REAL column set and NOT a {@code best_practices}
 * column. A migration naming a column the table does not have fails here, loudly, in a second.
 *
 * <p><b>How it runs, and why not Testcontainers</b> - the same reasoning as its sibling
 * {@link GenerateNodeAiFamilyDocsV465PostgresTest}: the {@code arc-build} CI runners expose no
 * Docker socket, so a {@code @Testcontainers} class SKIPS there, which is indistinguishable from
 * having no test while looking like coverage. CI provides a {@code postgres:16-alpine} service and
 * sets {@code ORCHESTRATOR_TEST_PG_URL}; with {@code CI} set and no URL this class REFUSES to skip.
 *
 * <p>It creates and drops {@code node_type_documentation}, so it refuses to start unless the
 * database name contains {@code test}. Locally:
 * {@code ORCHESTRATOR_TEST_PG_URL=jdbc:postgresql://localhost:5432/lc_orch_test
 * mvn -pl orchestrator-service test -Dtest=GenerateNodeCostDocsV486PostgresTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V486 the generate node documents what it cost - real Postgres, real migration SQL")
class GenerateNodeCostDocsV486PostgresTest {

    private static final String MIGRATION = "V486__generate_node_reports_what_it_cost.sql";

    private static final String URL = System.getenv("ORCHESTRATOR_TEST_PG_URL");
    private static final String USER =
            System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_USER", "postgres");
    private static final String PASSWORD =
            System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_PASSWORD", "postgres");

    private String migrationSql;
    private JdbcTemplate jdbc;

    @BeforeAll
    void setUpSchema() {
        requireDatabaseOnCi();

        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException(
                    "ORCHESTRATOR_TEST_PG_URL must point at a scratch database whose name contains "
                            + "'test' (this test drops node_type_documentation), got: " + database);
        }

        migrationSql = loadMigration();
        if (migrationSql == null) {
            throw new IllegalStateException(
                    "cannot read " + MIGRATION + " from the module working directory. The test "
                            + "reads the SHIPPED migration on purpose: a copy of the SQL inlined "
                            + "here would pass while the real file was broken - which is exactly "
                            + "the failure this class was written after.");
        }
        // Stripped for the same reason the V465 test strips it: creating an `orchestrator` schema
        // on a shared scratch database would collide with the other Postgres tests in this job.
        migrationSql = migrationSql.replace("SET search_path TO orchestrator;", "");

        awaitDatabase();
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("DROP TABLE IF EXISTS node_type_documentation");
        // The REAL columns, and only those. `best_practices` is deliberately absent: the first
        // version of this migration wrote to it, and a fixture inventing the column to be helpful
        // would have passed while the shipped file took the application down.
        jdbc.execute("""
                CREATE TABLE node_type_documentation (
                    type            VARCHAR(128) PRIMARY KEY,
                    label           VARCHAR(100),
                    category        VARCHAR(64),
                    description     TEXT,
                    variable_prefix VARCHAR(64),
                    outputs         JSONB,
                    parameters      JSONB,
                    concepts        JSONB,
                    examples        JSONB,
                    keywords        JSONB,
                    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
                    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """);
    }

    /** The generate row as V429 leaves it, in the two columns V486 rewrites. */
    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE node_type_documentation");
        jdbc.update("INSERT INTO node_type_documentation "
                        + "(type, category, variable_prefix, outputs, concepts) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?::jsonb)",
                "generate", "ai", "agent",
                """
                {"file": {"type": "object", "description": "The generated asset."},
                 "billed_quantity": {"type": "number", "description": "The size this run was billed on, counted in billed_unit."},
                 "billed_unit": {"type": "string", "description": "What billed_quantity counts: call, second, image or character. To work out the cost, convert billed_quantity into the rate's own unit first (60 seconds is 1 minute), then multiply."}}
                """,
                "[\"Pick the model FIRST: it decides the format, the accepted params and the price.\","
                        + " \"Every successful run is charged. What it cost is not on the answer,"
                        + " so multiply the rate by output.billed_quantity.\"]");
        // A neighbour, to prove the WHERE clause. Its row must come out untouched.
        jdbc.update("INSERT INTO node_type_documentation "
                        + "(type, category, variable_prefix, outputs, concepts) "
                        + "VALUES (?, ?, ?, '{}'::jsonb, '[]'::jsonb)",
                "media", "core", "core");
    }

    @Test
    @DisplayName("runs at all: every column it names exists on the real table")
    void theMigrationRuns() {
        // The whole point. A column that does not exist aborts Flyway, and with it the boot of
        // every service that migrates - which is how this was found, on a stack that would not
        // come up rather than on a test that went red.
        jdbc.execute(migrationSql);

        assertThat(str("SELECT type FROM node_type_documentation WHERE type = 'generate'"))
                .isEqualTo("generate");
    }

    @Test
    @DisplayName("documents billed_credits, and says that an absent amount is not a charge of zero")
    void documentsTheCharge() {
        jdbc.execute(migrationSql);

        String description = str(
                "SELECT outputs #>> '{billed_credits,description}' FROM node_type_documentation "
                        + "WHERE type = 'generate'");
        assertThat(description)
                .as("an output the node emits and this row does not name is one no agent references")
                .isNotNull()
                .contains("ABSENT means the platform charged nothing")
                .contains("not a charge of zero");
        assertThat(str("SELECT outputs #>> '{billed_credits,type}' FROM node_type_documentation "
                + "WHERE type = 'generate'")).isEqualTo("number");
    }

    @Test
    @DisplayName("stops telling an agent to reconstruct the cost by multiplying a rate")
    void retiresTheArithmetic() {
        jdbc.execute(migrationSql);

        // The advice was not merely incomplete, it was wrong: a rate can be republished between the
        // run and the reading, so the product is a number nobody was charged.
        assertThat(str("SELECT outputs #>> '{billed_unit,description}' FROM node_type_documentation "
                + "WHERE type = 'generate'"))
                .doesNotContain("then multiply")
                .contains("billed_credits");
        assertThat(str("SELECT concepts::text FROM node_type_documentation WHERE type = 'generate'"))
                .contains("billed_credits")
                .doesNotContain("multiply the rate by output.billed_quantity");
    }

    @Test
    @DisplayName("leaves the measurement itself alone, and the neighbouring node untouched")
    void touchesNothingElse() {
        jdbc.execute(migrationSql);

        // billed_quantity still says what it counts: the charge is a new fact beside it, not a
        // replacement for it.
        assertThat(str("SELECT outputs #>> '{billed_quantity,description}' FROM node_type_documentation "
                + "WHERE type = 'generate'")).contains("billed_unit");
        assertThat(str("SELECT concepts::text FROM node_type_documentation WHERE type = 'media'"))
                .isEqualTo("[]");
        assertThat(str("SELECT outputs::text FROM node_type_documentation WHERE type = 'media'"))
                .isEqualTo("{}");
    }

    private String str(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    private static void requireDatabaseOnCi() {
        if (URL != null && !URL.isBlank()) {
            return;
        }
        boolean onCi = System.getenv("CI") != null && !System.getenv("CI").isBlank();
        if (onCi) {
            throw new IllegalStateException(
                    "ORCHESTRATOR_TEST_PG_URL is unset on CI. This class must execute there: it is "
                            + "the only thing that RUNS V486, and a migration is the one artefact "
                            + "whose defects are invisible to every test that merely reads it. Keep "
                            + "it in a job carrying the postgres service.");
        }
        Assumptions.abort(
                "no scratch Postgres: set ORCHESTRATOR_TEST_PG_URL to run this locally "
                        + "(CI always sets it)");
    }

    private static void awaitDatabase() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
                return;
            } catch (Exception e) {
                last = new IllegalStateException(
                        "ORCHESTRATOR_TEST_PG_URL is set but the database is unreachable: " + URL, e);
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }

    private static String loadMigration() {
        String[] candidates = {
                "../migration-service/src/main/resources/db/migration/" + MIGRATION,
                "backend/migration-service/src/main/resources/db/migration/" + MIGRATION,
        };
        for (String candidate : candidates) {
            Path path = Path.of(candidate);
            if (Files.exists(path)) {
                try {
                    return Files.readString(path);
                } catch (Exception e) {
                    throw new IllegalStateException("unreadable migration: " + path, e);
                }
            }
        }
        return null;
    }
}

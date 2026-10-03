package com.apimarketplace.migration;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

final class FlywayTestSupport {

    private FlywayTestSupport() {
    }

    /**
     * Mirrors the production Flyway config ({@code migration-service/application.yml}):
     * {@code mixed=true} lets one migration interleave transactional DDL/DML with a
     * non-transactional {@code executeInTransaction=false} CONCURRENTLY script, and
     * {@code postgresql.transactional.lock=false} makes Flyway take its own coordination
     * lock at the SESSION level instead of holding it open inside a transaction. Without
     * the second setting, Flyway's default transactional lock is itself an open
     * transaction for the whole {@code migrate()} call, and {@code CREATE INDEX
     * CONCURRENTLY} must wait for every open transaction to end before it can proceed -
     * including Flyway's own, which never will until the migration finishes. That is a
     * self-deadlock: replaying V535/V536 through this harness with the default hung
     * indefinitely until this was set, matching the flyway/flyway#3854 upstream issue.
     */
    private static final java.util.Map<String, String> FLYWAY_PROPERTIES = java.util.Map.of(
            "flyway.mixed", "true",
            "flyway.postgresql.transactional.lock", "false");

    /**
     * A Postgres to replay migrations against: the one CI hands over through
     * {@code MIGRATION_TEST_PG_URL}, else a testcontainer.
     *
     * <p>The env var exists because the ARC runners have NO Docker socket, so a test
     * that insists on a container SKIPS there - and a skipped replay is a green build
     * that verified nothing, which is exactly what wiring these tests into CI was meant
     * to stop. When the variable is set the test MUST run: no assumption, no skip. It
     * is only on a developer machine, where neither the variable nor Docker may be
     * present, that skipping is the right answer.</p>
     */
    static PostgresTarget openPostgres() {
        String url = System.getenv("MIGRATION_TEST_PG_URL");
        if (url != null && !url.isBlank()) {
            String user = envOrDefault("MIGRATION_TEST_PG_USER", "postgres");
            String password = envOrDefault("MIGRATION_TEST_PG_PASSWORD", "postgres");
            return new PostgresTarget(url.endsWith("/") ? url : url + "/", user, password, null);
        }
        assumeDockerAvailable();
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.start();
        String base = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/";
        return new PostgresTarget(base, container.getUsername(), container.getPassword(), container);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value != null && !value.isBlank() ? value : fallback;
    }

    /** A Postgres to talk to, and whatever has to be shut down afterwards. */
    record PostgresTarget(String jdbcBase, String username, String password,
                          PostgreSQLContainer<?> container) implements AutoCloseable {

        String jdbcUrl(String databaseName) {
            return jdbcBase + databaseName;
        }

        /** A scratch database, dropped first so a re-run on a SHARED server is clean. */
        void createDatabase(String databaseName) throws Exception {
            try (var connection = DriverManager.getConnection(jdbcBase + "postgres", username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + databaseName);
                statement.execute("CREATE DATABASE " + databaseName);
            }
        }

        void runFlyway(String databaseName, Path migrations) {
            Flyway.configure()
                    .dataSource(jdbcUrl(databaseName), username, password)
                    .locations("filesystem:" + migrations.toAbsolutePath())
                    .configuration(FLYWAY_PROPERTIES)
                    .load()
                    .migrate();
        }

        @Override
        public void close() {
            if (container != null) container.stop();
        }
    }

    static void assumeDockerAvailable() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available - migration replay test skipped");
    }

    static void createDatabase(PostgreSQLContainer<?> postgres, String databaseName) throws Exception {
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
    }

    static void runFlyway(PostgreSQLContainer<?> postgres, String databaseName, Path migrations) {
        Flyway.configure()
                .dataSource(jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword())
                .locations("filesystem:" + migrations.toAbsolutePath())
                .load()
                .migrate();
    }

    static String jdbcUrl(PostgreSQLContainer<?> postgres, String databaseName) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/" + databaseName;
    }
}

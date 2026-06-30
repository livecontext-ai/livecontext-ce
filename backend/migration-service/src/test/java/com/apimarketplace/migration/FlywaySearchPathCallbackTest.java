package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

@DisplayName("Flyway search_path callback invariants")
class FlywaySearchPathCallbackTest {

    @Test
    @DisplayName("beforeEachMigrate resets leaked search_path before unqualified orchestrator tables")
    void beforeEachMigrateResetsLeakedSearchPath(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();

        Path withoutCallback = Files.createDirectory(tempDir.resolve("without-callback"));
        Path withCallback = Files.createDirectory(tempDir.resolve("with-callback"));
        writeSearchPathLeakFixture(withoutCallback, false);
        writeSearchPathLeakFixture(withCallback, true);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "without_callback");
            FlywayTestSupport.createDatabase(postgres, "with_callback");

            assertThatThrownBy(() -> FlywayTestSupport.runFlyway(postgres, "without_callback", withoutCallback))
                    .hasMessageContaining("relation \"workflow_runs\" does not exist");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "with_callback", withCallback))
                    .doesNotThrowAnyException();
            assertThat(lastEventSeqColumnExists(postgres, "with_callback")).isTrue();
        }
    }

    private static void writeSearchPathLeakFixture(Path directory, boolean withCallback) throws Exception {
        Files.writeString(directory.resolve("V1__leak_search_path.sql"), """
                CREATE SCHEMA orchestrator;
                CREATE TABLE orchestrator.workflow_runs (
                    id BIGINT PRIMARY KEY
                );

                CREATE SCHEMA agent;
                SET search_path TO agent;
                """);
        Files.writeString(directory.resolve("V2__touch_unqualified_orchestrator_table.sql"), """
                ALTER TABLE workflow_runs
                    ADD COLUMN last_event_seq BIGINT NOT NULL DEFAULT 0;
                """);

        if (withCallback) {
            String productionCallback = Files.readString(Path.of(
                    "src/main/resources/db/migration/beforeEachMigrate.sql"));
            Files.writeString(directory.resolve("beforeEachMigrate.sql"), productionCallback);
        }
    }

    private static boolean lastEventSeqColumnExists(PostgreSQLContainer<?> postgres, String databaseName)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT EXISTS (
                         SELECT 1
                           FROM information_schema.columns
                          WHERE table_schema = 'orchestrator'
                            AND table_name = 'workflow_runs'
                            AND column_name = 'last_event_seq'
                     )
                     """)) {
            resultSet.next();
            return resultSet.getBoolean(1);
        }
    }
}

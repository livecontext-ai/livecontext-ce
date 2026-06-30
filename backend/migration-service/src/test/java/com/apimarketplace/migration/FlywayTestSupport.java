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

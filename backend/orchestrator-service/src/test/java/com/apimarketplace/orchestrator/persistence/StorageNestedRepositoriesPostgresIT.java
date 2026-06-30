package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.domain.StorageNestedModels.StorageNestedRow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StorageNestedRepositoriesPostgresIT {

    private static PostgreSQLContainer<?> postgres;

    private JdbcTemplate jdbc;
    private StorageNestedRepositories repository;

    @BeforeAll
    static void startPostgres() {
        Assumptions.assumeTrue(
            DockerClientFactory.instance().isDockerAvailable(),
            "Docker not available - StorageNestedRepositoriesPostgresIT skipped");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword());
        dataSource.setDriverClassName(postgres.getDriverClassName());
        jdbc = new JdbcTemplate(dataSource);
        repository = new StorageNestedRepositories(jdbc);

        jdbc.execute("DROP SCHEMA IF EXISTS storage CASCADE");
        jdbc.execute("CREATE SCHEMA storage");
        jdbc.execute("""
            CREATE TABLE storage.storage (
                id UUID PRIMARY KEY,
                tenant_id TEXT NOT NULL,
                data JSONB NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """);
    }

    @Test
    void nestedArraySortPayloadIsBoundAndDoesNotBreakSqlOrDropTable() {
        UUID storageId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        jdbc.update("""
            INSERT INTO storage.storage (id, tenant_id, data)
            VALUES (?, ?, ?::jsonb)
            """,
            storageId,
            "tenant-1",
            """
            {"output":{"items":[{"name":"b"},{"name":"a"}]}}
            """);

        String maliciousSort = "name') DESC; DROP TABLE storage.storage; --";

        List<StorageNestedRow> rows = repository.findNestedData(
            storageId, "tenant-1", "output.items", 1, 10, maliciousSort, "asc");

        assertThat(rows).hasSize(2);
        assertStorageTableExists();
    }

    @Test
    void nestedPathPayloadIsBoundAndDoesNotBreakSqlOrDropTable() {
        UUID storageId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        jdbc.update("""
            INSERT INTO storage.storage (id, tenant_id, data)
            VALUES (?, ?, ?::jsonb)
            """,
            storageId,
            "tenant-1",
            """
            {"output":{"items":[{"name":"a"}]}}
            """);

        String maliciousPath = "output.items') IS NOT NULL; DROP TABLE storage.storage; --";

        assertThat(repository.countNestedData(storageId, "tenant-1", maliciousPath)).isZero();
        assertStorageTableExists();
    }

    private void assertStorageTableExists() {
        Integer tableCount = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM information_schema.tables
            WHERE table_schema = 'storage' AND table_name = 'storage'
            """, Integer.class);
        assertThat(tableCount).isEqualTo(1);
    }
}

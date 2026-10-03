package com.apimarketplace.datasource.persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASA LC-066: the table grid reads a row's stored classification before it edits or deletes it,
 * so the row event it publishes restricts the table-triggered run. The read runs on real SQL here
 * because what it must never do, hand back another tenant's or another table's classification for
 * an id the caller named, only shows against a real WHERE clause.
 *
 * <p>Runs when {@code DATASOURCE_TEST_PG_URL} is set (CI sets it; FAILS there if unset).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Row sensitivity reads (grid events, table-trigger load) - real Postgres (LC-066)")
class DataSourceItemQueryRepositorySensitivityPostgresTest {

    private static final String URL = System.getenv("DATASOURCE_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("DATASOURCE_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("DATASOURCE_TEST_PG_PASSWORD", "postgres");
    /** A schema of its own, so the unqualified {@code data_source_items} the repository queries is this one. */
    private static final String SCHEMA = "datasource_sensitivity_test";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceItemQueryRepository repository;

    @BeforeAll
    void setUpSchema() {
        if (URL == null || URL.isBlank()) {
            if (System.getenv("CI") != null && !System.getenv("CI").isBlank()) {
                throw new IllegalStateException("DATASOURCE_TEST_PG_URL is unset on CI; this test must run there");
            }
            Assumptions.abort("no scratch Postgres: set DATASOURCE_TEST_PG_URL to run this locally");
        }
        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException("DATASOURCE_TEST_PG_URL must name a scratch database containing 'test', got " + database);
        }
        dataSource = new SingleConnectionDataSource(URL, USER, PASSWORD, true);
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        jdbc.execute("SET search_path TO " + SCHEMA);
        jdbc.execute("DROP TABLE IF EXISTS data_source_items");
        jdbc.execute("CREATE TABLE data_source_items (id BIGSERIAL PRIMARY KEY, data_source_id BIGINT NOT NULL, "
            + "tenant_id VARCHAR(255) NOT NULL, data JSONB, data_sensitivity VARCHAR(16))");
        repository = new DataSourceItemQueryRepository(jdbc);
    }

    @AfterAll
    void dropSchema() {
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            dataSource.destroy();
        }
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE data_source_items");
    }

    private long row(long dataSourceId, String tenant, String sensitivity) {
        return jdbc.queryForObject(
            "INSERT INTO data_source_items (data_source_id, tenant_id, data, data_sensitivity) "
                + "VALUES (?, ?, '{}'::jsonb, ?) RETURNING id", Long.class, dataSourceId, tenant, sensitivity);
    }

    @Test
    @DisplayName("returns each named row's stored class, and no entry for an unclassified row")
    void returnsEachRowsStoredClass() {
        long restricted = row(1L, "tenant-a", "RESTRICTED");
        long normal = row(1L, "tenant-a", "NORMAL");
        long unclassified = row(1L, "tenant-a", null);

        Map<Long, String> byId = repository.sensitivityByIds(1L, "tenant-a", List.of(restricted, normal, unclassified));

        assertThat(byId).containsEntry(restricted, "RESTRICTED").containsEntry(normal, "NORMAL");
        assertThat(byId.get(unclassified)).isNull();
    }

    @Test
    @DisplayName("never answers for another tenant's row or another table's row, even when its id is named")
    void staysInsideTheCallersTenantAndTable() {
        long otherTenant = row(1L, "tenant-b", "RESTRICTED");
        long otherTable = row(2L, "tenant-a", "RESTRICTED");
        long own = row(1L, "tenant-a", "NORMAL");

        Map<Long, String> byId = repository.sensitivityByIds(1L, "tenant-a", List.of(otherTenant, otherTable, own));

        assertThat(byId).containsOnlyKeys(own);
    }

    @Test
    @DisplayName("anyRestricted (the table-trigger load): true only when a NAMED row of THIS table is RESTRICTED")
    void anyRestrictedLooksOnlyAtTheNamedRows() {
        DataSourceRepositories.DataSourceItemRepository items = new DataSourceRepositories.DataSourceItemRepository(
            jdbc, new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),
            new com.fasterxml.jackson.databind.ObjectMapper());
        long normal = row(1L, "tenant-a", "NORMAL");
        long unclassified = row(1L, "tenant-a", null);
        long restricted = row(1L, "tenant-b", "RESTRICTED");
        long otherTable = row(2L, "tenant-a", "RESTRICTED");

        assertThat(items.anyRestricted(1L, List.of(normal, unclassified))).isFalse();
        // Any owner: the ids come from a read the caller was scoped for (org tables have several).
        assertThat(items.anyRestricted(1L, List.of(normal, restricted))).isTrue();
        assertThat(items.anyRestricted(1L, List.of(normal, otherTable))).isFalse();
        assertThat(items.anyRestricted(1L, List.of())).isFalse();
    }

    @Test
    @DisplayName("an empty id list is answered without a query")
    void emptyIdListIsEmpty() {
        assertThat(repository.sensitivityByIds(1L, "tenant-a", List.of())).isEmpty();
    }
}

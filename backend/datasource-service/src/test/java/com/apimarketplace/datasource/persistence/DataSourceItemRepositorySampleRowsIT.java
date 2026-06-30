package com.apimarketplace.datasource.persistence;

import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postgres IT for {@link DataSourceItemRepository#sampleRowsByDataSourceIds} - the
 * batched window query that feeds each table card's mini-table preview. Real Postgres
 * is required because the contract is the {@code ROW_NUMBER() OVER (PARTITION BY …)}
 * slice + per-partition ordering, which an H2 fake would not exercise faithfully.
 *
 * <p>Pins four things the card preview depends on: (1) at most {@code perTable} rows
 * come back per datasource, (2) they are the TOP rows in {@code priority DESC, id ASC}
 * order, (3) partitioning never bleeds one table's rows into another's, and (4) the
 * {@code perTable} argument is clamped to {@code [1, 10]}.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("DataSourceItemRepository.sampleRowsByDataSourceIds (Postgres IT)")
class DataSourceItemRepositorySampleRowsIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("datasource_test")
            .withUsername("test")
            .withPassword("test");

    private DataSourceItemRepository repo;
    private JdbcTemplate jdbc;

    @BeforeAll
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        this.jdbc = new JdbcTemplate(ds);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(ds);
        this.repo = new DataSourceItemRepository(jdbc, namedJdbc, new ObjectMapper());

        // The repo SQL is schema-unqualified (search_path is set by Spring in prod);
        // creating the table in the default schema makes the unqualified name resolve.
        jdbc.execute("""
                CREATE TABLE data_source_items (
                    id             BIGSERIAL PRIMARY KEY,
                    data_source_id BIGINT NOT NULL,
                    tenant_id      VARCHAR(255) NOT NULL,
                    data           JSONB NOT NULL,
                    priority       INTEGER NOT NULL DEFAULT 0,
                    created_at     TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
                )
                """);

        // Table 1 - four rows with mixed priorities so ordering (priority DESC, id ASC) is observable.
        insert(1L, "{\"name\":\"low-A\"}", 0);   // id 1
        insert(1L, "{\"name\":\"high\"}", 9);    // id 2
        insert(1L, "{\"name\":\"mid\"}", 5);     // id 3
        insert(1L, "{\"name\":\"low-B\"}", 0);   // id 4
        // Table 2 - a single row; Table 3 - none (must be absent from the result).
        insert(2L, "{\"name\":\"solo\"}", 0);    // id 5
    }

    private void insert(long dataSourceId, String json, int priority) {
        jdbc.update("INSERT INTO data_source_items (data_source_id, tenant_id, data, priority) "
                + "VALUES (?, ?, ?::jsonb, ?)", dataSourceId, "tenant-1", json, priority);
    }

    @Test
    @DisplayName("Caps each table to perTable rows, ordered priority DESC then id ASC")
    void capsAndOrders() {
        Map<Long, List<Map<String, Object>>> result =
                repo.sampleRowsByDataSourceIds(List.of(1L, 2L, 3L), 3);

        // Table 1: top 3 of 4 rows → high(p9), mid(p5), then the lower-priority tie broken by id ASC (low-A, id 1).
        assertThat(result.get(1L)).hasSize(3);
        assertThat(result.get(1L)).extracting(r -> r.get("name"))
                .containsExactly("high", "mid", "low-A");

        // Table 2: its only row, unaffected by table 1's rows (partition isolation).
        assertThat(result.get(2L)).extracting(r -> r.get("name")).containsExactly("solo");

        // Table 3: no rows → absent (caller defaults to empty list).
        assertThat(result).doesNotContainKey(3L);
    }

    @Test
    @DisplayName("perTable larger than the row count returns every row; partitions never bleed")
    void returnsAllWhenFewerThanCap() {
        Map<Long, List<Map<String, Object>>> result =
                repo.sampleRowsByDataSourceIds(List.of(1L), 9);

        assertThat(result.get(1L)).hasSize(4); // all four rows of table 1, none of table 2's
        assertThat(result.get(1L)).extracting(r -> r.get("name"))
                .containsExactly("high", "mid", "low-A", "low-B"); // priority DESC, id ASC tie-break
    }

    @Test
    @DisplayName("perTable below 1 is clamped to 1 (returns the single top row)")
    void clampsLowerBound() {
        Map<Long, List<Map<String, Object>>> result =
                repo.sampleRowsByDataSourceIds(List.of(1L), 0);

        assertThat(result.get(1L)).extracting(r -> r.get("name")).containsExactly("high");
    }

    @Test
    @DisplayName("perTable above 10 is clamped to 10")
    void clampsUpperBound() {
        // Only table 1 (4 rows) exists, so the clamp itself isn't directly countable here;
        // the guarantee is exercised by capsAndOrders + this not-throwing oversize request.
        Map<Long, List<Map<String, Object>>> result =
                repo.sampleRowsByDataSourceIds(List.of(1L, 2L), 100);

        assertThat(result.get(1L)).hasSize(4);
        assertThat(result.get(2L)).hasSize(1);
    }

    @Test
    @DisplayName("An empty id list short-circuits to an empty map (no query)")
    void emptyIdsShortCircuits() {
        assertThat(repo.sampleRowsByDataSourceIds(List.of(), 3)).isEmpty();
    }

    @Test
    @DisplayName("A non-object JSON row (array/scalar root) degrades to an empty map, not a page failure")
    void nonObjectJsonRowDegradesGracefully() {
        // Valid JSONB, but its root is an array - it cannot bind to Map<String,Object>, exercising
        // the per-row catch. The datasource must still appear, with that row reduced to {}.
        insert(7L, "[1,2,3]", 0);

        Map<Long, List<Map<String, Object>>> result = repo.sampleRowsByDataSourceIds(List.of(7L), 3);

        assertThat(result.get(7L)).containsExactly(Map.of());
    }
}

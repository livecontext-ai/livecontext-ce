package com.apimarketplace.datasource.persistence;

import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceItem;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository.CopyCursor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The publication copy query on real SQL: the RESTRICTED rows (CASA LC-066) are left out by the
 * WHERE clause before LIMIT cuts the page, and keyset pages on {@code priority DESC, id ASC} tile
 * the table with no overlap and no gap, even when rows are inserted between two pages. Both only
 * show against a real ORDER BY / LIMIT, which a mocked JdbcTemplate cannot exercise.
 *
 * <p>Runs when {@code DATASOURCE_TEST_PG_URL} is set (CI sets it; FAILS there if unset).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Publication copy pages (RESTRICTED filter + keyset) - real Postgres")
class DataSourceItemCopyPagePostgresTest {

    private static final String URL = System.getenv("DATASOURCE_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("DATASOURCE_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("DATASOURCE_TEST_PG_PASSWORD", "postgres");
    /** A schema of its own, so the unqualified tables the repository queries are these ones. */
    private static final String SCHEMA = "datasource_copy_page_test";
    private static final String TENANT = "tenant-a";
    private static final String ORG = "org-a";
    private static final long TABLE = 1L;

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceItemRepository repository;

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
        jdbc.execute("DROP TABLE IF EXISTS data_sources");
        jdbc.execute("CREATE TABLE data_sources (id BIGINT PRIMARY KEY, organization_id VARCHAR(255))");
        // The production shape of the columns the copy reads (V538 sensitivity is NOT NULL there;
        // nullable here so the test also pins that an unclassified row is copied).
        jdbc.execute("CREATE TABLE data_source_items (id BIGSERIAL PRIMARY KEY, data_source_id BIGINT NOT NULL, "
                + "tenant_id VARCHAR(255) NOT NULL, data JSONB, priority INTEGER NOT NULL DEFAULT 0, "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT now(), data_sensitivity VARCHAR(16))");
        repository = new DataSourceItemRepository(jdbc, new NamedParameterJdbcTemplate(jdbc), new ObjectMapper());
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
        jdbc.execute("TRUNCATE data_sources");
        jdbc.update("INSERT INTO data_sources (id, organization_id) VALUES (?, ?), (?, ?)", TABLE, ORG, 2L, ORG);
    }

    private long row(long table, int priority, String sensitivity) {
        return jdbc.queryForObject("INSERT INTO data_source_items (data_source_id, tenant_id, data, priority, data_sensitivity) "
                + "VALUES (?, ?, '{\"n\":1}'::jsonb, ?, ?) RETURNING id", Long.class, table, TENANT, priority, sensitivity);
    }

    /** Every page of a copy, the way DataSourceClient.getAllItems walks them: keyset after the first. */
    private List<List<Long>> pages(int pageSize, BiFunction<Integer, CopyCursor, List<DataSourceItem>> read) {
        List<List<Long>> pages = new ArrayList<>();
        CopyCursor after = null;
        while (true) {
            List<DataSourceItem> page = read.apply(pageSize, after);
            pages.add(page.stream().map(DataSourceItem::id).toList());
            if (page.size() < pageSize) {
                return pages;
            }
            DataSourceItem last = page.get(page.size() - 1);
            after = new CopyCursor(last.priority(), last.id());
        }
    }

    @Test
    @DisplayName("RESTRICTED rows are left out; NORMAL and unclassified rows are copied")
    void restrictedRowsAreLeftOut() {
        long normal = row(TABLE, 0, "NORMAL");
        long unclassified = row(TABLE, 0, null);
        row(TABLE, 0, "RESTRICTED");
        row(2L, 0, "NORMAL");

        assertThat(repository.findCopyPageInOrgScope(TABLE, ORG, 0, 50, null))
                .extracting(DataSourceItem::id).containsExactly(normal, unclassified);
        assertThat(repository.findCopyPageForTenant(TABLE, TENANT, 0, 50, null))
                .extracting(DataSourceItem::id).containsExactly(normal, unclassified);
    }

    @Test
    @DisplayName("regression (copy paging): RESTRICTED rows never shorten a page, and keyset pages tile the table in order")
    void keysetPagesTileTheTableWithoutOverlap() {
        List<Long> expected = new ArrayList<>();
        int[] priorities = {5, 5, 5, 3, 3, 9, 0, 0, 0, 0, 7, 3, 5, 1, 1, 9, 2, 0, 3, 5, 4, 4, 6};
        for (int i = 0; i < priorities.length; i++) {
            // Every third row RESTRICTED: with filter-after-cut, the first page would come back short.
            long id = row(TABLE, priorities[i], i % 3 == 0 ? "RESTRICTED" : "NORMAL");
            if (i % 3 != 0) {
                expected.add(id);
            }
        }
        List<Long> stored = jdbc.queryForList("SELECT id FROM data_source_items WHERE data_source_id = ? "
                + "AND data_sensitivity <> 'RESTRICTED' ORDER BY priority DESC, id ASC", Long.class, TABLE);
        assertThat(stored).containsExactlyInAnyOrderElementsOf(expected);

        List<List<Long>> pages = pages(4, (size, after) -> repository.findCopyPageInOrgScope(TABLE, ORG, 0, size, after));

        for (int i = 0; i < pages.size() - 1; i++) {
            assertThat(pages.get(i)).as("page %d is full", i).hasSize(4);
        }
        assertThat(pages.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(stored);
    }

    @Test
    @DisplayName("regression (copy paging): a row inserted between two pages shifts nothing: no row copied twice, none skipped")
    void insertBetweenPagesDoesNotShiftTheCopy() {
        List<Long> before = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            before.add(row(TABLE, 0, "NORMAL"));
        }
        List<DataSourceItem> first = repository.findCopyPageForTenant(TABLE, TENANT, 0, 3, null);
        // A new row AHEAD of the cursor (higher priority): an offset page 2 would now repeat a row.
        row(TABLE, 10, "NORMAL");
        DataSourceItem last = first.get(first.size() - 1);
        List<DataSourceItem> second = repository.findCopyPageForTenant(TABLE, TENANT, 3, 3,
                new CopyCursor(last.priority(), last.id()));
        List<DataSourceItem> third = repository.findCopyPageForTenant(TABLE, TENANT, 6, 3,
                new CopyCursor(second.get(2).priority(), second.get(2).id()));

        List<Long> copied = new ArrayList<>();
        first.forEach(item -> copied.add(item.id()));
        second.forEach(item -> copied.add(item.id()));
        third.forEach(item -> copied.add(item.id()));
        assertThat(copied).containsExactlyElementsOf(before);
        // What an offset page 2 would have read: the shift the keyset avoids.
        assertThat(repository.findCopyPageForTenant(TABLE, TENANT, 3, 3, null))
                .extracting(DataSourceItem::id).contains(before.get(2));
    }
}

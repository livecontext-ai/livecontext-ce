package com.apimarketplace.orchestrator.integration.storage;

import com.apimarketplace.common.storage.repository.TenantStorageBreakdownRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postgres-backed end-to-end test for the GREATEST(...) clamp inside
 * {@code TenantStorageBreakdownRepository.incrementUsage}.
 *
 * <p>This test exists because H2 (the test DB for common-storage-service) does
 * not support PostgreSQL's {@code ON CONFLICT DO UPDATE} syntax, so the clamp
 * cannot be exercised behaviourally in that module. The SQL is extracted via
 * reflection from the {@link Query} annotation and run against a real Postgres
 * container, so a regression that strips {@code GREATEST(...)} from the SQL
 * fails the assertion (the row goes negative), not a string-pattern test.
 *
 * <p>Pre-fix prod evidence (2026-05-11): tenant 1 had {@code EXECUTION_DATA}
 * {@code used_bytes} drifting to {@code -10,677,252} in
 * {@code storage.storage_usage_history} because the unclamped ON CONFLICT
 * branch accepted negative cumulative sums.
 *
 * <p>Skipped silently when Docker is unavailable (mirrors
 * {@code OptimBundlePostgresIT}); CI runners with Docker exercise the full suite.
 */
@Testcontainers
@DisplayName("TenantStorageBreakdownRepository.incrementUsage - Postgres clamp E2E")
class TenantStorageBreakdownClampPostgresIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String TENANT = "tenant-clamp-it";
    private static final String CATEGORY = "EXECUTION_DATA";

    static NamedParameterJdbcTemplate jdbc;
    static String incrementSql;

    @BeforeAll
    static void setUpClass() throws NoSuchMethodException {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available - TenantStorageBreakdownClampPostgresIT skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new NamedParameterJdbcTemplate(ds);

        jdbc.getJdbcTemplate().execute("CREATE SCHEMA IF NOT EXISTS storage");
        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE IF NOT EXISTS storage.tenant_storage_breakdown ("
                        + "  tenant_id VARCHAR NOT NULL,"
                        + "  category VARCHAR(50) NOT NULL,"
                        + "  used_bytes BIGINT NOT NULL,"
                        + "  item_count INT NOT NULL,"
                        + "  calculated_at TIMESTAMPTZ NOT NULL,"
                        + "  PRIMARY KEY (tenant_id, category)"
                        + ")");

        // Extract the EXACT @Query SQL from the repository so any future refactor
        // that strips the GREATEST clamp is caught by the behavioural asserts below.
        Method method = TenantStorageBreakdownRepository.class.getMethod(
                "incrementUsage", String.class, String.class, long.class, int.class);
        Query annotation = method.getAnnotation(Query.class);
        if (annotation == null) {
            throw new IllegalStateException("@Query annotation missing on incrementUsage");
        }
        incrementSql = annotation.value();
    }

    @BeforeEach
    void cleanData() {
        Assumptions.assumeTrue(jdbc != null, "Docker not available - skipped");
        jdbc.getJdbcTemplate().execute("TRUNCATE storage.tenant_storage_breakdown");
    }

    @Test
    @DisplayName("Negative delta on a row at zero leaves used_bytes and item_count at zero")
    void clampsAtZeroWhenRowAtZero() {
        seed(0L, 0);

        runIncrement(-1_000L, -3);

        assertThat(usedBytes()).isEqualTo(0L);
        assertThat(itemCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Negative delta exceeding current used_bytes clamps to zero, not negative")
    void clampsWhenDeltaExceedsCurrent() {
        seed(500L, 2);

        runIncrement(-2_000L, -10);

        assertThat(usedBytes()).as("would have been -1500 pre-fix").isEqualTo(0L);
        assertThat(itemCount()).as("would have been -8 pre-fix").isEqualTo(0);
    }

    @Test
    @DisplayName("First INSERT branch also clamps a negative delta to zero")
    void clampsOnFirstInsert() {
        runIncrement(-500L, -1);

        assertThat(usedBytes()).isEqualTo(0L);
        assertThat(itemCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Positive delta is unaffected by the clamp")
    void positiveDeltaIsUnchanged() {
        seed(100L, 1);

        runIncrement(250L, 2);

        assertThat(usedBytes()).isEqualTo(350L);
        assertThat(itemCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Partial absorb - negative delta smaller than current decrements normally")
    void partialAbsorb() {
        seed(1_000L, 5);

        runIncrement(-400L, -2);

        assertThat(usedBytes()).isEqualTo(600L);
        assertThat(itemCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Repeated underflowing decrements never accumulate below zero (cumulative drift guard)")
    void cumulativeDecrementsCannotGoNegative() {
        seed(100L, 0);

        // Five decrements of 100 against a row at 100 - pre-fix this would land at -400.
        for (int i = 0; i < 5; i++) {
            runIncrement(-100L, 0);
        }

        assertThat(usedBytes()).as("clamp must hold across repeated underflowing writes").isEqualTo(0L);
    }

    // ----- helpers -----

    private void seed(long bytes, int count) {
        jdbc.update(
                "INSERT INTO storage.tenant_storage_breakdown(tenant_id, category, used_bytes, item_count, calculated_at) "
                        + "VALUES (:tid, :cat, :b, :c, now()) "
                        + "ON CONFLICT (tenant_id, category) DO UPDATE "
                        + "SET used_bytes = :b, item_count = :c, calculated_at = now()",
                new MapSqlParameterSource()
                        .addValue("tid", TENANT).addValue("cat", CATEGORY)
                        .addValue("b", bytes).addValue("c", count));
    }

    private void runIncrement(long deltaBytes, int deltaCount) {
        jdbc.update(incrementSql, new MapSqlParameterSource()
                .addValue("tenantId", TENANT)
                .addValue("category", CATEGORY)
                .addValue("deltaBytes", deltaBytes)
                .addValue("deltaCount", deltaCount));
    }

    private long usedBytes() {
        return readRow().get("used_bytes") instanceof Number n ? n.longValue() : -1L;
    }

    private int itemCount() {
        return readRow().get("item_count") instanceof Number n ? n.intValue() : -1;
    }

    private Map<String, Object> readRow() {
        return jdbc.queryForMap(
                "SELECT used_bytes, item_count FROM storage.tenant_storage_breakdown "
                        + "WHERE tenant_id = :tid AND category = :cat",
                new MapSqlParameterSource().addValue("tid", TENANT).addValue("cat", CATEGORY));
    }
}

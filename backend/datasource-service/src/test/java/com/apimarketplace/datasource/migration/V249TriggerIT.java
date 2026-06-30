package com.apimarketplace.datasource.migration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@code V249__datasource_items_bump_parent_updated_at.sql} -
 * the migration that adds 3 statement-level triggers on
 * {@code datasource.data_source_items} so that any INSERT/UPDATE/DELETE on a row
 * bumps the parent {@code data_sources.updated_at = NOW()}.
 *
 * <p>Why this test exists: the user's complaint was "workflow Gmail Auto-Labeler
 * updates table rows but the table doesn't move to top of Activity tab." That
 * symptom is caused by the parent's {@code updated_at} not advancing on
 * child-row CRUD. V249 closes the gap; this test pins the contract so the
 * next migration that touches the datasource schema can't silently regress it.
 *
 * <p>Pattern mirrors {@code V109TriggerIT} in agent-service: minimal V9-shape
 * baseline + apply V249 verbatim + assert trigger behavior. Real Postgres
 * required because H2 does not support {@code REFERENCING NEW TABLE} syntax.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V249 data_source_items → data_sources.updated_at bump triggers (Postgres IT)")
class V249TriggerIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("datasource_test")
            .withUsername("test")
            .withPassword("test");

    private static final String V249_PATH =
            "../migration-service/src/main/resources/db/migration/"
                    + "V249__datasource_items_bump_parent_updated_at.sql";

    private JdbcTemplate jdbc;

    @BeforeAll
    void applyV249OnMinimalBaseline() throws IOException {
        String jdbcUrl = POSTGRES.getJdbcUrl();
        DriverManagerDataSource ds = new DriverManagerDataSource(
                jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        this.jdbc = new JdbcTemplate(ds);

        // Minimal V9-shape baseline. Only the columns + FK V249 depends on -
        // not the full schema (idx_dsi_data_gin and others are irrelevant here).
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS datasource");
        jdbc.execute("""
                CREATE TABLE datasource.data_sources (
                    id          BIGSERIAL PRIMARY KEY,
                    tenant_id   VARCHAR(255) NOT NULL,
                    name        VARCHAR(255) NOT NULL,
                    source_type VARCHAR(50)  NOT NULL DEFAULT 'manual',
                    source_config JSONB     NOT NULL DEFAULT '{}'::jsonb,
                    status      VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
                    next_row_index INTEGER   NOT NULL DEFAULT 0,
                    created_at  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    updated_at  TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    created_by  VARCHAR(255) NOT NULL DEFAULT 'system'
                )
                """);
        jdbc.execute("""
                CREATE TABLE datasource.data_source_items (
                    id             BIGSERIAL PRIMARY KEY,
                    data_source_id BIGINT NOT NULL REFERENCES datasource.data_sources(id) ON DELETE CASCADE,
                    tenant_id      VARCHAR(255) NOT NULL,
                    data           JSONB NOT NULL,
                    priority       INTEGER NOT NULL DEFAULT 0,
                    row_index      INTEGER NOT NULL DEFAULT 0,
                    created_at     TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    updated_at     TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
                )
                """);

        // Apply V249 verbatim from the migration-service sources - reading the
        // file (vs duplicating SQL) guarantees test/prod parity.
        String v249 = Files.readString(Paths.get(V249_PATH), StandardCharsets.UTF_8);
        jdbc.execute(v249);
    }

    @Test
    @DisplayName("INSERT on data_source_items bumps parent data_sources.updated_at to NOW()")
    void insertBumpsParent() throws InterruptedException {
        long dsId = insertTable("Gmail emails");
        Instant before = readUpdatedAt(dsId);

        // Sleep to make the timestamp delta detectable on fast clocks.
        Thread.sleep(20);
        jdbc.update("INSERT INTO datasource.data_source_items "
                + "(data_source_id, tenant_id, data) VALUES (?, ?, ?::jsonb)",
                dsId, "tenant-1", "{\"from\":\"alice@example.com\"}");

        Instant after = readUpdatedAt(dsId);
        assertThat(after)
                .as("INSERT on the child row must bump the parent's updated_at - the V249 contract")
                .isAfter(before);
    }

    @Test
    @DisplayName("UPDATE on data_source_items bumps parent data_sources.updated_at")
    void updateBumpsParent() throws InterruptedException {
        long dsId = insertTable("Airbnb listings");
        long itemId = jdbc.queryForObject(
                "INSERT INTO datasource.data_source_items "
                        + "(data_source_id, tenant_id, data) VALUES (?, ?, ?::jsonb) RETURNING id",
                Long.class, dsId, "tenant-1", "{\"city\":\"Paris\"}");

        Instant before = readUpdatedAt(dsId);
        Thread.sleep(20);
        jdbc.update("UPDATE datasource.data_source_items SET data = ?::jsonb WHERE id = ?",
                "{\"city\":\"Lyon\"}", itemId);

        Instant after = readUpdatedAt(dsId);
        assertThat(after)
                .as("UPDATE on the child row must bump the parent's updated_at")
                .isAfter(before);
    }

    @Test
    @DisplayName("DELETE on data_source_items bumps parent data_sources.updated_at")
    void deleteBumpsParent() throws InterruptedException {
        long dsId = insertTable("Stale leads");
        long itemId = jdbc.queryForObject(
                "INSERT INTO datasource.data_source_items "
                        + "(data_source_id, tenant_id, data) VALUES (?, ?, ?::jsonb) RETURNING id",
                Long.class, dsId, "tenant-1", "{\"lead\":\"old\"}");

        Instant before = readUpdatedAt(dsId);
        Thread.sleep(20);
        jdbc.update("DELETE FROM datasource.data_source_items WHERE id = ?", itemId);

        Instant after = readUpdatedAt(dsId);
        assertThat(after)
                .as("DELETE on the child row must bump the parent's updated_at")
                .isAfter(before);
    }

    @Test
    @DisplayName("Bulk INSERT (multi-row statement) fires the trigger once and bumps parent once")
    void bulkInsertSingleTriggerFire() throws InterruptedException {
        long dsId = insertTable("Gmail emails bulk");
        Instant before = readUpdatedAt(dsId);

        Thread.sleep(20);
        // Single multi-row INSERT statement - Gmail Auto-Labeler bulk-insert
        // shape. Statement-level trigger fires ONCE for the whole batch.
        jdbc.update("INSERT INTO datasource.data_source_items "
                + "(data_source_id, tenant_id, data) VALUES "
                + "(?, ?, ?::jsonb), (?, ?, ?::jsonb), (?, ?, ?::jsonb)",
                dsId, "tenant-1", "{\"i\":1}",
                dsId, "tenant-1", "{\"i\":2}",
                dsId, "tenant-1", "{\"i\":3}");

        Instant after = readUpdatedAt(dsId);
        assertThat(after).isAfter(before);
    }

    @Test
    @DisplayName("Cascade DELETE of parent does not error - trigger bumps a row being deleted is a no-op")
    void cascadeDeleteIsHarmless() {
        long dsId = insertTable("To be deleted");
        jdbc.update("INSERT INTO datasource.data_source_items "
                + "(data_source_id, tenant_id, data) VALUES (?, ?, ?::jsonb)",
                dsId, "tenant-1", "{\"x\":1}");

        // Documented behavior: PG cascades children before parent vanishes, so
        // the DELETE trigger fires statement-level and the parent UPDATE
        // bumps a row about to be removed in the same tx. Must not raise.
        jdbc.update("DELETE FROM datasource.data_sources WHERE id = ?", dsId);

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM datasource.data_sources WHERE id = ?", Integer.class, dsId);
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("V249 is re-runnable - DROP TRIGGER IF EXISTS guards make the migration idempotent")
    void reapplyingMigrationIsIdempotent() throws IOException {
        String v249 = Files.readString(Paths.get(V249_PATH), StandardCharsets.UTF_8);
        // Apply a second time - must not throw "trigger already exists".
        jdbc.execute(v249);
        // Verify behavior still holds.
        long dsId = insertTable("Idempotency check");
        Instant before = readUpdatedAt(dsId);
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        jdbc.update("INSERT INTO datasource.data_source_items "
                + "(data_source_id, tenant_id, data) VALUES (?, ?, ?::jsonb)",
                dsId, "tenant-1", "{\"after\":\"reapply\"}");
        assertThat(readUpdatedAt(dsId)).isAfter(before);
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private long insertTable(String name) {
        return jdbc.queryForObject(
                "INSERT INTO datasource.data_sources (tenant_id, name) VALUES (?, ?) RETURNING id",
                Long.class, "tenant-1", name);
    }

    private Instant readUpdatedAt(long dsId) {
        OffsetDateTime ts = jdbc.queryForObject(
                "SELECT updated_at FROM datasource.data_sources WHERE id = ?",
                OffsetDateTime.class, dsId);
        return ts != null ? ts.toInstant() : null;
    }
}

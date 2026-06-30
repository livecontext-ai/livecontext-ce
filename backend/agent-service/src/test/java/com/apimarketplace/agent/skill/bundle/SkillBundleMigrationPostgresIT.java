package com.apimarketplace.agent.skill.bundle;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-Postgres e2e for the V374 skill-bundle migration. Unit tests mock the repositories, so
 * the actual DDL and its partial indexes never run there. This applies the REAL
 * {@code V374__skill_bundle_infra.sql} against a live Postgres (on a minimal pre-existing
 * {@code agent.skills} slice) and pins the constraint semantics the applier relies on:
 *
 * <ul>
 *   <li>{@code source_bundle_key} is added; its partial unique index rejects a duplicate
 *       non-null key (the applier's idempotent upsert key) yet allows many NULLs (every
 *       non-bundle skill).</li>
 *   <li>{@code skill_bundles} allows at most one {@code is_active=true} row
 *       ({@code idx_skill_bundles_one_active}) - the activate path depends on this.</li>
 *   <li>{@code skill_bundle_sync_status} is seeded as a single CHECK(id=1) row.</li>
 * </ul>
 *
 * <p>Loads the actual migration file (so a drift in the SQL is caught here), and is skipped
 * without Docker or when the migration file is not locatable from the module cwd.
 */
@Testcontainers
@DisplayName("V374 skill-bundle migration - constraints against real Postgres")
class SkillBundleMigrationPostgresIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void setUpClass() throws Exception {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available - SkillBundleMigrationPostgresIT skipped");

        String v374 = loadV374();
        Assumptions.assumeTrue(v374 != null,
                "V374__skill_bundle_infra.sql not found from module cwd - skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        // Minimal pre-existing slice the migration ALTERs. The real skills table has many more
        // columns; V374 only needs `skills` to exist to add source_bundle_key + its index.
        jdbc.execute("CREATE SCHEMA agent");
        jdbc.execute("CREATE TABLE agent.skills ("
                + "  id UUID PRIMARY KEY,"
                + "  name TEXT,"
                + "  is_global BOOLEAN NOT NULL DEFAULT FALSE,"
                + "  is_active BOOLEAN NOT NULL DEFAULT TRUE"
                + ")");

        // Run the REAL migration. The whole script (SET search_path + DDL) executes on one
        // connection so the search_path applies to the subsequent unqualified statements.
        jdbc.execute(v374);
    }

    private static String loadV374() {
        String[] candidates = {
                "../migration-service/src/main/resources/db/migration/V374__skill_bundle_infra.sql",
                "backend/migration-service/src/main/resources/db/migration/V374__skill_bundle_infra.sql",
        };
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.exists(p)) {
                try {
                    return Files.readString(p);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("adds source_bundle_key as VARCHAR(64)")
    void addsSourceBundleKeyColumn() {
        Integer len = jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                + "WHERE table_schema='agent' AND table_name='skills' AND column_name='source_bundle_key'",
                Integer.class);
        assertThat(len).isEqualTo(64);
    }

    @Test
    @DisplayName("partial unique index rejects a duplicate non-null source_bundle_key (the applier's upsert key) but allows many NULLs")
    void sourceBundleKeyPartialUnique() {
        // Many NULL source_bundle_key rows are fine (every non-bundle skill).
        insertSkill(UUID.randomUUID(), null);
        insertSkill(UUID.randomUUID(), null);

        // First bundle row for key "cloud-uuid-1" is fine.
        insertSkill(UUID.randomUUID(), "cloud-uuid-1");
        // A second row for the SAME cloud key must be rejected (idempotent upsert guarantee).
        assertThatThrownBy(() -> insertSkill(UUID.randomUUID(), "cloud-uuid-1"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A different cloud key is fine.
        insertSkill(UUID.randomUUID(), "cloud-uuid-2");
    }

    @Test
    @DisplayName("skill_bundles allows at most one active row (idx_skill_bundles_one_active)")
    void onlyOneActiveBundle() {
        insertBundle(1001L, true);
        // A second active row is rejected by the partial unique index.
        assertThatThrownBy(() -> insertBundle(1002L, true))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Inactive rows are unconstrained.
        insertBundle(1003L, false);
        insertBundle(1004L, false);
    }

    @Test
    @DisplayName("skill_bundle_sync_status is seeded as a single CHECK(id=1) row")
    void syncStatusSingletonSeeded() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM agent.skill_bundle_sync_status", Integer.class);
        assertThat(count).isEqualTo(1);
        Short id = jdbc.queryForObject("SELECT id FROM agent.skill_bundle_sync_status", Short.class);
        assertThat(id).isEqualTo((short) 1);
        // The CHECK(id=1) rejects any other id.
        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO agent.skill_bundle_sync_status (id) VALUES (2)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertSkill(UUID id, String sourceBundleKey) {
        jdbc.update("INSERT INTO agent.skills (id, name, is_global, source_bundle_key) VALUES (?, ?, ?, ?)",
                id, "s", sourceBundleKey != null, sourceBundleKey);
    }

    private void insertBundle(long version, boolean active) {
        jdbc.update("INSERT INTO agent.skill_bundles "
                + "(version, schema_version, checksum, signature, signing_key_id, issuer, "
                + " skill_count, raw_bytes_size, is_active) "
                + "VALUES (?, 1, ?, 's', 'k', 'i', 1, 10, ?)",
                version, "c".repeat(64), active);
    }
}

package com.apimarketplace.auth.migration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-Postgres regression for {@code V547__organization_invitation_pending_unique.sql}.
 *
 * <p>The table is created exactly as V3 created it (with the plain
 * {@code UNIQUE(organization_id, email, status)} whose default name is the one prod carries),
 * then V547 runs under the {@code orchestrator} search_path the deploy uses. What only a real
 * database can show: the partial index rejects a second PENDING row while ACCEPTED and
 * CANCELLED duplicates (re-invite after removal, second decline) are allowed, and the
 * migration is idempotent.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V547 one PENDING invitation per (organization, email) - real Postgres")
class OrganizationInvitationPendingUniqueV547MigrationPostgresTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

    static JdbcTemplate jdbc;
    static String beforeEach;
    static String v547;

    @BeforeAll
    static void setUpClass() {
        v547 = loadMigration("V547__organization_invitation_pending_unique.sql");
        beforeEach = loadMigration("beforeEachMigrate.sql");
        Assumptions.assumeTrue(v547 != null && beforeEach != null,
                "migration files not found from module cwd - skipped");

        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("CREATE SCHEMA orchestrator");
        jdbc.execute("CREATE SCHEMA auth");
        // The V3 shape of the table (FKs dropped, they are irrelevant to the index).
        jdbc.execute("""
                CREATE TABLE auth.organization_invitation (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    organization_id UUID NOT NULL,
                    email VARCHAR(255) NOT NULL,
                    role VARCHAR(20) NOT NULL DEFAULT 'member',
                    token VARCHAR(255) NOT NULL UNIQUE,
                    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    invited_by BIGINT NOT NULL,
                    created_at TIMESTAMPTZ DEFAULT NOW(),
                    expires_at TIMESTAMPTZ NOT NULL,
                    accepted_at TIMESTAMPTZ,
                    UNIQUE(organization_id, email, status)
                )""");
        assertThat(constraintNames()).contains("organization_invitation_organization_id_email_status_key");

        jdbc.execute(beforeEach + "\n" + v547);
        // Idempotent: a second run is a no-op, not an error.
        jdbc.execute(beforeEach + "\n" + v547);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM auth.organization_invitation");
    }

    @Test
    @DisplayName("the plain (organization_id, email, status) constraint is gone")
    void plainConstraintDropped() {
        assertThat(constraintNames()).doesNotContain("organization_invitation_organization_id_email_status_key");
    }

    @Test
    @DisplayName("a second PENDING invitation for the same address in the same organization is rejected")
    void secondPendingRejected() {
        insert("a@x.io", "PENDING");

        assertThatThrownBy(() -> insert("a@x.io", "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_organization_invitation_pending");
    }

    @Test
    @DisplayName("re-invite after removal and a second decline: ACCEPTED and CANCELLED duplicates are allowed")
    void acceptedAndCancelledDuplicatesAllowed() {
        insert("a@x.io", "ACCEPTED");
        insert("a@x.io", "ACCEPTED");
        insert("a@x.io", "CANCELLED");
        insert("a@x.io", "CANCELLED");
        insert("a@x.io", "EXPIRED");
        insert("a@x.io", "EXPIRED");
        insert("a@x.io", "PENDING");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM auth.organization_invitation WHERE email = 'a@x.io'", Long.class))
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("PENDING for the same address in ANOTHER organization is allowed")
    void pendingInAnotherOrganizationAllowed() {
        insert("a@x.io", "PENDING");
        jdbc.update("INSERT INTO auth.organization_invitation (organization_id, email, token, status, invited_by, expires_at) "
                + "VALUES (?, 'a@x.io', ?, 'PENDING', 1, NOW() + INTERVAL '7 days')", UUID.randomUUID(), UUID.randomUUID().toString());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.organization_invitation", Long.class)).isEqualTo(2L);
    }

    private static void insert(String email, String status) {
        jdbc.update("INSERT INTO auth.organization_invitation (organization_id, email, token, status, invited_by, expires_at) "
                + "VALUES (?, ?, ?, ?, 1, NOW() + INTERVAL '7 days')", ORG, email, UUID.randomUUID().toString(), status);
    }

    private static List<String> constraintNames() {
        return jdbc.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = 'auth.organization_invitation'::regclass",
                String.class);
    }

    private static String loadMigration(String name) {
        for (String prefix : new String[]{"", "../", "../../"}) {
            Path p = Path.of(prefix + "backend/migration-service/src/main/resources/db/migration/" + name);
            Path alt = Path.of(prefix + "migration-service/src/main/resources/db/migration/" + name);
            for (Path candidate : new Path[]{p, alt}) {
                if (Files.exists(candidate)) {
                    try {
                        return Files.readString(candidate);
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}

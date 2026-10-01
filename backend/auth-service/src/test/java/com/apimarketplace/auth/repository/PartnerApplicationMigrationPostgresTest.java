package com.apimarketplace.auth.repository;

import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V553 on a real Postgres. The service refuses a second open application, but only the partial
 * unique index holds under a double submit, and {@code ddl-auto} (what the Spring tests build
 * their schema with) cannot express a partial index or a CHECK. So this runs the REAL migration,
 * twice to prove it re-runs cleanly, and asserts on its constraints.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V553 partner applications migration - constraints on a real Postgres")
class PartnerApplicationMigrationPostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CREDENTIAL_TEST_PG",
            "it is the only proof that a double submit cannot open two applications for one user, "
                    + "which only V553's partial unique index enforces");

    private JdbcTemplate jdbc;

    private static String migration(String name) throws Exception {
        String relative = "migration-service/src/main/resources/db/migration/" + name;
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(relative);
            if (Files.isRegularFile(file)) return Files.readString(file);
        }
        throw new IllegalStateException("could not locate " + relative + " from " + here);
    }

    @BeforeAll
    void setUpSchema() throws Exception {
        DB.require();
        DriverManagerDataSource ds = new DriverManagerDataSource(DB.url(), DB.user(), DB.password());
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("DROP TABLE IF EXISTS auth.partner_application CASCADE");
        String v553 = migration("V553__partner_application.sql");
        jdbc.execute(v553);
        jdbc.execute(v553); // idempotent: a re-run must not fail
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE auth.partner_application RESTART IDENTITY");
    }

    private void insert(long userId, String status, Long codeId) {
        jdbc.update("INSERT INTO auth.partner_application (user_id, status, company_name, reward_code_id) VALUES (?, ?, 'Acme', ?)",
                userId, status, codeId);
    }

    @Test
    @DisplayName("one PENDING application per user: a second one is refused by the database")
    void onePendingPerUser() {
        insert(7, "PENDING", null);

        assertThatThrownBy(() -> insert(7, "PENDING", null)).isInstanceOf(DataIntegrityViolationException.class);
        insert(8, "PENDING", null); // another user is unaffected
    }

    @Test
    @DisplayName("a decided application does not block a new one: a rejected applicant can apply again")
    void decidedDoesNotBlock() {
        insert(7, "REJECTED", null);
        insert(7, "APPROVED", 99L);

        insert(7, "PENDING", null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.partner_application WHERE user_id = 7", Long.class))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("an APPROVED row must carry its code, and an unknown status is refused")
    void shapeChecks() {
        assertThatThrownBy(() -> insert(7, "APPROVED", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(7, "MAYBE", null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("created_at defaults to now() (the entity leaves it to the database)")
    void createdAtDefault() {
        insert(7, "PENDING", null);

        assertThat(jdbc.queryForObject("SELECT created_at IS NOT NULL FROM auth.partner_application", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("account purge: the applicant's rows are deleted, a purged reviewer is unlinked but the decision kept")
    void purgeStatementsOnRealTable() {
        insert(7, "PENDING", null);
        jdbc.update("INSERT INTO auth.partner_application (user_id, status, company_name, reward_code_id, reviewed_by) "
                + "VALUES (8, 'APPROVED', 'Beta', 99, 1)");

        jdbc.update(com.apimarketplace.auth.service.AccountPurgeService.DELETE_PARTNER_APPLICATIONS_SQL, 7L);
        jdbc.update(com.apimarketplace.auth.service.AccountPurgeService.UNLINK_PARTNER_APPLICATION_REVIEWER_SQL, 1L);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.partner_application WHERE user_id = 7", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status || ':' || coalesce(reviewed_by::text, 'none') "
                + "FROM auth.partner_application WHERE user_id = 8", String.class)).isEqualTo("APPROVED:none");
    }
}

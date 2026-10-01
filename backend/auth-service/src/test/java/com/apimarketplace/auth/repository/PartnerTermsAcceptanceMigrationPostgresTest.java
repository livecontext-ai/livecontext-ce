package com.apimarketplace.auth.repository;

import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V557 on a real Postgres, with the REAL record statement. {@code ddl-auto} knows neither the
 * CHECK constraint, the unique pair nor the column default, so this runs the migration itself,
 * twice to prove it re-runs cleanly, then executes the exact SQL of
 * {@link PartnerTermsAcceptanceRepository#record} (read from its annotation): the statement that
 * keeps the FIRST acceptance of a version, the one that formed the contract.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V557 partner terms acceptance migration and the record statement - real Postgres")
class PartnerTermsAcceptanceMigrationPostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CREDENTIAL_TEST_PG",
            "it is the only proof that an acceptance of the partner terms is recorded once and never overwritten");

    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private String recordSql;

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
        named = new NamedParameterJdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("DROP TABLE IF EXISTS auth.partner_terms_acceptance CASCADE");
        String v557 = migration("V557__partner_terms_acceptance.sql");
        jdbc.execute(v557);
        jdbc.execute(v557); // idempotent: a re-run must not fail
        recordSql = PartnerTermsAcceptanceRepository.class
                .getMethod("record", Long.class, String.class, String.class, Instant.class, String.class, String.class, String.class)
                .getAnnotation(Query.class).value();
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE auth.partner_terms_acceptance");
    }

    private int record(long userId, String version, Instant at, String source, String ip, String ua) {
        return named.update(recordSql, new MapSqlParameterSource()
                .addValue("userId", userId).addValue("version", version).addValue("fingerprint", "sha256:" + version)
                .addValue("now", Timestamp.from(at))
                .addValue("source", source).addValue("ip", ip).addValue("userAgent", ua));
    }

    private Map<String, Object> row(long userId, String version) {
        return jdbc.queryForMap("SELECT terms_fingerprint, accepted_at, source, ip_address, user_agent FROM auth.partner_terms_acceptance "
                + "WHERE user_id = ? AND terms_version = ?", userId, version);
    }

    @Test
    @DisplayName("record stores the acceptance with its evidence")
    void recordStores() {
        Instant at = Instant.parse("2026-10-02T09:00:00Z");

        assertThat(record(7, "2026-10-01", at, "APPLICATION", "203.0.113.7", "Mozilla/5.0")).isEqualTo(1);

        assertThat(row(7, "2026-10-01")).containsEntry("source", "APPLICATION")
                .containsEntry("ip_address", "203.0.113.7").containsEntry("user_agent", "Mozilla/5.0")
                .containsEntry("terms_fingerprint", "sha256:2026-10-01");
        assertThat(((Timestamp) row(7, "2026-10-01").get("accepted_at")).toInstant()).isEqualTo(at);
    }

    @Test
    @DisplayName("accepting the same version again writes nothing: the first acceptance, date and evidence, stands")
    void firstAcceptanceStands() {
        Instant first = Instant.parse("2026-10-02T09:00:00Z");
        record(7, "2026-10-01", first, "APPLICATION", "203.0.113.7", "first");

        assertThat(record(7, "2026-10-01", Instant.parse("2026-11-01T00:00:00Z"), "DASHBOARD", "198.51.100.4", "second"))
                .isZero();

        assertThat(row(7, "2026-10-01")).containsEntry("source", "APPLICATION").containsEntry("user_agent", "first");
        assertThat(((Timestamp) row(7, "2026-10-01").get("accepted_at")).toInstant()).isEqualTo(first);
    }

    @Test
    @DisplayName("each new version is a new row: the history of what a partner accepted is kept")
    void newVersionIsANewRow() {
        record(7, "2026-10-01", Instant.parse("2026-10-02T00:00:00Z"), "APPLICATION", null, null);
        record(7, "2027-03-01", Instant.parse("2027-03-05T00:00:00Z"), "DASHBOARD", null, null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth.partner_terms_acceptance WHERE user_id = 7", Long.class))
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("an acceptance without the fingerprint of the accepted text is refused by the database")
    void fingerprintRequired() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO auth.partner_terms_acceptance (user_id, terms_version, source) "
                + "VALUES (7, '2026-10-01', 'DASHBOARD')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a source other than APPLICATION or DASHBOARD is refused by the database")
    void unknownSourceRefused() {
        assertThatThrownBy(() -> record(7, "2026-10-01", Instant.now(), "EMAIL", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("defaults: a bare row is dated now, the evidence may be missing (a request without an address)")
    void defaults() {
        jdbc.update("INSERT INTO auth.partner_terms_acceptance (user_id, terms_version, terms_fingerprint, source) "
                + "VALUES (7, '2026-10-01', 'sha256:x', 'DASHBOARD')");

        Map<String, Object> r = row(7, "2026-10-01");
        assertThat(r.get("accepted_at")).isNotNull();
        assertThat(r.get("ip_address")).isNull();
    }
}

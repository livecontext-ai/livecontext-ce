package com.apimarketplace.auth.integration;

import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Base for auth-service Spring tests whose proof needs a REAL PostgreSQL AND must run on CI.
 *
 * <p><b>Why not {@link AuthPostgresIntegrationTest}.</b> That base starts a Testcontainers
 * database and is {@code @EnabledIf(dockerAvailable)}. The CI runners expose no Docker socket, so
 * every class on it is SKIPPED there, and a skipped class reports the same green as a passing one.
 * This base uses the {@code postgres} service container CI provides instead, through
 * {@link ScratchPostgres} ({@code CREDENTIAL_TEST_PG_*}), which skips on a laptop with no URL but
 * REFUSES to skip when {@code CI} is set.
 *
 * <p><b>Why a DEDICATED database on that server.</b> The scratch database named by the URL is
 * shared: several raw-JDBC classes hand-create {@code auth.*} tables in it and assert on them. A
 * Spring context here builds the schema with {@code ddl-auto: create-drop}, which would drop and
 * recreate tables of the same names under those classes. So this base creates (once) a sibling
 * database, {@value #DEDICATED_DATABASE}, on the same server and points the context there; the
 * shared database is only used to issue the {@code CREATE DATABASE}. The name keeps {@code test}
 * as its own part, the same rule {@link ScratchPostgres} applies to the URL it is given.
 *
 * <p>Schema bootstrap mirrors {@code promo-it-init.sql}: the {@code auth} and {@code storage}
 * schemas the entities map into, and the ShedLock table Flyway creates in production.
 */
@ActiveProfiles("integration-test")
abstract class AuthScratchPostgresSpringTest {

    static final String DEDICATED_DATABASE = "credit_ledger_spring_test";

    /** PostgreSQL SQLState {@code duplicate_database}. */
    static final String DUPLICATE_DATABASE = "42P04";

    static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CREDENTIAL_TEST_PG",
            "it is the only CI proof that a refused charge replayed after a top-up is billed once, "
                    + "on the real partial unique index idx_cl_source_id_unique, under real "
                    + "transactions and locks");

    private static volatile String dedicatedUrl;

    @BeforeAll
    static void requireScratchPostgres() throws Exception {
        // Aborts (laptop, no URL) or fails (CI, no URL / unreachable / not a scratch name) BEFORE
        // the Spring context is built, which happens only when the first test instance is created.
        DB.require();
        dedicatedUrl();
    }

    /** The JDBC URL of the dedicated database, created on first use. */
    static synchronized String dedicatedUrl() throws Exception {
        if (dedicatedUrl != null) return dedicatedUrl;
        try (Connection admin = DriverManager.getConnection(DB.url(), DB.user(), DB.password());
             Statement st = admin.createStatement()) {
            boolean exists;
            try (ResultSet rs = st.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '" + DEDICATED_DATABASE + "'")) {
                exists = rs.next();
            }
            if (!exists) {
                try {
                    st.execute("CREATE DATABASE " + DEDICATED_DATABASE);
                } catch (java.sql.SQLException e) {
                    // Another JVM (a parallel fork, or a second job on the same server) created it
                    // between the check and here: that is the state we wanted.
                    if (!DUPLICATE_DATABASE.equals(e.getSQLState())) throw e;
                }
            }
        }
        String url = siblingDatabaseUrl(DB.url(), DEDICATED_DATABASE);
        try (Connection c = DriverManager.getConnection(url, DB.user(), DB.password());
             Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS auth");
            st.execute("CREATE SCHEMA IF NOT EXISTS storage");
            st.execute("CREATE TABLE IF NOT EXISTS auth.shedlock ("
                    + " name VARCHAR(64) NOT NULL PRIMARY KEY,"
                    + " lock_until TIMESTAMP NOT NULL,"
                    + " locked_at TIMESTAMP NOT NULL,"
                    + " locked_by VARCHAR(255) NOT NULL)");
        }
        dedicatedUrl = url;
        return url;
    }

    /** Same server, same parameters, another database: replace the path's database segment. */
    static String siblingDatabaseUrl(String jdbcUrl, String database) {
        String[] parts = jdbcUrl.split("\\?", 2);
        String base = parts[0];
        int authority = base.indexOf("//");
        int slash = base.indexOf('/', authority + 2);
        String withDb = (slash < 0 ? base : base.substring(0, slash)) + "/" + database;
        return parts.length > 1 ? withDb + "?" + parts[1] : withDb;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> {
            String url = dedicatedUrlUnchecked();
            return url + (url.contains("?") ? "&" : "?") + "currentSchema=auth";
        });
        r.add("spring.datasource.username", DB::user);
        r.add("spring.datasource.password", DB::password);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.jpa.properties.hibernate.default_schema", () -> "auth");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        // Metered mode: unlimited=true would make every credit assertion vacuously green.
        r.add("credit.unlimited", () -> "false");
        r.add("subscription.internal-renewal.cron", () -> "-");
        r.add("subscription.yearly-credit-cycle.cron", () -> "-");
    }

    private static String dedicatedUrlUnchecked() {
        try {
            return dedicatedUrl();
        } catch (Exception e) {
            throw new IllegalStateException("cannot prepare " + DEDICATED_DATABASE + " on " + DB.url(), e);
        }
    }
}

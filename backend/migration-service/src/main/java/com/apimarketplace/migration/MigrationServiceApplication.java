package com.apimarketplace.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class MigrationServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(MigrationServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(MigrationServiceApplication.class, args);
    }

    @Bean
    FlywayMigrationStrategy repairThenMigrate(DataSource dataSource) {
        return flyway -> {
            ensureMigrationSourceTimezoneGuc(dataSource);
            flyway.repair();
            flyway.migrate();
        };
    }

    /**
     * PR13: ensure `lc.migration.source_timezone` GUC is set on the database
     * BEFORE Flyway runs. V196 (TIMESTAMP→TIMESTAMPTZ promotion) reads this
     * via `current_setting()` and fail-fasts if unset - preserving its
     * operator-contract safety check ("yes, source TZ was UTC").
     *
     * Root cause of the 2026-05-12 15:46 UTC prod crashloop (correctly
     * diagnosed only at round-1 audit time): commit 0a487702a added
     * `?options=-c lc.migration.source_timezone=UTC` to application.yml's
     * JDBC URL, but `deploy-direct.sh` writes a SPRING_DATASOURCE_URL env
     * var that OVERRIDES the application.yml URL and previously had NO
     * `options=` suffix. Spring picks the env var over application.yml, so
     * the GUC never reached the JDBC connection in prod even after the
     * "fix" was deployed. NOT a PgBouncer issue - migration-service runs
     * direct on port 5432 (see deploy-direct.sh:624 "DDL needs persistent
     * connections, not PgBouncer").
     *
     * This hook is one of THREE layers fixing the gap simultaneously:
     *  (1) deploy-direct.sh re-adds `?options=...` to the prod URL (commit
     *      same round) - restores the original 0a487702a intent.
     *  (2) Hikari `connection-init-sql: SET lc.migration.source_timezone =
     *      'UTC'` (application.yml) - runs at every pool connection acquire,
     *      including pre-warmed minimum-idle connections that exist before
     *      this Java hook fires.
     *  (3) This ALTER DATABASE hook - DB-level setting, applied at session
     *      start for ALL future backend connections. Belt-and-braces for
     *      DR-restore + fresh setups where neither (1) nor (2) had time to
     *      take effect.
     *
     * Best-effort: any SQLException (insufficient_privilege, network glitch,
     * etc.) is logged as WARN, not thrown - never block migration-service
     * startup. If all three layers fail simultaneously, V196 will RAISE
     * EXCEPTION with message "V196: lc.migration.source_timezone is not set.
     * See migration header for instructions." - the recipe is in the V196
     * SQL file header lines 39-49 (SET via psql, ?options= in JDBC URL, or
     * PGOPTIONS env).
     */
    // Package-private for unit testing - the hook itself is invoked only via the
    // FlywayMigrationStrategy bean, but tests need direct access without booting Spring.
    static void ensureMigrationSourceTimezoneGuc(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            String dbName = conn.getCatalog();
            if (dbName == null || dbName.isBlank()) {
                log.warn("PR13: dataSource.getConnection().getCatalog() returned null/blank - "
                       + "cannot set lc.migration.source_timezone GUC. V196 may fail-fast on fresh setup.");
                return;
            }
            String safeName = dbName.replace("\"", "\"\"");
            String sql = String.format(
                "ALTER DATABASE \"%s\" SET lc.migration.source_timezone TO 'UTC'", safeName);
            stmt.execute(sql);
            log.info("PR13: ensured lc.migration.source_timezone=UTC on database \"{}\" via ALTER DATABASE", dbName);
        } catch (SQLException e) {
            log.warn("PR13: could not set lc.migration.source_timezone GUC (sqlstate={}, msg={}). "
                   + "If V196 hasn't applied yet, it will fail-fast - recover via manual "
                   + "`ALTER DATABASE <name> SET lc.migration.source_timezone TO 'UTC'`. "
                   + "On already-applied envs this is a harmless no-op.",
                   e.getSQLState(), e.getMessage());
        }
    }
}

package com.apimarketplace.orchestrator.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the one-H2-database-per-context fix of the {@code integration-test} profile.
 *
 * <p><b>The bug.</b> Every context on that profile opened the SAME named in-memory database
 * ({@code jdbc:h2:mem:integrationdb}, kept alive by {@code DB_CLOSE_DELAY=-1}). Closing one
 * context (a {@code @DirtiesContext} class) ran Hibernate's create-drop DROP under every context
 * still cached in the JVM, and the next repository test failed with 'Table "WORKFLOWS" not found',
 * only when run after a dirtied class. The URL now carries {@code ${random.uuid}}, bound once
 * per context.
 *
 * <p>Two contexts are built here the way surefire's cached ones coexist, in one JVM, from the
 * profile's real configuration: each must get its own database, and a context's database must
 * stay the same across its own connections (the uuid is bound once, not per connection).
 */
@DisplayName("integration-test profile: one H2 database per Spring context")
class IntegrationTestProfileH2IsolationTest {

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(DataSourceAutoConfiguration.class)
    static class DataSourceOnly {
    }

    private static ConfigurableApplicationContext context() {
        return new SpringApplicationBuilder(DataSourceOnly.class)
            .web(WebApplicationType.NONE)
            .profiles("integration-test")
            .logStartupInfo(false)
            .run();
    }

    private static String url(ConfigurableApplicationContext context) throws SQLException {
        return context.getBean(DataSource.class).unwrap(HikariDataSource.class).getJdbcUrl();
    }

    private static int probeTables(ConfigurableApplicationContext context) {
        Integer count = new JdbcTemplate(context.getBean(DataSource.class)).queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ISOLATION_PROBE'", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("two contexts in one JVM resolve different database names")
    void eachContextResolvesItsOwnDatabaseName() throws Exception {
        try (ConfigurableApplicationContext first = context();
             ConfigurableApplicationContext second = context()) {
            assertThat(url(first)).startsWith("jdbc:h2:mem:integrationdb-").doesNotContain("${");
            assertThat(url(second)).startsWith("jdbc:h2:mem:integrationdb-");
            assertThat(url(first)).isNotEqualTo(url(second));
        }
    }

    @Test
    @DisplayName("a table created in one context does not exist in another, and a dropped one is not dropped elsewhere")
    void contextsDoNotShareTables() {
        try (ConfigurableApplicationContext first = context();
             ConfigurableApplicationContext second = context()) {
            new JdbcTemplate(first.getBean(DataSource.class)).execute("CREATE TABLE isolation_probe (id INT)");
            new JdbcTemplate(second.getBean(DataSource.class)).execute("CREATE TABLE isolation_probe (id INT)");

            new JdbcTemplate(first.getBean(DataSource.class)).execute("DROP TABLE isolation_probe");

            assertThat(probeTables(first)).as("dropped in its own database").isZero();
            assertThat(probeTables(second)).as("the other context's table survives the drop").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a context keeps the same database across its own connections")
    void aContextSeesItsOwnTableOnEveryConnection() throws SQLException {
        try (ConfigurableApplicationContext context = context()) {
            HikariDataSource pool = context.getBean(DataSource.class).unwrap(HikariDataSource.class);
            new JdbcTemplate(pool).execute("CREATE TABLE isolation_probe (id INT)");
            pool.getHikariPoolMXBean().softEvictConnections();

            assertThat(probeTables(context)).isEqualTo(1);
        }
    }
}

package com.apimarketplace.auth.integration;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for auth-service tests that need a real PostgreSQL.
 *
 * <p><b>One container for all of them, started once and never stopped.</b> The JUnit
 * {@code @Testcontainers} + {@code @Container} idiom starts a fresh database per test CLASS.
 * With two such classes in one surefire run that meant two ~20 s boots competing for the daemon,
 * and it failed exactly that way here ("Container startup failed for image postgres:16-alpine",
 * green on retry). This is the documented singleton-container pattern: the JVM reaps it on exit
 * via Ryuk, so nothing leaks.
 *
 * <p><b>{@code @EnabledIf} rather than {@code @Testcontainers(disabledWithoutDocker)}</b> so the
 * skip is explicit and greppable. These classes are named {@code *Test}, not {@code *IT}, on
 * purpose: the repo configures no failsafe plugin, so its {@code *IT} classes never execute
 * under {@code mvn test} or in CI, and a regression suite for a money bug that never runs is
 * worth nothing. The trade is that a machine without Docker skips them rather than failing.
 */
@ActiveProfiles("integration-test")
@EnabledIf("dockerAvailable")
abstract class AuthPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("apimarketplace")
            .withUsername("test")
            .withPassword("test")
            .withInitScript("promo-it-init.sql")
            .withReuse(false);

    static {
        if (dockerAvailable()) {
            POSTGRES.start();
        }
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=auth");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.jpa.properties.hibernate.default_schema", () -> "auth");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        // Metered mode: unlimited=true short-circuits every grant and would make the credit
        // assertions vacuously green.
        r.add("credit.unlimited", () -> "false");
        // These classes drive the renewal pass explicitly. A live hourly cron means a long class
        // occasionally straddles the top of the hour and the real scheduler mutates a fixture
        // mid-assert. "-" is Spring's disabled marker.
        r.add("subscription.internal-renewal.cron", () -> "-");
        r.add("subscription.yearly-credit-cycle.cron", () -> "-");
    }
}

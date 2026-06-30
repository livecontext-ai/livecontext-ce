package com.apimarketplace.orchestrator.integration.repository;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation for Orchestrator Service data layer integration tests.
 *
 * <p>Combines {@link DataJpaTest} with {@link ActiveProfiles} "integration-test"
 * for repository-focused testing using an H2 in-memory database.</p>
 *
 * <p>Disables SQL script initialization ({@code spring.sql.init.mode=never}) because
 * {@code @DataJpaTest} loads a sliced context where the extra non-JPA tables from
 * {@code schema-e2e-extra.sql} are not needed - Hibernate's {@code create-drop}
 * handles all JPA entity tables automatically.</p>
 *
 * <p>Uses {@code @AutoConfigureTestDatabase(replace = NONE)} to use the configured
 * H2 datasource URL (with PostgreSQL mode and dual-schema INIT) instead of the
 * default embedded database replacement.</p>
 *
 * <p>Tests annotated with this annotation will:
 * <ul>
 *   <li>Start a minimal Spring context with JPA components only</li>
 *   <li>Use H2 in-memory database with PostgreSQL compatibility mode</li>
 *   <li>Automatically roll back database changes after each test (via @Transactional on @DataJpaTest)</li>
 *   <li>Scan for @Entity and @Repository classes</li>
 *   <li>Disable Flyway migrations</li>
 *   <li>Provide TestEntityManager for test data setup</li>
 * </ul>
 *
 * <p>This annotation is intentionally separate from {@link com.apimarketplace.orchestrator.integration.IntegrationTest}
 * which starts a full Spring Boot application context. Use this annotation for pure repository/data layer tests
 * that do not require service-layer beans.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.sql.init.mode=never")
@ActiveProfiles("integration-test")
public @interface DataJpaIntegrationTest {
}

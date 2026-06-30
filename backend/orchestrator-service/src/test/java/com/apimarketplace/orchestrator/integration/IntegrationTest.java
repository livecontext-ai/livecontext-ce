package com.apimarketplace.orchestrator.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation for Orchestrator Service integration tests.
 *
 * <p>Combines {@link SpringBootTest}, {@link ActiveProfiles} with "integration-test",
 * and {@link Transactional} for automatic rollback after each test.</p>
 *
 * <p>Tests annotated with this annotation will:
 * <ul>
 *   <li>Start a full Spring application context</li>
 *   <li>Use H2 in-memory database (PostgreSQL compatibility mode, dual schemas: orchestrator + storage)</li>
 *   <li>Automatically roll back database changes after each test</li>
 *   <li>Disable Flyway migrations (schema created by Hibernate)</li>
 *   <li>Disable Temporal workflow engine</li>
 * </ul>
 *
 * <p>External services (Redis, S3/MinIO, Temporal, etc.) should be mocked using
 * {@code @MockBean} in individual test classes.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("integration-test")
@Transactional
public @interface IntegrationTest {
}

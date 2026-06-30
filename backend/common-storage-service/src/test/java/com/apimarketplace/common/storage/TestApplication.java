package com.apimarketplace.common.storage;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Minimal Spring Boot application for integration tests.
 * This module is a library, so it needs a test-only application class
 * to bootstrap the Spring context for @DataJpaTest and @SpringBootTest.
 */
@SpringBootApplication
@EntityScan("com.apimarketplace.common.storage")
@EnableJpaRepositories("com.apimarketplace.common.storage")
class TestApplication {
}

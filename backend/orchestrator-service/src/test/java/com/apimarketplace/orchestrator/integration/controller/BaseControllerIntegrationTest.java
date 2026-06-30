package com.apimarketplace.orchestrator.integration.controller;

import com.apimarketplace.orchestrator.integration.IntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for controller integration tests.
 * Provides common setup: MockMvc, ObjectMapper, and transactional rollback.
 *
 * <p>Uses the project's {@link IntegrationTest} meta-annotation which combines
 * {@code @SpringBootTest}, {@code @ActiveProfiles("integration-test")}, and
 * {@code @Transactional} for automatic rollback after each test.</p>
 *
 * <p>Adds {@code @AutoConfigureMockMvc} for HTTP contract testing through MockMvc
 * (verifying status codes, headers, and JSON serialization).</p>
 */
@IntegrationTest
@AutoConfigureMockMvc
public abstract class BaseControllerIntegrationTest {

    protected static final String TENANT_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
    protected static final String OTHER_TENANT_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    protected static final String X_USER_ID = "X-User-ID";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;
}

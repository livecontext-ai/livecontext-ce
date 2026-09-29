package com.apimarketplace.interfaces.config;

import com.apimarketplace.common.web.SharedApplicationScopeClient;
import com.apimarketplace.interfaces.client.OrchestratorCascadeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the orchestrator-side cascade client used by
 * {@code InterfaceService.deleteInterface} to scrub plan references
 * before the {@code interface.interfaces} row is removed.
 *
 * <p>{@code @ConditionalOnMissingBean}: the integration test harness
 * supplies a mocked {@code OrchestratorCascadeClient} via
 * {@code IntegrationTestConfig} (the orchestrator-service isn't
 * running in the test env, so the real client would throw on every
 * delete). Without this condition, Spring context caching across the
 * multi-test run can keep the production bean instead of the mock -
 * surfaces as a CI-only failure that doesn't reproduce when running
 * the test class in isolation (verified 2026-05-10).
 */
@Configuration
public class OrchestratorClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public OrchestratorCascadeClient orchestratorCascadeClient(
            @Value("${services.orchestrator-url:http://localhost:8099}") String orchestratorUrl) {
        return new OrchestratorCascadeClient(orchestratorUrl);
    }

    /**
     * Share-link scope checks (which interfaces belong to a shared application). Conditional so
     * the CE monolith, which also wires storage-service's copy, ends up with exactly one bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public SharedApplicationScopeClient sharedApplicationScopeClient(
            @Value("${services.orchestrator-url:http://localhost:8099}") String orchestratorUrl) {
        return new SharedApplicationScopeClient(orchestratorUrl);
    }
}

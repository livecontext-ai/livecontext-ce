package com.apimarketplace.orchestrator.services.state.elide;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Provides the dedicated {@code stateSnapshotMapper} bean used exclusively by
 * {@code StateSnapshotService} for state-snapshot JSONB write/read (P2.3
 * deliverable per design rev12 §3.5).
 *
 * <p>The mapper is a COPY of the default Spring-provided {@link ObjectMapper}
 * (preserves modules registered by Spring Boot - {@code JavaTimeModule},
 * {@code Jdk8Module}, etc.) with an additional {@link EpochStateRunningElideModule}
 * that conditionally omits the {@code runningNodeIds} field at write time
 * based on the per-call {@link EpochStateRunningElideSerializer#TENANT_ATTRIBUTE}
 * and the {@link TenantElideFlagResolver} per-tenant flag.
 *
 * <p>OTHER ObjectMappers in the application (the default Spring mapper used by
 * controllers, the canonicalizer mapper §7.4, agent-tool mapper, WS event mapper)
 * MUST NOT register the elide module. The design relies on this isolation:
 * - canonicalizer needs to see runningNodeIds for fixture round-trip stability
 * - agent-tool reads the field for status reporting
 * - controllers / WS events don't serialize EpochState directly
 *
 * <p>If a future refactor ever wires {@link EpochStateRunningElideModule} elsewhere,
 * an ArchUnit/integration test should fail loudly (P2.3.4 follow-up).
 */
@Configuration
public class StateSnapshotMapperConfig {

    /**
     * Build the dedicated state-snapshot mapper.
     *
     * <p>Uses {@link Jackson2ObjectMapperBuilder} (Spring Boot's auto-configured
     * builder bean) to create a FRESH {@link ObjectMapper} pre-configured with
     * the same module set / feature flags as the default mapper. This avoids
     * the circular-dependency hazard of injecting {@code ObjectMapper} directly
     * (Spring would see two mappers and try to inject {@code stateSnapshotMapper}
     * into itself).
     *
     * <p>Then registers the {@link EpochStateRunningElideModule} on top.
     *
     * @param mapperBuilder Spring Boot's auto-configured Jackson builder
     * @param flagResolver  per-tenant flag resolver - must be O(1) in-memory
     */
    @Bean(name = "stateSnapshotMapper")
    public ObjectMapper stateSnapshotMapper(
            Jackson2ObjectMapperBuilder mapperBuilder,
            TenantElideFlagResolver flagResolver) {
        ObjectMapper mapper = mapperBuilder.build();
        mapper.registerModule(new EpochStateRunningElideModule(flagResolver));
        return mapper;
    }
}

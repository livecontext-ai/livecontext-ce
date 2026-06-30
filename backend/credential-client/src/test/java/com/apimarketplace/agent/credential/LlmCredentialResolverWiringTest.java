package com.apimarketplace.agent.credential;

import com.apimarketplace.agent.resolver.LlmCredentialResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring regression for the 2026-05-09 Daily Email Digest fix
 * (deepseek workflow agent failed with "Provider deepseek is not configured").
 *
 * <p>Root cause: {@link CachedLlmCredentialResolver} only existed in agent-service,
 * so orchestrator-service - which runs {@code AbstractLLMProvider} in-process for
 * workflow-agent / classify / guardrail nodes - had no bean for
 * {@link LlmCredentialResolver}. The {@code @Autowired(required=false)} setter
 * stayed null and the provider fell back to the {@code @Value}-injected env var
 * path, which surfaces "Provider X is not configured" if the var is empty or
 * holds a placeholder.
 *
 * <p>2026-05-28 follow-up: classes moved out of {@code shared-agent-lib} (which
 * also drags in 20+ unrelated LLM-provider beans, schedulers, rate-limit factories
 * - too wide a blast radius for consumers that only want credential resolution)
 * into {@code credential-client} (the same lightweight jar already on every
 * consumer's classpath). The package name {@code com.apimarketplace.agent.credential}
 * is preserved verbatim so existing {@code @ComponentScan("com.apimarketplace.agent")}
 * declarations in {@code OrchestratorServiceApplication} and
 * {@code AgentServiceApplication} continue to pick up the beans with no scan
 * changes. Cross-artifact split-package is legal under classpath mode (Spring
 * Boot's default) - documented for future jpms readers.
 *
 * <p>This test pins the load-bearing annotations: if a future refactor strips
 * {@code @Component} or {@code @Repository}, Spring silently stops registering
 * the bean and the bug returns. Annotation-presence is far cheaper than a full
 * {@code @SpringBootTest} that spins a context (credential-client has no
 * application class to bootstrap one anyway). A higher-fidelity guard would be
 * a smoke {@code @SpringBootTest} in orchestrator-service / agent-service
 * itself - listed as nice-to-have.
 */
@DisplayName("LlmCredentialResolver wiring - Daily Email Digest regression")
class LlmCredentialResolverWiringTest {

    @Test
    @DisplayName("CachedLlmCredentialResolver carries @Component so Spring component-scan picks it up in orchestrator-service and agent-service")
    void cachedResolverHasComponentAnnotation() {
        assertThat(CachedLlmCredentialResolver.class.isAnnotationPresent(Component.class))
            .as("Removing @Component breaks auto-wiring in orchestrator-service (the in-process "
              + "consumer fixed by the 2026-05-09 move) and silently brings back the 'Provider X "
              + "is not configured' bug for workflow-agent / classify / guardrail / browser_agent.")
            .isTrue();
    }

    @Test
    @DisplayName("CachedLlmCredentialResolver implements the LlmCredentialResolver contract from credential-client")
    void cachedResolverImplementsContract() {
        assertThat(LlmCredentialResolver.class.isAssignableFrom(CachedLlmCredentialResolver.class))
            .as("AbstractLLMProvider's @Autowired setter requires the resolver to implement LlmCredentialResolver")
            .isTrue();
    }

    @Test
    @DisplayName("LlmCredentialRepository carries @Repository so it is registered as a Spring bean for CachedLlmCredentialResolver to inject")
    void repositoryHasRepositoryAnnotation() {
        assertThat(LlmCredentialRepository.class.isAnnotationPresent(Repository.class))
            .as("@Repository is required for the credential-client-backed repo to be a Spring bean - "
              + "without it, CachedLlmCredentialResolver fails to construct and Spring falls back "
              + "to a no-op resolver.")
            .isTrue();
    }

    @Test
    @DisplayName("CachedLlmCredentialResolver lives in the com.apimarketplace.agent.credential package - preserved across the 2026-05-28 move so consumer @ComponentScan stays unchanged")
    void resolverLivesInExpectedPackage() {
        // Sanity-check the package boundary. The classes physically moved from
        // shared-agent-lib into credential-client (lighter dep), but the
        // package name is intentionally preserved so consumer
        // @ComponentScan("com.apimarketplace.agent") declarations keep working
        // without per-service edits. If a future reorganization changes the
        // package, the absent-bean bug returns for every consumer.
        assertThat(CachedLlmCredentialResolver.class.getPackageName())
            .isEqualTo("com.apimarketplace.agent.credential");
        assertThat(LlmCredentialRepository.class.getPackageName())
            .isEqualTo("com.apimarketplace.agent.credential");
    }
}

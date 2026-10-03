package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * LC-073 / LC-006, CASA readiness round 3: {@code HttpExecutionService.setOutboundHttpClients} used
 * to be {@code @Autowired(required = false)}, so a missing {@link OutboundHttpClients} bean (a
 * component-scan mistake, a profile that never registers it, a future refactor) left
 * {@link HttpExecutionService#transport()} silently falling back to the unpinned {@code restTemplate}
 * field - every outbound call would still "work", just without the DNS-rebinding pin the whole class
 * exists to provide. Making the setter required turns that into a boot-time failure instead of a
 * silent, unpinned production.
 *
 * <p>Uses a real Spring context (not a mock), because the property under test - "does Spring itself
 * refuse to start" - cannot be observed by asserting on Java calls against an object nothing ever
 * instantiated. Both contexts here register the SAME {@link HttpExecutionService} bean definition
 * and differ only in whether an {@link OutboundHttpClients} bean is also registered.
 */
@DisplayName("HttpExecutionService - the pinned transport is REQUIRED, not an optional fallback (LC-073)")
class HttpExecutionServiceRequiredPinnedTransportTest {

    @Configuration
    static class WithoutPinnedClients {
        @Bean
        HttpExecutionService httpExecutionService() {
            return new HttpExecutionService(
                    mock(ApiToolParameterRepository.class),
                    mock(UserCredentialService.class),
                    mock(CredentialEncryptionService.class),
                    new ObjectMapper(),
                    mock(JdbcTemplate.class),
                    new RestTemplate(),
                    new ErrorPolicyEngine());
        }
        // No OutboundHttpClients bean registered - this is the pre-fix "missing bean" shape.
    }

    @Configuration
    static class WithPinnedClients {
        @Bean
        HttpExecutionService httpExecutionService() {
            return new HttpExecutionService(
                    mock(ApiToolParameterRepository.class),
                    mock(UserCredentialService.class),
                    mock(CredentialEncryptionService.class),
                    new ObjectMapper(),
                    mock(JdbcTemplate.class),
                    new RestTemplate(),
                    new ErrorPolicyEngine());
        }

        @Bean
        OutboundHttpClients outboundHttpClients() {
            return new OutboundHttpClients(2000, 2000, new ObjectMapper());
        }
    }

    @Test
    @DisplayName("regression: the context fails to start when OutboundHttpClients is missing")
    void contextRefusesToStartWithoutThePinnedClient() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(WithoutPinnedClients.class);

            assertThatThrownBy(ctx::refresh)
                    .isInstanceOf(UnsatisfiedDependencyException.class)
                    .as("pre-fix, this would have booted successfully with an unpinned transport - "
                            + "that silent fallback is exactly what this test forbids");
        }
    }

    @Test
    @DisplayName("the context starts and wires the pinned transport when the bean is present")
    void contextStartsAndWiresThePinnedTransport() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(WithPinnedClients.class)) {
            HttpExecutionService service = ctx.getBean(HttpExecutionService.class);
            OutboundHttpClients clients = ctx.getBean(OutboundHttpClients.class);

            assertThat(service.transport()).isSameAs(clients.restTemplate());
        }
    }
}

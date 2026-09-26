package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.credential.client.CredentialClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The gap fill is CLOUD only: on by default (cloud is the implicit default profile), and switched
 * off by the CE profile, where an ADMIN row minted locally would freeze the bundle-owned prices.
 */
@DisplayName("NeverPricedGenerationModelPublisher - on by default, off when the CE profile says so")
class NeverPricedGenerationModelPublisherWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(GenerationRegistry.class, () -> mock(GenerationRegistry.class))
            .withBean(CredentialClient.class, () -> mock(CredentialClient.class))
            .withUserConfiguration(NeverPricedGenerationModelPublisher.class);

    @Test
    @DisplayName("is created when the property is absent (the cloud)")
    void createdByDefault() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(NeverPricedGenerationModelPublisher.class));
    }

    @Test
    @DisplayName("is absent when catalog.generation.price-gap-fill.enabled=false (the CE profile)")
    void absentWhenDisabled() {
        runner.withPropertyValues("catalog.generation.price-gap-fill.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(NeverPricedGenerationModelPublisher.class));
    }

    @Test
    @DisplayName("the CE profile really carries the switch")
    void ceProfileTurnsItOff() throws Exception {
        java.nio.file.Path ce = java.nio.file.Path.of("..", "monolith-service", "src", "main", "resources", "application-ce.yml");
        String yml = java.nio.file.Files.readString(ce);
        assertThat(yml).containsPattern("price-gap-fill:\s*\n\s*enabled:\s*false");
    }
}

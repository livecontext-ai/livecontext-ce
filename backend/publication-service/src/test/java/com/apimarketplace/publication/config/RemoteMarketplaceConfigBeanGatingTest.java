package com.apimarketplace.publication.config;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.publication.repository.CeCloudLinkRepository;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.CeCloudLinkHeartbeatScheduler;
import com.apimarketplace.publication.service.CloudLinkService;
import com.apimarketplace.publication.service.RemoteMarketplaceService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.SnapshotCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bean-gating contract for {@link RemoteMarketplaceConfig}.
 *
 * <p>The configuration is annotated
 * {@code @ConditionalOnProperty(name = "marketplace.mode", havingValue = "remote")} with NO
 * {@code matchIfMissing}, so it defaults to false: the CE remote-marketplace beans
 * ({@link CloudLinkService}, {@link RemoteMarketplaceService}, {@link CeCloudLinkHeartbeatScheduler})
 * are wired ONLY on the cloud-linked CE monolith where {@code marketplace.mode=remote}. On a cloud
 * install or a plain CE (property unset, or {@code marketplace.mode=local}) these beans MUST NOT
 * exist, otherwise the install would carry an unconfigured cloud-link surface. These tests pin that
 * contract so a regression in the condition (flipping {@code matchIfMissing} or renaming the
 * property) is caught.
 */
@DisplayName("RemoteMarketplaceConfig - marketplace.mode=remote bean gating")
class RemoteMarketplaceConfigBeanGatingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RemoteMarketplaceConfigDependencies.class)
            .withUserConfiguration(RemoteMarketplaceConfig.class)
            // A non-default, non-blank key so CloudLinkService can construct when the gate opens.
            .withPropertyValues("cloud-link.encryption-key=unit-test-encryption-key");

    @Test
    @DisplayName("Property unset - remote-marketplace beans are ABSENT (matchIfMissing defaults to false)")
    void beansAbsentWhenPropertyUnset() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CloudLinkService.class);
            assertThat(context).doesNotHaveBean(RemoteMarketplaceService.class);
            assertThat(context).doesNotHaveBean(CeCloudLinkHeartbeatScheduler.class);
        });
    }

    @Test
    @DisplayName("marketplace.mode=local - remote-marketplace beans are ABSENT (no remote wiring on a non-remote install)")
    void beansAbsentWhenModeLocal() {
        contextRunner
                .withPropertyValues("marketplace.mode=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CloudLinkService.class);
                    assertThat(context).doesNotHaveBean(RemoteMarketplaceService.class);
                    assertThat(context).doesNotHaveBean(CeCloudLinkHeartbeatScheduler.class);
                });
    }

    @Test
    @DisplayName("marketplace.mode=remote - cloudLinkService + remoteMarketplaceService + heartbeat scheduler ARE wired (CE monolith)")
    void beansPresentWhenModeRemote() {
        contextRunner
                .withPropertyValues("marketplace.mode=remote")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CloudLinkService.class);
                    assertThat(context).hasSingleBean(RemoteMarketplaceService.class);
                    assertThat(context).hasSingleBean(CeCloudLinkHeartbeatScheduler.class);
                });
    }

    /** Supplies the config's collaborators so its beans can wire when the gate opens. */
    @Configuration(proxyBeanMethods = false)
    static class RemoteMarketplaceConfigDependencies {
        @Bean
        CeCloudLinkRepository cloudLinkRepository() {
            return mock(CeCloudLinkRepository.class);
        }

        @Bean
        PublicationReceiptRepository receiptRepository() {
            return mock(PublicationReceiptRepository.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        SnapshotCloneService snapshotCloneService() {
            return mock(SnapshotCloneService.class);
        }

        @Bean
        AuthClient authClient() {
            return mock(AuthClient.class);
        }

        @Bean
        AgentPublicationService agentPublicationService() {
            return mock(AgentPublicationService.class);
        }

        @Bean
        ResourcePublicationService resourcePublicationService() {
            return mock(ResourcePublicationService.class);
        }

        @Bean
        OrchestratorInternalClient orchestratorClient() {
            return mock(OrchestratorInternalClient.class);
        }
    }
}

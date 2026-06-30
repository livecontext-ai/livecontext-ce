package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bean-gating contract for {@link CeDownloadController}.
 *
 * <p>The controller is annotated
 * {@code @ConditionalOnProperty(name = "marketplace.ce-download.enabled", havingValue = "true")}
 * (no {@code matchIfMissing}, so it defaults to false). It is the cloud-side
 * entry point that lets a CE install acquire and DOWNLOAD a publication's
 * snapshot; on a plain CE / self-hosted install (where the property is unset)
 * the controller MUST NOT exist, otherwise CE would expose a route that bills
 * and hands out snapshots locally. These tests pin that the bean is wired ONLY
 * when the property is explicitly {@code true}.
 */
@DisplayName("CeDownloadController - marketplace.ce-download.enabled bean gating")
class CeDownloadControllerBeanGatingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CeDownloadDependencies.class)
            .withUserConfiguration(CeDownloadControllerImport.class);

    @Test
    @DisplayName("Property unset - controller bean is ABSENT (matchIfMissing defaults to false)")
    void beanAbsentWhenPropertyUnset() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CeDownloadController.class);
        });
    }

    @Test
    @DisplayName("Property set to false - controller bean is ABSENT (CE install gets no cloud download route)")
    void beanAbsentWhenPropertyFalse() {
        contextRunner
                .withPropertyValues("marketplace.ce-download.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CeDownloadController.class);
                });
    }

    @Test
    @DisplayName("Property set to true - controller bean IS wired (cloud EE instance)")
    void beanPresentWhenPropertyTrue() {
        contextRunner
                .withPropertyValues("marketplace.ce-download.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CeDownloadController.class);
                });
    }

    /** Supplies the controller's collaborators so it can wire when the gate opens. */
    @Configuration(proxyBeanMethods = false)
    static class CeDownloadDependencies {
        @Bean
        WorkflowPublicationRepository publicationRepository() {
            return mock(WorkflowPublicationRepository.class);
        }

        @Bean
        PublicationReceiptRepository receiptRepository() {
            return mock(PublicationReceiptRepository.class);
        }

        @Bean
        CreditConsumptionClient creditClient() {
            return mock(CreditConsumptionClient.class);
        }

        @Bean
        AuthClient authClient() {
            return mock(AuthClient.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CeDownloadController.class)
    static class CeDownloadControllerImport {
    }
}

package com.apimarketplace.publication.service;

import com.apimarketplace.common.plan.CloudPlanAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Bean-gating contract for {@link PublicationCloudPlanAccess}, whose class-level
 * {@code @ConditionalOnProperty(name = "marketplace.mode", havingValue = "remote")}
 * makes it a CE-only adapter. In the cloud deployment (mode absent or "local") the bean
 * must be absent so auth-service's {@code PlanLimitService} keeps using its local-plan
 * resolution untouched (its optional {@link CloudPlanAccess} dependency stays null).
 *
 * <p>Imports the REAL component so its own annotation is evaluated, mirroring
 * {@code CeLinkCloudOnlyBeanConditionTest} in auth-service.
 */
@DisplayName("PublicationCloudPlanAccess marketplace.mode gating")
class PublicationCloudPlanAccessBeanConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CloudPlanAccessImport.class);

    @Test
    @DisplayName("remoteModeWiresCloudPlanAccessBean - CE exposes the cloud-bound plan to PlanLimitService")
    void remoteModeWiresCloudPlanAccessBean() {
        contextRunner
                .withPropertyValues("marketplace.mode=remote")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PublicationCloudPlanAccess.class);
                    assertThat(context).hasSingleBean(CloudPlanAccess.class);
                });
    }

    @Test
    @DisplayName("localModeOmitsCloudPlanAccessBean - cloud deployment leaves PlanLimitService dependency null")
    void localModeOmitsCloudPlanAccessBean() {
        contextRunner
                .withPropertyValues("marketplace.mode=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PublicationCloudPlanAccess.class);
                });
    }

    @Test
    @DisplayName("missingModeOmitsCloudPlanAccessBean - default cloud deployment has no CE adapter bean")
    void missingModeOmitsCloudPlanAccessBean() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PublicationCloudPlanAccess.class);
                });
    }

    @Test
    @DisplayName("governingPlanCode(null) short-circuits to empty without touching CloudLinkService")
    void governingPlanCodeNullTenantReturnsEmptyAndSkipsDelegate() {
        CloudLinkService cloudLinkService = mock(CloudLinkService.class);
        PublicationCloudPlanAccess access = new PublicationCloudPlanAccess(cloudLinkService);

        Optional<String> result = access.governingPlanCode(null);

        assertThat(result).isEmpty();
        // The null-guard must NOT delegate (a null tenant has no cloud-bound plan to look up).
        verifyNoInteractions(cloudLinkService);
    }

    @Test
    @DisplayName("governingPlanCode delegates a non-null tenant to CloudLinkService.governingCloudPlanCode")
    void governingPlanCodeNonNullTenantDelegatesToCloudLinkService() {
        CloudLinkService cloudLinkService = mock(CloudLinkService.class);
        when(cloudLinkService.governingCloudPlanCode(42L)).thenReturn(Optional.of("PRO"));
        PublicationCloudPlanAccess access = new PublicationCloudPlanAccess(cloudLinkService);

        Optional<String> result = access.governingPlanCode(42L);

        assertThat(result).contains("PRO");
        verify(cloudLinkService).governingCloudPlanCode(42L);
    }

    @Test
    @DisplayName("governingPlanCode passes through an empty result from CloudLinkService for an unlinked tenant")
    void governingPlanCodeNonNullTenantPropagatesEmptyDelegateResult() {
        CloudLinkService cloudLinkService = mock(CloudLinkService.class);
        when(cloudLinkService.governingCloudPlanCode(any())).thenReturn(Optional.empty());
        PublicationCloudPlanAccess access = new PublicationCloudPlanAccess(cloudLinkService);

        Optional<String> result = access.governingPlanCode(7L);

        assertThat(result).isEmpty();
        verify(cloudLinkService).governingCloudPlanCode(7L);
        verify(cloudLinkService, never()).governingCloudPlanCode(null);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PublicationCloudPlanAccess.class)
    static class CloudPlanAccessImport {

        @Bean
        CloudLinkService cloudLinkService() {
            return mock(CloudLinkService.class);
        }
    }
}

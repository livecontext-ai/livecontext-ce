package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.license.EnterpriseLicenseResourceLimit;
import com.apimarketplace.auth.service.license.EnterpriseLicenseService;
import com.apimarketplace.auth.service.license.EnterpriseLicenseStatus;
import com.apimarketplace.common.plan.CloudPlanAccess;
import com.apimarketplace.common.web.AppEditionProvider;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanLimitServiceTest {

    @Mock UserRepository userRepository;
    @Mock SubscriptionRepository subscriptionRepository;
    @Mock AppEditionProvider editionProvider;
    @Mock EnterpriseLicenseService enterpriseLicenseService;
    @InjectMocks PlanLimitService service;

    private User user;
    private Plan plan;
    private Subscription subscription;

    @BeforeEach
    void setUp() {
        user = mock(User.class);
        when(user.getId()).thenReturn(42L);

        plan = mock(Plan.class);
        subscription = mock(Subscription.class);
        when(subscription.getPlan()).thenReturn(plan);
        when(editionProvider.isSelfHostedEnterprise()).thenReturn(false);
    }

    @Test
    void getLimit_returnsNullWhenNoUser() {
        when(userRepository.findByProviderId("u1")).thenReturn(Optional.empty());
        assertThat(service.getLimit("u1", "WORKFLOW")).isNull();
    }

    @Test
    void getLimit_returnsNullWhenNoActiveSubscription() {
        when(userRepository.findByProviderId("u1")).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.empty());
        assertThat(service.getLimit("u1", "WORKFLOW")).isNull();
    }

    @Test
    void getLimit_delegatesToPlanGetResourceLimit() {
        when(userRepository.findByProviderId("u1")).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.of(subscription));
        when(plan.getResourceLimit("WORKFLOW")).thenReturn(5);

        assertThat(service.getLimit("u1", "WORKFLOW")).isEqualTo(5);
    }

    @Test
    void getLimit_unlimitedReturnsNull() {
        when(userRepository.findByProviderId("u1")).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.of(subscription));
        when(plan.getResourceLimit("AGENT")).thenReturn(null);

        assertThat(service.getLimit("u1", "AGENT")).isNull();
    }

    @Test
    void getLimit_cachesResult() {
        when(userRepository.findByProviderId("u1")).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.of(subscription));
        when(plan.getResourceLimit("WORKFLOW")).thenReturn(3);

        service.getLimit("u1", "WORKFLOW");
        service.getLimit("u1", "WORKFLOW");
        service.getLimit("u1", "WORKFLOW");

        verify(userRepository, times(1)).findByProviderId("u1");
    }

    @Test
    void getLimit_cachesNullSentinel() {
        when(userRepository.findByProviderId("u1")).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.of(subscription));
        when(plan.getResourceLimit("AGENT")).thenReturn(null);

        assertThat(service.getLimit("u1", "AGENT")).isNull();
        assertThat(service.getLimit("u1", "AGENT")).isNull();
        verify(userRepository, times(1)).findByProviderId("u1");
    }

    @Test
    void getLimit_invalidateForcesRefresh() {
        when(userRepository.findByProviderId("u1")).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.of(subscription));
        when(plan.getResourceLimit("WORKFLOW")).thenReturn(3, 10);

        assertThat(service.getLimit("u1", "WORKFLOW")).isEqualTo(3);
        service.invalidate("u1");
        assertThat(service.getLimit("u1", "WORKFLOW")).isEqualTo(10);
    }

    @Test
    void getLimit_nullProviderIdReturnsNull() {
        assertThat(service.getLimit(null, "WORKFLOW")).isNull();
        assertThat(service.getLimit("", "WORKFLOW")).isNull();
        assertThat(service.getLimit("u1", null)).isNull();
        verifyNoInteractions(userRepository);
    }

    @Test
    void getPlanCode_returnsNoSubscriptionWhenMissing() {
        when(userRepository.findByProviderId("u1")).thenReturn(Optional.empty());
        assertThat(service.getPlanCode("u1")).isEqualTo(PlanLimitService.NO_SUBSCRIPTION);
    }

    @Test
    void getPlanCode_returnsPlanCode() {
        when(userRepository.findByProviderId("u1")).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.of(subscription));
        when(plan.getCode()).thenReturn("PRO");

        assertThat(service.getPlanCode("u1")).isEqualTo("PRO");
    }

    // ===== CE↔Cloud plan delegation (cloudPlanAccess; null in the cloud deployment) =====

    @Test
    void getPlanCode_cloudPlanGovernsAndUpgradesLocalNone() {
        // Local install has no subscription; paying on the bound cloud account (PRO) must unlock here.
        when(userRepository.findByProviderId("u1")).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.empty());
        CloudPlanAccess cloudPlanAccess = mock(CloudPlanAccess.class);
        when(cloudPlanAccess.governingPlanCode(42L)).thenReturn(Optional.of("PRO"));
        service.setCloudPlanAccess(cloudPlanAccess);

        assertThat(service.getPlanCode("u1")).isEqualTo("PRO");
    }

    @Test
    void getPlanCode_fallsBackToLocalPlanWhenCloudReturnsEmpty() {
        // BYOK / unlinked / cloud unreachable → empty → the local plan governs (never strips entitlements).
        when(userRepository.findByProviderId("u1")).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.of(subscription));
        when(plan.getCode()).thenReturn("STARTER");
        CloudPlanAccess cloudPlanAccess = mock(CloudPlanAccess.class);
        when(cloudPlanAccess.governingPlanCode(42L)).thenReturn(Optional.empty());
        service.setCloudPlanAccess(cloudPlanAccess);

        assertThat(service.getPlanCode("u1")).isEqualTo("STARTER");
    }

    @Test
    void getPlanCode_usesLocalPlanWhenNoCloudPlanAccessBean() {
        // Cloud deployment: no CloudPlanAccess bean wired (null) → local plan is authoritative.
        when(userRepository.findByProviderId("u1")).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.of(subscription));
        when(plan.getCode()).thenReturn("TEAM");

        assertThat(service.getPlanCode("u1")).isEqualTo("TEAM");
    }

    @Test
    void getLimit_isNotAffectedByCloudPlan_onlyFeatureGatingDelegates() {
        // Scoping guard: the cloud plan governs feature gating (getPlanCode), NOT resource limits,
        // which stay local (CE is unlimited-by-default). A cloud PRO must not silently re-cap limits.
        when(userRepository.findByProviderId("u1")).thenReturn(Optional.of(user));
        when(subscriptionRepository.findActiveByUserId(42L)).thenReturn(Optional.of(subscription));
        when(plan.getResourceLimit("WORKFLOW")).thenReturn(5);
        CloudPlanAccess cloudPlanAccess = mock(CloudPlanAccess.class);
        lenient().when(cloudPlanAccess.governingPlanCode(42L)).thenReturn(Optional.of("PRO"));
        service.setCloudPlanAccess(cloudPlanAccess);

        assertThat(service.getLimit("u1", "WORKFLOW")).isEqualTo(5);
    }

    @Test
    void selfHostedEnterpriseUsesSignedLicenseResourceLimit() {
        when(editionProvider.isSelfHostedEnterprise()).thenReturn(true);
        when(enterpriseLicenseService.resolveResourceLimit("WORKFLOW"))
                .thenReturn(new EnterpriseLicenseResourceLimit(true, EnterpriseLicenseService.SELF_HOSTED_PLAN_CODE, 25));

        assertThat(service.getLimit("u1", "WORKFLOW")).isEqualTo(25);

        verifyNoInteractions(userRepository, subscriptionRepository);
    }

    @Test
    void selfHostedEnterpriseKeepsExplicitUnlimitedLicenseLimit() {
        when(editionProvider.isSelfHostedEnterprise()).thenReturn(true);
        when(enterpriseLicenseService.resolveResourceLimit("AGENT"))
                .thenReturn(new EnterpriseLicenseResourceLimit(true, EnterpriseLicenseService.SELF_HOSTED_PLAN_CODE, null));

        assertThat(service.getLimit("u1", "AGENT")).isNull();

        verifyNoInteractions(userRepository, subscriptionRepository);
    }

    @Test
    void selfHostedEnterpriseInactiveLicenseLimitFailsClosedWithZero() {
        when(editionProvider.isSelfHostedEnterprise()).thenReturn(true);
        when(enterpriseLicenseService.resolveResourceLimit("WORKFLOW"))
                .thenReturn(EnterpriseLicenseResourceLimit.unlicensed());

        assertThat(service.getLimit("u1", "WORKFLOW")).isZero();

        verifyNoInteractions(userRepository, subscriptionRepository);
    }

    @Test
    void selfHostedEnterprisePlanCodeComesFromActiveLicense() {
        when(editionProvider.isSelfHostedEnterprise()).thenReturn(true);
        when(enterpriseLicenseService.currentStatus()).thenReturn(new EnterpriseLicenseStatus(
                true,
                "active",
                "lic-test",
                "Example Corp",
                EnterpriseLicenseService.SELF_HOSTED_PLAN_CODE,
                Instant.parse("2027-01-01T00:00:00Z"),
                JsonNodeFactory.instance.objectNode()));

        assertThat(service.getPlanCode("u1")).isEqualTo(EnterpriseLicenseService.SELF_HOSTED_PLAN_CODE);

        verifyNoInteractions(userRepository, subscriptionRepository);
    }

    @Test
    void selfHostedEnterpriseWithoutActiveLicenseReturnsNoSubscriptionPlanCode() {
        when(editionProvider.isSelfHostedEnterprise()).thenReturn(true);
        when(enterpriseLicenseService.currentStatus())
                .thenReturn(EnterpriseLicenseStatus.inactive("license_expired"));

        assertThat(service.getPlanCode("u1")).isEqualTo(PlanLimitService.NO_SUBSCRIPTION);

        verifyNoInteractions(userRepository, subscriptionRepository);
    }
}

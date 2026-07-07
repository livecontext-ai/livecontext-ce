package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.CeLinkEntitlements;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.service.CeLinkEntitlementsService;
import com.apimarketplace.auth.service.CeLinkService;
import com.apimarketplace.auth.service.CreditConsumptionDeadLetterService;
import com.apimarketplace.auth.service.ModelPricingService;
import com.apimarketplace.auth.service.OnboardingService;
import com.apimarketplace.auth.service.OrgRestrictionQueryService;
import com.apimarketplace.auth.service.PlanLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Contract tests for {@code GET /api/internal/auth/ce-link/{installId}/entitlements},
 * the subscription gate feeding the cloud-side CE catalog relay. The endpoint is
 * always 200 and fail-closed: any unknown/foreign/malformed input collapses to
 * {@code planCode="__NONE__", hasSubscription=false}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAuthController - CE link entitlements endpoint")
class InternalAuthControllerCeLinkEntitlementsTest {

    private static final UUID INSTALL_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private OrgRestrictionQueryService restrictionService;
    @Mock private CreditConsumptionDeadLetterService deadLetterService;
    @Mock private UserOnboardingRepository onboardingRepository;
    @Mock private OnboardingService onboardingService;
    @Mock private ModelPricingService modelPricingService;
    @Mock private PlanLimitService planLimitService;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private ObjectProvider<CeLinkService> ceLinkServiceProvider;
    @Mock private ObjectProvider<CeLinkEntitlementsService> ceLinkEntitlementsServiceProvider;
    @Mock private CeLinkEntitlementsService entitlementsService;

    private InternalAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAuthController(
                restrictionService, deadLetterService,
                onboardingRepository, onboardingService,
                modelPricingService, planLimitService,
                memberRepository, ceLinkServiceProvider,
                ceLinkEntitlementsServiceProvider);
    }

    @Test
    @DisplayName("Paid plan on the linked account resolves to hasSubscription=true")
    void paidPlanResolvesToSubscription() {
        when(ceLinkEntitlementsServiceProvider.getIfAvailable()).thenReturn(entitlementsService);
        when(entitlementsService.entitlementsForCaller(42L, INSTALL_ID))
                .thenReturn(new CeLinkEntitlements("PRO", 42L, 1, "monthly"));

        ResponseEntity<Map<String, Object>> response =
                controller.ceLinkEntitlements("42", INSTALL_ID.toString());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("planCode", "PRO")
                .containsEntry("hasSubscription", true);
    }

    @Test
    @DisplayName("No subscription (__NONE__) resolves to hasSubscription=false")
    void noSubscriptionResolvesToFalse() {
        when(ceLinkEntitlementsServiceProvider.getIfAvailable()).thenReturn(entitlementsService);
        when(entitlementsService.entitlementsForCaller(42L, INSTALL_ID))
                .thenReturn(CeLinkEntitlements.none());

        ResponseEntity<Map<String, Object>> response =
                controller.ceLinkEntitlements("42", INSTALL_ID.toString());

        assertThat(response.getBody())
                .containsEntry("planCode", PlanLimitService.NO_SUBSCRIPTION)
                .containsEntry("hasSubscription", false);
    }

    @Test
    @DisplayName("FREE plan is defensively treated as no subscription")
    void freePlanResolvesToFalse() {
        // CeLinkEntitlementsService already maps FREE to __NONE__, but the endpoint
        // must stay fail-closed even if a FREE code leaks through a future change.
        when(ceLinkEntitlementsServiceProvider.getIfAvailable()).thenReturn(entitlementsService);
        when(entitlementsService.entitlementsForCaller(42L, INSTALL_ID))
                .thenReturn(new CeLinkEntitlements("FREE", 42L, 0, null));

        ResponseEntity<Map<String, Object>> response =
                controller.ceLinkEntitlements("42", INSTALL_ID.toString());

        assertThat(response.getBody())
                .containsEntry("planCode", "FREE")
                .containsEntry("hasSubscription", false);
    }

    @Test
    @DisplayName("CE (CeLinkEntitlementsService bean absent) returns the __NONE__ shape instead of failing")
    void serviceAbsentReturnsNoneShape() {
        when(ceLinkEntitlementsServiceProvider.getIfAvailable()).thenReturn(null);

        ResponseEntity<Map<String, Object>> response =
                controller.ceLinkEntitlements("42", INSTALL_ID.toString());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("planCode", PlanLimitService.NO_SUBSCRIPTION)
                .containsEntry("hasSubscription", false);
    }

    @Test
    @DisplayName("Malformed install id returns the __NONE__ shape with 200, never a 500")
    void malformedInstallIdReturnsNoneShape() {
        when(ceLinkEntitlementsServiceProvider.getIfAvailable()).thenReturn(entitlementsService);

        ResponseEntity<Map<String, Object>> response =
                controller.ceLinkEntitlements("42", "not-a-uuid");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("planCode", PlanLimitService.NO_SUBSCRIPTION)
                .containsEntry("hasSubscription", false);
        verifyNoInteractions(entitlementsService);
    }

    @Test
    @DisplayName("Non-numeric user id returns the __NONE__ shape with 200, never a 500")
    void malformedUserIdReturnsNoneShape() {
        when(ceLinkEntitlementsServiceProvider.getIfAvailable()).thenReturn(entitlementsService);

        ResponseEntity<Map<String, Object>> response =
                controller.ceLinkEntitlements("not-a-number", INSTALL_ID.toString());

        assertThat(response.getBody())
                .containsEntry("planCode", PlanLimitService.NO_SUBSCRIPTION)
                .containsEntry("hasSubscription", false);
        verifyNoInteractions(entitlementsService);
    }
}

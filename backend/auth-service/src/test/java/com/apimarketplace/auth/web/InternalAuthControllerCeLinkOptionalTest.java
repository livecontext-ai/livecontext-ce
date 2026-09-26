package com.apimarketplace.auth.web;

import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.service.CeLinkEntitlementsService;
import com.apimarketplace.auth.service.CeLinkService;
import com.apimarketplace.auth.service.CreditConsumptionDeadLetterService;
import com.apimarketplace.auth.service.ModelPricingService;
import com.apimarketplace.auth.service.OrgRestrictionQueryService;
import com.apimarketplace.auth.service.PlanLimitService;
import com.apimarketplace.common.plan.CeLinkAccessResult;
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
import static org.mockito.Mockito.when;

/**
 * Regression guard for the CE-boot break: {@code CeLinkService} is a Cloud-only
 * bean (gated {@code @ConditionalOnProperty auth.mode=keycloak}), but
 * {@link InternalAuthController} is mixed-mode and also serves CE-required
 * endpoints ({@code /plans/limits}, {@code /org-restrictions}).
 *
 * <p>The dependency MUST therefore be optional ({@link ObjectProvider}); a hard
 * {@code final CeLinkService} ctor arg would throw {@code UnsatisfiedDependencyException}
 * at context load in the CE monolith ({@code auth.mode=embedded}) - the exact
 * scenario the cloud-only gating was meant to make boot. The
 * {@code /ce-link/{installId}/active} probe returns {@code active=false} in CE,
 * since no cloud link exists there.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAuthController - CeLinkService optional (CE boot)")
class InternalAuthControllerCeLinkOptionalTest {

    @Mock private OrgRestrictionQueryService restrictionService;
    @Mock private CreditConsumptionDeadLetterService deadLetterService;
    @Mock private UserOnboardingRepository onboardingRepository;
    @Mock private ModelPricingService modelPricingService;
    @Mock private PlanLimitService planLimitService;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private ObjectProvider<CeLinkService> ceLinkServiceProvider;
    @Mock private ObjectProvider<CeLinkEntitlementsService> ceLinkEntitlementsServiceProvider;
    @Mock private CeLinkService ceLinkService;

    private InternalAuthController controller;

    @BeforeEach
    void setUp() {
        // Constructs with an ObjectProvider - this alone proves the controller
        // wires up in CE where the CeLinkService bean is absent.
        controller = new InternalAuthController(
                restrictionService, deadLetterService,
                onboardingRepository,
                org.mockito.Mockito.mock(com.apimarketplace.auth.service.OnboardingService.class),
                modelPricingService, planLimitService,
                memberRepository, ceLinkServiceProvider,
                ceLinkEntitlementsServiceProvider,
                org.mockito.Mockito.mock(com.apimarketplace.auth.repository.UserProfileRepository.class),
                org.mockito.Mockito.mock(com.apimarketplace.auth.repository.UserRepository.class));
    }

    @Test
    @DisplayName("CE (CeLinkService bean absent) - /ce-link active returns active=false instead of failing")
    void ceLinkAbsentReturnsInactiveWithoutFailing() {
        UUID installId = UUID.randomUUID();
        when(ceLinkServiceProvider.getIfAvailable()).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.hasActiveCeLink(7L, installId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("active", false)
                .containsEntry("reason", "NOT_LINKED")
                .containsEntry("installId", installId.toString());
    }

    @Test
    @DisplayName("Cloud, linked and paid - delegates to CeLinkService.linkAccess -> active=true, no reason, plan echoed")
    void cloudDelegatesToCeLinkService() {
        UUID installId = UUID.randomUUID();
        when(ceLinkServiceProvider.getIfAvailable()).thenReturn(ceLinkService);
        when(ceLinkService.linkAccess(7L, installId)).thenReturn(CeLinkAccessResult.active("PRO"));

        ResponseEntity<Map<String, Object>> response = controller.hasActiveCeLink(7L, installId);

        assertThat(response.getBody())
                .containsEntry("active", true)
                .containsEntry("reason", null)
                .containsEntry("planCode", "PRO");
    }

    @Test
    @DisplayName("Cloud, user does not own the link - active=false, reason NOT_LINKED")
    void cloudInactiveWhenUserDoesNotOwnLink() {
        UUID installId = UUID.randomUUID();
        when(ceLinkServiceProvider.getIfAvailable()).thenReturn(ceLinkService);
        when(ceLinkService.linkAccess(7L, installId)).thenReturn(CeLinkAccessResult.notLinked());

        ResponseEntity<Map<String, Object>> response = controller.hasActiveCeLink(7L, installId);

        assertThat(response.getBody())
                .containsEntry("active", false)
                .containsEntry("reason", "NOT_LINKED")
                .containsEntry("planCode", null);
    }

    @Test
    @DisplayName("regression: Cloud, linked but the plan is not paid - active=false (old callers stay closed), reason PLAN_REQUIRED")
    void cloudSuspendedLinkIsNotActive() {
        UUID installId = UUID.randomUUID();
        when(ceLinkServiceProvider.getIfAvailable()).thenReturn(ceLinkService);
        when(ceLinkService.linkAccess(7L, installId)).thenReturn(CeLinkAccessResult.planRequired("FREE"));

        ResponseEntity<Map<String, Object>> response = controller.hasActiveCeLink(7L, installId);

        assertThat(response.getBody())
                .containsEntry("active", false)
                .containsEntry("reason", "PLAN_REQUIRED")
                .containsEntry("planCode", "FREE");
    }
}

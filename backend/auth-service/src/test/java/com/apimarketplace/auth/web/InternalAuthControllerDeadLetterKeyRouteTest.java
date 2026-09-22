package com.apimarketplace.auth.web;

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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * The dead-letter intake keeps the key route the agent services forward, so the replay
 * bills the turn the way the first attempt meant to (own key = flat fee, not token rate).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAuthController - dead-letter intake keeps the key route")
class InternalAuthControllerDeadLetterKeyRouteTest {

    @Mock private OrgRestrictionQueryService restrictionService;
    @Mock private CreditConsumptionDeadLetterService deadLetterService;
    @Mock private UserOnboardingRepository onboardingRepository;
    @Mock private OnboardingService onboardingService;
    @Mock private ModelPricingService modelPricingService;
    @Mock private PlanLimitService planLimitService;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private ObjectProvider<CeLinkService> ceLinkServiceProvider;
    @Mock private ObjectProvider<CeLinkEntitlementsService> ceLinkEntitlementsServiceProvider;

    private InternalAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAuthController(
                restrictionService, deadLetterService, onboardingRepository, onboardingService,
                modelPricingService, planLimitService, memberRepository, ceLinkServiceProvider,
                ceLinkEntitlementsServiceProvider,
                org.mockito.Mockito.mock(com.apimarketplace.auth.repository.UserProfileRepository.class),
                org.mockito.Mockito.mock(com.apimarketplace.auth.repository.UserRepository.class));
    }

    private static Map<String, Object> body(String keyRoute) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", "42");
        body.put("sourceType", "AGENT_EXECUTION");
        body.put("sourceId", "exec-1");
        body.put("provider", "openai");
        body.put("model", "gpt-4");
        body.put("promptTokens", 100);
        body.put("completionTokens", 50);
        body.put("errorReason", "Connection refused");
        if (keyRoute != null) {
            body.put("keyRoute", keyRoute);
        }
        return body;
    }

    @Test
    @DisplayName("keyRoute in the body reaches the service, with the org from the header")
    void keyRouteReachesTheService() {
        ResponseEntity<Void> response = controller.receiveDeadLetter(body("OWN_KEY"), "org-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(deadLetterService).persistFailedConsumption("42", "AGENT_EXECUTION", "exec-1",
                "openai", "gpt-4", 100, 50, "Connection refused", "org-1", "OWN_KEY");
    }

    @Test
    @DisplayName("a pre-V506 body without keyRoute persists a route-less entry (platform route)")
    void absentKeyRouteIsNull() {
        controller.receiveDeadLetter(body(null), "org-1");

        verify(deadLetterService).persistFailedConsumption("42", "AGENT_EXECUTION", "exec-1",
                "openai", "gpt-4", 100, 50, "Connection refused", "org-1", null);
    }
}

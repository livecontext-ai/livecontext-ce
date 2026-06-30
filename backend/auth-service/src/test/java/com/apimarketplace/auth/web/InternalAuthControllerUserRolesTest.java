package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.service.CeLinkService;
import com.apimarketplace.auth.service.CreditConsumptionDeadLetterService;
import com.apimarketplace.auth.service.ModelPricingService;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@code GET /users/{userId}/roles}. agent-service calls this at
 * CLI session start to resolve the caller's platform roles server-side - the
 * agent-cli MCP bridge bypasses the gateway, so no JWT validator injects an
 * X-User-Roles header. Without this resolution, admin-gated tools (e.g.
 * modifying a GLOBAL skill) saw no roles on the bridge path and always rejected.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAuthController.getUserRoles")
class InternalAuthControllerUserRolesTest {

    @Mock private OrgRestrictionQueryService restrictionService;
    @Mock private CreditConsumptionDeadLetterService deadLetterService;
    @Mock private UserOnboardingRepository onboardingRepository;
    @Mock private ModelPricingService modelPricingService;
    @Mock private PlanLimitService planLimitService;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private ObjectProvider<CeLinkService> ceLinkServiceProvider;

    private InternalAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAuthController(
                restrictionService, deadLetterService,
                onboardingRepository,
                org.mockito.Mockito.mock(com.apimarketplace.auth.service.OnboardingService.class),
                modelPricingService, planLimitService,
                memberRepository, ceLinkServiceProvider);
    }

    private UserOnboarding onboardingWithRoles(Set<String> roles) {
        User user = new User();
        user.setRoles(roles);
        return new UserOnboarding(user, "Some User");
    }

    @Test
    @DisplayName("Numeric id of an admin → roles list contains ADMIN")
    void numericIdAdminReturnsAdminRole() {
        when(onboardingRepository.findByUserIdFetchUser(42L))
                .thenReturn(Optional.of(onboardingWithRoles(Set.of("USER", "ADMIN"))));

        ResponseEntity<Map<String, Object>> response = controller.getUserRoles("42");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("userId", "42");
        assertThat((List<String>) body.get("roles")).contains("ADMIN", "USER");
    }

    @Test
    @DisplayName("Provider UUID → routes to findByUserProviderIdFetchUser")
    void providerUuidRoutesToProviderLookup() {
        String providerId = "abcd-1234-keycloak-sub";
        when(onboardingRepository.findByUserProviderIdFetchUser(providerId))
                .thenReturn(Optional.of(onboardingWithRoles(Set.of("USER"))));

        ResponseEntity<Map<String, Object>> response = controller.getUserRoles(providerId);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("userId", providerId);
        assertThat((List<String>) body.get("roles")).containsExactly("USER");
    }

    @Test
    @DisplayName("Unknown user → empty roles list (never null), so caller treats as non-admin")
    void unknownUserReturnsEmptyRoles() {
        when(onboardingRepository.findByUserIdFetchUser(999L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.getUserRoles("999");

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("userId", "999");
        assertThat((List<String>) body.get("roles")).isEmpty();
    }

    @Test
    @DisplayName("Onboarding present but user association null → empty roles, no NPE")
    void nullUserAssociationReturnsEmptyRoles() {
        UserOnboarding ob = new UserOnboarding(null, "Orphan");
        when(onboardingRepository.findByUserIdFetchUser(5L)).thenReturn(Optional.of(ob));

        ResponseEntity<Map<String, Object>> response = controller.getUserRoles("5");

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat((List<String>) body.get("roles")).isEmpty();
    }

    @Test
    @DisplayName("Null userId → 400 Bad Request")
    void nullUserIdReturnsBadRequest() {
        ResponseEntity<Map<String, Object>> response = controller.getUserRoles(null);
        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }
}

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

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@code GET /users/{userId}/publisher-profile} endpoint
 * added by the publisher-snapshot refactor. Publication-service relies on this
 * endpoint to freeze {@code workflow_publications.publisher_*} columns at every
 * (re)publish so an admin's session displayName cannot leak into the snapshot
 * (prod regression observed 2026-05-26→27).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAuthController.getPublisherProfile")
class InternalAuthControllerPublisherProfileTest {

    @Mock private OrgRestrictionQueryService restrictionService;
    @Mock private CreditConsumptionDeadLetterService deadLetterService;
    @Mock private UserOnboardingRepository onboardingRepository;
    @Mock private ModelPricingService modelPricingService;
    @Mock private PlanLimitService planLimitService;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private CeLinkService ceLinkService;
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

    @Test
    @DisplayName("Numeric id → resolves displayName + email + avatarUrl via user_onboarding → users join")
    void numericIdResolvesAllThreeFields() {
        User user = new User();
        user.setEmail("admin@example.com");
        user.setAvatarUrl("avatar-uuid-123");
        UserOnboarding ob = new UserOnboarding(user, "Real Admin Name");
        when(onboardingRepository.findByUserIdFetchUser(42L)).thenReturn(Optional.of(ob));

        ResponseEntity<Map<String, String>> response = controller.getPublisherProfile("42");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, String> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("userId", "42");
        assertThat(body).containsEntry("displayName", "Real Admin Name");
        assertThat(body).containsEntry("email", "admin@example.com");
        assertThat(body).containsEntry("avatarUrl", "avatar-uuid-123");
    }

    @Test
    @DisplayName("Provider UUID → routes to findByUserProviderId, returns full payload")
    void providerUuidRoutesToProviderIdLookup() {
        String providerId = "abcd-1234-keycloak-sub";
        User user = new User();
        user.setEmail("kc@example.com");
        user.setAvatarUrl("kc-avatar");
        UserOnboarding ob = new UserOnboarding(user, "Keycloak User");
        when(onboardingRepository.findByUserProviderIdFetchUser(providerId)).thenReturn(Optional.of(ob));

        ResponseEntity<Map<String, String>> response = controller.getPublisherProfile(providerId);

        Map<String, String> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("displayName", "Keycloak User");
        assertThat(body).containsEntry("email", "kc@example.com");
        assertThat(body).containsEntry("avatarUrl", "kc-avatar");
    }

    @Test
    @DisplayName("Unknown user → returns only {userId} (consistent with display-name endpoint)")
    void unknownUserReturnsUserIdOnly() {
        when(onboardingRepository.findByUserIdFetchUser(999L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> response = controller.getPublisherProfile("999");

        Map<String, String> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsOnlyKeys("userId");
        assertThat(body.get("userId")).isEqualTo("999");
    }

    @Test
    @DisplayName("User without avatar → omits avatarUrl key entirely (no empty string)")
    void userWithoutAvatarOmitsAvatarUrlKey() {
        User user = new User();
        user.setEmail("no-avatar@example.com");
        user.setAvatarUrl(null);
        UserOnboarding ob = new UserOnboarding(user, "Persona Without Avatar");
        when(onboardingRepository.findByUserIdFetchUser(7L)).thenReturn(Optional.of(ob));

        ResponseEntity<Map<String, String>> response = controller.getPublisherProfile("7");

        Map<String, String> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("displayName", "Persona Without Avatar");
        assertThat(body).containsEntry("email", "no-avatar@example.com");
        assertThat(body).doesNotContainKey("avatarUrl");
    }

    @Test
    @DisplayName("Null userId → 400 Bad Request")
    void nullUserIdReturnsBadRequest() {
        ResponseEntity<Map<String, String>> response = controller.getPublisherProfile(null);
        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }
}

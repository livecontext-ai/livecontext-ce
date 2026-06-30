package com.apimarketplace.auth.web;

import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.UserOnboardingRepository;
import com.apimarketplace.auth.service.CeLinkService;
import com.apimarketplace.auth.service.CreditConsumptionDeadLetterService;
import com.apimarketplace.auth.service.ModelPricingService;
import com.apimarketplace.auth.service.OnboardingService;
import com.apimarketplace.auth.service.OrgRestrictionQueryService;
import com.apimarketplace.auth.service.PlanLimitService;
import com.apimarketplace.common.auth.UserSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * Controller-level contract for the two display-name endpoints after they were
 * refactored to delegate resolution to {@link OnboardingService#resolveUserSummaries}.
 *
 * <p>Pins the seams the service test can't: the negative-cache padding
 * ({@code resolveBatch} MUST emit a null-name entry for every requested id the
 * resolver didn't return, so clients can cache misses) and the
 * {@code getDisplayName} response gating.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAuthController - display-name endpoints (delegation + contract)")
class InternalAuthControllerDisplayNameTest {

    @Mock private OrgRestrictionQueryService restrictionService;
    @Mock private CreditConsumptionDeadLetterService deadLetterService;
    @Mock private UserOnboardingRepository onboardingRepository;
    @Mock private OnboardingService onboardingService;
    @Mock private ModelPricingService modelPricingService;
    @Mock private PlanLimitService planLimitService;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private ObjectProvider<CeLinkService> ceLinkServiceProvider;

    private InternalAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAuthController(
                restrictionService, deadLetterService, onboardingRepository, onboardingService,
                modelPricingService, planLimitService, memberRepository, ceLinkServiceProvider);
    }

    @Test
    @DisplayName("resolveBatch returns resolved names AND pads unknown ids with a null entry (negative-cache contract)")
    void resolveBatchPadsUnknownIds() {
        when(onboardingService.resolveUserSummaries(anyCollection()))
                .thenReturn(Map.of("1", UserSummaryDto.displayNameOnly("1", "Ada Lovelace")));

        ResponseEntity<Map<String, UserSummaryDto>> response =
                controller.resolveBatch(List.of("1", "2"));

        Map<String, UserSummaryDto> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("1").displayName()).isEqualTo("Ada Lovelace");
        // The resolver didn't return "2" → the controller MUST still emit a
        // null-name entry so the client caches the miss instead of re-querying.
        assertThat(body).containsKey("2");
        assertThat(body.get("2").displayName()).isNull();
    }

    @Test
    @DisplayName("resolveBatch with empty body returns an empty map without calling the resolver")
    void resolveBatchEmpty() {
        ResponseEntity<Map<String, UserSummaryDto>> response = controller.resolveBatch(List.of());
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("resolveBatch over the 200-id cap returns 400 Bad Request")
    void resolveBatchOverCap() {
        List<String> tooMany = IntStream.rangeClosed(1, 201)
                .mapToObj(Integer::toString).collect(Collectors.toList());

        ResponseEntity<Map<String, UserSummaryDto>> response = controller.resolveBatch(tooMany);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("getDisplayName returns the resolved display name (CE fallback name flows through)")
    void getDisplayNameResolved() {
        when(onboardingService.resolveUserSummaries(anyCollection()))
                .thenReturn(Map.of("42", UserSummaryDto.displayNameOnly("42", "Ada Lovelace")));

        ResponseEntity<Map<String, String>> response = controller.getDisplayName("42");

        assertThat(response.getBody()).containsEntry("displayName", "Ada Lovelace");
        assertThat(response.getBody()).containsEntry("userId", "42");
    }

    @Test
    @DisplayName("getDisplayName omits the displayName key when the id resolves to nothing (negative signal)")
    void getDisplayNameUnresolved() {
        when(onboardingService.resolveUserSummaries(anyCollection())).thenReturn(Map.of());

        ResponseEntity<Map<String, String>> response = controller.getDisplayName("999");

        assertThat(response.getBody()).containsEntry("userId", "999");
        assertThat(response.getBody()).doesNotContainKey("displayName");
    }

    @Test
    @DisplayName("getDisplayName with a blank id returns 400 Bad Request")
    void getDisplayNameBlank() {
        ResponseEntity<Map<String, String>> response = controller.getDisplayName("  ");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

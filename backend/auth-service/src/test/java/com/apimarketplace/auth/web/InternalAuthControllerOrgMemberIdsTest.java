package com.apimarketplace.auth.web;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAuthController.getOrganizationMemberIds")
class InternalAuthControllerOrgMemberIdsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";

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

    @Test
    @DisplayName("returns member ids as strings under userIds")
    void returnsMemberIds() {
        when(memberRepository.findMemberUserIdsByOrganizationId(UUID.fromString(ORG)))
                .thenReturn(List.of(1L, 42L, 7L));

        ResponseEntity<Map<String, Object>> response = controller.getOrganizationMemberIds(ORG);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) response.getBody().get("userIds");
        assertThat(ids).containsExactlyInAnyOrder("1", "42", "7");
    }

    @Test
    @DisplayName("malformed org id → empty userIds (caller fails closed)")
    void malformedOrgEmpty() {
        ResponseEntity<Map<String, Object>> response = controller.getOrganizationMemberIds("not-a-uuid");
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) response.getBody().get("userIds");
        assertThat(ids).isEmpty();
    }
}

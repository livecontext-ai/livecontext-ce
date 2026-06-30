package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.GatewayCacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR11d-b â€” rate-limit setDefaultOrganization. Pins the contract:
 * <ul>
 *   <li>Idempotent re-flip to the SAME org passes through with no cooldown</li>
 *   <li>Cross-org flip within cooldown window returns 429 + Retry-After</li>
 *   <li>Cross-org flip outside cooldown succeeds + stamps user.lastDefaultFlipAt</li>
 *   <li>Cooldown stamp is set AFTER the membership write (failed save â†’ no
 *       cooldown burn)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationController.setDefaultOrganization â€” PR11d-b rate-limit")
class OrganizationControllerSetDefaultRateLimitTest {

    @Mock private com.apimarketplace.auth.service.OrganizationService organizationService;
    @Mock private com.apimarketplace.auth.service.OrganizationMemberService memberService;
    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private UserRepository userRepository;
    @Mock private com.apimarketplace.auth.service.OnboardingService onboardingService;
    @Mock private com.apimarketplace.auth.repository.OrganizationAuditEventRepository auditEventRepository;
    @Mock private GatewayCacheClient gatewayCacheClient;

    private OrganizationController controller;

    private static final Long USER_ID = 42L;
    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();

    private User user;
    private Organization orgA;
    private Organization orgB;

    @BeforeEach
    void setUp() {
        controller = new OrganizationController(organizationService, memberService,
                memberRepository, userRepository, onboardingService,
                auditEventRepository, gatewayCacheClient);
        ReflectionTestUtils.setField(controller, "setDefaultCooldownSeconds", 60);

        user = new User("u", "u@t.com", AuthProvider.KEYCLOAK, "kc-u");
        user.setId(USER_ID);
        orgA = new Organization("A", "a", false, user);
        orgA.setId(ORG_A);
        orgB = new Organization("B", "b", false, user);
        orgB.setId(ORG_B);

        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("getCurrentOrganization uses eager default membership so CE personal orgs serialize")
    void currentOrganizationUsesEagerDefaultMembership() {
        OrganizationMember defaultMembership = new OrganizationMember(orgA, user, OrganizationRole.OWNER, true);
        when(memberRepository.findActiveDefaultByUserId(USER_ID)).thenReturn(Optional.of(defaultMembership));
        when(memberRepository.countByOrganization_Id(ORG_A)).thenReturn(1L);

        ResponseEntity<com.apimarketplace.auth.dto.OrganizationDto> response =
                controller.getCurrentOrganization(USER_ID, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo(ORG_A);
        verify(memberRepository).findActiveDefaultByUserId(USER_ID);
        verify(memberRepository, never()).findByUser_IdAndIsDefaultTrue(USER_ID);
    }

    @Test
    @DisplayName("getCurrentOrganization uses eager active membership when an org header is present")
    void currentOrganizationUsesEagerActiveMembership() {
        OrganizationMember activeMembership = new OrganizationMember(orgB, user, OrganizationRole.MEMBER, false);
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_B, USER_ID))
                .thenReturn(Optional.of(activeMembership));
        when(memberRepository.countByOrganization_Id(ORG_B)).thenReturn(1L);

        ResponseEntity<com.apimarketplace.auth.dto.OrganizationDto> response =
                controller.getCurrentOrganization(USER_ID, ORG_B.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo(ORG_B);
        verify(memberRepository).findActiveByOrganizationIdAndUserId(ORG_B, USER_ID);
        verify(memberRepository, never()).findByOrganization_IdAndUser_Id(ORG_B, USER_ID);
    }

    @Test
    @DisplayName("updateOrganization uses eager active membership before mutating the organization")
    void updateOrganizationUsesEagerActiveMembership() {
        OrganizationMember activeMembership = new OrganizationMember(orgA, user, OrganizationRole.OWNER, true);
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_A, USER_ID))
                .thenReturn(Optional.of(activeMembership));
        when(memberRepository.countByOrganization_Id(ORG_A)).thenReturn(1L);

        ResponseEntity<com.apimarketplace.auth.dto.OrganizationDto> response =
                controller.updateOrganization(ORG_A, USER_ID, java.util.Map.of("name", "Renamed"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getName()).isEqualTo("Renamed");
        verify(memberRepository).findActiveByOrganizationIdAndUserId(ORG_A, USER_ID);
        verify(memberRepository, never()).findByOrganization_IdAndUser_Id(ORG_A, USER_ID);
        verify(organizationService).save(orgA);
    }

    @Test
    @DisplayName("Idempotent re-flip to the SAME org -> 200, NO cooldown stamp, NO membership write")
    void idempotentReFlipPassesThrough() {
        // Current default IS the target â€” no real flip.
        OrganizationMember currentInA = new OrganizationMember(orgA, user, OrganizationRole.OWNER, true);
        when(memberRepository.findByOrganization_IdAndUser_Id(ORG_A, USER_ID))
                .thenReturn(Optional.of(currentInA));
        when(memberRepository.findByUser_IdAndIsDefaultTrue(USER_ID))
                .thenReturn(Optional.of(currentInA));
        // Burn the cooldown so it would normally trip if this were a real flip.
        user.setLastDefaultFlipAt(LocalDateTime.now());

        ResponseEntity<Void> r = controller.setDefaultOrganization(ORG_A, USER_ID);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        // No membership write (no flip happened).
        verify(memberRepository, never()).save(any());
        // No cooldown stamp re-burn.
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Cross-org flip within cooldown â†’ 429 + Retry-After header")
    void crossOrgFlipWithinCooldownReturns429() {
        OrganizationMember inA = new OrganizationMember(orgA, user, OrganizationRole.OWNER, true);
        OrganizationMember inB = new OrganizationMember(orgB, user, OrganizationRole.MEMBER, false);
        when(memberRepository.findByOrganization_IdAndUser_Id(ORG_B, USER_ID))
                .thenReturn(Optional.of(inB));
        when(memberRepository.findByUser_IdAndIsDefaultTrue(USER_ID))
                .thenReturn(Optional.of(inA));
        // Just flipped 10 seconds ago â†’ within 60s cooldown.
        user.setLastDefaultFlipAt(LocalDateTime.now().minusSeconds(10));

        ResponseEntity<Void> r = controller.setDefaultOrganization(ORG_B, USER_ID);

        assertThat(r.getStatusCode().value()).isEqualTo(429);
        assertThat(r.getHeaders().getFirst("Retry-After"))
                .as("must surface seconds remaining so a client can back off precisely")
                .isNotNull();
        // No flip applied.
        verify(memberRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("CE (auth.mode=embedded): cross-org flip within cooldown is NOT rate-limited -> 200, flip applied")
    void ceEmbeddedBypassesCooldown() {
        // Same scenario as the 429 test (flipped 5s ago = well within the 60s window), but in CE.
        ReflectionTestUtils.setField(controller, "authMode", "embedded");
        OrganizationMember inA = new OrganizationMember(orgA, user, OrganizationRole.OWNER, true);
        OrganizationMember inB = new OrganizationMember(orgB, user, OrganizationRole.MEMBER, false);
        when(memberRepository.findByOrganization_IdAndUser_Id(ORG_B, USER_ID))
                .thenReturn(Optional.of(inB));
        when(memberRepository.findByUser_IdAndIsDefaultTrue(USER_ID))
                .thenReturn(Optional.of(inA));
        user.setLastDefaultFlipAt(LocalDateTime.now().minusSeconds(5));

        ResponseEntity<Void> r = controller.setDefaultOrganization(ORG_B, USER_ID);

        assertThat(r.getStatusCode().value())
                .as("CE (embedded) has no multi-tenant abuse surface, so switching your own workspace is never rate-limited")
                .isEqualTo(200);
        // The flip IS applied despite the recent previous flip (cooldown skipped, not just the 429 suppressed).
        verify(memberRepository).save(inA);
        verify(memberRepository).save(inB);
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("Cross-org flip OUTSIDE cooldown â†’ 200 + cooldown stamped AFTER membership write")
    void crossOrgFlipOutsideCooldownSucceeds() {
        OrganizationMember inA = new OrganizationMember(orgA, user, OrganizationRole.OWNER, true);
        OrganizationMember inB = new OrganizationMember(orgB, user, OrganizationRole.MEMBER, false);
        when(memberRepository.findByOrganization_IdAndUser_Id(ORG_B, USER_ID))
                .thenReturn(Optional.of(inB));
        when(memberRepository.findByUser_IdAndIsDefaultTrue(USER_ID))
                .thenReturn(Optional.of(inA));
        // Last flip was 2 minutes ago â†’ outside 60s cooldown.
        user.setLastDefaultFlipAt(LocalDateTime.now().minusMinutes(2));

        ResponseEntity<Void> r = controller.setDefaultOrganization(ORG_B, USER_ID);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        // Both memberships re-saved (old â†’ default=false, new â†’ default=true).
        verify(memberRepository).save(inA);
        verify(memberRepository).save(inB);
        // User row saved with new flip timestamp.
        verify(userRepository).save(user);
        assertThat(user.getLastDefaultFlipAt()).isAfter(LocalDateTime.now().minusSeconds(5));
    }

    @Test
    @DisplayName("First-ever flip (lastDefaultFlipAt=null) â†’ 200 (no cooldown to apply)")
    void firstFlipPassesNoCooldown() {
        OrganizationMember inA = new OrganizationMember(orgA, user, OrganizationRole.OWNER, true);
        OrganizationMember inB = new OrganizationMember(orgB, user, OrganizationRole.MEMBER, false);
        when(memberRepository.findByOrganization_IdAndUser_Id(ORG_B, USER_ID))
                .thenReturn(Optional.of(inB));
        when(memberRepository.findByUser_IdAndIsDefaultTrue(USER_ID))
                .thenReturn(Optional.of(inA));
        user.setLastDefaultFlipAt(null); // never flipped

        ResponseEntity<Void> r = controller.setDefaultOrganization(ORG_B, USER_ID);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(userRepository).save(user);
        assertThat(user.getLastDefaultFlipAt()).isNotNull();
    }

    @Test
    @DisplayName("Null X-User-ID â†’ 400 (defensive â€” gateway must inject)")
    void nullUserIdReturns400() {
        ResponseEntity<Void> r = controller.setDefaultOrganization(ORG_A, null);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("Target org not in user memberships â†’ 404")
    void targetNotMemberReturns404() {
        when(memberRepository.findByOrganization_IdAndUser_Id(ORG_A, USER_ID))
                .thenReturn(Optional.empty());
        ResponseEntity<Void> r = controller.setDefaultOrganization(ORG_A, USER_ID);
        assertThat(r.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("Set-default on a PAUSED org -> 409 (cannot pin a dormant workspace as default); no flip applied")
    void pausedTargetRejectedWith409() {
        com.apimarketplace.auth.service.PlanResolutionService planResolution =
                org.mockito.Mockito.mock(com.apimarketplace.auth.service.PlanResolutionService.class);
        ReflectionTestUtils.setField(controller, "planResolutionService", planResolution);
        OrganizationMember inA = new OrganizationMember(orgA, user, OrganizationRole.OWNER, true);
        OrganizationMember inB = new OrganizationMember(orgB, user, OrganizationRole.MEMBER, false);
        when(memberRepository.findByOrganization_IdAndUser_Id(ORG_B, USER_ID)).thenReturn(Optional.of(inB));
        when(memberRepository.findByUser_IdAndIsDefaultTrue(USER_ID)).thenReturn(Optional.of(inA));
        when(memberRepository.findActiveByOrganizationIdAndUserId(ORG_B, USER_ID)).thenReturn(Optional.of(inB));
        when(planResolution.canMemberActInOrg(inB)).thenReturn(false); // org B is dormant for this user

        ResponseEntity<Void> r = controller.setDefaultOrganization(ORG_B, USER_ID);

        assertThat(r.getStatusCode().value()).isEqualTo(409);
        // The dormant org never becomes the default; cooldown is never burned.
        verify(memberRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Set-default on a SOFT-DELETED org -> 409 (a deleted workspace is never enterable); no flip")
    void deletedTargetRejectedWith409() {
        OrganizationMember inB = new OrganizationMember(orgB, user, OrganizationRole.MEMBER, false);
        when(memberRepository.findByOrganization_IdAndUser_Id(ORG_B, USER_ID)).thenReturn(Optional.of(inB));
        orgB.setDeletedAt(LocalDateTime.now());
        when(organizationService.findById(ORG_B)).thenReturn(Optional.of(orgB));

        ResponseEntity<Void> r = controller.setDefaultOrganization(ORG_B, USER_ID);

        assertThat(r.getStatusCode().value()).isEqualTo(409);
        // The deleted workspace never becomes the default; no membership/cooldown write.
        verify(memberRepository, never()).save(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Re-confirming an ALREADY-default org is a no-op (200) even when paused - gate runs AFTER the no-op")
    void pausedButAlreadyDefaultIsNoOp() {
        com.apimarketplace.auth.service.PlanResolutionService planResolution =
                org.mockito.Mockito.mock(com.apimarketplace.auth.service.PlanResolutionService.class);
        ReflectionTestUtils.setField(controller, "planResolutionService", planResolution);
        OrganizationMember currentInB = new OrganizationMember(orgB, user, OrganizationRole.MEMBER, true);
        when(memberRepository.findByOrganization_IdAndUser_Id(ORG_B, USER_ID)).thenReturn(Optional.of(currentInB));
        when(memberRepository.findByUser_IdAndIsDefaultTrue(USER_ID)).thenReturn(Optional.of(currentInB));

        ResponseEntity<Void> r = controller.setDefaultOrganization(ORG_B, USER_ID);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        // No-op short-circuits BEFORE the paused gate, so the resolver is never consulted.
        verify(planResolution, never()).canMemberActInOrg(any());
        verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("PR11 round-3 (audit 1 M2) â€” cooldown=0 boot guard REFUSES to start")
    void cooldownZeroFailsFast() {
        OrganizationController boot = new OrganizationController(organizationService, memberService,
                memberRepository, userRepository, onboardingService,
                auditEventRepository, gatewayCacheClient);
        ReflectionTestUtils.setField(boot, "setDefaultCooldownSeconds", 0);

        org.assertj.core.api.Assertions.assertThatThrownBy(boot::validateRateLimitConfig)
                .as("cooldown=0 silently disables defence â€” must fail-fast at boot")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cooldown-seconds must be >= 1");
    }

    @Test
    @DisplayName("PR11 round-3 (audit 1 M2) â€” negative cooldown also REFUSES")
    void negativeCooldownFailsFast() {
        OrganizationController boot = new OrganizationController(organizationService, memberService,
                memberRepository, userRepository, onboardingService,
                auditEventRepository, gatewayCacheClient);
        ReflectionTestUtils.setField(boot, "setDefaultCooldownSeconds", -1);

        org.assertj.core.api.Assertions.assertThatThrownBy(boot::validateRateLimitConfig)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("PR11 round-3 (audit 1 M2) â€” cooldown=1 passes the guard (minimum allowed)")
    void cooldownOneAccepted() {
        OrganizationController boot = new OrganizationController(organizationService, memberService,
                memberRepository, userRepository, onboardingService,
                auditEventRepository, gatewayCacheClient);
        ReflectionTestUtils.setField(boot, "setDefaultCooldownSeconds", 1);
        // Must NOT throw.
        boot.validateRateLimitConfig();
    }
}

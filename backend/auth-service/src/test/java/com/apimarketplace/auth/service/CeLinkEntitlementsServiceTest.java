package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.dto.CeLinkEntitlements;
import com.apimarketplace.auth.repository.CeLinkRepository;
import com.apimarketplace.auth.service.PlanResolutionService.ActiveOrgEntitlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tranche 1 of CE↔Cloud pricing delegation: a self-hosted install inherits the
 * plan of the cloud account it is bound to, and never inherits a stale/foreign one.
 *
 * <p>The bound account's entitlement is resolved the SAME way the cloud resolves its own
 * {@code X-User-Plan} - the user's active/default workspace OWNER tier
 * ({@link PlanResolutionService#resolveActiveOrgEntitlement}) - so a TEAM-workspace member inherits
 * TEAM (not "no subscription"), AND the bound credit-tier index + cadence flow through so a linked
 * CE can align its pricing credit slider with the cloud account.
 */
class CeLinkEntitlementsServiceTest {

    private final CeLinkRepository ceLinkRepository = mock(CeLinkRepository.class);
    private final PlanResolutionService planResolutionService = mock(PlanResolutionService.class);
    private final CeLinkEntitlementsService service =
            new CeLinkEntitlementsService(ceLinkRepository, planResolutionService);

    @Test
    @DisplayName("Active link inherits the bound account's workspace-governed plan, credit tier and cadence")
    void activeLinkInheritsBoundAccountPlan() {
        UUID installId = UUID.randomUUID();
        CeLink link = new CeLink(installId, 1L, "CE install");
        when(ceLinkRepository.findById(installId)).thenReturn(Optional.of(link));
        when(planResolutionService.resolveActiveOrgEntitlement(1L))
                .thenReturn(new ActiveOrgEntitlement("PRO", 2, "yearly"));

        CeLinkEntitlements result = service.entitlementsForCaller(1L, installId);

        assertThat(result.planCode()).isEqualTo("PRO");
        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.creditTierIndex()).isEqualTo(2);
        assertThat(result.cadence()).isEqualTo("yearly");
        assertThat(result.hasSubscription()).isTrue();
    }

    @Test
    @DisplayName("A TEAM-workspace member inherits the owner's TEAM plan + tier (regression: used to read __NONE__/'None')")
    void teamWorkspaceMemberInheritsOwnerTeamPlan() {
        UUID installId = UUID.randomUUID();
        CeLink link = new CeLink(installId, 7L, "CE install");
        when(ceLinkRepository.findById(installId)).thenReturn(Optional.of(link));
        // resolveActiveOrgEntitlement follows the user to their default workspace OWNER's tier
        // (X-User-Plan), so a member of a TEAM workspace whose personal subscription is empty still
        // resolves the owner's TEAM plan + credit tier + cadence.
        when(planResolutionService.resolveActiveOrgEntitlement(7L))
                .thenReturn(new ActiveOrgEntitlement("TEAM", 3, "monthly"));

        CeLinkEntitlements result = service.entitlementsForCaller(7L, installId);

        assertThat(result.planCode()).isEqualTo("TEAM");
        assertThat(result.creditTierIndex()).isEqualTo(3);
        assertThat(result.cadence()).isEqualTo("monthly");
        assertThat(result.hasSubscription()).isTrue();
    }

    @Test
    @DisplayName("A FREE governing entitlement falls back to NO_SUBSCRIPTION (not 'FREE'), tier 0")
    void freeGoverningTierFallsBackToNoSubscription() {
        UUID installId = UUID.randomUUID();
        CeLink freeLink = new CeLink(installId, 1L, "CE install");
        when(ceLinkRepository.findById(installId)).thenReturn(Optional.of(freeLink));
        when(planResolutionService.resolveActiveOrgEntitlement(1L))
                .thenReturn(new ActiveOrgEntitlement("FREE", 0, null));

        CeLinkEntitlements result = service.entitlementsForCaller(1L, installId);

        assertThat(result.planCode()).isEqualTo(PlanLimitService.NO_SUBSCRIPTION);
        assertThat(result.creditTierIndex()).isZero();
        assertThat(result.cadence()).isNull();
        assertThat(result.hasSubscription()).isFalse();
    }

    @Test
    @DisplayName("A caller cannot read the entitlements of an install they do not own")
    void foreignCallerGetsNothing() {
        UUID installId = UUID.randomUUID();
        CeLink link = new CeLink(installId, 1L, "CE install");
        when(ceLinkRepository.findById(installId)).thenReturn(Optional.of(link));

        CeLinkEntitlements result = service.entitlementsForCaller(2L, installId);

        assertThat(result.hasSubscription()).isFalse();
        assertThat(result.userId()).isNull();
        verifyNoInteractions(planResolutionService);
    }

    @Test
    @DisplayName("Unknown install yields no subscription without touching the plan resolver")
    void unknownInstallYieldsNoSubscription() {
        UUID installId = UUID.randomUUID();
        when(ceLinkRepository.findById(installId)).thenReturn(Optional.empty());

        CeLinkEntitlements result = service.entitlementsForCaller(1L, installId);

        assertThat(result.hasSubscription()).isFalse();
        assertThat(result.planCode()).isEqualTo(PlanLimitService.NO_SUBSCRIPTION);
        assertThat(result.userId()).isNull();
        verifyNoInteractions(planResolutionService);
    }

    @Test
    @DisplayName("Revoked link does not leak the formerly-bound account's plan")
    void revokedLinkLeaksNothing() {
        UUID installId = UUID.randomUUID();
        CeLink link = new CeLink(installId, 1L, "CE install");
        link.revoke(CeLink.RevokeReason.USER, 1L);
        when(ceLinkRepository.findById(installId)).thenReturn(Optional.of(link));

        CeLinkEntitlements result = service.entitlementsForCaller(1L, installId);

        assertThat(result.hasSubscription()).isFalse();
        verifyNoInteractions(planResolutionService);
    }

    @Test
    @DisplayName("Null arguments are handled without a repository lookup")
    void nullArgsShortCircuit() {
        assertThat(service.entitlementsForCaller(1L, null).hasSubscription()).isFalse();
        assertThat(service.entitlementsForCaller(null, UUID.randomUUID()).hasSubscription()).isFalse();
        verifyNoInteractions(ceLinkRepository);
        verifyNoInteractions(planResolutionService);
    }
}

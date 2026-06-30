package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * PR8 (Q1=b) regression - pin {@link PlanResolutionService#resolvePayerUserId}.
 *
 * Drift here = wrong wallet gets debited. Worst-case shapes:
 * - MEMBER-in-TEAM resolved to executor → owner-pays-for-member contract broken
 * - OWNER-in-own-TEAM resolved to "someone else" → infinite loop / NPE
 * - Personal-org executor redirected → users see their wallet debited from
 *   what looks like a different account
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlanResolutionService.resolvePayerUserId (PR8 Q1=b contract)")
class PayerResolverContractTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private OrganizationMemberRepository memberRepository;

    private PlanResolutionService service;
    private User member;
    private User owner;
    private Organization personalOrg;
    private Organization teamOrg;

    @BeforeEach
    void setUp() {
        service = new PlanResolutionService(subscriptionRepository, memberRepository);
        member = new User("m", "m@test.com", AuthProvider.KEYCLOAK, "kc-m");
        member.setId(42L);
        owner = new User("o", "o@test.com", AuthProvider.KEYCLOAK, "kc-o");
        owner.setId(1L);
        personalOrg = new Organization("personal", "personal", true, member);
        personalOrg.setId(UUID.randomUUID());
        teamOrg = new Organization("team", "team", false, owner);
        teamOrg.setId(UUID.randomUUID());
    }

    @Test
    @DisplayName("MEMBER in TEAM org → payer = OWNER (Q1=b core invariant)")
    void memberInTeamRedirectsToOwner() {
        OrganizationMember mem = new OrganizationMember(teamOrg, member, OrganizationRole.MEMBER, true);
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.of(mem));

        assertThat(service.resolvePayerUserId(42L))
                .as("MEMBER of TEAM org must charge the OWNER's wallet (Q1=b)")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("ADMIN in TEAM org → payer = OWNER (admin doesn't pay)")
    void adminInTeamRedirectsToOwner() {
        OrganizationMember mem = new OrganizationMember(teamOrg, member, OrganizationRole.ADMIN, true);
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.of(mem));

        assertThat(service.resolvePayerUserId(42L)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Request active workspace overrides default org for owner-pays routing")
    void requestActiveWorkspaceOverridesDefaultOrg() {
        OrganizationMember teamMembership =
                new OrganizationMember(teamOrg, member, OrganizationRole.MEMBER, false);
        when(memberRepository.findActiveByOrganizationIdAndUserId(teamOrg.getId(), 42L))
                .thenReturn(Optional.of(teamMembership));

        TenantResolver.runWithOrgScope(teamOrg.getId().toString(), () ->
                assertThat(service.resolvePayerUserId(42L))
                        .as("payer must follow X-Organization-ID, not the persisted default org")
                        .isEqualTo(1L));
    }

    @Test
    @DisplayName("OWNER in their own TEAM org → payer = executor (no redirect, same wallet)")
    void ownerOfOwnTeamReturnsExecutor() {
        // Same user is both executor + owner.
        Organization ownedTeam = new Organization("mine", "mine", false, member);
        OrganizationMember mem = new OrganizationMember(ownedTeam, member, OrganizationRole.OWNER, true);
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.of(mem));

        assertThat(service.resolvePayerUserId(42L))
                .as("Owner of their own TEAM org pays themselves - no redirect")
                .isEqualTo(42L);
    }

    @Test
    @DisplayName("Executor in personal org → payer = executor (no redirect)")
    void personalOrgReturnsExecutor() {
        OrganizationMember mem = new OrganizationMember(personalOrg, member, OrganizationRole.OWNER, true);
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.of(mem));

        assertThat(service.resolvePayerUserId(42L)).isEqualTo(42L);
    }

    @Test
    @DisplayName("No default org → payer = executor (degenerate state, fall back to self-billing)")
    void noDefaultOrgReturnsExecutor() {
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.empty());

        assertThat(service.resolvePayerUserId(42L)).isEqualTo(42L);
    }

    @Test
    @DisplayName("Soft-deleted default org → payer = executor (defence in depth)")
    void softDeletedOrgReturnsExecutor() {
        teamOrg.setDeletedAt(java.time.LocalDateTime.now());
        OrganizationMember mem = new OrganizationMember(teamOrg, member, OrganizationRole.MEMBER, true);
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.of(mem));

        assertThat(service.resolvePayerUserId(42L)).isEqualTo(42L);
    }

    @Test
    @DisplayName("Null-owner default org (orphan row) → payer = executor (no NPE)")
    void nullOwnerReturnsExecutor() {
        Organization orphan = new Organization("orphan", "orphan", false, null);
        orphan.setId(UUID.randomUUID());
        OrganizationMember mem = new OrganizationMember(orphan, member, OrganizationRole.MEMBER, true);
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L)).thenReturn(Optional.of(mem));

        assertThat(service.resolvePayerUserId(42L)).isEqualTo(42L);
    }

    @Test
    @DisplayName("Repository exception → payer = executor (hot path safety, never throws)")
    void exceptionFallsBackToExecutor() {
        when(memberRepository.findByUser_IdAndIsDefaultTrue(42L))
                .thenThrow(new RuntimeException("DB hiccup"));

        assertThat(service.resolvePayerUserId(42L)).isEqualTo(42L);
    }

    @Test
    @DisplayName("Null executor → null (defensive - caller decides what to do)")
    void nullExecutorReturnsNull() {
        assertThat(service.resolvePayerUserId(null)).isNull();
    }
}

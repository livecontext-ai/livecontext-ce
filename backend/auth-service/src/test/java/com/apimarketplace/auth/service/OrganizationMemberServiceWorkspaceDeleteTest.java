package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.repository.*;
import com.apimarketplace.common.web.AppEditionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit coverage for the workspace soft-delete + restore lifecycle (the bug that re-enabled
 * deletion). Mirrors the OrganizationMemberServiceTest harness. Users carry no providerId so
 * the gateway-cache bust is a no-op.
 */
@ExtendWith(MockitoExtension.class)
class OrganizationMemberServiceWorkspaceDeleteTest {

    @Mock private OrganizationMemberRepository memberRepository;
    @Mock private OrganizationInvitationRepository invitationRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private UserRepository userRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private OnboardingService onboardingService;
    @Mock private AppEditionProvider editionProvider;

    private OrganizationMemberService service;
    private OrganizationAuditService auditService;

    private User owner;
    private User member;
    private Organization org;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        auditService = spy(new OrganizationAuditService(mock(OrganizationAuditEventRepository.class)));
        service = new OrganizationMemberService(
                memberRepository, invitationRepository, organizationRepository,
                userRepository, subscriptionRepository, onboardingService, auditService,
                mock(OrganizationInvitationMailer.class), editionProvider,
                mock(GatewayCacheClient.class), 1000, 1000);

        orgId = UUID.randomUUID();
        owner = user(1L);
        member = user(2L);
        org = new Organization("Acme", "acme", false, owner);
        org.setId(orgId);
    }

    private User user(Long id) {
        User u = new User();
        u.setId(id);
        u.setEmail("u" + id + "@test.com");
        return u; // no providerId -> bustGatewayCacheFor is a no-op
    }

    private OrganizationMember membership(User u, OrganizationRole role, boolean isDefault) {
        return new OrganizationMember(org, u, role, isDefault);
    }

    // ===================== soft-delete =====================

    @Test
    @DisplayName("soft-delete: refuses the personal workspace")
    void refusesPersonal() {
        org.setPersonal(true);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        assertThatThrownBy(() -> service.softDeleteOrganization(orgId, 1L, "Acme"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(org.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("soft-delete: refuses a non-OWNER requester")
    void refusesNonOwner() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(memberRepository.findByOrganization_IdAndUser_Id(orgId, 2L))
                .thenReturn(Optional.of(membership(member, OrganizationRole.MEMBER, false)));
        assertThatThrownBy(() -> service.softDeleteOrganization(orgId, 2L, "Acme"))
                .isInstanceOf(SecurityException.class);
        assertThat(org.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("soft-delete: refuses a confirmName that does not match")
    void refusesWrongConfirmName() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(memberRepository.findByOrganization_IdAndUser_Id(orgId, 1L))
                .thenReturn(Optional.of(membership(owner, OrganizationRole.OWNER, false)));
        assertThatThrownBy(() -> service.softDeleteOrganization(orgId, 1L, "Wrong Name"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(org.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("soft-delete: refuses an already-deleted workspace")
    void refusesAlreadyDeleted() {
        org.setDeletedAt(LocalDateTime.now());
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        assertThatThrownBy(() -> service.softDeleteOrganization(orgId, 1L, "Acme"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("soft-delete: stamps deleted_at/by, cancels PENDING invitations, audits DELETED")
    void happyPath() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(memberRepository.findByOrganization_IdAndUser_Id(orgId, 1L))
                .thenReturn(Optional.of(membership(owner, OrganizationRole.OWNER, false)));
        OrganizationInvitation pending = new OrganizationInvitation();
        pending.setStatus(InvitationStatus.PENDING);
        when(invitationRepository.findByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING))
                .thenReturn(List.of(pending));
        when(memberRepository.findByOrganization_Id(orgId))
                .thenReturn(List.of(membership(owner, OrganizationRole.OWNER, false)));

        service.softDeleteOrganization(orgId, 1L, "Acme");

        assertThat(org.isDeleted()).isTrue();
        assertThat(org.getDeletedBy()).isEqualTo(1L);
        assertThat(pending.getStatus()).isEqualTo(InvitationStatus.CANCELLED);
        verify(organizationRepository).save(org);
        verify(auditService).record(eq(orgId), eq(1L), eq(OrganizationAuditEvent.Type.DELETED), any());
    }

    @Test
    @DisplayName("soft-delete: promotes a fallback default for a member whose default was this org")
    void promotesFallbackDefault() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(memberRepository.findByOrganization_IdAndUser_Id(orgId, 1L))
                .thenReturn(Optional.of(membership(owner, OrganizationRole.OWNER, false)));
        when(invitationRepository.findByOrganization_IdAndStatus(orgId, InvitationStatus.PENDING))
                .thenReturn(List.of());

        // member's default IS this org; they also belong to a personal org that should be promoted.
        OrganizationMember memberHere = membership(member, OrganizationRole.MEMBER, true);
        Organization personal = new Organization("Personal", "personal", true, member);
        personal.setId(UUID.randomUUID());
        OrganizationMember personalMembership = new OrganizationMember(personal, member, OrganizationRole.OWNER, false);
        when(memberRepository.findByOrganization_Id(orgId)).thenReturn(List.of(memberHere));
        when(memberRepository.findByUser_Id(2L)).thenReturn(List.of(memberHere, personalMembership));

        service.softDeleteOrganization(orgId, 1L, "Acme");

        // Regression: the old default (membership in the deleted org) MUST be cleared, otherwise
        // setting a second is_default=true for the same user violates the single-default unique
        // index and rolls back the whole delete. The pre-fix code never cleared it.
        assertThat(memberHere.isDefault()).isFalse();
        verify(memberRepository).save(memberHere);
        assertThat(personalMembership.isDefault()).isTrue();
        verify(memberRepository).save(personalMembership);
    }

    // ===================== restore =====================

    @Test
    @DisplayName("restore: refuses a non-OWNER")
    void restoreRefusesNonOwner() {
        org.setDeletedAt(LocalDateTime.now());
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(memberRepository.findByOrganization_IdAndUser_Id(orgId, 2L))
                .thenReturn(Optional.of(membership(member, OrganizationRole.MEMBER, false)));
        assertThatThrownBy(() -> service.restoreOrganization(orgId, 2L))
                .isInstanceOf(SecurityException.class);
        assertThat(org.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("restore: refuses a workspace that is not deleted")
    void restoreRefusesNotDeleted() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(memberRepository.findByOrganization_IdAndUser_Id(orgId, 1L))
                .thenReturn(Optional.of(membership(owner, OrganizationRole.OWNER, false)));
        assertThatThrownBy(() -> service.restoreOrganization(orgId, 1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("restore: refuses an already-purged workspace (data is gone)")
    void restoreRefusesPurged() {
        org.setDeletedAt(LocalDateTime.now().minusDays(40));
        org.setPurgedAt(LocalDateTime.now());
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(memberRepository.findByOrganization_IdAndUser_Id(orgId, 1L))
                .thenReturn(Optional.of(membership(owner, OrganizationRole.OWNER, false)));
        assertThatThrownBy(() -> service.restoreOrganization(orgId, 1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("restore: clears deleted_at and audits RESTORED")
    void restoreHappyPath() {
        org.setDeletedAt(LocalDateTime.now());
        org.setDeletedBy(1L);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org));
        when(memberRepository.findByOrganization_IdAndUser_Id(orgId, 1L))
                .thenReturn(Optional.of(membership(owner, OrganizationRole.OWNER, false)));
        when(memberRepository.findByOrganization_Id(orgId))
                .thenReturn(List.of(membership(owner, OrganizationRole.OWNER, false)));

        service.restoreOrganization(orgId, 1L);

        assertThat(org.isDeleted()).isFalse();
        assertThat(org.getDeletedBy()).isNull();
        verify(organizationRepository).save(org);
        verify(auditService).record(eq(orgId), eq(1L), eq(OrganizationAuditEvent.Type.RESTORED), any());
    }
}

package com.apimarketplace.auth.integration;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.InvitationStatus;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationInvitation;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.InvitationDto;
import com.apimarketplace.auth.repository.OrganizationInvitationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
@Import(IntegrationTestConfig.class)
@AutoConfigureTestEntityManager
@DisplayName("Organization invitation repository")
class OrganizationInvitationRepositoryIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OrganizationInvitationRepository invitationRepository;

    private User inviter;
    private Organization organization;
    private UUID invitationId;

    @BeforeEach
    void setUp() {
        inviter = new User("inviter", "inviter@example.com", AuthProvider.LOCAL, "local-inviter");
        inviter.setEnabled(true);
        inviter.setEmailVerified(true);
        inviter.setUserVersion(1L);
        inviter = entityManager.persistAndFlush(inviter);

        organization = new Organization("CE Team", "ce-team", false, inviter);
        organization = entityManager.persistAndFlush(organization);

        OrganizationInvitation invitation = new OrganizationInvitation(
                organization,
                "invitee@example.com",
                OrganizationRole.MEMBER,
                inviter);
        invitation = entityManager.persistAndFlush(invitation);
        invitationId = invitation.getId();
        entityManager.clear();
    }

    @Test
    @DisplayName("loads inbox DTO relations for pending invitations after the repository transaction boundary")
    void findByEmailAndStatusLoadsDtoRelationsAfterDetach() {
        List<OrganizationInvitation> invitations = invitationRepository.findByEmailAndStatus(
                "invitee@example.com",
                InvitationStatus.PENDING);
        assertThat(invitations).hasSize(1);

        OrganizationInvitation loaded = invitations.get(0);
        entityManager.clear();

        InvitationDto dto = new InvitationDto(loaded, "Inviter Display");

        assertThat(dto.getOrganizationId()).isEqualTo(organization.getId());
        assertThat(dto.getOrganizationName()).isEqualTo("CE Team");
        assertThat(loaded.getInvitedBy().getEmail()).isEqualTo("inviter@example.com");
    }

    @Test
    @DisplayName("loads accept and decline relations when resolving an invitation by id")
    void findByIdLoadsDtoRelationsAfterDetach() {
        OrganizationInvitation loaded = invitationRepository.findById(invitationId).orElseThrow();
        entityManager.clear();

        InvitationDto dto = new InvitationDto(loaded, "Inviter Display");

        assertThat(dto.getOrganizationId()).isEqualTo(organization.getId());
        assertThat(dto.getOrganizationName()).isEqualTo("CE Team");
        assertThat(loaded.getInvitedBy().getEmail()).isEqualTo("inviter@example.com");
    }

    @Test
    @DisplayName("F3: re-inviting a removed member can be ACCEPTED again (a 2nd ACCEPTED row for the same email is allowed)")
    void reInviteAfterRemovalCanBeAcceptedAgain() {
        // First invitation accepted: the member joined, then was removed.
        OrganizationInvitation first = invitationRepository.findById(invitationId).orElseThrow();
        first.setStatus(InvitationStatus.ACCEPTED);
        entityManager.persistAndFlush(first);

        // The admin re-invites the same address; the invitee accepts again.
        Organization org = entityManager.find(Organization.class, organization.getId());
        User by = entityManager.find(User.class, inviter.getId());
        OrganizationInvitation second = entityManager.persistAndFlush(
                new OrganizationInvitation(org, "invitee@example.com", OrganizationRole.MEMBER, by));
        second.setStatus(InvitationStatus.ACCEPTED);
        entityManager.persistAndFlush(second);
        entityManager.clear();

        assertThat(invitationRepository.findAll())
                .filteredOn(inv -> inv.getEmail().equals("invitee@example.com"))
                .extracting(OrganizationInvitation::getStatus)
                .containsExactly(InvitationStatus.ACCEPTED, InvitationStatus.ACCEPTED);
    }

    @Test
    @DisplayName("F3: a second decline / cancel for the same email is allowed (a 2nd CANCELLED row)")
    void secondCancellationForSameEmailIsAllowed() {
        OrganizationInvitation first = invitationRepository.findById(invitationId).orElseThrow();
        first.setStatus(InvitationStatus.CANCELLED);
        entityManager.persistAndFlush(first);

        Organization org = entityManager.find(Organization.class, organization.getId());
        User by = entityManager.find(User.class, inviter.getId());
        OrganizationInvitation second = entityManager.persistAndFlush(
                new OrganizationInvitation(org, "invitee@example.com", OrganizationRole.MEMBER, by));
        second.setStatus(InvitationStatus.CANCELLED);
        entityManager.persistAndFlush(second);
        entityManager.clear();

        assertThat(invitationRepository.findAll())
                .filteredOn(inv -> inv.getEmail().equals("invitee@example.com"))
                .extracting(OrganizationInvitation::getStatus)
                .containsExactly(InvitationStatus.CANCELLED, InvitationStatus.CANCELLED);
    }
}

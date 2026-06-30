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
}

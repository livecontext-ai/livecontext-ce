package com.apimarketplace.auth.dto;

import com.apimarketplace.auth.domain.InvitationStatus;
import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationInvitation;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-4 regression guard - invite response DTO normalization.
 */
@DisplayName("InvitationDto (S-4 invitation security pass)")
class InvitationDtoTest {

    @Test
    @DisplayName("S-4 PRE-FIX REPRO: forInviteResponse always echoes PENDING, even when the DB "
            + "row has been auto-flipped to ACCEPTED - closes the existing-account info-leak")
    void forInviteResponseAlwaysReturnsPendingEvenWhenEntityIsAccepted() {
        // This is the exact shape the controller sees after an auto-accept:
        // the entity comes back from the service with status=ACCEPTED because
        // the invitee was already on the platform. Pre-fix, the DTO copied
        // that ACCEPTED verbatim, letting an attacker enumerate accounts by
        // diffing the response status.
        User owner = new User();
        owner.setId(1L);
        owner.setEmail("owner@example.com");
        Organization org = new Organization("Acme", "acme", false, owner);
        OrganizationInvitation invitation = new OrganizationInvitation(
                org, "existing@example.com", OrganizationRole.MEMBER, owner);
        invitation.setStatus(InvitationStatus.ACCEPTED);  // <-- the leak vector

        InvitationDto dto = InvitationDto.forInviteResponse(invitation, "Inviter Display");

        assertThat(dto.getStatus())
                .as("invite response status must NEVER reveal that the DB row is ACCEPTED")
                .isEqualTo(InvitationStatus.PENDING);
        // All other fields must still round-trip - only `status` is normalized.
        assertThat(dto.getEmail()).isEqualTo("existing@example.com");
        assertThat(dto.getRole()).isEqualTo(OrganizationRole.MEMBER);
        assertThat(dto.getInvitedByName()).isEqualTo("Inviter Display");
    }

    @Test
    @DisplayName("S-4: forInviteResponse on a freshly-created PENDING invitation also echoes "
            + "PENDING (no regression on the email-path)")
    void forInviteResponseLeavesPendingUntouched() {
        User owner = new User();
        owner.setId(1L);
        owner.setEmail("owner@example.com");
        Organization org = new Organization("Acme", "acme", false, owner);
        OrganizationInvitation invitation = new OrganizationInvitation(
                org, "newcomer@example.com", OrganizationRole.MEMBER, owner);
        // status defaults to PENDING

        InvitationDto dto = InvitationDto.forInviteResponse(invitation, "Inviter Display");

        assertThat(dto.getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    @DisplayName("S-4: the legacy 2-arg constructor still echoes the real DB status - used by "
            + "list endpoints where the admin already knows the membership state")
    void legacyConstructorPreservesActualStatus() {
        User owner = new User();
        owner.setId(1L);
        owner.setEmail("owner@example.com");
        Organization org = new Organization("Acme", "acme", false, owner);
        OrganizationInvitation invitation = new OrganizationInvitation(
                org, "existing@example.com", OrganizationRole.MEMBER, owner);
        invitation.setStatus(InvitationStatus.ACCEPTED);

        InvitationDto dto = new InvitationDto(invitation, "Inviter Display");

        // The 2-arg constructor is used by list endpoints (already admin-scoped),
        // and by tests. It MUST keep faithful semantics - only the invite-response
        // factory is normalized.
        assertThat(dto.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
    }

    // ===== CE invite-by-link: token exposed only when includeToken=true =====

    @Test
    @DisplayName("CE (includeToken=true): forInviteResponse carries the raw token so the admin "
            + "can build a copyable accept link")
    void forInviteResponseExposesTokenWhenIncludeTokenTrue() {
        User owner = new User();
        owner.setId(1L);
        owner.setEmail("owner@example.com");
        Organization org = new Organization("Acme", "acme", false, owner);
        OrganizationInvitation invitation = new OrganizationInvitation(
                org, "newcomer@example.com", OrganizationRole.MEMBER, owner);

        InvitationDto dto = InvitationDto.forInviteResponse(invitation, "Inviter Display", true);

        assertThat(dto.getToken())
                .as("CE invite-by-link must expose the token")
                .isEqualTo(invitation.getToken())
                .isNotBlank();
        // Status normalization (S-4) still applies.
        assertThat(dto.getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    @DisplayName("Cloud (default / includeToken=false): forInviteResponse NEVER exposes the token "
            + "- the token is delivered by email only")
    void forInviteResponseHidesTokenInCloud() {
        User owner = new User();
        owner.setId(1L);
        owner.setEmail("owner@example.com");
        Organization org = new Organization("Acme", "acme", false, owner);
        OrganizationInvitation invitation = new OrganizationInvitation(
                org, "newcomer@example.com", OrganizationRole.MEMBER, owner);

        // Both the default 2-arg factory and the explicit includeToken=false must hide it.
        assertThat(InvitationDto.forInviteResponse(invitation, "Inviter Display").getToken()).isNull();
        assertThat(InvitationDto.forInviteResponse(invitation, "Inviter Display", false).getToken()).isNull();
        // The 2-arg list constructor also hides the token (cloud pending-list).
        assertThat(new InvitationDto(invitation, "Inviter Display").getToken()).isNull();
    }
}

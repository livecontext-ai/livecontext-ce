package com.apimarketplace.auth.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR0.5a regression - pin the active-org transport contract.
 *
 * The gateway calls {@link UserResolutionResponse#findRoleForOrg(String)} on
 * every non-public request when an `X-Active-Organization-ID` header is
 * present. A null return = the user does NOT belong to the claimed org, so
 * the gateway must reject the active-org claim and fall back to the default
 * org context. Any drift in this lookup breaks the security model.
 */
@DisplayName("UserResolutionResponse.memberships + findRoleForOrg (PR0.5a contract)")
class UserResolutionResponseMembershipsTest {

    @Test
    @DisplayName("findRoleForOrg returns the role when user is a member")
    void findsRoleForKnownOrg() {
        UserResolutionResponse r = new UserResolutionResponse();
        r.setMemberships(List.of(
                new OrgMembershipDto("11111111-1111-1111-1111-111111111111", "OWNER"),
                new OrgMembershipDto("22222222-2222-2222-2222-222222222222", "MEMBER")));

        assertThat(r.findRoleForOrg("11111111-1111-1111-1111-111111111111")).isEqualTo("OWNER");
        assertThat(r.findRoleForOrg("22222222-2222-2222-2222-222222222222")).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("findRoleForOrg returns null when user is NOT a member of the claimed org "
            + "(prevents an attacker from impersonating org context via header injection)")
    void returnsNullForUnknownOrg() {
        UserResolutionResponse r = new UserResolutionResponse();
        r.setMemberships(List.of(new OrgMembershipDto("aaa", "OWNER")));

        assertThat(r.findRoleForOrg("bbb")).isNull();
    }

    @Test
    @DisplayName("findRoleForOrg returns null for null orgId - defensive, no NPE")
    void returnsNullForNullOrgId() {
        UserResolutionResponse r = new UserResolutionResponse();
        r.setMemberships(List.of(new OrgMembershipDto("aaa", "OWNER")));

        assertThat(r.findRoleForOrg(null)).isNull();
    }

    @Test
    @DisplayName("Default memberships list is empty (not null) - legacy payloads without the "
            + "field still deserialise cleanly and downstream lookups don't NPE")
    void defaultMembershipsIsEmptyNotNull() {
        UserResolutionResponse r = new UserResolutionResponse();

        assertThat(r.getMemberships()).isNotNull().isEmpty();
        assertThat(r.findRoleForOrg("any")).isNull();
    }

    @Test
    @DisplayName("setMemberships(null) coerces to empty list - defensive")
    void setMembershipsNullCoercesToEmpty() {
        UserResolutionResponse r = new UserResolutionResponse();
        r.setMemberships(null);

        assertThat(r.getMemberships()).isNotNull().isEmpty();
    }
}

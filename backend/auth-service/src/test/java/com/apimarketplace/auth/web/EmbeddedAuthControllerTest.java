package com.apimarketplace.auth.web;

import com.apimarketplace.auth.ce.CeInstallStateService;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.service.OrganizationMemberService;
import com.apimarketplace.auth.service.OrganizationMemberService.InvitationInfo;
import com.apimarketplace.auth.service.PasswordAuthService;
import com.apimarketplace.auth.service.PasswordAuthService.TokenPair;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EmbeddedAuthController} - focused on the public-registration
 * door check (V189) and the CE invite-by-link registration bypass. Other paths
 * (login, refresh, logout) are covered by the {@link PasswordAuthService} test
 * suite at the service layer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddedAuthController - /api/auth/register door check + invite-link bypass")
class EmbeddedAuthControllerTest {

    @Mock
    private PasswordAuthService passwordAuthService;

    @Mock
    private CeInstallStateService installStateService;

    @Mock
    private OrganizationMemberService organizationMemberService;

    @Mock
    private HttpServletRequest request;

    private EmbeddedAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new EmbeddedAuthController(
                passwordAuthService, installStateService, organizationMemberService);
    }

    private static Map<String, String> registerBody(String email, String invitationToken) {
        Map<String, String> body = new HashMap<>();
        body.put("email", email);
        body.put("password", "irrelevantPassword");
        body.put("firstName", "Stranger");
        body.put("lastName", "Visitor");
        if (invitationToken != null) {
            body.put("invitationToken", invitationToken);
        }
        return body;
    }

    private User stubRegisterReturnsUser(long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        when(passwordAuthService.register(eq(email), anyString(), any(), any())).thenReturn(user);
        TokenPair tokens = new TokenPair("access", "refresh", 900L, 86400L);
        lenient().when(passwordAuthService.generateTokenPair(eq(user), any(), any())).thenReturn(tokens);
        return user;
    }

    @Test
    @DisplayName("POST /register returns 403 registration_closed when door is closed (post-wizard default)")
    void registerReturns403WhenRegistrationClosed() {
        when(installStateService.isRegistrationOpen()).thenReturn(false);

        ResponseEntity<Map<String, Object>> response =
                controller.register(registerBody("stranger@example.com", null), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "registration_closed");
        // The auth service must NOT be touched when the door is closed.
        verify(passwordAuthService, never()).register(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("invite-link: a VALID matching token bypasses a CLOSED door, creates the user, "
            + "and auto-accepts the invitation (the user joins the org)")
    void validInvitationTokenBypassesClosedDoorAndAutoAccepts() {
        // Door is CLOSED, but the token resolves to a PENDING invitation for the
        // submitted email → the user is created and auto-joined.
        when(organizationMemberService.getInvitationInfo("good-token"))
                .thenReturn(new InvitationInfo(true, "invitee@example.com", "Acme", OrganizationRole.MEMBER, false));
        User user = stubRegisterReturnsUser(42L, "invitee@example.com");

        ResponseEntity<Map<String, Object>> response =
                controller.register(registerBody("invitee@example.com", "good-token"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // installStateService.isRegistrationOpen() must NOT gate when the bypass applies.
        verify(installStateService, never()).isRegistrationOpen();
        verify(passwordAuthService).register(eq("invitee@example.com"), anyString(), any(), any());
        // Auto-accept ran with the new user's id and the same token.
        verify(organizationMemberService).acceptInvitation("good-token", user.getId());
    }

    @Test
    @DisplayName("invite-link: token bypass tolerates a case-different email (equalsIgnoreCase)")
    void validInvitationTokenBypassIsCaseInsensitiveOnEmail() {
        when(organizationMemberService.getInvitationInfo("good-token"))
                .thenReturn(new InvitationInfo(true, "Invitee@Example.com", "Acme", OrganizationRole.MEMBER, false));
        User user = stubRegisterReturnsUser(7L, "invitee@example.com");

        ResponseEntity<Map<String, Object>> response =
                controller.register(registerBody("invitee@example.com", "good-token"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(installStateService, never()).isRegistrationOpen();
        verify(organizationMemberService).acceptInvitation("good-token", user.getId());
    }

    @Test
    @DisplayName("invite-link: an INVALID (unknown/expired) token does NOT bypass a closed door → 403, no user, no accept")
    void invalidInvitationTokenStillRejectedByClosedDoor() {
        when(organizationMemberService.getInvitationInfo("bogus"))
                .thenReturn(InvitationInfo.invalid());
        when(installStateService.isRegistrationOpen()).thenReturn(false);

        ResponseEntity<Map<String, Object>> response =
                controller.register(registerBody("stranger@example.com", "bogus"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "registration_closed");
        verify(passwordAuthService, never()).register(anyString(), anyString(), any(), any());
        verify(organizationMemberService, never()).acceptInvitation(anyString(), any());
    }

    @Test
    @DisplayName("invite-link: a VALID token but MISMATCHED email does NOT bypass a closed door → 403")
    void mismatchedEmailTokenDoesNotBypassClosedDoor() {
        // The token is for someone-else@; the attacker submits their own address.
        when(organizationMemberService.getInvitationInfo("good-token"))
                .thenReturn(new InvitationInfo(true, "someone-else@example.com", "Acme", OrganizationRole.MEMBER, false));
        when(installStateService.isRegistrationOpen()).thenReturn(false);

        ResponseEntity<Map<String, Object>> response =
                controller.register(registerBody("attacker@example.com", "good-token"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "registration_closed");
        verify(passwordAuthService, never()).register(anyString(), anyString(), any(), any());
        verify(organizationMemberService, never()).acceptInvitation(anyString(), any());
    }

    @Test
    @DisplayName("no token + OPEN door: normal registration succeeds, no invitation lookup or accept")
    void openDoorNoTokenRegistersNormally() {
        when(installStateService.isRegistrationOpen()).thenReturn(true);
        User user = stubRegisterReturnsUser(99L, "open@example.com");

        ResponseEntity<Map<String, Object>> response =
                controller.register(registerBody("open@example.com", null), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(passwordAuthService).register(eq("open@example.com"), anyString(), any(), any());
        verify(organizationMemberService, never()).getInvitationInfo(anyString());
        verify(organizationMemberService, never()).acceptInvitation(anyString(), any());
    }
}

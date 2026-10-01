package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SignupCanaryService")
class SignupCanaryServiceTest {

    private static final String EMAIL = "signup.canary@example.test";
    private static final String TOKEN = "t0k3n-t0k3n-t0k3n-t0k3n-t0k3n-t0k3n";
    private static final String PASSWORD = "a-long-daily-password-1";
    private static final TestAccountPolicy POLICY =
            new TestAccountPolicy("signup\\.canary(\\+[a-z0-9-]+)?@example\\.test");

    @Mock private KeycloakAdminEmailVerifier keycloakAdmin;

    private SignupCanaryService service(String email, String token, TestAccountPolicy policy) {
        return new SignupCanaryService(email, token, policy, keycloakAdmin);
    }

    @Test
    @DisplayName("creates the ONE configured identity when the token matches")
    void createsConfiguredIdentity() {
        when(keycloakAdmin.createPasswordUser(EMAIL, PASSWORD))
                .thenReturn(KeycloakAdminEmailVerifier.CreateOutcome.CREATED);

        assertThat(service(EMAIL, TOKEN, POLICY).createIdentity(TOKEN, PASSWORD))
                .isEqualTo(SignupCanaryService.Result.CREATED);
    }

    @Test
    @DisplayName("a wrong or missing token creates nothing")
    void wrongTokenCreatesNothing() {
        SignupCanaryService service = service(EMAIL, TOKEN, POLICY);

        assertThat(service.createIdentity("wrong", PASSWORD)).isEqualTo(SignupCanaryService.Result.UNAUTHORIZED);
        assertThat(service.createIdentity(null, PASSWORD)).isEqualTo(SignupCanaryService.Result.UNAUTHORIZED);
        verify(keycloakAdmin, never()).createPasswordUser(anyString(), anyString());
    }

    @Test
    @DisplayName("an identity left over from the previous run is reported, not reused")
    void leftoverIdentityIsReported() {
        when(keycloakAdmin.createPasswordUser(EMAIL, PASSWORD))
                .thenReturn(KeycloakAdminEmailVerifier.CreateOutcome.ALREADY_EXISTS);

        assertThat(service(EMAIL, TOKEN, POLICY).createIdentity(TOKEN, PASSWORD))
                .isEqualTo(SignupCanaryService.Result.ALREADY_EXISTS);
    }

    @Test
    @DisplayName("a short password is refused before Keycloak is called")
    void shortPasswordRefused() {
        assertThat(service(EMAIL, TOKEN, POLICY).createIdentity(TOKEN, "short"))
                .isEqualTo(SignupCanaryService.Result.INVALID_PASSWORD);
        verify(keycloakAdmin, never()).createPasswordUser(any(), any());
    }

    @Test
    @DisplayName("disabled when unconfigured, when the token is weak, or when the address is not a test account")
    void disabledUnlessSafelyConfigured() {
        assertThat(service("", TOKEN, POLICY).createIdentity(TOKEN, PASSWORD))
                .isEqualTo(SignupCanaryService.Result.DISABLED);
        assertThat(service(EMAIL, "", POLICY).createIdentity("", PASSWORD))
                .isEqualTo(SignupCanaryService.Result.DISABLED);
        assertThat(service(EMAIL, "short-token", POLICY).createIdentity("short-token", PASSWORD))
                .isEqualTo(SignupCanaryService.Result.DISABLED);
        // Not a test account: its deletion would wait 30 days and tomorrow's run would collide.
        assertThat(service(EMAIL, TOKEN, new TestAccountPolicy("")).createIdentity(TOKEN, PASSWORD))
                .isEqualTo(SignupCanaryService.Result.DISABLED);
        assertThat(new SignupCanaryService(EMAIL, TOKEN, POLICY, null).createIdentity(TOKEN, PASSWORD))
                .isEqualTo(SignupCanaryService.Result.DISABLED);
        verify(keycloakAdmin, never()).createPasswordUser(any(), any());
    }
}

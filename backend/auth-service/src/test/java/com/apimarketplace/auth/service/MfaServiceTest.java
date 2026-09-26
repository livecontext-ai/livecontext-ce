package com.apimarketplace.auth.service;

import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.KeycloakOtpCredentialClient.OtpCredential;
import com.apimarketplace.auth.service.KeycloakOtpCredentialClient.RecoveryCodes;
import com.apimarketplace.auth.service.KeycloakOtpCredentialClient.SecondFactors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MfaService")
class MfaServiceTest {

    private static final String PROVIDER_ID = "kc-123";
    private static final String TOTP = "CONFIGURE_TOTP";
    private static final String CODES = "CONFIGURE_RECOVERY_AUTHN_CODES";

    @Mock private UserRepository userRepository;
    @Mock private KeycloakOtpCredentialClient keycloak;
    @Mock(answer = Answers.RETURNS_SELF) private AuditLogger.Builder auditBuilder;
    @Mock private AuditLogger auditLogger;
    @Mock private ObjectProvider<KeycloakOtpCredentialClient> keycloakProvider;
    @Mock private ObjectProvider<AuditLogger> auditProvider;

    private MfaService service;

    @BeforeEach
    void setUp() {
        when(keycloakProvider.getIfAvailable()).thenReturn(keycloak);
        when(keycloakProvider.getObject()).thenReturn(keycloak);
        when(auditProvider.getIfAvailable()).thenReturn(auditLogger);
        when(auditLogger.event(any())).thenReturn(auditBuilder);
        when(keycloak.getRequiredActions(anyString())).thenReturn(List.of());
        service = new MfaService(userRepository, keycloakProvider, auditProvider, true);
    }

    private static User user(long id, String providerId, String... roles) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        when(u.getProviderId()).thenReturn(providerId);
        when(u.getRoles()).thenReturn(Set.of(roles));
        return u;
    }

    private static OtpCredential otp(String id) {
        return new OtpCredential(id, "Phone", Instant.parse("2026-09-25T10:00:00Z"));
    }

    private static RecoveryCodes codes(Integer remaining) {
        return new RecoveryCodes("rc-1", remaining, 12);
    }

    private void holds(String providerId, List<OtpCredential> apps, RecoveryCodes recovery) {
        when(keycloak.listSecondFactors(providerId)).thenReturn(new SecondFactors(apps, recovery));
    }

    private MfaService.MfaStatus statusOf(User u) {
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        return service.getStatus(u.getId());
    }

    @Nested
    @DisplayName("getStatus")
    class GetStatus {

        @Test
        @DisplayName("answers available=false in CE, where no Keycloak client exists")
        void unavailableWithoutKeycloak() {
            when(keycloakProvider.getIfAvailable()).thenReturn(null);

            MfaService.MfaStatus status = service.getStatus(1L);

            assertThat(status.available()).isFalse();
            verify(userRepository, never()).findById(any());
        }

        @Test
        @DisplayName("answers available=false for an account Keycloak does not know")
        void unavailableWithoutProviderId() {
            assertThat(statusOf(user(1L, null, "USER")).available()).isFalse();
        }

        @Test
        @DisplayName("answers available=false for a user id that does not exist")
        void unavailableForUnknownUser() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThat(service.getStatus(99L).available()).isFalse();
            verify(keycloak, never()).listSecondFactors(anyString());
        }

        @Test
        @DisplayName("throws when the pending actions cannot be read, even if the factors could")
        void neverGuessesWhenActionsReadFails() {
            User u = user(1L, PROVIDER_ID, "USER");
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            holds(PROVIDER_ID, List.of(otp("c1")), codes(12));
            when(keycloak.getRequiredActions(PROVIDER_ID)).thenThrow(new IllegalStateException("503"));

            assertThatThrownBy(() -> service.getStatus(1L))
                    .isInstanceOf(MfaService.MfaStatusUnavailableException.class);
        }

        @Test
        @DisplayName("reports the authenticator apps and the recovery codes left")
        void reportsDevicesAndCodes() {
            holds(PROVIDER_ID, List.of(otp("c1")), codes(9));

            MfaService.MfaStatus status = statusOf(user(1L, PROVIDER_ID, "USER"));

            assertThat(status.available()).isTrue();
            assertThat(status.totpEnabled()).isTrue();
            assertThat(status.devices()).extracting(MfaService.TotpDevice::id).containsExactly("c1");
            assertThat(status.recoveryCodes()).isEqualTo(new MfaService.RecoveryCodesStatus(9, 12));
            assertThat(status.required()).isFalse();
        }

        @Test
        @DisplayName("reports no recovery codes as null, not as zero left")
        void noCodesIsNull() {
            holds(PROVIDER_ID, List.of(otp("c1")), null);

            assertThat(statusOf(user(1L, PROVIDER_ID, "USER")).recoveryCodes()).isNull();
        }

        @Test
        @DisplayName("marks an admin as required and reports a pending CONFIGURE_TOTP")
        void adminRequiredAndPending() {
            holds(PROVIDER_ID, List.of(), null);
            when(keycloak.getRequiredActions(PROVIDER_ID)).thenReturn(List.of(TOTP, CODES));

            MfaService.MfaStatus status = statusOf(user(1L, PROVIDER_ID, "USER", "ADMIN"));

            assertThat(status.totpEnabled()).isFalse();
            assertThat(status.required()).isTrue();
            assertThat(status.setupPending()).isTrue();
        }

        @Test
        @DisplayName("throws instead of reporting 'no factor' when Keycloak cannot be read")
        void neverGuessesWhenKeycloakFails() {
            User u = user(1L, PROVIDER_ID, "USER");
            when(userRepository.findById(1L)).thenReturn(Optional.of(u));
            when(keycloak.listSecondFactors(PROVIDER_ID)).thenThrow(new IllegalStateException("503"));

            assertThatThrownBy(() -> service.getStatus(1L))
                    .isInstanceOf(MfaService.MfaStatusUnavailableException.class);
        }

        @Test
        @DisplayName("does not mark an admin as required when enforcement is switched off")
        void adminNotRequiredWhenEnforcementOff() {
            service = new MfaService(userRepository, keycloakProvider, auditProvider, false);
            holds(PROVIDER_ID, List.of(), null);

            assertThat(statusOf(user(1L, PROVIDER_ID, "ADMIN")).required()).isFalse();
        }
    }

    @Nested
    @DisplayName("orphan recovery codes (last authenticator app removed)")
    class OrphanRecoveryCodes {

        @Test
        @DisplayName("are deleted, so a login is not left demanding a recovery code every time")
        void retiredWhenNoAppLeft() {
            holds(PROVIDER_ID, List.of(), codes(12));

            MfaService.MfaStatus status = statusOf(user(1L, PROVIDER_ID, "USER"));

            verify(keycloak).deleteCredential(PROVIDER_ID, "rc-1");
            assertThat(status.recoveryCodes()).isNull();
            verify(auditLogger).event("mfa.recovery_codes_retired");
        }

        @Test
        @DisplayName("are kept while an authenticator app remains")
        void keptWithAnApp() {
            holds(PROVIDER_ID, List.of(otp("c1")), codes(12));

            statusOf(user(1L, PROVIDER_ID, "USER"));

            verify(keycloak, never()).deleteCredential(anyString(), anyString());
        }

        @Test
        @DisplayName("a failed deletion still answers, reporting the codes as they are")
        void deletionFailureStillAnswers() {
            holds(PROVIDER_ID, List.of(), codes(12));
            doThrow(new IllegalStateException("502")).when(keycloak).deleteCredential(PROVIDER_ID, "rc-1");

            MfaService.MfaStatus status = statusOf(user(1L, PROVIDER_ID, "USER"));

            assertThat(status.available()).isTrue();
            assertThat(status.recoveryCodes()).isEqualTo(new MfaService.RecoveryCodesStatus(12, 12));
        }
    }

    @Nested
    @DisplayName("enforceTotpForAdmin")
    class EnforceForAdmin {

        @Test
        @DisplayName("leaves an admin with an app and recovery codes untouched")
        void alreadyEnrolled() {
            holds(PROVIDER_ID, List.of(otp("c1")), codes(12));

            assertThat(service.enforceTotpForAdmin(user(7L, PROVIDER_ID, "ADMIN")))
                    .isEqualTo(MfaService.AdminEnforcement.ALREADY_ENROLLED);
            verify(keycloak, never()).setRequiredActions(any(), anyList());
            verify(keycloak, never()).logoutAllSessions(any());
        }

        @Test
        @DisplayName("arms the app THEN the recovery codes for an admin with neither")
        void armsBoth() {
            holds(PROVIDER_ID, List.of(), null);

            assertThat(service.enforceTotpForAdmin(user(7L, PROVIDER_ID, "ADMIN")))
                    .isEqualTo(MfaService.AdminEnforcement.SETUP_REQUIRED);
            verify(keycloak).setRequiredActions(PROVIDER_ID, List.of(TOTP, CODES));
        }

        @Test
        @DisplayName("arms only the recovery codes for an admin who has an app but no codes")
        void armsCodesOnly() {
            holds(PROVIDER_ID, List.of(otp("c1")), null);

            assertThat(service.enforceTotpForAdmin(user(7L, PROVIDER_ID, "ADMIN")))
                    .isEqualTo(MfaService.AdminEnforcement.SETUP_REQUIRED);
            verify(keycloak).setRequiredActions(PROVIDER_ID, List.of(CODES));
        }

        @Test
        @DisplayName("does not write again when everything missing is already pending")
        void alreadyPending() {
            holds(PROVIDER_ID, List.of(), null);
            when(keycloak.getRequiredActions(PROVIDER_ID)).thenReturn(List.of(TOTP, CODES));

            assertThat(service.enforceTotpForAdmin(user(7L, PROVIDER_ID, "ADMIN")))
                    .isEqualTo(MfaService.AdminEnforcement.ALREADY_PENDING);
            verify(keycloak, never()).setRequiredActions(any(), anyList());
            verify(keycloak, never()).logoutAllSessions(any());
        }

        @Test
        @DisplayName("adds only what is not pending yet, keeping the admin's other pending actions")
        void armsOnlyTheMissingAndKeepsOthers() {
            holds(PROVIDER_ID, List.of(), null);
            when(keycloak.getRequiredActions(PROVIDER_ID)).thenReturn(List.of("UPDATE_PASSWORD", TOTP));

            service.enforceTotpForAdmin(user(7L, PROVIDER_ID, "ADMIN"));

            verify(keycloak).setRequiredActions(PROVIDER_ID, List.of("UPDATE_PASSWORD", TOTP, CODES));
        }

        @Test
        @DisplayName("ends the admin's sessions when it arms, so it is not deferred for 14 days")
        void endsSessionsWhenArming() {
            holds(PROVIDER_ID, List.of(), null);

            service.enforceTotpForAdmin(user(7L, PROVIDER_ID, "ADMIN"));

            verify(keycloak).logoutAllSessions(PROVIDER_ID);
        }

        @Test
        @DisplayName("still reports setup armed when ending the sessions fails")
        void logoutFailureKeepsEnrollmentArmed() {
            holds(PROVIDER_ID, List.of(), null);
            doThrow(new IllegalStateException("502")).when(keycloak).logoutAllSessions(PROVIDER_ID);

            assertThat(service.enforceTotpForAdmin(user(7L, PROVIDER_ID, "ADMIN")))
                    .isEqualTo(MfaService.AdminEnforcement.SETUP_REQUIRED);
            verify(auditBuilder).write();
        }

        @Test
        @DisplayName("writes an mfa.setup_required audit event naming what it armed")
        void auditsArming() {
            holds(PROVIDER_ID, List.of(otp("c1")), null);

            service.enforceTotpForAdmin(user(7L, PROVIDER_ID, "ADMIN"));

            verify(auditLogger).event("mfa.setup_required");
            verify(auditBuilder).detail("actions", CODES);
            verify(auditBuilder).write();
        }
    }

    @Nested
    @DisplayName("enforceTotpForAllAdmins")
    class EnforceForAll {

        @Test
        @DisplayName("keeps going when one admin fails, and counts only the newly armed")
        void oneFailureDoesNotStopTheSweep() {
            User broken = user(1L, "kc-broken", "ADMIN");
            User bare = user(2L, "kc-bare", "ADMIN");
            User enrolled = user(3L, "kc-enrolled", "ADMIN");
            when(userRepository.findEnabledAdminsWithProviderId()).thenReturn(List.of(broken, bare, enrolled));
            when(keycloak.listSecondFactors("kc-broken")).thenThrow(new IllegalStateException("404"));
            holds("kc-bare", List.of(), null);
            holds("kc-enrolled", List.of(otp("c1")), codes(12));

            assertThat(service.enforceTotpForAllAdmins()).isEqualTo(1);
            verify(keycloak).setRequiredActions(eq("kc-bare"), eq(List.of(TOTP, CODES)));
        }

        @Test
        @DisplayName("does nothing when enforcement is switched off")
        void noopWhenDisabled() {
            service = new MfaService(userRepository, keycloakProvider, auditProvider, false);

            assertThat(service.enforceTotpForAllAdmins()).isZero();
            verify(userRepository, never()).findEnabledAdminsWithProviderId();
        }

        @Test
        @DisplayName("does nothing in CE, where no Keycloak client exists")
        void noopWithoutKeycloak() {
            when(keycloakProvider.getIfAvailable()).thenReturn(null);

            assertThat(service.enforceTotpForAllAdmins()).isZero();
            verify(userRepository, never()).findEnabledAdminsWithProviderId();
        }
    }
}

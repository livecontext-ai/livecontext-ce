package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.credential.client.CredentialClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.apimarketplace.credential.client.dto.CredentialScopesDto;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LC-029 producer. The web-search destination bound read a sensitivity tag that no component
 * anywhere wrote, so it classified every execution STANDARD and refused nothing on any
 * shipped path. This class is the input it was missing, and these tests pin the answer it
 * gives, including every case where it must answer "not restricted" rather than invent a
 * denial.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LC-029 restricted-scope tenant detection")
class RestrictedScopeTenantDetectorTest {

    private static final String TENANT = "tenant-1";

    @Mock private CredentialClient credentialClient;

    private RestrictedScopeTenantDetector detector(RestrictedScopeTenantDetector.Mode mode) {
        return new RestrictedScopeTenantDetector(credentialClient, mode, Duration.ofSeconds(60));
    }

    @Nested
    @DisplayName("classification")
    class Classification {

        @Test
        @DisplayName("a tenant holding a Gmail credential is restricted")
        void gmailCredentialIsRestricted() {
            when(credentialClient.getConfiguredIntegrations(TENANT))
                    .thenReturn(Set.of("slack", "gmail", "notion"));

            assertThat(detector(RestrictedScopeTenantDetector.Mode.BOUND)
                    .holdsRestrictedScopeCredential(TENANT)).isTrue();
        }

        @Test
        @DisplayName("the integration name is matched the way the catalog normalizes it")
        void integrationNameIsNormalized() {
            // "Google Drive" is the same integration as google_drive; a control that only
            // matched the exact stored spelling would miss it.
            when(credentialClient.getConfiguredIntegrations(TENANT))
                    .thenReturn(Set.of("Google Drive"));

            assertThat(detector(RestrictedScopeTenantDetector.Mode.BOUND)
                    .holdsRestrictedScopeCredential(TENANT)).isTrue();
        }

        @Test
        @DisplayName("a tenant holding only ordinary integrations is not restricted")
        void ordinaryIntegrationsAreNotRestricted() {
            when(credentialClient.getConfiguredIntegrations(TENANT))
                    .thenReturn(Set.of("slack", "notion", "stripe"));

            assertThat(detector(RestrictedScopeTenantDetector.Mode.BOUND)
                    .holdsRestrictedScopeCredential(TENANT)).isFalse();
        }

        @Test
        @DisplayName("a tenant with no credential at all is not restricted")
        void noCredentialsIsNotRestricted() {
            when(credentialClient.getConfiguredIntegrations(TENANT)).thenReturn(Set.of());

            assertThat(detector(RestrictedScopeTenantDetector.Mode.BOUND)
                    .holdsRestrictedScopeCredential(TENANT)).isFalse();
        }
    }

    @Nested
    @DisplayName("cases that must never invent a denial")
    class NeverInventsADenial {

        @Test
        @DisplayName("mode=off asks nobody and answers not restricted")
        void offModeSkipsTheLookupEntirely() {
            assertThat(detector(RestrictedScopeTenantDetector.Mode.OFF)
                    .holdsRestrictedScopeCredential(TENANT)).isFalse();

            verifyNoInteractions(credentialClient);
        }

        @Test
        @DisplayName("a blank tenant is not restricted and costs no lookup")
        void blankTenantIsNotRestricted() {
            RestrictedScopeTenantDetector detector = detector(RestrictedScopeTenantDetector.Mode.BOUND);

            assertThat(detector.holdsRestrictedScopeCredential(null)).isFalse();
            assertThat(detector.holdsRestrictedScopeCredential("   ")).isFalse();

            verifyNoInteractions(credentialClient);
        }

        @Test
        @DisplayName("with no credential client wired the answer is not restricted")
        void noCredentialClientIsNotRestricted() {
            RestrictedScopeTenantDetector detector = new RestrictedScopeTenantDetector(
                    null, RestrictedScopeTenantDetector.Mode.STRICT, Duration.ofSeconds(60));

            assertThat(detector.holdsRestrictedScopeCredential(TENANT)).isFalse();
        }

        @Test
        @DisplayName("a lookup that throws answers not restricted instead of failing the call")
        void lookupFailureIsNotRestricted() {
            when(credentialClient.getConfiguredIntegrations(TENANT))
                    .thenThrow(new IllegalStateException("auth-service down"));

            assertThat(detector(RestrictedScopeTenantDetector.Mode.BOUND)
                    .holdsRestrictedScopeCredential(TENANT)).isFalse();
        }
    }

    @Nested
    @DisplayName("caching")
    class Caching {

        @Test
        @DisplayName("repeated calls inside the window ask auth-service once")
        void answerIsCachedWithinTheWindow() {
            when(credentialClient.getConfiguredIntegrations(TENANT)).thenReturn(Set.of("gmail"));
            RestrictedScopeTenantDetector detector = detector(RestrictedScopeTenantDetector.Mode.BOUND);

            for (int i = 0; i < 5; i++) {
                assertThat(detector.holdsRestrictedScopeCredential(TENANT)).isTrue();
            }

            verify(credentialClient, times(1)).getConfiguredIntegrations(TENANT);
        }

        @Test
        @DisplayName("an expired entry is looked up again, so a revoked credential is seen")
        void expiredEntryIsRefreshed() {
            when(credentialClient.getConfiguredIntegrations(TENANT))
                    .thenReturn(Set.of("gmail"))
                    .thenReturn(Set.of("slack"));
            RestrictedScopeTenantDetector detector = new RestrictedScopeTenantDetector(
                    credentialClient, RestrictedScopeTenantDetector.Mode.BOUND, Duration.ZERO);

            assertThat(detector.holdsRestrictedScopeCredential(TENANT)).isTrue();
            assertThat(detector.holdsRestrictedScopeCredential(TENANT)).isFalse();

            verify(credentialClient, times(2)).getConfiguredIntegrations(TENANT);
        }

        @Test
        @DisplayName("one tenant's answer is never served to another")
        void answersAreKeyedByTenant() {
            when(credentialClient.getConfiguredIntegrations("t-gmail")).thenReturn(Set.of("gmail"));
            when(credentialClient.getConfiguredIntegrations("t-plain")).thenReturn(Set.of("slack"));
            RestrictedScopeTenantDetector detector = detector(RestrictedScopeTenantDetector.Mode.BOUND);

            assertThat(detector.holdsRestrictedScopeCredential("t-gmail")).isTrue();
            assertThat(detector.holdsRestrictedScopeCredential("t-plain")).isFalse();
            assertThat(detector.holdsRestrictedScopeCredential("t-gmail")).isTrue();
        }

        @Test
        @DisplayName("invalidate forces the next call to ask again")
        void invalidateClearsTheCache() {
            when(credentialClient.getConfiguredIntegrations(TENANT)).thenReturn(Set.of("gmail"));
            RestrictedScopeTenantDetector detector = detector(RestrictedScopeTenantDetector.Mode.BOUND);

            assertThat(detector.holdsRestrictedScopeCredential(TENANT)).isTrue();
            detector.invalidate();
            assertThat(detector.holdsRestrictedScopeCredential(TENANT)).isTrue();

            verify(credentialClient, times(2)).getConfiguredIntegrations(TENANT);
        }
    }

    @Nested
    @DisplayName("decided on the GRANTED scopes, not the integration name (audit round 2)")
    class GrantedScopes {

        private CredentialScopesDto oauth(String... scopes) {
            return new CredentialScopesDto("OAUTH2", List.of(scopes));
        }

        @Test
        @DisplayName("gmail connected with gmail.send only is NOT restricted")
        void gmailSendOnlyIsNotRestricted() {
            when(credentialClient.getConfiguredIntegrations(TENANT)).thenReturn(Set.of("gmail"));
            when(credentialClient.getCredentialScopes(TENANT, "gmail"))
                    .thenReturn(Optional.of(oauth("https://www.googleapis.com/auth/gmail.send")));

            assertThat(detector(RestrictedScopeTenantDetector.Mode.STRICT)
                    .holdsRestrictedScopeCredential(TENANT)).isFalse();
        }

        @Test
        @DisplayName("a Calendar-only Google credential is NOT restricted")
        void calendarOnlyIsNotRestricted() {
            when(credentialClient.getConfiguredIntegrations(TENANT)).thenReturn(Set.of("google_calendar"));
            when(credentialClient.getCredentialScopes(TENANT, "google_calendar"))
                    .thenReturn(Optional.of(oauth("https://www.googleapis.com/auth/calendar",
                            "https://www.googleapis.com/auth/calendar.events")));

            assertThat(detector(RestrictedScopeTenantDetector.Mode.STRICT)
                    .holdsRestrictedScopeCredential(TENANT)).isFalse();
        }

        @Test
        @DisplayName("gmail.readonly IS restricted")
        void gmailReadonlyIsRestricted() {
            when(credentialClient.getConfiguredIntegrations(TENANT)).thenReturn(Set.of("gmail"));
            when(credentialClient.getCredentialScopes(TENANT, "gmail"))
                    .thenReturn(Optional.of(oauth("https://www.googleapis.com/auth/gmail.send",
                            "https://www.googleapis.com/auth/gmail.readonly")));

            assertThat(detector(RestrictedScopeTenantDetector.Mode.STRICT)
                    .holdsRestrictedScopeCredential(TENANT)).isTrue();
        }

        @Test
        @DisplayName("a generic Google app granted full mail access IS restricted")
        void genericGoogleAppWithFullMailIsRestricted() {
            when(credentialClient.getConfiguredIntegrations(TENANT)).thenReturn(Set.of("google_workspace_custom"));
            when(credentialClient.getCredentialScopes(TENANT, "google_workspace_custom"))
                    .thenReturn(Optional.of(oauth("https://mail.google.com/")));

            assertThat(detector(RestrictedScopeTenantDetector.Mode.STRICT)
                    .holdsRestrictedScopeCredential(TENANT)).isTrue();
        }

        @Test
        @DisplayName("non-Google integrations are never looked up")
        void nonGoogleIntegrationsAreNotLookedUp() {
            when(credentialClient.getConfiguredIntegrations(TENANT)).thenReturn(Set.of("slack", "stripe"));

            assertThat(detector(RestrictedScopeTenantDetector.Mode.STRICT)
                    .holdsRestrictedScopeCredential(TENANT)).isFalse();
            verify(credentialClient, org.mockito.Mockito.never())
                    .getCredentialScopes(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        }
    }

    @Nested
    @DisplayName("mode property")
    class ModeProperty {

        @Test
        @DisplayName("the shipped default is strict: an empty destination list refuses fetch (audit round 2)")
        void defaultIsStrict() {
            assertThat(RestrictedScopeTenantDetector.Mode.parse(null))
                    .isEqualTo(RestrictedScopeTenantDetector.Mode.STRICT);
            assertThat(RestrictedScopeTenantDetector.Mode.parse(""))
                    .isEqualTo(RestrictedScopeTenantDetector.Mode.STRICT);
            assertThat(RestrictedScopeTenantDetector.Mode.parse("   "))
                    .isEqualTo(RestrictedScopeTenantDetector.Mode.STRICT);
        }

        @Test
        @DisplayName("a typo falls back to strict rather than relaxing the control")
        void unknownValueFallsBackToStrict() {
            assertThat(RestrictedScopeTenantDetector.Mode.parse("disabled"))
                    .isEqualTo(RestrictedScopeTenantDetector.Mode.STRICT);
        }

        @Test
        @DisplayName("off, bound and strict are all reachable and case-insensitive")
        void everyModeIsReachable() {
            assertThat(RestrictedScopeTenantDetector.Mode.parse("OFF"))
                    .isEqualTo(RestrictedScopeTenantDetector.Mode.OFF);
            assertThat(RestrictedScopeTenantDetector.Mode.parse(" Strict "))
                    .isEqualTo(RestrictedScopeTenantDetector.Mode.STRICT);
            assertThat(RestrictedScopeTenantDetector.Mode.parse("bound"))
                    .isEqualTo(RestrictedScopeTenantDetector.Mode.BOUND);
        }
    }
}

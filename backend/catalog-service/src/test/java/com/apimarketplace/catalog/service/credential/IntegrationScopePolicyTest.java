package com.apimarketplace.catalog.service.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that decides whether a standard connection could ever grant what an
 * endpoint needs. Getting it wrong in either direction costs a person real time: one
 * way sends them through a consent screen that cannot grant the scope, the other tells
 * them to go and register an OAuth application they do not need.
 */
@DisplayName("IntegrationScopePolicy")
class IntegrationScopePolicyTest {

    private static final String LABELS = "https://www.googleapis.com/auth/gmail.labels";
    private static final String SEND = "https://www.googleapis.com/auth/gmail.send";
    private static final String READONLY = "https://www.googleapis.com/auth/gmail.readonly";

    private static IntegrationScopePolicy managedCloudGmail() {
        return IntegrationScopePolicy.declared(List.of(LABELS, SEND), List.of(READONLY), false);
    }

    @Nested
    @DisplayName("on managed cloud, where a platform-shared OAuth app exists")
    class ManagedCloud {

        @Test
        @DisplayName("a restricted scope is named as needing the user's own OAuth client")
        void restrictedScopeNeedsOwnClient() {
            assertThat(managedCloudGmail().scopesNeedingOwnOAuthClient(List.of(READONLY)))
                    .containsExactly(READONLY);
        }

        @Test
        @DisplayName("a scope the shared app does request is grantable, so nothing is flagged")
        void platformScopeIsGrantable() {
            assertThat(managedCloudGmail().scopesNeedingOwnOAuthClient(List.of(SEND))).isEmpty();
        }

        @Test
        @DisplayName("a required scope declared in NEITHER list is flagged too, because the shared app still never asks for it")
        void undeclaredScopeIsAlsoUngrantable() {
            // The trap this guards: keying on membership of byokOnlyScopes would call an
            // undeclared scope grantable, and a Standard connect would then be offered as
            // the remedy for a scope it never requests.
            assertThat(managedCloudGmail().scopesNeedingOwnOAuthClient(
                    List.of("https://www.googleapis.com/auth/gmail.settings.basic")))
                    .containsExactly("https://www.googleapis.com/auth/gmail.settings.basic");
        }

        @Test
        @DisplayName("a template whose shared app requests nothing makes every scope need the user own client")
        void fullyRestrictedIntegration() {
            IntegrationScopePolicy classroom =
                    IntegrationScopePolicy.declared(List.of(), List.of("courses.readonly"), false);
            assertThat(classroom.scopesNeedingOwnOAuthClient(List.of("courses.readonly")))
                    .containsExactly("courses.readonly");
        }
    }

    @Nested
    @DisplayName("on an embedded-auth install, where the user's own client is the only client")
    class EmbeddedAuth {

        @Test
        @DisplayName("a restricted scope is grantable, because the authorize request carries the whole catalog set")
        void restrictedScopeIsGrantable() {
            IntegrationScopePolicy embedded =
                    IntegrationScopePolicy.declared(List.of(LABELS, SEND), List.of(READONLY), true);
            assertThat(embedded.scopesNeedingOwnOAuthClient(List.of(READONLY))).isEmpty();
            assertThat(embedded.grantableByStandardConnection())
                    .containsExactlyInAnyOrder(LABELS, SEND, READONLY);
        }

        @Test
        @DisplayName("a scope declared nowhere is still ungrantable, edition or not")
        void undeclaredScopeStaysUngrantable() {
            IntegrationScopePolicy embedded =
                    IntegrationScopePolicy.declared(List.of(LABELS), List.of(READONLY), true);
            assertThat(embedded.scopesNeedingOwnOAuthClient(List.of("something.else")))
                    .containsExactly("something.else");
        }
    }

    @Nested
    @DisplayName("when the template declares no policy")
    class UnknownPolicy {

        @Test
        @DisplayName("nothing is asserted, because a wrong assertion here sends a person to register an OAuth app for no reason")
        void unknownNeverAsserts() {
            assertThat(IntegrationScopePolicy.unknown().declared()).isFalse();
            assertThat(IntegrationScopePolicy.unknown().scopesNeedingOwnOAuthClient(List.of(READONLY)))
                    .isEmpty();
            assertThat(IntegrationScopePolicy.unknown().grantableByStandardConnection()).isEmpty();
        }
    }

    @Nested
    @DisplayName("scope comparison")
    class Comparison {

        @Test
        @DisplayName("a comma-joined platform list still grants its members, matching how granted scopes are read at execution")
        void commaJoinedPlatformListIsSplit() {
            // A declared list can arrive from the signed catalog bundle in the same
            // comma-joined shape a provider answers in. Reading it whole would make every
            // scope look ungrantable and send every caller to a custom OAuth connection.
            IntegrationScopePolicy joined =
                    IntegrationScopePolicy.declared(List.of(LABELS + "," + SEND), List.of(), false);
            assertThat(joined.scopesNeedingOwnOAuthClient(List.of(SEND))).isEmpty();
        }

        @Test
        @DisplayName("a required scope that legitimately contains a space is matched whole, never split")
        void requiredScopeWithASpaceIsMatchedWhole() {
            // workday declares "Tenant Non-Configurable". Splitting the REQUIRED side would
            // compare two tokens that no grant can ever hold.
            IntegrationScopePolicy workday = IntegrationScopePolicy.declared(
                    List.of("Tenant Non-Configurable"), List.of(), false);
            assertThat(workday.scopesNeedingOwnOAuthClient(List.of("Tenant Non-Configurable")))
                    .isEmpty();
        }

        @Test
        @DisplayName("an empty requirement needs nothing, whatever the policy says")
        void emptyRequirement() {
            assertThat(managedCloudGmail().scopesNeedingOwnOAuthClient(List.of())).isEmpty();
        }
    }
}

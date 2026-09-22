package com.apimarketplace.catalog.service.credential;

import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.credential.client.dto.CredentialIdentityDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The glue: which of the caller's accounts belong to the endpoint integration, and what
 * happens when a lookup fails.
 *
 * <p>The account FILTER is the load-bearing part. It has to admit exactly what the
 * executor admits: offering an account the run then refuses costs a failed call, and
 * hiding one it would have accepted hides the answer this whole feature exists to give.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EndpointCredentialCapabilityService")
class EndpointCredentialCapabilityServiceTest {

    private static final String TENANT = "121";
    private static final String READONLY = "https://www.googleapis.com/auth/gmail.readonly";

    @Mock private IntegrationScopePolicyReader policyReader;
    @Mock private UserCredentialService userCredentialService;

    private EndpointCredentialCapabilityService service() {
        return new EndpointCredentialCapabilityService(policyReader, userCredentialService);
    }

    private static Map<String, Object> base() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("type", "oauth2");
        base.put("requiredScopes", List.of(READONLY));
        return base;
    }

    private static CredentialIdentityDto identity(String name, String integration, List<String> scopes) {
        return new CredentialIdentityDto(1L, name, integration, "active", "OAuth2", scopes, true);
    }

    private static CredentialIdentityDto apiKeyIdentity(String name, String integration) {
        return new CredentialIdentityDto(2L, name, integration, "active", "API Key", null, true);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> accountsOf(Map<String, Object> block) {
        return (List<Map<String, Object>>) block.get("accounts");
    }

    @Nested
    @DisplayName("which accounts are considered")
    class AccountFilter {

        @Test
        @DisplayName("an account of another integration is left out")
        void otherIntegrationsAreExcluded() {
            when(policyReader.forIntegration(anyString())).thenReturn(IntegrationScopePolicy.unknown());
            when(userCredentialService.tryListIdentities(TENANT)).thenReturn(Optional.of(List.of(
                    identity("Slack", "slack", List.of()),
                    identity("Perso", "gmail", List.of(READONLY)))));

            Map<String, Object> block = service().describe(base(), "gmail", List.of(READONLY), TENANT);
            assertThat(accountsOf(block)).hasSize(1);
            assertThat(accountsOf(block).get(0).get("name")).isEqualTo("Perso");
        }

        @Test
        @DisplayName("punctuation in the integration does not make it a different provider, as the executor also holds")
        void identityIsNormalisedLikeTheExecutor() {
            // stability-ai against stabilityai. The executor matches these; a listing that
            // did not would report "no account connected" for one the run resolves fine.
            when(policyReader.forIntegration(anyString())).thenReturn(IntegrationScopePolicy.unknown());
            when(userCredentialService.tryListIdentities(TENANT)).thenReturn(Optional.of(List.of(
                    identity("Main", "stability-ai", List.of()))));

            Map<String, Object> block = service().describe(base(), "stabilityai", List.of(), TENANT);
            assertThat(accountsOf(block)).hasSize(1);
        }

        @Test
        @DisplayName("a credential with no integration is admitted by its NAME, which is how the workflow-native connectors identify themselves")
        void blankIntegrationMatchesByName() {
            when(policyReader.forIntegration(anyString())).thenReturn(IntegrationScopePolicy.unknown());
            when(userCredentialService.tryListIdentities(TENANT)).thenReturn(Optional.of(List.of(
                    identity("smtp", null, List.of()))));

            Map<String, Object> block = service().describe(base(), "smtp", List.of(), TENANT);
            assertThat(accountsOf(block)).hasSize(1);
        }

        @Test
        @DisplayName("the -credential suffix a requirement carries is stripped before matching")
        void requirementSuffixIsStripped() {
            when(policyReader.forIntegration(anyString())).thenReturn(IntegrationScopePolicy.unknown());
            when(userCredentialService.tryListIdentities(TENANT)).thenReturn(Optional.of(List.of(
                    identity("Main", "acme", List.of()))));

            Map<String, Object> block = service().describe(base(), "acme-credential", List.of(), TENANT);
            assertThat(accountsOf(block)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("what the account carries through")
    class AccountMapping {

        @Test
        @DisplayName("the auth mechanism travels, so a non-OAuth account is not judged on scopes it cannot have")
        void theTypeReachesTheAccount() {
            // The field exists end to end only if it is READ here. Plumbed and unread, it
            // would leave the scope comparison running against a credential the executor
            // exempts, and the contract would call a working account unusable.
            when(policyReader.forIntegration("gmail")).thenReturn(IntegrationScopePolicy.unknown());
            when(userCredentialService.tryListIdentities(TENANT))
                    .thenReturn(Optional.of(List.of(apiKeyIdentity("PAT", "gmail"))));

            Map<String, Object> block = service().describe(base(), "gmail", List.of(READONLY), TENANT);
            assertThat(accountsOf(block).get(0).get("canRunThis")).isEqualTo(true);
            assertThat(accountsOf(block).get(0)).doesNotContainKey("missingScopes");
        }
    }

    @Nested
    @DisplayName("fail-open, in every direction")
    class FailsOpen {

        @Test
        @DisplayName("a blank integration returns the contract block untouched and asks nobody")
        void blankIntegration() {
            Map<String, Object> original = base();
            assertThat(service().describe(original, "  ", List.of(READONLY), TENANT))
                    .isSameAs(original);
            verifyNoInteractions(policyReader, userCredentialService);
        }

        @Test
        @DisplayName("no tenant states the policy and says NOTHING about accounts, because zero accounts would be a claim")
        void noTenant() {
            // The trap: answering an empty account list here produces "No Gmail account is
            // connected", a statement about an account nobody named, addressed to a person
            // who may well have one connected.
            when(policyReader.forIntegration("gmail")).thenReturn(
                    IntegrationScopePolicy.declared(List.of(), List.of(READONLY), false));

            Map<String, Object> block = service().describe(base(), "gmail", List.of(READONLY), null);
            assertThat(block.get("standardConnectionGrantsThis")).isEqualTo(false);
            assertThat(block).doesNotContainKey("accounts");
            assertThat(block).doesNotContainKey("remedy");
            verifyNoInteractions(userCredentialService);
        }

        @Test
        @DisplayName("an UNAVAILABLE listing says nothing about accounts, which is the realistic auth-service failure")
        void listingUnavailableSaysNothing() {
            // This is the shape production actually takes. The ordinary listing catches
            // every failure and answers an empty list, so an unreachable auth-service and
            // "this account has no credentials" used to be the same answer - and one of
            // them tells a person to connect a service they already have. The capability
            // therefore reads through the listing that can say it could not look.
            when(policyReader.forIntegration("gmail")).thenReturn(
                    IntegrationScopePolicy.declared(List.of(), List.of(READONLY), false));
            when(userCredentialService.tryListIdentities(TENANT)).thenReturn(Optional.empty());

            Map<String, Object> block = service().describe(base(), "gmail", List.of(READONLY), TENANT);
            assertThat(block).doesNotContainKey("accounts");
            assertThat(block).doesNotContainKey("remedy");
            // What could still be worked out is still said.
            assertThat(block.get("standardConnectionGrantsThis")).isEqualTo(false);
        }

        @Test
        @DisplayName("an EMPTY listing is the opposite answer and DOES name the missing connection")
        void emptyListingIsAnAnswer() {
            // The pair matters: without this, the test above would pass just as well on an
            // implementation that never says anything about accounts at all.
            when(policyReader.forIntegration("gmail")).thenReturn(IntegrationScopePolicy.unknown());
            when(userCredentialService.tryListIdentities(TENANT)).thenReturn(Optional.of(List.of()));

            Map<String, Object> block = service().describe(base(), "gmail", List.of(READONLY), TENANT);
            assertThat(accountsOf(block)).isEmpty();
            assertThat((String) block.get("remedy")).contains("No Gmail account is connected");
        }

        @Test
        @DisplayName("the credential service throwing outright is still absorbed")
        void credentialServiceThrows() {
            when(policyReader.forIntegration("gmail")).thenReturn(IntegrationScopePolicy.unknown());
            when(userCredentialService.tryListIdentities(TENANT))
                    .thenThrow(new RuntimeException("auth-service unreachable"));

            Map<String, Object> original = base();
            assertThat(service().describe(original, "gmail", List.of(READONLY), TENANT))
                    .isSameAs(original);
        }

        @Test
        @DisplayName("a null base block is passed through rather than invented")
        void nullBase() {
            assertThat(service().describe(null, "gmail", List.of(), TENANT)).isNull();
        }
    }

    @Nested
    @DisplayName("the tenant-free scope question a refusal can ask")
    class ScopesNeedingOwnClient {

        @Test
        @DisplayName("answers from the policy without touching the credential service")
        void answersWithoutListingAccounts() {
            when(policyReader.forIntegration("gmail")).thenReturn(
                    IntegrationScopePolicy.declared(List.of(), List.of(READONLY), false));

            assertThat(service().scopesNeedingOwnOAuthClient("gmail", List.of(READONLY)))
                    .containsExactly(READONLY);
            verifyNoInteractions(userCredentialService);
        }

        @Test
        @DisplayName("an empty requirement, a blank integration, or a throwing reader all answer nothing")
        void emptyAnswers() {
            assertThat(service().scopesNeedingOwnOAuthClient("gmail", List.of())).isEmpty();
            assertThat(service().scopesNeedingOwnOAuthClient(" ", List.of(READONLY))).isEmpty();

            when(policyReader.forIntegration("boom")).thenThrow(new RuntimeException("down"));
            assertThat(service().scopesNeedingOwnOAuthClient("boom", List.of(READONLY))).isEmpty();
        }
    }
}

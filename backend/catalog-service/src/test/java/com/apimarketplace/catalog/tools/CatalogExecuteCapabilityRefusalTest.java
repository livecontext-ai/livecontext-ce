package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.catalog.service.credential.EndpointCredentialCapabilityService;
import com.apimarketplace.catalog.service.credential.IntegrationScopePolicy;
import com.apimarketplace.catalog.service.credential.IntegrationScopePolicyReader;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialIdentityDto;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the CREDENTIALS_REQUIRED refusal says once it can see the account inventory.
 *
 * <p>The sentence it used to give was "add your Gmail key to this account to run it",
 * for an endpoint that needs a scope the shared consent screen never requests. A user
 * following that advice connects Gmail, comes back, and is refused again - by a
 * different guard, with a different message. The refusal has to name the step that
 * works, and when a connected account of the caller's own could already run the call,
 * naming THAT is the step that works.
 */
@DisplayName("CREDENTIALS_REQUIRED - the remedy names a step that works")
class CatalogExecuteCapabilityRefusalTest {

    private static final String TOOL_ID = "1e58e9ef-44fc-4378-b4fa-61e80222aac0";
    private static final String TENANT = "121";
    private static final String READONLY = "https://www.googleapis.com/auth/gmail.readonly";
    private static final String SEND = "https://www.googleapis.com/auth/gmail.send";
    private static final String LABELS = "https://www.googleapis.com/auth/gmail.labels";

    private CatalogExecuteModule module;
    private RestTemplate restTemplate;
    private CredentialClient credentialClient;
    private IntegrationScopePolicyReader policyReader;
    private UserCredentialService userCredentialService;

    @BeforeEach
    void setUp() throws Exception {
        credentialClient = mock(CredentialClient.class);
        policyReader = mock(IntegrationScopePolicyReader.class);
        userCredentialService = mock(UserCredentialService.class);

        module = new CatalogExecuteModule(new ObjectMapper(), credentialClient);
        module.setCredentialCapability(
                new EndpointCredentialCapabilityService(policyReader, userCredentialService));

        restTemplate = mock(RestTemplate.class);
        Field rtField = CatalogExecuteModule.class.getDeclaredField("restTemplate");
        rtField.setAccessible(true);
        rtField.set(module, restTemplate);
        Field portField = CatalogExecuteModule.class.getDeclaredField("serverPort");
        portField.setAccessible(true);
        portField.setInt(module, 8081);

        // Gmail as the managed cloud declares it: the shared client asks for labels and
        // send; reading a mailbox is restricted and never appears on that consent screen.
        when(policyReader.forIntegration("gmail")).thenReturn(
                IntegrationScopePolicy.declared(List.of(LABELS, SEND), List.of(READONLY), false));

        // The pre-flight gate stands down: the caller HAS a Gmail credential.
        CredentialSummaryDto connected = new CredentialSummaryDto();
        connected.setName("Perso");
        when(credentialClient.getDefaultCredential(eq(TENANT), anyString()))
                .thenReturn(Optional.of(connected));

        stubInfo();
        stubUpstreamCredentialsRequired();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("iconSlug", "gmail");
        info.put("integrationName", "gmail");
        info.put("name", "list_messages");
        info.put("authType", "oauth2");
        info.put("requiredScopes", List.of(READONLY));
        when(restTemplate.exchange(
                contains("/api/catalog/tools/" + TOOL_ID + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(info, HttpStatus.OK));
    }

    /** The executor's own answer when it resolved no usable key. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubUpstreamCredentialsRequired() {
        String body = "{\"error\":\"credentials_required\","
                + "\"result\":{\"credential_name\":\"gmail\",\"credential_type\":\"oauth2\"},"
                + "\"metadata\":{\"iconSlug\":\"gmail\",\"toolName\":\"list_messages\"}}";
        when(restTemplate.exchange(
                contains("/catalog/v1/tools/" + TOOL_ID + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }

    private void accounts(CredentialIdentityDto... identities) {
        when(userCredentialService.tryListIdentities(TENANT)).thenReturn(Optional.of(List.of(identities)));
    }

    private static CredentialIdentityDto gmailAccount(String name, boolean isDefault, String... scopes) {
        return new CredentialIdentityDto(1L, name, "gmail", "active", "OAuth2", List.of(scopes), isDefault);
    }

    private ToolExecutionResult refusal() {
        return module.execute("execute",
                Map.of("tool_id", TOOL_ID, "params", Map.of("userId", "me")),
                TENANT,
                new ToolExecutionContext(TENANT, Map.of(), Map.of(), Set.of(), null, null, null, null))
                .orElseThrow();
    }

    @Test
    @DisplayName("an account that CAN run it is named for credential_name, instead of advice to add a key")
    void namesTheAccountThatWorks() {
        accounts(gmailAccount("Perso", true, SEND),
                 new CredentialIdentityDto(2L, "Boulot", "gmail", "active", "OAuth2",
                         List.of(READONLY, SEND), false));

        ToolExecutionResult result = refusal();

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.CREDENTIALS_REQUIRED);
        assertThat(result.error())
                .startsWith("CREDENTIALS_REQUIRED: ")
                .contains("credential_name=\"Boulot\"")
                .doesNotContain("Add your Gmail key to this account");
    }

    @Test
    @DisplayName("with no account that can run it, the remedy asks for their own OAuth client - the standard connect never grants this scope")
    void asksForAnOwnOAuthClient() {
        accounts(gmailAccount("Perso", true, SEND));

        assertThat(refusal().error())
                .contains("own OAuth client credentials")
                .contains(READONLY)
                .doesNotContain("Add your Gmail key to this account");
    }

    @Test
    @DisplayName("the structured half travels beside the sentence, under its own key so the existing credential field is untouched")
    @SuppressWarnings("unchecked")
    void structuredCapabilityIsAttached() {
        accounts(gmailAccount("Perso", true, SEND));

        Map<String, Object> metadata = refusal().metadata();
        assertThat(metadata.get("credential")).isEqualTo(Map.of("type", "oauth2"));

        Map<String, Object> capability = (Map<String, Object>) metadata.get("credentialCapability");
        assertThat(capability.get("standardConnectionGrantsThis")).isEqualTo(false);
        assertThat(capability.get("scopesNeedingOwnOAuthClient")).isEqualTo(List.of(READONLY));
        List<Map<String, Object>> listed = (List<Map<String, Object>>) capability.get("accounts");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("canRunThis")).isEqualTo(false);
        assertThat(listed.get(0).get("missingScopes")).isEqualTo(List.of(READONLY));
    }

    @Test
    @DisplayName("with no capability bean at all, the refusal is exactly the sentence it always was")
    void failsOpenToThePreviousSentence() {
        // The capability sharpens a refusal; it must never be the reason one exists, nor
        // change one it cannot improve.
        module.setCredentialCapability(null);
        accounts(gmailAccount("Perso", true, SEND));

        assertThat(refusal().error())
                .contains("no Gmail key is available, on this account or on this platform");
    }

    @Test
    @DisplayName("an unreadable scope policy makes the capability go quiet rather than invent a restriction")
    void unknownPolicyDoesNotInventARestriction() {
        // Telling a person to register an OAuth application they do not need costs more
        // than saying nothing, so silence is the direction an unknown fails in.
        when(policyReader.forIntegration("gmail")).thenReturn(IntegrationScopePolicy.unknown());
        accounts(gmailAccount("Perso", true, SEND));

        assertThat(refusal().error()).doesNotContain("OWN OAuth client");
    }
}

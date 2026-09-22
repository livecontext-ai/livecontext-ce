package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.PlatformCredentialLookupDto;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression: the pre-flight gate must ask whether a platform KEY exists, not whether a
 * platform OAuth APPLICATION is registered.
 *
 * <p>Those are different objects and the gate asked the wrong one. A shared OAuth app row
 * holds a client id and secret: it lets a USER connect their own account and can never
 * itself run a call on anybody's behalf. Because the row exists for every OAuth
 * integration the platform supports, the gate concluded there was a fallback pool, stood
 * down, and let the request reach the executor - which then resolved no key in either
 * pool and answered the generic "no key is available, on this account or on this
 * platform. Add your key to this account." Observed in production on a scheduled agent
 * calling Gmail every 30 minutes: the account had no Gmail connection at all, and the
 * only thing the run ever said was the one sentence a person cannot act on.
 *
 * <p>Both directions are asserted. A gate that never stands down would refuse every call
 * the platform genuinely sells, which is the opposite and equally expensive failure.
 */
@DisplayName("catalog execute pre-flight - which pool actually holds a key")
class CatalogExecutePlatformPoolPreflightTest {

    private static final String TOOL_ID = "1e58e9ef-44fc-4378-b4fa-61e80222aac0";
    private static final String TENANT = "121";

    private CatalogExecuteModule module;
    private RestTemplate restTemplate;
    private CredentialClient credentialClient;

    @BeforeEach
    void setUp() throws Exception {
        credentialClient = mock(CredentialClient.class);
        module = new CatalogExecuteModule(new ObjectMapper(), credentialClient);

        restTemplate = mock(RestTemplate.class);
        Field rtField = CatalogExecuteModule.class.getDeclaredField("restTemplate");
        rtField.setAccessible(true);
        rtField.set(module, restTemplate);
        Field portField = CatalogExecuteModule.class.getDeclaredField("serverPort");
        portField.setAccessible(true);
        portField.setInt(module, 8081);

        when(credentialClient.getDefaultCredential(eq(TENANT), anyString()))
                .thenReturn(Optional.empty());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubGmailInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("iconSlug", "gmail");
        info.put("integrationName", "gmail");
        info.put("name", "list_messages");
        info.put("authType", "oauth2");
        info.put("requiredScopes", List.of("https://www.googleapis.com/auth/gmail.readonly"));
        when(restTemplate.exchange(
                contains("/api/catalog/tools/" + TOOL_ID + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(info, HttpStatus.OK));
    }

    private Optional<ToolExecutionResult> execute() {
        return module.execute("execute",
                Map.of("tool_id", TOOL_ID, "params", Map.of("userId", "me")),
                TENANT,
                new ToolExecutionContext(TENANT, Map.of(), Map.of(), Set.of(), null, null, null, null));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(ToolExecutionResult result) {
        return (Map<String, Object>) result.data();
    }

    @Test
    @DisplayName("a shared OAuth APP with no platform key is not a fallback pool, so the caller is told to connect instead of being refused later")
    void oauthAppIsNotAKey() {
        stubGmailInfo();
        // The platform has the shared Google OAuth application registered - this is true
        // for every OAuth integration the platform supports - but no key of its own.
        PlatformCredentialLookupDto sharedApp = new PlatformCredentialLookupDto();
        sharedApp.setFound(true);
        when(credentialClient.findPlatformCredentialByName("gmail")).thenReturn(Optional.of(sharedApp));
        when(credentialClient.getAccessToken("PLATFORM", "gmail")).thenReturn(Optional.empty());

        ToolExecutionResult result = execute().orElseThrow();

        assertThat(payload(result).get("status")).isEqualTo("approval_needed");
        assertThat(payload(result).get("executed")).isEqualTo(false);
        assertThat(payload(result).get("platformKeyAvailable"))
                .as("the app row is not a key: reporting it as one is what sent the caller "
                        + "downstream to a refusal naming a pool that was always empty")
                .isEqualTo(false);
        // And the request never left the building.
        verify(restTemplate, never()).exchange(
                contains("/catalog/v1/tools/"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("a platform key that really exists still stands the gate down, so a call the platform sells is not blocked")
    void aRealPlatformKeyStillStandsTheGateDown() {
        stubGmailInfo();
        when(credentialClient.getAccessToken("PLATFORM", "gmail"))
                .thenReturn(Optional.of("a-real-platform-key"));

        // The gate found a pool that can serve the call and let it go on, which is proved
        // by the request LEAVING. A "no approval payload" assertion alone would also pass
        // if the module had thrown on the way, which is not the same thing at all.
        execute();
        verify(restTemplate).exchange(
                contains("/catalog/v1/tools/"), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(String.class));
    }

    @Test
    @DisplayName("naming an account skips the connect gate, which only ever looks for a DEFAULT one")
    void namingAnAccountSkipsTheGate() {
        // The gate asks auth-service for a credential marked default for the integration.
        // An account holding exactly one credential that nothing ever marked default
        // therefore reads as "not connected", and the caller would be shown a Connect card
        // for a service it had just named an account of. Whether the name resolves is
        // settled downstream, by a refusal that names the ACCOUNT rather than the brand.
        stubGmailInfo();
        when(credentialClient.getAccessToken("PLATFORM", "gmail")).thenReturn(Optional.empty());

        Optional<ToolExecutionResult> result = module.execute("execute",
                Map.of("tool_id", TOOL_ID, "params", Map.of("userId", "me"),
                       "credential_name", "Boulot"),
                TENANT,
                new ToolExecutionContext(TENANT, Map.of(), Map.of(), Set.of(), null, null, null, null));

        // Same anchor as above: the call went out rather than being answered with a card.
        verify(restTemplate).exchange(
                contains("/catalog/v1/tools/"), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(String.class));
        // And the gate did not even look, so no round trip was spent on a question the
        // caller had already answered.
        verify(credentialClient, never()).getDefaultCredential(eq(TENANT), anyString());
    }

    @Test
    @DisplayName("the credential lookups use the integration name, not the brand-shared icon slug")
    void looksUpByIntegrationNotByIcon() {
        // For a SEEDED api the two are equal by construction, so this case is about the
        // other population: an API registered through the tool, where the icon slug is
        // derived from the api slug and the integration name from the api name. The
        // executor resolves a credential by the integration name, so the gate has to ask
        // about the same string or it asks about a credential nothing resolves.
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("iconSlug", "acme-crm");
        info.put("integrationName", "acmecrm");
        info.put("name", "list_contacts");
        info.put("authType", "oauth2");
        when(restTemplate.exchange(
                contains("/api/catalog/tools/" + TOOL_ID + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(info, HttpStatus.OK));
        when(credentialClient.getAccessToken("PLATFORM", "acmecrm")).thenReturn(Optional.empty());

        ToolExecutionResult result = execute().orElseThrow();

        assertThat(payload(result).get("serviceType")).isEqualTo("acmecrm");
        verify(credentialClient).getDefaultCredential(TENANT, "acmecrm");
        verify(credentialClient, never()).getDefaultCredential(TENANT, "acme-crm");
    }
}

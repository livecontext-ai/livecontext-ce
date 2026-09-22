package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.catalog.service.credential.EndpointCredentialCapabilityService;
import com.apimarketplace.catalog.service.credential.IntegrationScopePolicy;
import com.apimarketplace.catalog.service.credential.IntegrationScopePolicyReader;
import com.apimarketplace.credential.client.dto.CredentialIdentityDto;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The contract a caller reads BEFORE it calls has to answer "can I run this", not only
 * "what does this need".
 *
 * <p>{@code requiredScopes} alone never answered it: a caller holding a Gmail credential
 * that was granted send-only reads "this needs gmail.readonly", concludes it is
 * connected, calls, and is refused. The gap between those two facts is the whole reason
 * this block exists.
 */
@DisplayName("response_schema - the credential block answers whether THIS caller can run it")
class CatalogSchemaCapabilityTest {

    private static final String TOOL_ID = "11111111-2222-3333-4444-555555555555";
    private static final String TENANT = "121";
    private static final String READONLY = "https://www.googleapis.com/auth/gmail.readonly";
    private static final String SEND = "https://www.googleapis.com/auth/gmail.send";

    private CatalogSchemaModule module;
    private RestTemplate restTemplate;
    private IntegrationScopePolicyReader policyReader;
    private UserCredentialService userCredentialService;

    @BeforeEach
    void setUp() throws Exception {
        module = new CatalogSchemaModule();
        policyReader = mock(IntegrationScopePolicyReader.class);
        userCredentialService = mock(UserCredentialService.class);
        module.setCredentialCapability(
                new EndpointCredentialCapabilityService(policyReader, userCredentialService));

        restTemplate = mock(RestTemplate.class);
        Field rtField = CatalogSchemaModule.class.getDeclaredField("restTemplate");
        rtField.setAccessible(true);
        rtField.set(module, restTemplate);
        Field portField = CatalogSchemaModule.class.getDeclaredField("serverPort");
        portField.setAccessible(true);
        portField.setInt(module, 8081);

        when(policyReader.forIntegration("gmail")).thenReturn(
                IntegrationScopePolicy.declared(List.of(SEND), List.of(READONLY), false));
        stubSkeletonAndInfo();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubSkeletonAndInfo() {
        Map<String, Object> skeleton = new LinkedHashMap<>();
        skeleton.put("skeleton", Map.of("messages", List.of()));
        skeleton.put("paths", List.of());
        when(restTemplate.exchange(
                contains("/api/v1/structure/tool/" + TOOL_ID + "/skeleton"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(skeleton, HttpStatus.OK));

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("authType", "oauth2");
        info.put("integrationName", "gmail");
        info.put("requiredScopes", List.of(READONLY));
        when(restTemplate.exchange(
                contains("/api/catalog/tools/" + TOOL_ID + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(info, HttpStatus.OK));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> credentialBlock() {
        ToolExecutionResult result = module.execute("response_schema",
                Map.of("tool_id", TOOL_ID), TENANT,
                new ToolExecutionContext(TENANT, Map.of(), Map.of(), Set.of(), null, null, null, null))
                .orElseThrow();
        assertThat(result.success()).isTrue();
        return (Map<String, Object>) ((Map<String, Object>) result.data()).get("credential");
    }

    private static CredentialIdentityDto gmail(String name, boolean isDefault, String... scopes) {
        return new CredentialIdentityDto(1L, name, "gmail", "active", "OAuth2", List.of(scopes), isDefault);
    }

    @Test
    @DisplayName("the requirement half is exactly what it always was")
    void requirementHalfIsUnchanged() {
        when(userCredentialService.tryListIdentities(TENANT)).thenReturn(Optional.of(List.of()));

        Map<String, Object> credential = credentialBlock();
        assertThat(credential.get("type")).isEqualTo("oauth2");
        assertThat(credential.get("requiredScopes")).isEqualTo(List.of(READONLY));
    }

    @Test
    @DisplayName("an account that can run it is named, so the very first call uses it")
    @SuppressWarnings("unchecked")
    void namesTheRunnableAccountUpFront() {
        when(userCredentialService.tryListIdentities(TENANT)).thenReturn(Optional.of(List.of(
                gmail("Perso", true, SEND),
                new CredentialIdentityDto(2L, "Boulot", "gmail", "active", "OAuth2",
                        List.of(READONLY), false))));

        Map<String, Object> credential = credentialBlock();
        assertThat(credential.get("runnableWith")).isEqualTo(List.of("Boulot"));
        assertThat((String) credential.get("remedy")).contains("credential_name=\"Boulot\"");
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) credential.get("accounts");
        assertThat(accounts).hasSize(2);
        assertThat(accounts.get(0).get("missingScopes")).isEqualTo(List.of(READONLY));
    }

    @Test
    @DisplayName("a restricted endpoint says a standard connection cannot grant it, before anyone spends a consent screen")
    void flagsTheRestrictedScope() {
        when(userCredentialService.tryListIdentities(TENANT)).thenReturn(Optional.of(List.of()));

        Map<String, Object> credential = credentialBlock();
        assertThat(credential.get("standardConnectionGrantsThis")).isEqualTo(false);
        assertThat(credential.get("scopesNeedingOwnOAuthClient")).isEqualTo(List.of(READONLY));
    }

    @Test
    @DisplayName("when the default account can run it there is no remedy, so a remedy always means something")
    void silentWhenItWillWork() {
        when(userCredentialService.tryListIdentities(TENANT))
                .thenReturn(Optional.of(List.of(gmail("Perso", true, READONLY))));

        assertThat(credentialBlock()).doesNotContainKey("remedy");
    }

    @Test
    @DisplayName("with no capability bean the contract is exactly the two keys it carried before")
    void failsOpenToTheRequirementOnly() {
        module.setCredentialCapability(null);

        Map<String, Object> credential = credentialBlock();
        assertThat(credential).containsOnlyKeys("type", "requiredScopes");
    }
}

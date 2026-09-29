package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A declared path parameter left unfilled is refused before the call.
 *
 * <p>Bug (prod, 2026-09-28): LinkedIn {@code get_profile} called without {@code personId}
 * went out as {@code https://api.linkedin.com/v2/people/(id:{personId})} and came back as
 * "UPSTREAM_REJECTED ... Illegal character in path at index 39", an error that never named
 * the parameter. The missing value was only logged as a WARN.
 *
 * <p>The trap the first version fell into, pinned by {@link CredentialFilled}: the importer
 * declares the placeholders the CREDENTIAL fills ({@code {token}}, {@code {apiKey}},
 * {@code {project_id}}) as path parameters without a default too, so a check run before
 * {@code replaceUrlTemplateVariables} refuses every one of those ~110 calls.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - unfilled path parameters are refused before the call")
class HttpExecutionServiceUnfilledPathParamTest {

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpExecutionService service;

    private static final String LINKEDIN_PROFILE = "https://api.linkedin.com/v2/people/(id:{personId})";
    private static final String MESSAGE_START = "Path parameter personId has no value";

    @BeforeEach
    void setUp() {
        lenient().when(encryptionService.decrypt(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(jdbcTemplate.queryForList(anyString(), (Object) any())).thenReturn(new ArrayList<>());
        lenient().when(jdbcTemplate.queryForList(anyString(), (Object) any(), (Object) any())).thenReturn(new ArrayList<>());
        lenient().when(userCredentialService.getAccessTokenInfo(anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(userCredentialService.getAccessToken(anyString(), anyString())).thenReturn(Optional.empty());
        service = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, restTemplate, new ErrorPolicyEngine(2, 10_000L));
        CredentialModeContext.clear();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private ApiToolEntity tool(String method, String endpoint, ApiToolParameterEntity... params) {
        ApiToolEntity t = new ApiToolEntity();
        t.setId(UUID.randomUUID());
        t.setMethod(method);
        t.setEndpoint(endpoint);
        t.setToolSlug("probe_tool");
        lenient().when(apiToolParameterRepository.findByApiToolId(t.getId())).thenReturn(Arrays.asList(params));
        return t;
    }

    private ApiToolEntity typed(ApiToolEntity t) {
        t.setExecutionMode("sync");
        t.setExecutionSpec("{\"mode\":\"sync\"}");
        t.setOutputSchema("[]");
        return t;
    }

    private static ApiToolParameterEntity param(String name, String type, String defaultValue) {
        ApiToolParameterEntity p = new ApiToolParameterEntity();
        p.setName(name);
        p.setParameterType(type);
        p.setDefaultValue(defaultValue);
        return p;
    }

    private static ApiEntity api(String baseUrl) {
        ApiEntity api = new ApiEntity();
        api.setBaseUrl(baseUrl);
        return api;
    }

    private ArrayNode params(Map<String, String> values) {
        ArrayNode arr = objectMapper.createArrayNode();
        values.forEach((k, v) -> arr.add(objectMapper.createObjectNode().put(k, v)));
        return arr;
    }

    private void respondOk() {
        lenient().when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("ok", true)));
    }

    /** A multi-field OAuth credential, like LinkedIn's: no single value to fall back on. */
    private void givenLinkedinCredential() {
        lenient().when(userCredentialService.getCredentialDataMap("user1", "linkedin"))
                .thenReturn(Map.of("access_token", "at", "refresh_token", "rt"));
    }

    // ── the three entry points refuse the LinkedIn shape ─────────────────────

    @Nested
    @DisplayName("an agent-owned path parameter left out is refused, on every entry point")
    class Refused {

        @Test
        @DisplayName("REGRESSION 2026-09-28: legacy call, LinkedIn get_profile without personId: refused, nothing sent")
        void legacyCallRefused() {
            ApiToolEntity tool = tool("GET", LINKEDIN_PROFILE, param("personId", "path", null));

            Map<String, Object> result = service.executeHttpCall(api("https://api.linkedin.com"), tool, params(Map.of()));

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(String.valueOf(result.get("error"))).contains(MESSAGE_START).contains("Nothing was sent");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("REGRESSION 2026-09-28: credentialed call (the one agents use): refused after the credential fill")
        void credentialedCallRefused() {
            givenLinkedinCredential();
            ApiToolEntity tool = tool("GET", LINKEDIN_PROFILE, param("personId", "path", null));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api("https://api.linkedin.com"), tool, params(Map.of()), Set.of(), "user1", "linkedin");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(String.valueOf(result.get("error"))).contains(MESSAGE_START);
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("typed call: refused the same way")
        void typedCallRefused() {
            givenLinkedinCredential();
            ApiToolEntity tool = typed(tool("GET", LINKEDIN_PROFILE, param("personId", "path", null)));

            Map<String, Object> result = service.executeHttpCallTyped(
                    api("https://api.linkedin.com"), tool, params(Map.of()), Set.of(), "user1", "linkedin", "tenant-1");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(String.valueOf(result.get("error"))).contains(MESSAGE_START);
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("a provided value is substituted and the call goes out, as before")
        void providedValueGoesOut() {
            givenLinkedinCredential();
            respondOk();
            ApiToolEntity tool = tool("GET", LINKEDIN_PROFILE, param("personId", "path", null));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api("https://api.linkedin.com"), tool, params(Map.of("personId", "abc123")), null, "user1", "linkedin"); // null: allowed names read from the definition

            assertThat(result.get("success")).as(String.valueOf(result.get("error"))).isEqualTo(true);
            verify(restTemplate).exchange(eq(URI.create("https://api.linkedin.com/v2/people/(id:abc123)")),
                    eq(HttpMethod.GET), any(HttpEntity.class), eq(Object.class));
        }
    }

    // ── placeholders the CREDENTIAL fills are never refused ──────────────────

    @Nested
    @DisplayName("a declared path parameter the credential fills still goes out (the ~110 endpoints)")
    class CredentialFilled {

        @Test
        @DisplayName("PubNub /publish/{pub_key}/{sub_key}/...: two hidden declared path params filled from the credential")
        void pubnubKeysFilledFromCredential() {
            when(userCredentialService.getCredentialDataMap("user1", "pubnub"))
                    .thenReturn(Map.of("pub_key", "pub-c-1", "sub_key", "sub-c-2"));
            respondOk();
            ApiToolEntity tool = tool("GET", "/publish/{pub_key}/{sub_key}/0/chan/0",
                    param("pub_key", "path", null), param("sub_key", "path", null));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api("https://ps.pndsn.com"), tool, params(Map.of()), Set.of(), "user1", "pubnub");

            assertThat(result.get("success")).isEqualTo(true);
            verify(restTemplate).exchange(eq(URI.create("https://ps.pndsn.com/publish/pub-c-1/sub-c-2/0/chan/0")),
                    eq(HttpMethod.GET), any(HttpEntity.class), eq(Object.class));
        }

        @Test
        @DisplayName("a credential that lacks the field is refused with the hint that the user must add it to the credential")
        void credentialMissingTheFieldPointsAtTheCredential() {
            when(userCredentialService.getCredentialDataMap("user1", "sanity"))
                    .thenReturn(Map.of("token", "t", "dataset", "production"));
            ApiToolEntity tool = tool("GET", "/v2021-06-07/projects/{project_id}", param("project_id", "path", null));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api("https://api.sanity.io"), tool, params(Map.of()), Set.of(), "user1", "sanity");

            assertThat(result.get("success")).isEqualTo(false);
            assertThat(String.valueOf(result.get("error")))
                    .contains("Path parameter project_id has no value")
                    .contains("add it to that credential");
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Ankr-style hidden {apiKey} on the typed path: filled from the credential, not refused")
        void hiddenApiKeyOnTypedPath() {
            when(userCredentialService.getCredentialDataMap("user1", "ankr")).thenReturn(Map.of("apiKey", "K1"));
            respondOk();
            ApiToolEntity tool = typed(tool("POST", "/{apiKey}", param("apiKey", "path", null)));

            Map<String, Object> result = service.executeHttpCallTyped(
                    api("https://rpc.ankr.com/eth"), tool, params(Map.of()), Set.of(), "user1", "ankr", "tenant-1");

            assertThat(result.get("success")).isEqualTo(true);
            verify(restTemplate).exchange(eq(URI.create("https://rpc.ankr.com/eth/K1")),
                    eq(HttpMethod.POST), any(HttpEntity.class), eq(Object.class));
        }

        @Test
        @DisplayName("Firebase-style {project_id} among several credential fields: filled by name, not refused")
        void projectIdFilledByName() {
            when(userCredentialService.getCredentialDataMap("user1", "firebase"))
                    .thenReturn(Map.of("project_id", "p-9", "api_key", "k"));
            respondOk();
            ApiToolEntity tool = tool("GET", "/v1/projects/{project_id}/databases", param("project_id", "path", null));

            Map<String, Object> result = service.executeHttpCallWithCredentials(
                    api("https://firestore.googleapis.com"), tool, params(Map.of()), Set.of(), "user1", "firebase");

            assertThat(result.get("success")).isEqualTo(true);
            verify(restTemplate).exchange(eq(URI.create("https://firestore.googleapis.com/v1/projects/p-9/databases")),
                    eq(HttpMethod.GET), any(HttpEntity.class), eq(Object.class));
        }
    }

    // ── the check itself, on a URL after every substitution ──────────────────

    @Nested
    @DisplayName("requireFilledPathParameters on a final URL")
    class Check {

        @Test
        @DisplayName("every missing parameter is named, not only the first")
        void allMissingAreNamed() {
            String endpoint = "https://api.example.com/orgs/{org}/repos/{repo}";
            ApiToolEntity tool = tool("GET", endpoint, param("org", "path", null), param("repo", "path", ""));

            assertThatThrownBy(() -> service.requireFilledPathParameters(endpoint, tool))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Path parameters org, repo have no value");
        }

        @Test
        @DisplayName("a path parameter with a default is left for the default to fill")
        void parameterWithDefaultIsNotRefused() {
            String endpoint = "https://api.twilio.com/2010-04-01/Accounts/{AccountSid}/Messages.json";
            ApiToolEntity tool = tool("POST", endpoint, param("AccountSid", "path", "{{username}}"));

            service.requireFilledPathParameters(endpoint, tool);
        }

        @Test
        @DisplayName("a base-URL variable left unfilled is not refused when a query parameter shares its name")
        void baseUrlVariableIsNotRefused() {
            String endpoint = "/api/v2/tickets.json";
            ApiToolEntity tool = tool("GET", endpoint, param("domain", "query", null));

            service.requireFilledPathParameters("https://{domain}.zendesk.com" + endpoint, tool);
        }

        @Test
        @DisplayName("a double-brace credential template is not a missing parameter")
        void doubleBraceTemplateIsNotRefused() {
            String endpoint = "https://api.example.com/accounts/{{account_id}}/items";
            ApiToolEntity tool = tool("GET", endpoint, param("account_id", "path", null));

            service.requireFilledPathParameters(endpoint, tool);
        }

        @Test
        @DisplayName("a brace sequence that names no declared parameter is left alone")
        void undeclaredBraceIsLeftAlone() {
            String endpoint = "https://api.example.com/search/{whatever}";
            ApiToolEntity tool = tool("GET", endpoint);

            service.requireFilledPathParameters(endpoint, tool);
        }

        @Test
        @DisplayName("if the parameter metadata cannot be read, nothing is refused (fails open)")
        void metadataFailureFailsOpen() {
            ApiToolEntity tool = tool("GET", LINKEDIN_PROFILE);
            when(apiToolParameterRepository.findByApiToolId(any())).thenThrow(new RuntimeException("db down"));

            service.requireFilledPathParameters(LINKEDIN_PROFILE, tool);
        }

        @Test
        @DisplayName("a URL with no brace at all never reads the metadata")
        void noBraceNoLookup() {
            ApiToolEntity tool = tool("GET", "https://api.example.com/me");

            service.requireFilledPathParameters("https://api.example.com/me", tool);

            verifyNoInteractions(apiToolParameterRepository);
        }
    }
}

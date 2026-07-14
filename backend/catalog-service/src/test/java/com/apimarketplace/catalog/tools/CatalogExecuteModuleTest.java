package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the credential-requirement contract on the {@code catalog(action='execute')}
 * pre-flight: when the user has no default credential for the tool's service, the
 * {@code approval_needed} payload must tell the agent the credential KIND (and OAuth
 * scopes), not just the brand - so it can explain to the user what request_credential
 * will ask them to connect.
 */
class CatalogExecuteModuleTest {

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
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubInfo(String toolId, Map<String, Object> infoBody) {
        when(restTemplate.exchange(
            contains("/api/catalog/tools/" + toolId + "/info"),
            eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenReturn(new ResponseEntity<>(infoBody, HttpStatus.OK));
    }

    @Nested
    @DisplayName("execute pre-flight - approval_needed credential block")
    class ApprovalNeeded {

        @Test
        @DisplayName("api_key tool with no connected credential surfaces credential.type=api_key in payload AND metadata")
        @SuppressWarnings("unchecked")
        void surfacesApiKeyType() {
            String toolId = "375ab18c-4d15-4274-8054-3830910296f2";
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("iconSlug", "cloudsun");
            info.put("name", "getCurrentWeather");
            info.put("description", "Get current weather");
            info.put("authType", "apiKey"); // DB drift value → must normalize
            stubInfo(toolId, info);
            // No default credential for the service → pre-flight returns approval_needed.
            when(credentialClient.getDefaultCredential(eq("user-1"), eq("cloudsun")))
                .thenReturn(Optional.empty());

            ToolExecutionResult result = module.execute(
                "execute",
                Map.of("tool_id", toolId, "params", Map.of("q", "Paris")),
                "user-1",
                ToolExecutionContext.of("user-1")
            ).orElseThrow();

            assertThat(result.success()).isTrue();
            Map<String, Object> payload = (Map<String, Object>) result.data();
            assertThat(payload.get("status")).isEqualTo("approval_needed");

            Map<String, Object> cred = (Map<String, Object>) payload.get("credential");
            assertThat(cred).isNotNull();
            assertThat(cred.get("type")).isEqualTo("api_key");
            assertThat(cred).doesNotContainKey("requiredScopes");

            // The same block must also ride on metadata (frontend + bridge read it there).
            Map<String, Object> meta = result.metadata();
            assertThat(meta.get("credential")).isEqualTo(cred);
        }

        @Test
        @DisplayName("oauth2 tool surfaces credential.type=oauth2 + requiredScopes so the agent can name the consent scopes")
        @SuppressWarnings("unchecked")
        void surfacesOauthScopes() {
            String toolId = "21212121-4343-6565-8787-090909090909";
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("iconSlug", "gmail");
            info.put("name", "sendEmail");
            info.put("description", "Send a Gmail message");
            info.put("authType", "oauth2");
            info.put("requiredScopes", List.of("https://www.googleapis.com/auth/gmail.send"));
            stubInfo(toolId, info);
            when(credentialClient.getDefaultCredential(eq("user-1"), eq("gmail")))
                .thenReturn(Optional.empty());

            ToolExecutionResult result = module.execute(
                "execute",
                Map.of("tool_id", toolId, "params", Map.of("to", "x@y.z")),
                "user-1",
                ToolExecutionContext.of("user-1")
            ).orElseThrow();

            Map<String, Object> payload = (Map<String, Object>) result.data();
            Map<String, Object> cred = (Map<String, Object>) payload.get("credential");
            assertThat(cred.get("type")).isEqualTo("oauth2");
            assertThat(cred.get("requiredScopes"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("https://www.googleapis.com/auth/gmail.send");
        }
    }

    @Nested
    @DisplayName("execute pre-flight - public (keyless) tools skip the gate")
    class PublicTool {

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void stubExecuteOk(String toolId) {
            when(restTemplate.exchange(
                contains("/catalog/v1/tools/" + toolId + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(
                    "{\"success\":true,\"data\":{\"ok\":1}}", HttpStatus.OK));
        }

        @Test
        @DisplayName("authType 'none' runs without a connection - no approval_needed, no credential lookup")
        @SuppressWarnings("unchecked")
        void noneAuthTypeSkipsGate() {
            String toolId = "11111111-2222-3333-4444-555555555555";
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("iconSlug", "polymarket");
            info.put("name", "getMarkets");
            info.put("description", "Public market data");
            info.put("authType", "none"); // keyless / public
            stubInfo(toolId, info);
            stubExecuteOk(toolId);

            ToolExecutionResult result = module.execute(
                "execute",
                Map.of("tool_id", toolId, "params", Map.of()),
                "user-1",
                ToolExecutionContext.of("user-1")
            ).orElseThrow();

            assertThat(result.success()).isTrue();
            Map<String, Object> payload = (Map<String, Object>) result.data();
            // NOT gated: the tool actually executed instead of returning a credential prompt.
            assertThat(payload.get("status")).isNotEqualTo("approval_needed");
            // The execution endpoint was reached (the gate did not short-circuit).
            verify(restTemplate).exchange(
                contains("/catalog/v1/tools/" + toolId + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
            // A public tool must cost ZERO credential round-trips.
            verify(credentialClient, never()).getDefaultCredential(any(), any());
        }

        @Test
        @DisplayName("absent authType is treated as keyless and skips the gate")
        @SuppressWarnings("unchecked")
        void absentAuthTypeSkipsGate() {
            String toolId = "66666666-7777-8888-9999-000000000000";
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("iconSlug", "somepublicapi");
            info.put("name", "getData");
            info.put("description", "Public data, no auth declared");
            // no authType key at all
            stubInfo(toolId, info);
            stubExecuteOk(toolId);

            ToolExecutionResult result = module.execute(
                "execute",
                Map.of("tool_id", toolId, "params", Map.of()),
                "user-1",
                ToolExecutionContext.of("user-1")
            ).orElseThrow();

            assertThat(result.success()).isTrue();
            Map<String, Object> payload = (Map<String, Object>) result.data();
            assertThat(payload.get("status")).isNotEqualTo("approval_needed");
            verify(credentialClient, never()).getDefaultCredential(any(), any());
        }
    }

    @Nested
    @DisplayName("execute - tool inputs supplied outside the `params` wrapper still reach catalog")
    class ParamShapeForwarding {

        private static final String TOOL_ID = "36885d10-5978-4c93-a1fd-69b6f7cc79a5"; // facebook list_page_posts

        /** Facebook list_page_posts is keyless-style here (authType none) so the pre-flight gate never
         *  short-circuits and execution actually POSTs to /catalog/v1/tools/{id}/execute. */
        @SuppressWarnings({"rawtypes", "unchecked"})
        private HttpEntity captureExecuteBody(Map<String, Object> params) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("iconSlug", "facebook");
            info.put("name", "list_page_posts");
            info.put("authType", "none");
            stubInfo(TOOL_ID, info);
            when(restTemplate.exchange(
                contains("/catalog/v1/tools/" + TOOL_ID + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"data\":{}}", HttpStatus.OK));

            module.execute("execute", params, "user-1", ToolExecutionContext.of("user-1")).orElseThrow();

            org.mockito.ArgumentCaptor<HttpEntity> captor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                contains("/catalog/v1/tools/" + TOOL_ID + "/execute"),
                eq(HttpMethod.POST), captor.capture(), eq(String.class));
            return captor.getValue();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> outboundParameters(HttpEntity<?> entity) {
            Map<String, Object> body = (Map<String, Object>) entity.getBody();
            return (Map<String, Object>) body.get("parameters");
        }

        @Test
        @DisplayName("params nested under `params` reach catalog (baseline - already worked)")
        void nestedParamsForwarded() {
            HttpEntity<?> entity = captureExecuteBody(new LinkedHashMap<>(Map.of(
                "tool_id", TOOL_ID, "params", new LinkedHashMap<>(Map.of("page_id", "123")))));
            assertThat(outboundParameters(entity)).containsEntry("page_id", "123");
        }

        @Test
        @DisplayName("REGRESSION: a location:path param nested under `inputs` (the reported Facebook page_id bug) reaches catalog")
        void inputsAliasForwarded() {
            // Pre-fix: `inputs` was not a recognized alias, so `parameters` went out empty ({}),
            // catalog left "/{page_id}/posts" literal and URI parsing failed with
            // "Illegal character in path". Post-fix the page_id survives so catalog substitutes
            // it into ".../123/posts".
            HttpEntity<?> entity = captureExecuteBody(new LinkedHashMap<>(Map.of(
                "tool_id", TOOL_ID, "inputs", new LinkedHashMap<>(Map.of("page_id", "123")))));
            assertThat(outboundParameters(entity)).containsEntry("page_id", "123");
        }

        @Test
        @DisplayName("REGRESSION: a location:path param flattened to the top level reaches catalog")
        void topLevelParamForwarded() {
            // Chat models frequently pass each tool parameter as a top-level sibling of
            // action/tool_id instead of nesting under `params`. Pre-fix these were dropped.
            HttpEntity<?> entity = captureExecuteBody(new LinkedHashMap<>(Map.of(
                "tool_id", TOOL_ID, "page_id", "123")));
            assertThat(outboundParameters(entity)).containsEntry("page_id", "123");
        }

        @Test
        @DisplayName("explicit `params` object wins over a same-named top-level stray")
        void nestedWinsOverStray() {
            HttpEntity<?> entity = captureExecuteBody(new LinkedHashMap<>(Map.of(
                "tool_id", TOOL_ID,
                "params", new LinkedHashMap<>(Map.of("page_id", "nested")),
                "page_id", "stray")));
            assertThat(outboundParameters(entity)).containsEntry("page_id", "nested");
        }

        @Test
        @DisplayName("control/shaping keys are never forwarded as tool input params")
        void reservedKeysNotForwarded() {
            HttpEntity<?> entity = captureExecuteBody(new LinkedHashMap<>(Map.of(
                "tool_id", TOOL_ID, "page_id", "123", "max_items", 5, "expand", List.of("data"))));
            Map<String, Object> outbound = outboundParameters(entity);
            assertThat(outbound).containsEntry("page_id", "123");
            assertThat(outbound).doesNotContainKeys("tool_id", "max_items", "expand");
        }
    }

    @Test
    @DisplayName("agent catalog execute forwards organization headers to the internal catalog execution request")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void forwardsOrgHeadersToInternalExecuteRequest() {
        String toolId = "375ab18c-4d15-4274-8054-3830910296f2";
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("iconSlug", "cloudsun");
        info.put("name", "getCurrentWeather");
        info.put("description", "Get current weather");
        info.put("authType", "apiKey");
        stubInfo(toolId, info);

        CredentialSummaryDto credential = new CredentialSummaryDto();
        credential.setName("Org default");
        credential.setIntegration("cloudsun");
        credential.setDefault(true);
        when(credentialClient.getDefaultCredential(eq("user-1"), eq("cloudsun")))
            .thenReturn(Optional.of(credential));

        when(restTemplate.exchange(
            contains("/catalog/v1/tools/" + toolId + "/execute"),
            eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(
                "{\"success\":true,\"headers\":{\"x-api-key\":\"redacted\"},\"metadata\":{\"credentialSource\":\"user\"}}",
                HttpStatus.OK));

        ToolExecutionContext context = new ToolExecutionContext(
            "user-1",
            Map.of("__streamId__", "stream-1"),
            Map.of(),
            Set.of(),
            null,
            null,
            "org-1",
            "MEMBER"
        );

        ToolExecutionResult result = module.execute(
            "execute",
            Map.of("tool_id", toolId, "params", Map.of("q", "Paris")),
            "user-1",
            context
        ).orElseThrow();

        assertThat(result.success()).isTrue();

        org.mockito.ArgumentCaptor<HttpEntity> entityCaptor = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
            contains("/catalog/v1/tools/" + toolId + "/execute"),
            eq(HttpMethod.POST),
            entityCaptor.capture(),
            eq(String.class)
        );

        HttpEntity captured = entityCaptor.getValue();
        assertThat(captured.getHeaders().getFirst("X-User-ID")).isEqualTo("user-1");
        assertThat(captured.getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-1");
        assertThat(captured.getHeaders().getFirst("X-Organization-Role")).isEqualTo("MEMBER");
    }
}

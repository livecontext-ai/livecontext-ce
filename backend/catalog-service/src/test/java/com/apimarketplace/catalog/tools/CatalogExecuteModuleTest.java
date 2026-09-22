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
            // success()=true here means "the request was surfaced", never "the call ran", and
            // this flag is the only thing that says so in a way the agent is told to read. The
            // two authorization refusals carry it, the prompts and every tool help tell the
            // agent to branch on it, so a payload that omits it reads as an ordinary success
            // and gets narrated as an API result for a call that never left the building.
            assertThat(payload.get("executed"))
                .as("the agent is taught to settle 'did this run?' on executed, everywhere")
                .isEqualTo(false);

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

    /**
     * A generation is a catalog call, so it meets the same pre-flight gates, and
     * both of them recognise a tool by the id the platform hands out.
     *
     * <p>These exist because the execute route accepts TWO identifiers for the
     * same endpoint - its id and its {@code api/tool} slug - while the gates in
     * front of it accept only the first. Sending the slug therefore executes
     * perfectly and disables both gates on the way, which is a failure no
     * response body shows. The restriction half is the one with teeth: an id the
     * list cannot contain is refused, so a restricted agent loses generation
     * entirely, and the refusal sends it to a catalog search that will never
     * return the id it is being asked for.
     */
    @Nested
    @DisplayName("generation delegation - the restriction gate reads the id the platform hands out")
    class GenerationRestrictionGate {

        private static final String ENDPOINT_ID = "375ab18c-4d15-4274-8054-3830910296f2";

        private ToolExecutionContext restrictedTo(String... allowedToolIds) {
            return new ToolExecutionContext(
                "user-1",
                Map.of("allowedToolIds", List.of(allowedToolIds)),
                Map.of(), Set.of(), null, null, null, null);
        }

        private ToolExecutionResult generate(String toolId, ToolExecutionContext context) {
            return module.executeGeneration(
                Map.of("tool_id", toolId, "params", Map.of("prompt", "a cat")),
                context,
                new CatalogExecuteModule.GenerationBilling(
                    "vid-fast", new java.math.BigDecimal("10"), "second"))
                .orElseThrow();
        }

        @Test
        @DisplayName("an agent allowed this endpoint may generate with it")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void allowedEndpointRuns() {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("iconSlug", "provider");
            info.put("name", "generateVideo");
            info.put("authType", "none");
            stubInfo(ENDPOINT_ID, info);
            when(restTemplate.exchange(
                contains("/catalog/v1/tools/" + ENDPOINT_ID + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"result\":{}}", HttpStatus.OK));

            ToolExecutionResult result = generate(ENDPOINT_ID, restrictedTo(ENDPOINT_ID));

            assertThat(result.success())
                .as("the endpoint is in the agent's allowed list, so the generation must run")
                .isTrue();
            verify(restTemplate).exchange(
                contains("/catalog/v1/tools/" + ENDPOINT_ID + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        }

        @Test
        @DisplayName("an agent NOT allowed this endpoint is refused, and no call is made")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void endpointOutsideTheListIsRefused() {
            ToolExecutionResult result = generate(
                ENDPOINT_ID, restrictedTo("11111111-1111-1111-1111-111111111111"));

            assertThat(result.success()).isFalse();
            verify(restTemplate, never()).exchange(
                contains("/execute"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        }

        @Test
        @DisplayName("a caller buying on the PLATFORM key is not stopped for having no key of its own")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void thePlatformPoolIsNotGatedOnTheCallersOwnKey() {
            // THE BLOCKER THIS PINS. The credential pre-flight reads the
            // caller's OWN credentials and never the platform's, and it returns
            // a result, so the tool is never dispatched. Applied to a caller who
            // asked for the platform key, that refuses the entire resale
            // product: the generate node and the app modal both ask for
            // `platform` by default, so no account without its own provider key
            // could ever buy a generation.
            //
            // Drives the REAL module, not a fabricated approval payload: the
            // defect was invisible to every test that mocked this boundary, and
            // one of them had pinned the wrong refusal as correct.
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("iconSlug", "elevenlabs");
            info.put("name", "textToSpeech");
            info.put("authType", "apiKey");
            stubInfo(ENDPOINT_ID, info);
            // No key of the caller's own, anywhere.
            when(credentialClient.getDefaultCredential(eq("user-1"), eq("elevenlabs")))
                .thenReturn(Optional.empty());
            when(restTemplate.exchange(
                contains("/catalog/v1/tools/" + ENDPOINT_ID + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"result\":{}}", HttpStatus.OK));

            ToolExecutionResult result = module.executeGeneration(
                Map.of("tool_id", ENDPOINT_ID,
                       "params", Map.of("prompt", "a cat"),
                       "credential_source", "platform"),
                new ToolExecutionContext("user-1", Map.of(), Map.of(), Set.of(), null, null, null, null),
                new CatalogExecuteModule.GenerationBilling(
                    "eleven-v3", new java.math.BigDecimal("12"), "character"))
                .orElseThrow();

            assertThat(result.success())
                .as("the platform pool is what this call asked for, so an empty own-key pool is irrelevant")
                .isTrue();
            verify(restTemplate).exchange(
                contains("/catalog/v1/tools/" + ENDPOINT_ID + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        }

        @Test
        @DisplayName("a caller who pinned its OWN key and has none is still warned, before anything runs")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void pinningYourOwnKeyWithoutOneStillWarns() {
            // The negative half. Skipping the gate for everyone would score as a
            // pass on the test above while deleting the warning this gate
            // exists to give.
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("iconSlug", "elevenlabs");
            info.put("name", "textToSpeech");
            info.put("authType", "apiKey");
            stubInfo(ENDPOINT_ID, info);
            when(credentialClient.getDefaultCredential(eq("user-1"), eq("elevenlabs")))
                .thenReturn(Optional.empty());

            ToolExecutionResult result = module.executeGeneration(
                Map.of("tool_id", ENDPOINT_ID,
                       "params", Map.of("prompt", "a cat"),
                       "credential_source", "user"),
                new ToolExecutionContext("user-1", Map.of(), Map.of(), Set.of(), null, null, null, null),
                new CatalogExecuteModule.GenerationBilling(
                    "eleven-v3", new java.math.BigDecimal("12"), "character"))
                .orElseThrow();

            assertThat(result.data()).isInstanceOf(Map.class);
            assertThat(((Map<String, Object>) result.data()).get("status")).isEqualTo("approval_needed");
            verify(restTemplate, never()).exchange(
                contains("/execute"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        }

        @Test
        @DisplayName("saying nothing about the pool falls back to the platform key rather than refusing")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void anUnpinnedCallFallsBackToThePlatformKey() {
            // Absent means "own key first, platform second". A missing own key
            // is therefore the second branch, not a dead end, and warning here
            // would refuse a call the platform can serve.
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("iconSlug", "elevenlabs");
            info.put("name", "textToSpeech");
            info.put("authType", "apiKey");
            stubInfo(ENDPOINT_ID, info);
            when(credentialClient.getDefaultCredential(eq("user-1"), eq("elevenlabs")))
                .thenReturn(Optional.empty());
            com.apimarketplace.credential.client.dto.PlatformCredentialLookupDto platform =
                new com.apimarketplace.credential.client.dto.PlatformCredentialLookupDto();
            platform.setFound(true);
            platform.setIntegrationName("elevenlabs");
            when(credentialClient.findPlatformCredentialByName("elevenlabs"))
                .thenReturn(Optional.of(platform));
            when(restTemplate.exchange(
                contains("/catalog/v1/tools/" + ENDPOINT_ID + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"result\":{}}", HttpStatus.OK));

            ToolExecutionResult result = module.executeGeneration(
                Map.of("tool_id", ENDPOINT_ID, "params", Map.of("prompt", "a cat")),
                new ToolExecutionContext("user-1", Map.of(), Map.of(), Set.of(), null, null, null, null),
                new CatalogExecuteModule.GenerationBilling(
                    "eleven-v3", new java.math.BigDecimal("12"), "character"))
                .orElseThrow();

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("the warning REPORTS whether a platform key exists, which is what picks the remedy")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void theWarningReportsWhichPoolsWereEmpty() {
            // THE JOINT. The consumer of this flag is tested against a value the
            // test itself supplies, so inverting what the PRODUCER writes left
            // 81 tests green: a caller with no platform key would be told to
            // pass credential_source='platform', the wrong-remedy loop that was
            // supposedly removed. Both directions are asserted, because a
            // constant satisfies either one alone.
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("iconSlug", "elevenlabs");
            info.put("name", "textToSpeech");
            info.put("authType", "apiKey");
            stubInfo(ENDPOINT_ID, info);
            when(credentialClient.getDefaultCredential(eq("user-1"), eq("elevenlabs")))
                .thenReturn(Optional.empty());

            // The PLATFORM tenant is where the executor resolves a platform key, so that is
            // what the flag has to be read from. Asking whether a platform OAuth
            // APPLICATION row exists answers a different question: that row lets a USER
            // connect their own account and can never run a call itself, so on every OAuth
            // integration it reported a fallback pool that cannot serve one call.
            when(credentialClient.getAccessToken("PLATFORM", "elevenlabs"))
                .thenReturn(Optional.of("platform-key"));

            ToolExecutionResult withPlatformKey = module.executeGeneration(
                Map.of("tool_id", ENDPOINT_ID, "params", Map.of("prompt", "a cat"),
                       "credential_source", "user"),
                new ToolExecutionContext("user-1", Map.of(), Map.of(), Set.of(), null, null, null, null),
                new CatalogExecuteModule.GenerationBilling(
                    "eleven-v3", new java.math.BigDecimal("12"), "character"))
                .orElseThrow();

            assertThat(((Map<String, Object>) withPlatformKey.data()).get("platformKeyAvailable"))
                .as("the platform sells this one, so switching pool is a remedy that works")
                .isEqualTo(true);

            when(credentialClient.getAccessToken("PLATFORM", "elevenlabs"))
                .thenReturn(Optional.empty());

            ToolExecutionResult withoutPlatformKey = module.executeGeneration(
                Map.of("tool_id", ENDPOINT_ID, "params", Map.of("prompt", "a cat"),
                       "credential_source", "user"),
                new ToolExecutionContext("user-1", Map.of(), Map.of(), Set.of(), null, null, null, null),
                new CatalogExecuteModule.GenerationBilling(
                    "eleven-v3", new java.math.BigDecimal("12"), "character"))
                .orElseThrow();

            assertThat(((Map<String, Object>) withoutPlatformKey.data()).get("platformKeyAvailable"))
                .as("neither pool has a key, and offering the empty one is advice that cannot work")
                .isEqualTo(false);
        }

        @Test
        @DisplayName("with no pool pinned and no platform key either, the warning is still given")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void anUnpinnedCallWithNoPoolAtAllStillWarns() {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("iconSlug", "elevenlabs");
            info.put("name", "textToSpeech");
            info.put("authType", "apiKey");
            stubInfo(ENDPOINT_ID, info);
            when(credentialClient.getDefaultCredential(eq("user-1"), eq("elevenlabs")))
                .thenReturn(Optional.empty());
            when(credentialClient.findPlatformCredentialByName("elevenlabs"))
                .thenReturn(Optional.empty());

            ToolExecutionResult result = module.executeGeneration(
                Map.of("tool_id", ENDPOINT_ID, "params", Map.of("prompt", "a cat")),
                new ToolExecutionContext("user-1", Map.of(), Map.of(), Set.of(), null, null, null, null),
                new CatalogExecuteModule.GenerationBilling(
                    "eleven-v3", new java.math.BigDecimal("12"), "character"))
                .orElseThrow();

            assertThat(((Map<String, Object>) result.data()).get("status")).isEqualTo("approval_needed");
        }

        @Test
        @DisplayName("the pricing context travels on the WIRE, or nothing downstream can charge correctly")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void thePricingContextReachesTheExecuteRequest() {
            // THE SEAM NOTHING WATCHED. The three X-Lc-Generation-* headers are
            // the only channel by which the model, the size and the unit reach
            // pricing. Deleting any of them silently disables a guard three
            // classes away: the dimension check has six well-written tests, and
            // every one of them builds its scope directly, so all six stay green
            // while the unit never leaves this method.
            //
            // credential_source is here for the same reason: it reaching the
            // body is what fixed "a workflow that asked for its OWN key was
            // billed the platform price instead", and the existing test for it
            // asserts on a helper that never removes the key, so it cannot fail.
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("iconSlug", "provider");
            info.put("name", "generateVideo");
            info.put("authType", "none");
            stubInfo(ENDPOINT_ID, info);
            when(restTemplate.exchange(
                contains("/catalog/v1/tools/" + ENDPOINT_ID + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"result\":{}}", HttpStatus.OK));

            module.executeGeneration(
                Map.of("tool_id", ENDPOINT_ID,
                       "params", Map.of("prompt", "a cat"),
                       "credential_source", "user"),
                new ToolExecutionContext("user-1", Map.of(), Map.of(), Set.of(), null, null, null, null),
                new CatalogExecuteModule.GenerationBilling(
                    "seedance-2.0", new java.math.BigDecimal("10"), "second"));

            org.mockito.ArgumentCaptor<HttpEntity> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                contains("/catalog/v1/tools/" + ENDPOINT_ID + "/execute"),
                eq(HttpMethod.POST), captor.capture(), eq(String.class));
            HttpEntity sent = captor.getValue();

            assertThat(sent.getHeaders().getFirst("X-Lc-Generation-Model"))
                .as("which price row applies")
                .isEqualTo("seedance-2.0");
            assertThat(sent.getHeaders().getFirst("X-Lc-Generation-Quantity"))
                .as("how big the call is")
                .isEqualTo("10");
            assertThat(sent.getHeaders().getFirst("X-Lc-Generation-Unit"))
                .as("what that number counts, without which it cannot be checked against the rate")
                .isEqualTo("second");
            assertThat(sent.getBody().toString())
                .as("who pays, which decides whether the platform bills at all")
                .contains("user");
            assertThat(sent.getHeaders().getFirst("X-Lc-Generation-Multiplier"))
                .as("a call at the published rate says nothing about a factor, so an ordinary "
                    + "generation's request is exactly the one it was before factors existed")
                .isNull();
        }

        @Test
        @DisplayName("a call whose choices move it off the published rate carries the factor")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void thePriceFactorTravelsOnItsOwnHeader() {
            // It multiplies the amount charged, and it travels on a SEALED header: the gateway and
            // the monolith both strip it inbound, so only the platform can set it. Lost here, a
            // 1080p render is billed at the 720p rate on every call with nothing to notice.
            when(restTemplate.exchange(
                contains("/catalog/v1/tools/" + ENDPOINT_ID + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"result\":{}}", HttpStatus.OK));

            module.executeGeneration(
                Map.of("tool_id", ENDPOINT_ID,
                       "params", Map.of("prompt", "a cat"),
                       "credential_source", "user"),
                new ToolExecutionContext("user-1", Map.of(), Map.of(), Set.of(), null, null, null, null),
                new CatalogExecuteModule.GenerationBilling(
                    "seedance-2.0", new java.math.BigDecimal("10"), "second",
                    new java.math.BigDecimal("2.4")));

            org.mockito.ArgumentCaptor<HttpEntity> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                contains("/catalog/v1/tools/" + ENDPOINT_ID + "/execute"),
                eq(HttpMethod.POST), captor.capture(), eq(String.class));

            assertThat(captor.getValue().getHeaders().getFirst("X-Lc-Generation-Multiplier"))
                .as("what the call's own choices do to the rate")
                .isEqualTo("2.4");
        }

        @Test
        @DisplayName("a factor of exactly 1 is omitted, because it says the same thing as silence")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void aFactorOfOneIsNotSent() {
            when(restTemplate.exchange(
                contains("/catalog/v1/tools/" + ENDPOINT_ID + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"success\":true,\"result\":{}}", HttpStatus.OK));

            module.executeGeneration(
                Map.of("tool_id", ENDPOINT_ID, "params", Map.of("prompt", "a cat")),
                new ToolExecutionContext("user-1", Map.of(), Map.of(), Set.of(), null, null, null, null),
                new CatalogExecuteModule.GenerationBilling(
                    "seedance-2.0", new java.math.BigDecimal("10"), "second",
                    java.math.BigDecimal.ONE));

            org.mockito.ArgumentCaptor<HttpEntity> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                contains("/catalog/v1/tools/" + ENDPOINT_ID + "/execute"),
                eq(HttpMethod.POST), captor.capture(), eq(String.class));

            assertThat(captor.getValue().getHeaders().getFirst("X-Lc-Generation-Multiplier")).isNull();
        }

        @Test
        @DisplayName("the api/tool slug is NOT an identifier this gate can honour, even for an allowed endpoint")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void theSlugCannotBeMatchedAgainstTheAllowedList() {
            // The regression this pins is upstream, in what the generation
            // surface delegates: this gate is right to refuse an id it was never
            // given, so the surface has to send the one it was. Were the slug
            // ever delegated again, an agent that IS allowed this endpoint would
            // lose generation with no trace but a log line.
            ToolExecutionResult result = generate("provider/vid-fast", restrictedTo(ENDPOINT_ID));

            assertThat(result.success()).isFalse();
            verify(restTemplate, never()).exchange(
                contains("/execute"), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        }
    }
}

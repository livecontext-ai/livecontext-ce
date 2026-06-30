package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the {@code catalog(action='response_schema')} contract that ALSO returns the
 * tool's {@code inputSchema} (param contract: types, defaults, allowedValues).
 *
 * <p>Without {@code inputSchema}, the LLM agent has no way to know which values are
 * admissible before calling {@code catalog(action='execute')} and ends up trial-and-erroring
 * on invalid enums. This is the LLM-facing payoff for the Phase A/A5 enrichment work.
 */
class CatalogSchemaModuleTest {

    private CatalogSchemaModule module;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() throws Exception {
        module = new CatalogSchemaModule();
        restTemplate = mock(RestTemplate.class);
        Field rtField = CatalogSchemaModule.class.getDeclaredField("restTemplate");
        rtField.setAccessible(true);
        rtField.set(module, restTemplate);
        Field portField = CatalogSchemaModule.class.getDeclaredField("serverPort");
        portField.setAccessible(true);
        portField.setInt(module, 8081);
    }

    @Nested
    @DisplayName("canHandle")
    class CanHandle {

        @Test
        void shouldHandleResponseSchema() {
            assertThat(module.canHandle("response_schema")).isTrue();
        }

        @Test
        void shouldNotHandleOtherActions() {
            assertThat(module.canHandle("search")).isFalse();
            assertThat(module.canHandle("execute")).isFalse();
            assertThat(module.canHandle("help")).isFalse();
        }
    }

    @Nested
    @DisplayName("response_schema execution")
    class ResponseSchemaExecution {

        @Test
        @DisplayName("merges inputSchema into the response so the agent learns admissible values BEFORE execute()")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void mergesInputSchemaWithDefaultAndAllowedValues() {
            String toolId = "11111111-2222-3333-4444-555555555555";

            Map<String, Object> skeletonBody = new LinkedHashMap<>();
            skeletonBody.put("skeleton", Map.of("id", "msg-abc", "subject", "Hello"));
            skeletonBody.put("paths", List.of("id -> string", "subject -> string"));
            skeletonBody.put("toolName", "Send Gmail");

            Map<String, Object> infoBody = new LinkedHashMap<>();
            infoBody.put("parameters", List.of(
                Map.of(
                    "name", "to",
                    "type", "body",
                    "dataType", "string",
                    "required", true,
                    "description", "Recipient address"
                ),
                Map.of(
                    "name", "model",
                    "type", "body",
                    "dataType", "string",
                    "required", true,
                    "description", "Model ID",
                    "defaultValue", "gpt-4o",
                    "allowedValues", List.of("gpt-4o", "gpt-4o-mini", "gpt-4.1")
                ),
                Map.of(
                    "name", "size",
                    "type", "body",
                    "dataType", "string",
                    "required", false,
                    "description", "Image size",
                    "defaultValue", "1024x1024",
                    "allowedValues", List.of("1024x1024", "1792x1024", "1024x1792")
                )
            ));

            ResponseEntity<Map> skeletonResponse = new ResponseEntity<>(skeletonBody, HttpStatus.OK);
            ResponseEntity<Map> infoResponse = new ResponseEntity<>(infoBody, HttpStatus.OK);

            when(restTemplate.exchange(
                contains("/api/v1/structure/tool/" + toolId + "/skeleton"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(skeletonResponse);
            when(restTemplate.exchange(
                contains("/api/catalog/tools/" + toolId + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(infoResponse);

            Optional<ToolExecutionResult> opt = module.execute("response_schema",
                Map.of("tool_id", toolId), null, null);

            assertThat(opt).isPresent();
            ToolExecutionResult result = opt.get();
            assertThat(result.success()).isTrue();

            Object data = result.data();
            assertThat(data).isInstanceOf(Map.class);
            Map<String, Object> payload = (Map<String, Object>) data;

            // Existing fields preserved
            assertThat(payload).containsKey("skeleton");
            assertThat(payload).containsKey("paths");
            assertThat(payload).containsKey("spelExamples");
            assertThat(payload.get("toolName")).isEqualTo("Send Gmail");

            // Phase D - input contract surfaces here
            assertThat(payload).containsKey("inputSchema");
            List<Map<String, Object>> inputSchema = (List<Map<String, Object>>) payload.get("inputSchema");
            assertThat(inputSchema).hasSize(3);

            Map<String, Object> modelParam = inputSchema.stream()
                .filter(p -> "model".equals(p.get("name")))
                .findFirst().orElseThrow();
            assertThat(modelParam.get("type")).isEqualTo("string");
            assertThat(modelParam.get("location")).isEqualTo("body");
            assertThat(modelParam.get("required")).isEqualTo(true);
            assertThat(modelParam.get("default")).isEqualTo("gpt-4o");
            assertThat(modelParam.get("allowedValues"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("gpt-4o", "gpt-4o-mini", "gpt-4.1");

            // Param without default/allowed should NOT carry the keys (avoid context noise)
            Map<String, Object> toParam = inputSchema.stream()
                .filter(p -> "to".equals(p.get("name")))
                .findFirst().orElseThrow();
            assertThat(toParam).doesNotContainKey("default");
            assertThat(toParam).doesNotContainKey("allowedValues");
        }

        @Test
        @DisplayName("forwards tenant and organization headers to skeleton and info lookups")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void forwardsTenantAndOrganizationHeadersToInternalLookups() {
            String toolId = "11111111-2222-3333-4444-555555555555";

            Map<String, Object> skeletonBody = new LinkedHashMap<>();
            skeletonBody.put("skeleton", Map.of());
            skeletonBody.put("paths", List.of());
            when(restTemplate.exchange(
                contains("/api/v1/structure/tool/" + toolId + "/skeleton"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(skeletonBody, HttpStatus.OK));
            when(restTemplate.exchange(
                contains("/api/catalog/tools/" + toolId + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("parameters", List.of()), HttpStatus.OK));

            ToolExecutionContext context = new ToolExecutionContext(
                "tenant-1",
                Map.of(),
                Map.of(),
                Set.of(),
                null,
                null,
                "org-1",
                "MEMBER"
            );

            Optional<ToolExecutionResult> opt = module.execute(
                "response_schema",
                Map.of("tool_id", toolId),
                "tenant-1",
                context
            );

            assertThat(opt).isPresent();
            assertThat(opt.get().success()).isTrue();

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate, times(2)).exchange(
                anyString(),
                eq(HttpMethod.GET),
                entityCaptor.capture(),
                eq(Map.class)
            );

            for (HttpEntity entity : entityCaptor.getAllValues()) {
                assertThat(entity.getHeaders().getFirst("X-User-ID")).isEqualTo("tenant-1");
                assertThat(entity.getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-1");
                assertThat(entity.getHeaders().getFirst("X-Organization-Role")).isEqualTo("MEMBER");
            }
        }

        @Test
        @DisplayName("falls back gracefully when /info is unreachable - response_schema still succeeds without inputSchema")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void inputSchemaFetchFailureDoesNotBreakResponseSchema() {
            String toolId = "99999999-8888-7777-6666-555555555555";

            Map<String, Object> skeletonBody = new LinkedHashMap<>();
            skeletonBody.put("skeleton", Map.of("id", "x"));
            skeletonBody.put("paths", List.of("id -> string"));

            ResponseEntity<Map> skeletonResponse = new ResponseEntity<>(skeletonBody, HttpStatus.OK);
            when(restTemplate.exchange(
                contains("/api/v1/structure/tool/" + toolId + "/skeleton"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(skeletonResponse);
            when(restTemplate.exchange(
                contains("/api/catalog/tools/" + toolId + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("connection refused"));

            Optional<ToolExecutionResult> opt = module.execute("response_schema",
                Map.of("tool_id", toolId), null, null);

            assertThat(opt).isPresent();
            ToolExecutionResult result = opt.get();
            assertThat(result.success()).isTrue();
            Map<String, Object> payload = (Map<String, Object>) result.data();
            assertThat(payload).containsKey("skeleton");
            // The agent gets the output skeleton; inputSchema is just absent - no crash.
            assertThat(payload).doesNotContainKey("inputSchema");
            // Same single-fetch failure must also drop the credential block (not fabricate one).
            assertThat(payload).doesNotContainKey("credential");
        }

        @Test
        @DisplayName("missing tool_id returns MISSING_PARAMETER unchanged")
        void missingToolIdFails() {
            Optional<ToolExecutionResult> opt = module.execute("response_schema", Map.of(), null, null);
            assertThat(opt).isPresent();
            assertThat(opt.get().success()).isFalse();
            assertThat(opt.get().error()).contains("tool_id");
        }

        @Test
        @DisplayName("whitespace-wrapped UUID is trimmed BEFORE forwarding - the internal URL contains no surrounding whitespace")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void whitespaceWrappedUuidIsTrimmedBeforeForwarding() {
            // Regression: the agent sometimes copies a UUID with stray whitespace. We must
            // trim BEFORE building the forwarded URL - otherwise Spring's @PathVariable UUID
            // converter on the internal /skeleton endpoint sees "/.../  uuid  /skeleton" and
            // 400s. The earlier test with `contains()` matchers passed vacuously even when the
            // raw (un-trimmed) value was forwarded.
            String wrapped = "  11111111-2222-3333-4444-555555555555  ";
            String trimmed = "11111111-2222-3333-4444-555555555555";

            Map<String, Object> skeletonBody = new LinkedHashMap<>();
            skeletonBody.put("skeleton", null);
            skeletonBody.put("paths", List.of());

            when(restTemplate.exchange(
                org.mockito.ArgumentMatchers.anyString(),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(skeletonBody, HttpStatus.OK));

            Optional<ToolExecutionResult> opt = module.execute("response_schema",
                Map.of("tool_id", wrapped), null, null);

            assertThat(opt).isPresent();
            assertThat(opt.get().success()).isTrue();

            org.mockito.ArgumentCaptor<String> urlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(restTemplate, org.mockito.Mockito.atLeastOnce())
                .exchange(urlCaptor.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));

            // Every URL forwarded must contain the trimmed UUID - and never any surrounding spaces.
            for (String url : urlCaptor.getAllValues()) {
                assertThat(url).contains(trimmed);
                assertThat(url).doesNotContain("  " + trimmed);
                assertThat(url).doesNotContain(trimmed + "  ");
            }
        }

        @Test
        @DisplayName("whitespace-only tool_id surfaces MISSING_PARAMETER (not INVALID_PARAMETER_VALUE)")
        void whitespaceOnlyToolIdIsTreatedAsMissing() {
            // Trimming "   " yields empty - semantically equivalent to no tool_id at all.
            // The agent should see MISSING_PARAMETER ("you forgot tool_id") rather than
            // INVALID_PARAMETER_VALUE ("the value is malformed"), which would mislead it
            // into trying to fix a value it never sent.
            Optional<ToolExecutionResult> opt = module.execute("response_schema",
                Map.of("tool_id", "   "), null, null);

            assertThat(opt).isPresent();
            assertThat(opt.get().success()).isFalse();
            assertThat(opt.get().error()).contains("tool_id");
            org.mockito.Mockito.verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("well-formed but unknown UUID propagates the controller's graceful cold-start payload (skeleton:null + hint)")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void wellFormedButUnknownUuidPropagatesGracefulColdStart() {
            // Cold-start contract end-to-end at the agent module level: the controller
            // returns 200 + {toolId, skeleton:null, paths:[], hint} for an unknown tool.
            // The module must surface that as success() (not error), so the agent reads
            // the hint instead of bouncing on an EXECUTION_FAILED.
            String unknownButValid = "12345678-1234-1234-1234-123456789abc";

            Map<String, Object> coldStartBody = new LinkedHashMap<>();
            coldStartBody.put("toolId", unknownButValid);
            coldStartBody.put("skeleton", null);
            coldStartBody.put("paths", List.of());
            coldStartBody.put("hint", "No response schema learned yet for this tool. Execute the tool at least once...");

            when(restTemplate.exchange(
                contains("/api/v1/structure/tool/"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(coldStartBody, HttpStatus.OK));
            // /info may still respond - could be an unknown UUID (404) or an existing-but-never-executed tool.
            when(restTemplate.exchange(
                contains("/api/catalog/tools/"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));

            Optional<ToolExecutionResult> opt = module.execute("response_schema",
                Map.of("tool_id", unknownButValid), null, null);

            assertThat(opt).isPresent();
            ToolExecutionResult result = opt.get();
            assertThat(result.success()).isTrue();

            Map<String, Object> payload = (Map<String, Object>) result.data();
            assertThat(payload.get("skeleton")).isNull();
            assertThat(payload).containsKey("hint");
            // No spelExamples on cold-start (paths is empty).
            assertThat(payload).doesNotContainKey("spelExamples");
        }

        @Test
        @DisplayName("malformed UUID (LLM hallucination) returns INVALID_PARAMETER_VALUE without hitting the internal endpoint")
        void rejectsMalformedUuidEarly() {
            // Regression: in prod the LLM produced "b52dd821-2e6a-47a3-8ee8-b3eb41tried"
            // (literally completed the suffix with the word "tried"). The internal
            // /skeleton endpoint @PathVariable UUID converter then threw and the
            // global handler returned a generic 500 - TOOL_050 EXECUTION_FAILED to
            // the agent. The validation must happen here so the agent gets a typed
            // INVALID_PARAMETER_VALUE and can retry with a corrected id.
            String malformed = "b52dd821-2e6a-47a3-8ee8-b3eb41tried";

            Optional<ToolExecutionResult> opt = module.execute("response_schema",
                Map.of("tool_id", malformed), null, null);

            assertThat(opt).isPresent();
            ToolExecutionResult result = opt.get();
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("UUID").contains(malformed);
            // Defense: the RestTemplate must NOT have been called - we validated upstream.
            org.mockito.Mockito.verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("inputSchema omits `default` key when defaultValue is blank")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void omitsBlankDefaultValue() {
            String toolId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

            Map<String, Object> skeletonBody = new LinkedHashMap<>();
            skeletonBody.put("skeleton", Map.of());
            skeletonBody.put("paths", List.of());

            Map<String, Object> infoBody = new LinkedHashMap<>();
            infoBody.put("parameters", List.of(
                Map.of(
                    "name", "page",
                    "type", "query",
                    "dataType", "integer",
                    "required", false,
                    "defaultValue", "",
                    "description", "Page number"
                )
            ));

            when(restTemplate.exchange(contains("/skeleton"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(skeletonBody, HttpStatus.OK));
            when(restTemplate.exchange(contains("/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(infoBody, HttpStatus.OK));

            Map<String, Object> payload = (Map<String, Object>) module.execute(
                "response_schema", Map.of("tool_id", toolId), null, null).get().data();
            List<Map<String, Object>> inputSchema = (List<Map<String, Object>>) payload.get("inputSchema");

            assertThat(inputSchema).hasSize(1);
            Map<String, Object> p = inputSchema.get(0);
            assertThat(p).doesNotContainKey("default");
            assertThat(p.get("name")).isEqualTo("page");
        }

        @Test
        @DisplayName("inputSchema emits required:false so the agent knows the param is optional and may be omitted")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void preservesRequiredFalseSoAgentKnowsItIsOptional() {
            String toolId = "aaaaaaaa-bbbb-cccc-dddd-ffffffffffff";

            Map<String, Object> skeletonBody = new LinkedHashMap<>();
            skeletonBody.put("skeleton", Map.of());
            skeletonBody.put("paths", List.of());

            Map<String, Object> infoBody = new LinkedHashMap<>();
            infoBody.put("parameters", List.of(
                Map.of(
                    "name", "parse_mode",
                    "type", "body",
                    "dataType", "string",
                    "required", false,
                    "description", "Optional formatting mode"
                )
            ));

            when(restTemplate.exchange(contains("/skeleton"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(skeletonBody, HttpStatus.OK));
            when(restTemplate.exchange(contains("/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(infoBody, HttpStatus.OK));

            Map<String, Object> payload = (Map<String, Object>) module.execute(
                "response_schema", Map.of("tool_id", toolId), null, null).get().data();
            List<Map<String, Object>> inputSchema = (List<Map<String, Object>>) payload.get("inputSchema");

            assertThat(inputSchema).hasSize(1);
            Map<String, Object> p = inputSchema.get(0);
            assertThat(p).containsEntry("required", false);
            assertThat(p.get("name")).isEqualTo("parse_mode");
        }

        @Test
        @DisplayName("inputSchema drops `location` when the parameter_type is not in {header,path,query,body}")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void unknownLocationIsDropped() {
            String toolId = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff";

            Map<String, Object> skeletonBody = new LinkedHashMap<>();
            skeletonBody.put("skeleton", Map.of());
            skeletonBody.put("paths", List.of());

            Map<String, Object> infoBody = new LinkedHashMap<>();
            infoBody.put("parameters", List.of(
                Map.of(
                    "name", "form_field",
                    "type", "formData", // hypothetical future location not in the whitelist
                    "dataType", "string",
                    "required", true
                )
            ));

            when(restTemplate.exchange(contains("/skeleton"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(skeletonBody, HttpStatus.OK));
            when(restTemplate.exchange(contains("/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(infoBody, HttpStatus.OK));

            Map<String, Object> payload = (Map<String, Object>) module.execute(
                "response_schema", Map.of("tool_id", toolId), null, null).get().data();
            List<Map<String, Object>> inputSchema = (List<Map<String, Object>>) payload.get("inputSchema");

            assertThat(inputSchema).hasSize(1);
            // Fail-closed on unknown location - better than leaking unexpected payload values.
            assertThat(inputSchema.get(0)).doesNotContainKey("location");
            assertThat(inputSchema.get(0).get("name")).isEqualTo("form_field");
            assertThat(inputSchema.get(0).get("type")).isEqualTo("string");
        }

        @Test
        @DisplayName("inputSchema surfaces `example` when /info exposes a concrete exampleValue")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void exampleValueIsForwarded() {
            String toolId = "cccccccc-dddd-eeee-ffff-aaaaaaaaaaaa";

            Map<String, Object> skeletonBody = new LinkedHashMap<>();
            skeletonBody.put("skeleton", Map.of());
            skeletonBody.put("paths", List.of());

            Map<String, Object> infoBody = new LinkedHashMap<>();
            infoBody.put("parameters", List.of(
                Map.of(
                    "name", "to",
                    "type", "body",
                    "dataType", "string",
                    "required", true,
                    "exampleValue", "user@example.com",
                    "description", "Recipient address"
                )
            ));

            when(restTemplate.exchange(contains("/skeleton"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(skeletonBody, HttpStatus.OK));
            when(restTemplate.exchange(contains("/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(infoBody, HttpStatus.OK));

            Map<String, Object> payload = (Map<String, Object>) module.execute(
                "response_schema", Map.of("tool_id", toolId), null, null).get().data();
            List<Map<String, Object>> inputSchema = (List<Map<String, Object>>) payload.get("inputSchema");

            assertThat(inputSchema).hasSize(1);
            assertThat(inputSchema.get(0).get("example")).isEqualTo("user@example.com");
        }

        @Test
        @DisplayName("surfaces the credential requirement (type) from /info authType so the agent learns the credential KIND before execute()")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void surfacesCredentialTypeFromAuthType() {
            String toolId = "12121212-3434-5656-7878-909090909090";

            Map<String, Object> skeletonBody = new LinkedHashMap<>();
            skeletonBody.put("skeleton", Map.of());
            skeletonBody.put("paths", List.of());

            Map<String, Object> infoBody = new LinkedHashMap<>();
            // The DB drift value 'apiKey' must normalize to 'api_key' for the agent.
            infoBody.put("authType", "apiKey");
            infoBody.put("parameters", List.of(
                Map.of("name", "q", "type", "query", "dataType", "string", "required", true)
            ));

            when(restTemplate.exchange(contains("/skeleton"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(skeletonBody, HttpStatus.OK));
            when(restTemplate.exchange(contains("/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(infoBody, HttpStatus.OK));

            Map<String, Object> payload = (Map<String, Object>) module.execute(
                "response_schema", Map.of("tool_id", toolId), null, null).get().data();

            assertThat(payload).containsKey("credential");
            Map<String, Object> credential = (Map<String, Object>) payload.get("credential");
            assertThat(credential.get("type")).isEqualTo("api_key");
            // api_key tools declare no OAuth scopes - the key must be absent (no noise).
            assertThat(credential).doesNotContainKey("requiredScopes");
        }

        @Test
        @DisplayName("OAuth tool surfaces credential.type=oauth2 AND requiredScopes so the agent can tell the user what consent will request")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void surfacesOauthScopes() {
            String toolId = "21212121-4343-6565-8787-090909090909";

            Map<String, Object> skeletonBody = new LinkedHashMap<>();
            skeletonBody.put("skeleton", Map.of());
            skeletonBody.put("paths", List.of());

            Map<String, Object> infoBody = new LinkedHashMap<>();
            infoBody.put("authType", "oauth2");
            infoBody.put("requiredScopes", List.of("https://www.googleapis.com/auth/gmail.send"));
            infoBody.put("parameters", List.of());

            when(restTemplate.exchange(contains("/skeleton"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(skeletonBody, HttpStatus.OK));
            when(restTemplate.exchange(contains("/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(infoBody, HttpStatus.OK));

            Map<String, Object> payload = (Map<String, Object>) module.execute(
                "response_schema", Map.of("tool_id", toolId), null, null).get().data();

            Map<String, Object> credential = (Map<String, Object>) payload.get("credential");
            assertThat(credential.get("type")).isEqualTo("oauth2");
            assertThat(credential.get("requiredScopes"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("https://www.googleapis.com/auth/gmail.send");
        }

        @Test
        @DisplayName("tool with no authType reports credential.type=none so the agent knows it can execute without request_credential")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void noAuthTypeReportsNone() {
            String toolId = "11112222-3333-4444-5555-666677778888";

            Map<String, Object> skeletonBody = new LinkedHashMap<>();
            skeletonBody.put("skeleton", Map.of());
            skeletonBody.put("paths", List.of());

            // No authType key at all (e.g. a keyless public API).
            Map<String, Object> infoBody = new LinkedHashMap<>();
            infoBody.put("parameters", List.of());

            when(restTemplate.exchange(contains("/skeleton"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(skeletonBody, HttpStatus.OK));
            when(restTemplate.exchange(contains("/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(infoBody, HttpStatus.OK));

            Map<String, Object> payload = (Map<String, Object>) module.execute(
                "response_schema", Map.of("tool_id", toolId), null, null).get().data();

            Map<String, Object> credential = (Map<String, Object>) payload.get("credential");
            assertThat(credential.get("type")).isEqualTo("none");
        }

        @Test
        @DisplayName("inputSchema only emits non-empty arrays for allowedValues - empty list = absent key")
        @SuppressWarnings({"rawtypes", "unchecked"})
        void emptyAllowedValuesIsOmitted() {
            String toolId = "00000000-1111-2222-3333-444444444444";

            Map<String, Object> skeletonBody = new LinkedHashMap<>();
            skeletonBody.put("skeleton", Map.of());
            skeletonBody.put("paths", List.of());

            Map<String, Object> infoBody = new LinkedHashMap<>();
            infoBody.put("parameters", List.of(
                Map.of(
                    "name", "filter",
                    "type", "query",
                    "dataType", "string",
                    "required", false,
                    "allowedValues", List.of()
                )
            ));

            when(restTemplate.exchange(contains("/skeleton"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(skeletonBody, HttpStatus.OK));
            when(restTemplate.exchange(contains("/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(infoBody, HttpStatus.OK));

            Optional<ToolExecutionResult> opt = module.execute("response_schema",
                Map.of("tool_id", toolId), null, null);

            Map<String, Object> payload = (Map<String, Object>) opt.get().data();
            List<Map<String, Object>> inputSchema = (List<Map<String, Object>>) payload.get("inputSchema");
            assertThat(inputSchema).hasSize(1);
            assertThat(inputSchema.get(0)).doesNotContainKey("allowedValues");
        }
    }
}

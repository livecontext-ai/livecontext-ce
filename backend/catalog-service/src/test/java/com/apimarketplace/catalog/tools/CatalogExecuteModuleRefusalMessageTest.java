package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolErrorCode;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a caller is TOLD when this path refuses, and who can read it.
 *
 * <p>Two readers share one string. An agent gets the message text and nothing
 * else: the MCP bridge forwards a failed tool result as its {@code error} alone
 * and the direct-API loop appends {@code "Error: " + error} to the conversation,
 * so {@code errorCode} and {@code metadata} never reach the model. A person gets
 * the same string rendered verbatim by the generation dialog, which shows
 * {@code result.error} on purpose, because every refusal here is supposed to be
 * something they can act on.
 *
 * <p>The defect these tests pin: the credentials refusal handed back the WHOLE
 * upstream envelope, serialized. A person was shown internal ids, an endpoint
 * path and a request id; an agent was shown a JSON document it had to parse to
 * learn anything. Every refusal on this path is now a sentence naming a remedy,
 * led by a stable code an agent can branch on without reading the prose.
 */
class CatalogExecuteModuleRefusalMessageTest {

    private static final String TOOL_ID = "elevenlabs/sound-generation";

    /**
     * The upstream answer, verbatim from the live run that found this: HTTP 200
     * carrying a refusal envelope, which is how catalog-service reports a
     * missing credential.
     */
    private static final String CREDENTIALS_REQUIRED_ENVELOPE = """
            {"success":false,
             "result":{"tool_name":"sound_generation","credential_type":"api_key",
                       "credential_name":"elevenlabs",
                       "tool_id":"3e2f8ed3-e8d3-4f59-b1b5-0bd307784ee2",
                       "error":"credentials_required",
                       "message":"This tool requires elevenlabs credentials"},
             "error":"credentials_required",
             "metadata":{"toolName":"sound_generation","endpoint":"/sound-generation",
                         "method":"POST","apiId":"e3cd5dfb-1111-2222-3333-444455556666",
                         "iconSlug":"elevenlabs","status":"unknown"},
             "executionTimeMs":404,
             "toolId":"3e2f8ed3-e8d3-4f59-b1b5-0bd307784ee2",
             "requestId":"c9ce98ff-7777-8888-9999-000011112222"}
            """;

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

    /**
     * The credential PRE-FLIGHT passes: the caller has a default credential for
     * the service, so the gate does not short-circuit and the call reaches the
     * execution endpoint, which is where the refusal under test is produced.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubPreflightPasses() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("iconSlug", "elevenlabs");
        info.put("name", "sound_generation");
        info.put("authType", "apiKey");
        when(restTemplate.exchange(
                contains("/api/catalog/tools/" + TOOL_ID + "/info"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(info, HttpStatus.OK));

        CredentialSummaryDto credential = new CredentialSummaryDto();
        credential.setName("My ElevenLabs key");
        credential.setIntegration("elevenlabs");
        credential.setDefault(true);
        when(credentialClient.getDefaultCredential(eq("user-1"), eq("elevenlabs")))
                .thenReturn(Optional.of(credential));
    }

    @SuppressWarnings("unchecked")
    private void stubExecuteAnswers(String body, HttpStatus status) {
        when(restTemplate.exchange(
                contains("/catalog/v1/tools/" + TOOL_ID + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(body, status));
    }

    /** Run one generation-shaped call, optionally naming a credential pool. */
    private ToolExecutionResult run(String credentialSource) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tool_id", TOOL_ID);
        params.put("params", Map.of("text", "a door closing"));
        if (credentialSource != null) {
            params.put("credential_source", credentialSource);
        }
        return module.execute("execute", params, "user-1", ToolExecutionContext.of("user-1")).orElseThrow();
    }

    /**
     * The upstream answer, verbatim from the live run that found this: HTTP 200
     * carrying a REFUSAL BY THE PROVIDER. ElevenLabs answered 401 because the
     * stored key had expired, and catalog-service reported that faithfully -
     * success=false, the status, and the sentence a person needs.
     */
    private static final String PROVIDER_REFUSED_ENVELOPE = """
            {"success":false,
             "result":{"httpStatus":{"code":401,
                       "error":"Authentication expired for elevenlabs. Please reconnect your account."}},
             "error":"Authentication expired for elevenlabs. Please reconnect your account.",
             "metadata":{"toolName":"sound_generation","endpoint":"/sound-generation",
                         "method":"POST","apiId":"e3cd5dfb-1111-2222-3333-444455556666",
                         "iconSlug":"elevenlabs","status":401,
                         "httpStatus":{"code":401,"error":""}},
             "executionTimeMs":392,
             "toolId":"3e2f8ed3-e8d3-4f59-b1b5-0bd307784ee2",
             "requestId":"14a708fb-7777-8888-9999-000011112222"}
            """;

    @Nested
    @DisplayName("a provider that refuses the call is reported as a refusal, not as a success")
    class ProviderRefused {

        @Test
        @DisplayName("REGRESSION: an upstream 401 inside a transport 200 fails, and does not claim a charge")
        void upstreamRefusalIsAFailure() {
            stubPreflightPasses();
            stubExecuteAnswers(PROVIDER_REFUSED_ENVELOPE, HttpStatus.OK);

            ToolExecutionResult result = run("user");

            // Pre-fix this was ToolExecutionResult.success(envelope): the caller
            // read success=true, and GenerationModule went on to blame its own
            // descriptor - "no asset could be retrieved ... You have been
            // charged for it" - for a call the provider had refused and that
            // ToolExecutionManager had already released.
            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(result.error()).startsWith(CatalogExecuteModule.UPSTREAM_REJECTED_CODE);
        }

        @Test
        @DisplayName("the provider's own sentence is what the reader gets, with the status and no false charge")
        void carriesTheProvidersOwnSentence() {
            stubPreflightPasses();
            stubExecuteAnswers(PROVIDER_REFUSED_ENVELOPE, HttpStatus.OK);

            ToolExecutionResult result = run("user");

            assertThat(result.error())
                    .contains("Please reconnect your account")
                    .contains("HTTP 401")
                    .contains("nothing was charged")
                    .doesNotContain("You have been charged");
            // Same rule as the sibling refusals: a sentence, never the envelope.
            assertThat(result.error()).doesNotStartWith("{");
            assertThat(result.error()).doesNotContain("requestId", "executionTimeMs", "apiId");
        }

        @Test
        @DisplayName("an envelope that reports success is still a success")
        void successEnvelopeStillSucceeds() {
            stubPreflightPasses();
            stubExecuteAnswers("""
                    {"success":true,
                     "result":{"audio":{"_type":"file","path":"1/general/catalog-binary/x.mp3",
                                        "name":"x.mp3","mimeType":"audio/mpeg","size":1024}},
                     "metadata":{"toolName":"sound_generation","status":200},
                     "toolId":"3e2f8ed3-e8d3-4f59-b1b5-0bd307784ee2"}
                    """, HttpStatus.OK);

            ToolExecutionResult result = run("user");

            assertThat(result.success()).isTrue();
        }
    }

    @Nested
    @DisplayName("a missing credential is refused in words, not with the upstream envelope")
    class CredentialsRequired {

        @Test
        @DisplayName("REGRESSION: the refusal is a sentence, and carries none of the envelope a reader cannot act on")
        void refusalIsASentenceNotTheEnvelope() {
            stubPreflightPasses();
            stubExecuteAnswers(CREDENTIALS_REQUIRED_ENVELOPE, HttpStatus.OK);

            ToolExecutionResult result = run("platform");

            assertThat(result.success()).isFalse();
            // Pre-fix this WAS the serialized envelope, so it began with '{'.
            assertThat(result.error()).doesNotStartWith("{");
            assertThat(result.error()).doesNotContain(
                    "requestId", "executionTimeMs", "apiId", "endpoint",
                    "3e2f8ed3-e8d3-4f59-b1b5-0bd307784ee2",
                    "c9ce98ff-7777-8888-9999-000011112222",
                    "e3cd5dfb-1111-2222-3333-444455556666",
                    "\"success\"");
        }

        @Test
        @DisplayName("it names the integration a key is missing for, so the reader knows what to connect")
        void namesTheIntegration() {
            stubPreflightPasses();
            stubExecuteAnswers(CREDENTIALS_REQUIRED_ENVELOPE, HttpStatus.OK);

            assertThat(run("platform").error()).contains("Elevenlabs");
        }

        @Test
        @DisplayName("asking for the platform's key is told the platform has none, and offered its own key")
        void platformPoolNamesTheOtherPool() {
            stubPreflightPasses();
            stubExecuteAnswers(CREDENTIALS_REQUIRED_ENVELOPE, HttpStatus.OK);

            String error = run("platform").error();
            assertThat(error).contains("this platform has none");
            assertThat(error).contains("credential_source='user'");
            // The remedy for the OTHER pool would be a lie here: the platform
            // key is exactly the thing that is missing.
            assertThat(error).doesNotContain("credential_source='platform'");
        }

        @Test
        @DisplayName("asking for your own key is told this account has none, and offered the platform key")
        void userPoolNamesTheOtherPool() {
            stubPreflightPasses();
            stubExecuteAnswers(CREDENTIALS_REQUIRED_ENVELOPE, HttpStatus.OK);

            String error = run("user").error();
            assertThat(error).contains("this account has none");
            assertThat(error).contains("credential_source='platform'");
            assertThat(error).doesNotContain("credential_source='user'");
        }

        @Test
        @DisplayName("naming no pool means NEITHER answered, so the refusal offers neither as a remedy")
        void unspecifiedPoolClaimsNoRemedyItCannotKeep() {
            stubPreflightPasses();
            stubExecuteAnswers(CREDENTIALS_REQUIRED_ENVELOPE, HttpStatus.OK);

            String error = run(null).error();
            assertThat(error).contains("on this account or on this platform");
            assertThat(error).contains("Add your Elevenlabs key to this account");
            // Suggesting a pool would be false: the fallback already tried both.
            assertThat(error).doesNotContain("credential_source=");
        }

        @Test
        @DisplayName("every credential refusal states nothing was charged, so a retry is not a second bill")
        void everyRefusalSaysNothingWasCharged() {
            stubPreflightPasses();
            stubExecuteAnswers(CREDENTIALS_REQUIRED_ENVELOPE, HttpStatus.OK);

            for (String source : new String[] {"platform", "user", null}) {
                assertThat(run(source).error())
                        .as("credential_source=%s", source)
                        .contains("nothing was charged");
            }
        }

        @Test
        @DisplayName("an agent still tells this failure apart without parsing prose: a leading code, plus the typed error code")
        void agentCanBranchWithoutReadingTheSentence() {
            stubPreflightPasses();
            stubExecuteAnswers(CREDENTIALS_REQUIRED_ENVELOPE, HttpStatus.OK);

            ToolExecutionResult result = run("platform");

            // The typed code, for the REST surfaces that forward it.
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.CREDENTIALS_REQUIRED);
            // And in the text, which is the ONLY channel that reaches a model
            // through the MCP bridge and through the direct-API tool loop.
            assertThat(result.error()).startsWith(CatalogExecuteModule.CREDENTIALS_REQUIRED_CODE + ":");
        }

        @Test
        @DisplayName("the structured detail an interface needs survives on metadata, so nothing machine-readable was lost")
        @SuppressWarnings("unchecked")
        void structuredDetailMovesToMetadata() {
            stubPreflightPasses();
            stubExecuteAnswers(CREDENTIALS_REQUIRED_ENVELOPE, HttpStatus.OK);

            Map<String, Object> metadata = run("platform").metadata();

            assertThat(metadata.get("credentialNeeded")).isEqualTo(true);
            assertThat(metadata.get("serviceType")).isEqualTo("elevenlabs");
            assertThat(metadata.get("serviceName")).isEqualTo("Elevenlabs");
            assertThat(metadata.get("credentialSource")).isEqualTo("platform");
            assertThat((Map<String, Object>) metadata.get("credential")).containsEntry("type", "api_key");
            // The credential card the chat draws is keyed off this, and it was
            // the one machine-readable thing the old blob DID carry properly.
            assertThat((Map<String, Object>) metadata.get("visualization"))
                    .containsEntry("type", "credential")
                    .containsEntry("iconSlug", "elevenlabs");
        }

        @Test
        @DisplayName("a 401 answered without an exception refuses the same way, not with a hand-written JSON string")
        void unauthorizedStatusUsesTheSameRefusal() {
            stubPreflightPasses();
            stubExecuteAnswers("{\"metadata\":{\"iconSlug\":\"elevenlabs\"}}", HttpStatus.UNAUTHORIZED);

            ToolExecutionResult result = run("platform");

            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.CREDENTIALS_REQUIRED);
            assertThat(result.error())
                    .doesNotStartWith("{")
                    .startsWith(CatalogExecuteModule.CREDENTIALS_REQUIRED_CODE + ":")
                    .contains("Elevenlabs");
        }
    }

    @Nested
    @DisplayName("the sibling refusals on this path keep the upstream body out of the message too")
    class SiblingRefusals {

        private Method handler;

        @BeforeEach
        void findHandler() throws Exception {
            handler = CatalogExecuteModule.class.getDeclaredMethod(
                    "handleHttpClientError", HttpClientErrorException.class, String.class);
            handler.setAccessible(true);
        }

        private ToolExecutionResult refuse(HttpStatus status, String body) throws Exception {
            HttpClientErrorException e = HttpClientErrorException.create(
                    status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                    body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            return (ToolExecutionResult) handler.invoke(module, e, TOOL_ID);
        }

        @Test
        @DisplayName("REGRESSION: a 4xx no longer publishes the raw upstream body, which the exception message inlines")
        @SuppressWarnings("unchecked")
        void fourXxKeepsTheBodyOutOfTheMessage() throws Exception {
            ToolExecutionResult result = refuse(HttpStatus.BAD_REQUEST,
                    "{\"error\":\"bad_request\",\"trace_id\":\"abc-123\","
                            + "\"stack\":\"com.example.Boom\",\"message\":\"text is required\"}");

            assertThat(result.error())
                    .startsWith(CatalogExecuteModule.UPSTREAM_REJECTED_CODE + ":")
                    .contains("HTTP 400")
                    .contains("nothing was charged")
                    // The one human line upstream wrote is quoted.
                    .contains("text is required")
                    // The rest of the body is not.
                    .doesNotContain("trace_id", "abc-123", "stack", "com.example.Boom");

            // Kept, in the channel that can carry a document.
            assertThat((Map<String, Object>) result.metadata().get("upstreamError"))
                    .containsEntry("trace_id", "abc-123");
        }

        @Test
        @DisplayName("regression (CASA LC-066): a Gmail 401 Reconnect card read nothing, so it never marks the conversation restricted")
        void expiredGmailCardIsNotRestrictedData() throws Exception {
            ToolExecutionResult result = refuse(HttpStatus.UNAUTHORIZED, "{\"service\":\"gmail\"}");

            // The card names Gmail so it can be drawn; the provider refused the call.
            assertThat(result.metadata()).containsEntry("iconSlug", "gmail").containsEntry("authExpired", true);
            assertThat(com.apimarketplace.common.classification.RestrictedDataPolicy
                    .fromToolMetadata(result.metadata()).isRestricted()).isFalse();
        }

        @Test
        @DisplayName("a rate limit says to wait, because 'change the call' is not the remedy for it")
        void rateLimitSaysWait() throws Exception {
            ToolExecutionResult result = refuse(HttpStatus.TOO_MANY_REQUESTS, "{\"message\":\"slow down\"}");

            assertThat(result.error()).contains("rate limited").contains("Wait before sending it again");
            assertThat(result.error()).doesNotContain("Change the call");
        }

        @Test
        @DisplayName("an upstream 'message' that is itself a JSON document is dropped, not quoted back into the sentence")
        void nestedDocumentIsNotQuoted() throws Exception {
            ToolExecutionResult result = refuse(HttpStatus.BAD_REQUEST,
                    "{\"message\":\"{\\\"code\\\":42,\\\"detail\\\":\\\"nope\\\"}\"}");

            assertThat(result.error()).doesNotContain("\"code\"").doesNotContain("The service said");
        }

        @Test
        @DisplayName("REGRESSION: a transport failure does not publish the internal host it could not reach")
        void transportFailureKeepsTheInternalHostOutOfTheMessage() {
            stubPreflightPasses();
            when(restTemplate.exchange(
                    contains("/catalog/v1/tools/" + TOOL_ID + "/execute"),
                    eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new RuntimeException(
                            "I/O error on POST request for \"http://localhost:8081/catalog/v1/tools/x/execute\": "
                                    + "Connection refused"));

            ToolExecutionResult result = run("platform");

            assertThat(result.error())
                    .startsWith(CatalogExecuteModule.TOOL_CALL_FAILED_CODE + ":")
                    .contains("nothing was charged")
                    .doesNotContain("localhost", "8081", "I/O error");
            // The detail is not lost, it is simply not in the reader's sentence.
            assertThat(String.valueOf(result.metadata().get("failureDetail"))).contains("Connection refused");
        }
    }
}

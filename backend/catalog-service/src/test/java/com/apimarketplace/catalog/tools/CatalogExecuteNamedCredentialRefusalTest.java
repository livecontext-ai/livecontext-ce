package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the caller reads when the account it named could not be used.
 *
 * <p>This is the whole safety claim of naming an account: the call is REFUSED rather
 * than quietly run against a different account. The refusal therefore has to arrive as
 * something the caller can act on, and it very nearly did not. The selection refusal
 * leaves the catalog as an HTTP 422, which the generic branch would have turned into
 * "the call was refused with HTTP 422", prefixing an agent-facing sentence with a status
 * code it can do nothing with, and then cutting the upstream explanation at 200
 * characters - losing exactly the half that says how to fix it.
 */
@DisplayName("catalog execute - the account named could not be used")
class CatalogExecuteNamedCredentialRefusalTest {

    private static final String TOOL_ID = "1e58e9ef-44fc-4378-b4fa-61e80222aac0";
    private static final String TENANT = "121";

    /** The sentence the catalog actually produces, at its real length. */
    private static final String UPSTREAM_SENTENCE =
            "This step selects its credential at run time (name \\\"Boulot\\\") but no active "
            + "credential of this integration is named that (either the name does not match one, "
            + "ignoring capitalisation and surrounding spaces, or the credential service could not "
            + "be reached). The call was NOT made: running it would have used this account's "
            + "default credential for the integration, which is a different account from the one "
            + "the workflow asked for.";

    private CatalogExecuteModule module;
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() throws Exception {
        CredentialClient credentialClient = mock(CredentialClient.class);
        module = new CatalogExecuteModule(new ObjectMapper(), credentialClient);

        restTemplate = mock(RestTemplate.class);
        Field rtField = CatalogExecuteModule.class.getDeclaredField("restTemplate");
        rtField.setAccessible(true);
        rtField.set(module, restTemplate);
        Field portField = CatalogExecuteModule.class.getDeclaredField("serverPort");
        portField.setAccessible(true);
        portField.setInt(module, 8081);

        stubInfo();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubInfo() {
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubSelectionRefused() {
        String body = "{\"success\":false,\"error\":\"CREDENTIAL_SELECTION_UNRESOLVED\","
                + "\"message\":\"" + UPSTREAM_SENTENCE + "\"}";
        when(restTemplate.exchange(
                contains("/catalog/v1/tools/" + TOOL_ID + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity",
                        org.springframework.http.HttpHeaders.EMPTY,
                        body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    private ToolExecutionResult refusal() {
        return module.execute("execute",
                Map.of("tool_id", TOOL_ID, "params", Map.of("userId", "me"),
                       "credential_name", "Boulot"),
                TENANT,
                new ToolExecutionContext(TENANT, Map.of(), Map.of(), Set.of(), null, null, null, null))
                .orElseThrow();
    }

    @Test
    @DisplayName("the refusal leads with the upstream sentence, whole, and never with an HTTP status")
    void theSentenceLeadsAndIsWhole() {
        stubSelectionRefused();

        ToolExecutionResult result = refusal();

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .startsWith("This step selects its credential at run time")
                .doesNotContain("HTTP 422")
                .doesNotContain("422");
        // The half that says how to fix it is the half a 200-character cut would remove.
        assertThat(result.error()).contains("the name does not match one");
    }

    @Test
    @DisplayName("it says what to do next in the caller's own vocabulary, not the workflow's")
    void itNamesTheArgumentTheCallerUsed() {
        stubSelectionRefused();

        assertThat(refusal().error())
                .contains("credential_name")
                .contains("accounts list")
                .contains("nothing was charged");
    }

    @Test
    @DisplayName("it is an INVALID PARAMETER, not a generic execution failure, so a caller can branch on it")
    void theErrorCodeSaysTheArgumentWasWrong() {
        stubSelectionRefused();

        assertThat(refusal().errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
    }

    @Test
    @DisplayName("a 422 that is NOT a credential-selection refusal keeps the generic wording, so the advice never names an argument the caller did not send")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void anUnrelated422KeepsTheGenericWording() {
        when(restTemplate.exchange(
                contains("/catalog/v1/tools/" + TOOL_ID + "/execute"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity",
                        org.springframework.http.HttpHeaders.EMPTY,
                        new byte[0], StandardCharsets.UTF_8));

        // Keyed on the code the body carries, not on the status: that route answers 422
        // from one place today, and a future refusal reusing it must not inherit advice
        // about credential_name from a caller that never sent one.
        assertThat(refusal().error())
                .doesNotContain("credential_name")
                .contains("refused with HTTP 422");
    }
}

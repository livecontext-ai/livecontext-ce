package com.apimarketplace.agent.tools.agent.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ToolCallRedactor")
class ToolCallRedactorTest {

    private ToolCallRedactor redactor;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        redactor = new ToolCallRedactor(mapper);
    }

    @Test
    @DisplayName("Field 'token' is redacted at any depth")
    void tokenFieldRedacted() {
        String json = "{\"token\":\"abc123\",\"other\":\"public\"}";

        String result = redactor.redactJsonString(json, "anything");

        assertThat(result).contains("\"token\":\"[REDACTED]\"");
        assertThat(result).contains("\"other\":\"public\"");
        assertThat(result).doesNotContain("abc123");
    }

    @Test
    @DisplayName("Multiple secret fields all redacted (token, secret, password, apikey, authorization)")
    void multipleSecretFieldsRedacted() {
        String json = "{\"access_token\":\"x\",\"refresh_token\":\"y\",\"client_secret\":\"z\","
                + "\"password\":\"p\",\"apiKey\":\"k\",\"Authorization\":\"Bearer foo\"}";

        String result = redactor.redactJsonString(json, "anything");

        assertThat(result).doesNotContain("\"x\"").doesNotContain("\"y\"").doesNotContain("\"z\"");
        assertThat(result).doesNotContain("\"p\"").doesNotContain("\"k\"").doesNotContain("Bearer foo");
        assertThat(result.split("\\[REDACTED\\]")).hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("Secret fields are redacted inside nested objects")
    void nestedSecretRedacted() {
        String json = "{\"outer\":{\"inner\":{\"api_key\":\"deepleak\"}}}";

        String result = redactor.redactJsonString(json, null);

        assertThat(result).contains("[REDACTED]");
        assertThat(result).doesNotContain("deepleak");
    }

    @Test
    @DisplayName("Secret fields are redacted inside arrays")
    void arraySecretRedacted() {
        String json = "{\"creds\":[{\"name\":\"a\",\"password\":\"x\"},{\"name\":\"b\",\"password\":\"y\"}]}";

        String result = redactor.redactJsonString(json, null);

        assertThat(result).doesNotContain("\"x\"").doesNotContain("\"y\"");
        assertThat(result).contains("[REDACTED]");
        assertThat(result).contains("\"name\":\"a\"").contains("\"name\":\"b\"");
    }

    @Test
    @DisplayName("Credential tools have ENTIRE body replaced (defense-in-depth)")
    void credentialToolsBodyFullyReplaced() {
        String json = "{\"name\":\"benign-field\",\"some_value\":42}";

        String result = redactor.redactJsonString(json, "credential.create");

        assertThat(result).contains("[REDACTED:credential-tool]");
        assertThat(result).doesNotContain("benign-field");
        assertThat(result).doesNotContain("42");
    }

    @Test
    @DisplayName("Plain non-credential tool keeps benign fields unchanged")
    void benignFieldsUntouched() {
        String json = "{\"name\":\"foo\",\"count\":42,\"active\":true}";

        String result = redactor.redactJsonString(json, "web_search");

        assertThat(result).contains("\"name\":\"foo\"");
        assertThat(result).contains("\"count\":42");
        assertThat(result).contains("\"active\":true");
        assertThat(result).doesNotContain("[REDACTED]");
    }

    @Test
    @DisplayName("Malformed JSON falls back to raw scrub")
    void malformedJsonFallsBackToRawScrub() {
        String raw = "Not really JSON \"token\":\"leak\" still in plain text";

        String result = redactor.redactJsonString(raw, null);

        assertThat(result).doesNotContain("leak");
        assertThat(result).contains("[REDACTED]");
    }

    @Test
    @DisplayName("redactMap round-trips a map and redacts secret fields")
    void redactMapRedactsSecrets() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", "SELECT * FROM users");
        args.put("api_key", "leaky-key-value");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) redactor.redactMap(args, "datasource.query");

        assertThat(result).containsEntry("query", "SELECT * FROM users");
        assertThat(result).containsEntry("api_key", "[REDACTED]");
    }

    @Test
    @DisplayName("redactMap returns the redacted-tool marker for credential-tools")
    void redactMapForCredentialTool() {
        Map<String, Object> args = Map.of("ignored", "data");

        Object result = redactor.redactMap(args, "credential");

        assertThat(result).isEqualTo("[REDACTED:credential-tool]");
    }

    @Test
    @DisplayName("Null and blank inputs are returned unchanged")
    void nullInputUnchanged() {
        assertThat(redactor.redactJsonString(null, "any")).isNull();
        assertThat(redactor.redactJsonString("", "any")).isEmpty();
        assertThat(redactor.redactMap(null, "any")).isNull();
    }

    @Test
    @DisplayName("Variants like x-api-key, X-Auth, csrf are caught (case-insensitive)")
    void caseInsensitiveCustomHeaders() {
        String json = "{\"X-API-Key\":\"a\",\"X-Auth\":\"b\",\"csrf_token\":\"c\"}";

        String result = redactor.redactJsonString(json, null);

        assertThat(result).doesNotContain("\"a\"").doesNotContain("\"b\"").doesNotContain("\"c\"");
    }
}

package com.apimarketplace.trigger.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TriggerConfigRedactor} - the trigger-config secret
 * sanitiser introduced for design v3.5 §L4 (trigger list endpoint must not
 * leak webhook auth secrets to the application owner UI).
 */
@DisplayName("TriggerConfigRedactor")
class TriggerConfigRedactorTest {

    @Nested
    @DisplayName("isSecretKey")
    class IsSecretKey {

        @Test
        @DisplayName("Common secret key names are detected (case-insensitive)")
        void detectsSecretKeyNames() {
            assertThat(TriggerConfigRedactor.isSecretKey("password")).isTrue();
            assertThat(TriggerConfigRedactor.isSecretKey("Password")).isTrue();
            assertThat(TriggerConfigRedactor.isSecretKey("PASSWORD")).isTrue();
            assertThat(TriggerConfigRedactor.isSecretKey("api_key")).isTrue();
            assertThat(TriggerConfigRedactor.isSecretKey("apiKey")).isTrue();
            assertThat(TriggerConfigRedactor.isSecretKey("client_secret")).isTrue();
            assertThat(TriggerConfigRedactor.isSecretKey("refresh_token")).isTrue();
            assertThat(TriggerConfigRedactor.isSecretKey("access_token")).isTrue();
            assertThat(TriggerConfigRedactor.isSecretKey("jwt_signing_key")).isTrue();
            assertThat(TriggerConfigRedactor.isSecretKey("private_key")).isTrue();
            assertThat(TriggerConfigRedactor.isSecretKey("authValue")).isTrue();
            assertThat(TriggerConfigRedactor.isSecretKey("oauth_credentials")).isTrue();
        }

        @Test
        @DisplayName("Non-secret key names are not flagged")
        void leavesNonSecretsAlone() {
            assertThat(TriggerConfigRedactor.isSecretKey("name")).isFalse();
            assertThat(TriggerConfigRedactor.isSecretKey("description")).isFalse();
            assertThat(TriggerConfigRedactor.isSecretKey("workflowId")).isFalse();
            assertThat(TriggerConfigRedactor.isSecretKey("httpMethod")).isFalse();
            assertThat(TriggerConfigRedactor.isSecretKey("authType")).isFalse();
            assertThat(TriggerConfigRedactor.isSecretKey("createdAt")).isFalse();
            assertThat(TriggerConfigRedactor.isSecretKey("isActive")).isFalse();
        }

        @Test
        @DisplayName("Null and empty inputs do not throw and are not secrets")
        void nullAndEmptyAreSafe() {
            assertThat(TriggerConfigRedactor.isSecretKey(null)).isFalse();
            assertThat(TriggerConfigRedactor.isSecretKey("")).isFalse();
        }
    }

    @Nested
    @DisplayName("redact (Map<String, Object>)")
    class RedactObjectMap {

        @Test
        @DisplayName("Null input returns null without throwing")
        void nullInputIsPassThrough() {
            assertThat(TriggerConfigRedactor.redact(null)).isNull();
        }

        @Test
        @DisplayName("Empty map returns an empty defensive copy")
        void emptyInputReturnsEmptyCopy() {
            Map<String, Object> input = new LinkedHashMap<>();
            Map<String, Object> output = TriggerConfigRedactor.redact(input);
            assertThat(output).isEmpty();
            // Defensive copy: mutating the output must not affect the input.
            output.put("k", "v");
            assertThat(input).isEmpty();
        }

        @Test
        @DisplayName("Secret-named entries are replaced with REDACTED")
        void redactsSecretEntries() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("name", "My Webhook");
            input.put("password", "hunter2");
            input.put("api_key", "sk-live-xxxxx");

            Map<String, Object> output = TriggerConfigRedactor.redact(input);

            assertThat(output).containsEntry("name", "My Webhook");
            assertThat(output).containsEntry("password", TriggerConfigRedactor.REDACTED);
            assertThat(output).containsEntry("api_key", TriggerConfigRedactor.REDACTED);
        }

        @Test
        @DisplayName("Non-secret values are passed through unchanged")
        void preservesNonSecrets() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("count", 42);
            input.put("enabled", true);
            input.put("description", "A trigger");

            Map<String, Object> output = TriggerConfigRedactor.redact(input);

            assertThat(output).containsEntry("count", 42);
            assertThat(output).containsEntry("enabled", true);
            assertThat(output).containsEntry("description", "A trigger");
        }

        @Test
        @DisplayName("Nested maps are walked recursively")
        void recursesIntoNestedMaps() {
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("username", "admin");
            nested.put("password", "hunter2");

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("authConfig", nested);
            input.put("name", "Webhook");

            Map<String, Object> output = TriggerConfigRedactor.redact(input);

            assertThat(output).containsEntry("name", "Webhook");
            @SuppressWarnings("unchecked")
            Map<String, Object> outNested = (Map<String, Object>) output.get("authConfig");
            assertThat(outNested).containsEntry("username", "admin");
            assertThat(outNested).containsEntry("password", TriggerConfigRedactor.REDACTED);
        }

        @Test
        @DisplayName("A map under a secret-named key is redacted wholesale")
        void wholesaleRedactsSecretKeyedSubtree() {
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("key", "value");

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("oauth_credentials", nested);

            Map<String, Object> output = TriggerConfigRedactor.redact(input);

            // Outer key is a secret → entire sub-tree replaced (don't leak structure).
            assertThat(output).containsEntry("oauth_credentials", TriggerConfigRedactor.REDACTED);
        }

        @Test
        @DisplayName("Lists containing maps are walked recursively")
        void recursesIntoListsOfMaps() {
            Map<String, Object> mapInList1 = new LinkedHashMap<>();
            mapInList1.put("name", "creds-1");
            mapInList1.put("secret", "top-secret-1");

            Map<String, Object> mapInList2 = new LinkedHashMap<>();
            mapInList2.put("name", "creds-2");
            mapInList2.put("api_key", "key-2");

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("entries", Arrays.asList(mapInList1, mapInList2));

            Map<String, Object> output = TriggerConfigRedactor.redact(input);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> outList = (List<Map<String, Object>>) output.get("entries");
            assertThat(outList).hasSize(2);
            assertThat(outList.get(0)).containsEntry("name", "creds-1");
            assertThat(outList.get(0)).containsEntry("secret", TriggerConfigRedactor.REDACTED);
            assertThat(outList.get(1)).containsEntry("name", "creds-2");
            assertThat(outList.get(1)).containsEntry("api_key", TriggerConfigRedactor.REDACTED);
        }

        @Test
        @DisplayName("Empty-string values for secret keys are NOT replaced (avoid noisy '***' for unset fields)")
        void emptyStringSecretsAreLeftAlone() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("password", "");
            input.put("api_key", "");

            Map<String, Object> output = TriggerConfigRedactor.redact(input);

            assertThat(output).containsEntry("password", "");
            assertThat(output).containsEntry("api_key", "");
        }

        @Test
        @DisplayName("Null values for secret keys remain null")
        void nullSecretValuesStayNull() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("password", null);

            Map<String, Object> output = TriggerConfigRedactor.redact(input);

            assertThat(output).containsKey("password");
            assertThat(output.get("password")).isNull();
        }

        @Test
        @DisplayName("Original input map is not mutated")
        void inputIsNotMutated() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("password", "hunter2");
            input.put("name", "Webhook");

            TriggerConfigRedactor.redact(input);

            // Input is unchanged after redaction call.
            assertThat(input).containsEntry("password", "hunter2");
            assertThat(input).containsEntry("name", "Webhook");
        }

        @Test
        @DisplayName("Self-referencing map does NOT StackOverflowError; cycle is short-circuited")
        void selfReferencingMapDoesNotOverflow() {
            // External libs can produce maps that point back to themselves
            // (e.g. JSON parsers with reference resolvers). The redactor must
            // not recurse infinitely.
            Map<String, Object> cyclic = new LinkedHashMap<>();
            cyclic.put("name", "self-loop");
            cyclic.put("self", cyclic);  // <-- direct cycle

            Map<String, Object> output = TriggerConfigRedactor.redact(cyclic);

            assertThat(output).containsEntry("name", "self-loop");
            assertThat(output).containsEntry("self", TriggerConfigRedactor.CYCLE_MARKER);
        }

        @Test
        @DisplayName("Mutually-recursive maps are short-circuited at the second visit")
        void mutuallyRecursiveMapsAreShortCircuited() {
            Map<String, Object> a = new LinkedHashMap<>();
            Map<String, Object> b = new LinkedHashMap<>();
            a.put("name", "a");
            a.put("toB", b);
            b.put("name", "b");
            b.put("toA", a);  // <-- forms an a→b→a cycle

            Map<String, Object> output = TriggerConfigRedactor.redact(a);

            assertThat(output).containsEntry("name", "a");
            @SuppressWarnings("unchecked")
            Map<String, Object> bCopy = (Map<String, Object>) output.get("toB");
            assertThat(bCopy).containsEntry("name", "b");
            // The back-edge to `a` is the cycle - gets the marker.
            assertThat(bCopy).containsEntry("toA", TriggerConfigRedactor.CYCLE_MARKER);
        }

        @Test
        @DisplayName("List that contains itself is short-circuited")
        void selfReferencingListDoesNotOverflow() {
            java.util.List<Object> selfList = new java.util.ArrayList<>();
            selfList.add("first");
            selfList.add(selfList);  // <-- list contains itself

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("items", selfList);

            Map<String, Object> output = TriggerConfigRedactor.redact(input);

            @SuppressWarnings("unchecked")
            java.util.List<Object> outList = (java.util.List<Object>) output.get("items");
            assertThat(outList.get(0)).isEqualTo("first");
            assertThat(outList.get(1)).isEqualTo(TriggerConfigRedactor.CYCLE_MARKER);
        }
    }

    @Nested
    @DisplayName("redactStringMap (Map<String, String>)")
    class RedactStringMap {

        @Test
        @DisplayName("Null input returns null")
        void nullInputIsPassThrough() {
            assertThat(TriggerConfigRedactor.redactStringMap(null)).isNull();
        }

        @Test
        @DisplayName("Secret entries replaced; others preserved")
        void redactsSecretEntries() {
            // Realistic webhook authConfig key shapes (camelCase + snake_case).
            Map<String, String> input = new LinkedHashMap<>();
            input.put("username", "admin");
            input.put("password", "hunter2");
            input.put("jwtSecretKey", "sk-live-xxxxx");
            input.put("refreshToken", "rt-yyy");
            input.put("headerName", "X-Custom-Header");
            input.put("authHeaderName", "X-Webhook-Secret");
            input.put("authHeaderValue", "webhook-secret-value");
            input.put("headerValue", "legacy-header-secret");

            Map<String, String> output = TriggerConfigRedactor.redactStringMap(input);

            assertThat(output).containsEntry("username", "admin");
            assertThat(output).containsEntry("password", TriggerConfigRedactor.REDACTED);
            assertThat(output).containsEntry("jwtSecretKey", TriggerConfigRedactor.REDACTED);
            assertThat(output).containsEntry("refreshToken", TriggerConfigRedactor.REDACTED);
            // "headerName" is just a header NAME (not its value) - public, not secret.
            assertThat(output).containsEntry("headerName", "X-Custom-Header");
            assertThat(output).containsEntry("authHeaderName", "X-Webhook-Secret");
            assertThat(output).containsEntry("authHeaderValue", TriggerConfigRedactor.REDACTED);
            assertThat(output).containsEntry("headerValue", TriggerConfigRedactor.REDACTED);
        }

        @Test
        @DisplayName("Empty-string secret values stay empty (not replaced)")
        void emptyStringSecretsAreLeftAlone() {
            Map<String, String> input = new LinkedHashMap<>();
            input.put("password", "");

            Map<String, String> output = TriggerConfigRedactor.redactStringMap(input);

            assertThat(output).containsEntry("password", "");
        }

        @Test
        @DisplayName("Defensive copy: mutating output does not change input")
        void outputIsDefensiveCopy() {
            Map<String, String> input = new LinkedHashMap<>();
            input.put("name", "trigger");
            Map<String, String> output = TriggerConfigRedactor.redactStringMap(input);

            output.put("added", "after");

            assertThat(input).doesNotContainKey("added");
        }
    }
}

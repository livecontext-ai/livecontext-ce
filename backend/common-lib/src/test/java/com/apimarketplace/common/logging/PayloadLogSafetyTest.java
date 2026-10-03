package com.apimarketplace.common.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the shared payload-redaction helpers introduced with LC-009 / LC-010
 * (security audit 2026-08-13).
 *
 * The payloads below are shaped like Gmail data because that is the case the rule exists for:
 * under Google's Limited Use requirements, restricted-scope content must not be written to a
 * log store that operators can read.
 */
@DisplayName("PayloadLogSafety")
class PayloadLogSafetyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET_BODY =
            "the wire transfer of 45000 EUR is approved, account BE71 0961 2345 6769";

    @Nested
    @DisplayName("describeKeys(Map)")
    class DescribeKeysMap {

        @Test
        @DisplayName("names the keys and never the values")
        void namesKeysOnly() {
            String described = PayloadLogSafety.describeKeys(Map.of(
                    "query", "from:ceo@acme.example",
                    "body", SECRET_BODY));

            assertThat(described).contains("query", "body", "2 keys");
            assertThat(described).doesNotContain(SECRET_BODY);
            assertThat(described).doesNotContain("ceo@acme.example");
        }

        @Test
        @DisplayName("sorts the keys so the same call always yields the same line")
        void sortsKeys() {
            Map<String, Object> unordered = new LinkedHashMap<>();
            unordered.put("zeta", 1);
            unordered.put("alpha", 2);

            assertThat(PayloadLogSafety.describeKeys(unordered)).isEqualTo("{alpha,zeta}(2 keys)");
        }

        @Test
        @DisplayName("handles null and empty without throwing")
        void handlesEdges() {
            assertThat(PayloadLogSafety.describeKeys((Map<String, ?>) null)).isEqualTo("null");
            assertThat(PayloadLogSafety.describeKeys(Map.of())).isEqualTo("{}");
        }
    }

    @Nested
    @DisplayName("describeKeys(JsonNode)")
    class DescribeKeysJson {

        @Test
        @DisplayName("names the fields of an object and never their values")
        void namesFieldsOnly() throws Exception {
            var node = MAPPER.readTree("{\"query\":\"from:ceo@acme.example\",\"body\":\"" + SECRET_BODY + "\"}");

            String described = PayloadLogSafety.describeKeys(node);

            assertThat(described).isEqualTo("{body,query}(2 keys)");
            assertThat(described).doesNotContain(SECRET_BODY);
        }

        @Test
        @DisplayName("reduces an array to its kind and length, never its items")
        void describesArray() throws Exception {
            var node = MAPPER.readTree("[{\"body\":\"" + SECRET_BODY + "\"},{\"body\":\"x\"}]");

            String described = PayloadLogSafety.describeKeys(node);

            assertThat(described).isEqualTo("[array:2]");
            assertThat(described).doesNotContain(SECRET_BODY);
        }

        @Test
        @DisplayName("reduces a bare scalar rather than printing it")
        void describesScalar() throws Exception {
            assertThat(PayloadLogSafety.describeKeys(MAPPER.readTree("\"" + SECRET_BODY + "\"")))
                    .isEqualTo("<scalar>");
        }

        @Test
        @DisplayName("handles null, JSON null and empty object")
        void handlesEdges() throws Exception {
            assertThat(PayloadLogSafety.describeKeys((com.fasterxml.jackson.databind.JsonNode) null)).isEqualTo("null");
            assertThat(PayloadLogSafety.describeKeys(MAPPER.readTree("null"))).isEqualTo("null");
            assertThat(PayloadLogSafety.describeKeys(MAPPER.readTree("{}"))).isEqualTo("{}");
        }
    }

    @Nested
    @DisplayName("describeSize")
    class DescribeSize {

        @Test
        @DisplayName("reports the length instead of the content")
        void reportsLength() {
            assertThat(PayloadLogSafety.describeSize("abcde")).isEqualTo("<5 chars>");
        }

        @Test
        @DisplayName("treats a null and the serialized string \"null\" alike")
        void handlesNulls() {
            assertThat(PayloadLogSafety.describeSize((String) null)).isEqualTo("<null>");
            assertThat(PayloadLogSafety.describeSize("null")).isEqualTo("<null>");
        }

        @Test
        @DisplayName("describeText reports only the size by default and the capped text only when enabled")
        void describeTextFollowsTheSwitch() {
            String body = "{\"contents\":[{\"text\":\"Hi Alice, wire 45000 EUR\"}]}";
            try {
                PayloadLogSafety.setPayloadLoggingEnabled(false);
                assertThat(PayloadLogSafety.describeText(body, 10)).isEqualTo("<" + body.length() + " chars>");
                assertThat(PayloadLogSafety.describeText(null, 10)).isEqualTo("<null>");

                PayloadLogSafety.setPayloadLoggingEnabled(true);
                assertThat(PayloadLogSafety.describeText(body, 10)).startsWith(body.substring(0, 10)).contains("chars]");
            } finally {
                PayloadLogSafety.setPayloadLoggingEnabled(false);
            }
        }

        @Test
        @DisplayName("does not serialize a null payload at all")
        void skipsSerializationWhenNull() {
            AtomicInteger calls = new AtomicInteger();

            String described = PayloadLogSafety.describeSize(null, payload -> {
                calls.incrementAndGet();
                return "should not happen";
            });

            assertThat(described).isEqualTo("<null>");
            assertThat(calls.get())
                    .as("the redacted path must not pay to render a payload it throws away")
                    .isZero();
        }

        @Test
        @DisplayName("serializes exactly once when the payload is present")
        void serializesOnce() {
            AtomicInteger calls = new AtomicInteger();

            String described = PayloadLogSafety.describeSize(
                    List.of("a", "b"),
                    payload -> {
                        calls.incrementAndGet();
                        return "[\"a\",\"b\"]";
                    });

            assertThat(described).isEqualTo("<9 chars>");
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("describeUrl")
    class DescribeUrl {

        @Test
        @DisplayName("drops the query, which is where the user's data travels")
        void dropsQuery() {
            String described = PayloadLogSafety.describeUrl(
                    "https://gmail.googleapis.test/gmail/v1/users/me/messages?q=" + SECRET_BODY);

            assertThat(described).isEqualTo("https://gmail.googleapis.test/<redacted>");
            assertThat(described).doesNotContain(SECRET_BODY);
        }

        @Test
        @DisplayName("drops the PATH as well, because credentials are interpolated into it")
        void dropsPath() {
            // alchemy.json / ankr.json declare "path": "/v2/{apiKey}". A helper that only cut at
            // the '?' would log the user's real API key verbatim, since there is no query at all.
            String described = PayloadLogSafety.describeUrl(
                    "https://eth-mainnet.g.alchemy.test/v2/SECRET_USER_API_KEY");

            assertThat(described).isEqualTo("https://eth-mainnet.g.alchemy.test/<redacted>");
            assertThat(described).doesNotContain("SECRET_USER_API_KEY");
        }

        @Test
        @DisplayName("keeps the origin, which is which third party we called")
        void keepsOrigin() {
            assertThat(PayloadLogSafety.describeUrl("https://api.example"))
                    .isEqualTo("https://api.example");
            assertThat(PayloadLogSafety.describeUrl("https://api.example:8443/v1/things"))
                    .isEqualTo("https://api.example:8443/<redacted>");
        }

        @Test
        @DisplayName("drops a fragment too")
        void dropsFragment() {
            assertThat(PayloadLogSafety.describeUrl("https://api.example/v1#" + SECRET_BODY))
                    .isEqualTo("https://api.example/<redacted>");
        }

        @Test
        @DisplayName("survives characters java.net.URI would reject, without losing the host")
        void survivesUnencodedCharacters() {
            // A real outbound query can contain an unencoded space. Strict URI parsing throws
            // there, which would have degraded every such call to "no idea", host included.
            String described = PayloadLogSafety.describeUrl(
                    "https://gmail.googleapis.test/v1/messages?q=" + SECRET_BODY);

            assertThat(described).isEqualTo("https://gmail.googleapis.test/<redacted>");
        }

        @Test
        @DisplayName("says nothing about a value it cannot read, rather than echoing it")
        void handlesUnparseable() {
            assertThat(PayloadLogSafety.describeUrl("ht tp://" + SECRET_BODY))
                    .isEqualTo("<relative url>");
            assertThat(PayloadLogSafety.describeUrl("/relative/" + SECRET_BODY))
                    .isEqualTo("<relative url>");
            assertThat(PayloadLogSafety.describeUrl("://" + SECRET_BODY))
                    .isEqualTo("<relative url>");
        }

        @Test
        @DisplayName("strips userinfo, which is a credential and a phishing display trick")
        void stripsUserInfo() {
            assertThat(PayloadLogSafety.describeUrl("https://user:s3cret@api.example/v1"))
                    .isEqualTo("https://api.example/<redacted>");
        }

        @Test
        @DisplayName("handles null")
        void handlesNull() {
            assertThat(PayloadLogSafety.describeUrl(null)).isEqualTo("null");
        }
    }

    @Nested
    @DisplayName("describeAny")
    class DescribeAny {

        @Test
        @DisplayName("names a map's keys without its values")
        void describesMap() {
            String described = PayloadLogSafety.describeAny(Map.of("body", SECRET_BODY, "id", 1));

            assertThat(described).isEqualTo("{body,id}(2 keys)");
            assertThat(described).doesNotContain(SECRET_BODY);
        }

        @Test
        @DisplayName("does not blow up on a non-String-keyed map, which would break the caller")
        void toleratesNonStringKeys() {
            // A ClassCastException raised inside a log-argument expression would propagate into
            // the request path, turning a logging helper into an outage.
            Map<Integer, String> numericKeys = Map.of(1, SECRET_BODY);

            String described = PayloadLogSafety.describeAny(numericKeys);

            assertThat(described).isEqualTo("{1}(1 keys)");
            assertThat(described).doesNotContain(SECRET_BODY);
        }

        @Test
        @DisplayName("reduces a collection to its size")
        void describesCollection() {
            assertThat(PayloadLogSafety.describeAny(List.of(SECRET_BODY, "x")))
                    .isEqualTo("[collection:2]");
        }

        @Test
        @DisplayName("reports the TYPE of an unknown object, never its toString")
        void describesUnknownByType() {
            Object opaque = new Object() {
                @Override
                public String toString() {
                    return SECRET_BODY;
                }
            };

            assertThat(PayloadLogSafety.describeAny(opaque)).doesNotContain(SECRET_BODY);
        }

        @Test
        @DisplayName("handles null and JSON nodes")
        void handlesEdges() throws Exception {
            assertThat(PayloadLogSafety.describeAny(null)).isEqualTo("null");
            assertThat(PayloadLogSafety.describeAny(MAPPER.readTree("{\"a\":1}")))
                    .isEqualTo("{a}(1 keys)");
        }
    }

    @Nested
    @DisplayName("describeEndpoint")
    class DescribeEndpoint {

        @Test
        @DisplayName("names the endpoint from the template, never from the substituted URL")
        void usesTheTemplate() {
            // The real defect this exists for: catalog endpoints interpolate values into the
            // PATH, so a substituted URL can carry a user id, a message id, or an API key.
            String described = PayloadLogSafety.describeEndpoint(
                    "https://eth-mainnet.g.alchemy.test/v2/SECRET_USER_API_KEY", "/v2/{apiKey}");

            assertThat(described).isEqualTo("https://eth-mainnet.g.alchemy.test/v2/{apiKey}");
            assertThat(described).doesNotContain("SECRET_USER_API_KEY");
        }

        @Test
        @DisplayName("keeps the placeholders, which name the operation without any user value")
        void keepsPlaceholders() {
            assertThat(PayloadLogSafety.describeEndpoint(
                    "https://gmail.test/users/alice@example.com/messages/18f3c9d2", "/users/{userId}/messages/{id}"))
                    .isEqualTo("https://gmail.test/users/{userId}/messages/{id}");
        }

        @Test
        @DisplayName("handles missing inputs without inventing a host")
        void handlesEdges() {
            assertThat(PayloadLogSafety.describeEndpoint(null, null)).isEqualTo("<unknown endpoint>");
            assertThat(PayloadLogSafety.describeEndpoint("https://api.test", "")).isEqualTo("https://api.test");
        }
    }

    @Nested
    @DisplayName("capMessage")
    class CapMessage {

        @Test
        @DisplayName("keeps a short message whole")
        void keepsShortMessage() {
            assertThat(PayloadLogSafety.capMessage("tool not found", 200)).isEqualTo("tool not found");
        }

        @Test
        @DisplayName("caps a long message and says how much was dropped")
        void capsLongMessage() {
            String provider = "x".repeat(500);

            String capped = PayloadLogSafety.capMessage(provider, 200);

            assertThat(capped).hasSize(200 + "...[+300 chars]".length());
            assertThat(capped).startsWith("x".repeat(200));
            assertThat(capped).endsWith("...[+300 chars]");
        }

        @Test
        @DisplayName("passes null through rather than printing the string \"null\"")
        void handlesNull() {
            assertThat(PayloadLogSafety.capMessage(null, 200)).isNull();
        }
    }
}

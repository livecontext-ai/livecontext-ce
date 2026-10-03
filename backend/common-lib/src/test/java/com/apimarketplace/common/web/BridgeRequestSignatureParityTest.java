package com.apimarketplace.common.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Java body-bound bridge signature to the golden values the Node bridge verifies against
 * ({@code shared/contracts/bridge-signature-fixtures.json}, also checked by
 * {@code mcp/bridge/test/bridgeSignature.test.mjs}). A drift on either side means every dispatch
 * 401s on the bridge once {@code BRIDGE_REQUIRE_SIGNATURE_V2=true}.
 */
@DisplayName("BridgeRequestSignature cross-language parity")
class BridgeRequestSignatureParityTest {

    @Test
    @DisplayName("every fixture case reproduces the golden br2_ signature")
    void fixturesMatch() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(locate("shared/contracts/bridge-signature-fixtures.json")));
        String secret = root.get("secretKey").asText();
        assertThat(root.get("cases").size()).isGreaterThanOrEqualTo(3);
        for (JsonNode c : root.get("cases")) {
            Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            c.get("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
            String signature = BridgeRequestSignature.sign(secret, c.get("method").asText(), c.get("path").asText(),
                    c.get("query").asText(), name -> headers.containsKey(name) ? List.of(headers.get(name)) : null,
                    c.get("timestamp").asText(), c.get("body").asText().getBytes(StandardCharsets.UTF_8));
            assertThat(signature).as(c.get("name").asText()).isEqualTo(c.get("expectedSignature").asText());
        }
    }

    @Test
    @DisplayName("changing one body byte changes the signature (the body is really bound)")
    void bodyIsBound() {
        String a = BridgeRequestSignature.sign("k", "POST", "/api/bridge/execute", null, n -> null, "1", "{\"a\":1}".getBytes());
        String b = BridgeRequestSignature.sign("k", "POST", "/api/bridge/execute", null, n -> null, "1", "{\"a\":2}".getBytes());
        assertThat(a).startsWith("br2_").isNotEqualTo(b);
    }

    static Path locate(String relative) {
        Path here = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && here != null; i++) {
            Path candidate = here.resolve(relative);
            if (Files.exists(candidate)) return candidate;
            here = here.getParent();
        }
        throw new IllegalStateException(relative + " not found");
    }
}

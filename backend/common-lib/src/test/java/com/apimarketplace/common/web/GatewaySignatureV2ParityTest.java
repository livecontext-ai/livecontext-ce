package com.apimarketplace.common.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-language parity for the v2 gateway signature (CASA LC-035): {@link GatewaySignatureV2}
 * must reproduce every golden in the {@code v2Cases} section of
 * {@code shared/contracts/gateway-signature-fixtures.json}, the same goldens the Node twin
 * ({@code mcp/bridge/lib/gatewayAuth.mjs}) is checked against.
 */
@DisplayName("Gateway signature v2 parity (cross-language fixture)")
class GatewaySignatureV2ParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record V2Case(String name, String secret, String method, String path, String query,
                  Map<String, List<String>> headers, String timestamp, String expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static JsonNode fixture() throws IOException {
        Path here = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && here != null; i++) {
            Path candidate = here.resolve("shared/contracts/gateway-signature-fixtures.json");
            if (Files.exists(candidate)) {
                return MAPPER.readTree(Files.newBufferedReader(candidate));
            }
            here = here.getParent();
        }
        throw new IllegalStateException("gateway-signature-fixtures.json not found");
    }

    static Stream<V2Case> cases() throws IOException {
        JsonNode root = fixture();
        List<V2Case> out = new ArrayList<>();
        for (JsonNode c : root.get("v2Cases")) {
            Map<String, List<String>> headers = new HashMap<>();
            c.get("headers").fields().forEachRemaining(e -> {
                List<String> values = new ArrayList<>();
                if (e.getValue().isArray()) {
                    e.getValue().forEach(v -> values.add(v.asText()));
                } else {
                    values.add(e.getValue().asText());
                }
                headers.put(e.getKey().toLowerCase(Locale.ROOT), values);
            });
            out.add(new V2Case(c.get("name").asText(), root.get("secretKey").asText(),
                    c.get("method").asText(), c.get("path").asText(), c.get("query").asText(),
                    headers, c.get("timestamp").asText(), c.get("expectedSignature").asText()));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("GatewaySignatureV2.sign reproduces the JS golden signature")
    void parity(V2Case c) {
        String actual = GatewaySignatureV2.sign(c.secret(), c.method(), c.path(), c.query(),
                name -> c.headers().get(name.toLowerCase(Locale.ROOT)), c.timestamp());
        assertThat(actual).isEqualTo(c.expected());
    }

    @Test
    @DisplayName("a | inside a field cannot make two different identities sign the same (the v1 flaw)")
    void separatorInsideAFieldDoesNotCollide() throws IOException {
        Map<String, String> byName = new HashMap<>();
        fixture().get("v2Cases").forEach(c -> byName.put(c.get("name").asText(), c.get("expectedSignature").asText()));
        assertThat(byName.get("separator in a field: provider \"a|b\", user \"c\""))
                .isNotEqualTo(byName.get("separator in a field: provider \"a\", user \"b|c\""));
        assertThat(byName.get("gateway-forwarded JWT user, GET with query"))
                .as("stripping the roles header must change the signature")
                .isNotEqualTo(byName.get("same request, roles stripped (must differ)"));
    }
}

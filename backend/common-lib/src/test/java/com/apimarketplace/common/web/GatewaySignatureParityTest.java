package com.apimarketplace.common.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-language parity test for the gateway HMAC signature.
 *
 * <p>Reads {@code shared/contracts/gateway-signature-fixtures.json} - the SAME fixture
 * consumed by the JS twin {@code mcp/bridge/lib/__tests__/gatewayAuth.test.mjs}. Asserts
 * that {@link GatewayAuthenticationFilter#generateExpectedSecret} (the value the filter
 * compares incoming requests against) reproduces each fixture's golden signature.</p>
 *
 * <p>This is what makes the bridge's {@code lib/gatewayAuth.mjs} signer trustworthy:
 * if its output matches the golden (proven by the JS twin) AND the filter's expected
 * value matches the same golden (proven here), then a request the bridge signs is
 * byte-identical to what the filter expects → it is accepted. If either implementation
 * drifts, its runner fails against the shared fixture.</p>
 */
@DisplayName("Gateway signature parity (cross-language fixture)")
class GatewaySignatureParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    record FixtureCase(String name, String secretKey, String providerId,
                       String userId, String organizationId, String timestamp,
                       String expectedSignature) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<FixtureCase> cases() throws IOException {
        Path fixturePath = locateFixture();
        JsonNode root = MAPPER.readTree(Files.newBufferedReader(fixturePath));
        String secretKey = root.get("secretKey").asText();
        List<FixtureCase> out = new ArrayList<>();
        for (JsonNode c : root.get("cases")) {
            out.add(new FixtureCase(
                    c.get("name").asText(),
                    secretKey,
                    c.get("providerId").asText(),
                    c.get("userId").asText(),
                    c.get("organizationId").asText(),
                    c.get("timestamp").asText(),
                    c.get("expectedSignature").asText()));
        }
        return out.stream();
    }

    /**
     * The test runs from the common-lib module's working directory; the fixture lives in
     * the repo-root's {@code shared/contracts/}. Walk up until we find it.
     */
    private static Path locateFixture() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path here = cwd;
        for (int i = 0; i < 6; i++) {
            Path candidate = here.resolve("shared/contracts/gateway-signature-fixtures.json");
            if (Files.exists(candidate)) return candidate;
            Path parent = here.getParent();
            if (parent == null) break;
            here = parent;
        }
        throw new IllegalStateException(
                "gateway-signature-fixtures.json not found from " + cwd
                + " - expected at <repo-root>/shared/contracts/gateway-signature-fixtures.json");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("filter.generateExpectedSecret reproduces the JS golden signature")
    void parity(FixtureCase fc) {
        GatewayFilterProperties properties = new GatewayFilterProperties();
        properties.setSecretKey(fc.secretKey());
        properties.setVerificationEnabled(true);
        GatewayAuthenticationFilter filter = new GatewayAuthenticationFilter(properties);

        String actual = filter.generateExpectedSecret(
                fc.providerId(), fc.timestamp(), fc.userId(), fc.organizationId());

        assertThat(actual)
                .as("Java signature must equal the JS golden for case '%s'", fc.name())
                .isEqualTo(fc.expectedSignature());
    }
}

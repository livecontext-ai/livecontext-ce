package com.apimarketplace.auth.config;

import com.apimarketplace.common.web.GatewayAuthenticationFilter;
import com.apimarketplace.common.web.GatewayFilterProperties;
import com.apimarketplace.common.web.GatewaySignatureVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASA: two internal prefixes of auth-service answered any caller inside the cluster.
 *
 * <ul>
 *   <li>{@code /api/internal/variables/}: {@code /bundle?tenantId=} returns the DECRYPTED workflow
 *       variables of any tenant, {@code /set} writes them. Its callers already signed, so it is
 *       gated in this release.</li>
 *   <li>the rest of {@code /api/internal/auth/}: its callers sign from this release, and the gate
 *       is armed one release later through {@code AUTH_INTERNAL_HMAC_REQUIRED_PATH}.</li>
 * </ul>
 *
 * <p>Runs the real {@link GatewayAuthenticationFilter} on auth-service's own YAML, resolving the
 * {@code ${VAR:default}} placeholders the way Spring would for the given environment.
 */
@DisplayName("auth-service: /api/internal/variables/ and /api/internal/auth/ HMAC gates")
class InternalAuthAndVariablesHmacGateTest {

    private static final String SECRET = "internal-gate-test-secret-0123456789";
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([A-Z0-9_]+):([^}]*)}$");

    private final AtomicInteger passed = new AtomicInteger();

    @SuppressWarnings("unchecked")
    private GatewayAuthenticationFilter filter(Map<String, String> env) throws Exception {
        Map<String, Object> cfg = new LinkedHashMap<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            for (Object doc : new Yaml().loadAll(in)) {
                if (doc instanceof Map) {
                    cfg.putAll((Map<String, Object>) doc);
                }
            }
        }
        Map<String, Object> yamlFilter =
                (Map<String, Object>) ((Map<String, Object>) cfg.get("gateway")).get("filter");
        List<String> hmacRequired = ((List<String>) yamlFilter.get("hmac-required-paths")).stream()
                .map(entry -> resolve(entry, env))
                .toList();
        GatewayFilterProperties properties = new GatewayFilterProperties();
        properties.setSecretKey(SECRET);
        properties.setVerificationEnabled(true);
        properties.setPublicPaths((List<String>) yamlFilter.get("public-paths"));
        properties.setHmacRequiredPaths(hmacRequired);
        return new GatewayAuthenticationFilter(properties);
    }

    private static String resolve(String entry, Map<String, String> env) {
        Matcher m = PLACEHOLDER.matcher(entry == null ? "" : entry);
        if (!m.matches()) {
            return entry;
        }
        return env.getOrDefault(m.group(1), m.group(2));
    }

    private int run(GatewayAuthenticationFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> passed.incrementAndGet());
        return response.getStatus();
    }

    private static MockHttpServletRequest unsigned(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("X-User-ID", "42");
        return request;
    }

    private static MockHttpServletRequest signed(String method, String path) {
        String ts = String.valueOf(System.currentTimeMillis());
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("X-Gateway-Secret",
                new GatewaySignatureVerifier(SECRET).expectedSecret("internal", ts, "42", "org-1"));
        request.addHeader("X-Gateway-Timestamp", ts);
        request.addHeader("X-Provider-ID", "internal");
        request.addHeader("X-User-ID", "42");
        request.addHeader("X-Organization-ID", "org-1");
        return request;
    }

    @Nested
    @DisplayName("/api/internal/variables/ (gated now)")
    class Variables {

        @Test
        @DisplayName("an unsigned bundle read (decrypted secrets of any tenant) is refused 401")
        void unsignedBundleRefused() throws Exception {
            MockHttpServletRequest request = unsigned("GET", "/api/internal/variables/bundle");
            request.setQueryString("tenantId=42");

            assertThat(run(filter(Map.of()), request)).isEqualTo(401);
            assertThat(passed.get()).isZero();
        }

        @Test
        @DisplayName("an unsigned write (/set) and listing (/list) are refused 401 as well")
        void unsignedSetAndListRefused() throws Exception {
            GatewayAuthenticationFilter filter = filter(Map.of());

            assertThat(run(filter, unsigned("POST", "/api/internal/variables/set"))).isEqualTo(401);
            assertThat(run(filter, unsigned("GET", "/api/internal/variables/list"))).isEqualTo(401);
            assertThat(passed.get()).isZero();
        }

        @Test
        @DisplayName("a signed bundle read passes")
        void signedBundlePasses() throws Exception {
            run(filter(Map.of()), signed("GET", "/api/internal/variables/bundle"));

            assertThat(passed.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("/api/internal/auth/ (staged)")
    class InternalAuth {

        @Test
        @DisplayName("disarmed (no AUTH_INTERNAL_HMAC_REQUIRED_PATH): an unsigned call still passes, as before")
        void disarmedLeavesTheNamespaceOpen() throws Exception {
            run(filter(Map.of()), unsigned("GET", "/api/internal/auth/plans/limits/WORKFLOW"));

            assertThat(passed.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("armed: an unsigned call is refused 401, the pricing snapshot included")
        void armedRefusesUnsigned() throws Exception {
            GatewayAuthenticationFilter filter =
                    filter(Map.of("AUTH_INTERNAL_HMAC_REQUIRED_PATH", "/api/internal/auth/"));

            assertThat(run(filter, unsigned("GET", "/api/internal/auth/plans/limits/WORKFLOW"))).isEqualTo(401);
            assertThat(run(filter, unsigned("GET", "/api/internal/auth/pricing/snapshot"))).isEqualTo(401);
            assertThat(run(filter, unsigned("GET", "/api/internal/auth/organizations/org-1/member-ids"))).isEqualTo(401);
            assertThat(passed.get()).isZero();
        }

        @Test
        @DisplayName("armed: a signed call passes")
        void armedAcceptsSigned() throws Exception {
            GatewayAuthenticationFilter filter =
                    filter(Map.of("AUTH_INTERNAL_HMAC_REQUIRED_PATH", "/api/internal/auth/"));

            run(filter, signed("GET", "/api/internal/auth/plans/limits/WORKFLOW"));

            assertThat(passed.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("the member-role and workspace sub-prefixes stay gated whether or not the switch is armed")
        void alreadyGatedSubPrefixesStayGated() throws Exception {
            GatewayAuthenticationFilter filter = filter(Map.of());

            assertThat(run(filter, unsigned("GET", "/api/internal/auth/member-role/org-1/42"))).isEqualTo(401);
            assertThat(run(filter, unsigned("POST", "/api/internal/auth/workspace/purge"))).isEqualTo(401);
            assertThat(passed.get()).isZero();
        }
    }
}

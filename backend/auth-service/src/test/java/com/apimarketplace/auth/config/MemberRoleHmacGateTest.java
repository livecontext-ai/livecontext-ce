package com.apimarketplace.auth.config;

import com.apimarketplace.common.web.GatewayAuthenticationFilter;
import com.apimarketplace.common.web.GatewayFilterProperties;
import com.apimarketplace.common.web.GatewaySignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The member-role lookup (gateway WebSocket, per run-driving action) sits in the public
 * {@code /api/internal/} namespace, so it is listed in THIS service's
 * {@code gateway.filter.hmac-required-paths}. Runs the real {@link GatewayAuthenticationFilter}
 * on auth-service's own YAML: unsigned or mis-bound calls are refused 401, a signed call bound
 * to the X-User-ID / X-Organization-ID it sends passes, and the sibling member-ids endpoint
 * (other callers, unsigned) is untouched.
 */
@DisplayName("auth-service: /api/internal/auth/member-role/ is HMAC-required")
class MemberRoleHmacGateTest {

    private static final String SECRET = "member-role-gate-test-secret-0123456789";
    private static final String PATH = "/api/internal/auth/member-role/org-1/42";

    private GatewayAuthenticationFilter filter;
    private final AtomicInteger passed = new AtomicInteger();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
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
        GatewayFilterProperties properties = new GatewayFilterProperties();
        properties.setSecretKey(SECRET);
        properties.setVerificationEnabled(true);
        properties.setPublicPaths((List<String>) yamlFilter.get("public-paths"));
        properties.setHmacRequiredPaths((List<String>) yamlFilter.get("hmac-required-paths"));
        filter = new GatewayAuthenticationFilter(properties);
    }

    private int run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> passed.incrementAndGet());
        return response.getStatus();
    }

    private static MockHttpServletRequest signed(String path, String signUser, String signOrg,
                                                 String sendUser, String sendOrg) {
        String ts = String.valueOf(System.currentTimeMillis());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-Gateway-Secret",
                new GatewaySignatureVerifier(SECRET).expectedSecret("internal", ts, signUser, signOrg));
        request.addHeader("X-Gateway-Timestamp", ts);
        request.addHeader("X-Provider-ID", "internal");
        if (sendUser != null) request.addHeader("X-User-ID", sendUser);
        if (sendOrg != null) request.addHeader("X-Organization-ID", sendOrg);
        return request;
    }

    @Test
    @DisplayName("unsigned call: 401, never reaches the controller")
    void unsignedRefused() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        request.addHeader("X-User-ID", "42");

        assertThat(run(request)).isEqualTo(401);
        assertThat(passed.get()).isZero();
    }

    @Test
    @DisplayName("signed with the org but sent without X-Organization-ID: 401 (the binding the gateway now honours)")
    void orgSignedButNotSentRefused() throws Exception {
        assertThat(run(signed(PATH, "42", "org-1", "42", null))).isEqualTo(401);
        assertThat(passed.get()).isZero();
    }

    @Test
    @DisplayName("signed and sent with the same user and org: passes")
    void boundSignaturePasses() throws Exception {
        run(signed(PATH, "42", "org-1", "42", "org-1"));

        assertThat(passed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the sibling /organizations/{id}/member-ids stays public (its callers do not sign)")
    void memberIdsStaysPublic() throws Exception {
        run(new MockHttpServletRequest("GET", "/api/internal/auth/organizations/org-1/member-ids"));

        assertThat(passed.get()).isEqualTo(1);
    }
}

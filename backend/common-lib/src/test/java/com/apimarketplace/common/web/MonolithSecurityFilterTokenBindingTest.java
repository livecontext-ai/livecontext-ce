package com.apimarketplace.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASA LC-015 (session-bound CE access tokens), LC-083 (iss + aud) and LC-032 (a browser on the
 * same host does not inherit loopback trust) in {@link MonolithSecurityFilter}.
 */
@DisplayName("MonolithSecurityFilter token binding and browser loopback (LC-015, LC-083, LC-032)")
class MonolithSecurityFilterTokenBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long BOOT_MS = 1_800_000_000_000L;
    private static KeyPair keyPair;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    private static MonolithSecurityFilter filter(Set<String> liveSessions) {
        return new MonolithSecurityFilter(keyPair::getPublic, List.of(), null, null, () -> BOOT_MS)
                .withTokenBinding("livecontext", "livecontext-ce", liveSessions::contains);
    }

    private static Map<String, Object> claims(long iatSeconds) {
        Map<String, Object> c = new HashMap<>();
        c.put("sub", "42");
        c.put("userId", 42);
        c.put("token_type", "access");
        c.put("iss", "livecontext");
        c.put("aud", "livecontext-ce");
        c.put("sid", "7");
        c.put("iat", iatSeconds);
        c.put("exp", BOOT_MS / 1000 + 3600);
        return c;
    }

    private static String jwt(Map<String, Object> claims) throws Exception {
        String header = b64(MAPPER.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String payload = b64(MAPPER.writeValueAsBytes(claims));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "." + b64(signature.sign());
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Status and forwarded X-User-ID for a bearer request on a protected path. */
    private static Object[] call(MonolithSecurityFilter filter, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workflows");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, jakarta.servlet.ServletResponse res) {
                captured.set(req);
            }
        });
        String user = captured.get() == null ? null : ((HttpServletRequest) captured.get()).getHeader("X-User-ID");
        return new Object[] {response.getStatus(), user};
    }

    private static final long AFTER_BOOT = BOOT_MS / 1000 + 10;
    private static final long BEFORE_BOOT = BOOT_MS / 1000 - 10;

    @Test
    @DisplayName("a token whose login session is live authenticates")
    void liveSessionAccepted() throws Exception {
        assertThat(call(filter(Set.of("7")), jwt(claims(AFTER_BOOT)))).containsExactly(200, "42");
    }

    @Test
    @DisplayName("LC-015: after logout / password change (session revoked) the access token is refused")
    void revokedSessionRefused() throws Exception {
        assertThat(call(filter(Set.of()), jwt(claims(AFTER_BOOT)))[0]).isEqualTo(401);
    }

    @Test
    @DisplayName("LC-015: fail closed, a session lookup that throws (DB down) answers 401")
    void sessionLookupErrorFailsClosed() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(keyPair::getPublic, List.of(), null, null, () -> BOOT_MS)
                .withTokenBinding("livecontext", "livecontext-ce", sid -> {
                    throw new IllegalStateException("database unavailable");
                });
        assertThat(call(filter, jwt(claims(AFTER_BOOT)))[0]).isEqualTo(401);
    }

    @Test
    @DisplayName("LC-015: a token minted after this boot without a sid is refused")
    void sidlessNewTokenRefused() throws Exception {
        Map<String, Object> c = claims(AFTER_BOOT);
        c.remove("sid");
        assertThat(call(filter(Set.of("7")), jwt(c))[0]).isEqualTo(401);
    }

    @Test
    @DisplayName("upgrade: a token issued by the previous version (no sid, no aud, before boot) still works")
    void legacyTokenStillAccepted() throws Exception {
        Map<String, Object> c = claims(BEFORE_BOOT);
        c.remove("sid");
        c.remove("aud");
        assertThat(call(filter(Set.of()), jwt(c))).containsExactly(200, "42");
    }

    @Test
    @DisplayName("LC-083: a wrong issuer or a wrong audience is refused")
    void issuerAndAudienceEnforced() throws Exception {
        Map<String, Object> wrongIss = claims(AFTER_BOOT);
        wrongIss.put("iss", "someone-else");
        assertThat(call(filter(Set.of("7")), jwt(wrongIss))[0]).isEqualTo(401);

        Map<String, Object> wrongAud = claims(AFTER_BOOT);
        wrongAud.put("aud", List.of("other-install"));
        assertThat(call(filter(Set.of("7")), jwt(wrongAud))[0]).isEqualTo(401);

        Map<String, Object> noAud = claims(AFTER_BOOT);
        noAud.remove("aud");
        assertThat(call(filter(Set.of("7")), jwt(noAud))[0]).isEqualTo(401);
    }

    @Test
    @DisplayName("LC-032: a browser request from the same host does not inherit loopback X-User-ID trust")
    void browserOnLoopbackIsExternal() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        for (String[] marker : new String[][] {
                {"Origin", "https://evil.example"}, {"Sec-Fetch-Site", "cross-site"}, {"Sec-Fetch-Mode", "cors"}}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/storage/quota");
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("X-User-ID", "42");
            // Even WITH the in-process secret, a browser marker means external (defence in depth).
            request.addHeader(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER, MonolithSecurityFilter.inProcessSecret());
            request.addHeader(marker[0], marker[1]);
            AtomicReference<ServletRequest> captured = new AtomicReference<>();
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
                @Override
                public void doFilter(ServletRequest req, jakarta.servlet.ServletResponse res) {
                    captured.set(req);
                }
            });
            assertThat(captured.get()).isNotNull();
            assertThat(((HttpServletRequest) captured.get()).getHeader("X-User-ID"))
                    .as("forged identity must be stripped when %s is present", marker[0])
                    .isNull();
        }
    }

    @Test
    @DisplayName("LC-032: a browser request to an internal path from the same host is 404, like any external call")
    void browserOnLoopbackCannotReachInternalPaths() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of("/api/internal/"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/internal/credentials/all");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-User-ID", "42");
        request.addHeader(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER, MonolithSecurityFilter.inProcessSecret());
        request.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(404);
    }
}

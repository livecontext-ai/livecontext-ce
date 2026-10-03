package com.apimarketplace.auth.credential.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OAuth2ReturnPath (LC-023 open redirect)")
class OAuth2ReturnPathTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/app/settings/credentials",
            "/fr/app/chat/123",
            "/app/workflows/abc?tab=nodes&x=1",
            "/app/a%20b",
            "/"
    })
    @DisplayName("keeps a same-origin path, query included")
    void keepsSafePaths(String path) {
        assertThat(OAuth2ReturnPath.sanitize(path)).isEqualTo(path);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ".evil.tld/x",            // frontendUrl + value = livecontext.ai.evil.tld
            "@evil.tld/x",            // userinfo
            "//evil.tld/x",           // protocol-relative
            "/\\evil.tld/x",          // browsers normalise to //evil.tld
            "https://evil.tld/x",
            "javascript:alert(1)",
            "app/settings",           // no leading slash
            "/app\\evil",
            "/app/x#fragment",
            "/app/\nx",
            "/app/\tx",
            "/app/ x"
    })
    @DisplayName("replaces anything that could leave the origin with the default path")
    void rejectsHostileValues(String hostile) {
        assertThat(OAuth2ReturnPath.sanitize(hostile)).isEqualTo(OAuth2ReturnPath.DEFAULT);
    }

    /*
     * Regression (CASA round 3, ASVS 5.1.5 defence in depth): any same-origin path passed, so an
     * OAuth connect with return_url=/api/... or /webhook/... landed the user on a backend
     * endpoint after the provider callback. Mirrors the frontend safeReturnPath.test.ts cases.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api",
            "/api/users/me",
            "/api/proxy/workflows?page=0",
            "/webhook",
            "/webhook/abc-123",
            "/webhooks/stripe",
            "/webhook-trigger/x",
            "/_next",
            "/_next/static/chunks/main.js",
            "/.well-known/security.txt",
            "/mcp",
            "/mcp/sse",
            "/ws/stream",
            "/approval-callback/telegram",
            "/widget.js",
            "/widget/abc/config",
            // Audit round 2: the prod ingress matches Prefix rules as strings.
            "/api;x",
            "/api.json",
            "/apiary",
            "/wsx",
            "/approval-callbackx",
            // Audit round 2: frontend rewrites and the /chat and /form ingress rules.
            "/chat/abc-123",
            "/chat/",
            "/chat/c",
            "/chatx",
            "/form",
            "/form/abc-123",
            "/formx",
            "/c",
            "/c/conv-1",
            "/share",
            "/share/tok-1",
            "/app/public/app-1/render.html",
            // Re-audit: the cloud ingress takes every /chat*, legacy redirects never run there.
            "/chat",
            "/chat/c/conv-1",
            // Re-audit: the frontend middleware 308-redirects /<locale><rest> to <rest>.
            "/en/api/x",
            "/en/api/proxy/x",
            "/fr/webhook/x",
            "/en/mcp",
            "/de/share/t",
            "/en/form/x",
            "/en/chat/abc",
            "/en/chat",
            "/en/widget/abc",
            "/es/c/conv-1",
            "/zh/_next/static/x.js",
            "/pt/.well-known/security.txt",
            "/en/fr/api",
            "/EN/API/x",
            "/%65n/api/x",
            "/en/%61pi",
            "/en/app/../api/x",
            // Final audit: a // anywhere in the path (a locale-strip redirect brings it to the front).
            "/en//x",
            "/en//2130706433",
            "/en/%2F%2F2130706433",
            "/en/app/x//y",
            // Final audit: still decoding after the last round is refused.
            "/%25252561pi"
    })
    @DisplayName("CASA3 returnto: a non-page target falls back to the default path")
    void rejectsNonPageTargets(String target) {
        assertThat(OAuth2ReturnPath.sanitize(target)).isEqualTo(OAuth2ReturnPath.DEFAULT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/%61pi/x",                       // percent-encoded letter
            "/%41%50%49/x",                   // percent-encoded upper case
            "/%2561pi/x",                     // double percent-encoded
            "/%77ebhook/x",
            "/%2ewell-known/security.txt",
            "/API/",
            "/WebHooks/stripe",
            "/MCP",
            "/./api",
            "/en/../api/x",
            "/en/%2e%2e/api/x",
            "/en/%252e%252e/api/x",
            "/en/%2F%2E%2E/api/x",            // encoded slash and parent segment
            "/en/%5C..%5Capi/x",              // encoded backslashes around a parent segment
            "/api%3Fx=1",                     // encoded query separator
            // Audit round 2: a malformed escape used to stop all decoding (raw form only checked).
            "/%61pi/x%zz",
            "/%61pi%zz",
            "/%2561pi/x%25zz",                // malformed only in the second round
            "/%61pi/%C3x",                    // invalid UTF-8 escape elsewhere
            "/%61%70%69%C3/x"                 // invalid UTF-8 in the same escape run
    })
    @DisplayName("CASA3 returnto: an encoded, cased or dot-segment form of a non-page target is refused")
    void rejectsNonPageBypassEncodings(String target) {
        assertThat(OAuth2ReturnPath.sanitize(target)).isEqualTo(OAuth2ReturnPath.DEFAULT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/en/app/settings/credentials",
            "/en/app/settings/integrations?tab=oauth&provider=gmail",
            "/app/workflows/abc-123/builder?node=mcp:send_email",
            "/fr/app/workflows/abc-123",
            "/en/app/chat",
            "/s/share-token-1",
            "/w/embed",
            "/local-mcp",
            "/docs/mcp-server",
            "/en/app/webhooks",
            "/en/app/c/conv-1?q=/api/x",
            "/en/app/search?next=%2Fapi%2Fproxy",
            "/widget-demo",
            "/widget",
            "/shared",
            "/compare",
            "/contact",
            "/changelog",
            "/f/form-1",
            "/for/agencies",
            "/en/app/public/x",
            "/en/app/publications",
            "/en/app/settings/public-access",
            "/workflows/builder",
            "/en/app/files/100%25off",
            "/en/app/notes/a%zz",
            // Locale paths rendered under the locale, or redirected to a real page.
            "/en",
            "/fr",
            "/en/app/chat",
            "/fr/app/public/app-1/render.html",
            "/en/fr/app/public/x",
            "/de/login",
            "/es/register",
            "/pt/onboarding",
            "/en/s/share-token-1",
            "/en/docs/mcp-server",
            "/en/compare",
            "/en/shared",
            "/fr/for/agencies"
    })
    @DisplayName("CASA3 returnto: page paths (OAuth connect from settings or a workflow) are kept unchanged")
    void keepsPagePaths(String page) {
        assertThat(OAuth2ReturnPath.sanitize(page)).isEqualTo(page);
    }

    @Test
    @DisplayName("null, blank and oversized values fall back to the default")
    void fallsBack() {
        assertThat(OAuth2ReturnPath.sanitize(null)).isEqualTo(OAuth2ReturnPath.DEFAULT);
        assertThat(OAuth2ReturnPath.sanitize("  ")).isEqualTo(OAuth2ReturnPath.DEFAULT);
        assertThat(OAuth2ReturnPath.sanitize("/" + "a".repeat(5000))).isEqualTo(OAuth2ReturnPath.DEFAULT);
    }

    /*
     * Shared parity table: shared/contracts/return-path-fixtures.json is ALSO run against the
     * frontend isSafeReturnPath (safeReturnPath.backendParity.test.ts), so the deny-list, the
     * locale-strip walk and the decoding rounds cannot drift between the two validators.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<String> sharedSafe() throws IOException {
        return fixture("safe");
    }

    static Stream<String> sharedUnsafe() throws IOException {
        return fixture("unsafe");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedSafe")
    @DisplayName("CASA3 returnto parity: keeps every shared-fixture page unchanged")
    void keepsSharedFixturePages(String page) {
        assertThat(OAuth2ReturnPath.sanitize(page)).isEqualTo(page);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sharedUnsafe")
    @DisplayName("CASA3 returnto parity: refuses every shared-fixture target")
    void refusesSharedFixtureTargets(String target) {
        assertThat(OAuth2ReturnPath.sanitize(target)).isEqualTo(OAuth2ReturnPath.DEFAULT);
    }

    private static Stream<String> fixture(String key) throws IOException {
        JsonNode cases = MAPPER.readTree(Files.readString(locateFixture())).get(key);
        List<String> values = new ArrayList<>();
        cases.forEach(node -> values.add(node.asText()));
        return values.stream();
    }

    /** The test runs from the module directory; the fixture lives in the repo root's shared/contracts. */
    private static Path locateFixture() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path here = cwd; here != null; here = here.getParent()) {
            Path candidate = here.resolve("shared/contracts/return-path-fixtures.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("return-path-fixtures.json not found from " + cwd
                + " - expected at <repo-root>/shared/contracts/return-path-fixtures.json");
    }
}

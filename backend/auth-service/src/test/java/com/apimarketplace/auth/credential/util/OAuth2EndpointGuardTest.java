package com.apimarketplace.auth.credential.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OAuth2EndpointGuard (LC-052 BYOK URL SSRF)")
class OAuth2EndpointGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://169.254.169.254/latest/meta-data/",   // cloud metadata
            "https://10.0.9.5/token",                       // private network (cluster)
            "https://127.0.0.1:8083/api/internal/credentials/all",
            "https://localhost/token",
            "https://[::1]/token",
            "https://192.168.1.10/oauth/token"
    })
    @DisplayName("refuses an internal target, with no DNS needed for a literal address")
    void refusesInternalTargets(String url) {
        assertThatThrownBy(() -> OAuth2EndpointGuard.assertSafe(url, "tokenUrl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenUrl");
    }

    @Test
    @DisplayName("refuses plain http: the token request carries the client secret")
    void refusesHttp() {
        assertThatThrownBy(() -> OAuth2EndpointGuard.assertSafe("http://8.8.8.8/token", "tokenUrl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    @DisplayName("accepts a public https endpoint and a per-instance {placeholder} host")
    void acceptsPublicAndTemplated() {
        assertThatCode(() -> OAuth2EndpointGuard.assertSafe("https://8.8.8.8/oauth/token", "tokenUrl"))
                .doesNotThrowAnyException();
        assertThatCode(() -> OAuth2EndpointGuard.assertSafe(
                "https://{shop}.myshopify.com/admin/oauth/access_token", "tokenUrl"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null/blank means 'not configured' and passes")
    void blankIsNotConfigured() {
        assertThatCode(() -> OAuth2EndpointGuard.assertSafe(null, "authUrl")).doesNotThrowAnyException();
        assertThatCode(() -> OAuth2EndpointGuard.assertSafe(" ", "authUrl")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertPublicHost keeps the template's scheme but still refuses an internal host")
    void publicHostOnly() {
        assertThatCode(() -> OAuth2EndpointGuard.assertPublicHostForUse("http://8.8.8.8/token", "tokenUrl"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> OAuth2EndpointGuard.assertPublicHostForUse("https://10.1.2.3/token", "tokenUrl"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("item 10: save tolerates an unresolvable host, USE refuses it (and any leftover placeholder)")
    void useTimeResolves() {
        assertThatCode(() -> OAuth2EndpointGuard.assertSafe("https://token.no-such-host.invalid/t", "tokenUrl"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> OAuth2EndpointGuard.assertSafeForUse("https://token.no-such-host.invalid/t", "tokenUrl"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OAuth2EndpointGuard.assertSafeForUse("https://{shop}.myshopify.com/t", "tokenUrl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("placeholder");
        assertThatThrownBy(() -> OAuth2EndpointGuard.assertSafeForUse("http://8.8.8.8/t", "tokenUrl"))
                .hasMessageContaining("https");
        assertThatCode(() -> OAuth2EndpointGuard.assertSafeForUse("https://8.8.8.8/t", "tokenUrl"))
                .doesNotThrowAnyException();
    }
}

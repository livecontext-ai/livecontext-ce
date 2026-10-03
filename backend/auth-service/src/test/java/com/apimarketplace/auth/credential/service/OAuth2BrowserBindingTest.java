package com.apimarketplace.auth.credential.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OAuth2BrowserBinding (LC-005)")
class OAuth2BrowserBindingTest {

    @Test
    @DisplayName("the value the browser holds matches the hash stored in the state")
    void matchesOwnValue() {
        String value = OAuth2BrowserBinding.newValue();

        assertThat(OAuth2BrowserBinding.matches(OAuth2BrowserBinding.hash(value), value)).isTrue();
    }

    @Test
    @DisplayName("another browser's value, a missing cookie and an unbound state are all refused")
    void refusesEverythingElse() {
        String hash = OAuth2BrowserBinding.hash(OAuth2BrowserBinding.newValue());

        assertThat(OAuth2BrowserBinding.matches(hash, OAuth2BrowserBinding.newValue())).isFalse();
        assertThat(OAuth2BrowserBinding.matches(hash, null)).isFalse();
        assertThat(OAuth2BrowserBinding.matches(hash, "")).isFalse();
        // Fail closed: a blob without a binding (written before the control existed, or by a
        // caller that skipped it) must never complete, whatever the browser presents.
        assertThat(OAuth2BrowserBinding.matches(null, "anything")).isFalse();
        assertThat(OAuth2BrowserBinding.matches("", "anything")).isFalse();
    }

    @Test
    @DisplayName("the stored hash never equals the secret value itself")
    void hashIsNotTheValue() {
        String value = OAuth2BrowserBinding.newValue();

        assertThat(OAuth2BrowserBinding.hash(value)).isNotEqualTo(value);
        assertThat(OAuth2BrowserBinding.newValue()).isNotEqualTo(value);
    }

    @Test
    @DisplayName("one cookie per flow: two states give two cookie names, neither containing the state")
    void cookieNamePerFlow() {
        String a = OAuth2BrowserBinding.cookieName("state-a", false);
        String b = OAuth2BrowserBinding.cookieName("state-b", false);

        assertThat(a).isNotEqualTo(b).startsWith("lc_oauth_").doesNotContain("state-a");
        assertThat(OAuth2BrowserBinding.cookieName("state-a", true)).startsWith("__Host-lc_oauth_");
    }

    @Test
    @DisplayName("https deployment: __Host- cookie, Secure, HttpOnly, SameSite=Lax, Path=/, no Domain")
    void secureCookieAttributes() {
        String header = OAuth2BrowserBinding.setCookieHeader("st", "v", true);

        assertThat(header).startsWith("__Host-lc_oauth_").contains("=v;")
                .contains("Path=/").contains("HttpOnly").contains("SameSite=Lax").contains("Secure")
                .contains("Max-Age=" + OAuth2BrowserBinding.COOKIE_MAX_AGE_SECONDS)
                .doesNotContainIgnoringCase("Domain=");
    }

    @Test
    @DisplayName("plain-http install (local, CE on a LAN address): no Secure, no __Host- prefix")
    void plainCookieAttributes() {
        String header = OAuth2BrowserBinding.setCookieHeader("st", "v", false);

        assertThat(header).startsWith("lc_oauth_").doesNotContain("Secure").contains("HttpOnly");
    }

    @Test
    @DisplayName("the clear header expires the same cookie name immediately")
    void clearCookie() {
        String set = OAuth2BrowserBinding.setCookieHeader("st", "v", true);
        String clear = OAuth2BrowserBinding.clearCookieHeader("st", true);

        assertThat(clear.substring(0, clear.indexOf('='))).isEqualTo(set.substring(0, set.indexOf('=')));
        assertThat(clear).contains("Max-Age=0");
    }

    @Test
    @DisplayName("Secure only when BOTH the app and the callback are https")
    void secureDeploymentNeedsBothEnds() {
        assertThat(OAuth2BrowserBinding.secureDeployment(
                "https://livecontext.ai", "https://livecontext.ai/api/credentials/oauth2/callback")).isTrue();
        assertThat(OAuth2BrowserBinding.secureDeployment(
                "http://localhost:3000", "http://localhost:8080/api/credentials/oauth2/callback")).isFalse();
        assertThat(OAuth2BrowserBinding.secureDeployment(
                "http://192.168.1.50:3000", "https://proxy.example/api/credentials/oauth2/callback")).isFalse();
    }

    @Test
    @DisplayName("LC-089: the state reference is a short one-way hash, stable per state")
    void stateRef() {
        String state = "0f6a3c1e-7b7e-4bd8-9a55-2c0e5d0f4a11";

        assertThat(OAuth2StateRef.of(state)).hasSize(12).isEqualTo(OAuth2StateRef.of(state))
                .doesNotContain("0f6a3c1e");
        assertThat(OAuth2StateRef.of(null)).isEqualTo("none");
    }
}

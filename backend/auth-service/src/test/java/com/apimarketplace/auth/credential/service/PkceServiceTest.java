package com.apimarketplace.auth.credential.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PkceService")
class PkceServiceTest {

    private final PkceService pkce = new PkceService();

    @Test
    @DisplayName("verifier is 43 chars of URL-safe base64 (RFC 7636 §4.1)")
    void verifierHasExpectedShape() {
        PkceService.PkceChallenge c = pkce.generate();
        assertThat(c.verifier()).hasSize(43);
        assertThat(c.verifier()).matches("[A-Za-z0-9_-]+");
        assertThat(c.verifier()).doesNotContain("=");
    }

    @Test
    @DisplayName("challenge = BASE64URL(SHA256(verifier))")
    void challengeMatchesRfc7636() throws Exception {
        PkceService.PkceChallenge c = pkce.generate();
        byte[] sha = MessageDigest.getInstance("SHA-256")
                .digest(c.verifier().getBytes(StandardCharsets.US_ASCII));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(sha);
        assertThat(c.challenge()).isEqualTo(expected);
    }

    @Test
    @DisplayName("method is always S256 - plain is not supported")
    void methodIsS256() {
        assertThat(pkce.generate().method()).isEqualTo("S256");
    }

    @Test
    @DisplayName("successive challenges are unique")
    void challengesAreUnique() {
        PkceService.PkceChallenge c1 = pkce.generate();
        PkceService.PkceChallenge c2 = pkce.generate();
        assertThat(c1.verifier()).isNotEqualTo(c2.verifier());
        assertThat(c1.challenge()).isNotEqualTo(c2.challenge());
    }
}

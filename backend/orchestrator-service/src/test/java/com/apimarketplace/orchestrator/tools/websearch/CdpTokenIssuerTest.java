package com.apimarketplace.orchestrator.tools.websearch;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Tests for the orchestrator-side CDP JWT issuer. The websearch-service
 * verifies these tokens with the same secret using its
 * {@code app/services/browser_agent/cdp_jwt.py} helper; we test the Java
 * side here in isolation by re-verifying with java-jwt.
 */
class CdpTokenIssuerTest {

    private static final String SECRET = "shared-secret-for-cdp-jwt";

    @Test
    @DisplayName("Issues a verifiable HS256 token containing all required claims")
    void issuesVerifiableTokenWithAllClaims() {
        CdpTokenIssuer issuer = new CdpTokenIssuer(SECRET, 300);
        String token = issuer.issue("ses_42", "user_99", "run_x", "node_y");

        assertThat(token).isNotNull();
        assertThat(token.split("\\.")).hasSize(3); // header.payload.signature

        DecodedJWT decoded = JWT.require(Algorithm.HMAC256(SECRET))
                .build()
                .verify(token);

        assertThat(decoded.getClaim("sid").asString()).isEqualTo("ses_42");
        assertThat(decoded.getClaim("sub").asString()).isEqualTo("user_99");
        assertThat(decoded.getClaim("rid").asString()).isEqualTo("run_x");
        assertThat(decoded.getClaim("nid").asString()).isEqualTo("node_y");
        assertThat(decoded.getIssuedAt()).isNotNull();
        assertThat(decoded.getExpiresAt()).isNotNull();
        assertThat(decoded.getExpiresAt().toInstant())
                .isAfter(Instant.now())
                .isBefore(Instant.now().plusSeconds(310));
    }

    @Test
    @DisplayName("Returns null when the secret is empty (configuration guard)")
    void returnsNullWhenSecretEmpty() {
        CdpTokenIssuer issuer = new CdpTokenIssuer("", 300);
        assertThat(issuer.issue("s", "u", "r", "n")).isNull();
        assertThat(issuer.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("isConfigured() is true only when secret is non-blank")
    void isConfiguredOnlyWhenSecretNonBlank() {
        assertThat(new CdpTokenIssuer("", 300).isConfigured()).isFalse();
        assertThat(new CdpTokenIssuer("   ", 300).isConfigured()).isFalse();
        assertThat(new CdpTokenIssuer("x", 300).isConfigured()).isTrue();
    }

    @Test
    @DisplayName("Returns null when required claim is blank")
    void returnsNullWhenRequiredClaimMissing() {
        CdpTokenIssuer issuer = new CdpTokenIssuer(SECRET, 300);
        assertThat(issuer.issue("", "u", "r", "n")).isNull();
        assertThat(issuer.issue("s", "u", "", "n")).isNull();
        assertThat(issuer.issue("s", "u", "r", "")).isNull();
    }

    @Test
    @DisplayName("Token signed with secret_A is rejected when verifying with secret_B")
    void tokenRejectedUnderWrongSecret() {
        String token = new CdpTokenIssuer("secret_A", 300).issue("s", "u", "r", "n");
        assertThat(token).isNotNull();

        Throwable thrown = catchThrowable(() -> JWT.require(Algorithm.HMAC256("secret_B"))
                .build()
                .verify(token));
        assertThat(thrown).isInstanceOf(JWTVerificationException.class);
    }

    @Test
    @DisplayName("ttl_seconds <= 0 is clamped to 1 second so tokens never have a non-positive TTL")
    void ttlClampedToAtLeastOneSecond() {
        CdpTokenIssuer issuer = new CdpTokenIssuer(SECRET, 0);
        String token = issuer.issue("s", "u", "r", "n");
        assertThat(token).isNotNull();
        DecodedJWT decoded = JWT.require(Algorithm.HMAC256(SECRET)).build().verify(token);
        long ttl = decoded.getExpiresAt().toInstant().getEpochSecond()
                - decoded.getIssuedAt().toInstant().getEpochSecond();
        assertThat(ttl).isGreaterThanOrEqualTo(1L);
    }
}

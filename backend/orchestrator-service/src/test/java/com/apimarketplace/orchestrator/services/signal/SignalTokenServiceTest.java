package com.apimarketplace.orchestrator.services.signal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SignalTokenService")
class SignalTokenServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-02-04T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneId.of("UTC"));
    private static final String SECRET = "test-secret-key-for-unit-testing";

    private SignalTokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new SignalTokenService(SECRET, FIXED_CLOCK);
    }

    @Nested
    @DisplayName("generateToken()")
    class GenerateTokenTests {

        @Test
        @DisplayName("Should generate a non-empty base64url token")
        void shouldGenerateToken() {
            Instant expiresAt = FIXED_NOW.plusSeconds(3600);

            String token = tokenService.generateToken("run-1", "core:wait_1", "0", expiresAt);

            assertNotNull(token);
            assertFalse(token.isBlank());
            // Base64url should not contain + or / or =
            assertFalse(token.contains("+"));
            assertFalse(token.contains("/"));
        }

        @Test
        @DisplayName("Should generate different tokens for different inputs")
        void shouldGenerateDifferentTokens() {
            Instant expiresAt = FIXED_NOW.plusSeconds(3600);

            String token1 = tokenService.generateToken("run-1", "core:wait_1", "0", expiresAt);
            String token2 = tokenService.generateToken("run-2", "core:wait_1", "0", expiresAt);

            assertNotEquals(token1, token2);
        }

        @Test
        @DisplayName("Should generate same token for same inputs (deterministic)")
        void shouldGenerateSameTokenForSameInputs() {
            Instant expiresAt = FIXED_NOW.plusSeconds(3600);

            String token1 = tokenService.generateToken("run-1", "core:wait_1", "0", expiresAt);
            String token2 = tokenService.generateToken("run-1", "core:wait_1", "0", expiresAt);

            assertEquals(token1, token2);
        }
    }

    @Nested
    @DisplayName("validateToken()")
    class ValidateTokenTests {

        @Test
        @DisplayName("Should validate a valid token and return claims")
        void shouldValidateValidToken() {
            Instant expiresAt = FIXED_NOW.plusSeconds(3600);
            String token = tokenService.generateToken("run-1", "core:wait_1", "0", expiresAt);

            SignalTokenService.TokenClaims claims = tokenService.validateToken(token);

            assertNotNull(claims);
            assertEquals("run-1", claims.runId());
            assertEquals("core:wait_1", claims.nodeId());
            assertEquals("0", claims.itemId());
            assertEquals(expiresAt, claims.expiresAt());
        }

        @Test
        @DisplayName("Should return null for null token")
        void shouldReturnNullForNullToken() {
            assertNull(tokenService.validateToken(null));
        }

        @Test
        @DisplayName("Should return null for blank token")
        void shouldReturnNullForBlankToken() {
            assertNull(tokenService.validateToken(""));
            assertNull(tokenService.validateToken("   "));
        }

        @Test
        @DisplayName("Should return null for expired token")
        void shouldReturnNullForExpiredToken() {
            Instant expiresAt = FIXED_NOW.minusSeconds(60); // already expired
            String token = tokenService.generateToken("run-1", "core:wait_1", "0", expiresAt);

            assertNull(tokenService.validateToken(token));
        }

        @Test
        @DisplayName("Should return null for tampered token")
        void shouldReturnNullForTamperedToken() {
            Instant expiresAt = FIXED_NOW.plusSeconds(3600);
            String token = tokenService.generateToken("run-1", "core:wait_1", "0", expiresAt);

            // Tamper with the token by changing a character
            String tampered = token.substring(0, token.length() - 1) + "X";

            assertNull(tokenService.validateToken(tampered));
        }

        @Test
        @DisplayName("Should return null for token signed with different secret")
        void shouldReturnNullForWrongSecret() {
            Instant expiresAt = FIXED_NOW.plusSeconds(3600);

            SignalTokenService otherService = new SignalTokenService("different-secret", FIXED_CLOCK);
            String token = otherService.generateToken("run-1", "core:wait_1", "0", expiresAt);

            // Validate with original service (different secret)
            assertNull(tokenService.validateToken(token));
        }

        @Test
        @DisplayName("Should return null for malformed base64 token")
        void shouldReturnNullForMalformedToken() {
            assertNull(tokenService.validateToken("not-a-valid-base64-token!!!"));
        }
    }
}

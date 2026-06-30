package com.apimarketplace.auth.security;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtTokenProvider Tests")
class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    // At least 64 bytes for HS512
    private static final String TEST_SECRET = "ThisIsAVeryLongSecretKeyForTestingPurposesThatMustBeAtLeast64BytesLong!!";
    private static final long ACCESS_TOKEN_EXPIRATION = 3600000L; // 1 hour
    private static final long REFRESH_TOKEN_EXPIRATION = 86400000L; // 24 hours

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "jwtSecret", TEST_SECRET);
        ReflectionTestUtils.setField(jwtTokenProvider, "accessTokenExpirationMs", ACCESS_TOKEN_EXPIRATION);
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshTokenExpirationMs", REFRESH_TOKEN_EXPIRATION);
        ReflectionTestUtils.setField(jwtTokenProvider, "issuer", "livecontext");
    }

    // ===== Helper methods =====

    private User createTestUser(Long id, String username, String email) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setAuthProvider(AuthProvider.KEYCLOAK);
        user.setRoles(Set.of("USER"));
        return user;
    }

    // ===== generateAccessToken =====

    @Nested
    @DisplayName("generateAccessToken")
    class GenerateAccessToken {

        @Test
        @DisplayName("should generate a valid JWT string")
        void shouldGenerateValidJwtString() {
            User user = createTestUser(1L, "testuser", "test@email.com");

            String token = jwtTokenProvider.generateAccessToken(user);

            assertThat(token).isNotNull();
            assertThat(token).isNotEmpty();
            // JWT has 3 parts separated by dots
            assertThat(token.split("\\.")).hasSize(3);
        }

        @Test
        @DisplayName("should fall back to numeric userId subject when providerId is absent")
        void shouldSetCorrectSubject() {
            User user = createTestUser(1L, "myuser", "my@email.com");

            String token = jwtTokenProvider.generateAccessToken(user);
            String subject = jwtTokenProvider.getUsernameFromToken(token);

            // Compatibility fallback for users created before providerId is populated.
            assertThat(subject).isEqualTo("1");
        }

        @Test
        @DisplayName("Should use providerId as subject when present for gateway resolution")
        void shouldUseProviderIdAsSubjectWhenPresent() {
            User user = createTestUser(1L, "myuser", "my@email.com");
            user.setAuthProvider(AuthProvider.LOCAL);
            user.setProviderId("local:my@email.com");

            String token = jwtTokenProvider.generateAccessToken(user);
            String subject = jwtTokenProvider.getUsernameFromToken(token);

            assertThat(subject).isEqualTo("local:my@email.com");
            assertThat(jwtTokenProvider.getUserIdFromToken(token)).isEqualTo(1L);
        }

        @Test
        @DisplayName("should set correct userId in claims")
        void shouldSetCorrectUserId() {
            User user = createTestUser(42L, "testuser", "test@email.com");

            String token = jwtTokenProvider.generateAccessToken(user);
            Long userId = jwtTokenProvider.getUserIdFromToken(token);

            assertThat(userId).isEqualTo(42L);
        }

        @Test
        @DisplayName("should include organization context claims when supplied")
        void shouldIncludeOrganizationContextClaimsWhenSupplied() {
            User user = createTestUser(42L, "testuser", "test@email.com");
            JwtTokenProvider.OrganizationClaims organizationClaims = new JwtTokenProvider.OrganizationClaims(
                    "default-org-uuid",
                    "MEMBER",
                    List.of(
                            new JwtTokenProvider.OrganizationMembershipClaim("default-org-uuid", "MEMBER", true, false),
                            new JwtTokenProvider.OrganizationMembershipClaim("team-org-uuid", "ADMIN", false, false)));

            String token = jwtTokenProvider.generateAccessToken(user, organizationClaims);

            String defaultOrganizationId = jwtTokenProvider.getClaimFromToken(
                    token, claims -> claims.get("defaultOrganizationId", String.class));
            String defaultOrganizationRole = jwtTokenProvider.getClaimFromToken(
                    token, claims -> claims.get("defaultOrganizationRole", String.class));
            Object memberships = jwtTokenProvider.getClaimFromToken(token, claims -> claims.get("memberships"));

            assertThat(defaultOrganizationId).isEqualTo("default-org-uuid");
            assertThat(defaultOrganizationRole).isEqualTo("MEMBER");
            assertThat(memberships).isInstanceOf(List.class);
            List<?> rawMemberships = (List<?>) memberships;
            assertThat(rawMemberships.stream()
                    .map(item -> ((Map<?, ?>) item).get("orgId").toString())
                    .toList())
                    .containsExactly("default-org-uuid", "team-org-uuid");
            @SuppressWarnings("unchecked")
            Map<String, Object> teamMembership = (Map<String, Object>) rawMemberships.get(1);
            assertThat(teamMembership)
                    .containsEntry("role", "ADMIN")
                    .containsEntry("personal", false)
                    .containsEntry("paused", false);
        }

        @Test
        @DisplayName("should set correct provider in claims")
        void shouldSetCorrectProvider() {
            User user = createTestUser(1L, "testuser", "test@email.com");
            user.setAuthProvider(AuthProvider.GOOGLE);

            String token = jwtTokenProvider.generateAccessToken(user);
            String provider = jwtTokenProvider.getProviderFromToken(token);

            assertThat(provider).isEqualTo("google");
        }

        @Test
        @DisplayName("should set expiration date in the future")
        void shouldSetExpirationInFuture() {
            User user = createTestUser(1L, "testuser", "test@email.com");

            String token = jwtTokenProvider.generateAccessToken(user);
            Date expiration = jwtTokenProvider.getExpirationDateFromToken(token);

            assertThat(expiration).isAfter(new Date());
        }

        @Test
        @DisplayName("should set expiration approximately 1 hour from now")
        void shouldSetExpirationApproximatelyOneHour() {
            User user = createTestUser(1L, "testuser", "test@email.com");

            String token = jwtTokenProvider.generateAccessToken(user);
            Date expiration = jwtTokenProvider.getExpirationDateFromToken(token);

            long expectedExpiry = System.currentTimeMillis() + ACCESS_TOKEN_EXPIRATION;
            // Allow 5 seconds tolerance
            assertThat(expiration.getTime()).isBetween(expectedExpiry - 5000, expectedExpiry + 5000);
        }
    }

    // ===== generateRefreshToken =====

    @Nested
    @DisplayName("generateRefreshToken")
    class GenerateRefreshToken {

        @Test
        @DisplayName("should generate a valid JWT string")
        void shouldGenerateValidJwtString() {
            User user = createTestUser(1L, "testuser", "test@email.com");

            String token = jwtTokenProvider.generateRefreshToken(user);

            assertThat(token).isNotNull();
            assertThat(token).isNotEmpty();
            assertThat(token.split("\\.")).hasSize(3);
        }

        @Test
        @DisplayName("should have longer expiration than access token")
        void shouldHaveLongerExpirationThanAccessToken() {
            User user = createTestUser(1L, "testuser", "test@email.com");

            String accessToken = jwtTokenProvider.generateAccessToken(user);
            String refreshToken = jwtTokenProvider.generateRefreshToken(user);

            Date accessExpiration = jwtTokenProvider.getExpirationDateFromToken(accessToken);
            Date refreshExpiration = jwtTokenProvider.getExpirationDateFromToken(refreshToken);

            assertThat(refreshExpiration).isAfter(accessExpiration);
        }

        @Test
        @DisplayName("should contain same subject (userId) as access token")
        void shouldContainSameUsername() {
            User user = createTestUser(1L, "testuser", "test@email.com");

            String accessToken = jwtTokenProvider.generateAccessToken(user);
            String refreshToken = jwtTokenProvider.generateRefreshToken(user);
            String accessSubject = jwtTokenProvider.getUsernameFromToken(accessToken);
            String refreshSubject = jwtTokenProvider.getUsernameFromRefreshToken(refreshToken);

            // Both tokens use the same compatibility subject when providerId is absent.
            assertThat(refreshSubject).isEqualTo(accessSubject);
            assertThat(refreshSubject).isEqualTo("1");
        }
    }

    // ===== validateToken =====

    @Nested
    @DisplayName("validateToken")
    class ValidateToken {

        @Test
        @DisplayName("should return true for valid token with matching user")
        void shouldReturnTrueForValidToken() {
            // Domain users are validated through the numeric userId claim, not the subject.
            User user = createTestUser(1L, "testuser", "test@email.com");
            String token = jwtTokenProvider.generateAccessToken(user);

            Boolean isValid = jwtTokenProvider.validateToken(token, user);

            assertThat(isValid).isTrue();
        }

        @Test
        @DisplayName("should return false when userId does not match")
        void shouldReturnFalseWhenUsernameMismatch() {
            User user = createTestUser(1L, "testuser", "test@email.com");
            User otherUser = createTestUser(2L, "otheruser", "other@email.com");
            String token = jwtTokenProvider.generateAccessToken(user);

            Boolean isValid = jwtTokenProvider.validateToken(token, otherUser);

            assertThat(isValid).isFalse();
        }
    }

    // ===== validateAccessToken =====

    @Nested
    @DisplayName("validateAccessToken")
    class ValidateAccessToken {

        @Test
        @DisplayName("should return true for valid access token")
        void shouldReturnTrueForValidAccessToken() {
            User user = createTestUser(1L, "testuser", "test@email.com");
            String token = jwtTokenProvider.generateAccessToken(user);

            Boolean isValid = jwtTokenProvider.validateAccessToken(token);

            assertThat(isValid).isTrue();
        }

        @Test
        @DisplayName("should return false for invalid token string")
        void shouldReturnFalseForInvalidToken() {
            Boolean isValid = jwtTokenProvider.validateAccessToken("invalid.token.string");

            assertThat(isValid).isFalse();
        }

        @Test
        @DisplayName("should return false for expired token")
        void shouldReturnFalseForExpiredToken() {
            // Create provider with very short expiration
            JwtTokenProvider shortExpProvider = new JwtTokenProvider();
            ReflectionTestUtils.setField(shortExpProvider, "jwtSecret", TEST_SECRET);
            ReflectionTestUtils.setField(shortExpProvider, "accessTokenExpirationMs", -1000L); // Already expired
            ReflectionTestUtils.setField(shortExpProvider, "refreshTokenExpirationMs", -1000L);
            ReflectionTestUtils.setField(shortExpProvider, "issuer", "livecontext");

            User user = createTestUser(1L, "testuser", "test@email.com");
            String token = shortExpProvider.generateAccessToken(user);

            Boolean isValid = jwtTokenProvider.validateAccessToken(token);

            assertThat(isValid).isFalse();
        }
    }

    // ===== validateRefreshToken =====

    @Nested
    @DisplayName("validateRefreshToken")
    class ValidateRefreshToken {

        @Test
        @DisplayName("should return true for valid refresh token")
        void shouldReturnTrueForValidRefreshToken() {
            User user = createTestUser(1L, "testuser", "test@email.com");
            String token = jwtTokenProvider.generateRefreshToken(user);

            Boolean isValid = jwtTokenProvider.validateRefreshToken(token);

            assertThat(isValid).isTrue();
        }

        @Test
        @DisplayName("should return false for invalid refresh token")
        void shouldReturnFalseForInvalidRefreshToken() {
            Boolean isValid = jwtTokenProvider.validateRefreshToken("invalid.token.data");

            assertThat(isValid).isFalse();
        }
    }

    // ===== getUsernameFromToken =====

    @Nested
    @DisplayName("getUsernameFromToken")
    class GetUsernameFromToken {

        @Test
        @DisplayName("should extract compatibility subject when providerId is absent")
        void shouldExtractCorrectUsername() {
            User user = createTestUser(1L, "extracted_user", "test@email.com");
            String token = jwtTokenProvider.generateAccessToken(user);

            String subject = jwtTokenProvider.getUsernameFromToken(token);

            // Compatibility fallback uses user ID when providerId is absent.
            assertThat(subject).isEqualTo("1");
        }

        @Test
        @DisplayName("should throw exception for invalid token")
        void shouldThrowExceptionForInvalidToken() {
            assertThatThrownBy(() -> jwtTokenProvider.getUsernameFromToken("bad.token"))
                    .isInstanceOf(Exception.class);
        }
    }

    // ===== getUserIdFromToken =====

    @Nested
    @DisplayName("getUserIdFromToken")
    class GetUserIdFromToken {

        @Test
        @DisplayName("should extract correct user ID")
        void shouldExtractCorrectUserId() {
            User user = createTestUser(99L, "testuser", "test@email.com");
            String token = jwtTokenProvider.generateAccessToken(user);

            Long userId = jwtTokenProvider.getUserIdFromToken(token);

            assertThat(userId).isEqualTo(99L);
        }
    }

    // ===== getProviderFromToken =====

    @Nested
    @DisplayName("getProviderFromToken")
    class GetProviderFromToken {

        @Test
        @DisplayName("should extract KEYCLOAK provider")
        void shouldExtractKeycloakProvider() {
            User user = createTestUser(1L, "testuser", "test@email.com");
            user.setAuthProvider(AuthProvider.KEYCLOAK);
            String token = jwtTokenProvider.generateAccessToken(user);

            String provider = jwtTokenProvider.getProviderFromToken(token);

            assertThat(provider).isEqualTo("keycloak");
        }

        @Test
        @DisplayName("should extract GOOGLE provider")
        void shouldExtractGoogleProvider() {
            User user = createTestUser(1L, "testuser", "test@email.com");
            user.setAuthProvider(AuthProvider.GOOGLE);
            String token = jwtTokenProvider.generateAccessToken(user);

            String provider = jwtTokenProvider.getProviderFromToken(token);

            assertThat(provider).isEqualTo("google");
        }

        @Test
        @DisplayName("should extract GITHUB provider")
        void shouldExtractGithubProvider() {
            User user = createTestUser(1L, "testuser", "test@email.com");
            user.setAuthProvider(AuthProvider.GITHUB);
            String token = jwtTokenProvider.generateAccessToken(user);

            String provider = jwtTokenProvider.getProviderFromToken(token);

            assertThat(provider).isEqualTo("github");
        }
    }

    // ===== Token uniqueness =====

    @Nested
    @DisplayName("Token uniqueness")
    class TokenUniqueness {

        @Test
        @DisplayName("should generate different tokens for different users")
        void shouldGenerateDifferentTokensForDifferentUsers() {
            User user1 = createTestUser(1L, "user1", "user1@email.com");
            User user2 = createTestUser(2L, "user2", "user2@email.com");

            String token1 = jwtTokenProvider.generateAccessToken(user1);
            String token2 = jwtTokenProvider.generateAccessToken(user2);

            assertThat(token1).isNotEqualTo(token2);
        }

        @Test
        @DisplayName("should generate different access and refresh tokens for same user")
        void shouldGenerateDifferentAccessAndRefreshTokens() {
            User user = createTestUser(1L, "testuser", "test@email.com");

            String accessToken = jwtTokenProvider.generateAccessToken(user);
            String refreshToken = jwtTokenProvider.generateRefreshToken(user);

            assertThat(accessToken).isNotEqualTo(refreshToken);
        }
    }
}

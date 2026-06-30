package com.apimarketplace.auth.service;

import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.bootstrap.FirstAdminBootstrap;
import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.RefreshToken;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.dto.OrgMembershipDto;
import com.apimarketplace.auth.metrics.AuthMetrics;
import com.apimarketplace.auth.repository.RefreshTokenRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.security.JwtTokenProvider;
import com.apimarketplace.auth.validation.UsernameValidator;
import org.springframework.beans.factory.annotation.Autowired;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Password-based authentication service for embedded CE mode.
 * Handles registration, login, and refresh token management.
 *
 * Security:
 * - BCrypt with cost 12 for password hashing
 * - SHA-256 for refresh token hashing (stored in DB)
 * - Rate limiting: 5 failed login attempts per minute per email (Caffeine)
 * - Refresh token rotation on each refresh
 * - Max 10 active refresh tokens per user
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "embedded")
public class PasswordAuthService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordAuthService.class);
    private static final int BCRYPT_STRENGTH = 12;
    private static final int MAX_ACTIVE_TOKENS_PER_USER = 10;
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom;

    @Autowired(required = false)
    private FirstAdminBootstrap firstAdminBootstrap;

    @Autowired(required = false)
    private OrganizationService organizationService;

    @Autowired(required = false)
    private AuthMetrics authMetrics;

    @Autowired(required = false)
    private AuditLogger auditLogger;

    @Autowired(required = false)
    private UsernameValidator usernameValidator;

    // Rate limiting: email -> failed attempt count (1 minute window)
    private final Cache<String, Integer> loginAttempts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10_000)
            .build();

    public PasswordAuthService(UserRepository userRepository,
                               RefreshTokenRepository refreshTokenRepository,
                               JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);
        this.secureRandom = new SecureRandom();
    }

    /**
     * Register a new user with email and password.
     *
     * @return the created user
     * @throws IllegalArgumentException if email already taken or password too weak
     */
    @Transactional
    public User register(String email, String password, String firstName, String lastName) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        email = email.trim().toLowerCase();

        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already registered");
        }

        // First registered user gets ADMIN role - gated on CE edition + install-state
        // (not user-count alone) + advisory lock for race safety. See FirstAdminBootstrap.
        // The helper returns false if it is not injected (defensive - should only happen
        // in pathological test contexts; production wiring always provides it).
        boolean isFirstUser = firstAdminBootstrap != null && firstAdminBootstrap.claimFirstAdminSlot();

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setAuthProvider(AuthProvider.LOCAL);
        user.setProviderId("local:" + email);
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setRoles(isFirstUser ? Set.of("USER", "ADMIN") : Set.of("USER"));
        user.setLastLoginAt(LocalDateTime.now());

        if (firstName != null && !firstName.isBlank()) {
            user.setFirstName(firstName.trim());
        }
        if (lastName != null && !lastName.isBlank()) {
            user.setLastName(lastName.trim());
        }

        String displayName = buildDisplayName(firstName, lastName, email);
        user.setUsername(buildInitialUsername(displayName, email));

        user = userRepository.save(user);

        // Create personal organization (cloud does this during onboarding)
        if (organizationService != null) {
            try {
                organizationService.createPersonalOrganization(user, displayName);
                logger.info("Personal organization created for user {}", user.getId());
            } catch (Exception e) {
                logger.warn("Failed to create personal organization for user {}: {}", user.getId(), e.getMessage());
            }
        }

        logger.info("New local user registered: id={}, email={}", user.getId(), email);
        if (authMetrics != null) authMetrics.signup("local", isFirstUser);
        return user;
    }

    /**
     * Authenticate a user with email and password.
     *
     * @return the authenticated user
     * @throws AuthenticationException if credentials are invalid or rate limited
     */
    @Transactional
    public User login(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new AuthenticationException("Email and password are required");
        }
        email = email.trim().toLowerCase();

        // Rate limiting
        Integer attempts = loginAttempts.getIfPresent(email);
        if (attempts != null && attempts >= MAX_LOGIN_ATTEMPTS) {
            logger.warn("Login rate limited for email={}", email);
            if (authMetrics != null) {
                authMetrics.rateLimitHit("login");
                authMetrics.loginFailure("local", "rate_limited");
            }
            throw new AuthenticationException("Too many login attempts. Please try again later.");
        }

        User user = userRepository.findByEmail(email)
                .orElse(null);

        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            // Increment failed attempts
            loginAttempts.put(email, (attempts != null ? attempts : 0) + 1);
            if (authMetrics != null) authMetrics.loginFailure("local", "invalid_credentials");
            throw new AuthenticationException("Invalid email or password");
        }

        // Check account is enabled
        if (!user.isEnabled()) {
            if (authMetrics != null) authMetrics.loginFailure("local", "disabled");
            throw new AuthenticationException("Account is disabled");
        }

        // Reset rate limit on success
        loginAttempts.invalidate(email);

        // Update last login
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        logger.info("User logged in: id={}, email={}", user.getId(), email);
        if (authMetrics != null) authMetrics.loginSuccess("local");
        return user;
    }

    /**
     * Generate access + refresh token pair for a user.
     *
     * @return TokenPair with both tokens
     */
    @Transactional
    public TokenPair generateTokenPair(User user, String userAgent, String ipAddress) {
        String accessToken = jwtTokenProvider.generateAccessToken(user, resolveOrganizationClaims(user));

        // Generate opaque refresh token
        byte[] tokenBytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String rawRefreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String tokenHash = hashToken(rawRefreshToken);

        // Evict oldest tokens if limit exceeded
        long activeCount = refreshTokenRepository.countActiveByUserId(user.getId(), LocalDateTime.now());
        if (activeCount >= MAX_ACTIVE_TOKENS_PER_USER) {
            refreshTokenRepository.revokeAllByUserId(user.getId(), LocalDateTime.now());
            logger.info("Revoked all refresh tokens for user {} (exceeded max {})", user.getId(), MAX_ACTIVE_TOKENS_PER_USER);
        }

        // Save refresh token
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(
                jwtTokenProvider.getRefreshTokenExpirationMs() / 1000);
        RefreshToken refreshToken = new RefreshToken(tokenHash, user, expiresAt);
        refreshToken.setUserAgent(userAgent != null ? truncate(userAgent, 512) : null);
        refreshToken.setIpAddress(ipAddress);
        refreshTokenRepository.save(refreshToken);

        return new TokenPair(
                accessToken,
                rawRefreshToken,
                jwtTokenProvider.getAccessTokenExpirationMs() / 1000,
                jwtTokenProvider.getRefreshTokenExpirationMs() / 1000
        );
    }

    private JwtTokenProvider.OrganizationClaims resolveOrganizationClaims(User user) {
        if (organizationService == null || user == null || user.getId() == null) {
            return null;
        }
        try {
            String defaultOrganizationId = null;
            String defaultOrganizationRole = null;
            var defaultMembership = organizationService.getDefaultMembership(user.getId());
            if (defaultMembership.isPresent()) {
                var membership = defaultMembership.get();
                if (membership.getOrganization() != null && membership.getOrganization().getId() != null) {
                    defaultOrganizationId = membership.getOrganization().getId().toString();
                }
                if (membership.getRole() != null) {
                    defaultOrganizationRole = membership.getRole().name();
                }
            }

            List<JwtTokenProvider.OrganizationMembershipClaim> memberships =
                    organizationService.listUserMembershipsDto(user.getId()).stream()
                            .map(PasswordAuthService::toTokenMembership)
                            .toList();

            if (defaultOrganizationId == null && memberships.isEmpty()) {
                return null;
            }
            return new JwtTokenProvider.OrganizationClaims(defaultOrganizationId, defaultOrganizationRole, memberships);
        } catch (Exception e) {
            logger.warn("Failed to resolve organization claims for user {}: {}", user.getId(), e.getMessage());
            return null;
        }
    }

    private static JwtTokenProvider.OrganizationMembershipClaim toTokenMembership(OrgMembershipDto membership) {
        return new JwtTokenProvider.OrganizationMembershipClaim(
                membership.getOrgId(),
                membership.getRole(),
                membership.isPersonal(),
                membership.isPaused());
    }

    /**
     * Refresh an access token using a refresh token.
     * Implements token rotation: old token is revoked, new one issued.
     *
     * @return new TokenPair
     * @throws AuthenticationException if refresh token is invalid/expired/revoked
     */
    @Transactional
    public TokenPair refresh(String rawRefreshToken, String userAgent, String ipAddress) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new AuthenticationException("Refresh token is required");
        }

        String tokenHash = hashToken(rawRefreshToken);
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthenticationException("Invalid refresh token"));

        if (!storedToken.isUsable()) {
            // Possible token reuse attack - revoke all tokens for this user
            if (storedToken.isRevoked()) {
                logger.warn("Refresh token reuse detected for user {}. Revoking all tokens.", storedToken.getUser().getId());
                refreshTokenRepository.revokeAllByUserId(storedToken.getUser().getId(), LocalDateTime.now());
                if (authMetrics != null) authMetrics.tokenReuseDetected();
            }
            if (authMetrics != null) authMetrics.tokenRefreshed("failure");
            throw new AuthenticationException("Refresh token expired or revoked");
        }

        // Revoke old token (rotation)
        storedToken.revoke();
        refreshTokenRepository.save(storedToken);

        // Issue new pair
        User user = storedToken.getUser();
        TokenPair pair = generateTokenPair(user, userAgent, ipAddress);
        if (authMetrics != null) authMetrics.tokenRefreshed("success");
        return pair;
    }

    /**
     * Revoke a specific refresh token (logout).
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        String tokenHash = hashToken(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            token.revoke();
            refreshTokenRepository.save(token);
            logger.info("Refresh token revoked for user {}", token.getUser().getId());
            if (authMetrics != null) authMetrics.logout("single");
        });
    }

    /**
     * Revoke all refresh tokens for a user (logout from all devices).
     */
    @Transactional
    public void logoutAll(Long userId) {
        int count = refreshTokenRepository.revokeAllByUserId(userId, LocalDateTime.now());
        logger.info("Revoked {} refresh tokens for user {}", count, userId);
        if (authMetrics != null) authMetrics.logout("all");
    }

    /**
     * Change user password. Revokes all existing refresh tokens.
     */
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.getPasswordHash() == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            if (authMetrics != null) authMetrics.passwordChanged("failure");
            throw new AuthenticationException("Current password is incorrect");
        }

        if (newPassword == null || newPassword.length() < 8) {
            if (authMetrics != null) authMetrics.passwordChanged("failure");
            throw new IllegalArgumentException("New password must be at least 8 characters");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Revoke all refresh tokens on password change
        refreshTokenRepository.revokeAllByUserId(userId, LocalDateTime.now());
        logger.info("Password changed for user {}, all refresh tokens revoked", userId);
        if (authMetrics != null) authMetrics.passwordChanged("success");
    }

    /**
     * Cleanup expired/revoked tokens older than 7 days.
     */
    @Scheduled(fixedRate = 86_400_000) // daily
    @SchedulerLock(name = "password_token_cleanup", lockAtMostFor = "PT10M")
    @Transactional
    public void cleanupExpiredTokens() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        int deleted = refreshTokenRepository.deleteExpiredOrRevoked(cutoff);
        if (deleted > 0) {
            logger.info("Cleaned up {} expired/revoked refresh tokens", deleted);
        }
    }

    /**
     * Build a display name from first + last name, with email as fallback.
     */
    private String buildDisplayName(String firstName, String lastName, String email) {
        String first = (firstName != null && !firstName.isBlank()) ? firstName.trim() : null;
        String last = (lastName != null && !lastName.isBlank()) ? lastName.trim() : null;

        if (first != null && last != null) {
            return first + " " + last;
        } else if (first != null) {
            return first;
        } else if (last != null) {
            return last;
        }
        // Fallback: use email local part
        int atIdx = email.indexOf('@');
        return atIdx > 0 ? email.substring(0, atIdx) : email;
    }

    private String buildInitialUsername(String displayName, String email) {
        if (usernameValidator != null) {
            return usernameValidator.generateUniqueUsername(displayName);
        }
        int atIdx = email.indexOf('@');
        return atIdx > 0 ? email.substring(0, atIdx) : email;
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    /**
     * Authentication exception for login failures.
     */
    public static class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) {
            super(message);
        }
    }

    /**
     * Access + refresh token pair.
     */
    public record TokenPair(
            String accessToken,
            String refreshToken,
            long accessTokenExpiresInSeconds,
            long refreshTokenExpiresInSeconds
    ) {}
}

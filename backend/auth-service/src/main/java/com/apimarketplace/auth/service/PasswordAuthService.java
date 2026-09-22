package com.apimarketplace.auth.service;

import com.apimarketplace.auth.audit.AuthEventRecorder;
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
    /**
     * Provider tag for embedded (self-hosted) email+password auth. Must stay equal to
     * what {@code AuthEventRecorder.providerTag(AuthProvider.LOCAL)} returns, or the same
     * sign-in would land under two labels on the same counter.
     */
    private static final String LOCAL_PROVIDER_TAG = "local";

    /**
     * Still here for the counters that have no recorder method yet: password change, token
     * refresh, token reuse and logout. Login and signup telemetry goes through
     * {@link #authEventRecorder} instead, so metric, audit row and analytics cannot drift
     * apart.
     */
    @Autowired(required = false)
    private AuthMetrics authMetrics;

    /**
     * The one strength rule for every password this service writes, and there are
     * THREE places that write one: {@link #register}, {@link #changePassword} and
     * {@link #resetPasswordTo}. All three go through
     * {@link #requireMinimumLength}, because an earlier version of this constant
     * claimed to cover them while register kept its own literal 8 (so weakening
     * the constant weakened two paths and silently left the third stricter).
     *
     * <p>The reset flow validates against it BEFORE claiming a single-use token,
     * so a rejected password does not cost the link.
     *
     * <p>{@code frontend/app/[locale]/reset-password/page.tsx} mirrors this value
     * to check before spending a link. Nothing can enforce that across the two
     * languages, so the number is pinned by a test on this side and the mirror
     * names this constant in a comment.
     */
    public static final int MIN_PASSWORD_LENGTH = 8;
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

    /**
     * Optional FIELD injection rather than constructor injection: the table ships to
     * both editions, but a slice test that never scanned the repository still has to be
     * able to build this service.
     */
    @Autowired(required = false)
    private com.apimarketplace.auth.repository.PasswordResetTokenRepository passwordResetTokenRepository;

    /**
     * The one door for login/signup telemetry: metric, audit row and product analytics
     * in a single call, so the three cannot drift apart.
     *
     * <p>This replaced a bare {@code AuditLogger} field that was injected here and never
     * once used, which is why self-hosted sign-ins bumped a counter and left NO audit
     * trail at all, while cloud sign-ins left one. Optional, like the other collaborators
     * above, so a slice test can still build this service by hand.
     */
    @Autowired(required = false)
    private AuthEventRecorder authEventRecorder;

    @Autowired(required = false)
    private UsernameValidator usernameValidator;

    // Rate limiting: email -> failed attempt count (1 minute window)
    private final Cache<String, Integer> loginAttempts = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10_000)
            .build();

    /**
     * Addresses whose lockout has already been written to the audit trail, so a flood of
     * refused attempts produces ONE signed row rather than one per request. Same window and
     * same bound as the limiter it shadows; losing an entry costs one extra audit row.
     */
    private final Cache<String, Boolean> rateLimitAudited = Caffeine.newBuilder()
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

        requireMinimumLength(password, "Password");

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
        LocalDateTime registeredAt = LocalDateTime.now();
        user.setLastLoginAt(registeredAt);
        // Registration issues tokens immediately, so it is also this account's first
        // authentication (see the recordSignupAndLogin call below).
        user.setLastAuthenticatedAt(registeredAt);

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
        // Registration hands back a token pair straight away (EmbeddedAuthController), so
        // it IS the first sign-in - same pairing the cloud path records.
        if (authEventRecorder != null) {
            authEventRecorder.recordSignupAndLogin(user.getId(), LOCAL_PROVIDER_TAG, isFirstUser);
        }
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
            // Once per lockout, not once per refused attempt. After the limiter arms, the
            // attacker sets the rate, and an HMAC-signed audit row per request would be write
            // amplification on the path meant to CONTAIN abuse, drowning the very trail a
            // security review reads. The metrics still count every refusal; they are cheap.
            if (authEventRecorder != null) {
                if (rateLimitAudited.getIfPresent(email) == null) {
                    rateLimitAudited.put(email, Boolean.TRUE);
                    authEventRecorder.recordLoginRateLimited(LOCAL_PROVIDER_TAG);
                } else {
                    authEventRecorder.recordLoginFailure(LOCAL_PROVIDER_TAG, "rate_limited");
                }
            }
            throw new AuthenticationException("Too many login attempts. Please try again later.");
        }

        User user = userRepository.findByEmail(email)
                .orElse(null);

        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            // Increment failed attempts
            loginAttempts.put(email, (attempts != null ? attempts : 0) + 1);
            if (authEventRecorder != null) authEventRecorder.recordLoginFailure(LOCAL_PROVIDER_TAG, "invalid_credentials");
            throw new AuthenticationException("Invalid email or password");
        }

        // Check account is enabled
        if (!user.isEnabled()) {
            if (authEventRecorder != null) authEventRecorder.recordLoginFailure(LOCAL_PROVIDER_TAG, "disabled");
            throw new AuthenticationException("Account is disabled");
        }

        // Reset rate limit on success
        loginAttempts.invalidate(email);

        // Both columns, because this IS the authentication instant. Cloud infers that
        // moment from a token's auth_time claim; here it is literally now, and leaving
        // last_authenticated_at alone would freeze it on self-hosted at whatever V495
        // seeded at upgrade time, under a column comment promising it only moves forward.
        LocalDateTime now = LocalDateTime.now();
        user.setLastLoginAt(now);
        // Guarded, because the column's contract is "moves forward only" and a clock that
        // steps backwards (an NTP correction on a self-hosted box) would otherwise write a
        // value that every later sign-in has to climb back over.
        if (user.getLastAuthenticatedAt() == null || now.isAfter(user.getLastAuthenticatedAt())) {
            user.setLastAuthenticatedAt(now);
        }
        userRepository.save(user);

        logger.info("User logged in: id={}, email={}", user.getId(), email);
        // One call, one sign-in. This path is already the authentication moment, so it
        // needs no auth_time comparison: the cloud path infers what happens literally here.
        if (authEventRecorder != null) authEventRecorder.recordLoginSuccess(user.getId(), LOCAL_PROVIDER_TAG);
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

        validateNewPassword(newPassword);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Revoke all refresh tokens on password change
        refreshTokenRepository.revokeAllByUserId(userId, LocalDateTime.now());
        // And any pending reset link, for the same reason the reset flow burns
        // them: a link minted before this change must not still open the account
        // after it. The realistic case is someone who notices a stranger in their
        // mailbox and changes their password from a live session; without this the
        // stranger's link keeps working for the rest of the hour.
        if (passwordResetTokenRepository != null) {
            int burned = passwordResetTokenRepository.invalidateLiveTokens(userId, LocalDateTime.now());
            if (burned > 0) {
                logger.info("Password changed for user {}; {} pending reset link(s) invalidated",
                        userId, burned);
            }
        }
        logger.info("Password changed for user {}, all refresh tokens revoked", userId);
        if (authMetrics != null) authMetrics.passwordChanged("success");
    }

    /**
     * Sets a password WITHOUT proving the old one, for the reset-by-e-mail flow.
     *
     * <p>Lives here rather than in {@code PasswordResetService} on purpose: this
     * class is the only place in the service that writes {@code passwordHash},
     * so the strength rule, the encoder strength and the "revoke every session"
     * consequence cannot drift between the two ways a password can change. The
     * caller has already proven possession of a single-use token; that is the
     * authorisation, and it is the caller's job, not this method's.
     *
     * <p>Revoking the refresh tokens is not housekeeping. Someone resetting a
     * password is often doing it BECAUSE a session is not theirs any more, and a
     * reset that left the old refresh tokens alive would hand the account back
     * to whoever holds them.
     */
    @Transactional
    public void resetPasswordTo(Long userId, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Re-checked HERE, not only when the token was issued: an account can be
        // suspended during the hour a link is live, and redeeming one afterwards
        // would let whoever holds it rewrite a suspended account's password.
        // PasswordResetService folds this into its uniform refusal message.
        if (!user.isEnabled()) {
            throw new IllegalArgumentException("User not found");
        }

        validateNewPassword(newPassword);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        refreshTokenRepository.revokeAllByUserId(userId, LocalDateTime.now());
        logger.info("Password reset for user {}, all refresh tokens revoked", userId);
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

    /**
     * Throws if {@code newPassword} fails the rule, without writing anything.
     *
     * <p>Public because the reset flow needs to check the password BEFORE it
     * spends the single-use token: it is the same rule, evaluated earlier, not a
     * second copy of it. {@link #changePassword} and {@link #resetPasswordTo}
     * both go through here so the rule and its message cannot drift.
     */
    public void validateNewPassword(String newPassword) {
        try {
            // The label differs from register's on purpose: "New password" is what
            // a change or reset form asks for, "Password" is what a signup form
            // asks for. Only the RULE is shared, which is the part that must not
            // drift, so the condition lives in exactly one place.
            requireMinimumLength(newPassword, "New password");
        } catch (IllegalArgumentException e) {
            if (authMetrics != null) authMetrics.passwordChanged("failure");
            throw e;
        }
    }

    /**
     * The length rule itself, in one place, phrased for the form that asked.
     *
     * @param label how the caller's form names the field, so the message reads
     *              naturally on signup as well as on a change or a reset
     */
    private static void requireMinimumLength(String password, String label) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    label + " must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }

    /**
     * Package-private and static so {@link PasswordResetService} hashes its
     * tokens with THIS construction rather than a second copy of it. Two
     * implementations of one security primitive are two things to keep in step.
     */
    static String hashToken(String rawToken) {
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

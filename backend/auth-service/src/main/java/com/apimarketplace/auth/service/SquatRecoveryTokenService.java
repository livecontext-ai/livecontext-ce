package com.apimarketplace.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * One-time tokens for the squat-recovery email link (doc §1 #41).
 *
 * <p>Token = 32 secure-random bytes (256 bits entropy), URL-safe base64. Stored
 * in Redis under {@code ce-link:squat-recovery:{token}} with a default 60-minute
 * TTL. Value = {@code installId|victimUserId} so consumption can dispatch to the
 * right ce_link row without trusting any path-param.
 *
 * <p>Consume uses {@code opsForValue().getAndDelete(key)} - atomic so a single
 * recovery link can't be replayed under concurrent clicks (the user's email
 * inbox + a copy in their browser history).
 *
 * <p>Redis is the right substrate (not the DB): tokens are inherently TTL'd,
 * volatile, and high-write. Persistence across replicas is required (the user
 * may click the link on any replica). Without Redis the service degenerates -
 * {@link Optional#empty()} on every consume, so the user simply can't recover
 * (acceptable: better than silently letting the squatter keep the link).
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class SquatRecoveryTokenService {

    private static final Logger log = LoggerFactory.getLogger(SquatRecoveryTokenService.class);

    private static final String KEY_PREFIX = "ce-link:squat-recovery:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;   // 256 bits

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public SquatRecoveryTokenService(
            StringRedisTemplate redisTemplate,
            @Value("${cloud-link.squat-recovery.token-ttl-minutes:60}") long ttlMinutes) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /**
     * Mint a fresh one-time token for ({@code installId}, {@code victimUserId}).
     * Returns the token string to embed in the recovery URL.
     */
    public String mint(UUID installId, Long victimUserId) {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String value = installId.toString() + "|" + victimUserId;
        redisTemplate.opsForValue().set(KEY_PREFIX + token, value, ttl);
        log.debug("SquatRecoveryToken minted for installId={} victimUserId={} ttl={}m",
                installId, victimUserId, ttl.toMinutes());
        return token;
    }

    /**
     * Non-destructive lookup. Returns the binding if the token exists, empty
     * otherwise. Use this before triggering the revoke side-effect so a failed
     * revoke (DB hiccup, etc.) doesn't burn the token and leave the victim
     * permanently locked out - confirm the revoke succeeded, then call
     * {@link #invalidate} to mark it consumed.
     */
    public Optional<TokenBinding> peek(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return parse(redisTemplate.opsForValue().get(KEY_PREFIX + token));
        } catch (RuntimeException redisFailure) {
            log.warn("SquatRecoveryToken peek: Redis call failed ({}). Treating as unknown.",
                    redisFailure.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Mark the token consumed. Idempotent - a missing key just returns. Called
     * by the controller only AFTER {@code adminRevoke} succeeds.
     */
    public void invalidate(String token) {
        if (token == null || token.isBlank()) return;
        try {
            redisTemplate.delete(KEY_PREFIX + token);
        } catch (RuntimeException redisFailure) {
            // Best-effort - the token will TTL out within at most one hour.
            log.warn("SquatRecoveryToken invalidate: Redis call failed ({}). Token will TTL out.",
                    redisFailure.getMessage());
        }
    }

    /**
     * Atomic peek-and-consume - kept for callers that don't need the
     * peek/invalidate split (e.g. tests, ops tooling).
     */
    public Optional<TokenBinding> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return parse(redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + token));
        } catch (RuntimeException redisFailure) {
            log.warn("SquatRecoveryToken consume: Redis call failed ({}). Treating as unknown token.",
                    redisFailure.getMessage());
            return Optional.empty();
        }
    }

    private Optional<TokenBinding> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        int sep = value.indexOf('|');
        if (sep <= 0 || sep == value.length() - 1) {
            log.warn("SquatRecoveryToken: malformed redis value '{}' - ignoring", value);
            return Optional.empty();
        }
        try {
            UUID installId = UUID.fromString(value.substring(0, sep));
            Long victimUserId = Long.parseLong(value.substring(sep + 1));
            return Optional.of(new TokenBinding(installId, victimUserId));
        } catch (IllegalArgumentException malformed) {
            log.warn("SquatRecoveryToken: parse failure on value '{}': {}", value, malformed.getMessage());
            return Optional.empty();
        }
    }

    /** Carrier returned by {@link #consume}. */
    public record TokenBinding(UUID installId, Long victimUserId) {}
}

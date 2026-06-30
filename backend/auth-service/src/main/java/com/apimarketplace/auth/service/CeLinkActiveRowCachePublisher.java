package com.apimarketplace.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Best-effort cross-replica invalidation broadcast for the
 * {@link CeLinkActiveRowCache}. When this replica writes a state-change
 * (REGISTER, REVOKE), it both (a) drops its local cache entry, and (b)
 * publishes the userId on this channel so SIBLING replicas drop theirs too.
 *
 * <p>The matching {@link CeLinkActiveRowCacheInvalidationListener} on every
 * replica (including this one) calls {@code activeRowCache.invalidate(userId)}.
 * Self-receive is harmless - Caffeine {@code invalidate} on a missing key is a
 * no-op.
 *
 * <p>Failure mode: if Redis is unavailable, we log a warning and continue.
 * The local invalidate has already happened, so the originating user sees
 * fresh state on this replica; other replicas catch up at TTL expiry (≤30s).
 * Caches don't earn the right to break writes.
 *
 * <p>{@code StringRedisTemplate} is {@code @Autowired(required = false)}: if a
 * deployment runs without Redis (e.g. embedded CE mode, never reached here
 * since CE doesn't have ce_link, but defensive) the publisher degenerates
 * gracefully.
 */
@Component
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class CeLinkActiveRowCachePublisher {

    private static final Logger log = LoggerFactory.getLogger(CeLinkActiveRowCachePublisher.class);

    /** Channel name - listeners on every replica subscribe to this. */
    public static final String CHANNEL = "ce-link:active-row:invalidate";

    @Nullable
    private final StringRedisTemplate redisTemplate;

    public CeLinkActiveRowCachePublisher(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Broadcast a cache invalidation to all replicas. Payload is the userId as
     * a plain ASCII number - keeps the listener parser trivial.
     */
    public void broadcastInvalidate(Long userId) {
        if (redisTemplate == null) {
            log.debug("CeLinkActiveRowCachePublisher: no StringRedisTemplate bean - skip broadcast for userId={}", userId);
            return;
        }
        try {
            redisTemplate.convertAndSend(CHANNEL, Long.toString(userId));
        } catch (RuntimeException publishFailure) {
            // Best-effort - never let a Redis hiccup fail the user write.
            log.warn("CeLinkActiveRowCachePublisher: failed to broadcast invalidate for userId={} ({}). Other replicas will catch up at TTL expiry.",
                    userId, publishFailure.getMessage());
        }
    }
}

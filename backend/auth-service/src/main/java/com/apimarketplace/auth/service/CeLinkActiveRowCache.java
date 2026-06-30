package com.apimarketplace.auth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Function;

/**
 * Caffeine cache for the {@code userHasAnyActiveLink(userId)} predicate - the
 * {@code §1 #4} mandatory-header gate that gets checked on every authenticated
 * call from users with at least one ACTIVE CE install.
 *
 * <p>Without this cache, every authenticated request would trigger an
 * {@code EXISTS} query against {@code auth.ce_link}. With it, the answer is
 * served from RAM for {@code ttlSeconds} (default 30s).
 *
 * <p><b>Cross-replica staleness window</b>: 30s is the worst-case window during
 * which a REGISTER on replica A is invisible to replica B's cache. The
 * mandatory-header gate is defense-in-depth (the request still has to pass
 * normal auth) so bounded staleness is acceptable. Redis pub/sub
 * cross-replica invalidation lives in PR3c (folded with retention scheduler -
 * single "cross-cluster coherence" theme).
 *
 * <p>Both positive and negative results are cached: a user with no ACTIVE link
 * is the common case, and we don't want every request from such a user to hit
 * the DB just to learn "still no link".
 */
@Component
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class CeLinkActiveRowCache {

    private final Cache<Long, Boolean> cache;

    public CeLinkActiveRowCache(
            @Value("${cloud-link.active-link-cache.ttl-seconds:30}") long ttlSeconds,
            @Value("${cloud-link.active-link-cache.max-size:50000}") long maxSize
    ) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maxSize)
                .build();
    }

    /**
     * Read the predicate. On a miss, invoke {@code loader} (typically the
     * service's DB query) and cache the result for the TTL.
     */
    public boolean get(Long userId, Function<Long, Boolean> loader) {
        return cache.get(userId, loader);
    }

    /**
     * Drop the cached value for {@code userId}. Called from
     * {@code CeLinkService.register/revoke} after the DB row flip so the next
     * gate check on the SAME replica sees fresh state immediately. Other
     * replicas wait for TTL expiry (or for PR3c's pub/sub).
     */
    public void invalidate(Long userId) {
        cache.invalidate(userId);
    }

    /** Test/ops helper - wipe the cache. */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /** Test helper - observe the underlying Caffeine instance. */
    Cache<Long, Boolean> underlying() {
        return cache;
    }
}

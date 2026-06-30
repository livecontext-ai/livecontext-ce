package com.apimarketplace.auth.client.access;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.OrgRestrictionDto;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.scope.OrgAccessCacheInvalidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Default {@link OrgAccessGuard} implementation, factorising the four near-identical
 * copies that previously lived in orchestrator-service, agent-service, datasource-service,
 * and interface-service.
 *
 * <p>In-process cache keyed by {@code orgId:userId:resourceType}, disabled by default
 * so access decisions remain authoritative even when Redis/EventBus is unavailable.
 * Callers may enable a positive TTL in controlled deployments; invalidation events
 * then drop stale entries after restriction mutations.
 *
 * <p>The behavioural deltas vs the pre-factorisation per-service copies are listed in
 * the {@link OrgAccessGuard} interface javadoc.
 */
public class OrgAccessGuardImpl implements OrgAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(OrgAccessGuardImpl.class);
    private static final long DEFAULT_CACHE_TTL_MS = 0L;
    private static final Set<String> ADMIN_ROLES = Set.of("OWNER", "ADMIN");

    private record CacheEntry(Set<String> restrictedIds, Instant expiresAt) {
        boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    }

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final AuthClient authClient;
    private final long cacheTtlMs;

    public OrgAccessGuardImpl(AuthClient authClient) {
        this(authClient, DEFAULT_CACHE_TTL_MS);
    }

    public OrgAccessGuardImpl(AuthClient authClient, EventBus eventBus) {
        this(authClient, DEFAULT_CACHE_TTL_MS, eventBus);
    }

    /**
     * Test-only constructor: override the cache TTL so unit tests can pin
     * expiry behaviour without a real clock or {@code Thread.sleep}.
     */
    public OrgAccessGuardImpl(AuthClient authClient, long cacheTtlMs) {
        this(authClient, cacheTtlMs, null);
    }

    public OrgAccessGuardImpl(AuthClient authClient, long cacheTtlMs, EventBus eventBus) {
        this.authClient = authClient;
        this.cacheTtlMs = cacheTtlMs;
        subscribeToInvalidations(eventBus);
    }

    @Override
    public Set<String> getRestrictedResourceIds(String orgId, String userId, String resourceType, String orgRole) {
        if (isAdminRole(orgRole)) {
            return Set.of();
        }
        if (orgId == null || userId == null || resourceType == null) {
            return Set.of();
        }

        if (cacheTtlMs <= 0L) {
            return authClient.getRestrictedResourceIds(orgId, userId, resourceType);
        }

        String cacheKey = orgId + ":" + userId + ":" + resourceType;
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            return entry.restrictedIds();
        }

        Set<String> restricted = authClient.getRestrictedResourceIds(orgId, userId, resourceType);
        cache.put(cacheKey, new CacheEntry(restricted, Instant.now().plusMillis(cacheTtlMs)));
        return restricted;
    }

    @Override
    public boolean canAccess(String orgId, String userId, String resourceType, String resourceId, String orgRole) {
        if (resourceId == null) {
            return false;
        }
        Set<String> restricted = getRestrictedResourceIds(orgId, userId, resourceType, orgRole);
        return !restricted.contains(resourceId);
    }

    @Override
    public Set<String> getWriteRestrictedResourceIds(String orgId, String userId, String resourceType, String orgRole) {
        if (isAdminRole(orgRole)) {
            return Set.of();
        }
        if (orgId == null || userId == null || resourceType == null) {
            return Set.of();
        }

        if (cacheTtlMs <= 0L) {
            return authClient.getWriteRestrictedResourceIds(orgId, userId, resourceType);
        }

        // Distinct cache key from the read set (":W" suffix).
        String cacheKey = orgId + ":" + userId + ":" + resourceType + ":W";
        CacheEntry entry = cache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            return entry.restrictedIds();
        }

        Set<String> restricted = authClient.getWriteRestrictedResourceIds(orgId, userId, resourceType);
        cache.put(cacheKey, new CacheEntry(restricted, Instant.now().plusMillis(cacheTtlMs)));
        return restricted;
    }

    @Override
    public boolean canWrite(String orgId, String userId, String resourceType, String resourceId, String orgRole) {
        if (resourceId == null) {
            return false;
        }
        Set<String> writeRestricted = getWriteRestrictedResourceIds(orgId, userId, resourceType, orgRole);
        return !writeRestricted.contains(resourceId);
    }

    @Override
    public <T> List<T> filterAccessible(List<T> items, String orgId, String userId,
                                         String resourceType, String orgRole,
                                         Function<T, String> idExtractor) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        if (isAdminRole(orgRole)) {
            return items;
        }
        Set<String> restricted = getRestrictedResourceIds(orgId, userId, resourceType, orgRole);
        if (restricted.isEmpty()) {
            return items;
        }
        return items.stream()
                .filter(item -> !restricted.contains(idExtractor.apply(item)))
                .collect(Collectors.toList());
    }

    @Override
    public void restrictAccess(String orgId, String memberUserId, String resourceType,
                                String resourceId, String restrictedBy) {
        authClient.restrictAccess(orgId, memberUserId, resourceType, resourceId, restrictedBy);
        invalidateCache(orgId, memberUserId);
    }

    @Override
    public void grantAccess(String orgId, String memberUserId, String resourceType, String resourceId) {
        authClient.grantAccess(orgId, memberUserId, resourceType, resourceId);
        invalidateCache(orgId, memberUserId);
    }

    @Override
    public void setRestrictions(String orgId, String memberUserId, String resourceType,
                                 Set<String> restrictedIds, String restrictedBy) {
        authClient.setRestrictions(orgId, memberUserId, resourceType, restrictedIds, restrictedBy);
        invalidateCache(orgId, memberUserId);
    }

    @Override
    public void setRestrictions(String orgId, String memberUserId, String resourceType,
                                 Set<String> restrictedIds, java.util.Map<String, String> permissionsById,
                                 String restrictedBy) {
        authClient.setRestrictions(orgId, memberUserId, resourceType, restrictedIds, permissionsById, restrictedBy);
        invalidateCache(orgId, memberUserId);
    }

    @Override
    public List<OrgRestrictionDto> getMemberRestrictions(String orgId, String memberUserId) {
        return authClient.getMemberRestrictions(orgId, memberUserId);
    }

    @Override
    public void invalidateCache(String orgId, String userId) {
        String prefix = orgId + ":" + userId + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    @Override
    public void invalidateCacheForOrg(String orgId) {
        String prefix = orgId + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void subscribeToInvalidations(EventBus eventBus) {
        if (eventBus == null) {
            return;
        }
        eventBus.subscribe(OrgAccessCacheInvalidation.CHANNEL, message -> {
            OrgAccessCacheInvalidation.parse(message).ifPresentOrElse(
                    event -> invalidateCache(event.orgId(), event.userId()),
                    () -> log.warn("Ignored malformed org access cache invalidation message: {}", message)
            );
        });
    }

    private static boolean isAdminRole(String orgRole) {
        return orgRole != null && ADMIN_ROLES.contains(orgRole);
    }
}

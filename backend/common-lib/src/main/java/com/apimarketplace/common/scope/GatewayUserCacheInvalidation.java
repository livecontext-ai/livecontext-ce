package com.apimarketplace.common.scope;

import java.util.Optional;

/**
 * Message contract for cross-pod invalidation of the gateway's user-resolution
 * cache ({@code QuotaCacheService}), keyed by auth provider id.
 *
 * <p>Why this exists: the gateway caches the full {@code UserResolutionResponse}
 * (memberships + org roles, 5-min TTL) in-process, per replica. Auth-service's
 * {@code GatewayCacheClient} invalidation is a single HTTP POST that a
 * Service/LB routes to ONE replica - with 2+ gateway replicas, a demoted or
 * removed member kept their stale role on the other replicas for up to the
 * TTL. Publishing on this channel reaches every replica (each gateway
 * subscribes via the shared {@code EventBus}), mirroring the pattern already
 * used by {@link OrgAccessCacheInvalidation} for the org-restriction cache.
 * The HTTP POST is kept as a belt-and-braces immediate path.
 */
public final class GatewayUserCacheInvalidation {

    public static final String CHANNEL = "gateway:user-cache:invalidate";

    private GatewayUserCacheInvalidation() {
    }

    public static String messageFor(String providerId) {
        return providerId == null ? "" : providerId.trim();
    }

    public static Optional<String> parse(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(message.trim());
    }
}

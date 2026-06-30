package com.apimarketplace.orchestrator.domain;

/**
 * The native resource kinds a user can favorite from their own library. Each
 * value is the stable {@code resource_type} stored in
 * {@code orchestrator.user_resource_favorites} (V361) and the path segment used
 * by {@code /api/favorites/{type}/...}.
 *
 * <p>These mirror the resource-list pages that carry a favorites star:
 * {@code /app/workflow}, {@code /app/tables}, {@code /app/interface},
 * {@code /app/agent}. Marketplace APPLICATION favorites are a separate concern
 * (publication-service, V359) - they reference publication ids, not native
 * resource ids, so they are deliberately NOT part of this enum.
 */
public enum ResourceFavoriteType {
    WORKFLOW,
    TABLE,
    INTERFACE,
    AGENT;

    /**
     * Case-insensitive parse of a path segment to a type, or {@code null} when
     * the segment names no known type (callers map that to a clean 400 rather
     * than letting an {@link IllegalArgumentException} bubble up).
     */
    public static ResourceFavoriteType fromString(String raw) {
        if (raw == null) return null;
        for (ResourceFavoriteType t : values()) {
            if (t.name().equalsIgnoreCase(raw.trim())) return t;
        }
        return null;
    }
}

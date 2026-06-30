package com.apimarketplace.common.scope;

import java.util.Optional;

public final class OrgAccessCacheInvalidation {

    public static final String CHANNEL = "auth:org-access:invalidate";
    private static final String SEPARATOR = "\t";

    private OrgAccessCacheInvalidation() {
    }

    public static String messageFor(String orgId, String userId) {
        return normalize(orgId) + SEPARATOR + normalize(userId);
    }

    public static Optional<Event> parse(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        String[] parts = message.split(SEPARATOR, -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Event(parts[0], parts[1]));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record Event(String orgId, String userId) {
    }
}

package com.apimarketplace.orchestrator.services.notification;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Bell-fetch payload - what frontend renders.
 *
 * @param items        ordered list of aggregated notifications (latest first)
 * @param unreadCount  badge count: how many items have {@code unread=true}
 *                     (computed across ALL buckets, not just the current page,
 *                     so the bell badge stays correct when paginating)
 * @param page         zero-based page index of {@code items}
 * @param size         page size used to slice
 * @param hasMore      {@code true} when buckets exist beyond the current page
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationsResponse(
        List<NotificationItem> items,
        int unreadCount,
        int page,
        int size,
        boolean hasMore
) {
    @Override
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public List<NotificationItem> items() {
        return items;
    }

    /** Backwards-compatible factory for non-paginated callers (treats result as a single page). */
    public static NotificationsResponse single(List<NotificationItem> items, int unreadCount) {
        return new NotificationsResponse(items, unreadCount, 0, items.size(), false);
    }
}

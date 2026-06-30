package com.apimarketplace.notification.client.dto;

/**
 * Response from {@code POST /api/internal/notifications/emit}.
 *
 * <p>{@code created=true} means a new row was inserted; {@code created=false}
 * means {@code ON CONFLICT DO NOTHING} fired (idempotent retry / multi-replica
 * race). Producers can ignore the {@code created} flag; it's exposed for
 * observability / debugging.
 */
public class NotificationEmitResponse {

    private boolean created;
    private Long notificationId;

    public NotificationEmitResponse() {}

    public NotificationEmitResponse(boolean created, Long notificationId) {
        this.created = created;
        this.notificationId = notificationId;
    }

    public boolean isCreated() { return created; }
    public void setCreated(boolean created) { this.created = created; }

    public Long getNotificationId() { return notificationId; }
    public void setNotificationId(Long notificationId) { this.notificationId = notificationId; }
}

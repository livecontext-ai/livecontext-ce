package com.apimarketplace.orchestrator.services.notification.delivery;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Published once per notification row that was REALLY inserted (the
 * {@code ON CONFLICT DO NOTHING RETURNING id} produced a row), so a replayed or
 * raced emit can never deliver twice: only the insert winner publishes.
 */
public record NotificationCreatedEvent(
        long notificationId,
        String tenantId,
        String organizationId,
        String category,
        String subjectType,
        UUID subjectId,
        String runIdPublic,
        Map<String, Object> payload,
        Instant occurredAt
) {
}

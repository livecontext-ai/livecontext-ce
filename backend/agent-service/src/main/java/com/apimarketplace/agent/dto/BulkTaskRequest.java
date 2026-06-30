package com.apimarketplace.agent.dto;

import java.util.List;
import java.util.UUID;

/**
 * Payload for the task board's bulk action endpoint ({@code POST /api/tasks/bulk}).
 * Applies one {@code action} to every id in {@code taskIds}, per-item (partial success).
 *
 * @param taskIds the tasks to act on (same-column selection enforced by the UI; the
 *                backend is order-independent and tolerates a mixed/stale set)
 * @param action  one of {@code cancel} (cascading cancel → 'cancelled'),
 *                {@code delete} (soft-delete → Deleted column),
 *                {@code restore} (Deleted → previous column), or
 *                {@code purge} (permanent hard-delete of a trashed task, owner-only)
 * @param reason  optional human-readable reason, forwarded to the cancel path
 */
public record BulkTaskRequest(List<UUID> taskIds, String action, String reason) {}

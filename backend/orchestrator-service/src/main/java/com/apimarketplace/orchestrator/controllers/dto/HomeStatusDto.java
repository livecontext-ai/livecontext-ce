package com.apimarketplace.orchestrator.controllers.dto;

import com.apimarketplace.orchestrator.services.notification.NotificationItem;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Single payload consumed by the home-page widget cluster. One round-trip
 * replaces the prior split between {@code /api/dashboard/active-automations}
 * and the legacy notification endpoints.
 *
 * <p>Wire-contract guarantee: {@code automations} and {@code items} are
 * always present (empty array when there is nothing to show). Only
 * {@code lastSeenAt} may be omitted from the JSON, via the field-level
 * {@link JsonInclude#NON_NULL} annotation. A record-level annotation here
 * would propagate to every field - and a backend regression that ever set
 * {@code automations} or {@code items} to {@code null} would silently strip
 * the field from the wire, crashing every consumer calling
 * {@code .some()} / {@code .length} / {@code .map()} on the frontend.
 *
 * @param automations  pinned workflows / applications / agents with armed
 *                     triggers (forward-looking - what's about to fire)
 * @param items        aggregated failed-pinned-run notifications (reverse-
 *                     looking - what needs the user)
 * @param unreadCount  badge count: items where {@code lastEventAt} is after
 *                     the user's read-state cursor
 * @param lastSeenAt   the user's read-state cursor at fetch time (for
 *                     debugging / explicit display; frontend doesn't need it
 *                     to compute unread). Nullable when the user has never
 *                     visited the bell - omitted from JSON in that case.
 */
public record HomeStatusDto(
        List<ActiveAutomationDto> automations,
        List<NotificationItem> items,
        int unreadCount,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant lastSeenAt
) {}

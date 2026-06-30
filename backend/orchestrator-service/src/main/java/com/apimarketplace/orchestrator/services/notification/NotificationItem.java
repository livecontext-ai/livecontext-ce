package com.apimarketplace.orchestrator.services.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight projection for a single notification item - what the bell
 * dropdown renders. Built from a workflow-run row + workflow name lookup.
 *
 * <p>Aggregation is done in Java service layer over rows: rows with the
 * same {@code (subjectId, category)} are folded into one item with
 * {@code count} reflecting the rollup. Distinct categories on the same
 * subject surface as distinct items so the bell can render
 * {@code "X failed runs"} alongside {@code "Y pending approvals"} with
 * correct severity per category (P2a multi-category design).
 *
 * <p>P7 multi-subject-type: fields are subject-agnostic. {@code subjectId} is
 * the UUID stored on the row regardless of subject_type (WORKFLOW workflow id,
 * CREDENTIAL synthetic UUID derived from credentialId, AGENT_TASK task id,
 * TRIGGER trigger id, APPLICATION application id). The frontend routes on
 * {@code subjectType} and uses the optional payload fields
 * ({@code integration}, {@code credentialId}) for non-workflow click-through.
 *
 * @param subjectId       UUID of the subject this notification refers to
 *                        (any subject_type; was {@code workflowId} pre-P7)
 * @param subjectName     user-facing label resolved by the matching
 *                        {@link SubjectNameResolver} (was {@code workflowName})
 * @param subjectType     {@code "WORKFLOW" | "CREDENTIAL" | "AGENT_TASK" |
 *                        "APPLICATION" | "TRIGGER"} - drives frontend routing
 * @param runIdPublic     WORKFLOW-only: the most-recent run's public id for
 *                        click-through. Null for non-WORKFLOW rows by emitter
 *                        contract (the {@code run_id_public} column is only
 *                        populated by orchestrator-side run notifications).
 * @param category        notification category - e.g. {@code "RUN_FAILED"},
 *                        {@code "APPROVAL_PENDING"}, {@code "CRED_EXPIRED"}
 * @param severity        {@code "info" | "warning" | "error"}
 * @param count           rolled-up count for this (subjectId, category) bucket
 * @param firstEventAt    earliest occurred_at among aggregated rows
 * @param lastEventAt     most-recent occurred_at among aggregated rows
 * @param unread          {@code true} when {@code lastEventAt > user.last_seen_at}
 * @param integration     CREDENTIAL-only: integration slug (e.g. {@code "googlecalendar"}),
 *                        captured from {@code payload->>'integration'} of the latest
 *                        row in the bucket. Null for other subject types.
 *                        Drives the bell's per-row {@code <ServiceIcon iconSlug=...>}.
 * @param credentialId    CREDENTIAL-only: the real {@code auth.credentials.id} BIGINT
 *                        as a stringified value, captured from
 *                        {@code payload->>'credentialId'}. Null for other subject
 *                        types. Used by the bell to deep-link to
 *                        {@code /app/settings/credentials?credentialId=<id>}.
 *                        TEXT, not BIGINT: avoids a brittle JSONB-{@code ::bigint}
 *                        cast that would crash the entire bell read if a future
 *                        emitter wrote a non-numeric value.
 * @param triggerKind     TRIGGER-only: lowercase trigger kind captured from
 *                        {@code payload->>'triggerKind'} - one of {@code "schedule"},
 *                        {@code "webhook"}, {@code "chat"}, {@code "form"} per
 *                        {@code TriggerLifecycleManager.emitTriggerDisabledAfterCommit}.
 *                        Null for other subject types. Drives the bell's per-row
 *                        icon (Clock for schedule, Webhook for webhook, etc.) and
 *                        the deep-link tab selection on /app/settings/public-access.
 */
public record NotificationItem(
        UUID subjectId,
        String subjectName,
        String subjectType,
        String runIdPublic,
        String category,
        String severity,
        int count,
        Instant firstEventAt,
        Instant lastEventAt,
        boolean unread,
        String integration,
        String credentialId,
        String triggerKind
) {}

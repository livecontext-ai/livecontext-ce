package com.apimarketplace.agent.dto;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskNoteEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        String tenantId,
        UUID parentTaskId,
        UUID createdByAgentId,
        String createdByUserId,
        UUID assignedToAgentId,
        String assignedToUserId,
        UUID reviewerAgentId,
        String reviewerUserId,
        UUID recurrenceId,
        String title,
        String instructions,
        Map<String, Object> taskContext,
        String priority,
        String status,
        String result,
        String errorMessage,
        int depth,
        Instant dueBy,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt,
        Integer maxReviewAttempts,
        int reviewAttemptCount,
        UUID assigneeExecutionId,
        UUID reviewerExecutionId,
        /** When the task was moved to the trash (status='deleted'); null while live. */
        Instant deletedAt,
        /** Status held before being trashed, so the UI can label Restore; null while live. */
        String previousStatus,
        /** Manual ordering rank within a board column (F1); null = unranked. */
        Double boardRank,
        /** Label ids on this task (F2); resolved to name/color via the board catalog. */
        List<String> labelIds,
        /** Estimated effort in minutes (F12); null = not set. */
        Integer estimateMinutes,
        /** Logged time spent in minutes (F12); null = not set. */
        Integer timeSpentMinutes,
        /** Ids of tasks blocking this one (F9); the board computes the blocked badge. */
        List<String> blockedByIds,
        /** Checklist items (F10): each {id, text, done}. */
        List<Map<String, Object>> checklist,
        /** File attachments (F10): each {id, fileName, storageKey, mimeType, sizeBytes}. */
        List<Map<String, Object>> attachments,
        List<NoteView> notes,
        /**
         * Resolved display info for every human id referenced by this task
         * (creator, assignee, reviewer, note authors): {@code userId → {displayName, avatarUrl}}.
         * Populated by the enrichment layer so the UI never has to fall back to
         * the viewer's own (Keycloak real) name. {@code null}/empty when un-enriched.
         */
        Map<String, UserRef> users) {

    /** A user's public display identity. Never the raw Keycloak real name - always the chosen displayName. */
    public record UserRef(String displayName, String avatarUrl) {}

    public record NoteView(UUID id, UUID authorAgentId, String authorUserId, String content, Instant createdAt) {
        public static NoteView from(AgentTaskNoteEntity e) {
            return new NoteView(e.getId(), e.getAuthorAgentId(), e.getAuthorUserId(), e.getContent(), e.getCreatedAt());
        }
    }

    public static TaskResponse from(AgentTaskEntity t, List<AgentTaskNoteEntity> notes) {
        List<NoteView> noteViews = notes == null ? List.of() : notes.stream().map(NoteView::from).toList();
        return new TaskResponse(
                t.getId(),
                t.getTenantId(),
                t.getParentTaskId(),
                t.getCreatedByAgentId(),
                t.getCreatedByUserId(),
                t.getAssignedToAgentId(),
                t.getAssignedToUserId(),
                t.getReviewerAgentId(),
                t.getReviewerUserId(),
                t.getRecurrenceId(),
                t.getTitle(),
                t.getInstructions(),
                t.getTaskContext(),
                t.getPriority(),
                t.getStatus(),
                t.getResult(),
                t.getErrorMessage(),
                t.getDepth(),
                t.getDueBy(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getStartedAt(),
                t.getCompletedAt(),
                t.getMaxReviewAttempts(),
                t.getReviewAttemptCount(),
                t.getAssigneeExecutionId(),
                t.getReviewerExecutionId(),
                t.getDeletedAt(),
                t.getPreviousStatus(),
                t.getBoardRank(),
                t.getLabelIds(),
                t.getEstimateMinutes(),
                t.getTimeSpentMinutes(),
                t.getBlockedByIds(),
                t.getChecklist(),
                t.getAttachments(),
                noteViews,
                Map.of());
    }

    public static TaskResponse from(AgentTaskEntity t) {
        return from(t, List.of());
    }

    /**
     * Returns a copy with the resolved {@code users} map attached. Used by the
     * enrichment layer after batch-resolving display names; keeps {@link #from}
     * pure (no service deps) so the WS publisher and agent-tool paths stay light.
     */
    public TaskResponse withUsers(Map<String, UserRef> resolved) {
        return new TaskResponse(
                id, tenantId, parentTaskId, createdByAgentId, createdByUserId,
                assignedToAgentId, assignedToUserId, reviewerAgentId, reviewerUserId, recurrenceId,
                title, instructions, taskContext, priority, status, result, errorMessage,
                depth, dueBy, createdAt, updatedAt, startedAt, completedAt,
                maxReviewAttempts, reviewAttemptCount, assigneeExecutionId, reviewerExecutionId,
                deletedAt, previousStatus, boardRank, labelIds, estimateMinutes, timeSpentMinutes, blockedByIds,
                checklist, attachments,
                notes, resolved == null ? Map.of() : resolved);
    }
}

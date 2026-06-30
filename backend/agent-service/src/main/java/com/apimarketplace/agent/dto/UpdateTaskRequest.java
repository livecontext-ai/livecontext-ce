package com.apimarketplace.agent.dto;

import java.util.UUID;

/**
 * @param agentId             the new agent assignee, or {@code null} meaning "no change"
 *                            (use {@code unassign=true} to explicitly move to the backlog)
 * @param assigneeUserId      the new HUMAN assignee (auth user id), or {@code null} meaning
 *                            "no change". Mutually exclusive with a non-null {@code agentId}:
 *                            a task is assigned to an agent XOR a person. A human assignee
 *                            does NOT auto-execute. {@code unassign=true} clears whichever
 *                            assignee (agent or human) is currently set.
 * @param unassign            if {@code true}, detach the task from its assignee (agent OR
 *                            human) and return it to the tenant backlog. Mutually exclusive
 *                            with a non-null {@code agentId} / {@code assigneeUserId}.
 * @param reviewerAgentId     the new reviewer agent, or {@code null} for "no change".
 * @param reviewerUserId      the new HUMAN reviewer (auth user id), or {@code null} for
 *                            "no change". Mutually exclusive with {@code reviewerAgentId}.
 * @param removeReviewer      clears whichever reviewer (agent or human) is currently set.
 * @param status              direct status transition (pending, in_progress, in_review,
 *                            completed, failed, cancelled). Validated: in_progress and
 *                            in_review require an assignee (agent OR human).
 * @param maxReviewAttempts   per-task override for the reviewer reject-loop cap.
 *                            {@code null} means "no change". To reset to the service
 *                            default, omit the field (leave {@code null}) - a clean
 *                            API keeps one knob per field. Range validated at [1, 20].
 */
public record UpdateTaskRequest(
        UUID agentId,
        String title,
        String instructions,
        String priority,
        Boolean unassign,
        UUID reviewerAgentId,
        Boolean removeReviewer,
        String status,
        Integer maxReviewAttempts,
        String assigneeUserId,
        String reviewerUserId) {

    /** Back-compat constructor for callers that don't care about unassignment/reviewer/status. */
    public UpdateTaskRequest(UUID agentId, String title, String instructions, String priority) {
        this(agentId, title, instructions, priority, null, null, null, null, null, null, null);
    }

    public UpdateTaskRequest(UUID agentId, String title, String instructions, String priority, Boolean unassign) {
        this(agentId, title, instructions, priority, unassign, null, null, null, null, null, null);
    }

    public UpdateTaskRequest(UUID agentId, String title, String instructions, String priority,
                             Boolean unassign, UUID reviewerAgentId, Boolean removeReviewer) {
        this(agentId, title, instructions, priority, unassign, reviewerAgentId, removeReviewer, null, null, null, null);
    }

    public UpdateTaskRequest(UUID agentId, String title, String instructions, String priority,
                             Boolean unassign, UUID reviewerAgentId, Boolean removeReviewer,
                             String status) {
        this(agentId, title, instructions, priority, unassign, reviewerAgentId, removeReviewer, status, null, null, null);
    }

    public UpdateTaskRequest(UUID agentId, String title, String instructions, String priority,
                             Boolean unassign, UUID reviewerAgentId, Boolean removeReviewer,
                             String status, Integer maxReviewAttempts) {
        this(agentId, title, instructions, priority, unassign, reviewerAgentId, removeReviewer, status,
                maxReviewAttempts, null, null);
    }
}

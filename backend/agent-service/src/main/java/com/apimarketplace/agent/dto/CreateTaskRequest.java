package com.apimarketplace.agent.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Payload for creating a task. {@code agentId} may be {@code null} - in that
 * case the task lands in the tenant backlog.
 *
 * <p>An assignee is an agent ({@code agentId}) XOR a human ({@code assigneeUserId}),
 * and likewise a reviewer is {@code reviewerAgentId} XOR {@code reviewerUserId}.
 * A HUMAN assignee never triggers auto-execution - the task is a Jira-style card
 * the person moves manually (no worker kickoff, no in_progress shimmer).</p>
 *
 * <p>{@code maxReviewAttempts} caps how many times the reviewer may reject
 * before the task is auto-failed (not auto-approved). {@code null} uses the
 * service-level default ({@code AgentTaskService.MAX_REVIEW_ATTEMPTS}).</p>
 */
public record CreateTaskRequest(
        UUID agentId,
        UUID reviewerAgentId,
        String title,
        String instructions,
        String priority,
        Map<String, Object> taskContext,
        Instant dueBy,
        UUID parentTaskId,
        Integer maxReviewAttempts,
        String assigneeUserId,
        String reviewerUserId) {

    /** Back-compat constructor (pre human-assignee): no user assignee/reviewer. */
    public CreateTaskRequest(UUID agentId,
                             UUID reviewerAgentId,
                             String title,
                             String instructions,
                             String priority,
                             Map<String, Object> taskContext,
                             Instant dueBy,
                             UUID parentTaskId,
                             Integer maxReviewAttempts) {
        this(agentId, reviewerAgentId, title, instructions, priority, taskContext, dueBy, parentTaskId,
                maxReviewAttempts, null, null);
    }

    public CreateTaskRequest(UUID agentId,
                             UUID reviewerAgentId,
                             String title,
                             String instructions,
                             String priority,
                             Map<String, Object> taskContext,
                             Instant dueBy,
                             UUID parentTaskId) {
        this(agentId, reviewerAgentId, title, instructions, priority, taskContext, dueBy, parentTaskId,
                null, null, null);
    }
}

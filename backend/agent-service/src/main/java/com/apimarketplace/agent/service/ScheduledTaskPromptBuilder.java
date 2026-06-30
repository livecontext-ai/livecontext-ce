package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.common.web.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Synthesizes the prompt an agent receives when its schedule fires.
 * <p>
 * Today, {@code ScheduleExecutorService.executeAgentSchedule()} reads a static
 * {@code schedule.schedulePrompt} field and sends it to the agent verbatim.
 * With task delegation, that's wasteful - the schedule should instead brief
 * the agent on its actual workload: pending tasks + backlog it can claim.
 * <p>
 * This builder returns either:
 * <ul>
 *   <li>A dynamic prompt listing the agent's top N active tasks + optional backlog,
 *       if the agent has any work</li>
 *   <li>The supplied fallback (the legacy static {@code schedulePrompt}), if there's nothing to do</li>
 * </ul>
 * <p>
 * Exposed via {@code GET /api/internal/agents/{agentId}/scheduled-prompt} so that
 * orchestrator-service can call it from its schedule executor without any JPA
 * coupling to the {@code agent.*} schema.
 */
@Service
public class ScheduledTaskPromptBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledTaskPromptBuilder.class);

    private static final int MAX_INBOX_ITEMS = 15;
    private static final int MAX_REVIEW_ITEMS = 10;
    private static final int MAX_BACKLOG_ITEMS = 5;

    private final AgentTaskRepository taskRepository;
    private final AgentRepository agentRepository;

    public ScheduledTaskPromptBuilder(AgentTaskRepository taskRepository,
                                      AgentRepository agentRepository) {
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
    }

    /**
     * Builds the prompt that replaces the schedule's static
     * {@code schedulePrompt} when a scheduled wakeup fires.
     * <p>
     * Behaviour:
     * <ul>
     *   <li>If the agent has ANY active inbox task or claimable backlog item,
     *       returns a dynamic task-focused prompt and the {@code fallback}
     *       is IGNORED entirely (full replacement - delegation overrides the
     *       legacy static schedule prompt).</li>
     *   <li>If the agent has no work at all, returns the {@code fallback}
     *       unchanged so the legacy schedule prompt still drives the wakeup.</li>
     * </ul>
     *
     * <p>PR26 - scope-strict workload retrieval. Pre-PR26 the 3 queries
     * filtered on tenantId only, returning org-tagged tasks into a personal
     * agent's prompt (and vice versa). Now routes via the same strict
     * finder pair the rest of the chain uses: org-scoped agents see only
     * org-tagged tasks, personal-scoped agents see only personal tasks.
     *
     * @param tenantId         required
     * @param organizationId   null = personal scope, non-null = org scope
     * @param agentId          the agent whose schedule is firing
     * @param fallback         the schedule's legacy static prompt (returned only
     *                         when there is no delegated work)
     */
    @Transactional(readOnly = true)
    public String build(String tenantId, String organizationId, UUID agentId, String fallback) {
        TenantResolver.requireOrgId(organizationId);
        List<AgentTaskEntity> inbox = taskRepository.findActiveInboxByOrganizationIdStrict(
                organizationId, agentId, PageRequest.of(0, MAX_INBOX_ITEMS));
        List<AgentTaskEntity> reviewInbox = taskRepository.findPendingReviewsByOrganizationIdStrict(
                organizationId, agentId, PageRequest.of(0, MAX_REVIEW_ITEMS));
        // V340 - only offer the shared backlog to agents that opted in. A non-
        // participating agent's wakeup still drives its assigned inbox + reviews
        // (targeted work), it is just never handed unassigned backlog it may be
        // ill-suited to claim.
        boolean backlogEnabled = agentRepository.findBacklogEnabledById(agentId).orElse(false);
        List<AgentTaskEntity> backlog = backlogEnabled
                ? taskRepository.findBacklogByOrganizationIdStrict(
                        organizationId, PageRequest.of(0, MAX_BACKLOG_ITEMS))
                : List.of();

        if (inbox.isEmpty() && reviewInbox.isEmpty() && backlog.isEmpty()) {
            // No delegated work - honour the legacy static schedulePrompt.
            return fallback == null ? "" : fallback;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("You have been woken up by your schedule. Here is your current workload:\n\n");

        if (!inbox.isEmpty()) {
            sb.append("## Tasks assigned to you (").append(inbox.size())
              .append(") - YOUR ROLE: assignee (do the work)\n");
            for (AgentTaskEntity t : inbox) {
                sb.append("- [").append(t.getPriority()).append("] ")
                  .append(t.getTitle())
                  .append(" (id=").append(t.getId())
                  .append(", status=").append(t.getStatus()).append(")\n");
            }
            sb.append('\n');
        }

        if (!reviewInbox.isEmpty()) {
            sb.append("## Tasks awaiting your review (").append(reviewInbox.size())
              .append(") - YOUR ROLE: reviewer (judge someone else's work - do NOT call task_complete)\n");
            for (AgentTaskEntity t : reviewInbox) {
                sb.append("- [").append(t.getPriority()).append("] ")
                  .append(t.getTitle())
                  .append(" (id=").append(t.getId()).append(")\n");
            }
            sb.append('\n');
        }

        if (!backlog.isEmpty()) {
            sb.append("## Backlog (").append(backlog.size()).append(" unassigned, any agent may claim)\n");
            for (AgentTaskEntity t : backlog) {
                sb.append("- [").append(t.getPriority()).append("] ")
                  .append(t.getTitle())
                  .append(" (id=").append(t.getId()).append(")\n");
            }
            sb.append('\n');
        }

        sb.append("## How to act\n");
        if (!inbox.isEmpty()) {
            sb.append("For ASSIGNED tasks (you are the assignee):\n");
            sb.append("  1. agent(action='inbox', task_id=<id>) → fetch + auto-start (pending → in_progress)\n");
            sb.append("  2. Do the work\n");
            sb.append("  3. agent(action='task_complete', task_id=<id>, result=<text>) - or task_reject(reason=<text>) if blocked\n\n");
        }
        if (!reviewInbox.isEmpty()) {
            sb.append("For REVIEW tasks (you are the reviewer - do NOT call task_complete on these):\n");
            sb.append("  1. agent(action='review_inbox') - lists tasks awaiting your review, each with the submitted result\n");
            sb.append("  2. Read the result, then EITHER:\n");
            sb.append("     • agent(action='task_approve', task_id=<id>) - approve → completed\n");
            sb.append("     • agent(action='task_reject_review', task_id=<id>, reason=<text>) - send back to assignee\n\n");
        }
        if (!backlog.isEmpty()) {
            sb.append("Optionally: agent(action='claim', task_id=<id>) to pick up a backlog item.\n\n");
        }
        sb.append("Process tasks in priority order.");
        // NOTE: fallback is intentionally discarded - when the agent has delegated
        // work, the task-driven prompt is the only source of truth for this wakeup.
        logger.debug("Schedule prompt replaced for agent {} (inbox={}, review={}, backlog={}, fallback len={})",
                agentId, inbox.size(), reviewInbox.size(), backlog.size(),
                fallback == null ? 0 : fallback.length());
        return sb.toString();
    }
}

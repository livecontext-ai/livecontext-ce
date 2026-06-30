package com.apimarketplace.agent.tools.agent.permission;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.common.web.TenantResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether the calling agent can read a given task's context
 * (events, executions, messages).
 *
 * <p>Recognized roles, in priority order:
 * <ul>
 *   <li>{@link Role#GOD} - caller has no restriction in {@code toolsConfig.agents}
 *       ({@code allowedAgentIds == null}). Convention {@code null=all} per
 *       {@code AgentToolsProvider} doc. Used by the user's primary chat agent.</li>
 *   <li>{@link Role#REVIEWER} - caller is {@code task.reviewer_agent_id}.</li>
 *   <li>{@link Role#CREATOR} - caller is {@code task.created_by_agent_id}.
 *       Direct parent only - no transitive ancestor visibility (a leak risk
 *       documented in the design audit).</li>
 *   <li>{@link Role#NONE} - no access.</li>
 * </ul>
 *
 * <p>Note: {@code assignee} is intentionally NOT a recognized role. The
 * assignee already has full visibility into its own conversation via
 * {@code get_history(self)}; granting it task-context access would expose
 * managerial signals (reviewer identity, internal notes, audit timeline)
 * that don't belong on the worker side.
 */
@Slf4j
@Component
public class TaskVisibilityResolver {

    public enum Role {
        GOD,       // primary chat agent / no toolsConfig.agents restriction
        REVIEWER,  // task.reviewer_agent_id == caller
        CREATOR,   // task.created_by_agent_id == caller (direct only)
        NONE       // no access
    }

    private final AgentTaskRepository taskRepository;

    public TaskVisibilityResolver(AgentTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /**
     * Resolve the role of the calling agent for a given task.
     *
     * <p>Returns {@link Role#NONE} when the task does not exist in the
     * caller's scope - this both denies access AND avoids leaking task
     * existence across scopes (we never disclose "task not found"
     * vs "not authorized" through the role).
     *
     * <p>PR24 - strict-isolation: scope is determined by
     * {@link ToolExecutionContext#orgId()}. Org-workspace callers see
     * tasks tagged for that org only; personal-scope callers see tasks
     * with {@code organization_id IS NULL} only. Closes the GOD-agent
     * cross-scope task resolution leak flagged by the PR24 scope audit:
     * a personal-scope GOD agent could previously resolve org-tagged
     * tasks and vice versa, since {@code findByIdAndTenantId} did not
     * filter by organization_id.
     */
    public Role resolveRole(UUID callerAgentId, UUID taskId, String tenantId,
                             ToolExecutionContext context) {
        if (callerAgentId == null || taskId == null || tenantId == null) {
            return Role.NONE;
        }

        // GOD takes priority - a caller without allowlist restriction sees
        // everything in the tenant. Same convention as the rest of the
        // codebase (AgentToolsProvider.java doc, SubAgentExecutionHandler).
        // Note: GOD is granted BEFORE the task lookup - but we still resolve
        // the task below to confirm tenant + org scope.
        boolean isGodAgent = isGodAgent(context);

        Optional<AgentTaskEntity> taskOpt = findTaskInScope(taskId, tenantId, context);
        if (taskOpt.isEmpty()) {
            // Task doesn't exist in this scope - even GOD doesn't get to
            // see across tenant OR org boundaries.
            return Role.NONE;
        }

        if (isGodAgent) return Role.GOD;

        AgentTaskEntity task = taskOpt.get();
        if (callerAgentId.equals(task.getReviewerAgentId())) return Role.REVIEWER;
        if (callerAgentId.equals(task.getCreatedByAgentId())) return Role.CREATOR;
        return Role.NONE;
    }

    /**
     * Convenience: resolve the role AND return the task entity in one shot
     * to avoid a second DB roundtrip from callers that need both.
     */
    public ResolvedTask resolveRoleAndTask(UUID callerAgentId, UUID taskId, String tenantId,
                                            ToolExecutionContext context) {
        if (callerAgentId == null || taskId == null || tenantId == null) {
            return new ResolvedTask(Role.NONE, null);
        }

        boolean isGodAgent = isGodAgent(context);
        AgentTaskEntity task = findTaskInScope(taskId, tenantId, context).orElse(null);
        if (task == null) return new ResolvedTask(Role.NONE, null);

        if (isGodAgent) return new ResolvedTask(Role.GOD, task);
        if (callerAgentId.equals(task.getReviewerAgentId())) return new ResolvedTask(Role.REVIEWER, task);
        if (callerAgentId.equals(task.getCreatedByAgentId())) return new ResolvedTask(Role.CREATOR, task);
        return new ResolvedTask(Role.NONE, task);
    }

    /**
     * PR24 - strict-isolation finder. Post-V263 (round-7, 2026-05-20):
     * organization_id is NOT NULL on every agent_tasks row, so the only
     * legitimate scope is org-strict. Caller MUST pass a non-blank
     * {@code organizationId} via {@code context.orgId()}; the legacy
     * personal-strict {@code organization_id IS NULL} branch was removed.
     */
    private Optional<AgentTaskEntity> findTaskInScope(UUID taskId, String tenantId,
                                                       ToolExecutionContext context) {
        String organizationId = context != null ? context.orgId() : null;
        TenantResolver.requireOrgId(organizationId);
        return taskRepository.findByIdAndOrganizationIdStrict(taskId, organizationId);
    }

    public record ResolvedTask(Role role, AgentTaskEntity task) {
        public boolean granted() {
            return role != Role.NONE;
        }
    }

    /**
     * A caller is the "god agent" when its toolsConfig has NO restriction
     * on accessible agents. The system-wide convention is
     * {@code allowedAgentIds == null} ⇒ no restriction, {@code []} ⇒ none,
     * specific list ⇒ restricted. This mirrors {@code SubAgentExecutionHandler#getAllowedAgentIds}.
     */
    private boolean isGodAgent(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) return false;
        Object allowed = ToolAccessControl.getAllowedIds(context.credentials(), "agent");
        // null OR absent ⇒ god agent. Empty list ⇒ explicitly restricted (no children).
        if (allowed == null) return true;
        return false;
    }
}

package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskRecurrenceEntity;
import com.apimarketplace.agent.dto.CreateRecurrenceRequest;
import com.apimarketplace.agent.dto.CreateTaskRequest;
import com.apimarketplace.agent.dto.RecurrenceResponse;
import com.apimarketplace.agent.dto.TaskResponse;
import com.apimarketplace.agent.dto.UpdateRecurrenceRequest;
import com.apimarketplace.agent.dto.UpdateTaskRequest;
import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.service.AgentTaskRecurrenceService;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.common.web.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.apimarketplace.agent.tools.common.ToolParamUtils.getIntParam;
import static com.apimarketplace.agent.tools.common.ToolParamUtils.getStringParam;
import static com.apimarketplace.agent.tools.common.ToolParamUtils.getUuidParam;
import static com.apimarketplace.agent.tools.common.ToolParamUtils.mergeParams;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Delegation module for the {@code agent} tool. Exposes 15 actions:
 * <ul>
 *   <li>Core: {@code assign, inbox, outbox, task_complete, task_reject,
 *       task_update, task_cancel, task_approve, task_reject_review}</li>
 *   <li>Backlog: {@code backlog, claim}</li>
 *   <li>Recurrences: {@code recurrence_create, recurrence_list,
 *       recurrence_update, recurrence_delete}</li>
 * </ul>
 * <p>
 * Caller resolution: the calling agent's UUID is read from
 * {@code context.credentials().get("__agentId__")} (populated by
 * {@code AgentToolsController}). When absent, the caller is treated as a
 * human user identified by {@code tenantId} (per the delegation plan §3,
 * which allows humans to create, update, cancel, and list tasks/recurrences).
 * Agent-owned actions ({@code inbox}, {@code task_complete}, {@code task_reject},
 * {@code claim}) still require an agent identity - a human has no inbox and
 * cannot execute tasks - and fail with a friendly error directing the user to
 * attach the conversation to a specific agent.
 * <p>
 * V340 - the shared-backlog actions ({@code backlog} browse + {@code claim}) are
 * additionally OPT-IN per agent: an agent caller may only browse/claim when its
 * {@code backlog_enabled} flag is true (checked via
 * {@link AgentTaskService#isBacklogEnabled(java.util.UUID)}). Human callers
 * browsing the backlog in direct chat are unaffected, and the human board
 * claim/assign path (in {@code AgentTaskController}) is a deliberate override
 * that is NOT governed by this flag.
 */
@Component
public class AgentDelegationModule implements ToolModule {

    private static final Logger log = LoggerFactory.getLogger(AgentDelegationModule.class);

    /** Rate-limit: a single conversation turn may not assign more than this many tasks. */
    public static final int MAX_ASSIGNS_PER_TURN = 5;

    /** Cap on how many distinct turnIds we track at once. Beyond this, oldest entries are evicted. */
    private static final int MAX_TRACKED_TURNS = 10_000;

    /** Entries older than this are evicted on every access (a turn rarely exceeds seconds). */
    private static final long TURN_TTL_MILLIS = 10L * 60 * 1000;  // 10 minutes

    private static final Map<String, Object> TASK_TURN_DECISION_METADATA = Map.of(
            "stopAgentLoop", true,
            "stopReason", "task_turn_decision");

    private static final Set<String> HANDLED_ACTIONS = Set.of(
            "assign", "inbox", "outbox", "review_inbox",
            "task_complete", "task_reject", "task_update", "task_cancel", "task_delete",
            "task_approve", "task_reject_review",
            "backlog", "claim",
            "recurrence_create", "recurrence_list", "recurrence_update", "recurrence_delete"
    );

    /**
     * Turn-scoped counter: {turnId -> (assignsSoFar, createdAtMillis)}.
     * Entries are evicted on access when they exceed {@link #TURN_TTL_MILLIS},
     * and the whole map is size-capped at {@link #MAX_TRACKED_TURNS}.
     */
    private final Map<String, TurnCounter> assignsPerTurn = new ConcurrentHashMap<>();

    private record TurnCounter(int count, long createdAtMillis) {}

    private final AgentTaskService taskService;
    private final AgentTaskRecurrenceService recurrenceService;
    private final com.apimarketplace.agent.repository.AgentTaskRepository taskRepository;

    public AgentDelegationModule(AgentTaskService taskService,
                                  AgentTaskRecurrenceService recurrenceService,
                                  com.apimarketplace.agent.repository.AgentTaskRepository taskRepository) {
        this.taskService = taskService;
        this.recurrenceService = recurrenceService;
        this.taskRepository = taskRepository;
    }

    /**
     * Audit 2026-05-17 round-5 - shared scope guard for MCP delegation
     * mutation actions. Public {@code AgentTaskController} pre-checks via
     * {@code isTaskInScope}; MCP path bypassed this shield, allowing multi-
     * org-same-tenant callers to mutate cross-org teammate tasks. Every
     * task_*-mutation handler now calls {@link #assertTaskInScope} BEFORE
     * hitting the tenant-only service path.
     */
    private void assertTaskInScope(UUID taskId, String tenantId, ToolExecutionContext ctx) {
        String orgId = ctx != null ? ctx.orgId() : null;
        TenantResolver.requireOrgId(orgId);
        if (!taskRepository.findByIdAndOrganizationIdStrict(taskId, orgId).isPresent()) {
            log.warn("[SCOPE] MCP task action cross-tenant blocked: taskId={} caller={} orgId={}",
                    taskId, tenantId, orgId);
            throw new IllegalArgumentException("task not found: " + taskId);
        }
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();  // definitions are centralized in AgentToolsProvider
    }

    @Override
    public boolean canHandle(String action) {
        return HANDLED_ACTIONS.contains(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action,
                                                  Map<String, Object> parameters,
                                                  String tenantId,
                                                  ToolExecutionContext context) {
        if (!canHandle(action)) return Optional.empty();
        var accessDenied = ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null,
                "agent",
                action);
        if (accessDenied.isPresent()) {
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get()));
        }
        try {
            return Optional.of(switch (action) {
                case "assign" -> handleAssign(parameters, tenantId, context);
                case "inbox" -> handleInbox(parameters, tenantId, context);
                case "outbox" -> handleOutbox(parameters, tenantId, context);
                case "task_complete" -> handleComplete(parameters, tenantId, context);
                case "task_reject" -> handleReject(parameters, tenantId, context);
                case "task_update" -> handleUpdate(parameters, tenantId, context);
                case "task_cancel" -> handleCancel(parameters, tenantId, context);
                case "task_delete" -> handleHardDelete(parameters, tenantId, context);
                case "task_approve" -> handleApprove(parameters, tenantId, context);
                case "task_reject_review" -> handleRejectReview(parameters, tenantId, context);
                case "review_inbox" -> handleReviewInbox(parameters, tenantId, context);
                case "backlog" -> handleBacklog(parameters, tenantId, context);
                case "claim" -> handleClaim(parameters, tenantId, context);
                case "recurrence_create" -> handleRecurrenceCreate(parameters, tenantId, context);
                case "recurrence_list" -> handleRecurrenceList(parameters, tenantId, context);
                case "recurrence_update" -> handleRecurrenceUpdate(parameters, tenantId, context);
                case "recurrence_delete" -> handleRecurrenceDelete(parameters, tenantId, context);
                default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown delegation action: " + action);
            });
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage()));
        } catch (Exception e) {
            log.error("Delegation action '{}' failed: {}", action, e.getMessage(), e);
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "internal error: " + e.getMessage()));
        }
    }

    // ========================================================================
    // Core task actions
    // ========================================================================

    private ToolExecutionResult handleAssign(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        UUID callerAgentId = optionalCallingAgentId(ctx);
        String callerUserId = callerAgentId == null ? tenantId : null;
        String turnId = getTurnId(ctx);
        if (turnId != null && !bumpTurnCounter(turnId)) {
            return ToolExecutionResult.failure(ToolErrorCode.RATE_LIMITED, "rate limit: cannot assign more than " + MAX_ASSIGNS_PER_TURN + " tasks per turn");
        }

        Map<String, Object> p = mergeParams(params);
        UUID agentId = getUuidParam(p, "agent_id");
        UUID reviewerAgentId = getUuidParam(p, "reviewer_agent_id");

        // Enforce allowedAgentIds: an agent can only assign to declared children.
        // Convention (matches TaskVisibilityResolver, AgentConversationModule, SubAgentExecutionHandler):
        //   allowedAgentIds == null  ⇒ unrestricted (god / primary chat agent - assign anywhere)
        //   allowedAgentIds == []    ⇒ explicitly restricted to "no children" - assign denied
        //   allowedAgentIds == [...] ⇒ restricted to listed UUIDs
        // Human callers (callerAgentId == null) are always unrestricted.
        if (callerAgentId != null) {
            List<String> allowed = getAllowedAgentIds(ctx);
            if (allowed != null) {
                if (agentId != null && !allowed.contains(agentId.toString())) {
                    return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "agent_id " + agentId + " is not in your allowed agents list. "
                            + "You can only assign tasks to agents configured in your toolsConfig.agents.");
                }
                if (reviewerAgentId != null && !reviewerAgentId.equals(callerAgentId)
                        && !allowed.contains(reviewerAgentId.toString())) {
                    return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "reviewer_agent_id " + reviewerAgentId + " is not in your allowed agents list. "
                            + "You can only designate reviewers from agents configured in your toolsConfig.agents, "
                            + "or set yourself as reviewer.");
                }
            }
            // allowedAgentIds == null: unrestricted, no check.
        }

        // start_mode controls execution timing. See help docs for full semantics.
        //   "pending"     → create row in pending, do NOT trigger the worker. Caller (UI or
        //                   orchestrator) wants the task to wait for an explicit pickup.
        //   "in_progress" → create + dispatch async kickoff (current default), return without
        //                   waiting. Worker promotes pending → in_progress when it acquires
        //                   the lock; the response status may still read 'pending' here.
        //   "execute"     → (default) create + kickoff + sync-wait until terminal state.
        //                   Returns the final status with .result / .error_message inline.
        // Backlog (no assignee) is always 'pending' regardless of start_mode.
        String startMode = getStringParam(p, "start_mode");
        if (startMode == null || startMode.isBlank()) {
            startMode = "execute";
        }
        startMode = startMode.toLowerCase();
        if (!"pending".equals(startMode)
                && !"in_progress".equals(startMode)
                && !"execute".equals(startMode)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "start_mode must be one of: 'pending', 'in_progress', 'execute' "
                    + "(default 'execute'). Got: '" + startMode + "'.");
        }

        UUID parentTaskId = getUuidParam(p, "parent_task_id");
        Integer maxReviewAttempts = getIntParam(p, "max_review_attempts");
        CreateTaskRequest req = new CreateTaskRequest(
                agentId,  // NULL = backlog
                reviewerAgentId,  // NULL = no reviewer (human user reviews from UI)
                getStringParam(p, "title"),
                getStringParam(p, "instructions"),
                getStringParam(p, "priority"),
                asMap(p.get("task_context")),
                parseInstant(getStringParam(p, "due_by")),
                parentTaskId,
                maxReviewAttempts
        );

        // start_mode='pending' suppresses the centralized kickoff. Other modes preserve it.
        boolean autoTriggerWorker = !"pending".equals(startMode);
        AgentTaskEntity t = taskService.assignTask(
                tenantId, callerAgentId, callerUserId, req, autoTriggerWorker);

        // Apply board extras (labels / estimate / blockers / checklist) at creation
        // time, so an agent can create a task WITH them in a single assign call.
        t = applyTaskExtras(p, tenantId, t.getId(), callerAgentId, callerUserId, t);

        // Backlog: no assignee, no kickoff, return immediately.
        if (t.getAssignedToAgentId() == null) {
            return ToolExecutionResult.success(Map.of(
                    "task_id", t.getId().toString(),
                    "status", t.getStatus(),
                    "assigned_to", "backlog",
                    "depth", t.getDepth()
            ));
        }

        // pending or in_progress mode: return immediately, no sync-wait.
        if ("pending".equals(startMode) || "in_progress".equals(startMode)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("task_id", t.getId().toString());
            result.put("status", t.getStatus());
            result.put("assigned_to", t.getAssignedToAgentId().toString());
            result.put("depth", t.getDepth());
            result.put("start_mode", startMode);
            return ToolExecutionResult.success(result);
        }

        // execute mode: sync-wait until terminal state. Re-read after to surface the post-
        // executeTaskSync state including completed/failed plus result/error_message.
        AgentTaskEntity finalTask = taskService.executeTaskSync(t);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", finalTask.getId().toString());
        result.put("status", finalTask.getStatus());
        result.put("assigned_to", finalTask.getAssignedToAgentId().toString());
        result.put("depth", finalTask.getDepth());
        if (finalTask.getResult() != null) {
            result.put("result", finalTask.getResult());
        }
        if (finalTask.getErrorMessage() != null) {
            result.put("error_message", finalTask.getErrorMessage());
        }
        return ToolExecutionResult.success(result);
    }

    private ToolExecutionResult handleInbox(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        UUID callerId = requireCallingAgentId(ctx, "inbox");
        Map<String, Object> p = mergeParams(params);
        UUID taskId = getUuidParam(p, "task_id");
        String organizationId = contextOrgId(ctx);
        if (taskId != null) {
            TaskResponse task = organizationId != null
                    ? taskService.getInboxTask(tenantId, organizationId, callerId, taskId)
                    : taskService.getInboxTask(tenantId, callerId, taskId);
            return ToolExecutionResult.success(task);
        }
        int limit = getIntParam(p, "limit", 20);
        List<AgentTaskEntity> list = organizationId != null
                ? taskService.getInboxList(tenantId, organizationId, callerId, limit)
                : taskService.getInboxList(tenantId, callerId, limit);
        return ToolExecutionResult.success(Map.of(
                "count", list.size(),
                "tasks", list.stream().map(TaskResponse::from).toList()
        ));
    }

    private ToolExecutionResult handleOutbox(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        UUID callerAgentId = optionalCallingAgentId(ctx);
        String callerUserId = callerAgentId == null ? tenantId : null;
        Map<String, Object> p = mergeParams(params);
        UUID taskId = getUuidParam(p, "task_id");
        String organizationId = contextOrgId(ctx);
        if (taskId != null) {
            // Single-task fetch supports both agent and user callers via the 4-arg overload.
            TaskResponse task = organizationId != null
                    ? taskService.getOutboxTask(tenantId, organizationId, callerAgentId, callerUserId, taskId)
                    : taskService.getOutboxTask(tenantId, callerAgentId, callerUserId, taskId);
            return ToolExecutionResult.success(task);
        }
        // List form: the repository indexes outbox by createdByAgentId, so a direct-chat caller
        // (no calling agent) has no agent-scoped task list to browse - only a single task_id lookup.
        if (callerAgentId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "outbox list is agent-scoped. Look up a specific task via "
                    + "agent(action='outbox', task_id=UUID).");
        }
        String status = getStringParam(p, "status");
        int limit = getIntParam(p, "limit", 20);
        List<AgentTaskEntity> list = organizationId != null
                ? taskService.getOutbox(tenantId, organizationId, callerAgentId, status, limit)
                : taskService.getOutbox(tenantId, callerAgentId, status, limit);
        return ToolExecutionResult.success(Map.of(
                "count", list.size(),
                "tasks", list.stream().map(TaskResponse::from).toList()
        ));
    }

    private ToolExecutionResult handleComplete(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        UUID callerId = requireCallingAgentId(ctx, "task_complete");
        Map<String, Object> p = mergeParams(params);
        UUID taskId = requireUuid(p, "task_id");
        assertTaskInScope(taskId, tenantId, ctx);
        String result = getStringParam(p, "result");
        if (result == null || result.isBlank()) {
            throw new IllegalArgumentException("result is required for task_complete");
        }
        Object rawForce = p.get("force");
        boolean force = rawForce instanceof Boolean b ? b
                : rawForce != null && Boolean.parseBoolean(rawForce.toString());
        AgentTaskEntity t = taskService.completeTask(tenantId, taskId, callerId, result, force,
                reviewerExecutionId(ctx));
        return ToolExecutionResult.success(
                Map.of("task_id", t.getId().toString(), "status", t.getStatus()),
                TASK_TURN_DECISION_METADATA);
    }

    private ToolExecutionResult handleReject(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        UUID callerId = requireCallingAgentId(ctx, "task_reject");
        Map<String, Object> p = mergeParams(params);
        UUID taskId = requireUuid(p, "task_id");
        assertTaskInScope(taskId, tenantId, ctx);
        String reason = getStringParam(p, "reason");
        AgentTaskEntity t = taskService.rejectTask(tenantId, taskId, callerId, reason, reviewerExecutionId(ctx));
        return ToolExecutionResult.success(
                Map.of("task_id", t.getId().toString(), "status", t.getStatus()),
                TASK_TURN_DECISION_METADATA);
    }

    private ToolExecutionResult handleUpdate(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        UUID callerAgentId = optionalCallingAgentId(ctx);
        String callerUserId = callerAgentId == null ? tenantId : null;
        Map<String, Object> p = mergeParams(params);
        UUID taskId = requireUuid(p, "task_id");
        assertTaskInScope(taskId, tenantId, ctx);
        UUID newAgentId = getUuidParam(p, "agent_id");

        // Enforce allowedAgentIds on reassign - same convention as assign:
        //   null ⇒ unrestricted (god); [] or [...] ⇒ caller must be in the list.
        if (callerAgentId != null && newAgentId != null) {
            List<String> allowed = getAllowedAgentIds(ctx);
            if (allowed != null && !allowed.contains(newAgentId.toString())) {
                return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "agent_id " + newAgentId + " is not in your allowed agents list. "
                        + "You can only reassign tasks to agents configured in your toolsConfig.agents.");
            }
            // allowed == null: unrestricted, no check.
        }

        Object rawUnassign = p.get("unassign");
        Boolean unassign = rawUnassign instanceof Boolean b ? b
                : rawUnassign != null && Boolean.parseBoolean(rawUnassign.toString()) ? Boolean.TRUE
                : null;
        UUID reviewerAgentId = getUuidParam(p, "reviewer_agent_id");
        Object rawRemoveReviewer = p.get("remove_reviewer");
        Boolean removeReviewer = rawRemoveReviewer instanceof Boolean b2 ? b2
                : rawRemoveReviewer != null && Boolean.parseBoolean(rawRemoveReviewer.toString()) ? Boolean.TRUE
                : null;
        String status = getStringParam(p, "status");
        Integer maxReviewAttempts = getIntParam(p, "max_review_attempts");
        UpdateTaskRequest req = new UpdateTaskRequest(
                newAgentId,
                getStringParam(p, "title"),
                getStringParam(p, "instructions"),
                getStringParam(p, "priority"),
                unassign,
                reviewerAgentId,
                removeReviewer,
                status,
                maxReviewAttempts
        );
        AgentTaskEntity t = taskService.updateTask(tenantId, taskId, callerAgentId, callerUserId, req);
        // Board extras (F2 labels, F12 estimate/time, F9 blockers, F10 checklist) may be
        // set in the same call - shared with the create path (handleAssign).
        t = applyTaskExtras(p, tenantId, taskId, callerAgentId, callerUserId, t);
        return ToolExecutionResult.success(TaskResponse.from(t));
    }

    /**
     * Apply the board-extra params (F2/F12/F9/F10) that an agent may pass on
     * {@code assign} or {@code task_update}. Each param is optional: an absent key
     * leaves that field untouched; an empty list clears it. Ids/values are
     * validated inside the respective service methods. Returns the latest task.
     */
    private AgentTaskEntity applyTaskExtras(Map<String, Object> p, String tenantId, UUID taskId,
                                            UUID callerAgentId, String callerUserId, AgentTaskEntity current) {
        AgentTaskEntity t = current;
        // Labels (F2/F20): label_ids = existing board UUIDs, labels = names (get-or-create).
        // Either present triggers a replace; their union becomes the new set.
        Object rawLabelIds = p.get("label_ids");
        Object rawLabelNames = p.get("labels");
        if (rawLabelIds instanceof List<?> || rawLabelNames instanceof List<?>) {
            t = taskService.setTaskLabels(tenantId, taskId, callerAgentId, callerUserId,
                    toStringList(rawLabelIds), toStringList(rawLabelNames));
        }
        boolean hasEstimate = p.containsKey("estimate_minutes");
        boolean hasTimeSpent = p.containsKey("time_spent_minutes");
        if (hasEstimate || hasTimeSpent) {
            Integer est = hasEstimate ? getIntParam(p, "estimate_minutes") : null;
            Integer spent = hasTimeSpent ? getIntParam(p, "time_spent_minutes") : null;
            t = taskService.setTaskEstimate(tenantId, taskId, callerAgentId, callerUserId,
                    est, false, spent, false);
        }
        Object rawBlockers = p.get("blocked_by");
        if (rawBlockers instanceof List<?> bl) {
            List<String> blockerIds = new ArrayList<>(bl.size());
            for (Object o : bl) if (o != null) blockerIds.add(o.toString());
            t = taskService.setTaskBlockers(tenantId, taskId, callerAgentId, callerUserId, blockerIds);
        }
        Object rawChecklist = p.get("checklist");
        if (rawChecklist instanceof List<?> cl) {
            List<Map<String, Object>> items = new ArrayList<>(cl.size());
            for (Object o : cl) {
                Map<String, Object> m = asMap(o);
                if (m != null) items.add(m);
            }
            t = taskService.setTaskChecklist(tenantId, taskId, callerAgentId, callerUserId, items);
        }
        return t;
    }

    /** Coerce a raw param to a list of non-null strings, or null when it is not a list. */
    private static List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) if (o != null) out.add(o.toString());
        return out;
    }

    private ToolExecutionResult handleCancel(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        UUID callerAgentId = optionalCallingAgentId(ctx);
        String callerUserId = callerAgentId == null ? tenantId : null;
        Map<String, Object> p = mergeParams(params);
        UUID taskId = requireUuid(p, "task_id");
        assertTaskInScope(taskId, tenantId, ctx);
        String reason = getStringParam(p, "reason");
        int cancelled = taskService.cancelTask(tenantId, taskId, callerAgentId, callerUserId, reason);
        return ToolExecutionResult.success(Map.of("task_id", taskId.toString(), "cancelled_count", cancelled));
    }

    private ToolExecutionResult handleHardDelete(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        Map<String, Object> p = mergeParams(params);
        UUID taskId = requireUuid(p, "task_id");
        assertTaskInScope(taskId, tenantId, ctx);
        int deleted = taskService.hardDeleteTask(tenantId, taskId, tenantId);
        return ToolExecutionResult.success(Map.of("task_id", taskId.toString(), "deleted_count", deleted));
    }

    private ToolExecutionResult handleApprove(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        UUID callerId = requireCallingAgentId(ctx, "task_approve");
        Map<String, Object> p = mergeParams(params);
        UUID taskId = requireUuid(p, "task_id");
        assertTaskInScope(taskId, tenantId, ctx);
        AgentTaskEntity t = taskService.approveTask(tenantId, taskId, callerId, reviewerExecutionId(ctx));
        return ToolExecutionResult.success(
                Map.of("task_id", t.getId().toString(), "status", t.getStatus()),
                TASK_TURN_DECISION_METADATA);
    }

    private ToolExecutionResult handleRejectReview(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        UUID callerId = requireCallingAgentId(ctx, "task_reject_review");
        Map<String, Object> p = mergeParams(params);
        UUID taskId = requireUuid(p, "task_id");
        assertTaskInScope(taskId, tenantId, ctx);
        String reason = getStringParam(p, "reason");
        AgentTaskEntity t = taskService.rejectReview(tenantId, taskId, callerId, reviewerExecutionId(ctx), reason);
        return ToolExecutionResult.success(
                Map.of("task_id", t.getId().toString(), "status", t.getStatus()),
                TASK_TURN_DECISION_METADATA);
    }

    private ToolExecutionResult handleReviewInbox(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        UUID callerId = requireCallingAgentId(ctx, "review_inbox");
        Map<String, Object> p = mergeParams(params);
        int limit = getIntParam(p, "limit", 20);
        String organizationId = contextOrgId(ctx);
        List<AgentTaskEntity> list = organizationId != null
                ? taskService.getReviewInbox(tenantId, organizationId, callerId, limit)
                : taskService.getReviewInbox(tenantId, callerId, limit);
        return ToolExecutionResult.success(Map.of(
                "count", list.size(),
                "tasks", list.stream().map(TaskResponse::from).toList()
        ));
    }

    // ========================================================================
    // Backlog actions
    // ========================================================================

    private ToolExecutionResult handleBacklog(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        // Backlog is tenant-scoped - no caller identity required. Humans browsing the
        // backlog in direct chat is explicitly supported.
        // V340 - an AGENT caller may only browse the shared backlog when it opted in
        // (participation is per-agent). Human callers (no __agentId__) are unaffected.
        UUID backlogCallerAgentId = optionalCallingAgentId(ctx);
        if (backlogCallerAgentId != null && !taskService.isBacklogEnabled(backlogCallerAgentId)) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                    "this agent is not enabled to access the shared backlog (it is opt-in per agent). "
                    + "Work the tasks assigned directly to you via agent(action='inbox'), or ask the owner "
                    + "to enable backlog participation for this agent.");
        }
        Map<String, Object> p = mergeParams(params);
        int limit = getIntParam(p, "limit", 20);
        String organizationId = contextOrgId(ctx);
        List<AgentTaskEntity> list = organizationId != null
                ? taskService.getBacklog(tenantId, organizationId, limit)
                : taskService.getBacklog(tenantId, limit);
        return ToolExecutionResult.success(Map.of(
                "count", list.size(),
                "tasks", list.stream().map(TaskResponse::from).toList()
        ));
    }

    private ToolExecutionResult handleClaim(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        UUID callerId = requireCallingAgentId(ctx, "claim");
        // V340 - autonomous backlog pickup is opt-in per agent. A non-participating
        // agent cannot claim shared backlog work (it could be ill-suited / wrongly
        // tooled). NOTE: the human board claim path (AgentTaskController) is a
        // deliberate manual override and stays open - it is NOT gated here.
        if (!taskService.isBacklogEnabled(callerId)) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                    "this agent is not enabled to pick up shared backlog tasks (backlog participation is "
                    + "opt-in per agent). Work the tasks assigned directly to you via agent(action='inbox'), "
                    + "or ask the owner to enable backlog participation for this agent.");
        }
        Map<String, Object> p = mergeParams(params);
        UUID taskId = requireUuid(p, "task_id");
        String organizationId = contextOrgId(ctx);
        // Read the dispatcher-minted executionId from MCP credentials (injected by
        // CliAgentService.startSession). Threaded into claimTask so the claim log
        // entry keys on the same UUID the observability writer persists as
        // agent_executions.id at end-of-run - closes the 2026-05-22 NULL task_id
        // bug at the path the bug actually fires on (schedule fire → MCP task_claim).
        String executionId = null;
        if (ctx != null && ctx.credentials() != null) {
            Object raw = ctx.credentials().get("__executionId__");
            if (raw instanceof String s && !s.isBlank()) {
                executionId = s;
            }
        }
        Optional<AgentTaskEntity> claimed = organizationId != null
                ? taskService.claimTask(tenantId, organizationId, callerId, taskId, executionId)
                : taskService.claimTask(tenantId, callerId, taskId);
        if (claimed.isEmpty()) {
            return ToolExecutionResult.success(Map.of(
                    "claimed", false,
                    "reason", "already_claimed_or_missing"
            ));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("claimed", true);
        out.put("task", TaskResponse.from(claimed.get()));
        return ToolExecutionResult.success(out);
    }

    // ========================================================================
    // Recurrence actions
    // ========================================================================

    private ToolExecutionResult handleRecurrenceCreate(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        UUID callerAgentId = optionalCallingAgentId(ctx);
        String callerUserId = callerAgentId == null ? tenantId : null;
        Map<String, Object> p = mergeParams(params);
        CreateRecurrenceRequest req = new CreateRecurrenceRequest(
                getStringParam(p, "title"),
                getStringParam(p, "instructions"),
                getStringParam(p, "cron"),
                getStringParam(p, "timezone"),
                getUuidParam(p, "target_agent_id"),
                getStringParam(p, "priority"),
                asMap(p.get("task_context"))
        );
        AgentTaskRecurrenceEntity r = recurrenceService.create(tenantId, callerAgentId, callerUserId, req);
        return ToolExecutionResult.success(RecurrenceResponse.from(r));
    }

    private ToolExecutionResult handleRecurrenceList(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        // The service accepts a null callingAgentId and returns all-in-tenant for human callers.
        UUID callerAgentId = optionalCallingAgentId(ctx);
        Map<String, Object> p = mergeParams(params);
        String scope = getStringParam(p, "scope");
        List<AgentTaskRecurrenceEntity> list = recurrenceService.list(tenantId, callerAgentId, scope);
        List<RecurrenceResponse> responses = new ArrayList<>(list.size());
        for (AgentTaskRecurrenceEntity r : list) responses.add(RecurrenceResponse.from(r));
        return ToolExecutionResult.success(Map.of("count", responses.size(), "recurrences", responses));
    }

    private ToolExecutionResult handleRecurrenceUpdate(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        UUID callerAgentId = optionalCallingAgentId(ctx);
        String callerUserId = callerAgentId == null ? tenantId : null;
        Map<String, Object> p = mergeParams(params);
        UUID recurrenceId = requireUuid(p, "recurrence_id");
        UpdateRecurrenceRequest req = new UpdateRecurrenceRequest(
                (Boolean) p.get("enabled"),
                getStringParam(p, "cron"),
                getStringParam(p, "title"),
                getStringParam(p, "instructions"),
                getStringParam(p, "priority")
        );
        AgentTaskRecurrenceEntity r = recurrenceService.update(tenantId, recurrenceId, callerAgentId, callerUserId, req);
        return ToolExecutionResult.success(RecurrenceResponse.from(r));
    }

    private ToolExecutionResult handleRecurrenceDelete(Map<String, Object> params, String tenantId, ToolExecutionContext ctx) {
        UUID callerAgentId = optionalCallingAgentId(ctx);
        String callerUserId = callerAgentId == null ? tenantId : null;
        Map<String, Object> p = mergeParams(params);
        UUID recurrenceId = requireUuid(p, "recurrence_id");
        recurrenceService.delete(tenantId, recurrenceId, callerAgentId, callerUserId);
        return ToolExecutionResult.success(Map.of("deleted", true, "recurrence_id", recurrenceId.toString()));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Returns the calling agent UUID from context credentials, or {@code null} if
     * the call originated from a non-agent caller (e.g. direct chat without an
     * attached agent). Handlers that can fall back to a human caller should call
     * this and pass {@code tenantId} as the user ID when the result is null.
     */
    private static UUID optionalCallingAgentId(ToolExecutionContext ctx) {
        if (ctx == null || ctx.credentials() == null) return null;
        Object raw = ctx.credentials().get("__agentId__");
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("invalid __agentId__ in credentials: " + raw);
        }
    }

    /**
     * Used by agent-only actions (inbox, task_complete, task_reject, claim) that
     * cannot be meaningfully performed by a human caller. Throws a clear message
     * directing the user to attach the conversation to an agent.
     */
    private static UUID requireCallingAgentId(ToolExecutionContext ctx, String action) {
        UUID id = optionalCallingAgentId(ctx);
        if (id != null) return id;
        throw new IllegalStateException(
                "action '" + action + "' requires an agent identity - a direct-chat caller has no inbox "
                + "and cannot execute/claim tasks. Attach this conversation to a specific agent, or use an "
                + "action that humans can perform: assign, backlog, task_update, task_cancel, outbox (single), "
                + "recurrence_create, recurrence_list, recurrence_update, recurrence_delete. "
                + "Note: task_approve, task_reject_review, and review_inbox also require agent identity.");
    }

    private static String getTurnId(ToolExecutionContext ctx) {
        if (ctx == null || ctx.credentials() == null) return null;
        Object turnId = ctx.credentials().get("turnId");
        return turnId == null ? null : turnId.toString();
    }

    private static UUID reviewerExecutionId(ToolExecutionContext ctx) {
        if (ctx == null || ctx.credentials() == null) return null;
        Object raw = ctx.credentials().get("__reviewerExecutionId__");
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("invalid __reviewerExecutionId__ in credentials: " + raw);
        }
    }

    private static String contextOrgId(ToolExecutionContext ctx) {
        if (ctx == null || ctx.orgId() == null || ctx.orgId().isBlank()) {
            return null;
        }
        return ctx.orgId();
    }

    /**
     * Atomically increment the per-turn assign counter with TTL-based eviction.
     * Returns {@code true} if the caller is still within the rate limit, {@code false}
     * if they've exceeded it. Also evicts expired entries and enforces the hard
     * size cap on the tracking map so long-running processes don't leak memory.
     */
    private boolean bumpTurnCounter(String turnId) {
        long now = System.currentTimeMillis();
        evictExpired(now);
        TurnCounter updated = assignsPerTurn.compute(turnId, (k, prev) -> {
            if (prev == null || now - prev.createdAtMillis() > TURN_TTL_MILLIS) {
                return new TurnCounter(1, now);
            }
            return new TurnCounter(prev.count() + 1, prev.createdAtMillis());
        });
        return updated.count() <= MAX_ASSIGNS_PER_TURN;
    }

    /**
     * Drops expired entries and, if the map is still over capacity, drops the
     * oldest entries until it fits. Cheap no-op when the map is small.
     */
    private void evictExpired(long now) {
        if (assignsPerTurn.isEmpty()) return;
        assignsPerTurn.entrySet().removeIf(e -> now - e.getValue().createdAtMillis() > TURN_TTL_MILLIS);
        int overflow = assignsPerTurn.size() - MAX_TRACKED_TURNS;
        if (overflow > 0) {
            assignsPerTurn.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(
                            (a, b) -> Long.compare(a.createdAtMillis(), b.createdAtMillis())))
                    .limit(overflow)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(assignsPerTurn::remove);
        }
    }

    private static UUID requireUuid(Map<String, Object> params, String key) {
        UUID v = getUuidParam(params, key);
        if (v == null) throw new IllegalArgumentException(key + " is required");
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> getAllowedAgentIds(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) return null;
        return ToolAccessControl.getAllowedIds(context.credentials(), "agent");
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid due_by (expected ISO-8601): " + s);
        }
    }
}

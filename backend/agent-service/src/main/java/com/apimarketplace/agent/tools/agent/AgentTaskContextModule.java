package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.domain.AgentExecutionMessageEntity;
import com.apimarketplace.agent.domain.AgentExecutionToolCallEntity;
import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskEventEntity;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.repository.AgentExecutionMessageRepository;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentExecutionToolCallRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.agent.permission.TaskVisibilityResolver;
import com.apimarketplace.agent.tools.agent.permission.TaskVisibilityResolver.Role;
import com.apimarketplace.agent.tools.agent.permission.ToolCallRedactor;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

import static com.apimarketplace.agent.tools.common.ToolParamUtils.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Tasks-as-context module for the {@code agent} tool.
 *
 * <p>Two actions:
 * <ul>
 *   <li>{@code task_get_context(task_id)} - JIT-navigable task summary:
 *       task header + events timeline + executions metadata + ordered execution_ids.
 *       The reviewer/creator/god agent uses this to decide whether the task
 *       was handled correctly. Returns no message bodies - just the keys.</li>
 *   <li>{@code task_get_execution(task_id, execution_id)} - zoom on one
 *       specific execution: full message timeline + tool calls (with secret
 *       redaction). Opt-in detail for the doubt cases (~5-10% of reviews).</li>
 * </ul>
 *
 * <p>Mirrors the {@code workflow.get_run} pattern: the agent first asks for
 * a structured summary with IDs, then drills into specific executions on
 * demand. No N+1, no heavy default payload.
 *
 * <p>Permission is decided ONCE per call via {@link TaskVisibilityResolver}.
 * Roles: GOD (caller has no toolsConfig.agents restriction = primary chat
 * agent), REVIEWER, CREATOR (direct only - no transitive ancestor).
 * The assignee is intentionally NOT granted access here - it has its own
 * conversation already and shouldn't see managerial signals (reviewer
 * identity, internal notes, audit timeline).
 */
@Slf4j
@Component
public class AgentTaskContextModule implements ToolModule {

    private static final Set<String> HANDLED_ACTIONS = Set.of(
            "task_get_context", "task_get_execution");

    /** Soft cap on events surfaced per call to keep payload bounded. */
    private static final int MAX_EVENTS = 50;
    /** Hard cap on messages returned by {@code task_get_execution} per page. */
    private static final int MAX_MESSAGES_PER_PAGE = 100;
    private static final int DEFAULT_MESSAGES_PER_PAGE = 50;
    /**
     * Hard cap on executions surfaced per task. Multi-round agent loops can attach hundreds
     * of executions to a single task; unbounded fetch would balloon the LLM payload and OOM
     * the JVM on heavy workloads.
     */
    private static final int MAX_EXECUTIONS = 50;
    /**
     * Hard cap on tool calls returned when {@code include_tool_calls=true}. Tool-call rows
     * embed full request/response bodies that routinely run MB-scale per row; an un-paginated
     * fetch was the OOM shape called out in the Email Digest incident.
     */
    private static final int MAX_TOOL_CALLS = 100;

    private final AgentService agentService;
    private final AgentExecutionRepository executionRepository;
    private final AgentExecutionMessageRepository messageRepository;
    private final AgentExecutionToolCallRepository toolCallRepository;
    private final AgentTaskEventRepository taskEventRepository;
    private final TaskVisibilityResolver visibilityResolver;
    private final ToolCallRedactor redactor;

    public AgentTaskContextModule(AgentService agentService,
                                   AgentExecutionRepository executionRepository,
                                   AgentExecutionMessageRepository messageRepository,
                                   AgentExecutionToolCallRepository toolCallRepository,
                                   AgentTaskEventRepository taskEventRepository,
                                   TaskVisibilityResolver visibilityResolver,
                                   ToolCallRedactor redactor) {
        this.agentService = agentService;
        this.executionRepository = executionRepository;
        this.messageRepository = messageRepository;
        this.toolCallRepository = toolCallRepository;
        this.taskEventRepository = taskEventRepository;
        this.visibilityResolver = visibilityResolver;
        this.redactor = redactor;
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String action) {
        return HANDLED_ACTIONS.contains(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(action)) return Optional.empty();
        return Optional.of(switch (action) {
            case "task_get_context" -> executeGetContext(parameters, tenantId, context);
            case "task_get_execution" -> executeGetExecution(parameters, tenantId, context);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown action: " + action);
        });
    }

    // ==================== task_get_context ====================

    private ToolExecutionResult executeGetContext(Map<String, Object> parameters, String tenantId,
                                                   ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        UUID taskId = getUuidParam(p, "task_id");
        if (taskId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "Parameter 'task_id' is required.");
        }
        UUID callerAgentId = getCallerAgentId(context);

        // Permission check (single DB roundtrip - also returns the task entity)
        var resolved = visibilityResolver.resolveRoleAndTask(callerAgentId, taskId, tenantId, context);
        if (!resolved.granted()) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "Not authorized to read task " + taskId + ". Allowed roles: REVIEWER, CREATOR, or god agent.");
        }

        AgentTaskEntity task = resolved.task();
        Role role = resolved.role();

        // Load events + executions paginated DESC at the DB level. We pull at most MAX_EVENTS
        // / MAX_EXECUTIONS rows and surface the total count to the agent so it knows when
        // history is truncated. Unbounded fetches would balloon both the LLM payload and the
        // JVM heap on long-running tasks.
        org.springframework.data.domain.Page<AgentTaskEventEntity> eventsPage =
                taskEventRepository.findByTaskIdOrderByCreatedAtDesc(
                        taskId, org.springframework.data.domain.PageRequest.of(0, MAX_EVENTS));
        // Batch A2 (2026-05-20) - route through the org-strict finder when the
        // task itself is tagged with an organizationId (V261 makes this always
        // non-null on new rows). Closes the cross-workspace read where a
        // tenant-only finder would pull executions tagged with a different org.
        String taskOrgId = task.getOrganizationId();
        org.springframework.data.domain.Page<AgentExecutionEntity> executionsPage;
        if (task.getAssignedToAgentId() == null) {
            executionsPage = new org.springframework.data.domain.PageImpl<>(java.util.List.of());
        } else if (taskOrgId != null && !taskOrgId.isBlank()) {
            executionsPage = executionRepository.findByTaskIdAndOrganizationIdStrictOrderByStartedAtDesc(
                    taskId, taskOrgId,
                    org.springframework.data.domain.PageRequest.of(0, MAX_EXECUTIONS));
        } else {
            executionsPage = executionRepository.findByTaskIdAndTenantIdOrderByStartedAtDesc(
                    taskId, tenantId,
                    org.springframework.data.domain.PageRequest.of(0, MAX_EXECUTIONS));
        }

        // Build response
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", buildTaskBlock(task));
        result.put("events", buildEventsBlock(eventsPage));
        result.put("executions", buildExecutionsBlock(executionsPage));
        result.put("viewer_role", role.name());
        result.put("hint", "Call agent(action='task_get_execution', task_id='" + taskId
                + "', execution_id='<id>') to drill into a specific execution's messages and tool calls.");

        return ToolExecutionResult.success(result);
    }

    private Map<String, Object> buildTaskBlock(AgentTaskEntity task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", task.getId().toString());
        m.put("title", task.getTitle());
        m.put("instructions", task.getInstructions());
        m.put("status", task.getStatus());
        m.put("priority", task.getPriority());
        m.put("assignee_agent_id", str(task.getAssignedToAgentId()));
        m.put("assignee_agent_name", resolveAgentName(task.getAssignedToAgentId(), task.getTenantId()));
        m.put("creator_agent_id", str(task.getCreatedByAgentId()));
        m.put("reviewer_agent_id", str(task.getReviewerAgentId()));
        m.put("parent_task_id", str(task.getParentTaskId()));
        m.put("result", task.getResult());
        m.put("error_message", task.getErrorMessage());
        m.put("created_at", instantOrNull(task.getCreatedAt()));
        m.put("updated_at", instantOrNull(task.getUpdatedAt()));
        return m;
    }

    private Map<String, Object> buildEventsBlock(org.springframework.data.domain.Page<AgentTaskEventEntity> eventsPage) {
        // Repo returned DESC (newest first); reverse so the agent reads chronologically.
        List<AgentTaskEventEntity> head = new ArrayList<>(eventsPage.getContent());
        java.util.Collections.reverse(head);
        List<Map<String, Object>> items = new ArrayList<>(head.size());
        for (AgentTaskEventEntity ev : head) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", ev.getId());
            e.put("event_type", ev.getEventType());
            e.put("actor_type", ev.getActorType());
            e.put("actor_id", ev.getActorId());
            e.put("created_at", instantOrNull(ev.getCreatedAt()));
            items.add(e);
        }
        long total = eventsPage.getTotalElements();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("total", total);
        block.put("shown", head.size());
        block.put("truncated", total > head.size());
        block.put("items", items);
        return block;
    }

    private List<Map<String, Object>> buildExecutionsBlock(
            org.springframework.data.domain.Page<AgentExecutionEntity> executionsPage) {
        // Order by started_at ASC for the agent (chronological reading order),
        // even though the repo gave us DESC. Reviewing typically goes earliest
        // attempt → latest, so it matches reading expectation.
        List<AgentExecutionEntity> chrono = new ArrayList<>(executionsPage.getContent());
        chrono.sort(Comparator.comparing(
                AgentExecutionEntity::getStartedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<Map<String, Object>> out = new ArrayList<>(chrono.size());
        int order = 0;
        for (AgentExecutionEntity ex : chrono) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("execution_id", ex.getId().toString());
            e.put("order", order++);
            e.put("status", ex.getStatus());
            e.put("started_at", instantOrNull(ex.getStartedAt()));
            e.put("ended_at", instantOrNull(ex.getEndedAt()));
            e.put("duration_ms", ex.getDurationMs());
            e.put("model", ex.getModel());
            e.put("provider", ex.getProvider());
            e.put("iteration_count", ex.getIterationCount());
            e.put("message_count", ex.getMessageCount());
            e.put("total_tool_calls", ex.getTotalToolCalls());
            e.put("successful_tool_calls", ex.getSuccessfulToolCalls());
            e.put("failed_tool_calls", ex.getFailedToolCalls());
            e.put("distinct_tools", ex.getDistinctTools() == null
                    ? List.of() : ex.getDistinctTools());
            e.put("loop_detected", ex.isLoopDetected());
            if (ex.isLoopDetected()) {
                e.put("loop_type", ex.getLoopType());
                e.put("loop_tool_name", ex.getLoopToolName());
            }
            e.put("credits_consumed", ex.getCreditsConsumed());
            out.add(e);
        }
        return out;
    }

    // ==================== task_get_execution ====================

    private ToolExecutionResult executeGetExecution(Map<String, Object> parameters, String tenantId,
                                                     ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        UUID taskId = getUuidParam(p, "task_id");
        UUID executionId = getUuidParam(p, "execution_id");
        if (taskId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "Parameter 'task_id' is required.");
        }
        if (executionId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "Parameter 'execution_id' is required.");
        }
        Integer limit = getIntParam(p, "limit", DEFAULT_MESSAGES_PER_PAGE);
        if (limit == null || limit <= 0) limit = DEFAULT_MESSAGES_PER_PAGE;
        if (limit > MAX_MESSAGES_PER_PAGE) limit = MAX_MESSAGES_PER_PAGE;
        Integer offset = getIntParam(p, "offset", 0);
        if (offset == null || offset < 0) offset = 0;
        boolean includeToolCalls = Boolean.TRUE.equals(p.get("include_tool_calls"));

        UUID callerAgentId = getCallerAgentId(context);

        // Permission via the same task-level resolver
        var resolved = visibilityResolver.resolveRoleAndTask(callerAgentId, taskId, tenantId, context);
        if (!resolved.granted()) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "Not authorized to read task " + taskId + ". Allowed roles: REVIEWER, CREATOR, or god agent.");
        }
        AgentTaskEntity task = resolved.task();

        // Verify the execution actually belongs to this task in this tenant.
        // Avoid TOCTOU: don't fetch by id then check task - fetch with full predicate.
        Optional<AgentExecutionEntity> execOpt = executionRepository.findById(executionId);
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (execOpt.isEmpty()
                || !Objects.equals(execOpt.get().getTaskId(), taskId)
                || !ScopeGuard.isInStrictScope(tenantId, orgId, execOpt.get().getTenantId(), execOpt.get().getOrganizationId())) {
            // 404-style - don't leak existence vs auth distinction
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Execution " + executionId + " not found for task " + taskId + ".");
        }
        AgentExecutionEntity exec = execOpt.get();

        // Paginate at the DB level - offset/limit honored via aligned PageRequest. Long agent
        // loops can persist thousands of messages per execution and tool-call rows carry
        // MB-scale request/response payloads; un-paginated fetch OOM'd the JVM under load.
        //
        // CRITICAL - when {@code offset} is NOT a multiple of {@code limit}, we fetch the
        // covering DB page (which holds at most {@code limit} rows from {@code pageBoundaryFrom})
        // and slice in-memory. The returned slice can be SHORTER than {@code limit} (only the
        // tail of the page is visible to the caller). The {@code to} watermark MUST reflect the
        // ACTUAL last row returned - not the request's {@code offset + limit} - otherwise the
        // hint advises the agent to skip ahead past unread rows. Example with offset=75 limit=50
        // total=300: page=1 holds [50,100), slice is [75,100) (25 rows). {@code to} must be 100,
        // not 125, so the agent's next call resumes at 100 and reads [100,150) cleanly. The
        // previous formula `to = min(from + limit, total)` lost [100,125) silently.
        int pageNumber = offset / limit;
        int pageBoundaryFrom = pageNumber * limit;
        org.springframework.data.domain.Page<AgentExecutionMessageEntity> dbPage =
                messageRepository.findByExecutionIdOrderBySequenceNumber(
                        executionId, org.springframework.data.domain.PageRequest.of(pageNumber, limit));
        int totalMessages = (int) Math.min(dbPage.getTotalElements(), Integer.MAX_VALUE);
        int from = Math.min(offset, totalMessages);
        List<AgentExecutionMessageEntity> page;
        int to;
        if (from >= totalMessages) {
            page = java.util.List.of();
            to = from;
        } else {
            List<AgentExecutionMessageEntity> dbContent = dbPage.getContent();
            int sliceFrom = from - pageBoundaryFrom;
            int sliceTo = Math.min(from + limit - pageBoundaryFrom, dbContent.size());
            page = sliceFrom >= sliceTo ? java.util.List.of() : new java.util.ArrayList<>(dbContent.subList(sliceFrom, sliceTo));
            // `to` = first row NOT returned in this response. Caller resumes from here.
            to = from + page.size();
        }

        // Tool calls (loaded only when requested - capped at MAX_TOOL_CALLS most recent).
        // The agent can drill into older tool calls via the dedicated REST endpoint if needed.
        org.springframework.data.domain.Page<AgentExecutionToolCallEntity> toolCallsPage = includeToolCalls
                ? toolCallRepository.findByExecutionIdOrderBySequenceNumberDesc(
                        executionId, org.springframework.data.domain.PageRequest.of(0, MAX_TOOL_CALLS))
                : new org.springframework.data.domain.PageImpl<>(java.util.List.of());
        // Reverse to ASC for chronological reading.
        List<AgentExecutionToolCallEntity> toolCalls = new java.util.ArrayList<>(toolCallsPage.getContent());
        java.util.Collections.reverse(toolCalls);
        boolean toolCallsTruncated = includeToolCalls && toolCallsPage.getTotalElements() > toolCalls.size();

        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> taskMin = new LinkedHashMap<>();
        taskMin.put("id", task.getId().toString());
        taskMin.put("title", task.getTitle());
        taskMin.put("status", task.getStatus());
        result.put("task", taskMin);

        Map<String, Object> execBlock = new LinkedHashMap<>();
        execBlock.put("execution_id", exec.getId().toString());
        execBlock.put("status", exec.getStatus());
        execBlock.put("started_at", instantOrNull(exec.getStartedAt()));
        execBlock.put("ended_at", instantOrNull(exec.getEndedAt()));
        execBlock.put("iteration_count", exec.getIterationCount());
        execBlock.put("total_tool_calls", exec.getTotalToolCalls());
        execBlock.put("model", exec.getModel());
        result.put("execution", execBlock);

        result.put("messages", buildMessagesBlock(page, totalMessages, from, to));
        if (includeToolCalls) {
            result.put("tool_calls", buildToolCallsBlock(toolCalls, toolCallsPage.getTotalElements(), toolCallsTruncated));
        }
        result.put("hint", to < totalMessages
                ? "More messages available. Call again with offset=" + to + "."
                : "All messages returned.");
        return ToolExecutionResult.success(result);
    }

    private Map<String, Object> buildMessagesBlock(List<AgentExecutionMessageEntity> page,
                                                    int total, int from, int to) {
        List<Map<String, Object>> items = new ArrayList<>(page.size());
        for (AgentExecutionMessageEntity m : page) {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("sequence_number", m.getSequenceNumber());
            i.put("iteration_number", m.getIterationNumber());
            i.put("role", m.getRole());
            i.put("tool_name", m.getToolName());
            i.put("tool_call_id", m.getToolCallId());
            // Content may carry tool args/results - redact secrets before surfacing.
            // The redactor handles JSON and falls back to raw scrub for plain text.
            String redactedContent = redactor.redactJsonString(m.getContent(), m.getToolName());
            i.put("content", redactedContent);
            i.put("content_length", m.getContentLength());
            items.add(i);
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("total", total);
        block.put("offset", from);
        block.put("returned", page.size());
        block.put("has_more", to < total);
        block.put("items", items);
        return block;
    }

    /**
     * Build the tool-calls response block. Wraps the items with {@code total}/{@code shown}/
     * {@code truncated} metadata so the agent knows when older tool calls were dropped (we
     * cap at {@link #MAX_TOOL_CALLS} most recent because each row can carry an MB-scale
     * request/response payload).
     */
    private Map<String, Object> buildToolCallsBlock(List<AgentExecutionToolCallEntity> calls,
                                                     long total, boolean truncated) {
        List<Map<String, Object>> items = new ArrayList<>(calls.size());
        for (AgentExecutionToolCallEntity tc : calls) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("sequence_number", tc.getSequenceNumber());
            c.put("iteration_number", tc.getIterationNumber());
            c.put("tool_call_id", tc.getToolCallId());
            c.put("tool_name", tc.getToolName());
            c.put("parallel_index", tc.getParallelIndex());
            c.put("success", tc.isSuccess());
            // Redact arguments AND content (results) - both can carry credentials.
            c.put("arguments", redactor.redactMap(tc.getArguments(), tc.getToolName()));
            c.put("content", redactor.redactJsonString(tc.getContent(), tc.getToolName()));
            items.add(c);
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("total", total);
        block.put("shown", items.size());
        block.put("truncated", truncated);
        block.put("items", items);
        return block;
    }

    // ==================== Helpers ====================

    private UUID getCallerAgentId(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) return null;
        Object raw = context.credentials().get("__agentId__");
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            log.debug("Invalid __agentId__ in context: {}", raw);
            return null;
        }
    }

    private String resolveAgentName(UUID agentId, String tenantId) {
        if (agentId == null) return null;
        try {
            return agentService.getAgent(agentId, tenantId)
                    .map(AgentEntity::getName).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(UUID v) { return v == null ? null : v.toString(); }

    private static String instantOrNull(java.time.LocalDateTime t) {
        return t == null ? null : t.toInstant(java.time.ZoneOffset.UTC).toString();
    }

    private static String instantOrNull(Instant t) {
        return t == null ? null : t.toString();
    }
}

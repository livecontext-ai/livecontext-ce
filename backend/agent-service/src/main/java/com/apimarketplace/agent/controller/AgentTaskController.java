package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.dto.AddNoteRequest;
import com.apimarketplace.agent.dto.BulkTaskRequest;
import com.apimarketplace.agent.dto.CreateTaskRequest;
import com.apimarketplace.agent.dto.TaskResponse;
import com.apimarketplace.agent.dto.UpdateTaskRequest;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.domain.AgentTaskEventEntity;
import com.apimarketplace.agent.domain.AgentTaskNoteEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * User-facing REST API for agent task delegation.
 * <p>
 * Endpoints surface the same operations as the {@code agent} tool's delegation actions
 * so humans can browse, create, and close tasks from an admin UI without going through
 * an agent.
 * <p>
 * All endpoints are tenant-scoped via {@code X-User-ID}. Agent-to-agent calls continue
 * to go through {@code AgentDelegationModule} - this controller is strictly for human
 * callers, so {@code callingAgentId} is always {@code null} and {@code callingUserId}
 * is the resolved tenant.
 */
@RestController
@RequestMapping("/api")
public class AgentTaskController {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskController.class);

    private final AgentTaskService taskService;
    private final TenantResolver tenantResolver;
    private final AgentRepository agentRepository;
    private final AgentTaskRepository taskRepository;
    private final AgentTaskEventRepository taskEventRepository;
    private final AgentTaskNoteRepository noteRepository;
    private final AgentExecutionRepository executionRepository;
    private final com.apimarketplace.agent.service.TaskResponseEnricher taskEnricher;

    public AgentTaskController(AgentTaskService taskService,
                               TenantResolver tenantResolver,
                               AgentRepository agentRepository,
                               AgentTaskRepository taskRepository,
                               AgentTaskEventRepository taskEventRepository,
                               AgentTaskNoteRepository noteRepository,
                               AgentExecutionRepository executionRepository,
                               com.apimarketplace.agent.service.TaskResponseEnricher taskEnricher) {
        this.taskService = taskService;
        this.tenantResolver = tenantResolver;
        this.agentRepository = agentRepository;
        this.taskRepository = taskRepository;
        this.taskEventRepository = taskEventRepository;
        this.noteRepository = noteRepository;
        this.executionRepository = executionRepository;
        this.taskEnricher = taskEnricher;
    }

    /**
     * Guardrails the user-provided {@code as_agent_id} by checking the agent
     * actually belongs to the caller's workspace. Without this any authenticated
     * user could impersonate an arbitrary agent UUID, potentially acting on
     * tasks they have no legitimate access to.
     * <p>
     * Batch A2 (2026-05-20) - routes through the org-strict membership check
     * when an orgId is in the request scope. The caller's role gate is
     * already enforced upstream by the gateway via {@code X-Organization-Role}.
     */
    private void assertAgentBelongsToTenant(UUID agentId, String tenantId) {
        String orgScope = TenantResolver.currentRequestOrganizationId();
        boolean ok = (orgScope != null && !orgScope.isBlank())
                ? agentRepository.existsByIdAndOrganizationIdStrict(agentId, orgScope)
                : agentRepository.existsByIdAndTenantId(agentId, tenantId);
        if (!ok) {
            throw new IllegalStateException(
                    "agent " + agentId + " does not belong to the caller's tenant");
        }
    }

    // ========================================================================
    // Board ordering (F1)
    // ========================================================================

    public record ReorderTasksRequest(java.util.List<String> orderedTaskIds) {}

    /**
     * Apply a manual within-column order after a drag. Body lists the column's
     * task ids in their new top-to-bottom order; each is stamped with a sequential
     * board rank. Ranks only matter within a column, so callers send one column's
     * order at a time.
     */
    @PutMapping("/tasks/rank")
    public ResponseEntity<?> reorderTasks(@RequestBody ReorderTasksRequest body, HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        if (body == null || body.orderedTaskIds() == null || body.orderedTaskIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "orderedTaskIds is required"));
        }
        try {
            List<UUID> ids = new ArrayList<>(body.orderedTaskIds().size());
            for (String s : body.orderedTaskIds()) ids.add(UUID.fromString(s));
            List<AgentTaskEntity> updated = taskService.reorderBoardTasks(tenantId, ids);
            return ResponseEntity.ok(Map.of(
                    "count", updated.size(),
                    "tasks", updated.stream().map(TaskResponse::from).toList()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record EstimateBody(Integer estimateMinutes, Boolean clearEstimate,
                               Integer timeSpentMinutes, Boolean clearTimeSpent) {}

    /** Set a task's estimate and/or logged time in minutes (F12). */
    @PutMapping("/tasks/{taskId}/estimate")
    public ResponseEntity<?> setEstimate(@PathVariable UUID taskId, @RequestBody EstimateBody body,
                                         HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        tenantResolver.resolveOrgId(request);
        try {
            AgentTaskEntity t = taskService.setTaskEstimate(tenantId, taskId, null, tenantId,
                    body == null ? null : body.estimateMinutes(),
                    body != null && Boolean.TRUE.equals(body.clearEstimate()),
                    body == null ? null : body.timeSpentMinutes(),
                    body != null && Boolean.TRUE.equals(body.clearTimeSpent()));
            return ResponseEntity.ok(TaskResponse.from(t));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record BlockersBody(List<String> blockedByIds) {}

    /** Replace a task's blocker set (F9 dependencies). */
    @PutMapping("/tasks/{taskId}/blockers")
    public ResponseEntity<?> setBlockers(@PathVariable UUID taskId, @RequestBody BlockersBody body,
                                         HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        tenantResolver.resolveOrgId(request);
        try {
            List<String> ids = body == null ? List.of() : body.blockedByIds();
            AgentTaskEntity t = taskService.setTaskBlockers(tenantId, taskId, null, tenantId, ids);
            return ResponseEntity.ok(TaskResponse.from(t));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record ChecklistBody(List<Map<String, Object>> items) {}
    public record AttachmentsBody(List<Map<String, Object>> attachments) {}

    /** Replace a task's checklist (F10). */
    @PutMapping("/tasks/{taskId}/checklist")
    public ResponseEntity<?> setChecklist(@PathVariable UUID taskId, @RequestBody ChecklistBody body,
                                          HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        tenantResolver.resolveOrgId(request);
        try {
            AgentTaskEntity t = taskService.setTaskChecklist(tenantId, taskId, null, tenantId,
                    body == null ? null : body.items());
            return ResponseEntity.ok(TaskResponse.from(t));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Replace a task's attachments (F10). Each links a file already uploaded to storage by key. */
    @PutMapping("/tasks/{taskId}/attachments")
    public ResponseEntity<?> setAttachments(@PathVariable UUID taskId, @RequestBody AttachmentsBody body,
                                            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        tenantResolver.resolveOrgId(request);
        try {
            AgentTaskEntity t = taskService.setTaskAttachments(tenantId, taskId, null, tenantId,
                    body == null ? null : body.attachments());
            return ResponseEntity.ok(TaskResponse.from(t));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ========================================================================
    // Listing endpoints
    // ========================================================================

    /** List all tasks assigned to a specific agent (its inbox). */
    @Transactional(readOnly = true)
    @GetMapping("/agents/{agentId}/tasks/inbox")
    public ResponseEntity<?> listInbox(
            @PathVariable UUID agentId,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        // PR23 - strict-isolation: org workspace sees org-tagged inbox only.
        String orgId = tenantResolver.resolveOrgId(request);
        try {
            assertAgentBelongsToTenant(agentId, tenantId);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
        List<AgentTaskEntity> list = taskService.getInboxList(tenantId, orgId, agentId, clampLimit(limit, 200));
        return ResponseEntity.ok(Map.of(
                "count", list.size(),
                "tasks", list.stream().map(TaskResponse::from).toList()
        ));
    }

    /** List all tasks created by a specific agent (its outbox). */
    @Transactional(readOnly = true)
    @GetMapping("/agents/{agentId}/tasks/outbox")
    public ResponseEntity<?> listOutbox(
            @PathVariable UUID agentId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        try {
            assertAgentBelongsToTenant(agentId, tenantId);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
        List<AgentTaskEntity> list = taskService.getOutbox(tenantId, orgId, agentId, status, clampLimit(limit, 200));
        return ResponseEntity.ok(Map.of(
                "count", list.size(),
                "tasks", list.stream().map(TaskResponse::from).toList()
        ));
    }

    /** List tasks awaiting a specific agent's review. */
    @Transactional(readOnly = true)
    @GetMapping("/agents/{agentId}/tasks/reviews")
    public ResponseEntity<?> listReviewInbox(
            @PathVariable UUID agentId,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        try {
            assertAgentBelongsToTenant(agentId, tenantId);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
        List<AgentTaskEntity> list = taskService.getReviewInbox(tenantId, orgId, agentId, clampLimit(limit, 200));
        return ResponseEntity.ok(Map.of(
                "count", list.size(),
                "tasks", list.stream().map(TaskResponse::from).toList()
        ));
    }

    /** List the workspace-wide backlog (unassigned tasks any agent may claim). */
    @Transactional(readOnly = true)
    @GetMapping("/tasks/backlog")
    public ResponseEntity<Map<String, Object>> listBacklog(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        List<AgentTaskEntity> list = taskService.getBacklog(tenantId, orgId, clampLimit(limit, 200));
        return ResponseEntity.ok(Map.of(
                "count", list.size(),
                "tasks", list.stream().map(TaskResponse::from).toList()
        ));
    }

    // ========================================================================
    // Board endpoints - tenant-scoped, no agent perspective needed
    // ========================================================================

    /** List all tasks for the workspace with filters + pagination. */
    @Transactional(readOnly = true)
    @GetMapping("/tasks")
    public ResponseEntity<Map<String, Object>> listTasks(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "assignedTo", required = false) String assignedTo,
            @RequestParam(value = "createdBy", required = false) String createdBy,
            @RequestParam(value = "priority", required = false) String priority,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "parentTaskId", required = false) String parentTaskId,
            @RequestParam(value = "sort", defaultValue = "updated_at") String sort,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        // PR23 - strict-isolation board: org workspace sees org-tagged rows only.
        String orgId = tenantResolver.resolveOrgId(request);
        int clampedSize = Math.min(Math.max(size, 1), 200);
        int clampedPage = Math.max(page, 0);
        int offset = clampedPage * clampedSize;

        // "unassigned" is a sentinel value - filter for assigned_to_agent_id IS NULL
        boolean unassigned = "unassigned".equalsIgnoreCase(assignedTo != null ? assignedTo.trim() : null);

        String statusParam = nullIfBlank(status);
        String assignedToParam = unassigned ? null : validUuidOrNull(assignedTo);
        String createdByParam = validUuidOrNull(createdBy);
        String priorityParam = nullIfBlank(priority);
        String searchParam = nullIfBlank(search);
        String parentParam = nullIfBlank(parentTaskId);
        // 'manual' = order by board_rank (F1 drag ordering). The query supports it (ORDER BY t.board_rank).
        String sortParam = Set.of("priority", "due_by", "created_at", "updated_at", "manual").contains(sort) ? sort : "updated_at";

        TenantResolver.requireOrgId(orgId);
        List<AgentTaskEntity> tasks = taskRepository.findAllFilteredByOrganizationIdStrict(
                orgId, statusParam, unassigned, assignedToParam, createdByParam,
                priorityParam, searchParam, parentParam, sortParam,
                clampedSize, offset);
        long total = taskRepository.countAllFilteredByOrganizationIdStrict(
                orgId, statusParam, unassigned, assignedToParam, createdByParam,
                priorityParam, searchParam, parentParam);

        return ResponseEntity.ok(Map.of(
                "tasks", taskEnricher.enrichAll(tasks.stream().map(TaskResponse::from).toList()),
                "total", total,
                "page", clampedPage,
                "size", clampedSize
        ));
    }

    /** Aggregated task counts by status. */
    @GetMapping("/tasks/stats")
    public ResponseEntity<Map<String, Object>> getStats(HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        TenantResolver.requireOrgId(orgId);
        // PR23 - strict-isolation board stats. Org workspace sees only org-tagged counts.
        List<Object[]> rows = taskRepository.countByStatusGroupedByOrganizationIdStrict(orgId);
        long backlog = taskRepository.countBacklogByOrganizationIdStrict(orgId);

        Map<String, Object> stats = new LinkedHashMap<>();
        long pending = 0, inProgress = 0, inReview = 0, completed = 0, failed = 0, cancelled = 0, deleted = 0, total = 0;
        for (Object[] row : rows) {
            String s = (String) row[0];
            long c = (Long) row[1];
            total += c;
            switch (s) {
                case "pending" -> pending = c;
                case "in_progress" -> inProgress = c;
                case "in_review" -> inReview = c;
                case "completed" -> completed = c;
                case "failed" -> failed = c;
                case "cancelled" -> cancelled = c;
                case "deleted" -> deleted = c;
            }
        }
        stats.put("pending", pending);
        stats.put("inProgress", inProgress);
        stats.put("inReview", inReview);
        stats.put("completed", completed);
        stats.put("failed", failed);
        stats.put("cancelled", cancelled);
        stats.put("deleted", deleted);
        stats.put("backlog", backlog);
        stats.put("total", total);
        return ResponseEntity.ok(stats);
    }

    private static final int DETAIL_NOTES_CAP = 50;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * Tenant-scoped detail - no agent perspective needed.
     * <p>
     * Notes are capped to the {@link #DETAIL_NOTES_CAP} most recent and returned in
     * chronological (ASC) order for display, so tasks with very long note threads
     * (rare) don't pin the JVM heap or stall the UI. The cap is generous enough that
     * typical task threads remain fully visible; if a user needs deeper browse, we'd
     * add a paginated /notes endpoint then.
     */
    @Transactional(readOnly = true)
    @GetMapping("/tasks/{taskId}/detail")
    public ResponseEntity<?> getTaskDetail(
            @PathVariable UUID taskId,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        // PR23 - strict-isolation scope check before reading the task + notes.
        String orgId = tenantResolver.resolveOrgId(request);
        Optional<AgentTaskEntity> opt = taskService.findTaskForScope(taskId, tenantId, orgId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        Page<AgentTaskNoteEntity> recentNotes = noteRepository.findByTaskIdOrderByCreatedAtDesc(
                taskId, PageRequest.of(0, DETAIL_NOTES_CAP));
        // Reverse to ASC so the panel renders oldest→newest like before.
        List<AgentTaskNoteEntity> notes = new java.util.ArrayList<>(recentNotes.getContent());
        java.util.Collections.reverse(notes);
        return ResponseEntity.ok(taskEnricher.enrich(TaskResponse.from(opt.get(), notes)));
    }

    /**
     * Audit trail events for a task.
     * <p>
     * Paginated DESC (page 0 = newest). Frontend reverses for chronological display
     * and triggers loadOlder on scroll-up, same pattern as the conversation history.
     * Default size 30 / max 100.
     */
    @Transactional(readOnly = true)
    @GetMapping("/tasks/{taskId}/events")
    public ResponseEntity<?> getTaskEvents(
            @PathVariable UUID taskId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "30") int size,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        // PR23 - Verify task belongs to caller's workspace scope before fetching events.
        if (taskService.findTaskForScope(taskId, tenantId, orgId).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
        Page<AgentTaskEventEntity> events = taskEventRepository.findByTaskIdOrderByCreatedAtDesc(taskId, pageable);
        return ResponseEntity.ok(events);
    }

    /** Direct children of a task. */
    @Transactional(readOnly = true)
    @GetMapping("/tasks/{taskId}/children")
    public ResponseEntity<?> getTaskChildren(
            @PathVariable UUID taskId,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        if (taskService.findTaskForScope(taskId, tenantId, orgId).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        TenantResolver.requireOrgId(orgId);
        // PR23 - child listing also scope-strict, so a parent visible to org users
        // cannot leak personal-scope children (if a misplaced row ever existed).
        List<AgentTaskEntity> children = taskRepository.findByParentTaskIdAndOrganizationIdStrict(orgId, taskId);
        return ResponseEntity.ok(taskEnricher.enrichAll(children.stream().map(TaskResponse::from).toList()));
    }

    /**
     * Executions linked to a task (via task_id column on agent_executions).
     * <p>
     * Paginated DESC (page 0 = newest run). Default size 20 / max 100. Multi-round agent
     * loops can attach hundreds of executions to a single task - unpaginated load would
     * OOM the JVM for long-running tasks.
     */
    @Transactional(readOnly = true)
    @GetMapping("/tasks/{taskId}/executions")
    public ResponseEntity<?> getTaskExecutions(
            @PathVariable UUID taskId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        if (taskService.findTaskForScope(taskId, tenantId, orgId).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        // PR23 - the parent task scope-check has already confirmed workspace match;
        // the child executions inherit the same scope by construction (PR20 stamping
        // mirror agent_executions.organization_id from the task scope at write time).
        // Batch A2 - route through the org-strict paged finder so the workspace
        // boundary is enforced at the SQL level too.
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
        Page<AgentExecutionEntity> executions = (orgId != null && !orgId.isBlank())
                ? executionRepository.findByTaskIdAndOrganizationIdStrictOrderByStartedAtDesc(taskId, orgId, pageable)
                : executionRepository.findByTaskIdAndTenantIdOrderByStartedAtDesc(taskId, tenantId, pageable);
        return ResponseEntity.ok(executions);
    }

    // ========================================================================
    // Single-task endpoints
    // ========================================================================

    /**
     * Fetch a single task scoped to the caller's perspective. The {@code as} query parameter
     * is required and names the agent whose perspective the task should be loaded under
     * (assignee-side for inbox semantics, then creator-side for outbox semantics as a
     * fallback). This is the same authorization the {@code agent} tool uses.
     */
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<?> getTask(
            @PathVariable UUID taskId,
            @RequestParam("as") UUID asAgentId,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        // PR23 - workspace scope on both inbox + outbox lookups.
        String orgId = tenantResolver.resolveOrgId(request);
        try {
            assertAgentBelongsToTenant(asAgentId, tenantId);
            try {
                return ResponseEntity.ok(taskService.getInboxTask(tenantId, orgId, asAgentId, taskId));
            } catch (IllegalStateException inboxDenied) {
                return ResponseEntity.ok(taskService.getOutboxTask(tenantId, orgId, asAgentId, null, taskId));
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tasks")
    public ResponseEntity<?> createTask(
            @RequestBody CreateTaskRequest request,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        try {
            AgentTaskEntity t = taskService.assignTask(tenantId, null, tenantId, request);
            return ResponseEntity.ok(TaskResponse.from(t));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * PR-JALON2 - scope pre-check helper. Verifies the caller's workspace
     * matches the task's organization_id before invoking the tenant-only
     * mutation service path. Mirror of InternalAgentTaskController.isTaskInScope
     * from PR26. Closes the public-controller cross-scope mutation hole.
     */
    private boolean isTaskInScope(UUID taskId, String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        return taskRepository.findByIdAndOrganizationIdStrict(taskId, organizationId).isPresent();
    }

    @PatchMapping("/tasks/{taskId}")
    public ResponseEntity<?> updateTask(
            @PathVariable UUID taskId,
            @RequestBody UpdateTaskRequest request,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        if (!isTaskInScope(taskId, tenantId, organizationId)) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        try {
            AgentTaskEntity t = taskService.updateTask(tenantId, taskId, null, tenantId, request);
            return ResponseEntity.ok(TaskResponse.from(t));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<?> cancelOrDeleteTask(
            @PathVariable UUID taskId,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "hard", required = false, defaultValue = "false") boolean hard,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        if (!isTaskInScope(taskId, tenantId, organizationId)) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        try {
            if (hard) {
                int deleted = taskService.hardDeleteTask(tenantId, taskId, tenantId);
                return ResponseEntity.ok(Map.of("deleted_count", deleted));
            }
            int cancelled = taskService.cancelTask(tenantId, taskId, null, tenantId, reason);
            return ResponseEntity.ok(Map.of("cancelled_count", cancelled));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    /** Upper bound on a single bulk request - matches the board's full-load window. */
    private static final int MAX_BULK_TASKS = 200;
    private static final Set<String> BULK_ACTIONS = Set.of("cancel", "delete", "restore", "purge");

    /**
     * Bulk task-board action: apply one {@code action} to a set of task ids, per-item
     * (partial success). Backs the board's multi-select bar and single-card drag onto the
     * Deleted column. Each id is scope-checked then routed to the matching service method
     * in its own transaction; a failing id is reported in {@code results} and does not
     * abort the others. Actions:
     * <ul>
     *   <li>{@code cancel}  - cascading cancel → 'cancelled' (terminal/stale rows are
     *       requalified directly, mirroring the single-card move-to-cancelled fallback)</li>
     *   <li>{@code delete}  - soft-delete → Deleted column (restorable, 30-day retention)</li>
     *   <li>{@code restore} - Deleted → previous column</li>
     *   <li>{@code purge}   - permanent hard-delete of a trashed task (tenant owner only)</li>
     * </ul>
     */
    @PostMapping("/tasks/bulk")
    public ResponseEntity<?> bulkTaskAction(
            @RequestBody BulkTaskRequest request,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        String action = request.action() == null ? "" : request.action().trim().toLowerCase();
        if (!BULK_ACTIONS.contains(action)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid action: " + request.action() + " (expected one of cancel, delete, restore, purge)"));
        }
        List<UUID> ids = request.taskIds() == null ? List.of() : request.taskIds();
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "taskIds is required"));
        }
        if (ids.size() > MAX_BULK_TASKS) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "too many tasks (max " + MAX_BULK_TASKS + " per request)"));
        }

        TenantResolver.requireOrgId(organizationId);
        List<Map<String, Object>> results = new ArrayList<>();
        int succeeded = 0, failed = 0;
        for (UUID id : ids) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task_id", id == null ? null : id.toString());
            try {
                if (id == null) {
                    throw new IllegalArgumentException("task not found");
                }
                // Load once: serves the workspace-scope check AND lets the cancel path
                // refuse a trashed row (so a direct API cancel can't silently un-trash it).
                AgentTaskEntity task = taskRepository.findByIdAndOrganizationIdStrict(id, organizationId)
                        .orElseThrow(() -> new IllegalArgumentException("task not found"));
                applyBulkAction(action, tenantId, task, request.reason());
                row.put("ok", true);
                succeeded++;
            } catch (Exception e) {
                row.put("ok", false);
                row.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                failed++;
            }
            results.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("requested", ids.size());
        body.put("succeeded", succeeded);
        body.put("failed", failed);
        body.put("results", results);
        return ResponseEntity.ok(body);
    }

    /** Routes one bulk task to its service call. Human caller ⇒ callingAgentId=null, callingUserId=tenant. */
    private void applyBulkAction(String action, String tenantId, AgentTaskEntity task, String reason) {
        UUID id = task.getId();
        switch (action) {
            case "cancel" -> {
                // A trashed task must be restored before it can be cancelled - otherwise the
                // requalification PATCH below would silently pull it out of the trash.
                if (AgentTaskEntity.STATUS_DELETED.equals(task.getStatus())) {
                    throw new IllegalStateException("task is in the Deleted column - restore it before cancelling");
                }
                int n = taskService.cancelTask(tenantId, id, null, tenantId, reason);
                if (n == 0) {
                    // Terminal or stale (cascade no-ops on completed/failed) - requalify
                    // directly to cancelled, same as the single-card move-to-cancelled path.
                    taskService.updateTask(tenantId, id, null, tenantId,
                            new UpdateTaskRequest(null, null, null, null, null, null, null,
                                    AgentTaskEntity.STATUS_CANCELLED));
                }
            }
            case "delete" -> taskService.softDeleteTask(tenantId, id, null, tenantId);
            case "restore" -> taskService.restoreTask(tenantId, id, null, tenantId);
            case "purge" -> taskService.purgeDeletedTask(tenantId, id, tenantId);
            default -> throw new IllegalArgumentException("invalid action: " + action);
        }
    }

    // ========================================================================
    // Transition endpoints - these require an agent to act as the assignee
    // ========================================================================

    @PostMapping("/tasks/{taskId}/complete")
    public ResponseEntity<?> completeTask(
            @PathVariable UUID taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        // Audit 2026-05-17 round-4 - scope pre-check on the task before mutation.
        if (!isTaskInScope(taskId, tenantId, organizationId)) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        UUID asAgentId = parseUuid(body.get("as_agent_id"));
        String result = (String) body.get("result");
        if (asAgentId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "as_agent_id is required"));
        }
        if (result == null || result.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "result is required"));
        }
        Object rawForce = body.get("force");
        boolean force = rawForce instanceof Boolean b ? b
                : rawForce != null && Boolean.parseBoolean(rawForce.toString());
        try {
            assertAgentBelongsToTenant(asAgentId, tenantId);
            AgentTaskEntity t = taskService.completeTask(tenantId, taskId, asAgentId, result, force);
            return ResponseEntity.ok(TaskResponse.from(t));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tasks/{taskId}/reject")
    public ResponseEntity<?> rejectTask(
            @PathVariable UUID taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        if (!isTaskInScope(taskId, tenantId, organizationId)) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        UUID asAgentId = parseUuid(body.get("as_agent_id"));
        String reason = (String) body.get("reason");
        if (asAgentId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "as_agent_id is required"));
        }
        try {
            assertAgentBelongsToTenant(asAgentId, tenantId);
            AgentTaskEntity t = taskService.rejectTask(tenantId, taskId, asAgentId, reason);
            return ResponseEntity.ok(TaskResponse.from(t));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tasks/{taskId}/approve")
    public ResponseEntity<?> approveTask(
            @PathVariable UUID taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        if (!isTaskInScope(taskId, tenantId, organizationId)) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        UUID asAgentId = parseUuid(body.get("as_agent_id"));
        try {
            AgentTaskEntity t;
            if (asAgentId != null) {
                // Reviewer agent approves
                assertAgentBelongsToTenant(asAgentId, tenantId);
                t = taskService.approveTask(tenantId, taskId, asAgentId);
            } else {
                // Tenant owner (user) approves from the task board
                t = taskService.approveTaskByUser(tenantId, taskId);
            }
            return ResponseEntity.ok(TaskResponse.from(t));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tasks/{taskId}/reject-review")
    public ResponseEntity<?> rejectReview(
            @PathVariable UUID taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        if (!isTaskInScope(taskId, tenantId, organizationId)) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        UUID asAgentId = parseUuid(body.get("as_agent_id"));
        String reason = (String) body.get("reason");
        try {
            AgentTaskEntity t;
            if (asAgentId != null) {
                // Reviewer agent rejects
                assertAgentBelongsToTenant(asAgentId, tenantId);
                t = taskService.rejectReview(tenantId, taskId, asAgentId, reason);
            } else {
                // Tenant owner (user) requests changes from the task board
                t = taskService.rejectReviewByUser(tenantId, taskId, reason);
            }
            return ResponseEntity.ok(TaskResponse.from(t));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/tasks/{taskId}/claim")
    public ResponseEntity<?> claimTask(
            @PathVariable UUID taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        if (!isTaskInScope(taskId, tenantId, organizationId)) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        UUID asAgentId = parseUuid(body.get("as_agent_id"));
        if (asAgentId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "as_agent_id is required"));
        }
        try {
            assertAgentBelongsToTenant(asAgentId, tenantId);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
        Optional<AgentTaskEntity> claimed = taskService.claimTask(tenantId, organizationId, asAgentId, taskId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (claimed.isEmpty()) {
            out.put("claimed", false);
            out.put("reason", "already_claimed_or_missing");
            return ResponseEntity.ok(out);
        }
        out.put("claimed", true);
        out.put("task", TaskResponse.from(claimed.get()));
        return ResponseEntity.ok(out);
    }

    // ========================================================================
    // Stop agent execution
    // ========================================================================

    /**
     * Stop a running agent on a task. Sets the Redis cancel key to halt the agent
     * and clears the execution lock so the task becomes editable again.
     */
    @PostMapping("/tasks/{taskId}/stop-agent")
    public ResponseEntity<?> stopAgent(
            @PathVariable UUID taskId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        if (!isTaskInScope(taskId, tenantId, organizationId)) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        String role = body.get("role") != null ? body.get("role").toString() : null;
        if (role == null || (!role.equals("assignee") && !role.equals("reviewer"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "role must be 'assignee' or 'reviewer'"));
        }
        try {
            TaskResponse result = taskService.stopAgentExecution(tenantId, organizationId, taskId, role);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    // ========================================================================
    // Notes
    // ========================================================================

    @PostMapping("/tasks/{taskId}/notes")
    public ResponseEntity<?> addNote(
            @PathVariable UUID taskId,
            @RequestBody AddNoteRequest request,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        if (!isTaskInScope(taskId, tenantId, organizationId)) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        if (request == null || request.content() == null || request.content().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }
        try {
            var note = taskService.addNote(tenantId, taskId, null, tenantId,
                    request.content(), request.mentionedUserIds());
            return ResponseEntity.ok(Map.of(
                    "note_id", note.getId().toString(),
                    "task_id", taskId.toString()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static int clampLimit(int limit, int max) {
        if (limit <= 0) return 20;
        return Math.min(limit, max);
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Returns the trimmed string if it is a valid UUID, null otherwise. */
    private static String validUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            UUID.fromString(s.trim());
            return s.trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID parseUuid(Object raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

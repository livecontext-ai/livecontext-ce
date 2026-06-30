package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.dto.CreateTaskRequest;
import com.apimarketplace.agent.dto.TaskResponse;
import com.apimarketplace.agent.dto.TaskSummaryResponse;
import com.apimarketplace.agent.dto.UpdateTaskRequest;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.agent.service.ScheduledTaskPromptBuilder;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Internal endpoints for task-delegation integration with other services.
 * <p>
 * Used by orchestrator-service's {@code ScheduleExecutorService} (via {@code AgentClient})
 * to inject dynamic task-inbox prompts at schedule fire time, and by conversation-service
 * to inject a compact task-summary block into the agent's system prompt.
 * <p>
 * Endpoint prefix: {@code /api/internal/agents}. No gateway authentication - internal network only.
 */
@RestController
@RequestMapping("/api/internal/agents")
public class InternalAgentTaskController {

    private static final Logger logger = LoggerFactory.getLogger(InternalAgentTaskController.class);

    private final ScheduledTaskPromptBuilder scheduledPromptBuilder;
    private final AgentTaskService taskService;
    private final TenantResolver tenantResolver;
    private final AgentRepository agentRepository;
    private final AgentTaskRepository taskRepository;
    private final AgentTaskNoteRepository noteRepository;

    public InternalAgentTaskController(ScheduledTaskPromptBuilder scheduledPromptBuilder,
                                        AgentTaskService taskService,
                                        TenantResolver tenantResolver,
                                        AgentRepository agentRepository,
                                        AgentTaskRepository taskRepository,
                                        AgentTaskNoteRepository noteRepository) {
        this.scheduledPromptBuilder = scheduledPromptBuilder;
        this.taskService = taskService;
        this.tenantResolver = tenantResolver;
        this.agentRepository = agentRepository;
        this.taskRepository = taskRepository;
        this.noteRepository = noteRepository;
    }

    /**
     * Defence-in-depth scope check for the internal path. The endpoint is
     * internal-only, but binding the path {@code agentId} to the workspace
     * scope (org when in scope, tenant otherwise) closes the door on
     * compromised upstream callers leaking cross-workspace data.
     * <p>
     * Batch A2 (2026-05-20) - org-aware routing through the V261 strict
     * finder when {@link TenantResolver#currentRequestOrganizationId()} is
     * populated (forwarders propagate {@code X-Organization-ID} through
     * service hops + scheduler daemons bind via {@code runWithOrgScope}).
     */
    private void assertAgentBelongsToTenant(UUID agentId, String tenantId) {
        String orgScope = TenantResolver.currentRequestOrganizationId();
        boolean ok = (orgScope != null && !orgScope.isBlank())
                ? agentRepository.existsByIdAndOrganizationIdStrict(agentId, orgScope)
                : agentRepository.existsByIdAndTenantId(agentId, tenantId);
        if (!ok) {
            throw new IllegalStateException(
                    "agent " + agentId + " does not belong to tenant " + tenantId);
        }
    }

    /**
     * PR26 - verifies the task is in the caller's workspace scope BEFORE the
     * tenant-only mutation service path runs. Pre-PR26 a personal-scope caller
     * could pass an org-tagged taskId and the tenant-only update/cancel paths
     * would succeed (cross-scope mutation hole on internal endpoints).
     *
     * @return true if the task is visible in the caller's scope.
     */
    private boolean isTaskInScope(UUID taskId, String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        return taskRepository.findByIdAndOrganizationIdStrict(taskId, organizationId).isPresent();
    }

    /**
     * Build the effective prompt to send to an agent when its schedule fires.
     * Body may contain {@code fallback} - the static schedule_prompt to use when the
     * agent has no pending tasks and no claimable backlog.
     */
    @PostMapping("/{agentId}/scheduled-prompt")
    public ResponseEntity<Map<String, Object>> buildScheduledPrompt(
            @PathVariable UUID agentId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String fallback = body != null && body.get("fallback") instanceof String s ? s : null;
        try {
            String tenantId = tenantResolver.resolve(request);
            String organizationId = tenantResolver.resolveOrgId(request);
            assertAgentBelongsToTenant(agentId, tenantId);
            // PR26 - thread orgId into the prompt builder so the task workload
            // returned to the agent matches its workspace scope. Pre-PR26 a
            // personal-scope agent received org-tagged tasks (and vice versa).
            String prompt = scheduledPromptBuilder.build(tenantId, organizationId, agentId, fallback);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("prompt", prompt == null ? "" : prompt);
            out.put("dynamic", prompt != null && !prompt.equals(fallback));
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            logger.error("Failed to build scheduled prompt for agent {}: {}", agentId, e.getMessage(), e);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("prompt", fallback == null ? "" : fallback);
            out.put("dynamic", false);
            out.put("error", e.getMessage());
            return ResponseEntity.ok(out);
        }
    }

    /**
     * Compact task summary for system-prompt injection.
     * Returns counts + a pre-rendered {@code promptSection} ready to paste into a system prompt.
     */
    @GetMapping("/{agentId}/task-summary")
    public ResponseEntity<Map<String, Object>> taskSummary(
            @PathVariable UUID agentId,
            @RequestParam(value = "sinceHours", defaultValue = "24") int sinceHours,
            HttpServletRequest request) {
        try {
            String tenantId = tenantResolver.resolve(request);
            // Audit 2026-05-17 round-4 - read X-Organization-ID so service-side
            // counts route to the org-scoped queries (closes leak where org-
            // teammate daemon-fired schedules saw tenant-wide task summary).
            String organizationId = tenantResolver.resolveOrgId(request);
            assertAgentBelongsToTenant(agentId, tenantId);
            Instant since = Instant.now().minus(Duration.ofHours(Math.max(1, sinceHours)));
            TaskSummaryResponse summary = taskService.getTaskSummaryForPrompt(tenantId, organizationId, agentId, since);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pendingCount", summary.pendingCount());
            out.put("completedOutboxCount", summary.completedOutboxCount());
            out.put("backlogCount", summary.backlogCount());
            out.put("hasTasks", summary.hasTasks());
            out.put("promptSection", summary.toPromptSection());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            logger.error("Failed to get task summary for agent {}: {}", agentId, e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                "pendingCount", 0,
                "completedOutboxCount", 0,
                "backlogCount", 0,
                "hasTasks", false,
                "promptSection", "",
                "error", e.getMessage()
            ));
        }
    }

    // ========================================================================
    // Task CRUD - called by orchestrator-service TaskNode via AgentClient
    // ========================================================================

    /**
     * Create a task from a workflow (no calling agent - human/workflow perspective).
     */
    @PostMapping("/tasks")
    public ResponseEntity<?> createTaskInternal(
            @RequestBody CreateTaskRequest request,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        try {
            AgentTaskEntity t = taskService.assignTask(tenantId, null, tenantId, request);
            return ResponseEntity.ok(TaskResponse.from(t));
        } catch (Exception e) {
            logger.error("Internal create task failed for tenant={}: {}", tenantId, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get a single task by ID.
     */
    @Transactional(readOnly = true)
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<?> getTaskInternal(
            @PathVariable UUID taskId,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        try {
            TenantResolver.requireOrgId(organizationId);
            // PR26 - scope-strict read. Pre-PR26 a personal-scope caller could
            // read org-tagged tasks (and vice versa) via this internal endpoint.
            Optional<AgentTaskEntity> opt =
                    taskRepository.findByIdAndOrganizationIdStrict(taskId, organizationId);
            if (opt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "task not found"));
            }
            var notes = noteRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
            return ResponseEntity.ok(TaskResponse.from(opt.get(), notes));
        } catch (Exception e) {
            logger.error("Internal get task failed id={}: {}", taskId, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Update a task (title, instructions, priority, status, assignee).
     */
    @PutMapping("/tasks/{taskId}")
    public ResponseEntity<?> updateTaskInternal(
            @PathVariable UUID taskId,
            @RequestBody UpdateTaskRequest request,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        // PR26 - scope pre-check before the tenant-only mutation path runs.
        // Closes the cross-scope mutation hole (personal caller updating an
        // org-tagged task by guessing the ID, or vice versa).
        if (!isTaskInScope(taskId, tenantId, organizationId)) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        try {
            AgentTaskEntity t = taskService.updateTask(tenantId, taskId, null, tenantId, request);
            return ResponseEntity.ok(TaskResponse.from(t));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Internal update task failed id={}: {}", taskId, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Cancel (soft delete) a task.
     */
    @DeleteMapping("/tasks/{taskId}")
    public ResponseEntity<?> deleteTaskInternal(
            @PathVariable UUID taskId,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        // PR26 - scope pre-check before the tenant-only cancel path runs.
        if (!isTaskInScope(taskId, tenantId, organizationId)) {
            return ResponseEntity.status(404).body(Map.of("error", "task not found"));
        }
        try {
            int cancelled = taskService.cancelTask(tenantId, taskId, null, tenantId, null);
            return ResponseEntity.ok(Map.of("cancelled_count", cancelled, "success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Internal delete task failed id={}: {}", taskId, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * List tasks with filters.
     * <p>
     * Workspace-strict since the TaskNode org-scope audit (2026-06-10): the
     * board endpoint and the internal get/update/delete paths were already
     * org-scoped (PR23/PR26), but this list used the tenant-wide finder and
     * leaked tasks across workspaces of the same tenant - a workflow running
     * in workspace A could list workspace B's tasks (which {@code get_task}
     * then 404'd on). Same org-strict finder as the board now.
     */
    @Transactional(readOnly = true)
    @GetMapping("/tasks")
    public ResponseEntity<?> listTasksInternal(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "assignedTo", required = false) String assignedTo,
            @RequestParam(value = "priority", required = false) String priority,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "page", defaultValue = "0") int page,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        try {
            TenantResolver.requireOrgId(organizationId);

            int clampedSize = Math.min(Math.max(size, 1), 200);
            int clampedPage = Math.max(page, 0);
            int offset = clampedPage * clampedSize;

            boolean unassigned = "unassigned".equalsIgnoreCase(assignedTo != null ? assignedTo.trim() : null);
            String statusParam = nullIfBlank(status);
            String assignedToParam = unassigned ? null : validUuidOrNull(assignedTo);
            String priorityParam = nullIfBlank(priority);
            String searchParam = nullIfBlank(search);

            List<AgentTaskEntity> tasks = taskRepository.findAllFilteredByOrganizationIdStrict(
                    organizationId, statusParam, unassigned, assignedToParam, null,
                    priorityParam, searchParam, null, "updated_at",
                    clampedSize, offset);
            long total = taskRepository.countAllFilteredByOrganizationIdStrict(
                    organizationId, statusParam, unassigned, assignedToParam, null,
                    priorityParam, searchParam, null);

            return ResponseEntity.ok(Map.of(
                    "tasks", tasks.stream().map(TaskResponse::from).toList(),
                    "count", tasks.size(),
                    "total", total,
                    "page", clampedPage,
                    "size", clampedSize
            ));
        } catch (Exception e) {
            logger.error("Internal list tasks failed for tenant={}: {}", tenantId, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static String nullIfBlank(String s) {
        return s != null && !s.isBlank() ? s.trim() : null;
    }

    private static String validUuidOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            UUID.fromString(s.trim());
            return s.trim();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

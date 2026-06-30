package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskEventEntity;
import com.apimarketplace.agent.domain.AgentTaskNoteEntity;
import com.apimarketplace.agent.domain.TaskStatusCategory;
import com.apimarketplace.agent.dto.AgentTaskDispatchView;
import com.apimarketplace.agent.dto.CreateTaskRequest;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.conversation.client.StreamRedisKeys;
import com.apimarketplace.agent.dto.TaskResponse;
import com.apimarketplace.agent.dto.TaskSummaryResponse;
import com.apimarketplace.agent.dto.UpdateTaskRequest;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.service.execution.AgentActivityPublisher;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Core business logic for the agent task delegation system.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Create / assign / backlog tasks (with implicit parent inference + depth cap)</li>
 *   <li>Inbox / outbox / backlog queries (all strictly tenant-scoped)</li>
 *   <li>Claim a backlog task (optimistic single-SQL swap)</li>
 *   <li>Complete / reject / cancel / update lifecycle transitions (all optimistic)</li>
 *   <li>Cascading cancel via recursive CTE</li>
 *   <li>Task summary counts for system prompt injection</li>
 * </ul>
 */
@Service
public class AgentTaskService {

    private static final Logger logger = LoggerFactory.getLogger(AgentTaskService.class);

    public static final int MAX_TASK_DEPTH = 5;
    /** Default cap on reviewer reject attempts before the task is auto-failed. Per-task override: {@code agent_tasks.max_review_attempts}. */
    public static final int MAX_REVIEW_ATTEMPTS = 3;
    /** Hard upper bound on the per-task override (prevents a malicious/mistaken 10_000-loop config). */
    public static final int MAX_REVIEW_ATTEMPTS_CEILING = 20;
    private static final long REVIEW_RETRY_DELAY_SECONDS = 30;

    /** Dedup window for identical assigns (bug #9). Weak LLMs often retry assign while the first is still in flight. */
    private static final long ASSIGN_DEDUP_WINDOW_SECONDS = 60;
    public static final int MAX_INSTRUCTIONS_BYTES = 50 * 1024;
    public static final int MAX_RESULT_BYTES = 50 * 1024;
    public static final int MAX_TASK_CONTEXT_BYTES = 50 * 1024;

    private static final Set<String> VALID_PRIORITIES =
            Set.of(AgentTaskEntity.PRIORITY_LOW,
                   AgentTaskEntity.PRIORITY_NORMAL,
                   AgentTaskEntity.PRIORITY_HIGH,
                   AgentTaskEntity.PRIORITY_URGENT);

    /**
     * Alias map for lenient priority parsing. LLMs (and humans) routinely use
     * synonyms like "medium", "critical", "p1", etc. instead of our canonical
     * four values. Rather than fail the call, we map every reasonable variant
     * to its canonical bucket. Keys are already lowercased and trimmed.
     */
    private static final Map<String, String> PRIORITY_ALIASES;
    static {
        Map<String, String> m = new HashMap<>();
        // --- low ---
        for (String s : new String[]{
                "low", "lo", "l", "minor", "trivial", "minimal", "cold",
                "nice-to-have", "nice_to_have", "nicetohave", "backlog",
                "whenever", "someday", "later", "p4", "p5", "priority4", "priority5"
        }) m.put(s, AgentTaskEntity.PRIORITY_LOW);
        // --- normal ---
        for (String s : new String[]{
                "normal", "norm", "n", "medium", "med", "mid", "middle", "moderate",
                "standard", "std", "default", "regular", "regulr", "average",
                "ordinary", "typical", "usual", "warm", "p3", "priority3"
        }) m.put(s, AgentTaskEntity.PRIORITY_NORMAL);
        // --- high ---
        for (String s : new String[]{
                "high", "hi", "h", "important", "major", "elevated", "priority",
                "prioritized", "significant", "hot", "p2", "priority2"
        }) m.put(s, AgentTaskEntity.PRIORITY_HIGH);
        // --- urgent ---
        for (String s : new String[]{
                "urgent", "urg", "u", "critical", "crit", "c", "blocker", "blocking",
                "emergency", "asap", "now", "immediate", "immediately", "top",
                "highest", "max", "maximum", "fire", "burning", "severe", "severe-1",
                "sev1", "sev0", "p0", "p1", "priority0", "priority1"
        }) m.put(s, AgentTaskEntity.PRIORITY_URGENT);
        PRIORITY_ALIASES = Map.copyOf(m);
    }

    private final AgentTaskRepository taskRepository;
    private final AgentTaskNoteRepository noteRepository;
    private final AgentTaskEventRepository eventRepository;
    private final AgentRepository agentRepository;
    private final AgentExecutionRepository executionRepository;
    private final TaskBoardPublisher taskBoardPublisher;
    private final ConversationClient conversationClient;

    /**
     * Append-only claim log writer. See {@link com.apimarketplace.agent.domain.AgentTaskClaimEntity}.
     * Optional only so the older test-only constructor (no-arg) keeps working without wiring; the
     * Spring-built instance always has it.
     */
    @Autowired(required = false)
    private com.apimarketplace.agent.repository.AgentTaskClaimRepository claimRepository;

    /**
     * P6: cross-service notification client. Optional ({@code required=false})
     * so existing AgentTaskService unit tests pass without wiring it. When
     * unwired, {@link #emitTaskAssignedAfterCommit} is a no-op.
     */
    @Autowired(required = false)
    private NotificationClient notificationClient;

    /**
     * Resolves org membership when validating a HUMAN task assignee / reviewer
     * (a person must belong to the task's workspace). Optional ({@code required=false})
     * so existing AgentTaskService unit tests construct without wiring it; when
     * unwired the membership check is skipped (no human-assignee path is exercised
     * in those tests). The Spring-built instance always has it.
     */
    @Autowired(required = false)
    private AuthClient authClient;

    /**
     * Redis template for setting the agent:cancel:{streamId} key to stop a running agent.
     * Field-injected optional so test-only constructors work without wiring.
     */
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /**
     * Fleet activity publisher - used to stamp the claimed taskId onto the
     * agent's in-flight execution via a synthetic {@code tool_call_started}
     * event. Without this, the task board cannot shimmer the card while the
     * agent runs (the bridge sync path emits {@code execution_started} with
     * {@code taskId=null} because the agent picks the task via MCP after
     * dispatch - see ConversationAgentService.executeSync). Field-injected
     * {@code required=false} so the test-only constructors stay no-arg.
     */
    @Autowired(required = false)
    private AgentActivityPublisher agentActivityPublisher;

    /**
     * Optional board-status registry (F4). When wired (the Spring instance), a
     * task status key is resolved to its {@link TaskStatusCategory} against the
     * task's board so the board/human path ({@link #updateTask} / restore)
     * accepts custom columns. Unwired (the test-only constructors) falls back to
     * the seven historical default keys, so legacy unit tests are unaffected.
     */
    @Autowired(required = false)
    private TaskStatusService taskStatusService;

    /**
     * Optional label catalog (F2). When wired, {@link #setTaskLabels} validates
     * label ids against the board catalog before storing them on the task.
     * Unwired (test-only constructors) stores the ids as given.
     */
    @Autowired(required = false)
    private TaskLabelService taskLabelService;

    /**
     * Self-reference used to invoke {@link #recordEvent} via the Spring proxy,
     * so the method's {@code REQUIRES_NEW} advice actually kicks in. {@link Lazy}
     * avoids the circular-reference error at startup.
     */
    private final AgentTaskService self;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    public AgentTaskService(AgentTaskRepository taskRepository,
                            AgentTaskNoteRepository noteRepository,
                            AgentTaskEventRepository eventRepository,
                            AgentRepository agentRepository,
                            AgentExecutionRepository executionRepository,
                            TaskBoardPublisher taskBoardPublisher,
                            ConversationClient conversationClient,
                            @Lazy AgentTaskService self) {
        this.taskRepository = taskRepository;
        this.noteRepository = noteRepository;
        this.eventRepository = eventRepository;
        this.agentRepository = agentRepository;
        this.executionRepository = executionRepository;
        this.taskBoardPublisher = taskBoardPublisher;
        this.conversationClient = conversationClient;
        this.self = self;
    }

    /** Test-only constructor (no conversationClient). Self-reference falls back to {@code this}. */
    public AgentTaskService(AgentTaskRepository taskRepository,
                            AgentTaskNoteRepository noteRepository,
                            AgentTaskEventRepository eventRepository,
                            AgentRepository agentRepository) {
        this(taskRepository, noteRepository, eventRepository, agentRepository, null);
    }

    /** Test-only constructor with optional conversationClient. */
    public AgentTaskService(AgentTaskRepository taskRepository,
                            AgentTaskNoteRepository noteRepository,
                            AgentTaskEventRepository eventRepository,
                            AgentRepository agentRepository,
                            ConversationClient conversationClient) {
        this.taskRepository = taskRepository;
        this.noteRepository = noteRepository;
        this.eventRepository = eventRepository;
        this.agentRepository = agentRepository;
        this.executionRepository = null;
        this.taskBoardPublisher = null;
        this.conversationClient = conversationClient;
        this.self = this;
    }

    // ========================================================================
    // Internal helpers - org-aware repository routing
    // ========================================================================

    /**
     * Batch A2 (2026-05-20) - workspace-scoped task lookup used by every
     * internal re-read in this service. Resolves {@code organizationId} from
     * the request thread-local ({@link TenantResolver#currentRequestOrganizationId()}
     * - populated by the HTTP request filter AND by
     * {@link TenantResolver#runWithOrgScope(String, Runnable)} on async forks)
     * and routes to {@link AgentTaskRepository#findByIdAndOrganizationIdStrict}.
     * <p>
     * Falls back to the legacy {@code findByIdAndTenantId} finder ONLY when no
     * orgId is in scope (test contexts, or pre-PR16 callers that didn't yet
     * forward the header). Post-V261 every row has a non-null
     * {@code organization_id} so the org-strict path is the production hot
     * path.
     */
    private Optional<AgentTaskEntity> findTaskByIdScoped(UUID taskId, String tenantId) {
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (orgId != null && !orgId.isBlank()) {
            return taskRepository.findByIdAndOrganizationIdStrict(taskId, orgId);
        }
        return taskRepository.findByIdAndTenantId(taskId, tenantId);
    }

    private Optional<AgentEntity> findAgentByIdScoped(UUID agentId) {
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (orgId != null && !orgId.isBlank()) {
            return agentRepository.findByIdAndOrganizationIdStrict(agentId, orgId);
        }
        return agentRepository.findById(agentId);
    }

    private Optional<AgentTaskDispatchView> findAgentTaskDispatchViewScoped(UUID agentId) {
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (orgId != null && !orgId.isBlank()) {
            return agentRepository.findTaskDispatchViewByIdAndOrganizationIdStrict(agentId, orgId);
        }
        return agentRepository.findTaskDispatchViewById(agentId);
    }

    /** Batch A2 - org-aware variant of {@link AgentTaskRepository#submitForReview}. */
    private int submitForReviewScoped(UUID taskId, String tenantId, UUID agentId, String result) {
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (orgId != null && !orgId.isBlank()) {
            return taskRepository.submitForReviewByOrganizationIdStrict(taskId, tenantId, orgId, agentId, result);
        }
        return taskRepository.submitForReview(taskId, tenantId, agentId, result);
    }

    /** Batch A2 - org-aware variant of {@link AgentTaskRepository#submitFailureForReview}. */
    private int submitFailureForReviewScoped(UUID taskId, String tenantId, UUID agentId, String reason) {
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (orgId != null && !orgId.isBlank()) {
            return taskRepository.submitFailureForReviewByOrganizationIdStrict(taskId, tenantId, orgId, agentId, reason);
        }
        return taskRepository.submitFailureForReview(taskId, tenantId, agentId, reason);
    }

    // ========================================================================
    // Assignment
    // ========================================================================

    /**
     * Creates a task. If {@code request.agentId()} is {@code null}, the task
     * lands in the tenant backlog. Otherwise it is immediately assigned.
     *
     * @param tenantId        required
     * @param callingAgentId  the agent creating the task ({@code null} for human callers)
     * @param callingUserId   the human creating the task ({@code null} for agent callers)
     */
    @Transactional
    public AgentTaskEntity assignTask(String tenantId,
                                       UUID callingAgentId,
                                       String callingUserId,
                                       CreateTaskRequest request) {
        return assignTask(tenantId, callingAgentId, callingUserId, request, true);
    }

    /**
     * Overload allowing the caller to suppress the centralized async kickoff.
     * <p>
     * {@code autoTriggerWorker=true} (default) preserves legacy behavior: after persistence
     * the assignee worker is dispatched async and will promote {@code pending → in_progress}
     * once it acquires the assignee lock.
     * <p>
     * {@code autoTriggerWorker=false} creates the row and returns. The task stays in
     * {@code pending} indefinitely until something else (a future {@code inbox(task_id)}
     * call from the assignee, a manual UI claim, or the assignee's own activation
     * trigger like {@code schedule_cron}) picks it up. This is the path used by:
     * <ul>
     *   <li>UI form-style task creation where the human poster does NOT want auto-execution.</li>
     *   <li>LLM agents calling {@code assign} with {@code start_mode='pending'}
     *       - "post the work, the assignee will run it on its own next activation".</li>
     * </ul>
     */
    public AgentTaskEntity assignTask(String tenantId,
                                       UUID callingAgentId,
                                       String callingUserId,
                                       CreateTaskRequest request,
                                       boolean autoTriggerWorker) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(request, "request");
        if (isBlank(request.title())) {
            throw new IllegalArgumentException("title is required");
        }
        if (isBlank(request.instructions())) {
            throw new IllegalArgumentException("instructions are required");
        }
        if (utf8Bytes(request.instructions()) > MAX_INSTRUCTIONS_BYTES) {
            throw new IllegalArgumentException(
                    "instructions exceed maximum size of " + MAX_INSTRUCTIONS_BYTES + " bytes");
        }
        if (request.taskContext() != null
                && utf8Bytes(request.taskContext().toString()) > MAX_TASK_CONTEXT_BYTES) {
            throw new IllegalArgumentException(
                    "task_context exceeds maximum size of " + MAX_TASK_CONTEXT_BYTES + " bytes");
        }
        String priority = normalizePriority(request.priority());

        Integer maxReviewAttempts = request.maxReviewAttempts();
        if (maxReviewAttempts != null) {
            if (maxReviewAttempts < 1) {
                throw new IllegalArgumentException(
                        "max_review_attempts must be >= 1 (got " + maxReviewAttempts + ")");
            }
            if (maxReviewAttempts > MAX_REVIEW_ATTEMPTS_CEILING) {
                throw new IllegalArgumentException(
                        "max_review_attempts must be <= " + MAX_REVIEW_ATTEMPTS_CEILING
                        + " (got " + maxReviewAttempts + ")");
            }
        }

        UUID assigneeId = request.agentId();
        UUID reviewerId = request.reviewerAgentId();
        String assigneeUserId = trimToNull(request.assigneeUserId());
        String reviewerUserId = trimToNull(request.reviewerUserId());

        // An assignee is an agent XOR a person; same for the reviewer. A HUMAN
        // assignee never triggers auto-execution - the kickoff below is gated on
        // assigneeId (the AGENT id), so a person-assigned task stays put as a
        // Jira-style card the person moves manually.
        if (assigneeId != null && assigneeUserId != null) {
            throw new IllegalArgumentException("a task is assigned to an agent XOR a person, not both");
        }
        if (reviewerId != null && reviewerUserId != null) {
            throw new IllegalArgumentException("a reviewer is an agent XOR a person, not both");
        }
        if (assigneeUserId != null && assigneeUserId.equals(reviewerUserId)) {
            throw new IllegalArgumentException("reviewer and assignee cannot be the same person");
        }
        if (assigneeId != null) {
            if (assigneeId.equals(callingAgentId)) {
                throw new IllegalArgumentException("an agent cannot assign a task to itself");
            }
            AgentEntity assignee = findAgentByIdScoped(assigneeId)
                    .orElseThrow(() -> new IllegalArgumentException("assignee agent not found: " + assigneeId));
            String orgId = TenantResolver.currentRequestOrganizationId();
            if (!ScopeGuard.isInStrictScope(tenantId, orgId, assignee.getTenantId(), assignee.getOrganizationId())) {
                throw new IllegalArgumentException("assignee agent belongs to a different tenant");
            }
        }
        if (reviewerId != null) {
            if (assigneeId != null && reviewerId.equals(assigneeId)) {
                throw new IllegalArgumentException("reviewer and assignee cannot be the same agent");
            }
            AgentEntity reviewer = findAgentByIdScoped(reviewerId)
                    .orElseThrow(() -> new IllegalArgumentException("reviewer agent not found: " + reviewerId));
            String revOrgId = TenantResolver.currentRequestOrganizationId();
            if (!ScopeGuard.isInStrictScope(tenantId, revOrgId, reviewer.getTenantId(), reviewer.getOrganizationId())) {
                throw new IllegalArgumentException("reviewer agent belongs to a different tenant");
            }
        }

        // A HUMAN assignee/reviewer must belong to the task's workspace. Skipped
        // when authClient is unwired (unit-test construction); the Spring instance
        // always has it. Fails closed: a non-member (or an auth-service outage that
        // returns an empty member set) is rejected rather than assigned/notified.
        if (authClient != null && (assigneeUserId != null || reviewerUserId != null)) {
            String memberOrgId = TenantResolver.currentRequestOrganizationId();
            Set<String> members = authClient.getOrganizationMemberIds(memberOrgId);
            if (assigneeUserId != null) requireWorkspaceMember(members, assigneeUserId, "assignee");
            if (reviewerUserId != null) requireWorkspaceMember(members, reviewerUserId, "reviewer");
        }

        // Parent task resolution:
        // 1. Explicit parentTaskId in request (human-created subtask) takes priority
        // 2. Otherwise, implicit inference from calling agent's in-progress task
        UUID parentTaskId = null;
        int depth = 0;
        if (request.parentTaskId() != null) {
            AgentTaskEntity parent = findTaskByIdScoped(request.parentTaskId(), tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("parent task not found: " + request.parentTaskId()));
            if (parent.getDepth() + 1 > MAX_TASK_DEPTH) {
                throw new IllegalStateException(
                        "task depth limit reached (max=" + MAX_TASK_DEPTH + ")");
            }
            parentTaskId = parent.getId();
            depth = parent.getDepth() + 1;
        } else if (callingAgentId != null) {
            Optional<AgentTaskEntity> parent = taskRepository
                    .findTopByTenantIdAndAssignedToAgentIdAndStatusOrderByStartedAtDesc(
                            tenantId, callingAgentId, AgentTaskEntity.STATUS_IN_PROGRESS);
            if (parent.isPresent()) {
                AgentTaskEntity p = parent.get();
                if (p.getDepth() + 1 > MAX_TASK_DEPTH) {
                    throw new IllegalStateException(
                            "task depth limit reached (max=" + MAX_TASK_DEPTH + ")");
                }
                parentTaskId = p.getId();
                depth = p.getDepth() + 1;
            }
        }

        // Bug #9 dedup - reject identical assigns from the same agent to the same assignee
        // within a short window while the first is still open. Prevents weak LLMs from spamming
        // the same delegation when they ignore the task_id they already received.
        if (callingAgentId != null && assigneeId != null) {
            Instant dedupAfter = Instant.now().minusSeconds(ASSIGN_DEDUP_WINDOW_SECONDS);
            List<AgentTaskEntity> dupes = taskRepository.findRecentDuplicateAssigns(
                    tenantId, callingAgentId, assigneeId, request.title(),
                    dedupAfter, PageRequest.of(0, 1));
            if (!dupes.isEmpty()) {
                AgentTaskEntity existing = dupes.get(0);
                throw new IllegalStateException(
                        "duplicate assign rejected: you already created task " + existing.getId()
                        + " for agent " + assigneeId + " with the same title within the last "
                        + ASSIGN_DEDUP_WINDOW_SECONDS + "s. Its current status is '"
                        + existing.getStatus() + "'. Do NOT retry assign - the previous "
                        + "delegation is still in progress (or was just completed). Wait for "
                        + "its result or call agent(action='outbox') to check status.");
            }
        }

        // Every freshly-created task starts 'pending' - the status represents reality,
        // not intent. It transitions to 'in_progress' only when executeAgentForTask
        // actually acquires the assignee lock and is about to dispatch the worker.
        // This avoids the lie of "in_progress with no worker actually running" that
        // existed when creation forced in_progress immediately.
        AgentTaskEntity task = new AgentTaskEntity();
        task.setTenantId(tenantId);
        // PR20 - workspace scope inherited from the inbound HTTP request header
        // (PR16 forwarders propagate X-Organization-ID through service hops).
        // NULL = personal scope. Async / scheduled callers without a request
        // context get null, which is the correct strict-isolation default for
        // pre-org rows.
        task.setOrganizationId(TenantResolver.currentRequestOrganizationId());
        task.setParentTaskId(parentTaskId);
        task.setCreatedByAgentId(callingAgentId);
        task.setCreatedByUserId(callingUserId);
        task.setAssignedToAgentId(assigneeId);
        task.setAssignedToUserId(assigneeUserId);
        task.setReviewerAgentId(reviewerId);
        task.setReviewerUserId(reviewerUserId);
        task.setTitle(truncate(request.title(), 500));
        task.setInstructions(request.instructions());
        task.setTaskContext(request.taskContext());
        task.setPriority(priority);
        task.setStatus(AgentTaskEntity.STATUS_PENDING);
        task.setDepth(depth);
        task.setDueBy(request.dueBy());
        task.setMaxReviewAttempts(maxReviewAttempts);

        // Human-aware "assigned_to" marker for the audit event + log line.
        String assignedToDisplay = assigneeId != null ? assigneeId.toString()
                : (assigneeUserId != null ? "user:" + assigneeUserId : "backlog");

        AgentTaskEntity saved = taskRepository.save(task);
        self.recordEvent(saved.getId(), AgentTaskEventEntity.EVT_CREATED, callingAgentId, callingUserId,
                null, Map.of("status", AgentTaskEntity.STATUS_PENDING, "assigned_to", assignedToDisplay));
        publishAfterCommit(() -> taskBoardPublisher.publishTaskCreated(tenantId, saved));
        // P6: surface AGENT_TASK_ASSIGNED in the user's bell when the task is
        // assigned to a specific agent (not the unassigned backlog). Skip backlog
        // to avoid spam - the assignee's "inbox" UX takes over for those.
        emitTaskAssignedAfterCommit(saved, tenantId, assigneeId);
        // A human assignee / reviewer gets the SAME bell category, but the
        // recipient is the person themselves (reusing AGENT_TASK_ASSIGNED keeps the
        // bell/i18n surface unchanged - see emitTaskAssignedToUserAfterCommit).
        if (assigneeUserId != null) emitTaskAssignedToUserAfterCommit(saved, assigneeUserId, "assignee");
        if (reviewerUserId != null) emitTaskAssignedToUserAfterCommit(saved, reviewerUserId, "reviewer");
        logger.info("Task {} created by {} for {} (tenant={}, depth={}, priority={})",
                saved.getId(), callingAgentId != null ? callingAgentId : callingUserId,
                assignedToDisplay, tenantId, depth, priority);

        // Centralized kickoff: every REST/MCP path that wants the worker to run funnels
        // through this single chokepoint. The worker is dispatched asynchronously; it will
        // promote pending→in_progress itself once it has acquired the assignee CAS lock.
        // The lock prevents double-dispatch when the caller also invokes executeTaskSync (MCP).
        // When autoTriggerWorker=false (UI form-style creation, or MCP start_mode='pending'),
        // we skip the kickoff entirely - the task lingers in pending until something else
        // picks it up.
        if (assigneeId != null && autoTriggerWorker) {
            triggerAssigneeAgentAsync(saved);
        }

        return saved;
    }

    /**
     * Max total time to wait for the task to reach a terminal state across all
     * worker↔reviewer cycles. Field initializers give unit tests a sane default;
     * Spring overrides via {@code @Value} at runtime.
     */
    @Value("${agent.task-sync.overall-timeout-ms:1200000}")  // 20 minutes
    private long taskPollTimeoutMs = 1_200_000L;

    @Value("${agent.task-sync.poll-interval-ms:2000}")  // 2 seconds
    private long taskPollIntervalMs = 2_000L;

    /** Give up on a post-reject worker rerun after this long with no status change. */
    @Value("${agent.task-sync.stuck-in-progress-exit-ms:480000}")  // 8 minutes
    private long stuckInProgressExitMs = 480_000L;

    /**
     * Executes the assigned agent synchronously and waits for the task to reach a
     * terminal state, following the full worker↔reviewer cycle:
     * <ol>
     *   <li>Run the assigned worker (blocks until its conversation completes).</li>
     *   <li>If {@code in_review}, poll while the reviewer runs async. On approve →
     *       {@code completed}; on reject → back to {@code in_progress} and
     *       {@link #triggerAssigneeAgentAsync} fires the worker again.</li>
     *   <li>Repeat until a terminal state or the overall timeout.</li>
     * </ol>
     * Infinite reject loops are bounded by {@link #MAX_REVIEW_ATTEMPTS} (or the
     * per-task {@code maxReviewAttempts} override) inside {@link #rejectReview},
     * which auto-*fails* the task after N attempts - so the poll loop here always
     * terminates on a real terminal status.
     * <p>
     * Must be called OUTSIDE the assignTask transaction (after commit) so the task
     * is visible to the sub-agent's conversation.
     *
     * @return the task entity in its final state
     */
    public AgentTaskEntity executeTaskSync(AgentTaskEntity task) {
        if (task.getAssignedToAgentId() == null || conversationClient == null) {
            return task;
        }
        // Step 1: execute the assigned agent. May no-op if another thread already holds
        // the assignee lock (e.g. the centralized kickoff in assignTask fired first) - in
        // that case we fall through to the poll loop and observe the other thread's work.
        executeAgentForTask(task);

        // Step 2: re-read task from DB to see current state. Use the org from the
        // task itself (set at creation) so the strict-scope finder lands even when
        // this is invoked from a context without a request thread-local (e.g.
        // sync delegation chain via executeTaskSync).
        AgentTaskEntity updated = (task.getOrganizationId() != null && !task.getOrganizationId().isBlank()
                ? taskRepository.findByIdAndOrganizationIdStrict(task.getId(), task.getOrganizationId())
                : taskRepository.findByIdAndTenantId(task.getId(), task.getTenantId()))
                .orElse(task);

        // Step 3: if terminal already, we're done.
        if (isTerminalStatus(updated.getStatus())) {
            return updated;
        }

        // Step 4: poll until terminal. Covers two cases:
        //  - Task is in_review with a reviewer: wait through the worker↔reviewer cycle.
        //  - Another thread holds the assignee lock (centralized kickoff is still running):
        //    wait for it. Status may be pending (worker hasn't promoted yet) or in_progress.
        boolean inReviewerFlow = AgentTaskEntity.STATUS_IN_REVIEW.equals(updated.getStatus())
                && updated.getReviewerAgentId() != null;
        boolean waitingForConcurrentRun = updated.getAssigneeExecutionId() != null
                && (AgentTaskEntity.STATUS_PENDING.equals(updated.getStatus())
                    || AgentTaskEntity.STATUS_IN_PROGRESS.equals(updated.getStatus()));
        if (!inReviewerFlow && !waitingForConcurrentRun) {
            return updated;
        }

        // Step 5: loop until terminal / timeout.
        return waitForTaskTerminal(updated);
    }

    /**
     * Polls the task until it reaches a terminal status ({@code completed} /
     * {@code failed} / {@code cancelled}) or the overall timeout expires.
     * <p>
     * Handles the full worker↔reviewer cycle: the reviewer runs async (triggered
     * by {@code completeTask}/{@code rejectTask}), and on rejection the worker is
     * re-triggered async by {@link #triggerAssigneeAgentAsync}. We just observe
     * status transitions and wait. If the task sits in {@code in_progress} without
     * a reviewer transition for {@link #STUCK_IN_PROGRESS_EXIT_MS}, we give up
     * (means the async worker retrigger fired but didn't advance the state).
     */
    private AgentTaskEntity waitForTaskTerminal(AgentTaskEntity task) {
        UUID taskId = task.getId();
        String tenantId = task.getTenantId();
        long deadline = System.currentTimeMillis() + taskPollTimeoutMs;
        AgentTaskEntity latest = task;
        String lastStatus = latest.getStatus();
        long lastStatusChangeMs = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(taskPollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            latest = findTaskByIdScoped(taskId, tenantId).orElse(latest);
            String status = latest.getStatus();
            if (isTerminalStatus(status)) {
                return latest;
            }
            if (!Objects.equals(status, lastStatus)) {
                lastStatus = status;
                lastStatusChangeMs = System.currentTimeMillis();
                continue;
            }
            // Watchdog: stuck in_progress too long - async worker retrigger fired but
            // the worker isn't advancing (no task_complete / task_reject called).
            //
            // Skip this watchdog when another thread legitimately holds the assignee
            // lock (assigneeExecutionId != null): in that case the task is in_progress
            // because the concurrent kickoff is still running, not stuck. Let the
            // overall taskPollTimeoutMs (20 min default) be the upper bound instead.
            if (AgentTaskEntity.STATUS_IN_PROGRESS.equals(status)
                    && latest.getAssigneeExecutionId() == null
                    && System.currentTimeMillis() - lastStatusChangeMs > stuckInProgressExitMs) {
                logger.warn("[TaskSync] Task {} stuck in_progress for {}ms after reviewer reject, returning latest state",
                        taskId, stuckInProgressExitMs);
                return latest;
            }
        }

        logger.warn("[TaskSync] Task {} poll timed out after {}ms (lastStatus={})",
                taskId, taskPollTimeoutMs, lastStatus);
        return latest;
    }

    private static boolean isTerminalStatus(String status) {
        return AgentTaskEntity.STATUS_COMPLETED.equals(status)
                || AgentTaskEntity.STATUS_FAILED.equals(status)
                || AgentTaskEntity.STATUS_CANCELLED.equals(status);
    }

    /**
     * Resolve a status key's {@link TaskStatusCategory} against the given task's
     * board (F4). Uses the {@link #taskStatusService} registry when wired so
     * custom columns classify correctly; falls back to the seven historical
     * default-key classifications otherwise (unit tests, un-materialised boards).
     */
    private Optional<TaskStatusCategory> categoryOfStatus(AgentTaskEntity task, String statusKey) {
        if (statusKey == null) return Optional.empty();
        if (taskStatusService != null) {
            Optional<TaskStatusCategory> c =
                    taskStatusService.categoryOf(task.getTenantId(), task.getOrganizationId(), statusKey);
            if (c.isPresent()) return c;
        }
        return TaskStatusCategory.ofDefaultKey(statusKey);
    }

    // ========================================================================
    // Inbox / Outbox / Backlog
    // ========================================================================

    /**
     * Back-compat - pre-PR23 callers that don't pass orgId default to personal scope
     * (resolved at the request boundary via {@link TenantResolver#currentRequestOrganizationId()}).
     * MCP tool callers (AgentDelegationModule) hit this overload too - same fallback.
     */
    @Transactional(readOnly = true)
    public List<AgentTaskEntity> getInboxList(String tenantId, UUID agentId, int limit) {
        return getInboxList(tenantId, TenantResolver.currentRequestOrganizationId(), agentId, limit);
    }

    /**
     * PR23 - strict-isolation inbox. Routes to the org-strict finder when orgId is
     * non-null/non-blank, otherwise the personal-scope finder. Caller MUST resolve
     * orgId at the request boundary (controller via tenantResolver.resolveOrgId,
     * MCP tool path falls through to currentRequestOrganizationId).
     */
    @Transactional(readOnly = true)
    public List<AgentTaskEntity> getInboxList(String tenantId, String organizationId, UUID agentId, int limit) {
        TenantResolver.requireOrgId(organizationId);
        Pageable page = PageRequest.of(0, Math.max(1, Math.min(limit, 100)));
        java.util.Collection<String> statuses = List.of(
                AgentTaskEntity.STATUS_PENDING,
                AgentTaskEntity.STATUS_IN_PROGRESS,
                AgentTaskEntity.STATUS_IN_REVIEW);
        return taskRepository.findAssignedInboxByOrganizationIdStrict(
                organizationId, agentId, statuses, page);
    }

    /** Back-compat - defaults to personal scope or RequestContextHolder fallback. */
    @Transactional
    public TaskResponse getInboxTask(String tenantId, UUID agentId, UUID taskId) {
        return getInboxTask(tenantId, TenantResolver.currentRequestOrganizationId(), agentId, taskId);
    }

    /**
     * Fetch a specific task and auto-transition it from {@code pending} to
     * {@code in_progress} on first access by its assignee. PR23 strict-isolation:
     * org workspace sees only org-tagged tasks, personal workspace sees only
     * untagged rows.
     */
    @Transactional
    public TaskResponse getInboxTask(String tenantId, String organizationId, UUID agentId, UUID taskId) {
        AgentTaskEntity task = findTaskForScope(taskId, tenantId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        if (!Objects.equals(task.getAssignedToAgentId(), agentId)) {
            throw new IllegalStateException("task is not assigned to calling agent");
        }
        if (AgentTaskEntity.STATUS_PENDING.equals(task.getStatus())) {
            // Batch A2 - use org-aware UPDATE variant directly (caller already
            // resolved + validated organizationId via findTaskForScope).
            int n = taskRepository.promoteToInProgressByOrganizationIdStrict(
                    taskId, tenantId, organizationId, agentId);
            if (n == 1) {
                // Detach the stale managed entity and re-fetch so Hibernate's
                // dirty check can't issue a second UPDATE that clobbers the
                // state set by the native promoteToInProgress. In tests
                // entityManager is null (no Spring injection), in which case we
                // reflect the transition onto the in-memory snapshot so callers
                // observe STATUS_IN_PROGRESS consistently.
                if (entityManager != null) {
                    entityManager.detach(task);
                    task = findTaskForScope(taskId, tenantId, organizationId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "task vanished after promoteToInProgress: " + taskId));
                } else {
                    task.setStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
                    if (task.getStartedAt() == null) task.setStartedAt(Instant.now());
                }
                self.recordEvent(taskId, AgentTaskEventEntity.EVT_STARTED, agentId, null,
                        Map.of("status", "pending"), Map.of("status", "in_progress"));
                final AgentTaskEntity promotedTask = task;
                publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, promotedTask));
                // Backfill task_id on any running execution for this agent that
                // doesn't have one yet. This links the execution that triggered
                // the inbox read to the task retroactively.
                if (executionRepository != null) {
                    try {
                        executionRepository.backfillTaskId(agentId, tenantId, taskId);
                    } catch (Exception e) {
                        logger.debug("Failed to backfill taskId on execution: {}", e.getMessage());
                    }
                }
            }
        }
        List<AgentTaskNoteEntity> notes = noteRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
        return TaskResponse.from(task, notes);
    }

    /**
     * Fetch a task from the caller's outbox. The caller is either an agent
     * ({@code callerAgentId}, matched against {@code created_by_agent_id}) or a
     * human ({@code callerUserId}, matched against {@code created_by_user_id}) -
     * at least one of the two must match for the read to be allowed.
     */
    @Transactional(readOnly = true)
    public TaskResponse getOutboxTask(String tenantId, UUID callerAgentId, String callerUserId, UUID taskId) {
        return getOutboxTask(tenantId, TenantResolver.currentRequestOrganizationId(), callerAgentId, callerUserId, taskId);
    }

    /** PR23 - strict-isolation outbox single fetch. */
    @Transactional(readOnly = true)
    public TaskResponse getOutboxTask(String tenantId, String organizationId, UUID callerAgentId,
                                       String callerUserId, UUID taskId) {
        AgentTaskEntity task = findTaskForScope(taskId, tenantId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        boolean agentMatches = callerAgentId != null
                && callerAgentId.equals(task.getCreatedByAgentId());
        boolean userMatches = callerUserId != null
                && callerUserId.equals(task.getCreatedByUserId());
        if (!agentMatches && !userMatches) {
            throw new IllegalStateException("task is not in your outbox (created by someone else)");
        }
        List<AgentTaskNoteEntity> notes = noteRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
        return TaskResponse.from(task, notes);
    }

    /** Back-compat wrapper for agent-only outbox lookups. */
    @Transactional(readOnly = true)
    public TaskResponse getOutboxTask(String tenantId, UUID callerAgentId, UUID taskId) {
        return getOutboxTask(tenantId, callerAgentId, null, taskId);
    }

    /** Back-compat - defaults to personal scope or RequestContextHolder fallback. */
    @Transactional(readOnly = true)
    public List<AgentTaskEntity> getOutbox(String tenantId, UUID agentId, String statusFilter, int limit) {
        return getOutbox(tenantId, TenantResolver.currentRequestOrganizationId(), agentId, statusFilter, limit);
    }

    /**
     * PR23 - strict-isolation outbox. Status filter is applied at SQL level when
     * non-blank (round-2 fix for pagination regression: filtering in-Java post-
     * fetch on a LIMIT N result set silently drops matching rows whose createdAt
     * falls outside the most-recent N window).
     */
    @Transactional(readOnly = true)
    public List<AgentTaskEntity> getOutbox(String tenantId, String organizationId,
                                            UUID agentId, String statusFilter, int limit) {
        TenantResolver.requireOrgId(organizationId);
        Pageable page = PageRequest.of(0, Math.max(1, Math.min(limit, 100)));
        boolean hasStatus = statusFilter != null && !statusFilter.isBlank();
        if (hasStatus) {
            return taskRepository.findOutboxByOrganizationIdStrictAndStatus(
                    organizationId, agentId, statusFilter, page);
        }
        return taskRepository.findOutboxByOrganizationIdStrict(organizationId, agentId, page);
    }

    /** Back-compat - defaults to personal scope or RequestContextHolder fallback. */
    @Transactional(readOnly = true)
    public List<AgentTaskEntity> getBacklog(String tenantId, int limit) {
        return getBacklog(tenantId, TenantResolver.currentRequestOrganizationId(), limit);
    }

    /** PR23 - strict-isolation backlog. */
    @Transactional(readOnly = true)
    public List<AgentTaskEntity> getBacklog(String tenantId, String organizationId, int limit) {
        TenantResolver.requireOrgId(organizationId);
        Pageable page = PageRequest.of(0, Math.max(1, Math.min(limit, 100)));
        return taskRepository.findBacklogByOrganizationIdStrict(organizationId, page);
    }

    /**
     * Tasks awaiting this agent's review ({@code reviewer_agent_id = agentId AND status = 'in_review'}).
     */
    @Transactional(readOnly = true)
    public List<AgentTaskEntity> getReviewInbox(String tenantId, UUID agentId, int limit) {
        return getReviewInbox(tenantId, TenantResolver.currentRequestOrganizationId(), agentId, limit);
    }

    /** PR23 - strict-isolation review inbox. */
    @Transactional(readOnly = true)
    public List<AgentTaskEntity> getReviewInbox(String tenantId, String organizationId, UUID agentId, int limit) {
        TenantResolver.requireOrgId(organizationId);
        Pageable page = PageRequest.of(0, Math.max(1, Math.min(limit, 100)));
        return taskRepository.findPendingReviewsByOrganizationIdStrict(organizationId, agentId, page);
    }

    /**
     * PR23 - strict-isolation single fetch (used by detail / events / children /
     * executions endpoints as the scope-check gate). Returns Optional.empty() when
     * the task exists but in a different workspace, NOT a {@code TaskNotFound}
     * exception - caller (controller) maps to 404.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<AgentTaskEntity> findTaskForScope(
            UUID taskId, String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        return taskRepository.findByIdAndOrganizationIdStrict(taskId, organizationId);
    }

    /**
     * Atomically claim a backlog task. First-come-first-served.
     *
     * @return the claimed task, or {@code Optional.empty()} if the task was already
     *         taken, not in the backlog, or in a different tenant.
     */
    @Transactional
    public Optional<AgentTaskEntity> claimTask(String tenantId, UUID agentId, UUID taskId) {
        return claimTask(tenantId, TenantResolver.currentRequestOrganizationId(), agentId, taskId);
    }

    /** PR23 strict-isolation claim. */
    @Transactional
    public Optional<AgentTaskEntity> claimTask(String tenantId, String organizationId, UUID agentId, UUID taskId) {
        return claimTask(tenantId, organizationId, agentId, taskId, null);
    }

    /**
     * Strict-isolation claim with execution correlation. {@code executionId} comes from the
     * MCP credential {@code __executionId__} (injected by {@link com.apimarketplace.agent.service.cli.CliAgentService#startSession})
     * and identifies the in-flight agent execution. When present, the claim is appended to
     * {@code agent_task_claims} so the observability writer can link the future
     * {@code agent_executions} row to this task - the architectural fix for the prod
     * issue where {@code agent_executions.task_id} was NULL on every schedule fire
     * (2026-05-22, 15/15 rows).
     *
     * <p>{@code executionId} is nullable to keep REST-direct claim paths working (admin UI,
     * tests) where no MCP credential is in play. In that case the claim log row is skipped
     * and the task↔execution link is simply absent for that claim event.
     */
    @Transactional
    public Optional<AgentTaskEntity> claimTask(String tenantId, String organizationId, UUID agentId, UUID taskId,
                                                String executionId) {
        TenantResolver.requireOrgId(organizationId);
        int updated = taskRepository.claimIfAvailableByOrganizationId(taskId, organizationId, agentId);
        if (updated == 0) return Optional.empty();
        AgentTaskEntity task = taskRepository.findByIdAndOrganizationIdStrict(taskId, organizationId).orElse(null);
        if (task != null) {
            self.recordEvent(taskId, AgentTaskEventEntity.EVT_CLAIMED, agentId, null,
                    Map.of("assigned_to", "backlog"),
                    Map.of("assigned_to", agentId.toString(), "status", "in_progress"));
            // Append claim log row keyed by the in-flight executionId. The row may exist
            // BEFORE the matching agent_executions row (claim arrives mid-execution, the
            // observability INSERT only fires at end-of-run) - that's intentional, the log
            // is the source of truth for task↔execution linkage and has no FK on execution_id.
            if (executionId != null && !executionId.isBlank() && claimRepository != null) {
                try {
                    var entry = new com.apimarketplace.agent.domain.AgentTaskClaimEntity();
                    entry.setExecutionId(UUID.fromString(executionId));
                    entry.setTaskId(taskId);
                    entry.setEvent(com.apimarketplace.agent.domain.AgentTaskClaimEntity.EVT_CLAIMED);
                    entry.setAgentId(agentId);
                    entry.setOrganizationId(organizationId);
                    entry.setTenantId(tenantId);
                    claimRepository.save(entry);
                } catch (Exception e) {
                    logger.warn("Failed to write claim log (taskId={}, executionId={}): {}",
                            taskId, executionId, e.getMessage());
                }
            }
            final AgentTaskEntity claimedTask = task;
            publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, claimedTask));
            // Stamp the claimed taskId onto the agent's currently-running execution so the
            // task board shimmer can fire. The frontend's useAgentActivityStore updates
            // currentTaskId from tool_call_started events (preserving prior fields), and
            // the shimmer predicate is isRunning && currentTaskId === task.id.
            if (agentActivityPublisher != null) {
                final UUID claimedAgentId = agentId;
                final UUID claimedTaskId = taskId;
                runAfterCommit(() -> agentActivityPublisher.publishToolCallStarted(
                        claimedAgentId.toString(), null,
                        "task_claim", UUID.randomUUID().toString(),
                        claimedTaskId.toString()), "Agent activity publish (task_claim)");
            }
        }
        return Optional.ofNullable(task);
    }

    // ========================================================================
    // Board ordering (F1 - manual within-column rank)
    // ========================================================================

    /**
     * Assign sequential manual ranks to the given tasks in the order provided
     * (the new top-to-bottom order of a board column after a drag). Board-scoped:
     * every id must resolve in the caller's scope. Ranks are only ever compared
     * within a column (the board groups by status, then orders by rank), so
     * reordering one column never disturbs another. No audit event is recorded -
     * reordering is a view gesture, not a lifecycle change.
     */
    @Transactional
    public List<AgentTaskEntity> reorderBoardTasks(String tenantId, List<UUID> orderedTaskIds) {
        if (orderedTaskIds == null || orderedTaskIds.isEmpty()) {
            throw new IllegalArgumentException("orderedTaskIds is required");
        }
        List<AgentTaskEntity> updated = new java.util.ArrayList<>(orderedTaskIds.size());
        double rank = 0.0;
        for (UUID id : orderedTaskIds) {
            Optional<AgentTaskEntity> opt = findTaskByIdScoped(id, tenantId);
            if (opt.isEmpty()) {
                throw new IllegalArgumentException("task not on this board: " + id);
            }
            AgentTaskEntity t = opt.get();
            t.setBoardRank(rank);
            rank += 1.0;
            AgentTaskEntity saved = taskRepository.save(t);
            updated.add(saved);
            publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, saved));
        }
        return updated;
    }

    // ========================================================================
    // Labels (F2)
    // ========================================================================

    /**
     * Replace the task's label set. Ids are validated against the board catalog
     * (when the catalog service is wired) so a task can never reference a label
     * that is not on its board. Pass an empty list to clear all labels.
     */
    @Transactional
    public AgentTaskEntity setTaskLabels(String tenantId, UUID taskId, UUID callingAgentId,
                                         String callingUserId, List<String> labelIds) {
        return setTaskLabels(tenantId, taskId, callingAgentId, callingUserId, labelIds, null);
    }

    /**
     * Replace the task's label set, accepting board-label UUIDs ({@code labelIds}) and/or label
     * NAMES ({@code labelNames}). Each name is resolved against the board catalog case-insensitively
     * and CREATED when absent (so an agent can label a task without first looking up UUIDs). The
     * union of resolved-name ids and explicit ids is validated and replaces the current set; pass
     * both empty/absent to clear. Name resolution requires the catalog service to be wired.
     */
    @Transactional
    public AgentTaskEntity setTaskLabels(String tenantId, UUID taskId, UUID callingAgentId,
                                         String callingUserId, List<String> labelIds, List<String> labelNames) {
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        List<String> requested = new java.util.ArrayList<>();
        if (taskLabelService != null && labelNames != null) {
            for (String name : labelNames) {
                if (name == null || name.isBlank()) continue;
                requested.add(taskLabelService.getOrCreateByName(
                        task.getTenantId(), task.getOrganizationId(), name).getId().toString());
            }
        }
        if (labelIds != null) requested.addAll(labelIds);
        List<String> validated = taskLabelService != null
                ? taskLabelService.validateLabelIds(task.getTenantId(), task.getOrganizationId(), requested)
                : requested;
        List<String> old = task.getLabelIds();
        task.setLabelIds(new java.util.ArrayList<>(validated));
        AgentTaskEntity saved = taskRepository.save(task);
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_UPDATED, callingAgentId, callingUserId,
                Map.of("label_ids", old == null ? List.of() : old), Map.of("label_ids", validated));
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, saved));
        return saved;
    }

    // ========================================================================
    // Estimation / time tracking (F12)
    // ========================================================================

    /**
     * Set the task's estimate and/or logged time (minutes). For each field, a
     * null value with its clear flag false leaves it unchanged; the clear flag
     * sets it back to null. Negative values are rejected.
     */
    @Transactional
    public AgentTaskEntity setTaskEstimate(String tenantId, UUID taskId, UUID callingAgentId, String callingUserId,
                                           Integer estimateMinutes, boolean clearEstimate,
                                           Integer timeSpentMinutes, boolean clearTimeSpent) {
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        if (clearEstimate) {
            task.setEstimateMinutes(null);
        } else if (estimateMinutes != null) {
            if (estimateMinutes < 0) throw new IllegalArgumentException("estimate_minutes must be >= 0");
            task.setEstimateMinutes(estimateMinutes);
        }
        if (clearTimeSpent) {
            task.setTimeSpentMinutes(null);
        } else if (timeSpentMinutes != null) {
            if (timeSpentMinutes < 0) throw new IllegalArgumentException("time_spent_minutes must be >= 0");
            task.setTimeSpentMinutes(timeSpentMinutes);
        }
        AgentTaskEntity saved = taskRepository.save(task);
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_UPDATED, callingAgentId, callingUserId,
                null, Map.of(
                        "estimate_minutes", String.valueOf(saved.getEstimateMinutes()),
                        "time_spent_minutes", String.valueOf(saved.getTimeSpentMinutes())));
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, saved));
        return saved;
    }

    // ========================================================================
    // Dependencies (F9 - blocked-by)
    // ========================================================================

    /**
     * Replace the task's blocker set (the tasks that must finish before it). Each
     * id must be another task in scope; self-reference and a direct reciprocal
     * cycle (the blocker is already blocked by this task) are rejected. An empty
     * list clears all blockers.
     */
    @Transactional
    public AgentTaskEntity setTaskBlockers(String tenantId, UUID taskId, UUID callingAgentId,
                                           String callingUserId, List<String> blockerIds) {
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        java.util.LinkedHashSet<String> validated = new java.util.LinkedHashSet<>();
        if (blockerIds != null) {
            for (String raw : blockerIds) {
                if (raw == null) continue;
                UUID blockerId;
                try {
                    blockerId = UUID.fromString(raw.trim());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("invalid task id: " + raw);
                }
                if (blockerId.equals(taskId)) {
                    throw new IllegalArgumentException("a task cannot block itself");
                }
                AgentTaskEntity blocker = findTaskByIdScoped(blockerId, tenantId)
                        .orElseThrow(() -> new IllegalArgumentException("blocker task not found: " + blockerId));
                if (blocker.getBlockedByIds() != null && blocker.getBlockedByIds().contains(taskId.toString())) {
                    throw new IllegalArgumentException(
                            "cannot add " + blockerId + " as a blocker: it is already blocked by this task (cycle)");
                }
                validated.add(blockerId.toString());
            }
        }
        List<String> old = task.getBlockedByIds();
        task.setBlockedByIds(new java.util.ArrayList<>(validated));
        AgentTaskEntity saved = taskRepository.save(task);
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_UPDATED, callingAgentId, callingUserId,
                Map.of("blocked_by_ids", old == null ? List.of() : old),
                Map.of("blocked_by_ids", new java.util.ArrayList<>(validated)));
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, saved));
        return saved;
    }

    // ========================================================================
    // Checklist + attachments (F10)
    // ========================================================================

    private static final int MAX_CHECKLIST_ITEMS = 100;
    private static final int MAX_ATTACHMENTS = 50;

    /** Replace the task's checklist. Each item is normalised to {id, text, done}. */
    @Transactional
    public AgentTaskEntity setTaskChecklist(String tenantId, UUID taskId, UUID callingAgentId,
                                            String callingUserId, List<Map<String, Object>> items) {
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        task.setChecklist(normalizeChecklist(items));
        AgentTaskEntity saved = taskRepository.save(task);
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_UPDATED, callingAgentId, callingUserId,
                null, Map.of("checklist_items", saved.getChecklist().size()));
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, saved));
        return saved;
    }

    /** Replace the task's attachments. Each links a file already in storage by key. */
    @Transactional
    public AgentTaskEntity setTaskAttachments(String tenantId, UUID taskId, UUID callingAgentId,
                                              String callingUserId, List<Map<String, Object>> items) {
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        task.setAttachments(normalizeAttachments(items));
        AgentTaskEntity saved = taskRepository.save(task);
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_UPDATED, callingAgentId, callingUserId,
                null, Map.of("attachments", saved.getAttachments().size()));
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, saved));
        return saved;
    }

    private static List<Map<String, Object>> normalizeChecklist(List<Map<String, Object>> items) {
        if (items == null) return new java.util.ArrayList<>();
        if (items.size() > MAX_CHECKLIST_ITEMS) {
            throw new IllegalArgumentException("at most " + MAX_CHECKLIST_ITEMS + " checklist items");
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            if (item == null) continue;
            String text = jsonStr(item.get("text"));
            if (text.isEmpty()) throw new IllegalArgumentException("checklist item text is required");
            if (text.length() > 500) throw new IllegalArgumentException("checklist item text exceeds 500 characters");
            Object doneRaw = item.get("done");
            boolean done = doneRaw instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(doneRaw));
            Map<String, Object> norm = new java.util.LinkedHashMap<>();
            norm.put("id", jsonId(item.get("id")));
            norm.put("text", text);
            norm.put("done", done);
            out.add(norm);
        }
        return out;
    }

    private static List<Map<String, Object>> normalizeAttachments(List<Map<String, Object>> items) {
        if (items == null) return new java.util.ArrayList<>();
        if (items.size() > MAX_ATTACHMENTS) {
            throw new IllegalArgumentException("at most " + MAX_ATTACHMENTS + " attachments");
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            if (item == null) continue;
            String fileName = jsonStr(item.get("fileName"));
            String storageKey = jsonStr(item.get("storageKey"));
            if (fileName.isEmpty()) throw new IllegalArgumentException("attachment fileName is required");
            if (storageKey.isEmpty()) throw new IllegalArgumentException("attachment storageKey is required");
            Map<String, Object> norm = new java.util.LinkedHashMap<>();
            norm.put("id", jsonId(item.get("id")));
            norm.put("fileName", fileName);
            norm.put("storageKey", storageKey);
            if (item.get("mimeType") != null) norm.put("mimeType", item.get("mimeType").toString());
            if (item.get("sizeBytes") != null) norm.put("sizeBytes", item.get("sizeBytes"));
            out.add(norm);
        }
        return out;
    }

    private static String jsonStr(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static String jsonId(Object idRaw) {
        return (idRaw == null || idRaw.toString().isBlank()) ? UUID.randomUUID().toString() : idRaw.toString();
    }

    // ========================================================================
    // Lifecycle transitions
    // ========================================================================

    /**
     * Agent submits work → the task ALWAYS moves to {@code in_review} (never straight to
     * {@code completed}). A reviewer agent, if one is assigned, is auto-triggered
     * ({@link #triggerReviewerAgentAsync}). When there is no reviewer at all, the task parks
     * in {@code in_review} awaiting a manual decision by its creator (or the tenant owner for
     * an agent-created task) via {@link #approveTaskByUser} / {@link #rejectReviewByUser};
     * an {@code AGENT_TASK_AWAITING_REVIEW} notification surfaces it to that recipient.
     * <p>
     * When {@code force} is false (default), completion is blocked if active
     * children exist. When {@code force} is true, active children are
     * cascade-cancelled before the parent moves to review.
     */
    @Transactional
    public AgentTaskEntity completeTask(String tenantId, UUID taskId, UUID agentId, String result) {
        return completeTask(tenantId, taskId, agentId, result, false, null);
    }

    @Transactional
    public AgentTaskEntity completeTask(String tenantId, UUID taskId, UUID agentId, String result, boolean force) {
        return completeTask(tenantId, taskId, agentId, result, force, null);
    }

    @Transactional
    public AgentTaskEntity completeTask(String tenantId, UUID taskId, UUID agentId, String result, boolean force,
                                        UUID reviewerExecutionId) {
        if (result != null && utf8Bytes(result) > MAX_RESULT_BYTES) {
            throw new IllegalArgumentException("result exceeds maximum size of " + MAX_RESULT_BYTES + " bytes");
        }

        // Role-rerouting: if the caller is the reviewer (not the assignee) and the task is
        // in_review, they meant task_approve. Weaker LLMs commonly confuse these verbs and
        // burn their iterations repeating task_complete - reroute so the review progresses.
        // See bug #8 in TASK_TEST_ERRORS.md.
        AgentTaskEntity existing = findTaskByIdScoped(taskId, tenantId).orElse(null);
        if (existing != null
                && agentId != null
                && agentId.equals(existing.getReviewerAgentId())
                && AgentTaskEntity.STATUS_IN_REVIEW.equals(existing.getStatus())) {
            logger.warn("[TaskReview] Rerouting task_complete→task_approve for task {} by reviewer {} (caller is reviewer, not assignee)",
                    taskId, agentId);
            return approveTask(tenantId, taskId, agentId, reviewerExecutionId);
        }

        // Child guard: block completion if active children exist (unless force)
        if (taskRepository.hasActiveChildren(taskId, tenantId)) {
            if (force) {
                int cancelled = taskRepository.cascadeCancelChildren(taskId, tenantId,
                        "parent task completed with force=true");
                logger.info("Force-completed task {}: cancelled {} active children", taskId, cancelled);
            } else {
                throw new IllegalStateException(
                        "task has active children - complete or cancel them first, or use force=true (task=" + taskId + ")");
            }
        }

        // Always → in_review (reviewer is either an agent or the tenant owner).
        // Batch A2 - route through org-aware CAS when an orgId is in the request
        // scope (controllers forward X-Organization-ID; async forks bind it via
        // TenantResolver.runWithOrgScope before invoking us).
        int updated = submitForReviewScoped(taskId, tenantId, agentId, result);
        if (updated == 0) {
            throw new IllegalStateException(wrongActionHint(taskId, tenantId, agentId, "complete"));
        }
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId).orElseThrow();
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_SUBMITTED_FOR_REVIEW, agentId, null,
                Map.of("status", "in_progress"), Map.of("status", "in_review"));
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, task));

        // Auto-trigger reviewer agent if assigned
        triggerReviewerAgentAsync(task);
        // No reviewer at all → the task awaits a manual decision; notify its creator/owner.
        emitTaskAwaitingReviewAfterCommit(task, tenantId);

        return task;
    }

    /**
     * Agent reports failure → always {@code in_review}.
     * The reviewer (agent or user) decides whether to accept the failure or send back.
     * <p>
     * Active children are automatically cascade-cancelled - a parent failure
     * invalidates the entire subtree.
     */
    @Transactional
    public AgentTaskEntity rejectTask(String tenantId, UUID taskId, UUID agentId, String reason) {
        return rejectTask(tenantId, taskId, agentId, reason, null);
    }

    @Transactional
    public AgentTaskEntity rejectTask(String tenantId, UUID taskId, UUID agentId, String reason,
                                      UUID reviewerExecutionId) {
        String effectiveReason = reason == null ? "rejected by agent" : reason;

        // Role-rerouting: same idea as completeTask - a reviewer calling task_reject on an
        // in_review task meant task_reject_review. See bug #8 in TASK_TEST_ERRORS.md.
        AgentTaskEntity existing = findTaskByIdScoped(taskId, tenantId).orElse(null);
        if (existing != null
                && agentId != null
                && agentId.equals(existing.getReviewerAgentId())
                && AgentTaskEntity.STATUS_IN_REVIEW.equals(existing.getStatus())) {
            logger.warn("[TaskReview] Rerouting task_reject→task_reject_review for task {} by reviewer {} (caller is reviewer, not assignee)",
                    taskId, agentId);
            return rejectReview(tenantId, taskId, agentId, reviewerExecutionId, effectiveReason);
        }

        // Cascade-cancel active children: parent failure invalidates the subtree
        if (taskRepository.hasActiveChildren(taskId, tenantId)) {
            int cancelled = taskRepository.cascadeCancelChildren(taskId, tenantId,
                    "parent task failed: " + effectiveReason);
            logger.info("Task {} failed: cascade-cancelled {} active children", taskId, cancelled);
        }

        // Always → in_review with error. Batch A2 - org-aware variant.
        int updated = submitFailureForReviewScoped(taskId, tenantId, agentId, effectiveReason);
        if (updated == 0) {
            throw new IllegalStateException(wrongActionHint(taskId, tenantId, agentId, "reject"));
        }
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId).orElseThrow();
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_SUBMITTED_FOR_REVIEW, agentId, null,
                Map.of("status", "in_progress"),
                Map.of("status", "in_review", "reason", effectiveReason));
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, task));

        // Auto-trigger reviewer agent if assigned
        triggerReviewerAgentAsync(task);
        // No reviewer at all → the failed work awaits a manual decision; notify creator/owner.
        emitTaskAwaitingReviewAfterCommit(task, tenantId);

        return task;
    }

    /**
     * Reviewer approves a task in {@code in_review} → {@code completed}.
     * Blocked if active children exist (same guard as completeTask).
     */
    @Transactional
    public AgentTaskEntity approveTask(String tenantId, UUID taskId, UUID reviewerAgentId) {
        return approveTask(tenantId, taskId, reviewerAgentId, null);
    }

    @Transactional
    public AgentTaskEntity approveTask(String tenantId, UUID taskId, UUID reviewerAgentId, UUID reviewerExecutionId) {
        requireReviewerExecutionToken(reviewerExecutionId, "approve");
        if (taskRepository.hasActiveChildren(taskId, tenantId)) {
            throw new IllegalStateException(
                    "task has active children - complete or cancel them first (task=" + taskId + ")");
        }
        int updated = reviewerExecutionId != null
                ? taskRepository.approveIfReviewerExecution(taskId, tenantId, reviewerAgentId, reviewerExecutionId)
                : taskRepository.approveIfReviewer(taskId, tenantId, reviewerAgentId);
        if (updated == 0) {
            throw new IllegalStateException(wrongReviewerActionHint(taskId, tenantId, reviewerAgentId, "approve"));
        }
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId).orElseThrow();
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_APPROVED, reviewerAgentId, null,
                Map.of("status", "in_review"), Map.of("status", "completed"));
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, task));
        return task;
    }

    /**
     * Reviewer agent rejects a task in {@code in_review} → back to {@code in_progress}.
     * The assigned agent is expected to retry.
     */
    @Transactional
    public AgentTaskEntity rejectReview(String tenantId, UUID taskId, UUID reviewerAgentId, String reason) {
        return rejectReview(tenantId, taskId, reviewerAgentId, null, reason);
    }

    @Transactional
    public AgentTaskEntity rejectReview(String tenantId, UUID taskId, UUID reviewerAgentId,
                                        UUID reviewerExecutionId, String reason) {
        requireReviewerExecutionToken(reviewerExecutionId, "reject_review");
        String effectiveReason = reason == null ? "review rejected" : reason;

        // Reject-loop cap: at the threshold we auto-*fail* the task (do not silently
        // promote unvalidated work). The counter is incremented first so we see the
        // threshold crossing atomically; then we read the cap from the SAME post-increment
        // snapshot to avoid any disagreement between the counter and the per-task override.
        // The increment CAS is scoped to {tenant, reviewer}: a non-reviewer caller gets 0
        // back and can never poison the counter.
        int attemptCount = self.incrementReviewAttemptCount(taskId, tenantId, reviewerAgentId, reviewerExecutionId);
        if (attemptCount == 0) {
            // Either not in_review, wrong tenant, or caller is not the reviewer - surface
            // the role-aware hint so the LLM knows which tool to call instead.
            throw new IllegalStateException(wrongReviewerActionHint(taskId, tenantId, reviewerAgentId, "reject_review"));
        }
        AgentTaskEntity postIncrement = findTaskByIdScoped(taskId, tenantId).orElse(null);
        int cap = effectiveMaxReviewAttempts(postIncrement);
        if (attemptCount >= cap) {
            logger.warn("[TaskReview] Max review attempts ({}/{}) reached for task {}, auto-failing",
                    attemptCount, cap, taskId);
            String failReason = "auto-failed after " + attemptCount
                    + " reviewer rejection(s) (cap=" + cap + "); last reviewer feedback: " + effectiveReason;
            AgentTaskEntity failedTask = self.autoFailAfterReviewerRejection(
                    taskId, tenantId, reviewerAgentId, reviewerExecutionId, failReason);
            if (failedTask == null) {
                // The CAS lost between the increment and the fail UPDATE - task already terminal.
                throw new IllegalStateException(wrongReviewerActionHint(taskId, tenantId, reviewerAgentId, "reject_review"));
            }
            return failedTask;
        }

        int updated = reviewerExecutionId != null
                ? taskRepository.rejectReviewIfReviewerExecution(taskId, tenantId, reviewerAgentId,
                        reviewerExecutionId, effectiveReason)
                : taskRepository.rejectReviewIfReviewer(taskId, tenantId, reviewerAgentId, effectiveReason);
        if (updated == 0) {
            throw new IllegalStateException(wrongReviewerActionHint(taskId, tenantId, reviewerAgentId, "reject_review"));
        }
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId).orElseThrow();
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_REVIEW_REJECTED, reviewerAgentId, null,
                Map.of("status", "in_review"),
                Map.of("status", "in_progress", "reason", effectiveReason));
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, task));
        // Reviewer sent the work back - re-trigger the worker so it can address the feedback
        // (in errorMessage) instead of leaving the task idle in in_progress. See TASK_TEST_ERRORS.md.
        triggerAssigneeAgentAsync(task);
        return task;
    }

    /**
     * Tenant owner approves a task in {@code in_review} → {@code completed}.
     * Used by the task board for manual review decisions, including overrides of
     * reviewer-agent tasks.
     */
    @Transactional
    public AgentTaskEntity approveTaskByUser(String tenantId, UUID taskId) {
        if (taskRepository.hasActiveChildren(taskId, tenantId)) {
            throw new IllegalStateException(
                    "task has active children - complete or cancel them first (task=" + taskId + ")");
        }
        int updated = taskRepository.approveByTenantOwner(taskId, tenantId);
        if (updated == 0) {
            throw new IllegalStateException(
                    "task cannot be approved: not in_review (task=" + taskId + ")");
        }
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId).orElseThrow();
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_APPROVED, null, tenantId,
                Map.of("status", "in_review"), Map.of("status", "completed"));
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, task));
        return task;
    }

    /**
     * Tenant owner rejects a task in {@code in_review} → back to {@code in_progress}.
     * Used by the task board for manual review decisions, including overrides of
     * reviewer-agent tasks.
     */
    @Transactional
    public AgentTaskEntity rejectReviewByUser(String tenantId, UUID taskId, String reason) {
        String effectiveReason = reason == null ? "review rejected by user" : reason;
        int updated = taskRepository.rejectReviewByTenantOwner(taskId, tenantId, effectiveReason);
        if (updated == 0) {
            throw new IllegalStateException(
                    "task review cannot be rejected: not in_review (task=" + taskId + ")");
        }
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId).orElseThrow();
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_REVIEW_REJECTED, null, tenantId,
                Map.of("status", "in_review"),
                Map.of("status", "in_progress", "reason", effectiveReason));
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, task));
        // Manual task-board review only sends the task back to in_progress. The user
        // decides when to start the assignee again; reviewer-agent rejections keep the
        // automated worker→reviewer loop and trigger the assignee above.
        return task;
    }

    @Transactional
    public AgentTaskEntity updateTask(String tenantId,
                                       UUID taskId,
                                       UUID callingAgentId,
                                       String callingUserId,
                                       UpdateTaskRequest request) {
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));

        // Authorization: creator (agent or human) OR tenant owner may update.
        boolean isCreatorAgent = callingAgentId != null
                && callingAgentId.equals(task.getCreatedByAgentId());
        boolean isCreatorHuman = callingUserId != null
                && callingUserId.equals(task.getCreatedByUserId());
        boolean isTenantOwner = callingAgentId == null && callingUserId != null
                && callingUserId.equals(tenantId);
        if (!isCreatorAgent && !isCreatorHuman && !isTenantOwner) {
            throw new IllegalStateException("only the task creator or tenant owner may update it");
        }

        // 'deleted' is never a manual status edit - it must go through the soft-delete
        // path so deleted_at/previous_status stay consistent. Leaving 'deleted' for a
        // real column IS allowed here (drag-out-of-trash restore, handled below).
        if (AgentTaskEntity.STATUS_DELETED.equals(request.status())) {
            throw new IllegalArgumentException(
                    "cannot set status to 'deleted' directly - use the delete action to move a task to the Deleted column");
        }

        // ── Human assignee / reviewer (Jira-style) ──
        // An assignee is an agent XOR a person; same for the reviewer. unassign /
        // removeReviewer clear whichever (agent or human) is currently set. A human
        // assignee never auto-executes: the kickoff at the bottom is gated on the
        // AGENT id, so a person-assigned task just changes columns manually.
        String reqAssigneeUserId = trimToNull(request.assigneeUserId());
        String reqReviewerUserId = trimToNull(request.reviewerUserId());
        boolean unassign = Boolean.TRUE.equals(request.unassign());
        boolean removeReviewer = Boolean.TRUE.equals(request.removeReviewer());
        if (request.agentId() != null && reqAssigneeUserId != null) {
            throw new IllegalArgumentException("a task is assigned to an agent XOR a person, not both");
        }
        if (unassign && (request.agentId() != null || reqAssigneeUserId != null)) {
            throw new IllegalArgumentException(
                    "unassign=true is mutually exclusive with assigning an agent or a person");
        }
        if (request.reviewerAgentId() != null && reqReviewerUserId != null) {
            throw new IllegalArgumentException("a reviewer is an agent XOR a person, not both");
        }
        if (removeReviewer && (request.reviewerAgentId() != null || reqReviewerUserId != null)) {
            throw new IllegalArgumentException(
                    "removeReviewer=true is mutually exclusive with setting a reviewer");
        }
        // Validate any NEW human assignee/reviewer belongs to the task's workspace.
        // Skipped when authClient is unwired (unit-test construction). Fails closed.
        if (authClient != null && (reqAssigneeUserId != null || reqReviewerUserId != null)) {
            Set<String> members = authClient.getOrganizationMemberIds(task.getOrganizationId());
            if (reqAssigneeUserId != null) requireWorkspaceMember(members, reqAssigneeUserId, "assignee");
            if (reqReviewerUserId != null) requireWorkspaceMember(members, reqReviewerUserId, "reviewer");
        }

        Map<String, Object> oldVals = new HashMap<>();
        Map<String, Object> newVals = new HashMap<>();
        boolean statusEventRecorded = false;

        // ── Status transition (flexible: any → any with validation) ──
        // F4: validate + classify the target against the task's BOARD (custom
        // columns allowed), not just the seven historical literals. The target
        // must resolve to a TaskStatusCategory; all branching keys off the
        // category so a custom "QA"/"Blocked" column behaves like its category.
        if (request.status() != null && !request.status().equals(task.getStatus())) {
            String target = request.status();
            TaskStatusCategory targetCat = categoryOfStatus(task, target)
                    .orElseThrow(() -> new IllegalArgumentException("invalid status: " + target));
            // A Deleted-category column is the trash: never set it via a plain edit
            // (the literal 'deleted' is blocked above; this also blocks a custom
            // column mapped to the deleted category). Use the delete action.
            if (targetCat == TaskStatusCategory.DELETED) {
                throw new IllegalArgumentException(
                        "cannot move a task to a Deleted-category column directly - use the delete action");
            }
            // The in_progress / in_review CATEGORY requires an assignee - an agent
            // OR a person. (A human assignee lets a teammate move the card across
            // columns Jira-style; it just doesn't auto-execute.) unassign in the
            // same request clears it.
            UUID effAgentAssignee = request.agentId() != null ? request.agentId() : task.getAssignedToAgentId();
            String effUserAssignee = reqAssigneeUserId != null ? reqAssigneeUserId : task.getAssignedToUserId();
            boolean hasAssignee = !unassign && (effAgentAssignee != null || effUserAssignee != null);
            if ((targetCat == TaskStatusCategory.IN_PROGRESS || targetCat == TaskStatusCategory.IN_REVIEW)
                    && !hasAssignee) {
                throw new IllegalStateException(target + " requires an assignee");
            }
            // F4 engine boundary: an AGENT-assigned task flows through the BUILT-IN
            // active columns - the agent loop (inbox / claim / complete / review)
            // reasons in canonical active states, so the task is always visible to
            // those queries and never stranded. Custom ACTIVE sub-columns (e.g. a
            // "Blocked" or "QA" column) are a human board-organisation tool for
            // human / unassigned cards; custom TERMINAL columns are fine for any
            // task. Block parking an agent's task in a custom active column.
            boolean targetIsCustomActive = targetCat.isActive()
                    && !targetCat.defaultStatusKey().equals(target);
            if (targetIsCustomActive && !unassign && effAgentAssignee != null) {
                throw new IllegalStateException(
                        "an agent-assigned task uses the built-in active columns (pending / in progress / in review); "
                        + "unassign it or assign a person to move it to the custom '" + target + "' column");
            }
            String oldStatus = task.getStatus();
            TaskStatusCategory oldCat = categoryOfStatus(task, oldStatus).orElse(null);
            boolean isFromTerminal = oldCat != null && oldCat.isTerminal();
            boolean isToNonTerminal = !targetCat.isTerminal();

            oldVals.put("status", oldStatus);
            task.setStatus(target);
            newVals.put("status", target);
            // Drag-out-of-trash restore to a specific column - clear the soft-delete
            // bookkeeping so the task is fully live again (the bulk Restore action goes
            // through restoreTask() instead, which returns it to previous_status).
            if (AgentTaskEntity.STATUS_DELETED.equals(oldStatus)) {
                task.setDeletedAt(null);
                task.setPreviousStatus(null);
            }
            if (oldCat == TaskStatusCategory.IN_REVIEW || targetCat == TaskStatusCategory.IN_REVIEW) {
                clearReviewerExecutionIfPresent(task, oldVals, newVals);
            }

            // Adjust timestamps based on the target CATEGORY.
            switch (targetCat) {
                case PENDING, IN_REVIEW -> task.setCompletedAt(null);
                case IN_PROGRESS -> {
                    if (task.getStartedAt() == null) task.setStartedAt(Instant.now());
                    task.setCompletedAt(null);
                }
                case DONE, FAILED, CANCELLED -> {
                    if (task.getCompletedAt() == null) task.setCompletedAt(Instant.now());
                }
                case DELETED -> { /* unreachable - blocked above */ }
            }

            // Clear result when leaving terminal → non-terminal
            if (isFromTerminal && isToNonTerminal) {
                task.setResult(null);
            }

            String eventType = isFromTerminal ? AgentTaskEventEntity.EVT_REOPENED
                    : AgentTaskEventEntity.EVT_UPDATED;
            self.recordEvent(taskId, eventType,
                    callingAgentId, callingUserId, Map.of("status", oldStatus), Map.of("status", target));
            statusEventRecorded = true;
        }

        // ── Field edits (allowed in ALL statuses) ──
        if (request.title() != null) {
            oldVals.put("title", task.getTitle());
            task.setTitle(truncate(request.title(), 500));
            newVals.put("title", task.getTitle());
        }
        if (request.instructions() != null) {
            if (utf8Bytes(request.instructions()) > MAX_INSTRUCTIONS_BYTES) {
                throw new IllegalArgumentException(
                        "instructions exceed maximum size of " + MAX_INSTRUCTIONS_BYTES + " bytes");
            }
            oldVals.put("instructions_len", task.getInstructions() == null ? 0 : task.getInstructions().length());
            task.setInstructions(request.instructions());
            newVals.put("instructions_len", request.instructions().length());
        }
        if (request.priority() != null) {
            oldVals.put("priority", task.getPriority());
            task.setPriority(normalizePriority(request.priority()));
            newVals.put("priority", task.getPriority());
        }

        // ── Unassign (clears the agent OR human assignee → backlog) ──
        if (unassign && (task.getAssignedToAgentId() != null || task.getAssignedToUserId() != null)) {
            // If current status requires an assignee and no explicit status change was
            // requested, auto-fallback to pending instead of rejecting.
            String currentStatus = task.getStatus();
            TaskStatusCategory currentCat = categoryOfStatus(task, currentStatus).orElse(null);
            if (request.status() == null
                    && (currentCat == TaskStatusCategory.IN_PROGRESS
                        || currentCat == TaskStatusCategory.IN_REVIEW)) {
                oldVals.put("status", currentStatus);
                task.setStatus(AgentTaskEntity.STATUS_PENDING);
                newVals.put("status", AgentTaskEntity.STATUS_PENDING);
                clearReviewerExecutionIfPresent(task, oldVals, newVals);
            }
            oldVals.put("assigned_to", assigneeMarker(task));
            task.setAssignedToAgentId(null);
            task.setAssignedToUserId(null);
            task.setStartedAt(null);
            newVals.put("assigned_to", "backlog");
        }

        // ── Reassign to an AGENT ──
        if (request.agentId() != null && !request.agentId().equals(task.getAssignedToAgentId())) {
            AgentEntity newAssignee = findAgentByIdScoped(request.agentId())
                    .orElseThrow(() -> new IllegalArgumentException("assignee not found: " + request.agentId()));
            String reassignOrgId = TenantResolver.currentRequestOrganizationId();
            if (!ScopeGuard.isInStrictScope(tenantId, reassignOrgId, newAssignee.getTenantId(), newAssignee.getOrganizationId())) {
                throw new IllegalArgumentException("new assignee belongs to a different tenant");
            }
            UUID taskReviewer = request.reviewerAgentId() != null ? request.reviewerAgentId() : task.getReviewerAgentId();
            if (taskReviewer != null && taskReviewer.equals(request.agentId())) {
                throw new IllegalArgumentException("reviewer and assignee cannot be the same agent");
            }
            oldVals.put("assigned_to", assigneeMarker(task));
            task.setAssignedToAgentId(request.agentId());
            task.setAssignedToUserId(null); // exclusivity: an agent assignee clears any human assignee
            newVals.put("assigned_to", request.agentId().toString());
            // Status rules on reassign (when no explicit status override):
            //   - pending → stays pending; the centralized kickoff (below) fires the new
            //     worker, which promotes to in_progress itself when it acquires the lock.
            //   - in_progress → stays in_progress; the assignee CAS lock arbitrates between
            //     the old worker's in-flight execution and the new kickoff (no double-dispatch).
            //   - in_review / terminal → preserved (user didn't ask to change status).
            //
            // We do NOT clear assigneeExecutionId: if the old worker is still mid-sendChatSync,
            // zeroing the lock would let the new kickoff acquire it and both workers would run
            // concurrently on the same task (wasted budget + polluted conversation). The lock
            // naturally releases in the old worker's finally, or ages out via the 15-min TTL.
        }

        // ── Reassign to a HUMAN (Jira-style; never auto-executes) ──
        if (reqAssigneeUserId != null && !reqAssigneeUserId.equals(task.getAssignedToUserId())) {
            String effReviewerUser = reqReviewerUserId != null ? reqReviewerUserId : task.getReviewerUserId();
            if (reqAssigneeUserId.equals(effReviewerUser)) {
                throw new IllegalArgumentException("reviewer and assignee cannot be the same person");
            }
            oldVals.put("assigned_to", assigneeMarker(task));
            task.setAssignedToUserId(reqAssigneeUserId);
            task.setAssignedToAgentId(null); // exclusivity: a human assignee clears any agent assignee
            newVals.put("assigned_to", "user:" + reqAssigneeUserId);
            // Notify the teammate (same bell category, recipient = the person).
            emitTaskAssignedToUserAfterCommit(task, reqAssigneeUserId, "assignee");
        }

        // ── Reviewer change (agent OR human) ──
        if (removeReviewer) {
            if (task.getReviewerAgentId() != null || task.getReviewerUserId() != null) {
                oldVals.put("reviewer", reviewerMarker(task));
                task.setReviewerAgentId(null);
                task.setReviewerUserId(null);
                clearReviewerExecutionIfPresent(task, oldVals, newVals);
                newVals.put("reviewer", "none");
            }
        } else if (request.reviewerAgentId() != null
                && !request.reviewerAgentId().equals(task.getReviewerAgentId())) {
            UUID newReviewerId = request.reviewerAgentId();
            UUID effectiveAssignee = request.agentId() != null ? request.agentId() : task.getAssignedToAgentId();
            if (effectiveAssignee != null && newReviewerId.equals(effectiveAssignee)) {
                throw new IllegalArgumentException("reviewer and assignee cannot be the same agent");
            }
            AgentEntity newReviewer = findAgentByIdScoped(newReviewerId)
                    .orElseThrow(() -> new IllegalArgumentException("reviewer not found: " + newReviewerId));
            String revUpdateOrgId = TenantResolver.currentRequestOrganizationId();
            if (!ScopeGuard.isInStrictScope(tenantId, revUpdateOrgId, newReviewer.getTenantId(), newReviewer.getOrganizationId())) {
                throw new IllegalArgumentException("reviewer belongs to a different tenant");
            }
            oldVals.put("reviewer", reviewerMarker(task));
            task.setReviewerAgentId(newReviewerId);
            task.setReviewerUserId(null); // exclusivity: an agent reviewer clears any human reviewer
            clearReviewerExecutionIfPresent(task, oldVals, newVals);
            newVals.put("reviewer", newReviewerId.toString());
        } else if (reqReviewerUserId != null
                && !reqReviewerUserId.equals(task.getReviewerUserId())) {
            // ── Set a HUMAN reviewer (informational only - no agent review loop) ──
            String effUserAssignee = reqAssigneeUserId != null ? reqAssigneeUserId : task.getAssignedToUserId();
            if (reqReviewerUserId.equals(effUserAssignee)) {
                throw new IllegalArgumentException("reviewer and assignee cannot be the same person");
            }
            oldVals.put("reviewer", reviewerMarker(task));
            task.setReviewerUserId(reqReviewerUserId);
            task.setReviewerAgentId(null); // exclusivity: a human reviewer clears any agent reviewer
            clearReviewerExecutionIfPresent(task, oldVals, newVals);
            newVals.put("reviewer", "user:" + reqReviewerUserId);
            emitTaskAssignedToUserAfterCommit(task, reqReviewerUserId, "reviewer");
        }

        // ── Review-attempt cap ──
        // Same 1-20 range used by assignTask; null leaves the current value untouched.
        if (request.maxReviewAttempts() != null) {
            int cap = request.maxReviewAttempts();
            if (cap < 1) {
                throw new IllegalArgumentException(
                        "max_review_attempts must be >= 1 (got " + cap + ")");
            }
            if (cap > MAX_REVIEW_ATTEMPTS_CEILING) {
                throw new IllegalArgumentException(
                        "max_review_attempts must be <= " + MAX_REVIEW_ATTEMPTS_CEILING
                                + " (got " + cap + ")");
            }
            Integer oldCap = task.getMaxReviewAttempts();
            if (!Integer.valueOf(cap).equals(oldCap)) {
                oldVals.put("max_review_attempts", oldCap == null ? "default" : oldCap);
                task.setMaxReviewAttempts(cap);
                newVals.put("max_review_attempts", cap);
            }
        }

        if (!statusEventRecorded && !newVals.isEmpty()) {
            self.recordEvent(taskId, AgentTaskEventEntity.EVT_UPDATED,
                    callingAgentId, callingUserId, oldVals, newVals);
        }
        AgentTaskEntity saved = taskRepository.save(task);
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, saved));

        // Centralized kickoff - same chokepoint as assignTask. Fires when this update
        // leaves the task runnable (pending OR in_progress) with an assignee. The worker
        // promotes pending→in_progress itself upon acquiring the CAS lock. Drag-to-
        // in_progress (explicit status override) and drag-onto-agent (reassign leaves
        // pending) both end here. The lock prevents double-dispatch.
        String finalStatus = saved.getStatus();
        if (saved.getAssignedToAgentId() != null
                && (AgentTaskEntity.STATUS_PENDING.equals(finalStatus)
                    || AgentTaskEntity.STATUS_IN_PROGRESS.equals(finalStatus))) {
            triggerAssigneeAgentAsync(saved);
        }
        return saved;
    }

    private static void clearReviewerExecutionIfPresent(AgentTaskEntity task,
                                                        Map<String, Object> oldVals,
                                                        Map<String, Object> newVals) {
        if (task.getReviewerExecutionId() == null) {
            return;
        }
        oldVals.putIfAbsent("reviewer_execution_id", task.getReviewerExecutionId().toString());
        task.setReviewerExecutionId(null);
        newVals.put("reviewer_execution_id", "none");
    }

    @Transactional
    public int cancelTask(String tenantId,
                          UUID taskId,
                          UUID callingAgentId,
                          String callingUserId,
                          String reason) {
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));

        // Authorization: creator, reviewer, or human can cancel - NOT the assigned agent.
        boolean isCreatorAgent = callingAgentId != null
                && callingAgentId.equals(task.getCreatedByAgentId());
        boolean isReviewer = callingAgentId != null
                && callingAgentId.equals(task.getReviewerAgentId());
        boolean isCreatorHuman = callingUserId != null
                && callingUserId.equals(task.getCreatedByUserId());
        boolean isTenantOwner = callingUserId != null
                && callingUserId.equals(tenantId);
        boolean isAssignee = callingAgentId != null
                && callingAgentId.equals(task.getAssignedToAgentId());
        if (isAssignee && !isCreatorAgent && !isReviewer) {
            throw new IllegalStateException(
                    "the assigned agent cannot cancel a task - use task_reject to report failure instead");
        }
        if (!isCreatorAgent && !isReviewer && !isCreatorHuman && !isTenantOwner) {
            throw new IllegalStateException("only the task creator or reviewer may cancel it");
        }

        int cancelled = taskRepository.cascadingCancel(taskId, tenantId, reason);
        if (cancelled > 0) {
            // Only emit an audit event when something actually transitioned - a
            // no-op cancel on a terminal task should not pollute the log.
            self.recordEvent(taskId, AgentTaskEventEntity.EVT_CANCELLED, callingAgentId, callingUserId,
                    null, Map.of("cancelled_count", cancelled, "reason", reason == null ? "" : reason));
            // Re-fetch to get the updated status for the WS event
            AgentTaskEntity updated = findTaskByIdScoped(taskId, tenantId).orElse(null);
            if (updated != null) {
                publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, updated));
            }
        }
        return cancelled;
    }

    // ========================================================================
    // Hard delete
    // ========================================================================

    /**
     * Permanently deletes a task and all related data (events, notes).
     * Only allowed for terminal tasks (completed, failed, cancelled).
     * Recursively deletes terminal children. Rejects if any child is non-terminal.
     * Tenant owner only.
     */
    @Transactional
    public int hardDeleteTask(String tenantId, UUID taskId, String callingUserId) {
        if (callingUserId == null || !callingUserId.equals(tenantId)) {
            throw new IllegalStateException("only the tenant owner may hard-delete tasks");
        }
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));

        // F4: classify by CATEGORY, not the literal status, so a custom TERMINAL
        // column (done/failed/cancelled) also qualifies for hard-delete and a
        // custom ACTIVE column correctly blocks it. DELETED is not terminal, so a
        // soft-deleted task still goes through the purge path, not here (unchanged).
        java.util.function.Predicate<AgentTaskEntity> isTerminalCategory = tk ->
                categoryOfStatus(tk, tk.getStatus()).map(TaskStatusCategory::isTerminal).orElse(false);

        if (!isTerminalCategory.test(task)) {
            throw new IllegalStateException(
                    "only terminal tasks (completed/failed/cancelled) can be hard-deleted, current: " + task.getStatus());
        }

        // Check children. Batch A2 - org-aware via task's own org tag (always
        // set by V261 + assignTask). Falls back to tenant-only when null.
        List<AgentTaskEntity> children = (task.getOrganizationId() != null && !task.getOrganizationId().isBlank())
                ? taskRepository.findByParentTaskIdAndOrganizationIdStrict(task.getOrganizationId(), taskId)
                : taskRepository.findByTenantIdAndParentTaskIdOrderByCreatedAtAsc(tenantId, taskId);
        for (AgentTaskEntity child : children) {
            if (!isTerminalCategory.test(child)) {
                throw new IllegalStateException(
                        "cannot delete: child task " + child.getId() + " is still " + child.getStatus());
            }
        }

        // Recursively delete children first
        int deleted = 0;
        for (AgentTaskEntity child : children) {
            deleted += hardDeleteTask(tenantId, child.getId(), callingUserId);
        }

        // Unlink executions and delete related data, then the task itself
        if (executionRepository != null) {
            executionRepository.unlinkTaskId(taskId);
        }
        eventRepository.deleteByTaskId(taskId);
        noteRepository.deleteByTaskId(taskId);
        taskRepository.delete(task);
        deleted++;

        final String deletedTaskId = taskId.toString();
        publishAfterCommit(() -> taskBoardPublisher.publishTaskDeleted(tenantId, deletedTaskId));
        logger.info("Hard-deleted task {} (tenant={})", taskId, tenantId);
        return deleted;
    }

    // ========================================================================
    // Soft delete (trash) + restore + retention purge
    // ========================================================================

    /**
     * Authorization shared by soft-delete and restore - same rule as
     * {@link #cancelTask}: the task creator, its reviewer, or the tenant owner may
     * trash/restore it; the assigned agent alone may not (it uses task_reject).
     */
    private void assertCanTrash(AgentTaskEntity task, String tenantId,
                                UUID callingAgentId, String callingUserId) {
        boolean isCreatorAgent = callingAgentId != null
                && callingAgentId.equals(task.getCreatedByAgentId());
        boolean isReviewer = callingAgentId != null
                && callingAgentId.equals(task.getReviewerAgentId());
        boolean isCreatorHuman = callingUserId != null
                && callingUserId.equals(task.getCreatedByUserId());
        boolean isTenantOwner = callingUserId != null
                && callingUserId.equals(tenantId);
        boolean isAssignee = callingAgentId != null
                && callingAgentId.equals(task.getAssignedToAgentId());
        if (isAssignee && !isCreatorAgent && !isReviewer) {
            throw new IllegalStateException(
                    "the assigned agent cannot delete a task - use task_reject to report failure instead");
        }
        if (!isCreatorAgent && !isReviewer && !isCreatorHuman && !isTenantOwner) {
            throw new IllegalStateException("only the task creator, reviewer, or tenant owner may delete it");
        }
    }

    /**
     * Soft-delete a task: move it to the board's Deleted/trash column. Allowed from
     * ANY status (unlike {@link #hardDeleteTask}, which is terminal-only). Records the
     * pre-trash status in {@code previous_status} so {@link #restoreTask} can return it
     * to its origin column, stamps {@code deleted_at} for the retention sweep, and
     * detaches any in-flight execution lock so the card no longer reads as "running"
     * (status='deleted' already removes it from every agent inbox/backlog/review query,
     * so no new dispatch happens; a running execution's eventual CAS write no-ops).
     * <p>Idempotent - a second call on an already-trashed task returns it unchanged.
     * <b>Non-cascading</b> by design: only the selected task is trashed; children keep
     * their own columns (their {@code parent_task_id} is nulled only if the parent is
     * later purged, via the FK {@code ON DELETE SET NULL}).
     */
    @Transactional
    public AgentTaskEntity softDeleteTask(String tenantId,
                                          UUID taskId,
                                          UUID callingAgentId,
                                          String callingUserId) {
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        assertCanTrash(task, tenantId, callingAgentId, callingUserId);
        if (AgentTaskEntity.STATUS_DELETED.equals(task.getStatus())) {
            return task; // already in the trash - no-op (authorization still enforced above)
        }

        String prev = task.getStatus();
        task.setPreviousStatus(prev);
        task.setStatus(AgentTaskEntity.STATUS_DELETED);
        task.setDeletedAt(Instant.now());
        // Detach any execution lock WITHOUT reverting status (forceUnlock* would set it
        // back to pending). The trashed task must stay 'deleted'.
        task.setAssigneeExecutionId(null);
        task.setReviewerExecutionId(null);

        AgentTaskEntity saved = taskRepository.save(task);
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_DELETED, callingAgentId, callingUserId,
                Map.of("status", prev), Map.of("status", AgentTaskEntity.STATUS_DELETED));
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, saved));
        logger.info("Soft-deleted task {} (tenant={}, from={})", taskId, tenantId, prev);
        return saved;
    }

    /**
     * Restore a trashed task to its previous column (the {@code previous_status} captured
     * at soft-delete; falls back to 'pending' if absent/invalid). If the prior column was
     * in_progress/in_review but the task no longer has an assignee, it falls back to
     * pending so the "requires an assignee" invariant is never violated. Only valid while
     * the task is in the trash.
     */
    @Transactional
    public AgentTaskEntity restoreTask(String tenantId,
                                       UUID taskId,
                                       UUID callingAgentId,
                                       String callingUserId) {
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        if (!AgentTaskEntity.STATUS_DELETED.equals(task.getStatus())) {
            throw new IllegalStateException("only a task in the Deleted column can be restored");
        }
        assertCanTrash(task, tenantId, callingAgentId, callingUserId);

        // F4: the previous column may be a custom status. Restore to it when it
        // still resolves to a (non-deleted) category on the board, else pending.
        String restored = task.getPreviousStatus();
        TaskStatusCategory restoredCat = categoryOfStatus(task, restored).orElse(null);
        if (restored == null || restoredCat == null || restoredCat == TaskStatusCategory.DELETED) {
            restored = AgentTaskEntity.STATUS_PENDING;
            restoredCat = TaskStatusCategory.PENDING;
        }
        if ((restoredCat == TaskStatusCategory.IN_PROGRESS || restoredCat == TaskStatusCategory.IN_REVIEW)
                && task.getAssignedToAgentId() == null && task.getAssignedToUserId() == null) {
            restored = AgentTaskEntity.STATUS_PENDING;
        }
        task.setStatus(restored);
        task.setDeletedAt(null);
        task.setPreviousStatus(null);

        AgentTaskEntity saved = taskRepository.save(task);
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_RESTORED, callingAgentId, callingUserId,
                Map.of("status", AgentTaskEntity.STATUS_DELETED), Map.of("status", restored));
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, saved));
        logger.info("Restored task {} (tenant={}, to={})", taskId, tenantId, restored);
        return saved;
    }

    /**
     * Permanently remove a single trashed task NOW (the board's "delete permanently"
     * action). Tenant-owner only, and only valid for a task already in the Deleted
     * column - live tasks must be trashed (or cancelled) first, mirroring the safety of
     * {@link #hardDeleteTask}.
     */
    @Transactional
    public int purgeDeletedTask(String tenantId, UUID taskId, String callingUserId) {
        if (callingUserId == null || !callingUserId.equals(tenantId)) {
            throw new IllegalStateException("only the tenant owner may permanently delete tasks");
        }
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        if (!AgentTaskEntity.STATUS_DELETED.equals(task.getStatus())) {
            throw new IllegalStateException(
                    "only a task in the Deleted column can be permanently deleted, current: " + task.getStatus());
        }
        purgeRow(task, tenantId);
        logger.info("Purged trashed task {} (tenant={})", taskId, tenantId);
        return 1;
    }

    /**
     * Retention sweep entry point (called by {@code TaskRetentionPurger}). Hard-purges
     * tasks soft-deleted more than {@code retentionDays} ago, in batches, each in its own
     * transaction so one bad row never rolls back the whole sweep. Runs without a request
     * scope (system job) - operates across every tenant. Returns the purged count.
     */
    public int purgeExpiredDeletedTasks(long retentionDays, int batchSize) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(Math.max(0, retentionDays)));
        List<UUID> ids = taskRepository.findExpiredDeletedIds(
                cutoff, PageRequest.of(0, Math.max(1, batchSize)));
        int purged = 0;
        for (UUID id : ids) {
            try {
                if (self.purgeExpiredDeletedTaskById(id)) purged++;
            } catch (Exception e) {
                logger.warn("Task retention purge failed for {}: {}", id, e.getMessage());
            }
        }
        return purged;
    }

    /**
     * Purges one expired trashed task in its own transaction. Re-checks the status under
     * the row read so a concurrent restore between the id scan and the delete is honored.
     * {@code public} only so Spring's proxy applies the {@code REQUIRES_NEW} advice.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean purgeExpiredDeletedTaskById(UUID id) {
        AgentTaskEntity task = taskRepository.findById(id).orElse(null);
        if (task == null || !AgentTaskEntity.STATUS_DELETED.equals(task.getStatus())) {
            return false;
        }
        purgeRow(task, task.getTenantId());
        logger.info("Retention-purged trashed task {} (tenant={})", id, task.getTenantId());
        return true;
    }

    /**
     * Hard-removes a task row and its related data (executions unlinked, events + notes
     * deleted), then publishes a board delete event after commit. Children's
     * {@code parent_task_id} is nulled by the FK {@code ON DELETE SET NULL}. Shared by the
     * manual permanent-delete and the retention purge - same cleanup as
     * {@link #hardDeleteTask}.
     */
    private void purgeRow(AgentTaskEntity task, String tenantId) {
        UUID taskId = task.getId();
        if (executionRepository != null) {
            executionRepository.unlinkTaskId(taskId);
        }
        eventRepository.deleteByTaskId(taskId);
        noteRepository.deleteByTaskId(taskId);
        taskRepository.delete(task);
        final String deletedTaskId = taskId.toString();
        publishAfterCommit(() -> taskBoardPublisher.publishTaskDeleted(tenantId, deletedTaskId));
    }

    // ========================================================================
    // Notes
    // ========================================================================

    @Transactional
    public AgentTaskNoteEntity addNote(String tenantId, UUID taskId, UUID agentId,
                                        String userId, String content) {
        return addNote(tenantId, taskId, agentId, userId, content, null);
    }

    /**
     * Add a note, optionally @-mentioning teammates (F11). Each mentioned user
     * that is a member of the task's workspace (and is not the author) receives
     * an {@code AGENT_TASK_MENTION} bell notification.
     */
    @Transactional
    public AgentTaskNoteEntity addNote(String tenantId, UUID taskId, UUID agentId,
                                        String userId, String content, List<String> mentionedUserIds) {
        if (isBlank(content)) {
            throw new IllegalArgumentException("note content is required");
        }
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        AgentTaskNoteEntity note = new AgentTaskNoteEntity();
        note.setTaskId(task.getId());
        // Stamp organization_id from the parent (V281 NOT NULL). The note inherits the parent's
        // org so the org-scoped finder can filter without a JOIN.
        note.setOrganizationId(task.getOrganizationId());
        note.setAuthorAgentId(agentId);
        note.setAuthorUserId(userId);
        note.setContent(content);
        AgentTaskNoteEntity saved = noteRepository.save(note);
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_NOTE_ADDED, agentId, userId,
                null, Map.of("note_id", saved.getId().toString()));
        // Publish updated task (with new note) for the board detail panel
        publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, task));
        emitNoteMentionsAfterCommit(task, saved, userId, mentionedUserIds);
        return saved;
    }

    // ========================================================================
    // Summary for prompt injection
    // ========================================================================

    @Transactional(readOnly = true)
    public TaskSummaryResponse getTaskSummaryForPrompt(String tenantId, UUID agentId, Instant since) {
        return getTaskSummaryForPrompt(tenantId, TenantResolver.currentRequestOrganizationId(), agentId, since);
    }

    /**
     * Audit 2026-05-17 round-4 - org-aware overload. The 4 counts are routed
     * to strict-org or strict-personal queries based on the caller's active
     * workspace, mirroring the backlog routing pattern from PR23. Daemon
     * callers (e.g. ScheduleExecutorService) MUST pass {@code organizationId}
     * explicitly; HTTP callers may pass null and the shim resolves it from
     * {@code RequestContextHolder}.
     */
    @Transactional(readOnly = true)
    public TaskSummaryResponse getTaskSummaryForPrompt(String tenantId,
                                                       String organizationId,
                                                       UUID agentId,
                                                       Instant since) {
        TenantResolver.requireOrgId(organizationId);
        long pending = taskRepository.countActiveInboxByOrganizationIdStrict(organizationId, agentId);
        long completed = taskRepository.countCompletedOutboxSinceByOrganizationIdStrict(organizationId, agentId, since);
        // V340 - only advertise the shared backlog (count + claim hint) to agents
        // that opted in. A non-participating agent never sees the backlog line, so
        // it is not nudged to claim work it has no relationship to. Directly-
        // assigned (pending) and review counts are always reported.
        long backlog = isBacklogEnabled(agentId)
                ? taskRepository.countBacklogByOrganizationIdStrict(organizationId)
                : 0L;
        long pendingReviews = taskRepository.countPendingReviewsByOrganizationIdStrict(organizationId, agentId);
        return new TaskSummaryResponse(pending, completed, backlog, pendingReviews);
    }

    /**
     * V340 - whether the agent has opted into the shared task backlog. Single
     * source of truth for every backlog-gating path (schedule prompt,
     * system-prompt task summary, MCP {@code claim}/{@code backlog}). A missing
     * agent resolves to {@code false} (deny). Directly-assigned inbox tasks and
     * review tasks are NOT governed by this flag - those are targeted, not backlog.
     */
    @Transactional(readOnly = true)
    public boolean isBacklogEnabled(UUID agentId) {
        if (agentId == null) return false;
        return agentRepository.findBacklogEnabledById(agentId).orElse(false);
    }

    /**
     * Returns the ID of the agent's most recent in-progress task (if any).
     * Used to inject {@code __taskId__} into sub-agent credentials for execution→task tracing.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> getCurrentInProgressTaskId(String tenantId, UUID agentId) {
        return taskRepository
                .findTopByTenantIdAndAssignedToAgentIdAndStatusOrderByStartedAtDesc(
                        tenantId, agentId, AgentTaskEntity.STATUS_IN_PROGRESS)
                .map(AgentTaskEntity::getId);
    }

    /**
     * Best-effort resolve of the most relevant task for an agent execution.
     * Prefers in_progress, falls back to the most recently started task
     * (any status) within the given window.
     */
    public Optional<UUID> resolveTaskIdForExecution(String tenantId, UUID agentId) {
        Optional<UUID> inProgress = getCurrentInProgressTaskId(tenantId, agentId);
        if (inProgress.isPresent()) return inProgress;
        // Fallback: most recently started task regardless of status
        return taskRepository
                .findTopByTenantIdAndAssignedToAgentIdAndStartedAtIsNotNullOrderByStartedAtDesc(
                        tenantId, agentId)
                .map(AgentTaskEntity::getId);
    }

    // ========================================================================
    // Real-time WebSocket publishing
    // ========================================================================

    /**
     * After the transaction commits, auto-trigger the reviewer agent if one is assigned.
     * Creates a fresh conversation and sends a review prompt via conversation-service.
     * Fire-and-forget: failures are logged but never propagate.
     */
    /**
     * Triggers the reviewer agent asynchronously (after transaction commit).
     * Used by {@code completeTask} and {@code rejectTask} when the task is NOT
     * being executed as part of a synchronous delegation chain.
     */
    private void triggerReviewerAgentAsync(AgentTaskEntity task) {
        if (task.getReviewerAgentId() == null || conversationClient == null) return;
        // Must run on a separate thread - executeReviewerForTask blocks in sendChatSync,
        // and running it in afterCommit would deadlock the worker's tool-response thread.
        // Phase 6 MIGRATION_ORG_ID_NOT_NULL (C-5, 2026-05-19): pin the task's
        // organization_id onto the ForkJoinPool worker so any persist downstream
        // (review event, conversation note) lands a concrete UUID for V261 NOT NULL.
        final String taskOrgId = task.getOrganizationId();
        runAfterCommit(() -> CompletableFuture.runAsync(() ->
                com.apimarketplace.common.web.TenantResolver.runWithOrgScope(
                        taskOrgId, () -> executeReviewerForTask(task)))
                .exceptionally(ex -> {
                    logger.error("[TaskReview] Async reviewer failed for task {}: {}", task.getId(), ex.getMessage(), ex);
                    return null;
                }), "Reviewer kickoff");
    }

    /**
     * Centralized assignee (worker) kickoff. Fired from every path that leaves a task in
     * {@code in_progress} with an assignee: {@code assignTask} (POST /tasks, MCP assign),
     * {@code updateTask} (PATCH status=in_progress, reassign), {@code rejectReview}
     * (reviewer sent work back). The CAS {@code assigneeExecutionId} lock inside
     * {@link #executeAgentForTask} serialises every caller so double-dispatch is impossible.
     * Mirrors {@link #triggerReviewerAgentAsync}: after-commit + async to avoid the
     * current thread blocking on {@code sendChatSync}.
     */
    private void triggerAssigneeAgentAsync(AgentTaskEntity task) {
        if (task.getAssignedToAgentId() == null || conversationClient == null) return;
        // Phase 6 MIGRATION_ORG_ID_NOT_NULL (C-5, 2026-05-19): bind org on the
        // ForkJoinPool worker - symmetric with triggerReviewerAgentAsync.
        final String taskOrgId = task.getOrganizationId();
        runAfterCommit(() -> CompletableFuture.runAsync(() ->
                com.apimarketplace.common.web.TenantResolver.runWithOrgScope(
                        taskOrgId, () -> executeAgentForTask(task)))
                .exceptionally(ex -> {
                    logger.error("[TaskExec] Async assignee kickoff failed for task {}: {}", task.getId(), ex.getMessage(), ex);
                    return null;
                }), "Assignee kickoff");
    }

    /**
     * Executes the reviewer agent synchronously (blocks until the review conversation completes).
     * The reviewer is expected to call {@code task_approve} or {@code task_reject_review}.
     */
    private void executeReviewerForTask(AgentTaskEntity task) {
        String tenantId = task.getTenantId();
        UUID taskId = task.getId();

        // If the task is no longer in_review (e.g., approved between scheduling and firing
        // a retry, or cancelled), skip. See bug #7 retry flow in TASK_TEST_ERRORS.md.
        Optional<AgentTaskEntity> current = findTaskByIdScoped(taskId, tenantId);
        if (current.isEmpty() || !AgentTaskEntity.STATUS_IN_REVIEW.equals(current.get().getStatus())) {
            logger.info("[TaskReview] Task {} no longer in_review, skipping reviewer execution", taskId);
            return;
        }

        // P3/P6: CAS guard - prevent concurrent reviewer executions for the same task
        UUID executionId = UUID.randomUUID();
        if (!self.tryLockReviewerExecution(taskId, executionId)) {
            logger.info("[TaskReview] Reviewer already running for task {}, skipping", taskId);
            return;
        }

        UUID reviewerAgentId = null;
        try {
            Optional<AgentTaskEntity> lockedTaskOpt = findTaskByIdScoped(taskId, tenantId);
            if (lockedTaskOpt.isEmpty()
                    || !AgentTaskEntity.STATUS_IN_REVIEW.equals(lockedTaskOpt.get().getStatus())
                    || lockedTaskOpt.get().getReviewerAgentId() == null) {
                logger.info("[TaskReview] Task {} changed after reviewer lock acquisition, skipping reviewer execution",
                        taskId);
                return;
            }
            AgentTaskEntity lockedTask = lockedTaskOpt.get();
            reviewerAgentId = lockedTask.getReviewerAgentId();

            Optional<AgentTaskDispatchView> reviewerOpt = findAgentTaskDispatchViewScoped(reviewerAgentId);
            if (reviewerOpt.isEmpty()) {
                logger.warn("[TaskReview] Reviewer agent {} not found, skipping for task {}", reviewerAgentId, taskId);
                return;
            }
            AgentTaskDispatchView reviewer = reviewerOpt.get();
            if (!Boolean.TRUE.equals(reviewer.isActive())) {
                logger.info("[TaskReview] Reviewer agent {} is inactive, skipping for task {}", reviewerAgentId, taskId);
                return;
            }

            String model = reviewer.modelName();
            String provider = reviewer.modelProvider();

            // Use the reviewer's existing conversation (one conversation per agent)
            String conversationId = conversationClient.findOrCreateAgentConversation(
                    reviewerAgentId.toString(), tenantId, reviewer.name(), lockedTask.getOrganizationId());
            if (conversationId == null) {
                logger.error("[TaskReview] Failed to create conversation for reviewer agent {}", reviewerAgentId);
                return;
            }

            String prompt = buildReviewPrompt(lockedTask);

            var result = conversationClient.sendChatSync(
                    tenantId, conversationId, prompt,
                    reviewerAgentId.toString(), model, provider, "TASK_REVIEW", taskId.toString(),
                    lockedTask.getOrganizationId(), executionId.toString());

            boolean success = Boolean.TRUE.equals(result.get("success"));
            if (success) {
                logger.info("[TaskReview] Reviewer agent {} executed for task {} (conversation: {})",
                        reviewerAgentId, taskId, conversationId);
            } else {
                logger.error("[TaskReview] Reviewer agent {} failed for task {}: {}",
                        reviewerAgentId, taskId, result.get("error"));
            }

            // P8: If the reviewer finished but never called task_approve/task_reject_review,
            // the task is still in_review. Increment the attempt counter so it eventually
            // auto-fails (MAX_REVIEW_ATTEMPTS or the per-task override) rather than staying orphaned.
            handleReviewerFailureToAct(taskId, tenantId, reviewerAgentId, executionId);
        } catch (Exception e) {
            logger.error("[TaskReview] Error executing reviewer agent {} for task {}: {}",
                    reviewerAgentId, taskId, e.getMessage(), e);
            // Also handle failure-to-act on exception (reviewer crashed without deciding)
            if (reviewerAgentId != null) {
                handleReviewerFailureToAct(taskId, tenantId, reviewerAgentId, executionId);
            }
        } finally {
            self.unlockReviewerExecution(taskId, executionId);
        }
    }

    /**
     * If the reviewer finished (or crashed) without calling task_approve or task_reject_review,
     * the task is still in_review. Increment the attempt counter so it auto-fails after
     * the effective cap is reached, preventing orphaned tasks (unvalidated work never silently
     * passes as completed).
     */
    private void handleReviewerFailureToAct(UUID taskId, String tenantId, UUID reviewerAgentId,
                                            UUID reviewerExecutionId) {
        try {
            // Increment first (atomic gate on status=in_review); then re-read to pick up
            // the post-increment count AND the per-task cap from the same snapshot.
            int attemptCount = self.incrementReviewAttemptCount(taskId, tenantId, reviewerAgentId, reviewerExecutionId);
            if (attemptCount == 0) {
                // Task no longer in_review (concurrent approve/reject/cancel), or the
                // reviewer id drifted (task reassigned) - nothing to do.
                return;
            }
            Optional<AgentTaskEntity> taskOpt = findTaskByIdScoped(taskId, tenantId);
            if (taskOpt.isEmpty() || !AgentTaskEntity.STATUS_IN_REVIEW.equals(taskOpt.get().getStatus())) {
                return;
            }
            int cap = effectiveMaxReviewAttempts(taskOpt.get());
            if (attemptCount >= cap) {
                logger.warn("[TaskReview] Reviewer failed to act {} times for task {}, auto-failing", attemptCount, taskId);
                self.autoFailAfterReviewerRejection(taskId, tenantId, reviewerAgentId, reviewerExecutionId,
                        "auto-failed after " + cap + " failed reviewer run(s) (no decision tool called)");
            } else {
                logger.warn("[TaskReview] Reviewer failed to act for task {} (attempt {}/{}), scheduling retry in {}s",
                        taskId, attemptCount, cap, REVIEW_RETRY_DELAY_SECONDS);
                // Schedule a delayed retry so the reviewer gets another chance.
                // Without this, the reject-loop cap is never reached and the task stays
                // in_review forever. See bug #7 in TASK_TEST_ERRORS.md.
                AgentTaskEntity freshTask = taskOpt.get();
                // Phase 6 MIGRATION_ORG_ID_NOT_NULL (C-5, 2026-05-19): delayed-executor
                // worker runs on a daemon thread - bind org so the listener filet stamps.
                final String freshOrgId = freshTask.getOrganizationId();
                CompletableFuture.runAsync(
                        () -> com.apimarketplace.common.web.TenantResolver.runWithOrgScope(
                                freshOrgId, () -> executeReviewerForTask(freshTask)),
                        CompletableFuture.delayedExecutor(REVIEW_RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
                ).exceptionally(ex -> {
                    logger.error("[TaskReview] Retry reviewer failed for task {}: {}", taskId, ex.getMessage(), ex);
                    return null;
                });
            }
        } catch (Exception e) {
            logger.error("[TaskReview] Error handling reviewer failure-to-act for task {}: {}", taskId, e.getMessage());
        }
    }

    /**
     * Backstop reaper - auto-fails in_progress tasks that have been stuck (no terminal update) far
     * longer than any agent could legitimately run, i.e. the assignee execution was orphaned (worker
     * or pod death, or a lost synchronous downstream call) so neither the in-loop inactivity watchdog
     * nor the normal completion path ever returned a result. Called by {@link TaskStuckInProgressSweeper}.
     *
     * <p>Idempotent and race-safe: {@link #markExecutionFailed} CASes on {@code status='in_progress'},
     * so a task that completed or transitioned concurrently is a silent no-op.</p>
     *
     * @param staleThresholdSeconds how old (by updatedAt) an in_progress task must be to be reaped
     * @param batchSize             max tasks to reap in one tick
     * @return number of tasks auto-failed
     */
    public int sweepStuckInProgressTasks(long staleThresholdSeconds, int batchSize) {
        Instant staleBefore = Instant.now().minusSeconds(staleThresholdSeconds);
        List<AgentTaskEntity> stuck = taskRepository.findStuckInProgressTasks(
                staleBefore, PageRequest.of(0, batchSize));
        int failed = 0;
        for (AgentTaskEntity task : stuck) {
            try {
                long ageSeconds = task.getUpdatedAt() == null ? -1
                        : (Instant.now().getEpochSecond() - task.getUpdatedAt().getEpochSecond());
                String reason = "Auto-failed by the stuck-task reaper: no terminal update for "
                        + staleThresholdSeconds + "s (age=" + ageSeconds + "s) - the agent execution was orphaned.";
                logger.warn("[TaskProgress][Sweeper] Reaping orphaned in_progress task {} (age={}s)",
                        task.getId(), ageSeconds);
                // markExecutionFailed CASes on status='in_progress' (a no-op if it already
                // transitioned), records the EVT_FAILED audit event and publishes the board update.
                self.markExecutionFailed(task.getId(), task.getTenantId(), reason);
                failed++;
            } catch (Exception e) {
                logger.error("[TaskProgress][Sweeper] Error reaping task {}: {}", task.getId(), e.getMessage(), e);
            }
        }
        return failed;
    }

    /**
     * Sweeper entry - recovers in_review tasks whose in-memory retry schedule was
     * lost (e.g., service restart between {@link #handleReviewerFailureToAct} scheduling
     * a retry and that retry firing). Called by {@link TaskReviewSweeper} on a fixed
     * cadence. Idempotent: the {@link #executeReviewerForTask} CAS guard plus the
     * status re-check prevent double-execution if a retry is already pending.
     *
     * @param staleThresholdSeconds how old (by updatedAt) a task must be to be swept
     * @param batchSize             max tasks to sweep in one tick
     * @return number of tasks for which a reviewer retry was triggered
     */
    public int sweepStuckReviewTasks(long staleThresholdSeconds, int batchSize) {
        Instant staleBefore = Instant.now().minusSeconds(staleThresholdSeconds);
        List<AgentTaskEntity> stuck = taskRepository.findStuckInReviewTasks(
                staleBefore, PageRequest.of(0, batchSize));
        int triggered = 0;
        for (AgentTaskEntity task : stuck) {
            try {
                if (task.getReviewerAgentId() == null) {
                    // Defensive: the repository query already filters reviewerAgentId IS NOT NULL,
                    // so reaching this branch means a task slipped through the filter - log so we
                    // can spot it in prod rather than silently skipping.
                    logger.warn("[TaskReview][Sweeper] Task {} has no reviewer but was returned by findStuckInReviewTasks; skipping",
                            task.getId());
                    continue;
                }
                // If already at max attempts, auto-fail directly instead of re-running the reviewer.
                int cap = effectiveMaxReviewAttempts(task);
                if (task.getReviewAttemptCount() >= cap) {
                    logger.warn("[TaskReview][Sweeper] Task {} already at {}/{} attempts, auto-failing",
                            task.getId(), task.getReviewAttemptCount(), cap);
                    AgentTaskEntity failedTask = self.autoFailAfterReviewerRejection(task.getId(), task.getTenantId(),
                            task.getReviewerAgentId(), task.getReviewerExecutionId(),
                            "auto-failed by sweeper after " + task.getReviewAttemptCount() + " attempt(s)");
                    // Only count as triggered if the CAS won - losing the race means another
                    // path (e.g. handleReviewerFailureToAct) already recovered the task.
                    if (failedTask != null) triggered++;
                    continue;
                }
                logger.info("[TaskReview][Sweeper] Re-triggering reviewer for stuck task {} (attempt {}/{}, age={}s)",
                        task.getId(), task.getReviewAttemptCount(), cap,
                        task.getUpdatedAt() == null ? -1 : (Instant.now().getEpochSecond() - task.getUpdatedAt().getEpochSecond()));
                // Phase 6 MIGRATION_ORG_ID_NOT_NULL (C-5/H-2, 2026-05-19): the
                // sweep runs on a @Scheduled thread; the dispatched CompletableFuture
                // crosses to ForkJoinPool.commonPool where RequestContextHolder is
                // empty. Bind the task's org so the OrgScopedEntityListener filet
                // stamps any persist (review note, audit, sub-agent spawn) with the
                // V261-NOT-NULL value.
                final String taskOrgId = task.getOrganizationId();
                CompletableFuture.runAsync(() -> com.apimarketplace.common.web.TenantResolver.runWithOrgScope(
                                taskOrgId, () -> executeReviewerForTask(task)))
                        .exceptionally(ex -> {
                            logger.error("[TaskReview][Sweeper] Retry reviewer failed for task {}: {}",
                                    task.getId(), ex.getMessage(), ex);
                            return null;
                        });
                triggered++;
            } catch (Exception e) {
                logger.error("[TaskReview][Sweeper] Error sweeping task {}: {}", task.getId(), e.getMessage(), e);
            }
        }
        return triggered;
    }

    /**
     * Builds a role-aware error when task_complete / task_reject is rejected because
     * the caller is the reviewer (not the assignee), or the task is already terminal,
     * or in_review. The message names the correct action so a weak LLM can recover
     * without hitting max_iterations. See bug #8 in TASK_TEST_ERRORS.md.
     *
     * @param verb "complete" (for task_complete) or "reject" (for task_reject)
     */
    private String wrongActionHint(UUID taskId, String tenantId, UUID callerId, String verb) {
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId).orElse(null);
        if (task == null) {
            return "task cannot be " + verb + ("complete".equals(verb) ? "d" : "ed")
                    + ": task not found (task=" + taskId + ")";
        }
        String status = task.getStatus();
        boolean callerIsReviewer = callerId != null && callerId.equals(task.getReviewerAgentId());
        boolean callerIsAssignee = callerId != null && callerId.equals(task.getAssignedToAgentId());

        if (AgentTaskEntity.STATUS_IN_REVIEW.equals(status) && callerIsReviewer) {
            return "You are the REVIEWER of this task, not the assignee. Do NOT call task_"
                    + verb + ". Instead call action='task_approve' (accept the result) or "
                    + "action='task_reject_review' with a reason (send it back for revision). "
                    + "task_id=" + taskId;
        }
        if (AgentTaskEntity.STATUS_IN_REVIEW.equals(status)) {
            return "task is already in_review - waiting for reviewer decision. Only the reviewer "
                    + "can progress it (via task_approve / task_reject_review). Do not retry task_"
                    + verb + ". (task=" + taskId + ")";
        }
        if (AgentTaskEntity.STATUS_COMPLETED.equals(status)
                || AgentTaskEntity.STATUS_FAILED.equals(status)
                || AgentTaskEntity.STATUS_CANCELLED.equals(status)) {
            return "task is already terminal (status=" + status + "). Do not retry task_"
                    + verb + ". (task=" + taskId + ")";
        }
        if (!callerIsAssignee) {
            return "you are not the assignee of this task (assigned_to="
                    + task.getAssignedToAgentId() + "). Only the assignee can call task_"
                    + verb + ". (task=" + taskId + ")";
        }
        return "task cannot be " + verb + ("complete".equals(verb) ? "d" : "ed")
                + ": expected status=in_progress, got " + status + " (task=" + taskId + ")";
    }

    /**
     * Role-aware error hint when a reviewer-only action (task_approve / task_reject_review)
     * fails the CAS update. Mirrors {@link #wrongActionHint} for symmetry - when the caller
     * is the ASSIGNEE (not the reviewer), they're told to call task_complete / task_reject
     * instead. When the task is already terminal or not found, the message makes that
     * explicit.
     *
     * @param verb "approve" or "reject_review"
     */
    private String wrongReviewerActionHint(UUID taskId, String tenantId, UUID callerId, String verb) {
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId).orElse(null);
        if (task == null) {
            return "task_" + verb + " failed: task not found (task=" + taskId + ")";
        }
        String status = task.getStatus();
        boolean callerIsReviewer = callerId != null && callerId.equals(task.getReviewerAgentId());
        boolean callerIsAssignee = callerId != null && callerId.equals(task.getAssignedToAgentId());

        if (AgentTaskEntity.STATUS_COMPLETED.equals(status)
                || AgentTaskEntity.STATUS_FAILED.equals(status)
                || AgentTaskEntity.STATUS_CANCELLED.equals(status)) {
            return "task is already terminal (status=" + status + "). Do not retry task_"
                    + verb + ". (task=" + taskId + ")";
        }
        if (callerIsAssignee && !callerIsReviewer) {
            return "You are the ASSIGNEE of this task, not the reviewer. Do NOT call task_"
                    + verb + ". Instead call action='task_complete' (submit your work) or "
                    + "action='task_reject' with a reason (report failure). task_id=" + taskId;
        }
        if (!AgentTaskEntity.STATUS_IN_REVIEW.equals(status)) {
            return "task_" + verb + " failed: task is not in_review yet (status=" + status
                    + "). Only tasks submitted for review (via task_complete / task_reject) "
                    + "can be approved or sent back. (task=" + taskId + ")";
        }
        if (!callerIsReviewer) {
            return "you are not the reviewer of this task (reviewer="
                    + task.getReviewerAgentId() + "). Only the designated reviewer can call task_"
                    + verb + ". (task=" + taskId + ")";
        }
        // Status is in_review AND caller is the reviewer - CAS failed for another reason
        // (race with concurrent approve/reject). Surface a generic retry hint.
        return "task_" + verb + " lost a race with another concurrent review decision "
                + "(task=" + taskId + "). Re-read the task to see its current state.";
    }

    private static void requireReviewerExecutionToken(UUID reviewerExecutionId, String verb) {
        if (reviewerExecutionId != null) {
            return;
        }
        throw new IllegalStateException("task_" + verb
                + " requires a reviewer execution token. Reviewer agents must resolve reviews from the task review run; "
                + "task-board users should approve or request changes without as_agent_id.");
    }

    private String buildReviewPrompt(AgentTaskEntity task) {
        StringBuilder sb = new StringBuilder();
        sb.append("A task has been submitted for your review.\n\n");
        sb.append("**Task:** ").append(task.getTitle()).append("\n");
        sb.append("**Task ID:** ").append(task.getId()).append("\n");
        if (task.getInstructions() != null && !task.getInstructions().isBlank()) {
            sb.append("**Instructions:** ").append(task.getInstructions()).append("\n");
        }
        if (task.getResult() != null && !task.getResult().isBlank()) {
            sb.append("\n**Agent result:**\n").append(task.getResult()).append("\n");
        }
        if (task.getErrorMessage() != null && !task.getErrorMessage().isBlank()) {
            sb.append("\n**Agent reported error:**\n").append(task.getErrorMessage()).append("\n");
        }
        sb.append("\nReview this task result. If the work is correct and complete, approve it. ");
        sb.append("If incorrect or incomplete, reject it with a clear reason.\n\n");
        sb.append("To approve, call the agent tool with:\n");
        sb.append("  action: \"task_approve\"\n");
        sb.append("  task_id: \"").append(task.getId()).append("\"\n\n");
        sb.append("To reject and send back for revision, call the agent tool with:\n");
        sb.append("  action: \"task_reject_review\"\n");
        sb.append("  task_id: \"").append(task.getId()).append("\"\n");
        sb.append("  reason: \"your reason here\"\n\n");
        sb.append("You MUST call one of these two actions. Do not use any other tools.");
        return sb.toString();
    }

    /**
     * After the transaction commits, auto-trigger the assigned agent to start working on the task.
     * Creates a fresh conversation and sends the task prompt via conversation-service.
     * Fire-and-forget: failures are logged but never propagate.
     */
    /**
     * Executes the assigned agent synchronously (blocks until the agent conversation completes).
     * The agent is expected to call {@code task_complete} or {@code task_reject} during execution.
     * If the agent is not found or inactive, the task is failed with an error message.
     */
    private void executeAgentForTask(AgentTaskEntity task) {
        UUID assigneeId = task.getAssignedToAgentId();
        String tenantId = task.getTenantId();
        UUID taskId = task.getId();

        // CAS lock: prevent double-dispatch when multiple kickoff paths race
        // (e.g. REST POST /tasks + MCP assign + reviewer-rejection retrigger).
        UUID executionId = UUID.randomUUID();
        if (!self.tryLockAssigneeExecution(taskId, executionId)) {
            logger.info("[TaskExec] Assignee already running for task {}, skipping kickoff", taskId);
            return;
        }

        // Honest status: we hold the lock and are about to dispatch - move pending→in_progress
        // now. This is the single source of truth for "a worker is actually running". Fails
        // silently if the task is already in a non-pending state (e.g. reviewer-reject retrigger
        // lands on in_progress directly; fine).
        self.promoteToInProgressIfPending(taskId, tenantId);

        try {
            Optional<AgentTaskDispatchView> agentOpt = findAgentTaskDispatchViewScoped(assigneeId);
            if (agentOpt.isEmpty()) {
                logger.warn("[TaskExec] Assigned agent {} not found, failing task {}", assigneeId, taskId);
                self.markExecutionFailed(taskId, tenantId,
                        "assigned agent not found: " + assigneeId);
                return;
            }
            AgentTaskDispatchView agent = agentOpt.get();
            if (!Boolean.TRUE.equals(agent.isActive())) {
                logger.info("[TaskExec] Assigned agent {} is inactive, failing task {}", assigneeId, taskId);
                self.markExecutionFailed(taskId, tenantId,
                        "assigned agent is inactive: " + assigneeId);
                return;
            }

            String model = agent.modelName();
            String provider = agent.modelProvider();

            // Use the agent's existing conversation (one conversation per agent)
            String conversationId = conversationClient.findOrCreateAgentConversation(
                    assigneeId.toString(), tenantId, agent.name(), task.getOrganizationId());
            if (conversationId == null) {
                logger.error("[TaskExec] Failed to create conversation for agent {}, failing task {}", assigneeId, taskId);
                self.markExecutionFailed(taskId, tenantId,
                        "failed to create conversation for agent: " + assigneeId);
                return;
            }

            String prompt = buildTaskPrompt(task);

            var result = conversationClient.sendChatSync(
                    tenantId, conversationId, prompt,
                    assigneeId.toString(), model, provider, "TASK", taskId.toString(), task.getOrganizationId());

            boolean success = Boolean.TRUE.equals(result.get("success"));
            if (success) {
                logger.info("[TaskExec] Agent {} executed for task {} (conversation: {})",
                        assigneeId, taskId, conversationId);
            } else {
                String error = result.get("error") != null ? result.get("error").toString() : "unknown error";
                // If the task is still in_progress (agent didn't call task_complete/reject), fail it.
                // Bug #6: goes to 'failed' not 'in_review' - infrastructure failure, not worker submission.
                Optional<AgentTaskEntity> latestTask = findTaskByIdScoped(taskId, tenantId);
                if (latestTask.isPresent() && isAcceptedTaskCompletionState(latestTask.get().getStatus())) {
                    logger.info("[TaskExec] Agent {} advanced task {} to {} despite chat success=false: {}",
                            assigneeId, taskId, latestTask.get().getStatus(), error);
                } else {
                    logger.error("[TaskExec] Agent {} failed for task {}: {}", assigneeId, taskId, error);
                    latestTask.ifPresent(t -> {
                        if (AgentTaskEntity.STATUS_IN_PROGRESS.equals(t.getStatus())) {
                            self.markExecutionFailed(taskId, tenantId,
                                    buildTaskFailureMessage(taskId, tenantId, error));
                        }
                    });
                }
            }
        } catch (Exception e) {
            logger.error("[TaskExec] Error executing agent {} for task {}: {}",
                    assigneeId, taskId, e.getMessage(), e);
            // Bug #6: infrastructure error → failed, not in_review.
            findTaskByIdScoped(taskId, tenantId).ifPresent(t -> {
                if (AgentTaskEntity.STATUS_IN_PROGRESS.equals(t.getStatus())) {
                    self.markExecutionFailed(taskId, tenantId,
                            buildTaskFailureMessage(taskId, tenantId, e.getMessage()));
                }
            });
        } finally {
            self.unlockAssigneeExecution(taskId, executionId);
        }
    }

    private static boolean isAcceptedTaskCompletionState(String status) {
        return AgentTaskEntity.STATUS_IN_REVIEW.equals(status)
                || AgentTaskEntity.STATUS_COMPLETED.equals(status);
    }

    /**
     * Builds a task failure message that surfaces the root cause without tautology, and
     * preserves prior reviewer feedback when the worker retry crashes after a rejection.
     *
     * <p>BUG-5 / BUG-8 / BUG-9: before this helper we concatenated a hard-coded
     * "agent execution failed: " prefix on top of an upstream message that was already
     * "Agent execution failed" - producing "agent execution failed: Agent execution failed"
     * and hiding the real reason. We also lost the {@code stop_reason} (BUDGET_EXHAUSTED,
     * MAX_ITERATIONS, TIMEOUT, ERROR) from the execution row.
     *
     * <p>BUG-4: on worker retry after a reviewer rejection, the task's {@code errorMessage}
     * already holds the reviewer's feedback (written by {@code rejectReviewIfReviewer}).
     * A naive overwrite erases that context - the reviewer told the worker what was wrong,
     * but the terminal failure only shows the retry crash. We detect this case via
     * {@link AgentTaskEntity#getReviewAttemptCount()} and prepend the reviewer feedback so
     * the creator / user sees BOTH "why the reviewer sent it back" AND "why the retry died".
     *
     * <p>Strategy (crash message computation):
     * <ol>
     *   <li>Look up the most recent {@code agent_executions} row for this task. If its
     *       {@code stop_reason} is a known non-ERROR terminal (BUDGET_EXHAUSTED,
     *       MAX_ITERATIONS, TIMEOUT) the task failed for a specific operational reason -
     *       surface it verbatim, ignoring the generic upstream message.</li>
     *   <li>Otherwise, use the row's {@code error_message} if populated (real root cause
     *       captured by the provider layer).</li>
     *   <li>Otherwise, fall back to the upstream message with tautological prefixes stripped.</li>
     * </ol>
     *
     * <p>All branches avoid double-prefixing: we never prepend "agent execution failed:"
     * to a message that already contains that text.
     */
    private String buildTaskFailureMessage(UUID taskId, String tenantId, String upstreamError) {
        String crashMessage = computeCrashMessage(taskId, tenantId, upstreamError);

        // BUG-4: if this task was previously sent back by a reviewer, its current
        // errorMessage holds the reviewer's feedback. Combining it with the retry crash
        // keeps both signals visible for whoever reads the terminal failure.
        try {
            AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId).orElse(null);
            if (task != null && task.getReviewAttemptCount() > 0) {
                String reviewerFeedback = task.getErrorMessage();
                if (reviewerFeedback != null && !reviewerFeedback.isBlank()) {
                    return "retry failed after " + task.getReviewAttemptCount()
                            + " reviewer rejection(s) - last reviewer feedback: "
                            + reviewerFeedback.trim()
                            + " | retry error: " + crashMessage;
                }
            }
        } catch (Exception lookupFailure) {
            logger.debug("[TaskExec] failed to look up reviewer feedback for task {}: {}",
                    taskId, lookupFailure.getMessage());
        }
        return crashMessage;
    }

    /**
     * Derives the worker-side crash reason, consulting the execution row first
     * ({@code stop_reason}, then {@code error_message}) and falling back to the
     * upstream message with tautological prefixes stripped.
     */
    private String computeCrashMessage(UUID taskId, String tenantId, String upstreamError) {
        try {
            if (executionRepository != null) {
                // Only need the most recent execution - fetch a page of size 1 instead of
                // pulling every execution attached to the task into memory. Long agent loops
                // with hundreds of attempts otherwise loaded an unbounded list just to peek
                // at row 0.
                List<com.apimarketplace.agent.domain.AgentExecutionEntity> rows =
                        executionRepository.findByTaskIdAndTenantIdOrderByStartedAtDesc(
                                taskId, tenantId,
                                org.springframework.data.domain.PageRequest.of(0, 1))
                        .getContent();
                if (rows != null && !rows.isEmpty()) {
                    var latest = rows.get(0);
                    String stopReason = latest.getStopReason();
                    if (stopReason != null
                            && !"COMPLETED".equals(stopReason)
                            && !"ERROR".equals(stopReason)) {
                        // Operational stop (BUDGET_EXHAUSTED, MAX_ITERATIONS, TIMEOUT, …).
                        // stop_reason is the real reason; upstream "Agent execution failed" is noise.
                        String reasonLabel = stopReason.toLowerCase().replace('_', ' ');
                        return "stopped: " + reasonLabel
                                + (latest.getErrorMessage() != null && !latest.getErrorMessage().isBlank()
                                    ? " - " + latest.getErrorMessage() : "");
                    }
                    if (latest.getErrorMessage() != null && !latest.getErrorMessage().isBlank()) {
                        return latest.getErrorMessage();
                    }
                }
            }
        } catch (Exception lookupFailure) {
            logger.debug("[TaskExec] failed to look up execution row for task {}: {}",
                    taskId, lookupFailure.getMessage());
        }
        return stripTautologicalPrefix(upstreamError);
    }

    /**
     * Strips redundant "agent execution failed/error:" prefixes from an upstream message
     * so that "agent execution failed: Agent execution failed" collapses to a single clear
     * statement. Also trims a trailing colon if the message is empty after stripping.
     */
    static String stripTautologicalPrefix(String raw) {
        if (raw == null || raw.isBlank()) {
            return "agent execution failed without a specific reason";
        }
        String msg = raw.trim();
        // Strip nested "Agent execution failed/error" prefixes (case-insensitive).
        // Matches whether followed by a colon+rest OR by end-of-string (bare tautological phrase).
        java.util.regex.Pattern prefix = java.util.regex.Pattern.compile(
                "^(?i)agent execution (?:failed|error)\\s*(?::\\s*|$)");
        while (prefix.matcher(msg).find()) {
            String next = prefix.matcher(msg).replaceFirst("").trim();
            if (next.equals(msg)) break;
            msg = next;
            if (msg.isEmpty()) return "agent execution failed without a specific reason";
        }
        return "agent execution failed: " + msg;
    }

    private String buildTaskPrompt(AgentTaskEntity task) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Task #").append(task.getId().toString(), 0, 8).append("] ").append(task.getTitle()).append("\n");
        if (task.getDepth() > 0) {
            sb.append("(Subtask, depth ").append(task.getDepth()).append(")\n");
        }
        if (task.getInstructions() != null && !task.getInstructions().isBlank()) {
            sb.append("\nInstructions:\n").append(task.getInstructions()).append("\n");
        }
        if (task.getTaskContext() != null && !task.getTaskContext().isEmpty()) {
            sb.append("\nContext:\n").append(task.getTaskContext()).append("\n");
        }
        // Include parent task context for subtasks. Use the task's own org tag
        // (set at creation) so the strict-scope finder also works on async
        // contexts without a request thread-local.
        if (task.getParentTaskId() != null) {
            Optional<AgentTaskEntity> parentOpt =
                    (task.getOrganizationId() != null && !task.getOrganizationId().isBlank())
                            ? taskRepository.findByIdAndOrganizationIdStrict(task.getParentTaskId(), task.getOrganizationId())
                            : taskRepository.findByIdAndTenantId(task.getParentTaskId(), task.getTenantId());
            parentOpt.ifPresent(parent -> {
                        sb.append("\nParent task [#").append(parent.getId().toString(), 0, 8)
                          .append("]: ").append(parent.getTitle());
                        if (parent.getInstructions() != null && !parent.getInstructions().isBlank()) {
                            String summary = parent.getInstructions().length() > 300
                                    ? parent.getInstructions().substring(0, 300) + "..."
                                    : parent.getInstructions();
                            sb.append("\nParent instructions (summary): ").append(summary);
                        }
                        sb.append("\n");
                    });
        }
        sb.append("\nWhen done, call agent(action='task_complete', task_id='")
          .append(task.getId())
          .append("', result='...') or agent(action='task_reject', task_id='")
          .append(task.getId())
          .append("', reason='...') if you cannot complete it.");
        return sb.toString();
    }

    /**
     * Publishes a task board event after the current transaction commits.
     * Non-blocking - failures are logged but never propagate.
     */
    private void publishAfterCommit(Runnable action) {
        if (taskBoardPublisher == null) return;
        runAfterCommit(action, "Task board publish");
    }

    /**
     * P6 - bell emission for {@code AGENT_TASK_ASSIGNED}. Fires only when the
     * task has an actual assignee (not the unassigned backlog). source_id is
     * the task UUID stringified - matches the master brief's idempotency
     * contract; ON CONFLICT DO NOTHING at the orchestrator endpoint dedupes
     * the rare double-emit (e.g. weak-LLM retry within the dedup window).
     */
    private void emitTaskAssignedAfterCommit(AgentTaskEntity saved, String tenantId, UUID assigneeId) {
        if (notificationClient == null) return;
        if (assigneeId == null || tenantId == null || saved == null || saved.getId() == null) return;

        runAfterCommit(() -> {
            NotificationEmitRequest req = new NotificationEmitRequest();
            req.setTenantId(tenantId);
            req.setOrganizationId(saved.getOrganizationId());
            req.setCategory("AGENT_TASK_ASSIGNED");
            req.setSeverity("info");
            req.setSubjectType("AGENT_TASK");
            req.setSubjectId(saved.getId());
            req.setSourceId(saved.getId().toString());
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "pending");
            if (saved.getTitle() != null) payload.put("subjectName", saved.getTitle());
            payload.put("assigneeAgentId", assigneeId.toString());
            if (saved.getPriority() != null) payload.put("priority", saved.getPriority());
            if (saved.getCreatedByAgentId() != null) {
                payload.put("createdByAgentId", saved.getCreatedByAgentId().toString());
            }
            req.setPayload(payload);
            req.setOccurredAt(saved.getCreatedAt() != null ? saved.getCreatedAt() : Instant.now());
            try {
                notificationClient.emit(req);
            } catch (Exception ex) {
                logger.debug("AGENT_TASK_ASSIGNED emit failed for task {} (non-critical): {}",
                        saved.getId(), ex.getMessage());
            }
        }, "AGENT_TASK_ASSIGNED emit");
    }

    /**
     * Bell emission when a task is assigned to (or set for review by) a HUMAN
     * teammate. Reuses the {@code AGENT_TASK_ASSIGNED} category on purpose - that
     * keeps the bell rendering + i18n surface unchanged - but the recipient
     * ({@code tenantId}) is the assigned/reviewing person, not the task owner.
     *
     * @param role {@code "assignee"} or {@code "reviewer"} - recorded in the
     *             payload and suffixed onto {@code sourceId} so a task with both
     *             a human assignee and a human reviewer emits two distinct rows.
     */
    private void emitTaskAssignedToUserAfterCommit(AgentTaskEntity saved, String recipientUserId, String role) {
        if (notificationClient == null) return;
        if (recipientUserId == null || recipientUserId.isBlank()
                || saved == null || saved.getId() == null) return;

        runAfterCommit(() -> {
            NotificationEmitRequest req = new NotificationEmitRequest();
            req.setTenantId(recipientUserId);
            req.setOrganizationId(saved.getOrganizationId());
            req.setCategory("AGENT_TASK_ASSIGNED");
            req.setSeverity("info");
            req.setSubjectType("AGENT_TASK");
            req.setSubjectId(saved.getId());
            req.setSourceId(saved.getId() + ":" + role);
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "pending");
            if (saved.getTitle() != null) payload.put("subjectName", saved.getTitle());
            payload.put("role", role);
            if (saved.getPriority() != null) payload.put("priority", saved.getPriority());
            req.setPayload(payload);
            req.setOccurredAt(saved.getCreatedAt() != null ? saved.getCreatedAt() : Instant.now());
            try {
                notificationClient.emit(req);
            } catch (Exception ex) {
                logger.debug("task-assigned-to-user emit failed for task {} (non-critical): {}",
                        saved.getId(), ex.getMessage());
            }
        }, "AGENT_TASK_ASSIGNED user emit");
    }

    /**
     * F11: notify each @-mentioned teammate of a note. Recipients are restricted
     * to members of the task's workspace (fail-closed when authClient is wired)
     * and never include the note's author. No-op when notifications are unwired.
     */
    private void emitNoteMentionsAfterCommit(AgentTaskEntity task, AgentTaskNoteEntity note,
                                             String authorUserId, List<String> mentionedUserIds) {
        if (notificationClient == null || mentionedUserIds == null || mentionedUserIds.isEmpty()) return;
        if (task == null || task.getId() == null || note == null) return;

        java.util.LinkedHashSet<String> recipients = new java.util.LinkedHashSet<>();
        Set<String> members = authClient != null ? authClient.getOrganizationMemberIds(task.getOrganizationId()) : null;
        for (String uid : mentionedUserIds) {
            if (uid == null || uid.isBlank()) continue;
            if (uid.equals(authorUserId)) continue;                 // never notify yourself
            if (members != null && !members.contains(uid)) continue; // members only
            recipients.add(uid);
        }
        if (recipients.isEmpty()) return;

        for (String recipient : recipients) {
            runAfterCommit(() -> {
                NotificationEmitRequest req = new NotificationEmitRequest();
                req.setTenantId(recipient);
                req.setOrganizationId(task.getOrganizationId());
                req.setCategory("AGENT_TASK_MENTION");
                req.setSeverity("info");
                req.setSubjectType("AGENT_TASK");
                req.setSubjectId(task.getId());
                req.setSourceId(note.getId() + ":" + recipient);
                Map<String, Object> payload = new HashMap<>();
                if (task.getTitle() != null) payload.put("subjectName", task.getTitle());
                payload.put("noteId", note.getId().toString());
                req.setPayload(payload);
                req.setOccurredAt(note.getCreatedAt() != null ? note.getCreatedAt() : Instant.now());
                try {
                    notificationClient.emit(req);
                } catch (Exception ex) {
                    logger.debug("note-mention emit failed for task {} (non-critical): {}",
                            task.getId(), ex.getMessage());
                }
            }, "AGENT_TASK_MENTION emit");
        }
    }

    /**
     * Bell emission when a task lands in {@code in_review} with <b>no reviewer at all</b> -
     * the "it comes back to the creator" case. A reviewer <i>agent</i> is auto-triggered
     * (so no human attention is needed yet) and a human <i>reviewer</i> was already notified
     * at assign time, so both are skipped here. With neither, the work awaits a manual
     * decision; we surface it to the human who can resolve it via the task board:
     * the task's human creator, or - for an agent-created task with no human creator - the
     * workspace/tenant owner (the {@code tenantId} the {@code approveByTenantOwner} CAS keys on).
     */
    private void emitTaskAwaitingReviewAfterCommit(AgentTaskEntity task, String tenantId) {
        if (notificationClient == null || task == null || task.getId() == null) return;
        if (task.getReviewerAgentId() != null || task.getReviewerUserId() != null) return;

        // Recipient = the human creator, or the workspace/tenant owner (passed in, not read
        // off the entity) for an agent-created task. tenantId is the caller-scoped owner the
        // approveByTenantOwner CAS keys on. Passing it in keeps this a stamping helper rather
        // than a hand-rolled tenant-vs-org scope predicate (OrgScopePredicateInvariantTest).
        String creator = task.getCreatedByUserId();
        String recipient = (creator != null && !creator.isBlank()) ? creator : tenantId;
        if (recipient == null || recipient.isBlank()) return;

        runAfterCommit(() -> {
            NotificationEmitRequest req = new NotificationEmitRequest();
            req.setTenantId(recipient);
            req.setOrganizationId(task.getOrganizationId());
            req.setCategory("AGENT_TASK_AWAITING_REVIEW");
            req.setSeverity("info");
            req.setSubjectType("AGENT_TASK");
            req.setSubjectId(task.getId());
            req.setSourceId(task.getId().toString());
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "in_review");
            if (task.getTitle() != null) payload.put("subjectName", task.getTitle());
            if (task.getPriority() != null) payload.put("priority", task.getPriority());
            req.setPayload(payload);
            req.setOccurredAt(Instant.now());
            try {
                notificationClient.emit(req);
            } catch (Exception ex) {
                logger.debug("AGENT_TASK_AWAITING_REVIEW emit failed for task {} (non-critical): {}",
                        task.getId(), ex.getMessage());
            }
        }, "AGENT_TASK_AWAITING_REVIEW emit");
    }

    /** Trims to null - blank/whitespace human ids collapse to "no assignee". */
    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Rejects a human assignee/reviewer that is not in the workspace's member set.
     * {@code members} is the result of {@link AuthClient#getOrganizationMemberIds}
     * (empty on transport failure → reject = fail closed).
     */
    private static void requireWorkspaceMember(Set<String> members, String userId, String role) {
        if (userId == null) return;
        if (members == null || !members.contains(userId)) {
            throw new IllegalArgumentException(
                    role + " user " + userId + " is not a member of this workspace");
        }
    }

    /** Audit "assigned_to" marker: agent uuid, {@code user:<id>}, or {@code backlog}. */
    private static String assigneeMarker(AgentTaskEntity t) {
        if (t.getAssignedToAgentId() != null) return t.getAssignedToAgentId().toString();
        if (t.getAssignedToUserId() != null) return "user:" + t.getAssignedToUserId();
        return "backlog";
    }

    /** Audit "reviewer" marker: agent uuid, {@code user:<id>}, or {@code none}. */
    private static String reviewerMarker(AgentTaskEntity t) {
        if (t.getReviewerAgentId() != null) return t.getReviewerAgentId().toString();
        if (t.getReviewerUserId() != null) return "user:" + t.getReviewerUserId();
        return "none";
    }

    /**
     * Generic after-commit runner - does NOT depend on {@code taskBoardPublisher}. Use for
     * side effects that must fire once the transaction is durable (e.g. the centralized
     * agent kickoff) even when the WS publisher is absent (test harness).
     */
    private void runAfterCommit(Runnable action, String label) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try { action.run(); } catch (Exception e) {
                        logger.debug("{} failed (non-critical): {}", label, e.getMessage());
                    }
                }
            });
        } else {
            try { action.run(); } catch (Exception e) {
                logger.debug("{} failed (non-critical): {}", label, e.getMessage());
            }
        }
    }

    // ========================================================================
    // Stop agent execution (user-initiated)
    // ========================================================================

    /**
     * Stops a running agent execution on a task by setting the Redis cancel key
     * directly (bypasses conversation-service cascade). Clears the execution lock
     * so the task becomes editable again.
     *
     * @param tenantId       caller's tenant
     * @param organizationId caller's org workspace
     * @param taskId         the task to stop
     * @param role           "assignee" or "reviewer"
     * @return the updated task
     * @throws IllegalArgumentException if task not found
     * @throws IllegalStateException    if task is not in the expected state or has no active execution
     */
    @Transactional
    public TaskResponse stopAgentExecution(String tenantId, String organizationId, UUID taskId, String role) {
        TenantResolver.requireOrgId(organizationId);
        AgentTaskEntity task = taskRepository.findByIdAndOrganizationIdStrict(taskId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));

        UUID executionId;
        if ("assignee".equals(role)) {
            if (!AgentTaskEntity.STATUS_IN_PROGRESS.equals(task.getStatus())) {
                throw new IllegalStateException("task is not in_progress (current: " + task.getStatus() + ")");
            }
            executionId = task.getAssigneeExecutionId();
            if (executionId == null) {
                throw new IllegalStateException("no active assignee execution to stop");
            }
        } else if ("reviewer".equals(role)) {
            if (!AgentTaskEntity.STATUS_IN_REVIEW.equals(task.getStatus())) {
                throw new IllegalStateException("task is not in_review (current: " + task.getStatus() + ")");
            }
            executionId = task.getReviewerExecutionId();
            if (executionId == null) {
                throw new IllegalStateException("no active reviewer execution to stop");
            }
        } else {
            throw new IllegalArgumentException("role must be 'assignee' or 'reviewer'");
        }

        // Set Redis cancel key so the running agent loop sees shouldStop()=true.
        // The assigneeExecutionId is a CAS lock UUID, NOT an AgentExecutionEntity PK
        // (the entity is only persisted by AgentObservabilityService after execution
        // completes). So we resolve the conversation via the agent's conversationId
        // directly - each agent has exactly one conversation.
        UUID agentId = "assignee".equals(role) ? task.getAssignedToAgentId() : task.getReviewerAgentId();
        if (agentId != null && redisTemplate != null && conversationClient != null) {
            try {
                String conversationId = conversationClient.findAgentConversation(
                        agentId.toString(), tenantId, organizationId);
                if (conversationId != null) {
                    String convIndexKey = StreamRedisKeys.convIndexKey(conversationId);
                    String streamId = redisTemplate.opsForValue().get(convIndexKey);
                    if (streamId != null) {
                        String cancelKey = "agent:cancel:" + streamId;
                        redisTemplate.opsForValue().set(cancelKey, "stopped_by_user", Duration.ofMinutes(5));
                        logger.info("Stop agent: set cancel key {} for task {} role={} agent={}",
                                cancelKey, taskId, role, agentId);
                    } else {
                        logger.warn("Stop agent: no streamId found for conversationId={} (task={}, role={}). "
                                + "Agent may have already finished. Clearing lock anyway.", conversationId, taskId, role);
                    }
                } else {
                    logger.warn("Stop agent: no conversation found for agent {} (task={}, role={})", agentId, taskId, role);
                }
            } catch (Exception e) {
                logger.warn("Stop agent: failed to resolve conversation for agent {} (task={}): {}",
                        agentId, taskId, e.getMessage());
            }
        }

        // Clear execution lock and update status
        if ("assignee".equals(role)) {
            taskRepository.forceUnlockAssigneeExecution(taskId);
        } else {
            taskRepository.forceUnlockReviewerExecution(taskId);
        }

        // Record audit event
        String oldStatus = task.getStatus();
        String newStatus = "assignee".equals(role) ? AgentTaskEntity.STATUS_PENDING : AgentTaskEntity.STATUS_IN_PROGRESS;
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_AGENT_STOPPED, null, tenantId,
                Map.of("status", oldStatus, "role", role),
                Map.of("status", newStatus, "role", role));

        // Publish board update after commit
        final String finalTenantId = tenantId;
        publishAfterCommit(() -> {
            taskRepository.findByIdAndOrganizationIdStrict(taskId, organizationId)
                    .ifPresent(t -> taskBoardPublisher.publishTaskUpdated(finalTenantId, t));
        });

        // Re-read and return
        if (entityManager != null) {
            entityManager.flush();
            entityManager.clear();
        }
        AgentTaskEntity updated = taskRepository.findByIdAndOrganizationIdStrict(taskId, organizationId)
                .orElseThrow(() -> new IllegalStateException("task vanished after stop: " + taskId));
        return TaskResponse.from(updated);
    }

    // ========================================================================
    // Reviewer concurrency + attempt tracking (transactional wrappers)
    // ========================================================================

    /** CAS lock for reviewer execution - runs in its own transaction so it works from async threads. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryLockReviewerExecution(UUID taskId, UUID executionId) {
        return taskRepository.tryLockReviewerExecution(taskId, executionId) > 0;
    }

    /** Release the reviewer execution lock - runs in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unlockReviewerExecution(UUID taskId, UUID executionId) {
        taskRepository.unlockReviewerExecution(taskId, executionId);
    }

    /** CAS lock for assignee (worker) execution - runs in its own transaction so it works from async threads. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryLockAssigneeExecution(UUID taskId, UUID executionId) {
        return taskRepository.tryLockAssigneeExecution(taskId, executionId) > 0;
    }

    /**
     * Promote a pending task to in_progress + set startedAt, write the audit event, and
     * publish a WS board update so the frontend card animates from pending → in_progress
     * at the exact moment the worker actually starts. Called from
     * {@link #executeAgentForTask} right after the assignee CAS lock is acquired - the
     * single moment where the status honestly transitions to "running".
     *
     * <p>Returns {@code true} if the CAS matched (task was pending and is now in_progress),
     * {@code false} if it was already in another state (reviewer-reject retrigger on an
     * already-in_progress task, race with a cancel, etc.). Runs in its own transaction
     * so it works from async ForkJoinPool threads.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean promoteToInProgressIfPending(UUID taskId, String tenantId) {
        int updated = taskRepository.promotePendingToInProgress(taskId, tenantId);
        if (updated == 0) return false;
        recordEvent(taskId, AgentTaskEventEntity.EVT_UPDATED, null, null,
                Map.of("status", AgentTaskEntity.STATUS_PENDING),
                Map.of("status", AgentTaskEntity.STATUS_IN_PROGRESS));
        if (taskBoardPublisher != null) {
            findTaskByIdScoped(taskId, tenantId)
                    .ifPresent(t -> taskBoardPublisher.publishTaskUpdated(tenantId, t));
        }
        return true;
    }

    /** Release the assignee execution lock - runs in its own transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void unlockAssigneeExecution(UUID taskId, UUID executionId) {
        taskRepository.unlockAssigneeExecution(taskId, executionId);
    }

    /**
     * Increment review attempt count and return the new value - runs in its own transaction.
     * Scoped to {tenant, reviewer}: a non-reviewer caller gets 0 so they cannot bump the
     * counter and prematurely drive the task to auto-fail. Also returns 0 if the task is
     * no longer in_review.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int incrementReviewAttemptCount(UUID taskId, String tenantId, UUID reviewerAgentId) {
        return incrementReviewAttemptCount(taskId, tenantId, reviewerAgentId, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int incrementReviewAttemptCount(UUID taskId, String tenantId, UUID reviewerAgentId,
                                           UUID reviewerExecutionId) {
        int updated = reviewerExecutionId != null
                ? taskRepository.incrementReviewAttemptCountForExecution(taskId, tenantId, reviewerAgentId,
                        reviewerExecutionId)
                : taskRepository.incrementReviewAttemptCount(taskId, tenantId, reviewerAgentId);
        if (updated == 0) {
            return 0; // task no longer in_review
        }
        return taskRepository.findById(taskId).map(AgentTaskEntity::getReviewAttemptCount).orElse(0);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Dispatcher that persists an audit event with two guarantees:
     * <ol>
     *   <li><b>Parent visibility.</b> When called inside an active transaction, the
     *       actual insert is deferred to {@code afterCommit} so the event row's
     *       FK target (the task row just created/updated by the parent tx) is
     *       guaranteed to be visible. A naked {@code REQUIRES_NEW} would run on
     *       a separate DB connection and could not see the uncommitted parent row,
     *       causing {@code agent_task_events_task_id_fkey} violations on CREATED.</li>
     *   <li><b>Failure isolation.</b> {@link #doRecordEvent} uses
     *       {@code REQUIRES_NEW}, so a failure on the event row (JSON serialization,
     *       constraint violation, …) cannot mark the outer task transaction as
     *       rollback-only. Audit trail is best-effort; the business transaction is not.</li>
     * </ol>
     * Must be called via {@code self.recordEvent(...)} (not {@code this.}) so the
     * Spring proxy on {@link #doRecordEvent} is reached when the afterCommit hook fires.
     */
    public void recordEvent(UUID taskId, String eventType, UUID agentId, String userId,
                             Map<String, Object> oldVal, Map<String, Object> newVal) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        self.doRecordEvent(taskId, eventType, agentId, userId, oldVal, newVal);
                    } catch (Exception e) {
                        logger.warn("Failed to record task event (afterCommit) task={} type={}: {}",
                                taskId, eventType, e.getMessage());
                    }
                }
            });
            return;
        }
        // No active tx (e.g. unit tests): run immediately. In production every
        // caller is @Transactional so this path is rarely taken.
        try {
            self.doRecordEvent(taskId, eventType, agentId, userId, oldVal, newVal);
        } catch (Exception e) {
            logger.warn("Failed to record task event task={} type={}: {}", taskId, eventType, e.getMessage());
        }
    }

    /**
     * Inner transactional worker - exposed as {@code public} only so Spring's
     * CGLIB proxy can apply the {@code REQUIRES_NEW} advice. Do not call
     * directly; go through {@link #recordEvent(UUID, String, UUID, String, Map, Map)}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void doRecordEvent(UUID taskId, String eventType, UUID agentId, String userId,
                               Map<String, Object> oldVal, Map<String, Object> newVal) {
        AgentTaskEventEntity evt = new AgentTaskEventEntity();
        evt.setTaskId(taskId);
        // V281 NOT NULL: stamp organization_id from the parent task. Fetch lightly via
        // taskRepository.findById; the outer try/catch in recordEvent swallows + WARN-logs any
        // throw here so a missing/deleted parent never breaks the calling state transition
        // (audit trail is best-effort per the contract above doRecordEvent).
        // Two-step distinguishes the two failure modes - a one-liner Optional.map collapses
        // them into the same WARN line and an oncall could chase a delete race when the real
        // cause is a null-org task.
        AgentTaskEntity parent = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "parent task missing for event taskId=" + taskId));
        String parentOrgId = parent.getOrganizationId();
        if (parentOrgId == null) {
            throw new IllegalStateException(
                    "parent task has null organization_id (post-V263 invariant violated) taskId=" + taskId);
        }
        evt.setOrganizationId(parentOrgId);
        evt.setEventType(eventType);
        if (agentId != null) {
            evt.setActorType(AgentTaskEventEntity.ACTOR_AGENT);
            evt.setActorId(agentId.toString());
        } else if (userId != null) {
            evt.setActorType(AgentTaskEventEntity.ACTOR_USER);
            evt.setActorId(userId);
        } else {
            evt.setActorType(AgentTaskEventEntity.ACTOR_SYSTEM);
        }
        evt.setOldValue(oldVal);
        evt.setNewValue(newVal);
        eventRepository.save(evt);
    }

    /**
     * Terminally fail a task after an infrastructure/execution error (worker crashed
     * before reaching task_complete/task_reject). Transitions in_progress → failed.
     * No reviewer trigger - this is not a worker decision to submit for review.
     * Runs in its own transaction (REQUIRES_NEW) so {@link #executeAgentForTask}, which
     * runs outside the {@code assignTask} transaction, can call it via {@code self.markExecutionFailed}.
     * See bug #6 in TASK_TEST_ERRORS.md.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExecutionFailed(UUID taskId, String tenantId, String reason) {
        int updated = taskRepository.markExecutionFailed(taskId, tenantId, reason);
        if (updated == 0) {
            return;
        }

        Map<String, Object> newValue = new HashMap<>();
        newValue.put("status", AgentTaskEntity.STATUS_FAILED);
        if (reason != null && !reason.isBlank()) {
            newValue.put("reason", reason);
        }
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_FAILED, null, null,
                Map.of("status", AgentTaskEntity.STATUS_IN_PROGRESS), newValue);

        findTaskByIdScoped(taskId, tenantId)
                .ifPresent(task -> publishAfterCommit(() ->
                        taskBoardPublisher.publishTaskUpdated(tenantId, task)));
    }

    /**
     * Auto-approve path - kept for callers that want the historical "give-up and
     * accept" behavior (no current service paths use it now that we default to
     * auto-fail; see {@link #autoFailAfterReviewerRejection}). Still reachable in
     * tests and retained so new callers can opt in explicitly. Runs in its own
     * transaction so audit event + board publish fire consistently with the manual
     * {@link #approveTask} and {@link #rejectReview} paths.
     *
     * <p>Returns the approved entity on success, or {@code null} if the CAS lost - caller
     * decides whether to treat that as silent no-op (async/sweeper) or a user-visible error
     * (synchronous reviewer flow).</p>
     *
     * <p>The audit event is written via {@code self.recordEvent} (best-effort, same tx
     * semantics as manual approve/reject - if the audit insert fails the approval still
     * stands). Board publish ties to this inner tx's commit because every caller invokes
     * this helper outside an outer @Transactional boundary.</p>
     *
     * <p>Must be invoked via {@code self.autoApproveAfterReviewerFailure(...)} so the Spring
     * proxy applies - called from async callbacks, a non-transactional sweeper method, and
     * a @Transactional reviewer-reject path (where REQUIRES_NEW keeps the transactions
     * independent).</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentTaskEntity autoApproveAfterReviewerFailure(UUID taskId, String tenantId,
                                                           UUID reviewerAgentId, String reason) {
        int approved = taskRepository.approveIfReviewer(taskId, tenantId, reviewerAgentId);
        if (approved == 0) {
            return null;
        }
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId).orElse(null);
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_APPROVED, reviewerAgentId, null,
                Map.of("status", "in_review"),
                Map.of("status", "completed", "reason", reason));
        if (task != null) {
            publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, task));
        }
        return task;
    }

    /**
     * Auto-fail path symmetric to {@link #autoApproveAfterReviewerFailure}. Used when the
     * reviewer reject-loop cap ({@code agent_tasks.max_review_attempts}, defaulting to
     * {@link #MAX_REVIEW_ATTEMPTS}) is reached. The task transitions {@code in_review → failed}
     * with {@code errorMessage = reason} and the audit trail records an
     * {@code AUTO_FAILED} event distinct from {@code REVIEW_REJECTED} so timeline
     * consumers can distinguish a terminal auto-fail from a non-terminal rejection.
     *
     * <p>Returns the failed entity on success, or {@code null} if the CAS lost.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentTaskEntity autoFailAfterReviewerRejection(UUID taskId, String tenantId,
                                                          UUID reviewerAgentId, String reason) {
        return autoFailAfterReviewerRejection(taskId, tenantId, reviewerAgentId, null, reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentTaskEntity autoFailAfterReviewerRejection(UUID taskId, String tenantId,
                                                          UUID reviewerAgentId, UUID reviewerExecutionId,
                                                          String reason) {
        int failed = reviewerExecutionId != null
                ? taskRepository.failReviewIfReviewerExecution(taskId, tenantId, reviewerAgentId,
                        reviewerExecutionId, reason)
                : taskRepository.failReviewIfReviewer(taskId, tenantId, reviewerAgentId, reason);
        if (failed == 0) {
            return null;
        }
        AgentTaskEntity task = findTaskByIdScoped(taskId, tenantId).orElse(null);
        self.recordEvent(taskId, AgentTaskEventEntity.EVT_AUTO_FAILED, reviewerAgentId, null,
                Map.of("status", "in_review"),
                Map.of("status", "failed", "reason", reason));
        if (task != null) {
            publishAfterCommit(() -> taskBoardPublisher.publishTaskUpdated(tenantId, task));
        }
        return task;
    }

    /**
     * Effective reject-loop cap for {@code task}. Uses the per-task override if set and
     * within {@code [1, MAX_REVIEW_ATTEMPTS_CEILING]}; otherwise {@link #MAX_REVIEW_ATTEMPTS}.
     */
    // Package-private for unit testing the clamping behavior directly.
    static int effectiveMaxReviewAttempts(AgentTaskEntity task) {
        Integer override = task == null ? null : task.getMaxReviewAttempts();
        if (override == null) return MAX_REVIEW_ATTEMPTS;
        int clamped = Math.max(1, Math.min(MAX_REVIEW_ATTEMPTS_CEILING, override));
        return clamped;
    }

    private static int utf8Bytes(String s) {
        return s == null ? 0 : s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String normalizePriority(String priority) {
        if (priority == null) return AgentTaskEntity.PRIORITY_NORMAL;
        String p = priority.trim().toLowerCase().replace(' ', '-');
        if (VALID_PRIORITIES.contains(p)) return p;
        String aliased = PRIORITY_ALIASES.get(p);
        if (aliased != null) return aliased;
        throw new IllegalArgumentException(
                "invalid priority: " + priority + " (must be one of " + VALID_PRIORITIES + ")");
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

}

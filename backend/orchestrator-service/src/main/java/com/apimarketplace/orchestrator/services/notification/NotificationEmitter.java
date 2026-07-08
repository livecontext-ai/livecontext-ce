package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.services.SignalResolvedEvent;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.events.SignalsCancelledEvent;
import com.apimarketplace.orchestrator.services.events.WorkflowApprovalPendingEvent;
import com.apimarketplace.orchestrator.services.events.WorkflowEpochFailedEvent;
import com.apimarketplace.orchestrator.services.events.WorkflowRunTerminatedEvent;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AFTER_COMMIT emitter for production-workflow user-facing notifications.
 * Subscribes to multiple application events and writes one row per logical
 * event into {@code orchestrator.notifications}, with a Redis WS hint to the
 * recipient. All listeners share the filter chain ({@link #isExcludedRun})
 * and the {@code INSERT … ON CONFLICT … RETURNING id} idempotency pattern.
 *
 * <ul>
 *   <li>{@link WorkflowRunTerminatedEvent} → {@code RUN_FAILED} (P0, terminal-run path)</li>
 *   <li>{@link WorkflowEpochFailedEvent}   → {@code RUN_FAILED} (P0, per-epoch path for reusable triggers)</li>
 *   <li>{@link WorkflowApprovalPendingEvent} → {@code APPROVAL_PENDING} (P2a, insert)</li>
 *   <li>{@link SignalResolvedEvent} (USER_APPROVAL only) → {@code APPROVAL_PENDING} (P2a, delete)</li>
 *   <li>{@link SignalsCancelledEvent} → {@code APPROVAL_PENDING} (P2a, bulk delete on cancel paths
 *       that bypass {@code SignalResolvedEvent})</li>
 * </ul>
 *
 * <p><b>Filter parity with V172 backfill</b> (V1 contract - keep aligned):
 * <ul>
 *   <li>{@code RunStatus.isFailure()} = {@code FAILED | CANCELLED | TIMEOUT}.
 *       {@code PARTIAL_SUCCESS} and {@code SKIPPED} are excluded by design -
 *       documented gap, will revisit when product asks.</li>
 *   <li>{@code workflow.pinned_version} non-null AND equal to
 *       {@code event.planVersion()} - production run only.</li>
 *   <li>{@code run.metadata} excludes runs with {@code __editorRun__=true},
 *       {@code __versionReplay__} key present (Integer or any value), and
 *       {@code __agentInitiated__=true} (filter retained even though no
 *       producer exists today - guards future regression). Exception: an
 *       {@code __editorRun__} run that IS the workflow's current production
 *       run ({@code workflow.production_run_id}) is NOT excluded - a pin
 *       promotes the editor run without stripping the flag, and the promoted
 *       run's fires are production fires.</li>
 *   <li>{@code run.run_id_public} not starting with {@code "showcase_"}.</li>
 * </ul>
 *
 * <p><b>Idempotency</b>: write uses {@code INSERT ... ON CONFLICT
 * (tenant_id, category, source_id) DO NOTHING RETURNING id}. The Redis push
 * fires ONLY when the RETURNING clause yielded a row, so multi-replica races
 * cannot double-publish.
 *
 * <p><b>Transactional context</b>: {@code @TransactionalEventListener
 * (AFTER_COMMIT)} runs after the originating transaction commits, with no
 * active transaction. Native DML through {@code EntityManager} requires one,
 * hence the explicit {@code @Transactional(REQUIRES_NEW)}.
 *
 * <p><b>Failure isolation</b>: any DB or Redis exception is logged + counted
 * but never propagated - the run-completion write has already committed and
 * the polling fallback (60s) recovers any missed push.
 */
@Component
public class NotificationEmitter {

    private static final Logger logger = LoggerFactory.getLogger(NotificationEmitter.class);

    private static final String CATEGORY_RUN_FAILED = "RUN_FAILED";
    private static final String CATEGORY_APPROVAL_PENDING = "APPROVAL_PENDING";
    private static final String SEVERITY_ERROR = "error";
    private static final String SEVERITY_INFO = "info";
    /** Single source of truth shared with {@link SubjectNameResolver#WORKFLOW}. */
    private static final String SUBJECT_TYPE_WORKFLOW = SubjectNameResolver.WORKFLOW;
    private static final String SHOWCASE_RUN_PREFIX = "showcase_";

    private static final String META_EDITOR_RUN = "__editorRun__";
    private static final String META_VERSION_REPLAY = "__versionReplay__";
    private static final String META_AGENT_INITIATED = "__agentInitiated__";

    /**
     * Wire format for {@code payload.endedAt}. Pinned to fixed millisecond
     * precision so the live-emitted shape matches the V172 backfill
     * ({@code to_char(... 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')}); without this,
     * {@code DateTimeFormatter.ISO_INSTANT} would emit variable precision
     * (no fraction for whole-second instants) and equality / dedup tooling
     * downstream would see two shapes for the same logical column.
     */
    private static final DateTimeFormatter ENDED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneOffset.UTC);

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowRedisPublisher redisPublisher;
    private final MeterRegistry meterRegistry;

    @PersistenceContext
    private EntityManager entityManager;

    public NotificationEmitter(WorkflowRepository workflowRepository,
                                WorkflowRunRepository workflowRunRepository,
                                WorkflowRedisPublisher redisPublisher,
                                MeterRegistry meterRegistry) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.redisPublisher = redisPublisher;
        this.meterRegistry = meterRegistry;
    }

    // @Order(0): must observe workflow.production_run_id BEFORE
    // RunTerminationListener (default = lowest precedence) rearms the pin on
    // this same event. Rearm re-points production_run_id away from the
    // just-failed run, and the promoted-editor-run override in isExcludedRun
    // compares against that column - emitting after the rearm would re-silence
    // the exact RUN_FAILED notification the override unlocks.
    @org.springframework.core.annotation.Order(0)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRunTerminated(WorkflowRunTerminatedEvent event) {
        try {
            handle(event);
        } catch (DataAccessException | PersistenceException ex) {
            // DataAccessException covers Redis (RedisConnectionFailureException),
            // JDBC (PSQLException -> DataAccessResourceFailureException), and
            // translated Hibernate session errors. PersistenceException covers
            // JPA. Anything else (e.g. IllegalStateException, NPE) propagates
            // so genuine programmer errors aren't masked.
            meterRegistry.counter("notification.emitter.errors",
                    "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[notification-emitter] swallowed for run {}: {}",
                    event.runId(), ex.getMessage());
        }
    }

    /**
     * P0 (notification V2): per-epoch failure listener for reusable-trigger
     * workflows (schedule / webhook / chat / form). The terminal
     * {@link WorkflowRunTerminatedEvent} above never fires for these runs
     * because they cycle WAITING_TRIGGER ↔ RUNNING. This listener writes one
     * notification per failed epoch with idempotency key
     * {@code runId + ":" + epoch}, so multi-fire failures are deduped per
     * epoch but distinct epochs produce distinct rows.
     *
     * <p>Same filter chain as the run-terminal path: pinned-version match +
     * editor / replay / agent-initiated metadata exclusions + showcase prefix
     * exclusion. Only the {@code source_id} shape and the absence of a
     * status-isFailure check (the publisher already gates on
     * {@code hasFailures=true}) differ.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onEpochFailed(WorkflowEpochFailedEvent event) {
        try {
            handleEpochFailed(event);
        } catch (DataAccessException | PersistenceException ex) {
            // DataAccessException covers Redis (RedisConnectionFailureException),
            // JDBC (PSQLException -> DataAccessResourceFailureException), and
            // translated Hibernate session errors. PersistenceException covers
            // JPA. Anything else (e.g. IllegalStateException, NPE) propagates
            // so genuine programmer errors aren't masked.
            // Expected failure modes: DB unreachable, Redis down, Hibernate
            // session lifecycle issues. Log, count, swallow - the originating
            // tx has committed; bell-poll fallback will heal stale state.
            meterRegistry.counter("notification.emitter.errors",
                    "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[notification-emitter] swallowed epoch-failed for run {} epoch {}: {}",
                    event.runId(), event.epoch(), ex.getMessage());
        }
    }

    private void handle(WorkflowRunTerminatedEvent event) {
        RunStatus status = event.status();
        if (status == null || !status.isFailure()) {
            return;
        }

        UUID workflowId = event.workflowId();
        UUID runId = event.runId();
        Integer planVersion = event.planVersion();
        if (workflowId == null || runId == null) {
            return;
        }

        Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(workflowId);
        if (workflowOpt.isEmpty()) {
            return;
        }
        WorkflowEntity workflow = workflowOpt.get();

        Integer pinned = workflow.getPinnedVersion();
        if (pinned == null || planVersion == null || !pinned.equals(planVersion)) {
            return;
        }

        Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findById(runId);
        if (runOpt.isEmpty()) {
            // Race: run deleted between commit and listener fire - skip
            // emission rather than fabricate a notification with missing
            // metadata exclusion checks.
            return;
        }
        WorkflowRunEntity run = runOpt.get();

        if (isExcludedRun(run, workflow)) return;

        String tenantId = run.getTenantId();
        if (tenantId == null) {
            return;
        }

        Instant occurredAt = run.getEndedAt() != null ? run.getEndedAt() : Instant.now();
        String sourceId = runId.toString();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status.toWireValue());
        payload.put("endedAt", ENDED_AT_FORMATTER.format(occurredAt));
        // Always write the key - even if null - to match V172 backfill shape
        // (jsonb_build_object stores 'runIdPublic' unconditionally). Without
        // this, backfilled rows have the key (possibly JSON null) and live
        // rows omit it; downstream consumers see two payload shapes.
        payload.put("runIdPublic", run.getRunIdPublic());

        String payloadJson;
        try {
            payloadJson = toJson(payload);
        } catch (RuntimeException ex) {
            meterRegistry.counter("notification.emitter.errors",
                    "type", "JsonSerialization").increment();
            return;
        }

        // Native INSERT … ON CONFLICT … RETURNING id. getResultList() is empty
        // when ON CONFLICT DO NOTHING fires (no row inserted), non-empty
        // otherwise. We publish ONLY when truly inserted to avoid duplicate
        // WS pushes across replicas under contention.
        @SuppressWarnings("unchecked")
        List<Object> inserted = entityManager.createNativeQuery(
                "INSERT INTO orchestrator.notifications " +
                        "(tenant_id, organization_id, category, severity, subject_type, subject_id, " +
                        " source_id, run_id, run_id_public, plan_version, payload, occurred_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?) " +
                        "ON CONFLICT (tenant_id, category, source_id) DO NOTHING RETURNING id"
        )
                .setParameter(1, tenantId)
                // V220 - workspace tag for org-mode fan-out. Null in personal
                // scope; the bell's personal-scope filter requires NULL here.
                .setParameter(2, run.getOrgId())
                .setParameter(3, CATEGORY_RUN_FAILED)
                .setParameter(4, SEVERITY_ERROR)
                .setParameter(5, SUBJECT_TYPE_WORKFLOW)
                .setParameter(6, workflowId)
                .setParameter(7, sourceId)
                .setParameter(8, runId)
                .setParameter(9, run.getRunIdPublic())
                .setParameter(10, planVersion)
                .setParameter(11, payloadJson)
                .setParameter(12, occurredAt)
                .getResultList();

        if (inserted.isEmpty()) {
            // Conflict: another emit attempt already wrote this row (or
            // recovery replayed). Idempotent no-op.
            return;
        }

        try {
            Map<String, Object> wsPayload = Map.of("category", CATEGORY_RUN_FAILED, "severity", SEVERITY_ERROR);
            redisPublisher.publishNotification(tenantId, "notification.created", wsPayload);
            // PR25 - fan out to org channel so teammates see the notification too.
            redisPublisher.publishOrgNotification(run.getOrgId(), "notification.created", wsPayload);
        } catch (RedisConnectionFailureException ex) {
            // Row already committed to DB; polling will surface it. Count and
            // continue.
            meterRegistry.counter("notification.emitter.errors",
                    "type", "RedisPublish").increment();
        }
    }

    private void handleEpochFailed(WorkflowEpochFailedEvent event) {
        UUID workflowId = event.workflowId();
        UUID runId = event.runId();
        Integer planVersion = event.planVersion();
        if (workflowId == null || runId == null) {
            return;
        }

        Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(workflowId);
        if (workflowOpt.isEmpty()) {
            return;
        }
        WorkflowEntity workflow = workflowOpt.get();

        Integer pinned = workflow.getPinnedVersion();
        if (pinned == null || planVersion == null || !pinned.equals(planVersion)) {
            return;
        }

        // Metadata exclusions: re-fetch the run because the publisher
        // computes them from a locked snapshot inside resetForNextCycle's
        // transaction; reading here AFTER_COMMIT picks up any flag the
        // commit just persisted (e.g. EditorRunResolver writes
        // __versionReplay__ post-recordWorkflowStart).
        Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findById(runId);
        if (runOpt.isEmpty()) {
            return;
        }
        WorkflowRunEntity run = runOpt.get();

        if (isExcludedRun(run, workflow)) return;

        // tenantId: event may carry an explicit override (rare - used by
        // legacy republish paths that synthesize an event without a live run
        // context). Fallback to run.getTenantId() is the common path.
        // organization_id: NO event-level override exists - run.getOrgId() is
        // the canonical workspace tag (V210 column, written at run creation)
        // and is always authoritative. Mixing in an event-supplied orgId
        // would re-introduce the drift class V220 closes.
        String tenantId = event.tenantId() != null ? event.tenantId() : run.getTenantId();
        if (tenantId == null) {
            return;
        }

        Instant occurredAt = event.endedAt() != null ? event.endedAt() : Instant.now();
        // Per-epoch idempotency key - V1 (run-terminal) used bare runId;
        // here we suffix the epoch so multiple cycles of the same reusable
        // run produce distinct rows (they are logically distinct events).
        String sourceId = runId + ":" + event.epoch();

        Map<String, Object> payload = new LinkedHashMap<>();
        // No RunStatus on a per-epoch event - the publisher already gated on
        // hasFailures=true. Wire shape stays compatible with V1: status is
        // always present, a synthetic "failed" matches the lowercase wire
        // convention from RunStatus.toWireValue().
        payload.put("status", "failed");
        payload.put("endedAt", ENDED_AT_FORMATTER.format(occurredAt));
        payload.put("runIdPublic", event.runIdPublic() != null ? event.runIdPublic() : run.getRunIdPublic());
        payload.put("epoch", event.epoch());

        String payloadJson;
        try {
            payloadJson = toJson(payload);
        } catch (RuntimeException ex) {
            meterRegistry.counter("notification.emitter.errors",
                    "type", "JsonSerialization").increment();
            return;
        }

        @SuppressWarnings("unchecked")
        List<Object> inserted = entityManager.createNativeQuery(
                "INSERT INTO orchestrator.notifications " +
                        "(tenant_id, organization_id, category, severity, subject_type, subject_id, " +
                        " source_id, run_id, run_id_public, plan_version, payload, occurred_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?) " +
                        "ON CONFLICT (tenant_id, category, source_id) DO NOTHING RETURNING id"
        )
                .setParameter(1, tenantId)
                // V220 - same workspace-tag contract as the run-terminal path.
                .setParameter(2, run.getOrgId())
                .setParameter(3, CATEGORY_RUN_FAILED)
                .setParameter(4, SEVERITY_ERROR)
                .setParameter(5, SUBJECT_TYPE_WORKFLOW)
                .setParameter(6, workflowId)
                .setParameter(7, sourceId)
                .setParameter(8, runId)
                .setParameter(9, run.getRunIdPublic())
                .setParameter(10, planVersion)
                .setParameter(11, payloadJson)
                .setParameter(12, occurredAt)
                .getResultList();

        if (inserted.isEmpty()) {
            return;
        }

        try {
            Map<String, Object> wsPayload = Map.of("category", CATEGORY_RUN_FAILED, "severity", SEVERITY_ERROR);
            redisPublisher.publishNotification(tenantId, "notification.created", wsPayload);
            // PR25 - fan out to org channel so teammates see the notification too.
            // Personal-scope runs (run.getOrgId() == null) are a no-op in the publisher.
            redisPublisher.publishOrgNotification(run.getOrgId(), "notification.created", wsPayload);
        } catch (RedisConnectionFailureException ex) {
            meterRegistry.counter("notification.emitter.errors",
                    "type", "RedisPublish").increment();
        }
    }

    // ========================================================================
    // P2a: APPROVAL_PENDING listeners (insert + delete)
    // ========================================================================

    /**
     * Insert one APPROVAL_PENDING row when a USER_APPROVAL signal_wait is
     * registered. AFTER_COMMIT on the publisher's outer
     * {@code registerSignal} tx; tx-rollback orphan window is pre-existing
     * (REQUIRES_NEW signal_wait commits independently - see memory
     * {@code signal_recovery_idempotency_bug}).
     *
     * <p>Filter parity with P0 ({@link #handle}): pinned-version match,
     * showcase / metadata exclusions, non-null tenant. {@code occurred_at}
     * carries {@code signal_wait.created_at} from the event so bell ordering
     * reflects registration time, not listener-fire time.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onApprovalPending(WorkflowApprovalPendingEvent event) {
        try {
            handleApprovalPending(event);
        } catch (DataAccessException | PersistenceException ex) {
            meterRegistry.counter("notification.emitter.errors",
                    "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[notification-emitter] swallowed approval-pending for signal {}: {}",
                    event.signalWaitId(), ex.getMessage());
        }
    }

    /**
     * Delete the matching APPROVAL_PENDING row when a USER_APPROVAL signal is
     * resolved (approve / reject / timeout-via-onTimerExpired). Non-USER_APPROVAL
     * resolutions are no-ops. Cancellation paths
     * ({@code cancelByRun}/{@code cancelByDagAndEpoch}/{@code cancelBlockingByDagAndEpoch}
     * /zombie guard) bypass {@code SignalResolvedEvent} and are handled by
     * {@link #onSignalsCancelled} via {@link SignalsCancelledEvent}.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSignalResolved(SignalResolvedEvent event) {
        try {
            SignalWaitEntity signal = event.getResolvedSignal();
            if (signal == null || signal.getSignalType() != SignalType.USER_APPROVAL) return;

            Optional<WorkflowRunEntity> runOpt =
                    workflowRunRepository.findByRunIdPublic(signal.getRunId());
            if (runOpt.isEmpty()) return;
            WorkflowRunEntity run = runOpt.get();
            String tenantId = run.getTenantId();
            if (tenantId == null) return;

            deleteApprovalPendingByIds(tenantId, run.getOrgId(), List.of(signal.getId()));
        } catch (DataAccessException | PersistenceException ex) {
            meterRegistry.counter("notification.emitter.errors",
                    "type", ex.getClass().getSimpleName()).increment();
        }
    }

    /**
     * Bulk-delete APPROVAL_PENDING rows for a {@link SignalsCancelledEvent}.
     * Published from cancel paths that bulk-UPDATE signal_waits and bypass
     * {@code SignalResolvedEvent}.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSignalsCancelled(SignalsCancelledEvent event) {
        try {
            if (event.userApprovalSignalIds().isEmpty()) return;
            Optional<WorkflowRunEntity> runOpt =
                    workflowRunRepository.findByRunIdPublic(event.runIdPublic());
            if (runOpt.isEmpty()) return;
            WorkflowRunEntity run = runOpt.get();
            String tenantId = run.getTenantId();
            if (tenantId == null) return;

            deleteApprovalPendingByIds(tenantId, run.getOrgId(), event.userApprovalSignalIds());
        } catch (DataAccessException | PersistenceException ex) {
            meterRegistry.counter("notification.emitter.errors",
                    "type", ex.getClass().getSimpleName()).increment();
        }
    }

    private void handleApprovalPending(WorkflowApprovalPendingEvent event) {
        Optional<WorkflowRunEntity> runOpt =
                workflowRunRepository.findByRunIdPublic(event.runIdPublic());
        if (runOpt.isEmpty()) return;
        WorkflowRunEntity run = runOpt.get();

        String tenantId = run.getTenantId();
        if (tenantId == null) return;

        UUID workflowId = run.getWorkflow() != null ? run.getWorkflow().getId() : null;
        if (workflowId == null) return;

        // Workflow loaded BEFORE the exclusion check: the editor-run override
        // needs workflow.production_run_id to recognize a promoted run.
        Optional<WorkflowEntity> wfOpt = workflowRepository.findById(workflowId);
        if (wfOpt.isEmpty()) return;
        WorkflowEntity workflow = wfOpt.get();

        if (isExcludedRun(run, workflow)) return;

        Integer pinned = workflow.getPinnedVersion();
        if (pinned == null || run.getPlanVersion() == null
                || !pinned.equals(run.getPlanVersion())) return;

        // occurred_at = signal_wait.created_at (precise event time)
        Instant occurredAt = event.createdAt() != null ? event.createdAt() : Instant.now();
        String sourceId = String.valueOf(event.signalWaitId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "pending");
        payload.put("expiresAt",
                event.expiresAt() != null ? ENDED_AT_FORMATTER.format(event.expiresAt()) : null);
        payload.put("runIdPublic", run.getRunIdPublic());

        String payloadJson;
        try {
            payloadJson = toJson(payload);
        } catch (RuntimeException ex) {
            meterRegistry.counter("notification.emitter.errors",
                    "type", "JsonSerialization").increment();
            return;
        }

        @SuppressWarnings("unchecked")
        List<Object> inserted = entityManager.createNativeQuery(
                "INSERT INTO orchestrator.notifications " +
                        "(tenant_id, organization_id, category, severity, subject_type, subject_id, " +
                        " source_id, run_id, run_id_public, plan_version, payload, occurred_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?) " +
                        "ON CONFLICT (tenant_id, category, source_id) DO NOTHING RETURNING id"
        )
                .setParameter(1, tenantId)
                // V220 - workspace tag from the run for bell org-mode fan-out.
                .setParameter(2, run.getOrgId())
                .setParameter(3, CATEGORY_APPROVAL_PENDING)
                .setParameter(4, SEVERITY_INFO)
                .setParameter(5, SUBJECT_TYPE_WORKFLOW)
                .setParameter(6, workflowId)
                .setParameter(7, sourceId)
                .setParameter(8, run.getId())
                .setParameter(9, run.getRunIdPublic())
                .setParameter(10, run.getPlanVersion())
                .setParameter(11, payloadJson)
                .setParameter(12, occurredAt)
                .getResultList();

        if (inserted.isEmpty()) return;

        try {
            Map<String, Object> wsPayload = Map.of("category", CATEGORY_APPROVAL_PENDING, "severity", SEVERITY_INFO);
            redisPublisher.publishNotification(tenantId, "notification.created", wsPayload);
            // PR25 - fan out to org channel.
            redisPublisher.publishOrgNotification(run.getOrgId(), "notification.created", wsPayload);
        } catch (RedisConnectionFailureException ex) {
            meterRegistry.counter("notification.emitter.errors",
                    "type", "RedisPublish").increment();
        }
    }

    /**
     * Bulk-delete APPROVAL_PENDING rows by signal-wait id (which is the
     * {@code source_id} for that category). JPQL collection binding via
     * {@code IN} is more reliable across Hibernate versions than native
     * {@code IN (?)} positional binding.
     *
     * <p>Scope predicate: the delete narrows to the run's
     * {@code organization_id}. Post-V261 (2026-05-19) every notification row
     * carries a non-null {@code organization_id} (personal workspaces use the
     * user's default personal org); the legacy IS NULL personal-scope branch
     * was removed. A {@code null orgId} parameter is rejected here so a
     * mis-wired caller is surfaced immediately instead of silently wiping no
     * rows (and bypassing the bell-clear). Mirrors
     * {@link NotificationService#deleteBuckets} scope semantics.
     *
     * <p>Publishes {@code notification.removed} only when at least one row
     * was actually deleted - under multi-replica races, only one replica's
     * DELETE returns rows; losers see a 0-count and skip the WS push.
     */
    private void deleteApprovalPendingByIds(String tenantId, String orgId, List<Long> ids) {
        if (ids.isEmpty()) return;
        // Post-V261: orgId must be present. Personal workspaces resolve to a
        // real organization_id at the gateway; the legacy null-orgId branch
        // (IS NULL + tenant_id) is dead. Silently no-op when null to keep
        // signal-resolution paths from crashing during the migration window;
        // log + count so any stale caller is observable.
        if (orgId == null) {
            meterRegistry.counter("notification.emitter.errors",
                    "type", "DeleteApprovalNullOrg").increment();
            return;
        }
        List<String> sourceIds = ids.stream().map(String::valueOf).toList();
        int rows = entityManager.createQuery(
                "DELETE FROM NotificationEntity n " +
                        " WHERE n.organizationId = ?1 AND n.category = ?2 AND n.sourceId IN ?3")
                .setParameter(1, orgId)
                .setParameter(2, CATEGORY_APPROVAL_PENDING)
                .setParameter(3, sourceIds)
                .executeUpdate();
        if (rows > 0) {
            try {
                Map<String, Object> wsPayload = Map.of("category", CATEGORY_APPROVAL_PENDING);
                redisPublisher.publishNotification(tenantId, "notification.removed", wsPayload);
                // PR25 - fan out delete to org channel so teammates' UIs also clear
                // the pending state. Personal-scope (orgId=null) is a no-op.
                redisPublisher.publishOrgNotification(orgId, "notification.removed", wsPayload);
            } catch (RedisConnectionFailureException ex) {
                meterRegistry.counter("notification.emitter.errors",
                        "type", "RedisPublish").increment();
            }
        }
    }

    // ========================================================================
    // Filter helpers (extracted from inline P0 filter chain - no behavior change)
    // ========================================================================

    private static boolean isShowcaseRun(WorkflowRunEntity run) {
        return run.getRunIdPublic() != null
                && run.getRunIdPublic().startsWith(SHOWCASE_RUN_PREFIX);
    }

    /**
     * A pin PROMOTES an existing run (almost always the editor run the user
     * tested with) to production and never strips its {@code __editorRun__}
     * metadata, so the flag alone cannot mean "editor iteration": when the
     * run IS the workflow's current production run
     * ({@code workflow.production_run_id}), its fires are production fires
     * and must notify like any other. Every other exclusion (showcase,
     * version replay, agent-initiated, unpromoted editor run) stays silent.
     */
    private static boolean isExcludedRun(WorkflowRunEntity run, WorkflowEntity workflow) {
        if (isShowcaseRun(run)) return true;
        Map<String, Object> meta = run.getMetadata();
        if (meta == null) return false;
        if (meta.containsKey(META_VERSION_REPLAY)) return true;
        if (Boolean.TRUE.equals(meta.get(META_AGENT_INITIATED))) return true;
        if (Boolean.TRUE.equals(meta.get(META_EDITOR_RUN))) {
            return workflow == null
                    || workflow.getProductionRunId() == null
                    || !workflow.getProductionRunId().equals(run.getId());
        }
        return false;
    }

    /**
     * Minimal Jackson-free JSON for the small fixed-shape payload. Avoids
     * pulling ObjectMapper into the hot path; values are
     * limited to String/primitives so escaping is straightforward.
     */
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append('"').append(escape(v.toString())).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }
}

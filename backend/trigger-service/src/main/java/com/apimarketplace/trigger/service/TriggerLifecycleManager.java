package com.apimarketplace.trigger.service;

import com.apimarketplace.notification.client.NotificationClient;
import com.apimarketplace.notification.client.dto.NotificationEmitRequest;
import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.domain.StandaloneChatEndpointEntity;
import com.apimarketplace.trigger.domain.StandaloneFormEndpointEntity;
import com.apimarketplace.trigger.domain.StandaloneWebhookEntity;
import com.apimarketplace.trigger.domain.TriggerState;
import com.apimarketplace.trigger.domain.TriggerStateAuditLogEntity;
import com.apimarketplace.trigger.domain.WebhookTokenEntity;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.repository.StandaloneChatEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import com.apimarketplace.trigger.repository.TriggerStateAuditLogRepository;
import com.apimarketplace.trigger.repository.WebhookTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sole authority for trigger config-row state transitions across the 5 trigger types
 * (schedule, webhook, chat endpoint, form endpoint, webhook token).
 *
 * <p>Round-7 contract (see the project docs): the dispatch layer
 * NEVER mutates state. Only this service, the admin API, the reaper, the pin
 * transaction, TTL/max_executions checks, and workflow deletion may transition state.
 *
 * <p>State transitions emitted:
 * <pre>
 *   arm(id, source)              → ACTIVE
 *   suspend(id, reason)          → SUSPENDED_NO_RUN | SUSPENDED_UNPINNED (per reason)
 *   archive(id, reason)          → ARCHIVED
 * </pre>
 *
 * <p>For PR2: keep the legacy {@code enabled}/{@code is_active} boolean columns in sync
 * with {@link TriggerState} so any code path that still reads the legacy flag observes
 * the right value. PR5 will drop the legacy columns once dispatch and admin paths read
 * {@code state} exclusively.
 */
@Service
public class TriggerLifecycleManager {

    private static final Logger logger = LoggerFactory.getLogger(TriggerLifecycleManager.class);

    /**
     * Origin of a state transition - used for observability + the {@code arm} flow's
     * "who armed me" audit trail.
     */
    public enum Source {
        /** {@code PinTransaction} (pin/unpin/rearm flow). */
        PIN,
        /** Admin REST endpoint or admin API. */
        ADMIN,
        /** Reaper reconciliation pass. */
        REAPER,
        /** Workflow plan sync after a save. */
        SYNC,
        /** TTL or max_executions check on the dispatch path. */
        QUOTA,
        /**
         * Workflow deletion cascade (workflow row + plan being removed).
         *
         * <p><b>Forensic-query caveat (legacy data, before 2026-05-13):</b>
         * APPLICATION-typed run cancellations also stamped {@code source='DELETION'}
         * via {@code WorkflowResumeService.applyApplicationSingleRunPolicyOnTermination}
         * (the pre-fix single-run policy reused this value). Historical audit-log
         * rows written before 2026-05-13 may therefore mix actual workflow
         * deletions with APPLICATION cancel cascades under {@code DELETION}.
         * From 2026-05-13 onward, cancel/pause cascades are stamped
         * {@link #RUN_TERMINATION} instead, leaving {@code DELETION} narrow to
         * the workflow-deletion path. Forensic queries spanning the cutoff date
         * should account for this tail.
         */
        DELETION,
        /**
         * Run termination cascade - the workflow itself stays, but one of its runs
         * was cancelled/paused (user dragged a card to the "paused" Kanban column
         * or hit the run-cancel REST). Distinct from {@link #DELETION}: the
         * workflow row is preserved; only the run's triggers are suspended so the
         * dispatcher stops picking them. Used by both
         * {@code WorkflowResumeService.cancelWorkflow} and {@code pauseWorkflow}
         * cascades to keep forensic queries on {@code source='DELETION'} narrow
         * to actual deletions. Introduced 2026-05-13.
         */
        RUN_TERMINATION
    }

    /**
     * Reason text persisted on the {@code last_disabled_reason} column. The set is
     * deliberately small + bounded (see VARCHAR(40) on the column) so dashboards and
     * alerts can group on it.
     */
    public static final class Reason {
        public static final String LEGACY_DISABLED      = "LEGACY_DISABLED";
        public static final String USER_DISABLED        = "USER_DISABLED";
        public static final String NO_PRODUCTION_RUN    = "NO_PRODUCTION_RUN";
        public static final String WORKFLOW_UNPINNED    = "WORKFLOW_UNPINNED";
        public static final String WORKFLOW_DELETED     = "WORKFLOW_DELETED";
        /**
         * User explicitly deleted a single trigger row (schedule, webhook, …)
         * via the admin UI. Distinct from {@link #WORKFLOW_DELETED} (the
         * entire workflow was deleted, cascading to all its triggers) and
         * from {@link #USER_DISABLED} (paused, recoverable). USER_DELETED
         * means the user wanted this specific row gone - archive (not
         * hard-delete) so audit + execution_count survive.
         */
        public static final String USER_DELETED         = "USER_DELETED";
        public static final String TTL_EXPIRED          = "TTL_EXPIRED";
        public static final String MAX_EXEC_REACHED     = "MAX_EXEC_REACHED";
        /**
         * Emitted by the dispatch path when a persisted cron expression no longer
         * passes the strict {@link com.apimarketplace.trigger.service.ScheduleCronParser#isValid}
         * gate (typically rows written before the silent-collapse step validation
         * landed, e.g. {@code "*&#47;120 * * * *"}). The row is archived rather than
         * left to hot-loop via the {@code +60s} fallback.
         */
        public static final String INVALID_CRON_LEGACY  = "INVALID_CRON_LEGACY";
        public static final String PLAN_TRIGGER_REMOVED = "PLAN_TRIGGER_REMOVED";
        /** Reserved - no current emitter. Planned for tenant-level suspension (billing/abuse). */
        public static final String TENANT_SUSPENDED     = "TENANT_SUSPENDED";

        /**
         * Precedence ordering for reasons - higher number = stronger reason.
         *
         * <p>Bands (gaps of 10 for future insertions):
         * <pre>
         *   100        : terminal/destructive (WORKFLOW_DELETED)
         *   80         : security/admin (TENANT_SUSPENDED - reserved)
         *   75         : user-initiated single-row delete (USER_DELETED)
         *   60-70      : user-initiated + quota (USER_DISABLED, TTL_EXPIRED, MAX_EXEC_REACHED)
         *   30-50      : structural (PLAN_TRIGGER_REMOVED, WORKFLOW_UNPINNED)
         *   10-20      : transient (NO_PRODUCTION_RUN)
         *   0          : legacy (LEGACY_DISABLED, unknown)
         * </pre>
         *
         * <p>Replacement rule: a new reason may overwrite an existing one only when
         * its precedence is &ge; the current reason's precedence. Prevents weak
         * reasons (e.g. {@code NO_PRODUCTION_RUN}) from clobbering strong ones
         * (e.g. {@code USER_DISABLED}).
         *
         * @return precedence value; {@code 0} for {@code null} or unrecognised
         *         reasons (treated as legacy).
         */
        public static int precedence(String reason) {
            if (reason == null) return 0;
            return switch (reason) {
                case WORKFLOW_DELETED      -> 100;
                case TENANT_SUSPENDED      -> 80;
                case USER_DELETED          -> 75;
                case USER_DISABLED         -> 70;
                case TTL_EXPIRED           -> 65;
                case MAX_EXEC_REACHED      -> 60;
                case INVALID_CRON_LEGACY   -> 55;
                case PLAN_TRIGGER_REMOVED  -> 50;
                case WORKFLOW_UNPINNED     -> 30;
                case NO_PRODUCTION_RUN     -> 20;
                case LEGACY_DISABLED       -> 0;
                default                    -> 0;
            };
        }

        /**
         * @return {@code true} when {@code newReason} may replace
         *         {@code currentReason} per the precedence rule
         *         ({@link #precedence(String)}).
         */
        public static boolean canReplace(String currentReason, String newReason) {
            return precedence(newReason) >= precedence(currentReason);
        }

        private Reason() {}
    }

    private final ScheduledExecutionRepository scheduleRepository;
    private final StandaloneWebhookRepository webhookRepository;
    private final StandaloneChatEndpointRepository chatRepository;
    private final StandaloneFormEndpointRepository formRepository;
    private final WebhookTokenRepository tokenRepository;

    /**
     * V169 introduced {@code trigger.trigger_state_audit_log} for forensic
     * "why did my trigger stop?" queries. Every transition writes one row
     * via {@link #log}; failure is non-fatal (logged at WARN, continues).
     * Optional dependency: existing unit tests instantiate this service with
     * mock repositories and don't wire the audit log - keeping it
     * {@code @Autowired(required = false)} preserves backwards compatibility
     * for those tests without reflection setup.
     */
    @Autowired(required = false)
    private TriggerStateAuditLogRepository auditLogRepository;

    /**
     * P4: cross-service notification client. Optional ({@code required=false})
     * so existing unit tests with mock repositories don't need to wire it.
     * When unwired, {@link #emitTriggerDisabledAfterCommit} is a no-op.
     */
    @Autowired(required = false)
    private NotificationClient notificationClient;

    /**
     * Reasons that surface as {@code WEBHOOK_TRIGGER_DISABLED} in the bell.
     * Skip set rationale:
     * <ul>
     *   <li>{@code WORKFLOW_DELETED} - workflow gone, click-through useless</li>
     *   <li>{@code LEGACY_DISABLED} - pre-v3.5 rows, not user-actionable</li>
     *   <li>{@code TENANT_SUSPENDED} - admin-driven, separate notification path</li>
     * </ul>
     */
    private static final Set<String> NOTIFIABLE_REASONS = Set.of(
            Reason.USER_DISABLED,
            Reason.NO_PRODUCTION_RUN,
            Reason.WORKFLOW_UNPINNED,
            Reason.TTL_EXPIRED,
            Reason.MAX_EXEC_REACHED,
            Reason.PLAN_TRIGGER_REMOVED);

    public TriggerLifecycleManager(ScheduledExecutionRepository scheduleRepository,
                                   StandaloneWebhookRepository webhookRepository,
                                   StandaloneChatEndpointRepository chatRepository,
                                   StandaloneFormEndpointRepository formRepository,
                                   WebhookTokenRepository tokenRepository) {
        this.scheduleRepository = scheduleRepository;
        this.webhookRepository = webhookRepository;
        this.chatRepository = chatRepository;
        this.formRepository = formRepository;
        this.tokenRepository = tokenRepository;
    }

    // ============================================================================
    // P4 - WEBHOOK_TRIGGER_DISABLED notification helper
    // ============================================================================

    /**
     * Schedule a {@code WEBHOOK_TRIGGER_DISABLED} bell emission for AFTER the
     * current suspend transaction commits. HTTP-in-tx is forbidden - see
     * {@code project_trigger_lifecycle_v3_5_design} memory P1 follow-up. Skip
     * when:
     * <ul>
     *   <li>{@link NotificationClient} not wired (test contexts)</li>
     *   <li>{@code reason} is not in {@link #NOTIFIABLE_REASONS}</li>
     *   <li>No active synchronization (caller is not @Transactional -
     *       fire immediately as a fallback so admin paths still work)</li>
     * </ul>
     *
     * <p>{@code source_id = triggerId + ":" + suspendedAt_epoch_day} per master
     * brief - collapses repeated suspends within the same day to one bell row,
     * but a re-suspend tomorrow surfaces as a new event.
     */
    private void emitTriggerDisabledAfterCommit(UUID triggerId, String triggerName,
                                                String tenantId, String reason,
                                                Instant suspendedAt, String triggerKind) {
        if (notificationClient == null) return;
        if (reason == null || !NOTIFIABLE_REASONS.contains(reason)) return;
        if (tenantId == null || triggerId == null) return;

        long epochDay = suspendedAt != null
                ? suspendedAt.truncatedTo(ChronoUnit.DAYS).getEpochSecond() / 86_400
                : Instant.now().truncatedTo(ChronoUnit.DAYS).getEpochSecond() / 86_400;

        NotificationEmitRequest req = new NotificationEmitRequest();
        req.setTenantId(tenantId);
        req.setCategory("WEBHOOK_TRIGGER_DISABLED");
        req.setSeverity("warning");
        req.setSubjectType("TRIGGER");
        req.setSubjectId(triggerId);
        req.setSourceId(triggerId + ":" + epochDay);
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "disabled");
        payload.put("reason", reason);
        payload.put("triggerKind", triggerKind);
        if (triggerName != null) payload.put("subjectName", triggerName);
        req.setPayload(payload);
        req.setOccurredAt(suspendedAt != null ? suspendedAt : Instant.now());

        Runnable fire = () -> {
            try {
                notificationClient.emit(req);
            } catch (Exception ex) {
                logger.warn("[lifecycle] WEBHOOK_TRIGGER_DISABLED emit failed for trigger {} kind {}: {}",
                        triggerId, triggerKind, ex.getMessage());
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { fire.run(); }
            });
        } else {
            // Fallback for non-transactional contexts (admin scripts, test paths).
            fire.run();
        }
    }

    // ============================================================================
    // Schedule
    // ============================================================================

    /**
     * Arm a schedule (transition to {@link TriggerState#ACTIVE} if recoverable).
     *
     * <p>Refuses {@link TriggerState#ARCHIVED} rows: ARCHIVED is documented as
     * "permanent - manual recovery only" ({@link TriggerState} javadoc). A
     * row archived for {@code MAX_EXEC_REACHED} would otherwise be silently
     * re-armed by any reactivate, only to skip dispatch (because
     * {@code executionCount &gt;= maxExecutions}) - invisible-but-not-firing.
     * Refusing the transition keeps the count semantics honest for the
     * caller and surfaces the situation via WARN log.
     *
     * @return {@code true} when the row is now in the armed contract
     *         ({@code state=ACTIVE && enabled && isActive}). Includes both
     *         freshly-transitioned and idempotently-already-aligned rows.
     *         {@code false} when the schedule is missing or refused
     *         (ARCHIVED). Callers can sum the {@code true} returns to get
     *         the dispatchable count, which is what
     *         {@code enableSchedulesByWorkflow} returns to orchestrator's
     *         reactivate verification.
     */
    @Transactional
    public boolean armSchedule(UUID scheduleId, Source source) {
        return scheduleRepository.findById(scheduleId).map(s -> {
            // RC2: ARCHIVED is permanent - refuse silently (with audit log) so
            // bulk arm callers (enableSchedulesByWorkflow) cannot silently
            // resurrect a row archived for MAX_EXEC_REACHED, USER_DISABLED,
            // PLAN_TRIGGER_REMOVED, etc.
            if (s.getState() == TriggerState.ARCHIVED) {
                logger.warn("[lifecycle] Refused arm of ARCHIVED schedule {} (reason={}) from source={} - " +
                        "ARCHIVED is permanent; use the admin unarchive path if recovery is intentional.",
                        scheduleId, s.getLastDisabledReason(), source);
                return false;
            }
            // Idempotency guard: skip the row write + lifecycle log when the
            // schedule is FULLY aligned. Multiple flows converge on the same
            // already-armed row - reactivate's pre-arm + post-sync verification
            // arm + the arm inside ScheduleSyncService.syncFromPinnedVersion
            // can each hit the same row in one reactivate.
            //
            // The guard checks ALL THREE booleans (not just state + enabled).
            // Legacy drift case `state=ACTIVE && enabled=true && isActive=false`
            // can plausibly arise from pre-PR2 rows or from any future code
            // path that touches one boolean without the others. Including
            // isActive in the guard means drift rows fall through to the
            // transition path and get healed by `setIsActive(true)` below.
            if (s.getState() == TriggerState.ACTIVE && s.isEnabled() && s.getIsActive()) {
                return true;
            }
            transitionSchedule(s, TriggerState.ACTIVE, null, source);
            // Keep the legacy booleans in sync so PR2 dispatch paths that still
            // read them observe the right value. PR5 drops the legacy columns.
            s.setEnabled(true);
            s.setIsActive(true);
            scheduleRepository.save(s);
            return true;
        }).orElse(false);
    }

    @Transactional
    public void suspendSchedule(UUID scheduleId, String reason, Source source) {
        scheduleRepository.findById(scheduleId).ifPresent(s -> {
            TriggerState target = WORKFLOW_UNPINNED_REASON.equals(reason)
                ? TriggerState.SUSPENDED_UNPINNED
                : TriggerState.SUSPENDED_NO_RUN;
            transitionSchedule(s, target, reason, source);
            s.setEnabled(false);
            scheduleRepository.save(s);
            emitTriggerDisabledAfterCommit(s.getId(), s.getName(), s.getTenantId(),
                    reason, s.getLastDisabledAt(), "schedule");
        });
    }

    @Transactional
    public void archiveSchedule(UUID scheduleId, String reason, Source source) {
        scheduleRepository.findById(scheduleId).ifPresent(s -> {
            transitionSchedule(s, TriggerState.ARCHIVED, reason, source);
            s.setEnabled(false);
            s.setIsActive(false);
            scheduleRepository.save(s);
        });
    }

    private void transitionSchedule(ScheduledExecutionEntity s, TriggerState target,
                                    String reason, Source source) {
        TriggerState from = s.getState();
        // Precedence guard (audit C tech debt #3): when re-entering the same suspended
        // state with a weaker reason, preserve the stronger one. E.g. USER_DISABLED (70)
        // must NOT be silently downgraded by PLAN_TRIGGER_REMOVED (50). Reason.canReplace
        // returns true iff new precedence >= current; equal precedence transitions through
        // (idempotent re-write of the same reason).
        if (from == target && target != TriggerState.ACTIVE
                && !Reason.canReplace(s.getLastDisabledReason(), reason)) {
            return;
        }
        s.setState(target);
        if (target != TriggerState.ACTIVE) {
            s.setLastDisabledReason(reason);
            s.setLastDisabledAt(Instant.now());
        } else {
            s.setLastDisabledReason(null);
            s.setLastDisabledAt(null);
        }
        s.setUpdatedAt(Instant.now());
        log(from, target, "schedule", s.getId(), reason, source);
    }

    // ============================================================================
    // Standalone webhook
    // ============================================================================

    @Transactional
    public void armWebhook(UUID webhookId, Source source) {
        webhookRepository.findById(webhookId).ifPresent(w -> {
            transitionWebhook(w, TriggerState.ACTIVE, null, source);
            w.setIsActive(true);
            webhookRepository.save(w);
        });
    }

    @Transactional
    public void suspendWebhook(UUID webhookId, String reason, Source source) {
        webhookRepository.findById(webhookId).ifPresent(w -> {
            TriggerState target = WORKFLOW_UNPINNED_REASON.equals(reason)
                ? TriggerState.SUSPENDED_UNPINNED
                : TriggerState.SUSPENDED_NO_RUN;
            transitionWebhook(w, target, reason, source);
            w.setIsActive(false);
            webhookRepository.save(w);
            emitTriggerDisabledAfterCommit(w.getId(), w.getName(), w.getTenantId(),
                    reason, w.getLastDisabledAt(), "webhook");
        });
    }

    @Transactional
    public void archiveWebhook(UUID webhookId, String reason, Source source) {
        webhookRepository.findById(webhookId).ifPresent(w -> {
            transitionWebhook(w, TriggerState.ARCHIVED, reason, source);
            w.setIsActive(false);
            webhookRepository.save(w);
        });
    }

    private void transitionWebhook(StandaloneWebhookEntity w, TriggerState target,
                                   String reason, Source source) {
        TriggerState from = w.getState();
        if (from == target && target != TriggerState.ACTIVE
                && !Reason.canReplace(w.getLastDisabledReason(), reason)) {
            return;
        }
        w.setState(target);
        if (target != TriggerState.ACTIVE) {
            w.setLastDisabledReason(reason);
            w.setLastDisabledAt(Instant.now());
        } else {
            w.setLastDisabledReason(null);
            w.setLastDisabledAt(null);
        }
        log(from, target, "webhook", w.getId(), reason, source);
    }

    // ============================================================================
    // Standalone chat endpoint
    // ============================================================================

    @Transactional
    public void armChat(UUID chatId, Source source) {
        chatRepository.findById(chatId).ifPresent(c -> {
            transitionChat(c, TriggerState.ACTIVE, null, source);
            c.setIsActive(true);
            chatRepository.save(c);
        });
    }

    @Transactional
    public void suspendChat(UUID chatId, String reason, Source source) {
        chatRepository.findById(chatId).ifPresent(c -> {
            TriggerState target = WORKFLOW_UNPINNED_REASON.equals(reason)
                ? TriggerState.SUSPENDED_UNPINNED
                : TriggerState.SUSPENDED_NO_RUN;
            transitionChat(c, target, reason, source);
            c.setIsActive(false);
            chatRepository.save(c);
            emitTriggerDisabledAfterCommit(c.getId(), c.getName(), c.getTenantId(),
                    reason, c.getLastDisabledAt(), "chat");
        });
    }

    @Transactional
    public void archiveChat(UUID chatId, String reason, Source source) {
        chatRepository.findById(chatId).ifPresent(c -> {
            transitionChat(c, TriggerState.ARCHIVED, reason, source);
            c.setIsActive(false);
            chatRepository.save(c);
        });
    }

    private void transitionChat(StandaloneChatEndpointEntity c, TriggerState target,
                                String reason, Source source) {
        TriggerState from = c.getState();
        if (from == target && target != TriggerState.ACTIVE
                && !Reason.canReplace(c.getLastDisabledReason(), reason)) {
            return;
        }
        c.setState(target);
        if (target != TriggerState.ACTIVE) {
            c.setLastDisabledReason(reason);
            c.setLastDisabledAt(Instant.now());
        } else {
            c.setLastDisabledReason(null);
            c.setLastDisabledAt(null);
        }
        log(from, target, "chat-endpoint", c.getId(), reason, source);
    }

    // ============================================================================
    // Standalone form endpoint
    // ============================================================================

    @Transactional
    public void armForm(UUID formId, Source source) {
        formRepository.findById(formId).ifPresent(f -> {
            transitionForm(f, TriggerState.ACTIVE, null, source);
            f.setIsActive(true);
            formRepository.save(f);
        });
    }

    @Transactional
    public void suspendForm(UUID formId, String reason, Source source) {
        formRepository.findById(formId).ifPresent(f -> {
            TriggerState target = WORKFLOW_UNPINNED_REASON.equals(reason)
                ? TriggerState.SUSPENDED_UNPINNED
                : TriggerState.SUSPENDED_NO_RUN;
            transitionForm(f, target, reason, source);
            f.setIsActive(false);
            formRepository.save(f);
            emitTriggerDisabledAfterCommit(f.getId(), f.getName(), f.getTenantId(),
                    reason, f.getLastDisabledAt(), "form");
        });
    }

    @Transactional
    public void archiveForm(UUID formId, String reason, Source source) {
        formRepository.findById(formId).ifPresent(f -> {
            transitionForm(f, TriggerState.ARCHIVED, reason, source);
            f.setIsActive(false);
            formRepository.save(f);
        });
    }

    private void transitionForm(StandaloneFormEndpointEntity f, TriggerState target,
                                String reason, Source source) {
        TriggerState from = f.getState();
        if (from == target && target != TriggerState.ACTIVE
                && !Reason.canReplace(f.getLastDisabledReason(), reason)) {
            return;
        }
        f.setState(target);
        if (target != TriggerState.ACTIVE) {
            f.setLastDisabledReason(reason);
            f.setLastDisabledAt(Instant.now());
        } else {
            f.setLastDisabledReason(null);
            f.setLastDisabledAt(null);
        }
        log(from, target, "form-endpoint", f.getId(), reason, source);
    }

    // ============================================================================
    // Webhook token (no legacy boolean - state is sole source of truth)
    // ============================================================================

    @Transactional
    public void armToken(Long tokenId, Source source) {
        tokenRepository.findById(tokenId).ifPresent(t -> {
            transitionToken(t, TriggerState.ACTIVE, null, source);
            tokenRepository.save(t);
        });
    }

    @Transactional
    public void suspendToken(Long tokenId, String reason, Source source) {
        tokenRepository.findById(tokenId).ifPresent(t -> {
            TriggerState target = WORKFLOW_UNPINNED_REASON.equals(reason)
                ? TriggerState.SUSPENDED_UNPINNED
                : TriggerState.SUSPENDED_NO_RUN;
            transitionToken(t, target, reason, source);
            tokenRepository.save(t);
        });
    }

    @Transactional
    public void archiveToken(Long tokenId, String reason, Source source) {
        tokenRepository.findById(tokenId).ifPresent(t -> {
            transitionToken(t, TriggerState.ARCHIVED, reason, source);
            tokenRepository.save(t);
        });
    }

    private void transitionToken(WebhookTokenEntity t, TriggerState target,
                                 String reason, Source source) {
        TriggerState from = t.getState();
        if (from == target && target != TriggerState.ACTIVE
                && !Reason.canReplace(t.getLastDisabledReason(), reason)) {
            return;
        }
        t.setState(target);
        if (target != TriggerState.ACTIVE) {
            t.setLastDisabledReason(reason);
            t.setLastDisabledAt(Instant.now());
        } else {
            t.setLastDisabledReason(null);
            t.setLastDisabledAt(null);
        }
        t.setUpdatedAt(Instant.now());
        log(from, target, "webhook-token", t.getId(), reason, source);
    }

    // ============================================================================
    // Common
    // ============================================================================

    private static final String WORKFLOW_UNPINNED_REASON = Reason.WORKFLOW_UNPINNED;

    private void log(TriggerState from, TriggerState to, String type, Object id,
                     String reason, Source source) {
        if (from == to) return;
        logger.info("[TriggerLifecycle] {} {} {} → {} (reason={}, source={})",
            type, id, from, to, reason, source);

        // V169 audit log - append a row per transition. Fail-open: persistence
        // failure (DB down, schema drift, …) is logged at WARN and does NOT
        // abort the lifecycle transition itself; the row write is observability
        // of the transition, not a correctness predicate of it. Same-tx
        // semantics: when this method is called inside a @Transactional path
        // (the arm/suspend/archive methods are all @Transactional), the audit
        // row commits with the state mutation - atomic on success, rolled
        // back on failure of either side.
        if (auditLogRepository != null) {
            try {
                TriggerStateAuditLogEntity row = new TriggerStateAuditLogEntity(
                        String.valueOf(id),
                        type,
                        from != null ? from.name() : null,
                        to.name(),
                        reason,
                        source.name(),
                        null  // actor - not yet captured at the lifecycle hub
                );
                auditLogRepository.save(row);
            } catch (Exception e) {
                logger.warn("[TriggerLifecycle] Failed to persist audit row for {} {} ({}→{}): {}",
                        type, id, from, to, e.getMessage());
            }
        }
    }
}

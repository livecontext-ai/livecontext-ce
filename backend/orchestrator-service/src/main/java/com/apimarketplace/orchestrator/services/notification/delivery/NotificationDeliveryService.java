package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.SuppressionReason;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Kind;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Medium;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Decides whether a new notification leaves the platform, and on which medium.
 * The bell already has it; this only ever ADDS an email or a chat message.
 *
 * <p>The rules, in the order they apply:
 * <ol>
 *   <li>Only the categories of a {@link NotificationTopic} are delivered at all.</li>
 *   <li>The person's choice for that topic in that workspace: OFF, EMAIL,
 *       CHANNEL or BOTH.</li>
 *   <li>A failed run joins its workflow's open incident; only the failure that
 *       OPENS the incident is sent, plus ONE "failing again" when it breaks within
 *       24 hours of an announced recovery. Reminders and "recovered" come from
 *       {@link NotificationDigestScheduler} and {@link #onProductionSuccess}.</li>
 *   <li>Digest topics are never sent alone: the daily summary picks them up.</li>
 *   <li>Email needs the plan ({@link NotificationEmailEntitlement}), except the
 *       topics sent on every plan ({@link NotificationTopic#emailOnEveryPlan()}).</li>
 *   <li>At most {@code dailyCap} immediate messages per person and medium in 24
 *       hours; beyond that the message is DEFERRED to the next summary.</li>
 * </ol>
 *
 * <p>Runs on its own small pool after the notification row committed, so a slow
 * SMTP relay or chat API never holds up a run. Every failure is swallowed and
 * counted: the bell row is the guarantee, delivery is best effort.
 */
@Service
public class NotificationDeliveryService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private static final Duration CAP_WINDOW = Duration.ofHours(24);

    private final NotificationPreferenceStore preferences;
    private final NotificationIncidentStore incidents;
    private final NotificationDeliveryLog deliveryLog;
    private final NotificationEmailEntitlement emailEntitlement;
    private final NotificationMessageComposer composer;
    private final NotificationSender sender;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final int dailyCap;

    /** Product analytics; optional so a unit test can build the service without it. */
    @Autowired(required = false)
    private EngagementAnalyticsEmitter analytics;

    public NotificationDeliveryService(NotificationPreferenceStore preferences,
                                       NotificationIncidentStore incidents,
                                       NotificationDeliveryLog deliveryLog,
                                       NotificationEmailEntitlement emailEntitlement,
                                       NotificationMessageComposer composer,
                                       NotificationSender sender,
                                       MeterRegistry meterRegistry,
                                       @Value("${notifications.delivery.enabled:true}") boolean enabled,
                                       @Value("${notifications.delivery.daily-cap:10}") int dailyCap) {
        this.preferences = preferences;
        this.incidents = incidents;
        this.deliveryLog = deliveryLog;
        this.emailEntitlement = emailEntitlement;
        this.composer = composer;
        this.sender = sender;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.dailyCap = dailyCap;
    }

    /**
     * {@code fallbackExecution}: an emitter that ever publishes outside a
     * transaction must still deliver, instead of being dropped with no log line
     * (the trap {@code NotificationEmitter#onBudgetReached} documents).
     */
    @Async("notificationDeliveryExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(NotificationCreatedEvent event) {
        try {
            handle(event);
        } catch (RuntimeException ex) {
            meterRegistry.counter("notification.delivery.errors", "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[notification-delivery] swallowed for notification {} ({}): {}",
                    event.notificationId(), event.category(), ex.getMessage());
        }
    }

    void handle(NotificationCreatedEvent event) {
        if (!enabled || event.tenantId() == null || event.organizationId() == null) return;
        NotificationTopic topic = NotificationTopic.ofCategory(event.category()).orElse(null);
        if (topic == null) return;

        DeliveryMode mode = preferences.resolve(event.tenantId(), event.organizationId(), topic);
        if (mode == DeliveryMode.OFF) {
            suppressed(event.tenantId(), event.organizationId(), SuppressionReason.PREFERENCE_OFF, null,
                    topic, event.category());
            return;
        }

        if ("RUN_FAILED".equals(event.category()) && event.subjectId() != null) {
            NotificationIncidentStore.FailureOutcome outcome = incidents.recordFailure(event.tenantId(),
                    event.organizationId(), event.subjectId(),
                    event.occurredAt() != null ? event.occurredAt() : Instant.now());
            switch (outcome) {
                case OPENED -> { /* the one failure to send, below */ }
                case FAILING_AGAIN -> {
                    deliver(event.tenantId(), event.organizationId(), event.notificationId(), Kind.ALERT,
                            composer.failingAgain(event), mode, topic, event.category());
                    return;
                }
                default -> {
                    // JOINED: already reported (or seeded by V528), surfaced by the daily reminder.
                    meterRegistry.counter("notification.delivery.suppressed",
                            "reason", outcome.name().toLowerCase(java.util.Locale.ROOT)).increment();
                    suppressed(event.tenantId(), event.organizationId(), SuppressionReason.INCIDENT_JOINED, null,
                            topic, event.category());
                    return;
                }
            }
        }

        if (topic.isDigest()) return;

        deliver(event.tenantId(), event.organizationId(), event.notificationId(), Kind.ALERT,
                composer.alert(event), mode, topic, event.category());
    }

    /**
     * Synchronous, one index probe: lets the caller skip its production-run
     * checks for the (overwhelmingly common) workflow that was not failing.
     */
    public boolean hasOpenIncident(UUID workflowId) {
        return enabled && workflowId != null && incidents.hasOpen(workflowId);
    }

    /*
     * Known, accepted race: this probe runs synchronously on the success, while the failure it
     * would close is recorded on the delivery pool. A success landing while that failure is still
     * queued finds nothing open; its recovery is then reported by the next production success, or
     * the incident closes silently after a week without failures.
     */

    /**
     * A production run or epoch of this workflow succeeded. Closes its open
     * incidents and tells each person it recovered. The caller has already
     * applied the production-run filter; this re-checks nothing but the cheap
     * "is anything open" probe.
     */
    @Async("notificationDeliveryExecutor")
    public void onProductionSuccess(UUID workflowId) {
        if (!enabled || workflowId == null) return;
        try {
            if (!incidents.hasOpen(workflowId)) return;
            // resolve() has already CLOSED every incident it returns, so each one's message is
            // isolated: a failure on one person's must not cost the others theirs.
            for (NotificationIncidentStore.Incident incident : incidents.resolve(workflowId, Instant.now())) {
                // A flapping workflow: its recovery was already announced today. Closed, not announced.
                if (!incident.announceRecovery()) continue;
                try {
                    DeliveryMode mode = preferences.resolve(incident.tenantId(), incident.organizationId(),
                            NotificationTopic.FAILURES);
                    if (mode == DeliveryMode.OFF) {
                        // Same report as the alert path: the recovery message was held back by choice.
                        suppressed(incident.tenantId(), incident.organizationId(), SuppressionReason.PREFERENCE_OFF,
                                null, NotificationTopic.FAILURES, INCIDENT_CATEGORY);
                        continue;
                    }
                    deliver(incident.tenantId(), incident.organizationId(), null, Kind.RECOVERED,
                            composer.recovered(incident), mode, NotificationTopic.FAILURES, INCIDENT_CATEGORY);
                } catch (RuntimeException ex) {
                    meterRegistry.counter("notification.delivery.errors", "type", ex.getClass().getSimpleName()).increment();
                    logger.warn("[notification-delivery] recovery message for incident {} swallowed: {}",
                            incident.id(), ex.getMessage());
                }
            }
        } catch (RuntimeException ex) {
            meterRegistry.counter("notification.delivery.errors", "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[notification-delivery] recovery for workflow {} swallowed: {}", workflowId, ex.getMessage());
        }
    }

    /**
     * The owner stopped this workflow on purpose: one of its schedules was paused or deleted,
     * its production run was paused or cancelled, or the workflow was unpinned or deleted. Its
     * open incidents close with no message, so the daily "still failing" reminder does not keep
     * reporting something its owner switched off. Other triggers of the workflow may still fire:
     * a failure after the stop then opens a new incident and is sent as a new alert.
     *
     * <p>THE one entry point for every stop site. A stop must succeed whether or not its
     * incident could be closed, so the close never runs inside the caller's transaction: a
     * failed UPDATE there would abort the stop even with the exception swallowed here, and a
     * rolled-back stop would have closed the incident anyway. It waits for the transaction to
     * COMPLETE as committed, then runs on the delivery pool, on a connection of its own. When
     * the close cannot be made, the incident closes by itself after a week without failures,
     * as before.
     *
     * <p>{@code afterCompletion}, not {@code afterCommit}: some callers are themselves inside
     * an afterCommit callback (the run cancel/pause cascade), and a synchronization registered
     * during that phase never receives afterCommit. It does receive afterCompletion.
     */
    public void onWorkflowStopped(UUID workflowId) {
        if (!enabled || workflowId == null) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) dispatchCloseStopped(workflowId);
                }
            });
            return;
        }
        dispatchCloseStopped(workflowId);
    }

    /**
     * The delivery pool, so the close gets its own connection instead of the one a just
     * completed transaction is still handing back. Optional: without it (a hand-built test)
     * the close runs inline.
     */
    private TaskExecutor stopExecutor;

    @Autowired(required = false)
    public void setStopExecutor(@Qualifier("notificationDeliveryExecutor") TaskExecutor stopExecutor) {
        this.stopExecutor = stopExecutor;
    }

    private void dispatchCloseStopped(UUID workflowId) {
        if (stopExecutor == null) {
            closeStopped(workflowId);
            return;
        }
        try {
            stopExecutor.execute(() -> closeStopped(workflowId));
        } catch (RuntimeException rejected) {
            // A saturated pool must not fail the stop either.
            meterRegistry.counter("notification.delivery.errors", "type", rejected.getClass().getSimpleName()).increment();
            logger.warn("[notification-delivery] closing incidents of stopped workflow {} not queued: {}",
                    workflowId, rejected.getMessage());
        }
    }

    private void closeStopped(UUID workflowId) {
        try {
            int closed = incidents.closeStopped(workflowId, Instant.now());
            if (closed > 0) {
                logger.info("[notification-delivery] closed {} incident(s) of stopped workflow {}", closed, workflowId);
            }
        } catch (RuntimeException ex) {
            meterRegistry.counter("notification.delivery.errors", "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[notification-delivery] closing incidents of stopped workflow {} swallowed: {}",
                    workflowId, ex.getMessage());
        }
    }

    /**
     * Sends one immediate message on every medium the person wants and may get.
     * A capped medium defers when there is a notification row to carry into the
     * summary; a reminder or recovery (no row) that hits the cap is dropped, the
     * incident itself staying visible in the bell and in the next reminder.
     */
    void deliver(String tenantId, String organizationId, Long notificationId, Kind kind,
                 NotificationMessage message, DeliveryMode mode, NotificationTopic topic) {
        deliver(tenantId, organizationId, notificationId, kind, message, mode, topic, null);
    }

    /**
     * @param category the bell category the message is about, when known; reported to
     *                 analytics only, it changes nothing about the delivery
     */
    void deliver(String tenantId, String organizationId, Long notificationId, Kind kind,
                 NotificationMessage message, DeliveryMode mode, NotificationTopic topic, String category) {
        if (message == null) return;
        if (mode.wantsEmail()) {
            if (!emailEntitlement.allows(tenantId, topic)) {
                suppressed(tenantId, organizationId, SuppressionReason.EMAIL_NOT_ON_PLAN, Medium.EMAIL, topic, category);
            } else if (underCap(tenantId, Medium.EMAIL)) {
                sender.email(tenantId, organizationId, notificationId, kind, message, topic, category);
            } else if (notificationId != null) {
                sender.deferred(tenantId, organizationId, notificationId, kind, Medium.EMAIL, topic, category);
            } else {
                // No bell row to carry into the summary: dropped (a deferred one is reported by the sender).
                suppressed(tenantId, organizationId, SuppressionReason.DAILY_CAP, Medium.EMAIL, topic, category);
            }
        }
        if (mode.wantsChannel()) {
            if (underCap(tenantId, Medium.CHANNEL)) {
                sender.channel(tenantId, organizationId, notificationId, kind, message, topic, category);
            } else if (notificationId != null) {
                sender.deferred(tenantId, organizationId, notificationId, kind, Medium.CHANNEL, topic, category);
            } else {
                suppressed(tenantId, organizationId, SuppressionReason.DAILY_CAP, Medium.CHANNEL, topic, category);
            }
        }
    }

    /** The only category that opens an incident, so the one a reminder or recovery is about. */
    static final String INCIDENT_CATEGORY = "RUN_FAILED";

    private void suppressed(String tenantId, String organizationId, SuppressionReason reason, Medium medium,
                            NotificationTopic topic, String category) {
        if (analytics != null) {
            analytics.notificationSuppressed(tenantId, organizationId, reason, medium, topic, category);
        }
    }

    /** True when this person may still receive an immediate message on {@code medium} today. */
    boolean underCap(String tenantId, Medium medium) {
        return deliveryLog.countImmediateSentSince(tenantId, medium, Instant.now().minus(CAP_WINDOW)) < dailyCap;
    }

    /** The mediums a reminder may still use for this person, for the scheduler. */
    List<Medium> openMediums(String tenantId, DeliveryMode mode) {
        java.util.ArrayList<Medium> out = new java.util.ArrayList<>(2);
        if (mode.wantsEmail() && emailEntitlement.allows(tenantId, NotificationTopic.FAILURES)
                && underCap(tenantId, Medium.EMAIL)) out.add(Medium.EMAIL);
        if (mode.wantsChannel() && underCap(tenantId, Medium.CHANNEL)) out.add(Medium.CHANNEL);
        return out;
    }
}

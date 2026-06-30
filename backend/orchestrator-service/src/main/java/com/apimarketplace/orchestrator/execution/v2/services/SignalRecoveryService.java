package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity.SignalWaitStatus;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Recovers signal state on server startup.
 *
 * Handles two scenarios:
 * 1. Stale CLAIMED signals: Server crashed between claim and resolve.
 *    These are reset to PENDING and their in-memory timers are re-scheduled.
 * 2. RESOLVED signals with still-RUNNING workflows: Server crashed AFTER
 *    resolveSignal() TX committed but BEFORE the @TransactionalEventListener(AFTER_COMMIT)
 *    fired. The signal is RESOLVED but execution was never resumed.
 *    These are re-triggered for resume (idempotent via getReadyNodes()).
 *
 * @see UnifiedSignalService
 * @see SignalResumeService
 */
@Service
public class SignalRecoveryService {

    private static final Logger logger = LoggerFactory.getLogger(SignalRecoveryService.class);

    private final SignalWaitRepository signalWaitRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final Clock clock;

    // Lazy to avoid circular startup dependencies
    @org.springframework.beans.factory.annotation.Autowired
    @Lazy
    private SignalResumeService signalResumeService;

    @org.springframework.beans.factory.annotation.Autowired
    @Lazy
    private SignalTimerScheduler timerScheduler;

    public SignalRecoveryService(SignalWaitRepository signalWaitRepository,
                                 WorkflowRunRepository workflowRunRepository,
                                 Clock clock) {
        this.signalWaitRepository = signalWaitRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.clock = clock;
    }

    /**
     * Recover signals on application startup.
     *
     * This runs after the application context is fully initialized,
     * ensuring all services (including SignalResumeService) are available.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverSignalsOnStartup() {
        logger.info("[SignalRecovery] Starting signal recovery on startup...");

        try {
            // 1. Reset stale CLAIMED signals (claimed > 30s ago) to PENDING
            // These represent signals that were being processed when the server crashed.
            int resetCount = resetStaleClaims();

            // 2. Find RESOLVED signals where the workflow is still RUNNING
            // These represent signals that were resolved but the resume event was lost
            // due to a crash between TX commit and AFTER_COMMIT event dispatch.
            int resumeCount = resumeOrphanedResolvedSignals();

            // 3. Re-schedule in-memory timers for all PENDING signals with expiresAt
            int timerCount = recoverInMemoryTimers();

            // 4. Log summary of all pending signals
            logPendingSignalSummary();

            logger.info("[SignalRecovery] Recovery complete: resetClaims={}, resumedOrphans={}, timersRecovered={}",
                resetCount, resumeCount, timerCount);

        } catch (Exception e) {
            logger.error("[SignalRecovery] Error during signal recovery: {}", e.getMessage(), e);
        }
    }

    /**
     * Reset CLAIMED signals that are older than the stale threshold.
     * Moves them back to PENDING so their in-memory timers can be re-scheduled.
     */
    private int resetStaleClaims() {
        Instant staleThreshold = clock.instant().minus(Duration.ofSeconds(30));
        int resetCount = signalWaitRepository.resetStaleClaims(staleThreshold);
        if (resetCount > 0) {
            logger.warn("[SignalRecovery] Reset {} stale CLAIMED signals to PENDING", resetCount);
        }
        return resetCount;
    }

    /**
     * Find RESOLVED signals whose runs are still RUNNING and re-trigger resume.
     *
     * This handles the case where resolveSignal() committed successfully but the
     * AFTER_COMMIT event was lost due to server crash. The signal is RESOLVED in DB
     * but SignalResumeService.onSignalResolved() never fired.
     *
     * Safe to re-trigger because:
     * - getReadyNodes() returns empty if successors already ran (idempotent)
     * - Each signal has a unique (runId, nodeId, itemId, epoch) constraint
     */
    private int resumeOrphanedResolvedSignals() {
        // Only look at signals resolved in the last hour (older ones are stale)
        Instant cutoff = clock.instant().minus(Duration.ofHours(1));
        List<SignalWaitEntity> orphanedSignals = signalWaitRepository.findResolvedSignalsForRunningWorkflows(cutoff);

        if (orphanedSignals.isEmpty()) {
            return 0;
        }

        logger.info("[SignalRecovery] Found {} RESOLVED signals with RUNNING workflows to re-trigger",
            orphanedSignals.size());

        int resumeCount = 0;
        for (SignalWaitEntity signal : orphanedSignals) {
            try {
                logger.info("[SignalRecovery] Re-triggering resume for signal: id={}, runId={}, nodeId={}",
                    signal.getId(), signal.getRunId(), signal.getNodeId());
                // Bind orgId on this ApplicationReadyEvent recovery thread. Same prod-fire
                // shape as the 2026-05-20 16:57 UTC AgentResultSubscriber incident: no
                // request context here, so any OrgScopedEntity persist downstream
                // (storage rows via persistSignalResolutionOutput) would trip the
                // @PrePersist fail-loud listener post-V263.
                String orgId = workflowRunRepository.findByRunIdPublic(signal.getRunId())
                    .map(WorkflowRunEntity::getOrganizationId)
                    .orElse(null);
                Runnable resume = () -> signalResumeService.resumeAfterSignal(signal);
                if (orgId == null || orgId.isBlank()) {
                    resume.run();
                } else {
                    com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgId, resume);
                }
                resumeCount++;
            } catch (Exception e) {
                logger.error("[SignalRecovery] Error re-triggering resume for signal {}: {}",
                    signal.getId(), e.getMessage(), e);
            }
        }

        return resumeCount;
    }

    /**
     * Re-schedule in-memory timers for all PENDING signals that have an expiresAt.
     * This handles the case where the server was restarted while timers were active.
     */
    private int recoverInMemoryTimers() {
        List<SignalWaitEntity> pending = signalWaitRepository.findPendingWithExpiration();
        List<SignalTimerScheduler.SignalTimerInfo> timerInfos = pending.stream()
            .map(s -> new SignalTimerScheduler.SignalTimerInfo(s.getId(), s.getExpiresAt()))
            .toList();
        timerScheduler.recoverPendingTimers(timerInfos);
        return timerInfos.size();
    }

    /**
     * Log a summary of all pending signals by type.
     */
    private void logPendingSignalSummary() {
        List<SignalWaitEntity> pendingSignals = signalWaitRepository.findAll().stream()
            .filter(s -> s.getStatus() == SignalWaitStatus.PENDING)
            .toList();

        if (pendingSignals.isEmpty()) {
            logger.info("[SignalRecovery] No pending signals");
            return;
        }

        long timerCount = pendingSignals.stream()
            .filter(s -> s.getSignalType() == com.apimarketplace.orchestrator.domain.execution.SignalType.WAIT_TIMER)
            .count();
        long approvalCount = pendingSignals.stream()
            .filter(s -> s.getSignalType() == com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL)
            .count();
        long webhookCount = pendingSignals.stream()
            .filter(s -> s.getSignalType() == com.apimarketplace.orchestrator.domain.execution.SignalType.WEBHOOK_WAIT)
            .count();

        logger.info("[SignalRecovery] Pending signals: total={}, timers={}, approvals={}, webhooks={}",
            pendingSignals.size(), timerCount, approvalCount, webhookCount);
    }
}

package com.apimarketplace.trigger.service;

import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.repository.StandaloneChatEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Periodic garbage collector for standalone trigger resources that were
 * created by the builder but never linked to a workflow.
 *
 * <p>Context: the builder auto-creates a standalone webhook / schedule /
 * chat endpoint / form endpoint on the first inspect of a trigger node,
 * under a stable {@code sourceNodeId}. When the user abandons the draft
 * (closes the tab before saving), the row lingers with
 * {@code workflow_id = NULL}. Without this reaper, every abandoned draft
 * permanently consumes one of the user's quota slots - and the V136 unique
 * index on {@code (tenant_id, source_node_id)} means a re-dragged node
 * would silently reuse the orphan, not create a fresh row (which is fine,
 * but only if the orphan is reclaimable, i.e. not hours-stale).
 *
 * <p>Runs hourly on a single node (ShedLock). Deletes rows with
 * {@code workflow_id IS NULL} older than {@code orphanTtlHours} (default 24h).
 * Non-destructive for linked resources - {@code workflow_id} is the signal.
 */
@Service
public class StandaloneTriggerReaperService {

    private static final Logger logger = LoggerFactory.getLogger(StandaloneTriggerReaperService.class);

    private final StandaloneWebhookRepository webhookRepository;
    private final StandaloneChatEndpointRepository chatEndpointRepository;
    private final StandaloneFormEndpointRepository formEndpointRepository;
    private final ScheduledExecutionRepository scheduleRepository;
    private final OrchestratorWorkflowExistenceClient workflowExistenceClient;

    @Value("${standalone-trigger.orphan-ttl-hours:24}")
    private int orphanTtlHours;

    @Value("${standalone-trigger.reaper.enabled:true}")
    private boolean enabled;

    @Value("${standalone-trigger.stale-fk-reaper.enabled:true}")
    private boolean staleFkReaperEnabled;

    /**
     * Race-guard: only consider trigger rows older than this when checking workflow
     * existence. Default 1h. Skips rows that may reference a workflow whose orchestrator
     * INSERT is still committing - preventing false-positive deletions of fresh links.
     */
    @Value("${standalone-trigger.stale-fk-reaper.min-age-minutes:60}")
    private int staleFkReaperMinAgeMinutes;

    /**
     * Cap on the size of a single existence-check round-trip. The orchestrator
     * endpoint joins the param into a comma-separated query string; keeping a
     * conservative cap avoids URL-length surprises and produces a steady RPS
     * profile when a tenant has thousands of historical workflow references.
     */
    private static final int EXISTENCE_CHECK_BATCH_SIZE = 100;

    public StandaloneTriggerReaperService(StandaloneWebhookRepository webhookRepository,
                                          StandaloneChatEndpointRepository chatEndpointRepository,
                                          StandaloneFormEndpointRepository formEndpointRepository,
                                          ScheduledExecutionRepository scheduleRepository,
                                          OrchestratorWorkflowExistenceClient workflowExistenceClient) {
        this.webhookRepository = webhookRepository;
        this.chatEndpointRepository = chatEndpointRepository;
        this.formEndpointRepository = formEndpointRepository;
        this.scheduleRepository = scheduleRepository;
        this.workflowExistenceClient = workflowExistenceClient;
    }

    /**
     * Reaps orphans across all four standalone trigger tables. Runs every
     * hour, guarded by ShedLock to serialize across orchestrator replicas.
     */
    @Scheduled(fixedDelayString = "${standalone-trigger.reaper.fixed-delay-ms:3600000}",
               initialDelayString = "${standalone-trigger.reaper.initial-delay-ms:120000}")
    @SchedulerLock(name = "standaloneTriggerReaper", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    @Transactional
    public void reapOrphans() {
        if (!enabled) return;

        Instant cutoff = Instant.now().minus(Duration.ofHours(orphanTtlHours));

        int webhooks = reapWebhooks(cutoff);
        int chats = reapChatEndpoints(cutoff);
        int forms = reapFormEndpoints(cutoff);
        int schedules = reapSchedules(cutoff);

        int total = webhooks + chats + forms + schedules;
        if (total > 0) {
            logger.info("[StandaloneTriggerReaper] Deleted {} orphan(s) older than {}h: webhooks={}, chats={}, forms={}, schedules={}",
                    total, orphanTtlHours, webhooks, chats, forms, schedules);
        } else {
            logger.debug("[StandaloneTriggerReaper] No orphans older than {}h", orphanTtlHours);
        }
    }

    int reapWebhooks(Instant cutoff) {
        var orphans = webhookRepository.findByWorkflowIdIsNullAndCreatedAtBefore(cutoff);
        if (orphans.isEmpty()) return 0;
        webhookRepository.deleteAll(orphans);
        return orphans.size();
    }

    int reapChatEndpoints(Instant cutoff) {
        var orphans = chatEndpointRepository.findByWorkflowIdIsNullAndCreatedAtBefore(cutoff);
        if (orphans.isEmpty()) return 0;
        chatEndpointRepository.deleteAll(orphans);
        return orphans.size();
    }

    int reapFormEndpoints(Instant cutoff) {
        var orphans = formEndpointRepository.findByWorkflowIdIsNullAndCreatedAtBefore(cutoff);
        if (orphans.isEmpty()) return 0;
        formEndpointRepository.deleteAll(orphans);
        return orphans.size();
    }

    int reapSchedules(Instant cutoff) {
        var orphans = scheduleRepository.findOrphansOlderThan(cutoff);
        if (orphans.isEmpty()) return 0;
        scheduleRepository.deleteAll(orphans);
        return orphans.size();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Stale-FK reaper (cross-schema reconciliation)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Daily sweep that prunes trigger rows whose {@code workflow_id} no longer
     * resolves to a live row in {@code orchestrator.workflows}.
     *
     * <p>The {@link #reapOrphans()} sweeper above only catches rows with
     * {@code workflow_id IS NULL} (abandoned builder drafts, bounded TTL).
     * <b>This sweeper catches the second leak class:</b> rows that <i>were</i>
     * linked to a real workflow that has since been deleted while the
     * {@code triggerClient.deleteSchedulesByWorkflow(...)} cascade in
     * {@code orchestrator-service.WorkflowManagementService.deleteWorkflow}
     * was unable to reach this service (network blip, restart, etc. - the
     * cascade is best-effort try/catch by design). Without this sweep those
     * rows accumulate forever and produce a "Workflow not found" warn every
     * tick of the schedule executor.
     *
     * <p>Mirror of {@code publication-service.PublicationCleanupService} -
     * the canonical pattern for FK-less cross-schema reconciliation in this
     * codebase. Cross-schema queries are forbidden by CLAUDE.md (each service
     * owns its schema), so the workflow-existence check goes over HTTP via
     * {@link OrchestratorWorkflowExistenceClient#getExistingWorkflowIds(Set)}.
     *
     * <p>Runs daily at 03:15 UTC, ShedLock-serialized, in its own transaction
     * so a failure here cannot poison the orphan reaper above.
     */
    @Scheduled(cron = "${standalone-trigger.stale-fk-reaper.cron:0 15 3 * * *}")
    @SchedulerLock(name = "standaloneTriggerStaleFkReaper", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void reapStaleWorkflowReferences() {
        if (!staleFkReaperEnabled) return;

        // Top-level guard mirrors PublicationCleanupService: a sweeper failure must
        // never escape to the Spring scheduler thread (would emit a noisy ERROR and
        // count against ShedLock's failure budget on some configurations).
        try {
            doReapStaleWorkflowReferences();
        } catch (Exception e) {
            logger.error("[StandaloneTriggerStaleFkReaper] Tick failed, will retry next cron: {}", e.getMessage(), e);
        }
    }

    private void doReapStaleWorkflowReferences() {
        Instant ageCutoff = Instant.now().minus(Duration.ofMinutes(staleFkReaperMinAgeMinutes));

        // Collect all distinct non-null workflow IDs across the four soft-link tables
        // in a single pass - a workflow referenced by both a webhook and a schedule
        // should produce one HTTP check, not two. Age filter excludes very fresh rows
        // whose workflow may not yet be visible in orchestrator (race guard).
        Set<UUID> referencedIds = new HashSet<>();
        referencedIds.addAll(webhookRepository.findDistinctNonNullWorkflowIds(ageCutoff));
        referencedIds.addAll(chatEndpointRepository.findDistinctNonNullWorkflowIds(ageCutoff));
        referencedIds.addAll(formEndpointRepository.findDistinctNonNullWorkflowIds(ageCutoff));
        referencedIds.addAll(scheduleRepository.findDistinctNonNullWorkflowIds(ageCutoff));

        if (referencedIds.isEmpty()) {
            logger.debug("[StandaloneTriggerStaleFkReaper] No workflow references to check");
            return;
        }

        // Batched existence check against orchestrator. Each batch's "missing" set
        // is the slice of orphans we delete; the union across batches is the full
        // dead-workflow set for this tick.
        Set<UUID> deadWorkflowIds = new HashSet<>();
        UUID[] all = referencedIds.toArray(new UUID[0]);
        for (int i = 0; i < all.length; i += EXISTENCE_CHECK_BATCH_SIZE) {
            int end = Math.min(i + EXISTENCE_CHECK_BATCH_SIZE, all.length);
            Set<UUID> batch = new HashSet<>();
            for (int j = i; j < end; j++) batch.add(all[j]);

            Set<UUID> alive = workflowExistenceClient.getExistingWorkflowIds(batch);
            // Fail-safe contract: when the client cannot reach orchestrator it returns
            // the input unchanged → batch \ alive == ∅ → nothing deleted in that batch.
            for (UUID id : batch) {
                if (!alive.contains(id)) deadWorkflowIds.add(id);
            }
        }

        if (deadWorkflowIds.isEmpty()) {
            logger.debug("[StandaloneTriggerStaleFkReaper] All {} referenced workflow(s) still exist",
                    referencedIds.size());
            return;
        }

        int webhooks = webhookRepository.deleteByWorkflowIdIn(deadWorkflowIds);
        int chats = chatEndpointRepository.deleteByWorkflowIdIn(deadWorkflowIds);
        int forms = formEndpointRepository.deleteByWorkflowIdIn(deadWorkflowIds);
        int schedules = scheduleRepository.deleteByWorkflowIdIn(deadWorkflowIds);
        int total = webhooks + chats + forms + schedules;

        // INFO-level audit trail with the actual UUIDs so an ops audit can reconstruct
        // exactly which workflows were reclaimed in case of dispute.
        logger.warn("[StandaloneTriggerStaleFkReaper] Deleted {} stale-FK row(s) referencing {} dead workflow(s): "
                        + "webhooks={}, chats={}, forms={}, schedules={}",
                total, deadWorkflowIds.size(), webhooks, chats, forms, schedules);
        logger.info("[StandaloneTriggerStaleFkReaper] Dead workflow IDs reclaimed this tick: {}", deadWorkflowIds);
    }
}

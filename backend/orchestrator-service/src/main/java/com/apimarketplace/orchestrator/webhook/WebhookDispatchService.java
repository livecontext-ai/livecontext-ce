package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.credit.CreditExhaustion;
import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.StandaloneWebhookDto;
import com.apimarketplace.trigger.client.dto.WebhookTokenDto;
import com.apimarketplace.trigger.client.webhook.WebhookConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for dispatching incoming webhook calls to workflow execution.
 *
 * Architecture (Multi-DAG support):
 * - Each webhook trigger has its own unique token
 * - Token lookup returns both workflowId and triggerId
 * - Webhook works when there is a run in WAITING_TRIGGER status
 *   (step-by-step mode also uses WAITING_TRIGGER when triggers are ready)
 *
 * @see ReusableTriggerService
 */
@Service
public class WebhookDispatchService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookDispatchService.class);

    private final TriggerClient triggerClient;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository runRepository;
    private final ReusableTriggerService triggerService;
    private final ProductionRunResolver productionRunResolver;
    private final WebhookResponseRegistry webhookResponseRegistry;
    private final WebhookRateLimiter rateLimiter;

    /**
     * Runs whose sync dispatch is still blocking a request thread inside {@code executeTrigger}.
     * The first half of what {@link #syncRequestStillParked} reports; entries live only for the
     * duration of that call and are removed in a {@code finally}.
     */
    private final java.util.Set<String> syncDispatchInFlight = ConcurrentHashMap.newKeySet();

    /**
     * The four webhook ceilings are properties, not constants: an operator whose legitimate
     * traffic hits one of them can raise it without a code change and a redeploy. Every default
     * is the shipped value, and a non-positive override falls back to it rather than disabling
     * the guard.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public WebhookDispatchService(TriggerClient triggerClient,
                                  WorkflowRepository workflowRepository,
                                  WorkflowRunRepository runRepository,
                                  ReusableTriggerService triggerService,
                                  ProductionRunResolver productionRunResolver,
                                  WebhookResponseRegistry webhookResponseRegistry,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${webhook.rate-limit.fires-per-token:120}") int firesPerToken,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${webhook.rate-limit.fires-per-owner:600}") int firesPerOwner,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${webhook.rate-limit.concurrent-sync-per-owner:20}") int concurrentSyncPerOwner,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${webhook.rate-limit.concurrent-sync-total:200}") int concurrentSyncTotal,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${webhook.rate-limit.sync-slot-ttl-seconds:90}") long syncSlotTtlSeconds,
                                  @org.springframework.beans.factory.annotation.Value(
                                          "${scaling.backend:memory}") String scalingBackend,
                                  org.springframework.beans.factory.ObjectProvider<
                                          org.springframework.data.redis.core.StringRedisTemplate> redisTemplate) {
        this(triggerClient, workflowRepository, runRepository, triggerService,
                productionRunResolver, webhookResponseRegistry,
                new WebhookRateLimiter(Clock.systemUTC(), firesPerToken, firesPerOwner,
                        concurrentSyncPerOwner, concurrentSyncTotal,
                        java.time.Duration.ofSeconds(syncSlotTtlSeconds))
                        .withClusterCounter(RedisFireCounter.forBackend(scalingBackend,
                                redisTemplate == null ? null : redisTemplate.getIfAvailable())));
    }

    /**
     * Cluster-wide fire counter for the per-token and per-owner windows (LC-042, audit round 2).
     *
     * <p>With several orchestrator replicas behind the gateway, a per-instance window multiplied
     * every ceiling by the replica count. When {@code scaling.backend=redis} (the multi-instance
     * mode) the counts live in Redis instead: a fixed one-minute bucket per key, {@code INCR} then
     * {@code EXPIRE} on the first hit, the same pattern the gateway rate limiter uses. Tokens are
     * hashed before they become a key. Any Redis error answers {@code null}, and the caller falls
     * back to its in-memory window rather than failing webhooks (CE runs without Redis and always
     * uses the in-memory window).
     */
    static final class RedisFireCounter implements WebhookRateLimiter.ClusterFireCounter {
        /**
         * INCR and PEXPIRE in one atomic step: with two separate calls, a crash or a dropped
         * connection between them left a bucket with no TTL that grew for ever.
         */
        static final org.springframework.data.redis.core.script.RedisScript<Long> INCR_WITH_TTL =
                new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                        "local c = redis.call('INCR', KEYS[1]) "
                                + "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end "
                                + "return c",
                        Long.class);

        private final org.springframework.data.redis.core.StringRedisTemplate redis;

        RedisFireCounter(org.springframework.data.redis.core.StringRedisTemplate redis) {
            this.redis = redis;
        }

        static WebhookRateLimiter.ClusterFireCounter forBackend(
                String scalingBackend, org.springframework.data.redis.core.StringRedisTemplate redis) {
            return "redis".equalsIgnoreCase(scalingBackend == null ? "" : scalingBackend.trim()) && redis != null
                    ? new RedisFireCounter(redis) : null;
        }

        @Override
        public Long hit(String key, java.time.Duration window, long nowMillis) {
            try {
                String bucketKey = "rl:webhook:" + key + ":" + (nowMillis / window.toMillis());
                return redis.execute(INCR_WITH_TTL, java.util.List.of(bucketKey),
                        String.valueOf(window.multipliedBy(2).toMillis()));
            } catch (RuntimeException e) {
                logger.warn("Webhook rate limit: Redis unavailable, using the in-memory window: {}", e.getMessage());
                return null;
            }
        }
    }

    /** All ceilings at their shipped defaults. */
    public WebhookDispatchService(TriggerClient triggerClient,
                                  WorkflowRepository workflowRepository,
                                  WorkflowRunRepository runRepository,
                                  ReusableTriggerService triggerService,
                                  ProductionRunResolver productionRunResolver,
                                  WebhookResponseRegistry webhookResponseRegistry) {
        this(triggerClient, workflowRepository, runRepository, triggerService,
                productionRunResolver, webhookResponseRegistry, new WebhookRateLimiter(Clock.systemUTC()));
    }

    /** Test seam: lets a test drive the sliding window from a fixed clock instead of sleeping. */
    WebhookDispatchService(TriggerClient triggerClient,
                           WorkflowRepository workflowRepository,
                           WorkflowRunRepository runRepository,
                           ReusableTriggerService triggerService,
                           ProductionRunResolver productionRunResolver,
                           WebhookResponseRegistry webhookResponseRegistry,
                           WebhookRateLimiter rateLimiter) {
        this.triggerClient = triggerClient;
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
        this.triggerService = triggerService;
        this.productionRunResolver = productionRunResolver;
        this.webhookResponseRegistry = webhookResponseRegistry;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Dispatch a webhook call to the appropriate workflow run.
     *
     * Multi-DAG flow:
     * 1. Find webhook token entity (contains workflowId AND triggerId)
     * 2. Find run in WAITING_TRIGGER status for that workflow
     * 3. If found, resolve the specific trigger and resume execution
     * 4. If not found, return not_active response
     *
     * @param token   The webhook token
     * @param payload The request payload
     * @param sync    Whether to wait for completion (ignored in V1)
     * @return WebhookResponse with execution status
     */
    public WebhookResponse dispatch(String token, Map<String, Object> payload, boolean sync) {
        // 0. Per-token sliding window, BEFORE anything that costs a lookup, a credit check or a
        //    run. The only per-fire gate used to be a boolean credit check, so an unauthenticated
        //    caller who knows one token could fire a workflow as fast as the network allowed;
        //    the `rate_limited` response existed and was mapped to 429 but had no producer at all
        //    (LC-042). Checking here also covers the standalone fallback below, which is reached
        //    through this same entry point.
        if (!rateLimiter.allowToken(token)) {
            logger.warn("Webhook rate limit hit for token {}... ({} fires/{}s)",
                    token != null ? token.substring(0, Math.min(8, token.length())) : "null",
                    rateLimiter.maxFiresPerToken(), WebhookRateLimiter.WINDOW.getSeconds());
            return WebhookResponse.rateLimited();
        }

        // 1. Find token entity (new multi-DAG table or legacy fallback)
        WebhookTokenDto tokenDto = triggerClient.findByToken(token);
        if (tokenDto == null) {
            // Fallback: try standalone webhook dispatch
            return dispatchStandalone(token, payload, sync);
        }

        UUID workflowId = tokenDto.getWorkflowId();
        String triggerId = tokenDto.getTriggerId();

        logger.info("Webhook call for workflow {} trigger {}: {}...",
                   workflowId, triggerId,
                   token.substring(0, Math.min(12, token.length())));

        // 2. Strict pin enforcement: production webhooks fire ONLY on the
        // workflow's pinned_version. Resolution is centralized in
        // ProductionRunResolver. The workflow lookup below is kept only because
        // the surrounding accumulation logic needs the WorkflowEntity reference;
        // the resolver re-loads it internally (see M3 in audit - minor 1 extra
        // DB hit, deferred for cleanup).
        WorkflowEntity workflow = workflowRepository.findById(workflowId).orElse(null);
        if (workflow == null) {
            logger.warn("Workflow {} not found for webhook dispatch", workflowId);
            return WebhookResponse.notFound();
        }

        // Per-owner cap, applied once the owner is known. The per-token window alone is not
        // enough: an owner with fifty webhook tokens gets fifty times the budget, and it is the
        // OWNER's orchestrator capacity and credits that are being spent (LC-042).
        if (!rateLimiter.allowOwner(workflow.getTenantId())) {
            logger.warn("Webhook rate limit hit for owner {} ({} fires/{}s across all their tokens)",
                    workflow.getTenantId(), rateLimiter.maxFiresPerOwner(),
                    WebhookRateLimiter.WINDOW.getSeconds());
            return WebhookResponse.rateLimited();
        }

        // Centralized: production webhook fires ONLY on the workflow's pinned version.
        // The unpinned fallback was removed (made prod behavior non-deterministic).
        ProductionRunResolver.Resolution resolution = productionRunResolver.resolve(workflowId, com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);
        if (!resolution.isFound()) {
            if (resolution.isNotPinned()) {
                logger.warn("Webhook for workflow {} refused: no pinned version. " +
                    "Pin a production version to enable webhook triggers.", workflowId);
                return WebhookResponse.notActive();
            }
            logger.info("No active run for webhook trigger for workflow {} ({})",
                workflowId, resolution.outcome());
            return WebhookResponse.notActive();
        }
        WorkflowRunEntity waitingRun = resolution.run().get();

        // PR22 R2 - workspace-scope guard on the pinned/multi-DAG webhook fire path.
        // The token was created in some workspace (WebhookTokenDto.organizationId).
        // The pinned workflow_run was created in some workspace (PR15 V209). A token
        // tagged for one scope MUST NOT fire a run in a different scope, even if both
        // happen to share the same tenant and workflow id. NULL on either side means
        // personal scope; both must agree.
        // When WebhookTokenDto.organizationId is null (legacy tokens pre-PR22), the
        // pinned run's org_id IS the canonical fire scope - we accept that mapping
        // by-passing the strict equality below, since refusing all legacy fires would
        // break every in-flight production trigger. Going forward (post-PR22 deploy),
        // newly-created tokens will carry the org_id explicitly.
        String tokenOrg = tokenDto.getOrganizationId();
        if (tokenOrg != null && !tokenOrg.isBlank()) {
            String runOrg = waitingRun.getOrgId();
            if (!ScopeGuard.crossResourceMatches(tokenOrg, runOrg)) {
                logger.info("Skipping pinned webhook for workflow {} - workspace mismatch "
                    + "(token org={}, run org={})", workflowId, tokenOrg, runOrg);
                return WebhookResponse.notActive();
            }
        }

        // Reject every terminal status (COMPLETED|FAILED|PARTIAL_SUCCESS|CANCELLED|TIMEOUT|SKIPPED).
        // Mirrors TriggerController: in normal flow resetForNextCycle transitions a finishing
        // cycle to WAITING_TRIGGER (cycleResult goes to metadata only), so a webhook that lands
        // on a run with a terminal status means the cycle never reset - typically because the
        // JVM crashed mid-execution. Re-firing webhooks on such a run reopens a new epoch each
        // time and re-triggers the same crash; that is the loop that produced the 73-epoch
        // accumulation on run_<id> (prod OOM 2026-05-07 12:40 UTC). A truly
        // terminal run requires explicit reactivation before accepting fires again.
        RunStatus runStatus = waitingRun.getStatus();
        if (runStatus != null && runStatus.isTerminal()) {
            logger.info("Latest run {} for workflow {} is terminal ({}), rejecting webhook",
                    waitingRun.getRunIdPublic(), workflowId, runStatus);
            return WebhookResponse.notActive();
        }
        String runId = waitingRun.getRunIdPublic();

        // 3. No credit gate here: an out-of-credit webhook fire must leave a trace the
        //    owner can find. The fire proceeds and NodeCreditGate fails the trigger node
        //    with the out-of-credit message, skipping the rest of the workflow.

        logger.info("Found waiting run {} for workflow {}, resolving trigger {}",
                   runId, workflowId, triggerId);
        if (sync) {
            // Sync-run ceiling. A sync fire occupies a request for up to the sync timeout: first
            // the calling thread, which blocks inside executeTrigger below, then a parked
            // DeferredResult (and the servlet async context behind it) until a respond node
            // answers or the timeout fires. The rate window alone does not bound how many are
            // occupied AT ONCE (LC-042).
            //
            // The slot is held for exactly that union and released when it ends - see
            // syncRequestStillParked. It is scoped per OWNER first so a tenant whose sync
            // webhooks never answer spends only their own ceiling; the platform-wide number is a
            // saturation backstop.
            if (!rateLimiter.acquireSyncSlot(runId, workflow.getTenantId(), this::syncRequestStillParked)) {
                logger.warn("Refusing sync webhook for run {} (owner {}): sync-run ceiling reached "
                                + "({} held platform-wide, {} per owner, {} total)",
                        runId, workflow.getTenantId(), rateLimiter.heldSyncSlots(),
                        rateLimiter.maxConcurrentSyncPerOwner(), rateLimiter.maxConcurrentSync());
                return WebhookResponse.rateLimited();
            }
            webhookResponseRegistry.expect(runId);
            // Marks the first half of the occupancy: this thread is about to block in
            // executeTrigger, before any DeferredResult exists for the registry to report.
            syncDispatchInFlight.add(runId);
        }

        // 4. Delegate to ReusableTriggerService.
        // Webhook bodies are untrusted external - strip the internal plan-control marker.
        Map<String, Object> sanitizedPayload = com.apimarketplace.orchestrator.trigger
                .ReusableTriggerService.sanitizePlanMarker(payload);
        try {
            TriggerExecutionResult result = triggerService.executeTrigger(
                waitingRun, triggerId, TriggerType.WEBHOOK, sanitizedPayload);

            if (result.success()) {
                return WebhookResponse.triggered(runId);
            } else {
                rateLimiter.releaseSyncSlot(runId);
                webhookResponseRegistry.cancelExpectation(runId);
                // The documented 402 for an out-of-credit webhook is preserved, but it
                // is now derived from the trigger node's real failure instead of a
                // pre-execution gate - so the caller still gets 402 AND the owner gets
                // a failed trigger + skipped nodes to look at.
                if (CreditExhaustion.isCreditExhausted(result.message())) {
                    return WebhookResponse.insufficientCredits();
                }
                return WebhookResponse.error(result.message());
            }
        } catch (Exception e) {
            rateLimiter.releaseSyncSlot(runId);
            webhookResponseRegistry.cancelExpectation(runId);
            logger.error("Failed to resolve webhook trigger for run {}: {}",
                        runId, e.getMessage(), e);
            return WebhookResponse.error("Failed to trigger workflow: " + e.getMessage());
        } finally {
            if (sync) {
                // This thread is done blocking. From here the occupancy, if any, is the
                // DeferredResult the controller is about to register, which the registry reports.
                syncDispatchInFlight.remove(runId);
            }
        }
    }

    /**
     * Whether the sync request for {@code runId} still occupies a request.
     *
     * <p>This is what makes the sync ceiling a measure of concurrency rather than of fires per
     * TTL. A sync request occupies a request in two consecutive phases and this service can
     * observe both: while {@code executeTrigger} blocks the calling thread it is in
     * {@link #syncDispatchInFlight}, and once the controller has parked its {@code DeferredResult}
     * the registry reports it {@link WebhookResponseRegistry#hasPending pending}. When neither
     * holds, the caller has been answered and the slot is free - a sync webhook that answers in
     * 200ms no longer costs its owner a slot for the whole TTL.
     *
     * <p>The handoff between the two phases (this method returning, the controller registering)
     * is a few method returns with no I/O in between, which the reclaim grace covers; see
     * {@link WebhookRateLimiter#SETTLE_GRACE}.
     */
    private boolean syncRequestStillParked(String runId) {
        return syncDispatchInFlight.contains(runId) || webhookResponseRegistry.hasPending(runId);
    }

    /**
     * Dispatch a webhook call via standalone webhook.
     * Finds all workflows whose trigger references this webhookId, then triggers those with waiting runs.
     */
    private WebhookResponse dispatchStandalone(String token, Map<String, Object> payload, boolean sync) {
        StandaloneWebhookDto standaloneWebhook = triggerClient.findStandaloneByToken(token);
        if (standaloneWebhook == null) {
            String tokenPreview = token != null ? token.substring(0, Math.min(8, token.length())) + "..." : "null";
            logger.warn("Webhook token not found (legacy or standalone): {}", tokenPreview);
            return WebhookResponse.notFound();
        }

        if (!Boolean.TRUE.equals(standaloneWebhook.getIsActive())) {
            triggerClient.logWebhookCall(standaloneWebhook.getId(), null, null, payload, "inactive", 0);
            return WebhookResponse.notActive();
        }

        String webhookIdStr = standaloneWebhook.getId().toString();
        String tenantId = standaloneWebhook.getTenantId();
        String webhookOrgIdScope = standaloneWebhook.getOrganizationId();

        // Per-owner cap, same contract as the pinned branch. This path is worse per fire than
        // that one: it scans the owner's workflows and can trigger SEVERAL runs from a single
        // request, so the owner budget matters more here, not less (LC-042).
        if (!rateLimiter.allowOwner(tenantId)) {
            logger.warn("Standalone webhook rate limit hit for owner {} ({} fires/{}s)",
                    tenantId, rateLimiter.maxFiresPerOwner(), WebhookRateLimiter.WINDOW.getSeconds());
            return WebhookResponse.rateLimited();
        }

        // BATCH-B (2026-05-20) - strict-org scoping. The legacy
        // findByTenantId(tenantId) returned every workflow the user owns across
        // every workspace they belong to, so a standalone webhook in workspace A
        // could fire workflows wired into workspace B (cross-org webhook misroute,
        // CRITICAL). The strict-org finder narrows the candidate set to the
        // webhook's own workspace; the {@code ScopeGuard.crossResourceMatches}
        // post-filter below (see ~line 250) remains as the second layer of defence
        // when the webhook itself was personal-scope (org_id = null).
        List<WorkflowEntity> workflows = webhookOrgIdScope != null
                ? workflowRepository.findByOrganizationIdStrict(webhookOrgIdScope)
                : workflowRepository.findByTenantId(tenantId);
        int triggeredCount = 0;

        for (WorkflowEntity workflow : workflows) {
            if (workflow.getPlan() == null) continue;

            try {
                // First pass: check current workflow plan for webhook reference
                WorkflowPlan currentPlan = WorkflowPlan.fromMap(workflow.getPlan());
                String matchedTriggerId = null;
                for (Trigger trigger : currentPlan.getTriggers()) {
                    if (!"webhook".equals(trigger.type())) continue;
                    Map<String, Object> params = trigger.params();
                    if (params == null) continue;
                    String refWebhookId = params.get("webhookId") != null
                            ? params.get("webhookId").toString() : null;
                    if (webhookIdStr.equals(refWebhookId)) {
                        matchedTriggerId = trigger.getNormalizedKey();
                        break;
                    }
                }
                if (matchedTriggerId == null) continue;

                // Found a matching trigger - production webhook MUST run on the
                // pinned version. The unpinned fallback was removed.
                ProductionRunResolver.Resolution standaloneRes =
                    productionRunResolver.resolve(workflow.getId(), com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);
                if (standaloneRes.isFound()) {
                        WorkflowRunEntity waitingRun = standaloneRes.run().get();

                        // PR22 - workspace-scope guard. The webhook is tagged for a workspace
                        // (organization_id NULL = personal, non-null = team). The pinned
                        // workflow_run was created in some workspace too (PR15 V209). A
                        // webhook MUST NOT fire a workflow that lives in a different
                        // workspace: same tenant, but different scope = cross-scope leak.
                        // org_id NULL on either side = personal-scope match required.
                        String webhookOrg = standaloneWebhook.getOrganizationId();
                        String runOrg = waitingRun.getOrgId();
                        if (!ScopeGuard.crossResourceMatches(webhookOrg, runOrg)) {
                            logger.info("Skipping standalone webhook for workflow {} - workspace mismatch "
                                + "(webhook org={}, run org={})", workflow.getId(), webhookOrg, runOrg);
                            continue;
                        }

                        // Reject every terminal status - same contract as the pinned-trigger
                        // branch above (line ~130) and as DatasourceTriggerDispatchService /
                        // WorkflowTriggerDispatchService / TriggerController. A standalone
                        // webhook landing on a terminal run typically means the JVM crashed
                        // mid-cycle and resetForNextCycle never reset to WAITING_TRIGGER -
                        // re-firing reopens a new epoch and re-triggers the crash.
                        RunStatus standaloneRunStatus = waitingRun.getStatus();
                        if (standaloneRunStatus != null && standaloneRunStatus.isTerminal()) {
                            // Mirror the pinned-branch log so a tenant with multiple workflows
                            // can still see which terminal run blocked the standalone fire.
                            logger.info("Latest run {} for workflow {} is terminal ({}), skipping standalone webhook fire",
                                waitingRun.getRunIdPublic(), workflow.getId(), standaloneRunStatus);
                            continue;
                        }

                        // No credit gate - same reason as the pinned branch above: the
                        // fire proceeds so the failure is visible on the trigger node.

                        try {
                            // Webhook bodies are untrusted external - strip the internal marker.
                            Map<String, Object> sanitizedPayload2 = com.apimarketplace.orchestrator.trigger
                                    .ReusableTriggerService.sanitizePlanMarker(payload);
                            TriggerExecutionResult result = triggerService.executeTrigger(
                                    waitingRun, matchedTriggerId, TriggerType.WEBHOOK, sanitizedPayload2);
                            if (result.success()) {
                                triggeredCount++;
                            }
                        } catch (Exception e) {
                            logger.error("Failed to trigger workflow {} via standalone webhook: {}",
                                    workflow.getId(), e.getMessage());
                        }
                    }
            } catch (Exception e) {
                logger.warn("Failed to parse plan for workflow {}: {}", workflow.getId(), e.getMessage());
            }
        }

        String status = triggeredCount > 0 ? "triggered" : "no_active_workflow";
        triggerClient.logWebhookCall(standaloneWebhook.getId(), null, null, payload, status, triggeredCount);

        if (triggeredCount > 0) {
            return WebhookResponse.triggered(triggeredCount + " workflow(s)");
        } else {
            return WebhookResponse.notActive();
        }
    }

    /**
     * Get webhook configuration by token.
     * Retrieves the webhook config from the workflow's plan for the specific trigger,
     * or from a standalone webhook entity.
     *
     * @param token The webhook token
     * @return WebhookConfig or null if not found
     */
    /**
     * Sliding-window counters for the webhook fire path.
     *
     * <p>Deliberately in-process rather than Redis-backed: the fire path already runs entirely
     * in this service, and an in-memory window is a correct (if per-replica) bound, whereas
     * adding a Redis round trip to the hot path would put a network dependency in front of every
     * customer webhook. With N replicas the effective ceiling is N times these numbers, which is
     * still a bound where there was none.
     *
     * <p>The window is a timestamp deque per key, not a fixed bucket: a fixed bucket lets a
     * caller fire the full budget in the last instant of one bucket and again in the first
     * instant of the next, which is exactly the burst this is meant to stop.
     */
    static class WebhookRateLimiter {

        /** Fires one token may make per {@link #WINDOW}. Well above any provider's retry cadence. */
        static final int DEFAULT_MAX_FIRES_PER_TOKEN = 120;
        /** Fires one owner may make per {@link #WINDOW} across every token they own. */
        static final int DEFAULT_MAX_FIRES_PER_OWNER = 600;
        /**
         * Sync runs ONE OWNER may hold at a time. This is the ceiling that actually protects
         * anything: it is what stops a single tenant whose sync webhooks never reach a respond
         * node from consuming the shared budget, and it cannot take another tenant's webhooks
         * down with it.
         */
        static final int DEFAULT_MAX_CONCURRENT_SYNC_PER_OWNER = 20;
        /**
         * Platform-wide backstop across all owners, sized so it is reached only when the process
         * really is saturated with parked sync requests. Deliberately far above the per-owner
         * ceiling: a global number low enough to bite in normal operation would mean one busy
         * tenant 429s every other tenant's sync webhooks, which is a worse outage than the abuse
         * it prevents.
         */
        static final int DEFAULT_MAX_CONCURRENT_SYNC = 200;
        static final java.time.Duration WINDOW = java.time.Duration.ofMinutes(1);
        /**
         * BACKSTOP for how long a sync slot may stay held. The slot is normally released as soon
         * as the request it represents is answered (the {@code stillParked} probe passed to
         * {@link #acquireSyncSlot}); the TTL only catches a slot whose probe never goes false,
         * which means an occupancy nobody ever ended.
         *
         * <p>It used to be the ONLY reclaim, which made the ceiling count "distinct sync runs
         * STARTED within the TTL" rather than requests occupied at once: a sync webhook answered
         * in 200ms still cost its owner a slot for 90 seconds.
         */
        static final java.time.Duration DEFAULT_SYNC_SLOT_TTL = java.time.Duration.ofSeconds(90);

        /**
         * A slot younger than this is never reclaimed on the probe, only on the TTL.
         *
         * <p>The probe reads two sources that hand over to each other: the dispatch thread stops
         * reporting the run the instant it returns, and the controller starts reporting it a few
         * method returns later when it parks the DeferredResult. Nothing does I/O in between, so
         * any observation of "not occupied" that is younger than this grace is the handoff, not
         * an answered request. Erring here is cheap in one direction only: too short would
         * reclaim a live request's slot (over-admitting), too long only delays a release.
         */
        static final java.time.Duration SETTLE_GRACE = java.time.Duration.ofSeconds(2);

        private final Clock clock;
        private final int maxFiresPerToken;
        private final int maxFiresPerOwner;
        private final int maxConcurrentSyncPerOwner;
        private final int maxConcurrentSync;
        private final java.time.Duration syncSlotTtl;
        private final Map<String, Deque<Long>> tokenWindows = new ConcurrentHashMap<>();
        private final Map<String, Deque<Long>> ownerWindows = new ConcurrentHashMap<>();
        private final Map<String, SyncSlot> syncSlots = new ConcurrentHashMap<>();
        /** Amortises the quiet-key sweep: see {@link #allow}. */
        private final java.util.concurrent.atomic.AtomicInteger sinceSweep =
                new java.util.concurrent.atomic.AtomicInteger();

        private static final int SWEEP_EVERY_N_FIRES = 256;

        /** One held sync run: when it was acquired and who owns it. */
        private record SyncSlot(long acquiredAtMillis, String ownerId) {}

        WebhookRateLimiter(Clock clock) {
            this(clock, DEFAULT_MAX_FIRES_PER_TOKEN, DEFAULT_MAX_FIRES_PER_OWNER,
                    DEFAULT_MAX_CONCURRENT_SYNC_PER_OWNER, DEFAULT_MAX_CONCURRENT_SYNC,
                    DEFAULT_SYNC_SLOT_TTL);
        }

        WebhookRateLimiter(Clock clock, int maxFiresPerToken, int maxFiresPerOwner,
                           int maxConcurrentSyncPerOwner, int maxConcurrentSync,
                           java.time.Duration syncSlotTtl) {
            this.clock = clock;
            // A non-positive configured value would disable the guard silently, which is the one
            // outcome a limit must never have: fall back to the shipped default instead.
            this.maxFiresPerToken = maxFiresPerToken > 0 ? maxFiresPerToken : DEFAULT_MAX_FIRES_PER_TOKEN;
            this.maxFiresPerOwner = maxFiresPerOwner > 0 ? maxFiresPerOwner : DEFAULT_MAX_FIRES_PER_OWNER;
            this.maxConcurrentSyncPerOwner = maxConcurrentSyncPerOwner > 0
                    ? maxConcurrentSyncPerOwner : DEFAULT_MAX_CONCURRENT_SYNC_PER_OWNER;
            this.maxConcurrentSync = maxConcurrentSync > 0 ? maxConcurrentSync : DEFAULT_MAX_CONCURRENT_SYNC;
            this.syncSlotTtl = syncSlotTtl != null && !syncSlotTtl.isNegative() && !syncSlotTtl.isZero()
                    ? syncSlotTtl : DEFAULT_SYNC_SLOT_TTL;
        }

        int maxFiresPerToken() {
            return maxFiresPerToken;
        }

        int maxFiresPerOwner() {
            return maxFiresPerOwner;
        }

        int maxConcurrentSyncPerOwner() {
            return maxConcurrentSyncPerOwner;
        }

        int maxConcurrentSync() {
            return maxConcurrentSync;
        }

        java.time.Duration syncSlotTtl() {
            return syncSlotTtl;
        }

        /** Cluster-wide counter; {@code null} answer means "unavailable, use the local window". */
        interface ClusterFireCounter {
            Long hit(String key, java.time.Duration window, long nowMillis);
        }

        private ClusterFireCounter clusterCounter;

        /** Makes the fire windows cluster-wide (null keeps them in-memory). */
        WebhookRateLimiter withClusterCounter(ClusterFireCounter counter) {
            this.clusterCounter = counter;
            return this;
        }

        boolean allowToken(String token) {
            String key = token == null ? "" : token;
            Boolean cluster = clusterAllows("token:" + sha256(key), maxFiresPerToken);
            return cluster != null ? cluster : allow(tokenWindows, key, maxFiresPerToken);
        }

        /** The cluster verdict, or {@code null} when there is no cluster counter or it failed. */
        private Boolean clusterAllows(String key, int limit) {
            if (clusterCounter == null) {
                return null;
            }
            Long count = clusterCounter.hit(key, WINDOW, clock.millis());
            return count == null ? null : count <= limit;
        }

        private static String sha256(String value) {
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return java.util.HexFormat.of().formatHex(digest);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        boolean allowOwner(String ownerId) {
            // No owner (legacy row with no tenant) means no owner budget to spend against; the
            // per-token window above still applies, so this is not an unguarded path.
            if (ownerId == null || ownerId.isBlank()) {
                return true;
            }
            Boolean cluster = clusterAllows("owner:" + ownerId, maxFiresPerOwner);
            return cluster != null ? cluster : allow(ownerWindows, ownerId, maxFiresPerOwner);
        }

        /**
         * Take a sync slot for {@code runId}, owned by {@code ownerId}, with no way to observe
         * when the request ends: every held slot then lives until the TTL. Tests of the ceiling
         * arithmetic itself use this; the fire path uses the probing overload.
         */
        boolean acquireSyncSlot(String runId, String ownerId) {
            return acquireSyncSlot(runId, ownerId, held -> true);
        }

        /**
         * Take a sync slot for {@code runId}, owned by {@code ownerId}.
         *
         * @param stillParked answers, for a run that holds a slot, whether its request is still
         *                    occupied. Slots it reports free (for longer than {@link #SETTLE_GRACE})
         *                    are reclaimed before the ceilings are tested, which is what makes
         *                    this a concurrency ceiling instead of a fires-per-TTL one.
         * @return false when the owner's ceiling, or the platform-wide backstop, is reached.
         */
        boolean acquireSyncSlot(String runId, String ownerId, java.util.function.Predicate<String> stillParked) {
            long now = clock.millis();
            reclaimSettledSlots(now, stillParked);
            if (runId == null || runId.isBlank()) {
                return true;
            }
            // A repeat fire on a run that already holds a slot re-arms the same slot rather than
            // consuming a second one: one parked DeferredResult per run is the real resource.
            SyncSlot existing = syncSlots.get(runId);
            if (existing != null) {
                syncSlots.put(runId, new SyncSlot(now, existing.ownerId()));
                return true;
            }
            if (syncSlots.size() >= maxConcurrentSync) {
                return false;
            }
            if (ownerId != null && !ownerId.isBlank()) {
                long held = syncSlots.values().stream()
                        .filter(slot -> ownerId.equals(slot.ownerId()))
                        .count();
                if (held >= maxConcurrentSyncPerOwner) {
                    return false;
                }
            }
            syncSlots.put(runId, new SyncSlot(now, ownerId));
            return true;
        }

        void releaseSyncSlot(String runId) {
            if (runId != null) {
                syncSlots.remove(runId);
            }
        }

        /**
         * Drop every slot whose request has ended, plus any that outlived the TTL backstop.
         *
         * <p>Runs on the acquire path only: a slot costs nothing until someone needs one, so
         * there is no timer to schedule and no state to keep between requests. Removal is
         * value-conditional ({@code remove(key, value)}) so a slot re-armed by a concurrent
         * repeat fire is not dropped by this sweep.
         */
        private void reclaimSettledSlots(long now, java.util.function.Predicate<String> stillParked) {
            for (Map.Entry<String, SyncSlot> entry : syncSlots.entrySet()) {
                SyncSlot slot = entry.getValue();
                long age = now - slot.acquiredAtMillis();
                if (age > syncSlotTtl.toMillis()) {
                    syncSlots.remove(entry.getKey(), slot);
                    continue;
                }
                if (age > SETTLE_GRACE.toMillis() && !stillParked.test(entry.getKey())) {
                    syncSlots.remove(entry.getKey(), slot);
                }
            }
        }

        /** Slots currently held, for tests and for the refusal log line. */
        int heldSyncSlots() {
            return syncSlots.size();
        }

        /**
         * Expire, test and record one hit atomically for {@code key}.
         *
         * <p>The whole read-modify-write runs inside {@code ConcurrentHashMap.compute}, which
         * holds the key's bin lock, so it is serialised against the sweep below. The previous
         * shape (a {@code computeIfAbsent} then a monitor on the returned deque) left a window
         * in which the sweep could drop a deque a concurrent caller had just obtained and not
         * yet appended to, silently losing that hit and its budget.
         */
        private boolean allow(Map<String, Deque<Long>> windows, String key, int limit) {
            long now = clock.millis();
            long cutoff = now - WINDOW.toMillis();
            boolean[] allowed = new boolean[1];
            windows.compute(key, (k, existing) -> {
                Deque<Long> hits = existing != null ? existing : new ArrayDeque<>();
                while (!hits.isEmpty() && hits.peekFirst() <= cutoff) {
                    hits.pollFirst();
                }
                if (hits.size() >= limit) {
                    allowed[0] = false;
                } else {
                    hits.addLast(now);
                    allowed[0] = true;
                }
                return hits.isEmpty() ? null : hits;
            });
            if (allowed[0]) {
                sweepQuietKeys(windows, cutoff);
            }
            return allowed[0];
        }

        /**
         * Drop keys that went quiet so a caller cycling through tokens cannot grow the map
         * without bound.
         *
         * <p>Amortised, not per fire: the sweep is O(n) over an attacker-influenced map (the
         * token window is keyed on the raw caller-supplied token, before any lookup validates
         * it), so running it on every accepted fire put an attacker-scaled cost on the hot path.
         *
         * <p>Removal goes through {@code computeIfPresent}, i.e. under the same bin lock
         * {@link #allow} mutates the deque with, so a key cannot be dropped in between a
         * concurrent caller's expire and append.
         */
        private void sweepQuietKeys(Map<String, Deque<Long>> windows, long cutoff) {
            if (sinceSweep.incrementAndGet() < SWEEP_EVERY_N_FIRES) {
                return;
            }
            sinceSweep.set(0);
            for (String key : List.copyOf(windows.keySet())) {
                windows.computeIfPresent(key, (k, d) -> d.isEmpty() || d.peekLast() <= cutoff ? null : d);
            }
        }
    }

    public WebhookConfig getWebhookConfigByToken(String token) {
        // First try legacy webhook_tokens table
        WebhookTokenDto tokenDto = triggerClient.findByToken(token);
        if (tokenDto != null) {
            String triggerId = tokenDto.getTriggerId();

            Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(tokenDto.getWorkflowId());
            if (workflowOpt.isEmpty()) {
                return null;
            }

            WorkflowEntity workflow = workflowOpt.get();
            Map<String, Object> planMap = workflow.getPlan();

            if (planMap == null) {
                return WebhookConfig.defaults();
            }

            try {
                WorkflowPlan plan = WorkflowPlan.fromMap(planMap);
                for (Trigger trigger : plan.getTriggers()) {
                    if ("webhook".equals(trigger.type()) && triggerId.equals(trigger.getNormalizedKey())) {
                        return WebhookConfig.fromTriggerParams(trigger.params());
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to parse workflow plan for webhook config: {}", e.getMessage());
            }

            return WebhookConfig.defaults();
        }

        // Fallback: try standalone webhooks
        StandaloneWebhookDto standaloneDto = triggerClient.findStandaloneByToken(token);
        if (standaloneDto != null) {
            Map<String, String> decryptedAuth = triggerClient.getDecryptedAuthConfig(standaloneDto.getId());
            return WebhookConfig.fromStandaloneWebhook(standaloneDto, decryptedAuth);
        }

        return null;
    }

}

package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.common.credit.CreditConsumptionClient;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    private final CreditConsumptionClient creditClient;
    private final WebhookResponseRegistry webhookResponseRegistry;

    public WebhookDispatchService(TriggerClient triggerClient,
                                  WorkflowRepository workflowRepository,
                                  WorkflowRunRepository runRepository,
                                  ReusableTriggerService triggerService,
                                  ProductionRunResolver productionRunResolver,
                                  CreditConsumptionClient creditClient,
                                  WebhookResponseRegistry webhookResponseRegistry) {
        this.triggerClient = triggerClient;
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
        this.triggerService = triggerService;
        this.productionRunResolver = productionRunResolver;
        this.creditClient = creditClient;
        this.webhookResponseRegistry = webhookResponseRegistry;
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

        // 3. Credit check
        if (!creditClient.checkCredits(waitingRun.getTenantId())) {
            logger.warn("Insufficient credits for tenant {}, rejecting webhook trigger for run {}",
                    waitingRun.getTenantId(), runId);
            return WebhookResponse.insufficientCredits();
        }

        logger.info("Found waiting run {} for workflow {}, resolving trigger {}",
                   runId, workflowId, triggerId);
        if (sync) {
            webhookResponseRegistry.expect(runId);
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
                webhookResponseRegistry.cancelExpectation(runId);
                return WebhookResponse.error(result.message());
            }
        } catch (Exception e) {
            webhookResponseRegistry.cancelExpectation(runId);
            logger.error("Failed to resolve webhook trigger for run {}: {}",
                        runId, e.getMessage(), e);
            return WebhookResponse.error("Failed to trigger workflow: " + e.getMessage());
        }
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

                        if (!creditClient.checkCredits(waitingRun.getTenantId())) {
                            logger.warn("Insufficient credits for tenant {}, skipping standalone webhook trigger",
                                    waitingRun.getTenantId());
                            continue;
                        }

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

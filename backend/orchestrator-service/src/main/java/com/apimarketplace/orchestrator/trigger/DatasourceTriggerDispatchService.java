package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.services.triggers.TriggerUserResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dispatch a row event (from trigger-service) onto the workflow's pinned run.
 *
 * Flow mirrors {@link com.apimarketplace.orchestrator.webhook.WebhookDispatchService}:
 *   1. Resolve the production run via {@link ProductionRunResolver}
 *      (enforces {@code pinned_version} - same contract as webhook/schedule).
 *   2. Reject cancelled/timeout runs.
 *   3. Check credits.
 *   4. Delegate to {@link ReusableTriggerService#executeTrigger} with
 *      {@link TriggerType#DATASOURCE}.
 *
 * trigger-service has already filtered subscriptions by dataSourceId, eventType
 * and the declarative filter, so this service only cares about the target
 * workflow/trigger and routing onto the right run.
 */
@Service
public class DatasourceTriggerDispatchService {

    private static final Logger log = LoggerFactory.getLogger(DatasourceTriggerDispatchService.class);

    private final ProductionRunResolver productionRunResolver;
    private final ReusableTriggerService triggerService;
    private final CreditConsumptionClient creditClient;
    private final TriggerUserResolver triggerUserResolver;

    public DatasourceTriggerDispatchService(ProductionRunResolver productionRunResolver,
                                            ReusableTriggerService triggerService,
                                            CreditConsumptionClient creditClient,
                                            TriggerUserResolver triggerUserResolver) {
        this.productionRunResolver = productionRunResolver;
        this.triggerService = triggerService;
        this.creditClient = creditClient;
        this.triggerUserResolver = triggerUserResolver;
    }

    public DispatchResult dispatch(UUID workflowId, String triggerId, String eventType,
                                   Long dataSourceId, Long rowId,
                                   Map<String, Object> row, Map<String, Object> previousRow,
                                   Instant triggeredAt) {
        return dispatch(workflowId, triggerId, eventType, dataSourceId, rowId,
                row, previousRow, triggeredAt, null);
    }

    /**
     * Workspace-scoped overload (2026-05-18). {@code eventOrgId} is the
     * organization that produced the row event (from
     * {@code DatasourceEventDispatchRequest.organizationId}); the dispatcher
     * refuses to fan-out when it disagrees with the matched workflow's own
     * {@code organization_id}, mirroring the guard in
     * {@link com.apimarketplace.orchestrator.webhook.WebhookDispatchService}
     * and {@link WorkflowTriggerDispatchService#dispatchWorkflowCompletion}.
     * Backwards-compatible: null/blank {@code eventOrgId} skips the guard,
     * matching legacy single-arg callers.
     */
    public DispatchResult dispatch(UUID workflowId, String triggerId, String eventType,
                                   Long dataSourceId, Long rowId,
                                   Map<String, Object> row, Map<String, Object> previousRow,
                                   Instant triggeredAt, String eventOrgId) {
        if (workflowId == null || triggerId == null || eventType == null) {
            return DispatchResult.malformed();
        }

        ProductionRunResolver.Resolution resolution = productionRunResolver.resolve(workflowId, com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);
        if (!resolution.isFound()) {
            if (resolution.isNotPinned()) {
                log.warn("Datasource trigger for workflow {} refused: no pinned version", workflowId);
                return DispatchResult.notPinned();
            }
            log.info("Datasource trigger for workflow {} skipped: {}", workflowId, resolution.outcome());
            return DispatchResult.noRun(resolution.outcome().name());
        }

        WorkflowRunEntity run = resolution.run().orElseThrow();
        // Workspace-scope guard: refuse to fan out when the event's source
        // workspace disagrees with the target workflow's workspace. Closes
        // the cross-workspace data fan-out path that the audit flagged as
        // the highest-leverage remaining leak.
        if (eventOrgId != null && !eventOrgId.isBlank()) {
            String runOrgId = run.getOrganizationId();
            if (!com.apimarketplace.common.scope.ScopeGuard.crossResourceMatches(eventOrgId, runOrgId)) {
                log.warn("[SCOPE] Datasource trigger cross-workspace blocked: workflow={} eventOrg={} runOrg={}",
                        workflowId, eventOrgId, runOrgId);
                return DispatchResult.noRun("CROSS_WORKSPACE_REFUSED");
            }
        }
        RunStatus status = run.getStatus();
        // Reject every terminal status. Mirrors WebhookDispatchService and
        // TriggerController: in normal flow resetForNextCycle transitions a finishing
        // cycle to WAITING_TRIGGER, so a terminal status here means the cycle never
        // reset (typically a JVM crash mid-execution). Re-firing reopens a new epoch
        // and re-triggers the same crash - see prod 2026-05-07 12:40 UTC where
        // run_<id> accumulated 73 epochs before the user noticed.
        if (status != null && status.isTerminal()) {
            log.info("Run {} is terminal ({}), skipping datasource trigger",
                    run.getRunIdPublic(), status);
            return DispatchResult.runTerminal(status.name());
        }

        if (!creditClient.checkCredits(run.getTenantId())) {
            log.warn("Insufficient credits for tenant {}, skipping datasource trigger on run {}",
                    run.getTenantId(), run.getRunIdPublic());
            return DispatchResult.insufficientCredits();
        }

        // Datasource rows can contain arbitrary remote-sourced fields; strip
        // the internal plan-control marker as defense-in-depth.
        Map<String, Object> payload = ReusableTriggerService.sanitizePlanMarker(
                buildPayload(eventType, dataSourceId, rowId, row, previousRow, triggeredAt, run.getTenantId()));

        try {
            TriggerExecutionResult result = triggerService.executeTrigger(
                    run, triggerId, TriggerType.DATASOURCE, payload);
            if (result.success()) {
                return DispatchResult.fired(run.getRunIdPublic());
            }
            return DispatchResult.error(result.message());
        } catch (Exception e) {
            log.error("Failed to fire datasource trigger for run {}: {}",
                    run.getRunIdPublic(), e.getMessage(), e);
            return DispatchResult.error(e.getMessage());
        }
    }

    /**
     * Shape of the payload handed to {@code TableTriggerNode} via its trigger
     * payload. Keys here are what the node reads to populate its outputs
     * (row, previous_row, event_type, row_id, datasource_id, triggered_at, triggered_by),
     * and what SpEL users reach via {@code {{trigger.*}}}.
     *
     * <p>{@code triggered_by} is resolved via {@link TriggerUserResolver} so it
     * matches the contract documented in the tables-trigger schema (display name
     * of the workflow owner). Falls back to an empty string when the owner cannot
     * be resolved - consistent with all other trigger resolvers.
     */
    private Map<String, Object> buildPayload(String eventType, Long dataSourceId, Long rowId,
                                             Map<String, Object> row, Map<String, Object> previousRow,
                                             Instant triggeredAt, String tenantId) {
        Map<String, Object> payload = new HashMap<>();
        // Normalize the event type to lowercase (e.g., ROW_CREATED → row_created)
        payload.put("event_type", eventType.toLowerCase());
        payload.put("datasource_id", dataSourceId);
        payload.put("row_id", rowId);
        payload.put("row", row);
        payload.put("previous_row", previousRow);
        payload.put("triggered_at", triggeredAt != null ? triggeredAt.toString() : Instant.now().toString());
        // Resolve the workflow owner's display name for triggered_by - same contract as
        // ScheduleTriggerResolver, WebhookTriggerResolver etc. Empty string when unknown.
        payload.put("triggered_by", triggerUserResolver.resolveDisplayName(tenantId));
        // Flatten row columns to top level so {{trigger.column_name}} works.
        if (row != null) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                // Do not clobber the structured keys above.
                payload.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        return payload;
    }

    public record DispatchResult(boolean success, String status, String message, String runId) {
        public static DispatchResult fired(String runId) {
            return new DispatchResult(true, "fired", null, runId);
        }
        public static DispatchResult notPinned() {
            return new DispatchResult(false, "not_pinned", "Workflow has no pinned version", null);
        }
        public static DispatchResult noRun(String outcome) {
            return new DispatchResult(false, "no_run", "No active run (" + outcome + ")", null);
        }
        public static DispatchResult runTerminal(String status) {
            return new DispatchResult(false, "run_terminal", "Run in status " + status, null);
        }
        public static DispatchResult insufficientCredits() {
            return new DispatchResult(false, "insufficient_credits", "Tenant out of credits", null);
        }
        public static DispatchResult malformed() {
            return new DispatchResult(false, "malformed", "Missing workflowId/triggerId/eventType", null);
        }
        public static DispatchResult error(String message) {
            return new DispatchResult(false, "error", message, null);
        }
    }
}

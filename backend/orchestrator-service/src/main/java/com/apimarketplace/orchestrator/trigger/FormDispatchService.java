package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class FormDispatchService {

    private static final Logger logger = LoggerFactory.getLogger(FormDispatchService.class);

    /**
     * Engine-reserved fields that must not be overwritten by user form data.
     * If a form field uses one of these names, it is silently dropped and a WARN is emitted.
     */
    private static final Set<String> RESERVED_FORM_FIELDS = Set.of(
            "submission_id", "submitted_at", "triggered_at", "triggered_by", "form_data"
    );

    private final TriggerClient triggerClient;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository runRepository;
    private final ReusableTriggerService triggerService;
    private final ProductionRunResolver productionRunResolver;
    private final CreditConsumptionClient creditClient;
    private final com.apimarketplace.common.storage.url.PublicFileUrlBuilder publicFileUrlBuilder;

    public FormDispatchService(TriggerClient triggerClient,
                               WorkflowRepository workflowRepository,
                               WorkflowRunRepository runRepository,
                               ReusableTriggerService triggerService,
                               ProductionRunResolver productionRunResolver,
                               CreditConsumptionClient creditClient,
                               com.apimarketplace.common.storage.url.PublicFileUrlBuilder publicFileUrlBuilder) {
        this.triggerClient = triggerClient;
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
        this.triggerService = triggerService;
        this.productionRunResolver = productionRunResolver;
        this.creditClient = creditClient;
        this.publicFileUrlBuilder = publicFileUrlBuilder;
    }

    /**
     * Get public form config (field definitions, no secrets).
     */
    public Map<String, Object> getFormConfig(String token) {
        StandaloneFormEndpointDto endpoint = triggerClient.findFormEndpointByToken(token);
        if (endpoint == null) {
            throw new IllegalArgumentException("Form endpoint not found");
        }

        Map<String, Object> config = new HashMap<>();
        config.put("name", endpoint.getName());
        config.put("description", endpoint.getDescription());
        config.put("formConfig", endpoint.getFormConfig());
        config.put("successMessage", endpoint.getSuccessMessage());
        config.put("isActive", endpoint.getIsActive());
        return config;
    }

    /**
     * Submit form data and dispatch to linked workflow.
     */
    public Map<String, Object> submitForm(String token, Map<String, Object> formData, String ipAddress) {
        StandaloneFormEndpointDto endpoint = triggerClient.findFormEndpointByToken(token);
        if (endpoint == null) {
            throw new IllegalArgumentException("Form endpoint not found");
        }

        if (!Boolean.TRUE.equals(endpoint.getIsActive())) {
            triggerClient.logFormSubmission(endpoint.getId(), formData, "inactive", 0, ipAddress);
            throw new IllegalStateException("Form endpoint is not active");
        }

        int triggered = 0;
        try {
            WorkflowEntity workflow = endpoint.getWorkflowId() != null
                    ? workflowRepository.findById(endpoint.getWorkflowId()).orElse(null)
                    : null;
            if (workflow == null || workflow.getPlan() == null) {
                logger.warn("No workflow or plan found for form endpoint '{}' (workflowId={})",
                        endpoint.getName(), endpoint.getWorkflowId());
            } else {
                // Centralized: production form fires ONLY on the workflow's pinned version.
                ProductionRunResolver.Resolution resolution =
                    productionRunResolver.resolve(workflow.getId(), com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);
                if (resolution.isNotPinned()) {
                    // Do NOT fall through to a success response: with no pinned
                    // production version the form fires nothing, so returning
                    // "accepted" made the public form show a green success while
                    // silently running an empty epoch (F8). Surface a clear error
                    // via the existing 409 path (mirrors the inactive-endpoint
                    // case + WebhookDispatchService.notActive). The dedicated
                    // re-throw guard below keeps this out of the generic catch.
                    logger.warn("Form endpoint '{}' refused: workflow {} has no pinned version. " +
                        "Pin a production version to enable form triggers.",
                        endpoint.getName(), workflow.getId());
                    triggerClient.logFormSubmission(endpoint.getId(), formData, "no_pinned_version", 0, ipAddress);
                    throw new IllegalStateException(
                        "This form is not accepting submissions yet: its workflow has no published production version.");
                }
                if (resolution.isFound()) {
                    WorkflowRunEntity run = resolution.run().get();

                    // PR22 R2 - workspace-scope guard. Mirror WebhookDispatchService
                    // .dispatchStandalone (lines 218-232): the form endpoint is tagged for
                    // a workspace, and the pinned run is tagged for a workspace (PR15 V209).
                    // A form endpoint MUST NOT fire a workflow that lives in a different
                    // workspace, even if both share the same tenant.
                    String endpointOrg = endpoint.getOrganizationId();
                    String runOrg = run.getOrgId();
                    if (!com.apimarketplace.common.scope.ScopeGuard.crossResourceMatches(endpointOrg, runOrg)) {
                        logger.info("Skipping form dispatch for workflow {} - workspace mismatch "
                            + "(endpoint org={}, run org={})", workflow.getId(), endpointOrg, runOrg);
                        // fall through to log + return triggered=0 below
                    } else {

                    // Skip terminal runs
                    RunStatus runStatus = run.getStatus();
                    if (!runStatus.isTerminal()) {

                        // Use stored triggerId directly (same as webhook pattern)
                        String matchedTriggerId = endpoint.getTriggerId();

                        if (matchedTriggerId == null) {
                            logger.warn("No triggerId on form endpoint '{}' - link the endpoint to a workflow with a form trigger",
                                    endpoint.getName());
                        } else if (!creditClient.checkCredits(run.getTenantId())) {
                            logger.warn("Insufficient credits for tenant {}, skipping form dispatch", run.getTenantId());
                        } else {
                            Map<String, Object> payload = buildFormPayload(formData, endpoint.getId().toString(), endpoint.getName());

                            TriggerExecutionResult result = triggerService.executeTrigger(
                                    run, matchedTriggerId, TriggerType.FORM, payload);
                            if (result.success()) {
                                triggered = 1;
                                logger.info("Form submission dispatched to workflow run {} for endpoint '{}'",
                                        run.getId(), endpoint.getName());
                            }
                        }
                    }
                    } // end of orgMatch guard else-branch
                }
            }
        } catch (IllegalStateException e) {
            // Refusals with a user-facing reason (e.g. "no published production
            // version") must reach the controller's 409 handler verbatim, not be
            // squashed into a generic 500 by the catch-all below. Already logged.
            throw e;
        } catch (Exception e) {
            logger.error("Error dispatching form submission for endpoint '{}': {}",
                    endpoint.getName(), e.getMessage());
            triggerClient.logFormSubmission(endpoint.getId(), formData, "error", 0, ipAddress);
            throw new RuntimeException("Failed to process form submission");
        }

        triggerClient.logFormSubmission(endpoint.getId(), formData,
                triggered > 0 ? "triggered" : "no_waiting_run", triggered, ipAddress);

        Map<String, Object> result = new HashMap<>();
        result.put("status", triggered > 0 ? "triggered" : "accepted");
        result.put("workflowsTriggered", triggered);
        result.put("successMessage", endpoint.getSuccessMessage());
        return result;
    }

    /**
     * Build the canonical trigger payload for a form submission.
     *
     * Adds submission_id, submitted_at, form_data, and flattens any FileRef-typed
     * fields so that runtime SpEL templates resolve the same shape as the persisted DB output.
     * FormTriggerNodeSpec.customTransform can then pass through these fields unchanged.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildFormPayload(Map<String, Object> formData,
                                                  String endpointId, String endpointName) {
        String submissionId = UUID.randomUUID().toString();
        String submittedAt = Instant.now().toString();

        // Warn and drop any user field that collides with an engine-reserved key.
        // Engine fields MUST win (same contract as WorkflowTriggerResolver / TableTriggerNodeSpec).
        formData.keySet().stream()
                .filter(RESERVED_FORM_FIELDS::contains)
                .forEach(k -> logger.warn(
                        "Form field '{}' collides with reserved engine field - dropping user value", k));

        Map<String, Object> filteredFormData = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : formData.entrySet()) {
            if (!RESERVED_FORM_FIELDS.contains(e.getKey())) {
                filteredFormData.put(e.getKey(), e.getValue());
            }
        }

        Map<String, Object> payload = new HashMap<>(filteredFormData);

        // Persist submission metadata at root level (canonical snake_case)
        payload.put("submission_id", submissionId);
        payload.put("submitted_at", submittedAt);
        payload.put("triggered_at", submittedAt);

        // form_data = copy of filtered user-submitted fields (reserved keys already excluded)
        payload.put("form_data", new LinkedHashMap<>(filteredFormData));

        // Flatten FileRef fields inline: <field>_file_url, <field>_file_name, etc.
        for (Map.Entry<String, Object> entry : filteredFormData.entrySet()) {
            if (isFileRef(entry.getValue())) {
                Map<String, Object> fileMap = (Map<String, Object>) entry.getValue();
                String key = entry.getKey();
                // Opaque, absolute file URL from the storage-row id (no tenant id / s3 key). A legacy
                // FileRef with no id yields a null _file_url (re-submit to regenerate it with an id).
                Object id = fileMap.get("id");
                payload.put(key + "_file_url",
                        id instanceof String sid && !sid.isBlank() ? publicFileUrlBuilder.fileUrl(sid, true) : null);
                payload.put(key + "_file_name", fileMap.get("name"));
                payload.put(key + "_file_size", fileMap.get("size"));
                payload.put(key + "_content_type", fileMap.get("mimeType"));
            }
        }

        // Metadata fields
        payload.put("_source", "form_endpoint");
        payload.put("_formEndpointId", endpointId);
        payload.put("_formEndpointName", endpointName);

        // Form data is untrusted external - strip the internal plan marker.
        return ReusableTriggerService.sanitizePlanMarker(payload);
    }

    private boolean isFileRef(Object value) {
        if (!(value instanceof Map)) return false;
        Map<?, ?> map = (Map<?, ?>) value;
        return "file".equals(map.get("_type"))
                && map.get("path") instanceof String
                && map.get("name") instanceof String
                && map.get("mimeType") instanceof String;
    }
}

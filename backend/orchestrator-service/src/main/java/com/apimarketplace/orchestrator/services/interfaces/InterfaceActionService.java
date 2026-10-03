package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.services.persistence.StepPayloadService;
import com.apimarketplace.orchestrator.utils.ExecutionConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists interface action data (form submissions, button clicks) to storage.
 *
 * <p>When a user interacts with an interface, each action's data is
 * stored under the action name key so downstream nodes can access it via
 * SpEL expressions like {@code {{interface:my_form.output.submit.name}}}.
 *
 * <p>Multiple actions accumulate - each action name is a separate key in the output.
 * Example output structure:
 * <pre>
 * {
 *   "output": {
 *     "submit": { "name": "John", "email": "john@example.com", "fired_at": "..." },
 *     "cancel": { "fired_at": "..." }
 *   }
 * }
 * </pre>
 */
@Service
public class InterfaceActionService {

    private static final Logger log = LoggerFactory.getLogger(InterfaceActionService.class);

    private final StorageService storageService;
    private final StorageRepository storageRepository;
    private final ObjectMapper objectMapper;

    /**
     * LC-066/LC-011 re-audit item 3: classify-and-tag before persisting (see
     * {@link StepPayloadService#classifySensitivityForRun}). {@code required=false} so a narrow
     * test construction of this service keeps booting without a StepPayloadService on hand; a
     * missing bean degrades to "not restricted" (the pre-fix behaviour), never a startup failure.
     */
    @Autowired(required = false)
    private StepPayloadService stepPayloadService;

    public InterfaceActionService(StorageService storageService,
                                  StorageRepository storageRepository,
                                  ObjectMapper objectMapper) {
        this.storageService = storageService;
        this.storageRepository = storageRepository;
        this.objectMapper = objectMapper;
    }

    /** Test/manual-wiring seam - production wiring uses the {@code @Autowired} field above. */
    void setStepPayloadService(StepPayloadService stepPayloadService) {
        this.stepPayloadService = stepPayloadService;
    }

    /**
     * Persist action data on the interface node's step data, merging by action name.
     *
     * <p>Each action name becomes a key in the output map. Multiple actions
     * accumulate instead of overwriting. Downstream nodes can reference:
     * {@code {{interface:my_form.output.submit.field_name}}}
     *
     * @param runId      The workflow run ID
     * @param nodeId     The interface node key (e.g., "interface:my_form")
     * @param actionName The action name (from data-action attribute, e.g., "submit")
     * @param data       The form data submitted by the user
     * @param tenantId   The tenant ID
     * @param epoch      The current execution epoch
     */
    public void persistActionData(String runId, String nodeId, String actionName,
                                  Map<String, Object> data, String tenantId, int epoch) {
        persistActionData(runId, nodeId, actionName, data, tenantId, epoch, null);
    }

    public void persistActionData(String runId, String nodeId, String actionName,
                                  Map<String, Object> data, String tenantId, int epoch,
                                  String workflowId) {
        persistActionData(runId, nodeId, actionName, data, tenantId, epoch, workflowId, 0);
    }

    public void persistActionData(String runId, String nodeId, String actionName,
                                  Map<String, Object> data, String tenantId, int epoch,
                                  String workflowId, int itemIndex) {
        // Build the action entry: form fields + fired_at
        Map<String, Object> actionEntry = new HashMap<>(data);
        actionEntry.put("fired_at", Instant.now().toString());

        // Load existing output to merge (accumulate multiple actions)
        Map<String, Object> existingOutput = loadExistingOutput(runId, nodeId, tenantId, epoch, itemIndex);
        existingOutput.put(actionName, actionEntry);

        Map<String, Object> payload = Map.of("output", existingOutput);

        log.info("[InterfaceAction] Persisting action data: runId={}, nodeId={}, actionName={}, itemIndex={}, fields={}",
                runId, nodeId, actionName, itemIndex, data.keySet());

        // LC-066/LC-011 re-audit item 3: classify-and-tag rather than always writing NORMAL with
        // expiresAt=null - a form field can echo Gmail/Drive content back (e.g. a "reply" form
        // pre-filled from an earlier restricted step), and a run that already holds restricted
        // data taints every later payload the same way every other payload path does.
        DataSensitivity sensitivity = stepPayloadService != null
                ? stepPayloadService.classifySensitivityForRun(runId, payload)
                : DataSensitivity.NORMAL;
        storageService.saveJsonWithContext(
                tenantId, payload, ExecutionConstants.CONTENT_TYPE_JSON,
                null, null, runId, nodeId, itemIndex, epoch, 0,
                workflowId, "INTERFACE_ACTION", sensitivity);
    }

    /**
     * Load existing output from storage for merging.
     * Returns a mutable map of the current output, or empty map if none exists.
     *
     * The item index scope is required for split/interface nodes. Without it, action data
     * submitted by one split item can be merged into another item's persisted output.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadExistingOutput(String runId, String nodeId,
                                                    String tenantId, int epoch, int itemIndex) {
        try {
            var entityOpt = storageRepository.findByRunIdAndStepKeyAndItemIndexAndEpoch(
                    runId, nodeId, itemIndex, epoch, tenantId);
            if (entityOpt.isPresent()) {
                Object rawData = entityOpt.get().getData();
                Map<String, Object> parsed;
                if (rawData instanceof Map) {
                    parsed = (Map<String, Object>) rawData;
                } else if (rawData instanceof String) {
                    parsed = objectMapper.readValue((String) rawData, Map.class);
                } else {
                    return new HashMap<>();
                }
                Object output = parsed.get("output");
                if (output instanceof Map) {
                    return new HashMap<>((Map<String, Object>) output);
                }
            }
        } catch (Exception e) {
            log.debug("[InterfaceAction] No existing output to merge (first action): runId={}, nodeId={}", runId, nodeId);
        }
        return new HashMap<>();
    }
}

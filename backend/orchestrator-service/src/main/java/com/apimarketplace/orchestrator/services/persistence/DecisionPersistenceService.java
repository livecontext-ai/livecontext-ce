package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.common.storage.exception.QuotaExceededException;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeType;
import com.apimarketplace.orchestrator.domain.workflow.DecisionEvaluationInfo;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.persistence.StepDataNativeRepository;
import com.apimarketplace.orchestrator.utils.ExecutionConstants;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service responsible for persisting decision evaluations.
 * Handles the storage of decision node outcomes including conditions,
 * selected branches, and evaluation context.
 */
@Service
public class DecisionPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(DecisionPersistenceService.class);

    private final StepDataNativeRepository nativeRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public DecisionPersistenceService(
            StepDataNativeRepository nativeRepository,
            StorageService storageService,
            ObjectMapper objectMapper) {
        this.nativeRepository = nativeRepository;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    /**
     * Records a decision evaluation to the database.
     *
     * @param context The persistence context containing execution info
     * @param evaluation The decision evaluation info to persist
     * @return true if the decision was persisted, false if it was a duplicate
     */
    public boolean recordDecisionEvaluation(DecisionPersistenceContext context, DecisionEvaluationInfo evaluation) {
        logger.debug("Recording decision evaluation - decisionNodeId: {}, decisionNodeLabel: {}",
                    evaluation.decisionNodeId(), evaluation.decisionNodeLabel());

        if (context.workflowRunId() == null) {
            logger.debug("No workflow run found for runId {}, skipping decision evaluation persistence",
                        context.runId());
            return false;
        }

        // Extract itemIndex and iteration from contextSnapshot
        Integer itemIndex = extractItemIndex(evaluation);
        Integer iteration = extractIteration(evaluation);

        logger.info("[DecisionPersist] About to save decision: nodeId={}, itemIndex={}, iteration={}, runId={}",
                    evaluation.decisionNodeId(), itemIndex, iteration, context.runId());

        // Build the entity
        WorkflowStepDataEntity entity = buildDecisionEntity(
            context, evaluation, itemIndex, iteration);

        try {
            // Create payload with all decision info for storage
            Map<String, Object> decisionPayload = convertDecisionEvaluationToMap(evaluation);
            decisionPayload.put("type", "decision");

            // Persist payload to storage. F10: when storage returns null
            // (S3/MinIO quota / outage), the decision ROW is still preserved
            // - it carries the selected_branch + condition fields in
            // dedicated columns and is the source of truth for routing,
            // replay, and post-mortem audit. The payload only holds the
            // verbose evaluations map for the inspector UI. Pre-fix, a
            // single transient storage hiccup discarded the entire decision
            // row including the branch routing decision; post-fix the row
            // lands with outputStorageId=NULL and a metadata marker so
            // operators know the audit blob is missing.
            UUID storageId = persistDecisionPayload(context, evaluation, decisionPayload);
            if (storageId == null) {
                logger.error("Decision storage payload failed for decisionNodeId {} run {} - persisting row "
                                + "with outputStorageId=NULL so branch routing is preserved",
                        evaluation.decisionNodeId(), context.runId());
                Map<String, Object> meta = entity.getMetadata();
                if (meta == null) meta = new java.util.HashMap<>();
                else meta = new java.util.HashMap<>(meta);
                meta.put("_storagePayloadFailed", true);
                entity.setMetadata(meta);
            }
            entity.setOutputStorageId(storageId);

            // Persist using ON CONFLICT DO NOTHING (DB-level dedup)
            boolean inserted = nativeRepository.insertIgnoringDuplicate(entity);
            if (!inserted) {
                logger.debug("[DecisionPersist] Duplicate decision detected: nodeId={}, itemIndex={}, iteration={}",
                            evaluation.decisionNodeId(), itemIndex, iteration);
                return false;
            }

            logger.debug("Decision evaluation saved - decisionNodeId: {}, itemIndex: {}, iteration: {}",
                        evaluation.decisionNodeId(), itemIndex, iteration);

            return true;

        } catch (Exception e) {
            logger.error("Exception saving decision evaluation for decisionNodeId {}: {}",
                        evaluation.decisionNodeId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Extracts itemIndex from the evaluation's context snapshot.
     */
    private Integer extractItemIndex(DecisionEvaluationInfo evaluation) {
        Map<String, Object> contextSnapshot = evaluation.contextSnapshot();
        if (contextSnapshot != null && contextSnapshot.containsKey(ExecutionConstants.KEY_ITEM_INDEX_ALT)) {
            return (Integer) contextSnapshot.get(ExecutionConstants.KEY_ITEM_INDEX_ALT);
        }
        return null;
    }

    /**
     * Extracts iteration from the evaluation's context snapshot.
     */
    private Integer extractIteration(DecisionEvaluationInfo evaluation) {
        Map<String, Object> contextSnapshot = evaluation.contextSnapshot();
        if (contextSnapshot != null && contextSnapshot.containsKey("iteration")) {
            return (Integer) contextSnapshot.get("iteration");
        }
        return null;
    }

    /**
     * Builds a WorkflowStepDataEntity for a decision evaluation.
     */
    private WorkflowStepDataEntity buildDecisionEntity(
            DecisionPersistenceContext context,
            DecisionEvaluationInfo evaluation,
            Integer itemIndex,
            Integer iteration) {

        WorkflowStepDataEntity entity = new WorkflowStepDataEntity();

        // Identifiers
        entity.setWorkflowRunId(context.workflowRunId());
        entity.setRunId(context.runId());

        // Use normalized label as step_alias for consistency
        String normalizedDecisionLabel = LabelNormalizer.normalizeLabel(evaluation.decisionNodeLabel());
        entity.setStepAlias(normalizedDecisionLabel != null ? normalizedDecisionLabel : evaluation.decisionNodeLabel());

        // Use "core:" + decision_node_id as tool_id to identify this is a decision
        entity.setToolId("core:" + evaluation.decisionNodeId());

        // Status
        entity.setStatus(RunStatus.COMPLETED.name());

        // Item index if in a loop
        entity.setItemIndex(itemIndex);

        // Iteration if in a loop (CRITICAL for deduplication)
        entity.setIteration(iteration);

        // Set epoch for re-run support
        entity.setEpoch(context.currentEpoch());

        // === NODE TYPE SPECIFIC FIELDS ===
        entity.setNodeType(NodeType.DECISION);
        entity.setSelectedBranch(evaluation.selectedBranch());

        // Extract condition expression and result from the selected condition
        setConditionFields(entity, evaluation.conditions());

        // Build metadata
        Map<String, Object> metadata = buildMetadata(context, evaluation, evaluation.conditions());
        entity.setMetadata(metadata);

        // Timestamps
        Instant now = Instant.now();
        entity.setStartTime(now);
        entity.setEndTime(now);

        // Tenant ID
        entity.setTenantId(context.tenantId());
        if (context.organizationId() != null && !context.organizationId().isBlank()) {
            entity.setOrganizationId(context.organizationId());
        }

        // No inputData for decisions
        entity.setInputData(null);
        entity.setHttpStatus(null);
        entity.setErrorMessage(null);

        // Set normalized key for StateManager integration
        entity.setNormalizedKey(LabelNormalizer.coreKey(
            normalizedDecisionLabel != null ? normalizedDecisionLabel : evaluation.decisionNodeLabel()));

        logger.info("💾 [DecisionPersist] Built DECISION entity: stepAlias={}, toolId={}, normalizedKey={}, " +
                   "itemIndex={}, selectedBranch={}, condition={}, condResult={}",
                   entity.getStepAlias(), entity.getToolId(), entity.getNormalizedKey(), entity.getItemIndex(),
                   entity.getSelectedBranch(), entity.getConditionExpression(), entity.getConditionResult());

        return entity;
    }

    /**
     * Sets condition expression and result fields on the entity.
     */
    private void setConditionFields(WorkflowStepDataEntity entity,
                                    List<DecisionEvaluationInfo.ConditionEvaluation> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return;
        }

        // Find the selected condition
        for (DecisionEvaluationInfo.ConditionEvaluation cond : conditions) {
            if (cond.selected()) {
                entity.setConditionExpression(cond.originalExpression());
                entity.setConditionResult(cond.result());
                return;
            }
        }

        // If no condition was selected (all false), store the first condition for reference
        DecisionEvaluationInfo.ConditionEvaluation firstCond = conditions.get(0);
        entity.setConditionExpression(firstCond.originalExpression());
        entity.setConditionResult(false);
    }

    /**
     * Builds metadata map for the decision entity.
     */
    private Map<String, Object> buildMetadata(
            DecisionPersistenceContext context,
            DecisionEvaluationInfo evaluation,
            List<DecisionEvaluationInfo.ConditionEvaluation> conditions) {

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("decisionType", "decision");
        metadata.put("workflowRunId", context.workflowRunId().toString());
        metadata.put("workflowId", context.workflowId());
        metadata.put("recordedAt", Instant.now().toString());
        metadata.put("tenantId", context.tenantId());
        metadata.put("decisionNodeId", evaluation.decisionNodeId());
        metadata.put("decisionNodeLabel", evaluation.decisionNodeLabel());
        metadata.put("sourceStepId", evaluation.sourceStepId());
        metadata.put("selectedBranch", evaluation.selectedBranch());

        // Add all conditions to metadata for detailed debugging
        if (conditions != null) {
            List<Map<String, Object>> conditionsList = new ArrayList<>();
            for (DecisionEvaluationInfo.ConditionEvaluation cond : conditions) {
                Map<String, Object> condMap = new HashMap<>();
                condMap.put("type", cond.type());
                condMap.put("expression", cond.originalExpression());
                condMap.put("resolved", cond.resolvedExpression());
                condMap.put("result", cond.result());
                condMap.put("selected", cond.selected());
                condMap.put("targetBranch", cond.targetBranch());
                conditionsList.add(condMap);
            }
            metadata.put("conditions", conditionsList);
        }

        return metadata;
    }

    /**
     * Converts DecisionEvaluationInfo to Map for JSON serialization.
     */
    private Map<String, Object> convertDecisionEvaluationToMap(DecisionEvaluationInfo evaluation) {
        List<DecisionEvaluationInfo.ConditionEvaluation> conditions = evaluation.conditions();
        int conditionsSize = conditions != null ? conditions.size() : 0;

        Map<String, Object> map = new HashMap<>();
        map.put("decisionNodeId", evaluation.decisionNodeId());
        map.put("decisionNodeLabel", evaluation.decisionNodeLabel());
        map.put("sourceStepId", evaluation.sourceStepId());
        map.put("selectedBranch", evaluation.selectedBranch());

        // Convert conditions with pre-allocation
        if (conditionsSize > 0) {
            List<Map<String, Object>> conditionsList = new ArrayList<>(conditionsSize);
            for (DecisionEvaluationInfo.ConditionEvaluation cond : conditions) {
                Map<String, Object> condMap = new HashMap<>();
                condMap.put("type", cond.type());
                condMap.put("originalExpression", cond.originalExpression());
                condMap.put("resolvedExpression", cond.resolvedExpression());
                condMap.put("result", cond.result());
                condMap.put("selected", cond.selected());
                condMap.put("targetBranch", cond.targetBranch());
                if (cond.errorMessage() != null) {
                    condMap.put("errorMessage", cond.errorMessage());
                }
                conditionsList.add(condMap);
            }
            map.put("conditions", conditionsList);
        } else {
            map.put("conditions", Collections.emptyList());
        }

        // Add context snapshot if present
        Map<String, Object> contextSnapshot = evaluation.contextSnapshot();
        if (contextSnapshot != null && !contextSnapshot.isEmpty()) {
            map.put("contextSnapshot", contextSnapshot);
        }

        return map;
    }

    /**
     * Persists the decision payload to storage.
     * Wraps decision data in "output" key for consistency with MCP steps.
     */
    private UUID persistDecisionPayload(
            DecisionPersistenceContext context,
            DecisionEvaluationInfo evaluation,
            Map<String, Object> payload) {
        try {
            // Wrap decision data in "output" key for consistency with MCP steps
            // This allows frontend to access data via path "output.selectedBranch" etc.
            Map<String, Object> storagePayload = new HashMap<>();
            storagePayload.put("output", payload);
            storagePayload.put("_metadata", Map.of(
                "type", "decision_output",
                "runId", context.runId(),
                "decisionNodeId", evaluation.decisionNodeId()
            ));

            return storageService.saveJsonWithContext(
                context.tenantId(),
                storagePayload,
                ExecutionConstants.CONTENT_TYPE_JSON,
                null,
                null, context.runId(), null, null, context.currentEpoch(),
                context.workflowId(),
                "DECISION"
            );
        } catch (QuotaExceededException quota) {
            // F2: distinguish quota-exceeded so ops can route the alert to tenant
            // action rather than infra investigation.
            logger.error("Storage quota exceeded persisting decision payload (tenant {}): {}",
                    quota.getTenantId(), quota.getMessage());
            return null;
        } catch (Exception e) {
            logger.error("Failed to persist decision payload: [{}] {}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Context object containing all necessary information for decision persistence.
     */
    public record DecisionPersistenceContext(
        String runId,
        UUID workflowRunId,
        String workflowId,
        String tenantId,
        int currentEpoch,
        String organizationId
    ) {
        public DecisionPersistenceContext(
                String runId,
                UUID workflowRunId,
                String workflowId,
                String tenantId,
                int currentEpoch) {
            this(runId, workflowRunId, workflowId, tenantId, currentEpoch, null);
        }
    }
}

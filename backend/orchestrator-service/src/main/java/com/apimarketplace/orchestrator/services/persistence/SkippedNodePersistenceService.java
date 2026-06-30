package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeType;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.persistence.StepDataNativeRepository;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service responsible for persisting skipped nodes in workflow_step_data.
 * Called when a node is skipped due to decision branch not taken or skip propagation.
 */
@Service
public class SkippedNodePersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(SkippedNodePersistenceService.class);

    private final StepDataNativeRepository nativeRepository;
    private final StepPayloadService stepPayloadService;
    private final WorkflowEntityResolverService entityResolverService;

    public SkippedNodePersistenceService(
            StepDataNativeRepository nativeRepository,
            StepPayloadService stepPayloadService,
            WorkflowEntityResolverService entityResolverService) {
        this.nativeRepository = nativeRepository;
        this.stepPayloadService = stepPayloadService;
        this.entityResolverService = entityResolverService;
    }

    /**
     * Records a skipped node to the database.
     *
     * @param execution The workflow execution
     * @param nodeId The node ID (e.g., "mcp:list_bases")
     * @param nodeLabel The node label (e.g., "list_bases")
     * @param skipReason Why this node was skipped
     * @param skipSourceNode The node that caused the skip
     * @param itemIndex The item index being processed
     * @return true if persistence succeeded, false otherwise
     */
    public boolean recordSkippedNode(WorkflowExecution execution,
                                     String nodeId,
                                     String nodeLabel,
                                     String skipReason,
                                     String skipSourceNode,
                                     int itemIndex) {
        return recordSkippedNode(execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, 0, null);
    }

    /**
     * Records a skipped node to the database with explicit epoch.
     *
     * @param execution The workflow execution
     * @param nodeId The node ID (e.g., "mcp:list_bases")
     * @param nodeLabel The node label (e.g., "list_bases")
     * @param skipReason Why this node was skipped
     * @param skipSourceNode The node that caused the skip
     * @param itemIndex The item index being processed
     * @param explicitEpoch Explicit epoch (positive = honored verbatim; 0 or negative = use
     *                      global fallback via getCurrentEpochFromRun). The {@code > 0}
     *                      threshold is intentional and load-bearing: legacy callers
     *                      (e.g. {@code SkipPropagationService:141}) call
     *                      {@code completeSkippedStep(...)} which builds {@code SkipContext}
     *                      via the 6-arg
     *                      {@link com.apimarketplace.orchestrator.services.completion.SkipContext#of(WorkflowExecution, String, String, String, String, int)}
     *                      factory - that factory hardcodes {@code epoch=0} regardless of
     *                      the run's current epoch. For those callers the global resolver
     *                      is the ONLY source of the correct epoch; changing the threshold
     *                      to {@code >= 0} would write {@code epoch=0} mid-run and silently
     *                      mis-bucket per-epoch step rows. New callers that DO have a real
     *                      epoch (including a real first {@code epoch=0}) should route
     *                      through {@link
     *                      com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator}'s
     *                      explicit-epoch overloads, which build SkipContext via the 7-arg
     *                      factory and honor the value verbatim.
     * @return true if persistence succeeded, false otherwise
     */
    public boolean recordSkippedNode(WorkflowExecution execution,
                                     String nodeId,
                                     String nodeLabel,
                                     String skipReason,
                                     String skipSourceNode,
                                     int itemIndex,
                                     int explicitEpoch) {
        return recordSkippedNode(execution, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, explicitEpoch, null);
    }

    /**
     * Records a skipped node to the database with explicit epoch and triggerId.
     *
     * @param execution The workflow execution
     * @param nodeId The node ID (e.g., "mcp:list_bases")
     * @param nodeLabel The node label (e.g., "list_bases")
     * @param skipReason Why this node was skipped
     * @param skipSourceNode The node that caused the skip
     * @param itemIndex The item index being processed
     * @param explicitEpoch Explicit epoch (positive = honored verbatim; 0 or negative = use
     *                      global fallback via getCurrentEpochFromRun).
     * @param triggerId DAG trigger ID resolved by the caller. When absent, the
     *                  legacy trigger:default sentinel is preserved.
     * @return true if persistence succeeded, false otherwise
     */
    public boolean recordSkippedNode(WorkflowExecution execution,
                                     String nodeId,
                                     String nodeLabel,
                                     String skipReason,
                                     String skipSourceNode,
                                     int itemIndex,
                                     int explicitEpoch,
                                     String triggerId) {
        return recordSkippedNode(execution, nodeId, nodeLabel, skipReason,
            skipSourceNode, itemIndex, 0, explicitEpoch, triggerId);
    }

    /**
     * Records a skipped node to the database with explicit iteration, epoch, and triggerId.
     */
    public boolean recordSkippedNode(WorkflowExecution execution,
                                     String nodeId,
                                     String nodeLabel,
                                     String skipReason,
                                     String skipSourceNode,
                                     int itemIndex,
                                     int iteration,
                                     int explicitEpoch,
                                     String triggerId) {
        UUID workflowRunId = entityResolverService.resolveWorkflowRunId(execution).orElse(null);
        if (workflowRunId == null) {
            logger.debug("No workflow run found for runId {}, skipping persistence", execution.getRunId());
            return false;
        }

        String runId = execution.getRunId();
        int currentEpoch = (explicitEpoch > 0) ? explicitEpoch : entityResolverService.getCurrentEpochFromRun(workflowRunId);
        // Spawn must mirror StepDataPersistenceService: a rerun bumps the run's spawn and the
        // skip rows written by the re-evaluated decision/switch belong to the NEW spawn. A
        // hardcoded spawn=0 made post-rerun SKIPPED rows look like pre-rerun state, so the
        // spawn-aware step aggregation kept reporting the deactivated branch as "completed".
        int currentSpawn = entityResolverService.getCurrentSpawnFromRun(workflowRunId);
        logger.info("Recording skipped node: nodeId={}, skipReason={}, itemIndex={}, iteration={}, explicitEpoch={}, resolvedEpoch={}, spawn={}, triggerId={}",
                nodeId, skipReason, itemIndex, iteration, explicitEpoch, currentEpoch, currentSpawn, triggerId);

        try {
            // Build entity for skipped node
            WorkflowStepDataEntity entity = buildSkippedNodeEntity(
                    execution, workflowRunId, nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, iteration, currentEpoch, currentSpawn, triggerId
            );

            // Create storage entry for skipped node
            Map<String, Object> skipPayload = buildSkipPayload(
                    nodeId, nodeLabel, skipReason, skipSourceNode, itemIndex, execution.getDisplayName(), entity.getTriggerId());

            UUID storageId = stepPayloadService.persistSkippedNodePayload(
                    execution.getPlan().getTenantId(), skipPayload, currentEpoch);
            entity.setOutputStorageId(storageId);

            // Persist to database using ON CONFLICT DO NOTHING
            boolean inserted = nativeRepository.insertIgnoringDuplicate(entity);
            if (!inserted) {
                logger.debug("Duplicate skipped node detected: nodeId={} itemIndex={}", nodeId, itemIndex);
                return false;
            }
            logger.info("Skipped node saved: nodeId={} itemIndex={} skipReason={}",
                    nodeId, itemIndex, skipReason);
            return true;

        } catch (Exception e) {
            logger.error("Exception saving skipped node {} for run {}: {}", nodeId, runId, e.getMessage(), e);
            return false;
        }
    }

    private WorkflowStepDataEntity buildSkippedNodeEntity(WorkflowExecution execution,
                                                          UUID workflowRunId,
                                                          String nodeId,
                                                          String nodeLabel,
                                                          String skipReason,
                                                          String skipSourceNode,
                                                          int itemIndex,
                                                          int iteration,
                                                          int currentEpoch,
                                                          int currentSpawn,
                                                          String triggerId) {
        WorkflowStepDataEntity entity = new WorkflowStepDataEntity();

        // Identifiers
        entity.setWorkflowRunId(workflowRunId);
        entity.setRunId(execution.getRunId());

        // Use normalized node label as step_alias for consistent matching
        String normalizedLabel = LabelNormalizer.normalizeLabel(nodeLabel);
        entity.setStepAlias(normalizedLabel != null ? normalizedLabel : nodeLabel);

        // Use nodeId as tool_id
        entity.setToolId(nodeId);

        // Status = SKIPPED
        entity.setStatus("SKIPPED");

        // Skip tracking
        entity.setSkipReason(skipReason);
        entity.setSkipSourceNode(skipSourceNode);

        // Item tracking
        entity.setItemIndex(itemIndex);
        entity.setIteration(iteration);
        entity.setEpoch(currentEpoch);
        entity.setSpawn(currentSpawn);

        // Timestamps
        Instant now = Instant.now();
        entity.setStartTime(now);
        entity.setEndTime(now);

        // Tenant ID
        entity.setTenantId(execution.getPlan().getTenantId());

        // Derive NodeType from nodeId, refining for core nodes via plan lookup
        NodeType nodeType = resolveNodeType(execution, nodeId);
        entity.setNodeType(nodeType);

        // Metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("workflowRunId", workflowRunId.toString());
        metadata.put("workflowId", execution.getPlan().getId());
        metadata.put("recordedAt", now.toString());
        metadata.put("tenantId", execution.getPlan().getTenantId());
        metadata.put("skipReason", skipReason);
        metadata.put("skipSourceNode", skipSourceNode);
        if (nodeType != null) {
            metadata.put("nodeType", nodeType.name());
        }
        String resolvedTriggerId = resolveTriggerId(triggerId);
        metadata.put("triggerId", resolvedTriggerId);
        entity.setMetadata(metadata);

        entity.setInputData(buildSkippedInputData(
                execution, skipReason, skipSourceNode, itemIndex, currentEpoch, resolvedTriggerId));
        entity.setHttpStatus(null);
        entity.setErrorMessage(null);

        // Set normalized key (consistent with StepDataPersistenceService)
        entity.setNormalizedKey(computeNormalizedKey(nodeId, normalizedLabel != null ? normalizedLabel : nodeLabel, nodeType));
        stampOrganizationId(entity, workflowRunId);

        // Ensure trigger_id is never null (required by V164 migration).
        entity.setTriggerId(resolvedTriggerId);

        return entity;
    }

    private String resolveTriggerId(String triggerId) {
        return triggerId != null && !triggerId.isBlank()
                ? triggerId
                : "trigger:default";
    }

    private Map<String, Object> buildSkippedInputData(WorkflowExecution execution,
                                                       String skipReason,
                                                       String skipSourceNode,
                                                       int itemIndex,
                                                       int epoch,
                                                       String triggerId) {
        Map<String, Object> inputData = new HashMap<>();
        inputData.put("skipped", true);
        inputData.put("skip_reason", skipReason);
        inputData.put("skip_source_node", skipSourceNode);
        inputData.put("item_index", itemIndex);
        inputData.put("epoch", epoch);
        inputData.put("trigger_id", triggerId);

        return inputData;
    }

    private void stampOrganizationId(WorkflowStepDataEntity entity, UUID workflowRunId) {
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (orgId == null || orgId.isBlank()) {
            orgId = entityResolverService.getOrganizationIdFromRun(workflowRunId).orElse(null);
        }
        if (orgId != null && !orgId.isBlank()) {
            entity.setOrganizationId(orgId);
        }
    }

    private Map<String, Object> buildSkipPayload(String nodeId, String nodeLabel,
                                                  String skipReason, String skipSourceNode,
                                                  int itemIndex, String displayName,
                                                  String triggerId) {
        Map<String, Object> output = new HashMap<>();
        output.put("status", "SKIPPED");
        output.put("skipReason", skipReason);
        output.put("skipSourceNode", skipSourceNode);
        output.put("nodeId", nodeId);
        output.put("nodeLabel", nodeLabel);
        output.put("itemIndex", itemIndex);
        output.put("trigger_id", triggerId);
        output.put("recordedAt", Instant.now().toString());

        // Standard execution envelope fields
        output.put("_status", "SKIPPED");
        output.put("_skip_reason", skipReason);
        output.put("_skip_source_node", skipSourceNode);
        output.put("_duration_ms", 0L);
        if (displayName != null) {
            output.put("_display_name", displayName);
        }

        Map<String, Object> skipPayload = new HashMap<>();
        skipPayload.put("output", output);
        return skipPayload;
    }

    /**
     * Resolves NodeType from nodeId, with refined lookup for core nodes via the plan.
     * For core: prefixed nodes, looks up the Core object to get the exact type (DECISION, SWITCH, TRANSFORM, etc.)
     * instead of defaulting to DECISION.
     */
    private NodeType resolveNodeType(WorkflowExecution execution, String nodeId) {
        NodeType baseType = NodeType.fromNodeId(nodeId);
        if (baseType == NodeType.DECISION && nodeId != null && nodeId.startsWith("core:")) {
            // Try to refine by looking up the core type from the plan
            for (Core core : execution.getPlan().getCores()) {
                if (nodeId.equals(core.getNormalizedKey())) {
                    return NodeType.fromCoreType(core.type());
                }
            }
        }
        return baseType;
    }

    /**
     * Computes normalized key consistent with StepDataPersistenceService.
     */
    private String computeNormalizedKey(String nodeId, String stepLabel, NodeType nodeType) {
        if (nodeId != null && LabelNormalizer.isNormalizedKey(nodeId)) return nodeId;
        String label = stepLabel != null ? stepLabel : nodeId;
        if (label == null) return null;
        String prefix = nodeType != null ? nodeType.getPrefix() : "step";
        String normalizedLabel = LabelNormalizer.normalizeLabel(label);
        return prefix + ":" + (normalizedLabel != null ? normalizedLabel : label);
    }
}

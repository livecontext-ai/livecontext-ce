package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeType;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.persistence.StepDataNativeRepository;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Service responsible for building and persisting WorkflowStepDataEntity instances.
 * Handles the transformation of step execution results into database entities.
 */
@Service
public class StepDataPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(StepDataPersistenceService.class);

    private final StepDataNativeRepository nativeRepository;
    private final StepPayloadService stepPayloadService;
    private final StepMetadataBuilder metadataBuilder;
    private final WorkflowEntityResolverService entityResolverService;

    public StepDataPersistenceService(
            StepDataNativeRepository nativeRepository,
            StepPayloadService stepPayloadService,
            StepMetadataBuilder metadataBuilder,
            WorkflowEntityResolverService entityResolverService) {
        this.nativeRepository = nativeRepository;
        this.stepPayloadService = stepPayloadService;
        this.metadataBuilder = metadataBuilder;
        this.entityResolverService = entityResolverService;
    }

    /**
     * Records a step execution result to the database.
     *
     * @param execution The workflow execution
     * @param stepId The step ID
     * @param stepAliasOrId The step alias or ID
     * @param graphNodeId The graph node ID
     * @param result The step execution result
     * @return StepPersistenceResult with success status and storage ID
     */
    public StepPersistenceResult recordStep(WorkflowExecution execution, String stepId, String stepAliasOrId,
                              String graphNodeId, StepExecutionResult result) {
        return recordStep(execution, stepId, stepAliasOrId, graphNodeId, result, 0);
    }

    /**
     * Records a step execution result to the database with explicit epoch.
     *
     * @param execution The workflow execution
     * @param stepId The step ID
     * @param stepAliasOrId The step alias or ID
     * @param graphNodeId The graph node ID
     * @param result The step execution result
     * @param explicitEpoch Explicit epoch (0 = use global fallback via getCurrentEpochFromRun)
     * @return StepPersistenceResult with success status and storage ID
     */
    public StepPersistenceResult recordStep(WorkflowExecution execution, String stepId, String stepAliasOrId,
                              String graphNodeId, StepExecutionResult result, int explicitEpoch) {
        return recordStep(execution, stepId, stepAliasOrId, graphNodeId, result, explicitEpoch, null);
    }

    /**
     * Records a step execution result with explicit epoch AND explicit triggerId.
     *
     * <p>2026-05-21 CRITICAL 2 fix (e2e audit): pre-fix, every COMPLETED
     * workflow_step_data row drifted to {@code trigger_id="trigger:default"}
     * because {@code buildStepEntity} only read {@code output.trigger_id} from
     * the step's output map (truthy only for trigger nodes). Non-trigger nodes
     * - i.e. the vast majority - landed under default, breaking the per-epoch
     * detail view (the "Items in epoch N for this node" inspector tab).
     * Threading the explicit triggerId from the call site (resolved upstream
     * from {@code ExecutionContext.triggerId()}) heals the row at write time.
     *
     * @param triggerId the DAG trigger ID; used as the fallback when the node's
     *                  output does not carry an explicit {@code trigger_id} key.
     *                  {@code null} preserves legacy "trigger:default" behavior.
     */
    public StepPersistenceResult recordStep(WorkflowExecution execution, String stepId, String stepAliasOrId,
                              String graphNodeId, StepExecutionResult result, int explicitEpoch, String triggerId) {
        UUID workflowRunId = entityResolverService.resolveWorkflowRunId(execution).orElse(null);
        if (workflowRunId == null) return StepPersistenceResult.notPersisted();

        String runId = execution.getRunId();
        int currentEpoch = (explicitEpoch > 0) ? explicitEpoch : entityResolverService.getCurrentEpochFromRun(workflowRunId);
        int currentSpawn = entityResolverService.getCurrentSpawnFromRun(workflowRunId);

        WorkflowStepDataEntity entity;
        try {
            entity = buildStepEntity(execution, workflowRunId, stepId, stepAliasOrId, graphNodeId, result, currentEpoch, currentSpawn, triggerId);
        } catch (Exception e) {
            logger.warn("Unable to build step entity for step {} (run {}): {}", stepId, runId, e.getMessage(), e);
            return StepPersistenceResult.notPersisted();
        }

        // Defense-in-depth (F24/F25): never silently drop a step because the node forgot to put
        // 'item_index' in its output map. Default to 0 and surface a warn so the node author
        // can fix the source - but the row STILL gets persisted.
        if (entity.getItemIndex() == null) {
            String nodeTypeName = entity.getNodeType() != null ? entity.getNodeType().name() : "UNKNOWN";
            logger.warn("Step missing 'item_index' in output map - defaulting to 0. " +
                "Node author should call BaseNode.successWithMetadata() or include item_index explicitly. " +
                "nodeId={}, nodeType={}, run={}", stepId, nodeTypeName, runId);
            entity.setItemIndex(0);
        }

        try {
            UUID storageId = entity.getOutputStorageId();
            if (storageId == null) {
                storageId = stepPayloadService.persistStepPayload(execution, stepId, stepAliasOrId, result, Collections.emptyMap(), currentEpoch);
                entity.setOutputStorageId(storageId);
            }
            // F2 - silent-null guard: if the payload write failed (S3 quota, MinIO
            // outage, JSON-serialize error), StepPayloadService returns null but
            // returns null silently. Without this stamp the row would land with
            // status=COMPLETED + outputStorageId=NULL → downstream templates
            // resolve to empty input → false-success cascade. Surface the failure
            // on the row's error_message so the user sees it in the UI without
            // changing the step's COMPLETED/FAILED status semantics. Existing
            // error_message (real step failure) is preserved by appending.
            if (storageId == null) {
                String marker = "[storage] Payload persist failed - output blob unavailable. See logs for cause.";
                String existing = entity.getErrorMessage();
                String combined = existing == null || existing.isBlank()
                        ? marker
                        : existing + " | " + marker;
                // Defense-in-depth: a pathological pre-existing 16K-capped message
                // plus the marker could exceed the column cap on concat. Route
                // through the shared truncate utility - hot path is identity.
                entity.setErrorMessage(com.apimarketplace.orchestrator.domain.workflow.ErrorMessageLimits.truncate(combined));
                logger.error("Storage payload missing on step row - stamping error_message: runId={} stepId={} status={}",
                        runId, stepId, entity.getStatus());
            }
            boolean inserted = nativeRepository.insertIgnoringDuplicate(entity);
            if (!inserted) {
                logger.debug("Duplicate step detected, skipping: step={} run={}", stepId, runId);
                return StepPersistenceResult.notPersisted();
            }
            return StepPersistenceResult.success(storageId);
        } catch (Exception e) {
            logger.error("Exception saving step {} for run {}: {}", stepId, runId, e.getMessage(), e);
            return StepPersistenceResult.notPersisted();
        }
    }

    /**
     * Builds a WorkflowStepDataEntity from a step execution result (legacy 8-arg
     * form). Delegates to the 9-arg variant with {@code triggerId=null} →
     * fallback to {@code "trigger:default"} when the node's output lacks
     * {@code trigger_id}. All existing test fixtures stay on this signature.
     *
     * @deprecated Use {@link #buildStepEntity(WorkflowExecution, UUID, String,
     *             String, String, StepExecutionResult, int, int, String)} (9-arg)
     *             to thread the real triggerId from the execution context -
     *             without it, every non-trigger node's row drifts to
     *             {@code trigger:default} (CRITICAL 2, 2026-05-21).
     */
    public WorkflowStepDataEntity buildStepEntity(WorkflowExecution execution, UUID workflowRunId,
                                                   String stepId, String stepAliasOrId, String graphNodeId,
                                                   StepExecutionResult result, int currentEpoch, int currentSpawn) {
        return buildStepEntity(execution, workflowRunId, stepId, stepAliasOrId, graphNodeId, result, currentEpoch, currentSpawn, null);
    }

    /**
     * Builds a WorkflowStepDataEntity from a step execution result, threading
     * an explicit triggerId from the caller's execution context.
     *
     * <p>Resolution order for the {@code trigger_id} column:
     * <ol>
     *   <li>{@code output.trigger_id} - when the node's output explicitly
     *       publishes it (trigger nodes; preserved for back-compat).</li>
     *   <li>{@code triggerId} argument - the DAG trigger ID resolved by
     *       {@code StepCompletionOrchestrator.complete} from
     *       {@code StepCompletionContext} (or upstream).</li>
     *   <li>{@code "trigger:default"} sentinel - preserved as the final
     *       fallback so the V164 NOT NULL constraint never fires.</li>
     * </ol>
     */
    public WorkflowStepDataEntity buildStepEntity(WorkflowExecution execution, UUID workflowRunId,
                                                   String stepId, String stepAliasOrId, String graphNodeId,
                                                   StepExecutionResult result, int currentEpoch, int currentSpawn,
                                                   String triggerId) {
        String lookupKey = stepAliasOrId != null ? stepAliasOrId : (result.stepId() != null ? result.stepId() : stepId);
        Optional<Step> stepOpt = execution.getPlan().findStep(lookupKey);
        String stepLabel = stepOpt.map(Step::label).orElse(LabelNormalizer.extractLabelFromKey(lookupKey));
        String toolIdValue = stepOpt.map(step -> step.id() != null ? step.id() : stepLabel).orElse(stepLabel);

        Map<String, Object> inputData = extractInputData(result);
        Map<String, Object> metadata = metadataBuilder.buildMetadata(execution, stepId, graphNodeId, result, workflowRunId);

        UUID storageId = stepPayloadService.persistStepPayload(execution, stepId, stepAliasOrId, result, metadata, currentEpoch, currentSpawn);

        Instant endTime = Instant.now();
        Instant startTime = result.executionTime() > 0 ? endTime.minusMillis(result.executionTime()) : endTime;

        String normalizedStepLabel = LabelNormalizer.normalizeLabel(stepLabel);

        WorkflowStepDataEntity entity = new WorkflowStepDataEntity(
                workflowRunId, execution.getRunId(),
                normalizedStepLabel != null ? normalizedStepLabel : stepLabel,
                toolIdValue, inputData, storageId,
                extractInteger(result, "http_status"),
                result.status().name(),
                startTime, endTime,
                result.isFailure() ? result.message() : null,
                execution.getPlan().getTenantId(),
                extractIntegerOrDefault(result, "epoch", currentEpoch),
                extractIntegerOrDefault(result, "spawn", currentSpawn),
                extractIntegerOrDefault(result, "iteration", 0),
                extractInteger(result, "item_index"),
                metadata
        );

        enrichEntityWithNodeTypeFields(entity, stepId, result);
        entity.setNormalizedKey(computeNormalizedKey(stepId, stepLabel, entity.getNodeType()));
        stampOrganizationId(entity, workflowRunId);

        // Ensure trigger_id is never null (required by V164 migration).
        //
        // 2026-05-21 CRITICAL 2 fix: prefer the explicit triggerId argument
        // (resolved from the caller's ExecutionContext) over the "trigger:default"
        // sentinel. output.trigger_id (set above by enrichEntityWithNodeTypeFields
        // for trigger nodes only) still wins for back-compat. Without this, every
        // non-trigger node's row drifted to "trigger:default" because the only
        // path to a real trigger ID was through the output map.
        if (entity.getTriggerId() == null) {
            entity.setTriggerId((triggerId != null && !triggerId.isBlank())
                ? triggerId
                : "trigger:default");
        }

        return entity;
    }

    /**
     * Enriches a step entity with node type specific fields.
     * All logic-related data goes to entity columns or metadata.
     * Storage should only contain raw response data.
     */
    public void enrichEntityWithNodeTypeFields(WorkflowStepDataEntity entity, String stepId, StepExecutionResult result) {
        if (result == null || result.output() == null) return;
        Map<String, Object> output = result.output();

        NodeType nodeType = NodeType.fromNodeId(stepId);
        String nodeTypeStr = (String) output.get("node_type");
        if (nodeTypeStr != null) {
            // Map output node_type string to enum, handling special cases
            NodeType resolved = switch (nodeTypeStr) {
                case "SPLIT" -> NodeType.SPLIT_CONTROLLER;
                case "LOOP" -> NodeType.LOOP_CONTROLLER;
                case "CLASSIFY", "GUARDRAIL" -> NodeType.AGENT; // Agent subtypes
                default -> {
                    try {
                        yield NodeType.valueOf(nodeTypeStr);
                    } catch (IllegalArgumentException e) {
                        yield nodeType; // Keep fromNodeId() result as fallback
                    }
                }
            };
            nodeType = resolved;
        }
        entity.setNodeType(nodeType);

        // Ensure metadata exists
        Map<String, Object> metadata = entity.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
            entity.setMetadata(metadata);
        }

        // Common fields
        if (output.get("item_id") != null) entity.setItemId(output.get("item_id").toString());
        if (output.get("trigger_id") != null) entity.setTriggerId(output.get("trigger_id").toString());
        if (result.isSkipped()) enrichSkippedFields(entity, result, output, metadata);

        // Node-type specific enrichment
        if (nodeType != null) {
            switch (nodeType) {
                case TRIGGER -> enrichTriggerFields(entity, output, metadata);
                case DECISION -> enrichDecisionFields(entity, output, metadata);
                case SWITCH -> enrichSwitchFields(entity, output, metadata);
                case OPTION -> enrichOptionFields(entity, output, metadata);
                case LOOP_CONTROLLER -> {} // Loop nodes no longer used
                case SPLIT_CONTROLLER -> enrichSplitFields(entity, output, metadata);
                case MERGE -> enrichMergeFields(entity, output, metadata);
                case FORK -> enrichForkFields(entity, output, metadata);
                case AGGREGATE -> enrichAggregateFields(entity, output, metadata);
                case AGENT -> enrichAgentFields(entity, output, metadata);
                case MCP -> enrichMcpFields(entity, output, metadata);
                case TRANSFORM -> {} // Transform stores resolved_params via extractInputData()
                default -> {}
            }
        }
    }

    private void enrichSkippedFields(
            WorkflowStepDataEntity entity,
            StepExecutionResult result,
            Map<String, Object> output,
            Map<String, Object> metadata) {

        String skipReason = firstNonBlank(
            output.get("skip_reason"),
            output.get("_skip_reason"),
            output.get("skipReason"),
            result.message(),
            metadata.get("statusMessage")
        );
        if (skipReason != null) {
            entity.setSkipReason(skipReason);
            metadata.putIfAbsent("skipReason", skipReason);
        }

        String skipSourceNode = firstNonBlank(
            output.get("skip_source_node"),
            output.get("_skip_source_node"),
            output.get("skipSourceNode")
        );
        if (skipSourceNode != null) {
            entity.setSkipSourceNode(skipSourceNode);
            metadata.putIfAbsent("skipSourceNode", skipSourceNode);
        }
    }

    private String firstNonBlank(Object... values) {
        if (values == null) return null;
        for (Object value : values) {
            if (value == null) continue;
            String text = value.toString();
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private void enrichTriggerFields(WorkflowStepDataEntity entity, Map<String, Object> output, Map<String, Object> metadata) {
        putIfNotNull(metadata, "trigger_type", output.get("trigger_type"));
        putIfNotNull(metadata, "items_spawned", output.get("items_spawned"));
        putIfNotNull(metadata, "itemsSpawned", output.get("itemsSpawned"));
    }

    private void enrichDecisionFields(WorkflowStepDataEntity entity, Map<String, Object> output, Map<String, Object> metadata) {
        if (output.get("selected_branch") != null) entity.setSelectedBranch(output.get("selected_branch").toString());
        if (output.get("condition_expression") != null) entity.setConditionExpression(output.get("condition_expression").toString());
        if (output.get("condition_result") instanceof Boolean) entity.setConditionResult((Boolean) output.get("condition_result"));

        // Store evaluations in metadata (small structured data)
        putIfNotNull(metadata, "evaluations", output.get("evaluations"));
        putIfNotNull(metadata, "skipped_branches", output.get("skipped_branches"));
        putIfNotNull(metadata, "condition_resolved", output.get("condition_resolved"));
        putIfNotNull(metadata, "selected_branch_index", output.get("selected_branch_index"));
    }

    private void enrichSwitchFields(WorkflowStepDataEntity entity, Map<String, Object> output, Map<String, Object> metadata) {
        if (output.get("selected_branch") != null) entity.setSelectedBranch(output.get("selected_branch").toString());
        if (output.get("selected_case") != null) entity.setSelectedBranch(output.get("selected_case").toString());

        putIfNotNull(metadata, "switch_expression", output.get("switch_expression"));
        putIfNotNull(metadata, "switch_value", output.get("switch_value"));
        putIfNotNull(metadata, "selected_case", output.get("selected_case"));
        putIfNotNull(metadata, "cases", output.get("cases"));
        putIfNotNull(metadata, "skipped_branches", output.get("skipped_branches"));
    }

    private void enrichOptionFields(WorkflowStepDataEntity entity, Map<String, Object> output, Map<String, Object> metadata) {
        if (output.get("selected_branch") != null) {
            entity.setSelectedBranch(output.get("selected_branch").toString());
        } else if (output.get("selected_choice_index") instanceof Number selectedChoiceIndex && selectedChoiceIndex.intValue() >= 0) {
            entity.setSelectedBranch("choice_" + selectedChoiceIndex.intValue());
        }

        putIfNotNull(metadata, "selected_choice", output.get("selected_choice"));
        putIfNotNull(metadata, "selected_label", output.get("selected_label"));
        putIfNotNull(metadata, "selected_choice_index", output.get("selected_choice_index"));
        putIfNotNull(metadata, "selected_branches", output.get("selected_branches"));
        putIfNotNull(metadata, "skipped_branches", output.get("skipped_branches"));
        putIfNotNull(metadata, "evaluations", output.get("evaluations"));
    }

    @SuppressWarnings("unchecked")
    private void enrichSplitFields(WorkflowStepDataEntity entity, Map<String, Object> output, Map<String, Object> metadata) {
        // New simplified split system uses "terminated" field
        // Map it to loopExitReason for state reconstruction compatibility
        if (Boolean.TRUE.equals(output.get("terminated"))) {
            String spawnReason = output.get("spawn_reason") != null
                ? output.get("spawn_reason").toString()
                : "split_completed";
            entity.setLoopExitReason(spawnReason);
        }

        // Also set split_id as loopId for proper entity association
        if (output.get("split_id") != null) {
            entity.setLoopId(output.get("split_id").toString());
        }

        putIfNotNull(metadata, "item_count", output.get("item_count"));
        putIfNotNull(metadata, "processed_items", output.get("processed_items"));
        putIfNotNull(metadata, "list_expression", output.get("list_expression"));
        putIfNotNull(metadata, "split_strategy", output.get("split_strategy"));
        putIfNotNull(metadata, "spawn_parallel_items", output.get("spawn_parallel_items"));
        putIfNotNull(metadata, "current_item", output.get("current_item"));
        putIfNotNull(metadata, "spawn_reason", output.get("spawn_reason"));
        putIfNotNull(metadata, "terminated", output.get("terminated"));
    }

    @SuppressWarnings("unchecked")
    private void enrichMergeFields(WorkflowStepDataEntity entity, Map<String, Object> output, Map<String, Object> metadata) {
        // Strategy field: merge output uses "strategy" (from MergeStrategy.name())
        Object strategy = output.get("strategy");
        if (strategy == null) strategy = output.get("merge_strategy"); // legacy fallback
        if (strategy != null) entity.setMergeStrategy(strategy.toString());

        // Source branches: extract from "sources" map keys (the actual output structure)
        if (output.get("sources") instanceof Map) {
            List<String> receivedBranches = new ArrayList<>(((Map<String, Object>) output.get("sources")).keySet());
            entity.setMergeReceivedBranches(receivedBranches);
        } else if (output.get("received_branches") instanceof List) {
            entity.setMergeReceivedBranches((List<String>) output.get("received_branches"));
        }

        if (output.get("skipped_branches") instanceof List) {
            entity.setMergeSkippedBranches((List<String>) output.get("skipped_branches"));
        }

        putIfNotNull(metadata, "source_count", output.get("source_count"));
        putIfNotNull(metadata, "success_count", output.get("success_count"));
        putIfNotNull(metadata, "item_count", output.get("item_count"));
        putIfNotNull(metadata, "merge_states", output.get("merge_states"));
        putIfNotNull(metadata, "waiting_for", output.get("waiting_for"));
    }

    private void enrichForkFields(WorkflowStepDataEntity entity, Map<String, Object> output, Map<String, Object> metadata) {
        putIfNotNull(metadata, "branches", output.get("branches"));
        putIfNotNull(metadata, "branches_count", output.get("branches_count"));
    }

    private void enrichAggregateFields(WorkflowStepDataEntity entity, Map<String, Object> output, Map<String, Object> metadata) {
        putIfNotNull(metadata, "aggregated_count", output.get("aggregated_count"));
        putIfNotNull(metadata, "fields", output.get("fields"));
    }

    private void enrichAgentFields(WorkflowStepDataEntity entity, Map<String, Object> output, Map<String, Object> metadata) {
        putIfNotNull(metadata, "model", output.get("model"));
        putIfNotNull(metadata, "provider", output.get("provider"));
        putIfNotNull(metadata, "iterations", output.get("iterations"));
        // tool_calls_detail is the raw list; derive count for metadata (tool_calls is now the array in DB schema)
        Object toolCallsRaw = output.get("tool_calls_detail");
        if (toolCallsRaw instanceof List<?> toolCallsList) {
            metadata.put("tool_calls", toolCallsList.size());
        } else {
            putIfNotNull(metadata, "tool_calls", output.get("tool_calls"));
        }
        putIfNotNull(metadata, "tokens_used", output.get("tokens_used"));
        putIfNotNull(metadata, "promptTokens", output.get("promptTokens"));
        putIfNotNull(metadata, "completionTokens", output.get("completionTokens"));

        // Handle CLASSIFY agent type - map selected_category to selectedBranch for branch propagation
        String nodeType = (String) output.get("node_type");
        if ("CLASSIFY".equals(nodeType)) {
            Object selectedCategory = output.get("selected_category");
            Object selectedCategoryIndex = output.get("selected_category_index");
            if (selectedCategoryIndex != null) {
                // Use the index-based port name for branch routing
                String branch = "category_" + selectedCategoryIndex;
                entity.setSelectedBranch(branch);
                logger.info("🏷️ Classify branch selection: category={}, selectedBranch={}", selectedCategory, branch);
            } else if (selectedCategory != null) {
                // Fallback to category label if index not available
                entity.setSelectedBranch(selectedCategory.toString());
                logger.info("🏷️ Classify branch selection (label fallback): selectedBranch={}", selectedCategory);
            }
            putIfNotNull(metadata, "selected_category", output.get("selected_category"));
            putIfNotNull(metadata, "selected_category_index", output.get("selected_category_index"));
            putIfNotNull(metadata, "confidence", output.get("confidence"));
            putIfNotNull(metadata, "reasoning", output.get("reasoning"));
        }

        // Handle GUARDRAIL agent type - map passed/failed to selectedBranch for branch propagation
        if ("GUARDRAIL".equals(nodeType)) {
            Object passed = output.get("passed");
            if (passed instanceof Boolean) {
                String branch = (Boolean) passed ? "pass" : "fail";
                entity.setSelectedBranch(branch);
                logger.info("🛡️ Guardrail branch selection: passed={}, selectedBranch={}", passed, branch);
            }
            putIfNotNull(metadata, "passed", output.get("passed"));
            putIfNotNull(metadata, "violations", output.get("violations"));
        }
    }

    private void enrichMcpFields(WorkflowStepDataEntity entity, Map<String, Object> output, Map<String, Object> metadata) {
        // Extract tool/api info
        String toolId = entity.getToolId();
        if (toolId != null && toolId.contains("/")) {
            String[] parts = toolId.split("/", 2);
            metadata.put("apiName", parts[0]);
            metadata.put("toolName", parts.length > 1 ? parts[1] : parts[0]);
        } else if (toolId != null) {
            metadata.put("toolName", toolId);
        }
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractInputData(StepExecutionResult result) {
        if (result.output() == null) return Collections.emptyMap();
        // Single source of truth: every node writes its resolved configuration (after
        // template + SpEL resolution) under the `resolved_params` key. This is what
        // feeds the inspector "Resolved parameters" panel via the DB `input_data` column.
        Object input = result.output().get("resolved_params");
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> resolvedParamsMap = (Map<String, Object>) map;

            // If resolved_params has a single "parameters" wrapper key with a Map value, unwrap it
            if (resolvedParamsMap.size() == 1 && resolvedParamsMap.containsKey("parameters")
                    && resolvedParamsMap.get("parameters") instanceof Map) {
                resolvedParamsMap = (Map<String, Object>) resolvedParamsMap.get("parameters");
            }

            Map<String, Object> copied = new HashMap<>();
            resolvedParamsMap.forEach((k, v) -> {
                if (v instanceof String str) {
                    // Filter invalid templates, unresolved expressions, and JS artifacts
                    if (str.startsWith("INVALID_TEMPLATE:") || str.contains("${")
                            || "[object Object]".equals(str)) {
                        return;
                    }
                }
                copied.put(k, v);
            });
            return copied;
        }
        return Collections.emptyMap();
    }

    private String computeNormalizedKey(String stepId, String stepLabel, NodeType nodeType) {
        if (stepId != null && LabelNormalizer.isNormalizedKey(stepId)) return stepId;
        String label = stepLabel != null ? stepLabel : stepId;
        if (label == null) return null;
        String prefix = determineNodeTypePrefix(stepId, nodeType);
        String normalizedLabel = LabelNormalizer.normalizeLabel(label);
        return prefix + ":" + (normalizedLabel != null ? normalizedLabel : label);
    }

    private String determineNodeTypePrefix(String stepId, NodeType nodeType) {
        // Extract prefix directly from stepId if it has a known prefix
        if (stepId != null && LabelNormalizer.isNormalizedKey(stepId)) {
            return stepId.substring(0, stepId.indexOf(":"));
        }
        if (stepId != null) {
            String lower = stepId.toLowerCase();
            if (lower.startsWith("trigger:") || lower.contains("trigger")) return "trigger";
            if (lower.startsWith("agent:")) return "agent";
        }
        if (nodeType != null) {
            return nodeType.getPrefix();
        }
        return "mcp";
    }

    private Integer extractInteger(StepExecutionResult result, String key) {
        if (result.output() == null) return null;
        Object value = result.output().get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private int extractIntegerOrDefault(StepExecutionResult result, String key, int defaultValue) {
        Integer val = extractInteger(result, key);
        return val != null ? val : defaultValue;
    }

    private Long extractLong(StepExecutionResult result, String key) {
        if (result.output() == null) return null;
        Object value = result.output().get(key);
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}

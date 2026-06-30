package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.common.storage.exception.QuotaExceededException;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.utils.ExecutionConstants;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service responsible for persisting step payload data to storage.
 * Handles the transformation and storage of step execution outputs.
 *
 * Uses OutputSchemaMapper to transform backend internal output format
 * to the expected DB schema format before storage.
 */
@Service
public class StepPayloadService {

    private static final Logger logger = LoggerFactory.getLogger(StepPayloadService.class);

    private final StorageService storageService;
    private final OutputSchemaMapper outputSchemaMapper;

    public StepPayloadService(StorageService storageService, OutputSchemaMapper outputSchemaMapper) {
        this.storageService = storageService;
        this.outputSchemaMapper = outputSchemaMapper;
    }

    /**
     * Persists the payload for a step execution to storage.
     *
     * @param execution The workflow execution
     * @param stepId The step ID
     * @param stepAliasOrId The step alias or ID
     * @param result The step execution result (can be null for decisions)
     * @param extraMetadata Additional metadata to include
     * @param epoch The trigger epoch number
     * @return The storage UUID, or null if persistence failed
     */
    public UUID persistStepPayload(WorkflowExecution execution,
                                   String stepId,
                                   String stepAliasOrId,
                                   StepExecutionResult result,
                                   Map<String, Object> extraMetadata,
                                   int epoch) {
        Integer itemIndex = extractItemIndex(result);
        int spawn = extractSpawn(result);
        return persistStepPayloadWithContext(execution, stepId, stepAliasOrId, result, extraMetadata, itemIndex, epoch, spawn);
    }

    /**
     * Persists the payload for a step execution with explicit epoch and spawn.
     */
    public UUID persistStepPayload(WorkflowExecution execution,
                                   String stepId,
                                   String stepAliasOrId,
                                   StepExecutionResult result,
                                   Map<String, Object> extraMetadata,
                                   int epoch,
                                   int spawn) {
        Integer itemIndex = extractItemIndex(result);
        return persistStepPayloadWithContext(execution, stepId, stepAliasOrId, result, extraMetadata, itemIndex, epoch, spawn);
    }

    /**
     * Persists the payload for a step execution to storage with explicit item index.
     * @deprecated Use the overload with spawn parameter instead.
     */
    public UUID persistStepPayloadWithContext(WorkflowExecution execution,
                                              String stepId,
                                              String stepAliasOrId,
                                              StepExecutionResult result,
                                              Map<String, Object> extraMetadata,
                                              Integer itemIndex,
                                              int epoch) {
        return persistStepPayloadWithContext(execution, stepId, stepAliasOrId, result, extraMetadata, itemIndex, epoch, 0);
    }

    /**
     * Persists the payload for a step execution to storage with explicit item index and spawn.
     */
    public UUID persistStepPayloadWithContext(WorkflowExecution execution,
                                              String stepId,
                                              String stepAliasOrId,
                                              StepExecutionResult result,
                                              Map<String, Object> extraMetadata,
                                              Integer itemIndex,
                                              int epoch,
                                              int spawn) {
        // If result is null (for decisions), use extraMetadata as payload
        if (result == null) {
            return persistDecisionPayload(execution, stepId, stepAliasOrId, extraMetadata, itemIndex, epoch);
        }

        // For normal steps, process the output
        Map<String, Object> payload = buildStepPayload(stepId, result, extraMetadata);

        Optional<String> toolIdOpt = execution.getPlan()
                .findStep(stepAliasOrId != null ? stepAliasOrId : stepId)
                .map(step -> step.id() != null ? step.id() : null);

        UUID toolUuid = toolIdOpt.flatMap(this::parseUuid).orElse(null);

        // Build step key for context querying
        String stepKey = buildStepKey(stepId, stepAliasOrId);
        String runId = execution.getRunId();

        try {
            return storageService.saveJsonWithContext(
                    execution.getPlan().getTenantId(),
                    payload,
                    ExecutionConstants.CONTENT_TYPE_JSON,
                    null,
                    toolUuid,
                    runId,
                    stepKey,
                    itemIndex != null ? itemIndex : 0,
                    epoch,
                    spawn,
                    execution.getPlan().getId(),
                    "STEP_OUTPUT"
            );
        } catch (QuotaExceededException quota) {
            // F2: distinguish quota-exceeded from generic I/O so operators can
            // route the alert (tenant action vs infra action). Caller (StepData-
            // PersistenceService) stamps the row's error_message so the failure
            // surfaces to the user - never a silent null storageId.
            logger.error("Storage quota exceeded persisting output payload for step {} (run {}, tenant {}): {}",
                    stepId, execution.getRunId(), quota.getTenantId(), quota.getMessage());
            return null;
        } catch (Exception e) {
            logger.error("Unable to persist output payload for step {} (run {}): [{}] {}",
                    stepId, execution.getRunId(), e.getClass().getSimpleName(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Extract spawn from result output if available.
     */
    private int extractSpawn(StepExecutionResult result) {
        if (result == null || result.output() == null) {
            return 0;
        }
        Object spawnObj = result.output().get("spawn");
        if (spawnObj instanceof Number) {
            return ((Number) spawnObj).intValue();
        }
        return 0;
    }

    /**
     * Extract item index from result output if available.
     */
    private Integer extractItemIndex(StepExecutionResult result) {
        if (result == null || result.output() == null) {
            return 0;
        }
        Object itemIndexObj = result.output().get("item_index");
        if (itemIndexObj == null) {
            itemIndexObj = result.output().get("itemIndex");
        }
        if (itemIndexObj instanceof Number) {
            return ((Number) itemIndexObj).intValue();
        }
        return 0;
    }

    /**
     * Build the step key with proper prefix (mcp:alias, trigger:alias, etc.)
     */
    private String buildStepKey(String stepId, String stepAliasOrId) {
        String lookupKey = stepAliasOrId != null ? stepAliasOrId : stepId;
        if (lookupKey == null) return null;

        // If already has a prefix, normalize the alias part
        if (lookupKey.contains(":")) {
            String[] parts = lookupKey.split(":", 2);
            String prefix = parts[0];
            String alias = LabelNormalizer.normalizeLabel(parts[1].split(":")[0]); // Handle core:label:port
            return prefix + ":" + alias;
        }

        // No prefix in lookupKey - derive prefix from stepId if available
        // This ensures core:, agent:, trigger:, table: nodes get the correct prefix
        if (stepId != null && stepId.contains(":")) {
            String prefix = stepId.substring(0, stepId.indexOf(":"));
            String normalizedAlias = LabelNormalizer.normalizeLabel(lookupKey);
            return prefix + ":" + normalizedAlias;
        }

        // Default to mcp prefix (unprefixed step IDs are MCP tool calls)
        String normalizedAlias = LabelNormalizer.normalizeLabel(lookupKey);
        return "mcp:" + normalizedAlias;
    }

    /**
     * Persists payload for skipped nodes.
     */
    public UUID persistSkippedNodePayload(String tenantId, Map<String, Object> skipPayload) {
        return persistSkippedNodePayload(tenantId, skipPayload, 0);
    }

    /**
     * Persists payload for skipped nodes with explicit epoch.
     *
     * @param tenantId The tenant ID
     * @param skipPayload The skip payload data
     * @param epoch The trigger epoch number (0 = unset)
     * @return The storage UUID, or null if persistence failed
     */
    public UUID persistSkippedNodePayload(String tenantId, Map<String, Object> skipPayload, int epoch) {
        try {
            return storageService.saveJsonWithContext(
                    tenantId,
                    skipPayload,
                    ExecutionConstants.CONTENT_TYPE_JSON,
                    null,
                    null, null, null, null, epoch,
                    null,
                    "SKIPPED_NODE"
            );
        } catch (QuotaExceededException quota) {
            logger.error("Storage quota exceeded persisting skipped node payload (tenant {}): {}",
                    quota.getTenantId(), quota.getMessage());
            return null;
        } catch (Exception e) {
            logger.error("Unable to persist skipped node payload: [{}] {}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            return null;
        }
    }

    private UUID persistDecisionPayload(WorkflowExecution execution,
                                        String stepId,
                                        String stepAliasOrId,
                                        Map<String, Object> extraMetadata,
                                        Integer itemIndex,
                                        int epoch) {
        String stepKey = buildStepKey(stepId, stepAliasOrId);
        String runId = execution.getRunId();

        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            // Inject standard execution envelope for decision payloads
            extraMetadata.put("_status", "COMPLETED");
            extraMetadata.put("_duration_ms", 0L);
            if (execution.getDisplayName() != null) {
                extraMetadata.put("_display_name", execution.getDisplayName());
            }

            Optional<String> toolIdOpt = execution.getPlan()
                    .findStep(stepAliasOrId != null ? stepAliasOrId : stepId)
                    .map(step -> step.id() != null ? step.id() : null);
            UUID toolUuid = toolIdOpt.flatMap(this::parseUuid).orElse(null);
            try {
                return storageService.saveJsonWithContext(
                        execution.getPlan().getTenantId(),
                        extraMetadata,
                        ExecutionConstants.CONTENT_TYPE_JSON,
                        null,
                        toolUuid,
                        runId,
                        stepKey,
                        itemIndex != null ? itemIndex : 0,
                        epoch,
                        execution.getPlan().getId(),
                        "DECISION"
                );
            } catch (QuotaExceededException quota) {
                logger.error("Storage quota exceeded persisting decision payload for {} (run {}, tenant {}): {}",
                        stepId, execution.getRunId(), quota.getTenantId(), quota.getMessage());
                return null;
            } catch (Exception e) {
                logger.error("Unable to persist decision payload for {} (run {}): [{}] {}",
                        stepId, execution.getRunId(), e.getClass().getSimpleName(), e.getMessage(), e);
                return null;
            }
        }
        // If extraMetadata is empty, create minimal payload with envelope
        Map<String, Object> minimalPayload = new HashMap<>();
        minimalPayload.put("_status", "COMPLETED");
        minimalPayload.put("_duration_ms", 0L);
        if (execution.getDisplayName() != null) {
            minimalPayload.put("_display_name", execution.getDisplayName());
        }
        return storageService.saveJsonWithContext(
                execution.getPlan().getTenantId(),
                minimalPayload,
                ExecutionConstants.CONTENT_TYPE_JSON,
                null,
                null,
                runId,
                stepKey,
                itemIndex != null ? itemIndex : 0,
                epoch,
                execution.getPlan().getId(),
                "DECISION"
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildStepPayload(String stepId, StepExecutionResult result, Map<String, Object> extraMetadata) {
        Map<String, Object> payload = new HashMap<>();

        if (result.output() != null && !result.output().isEmpty()) {
            Map<String, Object> outputClean = new HashMap<>(result.output());

            // Detect node type for schema transformation
            String nodeType = (String) outputClean.get("node_type");
            boolean isCoreNode = isCoreNodeType(nodeType);
            boolean isAgentNode = isAgentNodeType(nodeType);
            boolean isTableNode = isTableNodeType(nodeType);
            boolean isTriggerNode = isTriggerNodeType(nodeType)
                    || isTriggerStepId(stepId)
                    || isTriggerStepId(result.stepId());
            boolean preserveFields = isCoreNode || isAgentNode || isTableNode || isTriggerNode;

            // Preserve error info before schema transformation (mappers would strip it)
            String errorMessage = outputClean.get("error") instanceof String s ? s : null;

            // Transform output to expected DB schema format BEFORE removing redundant fields.
            // Schema mappers select exactly the fields they need from raw output,
            // so removeRedundantFields is not needed for schema-mapped node types.
            if (needsSchemaTransformation(nodeType)) {
                outputClean = outputSchemaMapper.transformToDbSchema(outputClean, nodeType);
                // Re-inject error after schema transformation so it's visible in the persisted output
                if (errorMessage != null) {
                    outputClean.put("error", errorMessage);
                }
            } else {
                // Remove fields that are already in step_data columns
                // But for core/agent nodes, preserve their output fields for UI display
                removeRedundantFields(outputClean, preserveFields);
            }

            // Inject standard execution envelope fields
            outputClean.put("_status", result.status().name());
            if (result.isFailure() && result.message() != null) {
                outputClean.put("_error", result.message());
            }
            outputClean.put("_duration_ms", result.executionTime());
            if (extraMetadata != null && extraMetadata.containsKey("__displayName__")) {
                outputClean.put("_display_name", extraMetadata.get("__displayName__"));
            }

            if (!outputClean.isEmpty()) {
                // Avoid double wrapping: if outputClean already has "output" key, use it directly
                if (outputClean.containsKey("output") && outputClean.get("output") instanceof Map) {
                    Map<String, Object> innerOutput = (Map<String, Object>) outputClean.get("output");
                    payload.put("output", innerOutput);
                    outputClean.remove("output");
                    payload.putAll(outputClean);
                } else {
                    payload.put("output", outputClean);
                }
            }

        }

        // Add extraMetadata to payload
        if (extraMetadata != null && !extraMetadata.isEmpty()) {
            payload.putAll(extraMetadata);
        }

        return payload;
    }

    /**
     * Check if this node type needs schema transformation.
     * Includes core nodes and agent nodes.
     */
    private boolean needsSchemaTransformation(String nodeType) {
        if (nodeType == null) return false;
        return outputSchemaMapper.hasMapper(nodeType);
    }

    /**
     * Check if this is a core node type that needs its output fields preserved in storage.
     */
    private boolean isCoreNodeType(String nodeType) {
        if (nodeType == null) return false;
        return switch (nodeType.toUpperCase()) {
            case "DECISION", "SWITCH", "LOOP", "SPLIT", "MERGE", "FORK", "AGGREGATE",
                 "WAIT", "TRANSFORM", "EXIT", "DOWNLOAD_FILE", "RESPONSE", "OPTION",
                 "FIND" -> true;
            default -> false;
        };
    }

    /**
     * Check if this is an agent node type.
     */
    private boolean isAgentNodeType(String nodeType) {
        if (nodeType == null) return false;
        return switch (nodeType.toUpperCase()) {
            case "AGENT", "CLASSIFY", "GUARDRAIL" -> true;
            default -> false;
        };
    }

    /**
     * Check if this is a table (CRUD) node type.
     */
    private boolean isTableNodeType(String nodeType) {
        if (nodeType == null) return false;
        return switch (nodeType.toUpperCase()) {
            case "INSERT_ROW", "GET_ROWS", "UPDATE_ROW", "DELETE_ROW", "CREATE_COLUMN" -> true;
            default -> false;
        };
    }

    /**
     * Check if this is a trigger node type.
     */
    private boolean isTriggerNodeType(String nodeType) {
        if (nodeType == null) return false;
        return switch (nodeType.toUpperCase()) {
            case "MANUAL_TRIGGER", "CHAT_TRIGGER", "WEBHOOK_TRIGGER", "SCHEDULE_TRIGGER",
                 "FORM_TRIGGER", "WORKFLOW_TRIGGER", "TABLE_TRIGGER" -> true;
            default -> false;
        };
    }

    private boolean isTriggerStepId(String stepId) {
        return stepId != null && stepId.startsWith("trigger:");
    }

    /**
     * Remove redundant fields from output before storing in blob storage.
     *
     * @param output The output map to clean
     * @param preserveCoreNodeFields If true, keep core node output fields for UI display
     */
    private void removeRedundantFields(Map<String, Object> output, boolean preserveCoreNodeFields) {
        // === Fields already in step_data columns - always remove ===
        // resolved_params is persisted to the dedicated `input_data` JSONB column by
        // StepDataPersistenceService.extractInputData(), so it's redundant in the blob.
        output.remove("resolved_params");
        output.remove("error");
        output.remove("http_status");
        if (!preserveCoreNodeFields) {
            output.remove("iteration");
            output.remove("currentIteration");
        }
        output.remove("epoch");
        output.remove("spawn");
        output.remove("item_index");
        output.remove("itemIndex");
        output.remove("itemId");
        output.remove("item_id");
        output.remove("triggerId");
        output.remove("trigger_id");
        output.remove("absoluteIndex");
        output.remove("tenantId");
        output.remove("tenant_id");
        output.remove("status");
        output.remove("step_id");
        output.remove("execution_time");
        output.remove("execution_duration");
        output.remove("mock");
        output.remove("execution_mode");

        // === Node type identifier - remove for non-core nodes ===
        if (!preserveCoreNodeFields) {
            output.remove("node_type");
        }

        // === Core node output fields - PRESERVE for UI display if this is a core node ===
        if (!preserveCoreNodeFields) {
            // Decision node logic
            output.remove("decision_node");
            output.remove("branches_evaluated");
            output.remove("selected_branch");
            output.remove("selected_branch_index");
            output.remove("skipped_branches");
            output.remove("evaluations");
            output.remove("condition_expression");
            output.remove("condition_result");
            output.remove("condition_resolved");

            // Switch node logic
            output.remove("switch_node");
            output.remove("switch_expression");
            output.remove("switch_value");
            output.remove("selected_case");
            output.remove("selected_case_index");
            output.remove("skipped_cases");
            output.remove("cases");
            output.remove("cases_evaluated");
            output.remove("matched_value");
            output.remove("match_result");

            // Loop node logic
            output.remove("loop_id");
            output.remove("loop_iteration");
            output.remove("loop_exit_reason");
            output.remove("max_iterations");
            output.remove("loop_condition");
            output.remove("carry");
            output.remove("reason");

            // Split node logic
            output.remove("item_count");
            output.remove("processed_items");
            output.remove("list_expression");
            output.remove("split_strategy");
            output.remove("spawn_parallel_items");
            output.remove("current_item");

            // Merge node logic
            output.remove("merge_strategy");
            output.remove("received_branches");
            output.remove("merge_states");
            output.remove("waiting_for");

            // Fork node logic
            output.remove("branches");
            output.remove("branches_count");

            // Option node logic
            output.remove("option_node");
            output.remove("choices_evaluated");
            output.remove("selected_choice");
            output.remove("selected_label");
            output.remove("selected_choice_index");
            output.remove("skipped_choices");
            // Note: evaluations, condition_expression, condition_result already handled by Decision

            // Aggregate node logic
            output.remove("aggregated_count");
            output.remove("received");
            output.remove("expected");
        }

        // Trigger node logic - always remove (these are in metadata)
        output.remove("trigger_type");
        output.remove("items_spawned");
        output.remove("itemsSpawned");

        // Agent node logic - preserve for schema mapper transformation
        // These fields are needed by ClassifyOutputSchemaMapper and AgentOutputSchemaMapper
        // The schema mappers will include only the fields they need
        if (!preserveCoreNodeFields) {
            output.remove("model");
            output.remove("provider");
            output.remove("iterations");
            output.remove("iterations_used");
            output.remove("tool_calls");
            output.remove("tokens_used");
            output.remove("promptTokens");
            output.remove("completionTokens");
            output.remove("durationMs");
            output.remove("reasoning");
            output.remove("selected_category");
            output.remove("selected_category_index");
            // Guardrail fields
            output.remove("passed");
            output.remove("violations");
            output.remove("details");
            output.remove("sanitized");
        }

        // === Execution metadata (not needed in storage) - always remove ===
        output.remove("success");
        output.remove("success_count");
        output.remove("failed_count");
        output.remove("total_items");
        output.remove("total_count");
        output.remove("total_processed");
        output.remove("errors");
        output.remove("missing");
        output.remove("is_running");
        output.remove("retry_count");
    }

    private Optional<UUID> parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

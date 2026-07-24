package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.common.storage.exception.QuotaExceededException;
import com.apimarketplace.common.storage.exception.StorageSerializationException;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.utils.ExecutionConstants;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

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

    /**
     * Deliberate bounded retry for TRANSIENT storage failures: max 2 attempts
     * total (1 retry). Quota and data-shaped failures are never retried - see
     * {@link #saveWithRetry}. This is THE single retry policy for step payload
     * writes; the old accidental second attempt in
     * {@code StepDataPersistenceService.recordStep} was folded into it.
     */
    static final int MAX_SAVE_ATTEMPTS = 2;

    /**
     * Backoff before the retry attempt, in milliseconds. Package-visible
     * setter for tests (zero it to keep tests fast).
     */
    private long retryBackoffMs = 150;

    private final StorageService storageService;
    private final OutputSchemaMapper outputSchemaMapper;

    public StepPayloadService(StorageService storageService, OutputSchemaMapper outputSchemaMapper) {
        this.storageService = storageService;
        this.outputSchemaMapper = outputSchemaMapper;
    }

    void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
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
        return persistStepPayloadOutcome(execution, stepId, stepAliasOrId, result, extraMetadata, itemIndex, epoch, spawn)
                .storageId();
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
        return persistStepPayloadOutcome(execution, stepId, stepAliasOrId, result, extraMetadata, itemIndex, epoch, spawn)
                .storageId();
    }

    /**
     * Discriminated-outcome variant with epoch + spawn (item index derived from
     * the result output). This is what the row-writing caller
     * ({@code StepDataPersistenceService.buildStepEntity}) uses so the failure
     * CAUSE reaches the step row.
     */
    public StepPayloadResult persistStepPayloadOutcome(WorkflowExecution execution,
                                                       String stepId,
                                                       String stepAliasOrId,
                                                       StepExecutionResult result,
                                                       Map<String, Object> extraMetadata,
                                                       int epoch,
                                                       int spawn) {
        Integer itemIndex = extractItemIndex(result);
        return persistStepPayloadOutcome(execution, stepId, stepAliasOrId, result, extraMetadata, itemIndex, epoch, spawn);
    }

    /**
     * Persists the payload for a step execution to storage with explicit item index.
     * @deprecated Use the overload with spawn parameter instead.
     */
    @Deprecated
    public UUID persistStepPayloadWithContext(WorkflowExecution execution,
                                              String stepId,
                                              String stepAliasOrId,
                                              StepExecutionResult result,
                                              Map<String, Object> extraMetadata,
                                              Integer itemIndex,
                                              int epoch) {
        return persistStepPayloadOutcome(execution, stepId, stepAliasOrId, result, extraMetadata, itemIndex, epoch, 0)
                .storageId();
    }

    /**
     * Persists the payload for a step execution to storage with explicit item index and spawn.
     *
     * @deprecated legacy flattening shim - collapses the discriminated outcome to a
     *             bare UUID (null on ANY failure). Row-writing callers must use
     *             {@link #persistStepPayloadOutcome} so the failure cause reaches
     *             the step row's error_message.
     */
    @Deprecated
    public UUID persistStepPayloadWithContext(WorkflowExecution execution,
                                              String stepId,
                                              String stepAliasOrId,
                                              StepExecutionResult result,
                                              Map<String, Object> extraMetadata,
                                              Integer itemIndex,
                                              int epoch,
                                              int spawn) {
        return persistStepPayloadOutcome(execution, stepId, stepAliasOrId, result, extraMetadata, itemIndex, epoch, spawn)
                .storageId();
    }

    /**
     * Persists the payload for a step execution and reports a DISCRIMINATED
     * outcome instead of flattening every failure to null.
     *
     * <p>Retry policy (the ONE deliberate mechanism - the old accidental second
     * attempt in {@code StepDataPersistenceService.recordStep} was folded in
     * here): up to {@value #MAX_SAVE_ATTEMPTS} attempts with a short backoff,
     * ONLY for transient causes. Never retried:
     * <ul>
     *   <li>{@link QuotaExceededException} - tenant action required;</li>
     *   <li>data-shaped failures ({@link StorageSerializationException},
     *       SQLSTATE class 22 e.g. 22P05) - the same bytes fail the same way
     *       every time. After the NUL-strip funnels 22P05 should be extinct;
     *       if it somehow occurs we still refuse to retry it.</li>
     * </ul>
     *
     * <p>Poison-safety contract: each attempt runs in a FRESH transaction.
     * {@code StorageService} is class-level {@code @Transactional} and no
     * caller up-stack ({@code StepDataPersistenceService.recordStep},
     * {@code WorkflowPersistenceService.recordStep},
     * {@code StepCompletionOrchestrator.complete}) is transactional, so each
     * {@code saveJsonWithContext} call opens and commits/rolls back its own
     * transaction - a failed first attempt cannot poison the retry. Pinned by
     * {@code StepPayloadRetryFreshTransactionIntegrationTest}: if someone adds
     * {@code @Transactional} up-stack, that test fails.
     */
    public StepPayloadResult persistStepPayloadOutcome(WorkflowExecution execution,
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

        return saveWithRetry(stepId, runId, () -> storageService.saveJsonWithContext(
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
        ));
    }

    /**
     * The single bounded-retry save loop. See {@link #persistStepPayloadOutcome}
     * for the policy; this method is the mechanism.
     */
    private StepPayloadResult saveWithRetry(String stepId, String runId, Supplier<UUID> save) {
        for (int attempt = 1; ; attempt++) {
            try {
                return StepPayloadResult.stored(save.get());
            } catch (QuotaExceededException quota) {
                // Distinguish quota-exceeded from generic I/O so operators can
                // route the alert (tenant action vs infra action). Never retried:
                // the quota does not free itself between attempts.
                logger.error("Storage quota exceeded persisting output payload for step {} (run {}, tenant {}): {}",
                        stepId, runId, quota.getTenantId(), quota.getMessage());
                return StepPayloadResult.failed(PayloadFailureCause.QUOTA_EXCEEDED);
            } catch (Exception e) {
                if (isDataShaped(e)) {
                    // Data-shaped (serialization failure or SQLSTATE class 22,
                    // e.g. 22P05 NUL-in-jsonb): retrying the same bytes cannot
                    // succeed - fail fast with the discriminated cause.
                    logger.error("Data-shaped storage failure persisting output payload for step {} (run {}): [{}] {}",
                            stepId, runId, e.getClass().getSimpleName(), e.getMessage(), e);
                    return StepPayloadResult.failed(PayloadFailureCause.SERIALIZATION);
                }
                if (attempt >= MAX_SAVE_ATTEMPTS) {
                    logger.error("Unable to persist output payload for step {} (run {}) after {} attempts: [{}] {}",
                            stepId, runId, attempt, e.getClass().getSimpleName(), e.getMessage(), e);
                    return StepPayloadResult.failed(PayloadFailureCause.TRANSIENT_EXHAUSTED);
                }
                logger.warn("Transient storage failure persisting output payload for step {} (run {}) - attempt {}/{}, retrying in {} ms: [{}] {}",
                        stepId, runId, attempt, MAX_SAVE_ATTEMPTS, retryBackoffMs,
                        e.getClass().getSimpleName(), e.getMessage());
                if (retryBackoffMs > 0) {
                    try {
                        Thread.sleep(retryBackoffMs);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        logger.warn("Interrupted during storage retry backoff for step {} (run {})", stepId, runId);
                        return StepPayloadResult.failed(PayloadFailureCause.TRANSIENT_EXHAUSTED);
                    }
                }
            }
        }
    }

    /**
     * True when the failure is data-shaped: the payload itself can never be
     * stored, so a retry is guaranteed to fail identically. Covers our own
     * {@link StorageSerializationException} and any SQLException in the cause
     * chain with an SQLSTATE in class 22 ("data exception" - includes 22P05,
     * unsupported Unicode escape sequence / NUL in jsonb).
     */
    static boolean isDataShaped(Throwable e) {
        for (Throwable t = e; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof StorageSerializationException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("22")) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * Decision-payload sibling of {@link #persistStepPayloadOutcome} - same
     * discriminated outcome, same single retry mechanism ({@link #saveWithRetry}).
     *
     * <p>Pre-fix this method swallowed every failure into a silent null (and
     * the minimal-payload branch was not even guarded - an exception there
     * killed the whole step row in the caller's catch-all). Note the decision
     * ROW itself is written by {@code DecisionPersistenceService}, which
     * already stamps the {@code _storagePayloadFailed} metadata marker when
     * the returned storageId is null (F10) - this outcome adds the CAUSE for
     * callers that consume it.
     */
    private StepPayloadResult persistDecisionPayload(WorkflowExecution execution,
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
            return saveWithRetry(stepId, runId, () -> storageService.saveJsonWithContext(
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
            ));
        }
        // If extraMetadata is empty, create minimal payload with envelope
        Map<String, Object> minimalPayload = new HashMap<>();
        minimalPayload.put("_status", "COMPLETED");
        minimalPayload.put("_duration_ms", 0L);
        if (execution.getDisplayName() != null) {
            minimalPayload.put("_display_name", execution.getDisplayName());
        }
        return saveWithRetry(stepId, runId, () -> storageService.saveJsonWithContext(
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
        ));
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

            // Preserve the mock markers before schema transformation (generic mappers
            // rebuild the map from declared fields and would drop them) - the persisted
            // __mocked__/__mock_source__ keys are what the inspector badge and the
            // agent's get_node_output "mocked" flag read.
            boolean mocked = com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys
                    .isMocked(outputClean);
            Object mockSource = outputClean.get(
                    com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys.MOCK_SOURCE);

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

            // Re-inject the mock markers after transformation/cleanup (both paths):
            // the badge must survive for every mocked node type.
            if (mocked) {
                outputClean.put(
                        com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys.MOCKED,
                        Boolean.TRUE);
                if (mockSource != null) {
                    outputClean.put(
                            com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys.MOCK_SOURCE,
                            mockSource);
                }
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

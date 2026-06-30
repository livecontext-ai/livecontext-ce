package com.apimarketplace.orchestrator.stepdata;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Maps WorkflowStepDataEntity to row data for frontend display.
 * Extracts node-specific fields based on nodeType.
 */
@Component
public class StepDataRowMapper {

    /**
     * Map an entity to a row with node-specific fields.
     *
     * @param entity      The step data entity
     * @param outputData  The output data from storage (can be null)
     * @param rowIndex    The 1-based row index for display (used as fallback for itemNumber)
     * @return Map of field name to value
     */
    public Map<String, Object> mapToRow(WorkflowStepDataEntity entity, Map<String, Object> outputData, int rowIndex) {
        Map<String, Object> row = new LinkedHashMap<>();

        // Common fields
        addCommonFields(row, entity, rowIndex);

        // Node-specific fields
        NodeType nodeType = entity.getNodeType();
        if (nodeType != null) {
            switch (nodeType) {
                case TRIGGER -> addTriggerFields(row, entity, outputData);
                case DECISION -> addDecisionFields(row, entity, outputData);
                case SWITCH -> addSwitchFields(row, entity, outputData);
                case LOOP_CONTROLLER -> addLoopFields(row, entity, outputData);
                case SPLIT_CONTROLLER -> addSplitFields(row, entity, outputData);
                case MERGE -> addMergeFields(row, entity, outputData);
                case FORK -> addForkFields(row, entity, outputData);
                case AGENT -> addAgentFields(row, entity, outputData);
                case MCP -> addMcpFields(row, entity, outputData);
                case TRANSFORM -> addTransformFields(row, entity, outputData);
                case WAIT -> addWaitFields(row, entity, outputData);
                case HTTP_REQUEST -> addHttpRequestFields(row, entity, outputData);
                case INSERT_ROW, GET_ROWS, UPDATE_ROW, DELETE_ROW, CREATE_COLUMN ->
                    addCrudFields(row, entity, outputData, entity.getToolId());
                default -> addGenericFields(row, entity, outputData);
            }
        } else {
            addGenericFields(row, entity, outputData);
        }

        return row;
    }

    /**
     * Map an entity to a row with node-specific fields (legacy - uses entity ID for itemNumber).
     *
     * @param entity      The step data entity
     * @param outputData  The output data from storage (can be null)
     * @return Map of field name to value
     */
    public Map<String, Object> mapToRow(WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        // Use itemNumber from entity, or fall back to entity ID
        int displayNumber = entity.getItemNumber() != null ? entity.getItemNumber() : entity.getId().intValue();
        return mapToRow(entity, outputData, displayNumber);
    }

    private void addCommonFields(Map<String, Object> row, WorkflowStepDataEntity entity, int rowIndex) {
        row.put("id", rowIndex);
        putIfNotNull(row, "status", entity.getStatus());
        putIfNotNull(row, "toolId", entity.getToolId());
        putIfNotNull(row, "nodeType", entity.getNodeType() != null ? entity.getNodeType().name() : null);
        putIfNotNull(row, "normalizedKey", entity.getNormalizedKey());

        // epoch: always include (even epoch 0 - the very first epoch)
        row.put("epoch", entity.getEpoch() != null ? entity.getEpoch() : 0);
        putIfNotNull(row, "spawn", entity.getSpawn());
        putIfNotNull(row, "iteration", entity.getIteration());
        putIfNotNull(row, "triggerId", entity.getTriggerId());

        if (entity.getStartTime() != null) {
            row.put("startTime", entity.getStartTime().toString());
        }
        if (entity.getEndTime() != null) {
            row.put("endTime", entity.getEndTime().toString());
        }
        if (entity.getStartTime() != null && entity.getEndTime() != null) {
            row.put("durationMs", Duration.between(entity.getStartTime(), entity.getEndTime()).toMillis());
        }

        putIfNotNull(row, "httpStatus", entity.getHttpStatus());

        // itemIndex defaults to 0 in entity - only include when actually set (>0)
        if (entity.getItemIndex() != null && entity.getItemIndex() > 0) {
            row.put("itemIndex", entity.getItemIndex());
        }
        putIfNotNull(row, "itemId", entity.getItemId());

        // Input data
        putIfNotNull(row, "input", entity.getInputData());

        // Skip tracking
        putIfNotNull(row, "skipReason", entity.getSkipReason());
        putIfNotNull(row, "skipSourceNode", entity.getSkipSourceNode());

        // Error (moved here from mapToRow - always part of common data)
        putIfNotNull(row, "errorMessage", entity.getErrorMessage());
    }

    private static void putIfNotNull(Map<String, Object> row, String key, Object value) {
        if (value != null) {
            row.put(key, value);
        }
    }

    /**
     * Extract output from raw storage data.
     * Tries outputData.get("output") first, falls back to raw outputData as-is.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractCleanOutput(Map<String, Object> outputData) {
        if (outputData == null) return null;

        // Prefer the nested "output" key if it's a Map
        Object nested = outputData.get("output");
        if (nested instanceof Map) {
            return (Map<String, Object>) nested;
        }

        return outputData;
    }

    private void addTriggerFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        Map<String, Object> metadata = entity.getMetadata();
        if (metadata != null) {
            putIfNotNull(row, "triggerType", metadata.getOrDefault("trigger_type", extractTriggerType(entity.getToolId())));
            Object itemsSpawned = metadata.get("items_spawned");
            if (itemsSpawned == null) itemsSpawned = metadata.get("itemsSpawned");
            putIfNotNull(row, "itemsSpawned", itemsSpawned);
        } else {
            putIfNotNull(row, "triggerType", extractTriggerType(entity.getToolId()));
        }

        Map<String, Object> cleanOutput = extractCleanOutput(outputData);
        if (cleanOutput != null) {
            row.put("output", cleanOutput);
        }
    }

    private String extractTriggerType(String toolId) {
        if (toolId == null) return "unknown";
        if (toolId.contains("webhook")) return "webhook";
        if (toolId.contains("schedule")) return "schedule";
        if (toolId.contains("manual")) return "manual";
        if (toolId.contains("chat")) return "chat";
        if (toolId.contains("datasource") || toolId.contains("tables")) return "datasource";
        return "trigger";
    }

    private void addDecisionFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        putIfNotNull(row, "selectedBranch", entity.getSelectedBranch());
        putIfNotNull(row, "conditionExpression", entity.getConditionExpression());
        putIfNotNull(row, "conditionResult", entity.getConditionResult());

        Map<String, Object> metadata = entity.getMetadata();
        if (metadata != null) {
            putIfNotNull(row, "evaluations", metadata.get("evaluations"));
            putIfNotNull(row, "skippedBranches", metadata.get("skipped_branches"));
            putIfNotNull(row, "conditionResolved", metadata.get("condition_resolved"));

            if (row.get("evaluations") == null && metadata.containsKey("decisionEvaluation")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> decisionEval = (Map<String, Object>) metadata.get("decisionEvaluation");
                putIfNotNull(row, "evaluations", decisionEval.get("conditions"));
            }
        }

        Map<String, Object> cleanOutput = extractCleanOutput(outputData);
        if (cleanOutput != null) {
            row.put("output", cleanOutput);
        }
    }

    private void addSwitchFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        putIfNotNull(row, "selectedBranch", entity.getSelectedBranch());

        Map<String, Object> metadata = entity.getMetadata();
        if (metadata != null) {
            putIfNotNull(row, "switchExpression", metadata.get("switch_expression"));
            putIfNotNull(row, "switchValue", metadata.get("switch_value"));
            putIfNotNull(row, "selectedCase", metadata.get("selected_case"));
            putIfNotNull(row, "cases", metadata.get("cases"));
            putIfNotNull(row, "skippedBranches", metadata.get("skipped_branches"));
        }

        Map<String, Object> cleanOutput = extractCleanOutput(outputData);
        if (cleanOutput != null) {
            row.put("output", cleanOutput);
        }
    }

    private void addLoopFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        putIfNotNull(row, "loopIteration", entity.getLoopIteration());
        putIfNotNull(row, "loopId", entity.getLoopId());
        putIfNotNull(row, "loopExitReason", entity.getLoopExitReason());
        putIfNotNull(row, "conditionExpression", entity.getConditionExpression());
        putIfNotNull(row, "conditionResult", entity.getConditionResult());

        Map<String, Object> metadata = entity.getMetadata();
        if (metadata != null) {
            putIfNotNull(row, "maxIterations", metadata.get("max_iterations"));
            putIfNotNull(row, "loopCondition", metadata.get("loop_condition"));
            putIfNotNull(row, "carryValue", metadata.get("carry"));

            Integer currentIteration = entity.getLoopIteration();
            Object maxIterObj = metadata.get("max_iterations");
            if (currentIteration != null && maxIterObj != null) {
                Map<String, Object> progress = new LinkedHashMap<>();
                progress.put("current", currentIteration);
                progress.put("max", maxIterObj);
                row.put("loopProgress", progress);
            }
        }

        Map<String, Object> cleanOutput = extractCleanOutput(outputData);
        if (cleanOutput != null) {
            row.put("output", cleanOutput);
        }
    }

    private void addSplitFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        putIfNotNull(row, "itemIndex", entity.getItemIndex());
        putIfNotNull(row, "currentIndex", entity.getItemIndex());

        Map<String, Object> metadata = entity.getMetadata();
        if (metadata != null) {
            putIfNotNull(row, "totalItems", metadata.get("item_count"));
            putIfNotNull(row, "processedItems", metadata.get("processed_items"));
            putIfNotNull(row, "list", metadata.get("list_expression"));
            putIfNotNull(row, "strategy", metadata.get("split_strategy"));
            putIfNotNull(row, "parallel", metadata.get("spawn_parallel_items"));
            putIfNotNull(row, "currentItem", metadata.get("current_item"));

            Object total = metadata.get("item_count");
            Object processed = metadata.get("processed_items");
            if (total != null) {
                Map<String, Object> progress = new LinkedHashMap<>();
                progress.put("total", total);
                progress.put("processed", processed != null ? processed : 0);
                row.put("splitProgress", progress);
            }
        }

        Map<String, Object> cleanOutput = extractCleanOutput(outputData);
        if (cleanOutput != null) {
            row.put("output", cleanOutput);
        }
    }

    private void addMergeFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        putIfNotNull(row, "mergeStrategy", entity.getMergeStrategy());
        putIfNotNull(row, "receivedBranches", entity.getMergeReceivedBranches());
        putIfNotNull(row, "skippedBranches", entity.getMergeSkippedBranches());

        List<String> received = entity.getMergeReceivedBranches();
        List<String> skipped = entity.getMergeSkippedBranches();
        if (received != null || skipped != null) {
            int receivedCount = received != null ? received.size() : 0;
            int skippedCount = skipped != null ? skipped.size() : 0;
            row.put("predecessorsCompleted", receivedCount);
            row.put("predecessorsSkipped", skippedCount);
            row.put("predecessorsTotal", receivedCount + skippedCount);
        }

        Map<String, Object> metadata = entity.getMetadata();
        if (metadata != null) {
            if (metadata.containsKey("merge_states")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mergeStates = (Map<String, Object>) metadata.get("merge_states");
                List<String> waitingFor = new ArrayList<>();
                mergeStates.forEach((key, value) -> {
                    if (!"COMPLETED".equals(value) && !"SKIPPED".equals(value)) {
                        waitingFor.add(key);
                    }
                });
                if (!waitingFor.isEmpty()) {
                    row.put("waitingFor", waitingFor);
                }
            }
            putIfNotNull(row, "waitingFor", metadata.get("waiting_for"));
        }

        Map<String, Object> cleanOutput = extractCleanOutput(outputData);
        if (cleanOutput != null) {
            row.put("output", cleanOutput);
        }
    }

    private void addForkFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        Map<String, Object> metadata = entity.getMetadata();
        if (metadata != null) {
            Object branches = metadata.get("branches");
            putIfNotNull(row, "branches", branches);
            if (branches instanceof List) {
                row.put("branchesCount", ((List<?>) branches).size());
            } else {
                putIfNotNull(row, "branchesCount", metadata.get("branches_count"));
            }
        }

        Map<String, Object> cleanOutput = extractCleanOutput(outputData);
        if (cleanOutput != null) {
            row.put("output", cleanOutput);
        }
    }

    private void addAgentFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        Map<String, Object> metadata = entity.getMetadata();
        if (metadata != null) {
            putIfNotNull(row, "model", metadata.get("model"));
            putIfNotNull(row, "provider", metadata.get("provider"));
            putIfNotNull(row, "llmIterations", metadata.get("iterations"));
            putIfNotNull(row, "toolCallsCount", metadata.get("tool_calls"));
            putIfNotNull(row, "tokensUsed", metadata.get("tokens_used"));
            putIfNotNull(row, "promptTokens", metadata.get("promptTokens"));
            putIfNotNull(row, "completionTokens", metadata.get("completionTokens"));
        }

        Map<String, Object> cleanOutput = extractCleanOutput(outputData);
        if (cleanOutput != null) {
            putIfNotNull(row, "response", extractAgentResponse(cleanOutput));
            row.put("output", cleanOutput);
        }
    }

    private String extractAgentResponse(Map<String, Object> outputData) {
        Object response = outputData.get("response");
        if (response != null) return response.toString();

        Object content = outputData.get("content");
        if (content != null) return content.toString();

        Object output = outputData.get("output");
        if (output instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> outputMap = (Map<String, Object>) output;
            Object innerContent = outputMap.get("content");
            if (innerContent != null) return innerContent.toString();
        }

        return null;
    }

    private void addMcpFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        String toolId = entity.getToolId();

        // Check for special step types
        boolean isTransform = toolId != null && toolId.contains("__transform__");
        boolean isWait = toolId != null && toolId.contains("__wait__");
        boolean isCrud = toolId != null && toolId.startsWith("crud/");
        boolean isHttpRequest = toolId != null && toolId.equals("http-request");

        if (isTransform) {
            addTransformFields(row, entity, outputData);
        } else if (isWait) {
            addWaitFields(row, entity, outputData);
        } else if (isCrud) {
            addCrudFields(row, entity, outputData, toolId);
        } else if (isHttpRequest) {
            addHttpRequestFields(row, entity, outputData);
        } else {
            addDefaultMcpFields(row, entity, outputData);
        }
    }

    private void addDefaultMcpFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        Map<String, Object> metadata = entity.getMetadata();
        String toolName = null;
        String apiName = null;

        if (metadata != null) {
            toolName = (String) metadata.get("toolName");
            apiName = (String) metadata.get("apiName");
        }

        if (toolName == null && entity.getToolId() != null) {
            String toolId = entity.getToolId();
            if (toolId.contains("/")) {
                String[] parts = toolId.split("/", 2);
                apiName = parts[0];
                toolName = parts.length > 1 ? parts[1] : parts[0];
            } else {
                toolName = toolId;
            }
        }

        putIfNotNull(row, "toolName", toolName);
        putIfNotNull(row, "apiName", apiName);

        Map<String, Object> cleanOutput = extractCleanOutput(outputData);
        if (cleanOutput != null) {
            row.put("output", cleanOutput);
        }
    }

    private void addTransformFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        Map<String, Object> inputData = entity.getInputData();
        if (inputData != null && inputData.containsKey("mappings")) {
            Object mappings = inputData.get("mappings");
            putIfNotNull(row, "mappings", mappings);
            if (mappings instanceof List) {
                row.put("mappingsCount", ((List<?>) mappings).size());
            }
        }

        Map<String, Object> cleanOutput = extractCleanOutput(outputData);
        if (cleanOutput != null) {
            row.put("output", cleanOutput);
        }
    }

    private void addWaitFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        Map<String, Object> inputData = entity.getInputData();
        if (inputData != null) {
            row.put("waitDuration", inputData.get("duration"));
        }

        // Actual wait is the duration
        if (entity.getStartTime() != null && entity.getEndTime() != null) {
            long actualWait = Duration.between(entity.getStartTime(), entity.getEndTime()).toMillis();
            row.put("actualWait", actualWait);
        }

        Map<String, Object> cleanOutput = extractCleanOutput(outputData);
        if (cleanOutput != null) {
            row.put("output", cleanOutput);
        }
    }

    private void addCrudFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData, String toolId) {
        String operation = resolveCrudOperation(entity.getNodeType(), toolId);
        row.put("operation", operation);

        Map<String, Object> inputData = entity.getInputData();
        if (inputData != null) {
            putIfNotNull(row, "whereClause", inputData.get("where"));
        }

        Map<String, Object> metadata = entity.getMetadata();
        if (metadata != null) {
            putIfNotNull(row, "dataSourceName", metadata.get("dataSourceName"));
        }

        Map<String, Object> cleanOutput = extractCleanOutput(outputData);
        if (cleanOutput != null) {
            Object rowsAffected = cleanOutput.get("rows_affected");
            if (rowsAffected == null) {
                rowsAffected = cleanOutput.get("rowsAffected");
            }
            if (rowsAffected == null && cleanOutput.containsKey("data")) {
                Object data = cleanOutput.get("data");
                if (data instanceof List) {
                    rowsAffected = ((List<?>) data).size();
                }
            }
            putIfNotNull(row, "rowsAffected", rowsAffected);
            row.put("output", cleanOutput);
        }
    }

    private void addHttpRequestFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        Map<String, Object> inputData = entity.getInputData();
        if (inputData != null) {
            putIfNotNull(row, "method", inputData.get("method"));
            putIfNotNull(row, "url", inputData.get("url"));
        }

        if (entity.getStartTime() != null && entity.getEndTime() != null) {
            row.put("responseTime", Duration.between(entity.getStartTime(), entity.getEndTime()).toMillis());
        }

        Map<String, Object> cleanOutput = extractCleanOutput(outputData);
        if (cleanOutput != null) {
            row.put("output", cleanOutput);
        }
    }

    private void addGenericFields(Map<String, Object> row, WorkflowStepDataEntity entity, Map<String, Object> outputData) {
        Map<String, Object> cleanOutput = extractCleanOutput(outputData);
        if (cleanOutput != null) {
            row.put("output", cleanOutput);
        }
    }

    /**
     * Resolve CRUD operation from NodeType first, then fall back to toolId string matching.
     */
    private String resolveCrudOperation(NodeType nodeType, String toolId) {
        // Primary: resolve from NodeType (always correct for table: nodes)
        if (nodeType != null) {
            return switch (nodeType) {
                case INSERT_ROW -> "INSERT";
                case GET_ROWS -> "READ";
                case UPDATE_ROW -> "UPDATE";
                case DELETE_ROW -> "DELETE";
                case CREATE_COLUMN -> "ADD_COLUMN";
                default -> resolveCrudOperationFromToolId(toolId);
            };
        }
        return resolveCrudOperationFromToolId(toolId);
    }

    private String resolveCrudOperationFromToolId(String toolId) {
        if (toolId == null) return "UNKNOWN";
        if (toolId.contains("insert") || toolId.contains("create")) return "INSERT";
        if (toolId.contains("read") || toolId.contains("get") || toolId.contains("query")) return "READ";
        if (toolId.contains("update")) return "UPDATE";
        if (toolId.contains("delete")) return "DELETE";
        return "UNKNOWN";
    }
}

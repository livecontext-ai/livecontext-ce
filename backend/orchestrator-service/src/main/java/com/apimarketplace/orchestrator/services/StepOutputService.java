package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.orchestrator.domain.StorageNestedModels.*;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for workflow step output data extraction and transformation.
 *
 * Extracted from WorkflowStepOutputController for Single Responsibility Principle.
 * Responsibilities:
 * - Extract useful data from nested JSON structures
 * - Merge outputs from multiple step executions
 * - Identify arrays in nested data
 * - Clean metadata from output data
 *
 * @see com.apimarketplace.orchestrator.controllers.workflow.WorkflowStepOutputController
 */
@Service
public class StepOutputService {

    private static final Logger logger = LoggerFactory.getLogger(StepOutputService.class);

    private static final String[] PRIORITY_ARRAY_FIELDS = {"items", "data", "results", "output", "rows", "records", "response"};
    private static final String[] DATA_KEYS = {"user", "items", "data", "results", "stories", "posts", "media", "comments", "users"};
    private static final String[] METADATA_KEYS = {
        "status", "message", "success", "errors", "mock", "execution",
        "step_id", "tool_id", "itemIndex", "item_index", "tenant_id",
        "triggerId", "http_status", "retry_count", "total_count",
        "total_items", "failed_count", "success_count", "execution_mode",
        "execution_time", "total_processed", "execution_duration",
        "is_running", "absoluteIndex", "full_output", "missing",
        "metadata", "apiId", "method", "endpoint", "toolName"
    };
    private static final String[] EXTRACTION_PATHS = {
        "output.output.response.data",
        "output.response.data",
        "response.data",
        "output.output.response",
        "output.response",
        "response",
        "output.output",
        "output",
        "data"
    };

    private final WorkflowStepDataRepository workflowStepDataRepository;
    private final StorageRepository storageRepository;
    private final StorageSkeletonService storageSkeletonService;
    private final ObjectMapper objectMapper;

    public StepOutputService(
            WorkflowStepDataRepository workflowStepDataRepository,
            StorageRepository storageRepository,
            StorageSkeletonService storageSkeletonService,
            ObjectMapper objectMapper) {
        this.workflowStepDataRepository = workflowStepDataRepository;
        this.storageRepository = storageRepository;
        this.storageSkeletonService = storageSkeletonService;
        this.objectMapper = objectMapper;
    }

    /**
     * Load raw output data from storage (no metadata enrichment).
     * Reusable by any service that needs the raw output map.
     *
     * @param outputStorageId the storage entity ID
     * @param tenantId        the tenant
     * @return parsed output map, or null if not found or empty
     */
    public Map<String, Object> loadRawOutput(UUID outputStorageId, String tenantId) {
        try {
            return storageRepository.findByIdAndTenantId(outputStorageId, tenantId)
                    .map(storage -> {
                        String dataJson = storage.getData();
                        if (dataJson == null || dataJson.isBlank()) return null;
                        try {
                            return objectMapper.readValue(dataJson, new TypeReference<Map<String, Object>>() {});
                        } catch (Exception e) {
                            logger.warn("Failed to parse output storage {}: {}", outputStorageId, e.getMessage());
                            return null;
                        }
                    })
                    .orElse(null);
        } catch (Exception e) {
            logger.warn("Failed to load output storage {}: {}", outputStorageId, e.getMessage());
            return null;
        }
    }

    /**
     * Durable per-item node outputs for one upstream node within a single epoch,
     * keyed by split item index. Used as a fallback by {@code SplitAggregateHandler}
     * when the in-memory {@code SplitContext.resultsByNode} lacks a routed item's
     * predecessor output (split→aggregate cross-pod async-agent resume, or after a
     * restart that emptied the in-memory cache - prod bug 2026-06-05).
     *
     * <p>Reads the per-item output rows straight from the storage table - ONE query
     * for the whole node ({@code idx_storage_run_step_epoch}-covered), each row already
     * carries its {@code itemIndex} + payload - so there is no N+1 over items and no
     * {@code workflow_step_data} round-trip. Epoch-scoped so a long-running cron run with
     * hundreds of epochs touches only the current epoch's rows; ordered oldest-first so a
     * later spawn/rerun for the same item index wins (last-write-wins, matching the
     * in-memory {@code SplitContext.withResultAtIndex} semantics).
     *
     * <p>Returns the node's OWN output object - the inner {@code output} map. This is the
     * persisted (schema-mapped) shape: for schema-mapped node types it equals what the warm
     * path produces after {@code buildItemEvalContext} re-maps the raw in-memory result (the
     * re-map is a no-op once {@code node_type} has been stripped at persist time); for
     * unmapped nodes the engine-envelope metadata fields may differ, but the business fields
     * a field expression reads are preserved. When a row has no {@code output} wrapper the
     * whole {@code data} map is returned (defensive).
     *
     * @param runId    the workflow run ID
     * @param stepKey  the upstream node key with prefix (e.g. {@code core:parse_headers});
     *                 matches {@code storage.step_key}
     * @param epoch    the epoch to scope to
     * @param tenantId the tenant that owns the output storage rows
     * @return map of split item index → node output (never {@code null}); empty if nothing durable exists
     */
    @SuppressWarnings("unchecked")
    public Map<Integer, Object> loadPerItemNodeOutputs(String runId, String stepKey, int epoch, String tenantId) {
        List<StorageEntity> rows = storageRepository.findByRunIdAndEpochAndStepKey(runId, epoch, stepKey, tenantId);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Object> byItem = new HashMap<>();
        for (StorageEntity row : rows) {
            String dataJson = row.getData();
            if (dataJson == null || dataJson.isBlank()) {
                continue;
            }
            Map<String, Object> data;
            try {
                data = objectMapper.readValue(dataJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                logger.warn("Failed to parse durable per-item output (run={}, step={}, epoch={}): {}",
                    runId, stepKey, epoch, e.getMessage());
                continue;
            }
            int itemIndex = row.getItemIndex() != null ? row.getItemIndex() : 0;
            // Persisted `data` wraps the node output under "output" (alongside metadata
            // like graphNodeId/recordedAt). The in-memory slot is the inner output map,
            // so unwrap to match it. Defensive: if no wrapper, use `data`.
            Object nodeOutput = (data.get("output") instanceof Map) ? data.get("output") : data;
            byItem.put(itemIndex, nodeOutput); // rows are createdAt ASC → latest spawn/rerun wins
        }
        return byItem;
    }

    /**
     * Durable per-item outputs for EVERY node that produced storage rows in one epoch,
     * keyed by {@code stepKey → (itemIndex → node output)}. The epoch-scoped superset of
     * {@link #loadPerItemNodeOutputs}: one query for the whole epoch instead of one per node.
     *
     * <p>Used by the split per-item read sites ({@code SplitAwareNodeExecutor.injectPredecessorPerItemOutputs}
     * and {@code SplitMergeHandler.aggregateResults}) to backfill per-item predecessor outputs the
     * in-memory {@code SplitContext.resultsByNode} is missing. The in-memory cache is per-pod RAM and
     * is emptied by a cross-pod async-agent/signal resume ({@code restoreContext} rebuilds items only),
     * a restart, or read before an async barrier seals - in all those cases a branch-rejoin merge (or
     * any cross-item consumer) silently collapses each item to a single "item 0" value. This is the same
     * durable fallback {@code SplitAggregateHandler.resolvePerItemResults} already applies for the
     * {@code core:aggregate} node, generalised to every per-item read site.
     *
     * <p>Same unwrap + latest-spawn/rerun-wins semantics as {@link #loadPerItemNodeOutputs}: for a
     * given {@code stepKey}+{@code itemIndex} the most recent write (greatest {@code createdAt}, then
     * {@code spawn}, then {@code id}) wins. This method does NOT rely on the row order returned by the
     * query: {@code findByRunIdAndEpoch} orders {@code createdAt ASC, id DESC} (its {@code id DESC}
     * tiebreak is for {@code RunContextService.buildPerItemContext}, a different consumer), whereas the
     * per-key sibling orders {@code id ASC}. Folding those raw orders with last-wins would pick opposite
     * rows on a same-millisecond tie, so we re-sort ascending by {@code (createdAt, spawn, id)} here and
     * let the last write win - keeping both durable read sites consistent. Returns an empty map when
     * nothing durable exists.
     *
     * @param runId    the workflow run ID
     * @param epoch    the epoch to scope to
     * @param tenantId the tenant that owns the output storage rows
     * @return {@code stepKey → (itemIndex → node output)}; never {@code null}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<Integer, Object>> loadPerItemOutputsByStepKey(String runId, int epoch, String tenantId) {
        List<StorageEntity> rows = storageRepository.findByRunIdAndEpoch(runId, epoch, tenantId);
        if (rows.isEmpty()) {
            return Map.of();
        }
        // Normalise to ascending (createdAt, spawn, id) so a blind put() lets the LATEST write win per
        // stepKey+itemIndex, independent of the query's id-DESC tiebreak (see javadoc). nullsFirst keeps
        // any row with a null sort field from throwing and orders it as "oldest".
        List<StorageEntity> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator
            .comparing(StorageEntity::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(StorageEntity::getSpawn, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(StorageEntity::getId, Comparator.nullsFirst(Comparator.naturalOrder())));
        Map<String, Map<Integer, Object>> byStepKey = new HashMap<>();
        for (StorageEntity row : ordered) {
            String stepKey = row.getStepKey();
            String dataJson = row.getData();
            if (stepKey == null || stepKey.isBlank() || dataJson == null || dataJson.isBlank()) {
                continue;
            }
            Map<String, Object> data;
            try {
                data = objectMapper.readValue(dataJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                logger.warn("Failed to parse durable per-item output (run={}, step={}, epoch={}): {}",
                    runId, stepKey, epoch, e.getMessage());
                continue;
            }
            int itemIndex = row.getItemIndex() != null ? row.getItemIndex() : 0;
            Object nodeOutput = (data.get("output") instanceof Map) ? data.get("output") : data;
            byStepKey.computeIfAbsent(stepKey, k -> new HashMap<>()).put(itemIndex, nodeOutput);
        }
        return byStepKey;
    }

    /**
     * Get merged outputs for all steps with a given alias.
     * <p>BATCH-B (2026-05-20) - delegates to the strict-org overload when
     * {@code orgId} is provided. The legacy tenant-only path remains for
     * backwards-compat with personal-scope runs (org_id = null).
     */
    public List<StorageNestedRow> getMergedOutputsForAlias(String runId, String stepAlias, String tenantId) {
        return getMergedOutputsForAlias(runId, stepAlias, tenantId, null);
    }

    /**
     * BATCH-B (2026-05-20) - org-aware overload of
     * {@link #getMergedOutputsForAlias(String, String, String)}.
     */
    public List<StorageNestedRow> getMergedOutputsForAlias(String runId, String stepAlias, String tenantId, String orgId) {
        // Use case-insensitive search
        List<WorkflowStepDataEntity> steps = (orgId != null && !orgId.isBlank())
                ? workflowStepDataRepository.findByRunIdAndStepAliasIgnoreCaseAndOrganizationIdStrict(
                        runId, stepAlias, orgId)
                : workflowStepDataRepository.findByRunIdAndStepAliasIgnoreCaseAndTenantId(
                        runId, stepAlias, tenantId);

        // Try with slashes if not found and alias contains dashes
        if (steps.isEmpty() && stepAlias.contains("-")) {
            String stepAliasWithSlashes = stepAlias.replace("-", "/");
            steps = (orgId != null && !orgId.isBlank())
                    ? workflowStepDataRepository.findByRunIdAndStepAliasIgnoreCaseAndOrganizationIdStrict(
                            runId, stepAliasWithSlashes, orgId)
                    : workflowStepDataRepository.findByRunIdAndStepAliasIgnoreCaseAndTenantId(
                            runId, stepAliasWithSlashes, tenantId);
            if (!steps.isEmpty()) {
                logger.debug("Found steps using alias with slashes: {} (original: {})", stepAliasWithSlashes, stepAlias);
            }
        }

        if (steps.isEmpty()) {
            logger.warn("No steps found for alias: {}, runId: {}, tenantId: {}", stepAlias, runId, tenantId);
            return List.of();
        }

        // Filter steps with outputStorageId
        List<WorkflowStepDataEntity> stepsWithOutput = steps.stream()
            .filter(step -> step.getOutputStorageId() != null)
            .toList();

        if (stepsWithOutput.isEmpty()) {
            logger.debug("No steps with output found for alias: {}", stepAlias);
            return List.of();
        }

        List<StorageNestedRow> allRows = new ArrayList<>();
        logger.info("Processing {} steps with output for alias: {}", stepsWithOutput.size(), stepAlias);

        for (WorkflowStepDataEntity step : stepsWithOutput) {
            try {
                StorageNestedRow row = loadAndEnrichStepOutput(step, tenantId);
                if (row != null) {
                    allRows.add(row);
                }
            } catch (Exception e) {
                logger.error("Error loading output for step {}: {}", step.getId(), e.getMessage(), e);
            }
        }

        logger.info("Total rows merged: {}", allRows.size());
        return allRows;
    }

    /**
     * Load and enrich step output with metadata.
     */
    private StorageNestedRow loadAndEnrichStepOutput(WorkflowStepDataEntity step, String tenantId) throws Exception {
        UUID outputStorageId = step.getOutputStorageId();

        logger.info("Processing step ID: {}, outputStorageId: {}, itemIndex: {}",
            step.getId(), outputStorageId, step.getItemIndex());

        StorageEntity storage = storageRepository.findByIdAndTenantId(outputStorageId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Storage not found: " + outputStorageId));

        String dataJson = storage.getData();
        if (dataJson == null || dataJson.trim().isEmpty()) {
            logger.warn("Step {} - Storage has no data", step.getId());
            return null;
        }

        // Get column definitions for logging
        List<StorageColumnDefinition> columns = storageSkeletonService.getColumnDefinitions(
            outputStorageId, tenantId, ""
        );
        logger.info("Step {} - Skeleton shows {} columns available", step.getId(), columns.size());

        // Parse JSONB data
        Map<String, Object> allData = objectMapper.readValue(dataJson, new TypeReference<>() {});
        logger.info("Step {} - Retrieved complete JSONB data with {} top-level keys", step.getId(), allData.size());

        // Enrich with metadata
        Map<String, Object> enrichedData = new HashMap<>(allData);
        enrichStepMetadata(enrichedData, step);

        logger.info("Step {} - Added complete JSONB data with {} keys (including metadata)",
            step.getId(), enrichedData.size());

        return new StorageNestedRow(
            0L,
            outputStorageId,
            tenantId,
            "",
            null,
            enrichedData,
            storage.getCreatedAt()
        );
    }

    /**
     * Enrich data with step metadata.
     */
    private void enrichStepMetadata(Map<String, Object> data, WorkflowStepDataEntity step) {
        // Call metadata
        data.put("_callId", step.getId());
        data.put("_callItemIndex", step.getItemIndex() != null ? step.getItemIndex() : 0);
        data.put("_callIteration", step.getIteration() != null ? step.getIteration() : 0);
        data.put("_callEpoch", step.getEpoch() != null ? step.getEpoch() : 0);

        // Step metadata
        if (step.getStepAlias() != null) data.put("stepAlias", step.getStepAlias());
        if (step.getToolId() != null) data.put("toolId", step.getToolId());
        if (step.getStatus() != null) data.put("status", step.getStatus());
        if (step.getHttpStatus() != null) data.put("httpStatus", step.getHttpStatus());
        if (step.getStartTime() != null) data.put("startTime", step.getStartTime().toString());
        if (step.getEndTime() != null) data.put("endTime", step.getEndTime().toString());
        if (step.getErrorMessage() != null) data.put("errorMessage", step.getErrorMessage());
        if (step.getSpawn() != null) data.put("spawn", step.getSpawn());

        // Input data
        if (step.getInputData() != null && !step.getInputData().isEmpty()) {
            data.put("input", step.getInputData());
            step.getInputData().forEach((key, value) -> data.put("input." + key, value));
        }

        // Metadata
        if (step.getMetadata() != null && !step.getMetadata().isEmpty()) {
            data.put("metadata", step.getMetadata());
            step.getMetadata().forEach((key, value) -> data.put("metadata." + key, value));
        }
    }

    /**
     * Recursively search for arrays in a nested data structure.
     * Returns the JSON path to the first array found.
     */
    public String findArrayPath(Object data, String currentPath) {
        if (data == null) {
            return null;
        }

        if (data instanceof List<?> list) {
            if (!list.isEmpty()) {
                return currentPath;
            }
            return null;
        }

        if (data instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) data;

            // Check priority fields first
            for (String field : PRIORITY_ARRAY_FIELDS) {
                if (map.containsKey(field)) {
                    Object value = map.get(field);
                    String newPath = currentPath.isEmpty() ? field : currentPath + "." + field;
                    String foundPath = findArrayPath(value, newPath);
                    if (foundPath != null) {
                        return foundPath;
                    }
                }
            }

            // Then check all other fields
            Set<String> prioritySet = Set.of(PRIORITY_ARRAY_FIELDS);
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                if (prioritySet.contains(key)) {
                    continue;
                }
                String newPath = currentPath.isEmpty() ? key : currentPath + "." + key;
                String foundPath = findArrayPath(entry.getValue(), newPath);
                if (foundPath != null) {
                    return foundPath;
                }
            }
        }

        return null;
    }

    /**
     * Extract useful data from nested structure.
     */
    public Map<String, Object> extractUsefulData(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }

        for (String path : EXTRACTION_PATHS) {
            Map<String, Object> extracted = getNestedValue(data, path);
            if (extracted != null && !extracted.isEmpty()) {
                logger.debug("Found data at path: '{}', keys: {}", path, extracted.keySet());

                // Check for common data keys
                for (String dataKey : DATA_KEYS) {
                    if (extracted.containsKey(dataKey)) {
                        Object innerData = extracted.get(dataKey);

                        if (innerData instanceof Map<?, ?>) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> innerMap = (Map<String, Object>) innerData;
                            if (!innerMap.isEmpty() && hasSignificantData(innerMap)) {
                                logger.info("Extracted inner data from key: '{}' at path: '{}'", dataKey, path);
                                return innerMap;
                            }
                        } else if (innerData instanceof List<?> innerList) {
                            if (!innerList.isEmpty()) {
                                logger.info("Found list data in key: '{}' at path: '{}' with {} items",
                                    dataKey, path, innerList.size());
                                return extracted;
                            }
                        }
                    }
                }

                if (hasSignificantData(extracted)) {
                    logger.info("Using extracted data from path: '{}' with {} keys", path, extracted.size());
                    return extracted;
                }

                // Check nested structures
                for (Object value : extracted.values()) {
                    if (value instanceof Map<?, ?>) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> nestedMap = (Map<String, Object>) value;
                        if (hasSignificantData(nestedMap)) {
                            logger.info("Found significant data in nested structure at path: '{}'", path);
                            return nestedMap;
                        }
                    }
                }
            }
        }

        // Try deep extraction
        Map<String, Object> deepExtracted = deepExtract(data);
        if (deepExtracted != null) {
            logger.debug("Found data via deep extraction");
            return deepExtracted;
        }

        return null;
    }

    /**
     * Deep extraction: recursively search for objects with significant data.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deepExtract(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                Map<String, Object> nestedMap = (Map<String, Object>) value;

                Map<String, Object> extracted = extractUsefulData(nestedMap);
                if (extracted != null && hasSignificantData(extracted)) {
                    logger.info("Deep extraction found data in key '{}' via extractUsefulData", key);
                    return extracted;
                }

                if (hasSignificantData(nestedMap)) {
                    logger.info("Deep extraction found significant data in key '{}'", key);
                    return nestedMap;
                }

                Map<String, Object> deeper = deepExtract(nestedMap);
                if (deeper != null) {
                    logger.info("Deep extraction found data deeper in key '{}'", key);
                    return deeper;
                }
            }
        }

        return null;
    }

    /**
     * Check if data contains significant information (not just metadata).
     */
    public boolean hasSignificantData(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return false;
        }

        Set<String> metadataSet = Set.of(METADATA_KEYS);

        for (String key : data.keySet()) {
            if (!metadataSet.contains(key)) {
                Object value = data.get(key);
                if (value != null) {
                    if (value instanceof String s && !s.isEmpty()) return true;
                    if (value instanceof Map<?, ?> m && !m.isEmpty()) return true;
                    if (value instanceof List<?> l && !l.isEmpty()) return true;
                    if (!(value instanceof String) && !(value instanceof Map) && !(value instanceof List)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Get nested value from a map using dot-separated path.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getNestedValue(Map<String, Object> data, String path) {
        if (data == null || path == null || path.isEmpty()) {
            return null;
        }

        String[] parts = path.split("\\.");
        Object current = data;

        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
                if (current == null) {
                    return null;
                }
            } else {
                return null;
            }
        }

        if (current instanceof Map) {
            return (Map<String, Object>) current;
        }

        return null;
    }

    /**
     * Clean metadata from data, keeping only meaningful fields.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> cleanMetadata(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return data;
        }

        Set<String> metadataSet = Set.of(METADATA_KEYS);
        Map<String, Object> cleaned = new HashMap<>();

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            boolean isMetadata = metadataSet.contains(key);

            if (!isMetadata || (value instanceof Map && !((Map<?, ?>) value).isEmpty()) ||
                (value instanceof List && !((List<?>) value).isEmpty())) {
                cleaned.put(key, value);
            }
        }

        return cleaned.isEmpty() ? data : cleaned;
    }

    /**
     * Paginate a list of rows.
     */
    public NestedPaginationResponse<StorageNestedRow> paginateRows(
            List<StorageNestedRow> allRows, int page, int limit) {
        int totalRows = allRows.size();
        int offset = (page - 1) * limit;
        int endIndex = Math.min(offset + limit, totalRows);

        List<StorageNestedRow> paginatedRows = allRows.subList(
            Math.min(offset, totalRows),
            endIndex
        );

        int totalPages = (int) Math.ceil((double) totalRows / limit);
        boolean hasMore = endIndex < totalRows;

        return new NestedPaginationResponse<>(
            paginatedRows,
            totalRows,
            null,
            hasMore,
            totalPages,
            "",
            null,
            List.of()
        );
    }
}

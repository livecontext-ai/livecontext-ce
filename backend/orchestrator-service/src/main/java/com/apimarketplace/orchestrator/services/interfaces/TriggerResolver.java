package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.TriggerBatchResult;
import com.apimarketplace.orchestrator.domain.workflow.TriggerItemContext;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;

import java.util.List;
import java.util.Map;

/**
 * Interface for trigger resolution operations.
 * Defines the contract for resolving workflow triggers and loading data.
 *
 * Implementations handle:
 * - Datasource trigger resolution
 * - Trigger item context building
 * - Batch processing for large datasets
 * - Step data loading
 *
 * @see com.apimarketplace.orchestrator.services.TriggerResolverService
 */
public interface TriggerResolver {

    // ==================== Trigger Resolution ====================

    /**
     * Resolve a trigger to get its data.
     *
     * @param trigger The trigger to resolve
     * @param tenantId The tenant identifier
     * @return Map of resolved trigger data
     */
    Map<String, Object> resolveTrigger(Trigger trigger, String tenantId);

    /**
     * Resolve a trigger with pre-resolved inputs.
     *
     * @param trigger The trigger to resolve
     * @param tenantId The tenant identifier
     * @param resolvedInputs Pre-resolved input values
     * @return Map of resolved trigger data
     */
    Map<String, Object> resolveTrigger(Trigger trigger, String tenantId, Map<String, Object> resolvedInputs);

    // ==================== Trigger Item Context ====================

    /**
     * Build trigger item contexts for a list of items.
     *
     * @param runId The run identifier
     * @param tenantId The tenant identifier
     * @param trigger The trigger definition
     * @param items List of data items
     * @param offset Starting offset in the full dataset
     * @param totalCount Total count of items
     * @param hasMore Whether there are more items
     * @return List of item contexts
     */
    List<TriggerItemContext> buildTriggerItemContexts(
            String runId,
            String tenantId,
            Trigger trigger,
            List<Map<String, Object>> items,
            int offset,
            int totalCount,
            boolean hasMore);

    // ==================== Batch Processing ====================

    /**
     * Resolve a trigger with pagination.
     *
     * @param trigger The trigger to resolve
     * @param tenantId The tenant identifier
     * @param offset Starting offset
     * @param limit Maximum items to fetch
     * @return Batch result with items and pagination info
     */
    TriggerBatchResult resolveTriggerBatch(Trigger trigger, String tenantId, int offset, int limit);

    /**
     * Resolve a trigger with pagination and pre-resolved inputs.
     *
     * @param trigger The trigger to resolve
     * @param tenantId The tenant identifier
     * @param offset Starting offset
     * @param limit Maximum items to fetch
     * @param resolvedInputs Pre-resolved input values
     * @return Batch result with items and pagination info
     */
    TriggerBatchResult resolveTriggerBatch(Trigger trigger, String tenantId, int offset, int limit, Map<String, Object> resolvedInputs);

    /**
     * Resolve a datasource trigger with automatic pagination.
     *
     * @param trigger The trigger to resolve
     * @param tenantId The tenant identifier
     * @return Batch result with all items
     */
    TriggerBatchResult resolveDatasourceWithAutoPagination(Trigger trigger, String tenantId);

    // ==================== Step Data ====================

    /**
     * Load previously stored step data.
     *
     * @param stepId The step identifier
     * @param execution The workflow execution
     * @return Map of step data, or null if not found
     */
    Map<String, Object> loadStepData(String stepId, WorkflowExecution execution);

    // ==================== Utility ====================

    /**
     * Check if a trigger exists.
     *
     * @param triggerId The trigger identifier
     * @param tenantId The tenant identifier
     * @return true if the trigger exists
     */
    boolean triggerExists(String triggerId, String tenantId);

    /**
     * Get trigger resolution statistics.
     *
     * @return Map of statistics
     */
    Map<String, Object> getTriggerStats();
}

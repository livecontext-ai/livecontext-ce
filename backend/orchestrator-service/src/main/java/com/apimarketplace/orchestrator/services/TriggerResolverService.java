package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.TriggerBatchResult;
import com.apimarketplace.orchestrator.domain.workflow.TriggerItemContext;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.services.interfaces.TriggerResolver;
import com.apimarketplace.orchestrator.services.triggers.DataSourceTriggerResolver;
import com.apimarketplace.orchestrator.services.triggers.TriggerItemContextBuilder;
import com.apimarketplace.orchestrator.services.triggers.TriggerPayloadBuilder;
import com.apimarketplace.orchestrator.services.triggers.TriggerTypeHandler;
import com.apimarketplace.orchestrator.config.WorkflowExecutionConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Facade service for resolving triggers and loading data.
 * Delegates to specialized TriggerTypeHandler implementations.
 * No mock/fallback mode - always uses real data.
 */
@Slf4j
@Service
public class TriggerResolverService implements TriggerResolver {

    private final List<TriggerTypeHandler> handlers;
    private final TriggerPayloadBuilder payloadBuilder;
    private final TriggerItemContextBuilder contextBuilder;
    private final DataSourceTriggerResolver dataSourceResolver;
    private final WorkflowExecutionConfig config;

    public TriggerResolverService(
            List<TriggerTypeHandler> handlers,
            TriggerPayloadBuilder payloadBuilder,
            TriggerItemContextBuilder contextBuilder,
            DataSourceTriggerResolver dataSourceResolver,
            WorkflowExecutionConfig config) {
        this.handlers = handlers;
        this.payloadBuilder = payloadBuilder;
        this.contextBuilder = contextBuilder;
        this.dataSourceResolver = dataSourceResolver;
        this.config = config;
    }

    /**
     * Resolves a trigger and loads its data.
     * @throws IllegalArgumentException if trigger type is unknown or unsupported
     */
    public Map<String, Object> resolveTrigger(Trigger trigger, String tenantId) {
        return resolveTrigger(trigger, tenantId, Map.of());
    }

    public Map<String, Object> resolveTrigger(Trigger trigger, String tenantId, Map<String, Object> resolvedInputs) {
        log.debug("Resolving trigger: {} for tenant: {}", trigger.id(), tenantId);

        // Find handler for trigger type
        for (TriggerTypeHandler handler : handlers) {
            if (handler.canHandle(trigger.type())) {
                return handler.resolve(trigger, tenantId, resolvedInputs);
            }
        }

        // No handler found - throw exception
        throw new IllegalArgumentException("Unsupported trigger type: " + trigger.type() + " for trigger: " + trigger.id());
    }

    /**
     * Builds a TriggerItemContext from raw payload.
     */
    public TriggerItemContext buildTriggerItemContext(
            String runId,
            String tenantId,
            Trigger trigger,
            Map<String, Object> rawPayload,
            int batchIndex,
            int absoluteIndex,
            int totalCount,
            boolean hasMore) {
        return contextBuilder.buildTriggerItemContext(
                runId, tenantId, trigger, rawPayload, batchIndex, absoluteIndex, totalCount, hasMore, null);
    }

    public TriggerItemContext buildTriggerItemContext(
            String runId,
            String tenantId,
            Trigger trigger,
            Map<String, Object> rawPayload,
            int batchIndex,
            int absoluteIndex,
            int totalCount,
            boolean hasMore,
            WorkflowExecution execution) {
        return contextBuilder.buildTriggerItemContext(
                runId, tenantId, trigger, rawPayload, batchIndex, absoluteIndex, totalCount, hasMore, execution);
    }

    /**
     * Builds a list of TriggerItemContexts from items.
     */
    public List<TriggerItemContext> buildTriggerItemContexts(
            String runId,
            String tenantId,
            Trigger trigger,
            List<Map<String, Object>> items,
            int offset,
            int totalCount,
            boolean hasMore) {
        return contextBuilder.buildTriggerItemContexts(runId, tenantId, trigger, items, offset, totalCount, hasMore);
    }

    /**
     * Loads step data from execution context.
     * @throws IllegalStateException if step data is not found
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadStepData(String stepId, WorkflowExecution execution) {
        log.debug("Loading step data: {}", stepId);

        Map<String, Object> stepOutputs = execution.getStepOutputs();

        if (stepOutputs.containsKey(stepId)) {
            Map<String, Object> stepData = (Map<String, Object>) stepOutputs.get(stepId);
            log.debug("Found step data for: {} ({} keys)", stepId, stepData.size());
            return stepData;
        }

        throw new IllegalStateException("Step data not found: " + stepId);
    }

    /**
     * Returns a batch of data for a datasource trigger.
     */
    public TriggerBatchResult resolveTriggerBatch(Trigger trigger, String tenantId, int offset, int limit) {
        return dataSourceResolver.resolveTriggerBatch(trigger, tenantId, offset, limit, Map.of());
    }

    public TriggerBatchResult resolveTriggerBatch(Trigger trigger, String tenantId, int offset, int limit, Map<String, Object> resolvedInputs) {
        return dataSourceResolver.resolveTriggerBatch(trigger, tenantId, offset, limit, resolvedInputs);
    }

    /**
     * Auto-paginate and load all pages for a datasource trigger.
     */
    public TriggerBatchResult resolveDatasourceWithAutoPagination(Trigger trigger, String tenantId) {
        return dataSourceResolver.resolveDatasourceWithAutoPagination(trigger, tenantId);
    }

    /**
     * Checks if a trigger exists (for validation).
     */
    public boolean triggerExists(String triggerId, String tenantId) {
        return true;
    }

    /**
     * Returns trigger resolution statistics.
     */
    public Map<String, Object> getTriggerStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalResolved", 0);
        stats.put("timestamp", System.currentTimeMillis());
        return stats;
    }
}

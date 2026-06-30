package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.datasource.client.dto.DataSourceItemDto;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.TriggerBatchResult;
import com.apimarketplace.orchestrator.config.WorkflowExecutionConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Resolves datasource triggers.
 * Handles batch loading and pagination.
 * Always uses real datasource data (no mock).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceTriggerResolver implements TriggerTypeHandler {

    private final WorkflowExecutionConfig config;
    private final DataSourceClient dataSourceClient;
    private final TriggerPayloadBuilder payloadBuilder;
    private final TriggerUserResolver triggerUserResolver;

    @Override
    public boolean canHandle(String triggerType) {
        return "datasource".equalsIgnoreCase(triggerType);
    }

    @Override
    public Map<String, Object> resolve(Trigger trigger, String tenantId, Map<String, Object> resolvedInputs) {
        log.debug("Resolving datasource trigger: {} with strategy: {}", trigger.id(), trigger.strategy());
        return resolveRealDatasource(trigger, tenantId, resolvedInputs);
    }

    private Map<String, Object> resolveRealDatasource(Trigger trigger, String tenantId, Map<String, Object> resolvedInputs) {
        try {
            TriggerBatchResult batch = resolveTriggerBatch(trigger, tenantId, 0, config.getTriggerBatchSize(), resolvedInputs);
            Map<String, Object> payload = new HashMap<>(batch.toLegacyPayload());
            payload.put("strategy", trigger.strategy());
            payload.put("hasMore", batch.hasMore());
            payload.put("nextOffset", batch.hasMore() ? batch.nextOffset() : null);

            if (resolvedInputs != null && !resolvedInputs.isEmpty()) {
                payload.put("_inputs", new HashMap<>(resolvedInputs));
                // Event-driven fires deliver event_type / row / previous_row / ... via
                // resolvedInputs. V97 docs advertise these as top-level - promote them so
                // {{trigger:<label>.output.event_type}} resolves. Legacy keys win; reserved
                // collisions are logged. No-op for non-event (manual/schedule) fires.
                payloadBuilder.promoteEventFields(payload, resolvedInputs);
            }

            payloadBuilder.annotateCapMetadata(payload, batch);

            // Unified trigger context: triggered_at (now, if event-driven paths haven't
            // already promoted a row's triggered_at) + triggered_by = owner's display name.
            payload.putIfAbsent("triggered_at", java.time.Instant.now().toString());
            payload.putIfAbsent("triggered_by", triggerUserResolver.resolveDisplayName(tenantId));
            return payload;
        } catch (IllegalArgumentException ex) {
            log.error("Invalid trigger configuration for {}: {}", trigger.id(), ex.getMessage());
            String message = ex.getMessage();
            String lower = message != null ? message.toLowerCase(Locale.ROOT) : "";
            String errorCode = lower.contains("not numeric") ? "invalid_datasource_id"
                    : lower.contains("not found") ? "datasource_not_found"
                    : "datasource_error";
            return payloadBuilder.buildErrorPayload(trigger, tenantId, errorCode, message);
        } catch (Exception ex) {
            log.error("Error loading datasource {} for tenant {}", trigger.id(), tenantId, ex);
            return payloadBuilder.buildErrorPayload(trigger, tenantId, "datasource_error", ex.getMessage());
        }
    }

    /**
     * Returns a batch of data for a datasource trigger.
     */
    public TriggerBatchResult resolveTriggerBatch(Trigger trigger, String tenantId, int offset, int limit, Map<String, Object> resolvedInputs) {
        log.debug("resolveTriggerBatch - trigger={}, tenantId={}, offset={}, limit={}",
                trigger != null ? trigger.id() : "null", tenantId, offset, limit);

        validateTriggerBatchInput(trigger, tenantId);

        int effectiveLimit = limit > 0 ? limit : config.getTriggerBatchSize();
        int effectiveOffset = Math.max(offset, 0);

        Integer datasourceId;
        try {
            datasourceId = Integer.valueOf(trigger.id());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Datasource trigger id '" + trigger.id() + "' is not numeric");
        }

        DataSourceDto dataSource = dataSourceClient.getDataSource(datasourceId.longValue(), tenantId);
        if (dataSource == null) {
            throw new IllegalArgumentException("Datasource " + trigger.id() + " not found for tenant " + tenantId);
        }

        List<DataSourceItemDto> items = dataSourceClient.getItems(datasourceId.longValue(), tenantId, effectiveOffset, effectiveLimit);
        int declaredTotal = dataSourceClient.getItemsCount(datasourceId.longValue(), tenantId);

        log.info("[DataSourceTriggerResolver] Retrieved {} items from DB for datasourceId={}, offset={}, limit={}",
                items.size(), datasourceId, effectiveOffset, effectiveLimit);

        List<Map<String, Object>> data = items.stream()
                .map(this::buildEnrichedItem)
                .toList();

        int nextOffset = Math.min(declaredTotal, effectiveOffset + data.size());
        boolean hasMore = nextOffset < declaredTotal;

        return new TriggerBatchResult(trigger, tenantId, data, effectiveOffset, effectiveLimit,
                data.size(), hasMore, nextOffset, declaredTotal);
    }

    /**
     * Auto-paginate and load all pages for a datasource trigger (real data).
     */
    public TriggerBatchResult resolveDatasourceWithAutoPagination(Trigger trigger, String tenantId) {
        Objects.requireNonNull(trigger, "trigger cannot be null");
        Objects.requireNonNull(tenantId, "tenantId cannot be null");

        int perBatchLimit = Math.max(1, config.getTriggerBatchSize());
        int maxItems = config.getMaxDatasourceItems();
        boolean unlimited = maxItems <= 0;

        List<Map<String, Object>> aggregatedItems = new ArrayList<>();
        TriggerBatchResult lastBatch = null;
        int offset = 0;

        while (true) {
            int requestLimit = unlimited
                    ? perBatchLimit
                    : Math.min(perBatchLimit, Math.max(0, maxItems - aggregatedItems.size()));
            if (requestLimit <= 0) break;

            TriggerBatchResult batch = resolveTriggerBatch(trigger, tenantId, offset, requestLimit, Map.of());
            lastBatch = batch;

            if (batch.items().isEmpty()) break;

            aggregatedItems.addAll(batch.items());

            if (!batch.hasMore()) break;

            offset = batch.nextOffset();

            if (!unlimited && aggregatedItems.size() >= maxItems) break;
        }

        return buildAggregatedResult(trigger, tenantId, aggregatedItems, lastBatch, perBatchLimit, maxItems, unlimited);
    }

    private void validateTriggerBatchInput(Trigger trigger, String tenantId) {
        if (trigger == null) {
            throw new IllegalArgumentException("Trigger cannot be null");
        }
        if (!"datasource".equalsIgnoreCase(trigger.type())) {
            throw new IllegalArgumentException("Trigger " + trigger.id() + " is not a datasource trigger");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be null or blank");
        }
    }

    private Map<String, Object> buildEnrichedItem(DataSourceItemDto item) {
        Map<String, Object> enrichedItem = new HashMap<>();
        enrichedItem.put("data", item.data());
        enrichedItem.put("priority", item.priority());
        enrichedItem.put("created_at", item.createdAt() != null ? item.createdAt().toString() : null);
        enrichedItem.put("id", item.id());
        enrichedItem.put("data_source_id", item.dataSourceId());
        return enrichedItem;
    }

    private TriggerBatchResult buildAggregatedResult(Trigger trigger, String tenantId,
            List<Map<String, Object>> aggregatedItems, TriggerBatchResult lastBatch,
            int perBatchLimit, int maxItems, boolean unlimited) {

        if (lastBatch == null) {
            return new TriggerBatchResult(trigger, tenantId, List.of(), 0, perBatchLimit, 0, false, 0, 0);
        }

        boolean reachedCap = !unlimited && aggregatedItems.size() >= maxItems && lastBatch.hasMore();
        if (reachedCap) {
            log.warn("Datasource trigger {} reached cap of {} items for tenant {} (total: {})",
                    trigger.id(), maxItems, tenantId, lastBatch.realTotalCount());
        }

        int limitValue = aggregatedItems.isEmpty() ? perBatchLimit : aggregatedItems.size();
        boolean hasMore = !reachedCap && lastBatch.hasMore();
        int nextOffsetValue = hasMore ? lastBatch.nextOffset() : aggregatedItems.size();

        return new TriggerBatchResult(trigger, tenantId, aggregatedItems, 0, Math.max(1, limitValue),
                aggregatedItems.size(), hasMore, nextOffsetValue, lastBatch.realTotalCount());
    }
}

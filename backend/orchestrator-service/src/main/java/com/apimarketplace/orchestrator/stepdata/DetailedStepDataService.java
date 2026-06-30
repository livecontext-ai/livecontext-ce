package com.apimarketplace.orchestrator.stepdata;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.NodeType;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.stepdata.DetailedStepDataResponse.PaginationInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service for retrieving detailed step data with node-specific columns.
 * Coordinates between repository, storage, and mapping components.
 */
@Service
@Transactional(readOnly = true)
public class DetailedStepDataService {

    private static final Logger logger = LoggerFactory.getLogger(DetailedStepDataService.class);

    private final WorkflowStepDataRepository stepDataRepository;
    private final StorageService storageService;
    private final ColumnDefinitionService columnDefinitionService;
    private final StepDataRowMapper rowMapper;

    public DetailedStepDataService(
            WorkflowStepDataRepository stepDataRepository,
            StorageService storageService,
            ColumnDefinitionService columnDefinitionService,
            StepDataRowMapper rowMapper) {
        this.stepDataRepository = stepDataRepository;
        this.storageService = storageService;
        this.columnDefinitionService = columnDefinitionService;
        this.rowMapper = rowMapper;
    }

    /**
     * Get detailed step data for a step alias with node-specific columns.
     *
     * @param runId     The public run ID
     * @param stepAlias The step alias
     * @param tenantId  The tenant ID
     * @param page      Page number (1-based)
     * @param pageSize  Number of items per page
     * @param status    Optional status filter (case-insensitive); null/blank = no filter
     * @param epoch     Optional epoch filter; null = no filter
     * @return DetailedStepDataResponse with columns and rows
     */
    public DetailedStepDataResponse getDetailedStepData(
            String runId,
            String stepAlias,
            String tenantId,
            int page,
            int pageSize,
            String status,
            Integer epoch) {
        return getDetailedStepData(runId, stepAlias, tenantId, null, page, pageSize, status, epoch);
    }

    /**
     * BATCH-B (2026-05-20) - org-aware overload. When {@code orgId} is provided,
     * the underlying repository call uses the strict-org finder so an org-mate
     * cannot leak step rows belonging to another workspace owned by the same
     * userId.
     */
    public DetailedStepDataResponse getDetailedStepData(
            String runId,
            String stepAlias,
            String tenantId,
            String orgId,
            int page,
            int pageSize,
            String status,
            Integer epoch) {

        logger.debug("Getting detailed step data: runId={}, stepAlias={}, tenantId={}, orgId={}, status={}, epoch={}",
                runId, stepAlias, tenantId, orgId, status, epoch);

        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), pageSize);
        List<String> rawStatuses = StepStatusFilter.expandToRawList(status);
        Page<WorkflowStepDataEntity> entityPage = loadDetailedPage(
                runId, stepAlias, tenantId, orgId, epoch, rawStatuses, pageable);

        if (entityPage.isEmpty()) {
            Page<WorkflowStepDataEntity> schemaPage = loadDetailedPage(
                    runId, stepAlias, tenantId, orgId, null, List.of(), PageRequest.of(0, 1));
            if (!schemaPage.isEmpty()) {
                WorkflowStepDataEntity firstAll = schemaPage.getContent().get(0);
                return new DetailedStepDataResponse(
                        firstAll.getNodeType(),
                        stepAlias,
                        firstAll.getToolId(),
                        List.of(),
                        List.of(),
                        PaginationInfo.of(page, pageSize, 0)
                );
            }

            logger.debug("No step data found for runId={}, stepAlias={}", runId, stepAlias);
            return new DetailedStepDataResponse(
                    null,
                    stepAlias,
                    null,
                    List.of(),
                    List.of(),
                    PaginationInfo.of(page, pageSize, 0)
            );
        }

        // Determine node type from first entity (all should have same type for same alias)
        List<WorkflowStepDataEntity> pageEntities = entityPage.getContent();
        WorkflowStepDataEntity firstEntity = pageEntities.get(0);
        NodeType nodeType = firstEntity.getNodeType();
        String toolId = firstEntity.getToolId();

        int totalRows = Math.toIntExact(entityPage.getTotalElements());
        int startIndex = (page - 1) * pageSize;

        // Map entities to rows with output data
        // Row index is 1-based and accounts for pagination (startIndex + position + 1)
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < pageEntities.size(); i++) {
            WorkflowStepDataEntity entity = pageEntities.get(i);
            Map<String, Object> outputData = loadOutputData(entity);
            int rowIndex = startIndex + i + 1; // 1-based index across all pages
            Map<String, Object> row = rowMapper.mapToRow(entity, outputData, rowIndex);
            preserveNullOutputFields(row);
            rows.add(row);
        }

        // Derive columns from actual row data (data-driven, not nodeType-driven)
        List<ColumnDefinition> columns = columnDefinitionService.deriveColumnsFromRows(rows);

        return new DetailedStepDataResponse(
                nodeType,
                stepAlias,
                toolId,
                columns,
                rows,
                PaginationInfo.of(page, pageSize, totalRows)
        );
    }

    private Page<WorkflowStepDataEntity> loadDetailedPage(
            String runId,
            String stepAlias,
            String tenantId,
            String orgId,
            Integer epoch,
            List<String> rawStatuses,
            Pageable pageable) {

        boolean hasStatusFilter = rawStatuses != null && !rawStatuses.isEmpty();
        if (orgId != null && !orgId.isBlank()) {
            return hasStatusFilter
                    ? stepDataRepository.findDetailedByRunIdAndStepAliasAndOrganizationIdAndStatusInStrict(
                            runId, stepAlias, orgId, epoch, rawStatuses, pageable)
                    : stepDataRepository.findDetailedByRunIdAndStepAliasAndOrganizationIdStrict(
                            runId, stepAlias, orgId, epoch, pageable);
        }

        return hasStatusFilter
                ? stepDataRepository.findDetailedByRunIdAndStepAliasAndTenantIdAndStatusIn(
                        runId, stepAlias, tenantId, epoch, rawStatuses, pageable)
                : stepDataRepository.findDetailedByRunIdAndStepAliasAndTenantId(
                        runId, stepAlias, tenantId, epoch, pageable);
    }

    /**
     * Get detailed step data for a specific step entity ID.
     */
    public DetailedStepDataResponse getDetailedStepDataById(
            Long stepId,
            String runId,
            String tenantId) {

        logger.debug("Getting detailed step data by ID: stepId={}, runId={}, tenantId={}", stepId, runId, tenantId);

        Optional<WorkflowStepDataEntity> entityOpt = stepDataRepository.findById(stepId);
        if (entityOpt.isEmpty()) {
            return null;
        }

        WorkflowStepDataEntity entity = entityOpt.get();

        // Verify access
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!entity.getRunId().equals(runId) || !ScopeGuard.isInStrictScope(tenantId, orgId, entity.getTenantId(), entity.getOrganizationId())) {
            logger.warn("Access denied for stepId={}", stepId);
            return null;
        }

        NodeType nodeType = entity.getNodeType();
        String toolId = entity.getToolId();

        // Map entity to row first
        Map<String, Object> outputData = loadOutputData(entity);
        Map<String, Object> row = rowMapper.mapToRow(entity, outputData);
        preserveNullOutputFields(row);

        // Derive columns from actual row data (data-driven)
        List<ColumnDefinition> columns = columnDefinitionService.deriveColumnsFromRows(List.of(row));

        return new DetailedStepDataResponse(
                nodeType,
                entity.getStepAlias(),
                toolId,
                columns,
                List.of(row),
                PaginationInfo.of(1, 1, 1)
        );
    }

    /**
     * Load output data from storage for an entity.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadOutputData(WorkflowStepDataEntity entity) {
        UUID outputStorageId = entity.getOutputStorageId();
        if (outputStorageId == null) {
            return null;
        }

        try {
            Optional<Object> payloadOpt = entity.getOrganizationId() != null && !entity.getOrganizationId().isBlank()
                    ? storageService.getByIdReadOnlyForScope(outputStorageId, entity.getTenantId(), entity.getOrganizationId())
                    : storageService.getByIdReadOnly(outputStorageId, entity.getTenantId());
            if (payloadOpt.isEmpty()) {
                return null;
            }

            Object payload = payloadOpt.get();
            if (payload instanceof Map) {
                return (Map<String, Object>) payload;
            }
            // Wrap non-map payloads
            Map<String, Object> wrapped = new HashMap<>();
            wrapped.put("value", payload);
            return wrapped;
        } catch (Exception e) {
            logger.warn("Failed to load output data for storageId={}: {}", outputStorageId, e.getMessage());
            return null;
        }
    }

    private void preserveNullOutputFields(Map<String, Object> row) {
        Object output = row.get("output");
        if (output instanceof Map<?, ?> || output instanceof Iterable<?> || output instanceof Object[]) {
            row.put("output", toNullPreservingJsonNode(output));
        }
    }

    private JsonNode toNullPreservingJsonNode(Object value) {
        if (value == null) {
            return NullNode.getInstance();
        }
        if (value instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    node.set(String.valueOf(entry.getKey()), toNullPreservingJsonNode(entry.getValue()));
                }
            }
            return node;
        }
        if (value instanceof Iterable<?> iterable) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            for (Object item : iterable) {
                array.add(toNullPreservingJsonNode(item));
            }
            return array;
        }
        if (value instanceof Object[] array) {
            ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
            for (Object item : array) {
                arrayNode.add(toNullPreservingJsonNode(item));
            }
            return arrayNode;
        }
        return JsonNodeFactory.instance.pojoNode(value);
    }
}

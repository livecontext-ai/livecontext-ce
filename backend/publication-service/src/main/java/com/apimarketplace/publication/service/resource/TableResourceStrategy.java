package com.apimarketplace.publication.service.resource;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.ColumnMappingSpecDto;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.datasource.client.dto.DataSourceItemDto;
import com.apimarketplace.datasource.client.dto.DataSourceTypeDto;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publish/acquire strategy for standalone TABLE (DataSource) resources.
 *
 * <p>Snapshot carries: metadata (name, description, sourceType), the raw
 * sourceConfig (may include a FileConfig {@code file_path}), the schema
 * (columnOrder, mappingSpec) and every row of data. On clone, any S3 paths -
 * whether in sourceConfig or in FILE / IMAGE column values - are re-uploaded
 * under the acquirer's tenant by {@link DataSourceFileCloneService}, and the
 * paths rewritten before rows are inserted.
 *
 * <p>DATABASE / API-sourced tables are snapshotted as-is. Credential secrets
 * are never stored on the datasource row - they live on
 * {@code auth.platform_credentials} keyed by integration name - so no
 * sanitization is required here.
 */
@Component
public class TableResourceStrategy implements ResourcePublicationStrategy {

    private static final Logger logger = LoggerFactory.getLogger(TableResourceStrategy.class);

    private final DataSourceClient dataSourceClient;
    private final DataSourceFileCloneService fileCloneService;
    private final ObjectMapper objectMapper;

    public TableResourceStrategy(DataSourceClient dataSourceClient,
                                  DataSourceFileCloneService fileCloneService,
                                  ObjectMapper objectMapper) {
        this.dataSourceClient = dataSourceClient;
        this.fileCloneService = fileCloneService;
        this.objectMapper = objectMapper;
    }

    @Override
    public PublicationType getPublicationType() {
        return PublicationType.TABLE;
    }

    @Override
    public DisplayMode getDisplayMode() {
        return DisplayMode.TABLE;
    }

    @Override
    public ResourceMetadata fetchOwnedResource(String resourceId, String tenantId) {
        Long id = parseId(resourceId);
        DataSourceDto ds = dataSourceClient.findByIdAndTenantId(id, tenantId);
        if (ds == null) {
            throw new IllegalArgumentException("Table not found: " + resourceId);
        }
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, ds.tenantId(), ds.organizationId())) {
            throw new IllegalArgumentException("Table does not belong to tenant");
        }
        return new ResourceMetadata(ds.name(), ds.description());
    }

    @Override
    public Map<String, Object> buildSnapshot(String resourceId, String tenantId) {
        return buildSnapshot(resourceId, tenantId, null);
    }

    @Override
    public Map<String, Object> buildSnapshot(String resourceId, String tenantId, String organizationId) {
        Long id = parseId(resourceId);
        // org present → strict org-scope lookup; blank → personal lookup (publish path).
        // Mirrors the client-overload convention used across the publication pipeline so the
        // admin review can rebuild an org-owned table on behalf of its publisher.
        boolean scoped = organizationId != null && !organizationId.isBlank();
        DataSourceDto ds = scoped
                ? dataSourceClient.findByIdAndTenantId(id, tenantId, organizationId)
                : dataSourceClient.findByIdAndTenantId(id, tenantId);
        if (ds == null) {
            throw new IllegalArgumentException("Table not found: " + resourceId);
        }

        List<DataSourceItemDto> items = scoped
                ? dataSourceClient.getAllItems(id, tenantId, organizationId)
                : dataSourceClient.getAllItems(id, tenantId);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", ds.name());
        snapshot.put("description", ds.description());
        snapshot.put("sourceType", ds.sourceType() != null ? ds.sourceType().name() : DataSourceTypeDto.INLINE.name());
        snapshot.put("sourceConfig", ds.sourceConfig() != null ? ds.sourceConfig() : Map.of());
        snapshot.put("columnOrder", ds.columnOrder() != null ? ds.columnOrder() : List.of());
        // Round-trip the mappingSpec through ObjectMapper so it's plain Map<String,Object>
        // in JSONB storage (avoids DTO-class metadata bleeding into the payload).
        snapshot.put("mappingSpec", ds.mappingSpec() != null
                ? objectMapper.convertValue(ds.mappingSpec(), new TypeReference<Map<String, Object>>() {})
                : Map.of());

        List<Map<String, Object>> itemSnapshots = new ArrayList<>(items.size());
        for (DataSourceItemDto item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("data", item.data() != null ? new LinkedHashMap<>(item.data()) : Map.of());
            if (item.priority() != null) row.put("priority", item.priority());
            itemSnapshots.add(row);
        }
        snapshot.put("items", itemSnapshots);
        return snapshot;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String cloneFromSnapshot(Map<String, Object> snapshot, String tenantId, UUID publicationId) {
        return cloneFromSnapshot(snapshot, tenantId, publicationId, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String cloneFromSnapshot(Map<String, Object> snapshot,
                                    String tenantId,
                                    UUID publicationId,
                                    String organizationId) {
        // Caller (ResourcePublicationService.acquireResource) already deep-copies
        // the stored payload before handing it to us, so we can mutate freely.
        String sourceType = snapshot.get("sourceType") != null
                ? snapshot.get("sourceType").toString() : DataSourceTypeDto.INLINE.name();

        Map<String, Object> sourceConfig = snapshot.get("sourceConfig") instanceof Map<?, ?> scm
                ? new LinkedHashMap<>((Map<String, Object>) scm) : new LinkedHashMap<>();

        List<Map<String, Object>> items = snapshot.get("items") instanceof List<?> itemsRaw
                ? new ArrayList<>((List<Map<String, Object>>) itemsRaw) : new ArrayList<>();

        Map<String, ColumnMappingSpecDto> mappingSpec = objectMapper.convertValue(
                snapshot.get("mappingSpec"),
                new TypeReference<Map<String, ColumnMappingSpecDto>>() {});
        if (mappingSpec == null) mappingSpec = Map.of();

        // Copy any S3 paths referenced in sourceConfig + row data to the acquirer's tenant
        fileCloneService.rewriteFilePaths(sourceType, sourceConfig, items, mappingSpec,
                tenantId, publicationId.toString(), organizationId);

        Map<String, Object> createRequest = new LinkedHashMap<>();
        createRequest.put("name", snapshot.get("name"));
        createRequest.put("description", snapshot.get("description"));
        createRequest.put("sourceType", sourceType);
        createRequest.put("sourceConfig", sourceConfig);
        createRequest.put("columnOrder", snapshot.get("columnOrder") != null
                ? snapshot.get("columnOrder") : List.of());
        createRequest.put("mappingSpec", snapshot.get("mappingSpec"));
        createRequest.put("sourcePublicationId", publicationId.toString());
        if (organizationId != null && !organizationId.isBlank()) {
            createRequest.put("organizationId", organizationId);
        }

        DataSourceDto created = dataSourceClient.createFromSnapshot(createRequest, tenantId);
        if (created == null || created.id() == null) {
            throw new RuntimeException("Failed to clone datasource from snapshot for tenant " + tenantId);
        }

        if (!items.isEmpty()) {
            int inserted = dataSourceClient.bulkInsertItems(created.id(), items, tenantId);
            logger.info("Inserted {} rows into cloned datasource {} for tenant {}",
                    inserted, created.id(), tenantId);
        }

        logger.info("Cloned table publication {} -> datasource {} for tenant {}",
                publicationId, created.id(), tenantId);
        return created.id().toString();
    }

    private Long parseId(String resourceId) {
        try {
            return Long.parseLong(resourceId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid table id (expected numeric): " + resourceId);
        }
    }
}

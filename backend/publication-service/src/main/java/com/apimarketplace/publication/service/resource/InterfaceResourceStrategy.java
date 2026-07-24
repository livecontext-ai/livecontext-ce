package com.apimarketplace.publication.service.resource;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.ColumnMappingSpecDto;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.datasource.client.dto.DataSourceItemDto;
import com.apimarketplace.datasource.client.dto.DataSourceTypeDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publish/acquire strategy for standalone INTERFACE resources (web pages rendered
 * in an iframe - <strong>not</strong> workflow interface nodes).
 *
 * <p>An interface's action mapping may reference external workflows (<code>__continue</code>,
 * action-name → workflowId). Those cannot survive a cross-tenant clone, so we strip
 * any workflow-bound action refs from <code>data.action_mapping</code> at snapshot time
 * - keeping only <code>__continue</code> style sentinels that are self-contained.
 * The acquirer can re-wire their own workflows after acquisition.
 *
 * <p>If the interface references a <code>dataSourceId</code>, the referenced table is
 * recursively snapshotted (schema + rows + S3 paths in FILE/IMAGE columns) and
 * re-cloned under the acquirer's tenant on acquisition, so the cloned interface is
 * wired to a cloned copy of the table rather than a cross-tenant reference.
 */
@Component
public class InterfaceResourceStrategy implements ResourcePublicationStrategy {

    private static final Logger logger = LoggerFactory.getLogger(InterfaceResourceStrategy.class);

    private final InterfaceClient interfaceClient;
    private final DataSourceClient dataSourceClient;
    private final DataSourceFileCloneService fileCloneService;
    private final ObjectMapper objectMapper;

    public InterfaceResourceStrategy(InterfaceClient interfaceClient,
                                      DataSourceClient dataSourceClient,
                                      DataSourceFileCloneService fileCloneService,
                                      ObjectMapper objectMapper) {
        this.interfaceClient = interfaceClient;
        this.dataSourceClient = dataSourceClient;
        this.fileCloneService = fileCloneService;
        this.objectMapper = objectMapper;
    }

    @Override
    public PublicationType getPublicationType() {
        return PublicationType.INTERFACE;
    }

    @Override
    public DisplayMode getDisplayMode() {
        return DisplayMode.INTERFACE;
    }

    @Override
    public ResourceMetadata fetchOwnedResource(String resourceId, String tenantId) {
        UUID interfaceId = parseId(resourceId);
        InterfaceDto iface = interfaceClient.getInterface(interfaceId, tenantId);
        if (iface == null) {
            throw new IllegalArgumentException("Interface not found: " + resourceId);
        }
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, iface.getTenantId(), iface.getOrganizationId())) {
            throw new IllegalArgumentException("Interface does not belong to tenant");
        }
        rejectWebSearch(iface);
        return new ResourceMetadata(iface.getName(), iface.getDescription());
    }

    @Override
    public Map<String, Object> buildSnapshot(String resourceId, String tenantId) {
        return buildSnapshot(resourceId, tenantId, null);
    }

    @Override
    public Map<String, Object> buildSnapshot(String resourceId, String tenantId, String organizationId) {
        UUID interfaceId = parseId(resourceId);
        InterfaceDto iface = getInterfaceScoped(interfaceId, tenantId, organizationId);
        if (iface == null) {
            throw new IllegalArgumentException("Interface not found: " + resourceId);
        }
        rejectWebSearch(iface);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", iface.getName());
        snapshot.put("description", iface.getDescription());
        snapshot.put("htmlTemplate", iface.getHtmlTemplate());
        snapshot.put("cssTemplate", iface.getCssTemplate());
        snapshot.put("jsTemplate", iface.getJsTemplate());
        snapshot.put("interfaceType", iface.getInterfaceType());
        // The format travels with the templates: the clone keeps the published shape.
        snapshot.put("format", iface.getFormat());

        Map<String, Object> sanitizedData = sanitizeData(iface.getData());
        if (sanitizedData != null) {
            snapshot.put("data", sanitizedData);
        }

        // Recursively snapshot the referenced table so the cloned interface
        // doesn't dangle on a cross-tenant dataSourceId.
        Long dsId = iface.getDataSourceId();
        if (dsId != null) {
            Map<String, Object> embeddedTable = buildEmbeddedTableSnapshot(dsId, tenantId, organizationId);
            if (embeddedTable != null) {
                snapshot.put("embeddedTable", embeddedTable);
            }
        }
        return snapshot;
    }

    /**
     * Resolve an interface in an explicit workspace scope. When {@code organizationId}
     * is present we pass it through; when blank we fall back to the 2-arg client overload
     * (request-org resolution) - mirroring {@link LandingInterfaceSnapshotter}. This lets the
     * publication-review comparison rebuild an org-owned interface on behalf of its publisher
     * instead of resolving against the reviewing admin's ambient org.
     */
    private InterfaceDto getInterfaceScoped(UUID interfaceId, String tenantId, String organizationId) {
        return (organizationId != null && !organizationId.isBlank())
                ? interfaceClient.getInterface(interfaceId, tenantId, organizationId)
                : interfaceClient.getInterface(interfaceId, tenantId);
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
        // Caller (ResourcePublicationService.acquireResource) already deep-copies the
        // stored payload before handing it to us, so we can mutate snapshot freely.
        Map<String, Object> req = new HashMap<>(snapshot);
        req.put("sourcePublicationId", publicationId.toString());
        if (organizationId != null && !organizationId.isBlank()) {
            req.put("organizationId", organizationId);
        }

        // If the interface carries an embedded table, clone it first and inject the
        // new dataSourceId into the interface create request.
        Object embeddedRaw = req.remove("embeddedTable");
        if (embeddedRaw instanceof Map<?, ?> embeddedMap) {
            Long clonedDsId = cloneEmbeddedTable((Map<String, Object>) embeddedMap, tenantId, publicationId, organizationId);
            if (clonedDsId != null) {
                req.put("dataSourceId", clonedDsId);
            }
        }

        InterfaceDto created = interfaceClient.createFromSnapshot(req, tenantId);
        if (created == null || created.getId() == null) {
            throw new RuntimeException("Failed to clone interface from snapshot for tenant " + tenantId);
        }
        logger.info("Cloned interface publication {} -> interface {} for tenant {}",
                publicationId, created.getId(), tenantId);
        return created.getId().toString();
    }

    /**
     * Build a snapshot of a referenced DataSource (name, schema, items). Returns
     * {@code null} if the table cannot be read (logged but non-fatal, since the
     * interface itself can still be cloned without the backing table).
     */
    private Map<String, Object> buildEmbeddedTableSnapshot(Long dsId, String tenantId, String organizationId) {
        try {
            boolean scoped = organizationId != null && !organizationId.isBlank();
            DataSourceDto ds = scoped
                    ? dataSourceClient.findByIdAndTenantId(dsId, tenantId, organizationId)
                    : dataSourceClient.findByIdAndTenantId(dsId, tenantId);
            if (ds == null) {
                logger.warn("Interface references non-existent dataSourceId={} for tenant {}, skipping embed", dsId, tenantId);
                return null;
            }
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("name", ds.name());
            table.put("description", ds.description());
            table.put("sourceType", ds.sourceType() != null ? ds.sourceType().name() : DataSourceTypeDto.INLINE.name());
            table.put("sourceConfig", ds.sourceConfig() != null ? ds.sourceConfig() : Map.of());
            table.put("columnOrder", ds.columnOrder() != null ? ds.columnOrder() : List.of());
            table.put("mappingSpec", ds.mappingSpec() != null
                    ? objectMapper.convertValue(ds.mappingSpec(), new TypeReference<Map<String, Object>>() {})
                    : Map.of());

            List<DataSourceItemDto> items = scoped
                    ? dataSourceClient.getAllItems(dsId, tenantId, organizationId)
                    : dataSourceClient.getAllItems(dsId, tenantId);
            List<Map<String, Object>> rows = new ArrayList<>(items.size());
            for (DataSourceItemDto item : items) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("data", item.data() != null ? new LinkedHashMap<>(item.data()) : Map.of());
                if (item.priority() != null) row.put("priority", item.priority());
                rows.add(row);
            }
            table.put("items", rows);
            return table;
        } catch (Exception e) {
            logger.warn("Failed to snapshot embedded table {} for interface clone: {}", dsId, e.getMessage());
            return null;
        }
    }

    /**
     * Clone the embedded table: rewrite its S3 paths under the acquirer's tenant,
     * create the row, bulk-insert items. Returns the new datasource ID, or
     * {@code null} on failure.
     */
    @SuppressWarnings("unchecked")
    private Long cloneEmbeddedTable(Map<String, Object> embedded,
                                    String tenantId,
                                    UUID publicationId,
                                    String organizationId) {
        try {
            String sourceType = embedded.get("sourceType") != null
                    ? embedded.get("sourceType").toString() : DataSourceTypeDto.INLINE.name();
            Map<String, Object> sourceConfig = embedded.get("sourceConfig") instanceof Map<?, ?> scm
                    ? new LinkedHashMap<>((Map<String, Object>) scm) : new LinkedHashMap<>();
            List<Map<String, Object>> items = embedded.get("items") instanceof List<?> itemsRaw
                    ? new ArrayList<>((List<Map<String, Object>>) itemsRaw) : new ArrayList<>();
            Map<String, ColumnMappingSpecDto> mappingSpec = objectMapper.convertValue(
                    embedded.get("mappingSpec"),
                    new TypeReference<Map<String, ColumnMappingSpecDto>>() {});
            if (mappingSpec == null) mappingSpec = Map.of();

            fileCloneService.rewriteFilePaths(sourceType, sourceConfig, items, mappingSpec,
                    tenantId, publicationId.toString(), organizationId);

            Map<String, Object> createRequest = new LinkedHashMap<>();
            createRequest.put("name", embedded.get("name"));
            createRequest.put("description", embedded.get("description"));
            createRequest.put("sourceType", sourceType);
            createRequest.put("sourceConfig", sourceConfig);
            createRequest.put("columnOrder", embedded.get("columnOrder") != null
                    ? embedded.get("columnOrder") : List.of());
            createRequest.put("mappingSpec", embedded.get("mappingSpec"));
            createRequest.put("sourcePublicationId", publicationId.toString());
            if (organizationId != null && !organizationId.isBlank()) {
                createRequest.put("organizationId", organizationId);
            }

            DataSourceDto created = dataSourceClient.createFromSnapshot(createRequest, tenantId);
            if (created == null || created.id() == null) {
                logger.warn("Failed to clone embedded table for interface publication {}", publicationId);
                return null;
            }
            if (!items.isEmpty()) {
                int inserted = dataSourceClient.bulkInsertItems(created.id(), items, tenantId);
                logger.info("Injected {} rows into embedded table {} for interface publication {}",
                        inserted, created.id(), publicationId);
            }
            return created.id();
        } catch (Exception e) {
            logger.warn("Failed to clone embedded table for interface publication {}: {}",
                    publicationId, e.getMessage());
            return null;
        }
    }

    /**
     * Remove external references from the interface's data payload.
     * Specifically: action_mapping entries whose value is a workflow UUID - those would
     * dangle in the acquirer's tenant. Self-contained sentinels (like <code>__continue</code>
     * and <code>navigate:...</code>) stay.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeData(Map<String, Object> data) {
        if (data == null) return null;
        Map<String, Object> copy = new LinkedHashMap<>(data);

        Object mapping = copy.get("action_mapping");
        if (mapping instanceof Map<?, ?> actionMap) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : actionMap.entrySet()) {
                Object val = e.getValue();
                if (val == null) continue;
                String s = val.toString();
                if (s.startsWith("__") || s.startsWith("navigate:")) {
                    sanitized.put(e.getKey().toString(), val);
                } else {
                    logger.debug("Stripped external action_mapping entry {}={} from interface snapshot",
                            e.getKey(), s);
                }
            }
            copy.put("action_mapping", sanitized);
        }
        return copy;
    }

    private UUID parseId(String resourceId) {
        try {
            return UUID.fromString(resourceId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid interface id format: " + resourceId);
        }
    }

    /**
     * Web-search interfaces are search-result containers, not standalone landing pages -
     * publishing one would expose rendered search data that belongs to the publisher's
     * tenant without a meaningful marketplace UX. Only page-type interfaces are shareable.
     */
    private void rejectWebSearch(InterfaceDto iface) {
        if ("web_search".equalsIgnoreCase(iface.getInterfaceType())) {
            throw new IllegalArgumentException(
                "Web-search interfaces cannot be published. Only page-type interfaces are shareable.");
        }
    }
}

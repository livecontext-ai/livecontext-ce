package com.apimarketplace.datasource.services;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.datasource.domain.DataSourceModels.*;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceRepository;
import com.apimarketplace.datasource.utils.DataSourceDefaults;
import com.apimarketplace.publication.client.PublicationClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.Set;

@Service
public class DataSourceService {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceService.class);

    private final DataSourceRepository dataSourceRepository;
    private final DataSourceItemRepository dataSourceItemRepository;
    private final StorageBreakdownService breakdownService;
    private final ObjectMapper objectMapper;
    private final OrgAccessGuard orgAccessService;
    private final com.apimarketplace.auth.client.entitlement.EntitlementGuard entitlementGuard;
    private final VectorFeatureGate vectorFeatureGate;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    // Enriches a list page's publication badge in ONE batched call (see getDataSourcesPaged),
    // replacing the former per-row is-resource-published fan-out. Bean from PublicationClientConfig
    // (already used by TablePublishModule).
    private final PublicationClient publicationClient;

    /** Folders of the tables list (V451). Optional: absent in tests that don't exercise them. */
    private final DataSourceFolderService folderService;

    // Two constructors now (the second is the pre-folders one, kept for the unit tests), so
    // Spring has to be told which one to inject - without this it looks for a no-arg one and
    // the whole service context fails to start.
    @org.springframework.beans.factory.annotation.Autowired
    public DataSourceService(DataSourceRepository dataSourceRepository,
                             DataSourceItemRepository dataSourceItemRepository,
                             StorageBreakdownService breakdownService,
                             ObjectMapper objectMapper,
                             OrgAccessGuard orgAccessService,
                             com.apimarketplace.auth.client.entitlement.EntitlementGuard entitlementGuard,
                             VectorFeatureGate vectorFeatureGate,
                             org.springframework.context.ApplicationEventPublisher eventPublisher,
                             PublicationClient publicationClient,
                             DataSourceFolderService folderService) {
        this.dataSourceRepository = dataSourceRepository;
        this.dataSourceItemRepository = dataSourceItemRepository;
        this.breakdownService = breakdownService;
        this.objectMapper = objectMapper;
        this.orgAccessService = orgAccessService;
        this.entitlementGuard = entitlementGuard;
        this.vectorFeatureGate = vectorFeatureGate;
        this.eventPublisher = eventPublisher;
        this.publicationClient = publicationClient;
        this.folderService = folderService;
    }

    /**
     * Back-compat constructor for callers that don't show folders (unit tests). The paged
     * list then returns no folder view and no narrowing.
     */
    public DataSourceService(DataSourceRepository dataSourceRepository,
                             DataSourceItemRepository dataSourceItemRepository,
                             StorageBreakdownService breakdownService,
                             ObjectMapper objectMapper,
                             OrgAccessGuard orgAccessService,
                             com.apimarketplace.auth.client.entitlement.EntitlementGuard entitlementGuard,
                             VectorFeatureGate vectorFeatureGate,
                             org.springframework.context.ApplicationEventPublisher eventPublisher,
                             PublicationClient publicationClient) {
        this(dataSourceRepository, dataSourceItemRepository, breakdownService, objectMapper,
             orgAccessService, entitlementGuard, vectorFeatureGate, eventPublisher, publicationClient, null);
    }

    public DataSource createDataSource(String tenantId, String name, String description,
                                        DataSourceType sourceType, Map<String, Object> sourceConfig,
                                        List<Map<String, Object>> data, String createdBy) {
        return createDataSource(tenantId, name, description, sourceType, sourceConfig, data, createdBy, null);
    }

    public DataSource createDataSource(String tenantId, String name, String description,
                                        DataSourceType sourceType, Map<String, Object> sourceConfig,
                                        List<Map<String, Object>> data, String createdBy,
                                        Map<String, ColumnMappingSpec> mappingSpec) {
        return createDataSource(tenantId, name, description, sourceType, sourceConfig, data, createdBy, mappingSpec, null);
    }

    public DataSource createDataSource(String tenantId, String name, String description,
                                        DataSourceType sourceType, Map<String, Object> sourceConfig,
                                        List<Map<String, Object>> data, String createdBy,
                                        Map<String, ColumnMappingSpec> mappingSpec, String organizationId) {
        return createDataSource(tenantId, name, description, sourceType, sourceConfig, data, createdBy,
                mappingSpec, organizationId, com.apimarketplace.common.classification.DataSensitivity.NORMAL);
    }

    /**
     * {@link #createDataSource(String, String, String, DataSourceType, Map, List, String, Map, String)}
     * whose initial rows are stamped {@code sensitivity}: RESTRICTED when the caller's context
     * holds Gmail / Drive content (CASA LC-066), so every later read of them is tagged. The tag
     * is permanent: user-table rows have no retention sweep.
     */
    public DataSource createDataSource(String tenantId, String name, String description,
                                        DataSourceType sourceType, Map<String, Object> sourceConfig,
                                        List<Map<String, Object>> data, String createdBy,
                                        Map<String, ColumnMappingSpec> mappingSpec, String organizationId,
                                        com.apimarketplace.common.classification.DataSensitivity sensitivity) {
        validateCreateDataSourceInput(tenantId, name, sourceType, sourceConfig, createdBy);

        // Plan resource limit check (REST + LLM tool path).
        if (entitlementGuard != null) {
            entitlementGuard.check(tenantId,
                    com.apimarketplace.auth.client.entitlement.ResourceType.DATASOURCE,
                    () -> dataSourceRepository.countByTenantId(tenantId));
        }

        logger.debug("Creating DataSource: {} for tenant: {}, sourceType: {}", name, tenantId, sourceType);

        // Generate default mapping spec from data if not provided
        Map<String, ColumnMappingSpec> finalMappingSpec;
        if (mappingSpec != null && !mappingSpec.isEmpty()) {
            finalMappingSpec = mappingSpec;
        } else if (data != null && !data.isEmpty()) {
            finalMappingSpec = DataSourceDefaults.generateMappingSpec(data);
            logger.debug("Auto-generated mappingSpec with {} fields", finalMappingSpec.size());
        } else {
            finalMappingSpec = Map.of();
        }

        // Generate default column order from data fields, falling back to mappingSpec keys
        Set<String> dataFields = DataSourceDefaults.extractFieldNames(data);
        if (dataFields.isEmpty() && !finalMappingSpec.isEmpty()) {
            dataFields = new LinkedHashSet<>(finalMappingSpec.keySet());
        }
        // Plan gate: a caller-supplied mappingSpec can carry vector columns wholesale,
        // bypassing the per-column validation chokepoint (the column-definition validators only
        // run on the columns[]/add_columns shapes). Reject here so the REST create, the internal
        // create, and the agent table-create all ask the same question. The creator IS the owner
        // at this point, since the table does not exist yet.
        String vectorColumn = VectorFeatureGate.findVectorColumn(finalMappingSpec);
        if (vectorColumn != null && !vectorFeatureGate.isVectorAllowed(tenantId)) {
            throw new IllegalArgumentException(vectorFeatureGate.deniedMessage(tenantId));
        }

        List<Map<String, Object>> columnOrder = DataSourceDefaults.generateColumnOrder(dataFields);
        logger.debug("Generated columnOrder with {} columns", columnOrder.size());

        DataSource dataSource = new DataSource(
                null, tenantId, name, description, sourceType, sourceConfig,
                DataSourceStatus.ACTIVE, Instant.now(), Instant.now(), createdBy, columnOrder, finalMappingSpec,
                null, null, null, organizationId
        );

        DataSource savedDataSource = dataSourceRepository.save(dataSource);
        if (savedDataSource == null) {
            throw new RuntimeException("Error saving DataSource");
        }
        logger.info("DataSource saved with ID: {}", savedDataSource.id());

        if (data != null && !data.isEmpty()) {
            addDataToSource(savedDataSource.id(), tenantId, data, sensitivity);
        }

        // New table created with a vector column (self-hosted): schedule the
        // HNSW index build post-commit (CONCURRENTLY cannot run inside the
        // creating transaction). One index per datasource - first vector
        // column's dimension wins, mirroring the partial-index design.
        if (vectorColumn != null) {
            ColumnMappingSpec spec = finalMappingSpec.get(vectorColumn);
            com.apimarketplace.datasource.events.VectorColumnCreatedEvent
                    .forColumn(savedDataSource.id(), spec != null ? spec.display() : null)
                    .ifPresent(eventPublisher::publishEvent);
        }

        return savedDataSource;
    }

    private void validateCreateDataSourceInput(String tenantId, String name, DataSourceType sourceType,
                                                Map<String, Object> sourceConfig, String createdBy) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name cannot be null or empty");
        }
        if (name.length() > 255) {
            throw new IllegalArgumentException("name cannot exceed 255 characters");
        }
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType cannot be null");
        }
        if (sourceConfig == null) {
            throw new IllegalArgumentException("sourceConfig cannot be null");
        }
        if (createdBy == null || createdBy.trim().isEmpty()) {
            throw new IllegalArgumentException("createdBy cannot be null or empty");
        }
    }

    public void addDataToSource(Long dataSourceId, String tenantId, List<Map<String, Object>> data) {
        addDataToSource(dataSourceId, tenantId, data, com.apimarketplace.common.classification.DataSensitivity.NORMAL);
    }

    /** {@link #addDataToSource(Long, String, List)} with every row stamped {@code sensitivity}. */
    public void addDataToSource(Long dataSourceId, String tenantId, List<Map<String, Object>> data,
                                com.apimarketplace.common.classification.DataSensitivity sensitivity) {
        if (dataSourceId == null) {
            throw new IllegalArgumentException("dataSourceId cannot be null");
        }
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        if (data == null) {
            throw new NullPointerException("data cannot be null");
        }

        logger.debug("Adding {} items to DataSource {}", data.size(), dataSourceId);
        long totalSize = 0;
        for (Map<String, Object> item : data) {
            DataSourceItem dataSourceItem = new DataSourceItem(
                    null, dataSourceId, tenantId, item, 0, Instant.now()
            );
            if (sensitivity != null && sensitivity.isRestricted()) {
                dataSourceItemRepository.insert(dataSourceItem, sensitivity);
            } else {
                dataSourceItemRepository.save(dataSourceItem);
            }
            totalSize += estimateItemSize(item);
        }
        breakdownService.increment(tenantId, "DATATABLES", totalSize, data.size());
        logger.debug("All {} items added successfully to DataSource {}", data.size(), dataSourceId);
    }

    public Optional<DataSource> getDataSource(Long id) {
        return dataSourceRepository.findById(id);
    }

    public List<DataSource> getDataSourcesByTenant(String tenantId) {
        return dataSourceRepository.findByTenantId(tenantId);
    }

    /**
     * Check if a user can access a datasource via org-level permissions.
     */
    public boolean canAccessViaOrg(String orgId, String userId, String resourceId, String orgRole) {
        return orgAccessService.canAccess(orgId, userId, "datasource", resourceId, orgRole);
    }

    /**
     * Check if a user can mutate a datasource via org-level permissions.
     */
    public boolean canWriteViaOrg(String orgId, String userId, String resourceId, String orgRole) {
        return orgAccessService.canWrite(orgId, userId, "datasource", resourceId, orgRole);
    }

    /**
     * Get datasources visible to a user, including org-shared ones.
     * Applies org-level deny-list filtering.
     *
     * <p>Post-V261 contract: {@code orgId} is required. Every user-scoped row now
     * has a non-null {@code organization_id} (gateway always injects
     * {@code X-Organization-ID}; personal-workspace users get their personal org
     * UUID from {@code auth.organization_member.is_default=true}).
     */
    public List<DataSource> getDataSources(String userId, String orgId, String orgRole) {
        TenantResolver.requireOrgId(orgId);
        List<DataSource> dataSources = dataSourceRepository.findByOrganizationOrOwner(orgId, userId);
        return orgAccessService.filterAccessible(dataSources, orgId, userId, "datasource", orgRole,
                ds -> String.valueOf(ds.id()));
    }

    /** First N rows previewed per card - kept small so the page payload stays bounded. */
    private static final int SAMPLE_ROWS_PER_TABLE = 3;

    /**
     * Page envelope for the paged list endpoint. {@code rowCounts} maps each
     * datasource id on the current page to its item (row) count - populated by a
     * single batch query so the card list can show "N rows" without an N+1 fan-out.
     * {@code sampleRows} maps each id to the ordered {@code data} payloads of its
     * first {@link #SAMPLE_ROWS_PER_TABLE} rows, batched the same way, so each card
     * can paint a mini-table preview without one items request per card.
     */
    public record DataSourcePage(List<DataSource> items, int totalCount, int page, int size,
                                 Map<Long, Long> rowCounts,
                                 Map<Long, List<Map<String, Object>>> sampleRows,
                                 Map<String, Map<String, String>> publicationStatuses,
                                 List<com.apimarketplace.common.folder.ResourceFolderDto> folders,
                                 List<Map<String, Object>> folderTrail,
                                 boolean folderMissing) {

        /** A page with no folder view - what every caller that does not ask for folders gets. */
        public DataSourcePage(List<DataSource> items, int totalCount, int page, int size,
                              Map<Long, Long> rowCounts,
                              Map<Long, List<Map<String, Object>>> sampleRows,
                              Map<String, Map<String, String>> publicationStatuses) {
            this(items, totalCount, page, size, rowCounts, sampleRows, publicationStatuses,
                 List.of(), List.of(), false);
        }
    }

    /** Standalone-resource publication type for table (datasource) sharing badges. */
    private static final String TABLE_PUBLICATION_TYPE = "TABLE";

    /**
     * Default-order overload (most-recently-modified first, no visibility filter). Kept so existing
     * callers / tests that don't sort or filter by visibility stay source-compatible.
     */
    public DataSourcePage getDataSourcesPaged(String userId, String orgId, String orgRole,
                                                String q, int page, int size) {
        return getDataSourcesPaged(userId, orgId, orgRole, q, page, size, null, null);
    }

    /**
     * Paged, DB-searchable, SERVER-sorted, SERVER-filtered list. Search (`q`) matches name +
     * description (ILIKE) over the org-filtered set; {@code sort} (name | lastModified, default
     * lastModified) orders it; {@code visibility} (all | public | private, default all) filters by the
     * binary shared/not-shared split. The publication badge for the returned page is resolved in a
     * SINGLE batched call to publication-service (no per-row fan-out), and only the requested page of
     * rows is returned - so the browser never loads more than it shows. This mirrors the workflow
     * board (server-paginated + server-enriched).
     *
     * <p>The visibility filter derives from publication status (owned by publication-service, a
     * different schema - no SQL join), so when it is active the status batch is resolved over the
     * whole searched set BEFORE paginating; when it is "all" the status is only needed for the page's
     * badges and is fetched after slicing. Either way it is ONE HTTP call.
     *
     * <p>Post-V261 contract: {@code orgId} is required (see
     * {@link #getDataSources(String, String, String)}).
     */
    public DataSourcePage getDataSourcesPaged(String userId, String orgId, String orgRole,
                                                String q, int page, int size,
                                                String sort, String visibility) {
        return getDataSourcesPaged(userId, orgId, orgRole, q, page, size, sort, visibility, null, false);
    }

    /**
     * Folder-aware overload (V451). A SEARCH looks everywhere (an active {@code q} ignores
     * {@code folderId} and the tiles step aside); otherwise {@code folderId} narrows to one
     * level ({@code "root"} = the tables filed nowhere). An ABSENT parameter means no folder
     * filter at all, which is what every other caller gets.
     */
    public DataSourcePage getDataSourcesPaged(String userId, String orgId, String orgRole,
                                                String q, int page, int size,
                                                String sort, String visibility,
                                                String folderId, boolean includeFolders) {
        TenantResolver.requireOrgId(orgId);
        boolean hasSearch = q != null && !q.isBlank();

        List<DataSource> all = dataSourceRepository.findByOrganizationOrOwner(orgId, userId);
        all = orgAccessService.filterAccessible(all, orgId, userId, "datasource", orgRole,
                ds -> String.valueOf(ds.id()));
        if (hasSearch) {
            String needle = q.trim().toLowerCase();
            all = all.stream()
                    .filter(ds -> matchesNameOrDescription(ds.name(), ds.description(), needle))
                    .toList();
        }

        String visFilter = visibility == null ? "all" : visibility.trim().toLowerCase();
        boolean filterByVisibility = visFilter.equals("public") || visFilter.equals("private");

        // When filtering by visibility we need each row's shared state over the WHOLE set before
        // paginating; resolve it once here and reuse it for the page badges below.
        Map<String, PublicationClient.ResourcePublicationStatusRef> fullSetStatuses = filterByVisibility
                ? publicationClient.findResourcePublicationStatuses(
                        TABLE_PUBLICATION_TYPE, resourceIdsOf(all), userId)
                : Map.of();
        if (filterByVisibility) {
            boolean wantPublic = visFilter.equals("public");
            final Map<String, PublicationClient.ResourcePublicationStatusRef> refs = fullSetStatuses;
            all = all.stream()
                    .filter(ds -> isShared(refs, ds) == wantPublic)
                    .toList();
        }

        // FOLDERS (V451). A table's filing lives in its own row, so the membership map is read
        // once here and serves both the narrowing and the tiles. The tiles are built from the
        // set as it stands at this point - after search and visibility, before the folder
        // narrowing - so a tile counts exactly what the caller may see.
        FolderScope folderScope = new FolderScope(userId, orgId);
        Map<Long, UUID> memberships = folderService == null ? Map.of() : folderService.memberships(folderScope);
        UUID folderFilter = parseFolderId(folderId);
        boolean rootOnly = folderId != null && folderFilter == null;
        boolean folderMissing = false;
        if (folderFilter != null && folderService != null
                && !folderService.existsInScope(folderFilter, folderScope)) {
            folderFilter = null;
            rootOnly = true;
            folderMissing = true;
        }
        List<DataSource> folderAggregateSource = all;
        if (!hasSearch && (rootOnly || folderFilter != null)) {
            final UUID wanted = folderFilter;
            all = all.stream()
                    .filter(ds -> java.util.Objects.equals(memberships.get(ds.id()), wanted))
                    .toList();
        }

        // Order, then slice. Stable sort keeps the created_at-DESC base order as the tie-breaker,
        // matching the former client-side processList semantics exactly.
        all = sortDataSources(all, sort);

        int totalCount = all.size();
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        int from = Math.min(safePage * safeSize, totalCount);
        int to = Math.min(from + safeSize, totalCount);
        List<DataSource> pageItems = all.subList(from, to);

        // Row counts AND a small row sample for THIS page only, each in a single batched
        // query (no N+1). The ids are already org-filtered above, so both batches are
        // pre-authorized. The sample feeds each card's mini-table preview.
        List<Long> pageIds = pageItems.stream().map(DataSource::id).toList();
        Map<Long, Long> rowCounts = dataSourceItemRepository.countByDataSourceIds(pageIds);
        Map<Long, List<Map<String, Object>>> sampleRows =
                dataSourceItemRepository.sampleRowsByDataSourceIds(pageIds, SAMPLE_ROWS_PER_TABLE);

        // Publication badge for the page, batched server-side (no per-row fan-out). Reuse the full-set
        // statuses already fetched for the visibility filter; otherwise fetch just the page's ids.
        Map<String, PublicationClient.ResourcePublicationStatusRef> pageRefs = filterByVisibility
                ? fullSetStatuses
                : publicationClient.findResourcePublicationStatuses(
                        TABLE_PUBLICATION_TYPE, resourceIdsOf(pageItems), userId);
        Map<String, Map<String, String>> publicationStatuses = toPublicationStatusMap(pageItems, pageRefs);

        List<com.apimarketplace.common.folder.ResourceFolderDto> folderTiles = List.of();
        List<Map<String, Object>> folderTrail = List.of();
        if (includeFolders && folderService != null) {
            folderTiles = hasSearch
                    ? List.of()
                    : folderService.listFolderSummaries(
                            folderScope, folderFilter, folderAggregateSource, memberships, sort);
            folderTrail = folderFilter == null
                    ? List.of()
                    : folderService.breadcrumb(folderService.listAll(folderScope), folderFilter).stream()
                            .map(com.apimarketplace.common.folder.AbstractResourceFolderController::toBareMap)
                            .toList();
        }

        return new DataSourcePage(pageItems, totalCount, safePage, safeSize, rowCounts, sampleRows,
                publicationStatuses, folderTiles, folderTrail, folderMissing);
    }

    /**
     * The folder to narrow to, or {@code null} for the top level / no filter. An unparseable
     * id is treated as the top level rather than an error: the filter is a view preference,
     * and a bad one must never take the list down.
     */
    private static UUID parseFolderId(String folderId) {
        if (folderId == null || folderId.isBlank() || "root".equalsIgnoreCase(folderId.trim())) return null;
        try {
            return UUID.fromString(folderId.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> resourceIdsOf(List<DataSource> list) {
        return list.stream().map(ds -> String.valueOf(ds.id())).toList();
    }

    private static boolean isShared(Map<String, PublicationClient.ResourcePublicationStatusRef> statuses,
                                    DataSource ds) {
        PublicationClient.ResourcePublicationStatusRef ref = statuses.get(String.valueOf(ds.id()));
        return ref != null && ref.published();
    }

    /**
     * Server-side equivalent of the frontend {@code listSort.processList} order for tables: {@code
     * name} (case-insensitive A→Z) or, by default, {@code lastModified} (updatedAt, falling back to
     * createdAt, most-recent first; missing dates last). Stable, so equal keys keep the upstream
     * created_at-DESC order. Locale-exactness with JS {@code localeCompare} is not required - typical
     * table names are ASCII and only the visible ordering matters.
     */
    private static List<DataSource> sortDataSources(List<DataSource> list, String sort) {
        String key = sort == null ? "lastmodified" : sort.trim().toLowerCase();
        List<DataSource> sorted = new ArrayList<>(list);
        if (key.equals("name")) {
            sorted.sort(Comparator.comparing(
                    ds -> ds.name() == null ? "" : ds.name(), String.CASE_INSENSITIVE_ORDER));
        } else {
            sorted.sort(DataSourceService::compareByModifiedDesc);
        }
        return sorted;
    }

    private static int compareByModifiedDesc(DataSource a, DataSource b) {
        Instant ta = a.updatedAt() != null ? a.updatedAt() : a.createdAt();
        Instant tb = b.updatedAt() != null ? b.updatedAt() : b.createdAt();
        if (ta == null && tb == null) return 0;
        if (ta == null) return 1;   // missing date sorts last
        if (tb == null) return -1;
        return tb.compareTo(ta);    // most-recent first
    }

    private static Map<String, Map<String, String>> toPublicationStatusMap(
            List<DataSource> pageItems,
            Map<String, PublicationClient.ResourcePublicationStatusRef> refs) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (DataSource ds : pageItems) {
            String id = String.valueOf(ds.id());
            PublicationClient.ResourcePublicationStatusRef ref = refs.get(id);
            if (ref == null) continue;
            Map<String, String> info = new LinkedHashMap<>();
            info.put("status", ref.status());
            if (ref.rejectionReason() != null) info.put("rejectionReason", ref.rejectionReason());
            out.put(id, info);
        }
        return out;
    }

    private static boolean matchesNameOrDescription(String name, String desc, String needle) {
        return (name != null && name.toLowerCase().contains(needle))
            || (desc != null && desc.toLowerCase().contains(needle));
    }

    public Optional<DataSource> getDataSourceByTenantAndName(String tenantId, String name) {
        return dataSourceRepository.findByTenantIdAndName(tenantId, name);
    }

    public Optional<DataSource> getDataSourceByIdAndTenant(Integer dataSourceId, String tenantId) {
        if (dataSourceId == null || tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return dataSourceRepository.findByIdAndTenantId(dataSourceId, tenantId);
    }

    public List<Map<String, Object>> getDataSourceData(Long dataSourceId, String predicate,
                                                        Long lastKey, int limit) {
        List<DataSourceItem> items;

        if (predicate != null && !predicate.isBlank()) {
            items = dataSourceItemRepository.findByDataSourceIdWithPredicate(dataSourceId, predicate, lastKey, limit);
        } else {
            items = dataSourceItemRepository.findByDataSourceIdWithPagination(dataSourceId, lastKey, limit);
        }

        return items.stream()
                .map(DataSourceItem::data)
                .toList();
    }

    public List<DataSourceItem> getDataSourceItems(Long dataSourceId, String predicate,
                                                    Long lastKey, int limit) {
        if (predicate != null && !predicate.isBlank()) {
            return dataSourceItemRepository.findByDataSourceIdWithPredicate(dataSourceId, predicate, lastKey, limit);
        } else {
            return dataSourceItemRepository.findByDataSourceIdWithPagination(dataSourceId, lastKey, limit);
        }
    }

    public List<Map<String, Object>> getData(Long dataSourceId, String mappingPath, String strategy, String tenantId) {
        Optional<DataSource> dataSource = dataSourceRepository.findById(dataSourceId);
        if (dataSource.isEmpty()) {
            return List.of();
        }

        List<DataSourceItem> items = dataSourceItemRepository.findByDataSourceId(dataSourceId);
        List<Map<String, Object>> data = items.stream()
                .map(DataSourceItem::data)
                .toList();

        return switch (strategy) {
            case "SINGLE" -> data.isEmpty() ? List.of() : List.of(data.get(0));
            case "FALLBACK" -> data.isEmpty() ? List.of() : data;
            default -> data;
        };
    }

    public DataSource updateDataSource(Long id, String name, String description,
                                        Map<String, Object> sourceConfig) {
        return updateDataSource(id, name, description, sourceConfig, null, null);
    }

    public DataSource updateDataSource(Long id, String name, String description,
                                        Map<String, Object> sourceConfig,
                                        String callerTenantId,
                                        String orgRole) {
        if (id == null) {
            throw new IllegalArgumentException("id cannot be null");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name cannot be null or empty");
        }
        if (name.length() > 255) {
            throw new IllegalArgumentException("name cannot exceed 255 characters");
        }
        if (sourceConfig == null) {
            throw new IllegalArgumentException("sourceConfig cannot be null");
        }

        return dataSourceRepository.findById(id)
                .map(existing -> {
                    // PR-2.f (audit fix): close the deny-list asymmetry on update.
                    // Same defensive contract as updateAgent / updateInterface:
                    // orgRole=null = strict MEMBER, OWNER never restricted so no-op
                    // for them. The controller currently has no orgRole header
                    // wiring on PUT /{id}, hence the inline gate ; a future
                    // PR-2.f.1 will plumb the role through for explicit ADMIN bypass.
                    String orgId = existing.organizationId();
                    String accessUserId = callerTenantId != null ? callerTenantId : existing.tenantId();
                    if (orgId != null
                            && !orgAccessService.canWrite(orgId, accessUserId,
                                    "datasource", id.toString(), orgRole)) {
                        logger.warn("OrgAccess denied: user {} restricted from updating datasource {} in org {}",
                                accessUserId, id, orgId);
                        throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                                "datasource", id.toString());
                    }
                    DataSource updated = new DataSource(
                            existing.id(), existing.tenantId(), name, description,
                            existing.sourceType(), sourceConfig, existing.status(),
                            existing.createdAt(), Instant.now(), existing.createdBy(),
                            existing.columnOrder(), existing.mappingSpec(),
                            existing.sourceWorkflowId(),
                            existing.sourcePublicationId(),
                            existing.projectId(), existing.organizationId()
                    );
                    DataSource saved = dataSourceRepository.save(updated);

                    return saved;
                })
                .orElseThrow(() -> new IllegalArgumentException("DataSource not found: " + id));
    }

    /**
     * Backward-compat overload - see 3-arg variant. Delegates to
     * {@link #deleteDataSource(Long, String, String)} with
     * {@code tenantId = datasource.tenantId()} (no extra check) and
     * {@code orgRole = null} (non-admin semantics if org-scoped).
     *
     * <p>HTTP callers MUST use the 3-arg overload with the gateway-validated
     * {@code X-Organization-Role} header. Internal/tool callers fall back to
     * the strict deny-list (the OWNER who installed them is never restricted,
     * so this is safe in practice).
     */
    public void deleteDataSource(Long id) {
        dataSourceRepository.findById(id).ifPresent(ds ->
                deleteDataSource(id, ds.tenantId(), null));
    }

    public void deleteDataSource(Long id, String tenantId, String orgRole) {
        dataSourceRepository.findById(id).ifPresent(dataSource -> {
            // PR-2 (V2 fix): org-scoped deny-list enforcement on write.
            // Post-V261: organization_id is always non-null; the null-guard remains
            // as a defensive belt-and-braces (legacy rows fully backfilled in V261,
            // but the deny-list contract still expects an org context).
            // OWNER/ADMIN bypass via OrgAccessGuard contract.
            String orgId = dataSource.organizationId();
            if (orgId != null
                    && !orgAccessService.canWrite(orgId, tenantId, "datasource", id.toString(), orgRole)) {
                logger.warn("OrgAccess denied: user {} restricted from datasource {} in org {}",
                        tenantId, id, orgId);
                throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                        "datasource", id.toString());
            }

            // Count items before deletion (for storage tracking)
            int itemCount = dataSourceItemRepository.findByDataSourceId(id).size();

            dataSourceRepository.deleteById(id);

            // Track storage: decrement items (size corrected by reconciliation)
            if (itemCount > 0) {
                breakdownService.increment(dataSource.tenantId(), "DATATABLES", 0, -itemCount);
            }

            // The vector ROWS cascaded away with the datasource; the per-datasource
            // HNSW index is a separate catalog object and did not. Drop it after
            // commit, on the bounded index executor.
            if (VectorFeatureGate.findVectorColumn(dataSource.mappingSpec()) != null) {
                eventPublisher.publishEvent(new com.apimarketplace.datasource.events.VectorDataSourceDeletedEvent(id));
            }
        });
    }

    public void clearDataSourceData(Long dataSourceId) {
        // Track storage: get count before clearing
        List<DataSourceItem> items = dataSourceItemRepository.findByDataSourceId(dataSourceId);
        String tenantId = items.isEmpty() ? null : items.get(0).tenantId();
        int count = items.size();

        dataSourceItemRepository.deleteByDataSourceId(dataSourceId);

        if (tenantId != null && count > 0) {
            breakdownService.increment(tenantId, "DATATABLES", 0, -count);
        }
    }

    /**
     * Backward-compat overload: tenant-only scope. Callers in a request context where
     * {@code X-Organization-ID} is available SHOULD prefer the 5-arg variant below - items
     * created by an org admin (tenant=A) are invisible to teammates (tenant=B) under this query.
     */
    public List<DataSourceItem> getDataSourceItemsByTenantAndDataSourcePaginated(Integer dataSourceId,
                                                                                  String tenantId,
                                                                                  int offset,
                                                                                  int limit) {
        return getDataSourceItemsByTenantAndDataSourcePaginated(dataSourceId, tenantId, null, offset, limit);
    }

    /**
     * True when any of these rows, returned by a read the caller was scoped for, is RESTRICTED
     * (CASA LC-066). A failed lookup answers false: the rows are still returned, untagged.
     */
    public boolean containsRestrictedItems(Long dataSourceId, List<DataSourceItem> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        try {
            return dataSourceItemRepository.anyRestricted(dataSourceId,
                    items.stream().map(DataSourceItem::id).toList());
        } catch (Exception e) {
            logger.warn("Could not read the sensitivity of datasource={} rows: {}", dataSourceId, e.getMessage());
            return false;
        }
    }

    /**
     * One page of a table for a caller that COPIES it out of the restricted-data controls: a
     * marketplace publication snapshot (table, interface, workflow, application or agent listing)
     * and the moderation view of one. RESTRICTED rows (Gmail / Drive-derived, CASA LC-066) are left
     * out of the copy; the publisher's own table keeps them.
     *
     * <p>The exclusion is in the query, before the page is cut: filtering a page after it was cut
     * made a page holding a RESTRICTED row come back short, which a caller paging to the end
     * cannot tell apart from the last page. It cannot fail open: an unreadable sensitivity is a
     * failed query, which propagates, so nothing is served. With {@code after} the page resumes
     * after that row (keyset) and {@code offset} is ignored. Scope selection mirrors
     * {@link #getDataSourceItemsByTenantAndDataSourcePaginated(Integer, String, String, int, int)}.
     */
    public List<DataSourceItem> getDataSourceItemsForCopy(Integer dataSourceId, String tenantId,
                                                          String organizationId, int offset, int limit,
                                                          DataSourceItemRepository.CopyCursor after) {
        if (dataSourceId == null || offset < 0 || limit <= 0) {
            return List.of();
        }
        if (organizationId != null && !organizationId.isBlank()) {
            return dataSourceItemRepository.findCopyPageInOrgScope(
                    dataSourceId.longValue(), organizationId, offset, limit, after);
        }
        if (tenantId == null) {
            return List.of();
        }
        logger.warn("[DataSourceService] copy page fell back to tenant-only scope (no organizationId): "
                + "dsId={}, tenant={}", dataSourceId, tenantId);
        return dataSourceItemRepository.findCopyPageForTenant(
                dataSourceId.longValue(), tenantId, offset, limit, after);
    }

    /**
     * SQL-paginated fetch with optional org-strict scope (LIMIT/OFFSET pushed down to Postgres).
     * Replaces the previous load-full-table-then-subList implementation so the orchestrator's
     * interface render cap (see {@code OrchestratorLimitsConfig}) actually bounds heap usage
     * on this side too.
     *
     * <p>Scope selection (post-V261 contract):
     * <ul>
     *   <li>{@code organizationId != null && !blank} → org-strict JOIN on parent
     *       {@code data_sources.organization_id}. Teammates of the same workspace see each other's
     *       items even when {@code tenant_id} differs.</li>
     *   <li>{@code organizationId == null} → legacy tenant-only fallback (creator's items only).
     *       WARN-logged so a missing org context surfaces in prod logs rather than silently
     *       returning empty results to teammates.</li>
     * </ul>
     */
    public List<DataSourceItem> getDataSourceItemsByTenantAndDataSourcePaginated(Integer dataSourceId,
                                                                                  String tenantId,
                                                                                  String organizationId,
                                                                                  int offset,
                                                                                  int limit) {
        if (dataSourceId == null) {
            return List.of();
        }
        if (offset < 0 || limit <= 0) {
            return List.of();
        }
        if (organizationId != null && !organizationId.isBlank()) {
            return dataSourceItemRepository.findByDataSourceIdInOrgScopePaginated(
                    dataSourceId.longValue(), organizationId, offset, limit);
        }
        if (tenantId == null) {
            return List.of();
        }
        logger.warn("[DataSourceService] paginated items fetch fell back to tenant-only scope "
                + "(no organizationId) - teammates of the owning org will see 0 rows: dsId={}, tenant={}",
                dataSourceId, tenantId);
        return dataSourceItemRepository.findByDataSourceIdAndTenantIdPaginated(
                dataSourceId.longValue(), tenantId, offset, limit);
    }

    /**
     * Backward-compat overload: tenant-only count. Same caveat as the 4-arg
     * {@link #getDataSourceItemsByTenantAndDataSourcePaginated} variant.
     */
    public int getDataSourceItemsCount(Integer dataSourceId, String tenantId) {
        return getDataSourceItemsCount(dataSourceId, tenantId, null);
    }

    /**
     * Cheap COUNT(*) with optional org-strict scope. {@code int} is preserved on the public
     * signature because the upstream {@code DataSourceClient.getItemsCount} consumer expects
     * {@code int}; values above {@code Integer.MAX_VALUE} are clamped at the source. Scope
     * selection mirrors {@link #getDataSourceItemsByTenantAndDataSourcePaginated}.
     */
    public int getDataSourceItemsCount(Integer dataSourceId, String tenantId, String organizationId) {
        if (dataSourceId == null) {
            return 0;
        }
        long count;
        if (organizationId != null && !organizationId.isBlank()) {
            count = dataSourceItemRepository.countByDataSourceIdInOrgScope(
                    dataSourceId.longValue(), organizationId);
        } else {
            if (tenantId == null) {
                return 0;
            }
            logger.warn("[DataSourceService] items count fell back to tenant-only scope "
                    + "(no organizationId): dsId={}, tenant={}", dataSourceId, tenantId);
            count = dataSourceItemRepository.countByDataSourceIdAndTenantId(
                    dataSourceId.longValue(), tenantId);
        }
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    public DataSource updateMappingSpec(Long id, Map<String, ColumnMappingSpec> mappingSpec) {
        return dataSourceRepository.findById(id)
                .map(existing -> {
                    DataSource updated = new DataSource(
                            existing.id(), existing.tenantId(), existing.name(), existing.description(),
                            existing.sourceType(), existing.sourceConfig(), existing.status(),
                            existing.createdAt(), Instant.now(), existing.createdBy(),
                            existing.columnOrder(), mappingSpec,
                            existing.sourceWorkflowId(),
                            existing.sourcePublicationId(),
                            existing.projectId(), existing.organizationId()
                    );
                    return dataSourceRepository.save(updated);
                })
                .orElseThrow(() -> new IllegalArgumentException("DataSource not found: " + id));
    }

    public Map<String, ColumnMappingSpec> getMappingSpec(Long id) {
        return dataSourceRepository.findById(id)
                .map(DataSource::mappingSpec)
                .orElseThrow(() -> new IllegalArgumentException("DataSource not found: " + id));
    }

    /**
     * Clone a data source by creating a copy with all its items.
     * Copies: name (with " (Copy)" suffix), description, sourceType, sourceConfig, columnOrder, mappingSpec, and all items.
     * Does NOT copy: sourceWorkflowId.
     */
    public DataSource cloneDataSource(Long sourceId, String tenantId) {
        return cloneDataSource(sourceId, tenantId, null);
    }

    public DataSource cloneDataSource(Long sourceId, String tenantId, String orgRole) {
        DataSource source = dataSourceRepository.findById(sourceId)
            .orElseThrow(() -> new IllegalArgumentException("DataSource not found: " + sourceId));
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, source.tenantId(), source.organizationId())) {
            throw new IllegalArgumentException("DataSource tenant mismatch");
        }

        // PR-2.c: org-scoped deny-list enforcement on clone.
        String sourceOrgId = source.organizationId();
        if (sourceOrgId != null
                && !orgAccessService.canWrite(sourceOrgId, tenantId, "datasource", sourceId.toString(), orgRole)) {
            logger.warn("OrgAccess denied: user {} restricted from cloning datasource {} in org {}",
                    tenantId, sourceId, sourceOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "datasource", sourceId.toString());
        }

        DataSource clone = new DataSource(
            null, tenantId, source.name() + " (Copy)", source.description(),
            source.sourceType(), source.sourceConfig(),
            DataSourceStatus.ACTIVE, Instant.now(), Instant.now(), tenantId,
            source.columnOrder(), source.mappingSpec(),
            null, null, null, source.organizationId()
        );
        DataSource saved = dataSourceRepository.save(clone);

        // Copy all items
        List<DataSourceItem> items = dataSourceItemRepository.findByDataSourceId(sourceId);
        long totalSize = 0;
        for (DataSourceItem item : items) {
            dataSourceItemRepository.save(new DataSourceItem(
                null, saved.id(), tenantId, item.data(), item.priority(), Instant.now()
            ));
            totalSize += estimateItemSize(item.data());
        }
        breakdownService.increment(tenantId, "DATATABLES", totalSize, items.size());
        return saved;
    }

    public Optional<DataSource> findByIdAndTenantId(Integer id, String tenantId) {
        return dataSourceRepository.findByIdAndTenantId(id, tenantId);
    }

    /**
     * Strict-org single fetch. Prefer this over
     * {@link #findByIdAndTenantId(Integer, String)} whenever the caller
     * already holds {@code X-Organization-ID}. Closes the cross-tenant
     * bleed within a shared personal-org bucket.
     */
    public Optional<DataSource> findByIdAndOrganizationIdStrict(Integer id, String organizationId) {
        return dataSourceRepository.findByIdAndOrganizationIdStrict(id, organizationId);
    }

    public List<DataSource> findByTenantId(String tenantId) {
        return dataSourceRepository.findByTenantId(tenantId);
    }

    private long estimateItemSize(Map<String, Object> item) {
        if (item == null || item.isEmpty()) return 0;
        try {
            return objectMapper.writeValueAsBytes(item).length;
        } catch (Exception e) {
            return 0;
        }
    }
}

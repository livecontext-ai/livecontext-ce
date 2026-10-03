package com.apimarketplace.datasource.controllers.internal;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.recentactivity.RecentActivityItemDto;
import com.apimarketplace.common.recentactivity.RecentActivityScopeResultDto;
import com.apimarketplace.common.recentactivity.ResourceKind;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.datasource.crud.dto.CrudRequest;
import com.apimarketplace.datasource.crud.dto.CrudResponse;
import com.apimarketplace.datasource.crud.service.CrudExecutorService;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceItem;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceStatus;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceRepository;
import com.apimarketplace.datasource.persistence.MappingSpecConverter;
import com.apimarketplace.datasource.services.DataSourceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Internal API endpoints for orchestrator-service to access datasource operations.
 * These endpoints are NOT exposed through the gateway (inter-service calls only).
 */
@RestController
@RequestMapping("/api/internal/datasource")
public class InternalDataSourceController {

    private static final Logger log = LoggerFactory.getLogger(InternalDataSourceController.class);

    private final DataSourceService dataSourceService;
    private final DataSourceRepository dataSourceRepository;
    private final DataSourceItemRepository dataSourceItemRepository;
    private final CrudExecutorService crudExecutorService;
    private final com.apimarketplace.datasource.crud.service.MediaCellHydrator mediaCellHydrator;
    private final ObjectMapper objectMapper;
    private final com.apimarketplace.datasource.services.VectorFeatureGate vectorFeatureGate;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public InternalDataSourceController(DataSourceService dataSourceService,
                                         DataSourceRepository dataSourceRepository,
                                         DataSourceItemRepository dataSourceItemRepository,
                                         CrudExecutorService crudExecutorService,
                                         com.apimarketplace.datasource.crud.service.MediaCellHydrator mediaCellHydrator,
                                         ObjectMapper objectMapper,
                                         com.apimarketplace.datasource.services.VectorFeatureGate vectorFeatureGate,
                                         org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.dataSourceService = dataSourceService;
        this.dataSourceRepository = dataSourceRepository;
        this.dataSourceItemRepository = dataSourceItemRepository;
        this.crudExecutorService = crudExecutorService;
        this.mediaCellHydrator = mediaCellHydrator;
        this.objectMapper = objectMapper;
        this.vectorFeatureGate = vectorFeatureGate;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Bulk find datasources by IDs. Honors {@code X-Organization-ID} when present to scope the
     * lookup to a single workspace - closes the pre-existing cross-org leak where any caller
     * with a list of IDs could get back full rows regardless of org membership. Tenant-only
     * fallback kept for legacy callers + emits a WARN log so the missing org context surfaces.
     */
    @PostMapping("/bulk-find")
    public ResponseEntity<List<DataSource>> bulkFind(@RequestBody List<Long> ids,
                                                      @RequestHeader(value = "X-User-ID", required = false) String tenantId,
                                                      @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        List<DataSource> results;
        if (hasOrg(organizationId)) {
            results = dataSourceRepository.findAllByIdsAndOrganizationId(ids, organizationId);
        } else if (tenantId != null && !tenantId.isBlank()) {
            log.warn("[InternalDataSource] bulk-find fell back to tenant-only scope (no organizationId): tenantId={}, idsCount={}",
                    tenantId, ids == null ? 0 : ids.size());
            results = dataSourceRepository.findAllByIdsAndTenantId(ids, tenantId);
        } else {
            log.warn("[InternalDataSource] bulk-find called without tenantId or organizationId - returning empty");
            results = List.of();
        }
        return ResponseEntity.ok(results);
    }

    // ========== Recent Activity (fan-out branch for /api/activities/recent) ==========

    /**
     * Top-{@value #RECENT_LIMIT} data_sources in the caller's active workspace
     * ordered by last edit time, plus the peer-scope count. Called by
     * orchestrator's {@code RecentActivityAggregatorService} via
     * {@code DataSourceClient.getRecentTables}.
     *
     * <p>Scope routing matches the established strict-pair pattern. Backed by
     * the V238 partial indexes ({@code idx_data_sources_org_updated_at} +
     * {@code idx_data_sources_tenant_updated_at_personal}).
     *
     * <p>Frontend-facing kind is {@link ResourceKind#TABLE} (user-facing
     * vocabulary - see ResourceKind javadoc on the data_sources ↔ TABLE name
     * choice).
     */
    private static final int RECENT_LIMIT = 50;

    @GetMapping("/recent-activity")
    public ResponseEntity<RecentActivityScopeResultDto> getRecentActivity(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader("X-Organization-ID") String orgId) {

        // Post-V261: every user-scoped row has a non-null organization_id and the
        // gateway always injects X-Organization-ID (personal-workspace users get
        // their personal org UUID from auth.organization_member.is_default=true).
        TenantResolver.requireOrgId(orgId);

        List<DataSource> rows = dataSourceRepository.findRecentByOrganizationIdStrict(orgId, RECENT_LIMIT);
        // Peer-scope count is informational only (empty-state hint). Now that every
        // resource lives under an org, there is no separate "personal IS NULL" peer
        // bucket - surface 0 to keep the DTO shape stable.
        int peerScopeCount = 0;

        List<RecentActivityItemDto> items = rows.stream()
                .map(ds -> RecentActivityItemDto.builder()
                        .kind(ResourceKind.TABLE)
                        .resourceId(ds.id() != null ? ds.id().toString() : "")
                        .name(ds.name())
                        .lastEditedAt(ds.updatedAt())
                        .actorId(ds.tenantId())
                        .build())
                .toList();

        return ResponseEntity.ok(new RecentActivityScopeResultDto(items, peerScopeCount));
    }

    /**
     * Get item count for a datasource. Honors {@code X-Organization-ID} when present so org
     * teammates see the same count as the creator (items table has no org_id column; isolation
     * is enforced via the parent {@code data_sources.organization_id}).
     */
    @GetMapping("/{id}/items/count")
    public ResponseEntity<Integer> getItemsCount(@PathVariable Long id,
                                                   @RequestHeader("X-User-ID") String tenantId,
                                                   @RequestHeader(value = "X-Organization-ID", required = false)
                                                       String organizationId) {
        int count = dataSourceService.getDataSourceItemsCount(id.intValue(), tenantId, organizationId);
        return ResponseEntity.ok(count);
    }

    /**
     * Get paginated items for a datasource.
     */
    @GetMapping("/{id}/items/page")
    public ResponseEntity<Map<String, Object>> getDataSourceData(
            @PathVariable Long id,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String predicate) {

        List<Map<String, Object>> data;
        if (predicate != null && !predicate.isBlank()) {
            data = dataSourceService.getDataSourceData(id, predicate, null, limit);
        } else {
            List<DataSourceItem> items = dataSourceService.getDataSourceItemsByTenantAndDataSourcePaginated(
                    id.intValue(), tenantId, offset, limit);
            data = items.stream().map(DataSourceItem::data).toList();
        }

        int totalCount = dataSourceService.getDataSourceItemsCount(id.intValue(), tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", data);
        result.put("totalCount", totalCount);
        result.put("hasMore", offset + limit < totalCount);
        return ResponseEntity.ok(result);
    }

    /**
     * Get raw items for a datasource (for trigger data resolution + interface-render path).
     * Honors {@code X-Organization-ID} when present - see {@link #getItemsCount} for rationale.
     *
     * <p>{@code hydrateMedia} decides which of the two legitimate answers the caller wants, and it
     * defaults to the STORED one. A caller that is going to RUN on the value wants media cells as
     * file objects, like every other read; a caller that is COPYING the table - the publication
     * snapshot, and the live side of the moderation diff that is compared against an older snapshot -
     * must get exactly what is stored, or an unchanged table reads as changed on every media cell.
     *
     * <p>{@code excludeRestricted=true} marks such a copy (publication snapshot, moderation view):
     * RESTRICTED rows are left out by the query, and {@code afterPriority} + {@code afterId} (the
     * last row of the previous page) resume it by keyset instead of offset. The cursor is read only
     * on a copy; every other caller keeps its offset / limit read unchanged.
     */
    @GetMapping("/{id}/items")
    public ResponseEntity<List<DataSourceItem>> getItems(
            @PathVariable Long id,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean hydrateMedia,
            @RequestParam(defaultValue = "false") boolean excludeRestricted,
            @RequestParam(required = false) Integer afterPriority,
            @RequestParam(required = false) Long afterId) {

        if (excludeRestricted) {
            if ((afterPriority == null) != (afterId == null)) {
                // Half a cursor cannot position a page; guessing would skip or repeat rows.
                return ResponseEntity.badRequest().build();
            }
            DataSourceItemRepository.CopyCursor after = afterId != null
                    ? new DataSourceItemRepository.CopyCursor(afterPriority, afterId) : null;
            // CASA LC-066: a publication copy (DataSourceClient.getAllItems) must not carry Gmail /
            // Drive-derived rows into a marketplace snapshot. Left out by the query itself, before
            // the page is cut; an unreadable sensitivity is a failed query, which answers an error
            // without the confirmation header (fails closed).
            List<DataSourceItem> items = dataSourceService.getDataSourceItemsForCopy(
                    id.intValue(), tenantId, organizationId, offset, limit, after);
            // The caller copies nothing without this confirmation: a datasource-service older than
            // the filter ignores excludeRestricted and must not be mistaken for one that applied it.
            // No sensitivity header: the query already left every RESTRICTED row out.
            // And this one: filtered before the cut, cursor honoured. Without it a copy cannot tell
            // a last page from a page shortened by the filter, nor a repeated first page.
            return ResponseEntity.ok()
                    .header(com.apimarketplace.datasource.client.DataSourceClient.RESTRICTED_EXCLUDED_HEADER, "true")
                    .header(com.apimarketplace.datasource.client.DataSourceClient.COPY_KEYSET_HEADER, "true")
                    .body(hydrateMedia ? hydrateMediaCells(id, items) : items);
        }

        List<DataSourceItem> items = dataSourceService.getDataSourceItemsByTenantAndDataSourcePaginated(
                id.intValue(), tenantId, organizationId, offset, limit);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        // CASA LC-066: a run that loads Gmail-derived rows holds that content, so it is restricted.
        if (dataSourceService.containsRestrictedItems(id, items)) {
            response.header(com.apimarketplace.datasource.client.DataSourceClient.DATA_SENSITIVITY_HEADER, "RESTRICTED");
        }
        return response.body(hydrateMedia ? hydrateMediaCells(id, items) : items);
    }

    /**
     * Media cells in the shape the rest of the platform reads them.
     *
     * <p>Asked for by a table trigger's {@code data[]} and by the interface-render path. A table
     * trigger payload carries BOTH that array and the promoted {@code row}, and the row is hydrated
     * at its source, so leaving this raw made one payload disagree with itself:
     * {@code trigger:x.output.row.photo} an object, {@code trigger:x.output.data[0].data.photo} a
     * string - the second being the form the node docs tell an agent to write. The interface path
     * gains the same thing: its file-URL rewriters only recognise an object, so a media cell in a
     * datasource-backed page reached them as text and rendered as raw JSON.
     */
    private List<DataSourceItem> hydrateMediaCells(Long dataSourceId, List<DataSourceItem> items) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        // Before the lookup: a page with no cell that could be a file must not pay for a query,
        // and this runs on every trigger resolution and every interface render.
        if (items.stream().noneMatch(item -> item.data() != null && !item.data().isEmpty())) {
            return items;
        }
        Map<String, com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec> mappingSpec;
        try {
            mappingSpec = dataSourceService.getDataSource(dataSourceId)
                    .map(DataSource::mappingSpec)
                    .orElse(null);
        } catch (Exception e) {
            // Best-effort, like the event path: the caller still gets its rows, in the stored form.
            log.warn("Could not read the column spec for datasource={} - media cells in this "
                    + "response keep their stored text form: {}", dataSourceId, e.getMessage());
            return items;
        }
        if (mediaCellHydrator.mediaColumns(mappingSpec).isEmpty()) {
            return items;
        }
        List<DataSourceItem> hydrated = new java.util.ArrayList<>(items.size());
        for (DataSourceItem item : items) {
            // A copy, not the repository's own map: the caller owns what it hands back.
            Map<String, Object> data = item.data() == null
                    ? null
                    : mediaCellHydrator.hydrateRow(new LinkedHashMap<>(item.data()), mappingSpec);
            hydrated.add(new DataSourceItem(item.id(), item.dataSourceId(), item.tenantId(),
                    data, item.priority(), item.createdAt()));
        }
        return hydrated;
    }

    /**
     * Clone a datasource (atomic operation).
     */
    @PostMapping("/clone")
    public ResponseEntity<DataSource> cloneDataSource(@RequestBody Map<String, Object> request) {
        Long sourceId = Long.valueOf(request.get("source_id").toString());
        String tenantId = (String) request.get("tenant_id");

        DataSource cloned = dataSourceService.cloneDataSource(sourceId, tenantId);
        return ResponseEntity.ok(cloned);
    }

    /**
     * Count datasources for a tenant.
     */
    @GetMapping("/count")
    public ResponseEntity<Long> countByTenant(@RequestHeader("X-User-ID") String tenantId) {
        long count = dataSourceRepository.countByTenantId(tenantId);
        return ResponseEntity.ok(count);
    }

    /**
     * Delete all datasources associated with a workflow. MUTATING - must be scoped to prevent
     * a workflow-ID guess from cascading a delete across orgs. Org-strict path is preferred;
     * tenant-only fallback for legacy callers (WARN-logged).
     */
    @DeleteMapping("/by-workflow/{workflowId}")
    public ResponseEntity<Void> deleteByWorkflow(@PathVariable UUID workflowId,
                                                   @RequestHeader("X-User-ID") String tenantId,
                                                   @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        // Read the vector-carrying datasources BEFORE the bulk delete: this path
        // deletes through the repository and never reaches
        // DataSourceService.deleteDataSource, so without this every vector table
        // owned by a deleted workflow left its HNSW index behind.
        List<DataSource> doomed = hasOrg(organizationId)
                ? dataSourceRepository.findBySourceWorkflowIdAndOrganizationId(workflowId, organizationId)
                : dataSourceRepository.findBySourceWorkflowIdAndTenantId(workflowId, tenantId);
        List<Long> vectorDataSourceIds = doomed.stream()
                .filter(ds -> com.apimarketplace.datasource.services.VectorFeatureGate.findVectorColumn(ds.mappingSpec()) != null)
                .map(DataSource::id)
                .toList();

        if (hasOrg(organizationId)) {
            dataSourceRepository.deleteBySourceWorkflowIdAndOrganizationId(workflowId, organizationId);
        } else {
            log.warn("[InternalDataSource] delete by-workflow fell back to tenant-only scope (no organizationId): tenantId={}, workflowId={}",
                    tenantId, workflowId);
            dataSourceRepository.deleteBySourceWorkflowIdAndTenantId(workflowId, tenantId);
        }
        for (Long id : vectorDataSourceIds) {
            eventPublisher.publishEvent(new com.apimarketplace.datasource.events.VectorDataSourceDeletedEvent(id));
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Find datasources by source workflow ID. Same org-strict / tenant-fallback pattern as
     * {@link #bulkFind} - closes the pre-existing cross-org leak.
     */
    @GetMapping("/by-workflow/{workflowId}")
    public ResponseEntity<List<DataSource>> findByWorkflow(@PathVariable UUID workflowId,
                                                            @RequestHeader("X-User-ID") String tenantId,
                                                            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        List<DataSource> results;
        if (hasOrg(organizationId)) {
            results = dataSourceRepository.findBySourceWorkflowIdAndOrganizationId(workflowId, organizationId);
        } else {
            log.warn("[InternalDataSource] find by-workflow fell back to tenant-only scope (no organizationId): tenantId={}, workflowId={}",
                    tenantId, workflowId);
            results = dataSourceRepository.findBySourceWorkflowIdAndTenantId(workflowId, tenantId);
        }
        return ResponseEntity.ok(results);
    }

    /**
     * Update the project ID of a datasource.
     */
    @PutMapping("/{id}/project")
    public ResponseEntity<Void> updateProjectId(@PathVariable Long id,
                                                  @RequestBody Map<String, String> body,
                                                  @RequestHeader("X-User-ID") String tenantId,
                                                  @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
                                                  @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        String projectIdStr = body.get("project_id");
        UUID projectId = (projectIdStr != null && !projectIdStr.isBlank()) ? UUID.fromString(projectIdStr) : null;
        if (hasOrg(organizationId)
                && !dataSourceService.canWriteViaOrg(organizationId, tenantId, id.toString(), orgRole)) {
            return ResponseEntity.status(403).build();
        }
        int updated = hasOrg(organizationId)
                ? dataSourceRepository.updateProjectIdAndOrganizationId(id, projectId, organizationId)
                : dataSourceRepository.updateProjectIdAndTenantId(id, projectId, tenantId);
        return updated > 0 ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    /**
     * Execute a CRUD operation (used by CrudToolExecutor in orchestrator).
     *
     * <p>{@code X-Organization-ID} is forwarded so the service-layer
     * verifyDataSourceAccess assertion can enforce strict org-match on the
     * resolved data source (defense-in-depth). Optional for back-compat
     * with older orchestrator builds that have not yet threaded the
     * header.
     */
    @PostMapping("/crud/execute")
    public ResponseEntity<CrudResponse> executeCrud(@RequestBody CrudRequest request,
                                                      @RequestHeader("X-User-ID") String tenantId,
                                                      @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        var result = crudExecutorService.execute(request, tenantId, organizationId);
        return ResponseEntity.ok(CrudResponse.fromDomain(result));
    }

    /**
     * Get the mapping spec for a datasource.
     */
    @GetMapping("/{id}/mapping-spec")
    public ResponseEntity<Map<String, ?>> getMappingSpec(@PathVariable Long id,
                                                          @RequestHeader("X-User-ID") String tenantId) {
        var spec = dataSourceService.getMappingSpec(id);
        return ResponseEntity.ok(spec);
    }

    /**
     * Find datasource by ID. When {@code X-Organization-ID} is supplied (the
     * common case post-V261: gateway always injects the active workspace
     * orgId), the lookup is strict-org-scoped - closes the cross-tenant
     * bleed where two members of the same personal-org bucket would
     * otherwise see each other's data sources. Without the header (back-
     * compat path for legacy callers), falls back to the tenant-only
     * lookup so older orchestrator code keeps working until it threads
     * the header through.
     */
    @GetMapping("/{id}/by-tenant")
    public ResponseEntity<DataSource> findByIdAndTenant(@PathVariable Long id,
                                                          @RequestHeader("X-User-ID") String tenantId,
                                                          @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        var lookup = (organizationId != null && !organizationId.isBlank())
                ? dataSourceService.findByIdAndOrganizationIdStrict(id.intValue(), organizationId)
                : dataSourceService.findByIdAndTenantId(id.intValue(), tenantId);
        return lookup
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Find datasources by project ID.
     */
    @GetMapping("/by-project/{projectId}")
    public ResponseEntity<List<DataSource>> findByProject(@PathVariable UUID projectId,
                                                            @RequestHeader("X-User-ID") String tenantId,
                                                            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        List<DataSource> results = hasOrg(organizationId)
                ? dataSourceRepository.findByProjectIdAndOrganizationId(projectId, organizationId)
                : dataSourceRepository.findByProjectIdAndTenantId(projectId, tenantId);
        return ResponseEntity.ok(results);
    }

    /** Row sample per table for the project Tables tab card preview - mirrors the paged list. */
    private static final int PROJECT_SAMPLE_ROWS_PER_TABLE = 3;

    /**
     * Find a project's datasources WITH the same preview payload the paged list endpoint
     * returns ({@code rowCounts} + {@code sampleRows}), so a project's Tables tab renders the
     * identical mini-table card as {@code /app/tables}. Same project scoping as
     * {@link #findByProject}; the two batch queries ({@code countByDataSourceIds},
     * {@code sampleRowsByDataSourceIds}) run once over all the project's ids (never N+1).
     * The row-count / sample maps are JSON-keyed by datasource id as a string.
     */
    @GetMapping("/by-project/{projectId}/details")
    public ResponseEntity<Map<String, Object>> findByProjectWithPreview(@PathVariable UUID projectId,
                                                                          @RequestHeader("X-User-ID") String tenantId,
                                                                          @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        List<DataSource> items = hasOrg(organizationId)
                ? dataSourceRepository.findByProjectIdAndOrganizationId(projectId, organizationId)
                : dataSourceRepository.findByProjectIdAndTenantId(projectId, tenantId);

        List<Long> ids = items.stream().map(DataSource::id).toList();
        Map<Long, Long> rowCounts = dataSourceItemRepository.countByDataSourceIds(ids);
        Map<Long, List<Map<String, Object>>> sampleRows =
                dataSourceItemRepository.sampleRowsByDataSourceIds(ids, PROJECT_SAMPLE_ROWS_PER_TABLE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("rowCounts", rowCounts);
        body.put("sampleRows", sampleRows);
        return ResponseEntity.ok(body);
    }

    /**
     * Count datasources by project ID.
     */
    @GetMapping("/by-project/{projectId}/count")
    public ResponseEntity<Long> countByProject(@PathVariable UUID projectId,
                                                    @RequestHeader("X-User-ID") String tenantId,
                                                    @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        long count = hasOrg(organizationId)
                ? dataSourceRepository.countByProjectIdAndOrganizationId(projectId, organizationId)
                : dataSourceRepository.countByProjectIdAndTenantId(projectId, tenantId);
        return ResponseEntity.ok(count);
    }

    /**
     * Create a datasource from snapshot data (used during publication acquire/clone).
     * Accepts snapshot fields: name, description, sourceType, sourceConfig, columnOrder, mappingSpec,
     * sourcePublicationId.
     *
     * <p>{@code X-Organization-ID} header is the authoritative org for the new row when present -
     * the snapshot body's {@code organizationId} is treated as a fallback only. Prevents a forged
     * body from stamping a row into another workspace.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/create-from-snapshot")
    public ResponseEntity<DataSource> createFromSnapshot(@RequestBody Map<String, Object> snapshot,
                                                           @RequestHeader("X-User-ID") String tenantId,
                                                           @RequestHeader(value = "X-Organization-ID", required = false) String headerOrgId) {
        String name = (String) snapshot.get("name");
        String description = (String) snapshot.get("description");
        String sourceTypeStr = snapshot.get("sourceType") != null ? snapshot.get("sourceType").toString() : "INLINE";
        DataSourceType sourceType = DataSourceType.valueOf(sourceTypeStr);

        Map<String, Object> sourceConfig = snapshot.get("sourceConfig") instanceof Map
                ? (Map<String, Object>) snapshot.get("sourceConfig") : Map.of();
        List<Map<String, Object>> columnOrder = snapshot.get("columnOrder") instanceof List
                ? (List<Map<String, Object>>) snapshot.get("columnOrder") : List.of();

        Map<String, ColumnMappingSpec> mappingSpec = null;
        Object mappingSpecRaw = snapshot.get("mappingSpec");
        if (mappingSpecRaw instanceof Map) {
            mappingSpec = objectMapper.convertValue(mappingSpecRaw,
                    new TypeReference<Map<String, ColumnMappingSpec>>() {});
            // Edition gate: a marketplace snapshot published from a self-hosted
            // deployment can carry vector columns. On managed cloud they are
            // stripped (not rejected - the rest of the acquired table must
            // survive); the workflow's similarity steps then fail at run time
            // with the gate's explicit message. The same names are purged from
            // columnOrder so the UI doesn't render a ghost column.
            List<String> strippedColumns = vectorFeatureGate.disallowedVectorColumns(tenantId, mappingSpec);
            if (!strippedColumns.isEmpty()) {
                mappingSpec = vectorFeatureGate.stripDisallowedVectorColumns(tenantId, mappingSpec);
                columnOrder = columnOrder.stream()
                        .filter(entry -> !strippedColumns.contains(String.valueOf(entry.get("field"))))
                        .toList();
            }
        }

        String pubIdStr = (String) snapshot.get("sourcePublicationId");
        UUID sourcePublicationId = pubIdStr != null ? UUID.fromString(pubIdStr) : null;
        // Header wins over body - the header is forwarded by OrgContextHeaderForwarder from the
        // caller's authenticated workspace, while the body is whatever the caller sends.
        String bodyOrg = firstNonBlank(snapshot.get("organizationId"), snapshot.get("organization_id"));
        String organizationId;
        if (hasOrg(headerOrgId)) {
            organizationId = headerOrgId;
            // WARN-log header/body mismatch so a caller bug (e.g. publication path sending stale
            // acquirer org in body while the user has switched workspace) is debuggable instead
            // of silently landing in the header's org.
            if (bodyOrg != null && !headerOrgId.equals(bodyOrg)) {
                log.warn("[InternalDataSource] create-from-snapshot org mismatch - header={} wins over body={} (tenant={})",
                        headerOrgId, bodyOrg, tenantId);
            }
        } else {
            organizationId = bodyOrg;
        }
        // Post-V263 invariant: data_sources.organization_id is NOT NULL. Without either source
        // of org the DB save would throw a constraint violation, which DataSourceClient swallows
        // as null → publication acquire silently drops the cloned table. Fail fast here so the
        // caller sees a 400 and the missing org context surfaces in their logs.
        if (!hasOrg(organizationId)) {
            log.warn("[InternalDataSource] create-from-snapshot rejected: no organizationId in header or body (tenant={}, name={})",
                    tenantId, name);
            return ResponseEntity.badRequest().build();
        }

        DataSource newDs = new DataSource(
                null, tenantId, name, description, sourceType, sourceConfig,
                DataSourceStatus.ACTIVE, null, null, tenantId,
                columnOrder, mappingSpec, null,
                sourcePublicationId, null, organizationId
        );

        DataSource saved = dataSourceRepository.save(newDs);

        // This path saves through the repository, not through
        // DataSourceService.createDataSource, so it never published the event that
        // builds the per-datasource HNSW index. A cloned vector table therefore had
        // its rows but no index, and every similarity search on it sequential-scanned,
        // silently, at the measured 153x cost. Publish it here for the column the
        // clone kept (dimension and metric travel in the column's display config).
        String vectorColumn = com.apimarketplace.datasource.services.VectorFeatureGate.findVectorColumn(mappingSpec);
        if (vectorColumn != null) {
            com.apimarketplace.datasource.events.VectorColumnCreatedEvent
                    .forColumn(saved.id(), mappingSpec.get(vectorColumn).display())
                    .ifPresent(eventPublisher::publishEvent);
        }
        return ResponseEntity.ok(saved);
    }

    /**
     * Bulk insert items into a datasource (used during publication acquire/clone).
     * Each item in the list is a map with "data" and optional "priority" fields.
     *
     * <p>When {@code X-Organization-ID} is present, verifies the target datasource belongs to
     * the caller's workspace before writing - closes the pre-existing cross-org write gap where
     * a DS-ID guess could insert rows into another org's table. 404 on mismatch (does not
     * reveal whether the DS exists in a different org).
     */
    @PostMapping("/{id}/items/bulk-insert")
    public ResponseEntity<Integer> bulkInsertItems(@PathVariable Long id,
                                                     @RequestBody List<Map<String, Object>> items,
                                                     @RequestHeader("X-User-ID") String tenantId,
                                                     @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        String ownerTenantId = resolveItemWriteOwnerTenant(id, tenantId, organizationId, "bulk-insert");
        if (ownerTenantId == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(insertItemSnapshots(id, items, ownerTenantId));
    }

    /**
     * Replace every row of a datasource with the supplied snapshots, in one transaction
     * (used by the publication "reset application data" path, which restores an acquired
     * app's tables to the rows frozen in the publication snapshot).
     *
     * <p>Same ownership gate as {@link #bulkInsertItems}. Atomic on purpose: a
     * delete-then-insert split across two calls would leave the table visibly empty (and
     * readable as such by a concurrent workflow run) between the two.
     */
    @Transactional
    @PostMapping("/{id}/items/replace")
    public ResponseEntity<Integer> replaceItems(@PathVariable Long id,
                                                  @RequestBody List<Map<String, Object>> items,
                                                  @RequestHeader("X-User-ID") String tenantId,
                                                  @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        String ownerTenantId = resolveItemWriteOwnerTenant(id, tenantId, organizationId, "replace");
        if (ownerTenantId == null) {
            return ResponseEntity.notFound().build();
        }
        dataSourceItemRepository.deleteByDataSourceId(id);
        int inserted = insertItemSnapshots(id, items, ownerTenantId);
        log.info("[InternalDataSource] replaced rows of ds={} with {} snapshot rows (tenant={})",
                id, inserted, ownerTenantId);
        return ResponseEntity.ok(inserted);
    }

    /**
     * Ownership gate shared by every item-write endpoint. Returns the tenant the new rows
     * must be stamped with, or {@code null} when the caller may not write to this datasource.
     *
     * <p>Item-row invariant: rows are stamped with the parent DS owner's tenantId, not the
     * caller's - caller-stamped rows are invisible to every owner-tenant-scoped read (see
     * CrudExecutorService.execute + V333).
     */
    private String resolveItemWriteOwnerTenant(Long id, String tenantId, String organizationId, String operation) {
        if (hasOrg(organizationId)) {
            // Org-strict gate: the parent DS must belong to this workspace.
            Optional<DataSource> inScope = dataSourceService.findByIdAndOrganizationIdStrict(id.intValue(), organizationId);
            if (inScope.isEmpty()) {
                log.warn("[InternalDataSource] {} rejected: ds={} not in org={}, tenant={}",
                        operation, id, organizationId, tenantId);
                return null;
            }
            return inScope.get().tenantId();
        }
        // Tenant-only fallback. Pre-fix this path inserted without ANY ownership check -
        // letting a caller without an org header have MORE privilege than one with the
        // header (a perverse incentive). Minimum gate: the DS must belong to this tenant.
        // Post-V261 the gateway always injects X-Organization-ID, so this path should only
        // fire for internal/scheduler callers - and even those should not write to a foreign
        // DS by ID guess.
        boolean tenantOwns = dataSourceService.findByIdAndTenantId(id.intValue(), tenantId).isPresent();
        if (!tenantOwns) {
            log.warn("[InternalDataSource] {} rejected: ds={} not owned by tenant={} (no orgId either)",
                    operation, id, tenantId);
            return null;
        }
        log.warn("[InternalDataSource] {} fell back to tenant-only scope (no organizationId): tenantId={}, dsId={}",
                operation, tenantId, id);
        return tenantId; // tenant gate passed → caller IS the owner
    }

    @SuppressWarnings("unchecked")
    private int insertItemSnapshots(Long dataSourceId, List<Map<String, Object>> items, String ownerTenantId) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Map<String, Object> itemSnapshot : items) {
            Map<String, Object> data = itemSnapshot.get("data") instanceof Map
                    ? (Map<String, Object>) itemSnapshot.get("data") : Map.of();
            Integer priority = itemSnapshot.get("priority") instanceof Number
                    ? ((Number) itemSnapshot.get("priority")).intValue() : null;

            DataSourceItem newItem = new DataSourceItem(null, dataSourceId, ownerTenantId, data, priority, null);
            dataSourceItemRepository.save(newItem);
            count++;
        }
        return count;
    }

    // ========== Internal CRUD endpoints (mirror public API for inter-service calls) ==========

    /**
     * Create a new datasource (inter-service equivalent of POST /api/data-sources).
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/create")
    public ResponseEntity<DataSource> createDataSource(
            @RequestBody Map<String, Object> request,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            String name = (String) request.get("name");
            String description = (String) request.get("description");
            String sourceTypeStr = (String) request.get("sourceType");
            DataSourceType sourceType = sourceTypeStr != null ? DataSourceType.valueOf(sourceTypeStr) : DataSourceType.INLINE;
            Map<String, Object> sourceConfig = request.get("sourceConfig") instanceof Map
                    ? (Map<String, Object>) request.get("sourceConfig") : Map.of();
            List<Map<String, Object>> data = (List<Map<String, Object>>) request.get("data");
            String createdBy = (String) request.get("createdBy");
            if (createdBy == null || createdBy.isBlank()) {
                createdBy = tenantId;
            }
            Map<String, ColumnMappingSpec> mappingSpec = MappingSpecConverter.fromUntyped(
                    request.get("mappingSpec"), objectMapper
            );

            DataSource dataSource = dataSourceService.createDataSource(
                    tenantId, name, description, sourceType, sourceConfig, data, createdBy, mappingSpec, orgId
            );
            return ResponseEntity.ok(dataSource);
        } catch (Exception e) {
            log.error("Internal create datasource failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(null);
        }
    }

    /**
     * Get a datasource by ID (inter-service equivalent of GET /api/data-sources/{id}).
     */
    @GetMapping("/{id}/get")
    public ResponseEntity<DataSource> getDataSource(@PathVariable Long id,
                                                      @RequestHeader("X-User-ID") String tenantId,
                                                      @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        return dataSourceService.getDataSource(id)
                .filter(ds -> ScopeGuard.isInStrictScope(tenantId, organizationId,
                        ds.tenantId(), ds.organizationId()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get all datasources for a tenant (inter-service equivalent of GET /api/data-sources).
     */
    @GetMapping("/all")
    public ResponseEntity<List<DataSource>> getAllDataSources(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        List<DataSource> dataSources = dataSourceService.getDataSources(tenantId, orgId, orgRole);
        return ResponseEntity.ok(dataSources);
    }

    /**
     * Update a datasource (inter-service equivalent of PUT /api/data-sources/{id}).
     */
    @PutMapping("/{id}/update")
    public ResponseEntity<DataSource> updateDataSource(@PathVariable Long id,
                                                         @RequestBody Map<String, Object> request,
                                                         @RequestHeader("X-User-ID") String tenantId,
                                                         @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        try {
            String name = (String) request.get("name");
            String description = (String) request.get("description");
            @SuppressWarnings("unchecked")
            Map<String, Object> sourceConfig = (Map<String, Object>) request.get("sourceConfig");

            DataSource dataSource = dataSourceService.updateDataSource(id, name, description, sourceConfig, tenantId, orgRole);
            if (dataSource != null) {
                return ResponseEntity.ok(dataSource);
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Internal update datasource id={} failed: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete a datasource (inter-service equivalent of DELETE /api/data-sources/{id}).
     */
    @DeleteMapping("/{id}/delete")
    public ResponseEntity<Void> deleteDataSource(@PathVariable Long id,
                                                    @RequestHeader("X-User-ID") String tenantId,
                                                    @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
                                                    @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        Optional<DataSource> dataSourceOpt = dataSourceService.getDataSource(id);
        if (dataSourceOpt.isPresent() && ScopeGuard.isInStrictScope(tenantId, organizationId,
                dataSourceOpt.get().tenantId(), dataSourceOpt.get().organizationId())) {
            dataSourceService.deleteDataSource(id, tenantId, orgRole);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value == null) continue;
            String text = value.toString();
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static boolean hasOrg(String organizationId) {
        return organizationId != null && !organizationId.isBlank();
    }
}

package com.apimarketplace.datasource.client;

import com.apimarketplace.common.recentactivity.RecentActivityScopeResultDto;
import com.apimarketplace.datasource.client.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for communicating with datasource-service.
 * Follows the same pattern as CreditConsumptionClient in common-lib.
 * <p>
 * All methods use the internal API endpoints of datasource-service.
 * The X-User-ID header is forwarded for tenant-scoped operations.
 */
public class DataSourceClient {

    private static final Logger log = LoggerFactory.getLogger(DataSourceClient.class);

    /**
     * Response header of the internal items read: {@code RESTRICTED} when a returned row is
     * (CASA LC-066). A header, so the rows' JSON, which other callers copy and compare, is unchanged.
     */
    public static final String DATA_SENSITIVITY_HEADER = "X-LiveContext-Data-Sensitivity";

    /**
     * Response header of the internal items read: {@code true} when the RESTRICTED rows were left
     * out as asked ({@code excludeRestricted=true}, CASA LC-066). A datasource-service that predates
     * the filter ignores the parameter and does not send this header, so a copy read without it is
     * refused rather than trusted (the rolling-update window).
     */
    public static final String RESTRICTED_EXCLUDED_HEADER = "X-LiveContext-Restricted-Excluded";

    /**
     * Response header of a copy page ({@code excludeRestricted=true}): {@code true} when the
     * RESTRICTED rows were left out BEFORE the page was cut and an {@code afterPriority} /
     * {@code afterId} cursor, when sent, was honoured. An older datasource-service filters after
     * the cut and ignores the cursor, so a copy page without it is not trusted.
     */
    public static final String COPY_KEYSET_HEADER = "X-LiveContext-Copy-Keyset";

    /**
     * Most rows a publication may ship per table, which bounds a publication copy
     * ({@link #getAllItems}): it reads ONE row past this, so a table at exactly the limit and a
     * larger one stay distinguishable and the caller's budget check refuses the larger one instead
     * of shipping a silently capped copy. publication-service's snapshot budget
     * ({@code publication.agent-snapshot.max-table-rows}, default 5000) is clamped to it.
     */
    public static final int MAX_COPY_ROWS = 5_000;

    /** Rows per request when {@link #getAllItems} pages through a table. */
    static final int COPY_PAGE_SIZE = 500;

    private final RestTemplate restTemplate;
    // Dedicated bounded-timeout template used ONLY by
    // {@link #getRecentTables} - see InterfaceClient/AgentClient for the
    // same pattern; auditor B v5 fix to keep the recent-activity branch from
    // parking an orchestrator aggregator thread on a slow datasource-service.
    private final RestTemplate recentActivityRestTemplate;
    private final String baseUrl;

    public DataSourceClient(String datasourceServiceUrl) {
        this.restTemplate = new RestTemplate();
        this.recentActivityRestTemplate = createRecentActivityRestTemplate();
        this.baseUrl = datasourceServiceUrl;
    }

    public DataSourceClient(RestTemplate restTemplate, String datasourceServiceUrl) {
        this.restTemplate = restTemplate;
        this.recentActivityRestTemplate = createRecentActivityRestTemplate();
        this.baseUrl = datasourceServiceUrl;
    }

    private static RestTemplate createRecentActivityRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return new RestTemplate(factory);
    }

    // ========== CRUD operations (via internal endpoints, no HMAC needed) ==========

    /**
     * Get a datasource by ID (tenant-scoped, org header only when a request or an async org
     * scope forwards one). Prefer {@link #getDataSource(Long, String, String)}.
     */
    public DataSourceDto getDataSource(Long id, String tenantId) {
        return getDataSource(id, tenantId, null);
    }

    /**
     * Get a datasource by ID in an explicit workspace scope. Use it whenever the caller holds
     * an organization id: off a request thread nothing forwards X-Organization-ID, and the
     * tenant-only scope reads a table created by another member of the workspace as missing.
     */
    public DataSourceDto getDataSource(Long id, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/datasource/" + id + "/get";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<DataSourceDto> response = restTemplate.exchange(url, HttpMethod.GET, entity, DataSourceDto.class);
            return response.getBody();
        } catch (Exception e) {
            logLookupFailure(e, "get datasource id=" + id + " tenant=" + tenantId + " org=" + organizationId);
            return null;
        }
    }

    /**
     * Get all datasources for a tenant.
     */
    public List<DataSourceDto> getDataSourcesByTenant(String tenantId) {
        String url = baseUrl + "/api/internal/datasource/all";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<DataSourceDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get datasources for tenant={}: {}", tenantId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get datasources with org-based access filtering.
     */
    public List<DataSourceDto> getDataSources(String tenantId, String orgId, String orgRole) {
        String url = baseUrl + "/api/internal/datasource/all";
        HttpHeaders headers = buildHeaders(tenantId);
        if (orgId != null) headers.set("X-Organization-ID", orgId);
        if (orgRole != null) headers.set("X-Organization-Role", orgRole);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<List<DataSourceDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get datasources for tenant={}, org={}: {}", tenantId, orgId, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ========== Internal API (used by orchestrator for operations not exposed publicly) ==========

    /**
     * Bulk find datasources by IDs.
     */
    public List<DataSourceDto> bulkFind(List<Long> ids, String tenantId) {
        return bulkFind(ids, tenantId, null);
    }

    /**
     * Bulk find datasources by IDs in an explicit organization scope.
     */
    public List<DataSourceDto> bulkFind(List<Long> ids, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/datasource/bulk-find";
        HttpEntity<List<Long>> entity = new HttpEntity<>(ids, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<List<DataSourceDto>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to bulk find datasources: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get item count for a datasource.
     */
    public int getItemsCount(Long dataSourceId, String tenantId) {
        String url = baseUrl + "/api/internal/datasource/" + dataSourceId + "/items/count";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Integer> response = restTemplate.exchange(url, HttpMethod.GET, entity, Integer.class);
            return response.getBody() != null ? response.getBody() : 0;
        } catch (Exception e) {
            log.error("Failed to get items count for ds={}: {}", dataSourceId, e.getMessage());
            return 0;
        }
    }

    /**
     * Get paginated items for a datasource.
     */
    public DataSourceDataDto getDataSourceData(Long dataSourceId, String tenantId, int offset, int limit) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/datasource/" + dataSourceId + "/items/page")
                .queryParam("offset", offset)
                .queryParam("limit", limit)
                .toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<DataSourceDataDto> response = restTemplate.exchange(url, HttpMethod.GET, entity, DataSourceDataDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get datasource data ds={}: {}", dataSourceId, e.getMessage());
            return new DataSourceDataDto(Collections.emptyList(), 0, false);
        }
    }

    /**
     * Get items with predicate filtering.
     */
    public DataSourceDataDto getDataSourceDataWithPredicate(Long dataSourceId, String tenantId,
                                                             int offset, int limit, String predicate) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/datasource/" + dataSourceId + "/items/page")
                .queryParam("offset", offset)
                .queryParam("limit", limit)
                .queryParam("predicate", predicate)
                .toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<DataSourceDataDto> response = restTemplate.exchange(url, HttpMethod.GET, entity, DataSourceDataDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get datasource data with predicate ds={}: {}", dataSourceId, e.getMessage());
            return new DataSourceDataDto(Collections.emptyList(), 0, false);
        }
    }

    /**
     * Clone a datasource (backward-compatible, relies on OrgContextHeaderForwarder).
     */
    public DataSourceDto cloneDataSource(Long sourceId, String tenantId) {
        return cloneDataSource(sourceId, tenantId, null);
    }

    /**
     * Clone a datasource in an explicit organization scope.
     */
    public DataSourceDto cloneDataSource(Long sourceId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/datasource/clone";
        CloneRequestDto request = new CloneRequestDto(sourceId, tenantId);
        HttpEntity<CloneRequestDto> entity = new HttpEntity<>(request, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<DataSourceDto> response = restTemplate.exchange(url, HttpMethod.POST, entity, DataSourceDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to clone datasource id={}: {}", sourceId, e.getMessage());
            return null;
        }
    }

    /**
     * Count datasources for a tenant.
     */
    public int countByTenantId(String tenantId) {
        String url = baseUrl + "/api/internal/datasource/count";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Integer> response = restTemplate.exchange(url, HttpMethod.GET, entity, Integer.class);
            return response.getBody() != null ? response.getBody() : 0;
        } catch (Exception e) {
            log.error("Failed to count datasources for tenant={}: {}", tenantId, e.getMessage());
            return 0;
        }
    }

    /**
     * Delete all datasources associated with a workflow (backward-compatible, relies on OrgContextHeaderForwarder).
     */
    public void deleteByWorkflowId(UUID workflowId, String tenantId) {
        deleteByWorkflowId(workflowId, tenantId, null);
    }

    /**
     * Delete all datasources associated with a workflow in an explicit organization scope.
     */
    public void deleteByWorkflowId(UUID workflowId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/datasource/by-workflow/" + workflowId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
        } catch (Exception e) {
            log.error("Failed to delete datasources for workflow={}: {}", workflowId, e.getMessage());
        }
    }

    /**
     * Update the project ID of a datasource.
     */
    public void updateProjectId(Long dataSourceId, UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/datasource/" + dataSourceId + "/project";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
                Map.of("project_id", projectId != null ? projectId.toString() : ""),
                buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
        } catch (Exception e) {
            log.error("Failed to update project for ds={}: {}", dataSourceId, e.getMessage());
        }
    }

    /**
     * Execute a CRUD operation (used by CrudToolExecutor in orchestrator).
     */
    public CrudResultDto executeCrud(CrudRequestDto request) {
        String url = baseUrl + "/api/internal/datasource/crud/execute";
        HttpEntity<CrudRequestDto> entity = new HttpEntity<>(request, buildHeaders(request.tenantId()));
        try {
            ResponseEntity<CrudResultDto> response = restTemplate.exchange(url, HttpMethod.POST, entity, CrudResultDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to execute CRUD operation: {}", e.getMessage());
            return new CrudResultDto(request.operation(), false, "Service unavailable: " + e.getMessage(), null);
        }
    }

    /**
     * Get the mapping spec for a datasource.
     */
    @SuppressWarnings("unchecked")
    public Map<String, ColumnMappingSpecDto> getMappingSpec(Long dataSourceId, String tenantId) {
        String url = baseUrl + "/api/internal/datasource/" + dataSourceId + "/mapping-spec";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            return response.getBody() != null ? response.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            log.error("Failed to get mapping spec for ds={}: {}", dataSourceId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Find datasource by ID and tenant ID.
     */
    public DataSourceDto findByIdAndTenantId(Long id, String tenantId) {
        return findByIdAndTenantId(id, tenantId, null);
    }

    /**
     * Find datasource by ID in an explicit workspace scope.
     */
    public DataSourceDto findByIdAndTenantId(Long id, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/datasource/" + id + "/by-tenant";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<DataSourceDto> response = restTemplate.exchange(url, HttpMethod.GET, entity, DataSourceDto.class);
            return response.getBody();
        } catch (Exception e) {
            logLookupFailure(e, "find datasource id=" + id + " tenant=" + tenantId + " org=" + organizationId);
            return null;
        }
    }

    /**
     * Find datasources by source workflow ID.
     */
    public List<DataSourceDto> findBySourceWorkflowId(UUID workflowId, String tenantId) {
        String url = baseUrl + "/api/internal/datasource/by-workflow/" + workflowId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<DataSourceDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to find datasources for workflow={}: {}", workflowId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Create a new datasource (backward-compatible, relies on OrgContextHeaderForwarder).
     */
    public DataSourceDto createDataSource(Map<String, Object> request, String tenantId) {
        return createDataSource(request, tenantId, null);
    }

    /**
     * Create a new datasource in an explicit organization scope.
     */
    public DataSourceDto createDataSource(Map<String, Object> request, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/datasource/create";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<DataSourceDto> response = restTemplate.exchange(url, HttpMethod.POST, entity, DataSourceDto.class);
            return response.getBody();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("Failed to create datasource: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Create datasource failed: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("Failed to create datasource: {}", e.getMessage());
            throw new RuntimeException("Create datasource failed: " + e.getMessage(), e);
        }
    }

    /**
     * Update a datasource (backward-compatible, relies on OrgContextHeaderForwarder).
     */
    public DataSourceDto updateDataSource(Long id, Map<String, Object> updates, String tenantId) {
        return updateDataSource(id, updates, tenantId, null);
    }

    /**
     * Update a datasource in an explicit organization scope.
     */
    public DataSourceDto updateDataSource(Long id, Map<String, Object> updates, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/datasource/" + id + "/update";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(updates, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<DataSourceDto> response = restTemplate.exchange(url, HttpMethod.PUT, entity, DataSourceDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to update datasource id={}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Delete a datasource.
     */
    public void deleteDataSource(Long id, String tenantId) {
        deleteDataSource(id, tenantId, null);
    }

    /**
     * Delete a datasource in an explicit organization scope.
     */
    public void deleteDataSource(Long id, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/datasource/" + id + "/delete";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
        } catch (Exception e) {
            log.error("Failed to delete datasource id={}: {}", id, e.getMessage());
        }
    }

    /**
     * Get datasources by project ID.
     */
    public List<DataSourceDto> findByProjectId(UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/datasource/by-project/" + projectId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<DataSourceDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to find datasources for project={}: {}", projectId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get a project's datasources WITH row counts + sample rows for the Tables tab card preview
     * (parity with {@code /app/tables}). Single round-trip; the preview maps are computed by two
     * batch queries server-side (never N+1). Returns {@link ProjectDataSourcesPreviewDto#empty()}
     * on failure so callers degrade to an empty list, never null.
     */
    public ProjectDataSourcesPreviewDto findByProjectIdWithPreview(UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/datasource/by-project/" + projectId + "/details";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<ProjectDataSourcesPreviewDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, ProjectDataSourcesPreviewDto.class);
            return response.getBody() != null ? response.getBody() : ProjectDataSourcesPreviewDto.empty();
        } catch (Exception e) {
            log.error("Failed to find datasources (with preview) for project={}: {}", projectId, e.getMessage());
            return ProjectDataSourcesPreviewDto.empty();
        }
    }

    /**
     * Count datasources by project ID.
     */
    public int countByProjectId(UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/datasource/by-project/" + projectId + "/count";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Integer> response = restTemplate.exchange(url, HttpMethod.GET, entity, Integer.class);
            return response.getBody() != null ? response.getBody() : 0;
        } catch (Exception e) {
            log.error("Failed to count datasources for project={}: {}", projectId, e.getMessage());
            return 0;
        }
    }

    /**
     * Get items for a datasource (used for trigger data resolution and the interface-render path).
     *
     * <p>Asks for media cells as file objects: these rows are about to be RUN on - mapped into node
     * parameters, rendered into a page - so they must look like the same rows read anywhere else.
     * {@link #getAllItems} deliberately does not ask, because it copies the table.
     */
    public List<DataSourceItemDto> getItems(Long dataSourceId, String tenantId, int offset, int limit) {
        return getItemsPage(dataSourceId, tenantId, offset, limit).items();
    }

    /**
     * {@link #getItems}, plus whether a returned row is RESTRICTED (CASA LC-066), for a caller
     * about to RUN on the rows: a run that loads Gmail-derived rows holds that content.
     */
    public DataSourceItemsPage getItemsPage(Long dataSourceId, String tenantId, int offset, int limit) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/datasource/" + dataSourceId + "/items")
                .queryParam("offset", offset)
                .queryParam("limit", limit)
                .queryParam("hydrateMedia", true)
                .toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<DataSourceItemDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            boolean restricted = "RESTRICTED".equalsIgnoreCase(
                    response.getHeaders().getFirst(DATA_SENSITIVITY_HEADER));
            return new DataSourceItemsPage(response.getBody(), restricted);
        } catch (Exception e) {
            log.error("Failed to get items for ds={}: {}", dataSourceId, e.getMessage());
            return new DataSourceItemsPage(Collections.emptyList(), false);
        }
    }

    /**
     * {@link #getAllItems(Long, String, String)} in tenant scope: a lenient copy, empty on failure
     * (see {@link #copyAllItems} for the paging, the confirmations and the row cap).
     */
    public List<DataSourceItemDto> getAllItems(Long dataSourceId, String tenantId) {
        return getAllItems(dataSourceId, tenantId, null);
    }

    /**
     * {@link #copyAllItems}, for a caller that can live without the rows: any failure answers an
     * empty list (logged) instead of throwing. Used by the moderation view of a listing, which
     * shows what it can; a PUBLISH must call {@link #copyAllItems}, so that a failed copy never
     * ships as an empty table.
     */
    public List<DataSourceItemDto> getAllItems(Long dataSourceId, String tenantId, String organizationId) {
        try {
            return copyAllItems(dataSourceId, tenantId, organizationId);
        } catch (TableCopyException e) {
            return Collections.emptyList();
        }
    }

    /**
     * A publication copy of a table, in an explicit organization scope (null = tenant scope): the
     * whole table in its stored order, or {@link #MAX_COPY_ROWS} + 1 rows when it is larger.
     * Throws {@link TableCopyException} when the copy cannot be completed, so a caller tells a
     * table that IS empty (an empty list) from one that could not be read.
     *
     * <p>Pages through the internal endpoint in pages of {@link #COPY_PAGE_SIZE} until a short page
     * (the {@code page} / {@code size} this method used to send were ignored by the endpoint, so a
     * copy held at most the first 50 rows). The first page is {@code offset=0}; each next one
     * resumes after the last row received ({@code afterPriority} / {@code afterId}, a keyset on the
     * total order {@code priority DESC, id ASC}), so a row inserted or deleted while the copy runs
     * does not shift the pages. Rows are de-duplicated by id. The copy is not one transaction: a
     * row whose priority changes mid-copy may be missed, and is never copied twice.
     *
     * <p>One row past the cap is read on purpose: {@code MAX_COPY_ROWS + 1} rows means "larger than
     * the cap", which the caller's snapshot budget refuses. Never a silently capped table.
     *
     * <p>Every page must confirm two things, or the copy fails: {@link #RESTRICTED_EXCLUDED_HEADER}
     * (RESTRICTED rows, Gmail / Drive-derived, CASA LC-066, were left out) and
     * {@link #COPY_KEYSET_HEADER} (they were left out BEFORE the page was cut, so a short page is
     * the last one, and a cursor was honoured). A datasource-service older than either answers
     * without it during a rolling update: trusting it could copy RESTRICTED rows, stop early on a
     * short page, or serve the first page again forever. Termination never depends on the server:
     * a full page that adds no new row fails the copy, and so does a copy still paging after
     * {@code ceil((MAX_COPY_ROWS + 1) / COPY_PAGE_SIZE) + 1} requests.
     *
     * <p>Deliberately does NOT ask for hydrated media cells: this feeds the publication snapshot and
     * the live side of the moderation diff, both of which are compared against stored copies. A
     * different encoding on one side would make an unchanged table read as changed on every media
     * cell.
     */
    public List<DataSourceItemDto> copyAllItems(Long dataSourceId, String tenantId, String organizationId) {
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        int wanted = MAX_COPY_ROWS + 1;
        int maxRequests = (wanted + COPY_PAGE_SIZE - 1) / COPY_PAGE_SIZE + 1;
        List<DataSourceItemDto> copy = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        DataSourceItemDto last = null;
        int received = 0;
        try {
            for (int request = 0; copy.size() < wanted; request++) {
                if (request >= maxRequests) {
                    throw copyFailure(dataSourceId, organizationId, copy.size(),
                            "still paging after " + maxRequests + " requests", null);
                }
                int limit = Math.min(COPY_PAGE_SIZE, wanted - copy.size());
                UriComponentsBuilder uri = UriComponentsBuilder
                        .fromHttpUrl(baseUrl + "/api/internal/datasource/" + dataSourceId + "/items")
                        .queryParam("offset", received)
                        .queryParam("limit", limit)
                        .queryParam("excludeRestricted", true);
                if (last != null) {
                    if (last.id() == null || last.priority() == null) {
                        throw copyFailure(dataSourceId, organizationId, copy.size(),
                                "a copied row has no id or priority to resume after", null);
                    }
                    uri.queryParam("afterPriority", last.priority()).queryParam("afterId", last.id());
                }
                ResponseEntity<List<DataSourceItemDto>> response = restTemplate.exchange(
                        uri.toUriString(), HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
                if (!"true".equalsIgnoreCase(response.getHeaders().getFirst(RESTRICTED_EXCLUDED_HEADER))) {
                    throw copyFailure(dataSourceId, organizationId, copy.size(), "the page did not confirm that "
                            + "RESTRICTED rows were left out (datasource-service older than the filter?)", null);
                }
                if (!"true".equalsIgnoreCase(response.getHeaders().getFirst(COPY_KEYSET_HEADER))) {
                    throw copyFailure(dataSourceId, organizationId, copy.size(), "the page did not confirm keyset "
                            + "paging (datasource-service older than the paged copy?)", null);
                }
                List<DataSourceItemDto> page = response.getBody() != null ? response.getBody() : List.of();
                received += page.size();
                int added = 0;
                for (DataSourceItemDto item : page) {
                    if (item.id() == null || seen.add(item.id())) {
                        copy.add(item);
                        added++;
                    }
                }
                if (page.size() < limit) {
                    return copy;
                }
                if (added == 0) {
                    throw copyFailure(dataSourceId, organizationId, copy.size(),
                            "a full page added no new row (the cursor was not honoured)", null);
                }
                last = page.get(page.size() - 1);
            }
            log.warn("Datasource ds={} org={} has more than {} copyable rows: the copy stops one row past the "
                    + "cap so the publication budget refuses it", dataSourceId, organizationId, MAX_COPY_ROWS);
            return copy;
        } catch (TableCopyException e) {
            throw e;
        } catch (Exception e) {
            throw copyFailure(dataSourceId, organizationId, copy.size(), e.getMessage(), e);
        }
    }

    private static TableCopyException copyFailure(Long dataSourceId, String organizationId, int copied,
                                                  String reason, Throwable cause) {
        log.error("Publication copy of ds={} org={} failed after {} rows, nothing copied: {}",
                dataSourceId, organizationId, copied, reason);
        return new TableCopyException(dataSourceId, "Copy of table " + dataSourceId + " failed: " + reason, cause);
    }

    /**
     * Create a datasource from snapshot data (backward-compatible, relies on OrgContextHeaderForwarder).
     * The snapshot map should contain: name, description, sourceType, sourceConfig, columnOrder, mappingSpec,
     * sourcePublicationId.
     */
    public DataSourceDto createFromSnapshot(Map<String, Object> snapshot, String tenantId) {
        return createFromSnapshot(snapshot, tenantId, null);
    }

    /**
     * Create a datasource from snapshot data in an explicit organization scope.
     * Used during publication acquire/clone.
     */
    public DataSourceDto createFromSnapshot(Map<String, Object> snapshot, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/datasource/create-from-snapshot";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(snapshot, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<DataSourceDto> response = restTemplate.exchange(url, HttpMethod.POST, entity, DataSourceDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create datasource from snapshot: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Bulk insert items into a datasource (used during publication acquire/clone).
     */
    public int bulkInsertItems(Long dataSourceId, List<Map<String, Object>> items, String tenantId) {
        String url = baseUrl + "/api/internal/datasource/" + dataSourceId + "/items/bulk-insert";
        HttpEntity<List<Map<String, Object>>> entity = new HttpEntity<>(items, buildHeaders(tenantId));
        try {
            ResponseEntity<Integer> response = restTemplate.exchange(url, HttpMethod.POST, entity, Integer.class);
            return response.getBody() != null ? response.getBody() : 0;
        } catch (Exception e) {
            log.error("Failed to bulk insert items for ds={}: {}", dataSourceId, e.getMessage());
            return 0;
        }
    }

    /**
     * Replace every row of a datasource with {@code items}, atomically (used by the
     * publication "reset application data" path). Unlike {@link #bulkInsertItems} this
     * WIPES the existing rows first, so the caller must already have decided the current
     * rows are disposable.
     *
     * @return the number of rows inserted, or -1 when the call failed (distinct from a
     *         legitimate 0-row replace, which the caller must not treat as an error)
     */
    public int replaceItems(Long dataSourceId, List<Map<String, Object>> items, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/datasource/" + dataSourceId + "/items/replace";
        HttpEntity<List<Map<String, Object>>> entity = new HttpEntity<>(items, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<Integer> response = restTemplate.exchange(url, HttpMethod.POST, entity, Integer.class);
            return response.getBody() != null ? response.getBody() : 0;
        } catch (Exception e) {
            log.error("Failed to replace items for ds={}: {}", dataSourceId, e.getMessage());
            return -1;
        }
    }

    // ========== Storage Usage ==========

    /**
     * Get storage usage for datatables category.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getDataSourceStorageUsage(String tenantId) {
        String url = baseUrl + "/api/internal/datasource/storage/usage";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);
            return response.getBody() != null ? response.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Failed to get datasource storage usage for tenant {}: {}", tenantId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ========== Recent Activity (fan-out branch for /api/activities/recent) ==========

    /**
     * Fetch the top-N most recently-edited tables (data_sources) in the
     * caller's active workspace plus the peer-scope count. Used by
     * orchestrator's {@code RecentActivityAggregatorService}.
     *
     * <p>Uses the dedicated {@link #recentActivityRestTemplate} (2s/3s) +
     * degrades to empty on failure. Method named "tables" not "datasources"
     * to align with the user-facing kind label (frontend renders the
     * {@link com.apimarketplace.common.recentactivity.ResourceKind#TABLE}
     * chip).
     */
    public RecentActivityScopeResultDto getRecentTables(String tenantId, String orgId) {
        if (tenantId == null || tenantId.isBlank()) {
            return new RecentActivityScopeResultDto(List.of(), 0);
        }
        String url = baseUrl + "/api/internal/datasource/recent-activity";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set("X-User-ID", tenantId);
        OrgContextHeaderForwarder.setIfPresent(headers, orgId);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<RecentActivityScopeResultDto> response = recentActivityRestTemplate.exchange(
                    url, HttpMethod.GET, entity, RecentActivityScopeResultDto.class);
            return response.getBody() != null
                    ? response.getBody()
                    : new RecentActivityScopeResultDto(List.of(), 0);
        } catch (Exception e) {
            log.warn("Failed to fetch recent activity (tables) tenant={} org={}: {}",
                    tenantId, orgId, e.getMessage());
            return new RecentActivityScopeResultDto(List.of(), 0);
        }
    }

    // ========== Helpers ==========

    /**
     * A single-table lookup answered 404 is the service saying "no such table in this scope",
     * a normal answer the caller turns into its own not-found result (null). Logging it at
     * ERROR buried real failures under routine validation misses, so 404 is WARN and only a
     * real failure (5xx, any other 4xx, I/O, timeout) stays ERROR.
     */
    private static void logLookupFailure(Exception e, String what) {
        if (e instanceof org.springframework.web.client.HttpClientErrorException.NotFound) {
            log.warn("Datasource not found ({}): {}", what, e.getMessage());
        } else {
            log.error("Failed to {}: {}", what, e.getMessage());
        }
    }

    private HttpHeaders buildHeaders(String tenantId) {
        return buildHeaders(tenantId, null);
    }

    private HttpHeaders buildHeaders(String tenantId, String organizationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        if (tenantId != null) {
            headers.set("X-User-ID", tenantId);
        }
        if (organizationId != null && !organizationId.isBlank()) {
            headers.set("X-Organization-ID", organizationId.trim());
        }
        // PR16 round-2 - forward X-Organization-ID / X-Organization-Role.
        // Pre-PR16 only the list-datasources path set these via explicit args;
        // other methods (get/update/delete/run/etc.) silently dropped org
        // context at the orchestrator → datasource-service hop.
        OrgContextHeaderForwarder.forward(headers);
        return headers;
    }

}

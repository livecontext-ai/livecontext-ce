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
     * Get a datasource by ID (tenant-scoped).
     */
    public DataSourceDto getDataSource(Long id, String tenantId) {
        String url = baseUrl + "/api/internal/datasource/" + id + "/get";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<DataSourceDto> response = restTemplate.exchange(url, HttpMethod.GET, entity, DataSourceDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get datasource id={}: {}", id, e.getMessage());
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
            log.error("Failed to find datasource id={} tenant={} org={}: {}", id, tenantId, organizationId, e.getMessage());
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
     * Get items for a datasource (used for trigger data resolution).
     */
    public List<DataSourceItemDto> getItems(Long dataSourceId, String tenantId, int offset, int limit) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/datasource/" + dataSourceId + "/items")
                .queryParam("offset", offset)
                .queryParam("limit", limit)
                .toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<DataSourceItemDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get items for ds={}: {}", dataSourceId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get ALL items for a datasource (no pagination).
     * Used by InterfaceRenderService and publication enrichment.
     */
    public List<DataSourceItemDto> getAllItems(Long dataSourceId, String tenantId) {
        return getAllItems(dataSourceId, tenantId, null);
    }

    /**
     * Get ALL items for a datasource in an explicit organization scope.
     */
    public List<DataSourceItemDto> getAllItems(Long dataSourceId, String tenantId, String organizationId) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/datasource/" + dataSourceId + "/items")
                .queryParam("page", 0)
                .queryParam("size", 10000)
                .toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<List<DataSourceItemDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get all items for ds={} org={}: {}", dataSourceId, organizationId, e.getMessage());
            return Collections.emptyList();
        }
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

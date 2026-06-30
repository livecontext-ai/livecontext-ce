package com.apimarketplace.publication.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

/**
 * HTTP client for calling orchestrator-service internal APIs.
 * Used by publication-service for workflow/run operations that stay in orchestrator.
 */
public class OrchestratorInternalClient {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorInternalClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;
    /**
     * Bounded-timeout client used ONLY for the synchronous pre-publish image
     * scan render. The main {@link #restTemplate} is intentionally left
     * untimed because legitimate calls like {@code captureShowcaseSnapshot}
     * can run long; but the screening render sits inside the publisher's
     * interactive {@code POST /pre-publish-scan} request, so a hung orchestrator
     * must fail fast (degrade to template-only) instead of stalling publish.
     */
    private final RestTemplate screeningRestTemplate;

    public OrchestratorInternalClient(String orchestratorUrl) {
        this(new RestTemplate(), orchestratorUrl);
    }

    public OrchestratorInternalClient(RestTemplate restTemplate, String orchestratorUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = orchestratorUrl;
        this.screeningRestTemplate = buildScreeningRestTemplate();
    }

    private static RestTemplate buildScreeningRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(20_000);
        return new RestTemplate(factory);
    }

    // ========== Workflow Operations ==========

    /**
     * Get workflow data for publication validation (plan, tenantId, type, status).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getWorkflowForPublication(UUID workflowId, String tenantId) {
        return getWorkflowForPublication(workflowId, tenantId, null);
    }

    /**
     * Get workflow data for publication validation in an explicit workspace scope.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getWorkflowForPublication(UUID workflowId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/publication-support/workflows/" + workflowId + "/for-publication";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get workflow {} for publication org={}: {}", workflowId, organizationId, e.getMessage());
            return null;
        }
    }

    /**
     * Get a specific plan version for a workflow.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPlanVersion(UUID workflowId, int version, String tenantId) {
        String url = baseUrl + "/api/internal/publication-support/workflows/" + workflowId + "/plan-version/" + version;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get plan version {} for workflow {}: {}", version, workflowId, e.getMessage());
            return null;
        }
    }

    /**
     * Get the latest plan version number for a workflow.
     */
    public Integer getLatestPlanVersion(UUID workflowId, String tenantId) {
        String url = baseUrl + "/api/internal/publication-support/workflows/" + workflowId + "/latest-plan-version";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Integer> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Integer.class);
            return response.getBody();
        } catch (Exception e) {
            log.debug("Failed to get latest plan version for workflow {}: {}", workflowId, e.getMessage());
            return null;
        }
    }

    /**
     * Create an APPLICATION workflow from an acquired publication.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createApplicationWorkflow(Map<String, Object> request, String tenantId) {
        String organizationId = request != null && request.get("organizationId") != null
                ? request.get("organizationId").toString()
                : null;
        return createApplicationWorkflow(request, tenantId, organizationId);
    }

    /**
     * Create an APPLICATION workflow from an acquired publication in an explicit workspace.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createApplicationWorkflow(Map<String, Object> request, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/publication-support/workflows/create-application";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create application workflow: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Refresh basePlan / plan / nodeIcons / title / description on an
     * existing APPLICATION workflow. Used on re-publish so the publisher's
     * own APPLICATION workflow tracks the new snapshot instead of staying
     * stuck on the original publish-time plan.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> refreshApplicationFromPublication(UUID applicationWorkflowId,
                                                                  Map<String, Object> request,
                                                                  String tenantId) {
        String url = baseUrl + "/api/internal/publication-support/workflows/"
                + applicationWorkflowId + "/refresh-from-publication";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to refresh application workflow {}: {}", applicationWorkflowId, e.getMessage());
            return null;
        }
    }

    /**
     * Enumerate every APPLICATION workflow (main + sub) tagged with this
     * publicationId in the tenant. Used by acquire-failure compensation
     * to clean up the full tree.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> findAllBySourcePublication(UUID publicationId, String tenantId) {
        return findAllBySourcePublication(publicationId, tenantId, null);
    }

    /**
     * Enumerate every APPLICATION workflow (main + sub) in the caller's exact scope.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> findAllBySourcePublication(UUID publicationId, String tenantId, String organizationId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/publication-support/workflows/all-by-source-publication")
                .queryParam("pubId", publicationId)
                .queryParam("tenantId", tenantId);
        if (hasText(organizationId)) {
            builder.queryParam("organizationId", organizationId);
        }
        String url = builder.toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to enumerate APPLICATION workflows for pub={} tenant={} org={}: {}",
                    publicationId, tenantId, organizationId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Find an existing acquired/application workflow by publication ID and tenant.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> findBySourcePublication(UUID publicationId, String tenantId) {
        return findBySourcePublication(publicationId, tenantId, null);
    }

    /**
     * Find an existing acquired/application workflow by publication ID in the caller's exact scope.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> findBySourcePublication(UUID publicationId, String tenantId, String organizationId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/publication-support/workflows/by-source-publication")
                .queryParam("pubId", publicationId)
                .queryParam("tenantId", tenantId);
        if (hasText(organizationId)) {
            builder.queryParam("organizationId", organizationId);
        }
        String url = builder.toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.debug("No application workflow found for publication {} tenant {} org {}",
                    publicationId, tenantId, organizationId);
            return null;
        }
    }

    /**
     * Check if a tenant already has a workflow from a specific publication.
     */
    public boolean existsBySourcePublication(UUID publicationId, String tenantId) {
        return existsBySourcePublication(publicationId, tenantId, null);
    }

    /**
     * Check if the caller's exact scope already has a workflow from a specific publication.
     */
    public boolean existsBySourcePublication(UUID publicationId, String tenantId, String organizationId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/publication-support/workflows/exists-by-source")
                .queryParam("pubId", publicationId)
                .queryParam("tenantId", tenantId);
        if (hasText(organizationId)) {
            builder.queryParam("organizationId", organizationId);
        }
        String url = builder.toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<Boolean> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Boolean.class);
            return Boolean.TRUE.equals(response.getBody());
        } catch (Exception e) {
            log.warn("Failed to check source publication existence: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get acquired workflows for a tenant.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAcquiredWorkflows(String tenantId) {
        return getAcquiredWorkflows(tenantId, null);
    }

    /**
     * Get acquired workflows for the caller's exact workspace.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getAcquiredWorkflows(String tenantId, String organizationId) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/api/internal/publication-support/workflows/acquired/" + tenantId);
        if (hasText(organizationId)) {
            builder.queryParam("organizationId", organizationId);
        }
        String url = builder.toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to get acquired workflows for tenant {} org {}: {}",
                    tenantId, organizationId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * List all non-APPLICATION workflow IDs for a tenant.
     * Used for agent publication when mode=all and no explicit workflow list.
     */
    @SuppressWarnings("unchecked")
    public List<String> getWorkflowIdsByTenant(String tenantId) {
        return getWorkflowIdsByTenant(tenantId, null);
    }

    /**
     * List all non-APPLICATION workflow IDs for a caller workspace.
     * Used for agent publication when mode=all and no explicit workflow list.
     */
    @SuppressWarnings("unchecked")
    public List<String> getWorkflowIdsByTenant(String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/publication-support/workflows/ids-by-tenant/" + tenantId;
        if (hasText(organizationId)) {
            url = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("organizationId", organizationId)
                    .toUriString();
        }
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<List<String>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to get workflow IDs for tenant {} org {}: {}", tenantId, organizationId, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ========== Run Operations ==========

    /**
     * Clone a showcase run.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> cloneRun(String sourceRunId, String prefix, String publicationId) {
        String url = baseUrl + "/api/internal/publication-support/runs/clone";
        Map<String, Object> body = Map.of(
                "sourceRunId", sourceRunId,
                "prefix", prefix,
                "publicationId", publicationId
        );
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(null));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to clone run {}: {}", sourceRunId, e.getMessage());
            return null;
        }
    }

    /**
     * Delete a cloned run.
     */
    public void deleteClonedRun(String runId) {
        String url = baseUrl + "/api/internal/publication-support/runs/clone/" + runId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to delete cloned run {}: {}", runId, e.getMessage());
        }
    }

    /**
     * Validate that a run is suitable as the source of a publication. The
     * naming carries the legacy "showcase" verb for URL backward-compat -
     * it does NOT check for any {@code showcase_*} prefix. See
     * {@code validateRunForPublication} on the orchestrator side.
     */
    public Map<String, Object> validateRunForPublication(String runId, String tenantId) {
        return validateShowcaseRun(runId, tenantId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> validateShowcaseRun(String runId, String tenantId) {
        return validateShowcaseRun(runId, tenantId, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> validateShowcaseRun(String runId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/publication-support/runs/" + runId + "/validate-showcase";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            log.warn("Showcase run validation failed for {}: HTTP {} - {}", runId, e.getStatusCode(), e.getResponseBodyAsString());
            return null;
        } catch (Exception e) {
            log.warn("Showcase run validation failed for {}: {}", runId, e.getMessage());
            return null;
        }
    }

    /**
     * Fetch interface_run_snapshots for a run keyed by interfaceId.toString().
     * Used by {@code WorkflowPublicationService.enrichPlanWithInterfaceData} to
     * embed the runtime variable/action mappings into {@code planSnapshot} so
     * the marketplace preview doesn't have to read from the publisher's tenant.
     *
     * @return map {interfaceId → {"variableMappings": ..., "actionMappings": ...}}
     *         - empty map when no snapshots, null on transport failure.
     */
    public Map<String, Map<String, Object>> getInterfaceSnapshotsForRun(String runIdPublic, String tenantId) {
        return getInterfaceSnapshotsForRun(runIdPublic, tenantId, null);
    }

    public Map<String, Map<String, Object>> getInterfaceSnapshotsForRun(String runIdPublic, String tenantId, String organizationId) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/api/internal/publication-support/runs/" + runIdPublic + "/interface-snapshots")
                .queryParam("tenantId", tenantId);
        if (hasText(organizationId)) {
            builder.queryParam("organizationId", organizationId);
        }
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<Map<String, Map<String, Object>>> response = restTemplate.exchange(
                    builder.toUriString(), HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Map.of();
        } catch (Exception e) {
            log.warn("Failed to fetch interface snapshots for run {} (tenant={} org={}): {}",
                    runIdPublic, tenantId, organizationId, e.getMessage());
            return null;
        }
    }

    /**
     * Capture a complete frozen view of a run (run state, aggregated steps,
     * per-epoch signals, and per-interface pre-rendered templates + items)
     * for storage on the publication entity's {@code showcase_snapshot}
     * JSONB column. Returned shape is documented on
     * {@code ShowcaseSnapshotBuilder} (orchestrator).
     *
     * @return the snapshot JSON or null on transport / 404 from upstream.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> captureShowcaseSnapshot(String runIdPublic, String tenantId) {
        return captureShowcaseSnapshot(runIdPublic, tenantId, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> captureShowcaseSnapshot(String runIdPublic, String tenantId, String organizationId) {
        return captureShowcaseSnapshot(runIdPublic, tenantId, organizationId, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> captureShowcaseSnapshot(String runIdPublic, String tenantId, String organizationId, Integer epochFilter) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/api/internal/publication-support/runs/" + runIdPublic + "/full-snapshot")
                .queryParam("tenantId", tenantId);
        if (hasText(organizationId)) {
            builder.queryParam("organizationId", organizationId);
        }
        if (epochFilter != null) {
            builder.queryParam("epochFilter", epochFilter);
        }
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    builder.toUriString(), HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (HttpStatusCodeException e) {
            log.warn("captureShowcaseSnapshot upstream returned {}: run={} tenant={} org={}",
                    e.getStatusCode(), runIdPublic, tenantId, organizationId);
            return null;
        } catch (Exception e) {
            log.warn("Failed to capture showcase snapshot for run {} (tenant={} org={}): {}",
                    runIdPublic, tenantId, organizationId, e.getMessage());
            return null;
        }
    }

    /**
     * Render a (source) run's interface and return ONLY the resolved
     * {@code items} list (each carrying a {@code data} sub-map). Used by the
     * pre-publish image screening to scan {@code items[].data} for images that
     * the static template hides behind {@code {{placeholders}}}.
     *
     * <p>Best-effort: returns an empty list on any transport/404/render error
     * so screening degrades to template-only rather than blocking publish.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> renderInterfaceItemsForRun(String runIdPublic, UUID interfaceId,
                                                                Integer epoch, String tenantId, String organizationId) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/api/internal/publication-support/runs/" + runIdPublic + "/interface-render")
                .queryParam("interfaceId", interfaceId);
        if (epoch != null) {
            builder.queryParam("epoch", epoch);
        }
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<Map<String, Object>> response = screeningRestTemplate.exchange(
                    builder.toUriString(), HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            Object items = response.getBody() != null ? response.getBody().get("items") : null;
            return items instanceof List ? (List<Map<String, Object>>) items : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to render interface {} items for run {} (tenant={} org={}): {}",
                    interfaceId, runIdPublic, tenantId, organizationId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Cleanup application runs for a workflow/publication.
     */
    public void cleanupApplicationRuns(UUID workflowId, String publicationId, String tenantId) {
        String url = baseUrl + "/api/internal/publication-support/runs/cleanup-application";
        Map<String, Object> body = new HashMap<>();
        body.put("workflowId", workflowId.toString());
        body.put("publicationId", publicationId);
        body.put("tenantId", tenantId);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to cleanup application runs for workflow={}: {}", workflowId, e.getMessage());
        }
    }

    /**
     * Delete an acquire-clone workflow row (compensation after a failed
     * acquisition). The orchestrator refuses unless the row is tagged with
     * the given publication and belongs to the tenant.
     *
     * <p>{@code organizationId} MUST be the acquirer's org scope: the
     * orchestrator's canonical delete runs a strict-scope guard against the
     * row's organization_id (NOT NULL post-V263), and it reads the caller org
     * from the {@code X-Organization-ID} header of THIS request. Without the
     * header every delete is refused and the compensation silently leaves the
     * cloned rows behind.
     *
     * @return true when the row was deleted
     */
    public boolean deleteAcquiredWorkflow(UUID workflowId, UUID publicationId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/publication-support/workflows/" + workflowId
                + "/acquired?pubId=" + publicationId + "&tenantId=" + tenantId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
            return true;
        } catch (Exception e) {
            log.warn("Failed to delete acquired workflow {} for publication {}: {}",
                    workflowId, publicationId, e.getMessage());
            return false;
        }
    }

    /**
     * Delete a DECOUPLED editable-duplicate workflow row (compensation after a failed
     * {@code duplicateToEditableWorkflow} clone). The orchestrator refuses unless the
     * row belongs to the tenant AND is a plain {@code WORKFLOW} with
     * {@code source_publication_id = NULL} - so it can never delete a real acquired
     * APPLICATION through this path. The acquired-application compensation uses
     * {@link #deleteAcquiredWorkflow}, whose {@code pubId.equals(sourcePublicationId)}
     * guard would NPE on the duplicate's null source.
     *
     * @return true when the row was deleted
     */
    public boolean deleteDecoupledDuplicateWorkflow(UUID workflowId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/publication-support/workflows/" + workflowId
                + "/decoupled-duplicate?tenantId=" + tenantId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
            return true;
        } catch (Exception e) {
            log.warn("Failed to delete decoupled duplicate workflow {}: {}", workflowId, e.getMessage());
            return false;
        }
    }

    /**
     * Count workflows in an organization (all types) - the WORKFLOW-quota count the
     * orchestrator's {@code saveWorkflow} entitlement gate uses
     * ({@code countByOrganizationIdStrict}). The duplicate-to-editable path needs it to
     * bill WORKFLOW quota itself: the internal {@code create-application} endpoint runs
     * no entitlement check. Returns 0 on transport failure (the guard then under-counts,
     * never over-blocks an acquire).
     */
    public long countWorkflowsByOrg(String organizationId) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/api/internal/publication-support/workflows/count-by-org")
                .queryParam("organizationId", organizationId);
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null, organizationId));
        try {
            ResponseEntity<Long> response = restTemplate.exchange(
                    builder.toUriString(), HttpMethod.GET, entity, Long.class);
            return response.getBody() != null ? response.getBody() : 0L;
        } catch (Exception e) {
            log.warn("Failed to count workflows for org {}: {}", organizationId, e.getMessage());
            return 0L;
        }
    }

    // ========== File Operations ==========

    /**
     * Copy an S3 file (download + re-upload under new path).
     * Returns a map with "newPath" and (when the new storage row has one) "newId" -
     * the new storage-row UUID the cloned FileRef must adopt so the opaque by-id URL
     * resolves in the target tenant. "newId" is omitted when the upload produced an
     * id-less FileRef (caller then drops any stale source id). Returns null on failure.
     *
     * <p>{@code organizationId} MUST be the acquirer's org for an acquire-clone (so the new
     * storage row is org-scoped and the by-id URL resolves for the acquirer). Pass {@code null}
     * for a publish-time snapshot copy into the {@code _publications} namespace - those files are
     * served only by the anonymous marketplace showcase via HMAC-signed-by-path URLs (never by-id),
     * so they need no org-scoped row; the acquirer later re-copies them by path and mints a fresh
     * org-scoped id. It is forwarded as {@code X-Organization-ID}; the orchestrator propagates it to
     * the storage upload so the {@code storage.storage} index row is org-scoped. Without it the index
     * insert fails (null organizationId) → no row → no {@code newId} → the cloned FileRef stays
     * id-less and renders broken on the by-id (authenticated) path.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> copyFile(Map<String, Object> request, String organizationId) {
        String url = baseUrl + "/api/internal/publication-support/files/copy";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(null, organizationId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to copy file: {}", e.getMessage());
            return null;
        }
    }

    // ========== Category Operations ==========

    /**
     * Get a workflow category by ID.
     * Returns map with {id, slug, name, iconSlug, color} or null if not found.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCategoryById(UUID categoryId) {
        String url = baseUrl + "/api/internal/publication-support/categories/" + categoryId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.debug("Category not found: {}", categoryId);
            return null;
        }
    }

    // ========== Helpers ==========

    // ========== Workflow Existence Check ==========

    /**
     * Check which workflow IDs from a given set actually exist in orchestrator.
     * Used by PublicationCleanupService to detect orphaned publications.
     *
     * @param workflowIds set of workflow IDs to check
     * @return set of IDs that exist in orchestrator (subset of input)
     */
    public Set<UUID> getExistingWorkflowIds(Set<UUID> workflowIds) {
        if (workflowIds == null || workflowIds.isEmpty()) {
            return Set.of();
        }
        String ids = workflowIds.stream().map(UUID::toString).reduce((a, b) -> a + "," + b).orElse("");
        String url = baseUrl + "/api/internal/publication-support/workflows/exists?ids=" + ids;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            ResponseEntity<Set<UUID>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            Set<UUID> body = response.getBody();
            return body != null ? body : Set.of();
        } catch (Exception e) {
            log.error("Failed to check existing workflow IDs: {}", e.getMessage());
            return workflowIds; // fail-safe: assume all exist to avoid false deactivation
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
        if (hasText(organizationId)) {
            headers.set("X-Organization-ID", organizationId);
        }
        return headers;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

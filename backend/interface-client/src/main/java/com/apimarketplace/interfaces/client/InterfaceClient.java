package com.apimarketplace.interfaces.client;

import com.apimarketplace.common.recentactivity.RecentActivityScopeResultDto;
import com.apimarketplace.interfaces.client.dto.*;
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
 * HTTP client for communicating with interface-service.
 * Used by orchestrator-service for interface CRUD and snapshot operations.
 */
public class InterfaceClient {

    private static final Logger log = LoggerFactory.getLogger(InterfaceClient.class);

    private final RestTemplate restTemplate;
    // Dedicated bounded-timeout template used ONLY by
    // {@link #getRecentActivity} - keeps the existing call paths' open-ended
    // timeouts untouched while preventing the recent-activity branch from
    // parking an aggregator thread on a slow / hung interface-service
    // (auditor B v5 fix: bare new RestTemplate() has infinite read timeout).
    private final RestTemplate recentActivityRestTemplate;
    private final String baseUrl;

    public InterfaceClient(String interfaceServiceUrl) {
        this.restTemplate = new RestTemplate();
        this.recentActivityRestTemplate = createRecentActivityRestTemplate();
        this.baseUrl = interfaceServiceUrl;
    }

    public InterfaceClient(RestTemplate restTemplate, String interfaceServiceUrl) {
        this.restTemplate = restTemplate;
        this.recentActivityRestTemplate = createRecentActivityRestTemplate();
        this.baseUrl = interfaceServiceUrl;
    }

    /**
     * 2s connect / 3s read timeout - tight enough that one slow downstream
     * never blocks the aggregator's {@code recentActivityExecutor} thread
     * past the {@code CompletableFuture.orTimeout(3s)} budget the caller
     * wraps around it. Mirrors the
     * {@code AgentClient#executionRestTemplate} pattern (AgentClient.java:55-59)
     * but tighter (3s vs 65 min) - read path, not execution path.
     */
    private static RestTemplate createRecentActivityRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return new RestTemplate(factory);
    }

    // ========== Interface CRUD (Internal API) ==========

    /**
     * Get an interface by ID. Post-V261: resolves the caller's active workspace
     * from {@link com.apimarketplace.common.web.TenantResolver#currentRequestOrganizationId()}
     * so server-side strict isolation matches the row's organization_id.
     */
    public InterfaceDto getInterface(UUID id, String tenantId) {
        return getInterface(id, tenantId, com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Get an interface by ID in an explicit workspace scope.
     */
    public InterfaceDto getInterface(UUID id, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/interfaces/" + id;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<InterfaceDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, InterfaceDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get interface id={} org={}: {}", id, organizationId, e.getMessage());
            return null;
        }
    }

    /**
     * Fetch the interface template (HTML/CSS/JS only) for the render path.
     * Explicitly unscoped: render needs the template regardless of who owns it
     * - the run's organization_id is the scope, not the interface row's.
     * Reserved for internal callers in {@code InterfaceRenderService} +
     * landing-page render. NOT a public API surface.
     */
    public InterfaceDto getInterfaceTemplateForRender(UUID id) {
        String url = baseUrl + "/api/internal/interfaces/" + id + "/template";
        try {
            ResponseEntity<InterfaceDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), InterfaceDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to fetch render template for interface id={}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * List interfaces for a tenant.
     */
    public List<InterfaceDto> listInterfaces(String tenantId) {
        String url = baseUrl + "/api/internal/interfaces";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<InterfaceDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to list interfaces for tenant={}: {}", tenantId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * List interfaces with org-based access filtering.
     */
    public List<InterfaceDto> listInterfaces(String tenantId, String orgId, String orgRole) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/interfaces")
                .queryParamIfPresent("orgId", java.util.Optional.ofNullable(orgId))
                .queryParamIfPresent("orgRole", java.util.Optional.ofNullable(orgRole))
                .toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<InterfaceDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to list interfaces for tenant={}, org={}: {}", tenantId, orgId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * List interfaces filtered by type.
     */
    public List<InterfaceDto> listInterfacesByType(String tenantId, String interfaceType) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/interfaces")
                .queryParam("type", interfaceType)
                .toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<InterfaceDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to list interfaces by type={}: {}", interfaceType, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Create a new interface (backward-compat, relies on OrgContextHeaderForwarder for sync callers).
     */
    public InterfaceDto createInterface(InterfaceCreateRequest request, String tenantId) {
        return createInterface(request, tenantId, null);
    }

    /**
     * Create a new interface with explicit organization scope. Use this overload when the caller
     * runs on an async/daemon thread where RequestContextHolder is empty (e.g. publication acquire,
     * workflow cloning, agent-driven interface creation) so X-Organization-ID is threaded explicitly.
     */
    public InterfaceDto createInterface(InterfaceCreateRequest request, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/interfaces";
        HttpEntity<InterfaceCreateRequest> entity = new HttpEntity<>(request, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<InterfaceDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, InterfaceDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create interface org={}: {}", organizationId, e.getMessage());
            return null;
        }
    }

    /**
     * Create an interface from a publication snapshot (backward-compat, relies on
     * OrgContextHeaderForwarder for sync callers).
     */
    public InterfaceDto createFromSnapshot(Map<String, Object> snapshot, String tenantId) {
        return createFromSnapshot(snapshot, tenantId, null);
    }

    /**
     * Create an interface from a publication snapshot with explicit organization scope.
     * Used by publication-service when acquiring an INTERFACE publication. The acquire path
     * may run on an async thread where RequestContextHolder is empty, so organizationId
     * must be threaded explicitly to ensure the new interface is created in the correct workspace.
     */
    public InterfaceDto createFromSnapshot(Map<String, Object> snapshot, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/interfaces/from-snapshot";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(snapshot, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<InterfaceDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, InterfaceDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create interface from snapshot org={}: {}", organizationId, e.getMessage());
            return null;
        }
    }

    /**
     * Update an existing interface (backward-compat, relies on OrgContextHeaderForwarder
     * for sync callers).
     */
    public InterfaceDto updateInterface(UUID id, InterfaceUpdateRequest request, String tenantId) {
        return updateInterface(id, request, tenantId, null);
    }

    /**
     * Update an existing interface with explicit organization scope. Use this overload when
     * the caller runs on an async/daemon thread where RequestContextHolder is empty, so
     * X-Organization-ID is threaded explicitly rather than relying on OrgContextHeaderForwarder.
     */
    public InterfaceDto updateInterface(UUID id, InterfaceUpdateRequest request, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/interfaces/" + id;
        HttpEntity<InterfaceUpdateRequest> entity = new HttpEntity<>(request, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<InterfaceDto> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, InterfaceDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to update interface id={} org={}: {}", id, organizationId, e.getMessage());
            return null;
        }
    }

    /**
     * Delete an interface.
     */
    public boolean deleteInterface(UUID id, String tenantId) {
        return deleteInterface(id, tenantId, null);
    }

    /**
     * Delete an interface in an explicit organization scope.
     */
    public boolean deleteInterface(UUID id, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/interfaces/" + id;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
            return true;
        } catch (Exception e) {
            log.error("Failed to delete interface id={}: {}", id, e.getMessage());
            return false;
        }
    }

    // ========== Agent Browse Interfaces ==========

    /**
     * Create or update an agent-browse interface (groups by conversation/message/agent).
     *
     * <p>Replaces {@code createOrUpdateWebSearchInterface} (removed 2026-05-22).
     * Search/fetch tool calls no longer persist - their results render inline
     * as a {@code FaviconStack} on the chat tool-call row (commit f600c8885).
     * Only browser-agent action results (live CDP card) still flow through
     * persisted Interface entities, now written under
     * {@code interface_type='agent_browse'}.
     */
    public InterfaceDto createOrUpdateAgentBrowseInterface(AgentBrowseInterfaceRequest request, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/agent-browse";
        HttpEntity<AgentBrowseInterfaceRequest> entity = new HttpEntity<>(request, buildHeaders(tenantId));
        try {
            ResponseEntity<InterfaceDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, InterfaceDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create/update agent_browse interface: {}", e.getMessage());
            return null;
        }
    }

    // ========== Image Generation Interfaces ==========

    /**
     * Create or update an image-generation interface (groups by conversation/message/agent).
     *
     * <p>Mirrors {@link #createOrUpdateAgentBrowseInterface}. Persists the
     * generated image payload as an Interface entity so the chat side
     * panel can re-fetch it via {@code interfaceId} (rather than relying
     * on the inline base64 staying in conversation history forever).
     *
     * <p>Returns {@code null} on failure - the caller (the
     * {@code ToolResultPersistEnricher} pipeline) treats {@code null} as
     * "no enrichment, fall back to the original result" so the LLM still
     * receives the image inline.
     */
    public InterfaceDto createOrUpdateImageGenerationInterface(
            com.apimarketplace.interfaces.client.dto.ImageGenerationInterfaceRequest request,
            String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/image-generation";
        HttpEntity<com.apimarketplace.interfaces.client.dto.ImageGenerationInterfaceRequest> entity =
                new HttpEntity<>(request, buildHeaders(tenantId));
        try {
            ResponseEntity<InterfaceDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, InterfaceDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create/update image generation interface: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Update web search screenshot.
     */
    public boolean updateWebSearchScreenshot(UUID interfaceId, String resultUrl, String screenshotKey, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/" + interfaceId + "/web-search-screenshot";
        Map<String, String> body = Map.of("result_url", resultUrl, "screenshot_key", screenshotKey);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
            return true;
        } catch (Exception e) {
            log.error("Failed to update screenshot for interface={}: {}", interfaceId, e.getMessage());
            return false;
        }
    }

    // ========== Slide Interfaces ==========

    /**
     * Create a slide interface.
     */
    public InterfaceDto createSlideInterface(String name, Map<String, Object> slideData, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/slide";
        Map<String, Object> body = Map.of("name", name, "slide_data", slideData);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(tenantId));
        try {
            ResponseEntity<InterfaceDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, InterfaceDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create slide interface: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Update slide data.
     */
    public InterfaceDto updateSlideData(UUID interfaceId, Map<String, Object> slideData, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/" + interfaceId + "/slide-data";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(slideData, buildHeaders(tenantId));
        try {
            ResponseEntity<InterfaceDto> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, InterfaceDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to update slide data for interface={}: {}", interfaceId, e.getMessage());
            return null;
        }
    }

    // ========== Snapshots ==========

    /**
     * Create a snapshot for a workflow run.
     */
    public InterfaceSnapshotDto createSnapshot(SnapshotCreateRequest request, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/snapshots";
        HttpEntity<SnapshotCreateRequest> entity = new HttpEntity<>(request, buildHeaders(tenantId));
        try {
            ResponseEntity<InterfaceSnapshotDto> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, InterfaceSnapshotDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to create snapshot: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get snapshot for a specific interface and run.
     */
    public InterfaceSnapshotDto getSnapshot(UUID interfaceId, UUID workflowRunId, String tenantId) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/interfaces/snapshots/find")
                .queryParam("interfaceId", interfaceId)
                .queryParam("workflowRunId", workflowRunId)
                .toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<InterfaceSnapshotDto> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, InterfaceSnapshotDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.debug("No snapshot found for interface={}, run={}", interfaceId, workflowRunId);
            return null;
        }
    }

    /**
     * Get all snapshots for a workflow run.
     */
    public List<InterfaceSnapshotDto> getSnapshotsForRun(UUID workflowRunId, String tenantId) {
        return getSnapshotsForRun(workflowRunId, tenantId, null);
    }

    /**
     * Get all snapshots for a workflow run in an explicit organization scope.
     */
    public List<InterfaceSnapshotDto> getSnapshotsForRun(UUID workflowRunId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/interfaces/snapshots/by-run/" + workflowRunId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<List<InterfaceSnapshotDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get snapshots for run={}: {}", workflowRunId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Delete all snapshots for a workflow run.
     */
    public void deleteSnapshotsForRun(UUID workflowRunId, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/snapshots/by-run/" + workflowRunId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to delete snapshots for run={} (non-critical): {}", workflowRunId, e.getMessage());
        }
    }

    /**
     * Refresh all interface snapshots of a workflow run with the current live HTML/CSS/JS of
     * their source {@link com.apimarketplace.interfaces.client.dto.InterfaceDto}.
     *
     * <p>Called by orchestrator-service at trigger-fire time so long-running
     * {@code WAITING_TRIGGER} runs (form/webhook/schedule "apps") pick up agent-driven interface
     * iterations on the next epoch - mirrors the workflow-plan refresh idiom in
     * {@code WorkflowResumeService.refreshPlanFromWorkflowDefinition}.
     *
     * <p><b>Non-critical</b>: a failure here MUST NOT block the trigger pipeline. The previous
     * snapshot (or worst case the live entity) is good enough to render the iframe; a missed
     * refresh only means the user sees an older UI until the next fire.
     *
     * @param workflowRunId the run whose snapshots to refresh
     * @param tenantId tenant header, propagated for routing/auditing only - refresh logic is
     *     tenant-agnostic at the snapshot-row level (each snapshot already carries its own
     *     tenant)
     */
    public void refreshSnapshotsFromLive(UUID workflowRunId, String tenantId) {
        refreshSnapshotsFromLive(workflowRunId, tenantId, null);
    }

    /**
     * Org-aware variant. Threads {@code organizationId} explicitly across the async trigger-fire
     * boundary - the caller (orchestrator's {@code ReusableTriggerService}) runs without an
     * inbound RequestContextHolder, so {@link OrgContextHeaderForwarder#forward} would otherwise
     * resolve no header and the receiver would fall to the unscoped legacy path. Explicit
     * threading mirrors the PR20 pattern on {@code AgentObservabilityClient.recordAsync}.
     *
     * <p>With a non-null orgId, the receiving service only refreshes snapshots whose parent
     * interface belongs to that org - closing the cross-org-write gap on the mutating endpoint.
     */
    public void refreshSnapshotsFromLive(UUID workflowRunId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/interfaces/snapshots/refresh-from-live/" + workflowRunId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            // Tolerated: trigger fire continues with whatever snapshot is there.
            log.warn("Failed to refresh interface snapshots for run={} (non-critical, snapshots stay frozen): {}",
                    workflowRunId, e.getMessage());
        }
    }

    /**
     * Best-effort activity-touch: hits {@code POST /api/internal/interfaces/{id}/touch}
     * so the bell's Activity tab surfaces this interface after a user action fire.
     * Called fire-and-forget from orchestrator's action-persist paths. Failures are
     * tolerated (non-critical, cosmetic timestamp only) - the user's action
     * persistence is unaffected by an interface-service outage on this call.
     */
    public void touchUpdatedAt(UUID interfaceId) {
        String url = baseUrl + "/api/internal/interfaces/" + interfaceId + "/touch";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            // Tolerated: cosmetic freshness signal only. Bell Activity row stays
            // at its prior updated_at until the next successful touch.
            log.warn("Failed to touch interface {} updated_at (non-critical, activity may show stale timestamp): {}",
                    interfaceId, e.getMessage());
        }
    }

    // ========== Variable Extraction ==========

    /**
     * Extract template variables from templates.
     */
    public List<String> extractTemplateVariables(String htmlTemplate, String cssTemplate, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/extract-variables";
        Map<String, String> body = new java.util.HashMap<>();
        if (htmlTemplate != null) body.put("html_template", htmlTemplate);
        if (cssTemplate != null) body.put("css_template", cssTemplate);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, buildHeaders(tenantId));
        try {
            ResponseEntity<List<String>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to extract template variables: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Extract form fields from HTML template.
     */
    public List<String> extractFormFields(String htmlTemplate, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/extract-form-fields";
        Map<String, String> body = Map.of("html_template", htmlTemplate);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, buildHeaders(tenantId));
        try {
            ResponseEntity<List<String>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to extract form fields: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ========== Project Operations ==========

    /**
     * Assign an interface to a project.
     */
    public boolean assignToProject(UUID interfaceId, UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/" + interfaceId + "/project/" + projectId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
            return true;
        } catch (Exception e) {
            log.error("Failed to assign interface {} to project {}: {}", interfaceId, projectId, e.getMessage());
            return false;
        }
    }

    /**
     * Remove an interface from a project.
     */
    public boolean removeFromProject(UUID interfaceId, UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/" + interfaceId + "/project/" + projectId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
            return true;
        } catch (Exception e) {
            log.error("Failed to remove interface {} from project {}: {}", interfaceId, projectId, e.getMessage());
            return false;
        }
    }

    /**
     * Count interfaces for a project.
     */
    public long countByProject(UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/count-by-project/" + projectId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Long> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Long.class);
            return response.getBody() != null ? response.getBody() : 0L;
        } catch (Exception e) {
            log.warn("Failed to count interfaces for project={}: {}", projectId, e.getMessage());
            return 0L;
        }
    }

    /**
     * Get interfaces for a project.
     */
    public List<InterfaceDto> getByProject(UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/by-project/" + projectId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<InterfaceDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get interfaces for project={}: {}", projectId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Unassign all interfaces from a project.
     */
    public void unassignAllFromProject(UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/unassign-project/" + projectId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to unassign interfaces from project={} (non-critical): {}", projectId, e.getMessage());
        }
    }

    // ========== Bulk Operations ==========

    /**
     * Find interfaces by source workflow ID.
     */
    public List<InterfaceDto> findBySourceWorkflowId(UUID workflowId, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/by-source-workflow/" + workflowId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<InterfaceDto>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to find interfaces by source workflow={}: {}", workflowId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Delete interfaces by source workflow ID.
     */
    public void deleteBySourceWorkflowId(UUID workflowId, String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/by-source-workflow/" + workflowId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to delete interfaces by source workflow={}: {}", workflowId, e.getMessage());
        }
    }

    /**
     * Count interfaces for a tenant.
     */
    public long countByTenant(String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/count";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Long> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Long.class);
            return response.getBody() != null ? response.getBody() : 0L;
        } catch (Exception e) {
            log.warn("Failed to count interfaces for tenant={}: {}", tenantId, e.getMessage());
            return 0L;
        }
    }

    // ========== Batch Operations ==========

    /**
     * Get multiple interfaces by IDs (batch fetch).
     */
    public List<InterfaceDto> getInterfacesByIds(List<UUID> ids, String tenantId) {
        return getInterfacesByIds(ids, tenantId, null);
    }

    /**
     * Get multiple interfaces by IDs in an explicit organization scope.
     */
    public List<InterfaceDto> getInterfacesByIds(List<UUID> ids, String tenantId, String organizationId) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        String url = baseUrl + "/api/internal/interfaces/batch";
        HttpEntity<List<UUID>> entity = new HttpEntity<>(ids, buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<List<InterfaceDto>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to batch get interfaces: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ========== Recent Activity (fan-out branch for /api/activities/recent) ==========

    /**
     * Fetch the top-N most recently-edited interfaces in the caller's active
     * workspace, plus a peer-scope count for the empty-state hint. Used by
     * orchestrator's {@code RecentActivityAggregatorService} fan-out branch
     * for INTERFACE rows.
     *
     * <p>Uses the dedicated {@link #recentActivityRestTemplate} (2s connect /
     * 3s read) so a hung interface-service can't park the aggregator thread.
     * On any failure returns an empty result - the aggregator already wraps
     * the call in {@code CompletableFuture.orTimeout(3s).exceptionally(...)}
     * for graceful partial-degradation, this is the second line of defense.
     *
     * <p>{@code orgId} is passed EXPLICITLY (not via
     * {@code forwardOrgContextHeaders}'s ThreadLocal) because the aggregator
     * runs on a dedicated executor thread where the inbound
     * RequestContextHolder is not propagated.
     */
    public RecentActivityScopeResultDto getRecentActivity(String tenantId, String orgId) {
        if (tenantId == null || tenantId.isBlank()) {
            return new RecentActivityScopeResultDto(List.of(), 0);
        }
        String url = baseUrl + "/api/internal/interfaces/recent-activity";
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
            log.warn("Failed to fetch recent activity (interfaces) tenant={} org={}: {}",
                    tenantId, orgId, e.getMessage());
            return new RecentActivityScopeResultDto(List.of(), 0);
        }
    }

    // ========== Storage Usage ==========

    /**
     * Get storage usage for interfaces category.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getInterfaceStorageUsage(String tenantId) {
        String url = baseUrl + "/api/internal/interfaces/storage/usage";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);
            return response.getBody() != null ? response.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Failed to get interface storage usage for tenant {}: {}", tenantId, e.getMessage());
            return Collections.emptyMap();
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
        // PR16 - forward X-Organization-ID / X-Organization-Role from the
        // inbound request to keep workspace context across cross-service hops.
        OrgContextHeaderForwarder.forward(headers);
        return headers;
    }

}

package com.apimarketplace.publication.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import com.apimarketplace.auth.client.entitlement.LimitExceededError;
import com.apimarketplace.auth.client.entitlement.LimitExceededException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

/**
 * HTTP client for communicating with publication-service.
 * Used by orchestrator-service and other services for publication operations.
 */
public class PublicationClient {

    private static final Logger log = LoggerFactory.getLogger(PublicationClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public PublicationClient(String publicationServiceUrl) {
        this.restTemplate = new RestTemplate();
        this.baseUrl = publicationServiceUrl;
    }

    public PublicationClient(RestTemplate restTemplate, String publicationServiceUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = publicationServiceUrl;
    }

    // ========== Workflow-level queries ==========

    public void unpublishByWorkflowId(UUID workflowId, String tenantId) {
        unpublishByWorkflowId(workflowId, tenantId, null);
    }

    public void unpublishByWorkflowId(UUID workflowId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/publications/unpublish-by-workflow/" + workflowId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to unpublish workflow={}: {}", workflowId, e.getMessage());
        }
    }

    public Set<UUID> findPublishedWorkflowIds(Collection<UUID> workflowIds, String tenantId) {
        if (workflowIds == null || workflowIds.isEmpty()) return Collections.emptySet();
        String url = baseUrl + "/api/internal/publications/published-workflow-ids";
        List<String> idStrings = workflowIds.stream().map(UUID::toString).toList();
        Map<String, Object> body = Map.of("workflowIds", idStrings);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(tenantId));
        try {
            ResponseEntity<Set<UUID>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptySet();
        } catch (Exception e) {
            log.warn("Failed to find published workflow IDs: {}", e.getMessage());
            return Collections.emptySet();
        }
    }

    /**
     * Batch (workflowId → publicationId) lookup for ACTIVE publications.
     *
     * <p>Used by agent-facing tools so {@code workflow(action='list')} can
     * embed {@code application_id} directly in each summary, saving an extra
     * round-trip to {@code application(action='my')}.
     *
     * <p>Best-effort: a publication-service failure returns an empty map (the
     * caller still has the boolean signal from {@link #findPublishedWorkflowIds}
     * if it falls back to that).
     */
    public Map<UUID, UUID> findActivePublicationIdsByWorkflowIds(Collection<UUID> workflowIds, String tenantId) {
        if (workflowIds == null || workflowIds.isEmpty()) return Collections.emptyMap();
        String url = baseUrl + "/api/internal/publications/publication-id-map";
        List<String> idStrings = workflowIds.stream().map(UUID::toString).toList();
        Map<String, Object> body = Map.of("workflowIds", idStrings);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(tenantId));
        try {
            ResponseEntity<Map<String, String>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            Map<String, String> raw = response.getBody();
            if (raw == null || raw.isEmpty()) return Collections.emptyMap();
            Map<UUID, UUID> out = new HashMap<>();
            for (Map.Entry<String, String> e : raw.entrySet()) {
                try {
                    out.put(UUID.fromString(e.getKey()), UUID.fromString(e.getValue()));
                } catch (IllegalArgumentException ignored) { /* skip malformed */ }
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to find publication-id map: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Batch (workflowId → publication status) lookup for still-shared
     * publications (ACTIVE / PENDING_REVIEW / REJECTED). Drives the workflow
     * list's moderation badge: a workflow in review shows "shared · in review"
     * instead of nothing. Best-effort - a publication-service failure returns
     * an empty map (the list then renders no shared marker rather than failing).
     */
    public Map<UUID, String> findPublicationStatusesByWorkflowIds(Collection<UUID> workflowIds, String tenantId) {
        if (workflowIds == null || workflowIds.isEmpty()) return Collections.emptyMap();
        String url = baseUrl + "/api/internal/publications/workflow-publication-statuses";
        List<String> idStrings = workflowIds.stream().map(UUID::toString).toList();
        Map<String, Object> body = Map.of("workflowIds", idStrings);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(tenantId));
        try {
            ResponseEntity<Map<String, String>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            Map<String, String> raw = response.getBody();
            if (raw == null || raw.isEmpty()) return Collections.emptyMap();
            Map<UUID, String> out = new HashMap<>();
            for (Map.Entry<String, String> e : raw.entrySet()) {
                try {
                    out.put(UUID.fromString(e.getKey()), e.getValue());
                } catch (IllegalArgumentException ignored) { /* skip malformed */ }
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to find publication statuses: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Batch (workflowId → marketplace visibility: PUBLIC / PRIVATE / UNLISTED) lookup for the
     * caller's still-shared publications (INACTIVE excluded). Drives the public / private indicator
     * + visibility filter on the workflow AND applications boards, mirroring {@code /app/applications}
     * (which reads {@code WorkflowPublication.visibility}). Best-effort - a publication-service
     * failure returns an empty map (the boards then show no visibility marker rather than failing).
     */
    public Map<UUID, String> findPublicationVisibilitiesByWorkflowIds(Collection<UUID> workflowIds, String tenantId) {
        if (workflowIds == null || workflowIds.isEmpty()) return Collections.emptyMap();
        String url = baseUrl + "/api/internal/publications/publication-visibilities-by-workflow-ids";
        List<String> idStrings = workflowIds.stream().map(UUID::toString).toList();
        Map<String, Object> body = Map.of("workflowIds", idStrings);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(tenantId));
        try {
            ResponseEntity<Map<String, String>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            Map<String, String> raw = response.getBody();
            if (raw == null || raw.isEmpty()) return Collections.emptyMap();
            Map<UUID, String> out = new HashMap<>();
            for (Map.Entry<String, String> e : raw.entrySet()) {
                try {
                    out.put(UUID.fromString(e.getKey()), e.getValue());
                } catch (IllegalArgumentException ignored) { /* skip malformed */ }
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to find publication visibilities: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Batch (publicationId → status) lookup keyed by the publication's OWN id, for ALL statuses
     * INCLUDING INACTIVE. The applications board uses this to EXCLUDE its raw APPLICATION-type
     * rows whose {@code sourcePublicationId} resolves to an INACTIVE (unpublished) publication,
     * matching {@code /app/applications}. Best-effort: a publication-service failure returns an
     * empty map, so the board falls open (shows everything) rather than breaking.
     */
    public Map<UUID, String> findStatusesByPublicationIds(Collection<UUID> publicationIds, String tenantId) {
        if (publicationIds == null || publicationIds.isEmpty()) return Collections.emptyMap();
        String url = baseUrl + "/api/internal/publications/publication-statuses-by-ids";
        List<String> idStrings = publicationIds.stream().map(UUID::toString).toList();
        Map<String, Object> body = Map.of("publicationIds", idStrings);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(tenantId));
        try {
            ResponseEntity<Map<String, String>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            Map<String, String> raw = response.getBody();
            if (raw == null || raw.isEmpty()) return Collections.emptyMap();
            Map<UUID, String> out = new HashMap<>();
            for (Map.Entry<String, String> e : raw.entrySet()) {
                try {
                    out.put(UUID.fromString(e.getKey()), e.getValue());
                } catch (IllegalArgumentException ignored) { /* skip malformed */ }
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to find publication statuses by ids: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * A publisher's own application publication for one of their workflows:
     * the publication id (so the board card can open {@code /app/applications/{id}}),
     * its moderation status (ACTIVE / PENDING_REVIEW / REJECTED), and the showcase
     * interface + run ids so the board can render the app preview via the authenticated
     * per-run path (valid at any visibility - the run is the caller's own), exactly like
     * {@code /app/applications}. {@code showcaseInterfaceId}/{@code showcaseRunId} may be
     * null when the publication has no captured showcase run.
     */
    public record ApplicationPublicationRef(UUID publicationId, String status,
                                            UUID showcaseInterfaceId, String showcaseRunId) {}

    /**
     * Batch (workflowId → {@link ApplicationPublicationRef}) lookup for the caller's OWN
     * <em>application</em> publications (WORKFLOW + showcase interface, non-INACTIVE) - the same
     * predicate {@code /app/applications} uses. Lets the applications board show a publisher's
     * self-published apps alongside acquired ones. Workflows with no application publication are
     * absent from the map. Best-effort: a publication-service failure returns an empty map, so the
     * board simply shows the acquired apps (fail-open) rather than breaking.
     */
    public Map<UUID, ApplicationPublicationRef> findApplicationPublicationsByWorkflowIds(
            Collection<UUID> workflowIds, String tenantId) {
        if (workflowIds == null || workflowIds.isEmpty()) return Collections.emptyMap();
        String url = baseUrl + "/api/internal/publications/application-publications-by-workflow-ids";
        List<String> idStrings = workflowIds.stream().map(UUID::toString).toList();
        Map<String, Object> body = Map.of("workflowIds", idStrings);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(tenantId));
        try {
            ResponseEntity<Map<String, Map<String, String>>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            Map<String, Map<String, String>> raw = response.getBody();
            if (raw == null || raw.isEmpty()) return Collections.emptyMap();
            Map<UUID, ApplicationPublicationRef> out = new HashMap<>();
            for (Map.Entry<String, Map<String, String>> e : raw.entrySet()) {
                Map<String, String> v = e.getValue();
                if (v == null) continue;
                try {
                    UUID wfId = UUID.fromString(e.getKey());
                    UUID pubId = UUID.fromString(v.get("publicationId"));
                    String ifaceIdRaw = v.get("showcaseInterfaceId");
                    UUID showcaseInterfaceId = ifaceIdRaw != null ? UUID.fromString(ifaceIdRaw) : null;
                    out.put(wfId, new ApplicationPublicationRef(pubId, v.get("status"),
                            showcaseInterfaceId, v.get("showcaseRunId")));
                } catch (IllegalArgumentException | NullPointerException ignored) { /* skip malformed */ }
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to find application publications by workflow ids: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    public boolean isWorkflowPublished(UUID workflowId, String tenantId) {
        String url = baseUrl + "/api/internal/publications/is-published/" + workflowId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Boolean> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Boolean.class);
            return Boolean.TRUE.equals(response.getBody());
        } catch (Exception e) {
            log.warn("Failed to check if workflow={} is published: {}", workflowId, e.getMessage());
            return false;
        }
    }

    // ========== Project operations ==========

    public void setProjectId(UUID publicationId, UUID projectId) {
        String url = baseUrl + "/api/internal/publications/" + publicationId + "/project/" + projectId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to set project for publication={}: {}", publicationId, e.getMessage());
        }
    }

    public void clearProjectId(UUID publicationId) {
        String url = baseUrl + "/api/internal/publications/" + publicationId + "/project";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to clear project for publication={}: {}", publicationId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> findByProjectId(UUID projectId) {
        return findByProjectId(projectId, null);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> findByProjectId(UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/publications/by-project/" + projectId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to find publications for project={}: {}", projectId, e.getMessage());
            return Collections.emptyList();
        }
    }

    public long countByProjectId(UUID projectId) {
        return countByProjectId(projectId, null);
    }

    public long countByProjectId(UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/publications/count-by-project/" + projectId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Long> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Long.class);
            return response.getBody() != null ? response.getBody() : 0L;
        } catch (Exception e) {
            log.warn("Failed to count publications for project={}: {}", projectId, e.getMessage());
            return 0L;
        }
    }

    public boolean assignToProject(UUID publicationId, UUID projectId, String userId) {
        String url = baseUrl + "/api/internal/publications/" + publicationId + "/assign-to-project";
        Map<String, String> body = Map.of("projectId", projectId.toString(), "userId", userId);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, buildHeaders(userId));
        try {
            ResponseEntity<Boolean> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, Boolean.class);
            return Boolean.TRUE.equals(response.getBody());
        } catch (Exception e) {
            log.warn("Failed to assign publication={} to project={}: {}", publicationId, projectId, e.getMessage());
            return false;
        }
    }

    public void removeFromProject(UUID publicationId, UUID projectId) {
        removeFromProject(publicationId, projectId, null);
    }

    public void removeFromProject(UUID publicationId, UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/publications/" + publicationId + "/remove-from-project/" + projectId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to remove publication={} from project={}: {}", publicationId, projectId, e.getMessage());
        }
    }

    public void unassignAllFromProject(UUID projectId) {
        unassignAllFromProject(projectId, null);
    }

    public void unassignAllFromProject(UUID projectId, String tenantId) {
        String url = baseUrl + "/api/internal/publications/unassign-all-from-project/" + projectId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to unassign publications from project={}: {}", projectId, e.getMessage());
        }
    }

    // ========== Marketplace operations ==========

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMarketplacePublications(int page, int size) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/publications/marketplace")
                .queryParam("page", page)
                .queryParam("size", size)
                .toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Failed to get marketplace publications: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> searchMarketplace(String query, int page, int size) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/publications/marketplace/search")
                .queryParam("q", query)
                .queryParam("page", page)
                .queryParam("size", size)
                .toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Failed to search marketplace publications: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMarketplaceByCategorySlug(String slug, int page, int size) {
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/api/internal/publications/marketplace/by-category")
                .queryParam("slug", slug)
                .queryParam("page", page)
                .queryParam("size", size)
                .toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Failed to get marketplace by category: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ========== Publication CRUD ==========

    @SuppressWarnings("unchecked")
    public Map<String, Object> getPublicationById(UUID publicationId) {
        String url = baseUrl + "/api/internal/publications/" + publicationId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.warn("Failed to get publication={}: {}", publicationId, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getPublicationsByPublisher(String tenantId) {
        String url = baseUrl + "/api/internal/publications/by-publisher/" + tenantId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.warn("Failed to get publications for publisher={}: {}", tenantId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Org-aware overload - sets {@code X-Organization-ID} explicitly so the
     * caller's active workspace travels with the acquire request even when
     * there is no inbound {@link org.springframework.web.context.request.RequestContextHolder}
     * context (background daemons, MCP tool paths).
     */
    public Map<String, Object> acquirePublication(UUID publicationId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/publications/" + publicationId + "/acquire";
        HttpHeaders headers = buildHeaders(tenantId);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        return doAcquireRequest(url, entity, publicationId);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> acquirePublication(UUID publicationId, String tenantId) {
        String url = baseUrl + "/api/internal/publications/" + publicationId + "/acquire";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        return doAcquireRequest(url, entity, publicationId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doAcquireRequest(String url, HttpEntity<Void> entity, UUID publicationId) {
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody() != null ? response.getBody() : Collections.emptyMap();
        } catch (HttpClientErrorException.Conflict e) {
            LimitExceededException lex = parseLimitExceeded(e);
            if (lex != null) throw lex;
            log.error("Failed to acquire publication={}: {}", publicationId, e.getMessage());
            throw new RuntimeException("Failed to acquire publication: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to acquire publication={}: {}", publicationId, e.getMessage());
            throw new RuntimeException("Failed to acquire publication: " + e.getMessage(), e);
        }
    }

    private static final ObjectMapper LIMIT_MAPPER = new ObjectMapper();

    private LimitExceededException parseLimitExceeded(HttpClientErrorException e) {
        try {
            LimitExceededError err = LIMIT_MAPPER.readValue(e.getResponseBodyAsByteArray(), LimitExceededError.class);
            if (LimitExceededError.ERROR_CODE.equals(err.error())) {
                return new LimitExceededException(err);
            }
        } catch (Exception ignored) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> publishWorkflow(Map<String, Object> request, String tenantId) {
        return publishWorkflow(request, tenantId, null);
    }

    /**
     * Org-aware overload - explicit {@code X-Organization-ID} so the publish
     * carries the caller's active workspace even from background daemons /
     * MCP tool paths.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> publishWorkflow(Map<String, Object> request, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/publications/publish";
        HttpHeaders headers = buildHeaders(tenantId);
        OrgContextHeaderForwarder.setIfPresent(headers, organizationId);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to publish workflow: {}", e.getMessage());
            throw new RuntimeException("Failed to publish workflow: " + e.getMessage(), e);
        }
    }

    // ========== Shared Links ==========

    @SuppressWarnings("unchecked")
    public Map<String, Object> registerSharedLink(String tenantId, String resourceType,
                                                   String resourceToken, UUID resourceId,
                                                   String title, String description) {
        String url = baseUrl + "/api/internal/shared-links/register";
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId);
        body.put("resourceType", resourceType);
        body.put("resourceToken", resourceToken);
        if (resourceId != null) body.put("resourceId", resourceId.toString());
        if (title != null) body.put("title", title);
        if (description != null) body.put("description", description);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(tenantId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.warn("Failed to register shared link for resourceToken={}: {}", resourceToken, e.getMessage());
            return null;
        }
    }

    public void unregisterSharedLink(String resourceToken) {
        String url = baseUrl + "/api/internal/shared-links/unregister";
        Map<String, String> body = Map.of("resourceToken", resourceToken);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, buildHeaders(null));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to unregister shared link for resourceToken={}: {}", resourceToken, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveSharedLinkByToken(String token) {
        String url = baseUrl + "/api/internal/shared-links/by-token/" + token;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.warn("Failed to resolve shared link for token={}: {}", token, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getSharedLinkByResourceToken(String resourceToken) {
        String url = baseUrl + "/api/internal/shared-links/by-resource/" + resourceToken;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.warn("Failed to get shared link for resourceToken={}: {}", resourceToken, e.getMessage());
            return null;
        }
    }

    // ========== Storage Usage ==========

    /**
     * Get storage usage for publications category.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPublicationStorageUsage(String tenantId) {
        String url = baseUrl + "/api/internal/publications/storage/usage";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);
            return response.getBody() != null ? response.getBody() : Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Failed to get publication storage usage for tenant {}: {}", tenantId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ========== Agent publication operations ==========

    public Map<String, Object> publishAgent(Map<String, Object> request, String tenantId) {
        return publishAgent(request, tenantId, null);
    }

    /**
     * Org-aware overload - see {@link #publishResource(Map, String, String)}
     * for the rationale. Stamps {@code X-Organization-ID} so publication-service
     * records {@code owner_type='ORG'} when the publish is issued from an org
     * workspace via an MCP tool.
     */
    public Map<String, Object> publishAgent(Map<String, Object> request, String tenantId, String orgId) {
        String url = baseUrl + "/api/internal/publications/publish-agent";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId, orgId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to publish agent: {}", e.getMessage());
            throw new RuntimeException("Failed to publish agent: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> acquireAgentPublication(UUID publicationId, String tenantId) {
        return acquireAgentPublication(publicationId, tenantId, null);
    }

    public Map<String, Object> acquireAgentPublication(UUID publicationId, String tenantId, String orgId) {
        String url = baseUrl + "/api/internal/publications/" + publicationId + "/acquire-agent";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, orgId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to acquire agent publication {}: {}", publicationId, e.getMessage());
            throw new RuntimeException("Failed to acquire agent publication: " + e.getMessage(), e);
        }
    }

    /**
     * Looks up whether an agent has an active publication. The downstream
     * controller (`/api/internal/publications/is-agent-published/{id}`) is
     * scoped by {@code agentConfigId} only (no org/tenant filter - a given
     * agent config can only be published once across the platform), so this
     * method intentionally has no org-aware overload. The previous 3-arg
     * variant was dropped 2026-05-21 audit after confirming the controller
     * ignores X-Organization-ID.
     */
    public boolean isAgentPublished(UUID agentConfigId, String tenantId) {
        String url = baseUrl + "/api/internal/publications/is-agent-published/" + agentConfigId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId));
        try {
            ResponseEntity<Boolean> response = restTemplate.exchange(url, HttpMethod.GET, entity, Boolean.class);
            return Boolean.TRUE.equals(response.getBody());
        } catch (Exception e) {
            log.warn("Failed to check agent publication status for {}: {}", agentConfigId, e.getMessage());
            return false;
        }
    }

    public void unpublishByAgentConfigId(UUID agentConfigId, String tenantId) {
        unpublishByAgentConfigId(agentConfigId, tenantId, null);
    }

    /**
     * Org-aware overload. Unlike {@link #isAgentPublished(UUID, String)} (which
     * is global by agentConfigId), the unpublish endpoint downstream IS
     * org-scoped: {@code InternalPublicationController.unpublishByAgentConfigId}
     * reads {@code X-Organization-ID} and refuses to delete rows owned by a
     * different workspace. Thread {@code orgId} from {@code ToolExecutionContext}
     * so a teammate's unpublish lands on the right workspace's row.
     */
    public void unpublishByAgentConfigId(UUID agentConfigId, String tenantId, String orgId) {
        String url = baseUrl + "/api/internal/publications/unpublish-agent/" + agentConfigId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, orgId));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to unpublish agent {}: {}", agentConfigId, e.getMessage());
        }
    }

    public Map<String, Object> getAgentMarketplace(int page, int size) {
        String url = UriComponentsBuilder.fromUriString(baseUrl + "/api/internal/publications/marketplace/agents")
                .queryParam("page", page)
                .queryParam("size", size)
                .toUriString();
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(buildHeaders(null)), new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.warn("Failed to get agent marketplace: {}", e.getMessage());
            return Map.of("content", List.of(), "totalElements", 0);
        }
    }

    // ========== Generic resource (TABLE / INTERFACE / SKILL) publication operations ==========

    public Map<String, Object> publishResource(Map<String, Object> request, String tenantId) {
        return publishResource(request, tenantId, null);
    }

    /**
     * Org-aware overload. Forwards {@code orgId} as {@code X-Organization-ID}
     * so publication-service stamps {@code owner_type='ORG'} when the publish
     * is issued from an org workspace. Used by MCP tool callers
     * (InterfacePublishModule, TablePublishModule, ...) that resolve org from
     * the {@code ToolExecutionContext} rather than the inbound HTTP request.
     */
    public Map<String, Object> publishResource(Map<String, Object> request, String tenantId, String orgId) {
        String url = baseUrl + "/api/internal/publications/publish-resource";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, buildHeaders(tenantId, orgId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to publish resource: {}", e.getMessage());
            throw new RuntimeException("Failed to publish resource: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> acquireResource(UUID publicationId, String tenantId) {
        return acquireResource(publicationId, tenantId, null);
    }

    public Map<String, Object> acquireResource(UUID publicationId, String tenantId, String organizationId) {
        String url = baseUrl + "/api/internal/publications/" + publicationId + "/acquire-resource";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, organizationId));
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to acquire resource publication {}: {}", publicationId, e.getMessage());
            throw new RuntimeException("Failed to acquire resource publication: " + e.getMessage(), e);
        }
    }

    public void unpublishResource(String type, String resourceId, String tenantId) {
        unpublishResource(type, resourceId, tenantId, null);
    }

    /**
     * Org-aware overload. Mirrors {@link #publishResource(Map, String, String)}
     * so org-scoped resources (owner_type='ORG') are visible to the
     * unpublish lookup. Without this, MCP {@code skill(action='unpublish')}
     * from a teammate's workspace silently no-ops. Audit 2026-05-21.
     */
    public void unpublishResource(String type, String resourceId, String tenantId, String orgId) {
        String url = baseUrl + "/api/internal/publications/unpublish-resource/" + type + "/" + resourceId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(tenantId, orgId));
        try {
            restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
        } catch (Exception e) {
            log.warn("Failed to unpublish {} resource {}: {}", type, resourceId, e.getMessage());
        }
    }

    public boolean isResourcePublished(String type, String resourceId) {
        String url = baseUrl + "/api/internal/publications/is-resource-published/" + type + "/" + resourceId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders(null));
        try {
            ResponseEntity<Boolean> response = restTemplate.exchange(url, HttpMethod.GET, entity, Boolean.class);
            return Boolean.TRUE.equals(response.getBody());
        } catch (Exception e) {
            log.warn("Failed to check {} resource {} publication status: {}", type, resourceId, e.getMessage());
            return false;
        }
    }

    /**
     * The shared/in-review/rejected state of one standalone resource publication: its moderation
     * {@code status} (ACTIVE / PENDING_REVIEW / REJECTED) and the {@code rejectionReason} (non-null
     * only for REJECTED). {@code published} is derivable as {@code "ACTIVE".equals(status)}.
     */
    public record ResourcePublicationStatusRef(String status, String rejectionReason) {
        public boolean published() { return "ACTIVE".equals(status); }
    }

    /**
     * Batch (resourceId → {@link ResourcePublicationStatusRef}) lookup for non-workflow resources
     * (TABLE / INTERFACE / SKILL / AGENT) of one {@code type}, non-INACTIVE. The owning service
     * (datasource / interface / skill / agent) calls this ONCE per list page to stamp the publication
     * badge onto each row, replacing the per-row {@link #isResourcePublished} fan-out - the
     * resource-library sibling of {@link #findPublicationStatusesByWorkflowIds}. The {@code resourceIds}
     * are the row ids: for AGENT these are agentConfigIds (publication-service matches them against the
     * agentConfigId column, since AGENT rows carry no string resource_id). Resources with no shared
     * publication are absent from the map. Best-effort: a publication-service failure returns an empty
     * map (the list then renders no shared marker rather than failing).
     */
    public Map<String, ResourcePublicationStatusRef> findResourcePublicationStatuses(
            String type, Collection<String> resourceIds, String tenantId) {
        if (resourceIds == null || resourceIds.isEmpty()) return Collections.emptyMap();
        String url = baseUrl + "/api/internal/publications/resource-publication-statuses";
        Map<String, Object> body = Map.of("type", type, "resourceIds", new ArrayList<>(resourceIds));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, buildHeaders(tenantId));
        try {
            ResponseEntity<Map<String, Map<String, String>>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            Map<String, Map<String, String>> raw = response.getBody();
            if (raw == null || raw.isEmpty()) return Collections.emptyMap();
            Map<String, ResourcePublicationStatusRef> out = new HashMap<>();
            for (Map.Entry<String, Map<String, String>> e : raw.entrySet()) {
                Map<String, String> v = e.getValue();
                if (v == null || v.get("status") == null) continue;
                out.put(e.getKey(), new ResourcePublicationStatusRef(v.get("status"), v.get("rejectionReason")));
            }
            return out;
        } catch (Exception e) {
            log.warn("Failed to find {} resource publication statuses: {}", type, e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ========== Helpers ==========

    private HttpHeaders buildHeaders(String tenantId) {
        return buildHeaders(tenantId, null);
    }

    /**
     * Explicit-org variant. Sets {@code X-Organization-ID} from the caller-
     * supplied {@code orgId} (typically resolved from a
     * {@code ToolExecutionContext}) and falls back to the inbound servlet
     * request's headers for any field the caller leaves null.
     */
    private HttpHeaders buildHeaders(String tenantId, String orgId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        if (tenantId != null) {
            headers.set("X-User-ID", tenantId);
        }
        OrgContextHeaderForwarder.setIfPresent(headers, orgId);
        // PR16 - forward X-Organization-ID / X-Organization-Role from the
        // inbound request when caller did not pre-set them, keeping workspace
        // context across cross-service hops.
        OrgContextHeaderForwarder.forward(headers);
        return headers;
    }

}

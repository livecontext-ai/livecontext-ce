package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.PublicationPendingReviewException;
import com.apimarketplace.publication.service.RemoteMarketplaceService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseSnapshotBackfillService;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Internal API for orchestrator-service to access publication data.
 * These endpoints are called by PublicationClient from other services.
 */
@RestController
@RequestMapping("/api/internal/publications")
public class InternalPublicationController {

    private static final Logger log = LoggerFactory.getLogger(InternalPublicationController.class);

    private final WorkflowPublicationRepository publicationRepository;
    private final WorkflowPublicationService publicationService;
    private final AgentPublicationService agentPublicationService;
    private final ResourcePublicationService resourcePublicationService;
    private final OrchestratorInternalClient orchestratorClient;
    private final ShowcaseSnapshotBackfillService backfillService;
    /**
     * Present ONLY in a CE monolith ({@code marketplace.mode=remote}; bean created by the
     * gated {@code RemoteMarketplaceConfig}). When present, the marketplace LIST/SEARCH
     * reads below proxy to the cloud - the same source the browser sees via
     * {@code /api/publications/remote/*} - so the agent's {@code application(action='search')}
     * tool discovers the SAME cloud marketplace as the human, instead of this install's
     * (near-empty) LOCAL publications. Absent in cloud → local reads (= the marketplace).
     */
    private final ObjectProvider<RemoteMarketplaceService> remoteMarketplaceProvider;

    public InternalPublicationController(WorkflowPublicationRepository publicationRepository,
                                          WorkflowPublicationService publicationService,
                                          AgentPublicationService agentPublicationService,
                                          ResourcePublicationService resourcePublicationService,
                                          OrchestratorInternalClient orchestratorClient,
                                          ShowcaseSnapshotBackfillService backfillService,
                                          ObjectProvider<RemoteMarketplaceService> remoteMarketplaceProvider) {
        this.publicationRepository = publicationRepository;
        this.publicationService = publicationService;
        this.agentPublicationService = agentPublicationService;
        this.resourcePublicationService = resourcePublicationService;
        this.orchestratorClient = orchestratorClient;
        this.backfillService = backfillService;
        this.remoteMarketplaceProvider = remoteMarketplaceProvider;
    }

    /**
     * Backfill {@code showcase_snapshot} JSONB on every legacy publication
     * that still references a {@code showcase_*} clone-row. Idempotent.
     * Triggered manually after deployment of V160.
     */
    @PostMapping("/backfill-showcase-snapshot")
    public ResponseEntity<List<Map<String, Object>>> backfillShowcaseSnapshot() {
        return ResponseEntity.ok(backfillService.backfillAll());
    }

    // ========== Workflow-level queries ==========

    @PostMapping("/unpublish-by-workflow/{workflowId}")
    @Transactional
    public ResponseEntity<Void> unpublishByWorkflowId(@PathVariable UUID workflowId) {
        publicationRepository.findByWorkflowId(workflowId).ifPresent(pub -> {
            pub.setStatus(WorkflowPublicationEntity.PublicationStatus.INACTIVE);
            publicationRepository.save(pub);
            log.info("Unpublished workflow {} via internal API", workflowId);
        });
        return ResponseEntity.ok().build();
    }

    @PostMapping("/published-workflow-ids")
    public ResponseEntity<Set<UUID>> findPublishedWorkflowIds(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("workflowIds");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(Collections.emptySet());
        }
        List<UUID> workflowIds = ids.stream().map(UUID::fromString).toList();
        List<UUID> publishedIds = publicationRepository.findPublishedWorkflowIds(workflowIds);
        return ResponseEntity.ok(new HashSet<>(publishedIds));
    }

    /**
     * Batch map of (workflowId → publicationId) for ACTIVE publications.
     *
     * <p>Used by agent-facing tools to embed the publication id directly in
     * {@code workflow(action='list')} responses so the agent can hop to
     * {@code application(action='get'/'execute')} without an extra round-trip.
     */
    @PostMapping("/publication-id-map")
    public ResponseEntity<Map<String, String>> findActivePublicationIdsByWorkflowIds(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("workflowIds");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }
        List<UUID> workflowIds = ids.stream().map(UUID::fromString).toList();
        List<Object[]> rows = publicationRepository.findActivePublicationIdsByWorkflowIds(workflowIds);
        Map<String, String> out = new HashMap<>();
        for (Object[] row : rows) {
            if (row.length >= 2 && row[0] instanceof UUID wfId && row[1] instanceof UUID pubId) {
                out.put(wfId.toString(), pubId.toString());
            }
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/is-published/{workflowId}")
    public ResponseEntity<Boolean> isWorkflowPublished(@PathVariable UUID workflowId) {
        boolean published = publicationRepository.existsByWorkflowId(workflowId);
        return ResponseEntity.ok(published);
    }

    /**
     * Batch map of (workflowId → publication status) for every still-shared
     * publication (ACTIVE / PENDING_REVIEW / REJECTED; INACTIVE excluded).
     *
     * <p>Lets the orchestrator workflow list render the moderation state
     * (a "shared · in review" badge for PENDING_REVIEW, "rejected" for
     * REJECTED) rather than just the ACTIVE-only boolean from
     * {@link #findPublishedWorkflowIds}.
     */
    @PostMapping("/workflow-publication-statuses")
    public ResponseEntity<Map<String, String>> findPublicationStatuses(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("workflowIds");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }
        List<UUID> workflowIds = ids.stream().map(UUID::fromString).toList();
        List<Object[]> rows = publicationRepository.findPublicationStatusesByWorkflowIds(workflowIds);
        Map<String, String> out = new HashMap<>();
        for (Object[] row : rows) {
            if (row.length >= 2 && row[0] instanceof UUID wfId && row[1] != null) {
                out.put(wfId.toString(), row[1].toString());
            }
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Batch map of (workflowId → marketplace visibility: PUBLIC / PRIVATE / UNLISTED) for the
     * caller's still-shared publications (INACTIVE excluded). Lets the orchestrator workflow AND
     * applications boards mark each own card with a public / private indicator and offer a
     * visibility filter, exactly like {@code /app/applications}. Workflows with no shared
     * publication are simply absent from the response map.
     */
    @PostMapping("/publication-visibilities-by-workflow-ids")
    public ResponseEntity<Map<String, String>> findPublicationVisibilities(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("workflowIds");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }
        List<UUID> workflowIds = ids.stream().map(UUID::fromString).toList();
        List<Object[]> rows = publicationRepository.findPublicationVisibilitiesByWorkflowIds(workflowIds);
        Map<String, String> out = new HashMap<>();
        for (Object[] row : rows) {
            if (row.length >= 2 && row[0] instanceof UUID wfId && row[1] != null) {
                out.put(wfId.toString(), row[1].toString());
            }
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Batch map of (publicationId → status) keyed by the publication's OWN id, for ALL
     * statuses INCLUDING INACTIVE. Lets the applications board (orchestrator) detect which of
     * its raw APPLICATION-type rows resolve to an INACTIVE publication so it can EXCLUDE those
     * leftover instances - aligning the board with {@code /app/applications}, which already
     * filters INACTIVE out. Unknown ids are simply absent from the response map.
     */
    @PostMapping("/publication-statuses-by-ids")
    public ResponseEntity<Map<String, String>> findStatusesByPublicationIds(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("publicationIds");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }
        List<UUID> publicationIds = ids.stream().map(UUID::fromString).toList();
        List<Object[]> rows = publicationRepository.findStatusesByPublicationIds(publicationIds);
        Map<String, String> out = new HashMap<>();
        for (Object[] row : rows) {
            if (row.length >= 2 && row[0] instanceof UUID pubId && row[1] != null) {
                out.put(pubId.toString(), row[1].toString());
            }
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Batch map of (workflowId → {publicationId, status}) for the caller's OWN
     * <em>application</em> publications (WORKFLOW + showcase interface, non-INACTIVE) - the same
     * predicate {@code /app/applications} uses. Lets the orchestrator applications board show a
     * publisher's self-published apps alongside the acquired ones; without it those apps were
     * absent from the Applications board and only appeared as plain workflow cards. Source
     * workflows are WORKFLOW-type (no {@code sourcePublicationId} of their own), so this batch
     * lookup is the only way the board learns their publication id. Workflows with no application
     * publication are simply absent from the response.
     */
    @PostMapping("/application-publications-by-workflow-ids")
    public ResponseEntity<Map<String, Map<String, String>>> findApplicationPublicationsByWorkflowIds(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.get("workflowIds");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }
        List<UUID> workflowIds = ids.stream().map(UUID::fromString).toList();
        List<Object[]> rows = publicationRepository.findApplicationPublicationsByWorkflowIds(workflowIds);
        Map<String, Map<String, String>> out = new HashMap<>();
        for (Object[] row : rows) {
            if (row.length >= 3 && row[0] instanceof UUID wfId && row[1] instanceof UUID pubId && row[2] != null) {
                Map<String, String> ref = new HashMap<>();
                ref.put("publicationId", pubId.toString());
                ref.put("status", row[2].toString());
                // Showcase ids (own published-as-app rows) let the applications board render via the
                // authenticated per-run path. Absent when the publication has no captured showcase run.
                if (row.length >= 5) {
                    if (row[3] != null) ref.put("showcaseInterfaceId", row[3].toString());
                    if (row[4] != null) ref.put("showcaseRunId", row[4].toString());
                }
                out.put(wfId.toString(), ref);
            }
        }
        return ResponseEntity.ok(out);
    }

    // ========== Agent publication operations ==========

    @PostMapping("/publish-agent")
    public ResponseEntity<?> publishAgent(
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestBody Map<String, Object> request) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        try {
            WorkflowPublicationEntity pub = agentPublicationService.publishAgent(request, tenantId, organizationId);
            return ResponseEntity.ok(Map.of("id", pub.getId().toString(), "title", pub.getTitle()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (PublicationPendingReviewException e) {
            // Pending-review guard (re-publish blocked) - 409, matching the
            // public controller and the PublisherProfileUnavailableException
            // contract. Without this branch the ISE escaped as a Spring 500.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to publish agent: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{publicationId}/acquire-agent")
    public ResponseEntity<?> acquireAgent(
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID publicationId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        try {
            Map<String, Object> result = agentPublicationService.acquireAgentPublication(publicationId, tenantId, organizationId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/is-agent-published/{agentConfigId}")
    public ResponseEntity<Boolean> isAgentPublished(@PathVariable UUID agentConfigId) {
        return ResponseEntity.ok(agentPublicationService.isAgentPublished(agentConfigId));
    }

    @PostMapping("/unpublish-agent/{agentConfigId}")
    @Transactional
    public ResponseEntity<Void> unpublishByAgentConfigId(
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID agentConfigId) {
        try {
            agentPublicationService.unpublishAgent(agentConfigId, tenantId, organizationId);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to unpublish agent {}: {}", agentConfigId, e.getMessage());
        } catch (PublicationPendingReviewException e) {
            // Pending-review guard - 409, matching the public controller and the
            // PublisherProfileUnavailableException contract (was a 500).
            log.warn("Unpublish agent {} rejected (conflict): {}", agentConfigId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (Exception e) {
            log.error("Unexpected error unpublishing agent {}", agentConfigId, e);
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok().build();
    }

    // ========== Standalone resource operations (TABLE / INTERFACE / SKILL) ==========

    @PostMapping("/publish-resource")
    public ResponseEntity<?> publishResource(
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestBody Map<String, Object> request) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        try {
            WorkflowPublicationEntity pub = resourcePublicationService.publishResource(request, tenantId, organizationId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", pub.getId().toString());
            result.put("title", pub.getTitle());
            result.put("status", pub.getStatus() != null ? pub.getStatus().name() : null);
            result.put("type", pub.getPublicationType() != null ? pub.getPublicationType().name() : null);
            result.put("resourceId", pub.getResourceId());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (PublicationPendingReviewException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to publish resource: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{publicationId}/acquire-resource")
    public ResponseEntity<?> acquireResource(
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID publicationId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        try {
            Map<String, Object> result = resourcePublicationService.acquireResource(publicationId, tenantId, organizationId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to acquire resource publication {}: {}", publicationId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/unpublish-resource/{type}/{resourceId}")
    @Transactional
    public ResponseEntity<?> unpublishResource(
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable String type,
            @PathVariable String resourceId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required"));
        }
        try {
            WorkflowPublicationEntity.PublicationType parsed =
                    WorkflowPublicationEntity.PublicationType.valueOf(type.toUpperCase());
            resourcePublicationService.unpublishResource(parsed, resourceId, tenantId, organizationId);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (PublicationPendingReviewException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/is-resource-published/{type}/{resourceId}")
    public ResponseEntity<Boolean> isResourcePublished(
            @PathVariable String type,
            @PathVariable String resourceId) {
        try {
            WorkflowPublicationEntity.PublicationType parsed =
                    WorkflowPublicationEntity.PublicationType.valueOf(type.toUpperCase());
            return ResponseEntity.ok(resourcePublicationService.isResourcePublished(parsed, resourceId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(false);
        }
    }

    @GetMapping("/resource-publication-status/{type}/{resourceId}")
    public ResponseEntity<Map<String, Object>> getResourcePublicationStatus(
            @PathVariable String type,
            @PathVariable String resourceId) {
        try {
            WorkflowPublicationEntity.PublicationType parsed =
                    WorkflowPublicationEntity.PublicationType.valueOf(type.toUpperCase());
            return ResponseEntity.ok(resourcePublicationService.getResourcePublicationStatus(parsed, resourceId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Batch map of (resourceId → {status, rejectionReason?}) for non-workflow resource publications
     * (TABLE / INTERFACE / SKILL / AGENT) of one {@code type}, non-INACTIVE. Lets an owning service
     * (datasource / interface / skill / agent) enrich a list page's publication badge in ONE call
     * instead of one {@code is-resource-published/{type}/{id}} per row - the resource-library
     * equivalent of {@link #findPublicationStatuses}. Body: {@code {type, resourceIds[]}}. For AGENT
     * the ids are agentConfigIds (matched against the agentConfigId column, since AGENT rows carry no
     * string resource_id). Unknown / unpublished ids are absent from the response map. An invalid
     * {@code type} yields an empty map (fail-open, like the other best-effort batch endpoints) rather
     * than a 400, so a caller's list never breaks on it.
     */
    @PostMapping("/resource-publication-statuses")
    public ResponseEntity<Map<String, Map<String, String>>> findResourcePublicationStatuses(
            @RequestBody Map<String, Object> body) {
        Object typeRaw = body.get("type");
        @SuppressWarnings("unchecked")
        List<String> resourceIds = (List<String>) body.get("resourceIds");
        if (typeRaw == null || resourceIds == null || resourceIds.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }
        WorkflowPublicationEntity.PublicationType parsed;
        try {
            parsed = WorkflowPublicationEntity.PublicationType.valueOf(typeRaw.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Map.of());
        }
        return ResponseEntity.ok(resourcePublicationService.getResourcePublicationStatuses(parsed, resourceIds));
    }

    // ========== Project operations ==========

    @PutMapping("/{publicationId}/project/{projectId}")
    @Transactional
    public ResponseEntity<Void> setProjectId(
            @PathVariable UUID publicationId,
            @PathVariable UUID projectId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        publicationRepository.findById(publicationId)
                .filter(pub -> matchesProjectWorkspace(pub, userId, organizationId))
                .ifPresent(pub -> {
            pub.setProjectId(projectId);
            publicationRepository.save(pub);
        });
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{publicationId}/project")
    @Transactional
    public ResponseEntity<Void> clearProjectId(
            @PathVariable UUID publicationId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        publicationRepository.findById(publicationId)
                .filter(pub -> matchesProjectWorkspace(pub, userId, organizationId))
                .ifPresent(pub -> {
            pub.setProjectId(null);
            publicationRepository.save(pub);
        });
        return ResponseEntity.ok().build();
    }

    @GetMapping("/by-project/{projectId}")
    public ResponseEntity<List<Map<String, Object>>> findByProjectId(
            @PathVariable UUID projectId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        List<WorkflowPublicationEntity> pubs = findPublicationsByProject(projectId, userId, organizationId);
        // These are the caller's OWN project apps (owner-scoped above), so - unlike the public
        // marketplace maps - also surface visibility + status so the project's Applications tab
        // matches /app/applications (correct public/private icon + pending/rejected badge). Kept
        // OUT of toSummaryMap so public marketplace listings don't leak a publisher's review state.
        List<Map<String, Object>> result = pubs.stream().map(pub -> {
            Map<String, Object> map = toSummaryMap(pub);
            map.put("visibility", pub.getVisibility() != null ? pub.getVisibility().name() : null);
            map.put("status", pub.getStatus() != null ? pub.getStatus().name() : null);
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/count-by-project/{projectId}")
    public ResponseEntity<Long> countByProjectId(
            @PathVariable UUID projectId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        long count = countPublicationsByProject(projectId, userId, organizationId);
        return ResponseEntity.ok(count);
    }

    @PostMapping("/{publicationId}/assign-to-project")
    @Transactional
    public ResponseEntity<Boolean> assignToProject(
            @PathVariable UUID publicationId,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-ID", required = false) String headerUserId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        UUID projectId = UUID.fromString(body.get("projectId"));
        String userId = firstNonBlank(headerUserId, body.get("userId"));
        boolean assigned = publicationRepository.findById(publicationId)
                .filter(p -> matchesProjectWorkspace(p, userId, organizationId))
                .map(p -> { p.setProjectId(projectId); publicationRepository.save(p); return true; })
                .orElse(false);
        return ResponseEntity.ok(assigned);
    }

    @PostMapping("/{publicationId}/remove-from-project/{projectId}")
    @Transactional
    public ResponseEntity<Void> removeFromProject(
            @PathVariable UUID publicationId,
            @PathVariable UUID projectId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        publicationRepository.findById(publicationId)
                .filter(p -> matchesProjectWorkspace(p, userId, organizationId))
                .filter(p -> projectId.equals(p.getProjectId()))
                .ifPresent(p -> { p.setProjectId(null); publicationRepository.save(p); });
        return ResponseEntity.ok().build();
    }

    @PostMapping("/unassign-all-from-project/{projectId}")
    @Transactional
    public ResponseEntity<Void> unassignAllFromProject(
            @PathVariable UUID projectId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        findPublicationsByProject(projectId, userId, organizationId).forEach(p -> {
            p.setProjectId(null);
            publicationRepository.save(p);
        });
        return ResponseEntity.ok().build();
    }

    // ========== Marketplace queries ==========

    @GetMapping("/marketplace")
    public ResponseEntity<Map<String, Object>> getMarketplacePublications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        RemoteMarketplaceService remote = remoteMarketplaceProvider.getIfAvailable();
        if (remote != null) {
            return ResponseEntity.ok(remoteToInternalPage(remote.fetchMarketplacePublications(page, size, null), page, size));
        }
        Page<WorkflowPublicationEntity> results = publicationRepository.findMarketplacePublications(
                PageRequest.of(page, size));
        return ResponseEntity.ok(toPageMap(results));
    }

    @GetMapping("/marketplace/search")
    public ResponseEntity<Map<String, Object>> searchMarketplace(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        RemoteMarketplaceService remote = remoteMarketplaceProvider.getIfAvailable();
        if (remote != null) {
            // The cloud search endpoint is NOT paginated (returns the FULL match list);
            // remoteToInternalPage slices it to one page so the agent's paginateProjection
            // (which rejects a slice larger than the requested limit) never throws.
            return ResponseEntity.ok(remoteToInternalPage(remote.searchMarketplacePublications(q, null), page, size));
        }
        // PR5 - compute the OR-joined verbatim token list ("compactQuery") server-side
        // so the public HTTP contract (single `q` param) is unchanged. The repo's
        // 3-branch FTS query uses both `q` (for English-stemmed plainto_tsquery + pg_trgm
        // similarity) and `compactQuery` (for verbatim to_tsquery with `|` joins).
        String compactQuery = buildCompactTsQuery(q);
        Page<WorkflowPublicationEntity> results = publicationRepository.searchMarketplace(
                q, compactQuery, PageRequest.of(page, size));
        return ResponseEntity.ok(toPageMap(results));
    }

    /**
     * Build a {@code to_tsquery('simple', …)} input from a free-form user query.
     *
     * <p>Strips tsquery operators ({@code @ & | ! ( ) :} and any other
     * non-alphanumeric chars) from each token via {@code [^\p{L}\p{N}]+}, lowercases,
     * drops empties, and OR-joins the remaining tokens with {@code " | "}.
     * Returns {@code null} when no usable tokens remain so the repo can guard the
     * branch ({@code :compactQuery IS NOT NULL}). Closes the
     * {@code to_tsquery('simple', 'c++')} → "syntax error in tsquery" footgun
     * flagged by PR5 audit v0.2.
     *
     * <p>Examples (unit-tested):
     * <ul>
     *   <li>{@code "flights to thailand"}  → {@code "flights | to | thailand"}</li>
     *   <li>{@code "c++ tutorial"}         → {@code "c | tutorial"}</li>
     *   <li>{@code "@!#$"}                 → {@code null} (no usable tokens)</li>
     *   <li>{@code ""}                     → {@code null}</li>
     * </ul>
     */
    static String buildCompactTsQuery(String q) {
        if (q == null || q.isBlank()) return null;
        String compact = java.util.Arrays.stream(q.trim().split("\\s+"))
                .map(t -> t.replaceAll("[^\\p{L}\\p{N}]+", ""))
                .map(String::toLowerCase)
                .filter(t -> !t.isEmpty())
                .collect(java.util.stream.Collectors.joining(" | "));
        return compact.isEmpty() ? null : compact;
    }

    @GetMapping("/marketplace/by-category")
    public ResponseEntity<Map<String, Object>> getByCategory(
            @RequestParam String slug,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        RemoteMarketplaceService remote = remoteMarketplaceProvider.getIfAvailable();
        if (remote != null) {
            return ResponseEntity.ok(remoteToInternalPage(remote.fetchMarketplacePublications(page, size, slug), page, size));
        }
        Page<WorkflowPublicationEntity> results = publicationRepository.findMarketplacePublicationsByCategorySlug(
                slug, PageRequest.of(page, size));
        return ResponseEntity.ok(toPageMap(results));
    }

    /**
     * Adapt a cloud marketplace payload (proxied by {@link RemoteMarketplaceService}:
     * {@code {publications, count, totalPages?}}) to THIS internal endpoint's stable contract
     * ({@code {content, totalElements, totalPages, page, size}} - see {@link #toPageMap}). Keeping
     * the contract identical means the agent's {@code application(action='search')} parser (reads
     * {@code content} + {@code totalElements}) is unchanged whether the source is local or cloud.
     *
     * <p>The cloud LIST/by-category endpoint is server-paginated (≤{@code size} items already), but
     * the cloud SEARCH endpoint is NOT - it returns the FULL match set in one shot. So slice to the
     * requested page ONLY when the payload exceeds one page: an already-paged list passes through
     * untouched, while an unpaginated search (or list) is paged here. This is mandatory, not
     * cosmetic: the agent's {@link com.apimarketplace.agent.tools.common.AgentListEnvelope#paginateProjection}
     * THROWS when handed a slice larger than the requested limit. {@code totalElements} stays the
     * full count so paging / hasMore stays correct. Fail-soft: null / missing keys → empty page.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> remoteToInternalPage(Map<String, Object> remote, int page, int size) {
        Object publications = remote == null ? null : remote.get("publications");
        List<Object> items = publications instanceof List<?> list ? (List<Object>) list : List.of();
        long count = remote != null && remote.get("count") instanceof Number n ? n.longValue() : items.size();
        int safeSize = Math.max(size, 1);
        List<Object> content = items;
        if (items.size() > safeSize) {
            int from = (int) Math.min((long) Math.max(page, 0) * safeSize, items.size());
            int to = Math.min(from + safeSize, items.size());
            content = new ArrayList<>(items.subList(from, to));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("totalElements", count);
        result.put("totalPages", (int) Math.ceil((double) count / safeSize));
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    // ========== Publication CRUD ==========

    @GetMapping("/{publicationId}")
    public ResponseEntity<Map<String, Object>> getPublicationById(@PathVariable UUID publicationId) {
        Optional<WorkflowPublicationEntity> pubOpt = publicationRepository.findById(publicationId);
        if (pubOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDetailMap(pubOpt.get()));
    }

    @GetMapping("/by-publisher/{tenantId}")
    public ResponseEntity<List<Map<String, Object>>> getByPublisher(
            @PathVariable String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        // V223 - when an active org is in scope, return the org workspace's
        // publications (visible to all teammates). Falls back to personal scope
        // when no org header is present, preserving back-compat for daemon /
        // background callers that don't set workspace context.
        List<WorkflowPublicationEntity> pubs;
        if (organizationId != null && !organizationId.isBlank()) {
            pubs = publicationService.getPublicationsForScope(tenantId, organizationId);
        } else {
            pubs = publicationService.getPublicationsByPublisher(tenantId);
        }
        List<Map<String, Object>> result = pubs.stream().map(pub -> {
            Map<String, Object> map = toSummaryMap(pub);
            map.put("status", pub.getStatus().name());
            map.put("visibility", pub.getVisibility().name());
            return map;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{publicationId}/acquire")
    public ResponseEntity<?> acquirePublication(
            @PathVariable UUID publicationId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            Map<String, Object> result = publicationService.acquirePublication(publicationId, tenantId, organizationId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to acquire publication {}: {}", publicationId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Extract the optional V273 showcase epoch from an internal publish request
     * Map. The agent's {@code application(action='create')} forwards it under
     * {@code showcaseEpoch}; absent or non-numeric → {@code null} (no pin, render
     * defaults to the latest epoch). Package-private + static so it is unit-
     * testable without standing up the controller - same idiom as
     * {@link #buildCompactTsQuery(String)}.
     */
    static Integer parseShowcaseEpoch(Map<String, Object> request) {
        return request != null && request.get("showcaseEpoch") instanceof Number n
                ? n.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/publish")
    public ResponseEntity<?> publishWorkflow(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            UUID workflowId = UUID.fromString((String) request.get("workflowId"));
            String title = (String) request.get("title");
            String description = (String) request.get("description");
            UUID showcaseInterfaceId = request.get("showcaseInterfaceId") != null
                    ? UUID.fromString((String) request.get("showcaseInterfaceId")) : null;
            String showcaseRunId = (String) request.get("showcaseRunId");
            UUID categoryId = request.get("categoryId") != null
                    ? UUID.fromString((String) request.get("categoryId")) : null;
            int creditsPerUse = request.get("creditsPerUse") != null
                    ? ((Number) request.get("creditsPerUse")).intValue() : 0;
            // Publisher identity (name/email/avatar) is snapshotted server-side
            // inside publishWorkflow via AuthClient.getPublisherProfile(tenantId).
            // Any caller-supplied publisher fields in the request body are
            // ignored - passing nulls makes that explicit at the call site.
            WorkflowPublicationEntity.PublicationVisibility visibility =
                    request.get("visibility") != null
                            ? WorkflowPublicationEntity.PublicationVisibility.valueOf((String) request.get("visibility"))
                            : WorkflowPublicationEntity.PublicationVisibility.PUBLIC;
            Integer planVersion = request.get("planVersion") != null
                    ? ((Number) request.get("planVersion")).intValue() : null;
            WorkflowPublicationEntity.DisplayMode displayMode =
                    request.get("displayMode") != null
                            ? WorkflowPublicationEntity.DisplayMode.valueOf((String) request.get("displayMode"))
                            : WorkflowPublicationEntity.DisplayMode.WORKFLOW;

            // V273 - internal callers (the agent's application(action='create')
            // path) now forward the chosen epoch exactly like the public-API
            // wizard does, so server-to-server publishes go through the SAME
            // WorkflowPublicationService.publishWorkflow pipeline with no epoch
            // divergence. null falls through to the legacy multi-epoch view
            // (render still defaults to the latest epoch), a value pins it.
            Integer showcaseEpoch = parseShowcaseEpoch(request);
            // V274 - viaScreeningWizard=false: server-to-server publishes never
            // go through the wizard's modal, so the auto-screening path in
            // captureAndStoreShowcaseSnapshot writes SKIPPED audit rows for
            // every flagged media URL.
            WorkflowPublicationEntity pub = publicationService.publishWorkflow(
                    workflowId, tenantId, organizationId, title, description,
                    showcaseInterfaceId, showcaseRunId,
                    categoryId, creditsPerUse,
                    visibility, planVersion, displayMode, showcaseEpoch, false, Map.of());

            Map<String, Object> result = toDetailMap(pub);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (PublicationPendingReviewException e) {
            // Pending-review guard (re-publish blocked) - 409, matching the
            // public controller. Was an unmapped 500 via the generic catch.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Failed to publish workflow: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ========== Mapping helpers ==========

    private Map<String, Object> toSummaryMap(WorkflowPublicationEntity pub) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", pub.getId().toString());
        map.put("workflowId", pub.getWorkflowId() != null ? pub.getWorkflowId().toString() : null);
        map.put("title", pub.getTitle());
        map.put("description", pub.getDescription());
        map.put("displayMode", pub.getDisplayMode() != null ? pub.getDisplayMode().name() : null);
        // publisherId is the human user who clicked publish - the avatar endpoint
        // (/api/proxy/users/{userId}/avatar) is keyed by it. Without it the frontend
        // PublisherAvatar falls back to name-initials (e.g. "LI"), so it MUST ride along.
        map.put("publisherId", pub.getPublisherId());
        map.put("publisherName", pub.getPublisherName());
        map.put("publisherAvatarUrl", pub.getPublisherAvatarUrl());
        map.put("planVersion", pub.getPlanVersion());
        map.put("useCount", pub.getUseCount());
        if (pub.getCategoryId() != null) {
            Map<String, Object> categoryData = orchestratorClient.getCategoryById(pub.getCategoryId());
            if (categoryData != null) {
                map.put("category", categoryData.get("slug"));
            }
        }
        if (pub.getNodeIcons() != null && !pub.getNodeIcons().isEmpty()) {
            map.put("nodeIcons", pub.getNodeIcons());
        }
        if (pub.getInterfaceCount() != null && pub.getInterfaceCount() > 0) {
            map.put("interfaceCount", pub.getInterfaceCount());
        }
        if (pub.getDatasourceCount() != null && pub.getDatasourceCount() > 0) {
            map.put("datasourceCount", pub.getDatasourceCount());
        }
        if (pub.getShowcaseInterfaceId() != null) {
            map.put("showcaseInterfaceId", pub.getShowcaseInterfaceId().toString());
        }
        if (pub.getShowcaseRunId() != null && !pub.getShowcaseRunId().isEmpty()) {
            map.put("showcaseRunId", pub.getShowcaseRunId());
        }
        return map;
    }

    private Map<String, Object> toDetailMap(WorkflowPublicationEntity pub) {
        Map<String, Object> map = toSummaryMap(pub);
        map.put("status", pub.getStatus() != null ? pub.getStatus().name() : null);
        map.put("visibility", pub.getVisibility() != null ? pub.getVisibility().name() : null);
        map.put("creditsPerUse", pub.getCreditsPerUse());
        map.put("publisherAvatarUrl", pub.getPublisherAvatarUrl());
        map.put("publishedAt", pub.getPublishedAt() != null ? pub.getPublishedAt().toString() : null);
        map.put("updatedAt", pub.getUpdatedAt() != null ? pub.getUpdatedAt().toString() : null);
        map.put("hasShowcase", pub.hasShowcase());
        return map;
    }

    private Map<String, Object> toPageMap(Page<WorkflowPublicationEntity> page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", page.getContent().stream().map(this::toSummaryMap).toList());
        result.put("totalElements", page.getTotalElements());
        result.put("totalPages", page.getTotalPages());
        result.put("page", page.getNumber());
        result.put("size", page.getSize());
        return result;
    }

    private List<WorkflowPublicationEntity> findPublicationsByProject(UUID projectId, String userId, String organizationId) {
        if (hasOrg(organizationId)) {
            return publicationRepository.findByProjectIdAndOwnerTypeAndOwnerId(
                    projectId, WorkflowPublicationEntity.OwnerType.ORG, organizationId);
        }
        if (hasText(userId)) {
            return publicationRepository.findByProjectIdAndOwnerTypeAndOwnerId(
                    projectId, WorkflowPublicationEntity.OwnerType.USER, userId);
        }
        return publicationRepository.findByProjectId(projectId);
    }

    private long countPublicationsByProject(UUID projectId, String userId, String organizationId) {
        if (hasOrg(organizationId)) {
            return publicationRepository.countByProjectIdAndOwnerTypeAndOwnerId(
                    projectId, WorkflowPublicationEntity.OwnerType.ORG, organizationId);
        }
        if (hasText(userId)) {
            return publicationRepository.countByProjectIdAndOwnerTypeAndOwnerId(
                    projectId, WorkflowPublicationEntity.OwnerType.USER, userId);
        }
        return publicationRepository.countByProjectId(projectId);
    }

    private boolean matchesProjectWorkspace(WorkflowPublicationEntity publication, String userId, String organizationId) {
        if (hasOrg(organizationId)) {
            return publication.getOwnerType() == WorkflowPublicationEntity.OwnerType.ORG
                    && organizationId.equals(publication.getOwnerId());
        }
        if (hasText(userId)) {
            return publication.getOwnerType() == WorkflowPublicationEntity.OwnerType.USER
                    && userId.equals(publication.getOwnerId());
        }
        return true;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasOrg(String organizationId) {
        return hasText(organizationId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}

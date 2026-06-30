package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.dto.PublicationListItem;
import com.apimarketplace.publication.domain.PublicationReviewEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.domain.PublicationSnapshotVersionEntity;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.LandingInterfaceSnapshotter;
import com.apimarketplace.publication.service.OnboardingCategoryMapper;
import com.apimarketplace.publication.service.PublicationListQueryService;
import com.apimarketplace.publication.service.PublicationPendingReviewException;
import com.apimarketplace.publication.service.PublicationReviewService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseFileRefRewriter;
import com.apimarketplace.publication.service.ShowcaseSnapshotReader;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.apimarketplace.publication.utils.PlanSnapshotSanitizer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * REST Controller for workflow publications.
 *
 * <p>List endpoints (marketplace, search, popular, my, purchases, acquired) use
 * {@link PublicationListQueryService} which selects all columns EXCEPT plan_snapshot
 * via native SQL. This avoids loading 200KB-2MB JSONB blobs into JVM heap for every
 * publication in a list query.</p>
 *
 * <p>Detail/write endpoints continue to use {@link WorkflowPublicationService}
 * which loads the full entity including planSnapshot.</p>
 */
@RestController
@RequestMapping("/api/publications")
public class WorkflowPublicationController {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowPublicationController.class);
    private static final String HEADER_SHARE_CONTEXT = "X-Share-Context";
    private static final String HEADER_SHARE_RESOURCE_TYPE = "X-Share-Resource-Type";
    private static final String HEADER_SHARE_RESOURCE_TOKEN = "X-Share-Resource-Token";
    private static final String SHARE_RESOURCE_APPLICATION = "APPLICATION";

    private final WorkflowPublicationService publicationService;
    private final AgentPublicationService agentPublicationService;
    private final PublicationListQueryService listQueryService;
    private final PublicationReviewService reviewService;
    private final ResourcePublicationService resourcePublicationService;
    private final OrchestratorInternalClient orchestratorClient;
    private final LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    private final ShowcaseSnapshotReader showcaseSnapshotReader;
    private final ShowcaseFileRefRewriter fileRefRewriter;
    private final OnboardingCategoryMapper onboardingCategoryMapper;
    private final OrgAccessGuard orgAccessGuard;

    public WorkflowPublicationController(WorkflowPublicationService publicationService,
                                          AgentPublicationService agentPublicationService,
                                          PublicationListQueryService listQueryService,
                                          PublicationReviewService reviewService,
                                          ResourcePublicationService resourcePublicationService,
                                          OrchestratorInternalClient orchestratorClient,
                                          LandingInterfaceSnapshotter landingInterfaceSnapshotter,
                                          ShowcaseSnapshotReader showcaseSnapshotReader,
                                          ShowcaseFileRefRewriter fileRefRewriter,
                                          OnboardingCategoryMapper onboardingCategoryMapper,
                                          OrgAccessGuard orgAccessGuard) {
        this.publicationService = publicationService;
        this.agentPublicationService = agentPublicationService;
        this.listQueryService = listQueryService;
        this.reviewService = reviewService;
        this.resourcePublicationService = resourcePublicationService;
        this.orchestratorClient = orchestratorClient;
        this.landingInterfaceSnapshotter = landingInterfaceSnapshotter;
        this.showcaseSnapshotReader = showcaseSnapshotReader;
        this.fileRefRewriter = fileRefRewriter;
        this.onboardingCategoryMapper = onboardingCategoryMapper;
        this.orgAccessGuard = orgAccessGuard;
    }

    // ========================================================================
    // Write endpoints (full entity)
    // ========================================================================

    /**
     * Publish a workflow (create or update).
     */
    @PostMapping
    public ResponseEntity<?> publishWorkflow(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @RequestBody PublishWorkflowRequest request) {
        try {
            if (isViewerRole(organizationRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "VIEWER role cannot modify publications"));
            }
            logger.info("Publishing workflow: {} for tenant: {} (org={})",
                    request.workflowId, tenantId,
                    organizationId != null && !organizationId.isBlank() ? organizationId : "personal");

            UUID workflowId = UUID.fromString(request.workflowId);

            // Re-publishing is a create-or-update mutation of the existing application, so a member
            // who is READ-restricted (or DENY'd) on it must not be able to overwrite it. No-op on
            // first publish (no existing publication row) and in personal scope.
            ResponseEntity<?> writeDenied = denyIfApplicationWriteRestrictedByWorkflow(
                    tenantId, organizationId, organizationRole, workflowId);
            if (writeDenied != null) {
                return writeDenied;
            }

            UUID showcaseInterfaceId = request.showcaseInterfaceId != null && !request.showcaseInterfaceId.isEmpty()
                    ? UUID.fromString(request.showcaseInterfaceId)
                    : null;

            String showcaseRunId = request.showcaseRunId != null && !request.showcaseRunId.isEmpty()
                    ? request.showcaseRunId
                    : null;

            UUID categoryId = request.categoryId != null && !request.categoryId.isEmpty()
                    ? UUID.fromString(request.categoryId)
                    : null;

            PublicationVisibility visibility = request.visibility != null
                    ? PublicationVisibility.valueOf(request.visibility.toUpperCase())
                    : PublicationVisibility.PUBLIC;

            DisplayMode displayMode = request.displayMode != null
                    ? DisplayMode.valueOf(request.displayMode.toUpperCase())
                    : null;

            WorkflowPublicationEntity publication = publicationService.publishWorkflow(
                    workflowId,
                    tenantId,
                    organizationId,
                    request.title,
                    request.description,
                    showcaseInterfaceId,
                    showcaseRunId,
                    categoryId,
                    request.creditsPerUse,
                    visibility,
                    request.planVersion,
                    displayMode,
                    request.showcaseEpoch,
                    Boolean.TRUE.equals(request.viaScreeningWizard),
                    toReplacementMap(request.imageReplacements)
            );

            return ResponseEntity.ok(toDetailResponse(publication));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request publishing workflow: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (PublicationPendingReviewException e) {
            // Pending-review guard (re-publish blocked). A client-state conflict,
            // not a server fault - return 409 so the frontend fails fast (5xx is
            // retried) and matches the publish/unpublish-resource convention.
            logger.warn("Publish rejected (conflict): {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error publishing workflow", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to publish workflow"));
        }
    }

    /**
     * Update publication info. Re-snapshots the plan from the backing source
     * workflow on every update (see WorkflowPublicationService.updatePublicationInfo),
     * so a publisher's "Publish update" reflects their latest source edits.
     */
    @PutMapping("/{publicationId}")
    public ResponseEntity<?> updatePublication(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @PathVariable String publicationId,
            @RequestBody UpdatePublicationRequest request) {
        try {
            if (isViewerRole(organizationRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "VIEWER role cannot modify publications"));
            }
            logger.info("Updating publication: {} for tenant: {} (org: {})",
                    publicationId, tenantId, organizationId);

            UUID pubId = UUID.fromString(publicationId);

            ResponseEntity<?> writeDenied = denyIfApplicationWriteRestricted(
                    tenantId, organizationId, organizationRole, pubId.toString());
            if (writeDenied != null) {
                return writeDenied;
            }

            UUID showcaseInterfaceId = request.showcaseInterfaceId != null && !request.showcaseInterfaceId.isEmpty()
                    ? UUID.fromString(request.showcaseInterfaceId)
                    : null;

            String showcaseRunId = request.showcaseRunId != null && !request.showcaseRunId.isEmpty()
                    ? request.showcaseRunId
                    : null;

            UUID categoryId = request.categoryId != null && !request.categoryId.isEmpty()
                    ? UUID.fromString(request.categoryId)
                    : null;

            PublicationVisibility visibility = request.visibility != null
                    ? PublicationVisibility.valueOf(request.visibility.toUpperCase())
                    : null;

            DisplayMode displayMode = request.displayMode != null
                    ? DisplayMode.valueOf(request.displayMode.toUpperCase())
                    : null;

            WorkflowPublicationEntity publication = publicationService.updatePublicationInfo(
                    pubId,
                    tenantId,
                    organizationId,
                    request.title,
                    request.description,
                    showcaseInterfaceId,
                    showcaseRunId,
                    categoryId,
                    request.creditsPerUse,
                    visibility,
                    displayMode,
                    request.showcaseEpoch,
                    Boolean.TRUE.equals(request.clearShowcaseEpoch),
                    Boolean.TRUE.equals(request.viaScreeningWizard),
                    toReplacementMap(request.imageReplacements)
            );

            return ResponseEntity.ok(toDetailResponse(publication));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request updating publication: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error updating publication", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update publication"));
        }
    }

    /**
     * Unpublish a workflow.
     */
    @PostMapping("/workflow/{workflowId}/unpublish")
    public ResponseEntity<?> unpublishWorkflow(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @PathVariable String workflowId) {
        try {
            if (isViewerRole(organizationRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "VIEWER role cannot modify publications"));
            }
            logger.info("Unpublishing workflow: {} for tenant: {} (org: {})", workflowId, tenantId, organizationId);

            UUID wfId = UUID.fromString(workflowId);

            ResponseEntity<?> writeDenied = denyIfApplicationWriteRestrictedByWorkflow(
                    tenantId, organizationId, organizationRole, wfId);
            if (writeDenied != null) {
                return writeDenied;
            }

            WorkflowPublicationEntity publication = publicationService.unpublishWorkflow(wfId, tenantId, organizationId);

            return ResponseEntity.ok(toDetailResponse(publication));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request unpublishing workflow: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (PublicationPendingReviewException e) {
            // Pending-review guard. 409 conflict, not 500 (see publishWorkflow).
            logger.warn("Unpublish rejected (conflict): {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error unpublishing workflow", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to unpublish workflow"));
        }
    }

    /**
     * Delete a publication.
     */
    @DeleteMapping("/workflow/{workflowId}")
    public ResponseEntity<?> deletePublication(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @PathVariable String workflowId) {
        try {
            if (isViewerRole(organizationRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "VIEWER role cannot modify publications"));
            }
            logger.info("Deleting publication for workflow: {} for tenant: {} (org: {})",
                    workflowId, tenantId, organizationId);

            UUID wfId = UUID.fromString(workflowId);

            ResponseEntity<?> writeDenied = denyIfApplicationWriteRestrictedByWorkflow(
                    tenantId, organizationId, organizationRole, wfId);
            if (writeDenied != null) {
                return writeDenied;
            }

            publicationService.deletePublication(wfId, tenantId, organizationId);

            return ResponseEntity.ok(Map.of("success", true, "message", "Publication deleted"));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request deleting publication: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (PublicationPendingReviewException e) {
            // Pending-review guard. 409 conflict (aligned with publish/unpublish).
            logger.warn("Publication deletion rejected (conflict): {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error deleting publication", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete publication"));
        }
    }

    // ========================================================================
    // Detail endpoints (full entity needed for planSnapshot)
    // ========================================================================

    /**
     * Get publication by workflow ID. Authenticated callers only - used by
     * the publisher's "is my workflow published?" UI. Non-owners get the
     * same visibility-gated, PII-stripped view as {@code /by-id/{id}} so a
     * known JWT can't be used to harvest publisher emails by guessing
     * workflow UUIDs.
     */
    @GetMapping("/workflow/{workflowId}")
    public ResponseEntity<?> getPublicationByWorkflowId(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable String workflowId) {
        try {
            UUID wfId = UUID.fromString(workflowId);
            Optional<WorkflowPublicationEntity> publication = publicationService.getPublicationByWorkflowId(wfId);

            if (publication.isEmpty()) {
                return ResponseEntity.ok(Map.of("published", false));
            }
            WorkflowPublicationEntity pub = publication.get();
            boolean isOwner = publicationService.isCallerInOwnerScope(pub, tenantId, organizationId);
            if (!isOwner && !isAnonymouslyReadable(pub)) {
                return ResponseEntity.ok(Map.of("published", false));
            }

            Map<String, Object> response = toDetailResponse(pub);
            if (!isOwner) {
                // publisherEmail is the actual harvesting risk - strip for
                // non-owners. publisherId is the tenant UUID; we keep it so
                // the marketplace avatar component can resolve
                // /api/proxy/users/{publisherId}/avatar - the user already
                // sees this ID in the avatar request URL anyway.
                response.remove("publisherEmail");
            }
            // Don't override 'published' - it's already set correctly in toDetailResponse() based on status
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error getting publication by workflow ID", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get publication"));
        }
    }

    /**
     * Get publication by ID.
     *
     * <p>planSnapshot handling depends on publication type:
     * <ul>
     *   <li><b>WORKFLOW</b> - contains node configs with potentially sensitive
     *       data (credentials, params, prompts). Non-owners get a
     *       {@link PlanSnapshotSanitizer#sanitizeForPreview sanitized} version
     *       (labels / positions / edges only); owners see the full plan.</li>
     *   <li><b>TABLE / INTERFACE / SKILL</b> - resource publications. The
     *       planSnapshot IS the public content (table schema + rows, interface
     *       html/css/js, skill instructions). Running it through the workflow
     *       sanitizer would wipe every field. Return the raw snapshot for
     *       everyone.</li>
     *   <li><b>AGENT</b> - planSnapshot is not used (the agent fleet lives in
     *       {@code agentSnapshot}, exposed via a dedicated endpoint). Pass
     *       through null safely.</li>
     * </ul>
     */
    @GetMapping("/{publicationId}")
    public ResponseEntity<?> getPublicationById(
            @PathVariable String publicationId,
            @RequestHeader(value = "X-User-ID", required = false) String requestingUserId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = HEADER_SHARE_CONTEXT, required = false) String shareContext,
            @RequestHeader(value = HEADER_SHARE_RESOURCE_TYPE, required = false) String shareResourceType,
            @RequestHeader(value = HEADER_SHARE_RESOURCE_TOKEN, required = false) String shareResourceToken) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            Optional<WorkflowPublicationEntity> publication = publicationService.getPublicationById(pubId);

            if (publication.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            WorkflowPublicationEntity pub = publication.get();
            boolean isShareContext = "true".equalsIgnoreCase(shareContext);
            boolean isSharedPublication = isShareTokenForPublication(
                    pubId, shareContext, shareResourceType, shareResourceToken);
            if (isShareContext && !isSharedPublication) {
                return ResponseEntity.notFound().build();
            }
            boolean isOwner = publicationService.isCallerInOwnerScope(pub, requestingUserId, organizationId);
            boolean visibleToPublic = isAnonymouslyReadable(pub);
            // Acquirers keep access to the metadata of an app they already
            // installed, even after the publisher unpublishes (INACTIVE) or
            // privatises (PRIVATE) it - otherwise the installed-app view at
            // /app/applications/{id} 404s on its first read and dead-ends with
            // "Failed to load application", despite the acquirer owning an
            // independent clone of the workflow. The receipt lookup is
            // workspace-scoped and only runs when the publication is NOT publicly
            // readable (the locked-out case): the hot anonymous marketplace path
            // never touches it, and the anonymous /by-id preview alias (no
            // X-User-ID) returns false so it stays public-only.
            boolean isAcquirer = !isOwner && !isSharedPublication && !visibleToPublic
                    && publicationService.callerHoldsReceipt(pubId, requestingUserId, organizationId);
            // Visibility gate: anyone other than the publisher / an acquirer can
            // only see ACTIVE + PUBLIC publications. PRIVATE / UNLISTED /
            // PENDING_REVIEW / REJECTED / INACTIVE → 404 to avoid UUID-driven
            // enumeration of pre-moderation or non-public entities.
            if (!isOwner && !isSharedPublication && !isAcquirer && !visibleToPublic) {
                return ResponseEntity.notFound().build();
            }

            WorkflowPublicationEntity.PublicationType type = pub.getPublicationType();
            boolean isWorkflow = type == null
                    || type == WorkflowPublicationEntity.PublicationType.WORKFLOW;

            Map<String, Object> response = toDetailResponse(pub);
            // Server-authoritative ownership signal on the detail response (the
            // marketplace listing already enriches this; the by-id detail did not).
            // The installed-app view reads it to let the OWNER edit their source
            // workflow in place + "Publish update", while acquirers/anonymous stay
            // read-only. Never trust a client-side owner guess (ORG ownership).
            response.put("ownedByMe", isOwner);
            if (!isOwner) {
                // Strip publisher email - that's the actual harvesting risk.
                // publisherId is kept so the marketplace avatar component can
                // resolve /api/proxy/users/{publisherId}/avatar; the ID is
                // already implicit in that request URL anyway.
                response.remove("publisherEmail");
            }
            if (isOwner || isSharedPublication || !isWorkflow) {
                // Resource publications (TABLE/INTERFACE/SKILL) - and owners of
                // any type - get the raw planSnapshot. For resources the snapshot
                // IS the public content; sanitizing would return an empty map.
                response.put("planSnapshot", pub.getPlanSnapshot());
            } else {
                response.put("planSnapshot",
                        PlanSnapshotSanitizer.sanitizeForPreview(pub.getPlanSnapshot()));
            }
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            // Malformed (non-UUID) publication id is a client error, not a server 500 - matches the
            // controller's house convention (e.g. getShowcaseData) of mapping IllegalArgumentException
            // to 400. Previously fell through to the generic catch below and returned 500.
            logger.warn("Bad request for publication by ID: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid publication id"));
        } catch (Exception e) {
            logger.error("Error getting publication by ID", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get publication"));
        }
    }

    /**
     * Centralized visibility gate for anonymous read paths. A publication is
     * fetchable by anyone (no auth) only when status=ACTIVE AND visibility
     * is PUBLIC or UNLISTED. UNLISTED is link-only (no listing) but still
     * readable by UUID - explicit semantics, not "leak by default".
     */
    private static boolean isAnonymouslyReadable(WorkflowPublicationEntity pub) {
        if (pub.getStatus() != PublicationStatus.ACTIVE) return false;
        return pub.getVisibility() == PublicationVisibility.PUBLIC
                || pub.getVisibility() == PublicationVisibility.UNLISTED;
    }

    private static boolean isShareTokenForPublication(UUID publicationId,
                                                      String shareContext,
                                                      String shareResourceType,
                                                      String shareResourceToken) {
        return publicationId != null
                && "true".equalsIgnoreCase(shareContext)
                && SHARE_RESOURCE_APPLICATION.equalsIgnoreCase(shareResourceType)
                && publicationId.toString().equals(shareResourceToken);
    }

    /**
     * Public-browsing alias for the authenticated publication detail handler.
     *
     * <p>The bare {@code /publications/{publicationId}} route is behind the gateway's
     * JWT filter (the root prefix is reserved for authenticated mutation endpoints
     * like POST /publications). The marketplace preview page, which anonymous
     * visitors must be able to reach, calls this allowlisted {@code /by-id/…} alias
     * instead. Same sanitization semantics: owners get the full planSnapshot,
     * everyone else (including anonymous) gets the preview-sanitized version.
     */
    @GetMapping("/by-id/{publicationId}")
    public ResponseEntity<?> getPublicationByIdPublic(
            @PathVariable String publicationId,
            @RequestHeader(value = "X-User-ID", required = false) String requestingUserId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        // Delegates to the auth-aware variant - visibility gate + PII scrub
        // for non-owners are enforced inside getPublicationById.
        return getPublicationById(publicationId, requestingUserId, organizationId, null, null, null);
    }

    /**
     * Get the current user's APPLICATION workflow for a given publication.
     * Works for both publishers and acquirers.
     * Uses OrchestratorInternalClient to find the application workflow.
     */
    @GetMapping("/{publicationId}/application-workflow")
    public ResponseEntity<?> getApplicationWorkflow(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = HEADER_SHARE_CONTEXT, required = false) String shareContext,
            @RequestHeader(value = HEADER_SHARE_RESOURCE_TYPE, required = false) String shareResourceType,
            @RequestHeader(value = HEADER_SHARE_RESOURCE_TOKEN, required = false) String shareResourceToken,
            @PathVariable String publicationId) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            boolean isShareContext = "true".equalsIgnoreCase(shareContext);
            boolean isSharedPublication = isShareTokenForPublication(
                    pubId, shareContext, shareResourceType, shareResourceToken);
            if (isShareContext && !isSharedPublication) {
                return ResponseEntity.notFound().build();
            }
            String effectiveOrganizationId = isSharedPublication
                    ? resolveApplicationWorkflowOrganizationId(pubId, organizationId)
                    : organizationId;
            Map<String, Object> appWorkflow = publicationService.findApplicationWorkflow(
                    pubId, tenantId, effectiveOrganizationId);
            if (appWorkflow == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("workflowId", appWorkflow.get("id")));
        } catch (Exception e) {
            logger.error("Error getting application workflow for publication {}", publicationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get application workflow"));
        }
    }

    private String resolveApplicationWorkflowOrganizationId(UUID publicationId,
                                                            String organizationId) {
        return publicationService.getPublicationById(publicationId)
                .filter(pub -> pub.getOwnerType() == WorkflowPublicationEntity.OwnerType.ORG)
                .map(WorkflowPublicationEntity::getOwnerId)
                .filter(ownerId -> ownerId != null && !ownerId.isBlank())
                .orElse(organizationId);
    }

    /**
     * Get showcase data for a publication (public, no auth).
     * The original showcase snapshot system was removed; this endpoint now returns
     * the showcase run ID so the caller can load run data separately.
     * Keeps backward compatibility with clients expecting pagination metadata.
     */
    @GetMapping("/{publicationId}/showcase")
    public ResponseEntity<?> getShowcaseData(
            @PathVariable String publicationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "1") int size) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            Optional<WorkflowPublicationEntity> publication = publicationService.getPublicationById(pubId);

            if (publication.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            WorkflowPublicationEntity pub = publication.get();
            Map<String, Object> result = new HashMap<>();
            result.put("showcaseRunId", pub.getShowcaseRunId());
            result.put("items", List.of());
            result.put("pagination", Map.of(
                    "page", page,
                    "size", size,
                    "totalItems", 0,
                    "totalPages", 0
            ));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request for showcase data: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error getting showcase data", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get showcase data"));
        }
    }

    // ========================================================================
    // Snapshot version endpoints
    // ========================================================================

    /**
     * List all snapshot versions for a publication (lightweight: no plan data).
     */
    @GetMapping("/{publicationId}/versions")
    public ResponseEntity<?> listSnapshotVersions(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable String publicationId) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            // Owner-only - version history exposes the publisher's plan
            // evolution including raw planSnapshot at /versions/{n}. Without
            // this gate any JWT holder could pull every historical plan of
            // any PRIVATE / PENDING_REVIEW publication.
            Optional<WorkflowPublicationEntity> pubOpt = publicationService.getPublicationById(pubId);
            if (pubOpt.isEmpty()) return ResponseEntity.notFound().build();
            if (!publicationService.isCallerInOwnerScope(pubOpt.get(), tenantId, organizationId)) {
                return ResponseEntity.notFound().build();
            }

            List<PublicationSnapshotVersionEntity> versions = publicationService.getSnapshotVersions(pubId);

            List<Map<String, Object>> items = versions.stream().map(v -> {
                Map<String, Object> item = new HashMap<>();
                item.put("version", v.getVersion());
                item.put("label", v.getLabel());
                item.put("createdAt", v.getCreatedAt() != null ? v.getCreatedAt().toString() : null);
                return item;
            }).toList();

            return ResponseEntity.ok(Map.of(
                    "publicationId", publicationId,
                    "count", items.size(),
                    "versions", items
            ));
        } catch (Exception e) {
            logger.error("Error listing snapshot versions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to list snapshot versions"));
        }
    }

    /**
     * Get a specific snapshot version (includes full plan data). Owner-only.
     */
    @GetMapping("/{publicationId}/versions/{version}")
    public ResponseEntity<?> getSnapshotVersion(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable String publicationId,
            @PathVariable int version) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            // Owner-only - full planSnapshot incl. node configs.
            Optional<WorkflowPublicationEntity> pubOpt = publicationService.getPublicationById(pubId);
            if (pubOpt.isEmpty()) return ResponseEntity.notFound().build();
            if (!publicationService.isCallerInOwnerScope(pubOpt.get(), tenantId, organizationId)) {
                return ResponseEntity.notFound().build();
            }

            Optional<PublicationSnapshotVersionEntity> snapshotVersion =
                    publicationService.getSnapshotVersion(pubId, version);

            if (snapshotVersion.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            PublicationSnapshotVersionEntity sv = snapshotVersion.get();
            Map<String, Object> response = new HashMap<>();
            response.put("publicationId", publicationId);
            response.put("version", sv.getVersion());
            response.put("label", sv.getLabel());
            response.put("createdAt", sv.getCreatedAt() != null ? sv.getCreatedAt().toString() : null);
            response.put("planSnapshot", sv.getPlanSnapshot());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error getting snapshot version", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get snapshot version"));
        }
    }

    // ========================================================================
    // Review endpoints
    // ========================================================================

    /**
     * Get top-level reviews for a publication (paginated, newest first).
     * Includes replyCount per review via batch query.
     */
    @GetMapping("/{publicationId}/reviews")
    public ResponseEntity<?> getReviews(
            @PathVariable String publicationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean onlyWithComment) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            Page<PublicationReviewEntity> reviews = reviewService.getReviews(pubId, page, size, onlyWithComment);

            // Batch-load reply counts
            List<UUID> reviewIds = reviews.getContent().stream()
                    .map(PublicationReviewEntity::getId)
                    .toList();
            Map<UUID, Long> replyCounts = reviewService.getReplyCountsBatch(reviewIds);

            List<Map<String, Object>> items = reviews.getContent().stream()
                    .map(r -> {
                        Map<String, Object> resp = toReviewResponse(r);
                        resp.put("replyCount", replyCounts.getOrDefault(r.getId(), 0L));
                        return resp;
                    })
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "reviews", items,
                    "page", page,
                    "size", size,
                    "totalElements", reviews.getTotalElements(),
                    "totalPages", reviews.getTotalPages()
            ));
        } catch (Exception e) {
            logger.error("Error getting reviews for publication {}", publicationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get reviews"));
        }
    }

    /**
     * Count of top-level reviews that carry a non-empty comment. Drives the
     * Comments tab badge on the publication panel - distinct from
     * {@code WorkflowPublication.reviewCount} which counts votes (used for the
     * average + Info tab vote count).
     */
    @GetMapping("/{publicationId}/reviews/comments-count")
    public ResponseEntity<?> getCommentsCount(@PathVariable String publicationId) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            return ResponseEntity.ok(Map.of("count", reviewService.countComments(pubId)));
        } catch (Exception e) {
            logger.error("Error getting comments count for publication {}", publicationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get comments count"));
        }
    }

    /**
     * Get current user's review for a publication.
     */
    @GetMapping("/{publicationId}/reviews/mine")
    public ResponseEntity<?> getMyReview(
            @RequestHeader("X-User-ID") String userId,
            @PathVariable String publicationId) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            Optional<PublicationReviewEntity> review = reviewService.getMyReview(pubId, userId);

            if (review.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(toReviewResponse(review.get()));
        } catch (Exception e) {
            logger.error("Error getting my review for publication {}", publicationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get review"));
        }
    }

    /**
     * Submit or update a review (upsert).
     */
    @PostMapping("/{publicationId}/reviews")
    public ResponseEntity<?> submitReview(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-User-Name", required = false) String userName,
            @RequestHeader(value = "X-User-Avatar", required = false) String userAvatar,
            @PathVariable String publicationId,
            @RequestBody SubmitReviewRequest request) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            PublicationReviewEntity review = reviewService.submitReview(
                    pubId, userId, userName, userAvatar,
                    request.rating, request.comment);

            return ResponseEntity.ok(toReviewResponse(review));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request submitting review: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error submitting review for publication {}", publicationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to submit review"));
        }
    }

    /**
     * Delete current user's review.
     */
    @DeleteMapping("/{publicationId}/reviews")
    public ResponseEntity<?> deleteReview(
            @RequestHeader("X-User-ID") String userId,
            @PathVariable String publicationId) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            reviewService.deleteReview(pubId, userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request deleting review: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error deleting review for publication {}", publicationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete review"));
        }
    }

    /**
     * Remove only the current user's comment, keeping their rating. When the
     * review was comment-only the row is removed entirely. Returns the surviving
     * review (rating kept, comment null), or {@code {success,removed}} when the
     * row was dropped.
     */
    @DeleteMapping("/{publicationId}/reviews/comment")
    public ResponseEntity<?> deleteReviewComment(
            @RequestHeader("X-User-ID") String userId,
            @PathVariable String publicationId) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            PublicationReviewEntity review = reviewService.clearComment(pubId, userId);
            return ResponseEntity.ok(review != null
                    ? toReviewResponse(review)
                    : Map.of("success", true, "removed", true));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request deleting review comment: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error deleting review comment for publication {}", publicationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete comment"));
        }
    }

    /**
     * Remove only the current user's rating, keeping their comment. When the
     * review was rating-only the row is removed entirely. Returns the surviving
     * review (comment kept, rating null), or {@code {success,removed}} when the
     * row was dropped.
     */
    @DeleteMapping("/{publicationId}/reviews/rating")
    public ResponseEntity<?> deleteReviewRating(
            @RequestHeader("X-User-ID") String userId,
            @PathVariable String publicationId) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            PublicationReviewEntity review = reviewService.clearRating(pubId, userId);
            return ResponseEntity.ok(review != null
                    ? toReviewResponse(review)
                    : Map.of("success", true, "removed", true));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request deleting review rating: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error deleting review rating for publication {}", publicationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete rating"));
        }
    }

    // ========================================================================
    // Reply endpoints
    // ========================================================================

    /**
     * Get replies for a specific review.
     */
    @GetMapping("/{publicationId}/reviews/{reviewId}/replies")
    public ResponseEntity<?> getReplies(
            @PathVariable String publicationId,
            @PathVariable String reviewId) {
        try {
            UUID revId = UUID.fromString(reviewId);
            List<PublicationReviewEntity> replies = reviewService.getReplies(revId);

            List<Map<String, Object>> items = replies.stream()
                    .map(this::toReviewResponse)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "replies", items,
                    "count", items.size()
            ));
        } catch (Exception e) {
            logger.error("Error getting replies for review {}", reviewId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get replies"));
        }
    }

    /**
     * Submit a reply to a review.
     */
    @PostMapping("/{publicationId}/reviews/{reviewId}/replies")
    public ResponseEntity<?> submitReply(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-User-Name", required = false) String userName,
            @RequestHeader(value = "X-User-Avatar", required = false) String userAvatar,
            @PathVariable String publicationId,
            @PathVariable String reviewId,
            @RequestBody SubmitReplyRequest request) {
        try {
            UUID revId = UUID.fromString(reviewId);
            PublicationReviewEntity reply = reviewService.submitReply(
                    revId, userId, userName, userAvatar, request.comment);
            return ResponseEntity.ok(toReviewResponse(reply));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request submitting reply: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error submitting reply for review {}", reviewId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to submit reply"));
        }
    }

    /**
     * Update own reply.
     */
    @PutMapping("/{publicationId}/reviews/replies/{replyId}")
    public ResponseEntity<?> updateReply(
            @RequestHeader("X-User-ID") String userId,
            @PathVariable String publicationId,
            @PathVariable String replyId,
            @RequestBody SubmitReplyRequest request) {
        try {
            UUID rId = UUID.fromString(replyId);
            PublicationReviewEntity updated = reviewService.updateReply(rId, userId, request.comment);
            return ResponseEntity.ok(toReviewResponse(updated));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request updating reply: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error updating reply {}", replyId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update reply"));
        }
    }

    /**
     * Delete own reply.
     */
    @DeleteMapping("/{publicationId}/reviews/replies/{replyId}")
    public ResponseEntity<?> deleteReply(
            @RequestHeader("X-User-ID") String userId,
            @PathVariable String publicationId,
            @PathVariable String replyId) {
        try {
            UUID rId = UUID.fromString(replyId);
            reviewService.deleteReply(rId, userId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request deleting reply: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error deleting reply {}", replyId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete reply"));
        }
    }

    private Map<String, Object> toReviewResponse(PublicationReviewEntity review) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", review.getId().toString());
        response.put("publicationId", review.getPublicationId().toString());
        response.put("parentId", review.getParentId() != null ? review.getParentId().toString() : null);
        response.put("reviewerId", review.getReviewerId());
        response.put("reviewerName", review.getReviewerName());
        response.put("reviewerAvatarUrl", review.getReviewerAvatarUrl());
        response.put("rating", review.getRating());
        response.put("comment", review.getComment());
        response.put("createdAt", review.getCreatedAt() != null ? review.getCreatedAt().toString() : null);
        response.put("updatedAt", review.getUpdatedAt() != null ? review.getUpdatedAt().toString() : null);
        return response;
    }

    // ========================================================================
    // List endpoints (lightweight -- no planSnapshot loaded from DB)
    // ========================================================================

    /**
     * Get my publications.
     * Uses lightweight query to avoid loading planSnapshot.
     */
    @GetMapping("/my")
    public ResponseEntity<?> getMyPublications(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @RequestParam(name = "applicationOnly", defaultValue = "false") boolean applicationOnly) {
        try {
            // V223 / #151 - route by active workspace. Org scope returns all
            // teammate publications; personal scope returns only the caller's own.
            Set<UUID> excludedPublicationIds = restrictedApplicationIds(
                    tenantId, organizationId, organizationRole, applicationOnly);
            List<PublicationListItem> publications =
                    listQueryService.findByScope(tenantId, organizationId, applicationOnly, excludedPublicationIds);

            List<Map<String, Object>> response = publications.stream()
                    .map(PublicationListItem::toResponseMap)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "count", response.size(),
                    "publications", response
            ));
        } catch (Exception e) {
            logger.error("Error getting my publications", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get publications"));
        }
    }

    /**
     * Paged + DB-searchable variant of {@link #getMyPublications}.
     * Returns {@code { items, totalCount, page, size }}.
     */
    @GetMapping("/my/paged")
    public ResponseEntity<?> getMyPublicationsPaged(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @RequestParam(name = "applicationOnly", defaultValue = "false") boolean applicationOnly,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size) {
        try {
            // V223 - route by active workspace.
            Set<UUID> excludedPublicationIds = restrictedApplicationIds(
                    tenantId, organizationId, organizationRole, applicationOnly);
            org.springframework.data.domain.Page<PublicationListItem> publications =
                    listQueryService.findByScopePaged(
                            tenantId, organizationId, applicationOnly, q, page, size, excludedPublicationIds);

            List<Map<String, Object>> items = publications.getContent().stream()
                    .map(PublicationListItem::toResponseMap)
                    .toList();

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("items", items);
            body.put("totalCount", publications.getTotalElements());
            body.put("page", publications.getNumber());
            body.put("size", publications.getSize());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            logger.error("Error getting paged my publications", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get publications"));
        }
    }

    private Set<UUID> restrictedApplicationIds(
            String tenantId,
            String organizationId,
            String organizationRole,
            boolean applicationOnly) {
        if (!applicationOnly || organizationId == null || organizationId.isBlank()) {
            return Set.of();
        }
        return orgAccessGuard.getRestrictedResourceIds(
                        organizationId, tenantId, "application", organizationRole).stream()
                .map(this::toUuidOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private UUID toUuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return null;
        }
    }

    /**
     * Get marketplace publications (public listing).
     * Optional category filter via query param.
     * Uses lightweight query to avoid loading planSnapshot.
     */
    @GetMapping("/marketplace")
    public ResponseEntity<?> getMarketplacePublications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String category,
            // Optional - present only for authenticated callers (the marketplace is public-browsable).
            // Used to flag publications the caller's ACTIVE workspace already owns (so the card shows
            // "Installed" instead of "Acquire"); absent → anonymous → ownedByMe stays false.
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            Page<PublicationListItem> publications;

            if (category != null && !category.isEmpty()) {
                publications = listQueryService.findMarketplacePublicationsByCategory(category, page, size);
            } else {
                publications = listQueryService.findMarketplacePublications(page, size);
            }

            List<Map<String, Object>> items = publications.getContent().stream()
                    .map(PublicationListItem::toResponseMap)
                    .toList();
            enrichOwnedByMe(items, userId, organizationId);

            return ResponseEntity.ok(Map.of(
                    "count", publications.getTotalElements(),
                    "page", page,
                    "size", size,
                    "totalPages", publications.getTotalPages(),
                    "category", category != null ? category : "all",
                    "publications", items
            ));
        } catch (Exception e) {
            logger.error("Error getting marketplace publications", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get marketplace publications"));
        }
    }

    /**
     * Flags each publication the caller's ACTIVE workspace already owns, so the marketplace
     * card renders "Installed" instead of "Acquire". Mirrors {@code isCallerInOwnerScope}:
     * applications/publications are workspace-scoped (owner_type/owner_id is the source of
     * truth) - an ORG-owned app is "mine" only when {@code owner_id == my active org}; a
     * USER-owned app when {@code owner_id == me}; legacy rows fall back to publisher_id. Being
     * a member of the owner org under a DIFFERENT active workspace does NOT count - acquiring
     * clones it into the active workspace, exactly like every other resource.
     */
    private void enrichOwnedByMe(List<Map<String, Object>> items, String userId, String organizationId) {
        for (Map<String, Object> m : items) {
            m.put("ownedByMe", computeOwnedByMe(
                    (String) m.get("ownerType"), (String) m.get("ownerId"),
                    (String) m.get("publisherId"), userId, organizationId));
        }
    }

    // Package-private for unit testing the ownership rule directly.
    static boolean computeOwnedByMe(String ownerType, String ownerId,
                                    String publisherId, String userId, String organizationId) {
        if (userId == null || userId.isBlank()) return false;
        if (ownerType == null || ownerId == null || ownerId.isBlank()) {
            return userId.equals(publisherId); // legacy / not-yet-assigned owner scope (matches isCallerInOwnerScope)
        }
        return switch (ownerType) {
            case "USER" -> userId.equals(ownerId);
            case "ORG" -> organizationId != null && !organizationId.isBlank() && organizationId.equals(ownerId);
            default -> userId.equals(publisherId);
        };
    }

    /**
     * Public "apps by this user" - the grid on the public profile page
     * ({@code /u/{username}}). Lists the user's ACTIVE + PUBLIC publications only,
     * so it is safe to serve without a JWT (path allowlisted in
     * {@code GatewayConstants.PUBLIC_ENDPOINTS}). Response shape mirrors
     * {@code /marketplace}: {@code { count, page, size, totalPages, publications }}.
     */
    @GetMapping("/by-publisher/{userId}")
    public ResponseEntity<?> getPublicationsByPublisher(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            Page<PublicationListItem> publications =
                    listQueryService.findPublicByPublisherPaged(userId, page, size);

            List<Map<String, Object>> items = publications.getContent().stream()
                    .map(PublicationListItem::toResponseMap)
                    .toList();

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("count", publications.getTotalElements());
            body.put("page", publications.getNumber());
            body.put("size", publications.getSize());
            body.put("totalPages", publications.getTotalPages());
            body.put("publisherId", userId);
            body.put("publications", items);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            logger.error("Error getting publications by publisher {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get publications"));
        }
    }

    /**
     * Onboarding "suggested applications" - personalized public marketplace
     * applications derived from the caller's onboarding choices.
     *
     * <p>The frontend forwards the onboarding profile (interests / useCases /
     * profession); {@link OnboardingCategoryMapper} maps it to marketplace
     * category slugs and {@link PublicationListQueryService#suggestApplications}
     * returns matching public applications (with a top-applications fallback when
     * none match). Read-only and self-contained - it reads no per-user server
     * state and ignores auth headers - but the path is NOT on the gateway public
     * allowlist (unlike {@code /marketplace}), so it is reached with the caller's
     * JWT; the frontend always invokes it from the authenticated app shell.
     */
    @GetMapping("/suggestions")
    public ResponseEntity<?> getSuggestedApplications(
            @RequestParam(required = false) List<String> interests,
            @RequestParam(required = false) List<String> useCases,
            @RequestParam(required = false) String profession,
            @RequestParam(defaultValue = "8") int limit) {
        try {
            List<String> categorySlugs =
                    onboardingCategoryMapper.toCategorySlugs(interests, useCases, profession);
            List<PublicationListItem> publications =
                    listQueryService.suggestApplications(categorySlugs, limit);

            List<Map<String, Object>> items = publications.stream()
                    .map(PublicationListItem::toResponseMap)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "count", items.size(),
                    "categories", categorySlugs,
                    "publications", items
            ));
        } catch (Exception e) {
            logger.error("Error getting suggested applications", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get suggested applications"));
        }
    }

    /**
     * Search publications.
     * Uses lightweight query to avoid loading planSnapshot.
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchPublications(
            @RequestParam String q,
            @RequestParam(required = false) String category,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            List<PublicationListItem> publications = listQueryService.searchByTitle(q, category);

            List<Map<String, Object>> response = publications.stream()
                    .map(PublicationListItem::toResponseMap)
                    .toList();
            enrichOwnedByMe(response, userId, organizationId);

            return ResponseEntity.ok(Map.of(
                    "count", response.size(),
                    "publications", response
            ));
        } catch (Exception e) {
            logger.error("Error searching publications", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to search publications"));
        }
    }

    /**
     * Get popular publications.
     * Uses lightweight query to avoid loading planSnapshot.
     */
    @GetMapping("/popular")
    public ResponseEntity<?> getPopularPublications(@RequestParam(defaultValue = "10") int limit) {
        try {
            List<PublicationListItem> publications = listQueryService.findPopularPublications(limit);

            List<Map<String, Object>> response = publications.stream()
                    .map(PublicationListItem::toResponseMap)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "count", response.size(),
                    "publications", response
            ));
        } catch (Exception e) {
            logger.error("Error getting popular publications", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get popular publications"));
        }
    }

    // ========================================================================
    // Acquisition endpoints
    // ========================================================================

    /**
     * Acquire a publication (clone as new workflow for the user).
     * Returns a Map with workflowId and title since the workflow is created
     * in orchestrator-service via internal API.
     */
    @PostMapping("/{publicationId}/acquire")
    public ResponseEntity<?> acquirePublication(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @PathVariable String publicationId) {
        try {
            // Acquire clones the publication into the caller's workspace - a create/write
            // action, so a read-only VIEWER must not be able to install into an org
            // (same gate as acquire-resource / acquire-agent).
            if (isViewerRole(organizationRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "VIEWER role cannot acquire publications"));
            }
            logger.info("Acquiring publication: {} for tenant: {} org: {}", publicationId, tenantId, organizationId);

            UUID pubId = UUID.fromString(publicationId);
            Map<String, Object> result = publicationService.acquirePublication(pubId, tenantId, organizationId);

            return ResponseEntity.ok(Map.of(
                    "workflowId", result.get("workflowId"),
                    "publicationId", publicationId,
                    "title", result.get("title")
            ));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request acquiring publication: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error acquiring publication", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to acquire publication"));
        }
    }

    /**
     * Get purchases (receipts) for the current user, with active workflow status.
     * Uses lightweight batch query for publication data to avoid loading planSnapshot.
     */
    @GetMapping("/purchases")
    public ResponseEntity<?> getPurchases(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            List<Map<String, Object>> purchases = publicationService.getPurchases(tenantId, organizationId);

            // Collect publication IDs from the purchases to batch-load lightweight DTOs
            List<UUID> pubIds = purchases.stream()
                    .map(p -> p.get("publicationId"))
                    .filter(Objects::nonNull)
                    .map(id -> UUID.fromString(id.toString()))
                    .distinct()
                    .toList();

            // Batch load lightweight publication data (no planSnapshot). Include INACTIVE rows:
            // the caller holds a receipt, so a purchase whose publisher unpublished/deleted the
            // source must still resolve the real publication (not fall back to the synth).
            Map<UUID, PublicationListItem> lightPubMap = listQueryService.findByIdsIncludingInactive(pubIds).stream()
                    .collect(Collectors.toMap(PublicationListItem::id, Function.identity()));

            List<Map<String, Object>> items = purchases.stream().map(p -> {
                Map<String, Object> item = new HashMap<>(p);
                String pubIdStr = p.get("publicationId") != null ? p.get("publicationId").toString() : null;
                if (pubIdStr != null) {
                    UUID pId = UUID.fromString(pubIdStr);
                    PublicationListItem lightPub = lightPubMap.get(pId);
                    // Replace with the lightweight DTO when the publication exists locally.
                    // When it does NOT (a CLOUD-sourced acquisition), KEEP the publication the
                    // service already put on the item (a minimal synth from the cloned workflow) -
                    // overwriting it to null here is what dropped remote purchases from My Purchases.
                    if (lightPub != null) {
                        item.put("publication", lightPub.toResponseMap());
                    }
                }
                return item;
            }).toList();

            return ResponseEntity.ok(Map.of(
                    "count", items.size(),
                    "purchases", items
            ));
        } catch (Exception e) {
            logger.error("Error getting purchases", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get purchases"));
        }
    }

    /**
     * Get acquired applications for the current user.
     * Uses OrchestratorInternalClient for workflow data and lightweight batch query
     * for publication data to avoid loading planSnapshot.
     */
    @GetMapping("/acquired")
    public ResponseEntity<?> getAcquiredApplications(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole) {
        try {
            List<Map<String, Object>> items = buildAcquiredItems(tenantId, organizationId, organizationRole);
            return ResponseEntity.ok(Map.of(
                    "count", items.size(),
                    "applications", items
            ));
        } catch (Exception e) {
            logger.error("Error getting acquired applications", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get acquired applications"));
        }
    }

    /**
     * Paged + searchable variant of {@link #getAcquiredApplications}.
     * Search matches workflow title and (when present) publication title/description.
     * Pagination is applied in-memory after building the joined items because the
     * underlying source is a cross-service join (orchestrator workflow + publication row).
     */
    @GetMapping("/acquired/paged")
    public ResponseEntity<?> getAcquiredApplicationsPaged(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size) {
        try {
            List<Map<String, Object>> items = buildAcquiredItems(tenantId, organizationId, organizationRole);

            if (q != null && !q.isBlank()) {
                String needle = q.trim().toLowerCase();
                items = items.stream().filter(item -> {
                    Object name = item.get("name");
                    if (name != null && name.toString().toLowerCase().contains(needle)) return true;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pub = (Map<String, Object>) item.get("publication");
                    if (pub != null) {
                        Object title = pub.get("title");
                        Object desc = pub.get("description");
                        if (title != null && title.toString().toLowerCase().contains(needle)) return true;
                        if (desc != null && desc.toString().toLowerCase().contains(needle)) return true;
                    }
                    return false;
                }).toList();
            }

            int totalCount = items.size();
            int safeSize = Math.min(Math.max(size, 1), 100);
            int safePage = Math.max(page, 0);
            int from = Math.min(safePage * safeSize, totalCount);
            int to = Math.min(from + safeSize, totalCount);
            List<Map<String, Object>> slice = items.subList(from, to);

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("items", slice);
            body.put("totalCount", totalCount);
            body.put("page", safePage);
            body.put("size", safeSize);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            logger.error("Error getting paged acquired applications", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get acquired applications"));
        }
    }

    /** Shared builder for acquired-application items (DRY between paged and non-paged endpoints). */
    private List<Map<String, Object>> buildAcquiredItems(String tenantId, String organizationId,
                                                         String organizationRole) {
        List<Map<String, Object>> workflows = publicationService.getAcquiredWorkflows(tenantId, organizationId);

        // Org per-member deny-list: drop acquired apps the member is restricted from, keyed on the
        // source publication id - the canonical "application" resource id, identical to the
        // my/published list filter (restrictedApplicationIds) and the write gates. Without this an
        // acquired app stayed visible on /app/applications even after an OWNER/ADMIN restricted it,
        // unlike the publisher's own apps. No-op in personal scope or for an OWNER/ADMIN (the guard
        // returns an empty set).
        Set<UUID> restricted = restrictedApplicationIds(tenantId, organizationId, organizationRole, true);
        if (!restricted.isEmpty()) {
            workflows = workflows.stream()
                    .filter(w -> {
                        Object src = w.get("sourcePublicationId");
                        return src == null || !restricted.contains(toUuidOrNull(src.toString()));
                    })
                    .toList();
        }

        List<UUID> pubIds = workflows.stream()
                .map(w -> w.get("sourcePublicationId"))
                .filter(Objects::nonNull)
                .map(id -> UUID.fromString(id.toString()))
                .distinct()
                .toList();
        // Include INACTIVE rows: an acquired app whose publisher unpublished (INACTIVE) or
        // deleted (soft delete = INACTIVE) the source must still resolve the REAL publication
        // (showcase fields + publisher + remote=false) so the installed-app card renders the
        // interface via the receipt-gated authenticated showcase-render, instead of dropping to
        // the synthetic remote stand-in / node-icon cover tile. The synth branch below then
        // serves ONLY genuinely cloud-sourced acquisitions absent from the local catalog.
        Map<UUID, PublicationListItem> pubMap = listQueryService.findByIdsIncludingInactive(pubIds).stream()
                .collect(Collectors.toMap(PublicationListItem::id, Function.identity()));

        return workflows.stream().map(w -> {
            Map<String, Object> item = new HashMap<>();
            item.put("workflowId", w.get("id"));
            String sourcePubId = w.get("sourcePublicationId") != null ? w.get("sourcePublicationId").toString() : null;
            item.put("sourcePublicationId", sourcePubId);
            item.put("name", w.get("title"));
            item.put("acquiredAt", w.get("acquiredAt"));

            if (sourcePubId != null) {
                PublicationListItem pub = pubMap.get(UUID.fromString(sourcePubId));
                if (pub != null) {
                    item.put("publication", pub.toResponseMap());
                } else {
                    // Cloud-sourced (remote) acquisition: the source publication lives on the
                    // cloud and is absent from the local catalog, so the local lookup is null.
                    // Synthesize a minimal publication from the cloned workflow so the acquired
                    // app still renders + opens on /app/applications instead of being silently
                    // dropped by the frontend (which skips items with a null publication).
                    item.put("publication", remoteAcquisitionPublication(sourcePubId, w.get("title")));
                }
            }
            return item;
        }).toList();
    }

    /**
     * Minimal publication stand-in for a CLOUD-sourced acquisition whose source
     * publication is absent from the local catalog. Carries just enough for the
     * acquired-app / purchase cards to render (id + title + an APPLICATION shape);
     * {@code remote=true} marks it so the UI can treat it as a cloud clone. The
     * live preview + actions still use the local clone's own {@code workflowId}.
     */
    private static Map<String, Object> remoteAcquisitionPublication(String sourcePublicationId, Object title) {
        Map<String, Object> synth = new HashMap<>();
        synth.put("id", sourcePublicationId);
        synth.put("title", title);
        synth.put("publicationType", "WORKFLOW");
        synth.put("displayMode", "APPLICATION");
        synth.put("creditsPerUse", 0);
        synth.put("remote", true);
        return synth;
    }

    // ========================================================================
    // Response mapping (full entity -- used only for detail/write endpoints)
    // ========================================================================

    /**
     * Convert a full entity to a response map.
     * Used only for detail and write endpoints where the entity is already loaded.
     * Includes backfill logic for pre-V118 publications.
     */
    private Map<String, Object> toDetailResponse(WorkflowPublicationEntity pub) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", pub.getId().toString());
        response.put("publicationType", pub.getPublicationType() != null ? pub.getPublicationType().name() : "WORKFLOW");
        response.put("workflowId", pub.getWorkflowId() != null ? pub.getWorkflowId().toString() : null);
        response.put("agentConfigId", pub.getAgentConfigId() != null ? pub.getAgentConfigId().toString() : null);
        response.put("title", pub.getTitle());
        response.put("description", pub.getDescription());

        // Showcase fields
        response.put("showcaseInterfaceId", pub.getShowcaseInterfaceId() != null ? pub.getShowcaseInterfaceId().toString() : null);
        response.put("showcaseRunId", pub.getShowcaseRunId());
        response.put("showcaseChosenEpoch", pub.getShowcaseChosenEpoch());
        response.put("hasShowcase", pub.hasShowcase());
        response.put("isApplication", pub.isApplication());
        response.put("displayMode", pub.getDisplayMode().name());

        // Category
        if (pub.getCategoryId() != null) {
            Map<String, Object> categoryData = orchestratorClient.getCategoryById(pub.getCategoryId());
            if (categoryData != null) {
                response.put("category", categoryData);
            } else {
                response.put("category", null);
            }
        } else {
            response.put("category", null);
        }

        response.put("creditsPerUse", pub.getCreditsPerUse());
        response.put("publisherId", pub.getPublisherId());
        response.put("publisherName", pub.getPublisherName());
        response.put("publisherEmail", pub.getPublisherEmail());
        response.put("publisherAvatarUrl", pub.getPublisherAvatarUrl());
        response.put("status", pub.getStatus().name());
        response.put("published", pub.getStatus() == WorkflowPublicationEntity.PublicationStatus.ACTIVE);
        response.put("visibility", pub.getVisibility().name());
        response.put("useCount", pub.getUseCount());
        response.put("totalCreditsEarned", pub.getTotalCreditsEarned());
        response.put("planVersion", pub.getPlanVersion());
        response.put("snapshotVersion", pub.getSnapshotVersion());
        response.put("nodeIcons", pub.getNodeIcons());

        // Lazy backfill resource counts for pre-V118 publications
        publicationService.backfillResourceCountsIfNeeded(pub);
        response.put("agentCount", pub.getAgentCount() != null ? pub.getAgentCount() : 0);
        response.put("skillCount", pub.getSkillCount() != null ? pub.getSkillCount() : 0);
        response.put("interfaceCount", pub.getInterfaceCount());
        response.put("datasourceCount", pub.getDatasourceCount());

        // Review stats
        response.put("averageRating", pub.getAverageRating() != null ? pub.getAverageRating() : 0.0);
        response.put("reviewCount", pub.getReviewCount() != null ? pub.getReviewCount() : 0);

        response.put("publishedAt", pub.getPublishedAt() != null ? pub.getPublishedAt().toString() : null);
        response.put("updatedAt", pub.getUpdatedAt() != null ? pub.getUpdatedAt().toString() : null);
        // Don't include planSnapshot in responses by default -- detail endpoints add it explicitly
        return response;
    }

    // ========================================================================
    // Agent publication endpoints
    // ========================================================================

    @PostMapping("/publish-agent")
    public ResponseEntity<?> publishAgent(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @RequestBody Map<String, Object> request) {
        try {
            if (isViewerRole(organizationRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "VIEWER role cannot modify publications"));
            }
            ResponseEntity<?> denied = denyIfResourceWriteRestricted(tenantId, organizationId, organizationRole,
                    "agent", request.get("agentConfigId") != null ? request.get("agentConfigId").toString() : null);
            if (denied != null) return denied;
            logger.info("Publishing agent for tenant: {} (org={})", tenantId,
                    organizationId != null && !organizationId.isBlank() ? organizationId : "personal");
            WorkflowPublicationEntity publication = agentPublicationService.publishAgent(request, tenantId, organizationId);
            return ResponseEntity.ok(toDetailResponse(publication));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request publishing agent: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (PublicationPendingReviewException e) {
            // Pending-review guard (re-publish blocked). 409 conflict, not 500.
            logger.warn("Agent publish rejected (conflict): {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error publishing agent", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to publish agent"));
        }
    }

    @PostMapping("/acquire-agent/{publicationId}")
    public ResponseEntity<?> acquireAgent(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @PathVariable String publicationId) {
        try {
            if (isViewerRole(organizationRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "VIEWER role cannot acquire publications"));
            }
            logger.info("Acquiring agent publication {} for tenant: {} org: {}", publicationId, tenantId, organizationId);
            UUID pubId = UUID.fromString(publicationId);
            Map<String, Object> result = agentPublicationService.acquireAgentPublication(pubId, tenantId, organizationId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request acquiring agent: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error acquiring agent publication", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to acquire agent"));
        }
    }

    @GetMapping("/marketplace/agents")
    public ResponseEntity<?> getAgentMarketplace(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            size = Math.min(Math.max(size, 1), 100);
            page = Math.max(page, 0);
            Page<PublicationListItem> publications = listQueryService.findMarketplaceAgentPublications(page, size);
            Map<String, Object> response = new HashMap<>();
            response.put("content", publications.getContent().stream()
                    .map(PublicationListItem::toResponseMap)
                    .collect(Collectors.toList()));
            response.put("totalElements", publications.getTotalElements());
            response.put("totalPages", publications.getTotalPages());
            response.put("page", publications.getNumber());
            response.put("size", publications.getSize());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error fetching agent marketplace", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch agent marketplace"));
        }
    }

    @GetMapping("/marketplace/agents/search")
    public ResponseEntity<?> searchAgentMarketplace(
            @RequestParam String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            List<PublicationListItem> results = listQueryService.searchAgentPublications(query);
            return ResponseEntity.ok(Map.of(
                    "content", results.stream().map(PublicationListItem::toResponseMap).collect(Collectors.toList()),
                    "totalElements", results.size()
            ));
        } catch (Exception e) {
            logger.error("Error searching agent marketplace", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to search agent marketplace"));
        }
    }

    @GetMapping("/is-agent-published/{agentConfigId}")
    public ResponseEntity<?> isAgentPublished(
            @RequestHeader("X-User-ID") String tenantId,
            @PathVariable String agentConfigId) {
        try {
            UUID agentId = UUID.fromString(agentConfigId);
            Map<String, Object> status = agentPublicationService.getAgentPublicationStatus(agentId);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("exists", false, "published", false));
        }
    }

    @PostMapping("/unpublish-agent/{agentConfigId}")
    public ResponseEntity<?> unpublishAgent(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @PathVariable String agentConfigId) {
        try {
            if (isViewerRole(organizationRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "VIEWER role cannot modify publications"));
            }
            ResponseEntity<?> denied = denyIfResourceWriteRestricted(
                    tenantId, organizationId, organizationRole, "agent", agentConfigId);
            if (denied != null) return denied;
            UUID agentId = UUID.fromString(agentConfigId);
            agentPublicationService.unpublishAgent(agentId, tenantId, organizationId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (PublicationPendingReviewException e) {
            // Pending-review guard. 409 conflict, not 500.
            logger.warn("Agent unpublish rejected (conflict): {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error unpublishing agent", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to unpublish agent"));
        }
    }

    /**
     * Public fetch of a frozen agent-fleet snapshot (marketplace preview).
     *
     * <p>The route lives under the allowlisted {@code /by-id/} prefix, so anonymous
     * marketplace visitors can load the agent canvas without signing in. The
     * snapshot is content-frozen at publication time - no publisher live data
     * leaks through. {@code X-User-ID} is intentionally NOT required: the gateway
     * strips it for public endpoints, and {@code getAgentSnapshot(pubId)} does
     * not need it.
     */
    @GetMapping("/by-id/{publicationId}/agent-snapshot")
    public ResponseEntity<?> getAgentSnapshot(@PathVariable String publicationId,
                                               @RequestHeader(value = "X-User-ID", required = false) String requestingUserId,
                                               @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            Optional<WorkflowPublicationEntity> pubOpt = publicationService.getPublicationById(pubId);
            if (pubOpt.isEmpty()) return ResponseEntity.notFound().build();
            WorkflowPublicationEntity pub = pubOpt.get();
            boolean isOwner = publicationService.isCallerInOwnerScope(pub, requestingUserId, organizationId);
            if (!isOwner && !isAnonymouslyReadable(pub)) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> snapshot = agentPublicationService.getAgentSnapshot(pubId);
            return ResponseEntity.ok(snapshot);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error fetching agent snapshot", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch agent snapshot"));
        }
    }

    // ========================================================================
    // Standalone resource publication endpoints (TABLE / INTERFACE / SKILL)
    // ========================================================================

    /**
     * Publish a standalone resource (TABLE / INTERFACE / SKILL).
     * Mirrors the internal endpoint - exposed here so the frontend (which talks
     * through the gateway, not the internal client) can drive share UIs.
     */
    @PostMapping("/publish-resource")
    public ResponseEntity<?> publishResource(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @RequestBody Map<String, Object> request) {
        try {
            if (isViewerRole(organizationRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "VIEWER role cannot modify publications"));
            }
            ResponseEntity<?> denied = denyIfResourceWriteRestricted(tenantId, organizationId, organizationRole,
                    resourceRestrictionType(parsePublicationTypeOrNull(request.get("type"))),
                    request.get("resourceId") != null ? request.get("resourceId").toString() : null);
            if (denied != null) return denied;
            WorkflowPublicationEntity pub = resourcePublicationService.publishResource(request, tenantId, organizationId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", pub.getId().toString());
            result.put("title", pub.getTitle());
            result.put("status", pub.getStatus() != null ? pub.getStatus().name() : null);
            result.put("type", pub.getPublicationType() != null ? pub.getPublicationType().name() : null);
            result.put("resourceId", pub.getResourceId());
            result.put("visibility", pub.getVisibility() != null ? pub.getVisibility().name() : null);
            result.put("creditsPerUse", pub.getCreditsPerUse());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (PublicationPendingReviewException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to publish resource", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/unpublish-resource/{type}/{resourceId}")
    public ResponseEntity<?> unpublishResource(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @PathVariable String type,
            @PathVariable String resourceId) {
        try {
            if (isViewerRole(organizationRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "VIEWER role cannot modify publications"));
            }
            WorkflowPublicationEntity.PublicationType parsed =
                    WorkflowPublicationEntity.PublicationType.valueOf(type.toUpperCase());
            ResponseEntity<?> denied = denyIfResourceWriteRestricted(
                    tenantId, organizationId, organizationRole, resourceRestrictionType(parsed), resourceId);
            if (denied != null) return denied;
            resourcePublicationService.unpublishResource(parsed, resourceId, tenantId, organizationId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (PublicationPendingReviewException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to unpublish resource", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/is-resource-published/{type}/{resourceId}")
    public ResponseEntity<Map<String, Object>> isResourcePublished(
            @PathVariable String type,
            @PathVariable String resourceId) {
        try {
            WorkflowPublicationEntity.PublicationType parsed =
                    WorkflowPublicationEntity.PublicationType.valueOf(type.toUpperCase());
            Map<String, Object> status = resourcePublicationService.getResourcePublicationStatus(parsed, resourceId);
            return ResponseEntity.ok(status);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Acquire a standalone resource publication (TABLE / INTERFACE / SKILL).
     * Clones the underlying resource - and any resources it references - into
     * the caller's tenant.
     */
    @PostMapping("/acquire-resource/{publicationId}")
    public ResponseEntity<?> acquireResource(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String organizationRole,
            @PathVariable String publicationId) {
        try {
            // Acquire clones the resource into the caller's workspace - a create/write
            // action, so a read-only VIEWER must not be able to install into an org.
            if (isViewerRole(organizationRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "VIEWER role cannot acquire publications"));
            }
            UUID pubId = UUID.fromString(publicationId);
            Map<String, Object> result = resourcePublicationService.acquireResource(pubId, tenantId, organizationId);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to acquire resource publication {}", publicationId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Per-type marketplace list - any of TABLE / INTERFACE / SKILL / WORKFLOW.
     * AGENT has its own endpoint ({@code /marketplace/agents}) kept for
     * backwards-compatibility.
     */
    @GetMapping("/marketplace/by-type/{type}")
    public ResponseEntity<?> getMarketplaceByType(
            @PathVariable String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            size = Math.min(Math.max(size, 1), 100);
            page = Math.max(page, 0);
            String normalized = type.toUpperCase();
            // Validate against the enum so typos don't silently return zero rows.
            WorkflowPublicationEntity.PublicationType.valueOf(normalized);
            Page<PublicationListItem> publications = listQueryService.findMarketplaceByType(normalized, page, size);
            List<Map<String, Object>> content = publications.getContent().stream()
                    .map(PublicationListItem::toResponseMap)
                    .toList();
            enrichOwnedByMe(content, userId, organizationId);
            return ResponseEntity.ok(Map.of(
                    "content", content,
                    "totalElements", publications.getTotalElements(),
                    "totalPages", publications.getTotalPages(),
                    "page", publications.getNumber(),
                    "size", publications.getSize()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown publication type: " + type));
        } catch (Exception e) {
            logger.error("Failed to list marketplace by type {}", type, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to fetch marketplace"));
        }
    }

    /**
     * Return just the landing-interface snippet of a publication so the
     * marketplace card / detail page can render the preview iframe without
     * loading the entire planSnapshot.
     *
     * <p>Resolution per publication type:
     * <ul>
     *   <li>{@code INTERFACE}  - planSnapshot is itself the interface ({html,css,js}Template)</li>
     *   <li>{@code TABLE/SKILL} - planSnapshot.landingInterface</li>
     *   <li>{@code AGENT}      - agentSnapshot.landingInterface</li>
     *   <li>{@code WORKFLOW}   - planSnapshot.landingInterface when present, else empty</li>
     * </ul>
     */
    @GetMapping("/by-id/{publicationId}/landing-snapshot")
    public ResponseEntity<?> getLandingSnapshot(@PathVariable String publicationId,
                                                 @RequestHeader(value = "X-User-ID", required = false) String requestingUserId,
                                                 @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            Optional<WorkflowPublicationEntity> maybePub = publicationService.getPublicationById(pubId);
            if (maybePub.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            WorkflowPublicationEntity pub = maybePub.get();
            boolean isOwner = publicationService.isCallerInOwnerScope(pub, requestingUserId, organizationId);
            if (!isOwner && !isAnonymouslyReadable(pub)) {
                return ResponseEntity.notFound().build();
            }
            WorkflowPublicationEntity.PublicationType type = pub.getPublicationType();

            Map<String, Object> landing = null;
            if (type == WorkflowPublicationEntity.PublicationType.INTERFACE) {
                Map<String, Object> plan = pub.getPlanSnapshot();
                if (plan != null) {
                    landing = new LinkedHashMap<>();
                    landing.put("htmlTemplate", plan.get("htmlTemplate"));
                    landing.put("cssTemplate", plan.get("cssTemplate"));
                    landing.put("jsTemplate", plan.get("jsTemplate"));
                    landing.put("interfaceType", plan.get("interfaceType"));
                    landing.put("data", plan.get("data"));
                }
            } else if (type == WorkflowPublicationEntity.PublicationType.AGENT) {
                Map<String, Object> snap = pub.getAgentSnapshot();
                Object li = snap != null ? snap.get("landingInterface") : null;
                if (li instanceof Map<?, ?> m) {
                    landing = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) landing.put(e.getKey().toString(), e.getValue());
                }
            } else {
                // WORKFLOW (and others) - read landingInterface ONLY from the
                // frozen plan_snapshot. Phase B.8 contract: marketplace never
                // touches the publisher's live tenant. Older publications that
                // don't embed landingInterface return null and the frontend
                // shows the generic preview.
                Map<String, Object> plan = pub.getPlanSnapshot();
                Object li = plan != null ? plan.get("landingInterface") : null;
                if (li instanceof Map<?, ?> m) {
                    landing = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) landing.put(e.getKey().toString(), e.getValue());
                }
            }

            // PR2 round-6: apply HMAC rewriter to landing.data so FileRefs in the
            // interface payload become signed proxy URLs the anonymous marketplace
            // viewer can resolve. Without this, raw FileRef Maps land in the
            // HTML template as `<img src='{"_type":"file",...}'>` (broken).
            if (landing != null) {
                landing = fileRefRewriter.rewriteLanding(landing, pub);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("publicationId", pub.getId().toString());
            response.put("type", type != null ? type.name() : null);
            response.put("title", pub.getTitle());
            response.put("description", pub.getDescription());
            response.put("landing", landing); // may be null (e.g. older workflow publications)
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to get landing snapshot for {}", publicationId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to fetch landing snapshot"));
        }
    }

    /**
     * Render a publication's frozen showcase clone - public, marketplace-only.
     *
     * <p>Anonymous visitors never pass a {@code runId} / {@code interfaceId};
     * the server reads those from the publication entity and forwards them to
     * the orchestrator. Authorisation layers:
     * <ol>
     *   <li>Route is under the {@code /by-id/} allowlist prefix (no JWT needed).</li>
     *   <li>Publication must be {@code status=ACTIVE} and {@code visibility=PUBLIC}.</li>
     *   <li>{@code showcaseRunId} must exist (hasShowcase).</li>
     *   <li>Orchestrator re-validates that the run is a {@code showcase_*} clone
     *       and that its {@code publication_id} matches - defense in depth.</li>
     * </ol>
     *
     * <p>The response shape matches {@code /api/interfaces/{id}/render} so the
     * frontend can swap endpoints without changing its rendering code.
     */
    @GetMapping("/by-id/{publicationId}/showcase-render")
    public ResponseEntity<?> renderPublicShowcase(
            @PathVariable String publicationId,
            @RequestParam(required = false) String interfaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "1") int size,
            @RequestParam(required = false) Integer epoch) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            Optional<WorkflowPublicationEntity> maybePub = publicationService.getPublicationById(pubId);
            if (maybePub.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            WorkflowPublicationEntity pub = maybePub.get();

            // Only ACTIVE + PUBLIC publications can expose their showcase to anonymous callers.
            // UNLISTED / PRIVATE / DRAFT / PENDING stay behind the auth'd /api/interfaces endpoint.
            if (pub.getStatus() != PublicationStatus.ACTIVE
                    || pub.getVisibility() != PublicationVisibility.PUBLIC) {
                logger.debug("[ShowcaseRender] Refused - publication {} status={} visibility={}",
                        publicationId, pub.getStatus(), pub.getVisibility());
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Publication is not publicly available"));
            }

            return renderShowcaseFromSnapshot(pub, interfaceId, page, size, epoch);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to render public showcase for {}", publicationId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to render showcase"));
        }
    }

    /**
     * Authenticated twin of {@link #renderPublicShowcase}. The anonymous
     * {@code /by-id/...} variant only serves ACTIVE+PUBLIC publications; this
     * one additionally lets the OWNER and an ACQUIRER (receipt-holder) read the
     * frozen showcase render even after the publisher unpublishes (INACTIVE) or
     * privatises (PRIVATE) it.
     *
     * <p>Why this exists: an installed-application card preview
     * ({@code ApplicationCard} -&gt; {@code ShowcasePreview}) renders an
     * acquired app via the publication's frozen showcase. Routing acquired
     * cards through the public endpoint made the preview 403 the moment the
     * publisher made the source publication non-public, so the card fell back
     * to the node-icon cover tile (looked like a "workflow view" with no
     * interface). The receipt lookup only runs when the publication is NOT
     * publicly readable, mirroring {@code getPublicationById}: the hot
     * marketplace path is unaffected and non-acquirers still get 403.</p>
     *
     * <p>Visibility note: like {@code getPublicationById}, an ACTIVE+UNLISTED
     * publication is treated as publicly readable here (link-only, readable by
     * UUID), so an authenticated caller can render it without a receipt - a
     * deliberately wider scope than the anonymous {@code /by-id} twin, which is
     * ACTIVE+PUBLIC only.</p>
     */
    @GetMapping("/{publicationId}/showcase-render")
    public ResponseEntity<?> renderShowcaseAuthenticated(
            @PathVariable String publicationId,
            @RequestHeader(value = "X-User-ID", required = false) String requestingUserId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam(required = false) String interfaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "1") int size,
            @RequestParam(required = false) Integer epoch) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            Optional<WorkflowPublicationEntity> maybePub = publicationService.getPublicationById(pubId);
            if (maybePub.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            WorkflowPublicationEntity pub = maybePub.get();
            boolean isOwner = publicationService.isCallerInOwnerScope(pub, requestingUserId, organizationId);
            boolean visibleToPublic = isAnonymouslyReadable(pub);
            boolean isAcquirer = !isOwner && !visibleToPublic
                    && publicationService.callerHoldsReceipt(pubId, requestingUserId, organizationId);
            if (!isOwner && !isAcquirer && !visibleToPublic) {
                logger.debug("[ShowcaseRender] Auth refused - publication {} status={} visibility={}",
                        publicationId, pub.getStatus(), pub.getVisibility());
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Publication is not available"));
            }
            return renderShowcaseFromSnapshot(pub, interfaceId, page, size, epoch);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to render showcase for {}", publicationId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to render showcase"));
        }
    }

    /**
     * Shared snapshot-render body for the public + authenticated showcase-render
     * endpoints. Reads ONLY from the publication's frozen {@code showcase_snapshot}
     * JSONB - never the publisher's live workflow/run - so it is safe to expose to
     * acquirers and anonymous visitors alike. The caller is responsible for the
     * visibility gate BEFORE invoking this.
     */
    private ResponseEntity<?> renderShowcaseFromSnapshot(WorkflowPublicationEntity pub,
                                                         String interfaceId,
                                                         int page,
                                                         int size,
                                                         Integer epoch) {
        if (pub.getShowcaseInterfaceId() == null
                || pub.getShowcaseRunId() == null
                || pub.getShowcaseRunId().isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // interfaceId is optional: defaults to the landing showcase interface.
        // Any interfaceId is allowed; the snapshot reader is scoped to this
        // publication's frozen render map and returns an empty template → 404
        // if the id doesn't resolve, so a caller cannot probe unrelated interfaces.
        String effectiveInterfaceId = (interfaceId != null && !interfaceId.isEmpty())
                ? interfaceId
                : pub.getShowcaseInterfaceId().toString();
        try {
            UUID.fromString(effectiveInterfaceId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid interfaceId"));
        }

        // Cap pagination so the endpoint stays cheap.
        int effectiveSize = size <= 0 ? 1 : Math.min(size, 10);
        int effectivePage = Math.max(0, page);

        // Strict snapshot-only mode: read ONLY from the publication's frozen
        // JSONB, never the publisher's live workflow / run. Missing snapshot →
        // 503 so an admin can backfill rather than silently leaking live state.
        if (!showcaseSnapshotReader.hasSnapshot(pub)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Snapshot not yet captured for this publication"));
        }
        Optional<Map<String, Object>> fromSnapshot = showcaseSnapshotReader.readInterfaceRender(
                pub, effectiveInterfaceId, effectivePage, effectiveSize, epoch);
        if (fromSnapshot.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = fromSnapshot.get();
        Object html = body.get("htmlTemplate");
        if (html == null || (html instanceof String s && s.isEmpty())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Public run-state read for a publication's frozen showcase clone.
     *
     * <p>Mirrors the auth'd {@code GET /api/v2/workflows/dag/runs/{runId}/state}
     * shape (plan, steps, edges, epochTimestamps, completed/failed/skipped
     * sets, seq, currentEpoch, totalDurationMs) so the same frontend
     * WorkflowRunManager can hydrate from it. Guards:
     * <ul>
     *   <li>Publication must be {@code status=ACTIVE} + {@code visibility=PUBLIC}.</li>
     *   <li>Orchestrator re-validates that {@code showcaseRunId} starts with
     *       {@code showcase_} and belongs to this publication (defense in depth).</li>
     * </ul>
     */
    /**
     * Public per-epoch status counts for a showcase clone. Mirrors
     * {@code GET /v2/workflows/dag/runs/{runId}/epochs/{epoch}/state} so the
     * frontend {@code useEpochStateViewing} hook can apply epoch-scoped
     * statusCounts (node/edge) without hitting the auth'd endpoint.
     */
    @GetMapping("/by-id/{publicationId}/epochs/{epoch}/state")
    public ResponseEntity<?> getPublicShowcaseEpochState(
            @PathVariable String publicationId,
            @PathVariable int epoch) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            Optional<WorkflowPublicationEntity> maybePub = publicationService.getPublicationById(pubId);
            if (maybePub.isEmpty()) return ResponseEntity.notFound().build();
            WorkflowPublicationEntity pub = maybePub.get();
            if (pub.getStatus() != PublicationStatus.ACTIVE
                    || pub.getVisibility() != PublicationVisibility.PUBLIC) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Publication is not publicly available"));
            }
            if (!showcaseSnapshotReader.hasSnapshot(pub)) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "Snapshot not yet captured for this publication"));
            }
            return showcaseSnapshotReader.readEpochState(pub, epoch)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to fetch public epoch-state for {}", publicationId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to fetch epoch-state"));
        }
    }

    /**
     * Public active-signal list for a specific epoch of a showcase clone.
     * Mirrors {@code GET /v2/workflows/dag/runs/{runId}/epochs/{epoch}/signals}.
     */
    @GetMapping("/by-id/{publicationId}/epochs/{epoch}/signals")
    public ResponseEntity<?> getPublicShowcaseEpochSignals(
            @PathVariable String publicationId,
            @PathVariable int epoch) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            Optional<WorkflowPublicationEntity> maybePub = publicationService.getPublicationById(pubId);
            if (maybePub.isEmpty()) return ResponseEntity.notFound().build();
            WorkflowPublicationEntity pub = maybePub.get();
            if (pub.getStatus() != PublicationStatus.ACTIVE
                    || pub.getVisibility() != PublicationVisibility.PUBLIC) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Publication is not publicly available"));
            }
            if (!showcaseSnapshotReader.hasSnapshot(pub)) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "Snapshot not yet captured for this publication"));
            }
            return showcaseSnapshotReader.readEpochSignals(pub, epoch)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to fetch public epoch-signals for {}", publicationId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to fetch epoch-signals"));
        }
    }

    /**
     * Public per-epoch (or global) aggregated-step list for a showcase clone.
     * Matches the auth'd {@code GET /v2/workflows/dag/instances/{runId}/steps/aggregated}.
     * The RunInfo panel calls this when the user clicks an epoch pill to
     * display the epoch-scoped step list.
     */
    @GetMapping("/by-id/{publicationId}/aggregated-steps")
    public ResponseEntity<?> getPublicShowcaseAggregatedSteps(
            @PathVariable String publicationId,
            @RequestParam(value = "epoch", required = false) Integer epoch) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            Optional<WorkflowPublicationEntity> maybePub = publicationService.getPublicationById(pubId);
            if (maybePub.isEmpty()) return ResponseEntity.notFound().build();
            WorkflowPublicationEntity pub = maybePub.get();
            if (pub.getStatus() != PublicationStatus.ACTIVE
                    || pub.getVisibility() != PublicationVisibility.PUBLIC) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Publication is not publicly available"));
            }
            if (!showcaseSnapshotReader.hasSnapshot(pub)) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "Snapshot not yet captured for this publication"));
            }
            return showcaseSnapshotReader.readAggregatedSteps(pub, epoch)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to fetch public aggregated-steps for {}", publicationId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to fetch aggregated-steps"));
        }
    }

    @GetMapping("/by-id/{publicationId}/run-state")
    public ResponseEntity<?> getPublicShowcaseRunState(@PathVariable String publicationId) {
        try {
            UUID pubId = UUID.fromString(publicationId);
            Optional<WorkflowPublicationEntity> maybePub = publicationService.getPublicationById(pubId);
            if (maybePub.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            WorkflowPublicationEntity pub = maybePub.get();

            if (pub.getStatus() != PublicationStatus.ACTIVE
                    || pub.getVisibility() != PublicationVisibility.PUBLIC) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Publication is not publicly available"));
            }
            if (!showcaseSnapshotReader.hasSnapshot(pub)) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "Snapshot not yet captured for this publication"));
            }
            return showcaseSnapshotReader.readRunState(pub)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to fetch public run-state for {}", publicationId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to fetch run-state"));
        }
    }

    private static boolean isViewerRole(String organizationRole) {
        return organizationRole != null && "VIEWER".equalsIgnoreCase(organizationRole.trim());
    }

    /**
     * Member write-restriction guard for an org-owned application (a published workflow).
     * Mirrors the canonical resource types (workflow, agent, datasource, interface, project,
     * file): ANY restriction - READ-only OR DENY - blocks writes, while OWNER/ADMIN bypass
     * (handled inside {@link OrgAccessGuard#canWrite}). The "application" restriction is keyed
     * on the publication id. No-op in personal scope (no org) or when the publication id is
     * unknown - the {@code isCallerInOwnerScope} check in the service stays the authoritative
     * owner gate. Returns a 403 {@link ResponseEntity} to short-circuit, or {@code null} to proceed.
     */
    private ResponseEntity<?> denyIfApplicationWriteRestricted(
            String tenantId, String organizationId, String organizationRole, String publicationId) {
        if (organizationId == null || organizationId.isBlank() || publicationId == null) {
            return null;
        }
        if (!orgAccessGuard.canWrite(organizationId, tenantId, "application", publicationId, organizationRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You do not have write access to this application"));
        }
        return null;
    }

    /**
     * Same member write-restriction guard as {@link #denyIfApplicationWriteRestricted}, but
     * resolving the publication id from a workflow id (for the delete / unpublish / re-publish
     * paths that are keyed on the workflow). Skips the DB lookup entirely in personal scope, and
     * is a no-op on first publish (no existing publication row to restrict).
     */
    private ResponseEntity<?> denyIfApplicationWriteRestrictedByWorkflow(
            String tenantId, String organizationId, String organizationRole, UUID workflowId) {
        if (organizationId == null || organizationId.isBlank()) {
            return null;
        }
        String publicationId = publicationService.getPublicationByWorkflowId(workflowId)
                .map(p -> p.getId().toString())
                .orElse(null);
        return denyIfApplicationWriteRestricted(tenantId, organizationId, organizationRole, publicationId);
    }

    /**
     * Member write-restriction guard for a standalone resource / agent publication. The org
     * restriction is keyed on the UNDERLYING resource - a published TABLE is a {@code datasource},
     * INTERFACE an {@code interface}, SKILL a {@code skill}, an agent publication an {@code agent} -
     * matching each resource's own CRUD enforcement (e.g. DataSourceService/InterfaceService/
     * AgentService all call {@code canWrite(org, user, "<type>", id, role)}), so a member who cannot
     * write the resource cannot publish or unpublish it. ANY restriction (READ-only or DENY) blocks;
     * OWNER/ADMIN bypass inside {@link OrgAccessGuard#canWrite}. No-op in personal scope or for a
     * non-restrictable type. Returns a 403 {@link ResponseEntity} to short-circuit, or {@code null}.
     */
    private ResponseEntity<?> denyIfResourceWriteRestricted(
            String tenantId, String organizationId, String organizationRole,
            String restrictionType, String resourceId) {
        if (organizationId == null || organizationId.isBlank()
                || restrictionType == null || resourceId == null || resourceId.isBlank()) {
            return null;
        }
        if (!orgAccessGuard.canWrite(organizationId, tenantId, restrictionType, resourceId, organizationRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "You do not have write access to this " + restrictionType));
        }
        return null;
    }

    /** Maps a standalone resource publication type to the underlying org-restriction resource type. */
    private static String resourceRestrictionType(WorkflowPublicationEntity.PublicationType type) {
        if (type == null) return null;
        return switch (type) {
            case TABLE -> "datasource";
            case INTERFACE -> "interface";
            case SKILL -> "skill";
            default -> null;
        };
    }

    private static WorkflowPublicationEntity.PublicationType parsePublicationTypeOrNull(Object raw) {
        if (raw == null) return null;
        try {
            return WorkflowPublicationEntity.PublicationType.valueOf(raw.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, String> toReplacementMap(java.util.List<ImageReplacementEntry> entries) {
        if (entries == null || entries.isEmpty()) return Map.of();
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (ImageReplacementEntry e : entries) {
            if (e.originalUrl != null && !e.originalUrl.isBlank()
                    && e.storageKey != null && !e.storageKey.isBlank()) {
                map.put(e.originalUrl, e.storageKey);
            }
        }
        return map;
    }

    // Request DTOs

    public static class PublishWorkflowRequest {
        public String workflowId;
        public String title;
        public String description;
        public String showcaseInterfaceId;  // Optional: UUID of the interface (present = application)
        public String showcaseRunId;        // Required for PUBLIC/UNLISTED, optional for PRIVATE
        public String categoryId;           // UUID of the category
        public Integer creditsPerUse;
        public String visibility;           // PUBLIC, PRIVATE, UNLISTED (defaults to PUBLIC)
        public Integer planVersion;         // Optional: specific version to publish (defaults to latest)
        public String displayMode;          // Optional: WORKFLOW, INTERFACE, APPLICATION (defaults to WORKFLOW)
        public Integer showcaseEpoch;       // V273 - publisher's chosen epoch for the marketplace preview (null = legacy multi-epoch view)
        public Boolean viaScreeningWizard;  // V274 - true when sent from the publish wizard (wizard handles audit log via /screening-decisions); false on MCP / scripted / S2S paths so the service auto-scans + logs SKIPPED rows
        public java.util.List<ImageReplacementEntry> imageReplacements;  // AI-generated replacement images: originalUrl → storageKey
    }

    public static class UpdatePublicationRequest {
        public String title;
        public String description;
        public String showcaseInterfaceId;  // Optional: UUID of the interface (present = application)
        public String showcaseRunId;        // Required for PUBLIC/UNLISTED, optional for PRIVATE
        public String categoryId;           // UUID of the category
        public Integer creditsPerUse;
        public String visibility;           // PUBLIC, PRIVATE, UNLISTED
        public String displayMode;          // Optional: WORKFLOW, INTERFACE, APPLICATION
        public Boolean clearShowcaseEpoch;  // V273 - clear the chosen epoch and use the multi-epoch showcase
        public Integer showcaseEpoch;       // V273 - chosen epoch (null = no change; on update, omit to preserve existing)
        public Boolean viaScreeningWizard;  // V274 - same semantics as on PublishWorkflowRequest
        public java.util.List<ImageReplacementEntry> imageReplacements;
    }

    public static class ImageReplacementEntry {
        public String originalUrl;
        public String storageKey;
    }

    public static class SubmitReviewRequest {
        public Short rating;    // 1-5, nullable (comment-only submit)
        public String comment;  // Optional
    }

    public static class SubmitReplyRequest {
        public String comment;
    }
}

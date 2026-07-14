package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.agent.client.dto.AgentSkillDto;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.datasource.client.dto.DataSourceItemDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceCreateRequest;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.config.PaidTemplatesFeatureFlag;
import com.apimarketplace.publication.domain.PublicationReceiptEntity;
import com.apimarketplace.publication.domain.PublicationSnapshotVersionEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.PublicationSnapshotVersionRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.utils.CredentialKeyDetector;
import com.apimarketplace.publication.utils.WorkflowIconExtractor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
 * Service for managing workflow publications.
 * Refactored for publication-service: uses HTTP clients (OrchestratorInternalClient,
 * AgentClient) instead of direct repository access for orchestrator/agent entities.
 */
@Service
@Transactional
public class WorkflowPublicationService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowPublicationService.class);

    /**
     * Seeded system-default category ("automation", V300) applied when a workflow
     * publish/update omits a category. Every workflow publication must stay
     * categorized so the marketplace never shows an uncategorized application.
     */
    private static final UUID DEFAULT_CATEGORY_ID =
            UUID.fromString("a0000000-0000-4000-8000-000000000001");

    private final WorkflowPublicationRepository publicationRepository;
    private final PublicationSnapshotVersionRepository snapshotVersionRepository;
    private final PublicationReceiptRepository receiptRepository;
    private final PublicationReviewRepository reviewRepository;
    private final OrchestratorInternalClient orchestratorClient;
    private final AgentClient agentClient;
    private final InterfaceClient interfaceClient;
    private final DataSourceClient dataSourceClient;
    private final StorageBreakdownService breakdownService;
    private final ObjectMapper objectMapper;
    private final SnapshotCloneService snapshotCloneService;
    private final com.apimarketplace.auth.client.entitlement.EntitlementGuard entitlementGuard;
    private final AuthClient authClient;

    /**
     * Acquire-time avatar file copy (snapshot autonomy). Field-injected to spare the
     * many test constructions; null (unit tests) falls back to the publishable pass-through.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    AvatarFileCloneService avatarFileCloneService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PublicationAcquisitionHelper acquisitionHelper;

    /**
     * V274 audit-gap closer - for publishes that DON'T go through the
     * wizard's screening modal (MCP {@code workflow_publish}, {@code
     * agent_publish}, scripted republish, internal server-to-server),
     * auto-scan the captured snapshot and write SKIPPED rows so every
     * publication has audit-trail coverage. Wizard publishes set
     * {@code viaScreeningWizard=true} and skip the auto-scan because
     * the wizard already POSTs decisions via /screening-decisions.
     *
     * <p>Field-injected with {@code required=false} so existing test
     * fixtures that construct the service via the 13-arg constructor
     * keep working; tests can set the field reflectively when they
     * want to assert chokepoint behaviour.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.publication.screening.ImageScreeningService imageScreeningService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.publication.screening.ImageScreeningDecisionRepository imageScreeningDecisionRepository;

    public WorkflowPublicationService(WorkflowPublicationRepository publicationRepository,
                                       PublicationSnapshotVersionRepository snapshotVersionRepository,
                                       PublicationReceiptRepository receiptRepository,
                                       PublicationReviewRepository reviewRepository,
                                       OrchestratorInternalClient orchestratorClient,
                                       AgentClient agentClient,
                                       InterfaceClient interfaceClient,
                                       DataSourceClient dataSourceClient,
                                       StorageBreakdownService breakdownService,
                                       ObjectMapper objectMapper,
                                       SnapshotCloneService snapshotCloneService,
                                       com.apimarketplace.auth.client.entitlement.EntitlementGuard entitlementGuard,
                                       AuthClient authClient) {
        this.publicationRepository = publicationRepository;
        this.snapshotVersionRepository = snapshotVersionRepository;
        this.receiptRepository = receiptRepository;
        this.reviewRepository = reviewRepository;
        this.orchestratorClient = orchestratorClient;
        this.agentClient = agentClient;
        this.interfaceClient = interfaceClient;
        this.dataSourceClient = dataSourceClient;
        this.breakdownService = breakdownService;
        this.objectMapper = objectMapper;
        this.snapshotCloneService = snapshotCloneService;
        this.entitlementGuard = entitlementGuard;
        this.authClient = authClient;
    }

    /**
     * Resolve an effective organizationId for the acquirer, falling back to the
     * caller's default-personal org when the request didn't carry
     * X-Organization-ID. Post-V261 the receipt's organization_id is NOT NULL
     * (publication.publication_receipts), so every acquisition path MUST
     * resolve a non-blank value before persisting. Daemon / async paths and
     * CE→cloud server-to-server calls don't have a bound request, hence the
     * authClient fallback (mirrors CeDownloadController).
     *
     * @throws IllegalArgumentException when the user has no default
     *         organization (degenerate state - onboarding always creates one).
     */
    private String resolveAcquirerOrg(String tenantId, String organizationId, UUID publicationId) {
        if (organizationId != null && !organizationId.isBlank()) {
            return organizationId;
        }
        String resolved = authClient.getDefaultOrganizationIdForUser(tenantId);
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalArgumentException(
                    "organizationId required after V261 (tenantId=" + tenantId
                            + ", publicationId=" + publicationId
                            + ") - user has no default organization");
        }
        return resolved;
    }

    // ========================================================================
    // Publish / Update / Unpublish / Delete
    // ========================================================================

    /**
     * Resolve a category and copy its denormalized fields onto the publication.
     * A {@code null} categoryId falls back to {@link #DEFAULT_CATEGORY_ID}
     * ("automation") so a workflow publication is never left without a category.
     * Throws when the resolved category does not exist.
     */
    private void applyCategory(WorkflowPublicationEntity publication, UUID categoryId) {
        UUID effectiveId = categoryId != null ? categoryId : DEFAULT_CATEGORY_ID;
        Map<String, Object> category = orchestratorClient.getCategoryById(effectiveId);
        if (category == null) {
            throw new IllegalArgumentException("Category not found: " + effectiveId);
        }
        publication.setCategoryId(effectiveId);
        publication.setCategorySlug((String) category.get("slug"));
        publication.setCategoryName((String) category.get("name"));
        publication.setCategoryIconSlug((String) category.get("iconSlug"));
        publication.setCategoryColor((String) category.get("color"));
    }

    /**
     * Publish a workflow (create or update publication) - personal-scope overload.
     * Delegates to {@link #publishWorkflow(UUID, String, String, String, String,
     * UUID, String, UUID, Integer, String, String, String, PublicationVisibility,
     * Integer, DisplayMode)} with a {@code null} organizationId, preserving the
     * pre-#151 caller contract.
     */
    public WorkflowPublicationEntity publishWorkflow(
            UUID workflowId,
            String tenantId,
            String title,
            String description,
            UUID showcaseInterfaceId,
            String showcaseRunId,
            UUID categoryId,
            Integer creditsPerUse,
            PublicationVisibility visibility,
            Integer requestedPlanVersion,
            DisplayMode displayMode) {
        return publishWorkflow(workflowId, tenantId, null, title, description,
                showcaseInterfaceId, showcaseRunId, categoryId, creditsPerUse,
                visibility, requestedPlanVersion, displayMode, null, false, Map.of());
    }

    /**
     * Publish a workflow (create or update publication) - org-aware overload.
     *
     * <p>When {@code organizationId} is non-null/blank, the resulting row is
     * org-owned ({@code owner_type='ORG'}, {@code owner_id=organizationId}) so
     * every teammate of that organization sees it in their "Mes applications"
     * list. {@code publisher_id} stays equal to {@code tenantId} (audit only).
     * Otherwise the row is personal ({@code owner_type='USER'},
     * {@code owner_id=tenantId}).
     */
    @SuppressWarnings("unchecked")
    public WorkflowPublicationEntity publishWorkflow(
            UUID workflowId,
            String tenantId,
            String organizationId,
            String title,
            String description,
            UUID showcaseInterfaceId,
            String showcaseRunId,
            UUID categoryId,
            Integer creditsPerUse,
            PublicationVisibility visibility,
            Integer requestedPlanVersion,
            DisplayMode displayMode,
            Integer showcaseEpoch,
            boolean viaScreeningWizard,
            Map<String, String> imageReplacements) {

        // Validate workflow exists and belongs to tenant (via orchestrator)
        Map<String, Object> workflowData = orchestratorClient.getWorkflowForPublication(workflowId, tenantId, organizationId);
        if (workflowData == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        String workflowTenantId = (String) workflowData.get("tenantId");
        String workflowOrgId = (String) workflowData.get("organizationId");
        if (!ScopeGuard.isInStrictScope(tenantId, organizationId, workflowTenantId, workflowOrgId)) {
            throw new IllegalArgumentException("Workflow does not belong to tenant");
        }

        String workflowType = (String) workflowData.get("workflowType");
        if ("APPLICATION".equals(workflowType)) {
            throw new IllegalArgumentException("Cannot publish an APPLICATION workflow. Only WORKFLOW types can be published.");
        }

        // Showcase run is required for PUBLIC/UNLISTED publications (marketplace demo)
        // PRIVATE publications can be created without a run (personal app deployment)
        PublicationVisibility effectiveVisibility = visibility != null ? visibility : PublicationVisibility.PUBLIC;
        boolean hasShowcaseRun = showcaseRunId != null && !showcaseRunId.isEmpty();

        if (!hasShowcaseRun && effectiveVisibility != PublicationVisibility.PRIVATE) {
            throw new IllegalArgumentException("A successful run is required to publish a PUBLIC or UNLISTED workflow");
        }

        // Validate showcase run if provided. The orchestrator endpoint
        // enforces tenant ownership, rejects step-by-step runs, and reports
        // whether the run terminated successfully (only COMPLETED /
        // PARTIAL_SUCCESS are publishable - FAILED/CANCELLED/TIMEOUT runs
        // would showcase a broken state to anonymous marketplace visitors).
        if (hasShowcaseRun) {
            // Note: tenant guard for runs is enforced authoritatively in
            // ShowcaseSnapshotBuilder.capture (orchestrator) which fails the
            // publish if the run belongs to another tenant. This pre-check
            // returns the publishable / step-by-step / status flags so we
            // can fail fast with a friendly error before the heavier capture.
            Map<String, Object> runData = orchestratorClient.validateShowcaseRun(showcaseRunId, tenantId, organizationId);
            if (runData == null) {
                throw new IllegalArgumentException("Showcase run not found: " + showcaseRunId);
            }
            Boolean isStepByStep = (Boolean) runData.get("isStepByStep");
            if (Boolean.TRUE.equals(isStepByStep)) {
                throw new IllegalArgumentException("Cannot publish a step-by-step run. Please use an automatic run.");
            }
            Object publishableRaw = runData.get("publishable");
            if (publishableRaw instanceof Boolean publishable && !publishable) {
                Object status = runData.get("status");
                throw new IllegalArgumentException("Cannot publish a run with status " + status
                        + ". Only successful runs (COMPLETED or PARTIAL_SUCCESS) can be published.");
            }
        }

        // Validate display mode: INTERFACE/APPLICATION require an interface
        DisplayMode effectiveDisplayMode = displayMode != null ? displayMode : DisplayMode.WORKFLOW;
        if (showcaseInterfaceId == null && effectiveDisplayMode != DisplayMode.WORKFLOW) {
            throw new IllegalArgumentException("INTERFACE and APPLICATION display modes require an interface");
        }

        // PUBLIC workflows are free - paid workflows belong in PRIVATE (personal deployment)
        // or UNLISTED (link-only sharing). This prevents charging marketplace users for
        // content that went through admin review purely as a paid funnel.
        int effectiveCredits = creditsPerUse != null ? creditsPerUse : 0;
        if (effectiveVisibility == PublicationVisibility.PUBLIC && effectiveCredits > 0) {
            throw new IllegalArgumentException(
                    "PUBLIC workflow publications must be free (creditsPerUse=0). "
                    + "Use PRIVATE or UNLISTED visibility for paid workflows.");
        }
        // Defense-in-depth: while paid templates are disabled platform-wide, reject
        // any creditsPerUse > 0 regardless of visibility - the frontend greys the
        // price input, but a direct curl POST must not slip through. Existing paid
        // publications are grandfathered (this gate runs only on new publishes /
        // republishes; the DB column itself is not migrated).
        if (effectiveCredits > 0 && !PaidTemplatesFeatureFlag.isEnabled()) {
            throw new IllegalArgumentException(
                    "Paid templates are coming soon. All new publications must be free "
                    + "(creditsPerUse=0) until the feature ships.");
        }

        // Check if publication already exists
        Optional<WorkflowPublicationEntity> existingPublication = publicationRepository.findByWorkflowId(workflowId);

        WorkflowPublicationEntity publication;
        boolean isRepublish = existingPublication.isPresent();
        // Plan cap on PUBLISHING (distinct from acquiring): enforce only on the FIRST
        // publish of a workflow - re-publish updates the existing row and must not count
        // again (mirrors the acquire first-time-only check). Cloud-only; EntitlementGuard
        // no-ops in CE-free (unlimited). NULL plan limit = unlimited.
        if (!isRepublish && entitlementGuard != null) {
            WorkflowPublicationEntity.OwnerType ownerType =
                    (organizationId != null && !organizationId.isBlank())
                            ? WorkflowPublicationEntity.OwnerType.ORG
                            : WorkflowPublicationEntity.OwnerType.USER;
            String ownerId = (organizationId != null && !organizationId.isBlank())
                    ? organizationId : tenantId;
            entitlementGuard.check(tenantId,
                    com.apimarketplace.auth.client.entitlement.ResourceType.PUBLICATION,
                    () -> publicationRepository.countByOwnerTypeAndOwnerId(ownerType, ownerId));
        }
        if (isRepublish) {
            publication = existingPublication.get();
            // Block re-publish while pending review
            if (publication.getStatus() == PublicationStatus.PENDING_REVIEW) {
                throw new PublicationPendingReviewException("Cannot re-publish while publication is pending review. Please wait for admin approval.");
            }
            // NOTE: previously we wiped publisher application runs HERE - that
            // was non-transactional and irreversible. If the snapshot capture
            // later threw, runs were gone forever and the @Transactional
            // rollback couldn't restore them. The cleanup is now deferred to
            // after the new snapshot is durably captured (see below, ~line 285).
            logger.info("Re-publishing for workflow: {} (cleanup deferred until capture succeeds)", workflowId);
        } else {
            // Create new publication
            publication = new WorkflowPublicationEntity();
            publication.setWorkflowId(workflowId);
            publication.setPublisherId(tenantId);
            logger.info("Creating new publication for workflow: {} (org={})", workflowId,
                    organizationId != null && !organizationId.isBlank() ? organizationId : "personal");
        }

        // Assign owning scope (#151). For re-publish the scope is fixed at
        // first-publish time and never flipped - flipping would re-route
        // visibility mid-flight and could orphan rows. assignOwnerFromContext
        // is idempotent on re-publish only when the requested scope matches
        // the existing one; the guard below enforces that.
        if (!publication.hasAssignedOwnerScope()) {
            publication.assignOwnerFromContext(tenantId, organizationId);
        } else {
            WorkflowPublicationEntity.OwnerType requestedType =
                    (organizationId != null && !organizationId.isBlank())
                            ? WorkflowPublicationEntity.OwnerType.ORG
                            : WorkflowPublicationEntity.OwnerType.USER;
            String requestedId = (organizationId != null && !organizationId.isBlank())
                    ? organizationId : tenantId;
            if (publication.getOwnerType() != requestedType
                    || !requestedId.equals(publication.getOwnerId())) {
                throw new IllegalArgumentException(
                        "Cannot change publication ownership scope on re-publish "
                        + "(was " + publication.getOwnerType() + "/" + publication.getOwnerId()
                        + ", requested " + requestedType + "/" + requestedId + ")");
            }
        }

        // Every workflow publication must carry a category - apply the one
        // provided, or default to "automation" when omitted (denormalized for
        // cross-schema isolation).
        applyCategory(publication, categoryId);

        // Update fields
        publication.setTitle(title);
        publication.setDescription(description);
        publication.setShowcaseInterfaceId(showcaseInterfaceId);
        publication.setShowcaseRunId(showcaseRunId);
        // V273 - null on first publish means "no preference, show all
        // captured epochs" (reader falls back to multi-epoch view). On
        // re-publish, null means "the caller didn't forward the pin, leave
        // the existing value alone" - mirroring the update path so the
        // publisher's pin survives a republish (e.g. agent MCP, scripted
        // republish) that doesn't carry showcaseEpoch through.
        if (showcaseEpoch != null) {
            publication.setShowcaseChosenEpoch(showcaseEpoch);
        }
        publication.setCreditsPerUse(creditsPerUse != null ? creditsPerUse : 0);
        // Frontend-supplied publisherName/Email/AvatarUrl are ignored -
        // identity is resolved server-side via AuthClient at every
        // (re)publish. See PublisherProfileSnapshotter for the rule.
        PublisherProfileSnapshotter.snapshotInto(publication, authClient, tenantId);
        publication.setVisibility(effectiveVisibility);
        publication.setDisplayMode(effectiveDisplayMode);
        if (effectiveVisibility == PublicationVisibility.PRIVATE) {
            publication.setStatus(PublicationStatus.ACTIVE);
        } else {
            publication.setStatus(PublicationStatus.PENDING_REVIEW);
            publication.setReviewerId(null);
            publication.setReviewedAt(null);
            publication.setRejectionReason(null);
        }

        // Snapshot the plan from the requested version, or fall back to current plan
        Map<String, Object> planSnapshot;
        Integer versionToStore;
        if (requestedPlanVersion != null) {
            Map<String, Object> versionData = orchestratorClient.getPlanVersion(workflowId, requestedPlanVersion, tenantId);
            if (versionData == null) {
                throw new IllegalArgumentException("Version " + requestedPlanVersion + " not found for workflow " + workflowId);
            }
            planSnapshot = (Map<String, Object>) versionData.get("plan");
            versionToStore = requestedPlanVersion;
        } else {
            planSnapshot = (Map<String, Object>) workflowData.get("plan");
            versionToStore = orchestratorClient.getLatestPlanVersion(workflowId, tenantId);
        }
        if (planSnapshot == null || planSnapshot.isEmpty()) {
            throw new IllegalArgumentException("Workflow has no plan to publish");
        }

        // Enrich, compute resource counts, and set the public plan snapshot.
        // Keep a separate owner-only copy before credential scrubbing so the
        // publisher's APPLICATION workflow preserves exact credential pins.
        Map<String, Object> ownerApplicationSnapshot =
                enrichAndSetPlanSnapshot(publication, planSnapshot, tenantId, organizationId);
        publication.setPlanVersion(versionToStore);

        WorkflowPublicationEntity saved = publicationRepository.save(publication);
        logger.info("Publication saved with ID: {}", saved.getId());

        // Save versioned snapshot for history/rollback (after save, so ID is guaranteed)
        saveSnapshotVersion(saved, saved.getPlanSnapshot());
        publicationRepository.save(saved);

        // Track storage for the publication
        breakdownService.trackSave(tenantId, "PUBLICATIONS", estimatePublicationSize(saved));

        // Capture the complete frozen view of the source run on the publication
        // entity itself. Marketplace reads target this JSONB rather than the
        // orchestrator, so the source run keeps its own life cycle (no clone).
        // Strict mode: this throws on failure → @Transactional rollback wipes
        // the (still un-cleaned) publication row. Crucially, we do this BEFORE
        // any destructive cleanup so a capture failure never destroys state
        // we couldn't restore.
        if (hasShowcaseRun) {
            captureAndStoreShowcaseSnapshot(saved, showcaseRunId, tenantId, organizationId, viaScreeningWizard, imageReplacements);
            validateShowcaseEpochSelection(saved, saved.getShowcaseChosenEpoch());
        } else if (showcaseEpoch != null) {
            validateShowcaseEpochSelection(saved, showcaseEpoch);
        }

        // Capture succeeded - NOW it's safe to wipe the publisher's old
        // application runs (re-publish only). If this fails the snapshot is
        // already durable; cleanup is best-effort and won't be retried, but
        // the publication is in a consistent state.
        if (isRepublish) {
            try {
                orchestratorClient.cleanupApplicationRuns(workflowId, saved.getId().toString(), tenantId);
            } catch (Exception e) {
                logger.warn("[Re-publish] cleanup of old application runs failed for workflow={} pub={}: {}",
                        workflowId, saved.getId(), e.getMessage());
            }
        }

        // Create or update the publisher's APPLICATION workflow
        createOrUpdatePublisherApplication(tenantId, saved, ownerApplicationSnapshot);

        return saved;
    }

    /**
     * Create or update the publisher's APPLICATION workflow for a publication.
     * On first publish: creates a new APPLICATION workflow with basePlan + plan.
     * On re-publish: cleans up old runs so the application starts fresh.
     */
    private void createOrUpdatePublisherApplication(String tenantId,
                                                    WorkflowPublicationEntity publication,
                                                    Map<String, Object> ownerApplicationSnapshot) {
        try {
            Map<String, Object> enrichedSnapshot = ownerApplicationSnapshot;
            if (enrichedSnapshot == null || enrichedSnapshot.isEmpty()) {
                logger.warn("No plan snapshot to create publisher APPLICATION for publication {}", publication.getId());
                return;
            }

            String ownerOrganizationId = ownerOrganizationId(publication);
            Map<String, Object> existingApp = ownerOrganizationId != null
                    ? orchestratorClient.findBySourcePublication(publication.getId(), tenantId, ownerOrganizationId)
                    : orchestratorClient.findBySourcePublication(publication.getId(), tenantId);
            String existingAppId = existingApp != null && existingApp.get("id") != null
                    ? existingApp.get("id").toString()
                    : null;

            if (existingAppId != null && !existingAppId.isBlank()) {
                // Re-publish: clean up old runs AND push the fresh basePlan /
                // plan / nodeIcons so the publisher's APPLICATION reflects
                // the new snapshot. Without the refresh the application
                // would keep running the old publish-time plan even after
                // we re-published with new interfaces / mappings.
                UUID appUuid = UUID.fromString(existingAppId);
                orchestratorClient.cleanupApplicationRuns(
                        appUuid, publication.getId().toString(), tenantId);

                Map<String, Object> basePlanCopy = objectMapper.convertValue(enrichedSnapshot,
                        new TypeReference<Map<String, Object>>() {});
                Map<String, Object> planCopy = objectMapper.convertValue(enrichedSnapshot,
                        new TypeReference<Map<String, Object>>() {});
                Map<String, Object> refreshRequest = new HashMap<>();
                refreshRequest.put("title", publication.getTitle());
                refreshRequest.put("description", publication.getDescription());
                refreshRequest.put("basePlan", basePlanCopy);
                refreshRequest.put("plan", planCopy);
                refreshRequest.put("nodeIcons", publication.getNodeIcons());
                if (ownerOrganizationId != null) {
                    refreshRequest.put("organizationId", ownerOrganizationId);
                }
                Map<String, Object> refreshed = orchestratorClient.refreshApplicationFromPublication(
                        appUuid, refreshRequest, tenantId);
                if (refreshed != null) {
                    logger.info("Refreshed publisher APPLICATION {} for publication {} (basePlan + runs cleanup)",
                            existingAppId, publication.getId());
                } else {
                    logger.warn("Refresh of publisher APPLICATION {} returned null for publication {}",
                            existingAppId, publication.getId());
                }
            } else {
                // First publish: create APPLICATION workflow
                Map<String, Object> basePlanCopy = objectMapper.convertValue(enrichedSnapshot,
                        new TypeReference<Map<String, Object>>() {});
                Map<String, Object> planCopy = objectMapper.convertValue(enrichedSnapshot,
                        new TypeReference<Map<String, Object>>() {});

                Map<String, Object> createRequest = new HashMap<>();
                createRequest.put("title", publication.getTitle());
                createRequest.put("description", publication.getDescription());
                createRequest.put("plan", planCopy);
                createRequest.put("basePlan", basePlanCopy);
                createRequest.put("sourcePublicationId", publication.getId().toString());
                createRequest.put("nodeIcons", publication.getNodeIcons());
                if (ownerOrganizationId != null) {
                    createRequest.put("organizationId", ownerOrganizationId);
                }

                Map<String, Object> result = orchestratorClient.createApplicationWorkflow(createRequest, tenantId);
                if (result != null) {
                    logger.info("Created publisher APPLICATION {} for publication {}",
                            result.get("id"), publication.getId());
                }
            }
        } catch (Exception e) {
            logger.error("Failed to create/update publisher APPLICATION for publication {}: {}",
                    publication.getId(), e.getMessage(), e);
        }
    }

    /**
     * Update publication info - personal-scope overload (no org context).
     */
    public WorkflowPublicationEntity updatePublicationInfo(
            UUID publicationId,
            String tenantId,
            String title,
            String description,
            UUID showcaseInterfaceId,
            String showcaseRunId,
            UUID categoryId,
            Integer creditsPerUse,
            PublicationVisibility visibility,
            DisplayMode displayMode) {
        return updatePublicationInfo(publicationId, tenantId, null, title, description,
                showcaseInterfaceId, showcaseRunId, categoryId, creditsPerUse,
                visibility, displayMode, null, false, false, Map.of());
    }

    /**
     * Update publication info and re-snapshot the plan from the current workflow state.
     * Org-aware: every teammate of the owning organization may update an ORG-owned
     * publication; for USER-owned rows only the owner may mutate.
     *
     * <p><b>Publisher columns are intentionally NOT refreshed</b> by this path -
     * {@code publisher_id / publisher_name / publisher_email / publisher_avatar_url}
     * are written exclusively at first publish and at republish via
     * {@link #publishWorkflow}. A PUT here edits metadata (title, description,
     * category, showcase, visibility, pricing) without re-asserting publisher
     * identity, which is the snapshot-model contract: identity freezes at
     * publish time and only refreshes on a publish event. This also preserves
     * any out-of-band publisher swap operators may have applied between
     * publishes.
     */
    @SuppressWarnings("unchecked")
    public WorkflowPublicationEntity updatePublicationInfo(
            UUID publicationId,
            String tenantId,
            String organizationId,
            String title,
            String description,
            UUID showcaseInterfaceId,
            String showcaseRunId,
            UUID categoryId,
            Integer creditsPerUse,
            PublicationVisibility visibility,
            DisplayMode displayMode,
            Integer showcaseEpoch,
            boolean clearShowcaseEpoch,
            boolean viaScreeningWizard,
            Map<String, String> imageReplacements) {

        WorkflowPublicationEntity publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalArgumentException("Publication not found: " + publicationId));

        if (!isCallerInOwnerScope(publication, tenantId, organizationId)) {
            throw new IllegalArgumentException("Publication does not belong to tenant");
        }

        // Determine effective visibility (new value if provided, otherwise keep existing)
        PublicationVisibility effectiveVisibility = visibility != null ? visibility : publication.getVisibility();
        boolean hasShowcaseRun = showcaseRunId != null && !showcaseRunId.isEmpty();

        // Showcase run is required for PUBLIC/UNLISTED (marketplace demo)
        if (!hasShowcaseRun && effectiveVisibility != PublicationVisibility.PRIVATE) {
            throw new IllegalArgumentException("A successful run is required for PUBLIC or UNLISTED publications");
        }

        // Validate showcase run if provided. The orchestrator endpoint
        // enforces tenant ownership, rejects step-by-step runs, and reports
        // whether the run terminated successfully (only COMPLETED /
        // PARTIAL_SUCCESS are publishable - FAILED/CANCELLED/TIMEOUT runs
        // would showcase a broken state to anonymous marketplace visitors).
        if (hasShowcaseRun) {
            // Note: tenant guard for runs is enforced authoritatively in
            // ShowcaseSnapshotBuilder.capture (orchestrator) which fails the
            // publish if the run belongs to another tenant. This pre-check
            // returns the publishable / step-by-step / status flags so we
            // can fail fast with a friendly error before the heavier capture.
            Map<String, Object> runData = orchestratorClient.validateShowcaseRun(showcaseRunId, tenantId, organizationId);
            if (runData == null) {
                throw new IllegalArgumentException("Showcase run not found: " + showcaseRunId);
            }
            Boolean isStepByStep = (Boolean) runData.get("isStepByStep");
            if (Boolean.TRUE.equals(isStepByStep)) {
                throw new IllegalArgumentException("Cannot publish a step-by-step run. Please use an automatic run.");
            }
            Object publishableRaw = runData.get("publishable");
            if (publishableRaw instanceof Boolean publishable && !publishable) {
                Object status = runData.get("status");
                throw new IllegalArgumentException("Cannot publish a run with status " + status
                        + ". Only successful runs (COMPLETED or PARTIAL_SUCCESS) can be published.");
            }
        }

        // Validate display mode
        DisplayMode effectiveDisplayMode = displayMode != null ? displayMode : publication.getDisplayMode();
        if (showcaseInterfaceId == null && effectiveDisplayMode != DisplayMode.WORKFLOW) {
            throw new IllegalArgumentException("INTERFACE and APPLICATION display modes require an interface");
        }

        // PUBLIC workflows must be free - mirror the publishWorkflow() rule so the PUT update
        // path can't flip visibility to PUBLIC while preserving creditsPerUse > 0.
        int effectiveCreditsUpdate = creditsPerUse != null ? creditsPerUse : publication.getCreditsPerUse();
        if (effectiveVisibility == PublicationVisibility.PUBLIC && effectiveCreditsUpdate > 0) {
            throw new IllegalArgumentException(
                    "PUBLIC workflow publications must be free (creditsPerUse=0). "
                    + "Use PRIVATE or UNLISTED visibility for paid workflows.");
        }
        // Defense-in-depth: same gate as publishWorkflow - block raising the price
        // above 0 on an existing publication while paid templates are disabled.
        // Existing paid pubs (whose price was set before the feature was paused)
        // can still be updated as long as `creditsPerUse` is NOT being changed,
        // because we compare the EFFECTIVE post-update value: an update payload
        // that omits creditsPerUse keeps the stored value untouched (grandfathered).
        // Only an explicit raise from 0 → N or N → M is rejected.
        if (effectiveCreditsUpdate > 0 && !PaidTemplatesFeatureFlag.isEnabled()
                && (creditsPerUse != null && creditsPerUse > 0)) {
            throw new IllegalArgumentException(
                    "Paid templates are coming soon. You can keep the existing price on this "
                    + "publication, but new or modified prices are not allowed until the "
                    + "feature ships.");
        }

        // Detect showcase source changes BEFORE overwriting. The selected
        // epoch is part of the frozen showcase snapshot contract: changing it
        // on an explicitly selected run/interface must recapture with the new
        // epoch filter. Without a selected run, the existing snapshot is the
        // only source of truth and the epoch is validated against it below.
        Integer oldShowcaseEpoch = publication.getShowcaseChosenEpoch();
        boolean showcaseSourceChanged = !Objects.equals(showcaseInterfaceId, publication.getShowcaseInterfaceId())
                || !Objects.equals(showcaseRunId, publication.getShowcaseRunId());
        boolean showcaseEpochChanged = hasShowcaseRun && (clearShowcaseEpoch
                ? oldShowcaseEpoch != null
                : showcaseEpoch != null && !Objects.equals(showcaseEpoch, oldShowcaseEpoch));
        boolean showcaseChanged = showcaseSourceChanged || showcaseEpochChanged;

        // Update category only when one is provided (denormalized for cross-schema
        // isolation). A null categoryId leaves the existing category untouched -
        // clearing is no longer possible, so every publication keeps its category.
        // New publishes default to "automation" in publishWorkflow().
        if (categoryId != null) {
            applyCategory(publication, categoryId);
        }

        publication.setTitle(title);
        publication.setDescription(description);
        publication.setShowcaseInterfaceId(showcaseInterfaceId);
        // Grandfathering safety net: when paid templates are disabled, a wizard
        // re-save that sends creditsPerUse=0 on a publication that ALREADY has
        // creditsPerUse > 0 would silently destroy the publisher's legacy price.
        // Preserve the stored value instead so accidental re-saves never erase
        // grandfathered pricing. Explicit clear-to-free is still possible after
        // the feature flag flips (or via a future admin override).
        Integer creditsToWrite = creditsPerUse;
        if (creditsPerUse != null && creditsPerUse == 0
                && !PaidTemplatesFeatureFlag.isEnabled()
                && publication.getCreditsPerUse() != null
                && publication.getCreditsPerUse() > 0) {
            creditsToWrite = publication.getCreditsPerUse();
        }
        publication.setCreditsPerUse(creditsToWrite != null ? creditsToWrite : 0);
        publication.setVisibility(visibility != null ? visibility : publication.getVisibility());
        publication.setDisplayMode(effectiveDisplayMode);

        // Every re-share re-enters moderation. Mirrors publishWorkflow(): a
        // PUBLIC/UNLISTED update returns to PENDING_REVIEW (reviewer state
        // cleared) so any content/metadata change is re-approved before it is
        // visible on the marketplace again; PRIVATE goes straight to ACTIVE
        // (no review path). Without this, an already-approved publication kept
        // its ACTIVE status across updates - letting un-reviewed content ship
        // live. The marketplace listing (status='ACTIVE' filter) drops the app
        // until re-approval, which is the intended behaviour.
        if (effectiveVisibility == PublicationVisibility.PRIVATE) {
            publication.setStatus(PublicationStatus.ACTIVE);
        } else {
            publication.setStatus(PublicationStatus.PENDING_REVIEW);
            publication.setReviewerId(null);
            publication.setReviewedAt(null);
            publication.setRejectionReason(null);
        }

        // Capture the legacy showcase clone id BEFORE we overwrite showcaseRunId.
        // Without this, the deletion branch below reads the *new* run id and the
        // old clone-row leaks forever in publisher.workflow_runs.
        String oldShowcaseRunId = publication.getShowcaseRunId();
        publication.setShowcaseRunId(showcaseRunId);
        // V273 - preserve existing chosen epoch when showcaseEpoch is omitted.
        // The wizard sends clearShowcaseEpoch=true when the publisher returns
        // to the multi-epoch showcase view.
        if (clearShowcaseEpoch) {
            publication.setShowcaseChosenEpoch(null);
        } else if (showcaseEpoch != null) {
            publication.setShowcaseChosenEpoch(showcaseEpoch);
        }

        // Always re-snapshot the plan from the current workflow state
        UUID workflowId = publication.getWorkflowId();
        Map<String, Object> workflowData = orchestratorClient.getWorkflowForPublication(workflowId, tenantId, organizationId);
        if (workflowData == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        Map<String, Object> currentPlan = (Map<String, Object>) workflowData.get("plan");
        Map<String, Object> ownerApplicationSnapshot = null;
        if (currentPlan != null && !currentPlan.isEmpty()) {
            ownerApplicationSnapshot = enrichAndSetPlanSnapshot(publication, currentPlan, tenantId, organizationId);
            Integer currentVersion = orchestratorClient.getLatestPlanVersion(workflowId, tenantId);
            publication.setPlanVersion(currentVersion);
        }

        // Re-capture the showcase snapshot if the source run changed. The
        // legacy showcase clone (if any) is deleted defensively so it stops
        // showing up as a phantom run in the publisher's tenant.
        boolean hasNewReplacements = imageReplacements != null && !imageReplacements.isEmpty();
        if (showcaseChanged) {
            if (oldShowcaseRunId != null && oldShowcaseRunId.startsWith("showcase_")) {
                try {
                    orchestratorClient.deleteClonedRun(oldShowcaseRunId);
                } catch (Exception e) {
                    logger.warn("Failed to delete old showcase run {}: {}", oldShowcaseRunId, e.getMessage());
                }
            }
            if (hasShowcaseRun) {
                captureAndStoreShowcaseSnapshot(publication, showcaseRunId, tenantId, organizationId, viaScreeningWizard, imageReplacements);
            } else {
                publication.setShowcaseSnapshot(null);
                publication.setShowcaseSnapshotCapturedAt(null);
            }
        } else if (hasNewReplacements && publication.getShowcaseSnapshot() != null) {
            applyImageReplacementsToExistingSnapshot(publication, imageReplacements, tenantId);
        }

        validateShowcaseEpochSelection(publication, publication.getShowcaseChosenEpoch());

        WorkflowPublicationEntity saved = publicationRepository.save(publication);

        // Save versioned snapshot for history/rollback
        if (currentPlan != null && !currentPlan.isEmpty()) {
            saveSnapshotVersion(saved, saved.getPlanSnapshot());
            publicationRepository.save(saved);
        }

        // Track storage size change
        breakdownService.trackSizeChange(tenantId, "PUBLICATIONS", estimatePublicationSize(saved));

        createOrUpdatePublisherApplication(tenantId, saved, ownerApplicationSnapshot);

        logger.info("Updated publication {} (metadata + plan re-snapshot)", publicationId);
        return saved;
    }

    /** Personal-scope overload - see {@link #unpublishWorkflow(UUID, String, String)}. */
    public WorkflowPublicationEntity unpublishWorkflow(UUID workflowId, String tenantId) {
        return unpublishWorkflow(workflowId, tenantId, null);
    }

    /**
     * Unpublish a workflow (set status to INACTIVE).
     * Org-aware: every teammate of the owning organization may unpublish an
     * ORG-owned publication; for USER-owned rows only the owner may mutate.
     */
    public WorkflowPublicationEntity unpublishWorkflow(UUID workflowId, String tenantId, String organizationId) {
        WorkflowPublicationEntity publication = publicationRepository.findByWorkflowId(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Publication not found for workflow: " + workflowId));

        if (!isCallerInOwnerScope(publication, tenantId, organizationId)) {
            throw new IllegalArgumentException("Publication does not belong to tenant");
        }

        if (publication.getStatus() == PublicationStatus.PENDING_REVIEW) {
            throw new PublicationPendingReviewException("Cannot unpublish while publication is pending review. Please wait for admin approval.");
        }

        publication.setStatus(PublicationStatus.INACTIVE);

        // Clean up reviews and reset stats
        reviewRepository.deleteByPublicationId(publication.getId());
        publication.setAverageRating(0.0);
        publication.setReviewCount(0);

        // Fully clean up application-dedicated runs on original workflow (via orchestrator)
        orchestratorClient.cleanupApplicationRuns(workflowId, publication.getId().toString(), tenantId);

        // Clean up all runs on the publisher's APPLICATION workflow so it starts fresh on republish
        cleanupPublisherApplicationRuns(tenantId, publication.getId());

        logger.info("Unpublished workflow: {} (stats preserved, application runs cleaned up)", workflowId);

        return publicationRepository.save(publication);
    }

    /**
     * Clean up application runs on the publisher's APPLICATION workflow for a publication.
     * Called on unpublish/republish so the application starts fresh.
     */
    private void cleanupPublisherApplicationRuns(String tenantId, UUID publicationId) {
        try {
            Map<String, Object> appData = orchestratorClient.findBySourcePublication(publicationId, tenantId);
            if (appData == null) return;

            String appId = appData.get("id") != null ? appData.get("id").toString() : null;
            if (appId != null) {
                orchestratorClient.cleanupApplicationRuns(UUID.fromString(appId), publicationId.toString(), tenantId);
            }
        } catch (Exception e) {
            logger.warn("Failed to clean up publisher APPLICATION runs for publication {}: {}",
                    publicationId, e.getMessage());
        }
    }

    /** Personal-scope overload - see {@link #deletePublication(UUID, String, String)}. */
    public void deletePublication(UUID workflowId, String tenantId) {
        deletePublication(workflowId, tenantId, null);
    }

    /**
     * Delete a publication (soft delete -- sets status to INACTIVE).
     * Hard delete is not allowed because existing acquirers hold receipts referencing this publication.
     * Org-aware: every teammate of the owning organization may delete an ORG-owned
     * publication; for USER-owned rows only the owner may mutate.
     */
    public void deletePublication(UUID workflowId, String tenantId, String organizationId) {
        WorkflowPublicationEntity publication = publicationRepository.findByWorkflowId(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("Publication not found for workflow: " + workflowId));

        if (!isCallerInOwnerScope(publication, tenantId, organizationId)) {
            throw new IllegalArgumentException("Publication does not belong to tenant");
        }

        if (publication.getStatus() == PublicationStatus.PENDING_REVIEW) {
            throw new PublicationPendingReviewException("Cannot delete while publication is pending review. Please wait for admin approval.");
        }

        publication.setStatus(PublicationStatus.INACTIVE);
        publicationRepository.save(publication);

        // Clean up cloned showcase run
        String showcaseRunId = publication.getShowcaseRunId();
        if (showcaseRunId != null && showcaseRunId.startsWith("showcase_")) {
            try {
                orchestratorClient.deleteClonedRun(showcaseRunId);
            } catch (Exception e) {
                logger.warn("Failed to delete showcase run on unpublish: {}", e.getMessage());
            }
        }

        logger.info("Soft-deleted publication for workflow: {}", workflowId);
    }

    // ========================================================================
    // Scope helpers (#151 owner_type dual-write)
    // ========================================================================

    /**
     * Check whether the caller is in the publication's owner scope.
     *
     * <ul>
     *   <li>USER-owned: only the publisher (or the legacy owner_id) may mutate.</li>
     *   <li>ORG-owned: any teammate of the owning organization may mutate
     *       (caller passes their active organization id; gateway has already
     *       validated membership).</li>
     * </ul>
     *
     * <p>Legacy fallback: when a row has no {@code ownerType} (pre-V223
     * regression read of an entity loaded from a stale cache) we treat it as
     * USER-owned by {@code publisherId}.
     */
    public boolean isCallerInOwnerScope(WorkflowPublicationEntity publication,
                                         String tenantId,
                                         String organizationId) {
        if (publication == null || tenantId == null) return false;
        WorkflowPublicationEntity.OwnerType type = publication.getOwnerType();
        String ownerId = publication.getOwnerId();
        if (!publication.hasAssignedOwnerScope()) {
            // Legacy or not-yet-persisted row - fall back to publisher_id equality.
            return tenantId.equals(publication.getPublisherId());
        }
        return switch (type) {
            case USER -> tenantId.equals(ownerId);
            case ORG -> organizationId != null
                    && !organizationId.isBlank()
                    && organizationId.equals(ownerId);
        };
    }

    /**
     * Whether the caller (in their current workspace scope) has acquired this
     * publication - i.e. holds a receipt for it.
     *
     * <p>Used to keep an acquirer's installed-application view working even
     * after the publisher unpublishes (status -&gt; INACTIVE) or privatises
     * (visibility -&gt; PRIVATE) the source publication. The acquirer owns an
     * independent clone of the workflow, so the visibility gate that hides the
     * publication from the public must not also lock them out of the metadata
     * for an app they already installed.</p>
     *
     * <p>Receipts are workspace-scoped (organization_id, NOT NULL post-V261),
     * matching the workspace-scoping of the applications list itself. This is a
     * read-path gate, so a missing scope returns {@code false} defensively
     * rather than throwing (unlike the mutation-path {@code hasReceiptInScope}).</p>
     */
    @Transactional(readOnly = true)
    public boolean callerHoldsReceipt(UUID publicationId, String tenantId, String organizationId) {
        if (publicationId == null || tenantId == null) {
            return false;
        }
        String normalizedOrgId = normalizeScope(organizationId);
        if (normalizedOrgId == null) {
            return false;
        }
        return receiptRepository.existsByOrganizationIdAndPublicationId(normalizedOrgId, publicationId);
    }

    // ========================================================================
    // Read operations
    // ========================================================================

    /**
     * Get publication by workflow ID.
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowPublicationEntity> getPublicationByWorkflowId(UUID workflowId) {
        return publicationRepository.findByWorkflowId(workflowId);
    }

    /**
     * List publications visible to the caller in their current scope.
     * When {@code organizationId} is non-null/blank the caller is in an
     * org workspace and sees every ORG-owned publication of that org;
     * otherwise the caller is in a personal workspace and sees only their
     * own USER-owned publications.
     */
    @Transactional(readOnly = true)
    public List<WorkflowPublicationEntity> getPublicationsForScope(String tenantId, String organizationId) {
        if (organizationId != null && !organizationId.isBlank()) {
            return publicationRepository.findByOwnerTypeAndOwnerIdOrderByPublishedAtDesc(
                    WorkflowPublicationEntity.OwnerType.ORG, organizationId);
        }
        return publicationRepository.findByOwnerTypeAndOwnerIdOrderByPublishedAtDesc(
                WorkflowPublicationEntity.OwnerType.USER, tenantId);
    }

    /**
     * Get publication by ID.
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowPublicationEntity> getPublicationById(UUID publicationId) {
        return publicationRepository.findById(publicationId);
    }

    /**
     * Check if workflow is published.
     */
    @Transactional(readOnly = true)
    public boolean isWorkflowPublished(UUID workflowId) {
        return publicationRepository.existsByWorkflowId(workflowId);
    }

    /**
     * Get all publications by publisher.
     */
    @Transactional(readOnly = true)
    public List<WorkflowPublicationEntity> getPublicationsByPublisher(String publisherId) {
        return publicationRepository.findByPublisherIdOrderByPublishedAtDesc(publisherId);
    }

    /**
     * Get marketplace publications (public and active).
     */
    @Transactional(readOnly = true)
    public Page<WorkflowPublicationEntity> getMarketplacePublications(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return publicationRepository.findMarketplacePublications(pageable);
    }

    /**
     * Get marketplace publications filtered by category.
     */
    @Transactional(readOnly = true)
    public Page<WorkflowPublicationEntity> getMarketplacePublicationsByCategory(String categorySlug, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return publicationRepository.findMarketplacePublicationsByCategorySlug(categorySlug, pageable);
    }

    /**
     * Get popular publications.
     */
    @Transactional(readOnly = true)
    public List<WorkflowPublicationEntity> getPopularPublications(int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return publicationRepository.findPopularPublications(pageable);
    }

    /**
     * Search publications by title.
     */
    @Transactional(readOnly = true)
    public List<WorkflowPublicationEntity> searchPublications(String query) {
        return publicationRepository.searchByTitle(query);
    }

    // ========================================================================
    // Acquisition
    // ========================================================================

    /**
     * Acquire a publication: clone the planSnapshot as a new workflow for the tenant.
     * Uses publication_receipts for free re-acquisition after delete.
     *
     * @return Map with "workflowId" and "title" keys
     */
    /**
     * Personal-scope overload. Organization-scoped routing uses
     * {@link #acquirePublication(UUID, String, String)}.
     */
    public Map<String, Object> acquirePublication(UUID publicationId, String tenantId) {
        return acquirePublication(publicationId, tenantId, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> acquirePublication(UUID publicationId, String tenantId, String organizationId) {
        WorkflowPublicationEntity publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalArgumentException("Publication not found: " + publicationId));

        // V261: receipt.organization_id is NOT NULL - resolve fallback from
        // user's default-personal org when request omitted X-Organization-ID.
        // Reassign-then-rebind to a final to keep lambda captures happy below.
        organizationId = resolveAcquirerOrg(tenantId, organizationId, publicationId);
        final String orgScope = organizationId;

        if (acquisitionHelper != null) {
            acquisitionHelper.validateNotOwnPublication(publication, tenantId, orgScope);
        } else if (isOwnPublication(publication, tenantId, orgScope)) {
            throw new IllegalArgumentException("Cannot acquire your own publication");
        }

        // Check if already has an active acquired workflow
        boolean alreadyHasWorkflow = existsApplicationInScope(publicationId, tenantId, organizationId);
        if (alreadyHasWorkflow) {
            throw new IllegalArgumentException("Publication already acquired");
        }

        // Check receipt for free re-acquisition
        boolean alreadyPaid = acquisitionHelper != null
                ? acquisitionHelper.validateAndCheckEntitlement(publication, tenantId, organizationId)
                : hasReceiptInScope(tenantId, publicationId, organizationId);

        if (acquisitionHelper == null && !alreadyPaid && publication.getVisibility() == PublicationVisibility.PRIVATE) {
            throw new IllegalArgumentException("Publication is private");
        }
        if (acquisitionHelper == null && !alreadyPaid && publication.getStatus() != PublicationStatus.ACTIVE) {
            throw new IllegalArgumentException("Publication is not active");
        }
        if (acquisitionHelper == null && alreadyPaid && publication.getStatus() == PublicationStatus.REJECTED) {
            throw new IllegalArgumentException("Publication is not available");
        }

        // Plan resource limit check - only on first-time acquisitions. Re-acquisitions
        // (deleted then re-acquired) don't count again because the receipt already exists.
        if (acquisitionHelper == null && !alreadyPaid && entitlementGuard != null) {
            entitlementGuard.check(tenantId,
                    com.apimarketplace.auth.client.entitlement.ResourceType.APPLICATION,
                    () -> countReceiptsInScope(tenantId, orgScope));
        }

        // Clone planSnapshot as a new workflow
        Map<String, Object> planSnapshot = publication.getPlanSnapshot();
        if (planSnapshot == null || planSnapshot.isEmpty()) {
            throw new IllegalArgumentException("Publication has no plan");
        }

        // Delegate cloning to SnapshotCloneService. Cross-service writes
        // (interface-service, datasource-service, agent-service, orchestrator)
        // are NOT covered by this method's @Transactional. On mid-clone
        // failure we run a best-effort compensating cleanup so the acquirer
        // doesn't end up with orphan interfaces/datasources/agents and a
        // half-wired APPLICATION workflow.
        Map<String, Object> acquireResult;
        try {
            acquireResult = cloneWorkflowSnapshot(
                    planSnapshot, tenantId, publicationId, publication, organizationId);
        } catch (AcquireCloneFailedException cloneFailure) {
            logger.error("[Acquire] cloneFromSnapshot failed for pub={} tenant={}: {} - running scoped compensation",
                    publicationId, tenantId, cloneFailure.getMessage());
            compensateAcquireFailure(cloneFailure.getCreatedWorkflowIds(), publicationId, tenantId, organizationId);
            // Re-throw the ORIGINAL cause so callers/tests see the real failure, not the wrapper.
            if (cloneFailure.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw cloneFailure;
        }

        if (acquisitionHelper != null) {
            acquisitionHelper.recordAcquisition(publication, tenantId, organizationId, alreadyPaid);
        } else {
            // Save receipt if first time (enables free re-acquisition after delete)
            if (!alreadyPaid) {
                int creditsPaid = publication.getCreditsPerUse() != null ? publication.getCreditsPerUse() : 0;
                PublicationReceiptEntity receipt = new PublicationReceiptEntity(
                        tenantId, publicationId, creditsPaid, normalizeScope(organizationId));
                receiptRepository.save(receipt);
                recordUsage(publicationId);
            }
        }

        // Publication is now acquired → retain its current snapshot version so its
        // history starts here (acquired pubs keep history; never-acquired keep none).
        ensureSnapshotVersionRetained(publication);

        // #2a - decouple-to-editable-workflow: AUTOMATICALLY create a freely-editable,
        // DECOUPLED WORKFLOW twin of the just-acquired application so the user can
        // customize it in /app/workflows while the APPLICATION clone above stays
        // run-only. Best-effort: a duplicate failure NEVER fails the acquire (the
        // application clone already succeeded and the receipt is saved).
        String applicationWorkflowId = acquireResult.get("workflowId") != null
                ? acquireResult.get("workflowId").toString() : null;
        duplicateAcquiredApplicationAsEditableWorkflow(
                planSnapshot, tenantId, organizationId, publication, applicationWorkflowId);

        logger.info("Tenant {} {} publication {} -> workflow {}",
                tenantId, alreadyPaid ? "re-acquired" : "acquired", publicationId, acquireResult.get("workflowId"));

        return acquireResult;
    }

    /**
     * #2a - create the editable, DECOUPLED {@code WORKFLOW} twin of a just-acquired
     * application. Sourced from the publication's enriched {@code planSnapshot}: its
     * embedded file refs live under {@code _publications/{publicationId}/}, so passing
     * {@code fileNamespaceId = publicationId} makes the clone-time allowlist match and
     * re-copy INDEPENDENT file copies into the acquirer's tenant (a fresh namespace would
     * only apply if the files were re-snapshotted, e.g. a future live-plan variant). The
     * twin is byte-equivalent to the run-only application at acquire time (apps are
     * run-only, so there is no acquirer edit to diverge), only its row type
     * ({@code WORKFLOW}) and source tag ({@code source_publication_id = NULL}, lineage in
     * {@code metadata.duplicatedFromApplicationId}) differ.
     *
     * <p>Quota: bills WORKFLOW quota only, NO credit (the run-only application already
     * billed APPLICATION). The internal {@code create-application} endpoint runs no
     * entitlement check, so the guard is enforced here. Over-quota → skip the twin, keep
     * the application. Clone failure → compensate ONLY the twin's own rows via
     * {@link OrchestratorInternalClient#deleteDecoupledDuplicateWorkflow} (never
     * {@code deleteAcquiredWorkflow}: its {@code pubId.equals(sourcePublicationId)} guard
     * NPEs on the twin's null source). Either way the acquire is unaffected.
     */
    private void duplicateAcquiredApplicationAsEditableWorkflow(Map<String, Object> planSnapshot,
                                                                String tenantId,
                                                                String organizationId,
                                                                WorkflowPublicationEntity publication,
                                                                String applicationWorkflowId) {
        final String orgScope = normalizeScope(organizationId);
        try {
            if (entitlementGuard != null && orgScope != null) {
                entitlementGuard.check(tenantId,
                        com.apimarketplace.auth.client.entitlement.ResourceType.WORKFLOW,
                        () -> orchestratorClient.countWorkflowsByOrg(orgScope));
            }
        } catch (RuntimeException quotaDenied) {
            logger.info("[Acquire/duplicate] WORKFLOW quota reached for tenant={} pub={} - "
                            + "skipping editable duplicate ({}); application clone is unaffected",
                    tenantId, publication.getId(), quotaDenied.getMessage());
            return;
        }

        try {
            Map<String, Object> duplicate = snapshotCloneService.duplicateToEditableWorkflow(
                    planSnapshot, tenantId, orgScope,
                    publication.getTitle(), publication.getDescription(),
                    publication.getNodeIcons(), publication.getId(), applicationWorkflowId);
            logger.info("[Acquire/duplicate] tenant {} pub {} -> editable WORKFLOW {} "
                            + "(decoupled from application {})",
                    tenantId, publication.getId(), duplicate.get("workflowId"), applicationWorkflowId);
        } catch (AcquireCloneFailedException dupFailure) {
            logger.warn("[Acquire/duplicate] editable duplicate failed for tenant={} pub={}: {} - "
                            + "compensating only its own rows; application clone is unaffected",
                    tenantId, publication.getId(), dupFailure.getMessage());
            compensateDuplicateFailure(dupFailure.getCreatedWorkflowIds(), tenantId, orgScope);
        } catch (RuntimeException e) {
            logger.warn("[Acquire/duplicate] editable duplicate errored for tenant={} pub={}: {} - "
                            + "application clone is unaffected", tenantId, publication.getId(), e.getMessage());
        }
    }

    /**
     * Best-effort compensation for a failed editable-duplicate clone: delete ONLY the
     * rows the duplicate created (root + any sub-workflows), each a plain decoupled
     * {@code WORKFLOW} the orchestrator guards as such. Swallows failures - the acquire
     * already succeeded and must not be masked by a cleanup-side error.
     */
    private void compensateDuplicateFailure(Set<String> createdWorkflowIds, String tenantId, String organizationId) {
        if (createdWorkflowIds == null || createdWorkflowIds.isEmpty()) return;
        String orgScope = normalizeScope(organizationId);
        for (String idStr : createdWorkflowIds) {
            if (idStr == null) continue;
            try {
                orchestratorClient.deleteDecoupledDuplicateWorkflow(UUID.fromString(idStr), tenantId, orgScope);
            } catch (Exception e) {
                logger.warn("[Acquire/duplicate/compensate] cleanup failed for {}: {}", idStr, e.getMessage());
            }
        }
    }

    /**
     * Best-effort compensating cleanup when {@code acquirePublication} fails
     * mid-clone. Walks the acquirer's tenant for any workflow row tagged with
     * this {@code publicationId} as source (the APPLICATION root AND every
     * sub-workflow WORKFLOW clone), deletes its runs + S3 files via
     * orchestrator, then drops the workflow row itself. Dropping the rows is
     * load-bearing: a leftover clone keeps the (org, publication) bucket
     * occupied - pre-fix it tripped the V268 unique index on the next acquire
     * attempt and blocked re-acquisition forever. Any partially-cloned
     * interfaces / datasources / agents are left in place (they're not
     * located by source-publication), but they're orphan-safe - no live
     * workflow references them once the rows are gone.
     *
     * <p>Failures here are swallowed. The original acquire exception is
     * already going to bubble up; we don't want to mask it with a
     * cleanup-side error.
     */
    private void compensateAcquireFailure(Set<String> createdWorkflowIds, UUID publicationId,
                                          String tenantId, String organizationId) {
        try {
            // SCOPED cleanup: delete only the workflow rows THIS acquisition created (root + each
            // sub-workflow clone), tracked through the failed clone. We deliberately do NOT
            // enumerate every (org, publication) row anymore - that org-wide sweep deleted a
            // concurrent acquisition's just-created row (the winner) when two first-time acquires
            // of the same publication raced.
            if (createdWorkflowIds == null || createdWorkflowIds.isEmpty()) {
                logger.info("[Acquire] no partially-cloned workflow rows to compensate for pub={} tenant={}",
                        publicationId, tenantId);
                return;
            }
            int deleted = 0;
            String orgScope = normalizeScope(organizationId);
            for (String cloneIdStr : createdWorkflowIds) {
                if (cloneIdStr == null) continue;
                try {
                    UUID cloneId = UUID.fromString(cloneIdStr);
                    orchestratorClient.cleanupApplicationRuns(cloneId, publicationId.toString(), tenantId);
                    if (orchestratorClient.deleteAcquiredWorkflow(cloneId, publicationId, tenantId, orgScope)) {
                        deleted++;
                    }
                } catch (Exception e) {
                    logger.warn("[Acquire/compensate] cleanup failed for clone={}: {}",
                            cloneIdStr, e.getMessage());
                }
            }
            // Note: interfaces / datasources / agents created in the acquirer's tenant during the
            // failed clone are left in place - functionally inert (no live workflow references them
            // once the rows above are gone) and storage drift is bounded by acquire-retry frequency.
            logger.info("[Acquire/compensate] scoped cleanup done for pub={} tenant={} ({} rows this "
                    + "acquisition created, {} deleted) - concurrent acquisitions' rows untouched",
                    publicationId, tenantId, createdWorkflowIds.size(), deleted);
        } catch (Exception e) {
            logger.warn("[Acquire/compensate] failed for pub={} tenant={}: {} (cleanup is best-effort)",
                    publicationId, tenantId, e.getMessage());
        }
    }

    /**
     * Batch load publications by IDs (single query).
     */
    @Transactional(readOnly = true)
    public Map<UUID, WorkflowPublicationEntity> getPublicationsByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return publicationRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(WorkflowPublicationEntity::getId, Function.identity()));
    }

    /**
     * Get purchases (receipts) for a tenant, enriched with publication info and active workflow status.
     */
    /**
     * Post-V261: organizationId is always present (gateway injects X-Organization-ID;
     * personal-workspace users carry their personal org UUID). The previous tenant-only
     * single-arg overload has been removed.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPurchases(String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        List<PublicationReceiptEntity> receipts = receiptRepository.findByOrganizationId(organizationId);
        return buildPurchases(tenantId, organizationId, receipts);
    }

    private List<Map<String, Object>> buildPurchases(String tenantId,
                                                     String organizationId,
                                                     List<PublicationReceiptEntity> receipts) {
        if (receipts.isEmpty()) return List.of();

        // Batch load publications
        List<UUID> pubIds = receipts.stream()
                .map(PublicationReceiptEntity::getPublicationId)
                .distinct()
                .toList();
        Map<UUID, WorkflowPublicationEntity> pubMap = getPublicationsByIds(pubIds);

        // Cloud-sourced (remote) acquisitions have NO local publication row - the
        // publication lives on the cloud. Index the cloned workflow (keyed by
        // sourcePublicationId) so those purchases still render (display title) AND so the
        // card can preview the LOCAL clone (A1): when the cloud publisher unpublishes/deletes
        // the source, the remote showcase 403s, but the acquirer's own clone is immune - we
        // surface its workflow id + entry interface so the frontend renders it via the local
        // authenticated per-run path instead of the dead cloud proxy.
        Map<UUID, Map<String, Object>> remoteClones = new HashMap<>();
        boolean anyRemote = receipts.stream()
                .anyMatch(r -> r.isRemoteAcquisition() && !pubMap.containsKey(r.getPublicationId()));
        if (anyRemote) {
            for (Map<String, Object> w : getAcquiredWorkflows(tenantId, organizationId)) {
                Object src = w.get("sourcePublicationId");
                if (src == null) continue;
                try {
                    remoteClones.put(UUID.fromString(src.toString()), w);
                } catch (IllegalArgumentException ignore) { /* skip non-UUID source ids */ }
            }
        }

        // Check which publications have an active workflow for this tenant
        List<Map<String, Object>> result = new ArrayList<>();
        for (PublicationReceiptEntity receipt : receipts) {
            UUID pubId = receipt.getPublicationId();
            boolean hasActiveWorkflow = existsApplicationInScope(pubId, tenantId, organizationId);

            Map<String, Object> item = new HashMap<>();
            item.put("publicationId", pubId.toString());
            item.put("organizationId", receipt.getOrganizationId());
            item.put("creditsPaid", receipt.getCreditsPaid());
            item.put("acquiredAt", receipt.getAcquiredAt() != null ? receipt.getAcquiredAt().toString() : null);
            item.put("hasActiveWorkflow", hasActiveWorkflow);

            WorkflowPublicationEntity pub = pubMap.get(pubId);
            if (pub != null) {
                item.put("publication", pub);
            } else if (receipt.isRemoteAcquisition()) {
                // CLOUD-sourced acquisition: the publication lives on the cloud and is absent
                // from the local catalog, so synthesize a minimal one (title from the cloned
                // workflow, price from the receipt) - otherwise the purchase is dropped. A
                // non-remote receipt with no local publication (publisher deleted it) keeps the
                // prior behavior: null publication → hidden.
                Map<String, Object> clone = remoteClones.get(pubId);
                Map<String, Object> synth = new HashMap<>();
                synth.put("id", pubId.toString());
                synth.put("title", clone != null && clone.get("title") != null ? clone.get("title").toString() : null);
                synth.put("publicationType", "WORKFLOW");
                synth.put("displayMode", "APPLICATION");
                synth.put("creditsPerUse", receipt.getCreditsPaid());
                synth.put("status", "ACTIVE");
                synth.put("remote", true);
                // A1 - LOCAL clone preview: hand the frontend the clone's workflow id + entry
                // interface (provided lean by orchestrator's acquired-workflows payload) so the
                // card can FALL BACK to rendering the acquirer's OWN clone via the local
                // authenticated per-run path when the cloud source is unpublished/deleted. The
                // run id is resolved frontend-side via the existing run-version-batch.
                String entryInterfaceId = clone != null && clone.get("entryInterfaceId") != null
                        ? clone.get("entryInterfaceId").toString() : null;
                if (clone != null && clone.get("id") != null && entryInterfaceId != null) {
                    synth.put("acquiredWorkflowId", clone.get("id").toString());
                    synth.put("showcaseInterfaceId", entryInterfaceId);
                    synth.put("localShowcase", true);
                }
                item.put("publication", synth);
            } else {
                item.put("publication", null);
            }

            result.add(item);
        }
        return result;
    }

    /**
     * Get acquired workflows for a tenant, enriched with publication info.
     */
    /** Org-aware overload - see {@link #acquirePublication(UUID, String, String)} for rationale. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAcquiredWorkflows(String tenantId, String organizationId) {
        return normalizeScope(organizationId) != null
                ? orchestratorClient.getAcquiredWorkflows(tenantId, normalizeScope(organizationId))
                : orchestratorClient.getAcquiredWorkflows(tenantId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAcquiredWorkflows(String tenantId) {
        return orchestratorClient.getAcquiredWorkflows(tenantId);
    }

    /**
     * Find the APPLICATION workflow for a tenant and publication.
     * Works for both publisher (who gets one on publish) and acquirer (who gets one on acquire).
     *
     * @return Map with workflow data or null if not found
     */
    /** Org-aware overload - see {@link #acquirePublication(UUID, String, String)} for rationale. */
    @Transactional(readOnly = true)
    public Map<String, Object> findApplicationWorkflow(UUID publicationId, String tenantId, String organizationId) {
        return normalizeScope(organizationId) != null
                ? orchestratorClient.findBySourcePublication(publicationId, tenantId, normalizeScope(organizationId))
                : orchestratorClient.findBySourcePublication(publicationId, tenantId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findApplicationWorkflow(UUID publicationId, String tenantId) {
        return orchestratorClient.findBySourcePublication(publicationId, tenantId);
    }

    /**
     * Record usage of a publication using atomic increment (avoids race conditions
     * with concurrent acquirers via a single UPDATE ... SET use_count = use_count + 1).
     */
    public void recordUsage(UUID publicationId) {
        publicationRepository.incrementUsage(publicationId);
    }

    private static String normalizeScope(String organizationId) {
        return organizationId != null && !organizationId.isBlank() ? organizationId : null;
    }

    private static String ownerOrganizationId(WorkflowPublicationEntity publication) {
        return publication != null
                && publication.getOwnerType() == WorkflowPublicationEntity.OwnerType.ORG
                ? normalizeScope(publication.getOwnerId())
                : null;
    }

    private boolean hasReceiptInScope(String tenantId, UUID publicationId, String organizationId) {
        // Post-V261: organizationId is always present (gateway injects X-Organization-ID;
        // personal-workspace users carry their personal org UUID). The IS-NULL branch is dead.
        String normalizedOrgId = normalizeScope(organizationId);
        if (normalizedOrgId == null) {
            throw new IllegalArgumentException(
                    "organizationId required after V261 (tenantId=" + tenantId
                            + ", publicationId=" + publicationId + ")");
        }
        return receiptRepository.existsByOrganizationIdAndPublicationId(normalizedOrgId, publicationId);
    }

    private long countReceiptsInScope(String tenantId, String organizationId) {
        // Post-V261 invariant: organizationId must be present. See hasReceiptInScope above.
        String normalizedOrgId = normalizeScope(organizationId);
        if (normalizedOrgId == null) {
            throw new IllegalArgumentException(
                    "organizationId required after V261 (tenantId=" + tenantId + ")");
        }
        return receiptRepository.countByOrganizationId(normalizedOrgId);
    }

    private boolean existsApplicationInScope(UUID publicationId, String tenantId, String organizationId) {
        String normalizedOrgId = normalizeScope(organizationId);
        return normalizedOrgId != null
                ? orchestratorClient.existsBySourcePublication(publicationId, tenantId, normalizedOrgId)
                : orchestratorClient.existsBySourcePublication(publicationId, tenantId);
    }

    private Map<String, Object> cloneWorkflowSnapshot(Map<String, Object> planSnapshot,
                                                      String tenantId,
                                                      UUID publicationId,
                                                      WorkflowPublicationEntity publication,
                                                      String organizationId) {
        String normalizedOrgId = normalizeScope(organizationId);
        if (normalizedOrgId != null) {
            return snapshotCloneService.cloneFromSnapshot(
                    planSnapshot, tenantId, publicationId,
                    publication.getTitle(), publication.getDescription(),
                    publication.getNodeIcons(), normalizedOrgId);
        }
        return snapshotCloneService.cloneFromSnapshot(
                planSnapshot, tenantId, publicationId,
                publication.getTitle(), publication.getDescription(),
                publication.getNodeIcons());
    }

    private boolean isOwnPublication(WorkflowPublicationEntity publication,
                                     String tenantId,
                                     String organizationId) {
        if (publication == null || tenantId == null) {
            return false;
        }
        if (tenantId.equals(publication.getPublisherId())) {
            return true;
        }
        if (!publication.hasAssignedOwnerScope()) {
            return false;
        }
        String normalizedOrgId = normalizeScope(organizationId);
        return publication.getOwnerType() == WorkflowPublicationEntity.OwnerType.ORG
                && normalizedOrgId != null
                && normalizedOrgId.equals(publication.getOwnerId());
    }

    // ========================================================================
    // Publish validation
    // ========================================================================

    private long estimatePublicationSize(WorkflowPublicationEntity pub) {
        long size = 0;
        try {
            if (pub.getPlanSnapshot() != null) {
                size += objectMapper.writeValueAsBytes(pub.getPlanSnapshot()).length;
            }
            if (pub.getNodeIcons() != null) {
                size += objectMapper.writeValueAsBytes(pub.getNodeIcons()).length;
            }
        } catch (Exception e) {
            logger.debug("Failed to estimate publication size: {}", e.getMessage());
        }
        return size;
    }

    // ========================================================================
    // Plan enrichment pipeline (publish time)
    // ========================================================================

    /**
     * Enrich the plan with interface/datasource snapshots, compute resource counts,
     * extract node icons, and set everything on the publication entity.
     * Also saves a versioned snapshot for history/rollback.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> enrichAndSetPlanSnapshot(WorkflowPublicationEntity publication, Map<String, Object> planSnapshot, String tenantId) {
        return enrichAndSetPlanSnapshot(publication, planSnapshot, tenantId, null);
    }

    private Map<String, Object> enrichAndSetPlanSnapshot(WorkflowPublicationEntity publication, Map<String, Object> planSnapshot, String tenantId, String organizationId) {
        // Inject agent-referenced resources (tables/interfaces) that aren't already in the plan
        // so they get enriched and snapshotted for the acquirer
        injectAgentReferencedResources(planSnapshot, tenantId, organizationId);

        enrichPlanWithInterfaceData(planSnapshot, tenantId, organizationId, publication.getShowcaseRunId());
        enrichPlanWithDatasourceData(planSnapshot, tenantId, organizationId);
        enrichPlanWithDatasourceItems(planSnapshot, tenantId, organizationId);
        enrichPlanWithAgentData(planSnapshot, tenantId, organizationId);
        snapshotDataInputFiles(planSnapshot, publication.getId(), tenantId);
        // Audit D2 Gap#1: also scan interfaces[]._snapshot_data and
        // agents[]._snapshot_agent_{config,toolsConfig,skills} for FileRefs and
        // re-namespace them. Without this, FileRefs embedded in interface
        // variable_mapping resolutions or agent configs would survive at the
        // publisher's tenant path through publish, and the acquire-time
        // allowlist would refuse them, producing 401s on first render.
        snapshotPlanEmbeddedFileRefs(planSnapshot, publication.getId(), tenantId);

        // Snapshot sub-workflows referenced by cores, agents, and triggers
        // (recursive with cycle detection). publicationId is threaded so
        // sub-plans copy their data_input files under the publication's S3
        // namespace (same as the top-level plan does at line ~838).
        enrichPlanWithSubWorkflowData(planSnapshot, tenantId, organizationId, publication.getWorkflowId(), publication.getId());

        Map<String, Object> ownerApplicationSnapshot = objectMapper.convertValue(planSnapshot,
                new TypeReference<Map<String, Object>>() {});

        // Strip sensitive credentials at publish time (defense-in-depth: also stripped at acquire time)
        stripSensitiveCredentials(planSnapshot);

        publication.setPlanSnapshot(planSnapshot);
        publication.setNodeIcons(WorkflowIconExtractor.extractNodeIcons(planSnapshot));

        // Compute resource counts from the plan
        Object interfacesRaw = planSnapshot.get("interfaces");
        int ifaceCount = (interfacesRaw instanceof List) ? ((List<?>) interfacesRaw).size() : 0;

        Object tablesRaw = planSnapshot.get("tables");
        int dsCount = 0;
        if (tablesRaw instanceof List) {
            Set<String> uniqueDsIds = new HashSet<>();
            for (Object t : (List<?>) tablesRaw) {
                if (t instanceof Map) {
                    Object dsId = ((Map<String, Object>) t).get("dataSourceId");
                    if (dsId != null) uniqueDsIds.add(dsId.toString());
                }
            }
            dsCount = uniqueDsIds.size();
        }
        publication.setInterfaceCount(ifaceCount);
        publication.setDatasourceCount(dsCount);

        // Build denormalized search index. Title / description / category /
        // publisher are populated on the entity by callers BEFORE this helper
        // runs (publish path: lines 244-251; update path: lines 495-499).
        publication.setSearchText(SearchTextBuilder.create()
                .add(publication.getTitle()).add(publication.getDescription())
                .add(publication.getCategoryName()).add(publication.getCategorySlug())
                .add(publication.getPublisherName())
                .fromPlanSnapshot(planSnapshot)
                .build(publication.getId(), "WORKFLOW"));
        return ownerApplicationSnapshot;
    }

    /**
     * Capture the complete frozen view of a source run (run state, aggregated
     * steps, per-epoch signals, per-interface pre-rendered templates + items)
     * and persist it on the publication entity. Marketplace anonymous reads
     * target this JSONB so the source run keeps its own life cycle and the
     * orchestrator is never queried at request time.
     *
     * <p>Strict mode: failure here aborts the publish flow. The marketplace
     * has no live-workflow fallback, so a publication without a snapshot is
     * not safe to expose. Caller is expected to surface the error to the
     * publisher so they can retry rather than getting a half-ghost
     * publication.
     */
    private void captureAndStoreShowcaseSnapshot(WorkflowPublicationEntity publication,
                                                  String sourceRunIdPublic,
                                                  String tenantId,
                                                  String organizationId,
                                                  boolean viaScreeningWizard,
                                                  Map<String, String> imageReplacements) {
        Integer epochFilter = publication.getShowcaseChosenEpoch();
        Map<String, Object> snapshot;
        try {
            snapshot = orchestratorClient.captureShowcaseSnapshot(sourceRunIdPublic, tenantId, organizationId, epochFilter);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Showcase snapshot capture failed for run=" + sourceRunIdPublic + ": " + e.getMessage(), e);
        }
        if (snapshot == null || snapshot.isEmpty()) {
            throw new IllegalStateException(
                    "Showcase snapshot capture returned empty payload for run=" + sourceRunIdPublic);
        }

        // P0 fix: walk the captured run-state and re-namespace any FileRef
        // (image_generation outputs, download_file, sftp downloads, catalog
        // binary tools like Drive/Gmail/S3, …) under the publication's S3
        // namespace. Without this, the marketplace preview's signed URLs
        // stay pointed at the publisher's tenant - which is either a 403
        // (cross-tenant isolation) or a leak depending on policy.
        int copied = copyFileRefsInRunState(snapshot, publication.getId(), tenantId, sourceRunIdPublic);
        if (copied > 0) {
            logger.info("[ShowcaseSnapshot] copied {} FileRef(s) into publication namespace pub={}",
                    copied, publication.getId());
        }

        // AI screening replacements: copy each replacement image into the
        // publication namespace and store the mapping in the snapshot so
        // ShowcaseSnapshotReader can apply them at render time with fresh
        // signed URLs. Source interface HTML is NEVER mutated.
        if (imageReplacements != null && !imageReplacements.isEmpty()) {
            Map<String, String> namespacedReplacements = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, String> repl : imageReplacements.entrySet()) {
                String originalUrl = repl.getKey();
                String sourcePath = repl.getValue();
                String newPath = copyReplacementImageToPublicationNamespace(
                        sourcePath, publication.getId(), tenantId);
                if (newPath != null) {
                    namespacedReplacements.put(originalUrl, newPath);
                } else {
                    logger.warn("[ShowcaseSnapshot] failed to copy replacement image {} for pub={}",
                            sourcePath, publication.getId());
                }
            }
            if (!namespacedReplacements.isEmpty()) {
                snapshot.put("imageReplacements", namespacedReplacements);
                logger.info("[ShowcaseSnapshot] stored {} image replacement(s) for pub={}",
                        namespacedReplacements.size(), publication.getId());
            }
        }

        publication.setShowcaseSnapshot(snapshot);
        publication.setShowcaseSnapshotCapturedAt(java.time.Instant.now());
        publicationRepository.save(publication);
        logger.info("[ShowcaseSnapshot] captured run={} pub={} version={}",
                sourceRunIdPublic, publication.getId(), snapshot.getOrDefault("version", "?"));

        // Wave 2a audit-gap closer - for non-wizard publishes (MCP, scripted,
        // internal S2S), auto-scan the snapshot's interface renders and write
        // SKIPPED rows to image_screening_decisions so every publication has
        // audit-trail coverage. Wizard publishes set viaScreeningWizard=true
        // and skip this because the wizard already POSTs decisions via the
        // /screening-decisions endpoint with KEPT_ATTESTED / SKIPPED based on
        // user choice. Fire-and-forget: a screening logging failure must NOT
        // roll back a successful publish (the audit row is nice-to-have, the
        // publish itself is the contract).
        if (!viaScreeningWizard) {
            try {
                autoScreenSnapshot(publication, snapshot, tenantId);
            } catch (Exception e) {
                logger.warn("[ShowcaseSnapshot] auto-screening failed for pub={} (non-blocking): {}",
                        publication.getId(), e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateShowcaseEpochSelection(WorkflowPublicationEntity publication, Integer showcaseEpoch) {
        if (showcaseEpoch == null) {
            return;
        }
        if (showcaseEpoch < 0) {
            throw new IllegalArgumentException("Showcase epoch must be zero or greater");
        }

        Map<String, Object> snapshot = publication.getShowcaseSnapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            throw new IllegalArgumentException("Cannot choose a showcase epoch before a showcase snapshot exists");
        }

        int snapshotEpoch = resolveSnapshotEpochKey(snapshot, showcaseEpoch);
        UUID interfaceId = publication.getShowcaseInterfaceId();
        if (interfaceId != null) {
            Object renders = snapshot.get("interfaceRenders");
            if (renders instanceof Map<?, ?> rendersMap) {
                Object interfaceEntry = rendersMap.get(interfaceId.toString());
                if (interfaceEntry instanceof Map<?, ?> entry) {
                    Object byEpoch = ((Map<String, Object>) entry).get("byEpoch");
                    if (byEpoch instanceof Map<?, ?> byEpochMap) {
                        if (byEpochMap.containsKey(String.valueOf(snapshotEpoch))) {
                            return;
                        }
                        throw new IllegalArgumentException("Showcase epoch " + showcaseEpoch
                                + " does not exist for the selected interface");
                    }
                }
            }
        }

        if (snapshotEpochExists(snapshot, snapshotEpoch)) {
            return;
        }

        throw new IllegalArgumentException("Showcase epoch " + showcaseEpoch
                + " does not exist in the selected run");
    }

    private int resolveSnapshotEpochKey(Map<String, Object> snapshot, int showcaseEpoch) {
        Object sourceEpoch = snapshot.get("sourceEpoch");
        if (sourceEpoch instanceof Number n && n.intValue() == showcaseEpoch) {
            return 1;
        }
        if (sourceEpoch instanceof String s) {
            try {
                if (Integer.parseInt(s) == showcaseEpoch) {
                    return 1;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to legacy unfiltered snapshot keys.
            }
        }
        return showcaseEpoch;
    }

    @SuppressWarnings("unchecked")
    private boolean snapshotEpochExists(Map<String, Object> snapshot, int epoch) {
        Object epochStates = snapshot.get("epochStates");
        if (epochStates instanceof Map<?, ?> epochStateMap
                && epochStateMap.get(String.valueOf(epoch)) instanceof Map<?, ?> epochState
                && hasEpochExecutionData(epochState)) {
            return true;
        }

        Object aggregated = snapshot.get("aggregatedSteps");
        if (aggregated instanceof Map<?, ?> aggregatedMap) {
            Object byEpoch = ((Map<String, Object>) aggregatedMap).get("byEpoch");
            if (byEpoch instanceof Map<?, ?> byEpochMap
                    && isNonEmptySnapshotValue(byEpochMap.get(String.valueOf(epoch)))) {
                return true;
            }
        }

        Object runState = snapshot.get("runState");
        if (runState instanceof Map<?, ?> runStateMap) {
            Object timestamps = ((Map<String, Object>) runStateMap).get("epochTimestamps");
            if (timestamps instanceof List<?> list) {
                for (Object row : list) {
                    if (row instanceof Map<?, ?> rowMap) {
                        Object rawEpoch = rowMap.get("epoch");
                        if (rawEpoch instanceof Number number && number.intValue() == epoch) {
                            return true;
                        }
                    }
                }
                return false;
            }
            return epoch == 0;
        }

        return epoch == 0;
    }

    private boolean hasEpochExecutionData(Map<?, ?> epochState) {
        Object nodes = epochState.get("nodes");
        if (nodes instanceof Map<?, ?> nodeMap && !nodeMap.isEmpty()) {
            return true;
        }
        Object edges = epochState.get("edges");
        if (edges instanceof Map<?, ?> edgeMap && !edgeMap.isEmpty()) {
            return true;
        }
        for (String key : List.of("readyNodeIds", "awaitingSignalNodeIds", "completedNodeIds",
                "failedNodeIds", "skippedNodeIds", "runningNodeIds")) {
            if (isNonEmptySnapshotValue(epochState.get(key))) {
                return true;
            }
        }
        return false;
    }

    private boolean isNonEmptySnapshotValue(Object value) {
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return value != null;
    }

    /**
     * Wave 2a audit-gap closer. Walks the captured snapshot's
     * {@code interfaceRenders[*].defaultRender} entries, scans each
     * htmlTemplate/cssTemplate/jsTemplate via {@link ImageScreeningService},
     * and persists one SKIPPED row per flagged URL.
     *
     * <p>Idempotent via the V274 UNIQUE constraint
     * {@code (publication_id, snapshot_version, image_url_hash)} -
     * duplicate rows from re-publish are silently swallowed.
     *
     * <p>No-op when the screening beans are not wired (test fixtures using
     * the 13-arg constructor) or when the snapshot has no interface renders.
     */
    @SuppressWarnings("unchecked")
    private void autoScreenSnapshot(WorkflowPublicationEntity publication,
                                    Map<String, Object> snapshot,
                                    String tenantId) {
        if (imageScreeningService == null || imageScreeningDecisionRepository == null) {
            return; // screening beans not present (test fixture path)
        }
        Object rendersObj = snapshot.get("interfaceRenders");
        if (!(rendersObj instanceof Map<?, ?> rendersMap) || rendersMap.isEmpty()) {
            return;
        }

        int loggedCount = 0;
        for (Map.Entry<?, ?> ifaceEntry : rendersMap.entrySet()) {
            Object entryValue = ifaceEntry.getValue();
            if (!(entryValue instanceof Map<?, ?> ifaceMap)) continue;
            Object defaultRender = ifaceMap.get("defaultRender");
            if (!(defaultRender instanceof Map<?, ?> renderMap)) continue;

            String htmlTemplate = (String) renderMap.get("htmlTemplate");
            String cssTemplate = (String) renderMap.get("cssTemplate");
            String jsTemplate = (String) renderMap.get("jsTemplate");

            // Wave 2b - also scan the captured items[].data so the non-wizard
            // (MCP / scripted) publish path logs an audit row for scraped CDN
            // URLs and downloaded FileRefs, matching the wizard's coverage.
            List<Map<String, Object>> items = (renderMap.get("items") instanceof List<?> rawItems)
                    ? rawItems.stream()
                        .filter(it -> it instanceof Map)
                        .map(it -> (Map<String, Object>) it)
                        .toList()
                    : List.of();

            com.apimarketplace.publication.screening.ImageScreeningReport report =
                    imageScreeningService.scan(htmlTemplate, cssTemplate, jsTemplate, items);
            if (report.isClean()) continue;

            for (com.apimarketplace.publication.screening.ImageScreeningReport.FlaggedImage img : report.flagged()) {
                try {
                    com.apimarketplace.publication.screening.ImageScreeningDecisionEntity row =
                            new com.apimarketplace.publication.screening.ImageScreeningDecisionEntity();
                    row.setPublicationId(publication.getId());
                    row.setSnapshotVersion(publication.getSnapshotVersion() != null
                            ? publication.getSnapshotVersion() : 0);
                    row.setDecidedBy(tenantId != null ? tenantId : "system");
                    row.setOrganizationId(publication.getOwnerType() == WorkflowPublicationEntity.OwnerType.ORG
                            ? publication.getOwnerId() : null);
                    row.setImageUrlHash(sha256Hex(img.url()));
                    row.setImageUrlHost(hostOfOrUnknown(img.url()));
                    row.setImageSource(com.apimarketplace.publication.screening.ImageScreeningDecisionEntity.ImageSource
                            .valueOf(img.source().name()));
                    row.setDecision(com.apimarketplace.publication.screening.ImageScreeningDecisionEntity.Decision.SKIPPED);
                    row.setAttestationTextVersion(
                            com.apimarketplace.publication.controller.PublicationScreeningController.CURRENT_ATTESTATION_TEXT_VERSION);
                    // attestation_text deliberately null - auto-scan path never
                    // claims rights on the publisher's behalf.
                    imageScreeningDecisionRepository.save(row);
                    loggedCount++;
                } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                    // uq_image_decision - already covered (republish, prior run).
                    logger.debug("[Auto-screening] skipped duplicate row pub={} urlHash={}",
                            publication.getId(), sha256Hex(img.url()));
                }
            }
        }
        if (loggedCount > 0) {
            logger.info("[Auto-screening] logged {} SKIPPED row(s) for non-wizard publish pub={}",
                    loggedCount, publication.getId());
        }
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String hostOfOrUnknown(String url) {
        try {
            java.net.URI uri = new java.net.URI(url.trim());
            String host = uri.getHost();
            return host == null ? "unknown" : host.toLowerCase(java.util.Locale.ROOT);
        } catch (java.net.URISyntaxException | NullPointerException e) {
            return "unknown";
        }
    }

    /**
     * Walk the showcase snapshot's run state and re-copy any FileRef value
     * (a Map carrying {@code _type=="file"} + {@code path}) from the
     * publisher's tenant S3 namespace into the publication-bound namespace
     * {@code _publications/{pubId}/run-outputs/{stepHash}/{filename}}, then
     * rewrite the path in place.
     *
     * <p>Without this, FileRefs produced by image-generation tools, the
     * download-file core, sftp downloads, convert-to-file, and every
     * catalog binary tool (Gmail attachments, Drive download, Slack file,
     * AWS S3, etc.) survive into the snapshot pinned to the publisher's
     * S3 path. The marketplace preview's signed URLs would then either
     * 403 (tenant isolation) or, worse, leak across tenants if the
     * preview reused the publisher's STS session.
     *
     * <p>Best-effort per file: a copy failure logs a warning and leaves
     * the original path untouched. The publish doesn't abort - preserving
     * marketplace usability over file-by-file completeness.
     *
     * @return number of FileRef paths actually rewritten
     */
    @SuppressWarnings("unchecked")
    private int copyFileRefsInRunState(Map<String, Object> snapshot, UUID publicationId,
                                        String tenantId, String sourceRunIdPublic) {
        if (snapshot == null) return 0;
        // Scan the run-state branches that hold step outputs / global data PLUS
        // the interfaceRenders subtree the marketplace actually reads at render
        // time (`ShowcaseSnapshotReader.readInterfaceRender` → items[].data).
        // Without walking interfaceRenders, the captured items[].data keep the
        // publisher's tenant FileRef path - the marketplace `<img>` works only
        // as long as the publisher's source S3 file survives.
        // Touching planSnapshot here would re-copy data_input files which are
        // already handled by snapshotDataInputFiles() at publish time.
        int[] count = new int[]{0};
        Object runState = snapshot.get("runState");
        if (runState instanceof Map) {
            walkAndCopyFileRefs((Map<String, Object>) runState, publicationId, tenantId,
                    sourceRunIdPublic, count);
        }
        Object aggregatedSteps = snapshot.get("aggregatedSteps");
        if (aggregatedSteps instanceof Map) {
            walkAndCopyFileRefs((Map<String, Object>) aggregatedSteps, publicationId, tenantId,
                    sourceRunIdPublic, count);
        }
        // interfaceRenders.<id>.defaultRender.items[].data + byEpoch.<n>.items[].data:
        // canonical marketplace render surface. triggerData intentionally NOT
        // walked - can carry uploads from a different tenant who fired the
        // interface post-publish, never to be copied into publisher namespace.
        Object interfaceRenders = snapshot.get("interfaceRenders");
        if (interfaceRenders instanceof Map<?, ?> renders) {
            for (Object renderEntry : renders.values()) {
                if (!(renderEntry instanceof Map<?, ?> entryMap)) continue;
                Map<String, Object> entry = (Map<String, Object>) entryMap;
                Object defaultRender = entry.get("defaultRender");
                if (defaultRender instanceof Map<?, ?> defMap) {
                    walkInterfaceRenderItems((Map<String, Object>) defMap, publicationId, tenantId,
                            sourceRunIdPublic, count);
                }
                Object byEpoch = entry.get("byEpoch");
                if (byEpoch instanceof Map<?, ?> epochs) {
                    for (Object perEpoch : epochs.values()) {
                        if (perEpoch instanceof Map<?, ?> perEpochMap) {
                            walkInterfaceRenderItems((Map<String, Object>) perEpochMap, publicationId,
                                    tenantId, sourceRunIdPublic, count);
                        }
                    }
                }
            }
        }
        return count[0];
    }

    /**
     * Walk only the {@code items[].data} subtree of an interface render entry -
     * mirroring the scope of {@link ShowcaseFileRefRewriter#rewriteItems} so
     * the namespace-copied paths exactly cover the keys the rewriter will sign.
     *
     * <p>Before walking for FileRef objects, normalizes proxy URL strings
     * ({@code /api/files/proxy?key=...}) into structured FileRef maps
     * ({@code {_type:"file", path:"..."}}). This handles the case where
     * interface templates or FormDispatchService bake file references as
     * already-rendered proxy URLs rather than structured FileRef objects.
     */
    @SuppressWarnings("unchecked")
    private void walkInterfaceRenderItems(Map<String, Object> renderEntry, UUID publicationId,
                                           String tenantId, String sourceRunIdPublic, int[] count) {
        Object items = renderEntry.get("items");
        if (!(items instanceof List<?> itemList)) return;
        for (Object item : itemList) {
            if (!(item instanceof Map<?, ?> itemMap)) continue;
            Object data = ((Map<String, Object>) itemMap).get("data");
            if (data instanceof Map<?, ?> dataMap) {
                normalizeProxyUrlsInMap((Map<String, Object>) dataMap);
                walkAndCopyFileRefs(data, publicationId, tenantId, sourceRunIdPublic, count);
                copyFileRefsInJsonStrings((Map<String, Object>) dataMap, publicationId, tenantId, sourceRunIdPublic, count);
            }
        }
    }

    // ─── Proxy URL → FileRef normalization ────────────────────────────────────

    private static final String PROXY_PREFIX = "/api/files/proxy?";

    /**
     * Recursively walk a Map and convert any string value that looks like
     * {@code /api/files/proxy?key=<encoded_key>&...} into a structured FileRef
     * map {@code {_type:"file", path:<decoded_key>, name:<filename>}}.
     * Also detects JSON-encoded strings (arrays/objects) that embed proxy URLs
     * and normalizes them in-place.
     */
    @SuppressWarnings("unchecked")
    private void normalizeProxyUrlsInMap(Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                Object normalized = normalizeProxyUrlValue(s);
                if (normalized != value) {
                    entry.setValue(normalized);
                }
            } else if (value instanceof Map<?, ?> nested) {
                normalizeProxyUrlsInMap((Map<String, Object>) nested);
            } else if (value instanceof List<?> list) {
                normalizeProxyUrlsInList((List<Object>) list);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void normalizeProxyUrlsInList(List<Object> list) {
        for (int i = 0; i < list.size(); i++) {
            Object value = list.get(i);
            if (value instanceof String s) {
                Object normalized = normalizeProxyUrlValue(s);
                if (normalized != value) {
                    list.set(i, normalized);
                }
            } else if (value instanceof Map<?, ?> nested) {
                normalizeProxyUrlsInMap((Map<String, Object>) nested);
            } else if (value instanceof List<?> nested) {
                normalizeProxyUrlsInList((List<Object>) nested);
            }
        }
    }

    /**
     * If the string is a proxy URL, convert to FileRef map.
     * If the string is a JSON array/object containing proxy URLs, parse, normalize, re-serialize.
     * Otherwise return the original string (identity).
     */
    @SuppressWarnings("unchecked")
    private Object normalizeProxyUrlValue(String s) {
        // Direct proxy URL string → FileRef map
        if (s.startsWith(PROXY_PREFIX)) {
            String key = extractKeyFromProxyUrl(s);
            if (key != null && !key.isBlank()) {
                Map<String, Object> fileRef = new LinkedHashMap<>();
                fileRef.put("_type", "file");
                fileRef.put("path", key);
                fileRef.put("name", extractFileName(key));
                fileRef.put("mimeType", guessMimeType(key));
                return fileRef;
            }
        }
        // JSON-encoded string that may contain embedded proxy URLs
        if ((s.startsWith("[") || s.startsWith("{")) && s.contains(PROXY_PREFIX)) {
            try {
                Object parsed = objectMapper.readValue(s, Object.class);
                if (normalizeProxyUrlsInParsedJson(parsed)) {
                    return objectMapper.writeValueAsString(parsed);
                }
            } catch (Exception ignored) {
                // Not valid JSON or serialization error - leave as-is
            }
        }
        return s; // unchanged
    }

    /**
     * Walk a parsed JSON structure (from a JSON-encoded string field) and replace
     * proxy URL strings with FileRef maps. Returns true if any replacement was made.
     */
    @SuppressWarnings("unchecked")
    private boolean normalizeProxyUrlsInParsedJson(Object node) {
        boolean changed = false;
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            for (Map.Entry<String, Object> entry : m.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String s && s.startsWith(PROXY_PREFIX)) {
                    String key = extractKeyFromProxyUrl(s);
                    if (key != null && !key.isBlank()) {
                        Map<String, Object> fileRef = new LinkedHashMap<>();
                        fileRef.put("_type", "file");
                        fileRef.put("path", key);
                        fileRef.put("name", extractFileName(key));
                        fileRef.put("mimeType", guessMimeType(key));
                        entry.setValue(fileRef);
                        changed = true;
                    }
                } else if (value instanceof Map || value instanceof List) {
                    changed |= normalizeProxyUrlsInParsedJson(value);
                }
            }
        } else if (node instanceof List<?> list) {
            List<Object> mutableList = (List<Object>) list;
            for (int i = 0; i < mutableList.size(); i++) {
                Object value = mutableList.get(i);
                if (value instanceof String s && s.startsWith(PROXY_PREFIX)) {
                    String key = extractKeyFromProxyUrl(s);
                    if (key != null && !key.isBlank()) {
                        Map<String, Object> fileRef = new LinkedHashMap<>();
                        fileRef.put("_type", "file");
                        fileRef.put("path", key);
                        fileRef.put("name", extractFileName(key));
                        fileRef.put("mimeType", guessMimeType(key));
                        mutableList.set(i, fileRef);
                        changed = true;
                    }
                } else if (value instanceof Map || value instanceof List) {
                    changed |= normalizeProxyUrlsInParsedJson(value);
                }
            }
        }
        return changed;
    }

    /**
     * Walk a data map looking for String values that are JSON arrays/objects containing
     * FileRef maps (produced by normalizeProxyUrlsInParsedJson). Parse them, call
     * walkAndCopyFileRefs to copy files, then re-serialize back to a JSON string.
     * This preserves the String type (template compatibility) while ensuring files
     * are copied to _publications/ namespace.
     */
    @SuppressWarnings("unchecked")
    private void copyFileRefsInJsonStrings(Map<String, Object> map, UUID publicationId,
                                            String tenantId, String sourceRunIdPublic, int[] count) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s && (s.startsWith("[") || s.startsWith("{")) && s.contains("\"_type\":\"file\"")) {
                try {
                    Object parsed = objectMapper.readValue(s, Object.class);
                    walkAndCopyFileRefs(parsed, publicationId, tenantId, sourceRunIdPublic, count);
                    // Re-serialize - walkAndCopyFileRefs mutates paths in-place
                    entry.setValue(objectMapper.writeValueAsString(parsed));
                } catch (Exception ignored) {
                    // Not valid JSON - leave as-is
                }
            }
        }
    }

    /**
     * Extract the S3 key from a proxy URL string like
     * {@code /api/files/proxy?key=1%2Fworkflow%2Frun%2Fstep%2Ffile.jpg&disposition=inline}.
     */
    private static String extractKeyFromProxyUrl(String url) {
        int keyStart = url.indexOf("key=");
        if (keyStart < 0) return null;
        keyStart += 4; // skip "key="
        int keyEnd = url.indexOf('&', keyStart);
        String encoded = keyEnd > 0 ? url.substring(keyStart, keyEnd) : url.substring(keyStart);
        try {
            return java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return encoded; // fallback to encoded value
        }
    }

    private static String guessMimeType(String path) {
        if (path == null) return "application/octet-stream";
        String lower = path.toLowerCase();
        // images
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".avif")) return "image/avif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        // video - an uploaded replacement for a flagged <video> must serve a
        // real video Content-Type so the browser plays it (octet-stream won't).
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".ogv")) return "video/ogg";
        // audio
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    @SuppressWarnings("unchecked")
    private void walkAndCopyFileRefs(Object node, UUID publicationId, String tenantId,
                                      String sourceRunIdPublic, int[] count) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            // FileRef detection: STRICT contract - `_type == "file"` + non-empty
            // String `path`. Aligned with ShowcaseFileRefRewriter.isFileRef
            // (downstream renderer). The `_type` vs `type` discriminator drift
            // silently breaks rendering: a Map with bare `type:"file"` would
            // get copied here but never HMAC-signed at render time.
            //
            // No tenant-prefix guard: the snapshot was already captured from a
            // validated run, so ALL FileRefs in it are legitimate for this
            // publication. In cross-org scenarios the publishing user's tenantId
            // differs from the file owner's tenant (files live under the
            // workflow owner's path). Filtering on tenantId would silently skip
            // those files, leaving raw FileRefs in the snapshot that the
            // ShowcaseFileRefRewriter refuses to sign → broken marketplace images.
            // The copy destination (_publications/{pubId}/...) is publication-
            // scoped, so no cross-tenant leak is possible.
            if ("file".equals(m.get("_type")) && m.get("path") instanceof String oldPath
                    && !oldPath.isBlank() && !oldPath.startsWith("_publications/")) {
                // Audit trail: log when the file belongs to a different tenant
                // than the publisher (cross-org copy). This is expected and safe
                // but worth tracking for diagnostics.
                int slashIdx = oldPath.indexOf('/');
                String fileOwnerTenant = slashIdx > 0 ? oldPath.substring(0, slashIdx) : null;
                if (fileOwnerTenant != null && tenantId != null && !tenantId.isBlank()
                        && !fileOwnerTenant.equals(tenantId)) {
                    logger.warn("[ShowcaseSnapshot/files] cross-tenant copy: publisher={} fileOwner={} path={} (pub={})",
                            tenantId, fileOwnerTenant, oldPath, publicationId);
                }

                String fileName = m.get("name") instanceof String s && !s.isBlank()
                        ? s : extractFileName(oldPath);
                String mimeType = m.get("mimeType") instanceof String mt ? mt : "application/octet-stream";
                String stepAlias = "snapshot-runout-" + Integer.toHexString(oldPath.hashCode());

                Map<String, Object> req = new HashMap<>();
                req.put("sourcePath", oldPath);
                // Let the copy endpoint infer sourceTenantId from the path
                // (first segment). The publishing user's tenantId may differ
                // from the file owner's tenant in cross-org scenarios.
                req.put("tenantId", "_publications");
                req.put("workflowId", publicationId.toString());
                req.put("runId", "snapshot");
                req.put("stepAlias", stepAlias);
                req.put("fileName", fileName);
                req.put("mimeType", mimeType);
                try {
                    // organizationId=null: snapshot files live under `_publications` and are served by
                    // the anonymous showcase via HMAC-by-path (no by-id lookup needed). The acquirer
                    // re-copies them by path and mints a fresh org-scoped id (acquire path forwards org).
                    Map<String, Object> result = orchestratorClient.copyFile(req, null);
                    if (result != null && result.get("newPath") instanceof String newPath) {
                        m.put("path", newPath);
                        // Rewrite the opaque `id` to the NEW storage row (the by-id URL is built from
                        // id). If the copy returned no id, DROP the stale source id rather than leave
                        // it pointing at the publisher's row → the authenticated snapshot preview would
                        // 403/404 cross-tenant.
                        if (result.get("newId") instanceof String newId) {
                            m.put("id", newId);
                        } else {
                            m.remove("id");
                        }
                        count[0]++;
                    } else {
                        logger.debug("[ShowcaseSnapshot/files] copy returned no newPath for {} (run={})",
                                oldPath, sourceRunIdPublic);
                    }
                } catch (Exception e) {
                    logger.warn("[ShowcaseSnapshot/files] copy failed for {} (run={}): {}",
                            oldPath, sourceRunIdPublic, e.getMessage());
                }
                // Don't recurse below the FileRef - the inner `path` is the
                // only field we needed to rewrite.
                return;
            }
            for (Object v : m.values()) {
                walkAndCopyFileRefs(v, publicationId, tenantId, sourceRunIdPublic, count);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                walkAndCopyFileRefs(item, publicationId, tenantId, sourceRunIdPublic, count);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyImageReplacementsToExistingSnapshot(WorkflowPublicationEntity publication,
                                                           Map<String, String> imageReplacements,
                                                           String tenantId) {
        Map<String, Object> snapshot = publication.getShowcaseSnapshot();
        Map<String, String> namespacedReplacements = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> repl : imageReplacements.entrySet()) {
            String newPath = copyReplacementImageToPublicationNamespace(
                    repl.getValue(), publication.getId(), tenantId);
            if (newPath != null) {
                namespacedReplacements.put(repl.getKey(), newPath);
            }
        }
        if (!namespacedReplacements.isEmpty()) {
            Object existing = snapshot.get("imageReplacements");
            if (existing instanceof Map<?, ?> existingMap) {
                Map<String, String> merged = new java.util.LinkedHashMap<>((Map<String, String>) existingMap);
                merged.putAll(namespacedReplacements);
                snapshot.put("imageReplacements", merged);
            } else {
                snapshot.put("imageReplacements", namespacedReplacements);
            }
            publication.setShowcaseSnapshot(snapshot);
            logger.info("[ShowcaseSnapshot] patched {} image replacement(s) on existing snapshot pub={}",
                    namespacedReplacements.size(), publication.getId());
        }
    }

    private String copyReplacementImageToPublicationNamespace(String sourcePath, UUID publicationId, String tenantId) {
        String fileName = extractFileName(sourcePath);
        String stepAlias = "ai-replace-" + Integer.toHexString(sourcePath.hashCode());
        String mimeType = guessMimeType(fileName);
        Map<String, Object> req = new HashMap<>();
        req.put("sourcePath", sourcePath);
        req.put("tenantId", "_publications");
        req.put("workflowId", publicationId.toString());
        req.put("runId", "snapshot");
        req.put("stepAlias", stepAlias);
        req.put("fileName", fileName);
        req.put("mimeType", mimeType);
        try {
            // organizationId=null: the replacement lands under `_publications` and is consumed
            // path-only by the HMAC-signed showcase (imageReplacements map), never by-id.
            Map<String, Object> result = orchestratorClient.copyFile(req, null);
            if (result != null && result.get("newPath") instanceof String newPath) {
                return newPath;
            }
        } catch (Exception e) {
            logger.warn("[ShowcaseSnapshot/ai-replace] copy failed for {}: {}", sourcePath, e.getMessage());
        }
        return null;
    }

    private static String extractFileName(String path) {
        if (path == null) return "file";
        int idx = path.lastIndexOf('/');
        return idx >= 0 && idx < path.length() - 1 ? path.substring(idx + 1) : path;
    }


    /**
     * Record the publication's snapshot version under the acquisition-based
     * retention policy (no fixed cap):
     * <ul>
     *   <li><b>Acquired</b> (≥1 receipt) → KEEP every version; re-acquirers may
     *       need any past version.</li>
     *   <li><b>Never acquired</b> → KEEP NOTHING; the live {@code plan_snapshot} on
     *       the publication row is all a fresh acquisition needs, so the whole
     *       history is dropped.</li>
     * </ul>
     * The version counter lives on the publication ({@code snapshot_version}), so it
     * keeps advancing even after a never-acquired publication's history is purged.
     */
    private void saveSnapshotVersion(WorkflowPublicationEntity publication, Map<String, Object> planSnapshot) {
        UUID publicationId = publication.getId();
        int prevVersion = publication.getSnapshotVersion() != null
                ? publication.getSnapshotVersion()
                : snapshotVersionRepository.getMaxVersion(publicationId).orElse(0);
        int nextVersion = prevVersion + 1;
        publication.setSnapshotVersion(nextVersion);

        if (receiptRepository.existsByPublicationId(publicationId)) {
            snapshotVersionRepository.save(new PublicationSnapshotVersionEntity(publicationId, nextVersion, planSnapshot));
            logger.debug("Kept snapshot version {} for acquired publication {}", nextVersion, publicationId);
        } else {
            // Never acquired → retain no history (drop any stragglers too).
            int dropped = snapshotVersionRepository.deleteAllByPublicationId(publicationId);
            logger.debug("Publication {} not acquired - version bumped to {}, dropped {} retained snapshot(s)",
                    publicationId, nextVersion, dropped);
        }
    }

    /**
     * When a publication becomes acquired, retain its CURRENT snapshot as a version so
     * the acquisition-based policy holds from the acquired version onward: an acquired
     * publication keeps version history (a never-acquired one keeps none, so this row
     * would otherwise be absent). This row surfaces in the owner's version-history list.
     *
     * <p>NOTE: re-acquisition clones the publication's LIVE {@code plan_snapshot}, NOT
     * this retained row - so this is about preserving the version history, not gating
     * what a re-acquirer receives. Idempotent: only inserts when that
     * {@code (publicationId, version)} pair isn't already stored. Package-private for test.
     */
    void ensureSnapshotVersionRetained(WorkflowPublicationEntity publication) {
        Integer version = publication.getSnapshotVersion();
        Map<String, Object> snapshot = publication.getPlanSnapshot();
        if (version == null || snapshot == null) return;
        UUID publicationId = publication.getId();
        if (snapshotVersionRepository.findByPublicationIdAndVersion(publicationId, version).isEmpty()) {
            snapshotVersionRepository.save(new PublicationSnapshotVersionEntity(publicationId, version, snapshot));
            logger.debug("Preserved acquired snapshot version {} for publication {}", version, publicationId);
        }
    }

    /**
     * List all snapshot versions for a publication (metadata only, no plan data).
     */
    @Transactional(readOnly = true)
    public List<PublicationSnapshotVersionEntity> getSnapshotVersions(UUID publicationId) {
        return snapshotVersionRepository.findByPublicationIdOrderByVersionDesc(publicationId);
    }

    /**
     * Get a specific snapshot version for a publication.
     */
    @Transactional(readOnly = true)
    public Optional<PublicationSnapshotVersionEntity> getSnapshotVersion(UUID publicationId, int version) {
        return snapshotVersionRepository.findByPublicationIdAndVersion(publicationId, version);
    }

    /**
     * Backfill resource counts for pre-V118 publications.
     * Computes counts from planSnapshot and persists them so this only runs once.
     */
    @SuppressWarnings("unchecked")
    public void backfillResourceCountsIfNeeded(WorkflowPublicationEntity publication) {
        if (publication.getInterfaceCount() != null && publication.getInterfaceCount() > 0
                && publication.getDatasourceCount() != null && publication.getDatasourceCount() > 0) {
            return; // Already computed
        }
        Map<String, Object> plan = publication.getPlanSnapshot();
        if (plan == null) return;

        boolean changed = false;

        if (publication.getInterfaceCount() == null || publication.getInterfaceCount() == 0) {
            Object ifaces = plan.get("interfaces");
            if (ifaces instanceof List && !((List<?>) ifaces).isEmpty()) {
                publication.setInterfaceCount(((List<?>) ifaces).size());
                changed = true;
            }
        }

        if (publication.getDatasourceCount() == null || publication.getDatasourceCount() == 0) {
            Object tables = plan.get("tables");
            if (tables instanceof List && !((List<?>) tables).isEmpty()) {
                Set<String> uniqueDs = new HashSet<>();
                for (Object t : (List<?>) tables) {
                    if (t instanceof Map) {
                        Object dsId = ((Map<String, Object>) t).get("dataSourceId");
                        if (dsId != null) uniqueDs.add(dsId.toString());
                    }
                }
                if (!uniqueDs.isEmpty()) {
                    publication.setDatasourceCount(uniqueDs.size());
                    changed = true;
                }
            }
        }

        if (changed) {
            publicationRepository.save(publication);
        }
    }

    // ========================================================================
    // Plan enrichment (used by both workflow and agent publication)
    // ========================================================================

    /**
     * Run the full enrichment pipeline on a workflow plan.
     * Injects agent-referenced resources, enriches interfaces/datasources/agents/sub-workflows,
     * and strips sensitive credentials.
     * Used by AgentPublicationService to enrich workflow plans referenced by agents.
     *
     * @param plan        the workflow plan to enrich (mutated in place)
     * @param tenantId    the tenant owning the resources
     * @param workflowId  the workflow UUID (for sub-workflow cycle detection)
     */
    void enrichWorkflowPlan(Map<String, Object> plan, String tenantId, UUID workflowId) {
        enrichWorkflowPlan(plan, tenantId, null, workflowId);
    }

    /**
     * Run the full enrichment pipeline on a workflow plan inside an explicit workspace scope.
     */
    void enrichWorkflowPlan(Map<String, Object> plan, String tenantId, String organizationId, UUID workflowId) {
        injectAgentReferencedResources(plan, tenantId, organizationId);
        // No source run available in agent-publication context - pass null,
        // mappings will be absent from the snapshot (acceptable: agent-published
        // sub-workflows don't have a marketplace preview surface that consumes them).
        enrichPlanWithInterfaceData(plan, tenantId, organizationId, null);
        enrichPlanWithDatasourceData(plan, tenantId, organizationId);
        enrichPlanWithDatasourceItems(plan, tenantId, organizationId);
        enrichPlanWithAgentData(plan, tenantId, organizationId);
        enrichPlanWithSubWorkflowData(plan, tenantId, organizationId, workflowId);
        stripSensitiveCredentials(plan);
    }

    // ========================================================================
    // Agent resource injection (publish time)
    // ========================================================================

    /**
     * Scan agents' toolsConfig for resource IDs (tables, interfaces) not already in the plan
     * and inject placeholder nodes so they get enriched during the snapshot pipeline.
     * Without this, agent-referenced resources outside the plan would be lost in the snapshot.
     */
    @SuppressWarnings("unchecked")
    void injectAgentReferencedResources(Map<String, Object> plan, String tenantId) {
        injectAgentReferencedResources(plan, tenantId, null);
    }

    @SuppressWarnings("unchecked")
    void injectAgentReferencedResources(Map<String, Object> plan, String tenantId, String organizationId) {
        Object agentsRaw = plan.get("agents");
        if (!(agentsRaw instanceof List<?> agents)) return;

        // Collect agentConfigIds
        List<UUID> agentUuids = agents.stream()
                .filter(a -> a instanceof Map)
                .map(a -> ((Map<?, ?>) a).get("agentConfigId"))
                .filter(Objects::nonNull)
                .map(id -> { try { return UUID.fromString(id.toString()); } catch (Exception e) { return null; } })
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (agentUuids.isEmpty()) return;

        // Batch load agent entities
        Map<UUID, AgentDto> entityMap = agentClient.bulkFind(agentUuids, tenantId, organizationId).stream()
                .filter(entity -> ScopeGuard.isInStrictScope(tenantId, organizationId,
                        entity.getTenantId(), entity.getOrganizationId()))
                .collect(Collectors.toMap(AgentDto::getId, Function.identity()));

        // Collect existing resource IDs in the plan
        Set<String> existingDsIds = extractPlanDataSourceIds(plan);
        Set<String> existingIfaceIds = extractPlanInterfaceIds(plan);

        Set<String> dsIdsToInject = new HashSet<>();
        Set<String> ifaceIdsToInject = new HashSet<>();

        for (Object agent : agents) {
            if (!(agent instanceof Map<?, ?> agentMap)) continue;
            Object configIdRaw = agentMap.get("agentConfigId");
            if (configIdRaw == null) continue;
            UUID configId;
            try { configId = UUID.fromString(configIdRaw.toString()); } catch (Exception e) { continue; }

            AgentDto dto = entityMap.get(configId);
            if (dto == null || dto.getToolsConfig() == null) continue;

            Map<String, Object> tc = dto.getToolsConfig();
            Object tablesRaw = tc.get("tables");
            if (tablesRaw instanceof List<?> tableList) {
                for (Object t : tableList) {
                    if (t != null && !existingDsIds.contains(t.toString())) {
                        dsIdsToInject.add(t.toString());
                    }
                }
            }

            Object ifacesRaw = tc.get("interfaces");
            if (ifacesRaw instanceof List<?> ifaceList) {
                for (Object i : ifaceList) {
                    if (i != null && !existingIfaceIds.contains(i.toString())) {
                        ifaceIdsToInject.add(i.toString());
                    }
                }
            }
        }

        // Inject missing datasource references as placeholder table nodes
        if (!dsIdsToInject.isEmpty()) {
            List<Map<String, Object>> tables = plan.get("tables") instanceof List
                    ? new ArrayList<>((List<Map<String, Object>>) plan.get("tables"))
                    : new ArrayList<>();
            for (String dsId : dsIdsToInject) {
                Map<String, Object> placeholder = new HashMap<>();
                placeholder.put("dataSourceId", dsId);
                placeholder.put("_injected_by_agent", true);
                tables.add(placeholder);
            }
            plan.put("tables", tables);
        }

        // Inject missing interface references as placeholder interface nodes
        if (!ifaceIdsToInject.isEmpty()) {
            List<Map<String, Object>> interfaces = plan.get("interfaces") instanceof List
                    ? new ArrayList<>((List<Map<String, Object>>) plan.get("interfaces"))
                    : new ArrayList<>();
            for (String ifaceId : ifaceIdsToInject) {
                Map<String, Object> placeholder = new HashMap<>();
                placeholder.put("id", ifaceId);
                placeholder.put("_injected_by_agent", true);
                interfaces.add(placeholder);
            }
            plan.put("interfaces", interfaces);
        }
    }

    // ========================================================================
    // Interface enrichment (publish time) and cloning (acquire time)
    // ========================================================================

    /**
     * Enrich interface nodes in the plan with full InterfaceDto data.
     * Uses batch loading (single query) to avoid N+1.
     */
    @SuppressWarnings("unchecked")
    void enrichPlanWithInterfaceData(Map<String, Object> plan, String tenantId, String showcaseRunIdPublic) {
        enrichPlanWithInterfaceData(plan, tenantId, null, showcaseRunIdPublic);
    }

    @SuppressWarnings("unchecked")
    void enrichPlanWithInterfaceData(Map<String, Object> plan, String tenantId, String organizationId, String showcaseRunIdPublic) {
        Object interfacesRaw = plan.get("interfaces");
        if (!(interfacesRaw instanceof List)) return;

        List<Map<String, Object>> interfaces = (List<Map<String, Object>>) interfacesRaw;

        // Collect all valid UUIDs
        List<UUID> ids = interfaces.stream()
                .map(n -> n.get("id"))
                .filter(Objects::nonNull)
                .map(id -> { try { return UUID.fromString(id.toString()); } catch (IllegalArgumentException e) { return null; } })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (ids.isEmpty()) return;

        // Single batch query via client, followed by a local strict-scope
        // guard. The internal batch endpoint is shared infrastructure; publish
        // snapshotting must never freeze templates/data from another active
        // workspace even if a plan carries a foreign interface UUID.
        Map<UUID, InterfaceDto> entityMap = interfaceClient.getInterfacesByIds(new ArrayList<>(ids), tenantId, organizationId).stream()
                .filter(entity -> ScopeGuard.isInStrictScope(tenantId, organizationId,
                        entity.getTenantId(), entity.getOrganizationId()))
                .collect(Collectors.toMap(InterfaceDto::getId, Function.identity(), (left, right) -> left));

        // Phase B.1: when a source run is provided (marketplace publish path),
        // also fetch the runtime variableMappings + actionMappings so the
        // marketplace preview can render the interface without reading from
        // interface_run_snapshots in the publisher's tenant. The map is keyed
        // by interface id (string). Empty when no run / no snapshots.
        Map<String, Map<String, Object>> runtimeMappings;
        if (showcaseRunIdPublic != null && !showcaseRunIdPublic.isBlank()) {
            Map<String, Map<String, Object>> fetched = orchestratorClient
                    .getInterfaceSnapshotsForRun(showcaseRunIdPublic, tenantId, organizationId);
            runtimeMappings = fetched != null ? fetched : Map.of();
        } else {
            runtimeMappings = Map.of();
        }

        // Always write _snapshot_* fields, even when the entity is missing
        // (deleted, agent-referenced but not loaded, etc.). The marker prefix
        // alone is what tells PublicationSnapshotContext.getInterfaceSnapshot
        // "I'm a snapshot" - if we left the prefix off, the frontend would
        // silently fall back to the auth'd /api/interfaces/{id} endpoint,
        // which 401s for anonymous visitors and leaks publisher data for
        // authenticated viewers. Writing null fields is safe: the preview
        // renders empty (better than leak), and the publisher gets a warning
        // on every miss so they can re-link / delete the orphan interface.
        java.util.List<String> missing = new java.util.ArrayList<>();
        // FORM interfaces bind to a backing datasource by numeric id. Collect
        // those ids that have no table node yet so they get injected (below) and
        // therefore enriched + cloned for the acquirer - without a cloned
        // datasource there is nothing for SnapshotCloneService to remap the
        // FORM binding to, and the acquired form has no table at all.
        Set<String> existingDsIds = extractPlanDataSourceIds(plan);
        Set<String> formDsToInject = new HashSet<>();
        for (Map<String, Object> ifaceNode : interfaces) {
            Object idRaw = ifaceNode.get("id");
            if (idRaw == null) continue;
            UUID interfaceId;
            try {
                interfaceId = UUID.fromString(idRaw.toString());
            } catch (IllegalArgumentException e) {
                continue;
            }
            InterfaceDto entity = entityMap.get(interfaceId);
            // Always inject runtime mappings when available - independent of
            // entity presence so a deleted-but-still-referenced interface still
            // gets its mappings for the showcase render path.
            Map<String, Object> mappings = runtimeMappings.get(interfaceId.toString());
            Object variableMappings = mappings != null ? mappings.get("variableMappings") : null;
            Object actionMappings = mappings != null ? mappings.get("actionMappings") : null;

            if (entity == null) {
                missing.add(interfaceId.toString());
                ifaceNode.put("_snapshot_htmlTemplate", null);
                ifaceNode.put("_snapshot_cssTemplate", null);
                ifaceNode.put("_snapshot_jsTemplate", null);
                ifaceNode.put("_snapshot_templateVariables", null);
                ifaceNode.put("_snapshot_name", null);
                ifaceNode.put("_snapshot_description", null);
                ifaceNode.put("_snapshot_interfaceType", null);
                ifaceNode.put("_snapshot_data", null);
                ifaceNode.put("_snapshot_variableMappings", variableMappings);
                ifaceNode.put("_snapshot_actionMappings", actionMappings);
                continue;
            }
            ifaceNode.put("_snapshot_htmlTemplate", entity.getHtmlTemplate());
            ifaceNode.put("_snapshot_cssTemplate", entity.getCssTemplate());
            ifaceNode.put("_snapshot_jsTemplate", entity.getJsTemplate());
            ifaceNode.put("_snapshot_templateVariables", entity.getTemplateVariables());
            ifaceNode.put("_snapshot_name", entity.getName());
            ifaceNode.put("_snapshot_description", entity.getDescription());
            ifaceNode.put("_snapshot_interfaceType", entity.getInterfaceType());
            ifaceNode.put("_snapshot_data", entity.getData());
            ifaceNode.put("_snapshot_variableMappings", variableMappings);
            ifaceNode.put("_snapshot_actionMappings", actionMappings);
            // FORM-typed interface bindings - without these, an acquired
            // interface that submits to a backing datasource silently breaks.
            ifaceNode.put("_snapshot_formFields", entity.getFormFields());
            ifaceNode.put("_snapshot_targetTable", entity.getTargetTable());
            ifaceNode.put("_snapshot_dataSourceId", entity.getDataSourceId());
            Object formDsId = entity.getDataSourceId();
            if (formDsId != null && !existingDsIds.contains(formDsId.toString())) {
                formDsToInject.add(formDsId.toString());
            }
        }
        if (!missing.isEmpty()) {
            logger.warn("Publication for tenant {} references {} interface(s) that could not be loaded - they will preview empty: {}",
                    tenantId, missing.size(), String.join(", ", missing));
        }
        // Inject FORM-referenced datasources missing from tables[] as placeholder
        // table nodes (mirrors injectAgentReferencedResources) so the following
        // enrichPlanWithDatasourceData snapshots them and the acquirer gets a
        // cloned copy that the FORM binding remap can target.
        if (!formDsToInject.isEmpty()) {
            List<Map<String, Object>> tables = plan.get("tables") instanceof List
                    ? new ArrayList<>((List<Map<String, Object>>) plan.get("tables"))
                    : new ArrayList<>();
            for (String dsId : formDsToInject) {
                Map<String, Object> placeholder = new HashMap<>();
                placeholder.put("dataSourceId", dsId);
                placeholder.put("_injected_by_interface", true);
                tables.add(placeholder);
            }
            plan.put("tables", tables);
        }
    }

    // NOTE: a duplicate cloneInterfacesForTenant lived here previously and was
    // never called - the live acquire path delegates to
    // SnapshotCloneService.cloneInterfacesForTenant. Removed in the audit
    // round to eliminate the drift surface (the duplicate had stale field
    // handling and would have shipped FORM bindings broken even after the
    // canonical version was fixed).

    // ========================================================================
    // DataSource enrichment (publish time) and cloning (acquire time)
    // ========================================================================

    /**
     * Enrich table nodes in the plan with DataSource structure data.
     * Uses batch loading (single query) to avoid N+1.
     * Only structure is stored -- no row data (DataSourceItem).
     */
    @SuppressWarnings("unchecked")
    void enrichPlanWithDatasourceData(Map<String, Object> plan, String tenantId) {
        enrichPlanWithDatasourceData(plan, tenantId, null);
    }

    @SuppressWarnings("unchecked")
    void enrichPlanWithDatasourceData(Map<String, Object> plan, String tenantId, String organizationId) {
        Object tablesRaw = plan.get("tables");
        if (!(tablesRaw instanceof List)) return;

        List<Map<String, Object>> tables = (List<Map<String, Object>>) tablesRaw;

        // Collect all unique datasource IDs
        List<Long> dsIds = tables.stream()
                .map(n -> n.get("dataSourceId"))
                .filter(Objects::nonNull)
                .map(id -> { try { return Long.parseLong(id.toString()); } catch (NumberFormatException e) { return null; } })
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (dsIds.isEmpty()) return;

        // Single batch query via DataSourceClient
        Map<Long, DataSourceDto> dsMap = dataSourceClient.bulkFind(dsIds, tenantId, organizationId).stream()
                .filter(ds -> ScopeGuard.isInStrictScope(tenantId, organizationId,
                        ds.tenantId(), ds.organizationId()))
                .collect(Collectors.toMap(DataSourceDto::id, Function.identity()));

        for (Map<String, Object> tableNode : tables) {
            Object dsIdRaw = tableNode.get("dataSourceId");
            if (dsIdRaw == null) continue;
            try {
                Long dataSourceId = Long.parseLong(dsIdRaw.toString());
                DataSourceDto ds = dsMap.get(dataSourceId);
                if (ds != null) {
                    tableNode.put("_snapshot_ds_name", ds.name());
                    tableNode.put("_snapshot_ds_description", ds.description());
                    tableNode.put("_snapshot_ds_sourceType", ds.sourceType() != null ? ds.sourceType().name() : "INLINE");
                    tableNode.put("_snapshot_ds_sourceConfig", ds.sourceConfig());
                    tableNode.put("_snapshot_ds_columnOrder", ds.columnOrder());
                    tableNode.put("_snapshot_ds_mappingSpec", ds.mappingSpec() != null
                            ? objectMapper.convertValue(ds.mappingSpec(), new TypeReference<Map<String, Object>>() {}) : null);
                }
            } catch (NumberFormatException e) {
                // skip
            }
        }
    }

    /**
     * Enrich table nodes in the plan with DataSourceItem rows (row data).
     * Called after enrichPlanWithDatasourceData() which captures structure.
     * This captures actual row data so acquirers get pre-populated tables.
     */
    @SuppressWarnings("unchecked")
    void enrichPlanWithDatasourceItems(Map<String, Object> plan, String tenantId) {
        enrichPlanWithDatasourceItems(plan, tenantId, null);
    }

    @SuppressWarnings("unchecked")
    void enrichPlanWithDatasourceItems(Map<String, Object> plan, String tenantId, String organizationId) {
        Object tablesRaw = plan.get("tables");
        if (!(tablesRaw instanceof List)) return;

        List<Map<String, Object>> tables = (List<Map<String, Object>>) tablesRaw;

        // Track already-snapshotted datasource IDs to avoid duplicating items
        // when multiple table nodes reference the same datasource
        Set<Long> snapshotted = new HashSet<>();

        for (Map<String, Object> tableNode : tables) {
            Object dsIdRaw = tableNode.get("dataSourceId");
            if (dsIdRaw == null) continue;
            try {
                Long dataSourceId = Long.parseLong(dsIdRaw.toString());
                if (snapshotted.contains(dataSourceId)) continue;
                snapshotted.add(dataSourceId);
                if (!tableNode.containsKey("_snapshot_ds_name")) {
                    continue;
                }

                List<DataSourceItemDto> items = dataSourceClient.getAllItems(dataSourceId, tenantId, organizationId);
                if (items.isEmpty()) continue;

                // Store as lightweight maps: {data: {...}, priority: N}
                List<Map<String, Object>> itemSnapshots = items.stream()
                        .map(item -> {
                            Map<String, Object> snapshot = new HashMap<>();
                            snapshot.put("data", item.data());
                            snapshot.put("priority", item.priority());
                            return snapshot;
                        })
                        .collect(Collectors.toList());

                tableNode.put("_snapshot_ds_items", itemSnapshots);
                logger.info("Snapshotted {} items for datasource {}", items.size(), dataSourceId);
            } catch (NumberFormatException e) {
                // skip
            }
        }
    }

    /**
     * Clone datasource structure from the enriched plan into the acquiring tenant.
     * For each table node with _snapshot_ds_* data, create a new empty DataSource
     * (structure only, no rows) and rewrite the dataSourceId in the plan.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> cloneDatasourcesForTenant(Map<String, Object> plan, String tenantId, UUID publicationId) {
        Object tablesRaw = plan.get("tables");
        if (!(tablesRaw instanceof List)) return Map.of();

        List<Map<String, Object>> tables = new ArrayList<>((List<Map<String, Object>>) tablesRaw);
        plan.put("tables", tables);

        // Track already-cloned datasource IDs to avoid creating duplicates
        // when multiple table nodes reference the same datasource
        Map<String, Long> clonedDsMap = new HashMap<>();

        for (Map<String, Object> tableNode : tables) {
            Object snapshotName = tableNode.get("_snapshot_ds_name");
            if (snapshotName == null) continue;

            String oldDsId = tableNode.get("dataSourceId") != null ? tableNode.get("dataSourceId").toString() : null;

            // Check if we already cloned this datasource (same oldDsId)
            if (oldDsId != null && clonedDsMap.containsKey(oldDsId)) {
                tableNode.put("dataSourceId", clonedDsMap.get(oldDsId).toString());
                removeSnapshotDsFields(tableNode);
                continue;
            }

            // Build snapshot data for remote creation
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("name", snapshotName.toString());
            if (tableNode.get("_snapshot_ds_description") != null) {
                snapshot.put("description", tableNode.get("_snapshot_ds_description").toString());
            }
            snapshot.put("sourceType", tableNode.get("_snapshot_ds_sourceType") != null
                    ? tableNode.get("_snapshot_ds_sourceType").toString() : "INLINE");
            snapshot.put("sourceConfig", tableNode.get("_snapshot_ds_sourceConfig") instanceof Map
                    ? tableNode.get("_snapshot_ds_sourceConfig") : Map.of());
            snapshot.put("columnOrder", tableNode.get("_snapshot_ds_columnOrder") instanceof List
                    ? tableNode.get("_snapshot_ds_columnOrder") : List.of());
            snapshot.put("mappingSpec", tableNode.get("_snapshot_ds_mappingSpec"));
            snapshot.put("sourcePublicationId", publicationId.toString());

            // Create new DataSource via datasource-service
            DataSourceDto saved = dataSourceClient.createFromSnapshot(snapshot, tenantId);
            if (saved == null) {
                logger.error("Failed to clone datasource from snapshot for tenant {}", tenantId);
                removeSnapshotDsFields(tableNode);
                continue;
            }

            String newDsId = saved.id().toString();

            // Inject snapshotted items into the cloned datasource
            injectSnapshotItems(tableNode, saved.id(), tenantId);

            // Track to handle multiple table nodes referencing the same datasource
            if (oldDsId != null) {
                clonedDsMap.put(oldDsId, saved.id());
            }

            // Rewrite the datasource ID in the plan
            tableNode.put("dataSourceId", newDsId);
            logger.info("Cloned datasource {} -> {} for tenant {}", oldDsId, newDsId, tenantId);

            removeSnapshotDsFields(tableNode);
        }

        // Build old->new string mapping for agent toolsConfig remapping
        Map<String, String> dsMapping = new HashMap<>();
        for (var entry : clonedDsMap.entrySet()) {
            dsMapping.put(entry.getKey(), entry.getValue().toString());
        }
        return dsMapping;
    }

    /**
     * Remove _snapshot_ds_* fields from a table node after cloning.
     */
    private void removeSnapshotDsFields(Map<String, Object> tableNode) {
        tableNode.remove("_snapshot_ds_name");
        tableNode.remove("_snapshot_ds_description");
        tableNode.remove("_snapshot_ds_sourceType");
        tableNode.remove("_snapshot_ds_sourceConfig");
        tableNode.remove("_snapshot_ds_columnOrder");
        tableNode.remove("_snapshot_ds_mappingSpec");
        tableNode.remove("_snapshot_ds_items");
    }

    /**
     * Inject snapshotted DataSourceItems from the plan into a newly cloned datasource.
     */
    @SuppressWarnings("unchecked")
    private void injectSnapshotItems(Map<String, Object> tableNode, Long newDsId, String tenantId) {
        Object itemsRaw = tableNode.get("_snapshot_ds_items");
        if (!(itemsRaw instanceof List)) return;

        List<Map<String, Object>> itemSnapshots = (List<Map<String, Object>>) itemsRaw;
        if (itemSnapshots.isEmpty()) return;

        int count = dataSourceClient.bulkInsertItems(newDsId, itemSnapshots, tenantId);
        if (count > 0) {
            logger.info("Injected {} items into cloned datasource {}", count, newDsId);
            breakdownService.increment(tenantId, "DATA", count * 200L, count); // estimate ~200 bytes per item
        }
    }

    // ========================================================================
    // Agent enrichment (publish time) and cloning (acquire time)
    // ========================================================================

    /**
     * Enrich agent nodes in the plan with AgentDto data and scoped toolsConfig.
     * Collects resource IDs present in the plan and restricts each agent's toolsConfig
     * to only reference resources that exist in the plan.
     */
    @SuppressWarnings("unchecked")
    void enrichPlanWithAgentData(Map<String, Object> plan, String tenantId) {
        enrichPlanWithAgentData(plan, tenantId, null);
    }

    @SuppressWarnings("unchecked")
    void enrichPlanWithAgentData(Map<String, Object> plan, String tenantId, String organizationId) {
        Object agentsRaw = plan.get("agents");
        if (!(agentsRaw instanceof List)) return;

        List<Map<String, Object>> agents = (List<Map<String, Object>>) agentsRaw;

        // Collect all resource IDs present in the plan for scoping
        Set<String> planTableIds = extractPlanDataSourceIds(plan);
        Set<String> planInterfaceIds = extractPlanInterfaceIds(plan);
        Set<String> planAgentConfigIds = extractPlanAgentConfigIds(plan);

        // Batch load all referenced agent entities via AgentClient
        List<UUID> agentUuids = agents.stream()
                .map(a -> a.get("agentConfigId"))
                .filter(Objects::nonNull)
                .map(id -> { try { return UUID.fromString(id.toString()); } catch (Exception e) { return null; } })
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (agentUuids.isEmpty()) return;

        Map<UUID, AgentDto> entityMap = agentClient.bulkFind(agentUuids, tenantId, organizationId).stream()
                .filter(entity -> ScopeGuard.isInStrictScope(tenantId, organizationId,
                        entity.getTenantId(), entity.getOrganizationId()))
                .collect(Collectors.toMap(AgentDto::getId, Function.identity()));

        for (Map<String, Object> agentNode : agents) {
            Object configIdRaw = agentNode.get("agentConfigId");
            if (configIdRaw == null) continue;

            UUID configId;
            try { configId = UUID.fromString(configIdRaw.toString()); }
            catch (Exception e) { continue; }

            AgentDto entity = entityMap.get(configId);
            if (entity == null) continue;

            // Snapshot core agent fields. Symmetric with
            // AgentPublicationService.buildAgentSnapshot - every field that
            // affects execution must be captured so the cloned agent in the
            // acquirer's tenant runs identically to the publisher's.
            agentNode.put("_snapshot_agent_name", entity.getName());
            agentNode.put("_snapshot_agent_description", entity.getDescription());
            agentNode.put("_snapshot_agent_systemPrompt", entity.getSystemPrompt());
            agentNode.put("_snapshot_agent_modelProvider", entity.getModelProvider());
            agentNode.put("_snapshot_agent_modelName", entity.getModelName());
            agentNode.put("_snapshot_agent_temperature", entity.getTemperature() != null ? entity.getTemperature().doubleValue() : null);
            agentNode.put("_snapshot_agent_maxTokens", entity.getMaxTokens());
            agentNode.put("_snapshot_agent_maxIterations", entity.getMaxIterations());
            agentNode.put("_snapshot_agent_executionTimeout", entity.getExecutionTimeout());
            agentNode.put("_snapshot_agent_inactivityTimeout", entity.getInactivityTimeout());
            agentNode.put("_snapshot_agent_avatarUrl", entity.getAvatarUrl());
            agentNode.put("_snapshot_agent_config", entity.getConfig());
            agentNode.put("_snapshot_agent_dataSourceId", entity.getDataSourceId());
            // V100 per-agent guard overrides - fall back to platform defaults
            // on the acquirer's side if missing, but the publisher's values
            // must travel with the snapshot when set.
            agentNode.put("_snapshot_agent_maxPerResourcePerTurn", entity.getMaxPerResourcePerTurn());
            agentNode.put("_snapshot_agent_loopIdenticalStop", entity.getLoopIdenticalStop());
            agentNode.put("_snapshot_agent_loopConsecutiveStop", entity.getLoopConsecutiveStop());
            // Stage 5.2b COLD summariser overrides
            agentNode.put("_snapshot_agent_compactionModelProvider", entity.getCompactionModelProvider());
            agentNode.put("_snapshot_agent_compactionModelName", entity.getCompactionModelName());
            // Budget cap - without this, an acquired agent runs uncapped.
            agentNode.put("_snapshot_agent_creditBudget", entity.getCreditBudget());
            agentNode.put("_snapshot_agent_budgetResetMode", entity.getBudgetResetMode());
            // M3: per-agent reasoning-effort override (affects CLI/bridge model behaviour) - without
            // this an acquired agent loses the publisher's effort setting and falls back to default.
            agentNode.put("_snapshot_agent_reasoningEffort", entity.getReasoningEffort());
            // Webhook + schedule trigger configs (token regenerated on
            // acquire by AgentPublicationService.buildAgentSnapshot pattern)
            try {
                Map<String, Object> webhookConfig = agentClient.getWebhookConfig(configId, tenantId, organizationId);
                if (webhookConfig != null) {
                    Map<String, Object> webhookSnapshot = new LinkedHashMap<>();
                    webhookSnapshot.put("httpMethod", webhookConfig.getOrDefault("httpMethod", "POST"));
                    webhookSnapshot.put("memoryEnabled", webhookConfig.getOrDefault("memoryEnabled", false));
                    agentNode.put("_snapshot_agent_webhookConfig", webhookSnapshot);
                }
            } catch (Exception e) {
                logger.debug("[Snapshot] webhookConfig fetch failed for agent {}: {}", configId, e.getMessage());
            }
            try {
                Map<String, Object> scheduleConfig = agentClient.getScheduleConfig(configId, tenantId, organizationId);
                if (scheduleConfig != null) {
                    Map<String, Object> scheduleSnapshot = new LinkedHashMap<>();
                    scheduleSnapshot.put("cronExpression", scheduleConfig.get("cronExpression"));
                    scheduleSnapshot.put("timezone", scheduleConfig.getOrDefault("timezone", "UTC"));
                    scheduleSnapshot.put("maxExecutions", scheduleConfig.get("maxExecutions"));
                    scheduleSnapshot.put("schedulePrompt", scheduleConfig.get("schedulePrompt"));
                    scheduleSnapshot.put("withMemory", scheduleConfig.getOrDefault("withMemory", false));
                    agentNode.put("_snapshot_agent_scheduleConfig", scheduleSnapshot);
                }
            } catch (Exception e) {
                logger.debug("[Snapshot] scheduleConfig fetch failed for agent {}: {}", configId, e.getMessage());
            }

            // Scope toolsConfig to plan resources only
            Map<String, Object> scopedToolsConfig = scopeToolsConfig(
                    entity.getToolsConfig(), planTableIds, planInterfaceIds, planAgentConfigIds);
            agentNode.put("_snapshot_agent_toolsConfig", scopedToolsConfig);

            // Snapshot skills via AgentClient
            List<AgentSkillDto> agentSkills = agentClient.getSkillsForAgent(configId, tenantId, organizationId);
            if (!agentSkills.isEmpty()) {
                List<Map<String, Object>> skillSnapshots = new ArrayList<>();
                for (AgentSkillDto as : agentSkills) {
                    Map<String, Object> skillData = new HashMap<>();
                    skillData.put("name", as.getSkillName());
                    skillData.put("description", as.getSkillDescription());
                    skillData.put("icon", as.getSkillIcon());
                    skillData.put("instructions", as.getSkillInstructions());
                    skillData.put("sortOrder", as.getSortOrder());
                    skillSnapshots.add(skillData);
                }
                agentNode.put("_snapshot_agent_skills", skillSnapshots);
            }

            logger.info("Enriched agent node {} with entity {} for publish", agentNode.get("id"), configId);
        }
    }

    /**
     * Scope toolsConfig to only include resources present in the plan, driven by the
     * AUTHORITATIVE per-family grant ({@code <family>Grant} ∈ none|all|custom):
     *
     * <ul>
     *   <li>{@code all} → {@code custom} + ALL plan IDs of that family. The plan IS
     *       the workflow's dependency set; an "all" authorization never travels to the
     *       acquirer (a publisher-authored agent must not get blanket access over the
     *       acquirer's tenant), and it never enumerates the publisher's tenant either.</li>
     *   <li>{@code custom} → {@code custom} + intersection of the explicit list with plan IDs.</li>
     *   <li>{@code none} → {@code none} + empty list (a stale list never resurrects access).</li>
     *   <li>absent/invalid grant (legacy rows) → derived from the LIST as before:
     *       null/absent list = all → plan IDs; [] → []; [ids] → intersection.</li>
     * </ul>
     *
     * <p>workflows: always {@code ["__self__"]} (custom). applications: always none
     * (not cloned). tools (MCP catalogue) and webSearch kept as-is; mode forced to
     * {@code custom}.
     *
     * <p>The grants are EMITTED alongside the lists: the acquirer's normalizeToolsConfig
     * preserves a valid present grant, which fixes the silent all→none collapse
     * (a grant=all row persists an EMPTY list, which the old list-only logic read as
     * "explicitly blocked" - the cloned agent lost access even to the plan's resources).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> scopeToolsConfig(Map<String, Object> original,
                                                  Set<String> planTableIds,
                                                  Set<String> planInterfaceIds,
                                                  Set<String> planAgentConfigIds) {
        Map<String, Object> scoped = new HashMap<>();

        if (original != null) {
            // Keep tools (MCP global) and webSearch as-is
            if (original.containsKey("tools")) scoped.put("tools", original.get("tools"));
            if (original.containsKey("webSearch")) scoped.put("webSearch", original.get("webSearch"));
        }

        scoped.put("mode", "custom");
        scopeFamily(scoped, original, "tables", planTableIds);
        scopeFamily(scoped, original, "interfaces", planInterfaceIds);
        scopeFamily(scoped, original, "agents", planAgentConfigIds);
        scoped.put("workflows", List.of("__self__"));
        scoped.put("workflowsGrant", "custom");
        // applications are never cloned - deny explicitly so the clone cannot
        // inherit an applications grant from the publisher's config.
        scoped.put("applications", List.of());
        scoped.put("applicationsGrant", "none");

        return scoped;
    }

    /**
     * Resolve one family's scoped id list + emitted grant (see {@link #scopeToolsConfig}).
     * The emitted grant is re-derived from the RESULT ({@code custom} when non-empty,
     * {@code none} when empty) so the clone's config is self-consistent - never "custom"
     * with a meaningless empty payload, and never "all".
     */
    private void scopeFamily(Map<String, Object> scoped, Map<String, Object> original,
                              String key, Set<String> planIds) {
        Object grantRaw = original != null ? original.get(key + "Grant") : null;
        String grant = grantRaw != null ? grantRaw.toString() : null;
        List<String> ids;
        if ("all".equals(grant)) {
            // The plan is the dependency set: downscope "all" to every plan resource
            // of this family (fixes the all→none collapse; see method javadoc).
            ids = new ArrayList<>(planIds);
        } else if ("none".equals(grant)) {
            ids = List.of();
        } else if ("custom".equals(grant)) {
            Object value = original.get(key);
            ids = (value instanceof List<?> list)
                    ? list.stream().map(Object::toString).filter(planIds::contains).collect(Collectors.toList())
                    : List.of();
        } else {
            // Legacy row without a valid grant: list-derived semantics, unchanged.
            ids = scopeResourceList(original, key, planIds);
        }
        scoped.put(key, ids);
        scoped.put(key + "Grant", ids.isEmpty() ? "none" : "custom");
    }

    /**
     * For a given resource key, intersect the configured IDs with what's in the plan.
     * null/absent = all -> return all plan IDs; [] = none -> return []; [ids] -> intersection.
     */
    @SuppressWarnings("unchecked")
    private List<String> scopeResourceList(Map<String, Object> toolsConfig, String key, Set<String> planIds) {
        if (toolsConfig == null || !toolsConfig.containsKey(key) || toolsConfig.get(key) == null) {
            // null = all -> restrict to plan resources
            return new ArrayList<>(planIds);
        }
        Object value = toolsConfig.get(key);
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return List.of(); // explicitly blocked
            // Intersect with plan
            return list.stream()
                    .map(Object::toString)
                    .filter(planIds::contains)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>(planIds);
    }

    /** Extract all dataSourceId values from plan tables[]. */
    private Set<String> extractPlanDataSourceIds(Map<String, Object> plan) {
        Object tablesRaw = plan.get("tables");
        if (!(tablesRaw instanceof List<?> tables)) return Set.of();
        return tables.stream()
                .filter(t -> t instanceof Map)
                .map(t -> ((Map<?, ?>) t).get("dataSourceId"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    /** Extract all interface IDs from plan interfaces[]. */
    private Set<String> extractPlanInterfaceIds(Map<String, Object> plan) {
        Object ifacesRaw = plan.get("interfaces");
        if (!(ifacesRaw instanceof List<?> ifaces)) return Set.of();
        return ifaces.stream()
                .filter(i -> i instanceof Map)
                .map(i -> ((Map<?, ?>) i).get("id"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    /** Extract all agentConfigId values from plan agents[]. */
    private Set<String> extractPlanAgentConfigIds(Map<String, Object> plan) {
        Object agentsRaw = plan.get("agents");
        if (!(agentsRaw instanceof List<?> agents)) return Set.of();
        return agents.stream()
                .filter(a -> a instanceof Map)
                .map(a -> ((Map<?, ?>) a).get("agentConfigId"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    /**
     * Clone agents from the enriched plan into the acquiring tenant.
     * For each agent node with _snapshot_agent_* data, create a new agent + skills
     * via AgentClient, rewrite agentConfigId in the plan, and remap toolsConfig resource IDs.
     */
    @SuppressWarnings("unchecked")
    private void cloneAgentsForTenant(Map<String, Object> plan, String tenantId, UUID publicationId,
                                       String acquiredWorkflowId,
                                       Map<String, String> interfaceMapping,
                                       Map<String, String> dsMapping) {
        Object agentsRaw = plan.get("agents");
        if (!(agentsRaw instanceof List)) return;

        List<Map<String, Object>> agents = new ArrayList<>((List<Map<String, Object>>) agentsRaw);
        plan.put("agents", agents);

        // Track agent ID mapping (old agentConfigId -> new agentConfigId) for sub-agent remapping
        Map<String, String> agentMapping = new HashMap<>();

        for (Map<String, Object> agentNode : agents) {
            Object snapshotName = agentNode.get("_snapshot_agent_name");
            if (snapshotName == null) continue;

            String oldConfigId = agentNode.get("agentConfigId") != null ? agentNode.get("agentConfigId").toString() : null;

            // Build clone request for AgentClient
            Map<String, Object> cloneRequest = new HashMap<>();
            cloneRequest.put("tenantId", tenantId);
            cloneRequest.put("publicationId", publicationId.toString());
            cloneRequest.put("name", snapshotName.toString());
            cloneRequest.put("description", agentNode.get("_snapshot_agent_description"));
            cloneRequest.put("systemPrompt", agentNode.get("_snapshot_agent_systemPrompt"));
            cloneRequest.put("modelProvider", agentNode.get("_snapshot_agent_modelProvider"));
            cloneRequest.put("modelName", agentNode.get("_snapshot_agent_modelName"));
            cloneRequest.put("temperature", agentNode.get("_snapshot_agent_temperature"));
            cloneRequest.put("maxTokens", agentNode.get("_snapshot_agent_maxTokens"));
            cloneRequest.put("maxIterations", agentNode.get("_snapshot_agent_maxIterations"));
            cloneRequest.put("executionTimeout", agentNode.get("_snapshot_agent_executionTimeout"));
            cloneRequest.put("inactivityTimeout", agentNode.get("_snapshot_agent_inactivityTimeout"));
            cloneRequest.put("config", agentNode.get("_snapshot_agent_config"));

            // Avatar: copy an uploaded/AI file into the ACQUIRER's storage so the clone
            // survives the publisher deleting theirs (presets/http pass through). Org scope
            // comes from the request-bound context (this path has no explicit org param).
            String avatarUrl = agentNode.get("_snapshot_agent_avatarUrl") != null
                    ? agentNode.get("_snapshot_agent_avatarUrl").toString() : null;
            cloneRequest.put("avatarUrl", avatarFileCloneService != null
                    ? avatarFileCloneService.cloneForTenant(avatarUrl, tenantId,
                            com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId())
                    : com.apimarketplace.publication.utils.AvatarUrlPolicy.publishable(avatarUrl));

            // Restore dataSourceId with remapping to the cloned datasource
            Object dsIdRaw = agentNode.get("_snapshot_agent_dataSourceId");
            if (dsIdRaw instanceof Number n && n.longValue() > 0) {
                String oldDsId = String.valueOf(n.longValue());
                String newDsId = dsMapping.get(oldDsId);
                if (newDsId != null) {
                    cloneRequest.put("dataSourceId", Long.parseLong(newDsId));
                }
            }

            // Scoped toolsConfig
            Object toolsConfigRaw = agentNode.get("_snapshot_agent_toolsConfig");
            if (toolsConfigRaw instanceof Map) {
                cloneRequest.put("toolsConfig", toolsConfigRaw);
            }

            // Skills
            Object skillsRaw = agentNode.get("_snapshot_agent_skills");
            if (skillsRaw instanceof List) {
                cloneRequest.put("skills", skillsRaw);
            }

            // Clone agent via AgentClient (creates agent + skills + links in one call)
            Map<String, Object> result = agentClient.cloneFromSnapshot(cloneRequest);
            if (result == null) {
                logger.warn("Failed to clone agent {} for tenant {}", oldConfigId, tenantId);
                removeSnapshotAgentFields(agentNode);
                continue;
            }

            String newConfigId = (String) result.get("agentId");

            if (oldConfigId != null && newConfigId != null) {
                agentMapping.put(oldConfigId, newConfigId);
            }

            // Rewrite agentConfigId in plan
            agentNode.put("agentConfigId", newConfigId);
            logger.info("Cloned agent {} -> {} for tenant {}", oldConfigId, newConfigId, tenantId);

            // Remove snapshot fields
            removeSnapshotAgentFields(agentNode);
        }

        // Second pass: remap resource IDs in each cloned agent's toolsConfig
        for (Map<String, Object> agentNode : agents) {
            String newConfigId = agentNode.get("agentConfigId") != null ? agentNode.get("agentConfigId").toString() : null;
            if (newConfigId == null) continue;

            try {
                UUID agentId = UUID.fromString(newConfigId);

                // Build mappings for the remap call
                Map<String, Object> mappings = new HashMap<>();
                mappings.put("tables", dsMapping);
                mappings.put("interfaces", interfaceMapping);
                mappings.put("agents", agentMapping);
                mappings.put("workflowId", acquiredWorkflowId);

                agentClient.remapToolsConfig(agentId, mappings);
            } catch (Exception e) {
                logger.warn("Failed to remap toolsConfig for agent {}: {}", newConfigId, e.getMessage());
            }
        }
    }

    private void removeSnapshotAgentFields(Map<String, Object> agentNode) {
        agentNode.remove("_snapshot_agent_name");
        agentNode.remove("_snapshot_agent_description");
        agentNode.remove("_snapshot_agent_systemPrompt");
        agentNode.remove("_snapshot_agent_modelProvider");
        agentNode.remove("_snapshot_agent_modelName");
        agentNode.remove("_snapshot_agent_temperature");
        agentNode.remove("_snapshot_agent_maxTokens");
        agentNode.remove("_snapshot_agent_maxIterations");
        agentNode.remove("_snapshot_agent_executionTimeout");
        agentNode.remove("_snapshot_agent_inactivityTimeout");
        agentNode.remove("_snapshot_agent_avatarUrl");
        agentNode.remove("_snapshot_agent_config");
        agentNode.remove("_snapshot_agent_toolsConfig");
        agentNode.remove("_snapshot_agent_skills");
        agentNode.remove("_snapshot_agent_dataSourceId");
    }

    // ========================================================================
    // DataInput file snapshot (publish time) and cloning (acquire time)
    // ========================================================================

    /**
     * Snapshot DataInput files at publish time: record file references in the plan
     * so they can be cloned at acquire time.
     *
     * Note: S3 file operations (download/upload) are delegated to the orchestrator
     * via OrchestratorInternalClient since FileStorageService is orchestrator-internal.
     * The plan already contains file.path references pointing to existing S3 keys;
     * at publish time we copy them to a publication-owned path via orchestrator.
     */
    @SuppressWarnings("unchecked")
    void snapshotDataInputFiles(Map<String, Object> plan, UUID publicationId, String sourceTenantId) {
        if (publicationId == null) return;
        // organizationId=null: snapshot files land under the `_publications` namespace and are
        // served by the anonymous marketplace showcase via HMAC-by-path (no by-id lookup), so an
        // org-scoped storage row isn't required. The acquirer later re-copies them by path and
        // mints a fresh org-scoped id (SnapshotCloneService forwards the acquirer's org).
        copyDataInputFiles(plan, "_publications", sourceTenantId, publicationId.toString(), "snapshot", null);
    }

    /**
     * Companion to {@link #snapshotDataInputFiles}: scans the OTHER subtrees of
     * {@code planSnapshot} that carry FileRef Maps at publish time and copies
     * each file under {@code _publications/{pubId}/...}. Without this, a FileRef
     * embedded in an interface's resolved data ({@code _snapshot_data}) or an
     * agent's config / toolsConfig / skills ({@code _snapshot_agent_*}) survives
     * at the publisher's tenant path through publish, and the acquire-time
     * {@code SnapshotCloneService.scanAndCloneFileRefs} allowlist correctly
     * refuses to copy it (path doesn't start with {@code _publications/{pubId}/}),
     * leaving the cloned interface / agent row with a cross-tenant path that
     * 401s at the acquirer's first runtime render.
     *
     * <p>Reuses {@link #walkAndCopyFileRefs} via {@link #copyFileRefsInRunState}'s
     * helper so detection + mutation semantics stay identical (strict {@code _type})
     * - the {@code !startsWith("_publications/")} guard makes it idempotent on
     * re-publish.
     *
     * @param plan the planSnapshot Map (interfaces[]._snapshot_data,
     *             agents[]._snapshot_agent_{config,toolsConfig,skills})
     * @param publicationId target publication; namespace is
     *             {@code _publications/{publicationId}/...}
     * @param tenantId publisher tenant - forwarded to walkAndCopyFileRefs for logging
     */
    @SuppressWarnings("unchecked")
    void snapshotPlanEmbeddedFileRefs(Map<String, Object> plan, UUID publicationId, String tenantId) {
        if (publicationId == null || plan == null) return;
        int[] count = new int[]{0};
        Object interfaces = plan.get("interfaces");
        if (interfaces instanceof List<?> ifaceList) {
            for (Object ifaceNode : ifaceList) {
                if (!(ifaceNode instanceof Map<?, ?> ifaceMap)) continue;
                Object data = ((Map<String, Object>) ifaceMap).get("_snapshot_data");
                if (data != null) {
                    walkAndCopyFileRefs(data, publicationId, tenantId, "plan-iface-data", count);
                }
            }
        }
        Object agents = plan.get("agents");
        if (agents instanceof List<?> agentList) {
            for (Object agentNode : agentList) {
                if (!(agentNode instanceof Map<?, ?> agentMap)) continue;
                Map<String, Object> a = (Map<String, Object>) agentMap;
                Object config = a.get("_snapshot_agent_config");
                if (config != null) {
                    walkAndCopyFileRefs(config, publicationId, tenantId, "plan-agent-config", count);
                }
                Object toolsConfig = a.get("_snapshot_agent_toolsConfig");
                if (toolsConfig != null) {
                    walkAndCopyFileRefs(toolsConfig, publicationId, tenantId, "plan-agent-toolsConfig", count);
                }
                Object skills = a.get("_snapshot_agent_skills");
                if (skills != null) {
                    walkAndCopyFileRefs(skills, publicationId, tenantId, "plan-agent-skills", count);
                }
            }
        }
        if (count[0] > 0) {
            logger.info("[ShowcaseSnapshot/plan] copied {} embedded FileRef(s) (interface/agent) under publication namespace {}",
                    count[0], publicationId);
        }
    }

    /**
     * Clone DataInput files at acquire time: copy S3 blobs from the publication path
     * to the acquirer's tenant path.
     */
    @SuppressWarnings("unchecked")
    private void cloneDataInputFilesForTenant(Map<String, Object> plan, String tenantId, String workflowId) {
        copyDataInputFiles(plan, tenantId, "_publications", workflowId, "publication", null);
    }

    /**
     * Shared logic: scan cores[] for DataInput file items and delegate S3 copy
     * operations to the orchestrator service via its internal API.
     *
     * The orchestrator handles the actual S3 download/upload since FileStorageService
     * is orchestrator-internal. The plan file.path references are rewritten by the
     * orchestrator and returned in the response.
     */
    @SuppressWarnings("unchecked")
    private void copyDataInputFiles(Map<String, Object> plan, String tenantId, String sourceTenantId,
                                    String workflowId, String runId, String organizationId) {
        Object coresRaw = plan.get("cores");
        if (!(coresRaw instanceof List<?> cores)) return;

        for (Object coreRaw : cores) {
            if (!(coreRaw instanceof Map<?, ?> coreMap)) continue;

            Object dataInputRaw = coreMap.get("dataInput");
            if (!(dataInputRaw instanceof Map<?, ?> dataInputMap)) continue;

            Object itemsRaw = dataInputMap.get("items");
            if (!(itemsRaw instanceof List<?> items)) continue;

            for (Object itemRaw : items) {
                if (!(itemRaw instanceof Map<?, ?> itemMap)) continue;

                if (!"file".equals(itemMap.get("type"))) continue;

                Object fileRaw = itemMap.get("file");
                if (!(fileRaw instanceof Map<?, ?> ignored)) continue;

                Map<String, Object> fileMap = (Map<String, Object>) fileRaw;
                String sourcePath = fileMap.get("path") != null ? fileMap.get("path").toString() : null;
                if (sourcePath == null || sourcePath.isBlank()) continue;

                String fileName = fileMap.get("name") != null ? fileMap.get("name").toString() : "file";
                String mimeType = fileMap.get("mimeType") != null ? fileMap.get("mimeType").toString() : "application/octet-stream";
                String stepAlias = coreMap.get("id") != null ? coreMap.get("id").toString() : "data_input";

                // Delegate file copy to orchestrator (which owns FileStorageService)
                try {
                    Map<String, Object> copyRequest = new HashMap<>();
                    copyRequest.put("sourcePath", sourcePath);
                    copyRequest.put("sourceTenantId",
                            sourceTenantId != null && !sourceTenantId.isBlank()
                                    ? sourceTenantId
                                    : sourceTenantIdForPath(sourcePath));
                    copyRequest.put("tenantId", tenantId);
                    copyRequest.put("workflowId", workflowId);
                    copyRequest.put("runId", runId);
                    copyRequest.put("stepAlias", stepAlias);
                    copyRequest.put("fileName", fileName);
                    copyRequest.put("mimeType", mimeType);

                    Map<String, Object> copyResult = orchestratorClient.copyFile(copyRequest, organizationId);
                    if (copyResult != null && copyResult.get("newPath") != null) {
                        fileMap.put("path", copyResult.get("newPath").toString());
                        // Rewrite the opaque id to the NEW storage row (by-id URL is built from id);
                        // drop the stale source id if the copy returned none.
                        if (copyResult.get("newId") instanceof String newId) {
                            fileMap.put("id", newId);
                        } else {
                            fileMap.remove("id");
                        }
                        logger.info("Copied DataInput file {} -> {}", sourcePath, copyResult.get("newPath"));
                    } else {
                        logger.warn("Failed to copy DataInput file {}: no newPath in response", sourcePath);
                    }
                } catch (Exception e) {
                    logger.error("Failed to copy DataInput file {}: {}", sourcePath, e.getMessage());
                    throw new RuntimeException("DataInput file copy failed: " + sourcePath, e);
                }
            }
        }
    }

    private static String sourceTenantIdForPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (path.contains("://")) {
            return null;
        }
        int idx = path.indexOf('/');
        if (idx <= 0) {
            return null;
        }
        return path.substring(0, idx);
    }

    // ========================================================================
    // Security: strip sensitive credentials from published plans
    // ========================================================================

    /**
     * Strip all sensitive credentials from the plan at publish time
     * (defense-in-depth - also stripped at acquire time by
     * {@link SnapshotCloneService}).
     *
     * <p>Covers two layers:
     * <ol>
     *   <li><b>Known-typed fields</b>: HTTP {@code authConfig}, sendEmail
     *       {@code credentialId}, cryptoJwt {@code key/secret/token}, and
     *       any node carrying a {@code credentialId} field (covers MCP API
     *       calls and OAuth-bound cores).</li>
     *   <li><b>Generic recursive scrub</b>: everything inside cores, mcps,
     *       agents, triggers, interfaces is walked recursively; any key
     *       matching a credential-shaped substring (password, token,
     *       api_key, bearer, secret, …) has its value redacted. This catches
     *       custom auth headers, MCP tool params, embedded keys in HTTP
     *       bodies, etc.</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    void stripSensitiveCredentials(Map<String, Object> plan) {
        // Layer 1 - known-typed sub-objects on each core. The recursive
        // layer 2 below catches credential-shaped key names everywhere,
        // but cores carry typed configs whose sensitive fields don't have
        // credential-shaped names (`username`, `host`, `query`, `code`,
        // `localContent`, `payload` …) and would slip through. Strip them
        // by exact field name here.
        Object coresRaw = plan.get("cores");
        if (coresRaw instanceof List<?> cores) {
            for (Object core : cores) {
                if (!(core instanceof Map<?, ?> coreMap)) continue;
                Map<String, Object> mc = (Map<String, Object>) coreMap;

                // http_request - drop the entire authConfig block
                Object httpReq = mc.get("httpRequest");
                if (httpReq instanceof Map<?, ?> httpMap) {
                    safeRemove((Map<String, Object>) httpMap, "authConfig");
                }
                // send_email - credentialId + raw SMTP credentials
                Object sendEmail = mc.get("sendEmail");
                if (sendEmail instanceof Map<?, ?> emailMap) {
                    Map<String, Object> em = (Map<String, Object>) emailMap;
                    safeRemove(em, "credentialId");
                    safeRemove(em, "smtpUsername");
                    safeRemove(em, "smtpPassword");
                }
                // email_inbox - credentialId (IMAP credential is owned by the acquirer)
                Object emailInbox = mc.get("emailInbox");
                if (emailInbox instanceof Map<?, ?> inboxMap) {
                    safeRemove((Map<String, Object>) inboxMap, "credentialId");
                }
                // crypto_jwt - drop key/secret/token AND the payload (JWT
                // claims may carry user PII / session ids)
                Object cryptoJwt = mc.get("cryptoJwt");
                if (cryptoJwt instanceof Map<?, ?> cryptoMap) {
                    Map<String, Object> cm = (Map<String, Object>) cryptoMap;
                    safeRemove(cm, "key");
                    safeRemove(cm, "secret");
                    safeRemove(cm, "token");
                    safeRemove(cm, "payload");
                }
                // ssh - credentialId, raw username/password/privateKey
                Object ssh = mc.get("ssh");
                if (ssh instanceof Map<?, ?> sshMap) {
                    Map<String, Object> s = (Map<String, Object>) sshMap;
                    safeRemove(s, "credentialId");
                    safeRemove(s, "username");
                    safeRemove(s, "password");
                    safeRemove(s, "privateKey");
                }
                // sftp - same as ssh + raw file payload that may have been
                // pasted into the plan inline
                Object sftp = mc.get("sftp");
                if (sftp instanceof Map<?, ?> sftpMap) {
                    Map<String, Object> s = (Map<String, Object>) sftpMap;
                    safeRemove(s, "credentialId");
                    safeRemove(s, "username");
                    safeRemove(s, "password");
                    safeRemove(s, "privateKey");
                    safeRemove(s, "localContent");
                }
                // database - credentialId + connection details
                Object db = mc.get("database");
                if (db instanceof Map<?, ?> dbMap) {
                    Map<String, Object> d = (Map<String, Object>) dbMap;
                    safeRemove(d, "credentialId");
                    safeRemove(d, "username");
                    safeRemove(d, "password");
                    safeRemove(d, "host");
                    safeRemove(d, "databaseName");
                }
                // code - body may carry hardcoded secrets pasted by the
                // publisher. Scan with the same regex set the agent path
                // uses so `sk-XXX`, `AIza...`, `AKIA...`, `ghp_...`, JWT
                // patterns, and `key:value` assignments get redacted.
                Object code = mc.get("code");
                if (code instanceof Map<?, ?> codeMap) {
                    Map<String, Object> c = (Map<String, Object>) codeMap;
                    Object body = c.get("code");
                    if (body instanceof String s) {
                        c.put("code", redactCredentialPatternsInText(s));
                    }
                }
                // rss.url - may embed `?api_key=…` query string; redact
                // common patterns inline.
                Object rss = mc.get("rss");
                if (rss instanceof Map<?, ?> rssMap) {
                    Object url = ((Map<String, Object>) rssMap).get("url");
                    if (url instanceof String u) {
                        ((Map<String, Object>) rssMap).put("url", redactCredentialPatternsInText(u));
                    }
                }
                // Generic credentialId on any core (covers OAuth-bound nodes)
                safeRemove(mc, "credentialId");
                safeRemove(mc, "credentialSource");
            }
        }

        // Strip step-level platformCredentialId on every mcp/agent step. The
        // recursive layer 2 catches `credentialId` by key name but
        // `platformCredentialId` AND `credentialSource` need explicit drops.
        // Defensive against immutable Maps (used in unit tests via Map.of).
        for (String stepBucket : new String[]{"mcps", "agents"}) {
            Object bucket = plan.get(stepBucket);
            if (bucket instanceof List<?> steps) {
                for (Object step : steps) {
                    if (step instanceof Map<?, ?> stepMap) {
                        Map<String, Object> s = (Map<String, Object>) stepMap;
                        safeRemove(s, "platformCredentialId");
                        safeRemove(s, "credentialSource");
                    }
                }
            }
        }

        // Trigger params - webhook auth credentials live in
        // `triggers[].params.{basicPassword, jwtSecretKey, headerSecret, …}`
        // which are not under `cores`. Strip them by name (the recursive
        // layer would catch `password` but misses bare `secret` / `key`
        // because those are intentionally excluded for the false-positive
        // floor - `maxTokens`, `sessionId`, …).
        Object triggers = plan.get("triggers");
        if (triggers instanceof List<?> triggerList) {
            for (Object t : triggerList) {
                if (!(t instanceof Map<?, ?> tm)) continue;
                Object params = ((Map<String, Object>) tm).get("params");
                if (params instanceof Map<?, ?> pm) {
                    Map<String, Object> p = (Map<String, Object>) pm;
                    safeRemove(p, "basicUsername");
                    safeRemove(p, "basicPassword");
                    safeRemove(p, "headerSecret");
                    safeRemove(p, "jwtSecretKey");
                }
            }
        }

        // Layer 2 - generic recursive scrub on the whole plan. The redaction
        // rule is identical to ShowcaseSnapshotBuilder.scrubMap so we have
        // symmetric behavior between plan-snapshot and run-state JSONB.
        scrubRecursivelyForCredentials(plan);
    }

    /**
     * Vendor-specific secret patterns commonly inlined into agent prompts,
     * code bodies, and URL templates. Same set as
     * {@code AgentPublicationService.CREDENTIAL_TEXT_PATTERNS} - keep in
     * sync. Used by {@link #stripSensitiveCredentials} to scrub free-form
     * text leaves where a key-name scrubber can't help.
     */
    private static final java.util.regex.Pattern[] PLAN_CREDENTIAL_TEXT_PATTERNS = {
            java.util.regex.Pattern.compile("sk[-_][A-Za-z0-9_\\-]{16,}"),
            java.util.regex.Pattern.compile("(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{16,}"),
            java.util.regex.Pattern.compile("AIza[A-Za-z0-9_\\-]{20,}"),
            java.util.regex.Pattern.compile("ya29\\.[A-Za-z0-9_\\-]{20,}"),
            java.util.regex.Pattern.compile("(?i)bearer\\s+[A-Za-z0-9_\\-\\.=]+"),
            java.util.regex.Pattern.compile("gh[opsur]_[A-Za-z0-9]{30,}"),
            java.util.regex.Pattern.compile("xox[baprs]-[A-Za-z0-9\\-]{10,}"),
            java.util.regex.Pattern.compile("xapp-[A-Za-z0-9\\-]{10,}"),
            java.util.regex.Pattern.compile("AKIA[0-9A-Z]{16}"),
            java.util.regex.Pattern.compile("AC[a-f0-9]{32}"),
            java.util.regex.Pattern.compile("SK[a-f0-9]{32}"),
            java.util.regex.Pattern.compile("eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}"),
            java.util.regex.Pattern.compile("(?i)(api[_-]?key|password|secret|token|client[_-]?secret|refresh[_-]?token|access[_-]?token)\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-\\.=]{8,}[\"']?")
    };

    private static String redactCredentialPatternsInText(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (java.util.regex.Pattern p : PLAN_CREDENTIAL_TEXT_PATTERNS) {
            out = p.matcher(out).replaceAll("[redacted]");
        }
        return out;
    }

    /**
     * Best-effort {@link Map#remove}. Production plans are mutable JSON-deser
     * Maps but unit tests pass {@code Map.of(...)} immutable maps; swallow
     * the {@link UnsupportedOperationException} so a strip pass on a
     * legitimate-but-immutable nested config doesn't poison the entire
     * publish pipeline.
     */
    private static void safeRemove(Map<String, Object> map, String key) {
        try {
            map.remove(key);
        } catch (UnsupportedOperationException ignored) {
            // Immutable map (Map.of(...) in tests, or a defensive wrap).
            // The key wasn't there to remove - accept and move on.
        }
    }

    /**
     * Best-effort {@link Map#put}. Mirrors {@link #safeRemove} - production
     * plans are mutable JSON-deser maps but unit tests pass {@code Map.of(...)}
     * immutable maps; swallow the {@link UnsupportedOperationException} so a
     * scrub pass on a legitimate-but-immutable nested config doesn't poison the
     * whole publish pipeline.
     */
    private static void safeScrubPut(Map<String, Object> map, String key, Object value) {
        try {
            map.put(key, value);
        } catch (UnsupportedOperationException ignored) {
            // Immutable map (Map.of(...) in tests, or a defensive wrap).
        }
    }

    @SuppressWarnings("unchecked")
    private static void scrubRecursivelyForCredentials(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            for (Map.Entry<String, Object> e : new ArrayList<>(m.entrySet())) {
                String key = e.getKey();
                if (key != null && CredentialKeyDetector.looksSensitive(key)) {
                    safeScrubPut(m, key, CredentialKeyDetector.REDACTED);
                } else if (e.getValue() instanceof String s) {
                    // Inline-secret scrub on EVERY string leaf, not just the
                    // hand-picked code/url fields above. Closes the gap where a
                    // publisher pastes a literal key (Bearer …, sk-/sk_…, AKIA…,
                    // ghp_…, JWT, key=value) into a benignly-named field that the
                    // key-name detector can't flag. Symmetric with
                    // AgentPublicationService.scrubByKeyAndRedactStrings and
                    // ShowcaseSnapshotBuilder, so a secret can't leak to acquirers
                    // through any of the three scrub paths. No-op for clean text.
                    String redacted = redactCredentialPatternsInText(s);
                    if (!redacted.equals(s)) {
                        safeScrubPut(m, key, redacted);
                    }
                } else {
                    scrubRecursivelyForCredentials(e.getValue());
                }
            }
        } else if (node instanceof List<?> list) {
            List<Object> l;
            try {
                l = (List<Object>) list;
            } catch (ClassCastException ex) {
                return;
            }
            for (int i = 0; i < l.size(); i++) {
                Object item = l.get(i);
                if (item instanceof String s) {
                    String redacted = redactCredentialPatternsInText(s);
                    if (!redacted.equals(s)) {
                        try {
                            l.set(i, redacted);
                        } catch (UnsupportedOperationException ignored) {
                            // immutable list (List.of(...) in tests) - leave as-is
                        }
                    }
                } else {
                    scrubRecursivelyForCredentials(item);
                }
            }
        }
    }

    // ========================================================================
    // Sub-workflow enrichment (publish time)
    // ========================================================================

    /**
     * Recursively snapshot sub-workflows referenced by the plan.
     * Collects workflow IDs from 3 sources:
     *   1. cores[].subWorkflow.workflowId (core sub_workflow nodes)
     *   2. agents[]._snapshot_agent_toolsConfig.workflows (agent workflow access)
     *   3. triggers[].type=workflow/error -> id (workflow/error trigger nodes)
     *
     * Cycles are broken with a PER-BRANCH ancestor set (the workflow ids on the current recursion
     * path, pre-seeded with the main workflow): a workflow on the path is skipped, but a child
     * shared by two SIBLING parents (a diamond) is still snapshotted under each - siblings do not
     * share visited state. Self-references are marked with the "__self__" sentinel for acquire-time
     * remapping.
     *
     * @param plan       the plan being enriched (mutated in place)
     * @param tenantId   the publisher's tenant ID
     * @param mainWorkflowId the ID of the main workflow being published
     */
    void enrichPlanWithSubWorkflowData(Map<String, Object> plan, String tenantId, UUID mainWorkflowId) {
        enrichPlanWithSubWorkflowData(plan, tenantId, mainWorkflowId, null);
    }

    void enrichPlanWithSubWorkflowData(Map<String, Object> plan, String tenantId, String organizationId,
                                        UUID mainWorkflowId) {
        enrichPlanWithSubWorkflowData(plan, tenantId, organizationId, mainWorkflowId, null);
    }

    /**
     * Variant that threads {@code publicationId} so sub-plans can also copy
     * their {@code core:data_input} files under the publication's S3 namespace
     * via {@link #snapshotDataInputFiles}. Without this the sub-workflow's
     * file paths stay pointed at the publisher's tenant, which the marketplace
     * (and acquirers) cannot read.
     */
    void enrichPlanWithSubWorkflowData(Map<String, Object> plan, String tenantId, UUID mainWorkflowId,
                                        UUID publicationId) {
        enrichPlanWithSubWorkflowData(plan, tenantId, null, mainWorkflowId, publicationId);
    }

    /**
     * Defensive bound on the TOTAL number of sub-workflow snapshots created during one publish,
     * across the whole recursion. Realistic nesting is a handful; this only trips on a pathological
     * or deeply-shared (diamond-lattice) reference graph, preventing an exponential fetch/bloat
     * blow-up. Package-private (non-final) so tests can exercise the cap with a small value.
     */
    static int maxSnapshottedSubWorkflows = 1000;

    void enrichPlanWithSubWorkflowData(Map<String, Object> plan, String tenantId, String organizationId,
                                        UUID mainWorkflowId, UUID publicationId) {
        Set<String> ancestorWorkflowIds = new HashSet<>();
        String mainId = mainWorkflowId.toString();
        ancestorWorkflowIds.add(mainId);

        // Mark self-referencing sub-workflow nodes with __self__ sentinel
        markSelfRefNodes(plan, mainId);

        // Shared global budget so a pathological / cyclic reference graph can't blow up into an
        // exponential number of remote fetches + snapshot copies (see maxSnapshottedSubWorkflows).
        enrichPlanResources(plan, tenantId, organizationId, ancestorWorkflowIds, mainId, publicationId,
                new int[]{ maxSnapshottedSubWorkflows });
    }

    /**
     * Recursive enrichment: collect all workflow IDs from cores/agents/triggers,
     * fetch their plans, enrich them recursively, and store in _snapshot_subworkflows.
     */
    @SuppressWarnings("unchecked")
    private void enrichPlanResources(Map<String, Object> plan, String tenantId, String organizationId,
                                      Set<String> ancestorWorkflowIds, String mainWorkflowId,
                                      UUID publicationId, int[] remainingBudget) {
        // Collect workflow IDs from all 3 sources
        Set<String> workflowIds = new HashSet<>();
        workflowIds.addAll(collectCoreSubWorkflowIds(plan));
        workflowIds.addAll(collectAgentWorkflowIds(plan));
        workflowIds.addAll(collectTriggerWorkflowIds(plan));

        // Skip self-sentinels and any workflow already on the current ANCESTOR path (cycle break).
        // We intentionally do NOT skip a workflow merely because a SIBLING branch already
        // snapshotted it: a child shared by two parents (diamond A->B, A->C, B->D, C->D) must be
        // snapshotted under BOTH parents, else the un-snapshotted parent's node dangles at acquire.
        workflowIds.remove("__self__");
        workflowIds.removeAll(ancestorWorkflowIds);

        if (workflowIds.isEmpty()) return;

        Map<String, Object> subWorkflowSnapshots = new HashMap<>();

        for (String workflowId : workflowIds) {
            if (remainingBudget[0] <= 0) {
                logger.warn("Sub-workflow snapshot budget ({}) exhausted while publishing workflow {}; "
                        + "remaining nested sub-workflows are NOT snapshotted (likely a pathological or "
                        + "deeply-shared reference graph). Their acquired clones would be incomplete.",
                        maxSnapshottedSubWorkflows, mainWorkflowId);
                break;
            }
            try {
                UUID wfUuid = UUID.fromString(workflowId);
                Map<String, Object> wfData = orchestratorClient.getWorkflowForPublication(wfUuid, tenantId, organizationId);
                if (wfData == null || wfData.get("plan") == null) {
                    logger.warn("Sub-workflow {} not found or has no plan, skipping snapshot", workflowId);
                    continue;
                }
                if (!workflowDataInStrictScope(wfData, tenantId, organizationId)) {
                    logger.warn("Sub-workflow {} is outside caller scope, skipping snapshot", workflowId);
                    continue;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> subPlan = (Map<String, Object>) wfData.get("plan");

                // Recursively enrich the sub-workflow's plan with the SAME
                // pipeline as the top-level plan. Without injecting agent-
                // referenced resources at sub-level, agents inside this sub
                // that point at tables/interfaces outside the sub-plan would
                // be silently scoped to nothing on acquire. Without copying
                // the sub-plan's data_input files, those files would stay
                // under the publisher's tenant and break for the marketplace.
                injectAgentReferencedResources(subPlan, tenantId, organizationId);
                enrichPlanWithInterfaceData(subPlan, tenantId, organizationId, null);
                enrichPlanWithDatasourceData(subPlan, tenantId, organizationId);
                enrichPlanWithDatasourceItems(subPlan, tenantId, organizationId);
                enrichPlanWithAgentData(subPlan, tenantId, organizationId);
                // Mark back-references to the main workflow as __self__ AFTER agent enrichment, so
                // agent _snapshot_agent_toolsConfig.workflows (populated just above) and workflow/
                // error triggers pointing at the root are caught - not just core sub_workflow nodes.
                markSelfRefNodes(subPlan, mainWorkflowId);
                if (publicationId != null) {
                    snapshotDataInputFiles(subPlan, publicationId, tenantId);
                    // H3: re-namespace FileRefs EMBEDDED in this sub-workflow's interface
                    // (_snapshot_data) and agent (_snapshot_agent_{config,toolsConfig,skills})
                    // into the publication namespace. Without this, only the top-level plan's
                    // embedded FileRefs are copied (line ~1454), so a nested interface/agent file
                    // stays at the publisher path and 401s for the acquirer.
                    snapshotPlanEmbeddedFileRefs(subPlan, publicationId, tenantId);
                }
                // Recurse with a PER-BRANCH ancestor set (a copy) so sibling subtrees don't
                // suppress one another's shared children, while a cycle on THIS path is still cut.
                Set<String> childAncestors = new HashSet<>(ancestorWorkflowIds);
                childAncestors.add(workflowId);
                enrichPlanResources(subPlan, tenantId, organizationId, childAncestors, mainWorkflowId, publicationId, remainingBudget);
                stripSensitiveCredentials(subPlan);

                // Use "name" (from orchestrator response) with "title" fallback (for compatibility)
                String snapshotName = wfData.get("name") != null
                        ? wfData.get("name").toString()
                        : (String) wfData.getOrDefault("title", "Sub-workflow");

                Map<String, Object> snapshot = new HashMap<>();
                snapshot.put("plan", subPlan);
                snapshot.put("name", snapshotName);
                snapshot.put("description", wfData.getOrDefault("description", ""));

                subWorkflowSnapshots.put(workflowId, snapshot);
                remainingBudget[0]--;
                logger.info("Snapshotted sub-workflow {} for publication", workflowId);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid sub-workflow ID {}, skipping", workflowId);
            } catch (Exception e) {
                logger.error("Failed to snapshot sub-workflow {}: {}", workflowId, e.getMessage());
            }
        }

        if (!subWorkflowSnapshots.isEmpty()) {
            plan.put("_snapshot_subworkflows", subWorkflowSnapshots);
        }
    }

    private boolean workflowDataInStrictScope(Map<String, Object> workflowData,
                                              String tenantId,
                                              String organizationId) {
        return ScopeGuard.isInStrictScope(
                tenantId,
                organizationId,
                nullableString(workflowData.get("tenantId")),
                nullableString(workflowData.get("organizationId")));
    }

    private static String nullableString(Object value) {
        return value != null ? value.toString() : null;
    }

    /**
     * Collect workflow IDs from cores[].subWorkflow.workflowId where type=sub_workflow.
     */
    @SuppressWarnings("unchecked")
    private Set<String> collectCoreSubWorkflowIds(Map<String, Object> plan) {
        Set<String> ids = new HashSet<>();
        Object coresRaw = plan.get("cores");
        if (!(coresRaw instanceof List<?> cores)) return ids;
        for (Object core : cores) {
            if (!(core instanceof Map<?, ?> coreMap)) continue;
            if (!"sub_workflow".equals(coreMap.get("type"))) continue;
            Object subWf = coreMap.get("subWorkflow");
            if (!(subWf instanceof Map<?, ?> subMap)) continue;
            Object wfId = subMap.get("workflowId");
            if (wfId != null && !"__self__".equals(wfId.toString())) {
                ids.add(wfId.toString());
            }
        }
        return ids;
    }

    /**
     * Collect workflow IDs from agents[]._snapshot_agent_toolsConfig.workflows.
     * Only collects from already-enriched agent nodes (after enrichPlanWithAgentData).
     */
    @SuppressWarnings("unchecked")
    private Set<String> collectAgentWorkflowIds(Map<String, Object> plan) {
        Set<String> ids = new HashSet<>();
        Object agentsRaw = plan.get("agents");
        if (!(agentsRaw instanceof List<?> agents)) return ids;
        for (Object agent : agents) {
            if (!(agent instanceof Map<?, ?> agentMap)) continue;
            Object toolsConfigRaw = agentMap.get("_snapshot_agent_toolsConfig");
            if (!(toolsConfigRaw instanceof Map<?, ?> toolsConfig)) continue;
            Object wfListRaw = toolsConfig.get("workflows");
            if (!(wfListRaw instanceof List<?> wfList)) continue;
            for (Object wf : wfList) {
                if (wf != null && !"__self__".equals(wf.toString())) {
                    ids.add(wf.toString());
                }
            }
        }
        return ids;
    }

    /**
     * Collect workflow IDs from triggers[] where type is "workflow" or "error".
     * These triggers reference parent workflows by their ID field.
     */
    @SuppressWarnings("unchecked")
    private Set<String> collectTriggerWorkflowIds(Map<String, Object> plan) {
        Set<String> ids = new HashSet<>();
        Object triggersRaw = plan.get("triggers");
        if (!(triggersRaw instanceof List<?> triggers)) return ids;
        for (Object t : triggers) {
            if (!(t instanceof Map<?, ?> triggerMap)) continue;
            String type = triggerMap.get("type") != null ? triggerMap.get("type").toString().toLowerCase() : "";
            // "workflow" and "error" triggers reference a parent workflow via their id field
            // (trigger node ID = source workflow UUID). Both need sub-workflow snapshotting.
            if ("workflow".equals(type) || "error".equals(type)) {
                Object id = triggerMap.get("id");
                if (id != null) {
                    ids.add(id.toString());
                }
            }
        }
        return ids;
    }

    /**
     * Replace references to the MAIN workflow with the "__self__" sentinel, across all three
     * reference shapes: core {@code sub_workflow} nodes, agent
     * {@code _snapshot_agent_toolsConfig.workflows}, and {@code workflow}/{@code error} trigger ids.
     * At acquire time, SnapshotCloneService.remapSelfReferences() replaces "__self__" with the
     * actual cloned workflow ID. Without covering the agent/trigger shapes, a sub-workflow that
     * references the root via an agent's workflow access or a workflow/error trigger keeps the
     * publisher-tenant id and dangles for the acquirer.
     */
    @SuppressWarnings("unchecked")
    private void markSelfRefNodes(Map<String, Object> plan, String mainWorkflowId) {
        Object coresRaw = plan.get("cores");
        if (coresRaw instanceof List<?> cores) {
            for (Object core : cores) {
                if (!(core instanceof Map<?, ?> coreMap)) continue;
                if (!"sub_workflow".equals(coreMap.get("type"))) continue;
                Object subWf = coreMap.get("subWorkflow");
                if (!(subWf instanceof Map<?, ?> subMap)) continue;
                if (mainWorkflowId.equals(nullableString(subMap.get("workflowId")))) {
                    ((Map<String, Object>) subMap).put("workflowId", "__self__");
                }
            }
        }
        // agents[]._snapshot_agent_toolsConfig.workflows entries == main → __self__
        Object agentsRaw = plan.get("agents");
        if (agentsRaw instanceof List<?> agents) {
            for (Object agent : agents) {
                if (!(agent instanceof Map<?, ?> agentMap)) continue;
                Object tcRaw = agentMap.get("_snapshot_agent_toolsConfig");
                if (!(tcRaw instanceof Map<?, ?> tc)) continue;
                Object wfListRaw = tc.get("workflows");
                if (!(wfListRaw instanceof List<?> wfList)) continue;
                List<Object> rewritten = new ArrayList<>(wfList.size());
                boolean changed = false;
                for (Object wf : wfList) {
                    if (mainWorkflowId.equals(nullableString(wf))) { rewritten.add("__self__"); changed = true; }
                    else rewritten.add(wf);
                }
                if (changed) ((Map<String, Object>) tc).put("workflows", rewritten);
            }
        }
        // triggers[type=workflow|error].id == main → __self__
        Object triggersRaw = plan.get("triggers");
        if (triggersRaw instanceof List<?> triggers) {
            for (Object t : triggers) {
                if (!(t instanceof Map<?, ?> triggerMap)) continue;
                String type = triggerMap.get("type") != null ? triggerMap.get("type").toString().toLowerCase() : "";
                if (("workflow".equals(type) || "error".equals(type))
                        && mainWorkflowId.equals(nullableString(triggerMap.get("id")))) {
                    ((Map<String, Object>) triggerMap).put("id", "__self__");
                }
            }
        }
    }
}

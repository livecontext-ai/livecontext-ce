package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.auth.client.entitlement.ResourceType;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.config.PaidTemplatesFeatureFlag;
import com.apimarketplace.publication.domain.PublicationReceiptEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.resource.ResourcePublicationStrategy;
import com.apimarketplace.publication.service.resource.ResourcePublicationStrategy.ResourceMetadata;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Publishes and acquires standalone non-workflow, non-agent resources -
 * currently {@link PublicationType#TABLE TABLE},
 * {@link PublicationType#INTERFACE INTERFACE}, {@link PublicationType#SKILL SKILL}.
 *
 * <p>This service owns the common publication flow (ownership check,
 * entitlement guard, receipt tracking, usage counter, publication lifecycle)
 * and delegates the type-specific snapshot / clone work to a
 * {@link ResourcePublicationStrategy} keyed by {@link PublicationType}.
 *
 * <p>Workflow and agent publications keep their existing dedicated services
 * ({@link WorkflowPublicationService}, {@link AgentPublicationService}) because
 * their snapshots are recursive (workflow plans, nested agents) and diverge
 * from the simple single-resource flow implemented here.
 */
@Service
@Transactional
public class ResourcePublicationService {

    private static final Logger logger = LoggerFactory.getLogger(ResourcePublicationService.class);

    private final WorkflowPublicationRepository publicationRepository;
    private final PublicationReceiptRepository receiptRepository;
    private final OrchestratorInternalClient orchestratorClient;
    private final EntitlementGuard entitlementGuard;
    private final ObjectMapper objectMapper;
    private final LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    private final WorkflowPublicationService workflowPublicationService;
    private final Map<PublicationType, ResourcePublicationStrategy> strategies;
    private final AuthClient authClient;

    public ResourcePublicationService(WorkflowPublicationRepository publicationRepository,
                                       PublicationReceiptRepository receiptRepository,
                                       OrchestratorInternalClient orchestratorClient,
                                       EntitlementGuard entitlementGuard,
                                       ObjectMapper objectMapper,
                                       LandingInterfaceSnapshotter landingInterfaceSnapshotter,
                                       WorkflowPublicationService workflowPublicationService,
                                       List<ResourcePublicationStrategy> strategyList,
                                       AuthClient authClient) {
        this.publicationRepository = publicationRepository;
        this.receiptRepository = receiptRepository;
        this.orchestratorClient = orchestratorClient;
        this.entitlementGuard = entitlementGuard;
        this.objectMapper = objectMapper;
        this.landingInterfaceSnapshotter = landingInterfaceSnapshotter;
        this.workflowPublicationService = workflowPublicationService;
        this.authClient = authClient;

        Map<PublicationType, ResourcePublicationStrategy> map = new EnumMap<>(PublicationType.class);
        for (ResourcePublicationStrategy s : strategyList) {
            map.put(s.getPublicationType(), s);
        }
        this.strategies = map;
        logger.info("ResourcePublicationService registered {} strategies: {}", map.size(), map.keySet());
    }

    /**
     * V261 - see {@code WorkflowPublicationService#resolveAcquirerOrg}. Falls
     * back to default-personal org for daemon/async paths that don't carry
     * X-Organization-ID. Throws when the user has no default org membership.
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
    // Publish
    // ========================================================================

    /**
     * Publish a standalone resource (TABLE / INTERFACE / SKILL). Re-publishing an
     * existing active publication refreshes its snapshot and metadata.
     *
     * <p>Required request fields: {@code type} (case-insensitive PublicationType),
     * {@code resourceId} (string ID, UUID for interface/skill, numeric for table).
     * Optional: title, description, categoryId, visibility, creditsPerUse, publisherName/Email/AvatarUrl.
     */
    /** Personal-scope overload - see {@link #publishResource(Map, String, String)}. */
    public WorkflowPublicationEntity publishResource(Map<String, Object> request, String tenantId) {
        return publishResource(request, tenantId, null);
    }

    /**
     * Publish a standalone resource. Org-aware: see #151. When
     * {@code organizationId} is non-null/blank the row is org-owned; otherwise
     * personal-scoped to the publisher.
     */
    public WorkflowPublicationEntity publishResource(Map<String, Object> request, String tenantId, String organizationId) {
        PublicationType type = parseType(request.get("type"));
        String resourceId = requireString(request.get("resourceId"), "resourceId");
        ResourcePublicationStrategy strategy = requireStrategy(type);

        // Landing interface: required for TABLE/SKILL (presentation page), forbidden for INTERFACE
        // (the resource itself IS the interface). AGENT is handled in AgentPublicationService.
        UUID landingInterfaceId = landingInterfaceSnapshotter.parseInterfaceId(request.get("interfaceId"));
        if (type == PublicationType.INTERFACE) {
            if (landingInterfaceId != null) {
                throw new IllegalArgumentException(
                        "INTERFACE publications cannot have a separate landing interface - the resource is already an interface");
            }
        } else if (landingInterfaceId == null) {
            throw new IllegalArgumentException("interfaceId is required to publish a " + type + " (landing page)");
        }

        ResourceMetadata metadata = strategy.fetchOwnedResource(resourceId, tenantId);

        WorkflowPublicationEntity publication = publicationRepository
                .findByPublicationTypeAndResourceId(type, resourceId)
                .orElseGet(() -> {
                    WorkflowPublicationEntity p = new WorkflowPublicationEntity();
                    p.setPublisherId(tenantId);
                    p.setPublicationType(type);
                    p.setResourceId(resourceId);
                    p.setDisplayMode(strategy.getDisplayMode());
                    return p;
                });

        if (!workflowPublicationService.isCallerInOwnerScope(publication, tenantId, organizationId)) {
            throw new IllegalArgumentException("Not the publisher of this resource");
        }

        // Assign owning scope on first publish; re-publish keeps the original scope.
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
        if (publication.getStatus() == PublicationStatus.PENDING_REVIEW) {
            throw new PublicationPendingReviewException(
                    "Cannot re-publish while publication is pending review. Please wait for admin approval.");
        }

        String title = stringOr(request.get("title"), metadata.name());
        String description = stringOr(request.get("description"), metadata.description());
        publication.setTitle(title != null ? title : resourceId);
        publication.setDescription(description);

        applyCategory(publication, request.get("categoryId"));
        applyPublisherInfo(publication, tenantId);
        applyVisibilityAndStatus(publication, request.get("visibility"));

        Object creditsRaw = request.get("creditsPerUse");
        int creditsPerUse = creditsRaw instanceof Number n ? n.intValue() : 0;
        // Defense-in-depth: paid templates are disabled platform-wide until the
        // billing pipeline ships. Frontend modals grey the price input, but a
        // direct curl POST must not slip through. Existing paid pubs are
        // grandfathered (this gate runs only on new publishes).
        if (creditsPerUse > 0 && !PaidTemplatesFeatureFlag.isEnabled()) {
            throw new IllegalArgumentException(
                    "Paid templates are coming soon. All new publications must be free "
                    + "(creditsPerUse=0) until the feature ships.");
        }
        publication.setCreditsPerUse(creditsPerUse);

        Map<String, Object> snapshot = strategy.buildSnapshot(resourceId, tenantId);
        if (landingInterfaceId != null) {
            snapshot.put("landingInterface", landingInterfaceSnapshotter.buildSnapshot(
                    landingInterfaceId, tenantId, organizationId));
            publication.setShowcaseInterfaceId(landingInterfaceId);
        }
        publication.setPlanSnapshot(snapshot);

        // Build denormalized search index. title/description are local vars
        // resolved above (lines 130-131); type is the method parameter.
        publication.setSearchText(SearchTextBuilder.create()
                .add(title).add(description)
                .add(publication.getCategoryName()).add(publication.getCategorySlug())
                .add(publication.getPublisherName())
                .fromResourceSnapshot(snapshot)
                .build(publication.getId(), type.name()));

        WorkflowPublicationEntity saved = publicationRepository.save(publication);
        logger.info("Published {} resource {} as publication {} for tenant {}",
                type, resourceId, saved.getId(), tenantId);
        return saved;
    }

    // ========================================================================
    // Acquire
    // ========================================================================

    /**
     * Acquire a resource publication - clone the snapshot into the acquirer's
     * workspace, writing a receipt so the acquirer can re-acquire for free.
     *
     * @return map with {@code resourceId} (new id in acquirer's tenant) and {@code type}
     */
    /** Personal-scope overload - see {@link #acquireResource(UUID, String, String)}. */
    public Map<String, Object> acquireResource(UUID publicationId, String tenantId) {
        return acquireResource(publicationId, tenantId, null);
    }

    public Map<String, Object> acquireResource(UUID publicationId, String tenantId, String organizationId) {
        WorkflowPublicationEntity publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalArgumentException("Publication not found: " + publicationId));

        PublicationType type = publication.getPublicationType();
        requireStrategy(type); // fail fast if the type has no resource strategy

        // V261: receipt.organization_id is NOT NULL - resolve fallback from
        // user's default-personal org when request omitted X-Organization-ID.
        // Reassign-then-rebind to a final to keep lambda captures happy below.
        organizationId = resolveAcquirerOrg(tenantId, organizationId, publicationId);
        final String orgScope = organizationId;

        if (isOwnPublication(publication, tenantId, orgScope)) {
            throw new IllegalArgumentException("Cannot acquire your own publication");
        }

        boolean hasReceipt = hasReceiptInScope(tenantId, publicationId, organizationId);

        // Publication must be ACTIVE for first-time acquisitions, but holders of an
        // existing receipt can always re-acquire - the snapshot is preserved even when
        // the publisher unpublishes (status = INACTIVE). REJECTED publications stay off.
        if (!hasReceipt && publication.getVisibility() == PublicationVisibility.PRIVATE) {
            throw new IllegalArgumentException("Publication is private");
        }
        if (!hasReceipt && publication.getStatus() != PublicationStatus.ACTIVE) {
            throw new IllegalArgumentException("Publication is not active");
        }
        if (hasReceipt && publication.getStatus() == PublicationStatus.REJECTED) {
            throw new IllegalArgumentException("Publication is not available");
        }

        // Entitlement only for first-time acquisitions (re-acquire is free).
        // Resource entitlements: TABLE uses DATA, INTERFACE uses INTERFACE, SKILL uses SKILL when available.
        if (!hasReceipt && entitlementGuard != null) {
            ResourceType resType = entitlementResourceType(type);
            if (resType != null) {
                entitlementGuard.check(tenantId, resType, () -> countReceiptsInScope(tenantId, orgScope));
            }
        }

        Map<String, Object> snapshot = publication.getPlanSnapshot();
        if (snapshot == null) {
            throw new IllegalStateException("Publication has no snapshot: " + publicationId);
        }

        String newResourceId = cloneResourceFromSnapshot(type, snapshot, tenantId, publicationId, organizationId);

        if (!hasReceipt) {
            int creditsPaid = publication.getCreditsPerUse() != null ? publication.getCreditsPerUse() : 0;
            receiptRepository.save(new PublicationReceiptEntity(
                    tenantId, publicationId, creditsPaid, normalizeScope(organizationId)));
        }
        publicationRepository.incrementUsage(publicationId);

        logger.info("Acquired {} publication {} -> resource {} for tenant {}",
                type, publicationId, newResourceId, tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type.name());
        result.put("resourceId", newResourceId);
        result.put("publicationId", publicationId.toString());
        return result;
    }

    /**
     * CE-cloud remote acquire of a standalone resource (TABLE / INTERFACE /
     * SKILL) publication. The publication lives on the cloud (its id is absent
     * from the local DB), so {@link RemoteMarketplaceService} has already
     * fetched its planSnapshot from the cloud (charging the linked cloud account
     * for paid publications, surfacing INSUFFICIENT_CREDITS on a cloud 402) and
     * verified there is no existing local receipt. We clone the resource from
     * that snapshot under the acquirer's tenant and write a remote-acquisition
     * receipt. {@code creditsPaid} is what the cloud actually charged.
     */
    public Map<String, Object> acquireResourceFromCloudSnapshot(PublicationType type, Map<String, Object> snapshot,
                                                                String tenantId, UUID publicationId,
                                                                String organizationId, int creditsPaid) {
        if (snapshot == null || snapshot.isEmpty()) {
            throw new IllegalStateException("Cloud returned an empty snapshot for resource publication " + publicationId);
        }
        String newResourceId = cloneResourceFromSnapshot(type, snapshot, tenantId, publicationId, organizationId);

        PublicationReceiptEntity receipt = new PublicationReceiptEntity(
                tenantId, publicationId, creditsPaid, normalizeScope(organizationId));
        receipt.setRemoteAcquisition(true);
        receiptRepository.save(receipt);

        logger.info("Acquired remote {} publication {} -> resource {} for tenant {}",
                type, publicationId, newResourceId, tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type.name());
        result.put("resourceId", newResourceId);
        result.put("publicationId", publicationId.toString());
        return result;
    }

    /**
     * Shared snapshot clone for a resource publication: resolves the
     * type-specific {@link ResourcePublicationStrategy} and clones a DEEP COPY
     * of the snapshot under {@code tenantId}. Shared by the local
     * {@link #acquireResource} and the CE-cloud {@link #acquireResourceFromCloudSnapshot}.
     */
    private String cloneResourceFromSnapshot(PublicationType type, Map<String, Object> snapshot,
                                             String tenantId, UUID publicationId, String organizationId) {
        ResourcePublicationStrategy strategy = requireStrategy(type);
        // Deep copy so strategy mutations do not touch the stored publication payload.
        Map<String, Object> snapshotCopy = objectMapper.convertValue(snapshot,
                new TypeReference<Map<String, Object>>() {});
        return strategy.cloneFromSnapshot(
                snapshotCopy, tenantId, publicationId, normalizeScope(organizationId));
    }

    // ========================================================================
    // Unpublish + queries
    // ========================================================================

    /** Personal-scope overload - see {@link #unpublishResource(PublicationType, String, String, String)}. */
    public void unpublishResource(PublicationType type, String resourceId, String tenantId) {
        unpublishResource(type, resourceId, tenantId, null);
    }

    /**
     * Unpublish a standalone resource. Org-aware: see #151.
     */
    public void unpublishResource(PublicationType type, String resourceId, String tenantId, String organizationId) {
        requireStrategy(type);
        WorkflowPublicationEntity publication = publicationRepository
                .findByPublicationTypeAndResourceId(type, resourceId)
                .orElseThrow(() -> new IllegalArgumentException("Publication not found for " + type + ":" + resourceId));

        if (!workflowPublicationService.isCallerInOwnerScope(publication, tenantId, organizationId)) {
            throw new IllegalArgumentException("Not the publisher");
        }
        if (publication.getStatus() == PublicationStatus.PENDING_REVIEW) {
            throw new PublicationPendingReviewException(
                    "Cannot unpublish while publication is pending review. Please wait for admin approval.");
        }

        publication.setStatus(PublicationStatus.INACTIVE);
        publicationRepository.save(publication);
        logger.info("Unpublished {} resource {} for tenant {}", type, resourceId, tenantId);
    }

    @Transactional(readOnly = true)
    public boolean isResourcePublished(PublicationType type, String resourceId) {
        return publicationRepository
                .findByPublicationTypeAndResourceId(type, resourceId)
                .map(p -> p.getStatus() == PublicationStatus.ACTIVE)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getResourcePublicationStatus(PublicationType type, String resourceId) {
        Optional<WorkflowPublicationEntity> pub = publicationRepository
                .findByPublicationTypeAndResourceId(type, resourceId);
        if (pub.isEmpty()) {
            return Map.of("exists", false);
        }
        WorkflowPublicationEntity p = pub.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exists", true);
        out.put("status", p.getStatus().name());
        out.put("published", p.getStatus() == PublicationStatus.ACTIVE);
        out.put("publicationId", p.getId().toString());
        if (p.getStatus() == PublicationStatus.REJECTED && p.getRejectionReason() != null) {
            out.put("rejectionReason", p.getRejectionReason());
        }
        return out;
    }

    /**
     * Batch variant of {@link #getResourcePublicationStatus} for a whole page of resource ids of one
     * {@code type}. Replaces the resource-list per-row status sweep (one
     * {@code is-resource-published/{type}/{id}} call per card) with a single query, mirroring the
     * workflow board's batched enrichment. Each present resourceId maps to {@code {status,
     * rejectionReason?}}; ids with no shared publication (none, or INACTIVE only) are simply absent
     * from the result, which the caller reads as "not shared / private" - identical UI outcome to the
     * per-id call returning {@code published=false}. {@code published} is derivable as
     * {@code status == "ACTIVE"}, so it is not duplicated into the map.
     */
    @Transactional(readOnly = true)
    public Map<String, Map<String, String>> getResourcePublicationStatuses(
            PublicationType type, Collection<String> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return Map.of();
        }
        // AGENT publications are keyed by agentConfigId (a UUID column), NOT the string resource_id
        // (which is null for them), so they need a dedicated query keyed on that column; every other
        // resource type (TABLE / INTERFACE / SKILL) matches on resource_id.
        List<Object[]> rows;
        if (type == PublicationType.AGENT) {
            List<UUID> agentConfigIds = new java.util.ArrayList<>();
            for (String id : resourceIds) {
                try { agentConfigIds.add(UUID.fromString(id)); } catch (IllegalArgumentException ignored) { }
            }
            if (agentConfigIds.isEmpty()) {
                return Map.of();
            }
            rows = publicationRepository.findAgentPublicationStatusesByConfigIds(agentConfigIds);
        } else {
            rows = publicationRepository.findResourcePublicationStatusesByTypeAndResourceIds(type, resourceIds);
        }
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row.length >= 2 && row[0] != null && row[1] != null) {
                Map<String, String> info = new LinkedHashMap<>();
                info.put("status", row[1].toString());
                // rejectionReason is non-null only for REJECTED rows; the card shows it on hover.
                if (row.length >= 3 && row[2] != null) {
                    info.put("rejectionReason", row[2].toString());
                }
                out.put(row[0].toString(), info);
            }
        }
        return out;
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private ResourcePublicationStrategy requireStrategy(PublicationType type) {
        ResourcePublicationStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException(
                    "No resource publication strategy for type: " + type
                    + " (available: " + strategies.keySet() + ")");
        }
        return strategy;
    }

    private PublicationType parseType(Object raw) {
        String s = requireString(raw, "type");
        try {
            return PublicationType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid publication type: " + s);
        }
    }

    private String requireString(Object raw, String field) {
        if (raw == null) throw new IllegalArgumentException(field + " is required");
        String s = raw.toString();
        if (s.isBlank()) throw new IllegalArgumentException(field + " is required");
        return s;
    }

    private String stringOr(Object raw, String fallback) {
        if (raw == null) return fallback;
        String s = raw.toString();
        return s.isBlank() ? fallback : s;
    }

    private static String normalizeScope(String organizationId) {
        return organizationId != null && !organizationId.isBlank() ? organizationId : null;
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

    private void applyCategory(WorkflowPublicationEntity publication, Object categoryIdRaw) {
        if (categoryIdRaw == null) return;
        String categoryIdStr = categoryIdRaw.toString();
        if (categoryIdStr.isBlank()) return;
        try {
            UUID categoryId = UUID.fromString(categoryIdStr);
            Map<String, Object> category = orchestratorClient.getCategoryById(categoryId);
            if (category != null) {
                publication.setCategoryId(categoryId);
                publication.setCategorySlug((String) category.get("slug"));
                publication.setCategoryName((String) category.get("name"));
                publication.setCategoryIconSlug((String) category.get("iconSlug"));
                publication.setCategoryColor((String) category.get("color"));
            }
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid categoryId {}, skipping", categoryIdStr);
        }
    }

    private void applyPublisherInfo(WorkflowPublicationEntity publication, String tenantId) {
        // Frontend body publisher fields are ignored - identity is resolved
        // server-side via AuthClient at every (re)publish. See
        // PublisherProfileSnapshotter for the uniform rule across paths.
        PublisherProfileSnapshotter.snapshotInto(publication, authClient, tenantId);
    }

    private void applyVisibilityAndStatus(WorkflowPublicationEntity publication, Object visRaw) {
        PublicationVisibility visibility = PublicationVisibility.PUBLIC;
        if (visRaw != null) {
            try {
                visibility = PublicationVisibility.valueOf(visRaw.toString().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid visibility: " + visRaw);
            }
        }
        publication.setVisibility(visibility);
        if (visibility == PublicationVisibility.PRIVATE) {
            publication.setStatus(PublicationStatus.ACTIVE);
        } else {
            publication.setStatus(PublicationStatus.PENDING_REVIEW);
            publication.setReviewerId(null);
            publication.setReviewedAt(null);
            publication.setRejectionReason(null);
        }
    }

    /**
     * Map a PublicationType to the ResourceType the entitlement guard should enforce.
     * Returns null when no per-resource quota applies to this type (SKILL today).
     */
    private ResourceType entitlementResourceType(PublicationType type) {
        return switch (type) {
            case TABLE -> ResourceType.DATASOURCE;
            case INTERFACE -> ResourceType.INTERFACE;
            default -> null; // SKILL has no per-plan cap
        };
    }
}

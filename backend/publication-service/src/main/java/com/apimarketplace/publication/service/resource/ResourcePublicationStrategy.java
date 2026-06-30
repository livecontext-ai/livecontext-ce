package com.apimarketplace.publication.service.resource;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;

import java.util.Map;
import java.util.UUID;

/**
 * Strategy for publishing and acquiring a standalone resource type
 * (TABLE / INTERFACE / SKILL) through the unified publication pipeline.
 *
 * <p>Each implementation handles the type-specific snapshot/clone logic while
 * {@link ResourcePublicationService} owns the common flow: ownership
 * validation, entitlement check, receipt creation, usage counter.
 *
 * <p>Keeping this layer thin (no validation, no HTTP, no persistence) is
 * deliberate - strategies only know how to serialize one resource type and
 * how to rebuild it for an acquiring tenant.
 */
public interface ResourcePublicationStrategy {

    /** The publication type this strategy handles. Used as the registry key. */
    PublicationType getPublicationType();

    /** The display mode written to the publication row (drives marketplace UI). */
    DisplayMode getDisplayMode();

    /**
     * Load the tenant-owned resource and return its public metadata.
     * Throws {@link IllegalArgumentException} if the resource does not exist or
     * does not belong to the tenant.
     *
     * @param resourceId raw resource id as a string (UUID for interface/skill, numeric for table)
     * @param tenantId   the publishing tenant
     * @return resolved name + description, used to default the publication title/description
     */
    ResourceMetadata fetchOwnedResource(String resourceId, String tenantId);

    /**
     * Build the snapshot payload stored on the publication (as planSnapshot JSONB).
     * Snapshots must be self-contained - everything the acquirer needs to rebuild
     * the resource without access to the publisher's tenant data.
     *
     * @param resourceId the resource being published
     * @param tenantId   the publishing tenant (used to read owned data)
     * @return an opaque map that this same strategy will later interpret in
     *         {@link #cloneFromSnapshot}. Must be JSON-serializable.
     */
    Map<String, Object> buildSnapshot(String resourceId, String tenantId);

    /**
     * Build the snapshot payload for a resource in an explicit workspace scope.
     *
     * <p>The default keeps the personal-scope behaviour ({@link #buildSnapshot(String, String)}),
     * which resolves the request organization from the ambient {@code TenantResolver}. Strategies
     * whose backing resource is organization-scoped override this so a caller that is NOT in the
     * owning workspace - most notably the admin publication-review comparison, which rebuilds the
     * live source on behalf of another tenant - can pass the publication's owning {@code organizationId}
     * explicitly instead of leaking the caller's ambient org. Pass {@code null}/blank for personal scope.
     */
    default Map<String, Object> buildSnapshot(String resourceId, String tenantId, String organizationId) {
        return buildSnapshot(resourceId, tenantId);
    }

    /**
     * Recreate the resource in the acquiring tenant's workspace from a snapshot.
     *
     * <p>The passed {@code snapshot} is already a deep copy owned by the caller -
     * strategies may mutate it freely without risk of touching the stored
     * publication payload.
     *
     * @param snapshot      the map previously produced by {@link #buildSnapshot}
     *                      (safe to mutate; caller has deep-copied it)
     * @param tenantId      the acquiring tenant
     * @param publicationId used for traceability (written as {@code sourcePublicationId})
     * @return the newly-created resource id (string form)
     */
    String cloneFromSnapshot(Map<String, Object> snapshot, String tenantId, UUID publicationId);

    /**
     * Recreate the resource in the acquiring tenant's active organization workspace.
     *
     * <p>Strategies that persist organization-scoped resources override this method.
     * The default keeps existing personal-scope behaviour for strategies that do not
     * yet have an organization-aware clone path.
     */
    default String cloneFromSnapshot(Map<String, Object> snapshot,
                                     String tenantId,
                                     UUID publicationId,
                                     String organizationId) {
        return cloneFromSnapshot(snapshot, tenantId, publicationId);
    }

    /**
     * Immutable carrier for the publisher-visible metadata of a resource.
     * Returned by {@link #fetchOwnedResource} so the service layer can default
     * title/description when the publish request does not provide them.
     */
    record ResourceMetadata(String name, String description) {}
}

package com.apimarketplace.publication.repository;

import com.apimarketplace.publication.domain.PublicationReceiptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Receipt lookup repository.
 *
 * <p>Post-V261, every persisted receipt has a non-null {@code organization_id}
 * (the gateway always injects {@code X-Organization-ID}; personal-workspace
 * users get their personal org UUID from {@code auth.organization_member.is_default=true}).
 * Therefore tenant-only / {@code organization_id IS NULL} lookups are dead
 * paths and have been removed. Callers MUST pass a non-null organizationId.
 */
@Repository
public interface PublicationReceiptRepository extends JpaRepository<PublicationReceiptEntity, UUID> {

    boolean existsByOrganizationIdAndPublicationId(String organizationId, UUID publicationId);

    /**
     * Has ANY user/org ever acquired this publication? Drives snapshot-version
     * retention: an acquired publication keeps its full version history, a
     * never-acquired one keeps none.
     */
    boolean existsByPublicationId(UUID publicationId);

    List<PublicationReceiptEntity> findByOrganizationId(String organizationId);

    long countByOrganizationId(String organizationId);

    // The legacy {@code existsByTenantIdAndPublicationId(String, UUID)} orphan
    // (introduced as a CE-cloud carve-out for CeDownloadController) was removed
    // post-V261 - CeDownloadController.acquireWithAuth now routes through
    // {@link #existsByOrganizationIdAndPublicationId} after resolving the
    // cloud-side personal org from the CE customer identity. No src/main
    // callers remained at deletion time (verified 2026-05-20).
}

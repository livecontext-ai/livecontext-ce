package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.auth.client.entitlement.ResourceType;
import com.apimarketplace.publication.domain.PublicationReceiptEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Shared helper for application-style publication acquisition flows: ownership
 * validation, active-state checks, entitlement guard (free-plan quota), receipt
 * creation, and usage counter increment.
 *
 * <p>Used by {@link WorkflowPublicationService} and {@link AgentPublicationService}
 * so that validation rules stay identical for APPLICATION-backed acquisitions.
 * Standalone resource acquisitions keep their own resource-type entitlement mapping.
 */
@Component
public class PublicationAcquisitionHelper {

    private static final Logger logger = LoggerFactory.getLogger(PublicationAcquisitionHelper.class);

    private final WorkflowPublicationRepository publicationRepository;
    private final PublicationReceiptRepository receiptRepository;
    private final EntitlementGuard entitlementGuard;

    public PublicationAcquisitionHelper(WorkflowPublicationRepository publicationRepository,
                                         PublicationReceiptRepository receiptRepository,
                                         @Autowired(required = false) EntitlementGuard entitlementGuard) {
        this.publicationRepository = publicationRepository;
        this.receiptRepository = receiptRepository;
        this.entitlementGuard = entitlementGuard;
    }

    /**
     * Validates that the acquiring tenant is not the publisher.
     * Re-acquisition of one's own publication is always illegal.
     */
    public void validateNotOwnPublication(WorkflowPublicationEntity publication, String tenantId) {
        validateNotOwnPublication(publication, tenantId, null);
    }

    public void validateNotOwnPublication(WorkflowPublicationEntity publication,
                                          String tenantId,
                                          String organizationId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required to acquire a publication");
        }
        if (isOwnPublication(publication, tenantId, organizationId)) {
            throw new IllegalArgumentException("Cannot acquire your own publication");
        }
    }

    /**
     * Validates that the publication is acquirable by an external tenant.
     * Active + non-private are required for new acquisitions; re-acquisitions
     * (receipt holders) can still retrieve an INACTIVE or PRIVATE publication
     * as long as its snapshot is preserved. REJECTED publications stay off.
     */
    public void validateAcquirable(WorkflowPublicationEntity publication, boolean alreadyPaid) {
        if (!alreadyPaid && publication.getVisibility() == PublicationVisibility.PRIVATE) {
            throw new IllegalArgumentException("Publication is private");
        }
        if (!alreadyPaid && publication.getStatus() != PublicationStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "Publication is not active (status=" + publication.getStatus() + ")");
        }
        if (alreadyPaid && publication.getStatus() == PublicationStatus.REJECTED) {
            throw new IllegalArgumentException("Publication is not available");
        }
    }

    /**
     * Returns true if the tenant has previously acquired this publication (receipt exists).
     */
    public boolean hasReceipt(String tenantId, UUID publicationId) {
        return hasReceipt(tenantId, publicationId, null);
    }

    /**
     * Returns true if the exact workspace has previously acquired this publication.
     *
     * <p>Post-V261: organizationId is always present (gateway injects X-Organization-ID;
     * personal-workspace users carry their personal org UUID). The previous IS-NULL
     * tenant-only fallback is dead and removed.
     */
    public boolean hasReceipt(String tenantId, UUID publicationId, String organizationId) {
        if (!hasText(organizationId)) {
            throw new IllegalArgumentException(
                    "organizationId required after V261 (tenantId=" + tenantId
                            + ", publicationId=" + publicationId + ")");
        }
        return receiptRepository.existsByOrganizationIdAndPublicationId(organizationId, publicationId);
    }

    /**
     * Enforces the APPLICATION-quota entitlement for first-time acquisitions only.
     * Receipt holders bypass this check so they can always re-acquire after deletion.
     *
     * @throws RuntimeException if the tenant's plan does not allow another APPLICATION resource
     */
    public void enforceEntitlementIfFirstTime(String tenantId, boolean alreadyPaid) {
        enforceEntitlementIfFirstTime(tenantId, null, alreadyPaid);
    }

    public void enforceEntitlementIfFirstTime(String tenantId, String organizationId, boolean alreadyPaid) {
        if (alreadyPaid || entitlementGuard == null) {
            return;
        }
        entitlementGuard.check(tenantId, ResourceType.APPLICATION,
                () -> countReceiptsInScope(tenantId, organizationId));
    }

    /**
     * Creates a receipt (if absent) and increments the usage counter atomically.
     * Must be called after the clone/creation of the downstream resource succeeded,
     * so that failed acquisitions don't pollute the counter.
     */
    public void recordAcquisition(WorkflowPublicationEntity publication, String tenantId, boolean alreadyPaid) {
        recordAcquisition(publication, tenantId, null, alreadyPaid);
    }

    public void recordAcquisition(WorkflowPublicationEntity publication,
                                  String tenantId,
                                  String organizationId,
                                  boolean alreadyPaid) {
        if (alreadyPaid) {
            logger.debug("Tenant {} re-acquired publication {} - no new receipt", tenantId, publication.getId());
            return;
        }
        // Post-V261 invariant: receipt.organization_id is NOT NULL. Callers
        // upstream (acquire*Publication services) already resolve a non-blank
        // org via authClient.getDefaultOrganizationIdForUser fallback before
        // reaching here; we fail-closed if that contract is broken so the bug
        // surfaces with a clear message instead of as DataIntegrityViolation.
        if (!hasText(organizationId)) {
            throw new IllegalArgumentException(
                    "organizationId required after V261 (tenantId=" + tenantId
                            + ", publicationId=" + publication.getId() + ")");
        }
        int credits = publication.getCreditsPerUse() != null ? publication.getCreditsPerUse() : 0;
        PublicationReceiptEntity receipt = new PublicationReceiptEntity(
                tenantId, publication.getId(), credits, organizationId);
        receiptRepository.save(receipt);
        publicationRepository.incrementUsage(publication.getId());
        logger.info("Tenant {} acquired publication {} - receipt saved, usage incremented", tenantId, publication.getId());
    }

    /**
     * Full pre-clone validation pipeline: ownership, acquirability, entitlement.
     * Returns {@code alreadyPaid} so the caller can pass it to
     * {@link #recordAcquisition(WorkflowPublicationEntity, String, boolean)}.
     */
    public boolean validateAndCheckEntitlement(WorkflowPublicationEntity publication, String tenantId) {
        return validateAndCheckEntitlement(publication, tenantId, null);
    }

    public boolean validateAndCheckEntitlement(WorkflowPublicationEntity publication,
                                               String tenantId,
                                               String organizationId) {
        validateNotOwnPublication(publication, tenantId, organizationId);
        boolean alreadyPaid = hasReceipt(tenantId, publication.getId(), organizationId);
        validateAcquirable(publication, alreadyPaid);
        enforceEntitlementIfFirstTime(tenantId, organizationId, alreadyPaid);
        return alreadyPaid;
    }

    private static String normalizeScope(String organizationId) {
        return organizationId != null && !organizationId.isBlank() ? organizationId : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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

    private long countReceiptsInScope(String tenantId, String organizationId) {
        // Post-V261 invariant: organizationId is always present. See hasReceipt above.
        if (!hasText(organizationId)) {
            throw new IllegalArgumentException(
                    "organizationId required after V261 (tenantId=" + tenantId + ")");
        }
        return receiptRepository.countByOrganizationId(organizationId);
    }
}

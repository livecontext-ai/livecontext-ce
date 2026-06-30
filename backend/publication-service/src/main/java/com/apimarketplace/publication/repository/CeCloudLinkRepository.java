package com.apimarketplace.publication.repository;

import com.apimarketplace.publication.domain.CeCloudLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CeCloudLinkRepository extends JpaRepository<CeCloudLinkEntity, UUID> {

    Optional<CeCloudLinkEntity> findByTenantId(Long tenantId);

    void deleteByTenantId(Long tenantId);

    boolean existsByLlmSource(String llmSource);

    /**
     * THE active cloud link of this CE install, install-globally - the most
     * recently linked registered link (registeredAt != null), regardless of
     * llmSource. Used by the model-catalog bundle sync, which runs once per
     * install (not per tenant): being cloud-linked is what entitles the install
     * to catalog updates, independent of the per-tenant CLOUD/BYOK inference
     * choice. Empty when this install has no registered link.
     */
    Optional<CeCloudLinkEntity> findFirstByRegisteredAtNotNullOrderByLinkedAtDesc();
}

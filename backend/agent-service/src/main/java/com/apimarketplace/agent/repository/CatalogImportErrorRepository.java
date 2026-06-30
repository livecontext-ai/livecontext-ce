package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.CatalogImportErrorEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CatalogImportErrorRepository extends JpaRepository<CatalogImportErrorEntity, Long> {

    List<CatalogImportErrorEntity> findByBundleIdOrderByOccurredAtDesc(Long bundleId);

    List<CatalogImportErrorEntity> findByProviderAndModelIdOrderByOccurredAtDesc(String provider, String modelId);
}

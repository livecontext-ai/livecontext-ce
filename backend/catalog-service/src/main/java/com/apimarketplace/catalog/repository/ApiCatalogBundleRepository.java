package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ApiCatalogBundleRepository extends JpaRepository<ApiCatalogBundleEntity, Long> {

    Optional<ApiCatalogBundleEntity> findByVersion(Long version);

    Optional<ApiCatalogBundleEntity> findFirstByActiveTrue();

    Optional<ApiCatalogBundleEntity> findTopByOrderByVersionDesc();

    /**
     * Atomically clear the active flag on every row. Callers run this inside
     * the same TX as a subsequent {@code save(newActive)} so the partial
     * unique index {@code idx_api_catalog_bundles_one_active} is never violated.
     */
    @Modifying
    @Query("UPDATE ApiCatalogBundleEntity b SET b.active = false WHERE b.active = true")
    int deactivateAll();
}

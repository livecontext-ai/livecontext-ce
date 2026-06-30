package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.CatalogBundleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CatalogBundleRepository extends JpaRepository<CatalogBundleEntity, Long> {

    Optional<CatalogBundleEntity> findByVersion(Long version);

    Optional<CatalogBundleEntity> findFirstByActiveTrue();

    Optional<CatalogBundleEntity> findTopByOrderByVersionDesc();

    /**
     * Atomically clear the active flag on every row. Callers run this inside
     * the same TX as a subsequent {@code save(newActive)} so the partial
     * unique index {@code idx_catalog_bundles_one_active} is never violated.
     */
    @Modifying
    @Query("UPDATE CatalogBundleEntity b SET b.active = false WHERE b.active = true")
    int deactivateAll();
}

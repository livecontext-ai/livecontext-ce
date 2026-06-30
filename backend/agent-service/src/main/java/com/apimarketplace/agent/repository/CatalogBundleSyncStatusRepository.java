package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.CatalogBundleSyncStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogBundleSyncStatusRepository
        extends JpaRepository<CatalogBundleSyncStatusEntity, Short> {
}
